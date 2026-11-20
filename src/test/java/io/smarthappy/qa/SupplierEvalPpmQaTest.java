package io.smarthappy.qa;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;

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

import io.smarthappy.support.PostgresTestBase;
import io.smarthappy.support.TestAccounts;

/**
 * QA — QMS 협력사 품질 계약 → SRM 입고 PPM(R4a, EvalService.recvPpm) 경계를 실제 QMS 데이터로. 모듈 시험(EvalPpmTest)은 계약을 바꿔 끼워 보므로
 * 여기서는 진짜 수입검사·NCR → 평가 생성까지 잇는다: 손 검산 PPM·근거 / QQ-1 결정(NCR 기준)과 PPM 식의 만남(QE-1 사용자 결정 = 무효 LOT 수량도 분모에) /
 * 366일 기간·첫날 0시 경계(QMS 1년 상한과 맞물림) / A사로 B사 / 확정 평가는 QMS 값이 바뀌어도 그대로(재계산 거부).
 */
@AutoConfigureMockMvc
class SupplierEvalPpmQaTest extends PostgresTestBase {

    static final String PW = "Qa-ledger-hold-41";
    static final String I = "/api/qms/insps";
    static final String N = "/api/qms/ncrs";
    static final String T = "/api/srm/eval-tmpls";
    static final String E = "/api/srm/evals";

    @Autowired
    MockMvc mvc;

    @Autowired
    TestAccounts accounts;

    String comp;
    RequestPostProcessor me;
    LocalDate today;
    long defect;

    @BeforeEach
    void setUp() throws Exception {
        comp = accounts.newComp("QE");
        today = accounts.query(comp, "select " + TestAccounts.COMP_TODAY, LocalDate.class);
        me = setUpCompany(comp);
        defect = accounts.query(comp, "select defect_id from qms_defect where defect_cd = 'QE-D'", Long.class);
    }

    /** 회사 하나: 사용자·권한, QMS 수입검사 확정본(QE-RM × IQC), 불량 코드, SRM 평가 템플릿(사람 10점 60 + PPM AUTO 20점 40) */
    RequestPostProcessor setUpCompany(String c) throws Exception {
        long u = accounts.newUser(c, "qa1", PW);
        accounts.grant(c, u, "QA", "qms-insp-plan", "SEARCH", "SAVE", "APPROVE", "DELETE");
        accounts.grant(c, u, "QA", "qms-insp", "SEARCH", "SAVE", "DELETE");
        accounts.grant(c, u, "QA", "qms-ncr", "SEARCH", "SAVE", "DELETE");
        accounts.grant(c, u, "QA", "srm-eval-tmpl", "SEARCH", "SAVE", "APPROVE");
        accounts.grant(c, u, "QA", "srm-eval", "SEARCH", "SAVE", "APPROVE");
        RequestPostProcessor who = accounts.as(c, u, "qa1");
        accounts.exec(c, """
                insert into mst_item (comp_cd, item_cd, item_nm, item_type, proc_type, unit_cd, created_by, updated_by)
                values (?, 'QE-RM', 'QA 원자재', 'RM', 'BUY', 'EA', 't', 't')""", c);
        accounts.exec(c, "insert into mst_proc (comp_cd, proc_cd, proc_nm, proc_cat, created_by, updated_by) "
                + "values (?, 'IQC', '수입 검사', 'INSPECT', 't', 't')", c);
        long ch = accounts.query(c, """
                insert into qms_char (comp_cd, char_type, proc_id, char_no, char_nm, data_type, scale, created_by, updated_by)
                select ?, 'PROCESS', proc_id, '1', '녹 없음', 'ATTRIBUTE', 0, 't', 't' from mst_proc where proc_cd = 'IQC'
                returning char_id""", Long.class, c);
        accounts.exec(c, "insert into qms_sampling_plan (comp_cd, plan_cd, plan_nm, method, fixed_n, ac, re, created_by, updated_by) "
                + "values (?, 'S5', '고정 5', 'FIXED', 5, 0, 1, 't', 't')", c);
        accounts.exec(c, "insert into qms_defect (comp_cd, defect_cd, defect_nm, cat_cd, created_by, updated_by) "
                + "values (?, 'QE-D', '녹', 'APPEARANCE', 't', 't')", c);
        long plan = id(send(who, post("/api/qms/insp-plans"), "{\"inspType\":\"RECEIVE\",\"itemCd\":\"QE-RM\",\"classCd\":null,\"procCd\":\"IQC\"}"));
        send(who, put("/api/qms/insp-plans/" + plan + "/lines?rowVer=" + planVer(c, plan)), "{\"lines\":[{\"rowKey\":\"a\",\"lineId\":null,"
                + "\"lineNo\":10,\"charId\":" + ch + ",\"samplingPlanCd\":\"S5\",\"gaugeClass\":\"VISUAL\",\"freqType\":null,\"freqN\":null,"
                + "\"methodNm\":\"육안\",\"reactionPlan\":\"격리\"}]}").andExpect(status().isOk());
        send(who, post("/api/qms/insp-plans/" + plan + "/release"), "{\"rowVer\":" + planVer(c, plan) + "}").andExpect(status().isOk());
        long t = id(send(who, post(T), "{\"tmplCd\":\"QPPM\",\"tmplNm\":\"QA PPM\",\"validFrom\":\"2020-01-01\",\"items\":["
                + "{\"itemNm\":\"불량 대응\",\"maxScore\":\"10\",\"weight\":\"60\"},"
                + "{\"itemNm\":\"입고 PPM\",\"maxScore\":\"20\",\"weight\":\"40\",\"inputType\":\"AUTO\",\"metricCd\":\"RECV_PPM\",\"bands\":["
                + "{\"upperPpm\":\"0\",\"scorePct\":\"100\"},{\"upperPpm\":\"500\",\"scorePct\":\"80\"},{\"upperPpm\":\"2000\",\"scorePct\":\"50\"},"
                + "{\"scorePct\":\"0\"}]}],"
                + "\"grades\":[{\"gradeCd\":\"A\",\"minScore\":\"90\"},{\"gradeCd\":\"C\",\"minScore\":\"0\"}]}"));
        int tv = JsonPath.read(mvc.perform(get(T + "/" + t).with(who)).andReturn().getResponse().getContentAsString(), "$.data.tmpl.rowVer");
        send(who, post(T + "/" + t + "/release"), "{\"rowVer\":" + tv + "}").andExpect(status().isOk());
        return who;
    }

    int planVer(String c, long plan) {
        return accounts.query(c, "select row_ver from qms_insp_plan where insp_plan_id = ?", Integer.class, plan);
    }

    ResultActions send(RequestPostProcessor who, MockHttpServletRequestBuilder b, String body) throws Exception {
        return mvc.perform(b.with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body).with(who));
    }

    ResultActions send(MockHttpServletRequestBuilder b, String body) throws Exception {
        return send(me, b, body);
    }

    static long id(ResultActions r) throws Exception {
        MvcResult m = r.andExpect(status().isOk()).andReturn();
        return ((Number) JsonPath.read(m.getResponse().getContentAsString(), "$.data")).longValue();
    }

    String body(String url) throws Exception {
        return mvc.perform(get(url).with(me)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    long supplier(String c, String cd) {
        long id = accounts.query(c, """
                insert into mst_cust (comp_cd, cust_cd, cust_nm, cust_type, created_by, updated_by)
                values (?, ?, ?, 'PURCHASE', 't', 't') returning cust_id""", Long.class, c, cd, cd + " 협력사");
        accounts.exec(c, "insert into srm_supplier (cust_id, comp_cd, currency_cd, created_by, updated_by) values (?, ?, 'KRW', 't', 't')", id, c);
        return id;
    }

    void lot(String no, long supplier, String qty) {
        accounts.exec(comp, """
                insert into lot_master (comp_cd, lot_no, item_id, origin, qty_created, unit_cd, supplier_cust_id, supplier_lot_no, occurred_at,
                                        created_by, updated_by)
                select ?, ?, item_id, 'RECEIVED', ?::numeric, 'EA', ?, 'S' || ?, now(), 't', 't' from mst_item where item_cd = 'QE-RM'""",
                comp, no, qty, supplier, no);
    }

    /** 수입검사 → 5개 측정 → 판정 확정. [검사 id, 검사 번호] */
    Object[] judged(String lotNo, boolean ok) throws Exception {
        long id = id(send(post(I), "{\"lotNo\":\"" + lotNo + "\",\"inspType\":\"RECEIVE\",\"procCd\":\"IQC\"}"));
        long line = accounts.query(comp, "select insp_line_id from qms_insp_line where insp_id = ?", Long.class, id);
        StringBuilder a = new StringBuilder();
        for (int i = 1; i <= 5; i++) {
            a.append(i == 1 ? "" : ",").append("{\"sampleNo\":").append(i).append(",\"okYn\":\"").append(!ok && i == 1 ? "N" : "Y").append("\"}");
        }
        send(post(I + "/" + id + "/meas"), "{\"inspLineId\":" + line + ",\"entries\":[" + a + "]}").andExpect(status().isOk());
        send(post(I + "/" + id + "/judge"), "{\"rowVer\":" + inspVer(id) + "}").andExpect(status().isOk());
        return new Object[] {id, accounts.query(comp, "select insp_no from qms_insp where insp_id = ?", String.class, id)};
    }

    int inspVer(long id) {
        return accounts.query(comp, "select row_ver from qms_insp where insp_id = ?", Integer.class, id);
    }

    long ncr(String lotNo, String inspNo, String qty) throws Exception {
        return id(send(post(N), "{\"lotNo\":\"" + lotNo + "\",\"source\":\"" + (inspNo == null ? "CLAIM" : "INSP") + "\",\"inspNo\":"
                + (inspNo == null ? "null" : "\"" + inspNo + "\"") + ",\"defectId\":" + defect + ",\"ncQty\":" + qty + ",\"description\":\"녹\"}"));
    }

    ResultActions createEval(RequestPostProcessor who, String custCd, LocalDate from, LocalDate to) throws Exception {
        return send(who, post(E), "{\"custCd\":\"" + custCd + "\",\"tmplCd\":\"QPPM\",\"periodFrom\":\"" + from + "\",\"periodTo\":\"" + to + "\"}");
    }

    /** PPM 항목(두 번째) — "autoYn score rawValue basis" */
    String ppm(long ev) throws Exception {
        String b = body(E + "/" + ev);
        return JsonPath.read(b, "$.data.scores[1].autoYn") + " " + JsonPath.read(b, "$.data.scores[1].score") + " "
                + JsonPath.read(b, "$.data.scores[1].rawValue") + " " + JsonPath.read(b, "$.data.scores[1].basis");
    }

    void act(long ev, String action) throws Exception {
        send(post(E + "/" + ev + "/" + action), "{\"rowVer\":" + JsonPath.read(body(E + "/" + ev), "$.data.eval.rowVer") + "}")
                .andExpect(status().isOk());
    }

    // ------------------------------------------------------------------ 시험

    /** 실제 QMS 데이터 → PPM: LOT 3000·1000 NG 에 NCR 1·1(처리 중 2), 검사 없는 클레임 NCR 2 → 2/4000 = 500 PPM → 80% × 20 = 16, 근거 PPM:4000:2:2:2 */
    @Test
    void 실제_수입검사_NCR_로_PPM_손검산() throws Exception {
        long s = supplier(comp, "QE-V1");
        lot("QE-A", s, "3000");
        lot("QE-B", s, "1000");
        ncr("QE-A", (String) judged("QE-A", false)[1], "1");
        ncr("QE-B", (String) judged("QE-B", false)[1], "1");
        ncr("QE-A", null, "2");
        long ev = id(createEval(me, "QE-V1", today.minusDays(10), today));
        assertThat(ppm(ev)).isEqualTo("Y 16 500 PPM:4000:2:2:2");
    }

    /**
     * QE-1(사용자 결정 = 무효 검사 LOT 수량도 분모에, docs/29_P3 16.1): 무효 검사의 NCR 불량 6 이 남는 LOT(50)은 그 LOT 수량도 분모에 들어간다 —
     * ① 그 협력사에 다른 검사가 없어도 50 / 6 → 120,000 PPM(자료 없음이 아님) ② 다른 LOT 검사(100, OK)가 더해지면 150 / 6 → 40,000 PPM.
     */
    @Test
    void 무효_검사의_NCR_불량과_PPM_식() throws Exception {
        long s = supplier(comp, "QE-V2");
        lot("QE-V", s, "50");
        Object[] ng = judged("QE-V", false);
        ncr("QE-V", (String) ng[1], "6");
        send(post(I + "/" + ng[0] + "/void"), "{\"rowVer\":" + inspVer((Long) ng[0]) + ",\"reason\":\"시료 바뀜\"}").andExpect(status().isOk());
        long ev = id(createEval(me, "QE-V2", today.minusDays(10), today));
        assertThat(ppm(ev)).as("① 무효 LOT 50 이 분모 — 50 / 6").isEqualTo("Y 0 120000 PPM:50:6:1:0");
        lot("QE-OK", s, "100");
        judged("QE-OK", true);
        act(ev, "recalc");
        assertThat(ppm(ev)).as("② 분모 150(무효 LOT 50 + OK LOT 100) / 분자 6").isEqualTo("Y 0 40000 PPM:150:6:1:0");
    }

    /** 기간 366일(SRM 상한) — QMS 1년 상한 안이라 값이 나온다. 첫날 0시(회사 시간대)는 들고, 그 1분 전은 빠진다 */
    @Test
    void 기간_366일과_첫날_0시_경계() throws Exception {
        long s = supplier(comp, "QE-V3");
        lot("QE-F", s, "1000");
        lot("QE-G", s, "1000");
        long f = (Long) judged("QE-F", false)[0];
        long g = (Long) judged("QE-G", true)[0];
        LocalDate from = today.minusDays(365);
        SupplierQualityQaTest.ownerExec("update qms_insp set judged_at = (?::date)::timestamp at time zone 'Asia/Seoul' where insp_id = ?",
                from.toString(), f);
        SupplierQualityQaTest.ownerExec("update qms_insp set judged_at = (?::date)::timestamp at time zone 'Asia/Seoul' - interval '1 minute' "
                + "where insp_id = ?", from.toString(), g);
        long ev = id(createEval(me, "QE-V3", from, today));
        assertThat(ppm(ev)).as("F(첫날 0시, NG·NCR 없음)만 — 검사 1000, 불량 0").isEqualTo("Y 20 0 PPM:1000:0:0:0");
        createEval(me, "QE-V3", from.minusDays(1), today).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].code").value("RANGE_TOO_LONG"));
    }

    /** A사로 B사: B사에만 있는 협력사 코드 → 칸 오류, A사 같은 코드 협력사는 B사 QMS 값을 보지 않는다(PPM_NONE) */
    @Test
    void A사로_B사_협력사와_QMS_값은_보이지_않는다() throws Exception {
        String b = accounts.newComp("QF");
        RequestPostProcessor bMe = setUpCompany(b);
        long bs = supplier(b, "QE-B1");
        String savedComp = comp;
        RequestPostProcessor savedMe = me;
        comp = b;
        me = bMe;
        defect = accounts.query(b, "select defect_id from qms_defect where defect_cd = 'QE-D'", Long.class);
        lot("QE-X", bs, "500");
        ncr("QE-X", (String) judged("QE-X", false)[1], "5");
        assertThat(ppm(id(createEval(bMe, "QE-B1", today.minusDays(10), today)))).isEqualTo("Y 0 10000 PPM:500:5:1:0");
        comp = savedComp;
        me = savedMe;
        createEval(me, "QE-B1", today.minusDays(10), today).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("custCd"));
        supplier(comp, "QE-B1");
        assertThat(ppm(id(createEval(me, "QE-B1", today.minusDays(10), today)))).isEqualTo("N null null PPM_NONE");
    }

    /** 확정한 평가는 그 뒤 QMS 값이 바뀌어도(새 NCR) 그대로 — 재계산 거부, 점수·근거 유지(QQ-1 의 소급 변경은 확정 전까지만) */
    @Test
    void 확정_평가는_QMS_값이_바뀌어도_그대로() throws Exception {
        long s = supplier(comp, "QE-V4");
        lot("QE-C", s, "2000");
        String insp = (String) judged("QE-C", false)[1];
        ncr("QE-C", insp, "1");
        long ev = id(createEval(me, "QE-V4", today.minusDays(10), today));
        assertThat(ppm(ev)).isEqualTo("Y 16 500 PPM:2000:1:1:0");
        long q = ((Number) JsonPath.read(body(E + "/" + ev), "$.data.scores[0].itemId")).longValue();
        send(put(E + "/" + ev + "/scores"), "{\"rowVer\":" + JsonPath.read(body(E + "/" + ev), "$.data.eval.rowVer")
                + ",\"scores\":[{\"itemId\":" + q + ",\"score\":\"10\"}]}").andExpect(status().isOk());
        act(ev, "confirm");
        ncr("QE-C", insp, "9");
        MvcResult r = send(post(E + "/" + ev + "/recalc"), "{\"rowVer\":" + JsonPath.read(body(E + "/" + ev), "$.data.eval.rowVer") + "}")
                .andReturn();
        assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isBetween(400, 499);
        assertThat(ppm(ev)).isEqualTo("Y 16 500 PPM:2000:1:1:0");
        assertThat((String) JsonPath.read(body(E + "/" + ev), "$.data.eval.status")).isEqualTo("CONFIRMED");
    }
}
