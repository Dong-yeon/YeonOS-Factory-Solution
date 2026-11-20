package io.smarthappy.module.qms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * 8D ↔ 부적합(NCR) 연결 (docs/29_P3 17장) — 8D 저장에 NCR 번호(대소문자 무시), 빠뜨리면 그대로·빈 값이면 끊기, 바꾸기,
 * NCR 상세의 이어진 8D 목록 + 이상 사용(없는·B사 번호 = 칸 오류, 취소된 NCR 새 연결 거부·이미 이어진 것은 유지, 종결·취소 8D 변경 거부,
 * 직접 SQL B사 NCR 연결 거부, B사 사용자는 A사 NCR 상세를 못 봄).
 */
class CapaNcrLinkTest extends QmsTestBase {

    static final String A = "/api/qms/capas";
    static final String N = "/api/qms/ncrs";

    long defect;

    @BeforeEach
    void linkSetUp() {
        accounts.grant(comp, userId, "QA", "qms-capa", "SEARCH", "SAVE", "APPROVE");
        accounts.grant(comp, userId, "QA", "qms-ncr", "SEARCH", "SAVE", "DELETE");
        defect = accounts.query(comp, """
                insert into qms_defect (comp_cd, defect_cd, defect_nm, cat_cd, created_by, updated_by)
                values (?, 'D-LNK', '버', 'APPEARANCE', 't', 't') returning defect_id""", Long.class, comp);
    }

    long lot(String c, String no) {
        return accounts.query(c, """
                insert into lot_master (comp_cd, lot_no, item_id, origin, qty_created, unit_cd, occurred_at, created_by, updated_by)
                select ?, ?, item_id, 'RECEIVED', 100, 'EA', now(), 't', 't' from mst_item where item_cd = 'FG1' returning lot_id""",
                Long.class, c, no);
    }

    /** 고객 클레임 NCR → 번호 */
    String ncr(String lotNo) throws Exception {
        lot(comp, lotNo);
        long id = id(postJson(N, "{\"lotNo\":" + q(lotNo) + ",\"source\":\"CLAIM\",\"defectId\":" + defect
                + ",\"ncQty\":1,\"description\":\"버 발생\"}"));
        return accounts.query(comp, "select ncr_no from qms_ncr where ncr_id = ?", String.class, id);
    }

    long ncrId(String no) {
        return accounts.query(comp, "select ncr_id from qms_ncr where ncr_no = ?", Long.class, no);
    }

    ResultActions create(String ncrNo) throws Exception {
        return postJson(A, "{\"source\":\"INTERNAL\",\"title\":\"버 재발\",\"ncrNo\":" + q(ncrNo) + "}");
    }

    int ver(long id) {
        return accounts.query(comp, "select row_ver from qms_capa where capa_id = ?", Integer.class, id);
    }

    /** ncrNoJson = null 이면 ncrNo 칸을 보내지 않는다 */
    ResultActions save(long id, String ncrNoJson) throws Exception {
        return putJson(A + "/" + id, "{\"title\":\"버 재발\"" + (ncrNoJson == null ? "" : ",\"ncrNo\":" + ncrNoJson) + ",\"rowVer\":" + ver(id) + "}");
    }

    String linked(long capa) {
        return accounts.query(comp, "select coalesce(n.ncr_no, '-') from qms_capa c left join qms_ncr n on n.ncr_id = c.ncr_id where c.capa_id = ?",
                String.class, capa);
    }

    @Test
    void 연결_빠뜨리면_그대로_빈값은_끊기_바꾸기_NCR상세에_이어진_8D() throws Exception {
        String a = ncr("L-A");
        String b = ncr("L-B");
        long c1 = id(create(a.toLowerCase()));   // 대소문자 무시
        assertThat(linked(c1)).isEqualTo(a);
        mvc.perform(get(A + "/" + c1).with(admin)).andExpect(jsonPath("$.data.capa.ncrNo").value(a))
                .andExpect(jsonPath("$.data.capa.ncrStatus").value("OPEN"))
                .andExpect(jsonPath("$.data.capa.ncrLotNo").value("L-A"));       // 출하 추적 바로가기용(35장) — 상세에만
        mvc.perform(get(A).with(admin)).andExpect(jsonPath("$.data[0].ncrLotNo").doesNotExist());
        // ncrNo 를 보내지 않은 저장 = 그대로(몰래 끊지 않는다)
        save(c1, null).andExpect(status().isOk());
        assertThat(linked(c1)).isEqualTo(a);
        save(c1, q(" " + b + " ")).andExpect(status().isOk());
        assertThat(linked(c1)).isEqualTo(b);
        save(c1, "\"\"").andExpect(status().isOk());
        assertThat(linked(c1)).isEqualTo("-");
        mvc.perform(get(A + "/" + c1).with(admin)).andExpect(jsonPath("$.data.capa.ncrNo").doesNotExist())
                .andExpect(jsonPath("$.data.capa.ncrLotNo").doesNotExist());
        // 한 NCR 에 8D 여럿 — NCR 상세에 번호 순으로(번호·단계만)
        save(c1, q(a)).andExpect(status().isOk());
        long c2 = id(create(a));
        String no1 = accounts.query(comp, "select capa_no from qms_capa where capa_id = ?", String.class, c1);
        String no2 = accounts.query(comp, "select capa_no from qms_capa where capa_id = ?", String.class, c2);
        mvc.perform(get(N + "/" + ncrId(a)).with(admin)).andExpect(jsonPath("$.data.capas.length()").value(2))
                .andExpect(jsonPath("$.data.capas[0].capaNo").value(no1)).andExpect(jsonPath("$.data.capas[1].capaNo").value(no2))
                .andExpect(jsonPath("$.data.capas[0].status").value("OPEN")).andExpect(jsonPath("$.data.capas[0].title").doesNotExist());
        mvc.perform(get(N + "/" + ncrId(b)).with(admin)).andExpect(jsonPath("$.data.capas.length()").value(0));
        // 빈 번호로 만들기 = 연결 없음
        assertThat(linked(id(create("")))).isEqualTo("-");
    }

    @Test
    void 없는_번호_취소된_NCR_종결된_8D_는_막고_이미_이어진_취소_NCR_은_유지() throws Exception {
        String a = ncr("L-A");
        String gone = ncr("L-C");
        long before = accounts.query(comp, "select count(*) from qms_capa", Long.class);
        create("NCR-NOPE").andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("ncrNo"))
                .andExpect(jsonPath("$.errors[0].code").value("NCR_NOT_FOUND"));
        assertThat(accounts.query(comp, "select count(*) from qms_capa", Long.class)).isEqualTo(before);
        // 이어 둔 뒤 NCR 취소 → 같은 번호로 다시 저장은 그대로 됨, 새로 잇기는 거부
        long c1 = id(create(gone));
        int nv = accounts.query(comp, "select row_ver from qms_ncr where ncr_no = ?", Integer.class, gone);
        postJson(N + "/" + ncrId(gone) + "/cancel", "{\"rowVer\":" + nv + ",\"reason\":\"중복\"}").andExpect(status().isOk());
        save(c1, q(gone)).andExpect(status().isOk());
        assertThat(linked(c1)).isEqualTo(gone);
        long c2 = id(create(a));
        save(c2, q(gone)).andExpect(jsonPath("$.errors[0].field").value("ncrNo")).andExpect(jsonPath("$.errors[0].code").value("NCR_CANCELLED"));
        create(gone).andExpect(jsonPath("$.errors[0].code").value("NCR_CANCELLED"));
        assertThat(linked(c2)).isEqualTo(a);
        // 취소된 8D 는 연결도 못 바꾼다(기존 상태 규칙)
        postJson(A + "/" + c2 + "/cancel", "{\"rowVer\":" + ver(c2) + ",\"reason\":\"중복 접수\"}").andExpect(status().isOk());
        save(c2, "\"\"").andExpect(jsonPath("$.code").value("QMS_CAPA_STATE"));
        assertThat(linked(c2)).isEqualTo(a);
    }

    @Test
    void A사로_B사_번호는_칸오류_직접SQL_거부_B사는_A사_NCR_상세를_못봄() throws Exception {
        String a = ncr("L-A");
        long aNcr = ncrId(a);
        long c1 = id(create(a));
        String other = accounts.newComp("QL");
        fixtures(other);
        long bUser = accounts.newUser(other, "admin1", PW);
        accounts.grant(other, bUser, "QA", "qms-capa", "SEARCH", "SAVE");
        accounts.grant(other, bUser, "QA", "qms-ncr", "SEARCH", "SAVE");
        RequestPostProcessor b = accounts.as(other, bUser, "admin1");
        // B사 범위에서 A사 NCR 번호 → 없는 번호와 같은 칸 오류(존재 노출 없음)
        long bCapa = id(mvc.perform(json(post(A),
                "{\"source\":\"INTERNAL\",\"title\":\"t\"}").with(b)));
        mvc.perform(json(put(A + "/" + bCapa), "{\"title\":\"t\",\"ncrNo\":" + q(a) + ",\"rowVer\":0}").with(b))
                .andExpect(jsonPath("$.errors[0].field").value("ncrNo")).andExpect(jsonPath("$.errors[0].code").value("NCR_NOT_FOUND"));
        mvc.perform(get(N + "/" + aNcr).with(b)).andExpect(status().isNotFound());
        // 직접 SQL: B사 8D 에 A사 NCR id → 같은 회사 확인 트리거
        constraint(other, "fk_qms_capa_ncr_comp", () -> accounts.exec(other, "update qms_capa set ncr_id = ? where capa_id = ?", aNcr, bCapa));
        // A사 NCR 상세의 8D 목록에 B사 8D 가 섞이지 않는다
        mvc.perform(get(N + "/" + aNcr).with(admin)).andExpect(jsonPath("$.data.capas.length()").value(1))
                .andExpect(jsonPath("$.data.capas[0].capaId").value((int) c1));
        // B사 사용자가 A사 8D 에 B사 쪽 번호로 연결 시도 → 404(A사 8D 가 안 보임)
        mvc.perform(json(put(A + "/" + c1), "{\"title\":\"t\",\"ncrNo\":\"\",\"rowVer\":0}").with(b)).andExpect(status().isNotFound());
        assertThat(linked(c1)).isEqualTo(a);
    }

    /** 소유자·replica(트리거·FK 건너뜀, CHECK 는 그대로) — 결재를 거치지 않고 상태를 옮긴다 */
    static void replica(String sql, Object... args) throws Exception {
        try (java.sql.Connection c = java.sql.DriverManager.getConnection(jdbcUrl(), OWNER_USER, testPassword())) {
            try (java.sql.Statement st = c.createStatement()) {
                st.execute("set session_replication_role = replica");
            }
            try (java.sql.PreparedStatement ps = c.prepareStatement(sql)) {
                for (int i = 0; i < args.length; i++) {
                    ps.setObject(i + 1, args[i]);
                }
                ps.execute();
            }
        }
    }

    @Test
    void 종결결재중_연결은_직접SQL로도_못바꾼다_CN7() throws Exception {
        String a = ncr("L-CN7A");
        String b = ncr("L-CN7B");
        long c1 = id(create(a));
        replica("update qms_capa set status = 'CLOSING', apr_id = 0, d1_team = 't', d2_problem = 't', d3_contain = 't', d4_cause = 't', d5_action = 't', d6_verify = 't', d7_prevent = 't', d8_close = 't' where capa_id = ?", c1);
        // 서비스(기존): 결재 중 저장 → 상태 오류
        save(c1, q(b)).andExpect(jsonPath("$.code").value("QMS_CAPA_STATE"));
        // 직접 SQL(앱 계정): 바꾸기·끊기 모두 거부, 같은 값으로는 그대로(다른 칸을 안 바꾸는 갱신)
        constraint(comp, "ck_qms_capa_closing_locked", () -> accounts.exec(comp, "update qms_capa set ncr_id = ? where capa_id = ?", ncrId(b), c1));
        constraint(comp, "ck_qms_capa_closing_locked", () -> accounts.exec(comp, "update qms_capa set ncr_id = null where capa_id = ?", c1));
        assertThat(accounts.exec(comp, "update qms_capa set ncr_id = ? where capa_id = ?", ncrId(a), c1)).isEqualTo(1);
        assertThat(linked(c1)).isEqualTo(a);
        // 반려(CLOSING → ACTIONED)는 연결을 안 바꾸면 된다, 그 뒤엔 다시 바꿀 수 있다
        assertThat(accounts.exec(comp, "update qms_capa set status = 'ACTIONED' where capa_id = ?", c1)).isEqualTo(1);
        assertThat(accounts.exec(comp, "update qms_capa set ncr_id = ? where capa_id = ?", ncrId(b), c1)).isEqualTo(1);
        assertThat(linked(c1)).isEqualTo(b);
        // 종결·취소는 행 전체가 이미 고정(기존 규칙 그대로)
        long c2 = id(create(a));
        replica("update qms_capa set status = 'CLOSED', closed_at = now(), closed_by = 't', d1_team = 't', d2_problem = 't', d3_contain = 't', d4_cause = 't', d5_action = 't', d6_verify = 't', d7_prevent = 't', d8_close = 't' where capa_id = ?", c2);
        constraint(comp, "ck_qms_capa_immutable", () -> accounts.exec(comp, "update qms_capa set ncr_id = ? where capa_id = ?", ncrId(b), c2));
    }
}
