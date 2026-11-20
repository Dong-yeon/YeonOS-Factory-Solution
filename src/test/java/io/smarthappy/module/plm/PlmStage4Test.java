package io.smarthappy.module.plm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.jayway.jsonpath.JsonPath;

import io.smarthappy.platform.common.db.PgErrors;
import io.smarthappy.support.PostgresTestBase;
import io.smarthappy.support.TestAccounts;

/**
 * PLM 4단계 (docs/39 14장) — D4 ECO 필수 정책(BOM·라우팅 확정은 ECO 적용 경로에서만), ECO 일괄 대체 연동,
 * PPAP 패키지(요소·제출·판정 → ECO 고객 승인), 고객 통지 PCN. 상태·권한·값·A사로 B사·직접 SQL.
 */
@AutoConfigureMockMvc
class PlmStage4Test extends PostgresTestBase {

    private static final String PW = "Maple-anchor-63";
    private static final String ECO = "/api/plm/ecos";
    private static final String PPAP = "/api/plm/ppaps";
    private static final String PCN = "/api/plm/pcns";
    private static final String APR = "/api/platform/approvals";

    @Autowired
    MockMvc mvc;

    @Autowired
    TestAccounts accounts;

    String comp;
    long ownerId;
    long approverId;
    RequestPostProcessor owner;
    RequestPostProcessor approver;
    String today;

    @BeforeEach
    void setUp() {
        comp = accounts.newComp("PLF");
        ownerId = accounts.newUser(comp, "eng1", PW);
        approverId = accounts.newUser(comp, "mgr1", PW);
        for (long u : new long[] {ownerId, approverId}) {
            accounts.grant(comp, u, "ENG", "plm-eco", "SEARCH", "SAVE", "APPROVE");
            accounts.grant(comp, u, "ENG", "mst-bom", "SEARCH", "SAVE", "APPROVE");
            accounts.grant(comp, u, "ENG", "mst-routing", "SEARCH", "SAVE", "APPROVE");
        }
        owner = accounts.as(comp, ownerId, "eng1");
        approver = accounts.as(comp, approverId, "mgr1");
        accounts.addPlant(comp, "P1", "1공장");
        today = accounts.query(comp, "select " + TestAccounts.COMP_TODAY + "::text", String.class);
        seed(comp);
    }

    /** FG·FG2 ← RM (BOM A0 확정), RM3 = 대체 자재, FG 라우팅 A0 확정 + A1 작성 중, 판매 고객 C1·C2 */
    private void seed(String c) {
        for (String i : new String[] {"FG", "FG2", "RM", "RM3"}) {
            accounts.exec(c, """
                    insert into mst_item (comp_cd, item_cd, item_nm, item_type, proc_type, unit_cd, created_by, updated_by)
                    values (?, ?, ?, 'FG', 'MAKE', 'EA', 't', 't')""", c, i, i + " 품목");
        }
        for (String parent : new String[] {"FG", "FG2"}) {
            long b = accounts.query(c, """
                    insert into mst_bom (comp_cd, item_id, rev, valid_from, created_by, updated_by)
                    values (?, (select item_id from mst_item where item_cd = ?), 'A0', (now() at time zone 'Asia/Seoul')::date - 30, 't', 't') returning bom_id""",
                    Long.class, c, parent);
            accounts.exec(c, """
                    insert into mst_bom_line (comp_cd, bom_id, line_no, item_id, qty, unit_cd, line_type, issue_method,
                                              created_by, updated_by)
                    values (?, ?, 10, (select item_id from mst_item where item_cd = 'RM'), 2, 'EA', 'NORMAL', 'BACKFLUSH', 't', 't')""", c, b);
            accounts.exec(c, "update mst_bom set status = 'RELEASED', released_at = now(), released_by = 't' where bom_id = ?", b);
        }
        accounts.exec(c, """
                insert into mst_proc (comp_cd, proc_cd, proc_nm, proc_cat, outsrc_yn, created_by, updated_by)
                values (?, 'PLATE', '외주 도금', 'PAINT', 'Y', 't', 't')""", c);
        for (String rev : new String[] {"A0", "A1"}) {
            long r = accounts.query(c, """
                    insert into mst_routing (comp_cd, item_id, plant_cd, rev, valid_from, created_by, updated_by)
                    values (?, (select item_id from mst_item where item_cd = 'FG'), 'P1', ?,
                            case when ? = 'A0' then (now() at time zone 'Asia/Seoul')::date - 30 else (now() at time zone 'Asia/Seoul')::date + 1 end, 't', 't') returning routing_id""",
                    Long.class, c, rev, rev);
            accounts.exec(c, """
                    insert into mst_routing_step (comp_cd, routing_id, op_seq, proc_id, created_by, updated_by)
                    values (?, ?, 10, (select proc_id from mst_proc where proc_cd = 'PLATE'), 't', 't')""", c, r);
            if ("A0".equals(rev)) {
                accounts.exec(c, "update mst_routing set status = 'RELEASED', released_at = now(), released_by = 't' where routing_id = ?", r);
            }
        }
        for (String cust : new String[] {"C1", "C2"}) {
            accounts.exec(c, """
                    insert into mst_cust (comp_cd, cust_cd, cust_nm, cust_type, created_by, updated_by)
                    values (?, ?, ?, 'SALES', 't', 't')""", c, cust, cust + " 고객");
        }
    }

    // ---------- 도우미 ----------

    private long id(String c, String sql, Object... args) {
        return accounts.query(c, sql, Long.class, args);
    }

    private MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder b, String body) {
        return b.with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private ResultActions postAs(RequestPostProcessor who, String url, String body) throws Exception {
        return mvc.perform(json(post(url), body).with(who));
    }

    private ResultActions putAs(RequestPostProcessor who, String url, String body) throws Exception {
        return mvc.perform(json(put(url), body).with(who));
    }

    private static long data(ResultActions r) throws Exception {
        MvcResult m = r.andExpect(status().isOk()).andReturn();
        return ((Number) JsonPath.read(m.getResponse().getContentAsString(), "$.data")).longValue();
    }

    private int ver(String table, String key, long id) {
        return accounts.query(comp, "select row_ver from " + table + " where " + key + " = ?", Integer.class, id);
    }

    private int ecoVer(long ecoId) {
        return ver("plm_eco", "eco_id", ecoId);
    }

    private long newEco(String extra) throws Exception {
        return data(postAs(owner, ECO, "{\"title\":\"4단계\",\"reasonCd\":\"QUALITY\",\"effectiveDate\":\"" + today + "\""
                + (extra == null ? "" : "," + extra) + "}"));
    }

    private void submitApprove(long eco) throws Exception {
        postAs(owner, ECO + "/" + eco + "/submit", "{\"rowVer\":" + ecoVer(eco)
                + ",\"lines\":[{\"userId\":" + approverId + "}]}").andExpect(status().isOk());
        long apr = id(comp, "select apr_id from plm_eco where eco_id = ?", eco);
        postAs(approver, APR + "/" + apr + "/approve", "{}").andExpect(status().isOk());
    }

    private ResultActions release(long eco) throws Exception {
        return postAs(approver, ECO + "/" + eco + "/release", "{\"rowVer\":" + ecoVer(eco) + "}");
    }

    private void policy(String c, String key, String value) {
        accounts.exec(c, "insert into sys_policy (comp_cd, policy_key, policy_value, created_by, updated_by) values (?, ?, ?, 't', 't')",
                c, key, value);
    }

    /** FG 새 BOM 작성 중 리비전(A1, RM3) */
    private long draftBom(String c) {
        long b = id(c, """
                insert into mst_bom (comp_cd, item_id, rev, valid_from, created_by, updated_by)
                values (?, (select item_id from mst_item where item_cd = 'FG'), 'A1', (now() at time zone 'Asia/Seoul')::date + 1, 't', 't') returning bom_id""", c);
        accounts.exec(c, """
                insert into mst_bom_line (comp_cd, bom_id, line_no, item_id, qty, unit_cd, line_type, issue_method,
                                          created_by, updated_by)
                values (?, ?, 10, (select item_id from mst_item where item_cd = 'RM3'), 2, 'EA', 'NORMAL', 'BACKFLUSH', 't', 't')""", c, b);
        return b;
    }

    private long fileOf(String c, long ecoId, String name) {
        return id(c, """
                insert into sys_file (comp_cd, target_type, target_id, orig_name, ext, content_type, size_bytes, sha256, storage_key,
                                      created_by, updated_by)
                values (?, 'plm-eco', ?, ?, 'pdf', 'application/pdf', 10, repeat('a', 64), ? || '/2026/10/' || gen_random_uuid(), 't', 't')
                returning file_id""", c, String.valueOf(ecoId), name, c);
    }

    // ---------- D4 ECO 필수 정책 ----------

    @Test
    void D4_정책_Y_면_BOM_라우팅_직접_확정은_막히고_ECO_적용으로만_확정된다() throws Exception {
        long bom = draftBom(comp);
        long routing = id(comp, "select routing_id from mst_routing where rev = 'A1'");
        policy(comp, "PLM_ECO_REQUIRED", "Y");
        // BOM·라우팅 화면 확정 → 409, 아무것도 확정되지 않음
        accounts.exec(comp, "update mst_bom set valid_from = (now() at time zone 'Asia/Seoul')::date where bom_id = ?", bom);
        postAs(approver, "/api/master/boms/" + bom + "/release", "{\"rowVer\":" + ver("mst_bom", "bom_id", bom) + "}")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PLM_ECO_REQUIRED"));
        accounts.exec(comp, "update mst_routing set valid_from = (now() at time zone 'Asia/Seoul')::date where routing_id = ?", routing);
        postAs(approver, "/api/master/routings/" + routing + "/release", "{\"rowVer\":" + ver("mst_routing", "routing_id", routing) + "}")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PLM_ECO_REQUIRED"));
        // 직접 SQL(앱 계정) — 트리거가 막는다
        Throwable t = catchThrowable(() -> accounts.exec(comp,
                "update mst_bom set status = 'RELEASED', released_at = now(), released_by = 'x' where bom_id = ?", bom));
        assertThat(PgErrors.constraint(t)).contains("ck_plm_eco_required");
        assertThat(accounts.query(comp, "select status from mst_bom where bom_id = ?", String.class, bom)).isEqualTo("DRAFT");

        // ECO 적용 경로 → 둘 다 확정
        long eco = newEco(null);
        putAs(owner, ECO + "/" + eco + "/targets", "{\"rowVer\":" + ecoVer(eco) + ",\"targets\":[{\"rowKey\":\"b\",\"targetType\":\"BOM\",\"refId\":"
                + bom + "},{\"rowKey\":\"r\",\"targetType\":\"ROUTING\",\"refId\":" + routing + "}]}").andExpect(status().isOk());
        submitApprove(eco);
        release(eco).andExpect(status().isOk());
        assertThat(accounts.query(comp, "select status from mst_bom where bom_id = ?", String.class, bom)).isEqualTo("RELEASED");
        assertThat(accounts.query(comp, "select status from mst_routing where routing_id = ?", String.class, routing)).isEqualTo("RELEASED");
        // 허용 표시는 그 트랜잭션에서만 — 다음 트랜잭션의 직접 확정은 다시 막힌다
        long bom2 = id(comp, """
                insert into mst_bom (comp_cd, item_id, rev, valid_from, created_by, updated_by)
                values (?, (select item_id from mst_item where item_cd = 'FG2'), 'A1', (now() at time zone 'Asia/Seoul')::date + 5, 't', 't') returning bom_id""", comp);
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(comp,
                "update mst_bom set status = 'RELEASED', released_at = now(), released_by = 'x' where bom_id = ?", bom2))))
                .contains("ck_plm_eco_required");
    }

    @Test
    void D4_정책_N_기본이면_기존대로_직접_확정되고_다른_회사_정책은_영향_없다() throws Exception {
        // B사가 Y 로 바꿔도 A사는 기본 N
        String compB = accounts.newComp("PL4B");
        policy(compB, "PLM_ECO_REQUIRED", "Y");
        long bom = draftBom(comp);
        accounts.exec(comp, "update mst_bom set valid_from = (now() at time zone 'Asia/Seoul')::date where bom_id = ?", bom);
        postAs(approver, "/api/master/boms/" + bom + "/release", "{\"rowVer\":" + ver("mst_bom", "bom_id", bom) + "}")
                .andExpect(status().isOk());
        assertThat(accounts.query(comp, "select status from mst_bom where bom_id = ?", String.class, bom)).isEqualTo("RELEASED");
    }

    // ---------- 일괄 대체 연동 ----------

    @Test
    void 일괄_대체를_ECO_유효일로_실행하면_새_리비전이_BOM_대상으로_묶이고_적용으로_확정된다() throws Exception {
        long eco = newEco(null);
        MvcResult pv = mvc.perform(get(ECO + "/" + eco + "/replace-preview?fromItemCd=rm&toItemCd=rm3").with(owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.validFrom").value(today))
                .andExpect(jsonPath("$.data.rows.length()").value(2))
                .andExpect(jsonPath("$.data.rows[*].action").value(Matchers.everyItem(Matchers.is("NEW_REV")))).andReturn();
        java.util.List<Integer> src = JsonPath.read(pv.getResponse().getContentAsString(), "$.data.rows[*].sourceBomId");
        postAs(owner, ECO + "/" + eco + "/replace", "{\"rowVer\":" + ecoVer(eco) + ",\"fromItemCd\":\"RM\",\"toItemCd\":\"RM3\",\"bomIds\":"
                + src + "}").andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(2));
        mvc.perform(get(ECO + "/" + eco).with(owner))
                .andExpect(jsonPath("$.data.targets.length()").value(2))
                .andExpect(jsonPath("$.data.targets[*].targetType").value(Matchers.everyItem(Matchers.is("BOM"))))
                .andExpect(jsonPath("$.data.targets[0].changeDesc").value("RM → RM3"))
                .andExpect(jsonPath("$.data.targets[0].refStatus").value("DRAFT"));
        submitApprove(eco);
        release(eco).andExpect(status().isOk());
        String ecoNo = accounts.query(comp, "select eco_no from plm_eco where eco_id = ?", String.class, eco);
        assertThat(accounts.query(comp, """
                select count(*) from mst_bom b join mst_bom_line l on l.bom_id = b.bom_id
                 where b.status = 'RELEASED' and b.change_no = ? and b.valid_from = (now() at time zone 'Asia/Seoul')::date
                   and l.item_id = (select item_id from mst_item where item_cd = 'RM3')""", Integer.class, ecoNo)).isEqualTo(2);
    }

    @Test
    void 일괄_대체_결과_리비전이_다른_열린_ECO_에_묶여_있으면_전체_롤백() throws Exception {
        // FG 작성 중 A1 이 RM 을 쓰고 다른 ECO 에 묶임 → 일괄 대체는 그 작성 중에 반영하려 하지만 대상으로 묶을 수 없다
        long draft = id(comp, """
                insert into mst_bom (comp_cd, item_id, rev, valid_from, created_by, updated_by)
                values (?, (select item_id from mst_item where item_cd = 'FG'), 'A1', (now() at time zone 'Asia/Seoul')::date + 1, 't', 't') returning bom_id""", comp);
        accounts.exec(comp, """
                insert into mst_bom_line (comp_cd, bom_id, line_no, item_id, qty, unit_cd, line_type, issue_method, created_by, updated_by)
                values (?, ?, 10, (select item_id from mst_item where item_cd = 'RM'), 2, 'EA', 'NORMAL', 'BACKFLUSH', 't', 't')""", comp, draft);
        long other = newEco(null);
        putAs(owner, ECO + "/" + other + "/targets", "{\"rowVer\":" + ecoVer(other) + ",\"targets\":[{\"rowKey\":\"b\",\"targetType\":\"BOM\",\"refId\":"
                + draft + "}]}").andExpect(status().isOk());
        long eco = newEco(null);
        long fg2 = id(comp, "select b.bom_id from mst_bom b join mst_item i on i.item_id = b.item_id where i.item_cd = 'FG2'");
        int bomsBefore = accounts.query(comp, "select count(*) from mst_bom", Integer.class);
        postAs(owner, ECO + "/" + eco + "/replace", "{\"rowVer\":" + ecoVer(eco) + ",\"fromItemCd\":\"RM\",\"toItemCd\":\"RM3\",\"bomIds\":["
                + draft + "," + fg2 + "]}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].rowKey").value("b" + draft))
                .andExpect(jsonPath("$.errors[0].code").value("OPEN_ECO"));
        // 롤백: FG2 새 리비전 없음, 작성 중 행은 RM 그대로, 대상 없음
        assertThat(accounts.query(comp, "select count(*) from mst_bom", Integer.class)).isEqualTo(bomsBefore);
        assertThat(accounts.query(comp, "select i.item_cd from mst_bom_line l join mst_item i on i.item_id = l.item_id where l.bom_id = ?",
                String.class, draft)).isEqualTo("RM");
        assertThat(accounts.query(comp, "select count(*) from plm_eco_target where eco_id = ?", Integer.class, eco)).isZero();
        // 작성 중 아닌 지시·오래된 rowVer
        postAs(owner, ECO + "/" + eco + "/replace", "{\"rowVer\":99,\"fromItemCd\":\"RM\",\"toItemCd\":\"RM3\",\"bomIds\":[" + fg2 + "]}")
                .andExpect(status().isConflict());
        submitApprove(other);
        postAs(owner, ECO + "/" + other + "/replace", "{\"rowVer\":" + ecoVer(other) + ",\"fromItemCd\":\"RM\",\"toItemCd\":\"RM3\",\"bomIds\":["
                + fg2 + "]}").andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PLM_ECO_STATE"));
    }

    // ---------- PPAP ----------

    @Test
    void PPAP_요소를_채워_제출하고_승인하면_ECO_고객_승인이_채워져_적용할_수_있다() throws Exception {
        long eco = newEco("\"custApprovalReqYn\":\"Y\",\"custCd\":\"C1\"");
        putAs(owner, ECO + "/" + eco + "/targets", "{\"rowVer\":" + ecoVer(eco)
                + ",\"targets\":[{\"rowKey\":\"i\",\"targetType\":\"ITEM\",\"itemCd\":\"FG\"}]}").andExpect(status().isOk());
        long ppap = data(postAs(owner, ECO + "/" + eco + "/ppap", "{}"));
        mvc.perform(get(ECO + "/" + eco + "/ppap").with(owner))
                .andExpect(jsonPath("$.data.ppap.custCd").value("C1"))
                .andExpect(jsonPath("$.data.ppap.submitLevel").value(3))
                .andExpect(jsonPath("$.data.elems.length()").value(18))
                .andExpect(jsonPath("$.data.elems[*].reqCd").value(Matchers.everyItem(Matchers.is("S"))));
        // 하나 더 만들기 → 409
        postAs(owner, ECO + "/" + eco + "/ppap", "{}").andExpect(status().isConflict());
        // 제출 조건 미달: S 17개 OPEN + PSW
        postAs(owner, PPAP + "/" + ppap + "/submit", "{\"rowVer\":" + ver("plm_ppap", "ppap_id", ppap) + "}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("PLM_PPAP_INCOMPLETE"))
                .andExpect(jsonPath("$.errors.length()").value(18))
                .andExpect(jsonPath("$.errors[?(@.rowKey == 'e18')].code").value("PSW_REQUIRED"));
        // 레벨 1(PSW 만 S)로 바꾸고 요구를 맞춤 + PSW DONE(첨부 = 이 ECO 첨부)
        long psw = fileOf(comp, eco, "psw.pdf");
        StringBuilder elems = new StringBuilder();
        for (int n = 1; n <= 17; n++) {
            elems.append("{\"elemNo\":").append(n).append(",\"reqCd\":\"").append(n == 13 ? "N" : "R").append("\",\"status\":\"OPEN\"},");
        }
        elems.append("{\"elemNo\":18,\"reqCd\":\"S\",\"status\":\"DONE\",\"fileId\":").append(psw).append(",\"note\":\"PSW 서명\"}");
        putAs(owner, PPAP + "/" + ppap, "{\"rowVer\":" + ver("plm_ppap", "ppap_id", ppap) + ",\"submitLevel\":1,\"elems\":[" + elems + "]}")
                .andExpect(status().isOk());
        mvc.perform(get(ECO + "/" + eco + "/ppap").with(owner))
                .andExpect(jsonPath("$.data.elems[17].fileName").value("psw.pdf"))
                .andExpect(jsonPath("$.data.elems[12].reqCd").value("N"));
        postAs(owner, PPAP + "/" + ppap + "/submit", "{\"rowVer\":" + ver("plm_ppap", "ppap_id", ppap) + "}").andExpect(status().isOk());
        // 제출 뒤 수정 불가(API·직접 SQL)
        putAs(owner, PPAP + "/" + ppap, "{\"rowVer\":" + ver("plm_ppap", "ppap_id", ppap) + ",\"submitLevel\":3}").andExpect(status().isConflict());
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(comp,
                "update plm_ppap_elem set status = 'OPEN' where ppap_id = ? and elem_no = 18", ppap)))).contains("ck_plm_ppap_fixed");

        // 적용 전: ECO 결재 승인 → 고객 승인 전이라 적용 불가
        submitApprove(eco);
        release(eco).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PLM_CUST_APPROVAL_REQUIRED"));
        // 판정 값 검사: 승인 번호 없음 / 제출일 앞
        postAs(approver, PPAP + "/" + ppap + "/decide", "{\"rowVer\":" + ver("plm_ppap", "ppap_id", ppap)
                + ",\"decision\":\"APPROVED\",\"decidedOn\":\"" + today + "\"}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("decisionRef"));
        postAs(approver, PPAP + "/" + ppap + "/decide", "{\"rowVer\":" + ver("plm_ppap", "ppap_id", ppap)
                + ",\"decision\":\"APPROVED\",\"ref\":\"PSW-1\",\"decidedOn\":\"" + LocalDate.parse(today).minusDays(1) + "\"}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].code").value("BEFORE_SUBMITTED"));
        // 승인 → ECO 고객 승인 채움 → 적용
        postAs(approver, PPAP + "/" + ppap + "/decide", "{\"rowVer\":" + ver("plm_ppap", "ppap_id", ppap)
                + ",\"decision\":\"APPROVED\",\"ref\":\"PSW-1\",\"decidedOn\":\"" + today + "\"}").andExpect(status().isOk());
        assertThat(accounts.query(comp, "select cust_approval_status || ' ' || cust_approval_ref || ' ' || cust_approved_on from plm_eco where eco_id = ?",
                String.class, eco)).isEqualTo("APPROVED PSW-1 " + today);
        release(eco).andExpect(status().isOk());
        // 승인된 패키지는 끝: 다시 판정·다시 작성 중 불가
        postAs(owner, PPAP + "/" + ppap + "/reopen", "{\"rowVer\":" + ver("plm_ppap", "ppap_id", ppap) + "}").andExpect(status().isConflict());
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(comp,
                "update plm_ppap set decision_ref = 'X' where ppap_id = ?", ppap)))).contains("ck_plm_ppap_fixed");
    }

    @Test
    void PPAP_거절되면_다시_작성_중으로_재제출하고_값_권한_첨부를_검사한다() throws Exception {
        long eco = newEco(null);
        // ECO 고객이 없으면 고객 필수, 판매 거래처 아님·레벨 범위
        postAs(owner, ECO + "/" + eco + "/ppap", "{}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("custCd"));
        postAs(owner, ECO + "/" + eco + "/ppap", "{\"custCd\":\"NOPE\"}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].code").value("NOT_SALES"));
        postAs(owner, ECO + "/" + eco + "/ppap", "{\"custCd\":\"C2\",\"submitLevel\":9}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].code").value("RANGE"));
        long ppap = data(postAs(owner, ECO + "/" + eco + "/ppap", "{\"custCd\":\"c2\",\"submitLevel\":5}"));
        assertThat(accounts.query(comp, "select string_agg(distinct req_cd, '') from plm_ppap_elem where ppap_id = ?", String.class, ppap))
                .isEqualTo("R");
        // 다른 ECO 의 첨부를 요소에 → fileId NOT_FOUND
        long otherEco = newEco(null);
        long foreign = fileOf(comp, otherEco, "other.pdf");
        putAs(owner, PPAP + "/" + ppap, "{\"rowVer\":" + ver("plm_ppap", "ppap_id", ppap) + ",\"elems\":[{\"elemNo\":18,\"reqCd\":\"S\",\"status\":\"DONE\",\"fileId\":"
                + foreign + "}]}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].rowKey").value("e18")).andExpect(jsonPath("$.errors[0].field").value("fileId"));
        // 없는 요소 번호·잘못된 코드
        putAs(owner, PPAP + "/" + ppap, "{\"rowVer\":" + ver("plm_ppap", "ppap_id", ppap) + ",\"elems\":[{\"elemNo\":19,\"reqCd\":\"S\",\"status\":\"DONE\"}]}")
                .andExpect(status().isBadRequest());
        putAs(owner, PPAP + "/" + ppap, "{\"rowVer\":" + ver("plm_ppap", "ppap_id", ppap) + ",\"elems\":[{\"elemNo\":1,\"reqCd\":\"X\",\"status\":\"Y\"}]}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.length()").value(2));
        putAs(owner, PPAP + "/" + ppap, "{\"rowVer\":" + ver("plm_ppap", "ppap_id", ppap) + ",\"elems\":[{\"elemNo\":18,\"reqCd\":\"S\",\"status\":\"DONE\"}]}")
                .andExpect(status().isOk());
        // 미래 제출일
        postAs(owner, PPAP + "/" + ppap + "/submit", "{\"rowVer\":" + ver("plm_ppap", "ppap_id", ppap) + ",\"submittedOn\":\""
                + LocalDate.parse(today).plusDays(1) + "\"}").andExpect(status().isBadRequest());
        // 판정 전에 다시 작성 중·판정은 APPROVE 권한
        long saveOnly = accounts.newUser(comp, "eng2", PW);
        accounts.grant(comp, saveOnly, "ENG2", "plm-eco", "SEARCH", "SAVE");
        RequestPostProcessor eng2 = accounts.as(comp, saveOnly, "eng2");
        postAs(owner, PPAP + "/" + ppap + "/decide", "{\"rowVer\":0,\"decision\":\"REJECTED\",\"decidedOn\":\"" + today + "\"}")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PLM_PPAP_STATE"));
        postAs(owner, PPAP + "/" + ppap + "/submit", "{\"rowVer\":" + ver("plm_ppap", "ppap_id", ppap) + "}").andExpect(status().isOk());
        postAs(eng2, PPAP + "/" + ppap + "/decide", "{\"rowVer\":" + ver("plm_ppap", "ppap_id", ppap)
                + ",\"decision\":\"REJECTED\",\"decidedOn\":\"" + today + "\"}").andExpect(status().isForbidden());
        postAs(approver, PPAP + "/" + ppap + "/decide", "{\"rowVer\":" + ver("plm_ppap", "ppap_id", ppap)
                + ",\"decision\":\"REJECTED\",\"decidedOn\":\"" + today + "\",\"note\":\"치수 미달\"}").andExpect(status().isOk());
        // 거절은 ECO 고객 승인을 건드리지 않는다
        assertThat(accounts.query(comp, "select cust_approval_status from plm_eco where eco_id = ?", String.class, eco)).isEqualTo("NA");
        postAs(eng2, PPAP + "/" + ppap + "/reopen", "{\"rowVer\":" + ver("plm_ppap", "ppap_id", ppap) + "}").andExpect(status().isOk());
        mvc.perform(get(ECO + "/" + eco + "/ppap").with(owner))
                .andExpect(jsonPath("$.data.ppap.status").value("DRAFT"))
                .andExpect(jsonPath("$.data.ppap.decidedOn").doesNotExist());
        // 직접 SQL: 작성 중에서 바로 승인 건너뛰기
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(comp,
                "update plm_ppap set status = 'APPROVED', decided_on = (now() at time zone 'Asia/Seoul')::date, decision_ref = 'x' where ppap_id = ?", ppap))))
                .contains("ck_plm_ppap_transition");
    }

    // ---------- PCN ----------

    @Test
    void PCN_통지_응답은_한_번이고_기한_지나면_표시된다() throws Exception {
        long eco = newEco(null);
        String d10 = LocalDate.parse(today).minusDays(10).toString();
        String d5 = LocalDate.parse(today).minusDays(5).toString();
        long p1 = data(postAs(owner, ECO + "/" + eco + "/pcns", "{\"custCd\":\"C1\",\"notifiedOn\":\"" + d10 + "\",\"responseDue\":\"" + d5
                + "\",\"note\":\"도금 변경 통지\"}"));
        long p2 = data(postAs(owner, ECO + "/" + eco + "/pcns", "{\"custCd\":\"C2\",\"notifiedOn\":\"" + today + "\",\"responseDue\":\"" + today + "\"}"));
        mvc.perform(get(ECO + "/" + eco + "/pcns").with(owner))
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].pcnNo").value(Matchers.matchesPattern("^PCN\\d{4}-0001$")))
                .andExpect(jsonPath("$.data[0].overdue").value(true))
                .andExpect(jsonPath("$.data[1].overdue").value(false));
        // 같은 고객 열린 통지 두 번 / 기한 < 통지일 / 미래 통지일 / 판매 거래처 아님
        postAs(owner, ECO + "/" + eco + "/pcns", "{\"custCd\":\"C1\",\"notifiedOn\":\"" + today + "\",\"responseDue\":\"" + today + "\"}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].code").value("PCN_OPEN"));
        postAs(owner, ECO + "/" + eco + "/pcns", "{\"custCd\":\"C1\",\"notifiedOn\":\"" + today + "\",\"responseDue\":\"" + d5 + "\"}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].code").value("BEFORE_NOTIFIED"));
        postAs(owner, ECO + "/" + eco + "/pcns", "{\"custCd\":\"C1\",\"notifiedOn\":\"" + LocalDate.parse(today).plusDays(1)
                + "\",\"responseDue\":\"" + LocalDate.parse(today).plusDays(9) + "\"}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].code").value("FUTURE"));
        // 응답: 통지일 앞 / 정상 / 두 번째 → 409
        postAs(owner, PCN + "/" + p1 + "/response", "{\"rowVer\":0,\"status\":\"APPROVED\",\"respondedOn\":\"" + LocalDate.parse(d10).minusDays(1) + "\"}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].code").value("BEFORE_NOTIFIED"));
        postAs(owner, PCN + "/" + p1 + "/response", "{\"rowVer\":0,\"status\":\"APPROVED\",\"respondedOn\":\"" + today + "\",\"note\":\"승인\"}")
                .andExpect(status().isOk());
        postAs(owner, PCN + "/" + p1 + "/response", "{\"rowVer\":1,\"status\":\"REJECTED\",\"respondedOn\":\"" + today + "\"}")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PLM_PCN_STATE"));
        postAs(owner, PCN + "/" + p2 + "/response", "{\"rowVer\":0,\"status\":\"MAYBE\",\"respondedOn\":\"" + today + "\"}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("status"));
        // 응답 뒤 같은 고객 새 통지는 된다(열린 통지 하나 규칙)
        postAs(owner, ECO + "/" + eco + "/pcns", "{\"custCd\":\"C1\",\"notifiedOn\":\"" + today + "\",\"responseDue\":\"" + today + "\"}")
                .andExpect(status().isOk());
        // 직접 SQL: 응답 뒤 고치기·통지일 고치기
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(comp, "update plm_pcn set status = 'REJECTED' where pcn_id = ?", p1))))
                .contains("ck_plm_pcn_transition");
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(comp, "update plm_pcn set notified_on = notified_on - 1 where pcn_id = ?", p2))))
                .contains("ck_plm_pcn_transition");
        // 취소된 지시에는 통지 기록 불가
        postAs(owner, ECO + "/" + eco + "/cancel", "{\"rowVer\":" + ecoVer(eco) + ",\"reason\":\"보류\"}").andExpect(status().isOk());
        postAs(owner, ECO + "/" + eco + "/pcns", "{\"custCd\":\"C2\",\"notifiedOn\":\"" + today + "\",\"responseDue\":\"" + today + "\"}")
                .andExpect(status().isConflict());
    }

    // ---------- A사로 B사 ----------

    @Test
    void B_A사로_B사_PPAP_PCN_일괄_대체_첨부에_손댈_수_없다() throws Exception {
        String compB = accounts.newComp("PL4B");
        accounts.addPlant(compB, "P1", "1공장");
        seed(compB);
        long userB = accounts.newUser(compB, "engB", PW);
        accounts.grant(compB, userB, "ENG", "plm-eco", "SEARCH", "SAVE", "APPROVE");
        RequestPostProcessor b = accounts.as(compB, userB, "engB");
        long ecoB = data(mvc.perform(json(post(ECO), "{\"title\":\"B\",\"reasonCd\":\"COST\",\"effectiveDate\":\"" + today
                + "\",\"custApprovalReqYn\":\"Y\",\"custCd\":\"C1\"}").with(b)));
        long ppapB = data(mvc.perform(json(post(ECO + "/" + ecoB + "/ppap"), "{}").with(b)));
        long pcnB = data(mvc.perform(json(post(ECO + "/" + ecoB + "/pcns"), "{\"custCd\":\"C1\",\"notifiedOn\":\"" + today
                + "\",\"responseDue\":\"" + today + "\"}").with(b)));
        long fileB = fileOf(compB, ecoB, "b.pdf");

        // A사 사용자로 B사 지시·패키지·통지
        mvc.perform(get(ECO + "/" + ecoB + "/ppap").with(owner)).andExpect(status().isNotFound());
        mvc.perform(get(ECO + "/" + ecoB + "/pcns").with(owner)).andExpect(status().isNotFound());
        postAs(owner, ECO + "/" + ecoB + "/ppap", "{\"custCd\":\"C1\"}").andExpect(status().isNotFound());
        postAs(owner, ECO + "/" + ecoB + "/pcns", "{\"custCd\":\"C1\",\"notifiedOn\":\"" + today + "\",\"responseDue\":\"" + today + "\"}")
                .andExpect(status().isNotFound());
        putAs(owner, PPAP + "/" + ppapB, "{\"rowVer\":0}").andExpect(status().isNotFound());
        postAs(owner, PPAP + "/" + ppapB + "/submit", "{\"rowVer\":0}").andExpect(status().isNotFound());
        postAs(approver, PPAP + "/" + ppapB + "/decide", "{\"rowVer\":0,\"decision\":\"APPROVED\",\"ref\":\"X\",\"decidedOn\":\"" + today + "\"}")
                .andExpect(status().isNotFound());
        postAs(owner, PCN + "/" + pcnB + "/response", "{\"rowVer\":0,\"status\":\"ACK\",\"respondedOn\":\"" + today + "\"}")
                .andExpect(status().isNotFound());
        mvc.perform(get(ECO + "/" + ecoB + "/replace-preview?fromItemCd=RM&toItemCd=RM3").with(owner)).andExpect(status().isNotFound());
        postAs(owner, ECO + "/" + ecoB + "/replace", "{\"rowVer\":0,\"fromItemCd\":\"RM\",\"toItemCd\":\"RM3\",\"bomIds\":[1]}")
                .andExpect(status().isNotFound());

        // A사 지시에 B사 첨부·B사 고객
        long eco = newEco(null);
        long ppap = data(postAs(owner, ECO + "/" + eco + "/ppap", "{\"custCd\":\"C1\"}"));
        putAs(owner, PPAP + "/" + ppap, "{\"rowVer\":" + ver("plm_ppap", "ppap_id", ppap) + ",\"elems\":[{\"elemNo\":18,\"reqCd\":\"S\",\"status\":\"DONE\",\"fileId\":"
                + fileB + "}]}").andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("fileId"));
        long custB = id(compB, "select cust_id from mst_cust where cust_cd = 'C1'");
        // 직접 SQL(A사 범위): B사 고객으로 패키지·통지, B사 지시로 통지, B사 패키지에 요소
        String[] sqls = {
            "insert into plm_pcn (comp_cd, pcn_no, eco_id, cust_id, notified_on, response_due, created_by, updated_by) values (?, 'X1', ?, ?, (now() at time zone 'Asia/Seoul')::date, (now() at time zone 'Asia/Seoul')::date, 't', 't')",
            "insert into plm_pcn (comp_cd, pcn_no, eco_id, cust_id, notified_on, response_due, created_by, updated_by) values (?, 'X2', ?, (select cust_id from mst_cust where cust_cd = 'C2'), (now() at time zone 'Asia/Seoul')::date, (now() at time zone 'Asia/Seoul')::date, 't', 't')",
        };
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(comp, sqls[0], comp, eco, custB)))).contains("fk_plm_pcn_ref_comp");
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(comp, sqls[1], comp, ecoB)))).contains("fk_plm_pcn_ref_comp");
        long eco2 = newEco(null);
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(comp,
                "insert into plm_ppap (comp_cd, eco_id, cust_id, created_by, updated_by) values (?, ?, ?, 't', 't')", comp, eco2, custB))))
                .contains("fk_plm_ppap_ref_comp");
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(comp,
                "insert into plm_ppap_elem (comp_cd, ppap_id, elem_no, req_cd, created_by, updated_by) values (?, ?, 1, 'S', 't', 't')",
                comp, ppapB)))).contains("fk_plm_ppap_elem_comp");
        // B사 comp_cd 로 넣기 — RLS WITH CHECK 또는 트리거
        assertThat(catchThrowable(() -> accounts.exec(comp,
                "insert into plm_pcn (comp_cd, pcn_no, eco_id, cust_id, notified_on, response_due, created_by, updated_by) values (?, 'X3', ?, ?, (now() at time zone 'Asia/Seoul')::date, (now() at time zone 'Asia/Seoul')::date, 't', 't')",
                compB, ecoB, custB))).isNotNull();
        assertThat(accounts.query(compB, "select count(*) from plm_pcn", Integer.class)).isEqualTo(1);
        // B사 데이터는 그대로
        assertThat(accounts.query(compB, "select status from plm_ppap where ppap_id = ?", String.class, ppapB)).isEqualTo("DRAFT");
        assertThat(accounts.query(compB, "select cust_approval_status from plm_eco where eco_id = ?", String.class, ecoB)).isEqualTo("PENDING");
    }
}
