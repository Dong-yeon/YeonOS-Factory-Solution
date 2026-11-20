package io.smarthappy.module.plm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.jayway.jsonpath.JsonPath;

import io.smarthappy.module.plm.PlmDtos.PpapElemRow;
import io.smarthappy.module.plm.PlmDtos.PpapRow;
import io.smarthappy.platform.common.db.PgErrors;
import io.smarthappy.support.PostgresTestBase;
import io.smarthappy.support.TestAccounts;

/**
 * PPAP 요소 6·7 ← APQP 현행 확정본 (docs/39 38장 Q1~Q7, PMS A3-6). APQP 는 계약 ApqpLookup(실제 빈)으로 읽고, APQP 표는 owner 로 시드한다.
 * 상위 필수: 후보 밖 rev 400 · SEARCH 없음 → 후보 빈 목록·연결 403 · 요소 6 에 CP 거부 · 연결 없는 패키지 서명 해시 불변 · A사로 B사.
 */
@AutoConfigureMockMvc
class PlmPpapApqpTest extends PostgresTestBase {

    private static final String PW = "Maple-anchor-63";

    @Autowired
    MockMvc mvc;

    @Autowired
    TestAccounts accounts;

    String comp;
    RequestPostProcessor eng;
    RequestPostProcessor noApqp;
    RequestPostProcessor viewer;
    long apSet;

    @BeforeEach
    void setUp() {
        comp = accounts.newComp("PPQ");
        eng = user(comp, "eng1", true);
        noApqp = user(comp, "eng2", false);
        long viewId = accounts.newUser(comp, "view1", PW);
        accounts.grant(comp, viewId, "VIEW", "plm-eco", "SEARCH");
        accounts.grant(comp, viewId, "VIEW", "apqp-fmea", "SEARCH");
        viewer = accounts.as(comp, viewId, "view1");
        apSet = fixture(comp);
    }

    private RequestPostProcessor user(String c, String login, boolean apqp) {
        long id = accounts.newUser(c, login, PW);
        accounts.grant(c, id, "ENG", "plm-eco", "SEARCH", "SAVE");
        if (apqp) {
            accounts.grant(c, id, "APQPV", "apqp-fmea", "SEARCH");
            accounts.grant(c, id, "APQPV", "apqp-cp", "SEARCH");
        }
        return accounts.as(c, id, login);
    }

    /** 품목 ZT-A(지시 대상)·ZT-B(대상 아님)·고객 C1·AP 세트 → AP 세트 id */
    private long fixture(String c) {
        for (String it : new String[] {"ZT-A", "ZT-B"}) {
            accounts.exec(c, """
                    insert into mst_item (comp_cd, item_cd, item_nm, item_type, proc_type, unit_cd, created_by, updated_by)
                    values (?, ?, ?, 'FG', 'MAKE', 'EA', 't', 't')""", c, it, it + " 브래킷");
        }
        accounts.exec(c, "insert into mst_cust (comp_cd, cust_cd, cust_nm, cust_type, created_by, updated_by) values (?, 'C1', 'C1 고객', 'SALES', 't', 't')", c);
        return accounts.query(c, """
                insert into apqp_ap_set (comp_cd, set_cd, set_nm, use_yn, created_by, updated_by) values (?, 'ZT-AP', 'ZT 예시', 'Y', 't', 't')
                returning set_id""", Long.class, c);
    }

    private long item(String c, String cd) {
        return accounts.query(c, "select item_id from mst_item where item_cd = ?", Long.class, cd);
    }

    /** PFMEA 리비전 — 작성 중으로 넣고 status 까지(RELEASED·OBSOLETE 는 확정을 거친다) */
    private long fmea(String c, long ap, String itemCd, String no, int rev, String status) {
        long id = accounts.query(c, """
                insert into apqp_fmea (comp_cd, fmea_no, rev, item_id, title, ap_set_id, created_by, updated_by)
                values (?, ?, ?, (select item_id from mst_item where item_cd = ?), ?, ?, 't', 't') returning fmea_id""",
                Long.class, c, no, rev, itemCd, "ZT PFMEA " + no, ap);
        if (!"DRAFT".equals(status)) {
            accounts.exec(c, "update apqp_fmea set status = 'RELEASED', released_at = now(), released_by = 't' where fmea_id = ?", id);
        }
        if ("OBSOLETE".equals(status)) {
            accounts.exec(c, "update apqp_fmea set status = 'OBSOLETE' where fmea_id = ?", id);
        }
        return id;
    }

    /** 관리계획서 리비전(행 1개 — 확정 조건) */
    private long cp(String c, String itemCd, String no, String phase, String status) {
        long id = accounts.query(c, """
                insert into apqp_cp (comp_cd, cp_no, phase, item_id, title, created_by, updated_by)
                values (?, ?, ?, (select item_id from mst_item where item_cd = ?), ?, 't', 't') returning cp_id""",
                Long.class, c, no, phase, itemCd, "ZT 관리계획서 " + no);
        accounts.exec(c, """
                insert into apqp_cp_line (comp_cd, cp_id, line_no, step_text, char_text, ctrl_method, created_by, updated_by)
                values (?, ?, 1, '10 용접', '용접 전류', 'OTHER', 't', 't')""", c, id);
        if (!"DRAFT".equals(status)) {
            accounts.exec(c, "update apqp_cp set status = 'RELEASED', released_at = now(), released_by = 't' where cp_id = ?", id);
        }
        return id;
    }

    /** 지시(대상 ZT-A) + C1 패키지(작성 중, 18요소) → {ecoId, ppapId} */
    private long[] ppap(String c, String ecoNo) {
        long eco = accounts.query(c, """
                insert into plm_eco (comp_cd, eco_no, title, reason_cd, effective_date, owner_id, created_by, updated_by)
                values (?, ?, 'ZT 브래킷 두께 변경', 'QUALITY', current_date, (select min(user_id) from sys_user where comp_cd = ?), 't', 't')
                returning eco_id""", Long.class, c, ecoNo, c);
        accounts.exec(c, """
                insert into plm_eco_target (comp_cd, eco_id, seq, target_type, item_id, created_by, updated_by)
                values (?, ?, 1, 'ITEM', (select item_id from mst_item where item_cd = 'ZT-A'), 't', 't')""", c, eco);
        long p = accounts.query(c, """
                insert into plm_ppap (comp_cd, eco_id, cust_id, submit_level, created_by, updated_by)
                values (?, ?, (select cust_id from mst_cust where cust_cd = 'C1'), 3, 't', 't') returning ppap_id""", Long.class, c, eco);
        accounts.exec(c, """
                insert into plm_ppap_elem (comp_cd, ppap_id, elem_no, req_cd, created_by, updated_by)
                select ?, ?, n, 'S', 't', 't' from generate_series(1, 18) n""", c, p);
        return new long[] {eco, p};
    }

    private String body(RequestPostProcessor who, String url) throws Exception {
        return mvc.perform(get(url).with(who)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    private ResultActions link(RequestPostProcessor who, long ppapId, int elemNo, Long revId) throws Exception {
        int ver = accounts.query(comp, "select row_ver from plm_ppap where ppap_id = ?", Integer.class, ppapId);
        return link(who, ppapId, ver, elemNo, revId);
    }

    private ResultActions link(RequestPostProcessor who, long ppapId, int ver, int elemNo, Long revId) throws Exception {
        return mvc.perform(put("/api/plm/ppaps/" + ppapId + "/apqp").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"rowVer\":" + ver + ",\"elemNo\":" + elemNo + ",\"revId\":" + revId + "}").with(who));
    }

    private String elem(RequestPostProcessor who, long ecoId, int elemNo, String field) throws Exception {
        Object v = JsonPath.read(body(who, "/api/plm/ecos/" + ecoId + "/ppap"), "$.data.elems[" + (elemNo - 1) + "]." + field);
        return v == null ? null : String.valueOf(v);
    }

    @Test
    void 후보는_지시_대상_품목의_현행_확정본만_CP는_양산먼저_연결하면_label과_상태_폐기돼도_근거는_그대로() throws Exception {
        long[] p = ppap(comp, "ECO-Q1");
        long f1 = fmea(comp, apSet, "ZT-A", "PF-A", 1, "RELEASED");
        fmea(comp, apSet, "ZT-A", "PF-D", 1, "DRAFT");
        fmea(comp, apSet, "ZT-B", "PF-B", 1, "RELEASED");     // 대상 아닌 품목
        long proto = cp(comp, "ZT-A", "CP-P", "PROTO", "RELEASED");
        long prod = cp(comp, "ZT-A", "CP-M", "PRODUCTION", "RELEASED");
        cp(comp, "ZT-A", "CP-D", "PRE_LAUNCH", "DRAFT");
        String cand = body(eng, "/api/plm/ppaps/" + p[1] + "/apqp-candidates");
        assertThat((List<String>) JsonPath.read(cand, "$.data.fmea[*].no")).containsExactly("PF-A");
        assertThat((List<String>) JsonPath.read(cand, "$.data.fmea[*].itemCd")).containsExactly("ZT-A");
        assertThat((List<String>) JsonPath.read(cand, "$.data.cp[*].no")).containsExactly("CP-M", "CP-P");
        assertThat((List<Integer>) JsonPath.read(cand, "$.data.cp[*].revId")).containsExactly((int) prod, (int) proto);
        // 연결 — label 은 서버가, 상태 = 현행 확정, 패키지 row_ver 올라감
        int ver = accounts.query(comp, "select row_ver from plm_ppap where ppap_id = ?", Integer.class, p[1]);
        link(eng, p[1], 6, f1).andExpect(status().isOk());
        link(eng, p[1], 7, prod).andExpect(status().isOk());
        assertThat(accounts.query(comp, "select row_ver from plm_ppap where ppap_id = ?", Integer.class, p[1])).isEqualTo(ver + 2);
        String d = body(eng, "/api/plm/ecos/" + p[0] + "/ppap");
        assertThat((List<String>) JsonPath.read(d, "$.data.apqpKinds")).containsExactly("FMEA", "CP");
        assertThat(elem(eng, p[0], 6, "apqpLabel")).isEqualTo("PF-A rev 1");
        assertThat(elem(eng, p[0], 6, "apqpStatus")).isEqualTo("RELEASED");
        assertThat(elem(eng, p[0], 7, "apqpLabel")).isEqualTo("CP-M rev 1 PRODUCTION");
        assertThat(elem(eng, p[0], 7, "apqpKind")).isEqualTo("CP");
        // APQP 새 리비전 확정(앞 것 폐기) → 근거 그대로, 상태 OBSOLETE, 후보는 rev 2
        accounts.exec(comp, "update apqp_fmea set status = 'OBSOLETE' where fmea_id = ?", f1);
        long f1r2 = fmea(comp, apSet, "ZT-A", "PF-A", 2, "RELEASED");
        assertThat(elem(eng, p[0], 6, "apqpRevId")).isEqualTo(String.valueOf(f1));
        assertThat(elem(eng, p[0], 6, "apqpStatus")).isEqualTo("OBSOLETE");
        assertThat((List<Integer>) JsonPath.read(body(eng, "/api/plm/ppaps/" + p[1] + "/apqp-candidates"), "$.data.fmea[*].revId"))
                .containsExactly((int) f1r2);
        // 요약서 증빙 칸: 파일 없으면 label(Q6) — 서비스 경유 대신 같은 규칙을 표에서 확인
        assertThat(accounts.query(comp, "select apqp_label from plm_ppap_elem where ppap_id = ? and elem_no = 6", String.class, p[1]))
                .isEqualTo("PF-A rev 1");
        // 끊기 — 세 칸 모두 null
        link(eng, p[1], 6, null).andExpect(status().isOk());
        assertThat(accounts.query(comp, """
                select count(*) from plm_ppap_elem where ppap_id = ? and elem_no = 6 and apqp_kind is null and apqp_rev_id is null and apqp_label is null""",
                Integer.class, p[1])).isEqualTo(1);
    }

    @Test
    void 후보_밖_리비전과_종류가_다른_요소는_400_label은_서버가_만들고_DB도_막는다() throws Exception {
        long[] p = ppap(comp, "ECO-Q2");
        long f1 = fmea(comp, apSet, "ZT-A", "PF-A", 1, "RELEASED");
        long draft = fmea(comp, apSet, "ZT-A", "PF-D", 1, "DRAFT");
        long other = fmea(comp, apSet, "ZT-B", "PF-B", 1, "RELEASED");
        long obs = fmea(comp, apSet, "ZT-A", "PF-O", 1, "OBSOLETE");
        long c1 = cp(comp, "ZT-A", "CP-M", "PRODUCTION", "RELEASED");
        // 후보 밖: 작성 중·다른 품목·폐기·없는 id
        for (long bad : new long[] {draft, other, obs, -1L}) {
            link(eng, p[1], 6, bad).andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].code").value("NOT_CANDIDATE"));
        }
        // 종류가 다른 요소: 요소 6 에 CP id, 요소 7 에 FMEA id → 후보 밖(id 가 우연히 같아도 종류로 가른다)
        link(eng, p[1], 6, c1).andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].code").value("NOT_CANDIDATE"));
        link(eng, p[1], 7, f1).andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].code").value("NOT_CANDIDATE"));
        // 6·7 아닌 요소
        link(eng, p[1], 5, f1).andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].code").value("CODE"));
        // 낡은 row_ver → 409
        link(eng, p[1], 99, 6, f1).andExpect(status().isConflict());
        assertThat(accounts.query(comp, "select count(*) from plm_ppap_elem where ppap_id = ? and apqp_rev_id is not null", Integer.class, p[1]))
                .isZero();
        // label 위조 칸은 받지 않는다(서버가 만든다)
        int ver = accounts.query(comp, "select row_ver from plm_ppap where ppap_id = ?", Integer.class, p[1]);
        mvc.perform(put("/api/plm/ppaps/" + p[1] + "/apqp").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"rowVer\":" + ver + ",\"elemNo\":6,\"revId\":" + f1 + ",\"label\":\"가짜\"}").with(eng)).andExpect(status().isOk());
        assertThat(accounts.query(comp, "select apqp_label from plm_ppap_elem where ppap_id = ? and elem_no = 6", String.class, p[1]))
                .isEqualTo("PF-A rev 1");
        // DB CHECK: 요소 6 ↔ FMEA 만, 셋 다 있거나 셋 다 없음
        Throwable kind = catchThrowable(() -> accounts.exec(comp, "update plm_ppap_elem set apqp_kind = 'CP' where ppap_id = ? and elem_no = 6", p[1]));
        assertThat(PgErrors.constraint(kind)).hasValue("ck_plm_ppap_elem_apqp_kind");
        Throwable part = catchThrowable(() -> accounts.exec(comp, "update plm_ppap_elem set apqp_label = null where ppap_id = ? and elem_no = 6", p[1]));
        assertThat(PgErrors.constraint(part)).hasValue("ck_plm_ppap_elem_apqp");
    }

    @Test
    void APQP_조회권한_없으면_후보_빈목록_연결_403_있던_근거는_label만_보인다_조회권한만이면_403() throws Exception {
        long[] p = ppap(comp, "ECO-Q3");
        long f1 = fmea(comp, apSet, "ZT-A", "PF-A", 1, "RELEASED");
        String cand = body(noApqp, "/api/plm/ppaps/" + p[1] + "/apqp-candidates");
        assertThat((List<?>) JsonPath.read(cand, "$.data.fmea")).isEmpty();
        assertThat((List<?>) JsonPath.read(cand, "$.data.cp")).isEmpty();
        link(noApqp, p[1], 6, f1).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("NO_PERMISSION"));
        link(noApqp, p[1], 6, null).andExpect(status().isForbidden());
        link(eng, p[1], 6, f1).andExpect(status().isOk());
        assertThat((List<?>) JsonPath.read(body(noApqp, "/api/plm/ecos/" + p[0] + "/ppap"), "$.data.apqpKinds")).isEmpty();
        assertThat(elem(noApqp, p[0], 6, "apqpLabel")).isEqualTo("PF-A rev 1");
        assertThat(elem(noApqp, p[0], 6, "apqpStatus")).isNull();
        // PLM 조회 권한만(SAVE 없음) → 후보·연결 403
        mvc.perform(get("/api/plm/ppaps/" + p[1] + "/apqp-candidates").with(viewer)).andExpect(status().isForbidden());
        link(viewer, p[1], 6, null).andExpect(status().isForbidden());
    }

    @Test
    void 제출_뒤에는_연결을_바꿀_수_없고_가드가_새_칸도_막는다() throws Exception {
        long[] p = ppap(comp, "ECO-Q4");
        long f1 = fmea(comp, apSet, "ZT-A", "PF-A", 1, "RELEASED");
        link(eng, p[1], 6, f1).andExpect(status().isOk());
        accounts.exec(comp, "update plm_ppap set status = 'SUBMITTED', submitted_on = current_date where ppap_id = ?", p[1]);
        link(eng, p[1], 6, null).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PLM_PPAP_STATE"));
        Throwable t = catchThrowable(() -> accounts.exec(comp,
                "update plm_ppap_elem set apqp_kind = null, apqp_rev_id = null, apqp_label = null where ppap_id = ? and elem_no = 6", p[1]));
        assertThat(PgErrors.constraint(t)).hasValue("ck_plm_ppap_fixed");
        assertThat(elem(eng, p[0], 6, "apqpLabel")).isEqualTo("PF-A rev 1");
    }

    @Test
    void 연결_없는_패키지의_서명_내용은_예전과_같고_연결이_있으면_덧붙는다() {
        PpapRow r = new PpapRow();
        r.setPpapId(1);
        r.setEcoId(2);
        r.setCustCd("C1");
        r.setSubmitLevel(3);
        r.setStatus("DRAFT");
        PpapElemRow e = new PpapElemRow();
        e.setElemNo(6);
        e.setReqCd("S");
        e.setStatus("OPEN");
        // 38장 이전 형식 그대로(연결 칸이 생겨도 null 이면 줄이 없다) — 기존 서명 해시 불변
        String before = "PLM_PPAP v1\n1:1\n1:2\n2:C1\n1:3\n5:DRAFT\n-\n-\n-\n-\nE\n1:6\n1:S\n4:OPEN\n-\n";
        assertThat(PlmSignContent.ppap(r, List.of(e))).isEqualTo(before);
        assertThat(PlmSignContent.ppapHash(r, List.of(e))).isEqualTo(io.smarthappy.platform.esign.ESignService.hash(before));
        e.setApqpKind("FMEA");
        e.setApqpRevId(7L);
        e.setApqpLabel("PF-A rev 1");
        e.setApqpStatus("OBSOLETE");    // 상태는 해시에 넣지 않는다(폐기돼도 서명 그대로)
        assertThat(PlmSignContent.ppap(r, List.of(e))).isEqualTo(before + "A\n4:FMEA\n1:7\n10:PF-A rev 1\n");
    }

    @Test
    void A사로_B사_패키지는_404_B사_리비전은_후보_밖() throws Exception {
        long[] p = ppap(comp, "ECO-Q6");
        String b = accounts.newComp("PPR");
        RequestPostProcessor bEng = user(b, "eng1", true);
        long bSet = fixture(b);
        long bF = fmea(b, bSet, "ZT-A", "PF-A", 1, "RELEASED");     // 같은 품번·번호지만 B사 것
        mvc.perform(get("/api/plm/ppaps/" + p[1] + "/apqp-candidates").with(bEng)).andExpect(status().isNotFound());
        mvc.perform(put("/api/plm/ppaps/" + p[1] + "/apqp").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"rowVer\":0,\"elemNo\":6,\"revId\":" + bF + "}").with(bEng)).andExpect(status().isNotFound());
        // A사 사용자가 B사 리비전 id → 후보 밖(RLS 로 안 보임)
        assertThat((List<?>) JsonPath.read(body(eng, "/api/plm/ppaps/" + p[1] + "/apqp-candidates"), "$.data.fmea")).isEmpty();
        link(eng, p[1], 6, bF).andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].code").value("NOT_CANDIDATE"));
        // B사 범위에서 A사 패키지 행은 0
        assertThat(accounts.query(b, "select count(*) from plm_ppap_elem where ppap_id = ?", Integer.class, p[1])).isZero();
        assertThat(item(b, "ZT-A")).isNotEqualTo(item(comp, "ZT-A"));
    }
}
