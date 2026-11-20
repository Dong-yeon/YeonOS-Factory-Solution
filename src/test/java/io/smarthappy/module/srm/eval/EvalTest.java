package io.smarthappy.module.srm.eval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.jayway.jsonpath.JsonPath;

import io.smarthappy.platform.common.db.PgErrors;
import io.smarthappy.support.PostgresTestBase;
import io.smarthappy.support.TestAccounts;

/**
 * SRM S6 협력사 평가 (docs/31 4.6) — 템플릿 리비전(가중치 100·등급 0 필수·확정 불변·기간 겹침·자동 마감·작성 중 하나),
 * 평가(AUTO ACK_RATE·CERT_VALID 계산, 손 검산 총점·등급·과락·HOLD 제안, 확정 불변, 중복·미래·템플릿 없음),
 * 직접 SQL(트리거가 총점 다시 계산), A사로 B사, 권한.
 */
@AutoConfigureMockMvc
class EvalTest extends PostgresTestBase {

    private static final String PW = "Maple-anchor-63";
    private static final String T = "/api/srm/eval-tmpls";
    private static final String E = "/api/srm/evals";
    private static final String PO = "/api/srm/pos";
    private static final String ACK = "/api/srm/acks";

    @Autowired
    MockMvc mvc;

    @Autowired
    TestAccounts accounts;

    String comp;
    long buyer;
    RequestPostProcessor me;
    LocalDate today;
    long vCust;

    @BeforeEach
    void setUp() {
        comp = accounts.newComp("EV");
        buyer = accounts.newUser(comp, "buyer1", PW);
        grantAll(comp, buyer);
        me = accounts.as(comp, buyer, "buyer1");
        today = LocalDate.now(ZoneId.of("Asia/Seoul"));
        vCust = seed(comp);
    }

    private void grantAll(String c, long user) {
        accounts.grant(c, user, "PUR", "srm-eval-tmpl", "SEARCH", "SAVE", "APPROVE");
        accounts.grant(c, user, "PUR", "srm-eval", "SEARCH", "SAVE", "APPROVE");
        accounts.grant(c, user, "PUR", "srm-po", "SEARCH", "SAVE", "APPROVE");
        accounts.grant(c, user, "PUR", "srm-ack", "SEARCH", "SAVE");
    }

    private long seed(String c) {
        accounts.addPlant(c, "P1", "1공장");
        accounts.exec(c, "insert into sys_policy (comp_cd, policy_key, policy_value, created_by, updated_by) values (?, 'SRM_PO_APPROVAL', 'N', 't', 't')", c);
        accounts.exec(c, """
                insert into mst_item (comp_cd, item_cd, item_nm, item_type, proc_type, unit_cd, lot_ctrl_yn, created_by, updated_by)
                values (?, 'BOLT-M8', '볼트', 'RM', 'BUY', 'EA', 'N', 't', 't')""", c);
        long id = accounts.query(c, """
                insert into mst_cust (comp_cd, cust_cd, cust_nm, cust_type, created_by, updated_by)
                values (?, 'V100', 'V100 상사', 'PURCHASE', 't', 't') returning cust_id""", Long.class, c);
        accounts.exec(c, "insert into srm_supplier (cust_id, comp_cd, currency_cd, created_by, updated_by) values (?, ?, 'KRW', 't', 't')", id, c);
        return id;
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

    private static long id(ResultActions r) throws Exception {
        MvcResult m = r.andExpect(status().isOk()).andReturn();
        return ((Number) JsonPath.read(m.getResponse().getContentAsString(), "$.data")).longValue();
    }

    private String body(RequestPostProcessor who, String url) throws Exception {
        return mvc.perform(get(url).with(who)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    private int ver(String url) throws Exception {
        String b = body(me, url);
        Integer v = JsonPath.read(b, url.startsWith(T) ? "$.data.tmpl.rowVer" : "$.data.eval.rowVer");
        return v;
    }

    private ResultActions act(RequestPostProcessor who, String base, long id, String action, String reason) throws Exception {
        return postAs(who, base + "/" + id + "/" + action, "{\"rowVer\":" + ver(base + "/" + id)
                + (reason == null ? "" : ",\"reason\":\"" + reason + "\"") + "}");
    }

    /** 품질(사람, 10점, 가중 40, 5 미만 과락) + 회신율(AUTO ACK_RATE, 5점, 35) + 인증(AUTO CERT_VALID, 100점, w3) */
    private static String items(String w3) {
        return "[{\"sectionNm\":\"품질\",\"itemNm\":\"불량 대응\",\"maxScore\":\"10\",\"weight\":\"40\",\"failBelow\":\"5\"},"
                + "{\"sectionNm\":\"납기\",\"itemNm\":\"회신율\",\"maxScore\":\"5\",\"weight\":\"35\",\"inputType\":\"AUTO\",\"metricCd\":\"ACK_RATE\"},"
                + "{\"sectionNm\":\"체계\",\"itemNm\":\"인증 유효\",\"maxScore\":\"100\",\"weight\":\"" + w3 + "\",\"inputType\":\"AUTO\",\"metricCd\":\"CERT_VALID\"}]";
    }

    private static final String GRADES = "[{\"gradeCd\":\"A\",\"minScore\":\"90\"},{\"gradeCd\":\"B\",\"minScore\":\"80\"},"
            + "{\"gradeCd\":\"C\",\"minScore\":\"60\"},{\"gradeCd\":\"D\",\"minScore\":\"0\",\"suggestHoldYn\":\"Y\"}]";

    private long template(RequestPostProcessor who, String cd, String from, String w3, String grades) throws Exception {
        return id(postAs(who, T, "{\"tmplCd\":\"" + cd + "\",\"tmplNm\":\"" + cd + " 평가\",\"validFrom\":\"" + from + "\",\"items\":"
                + items(w3) + ",\"grades\":" + grades + "}"));
    }

    private long released(RequestPostProcessor who, String cd) throws Exception {
        long t = template(who, cd, "2020-01-01", "25", GRADES);
        int v = JsonPath.read(body(who, T + "/" + t), "$.data.tmpl.rowVer");
        postAs(who, T + "/" + t + "/release", "{\"rowVer\":" + v + "}").andExpect(status().isOk());
        return t;
    }

    private ResultActions evalCreate(RequestPostProcessor who, String cust, String tmpl, String from, String to) throws Exception {
        return postAs(who, E, "{\"custCd\":\"" + cust + "\",\"tmplCd\":\"" + tmpl + "\",\"periodFrom\":\"" + from + "\",\"periodTo\":\"" + to + "\"}");
    }

    private ResultActions scores(RequestPostProcessor who, long ev, String json) throws Exception {
        int v = JsonPath.read(body(who, E + "/" + ev), "$.data.eval.rowVer");
        return putAs(who, E + "/" + ev + "/scores", "{\"rowVer\":" + v + ",\"scores\":" + json + "}");
    }

    private long itemId(long ev, int idx) throws Exception {
        return ((Number) JsonPath.read(body(me, E + "/" + ev), "$.data.scores[" + idx + "].itemId")).longValue();
    }

    /** 발행 발주(오늘) — 회신 여부를 정할 수 있게 */
    private long issuedPo() throws Exception {
        long id = id(postAs(me, PO, "{\"custCd\":\"V100\",\"plantCd\":\"P1\",\"lines\":[{\"itemCd\":\"BOLT-M8\",\"qty\":\"10\",\"unitPrice\":\"1\",\"dueDate\":\""
                + today.plusDays(20) + "\"}]}"));
        for (String a : List.of("confirm", "issue")) {
            int v = JsonPath.read(body(me, PO + "/" + id), "$.data.po.rowVer");
            postAs(me, PO + "/" + id + "/" + a, "{\"rowVer\":" + v + "}").andExpect(status().isOk());
        }
        return id;
    }

    // ---------- 템플릿 ----------

    @Test
    void 템플릿_확정은_가중치합100_등급0_필수_확정본_불변_새리비전은_복사_이전_끝없는_확정본은_전날로_마감() throws Exception {
        long t = template(me, "q-sq", "2020-01-01", "20", "[{\"gradeCd\":\"A\",\"minScore\":\"90\"}]");
        String d = body(me, T + "/" + t);
        assertThat((String) JsonPath.read(d, "$.data.tmpl.tmplCd")).isEqualTo("Q-SQ");
        assertThat((String) JsonPath.read(d, "$.data.tmpl.weightSum")).isEqualTo("95");
        act(me, T, t, "release", null).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[?(@.field == 'items')].code").value(Matchers.contains("SRM_EVAL_WEIGHT")))
                .andExpect(jsonPath("$.errors[?(@.field == 'grades')].code").value(Matchers.contains("SRM_EVAL_GRADE0")));
        // 직접 SQL 로 확정 → 트리거(가중치 95)
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(comp,
                "update srm_eval_tmpl set status = 'RELEASED', released_by = 't', released_at = now() where tmpl_id = ?", t))))
                .contains("ck_srm_eval_tmpl_weight");
        putAs(me, T + "/" + t, "{\"tmplNm\":\"SQ 평가\",\"validFrom\":\"2020-01-01\",\"rowVer\":" + ver(T + "/" + t) + ",\"items\":"
                + items("25") + ",\"grades\":" + GRADES + "}").andExpect(status().isOk());
        act(me, T, t, "release", null).andExpect(status().isOk());
        // 확정본: 수정 SRM_STATE, 직접 SQL 항목·이름 수정 막힘
        putAs(me, T + "/" + t, "{\"tmplNm\":\"x\",\"validFrom\":\"2020-01-01\",\"rowVer\":" + ver(T + "/" + t) + ",\"items\":[],\"grades\":[]}")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SRM_STATE"));
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(comp,
                "update srm_eval_item set weight = 50 where tmpl_id = ? and item_no = 1", t)))).contains("ck_srm_eval_tmpl_child_draft");
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(comp,
                "delete from srm_eval_grade where tmpl_id = ?", t)))).contains("ck_srm_eval_tmpl_child_draft");
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(comp,
                "update srm_eval_tmpl set tmpl_nm = 'x' where tmpl_id = ?", t)))).contains("ck_srm_eval_tmpl_draft");
        // 새 리비전 = 복사, 두 번째는 작성 중이 이미 있음
        long r1 = id(postAs(me, T + "/" + t + "/new-rev", "{}"));
        String d1 = body(me, T + "/" + r1);
        assertThat((Integer) JsonPath.read(d1, "$.data.tmpl.rev")).isEqualTo(1);
        assertThat((List<?>) JsonPath.read(d1, "$.data.items")).hasSize(3);
        assertThat((List<?>) JsonPath.read(d1, "$.data.grades")).hasSize(4);
        postAs(me, T + "/" + t + "/new-rev", "{}").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SRM_EVAL_DRAFT_EXISTS"));
        // 같은 코드 신규 → DUPLICATE(새 리비전으로)
        postAs(me, T, "{\"tmplCd\":\"Q-SQ\",\"tmplNm\":\"x\",\"validFrom\":\"2020-01-01\"}").andExpect(jsonPath("$.errors[0].code").value("DUPLICATE"));
        // rev1 확정(시작 = 오늘) → rev0 은 어제로 마감
        act(me, T, r1, "release", null).andExpect(status().isOk());
        assertThat((String) JsonPath.read(body(me, T + "/" + t), "$.data.tmpl.validTo")).isEqualTo(today.minusDays(1).toString());
        // 시작일이 같거나 이른 새 리비전은 마감할 수 없어 겹침
        long r2 = id(postAs(me, T + "/" + r1 + "/new-rev", "{}"));
        putAs(me, T + "/" + r2, "{\"tmplNm\":\"SQ\",\"validFrom\":\"" + today.minusDays(3) + "\",\"rowVer\":" + ver(T + "/" + r2)
                + ",\"items\":" + items("25") + ",\"grades\":" + GRADES + "}").andExpect(status().isOk());
        act(me, T, r2, "release", null).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].code").value("SRM_EVAL_OVERLAP"));
        act(me, T, r2, "obsolete", null).andExpect(status().isOk());
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(comp,
                "update srm_eval_tmpl set status = 'DRAFT' where tmpl_id = ?", r2)))).contains("ck_srm_eval_tmpl_transition");
    }

    @Test
    void 템플릿_입력검사_행오류() throws Exception {
        postAs(me, T, "{\"tmplCd\":\"한글\",\"tmplNm\":\"\",\"validFrom\":\"2020-01-01\",\"validTo\":\"2019-01-01\",\"items\":["
                + "{\"itemNm\":\"a\",\"maxScore\":\"10\",\"weight\":\"101\",\"failBelow\":\"11\"},"
                + "{\"itemNm\":\"b\",\"maxScore\":\"0\",\"weight\":\"10.123\",\"inputType\":\"AUTO\"},"
                + "{\"itemNm\":\"c\",\"maxScore\":\"5\",\"weight\":\"10\",\"inputType\":\"AUTO\",\"metricCd\":\"NOPE\"}],\"grades\":["
                + "{\"gradeCd\":\"a\",\"minScore\":\"0\"},{\"gradeCd\":\"A\",\"minScore\":\"0.00\"},{\"gradeCd\":\"9X\",\"minScore\":\"101\"}]}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[?(@.field == 'tmplCd')].code").value(Matchers.contains("FORMAT")))
                .andExpect(jsonPath("$.errors[?(@.field == 'tmplNm')].code").value(Matchers.contains("REQUIRED")))
                .andExpect(jsonPath("$.errors[?(@.field == 'validTo')].code").value(Matchers.contains("INVALID_RANGE")))
                .andExpect(jsonPath("$.errors[?(@.rowKey == '0')].code").value(Matchers.containsInAnyOrder("RANGE", "SRM_EVAL_FAIL_ABOVE_MAX")))
                .andExpect(jsonPath("$.errors[?(@.rowKey == '1')].code").value(Matchers.containsInAnyOrder("POSITIVE", "SCALE", "REQUIRED")))
                .andExpect(jsonPath("$.errors[?(@.rowKey == '2')].code").value(Matchers.contains("CODE")))
                .andExpect(jsonPath("$.errors[?(@.rowKey == 'g1')].code").value(Matchers.containsInAnyOrder("DUPLICATE", "DUPLICATE")))
                .andExpect(jsonPath("$.errors[?(@.rowKey == 'g2')].code").value(Matchers.containsInAnyOrder("FORMAT", "RANGE")));
        assertThat(accounts.query(comp, "select count(*) from srm_eval_tmpl", Integer.class)).isZero();
    }

    // ---------- 평가 ----------

    @Test
    void 평가_AUTO_회신율과_인증_계산_손검산_총점_등급_과락_HOLD제안_확정불변() throws Exception {
        released(me, "SQ");
        // 발주 2건 발행, 1건만 기한 안 회신 → 회신율 50% → 5 × 0.5 = 2.5
        long p1 = issuedPo();
        issuedPo();
        String d = body(me, ACK + "/pos/" + p1);
        long line = ((Number) JsonPath.read(d, "$.data.lines[0].poLineId")).longValue();
        postAs(me, ACK + "/pos/" + p1, "{\"rev\":0,\"lines\":[{\"poLineId\":" + line + ",\"promisedDate\":\"" + today.plusDays(10)
                + "\",\"promisedQty\":\"10\"}]}").andExpect(status().isOk());
        // 인증서: 만료일 = 기간 끝 날(오늘) → 당일까지 유효
        accounts.exec(comp, "insert into srm_supplier_cert (comp_cd, cust_id, cert_type_cd, issued_on, expires_on, created_by, updated_by) "
                + "values (?, ?, 'ISO9001', '2020-01-01', ?, 't', 't')", comp, vCust, today);
        long ev = id(evalCreate(me, "v100", "sq", today.minusDays(30).toString(), today.toString()));
        String e = body(me, E + "/" + ev);
        assertThat((String) JsonPath.read(e, "$.data.eval.evalNo")).startsWith("EV" + today.getYear());
        assertThat((String) JsonPath.read(e, "$.data.scores[1].score")).isEqualTo("2.5");
        assertThat((String) JsonPath.read(e, "$.data.scores[1].autoYn")).isEqualTo("Y");
        assertThat((String) JsonPath.read(e, "$.data.scores[1].basis")).isEqualTo("ACK_RATE:2:1");
        assertThat((String) JsonPath.read(e, "$.data.scores[1].rawValue")).isEqualTo("50");
        assertThat((String) JsonPath.read(e, "$.data.scores[2].score")).isEqualTo("100");
        assertThat((Boolean) JsonPath.read(e, "$.data.preview.complete")).isFalse();
        // 확정 전 필수: 품질 점수 없음 → 행 0 REQUIRED
        act(me, E, ev, "confirm", null).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].rowKey").value("0")).andExpect(jsonPath("$.errors[0].code").value("REQUIRED"));
        long q = itemId(ev, 0);
        long a = itemId(ev, 1);
        // 자동 항목 덮어쓰기·배점 초과·음수·다른 템플릿 항목
        scores(me, ev, "[{\"itemId\":" + a + ",\"score\":\"5\"},{\"itemId\":" + q + ",\"score\":\"10.5\"}]")
                .andExpect(jsonPath("$.errors[?(@.rowKey == '0')].code").value(Matchers.contains("SRM_EVAL_AUTO")))
                .andExpect(jsonPath("$.errors[?(@.rowKey == '1')].code").value(Matchers.contains("SRM_EVAL_OVER_MAX")));
        scores(me, ev, "[{\"itemId\":" + q + ",\"score\":\"-1\"}]").andExpect(jsonPath("$.errors[0].code").value("NOT_NEGATIVE"));
        scores(me, ev, "[{\"itemId\":999999,\"score\":\"1\"}]").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_VALUE"));
        // 품질 7 → 7/10×40 + 2.5/5×35 + 100/100×25 = 28 + 17.5 + 25 = 70.5 → C
        scores(me, ev, "[{\"itemId\":" + q + ",\"score\":\"7\",\"comment\":\"클레임 1건\"},{\"itemId\":" + a + ",\"comment\":\"자동\"}]")
                .andExpect(status().isOk());
        e = body(me, E + "/" + ev);
        assertThat((String) JsonPath.read(e, "$.data.preview.total")).isEqualTo("70.5");
        assertThat((String) JsonPath.read(e, "$.data.preview.gradeCd")).isEqualTo("C");
        act(me, E, ev, "confirm", null).andExpect(status().isOk());
        e = body(me, E + "/" + ev);
        assertThat((String) JsonPath.read(e, "$.data.eval.status")).isEqualTo("CONFIRMED");
        assertThat((String) JsonPath.read(e, "$.data.eval.totalScore")).isEqualTo("70.5");
        assertThat((String) JsonPath.read(e, "$.data.eval.gradeCd")).isEqualTo("C");
        assertThat((String) JsonPath.read(e, "$.data.eval.failYn")).isEqualTo("N");
        assertThat((Object) JsonPath.read(e, "$.data.eval.suggestTradeStatus")).isNull();
        // 확정 불변: 점수 저장·다시 계산·취소 SRM_STATE, 직접 SQL 점수·머리 수정 막힘
        scores(me, ev, "[{\"itemId\":" + q + ",\"score\":\"9\"}]").andExpect(status().isConflict());
        act(me, E, ev, "recalc", null).andExpect(status().isConflict());
        act(me, E, ev, "cancel", "x").andExpect(status().isConflict());
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(comp,
                "update srm_eval_score set score = 10 where eval_id = ?", ev)))).contains("ck_srm_eval_score_draft");
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(comp,
                "update srm_eval set grade_cd = 'A' where eval_id = ?", ev)))).contains("ck_srm_eval_final");
        // 같은 협력사·기간·템플릿 중복
        evalCreate(me, "V100", "SQ", today.minusDays(30).toString(), today.toString())
                .andExpect(jsonPath("$.errors[0].code").value("SRM_EVAL_DUP"));
        // 과락: 다른 기간, 품질 4 → 16 + 2.5/5×35(기간에 발행 없음 → 사람 입력 5 → 35) + 인증 → 등급 C 이상이어도 과락·HOLD 제안
        long ev2 = id(evalCreate(me, "V100", "SQ", today.minusDays(90).toString(), today.minusDays(60).toString()));
        e = body(me, E + "/" + ev2);
        assertThat((String) JsonPath.read(e, "$.data.scores[1].autoYn")).isEqualTo("N");
        assertThat((String) JsonPath.read(e, "$.data.scores[1].basis")).isEqualTo("ACK_NONE");
        assertThat((String) JsonPath.read(e, "$.data.scores[2].score")).isEqualTo("100");   // 그때도 유효
        scores(me, ev2, "[{\"itemId\":" + itemId(ev2, 0) + ",\"score\":\"4\"},{\"itemId\":" + itemId(ev2, 1) + ",\"score\":\"5\"}]")
                .andExpect(status().isOk());
        act(me, E, ev2, "confirm", null).andExpect(status().isOk());
        e = body(me, E + "/" + ev2);
        assertThat((String) JsonPath.read(e, "$.data.eval.totalScore")).isEqualTo("76");      // 16 + 35 + 25
        assertThat((String) JsonPath.read(e, "$.data.eval.gradeCd")).isEqualTo("C");
        assertThat((String) JsonPath.read(e, "$.data.eval.failYn")).isEqualTo("Y");
        assertThat((String) JsonPath.read(e, "$.data.eval.suggestTradeStatus")).isEqualTo("HOLD");
        // 제안만 — 거래 상태는 그대로(D11)
        assertThat(accounts.query(comp, "select trade_status from srm_supplier where cust_id = ?", String.class, vCust)).isEqualTo("ACTIVE");
    }

    @Test
    void 평가_만들기_검사_인증만료는_0점_다시계산_취소_직접SQL_총점조작() throws Exception {
        released(me, "SQ");
        evalCreate(me, "V100", "SQ", today.toString(), today.plusDays(1).toString()).andExpect(jsonPath("$.errors[0].code").value("SRM_EVAL_FUTURE"));
        evalCreate(me, "V100", "SQ", today.toString(), today.minusDays(1).toString()).andExpect(jsonPath("$.errors[0].code").value("INVALID_RANGE"));
        evalCreate(me, "V100", "SQ", "2024-01-01", "2025-06-30").andExpect(jsonPath("$.errors[0].code").value("RANGE_TOO_LONG"));
        evalCreate(me, "V100", "NOPE", today.minusDays(5).toString(), today.toString()).andExpect(jsonPath("$.errors[0].code").value("SRM_EVAL_NO_TMPL"));
        evalCreate(me, "V100", "SQ", "2019-01-01", "2019-12-31").andExpect(jsonPath("$.errors[0].code").value("SRM_EVAL_NO_TMPL"));   // 템플릿 시작 전
        evalCreate(me, "V999", "SQ", today.minusDays(5).toString(), today.toString()).andExpect(jsonPath("$.errors[0].code").value("NO_SUCH_CODE"));
        // 인증서 만료(어제) → 0점, 근거 CERT:1:어제
        accounts.exec(comp, "insert into srm_supplier_cert (comp_cd, cust_id, cert_type_cd, issued_on, expires_on, created_by, updated_by) "
                + "values (?, ?, 'ISO9001', '2020-01-01', ?, 't', 't')", comp, vCust, today.minusDays(1));
        long ev = id(evalCreate(me, "V100", "SQ", today.minusDays(5).toString(), today.toString()));
        String e = body(me, E + "/" + ev);
        assertThat((String) JsonPath.read(e, "$.data.scores[2].score")).isEqualTo("0");
        assertThat((String) JsonPath.read(e, "$.data.scores[2].basis")).isEqualTo("CERT:1:" + today.minusDays(1));
        // 갱신 인증서(새 행) → 다시 계산하면 100
        accounts.exec(comp, "insert into srm_supplier_cert (comp_cd, cust_id, cert_type_cd, issued_on, expires_on, created_by, updated_by) "
                + "values (?, ?, 'ISO9001', ?, ?, 't', 't')", comp, vCust, today.minusDays(1), today.plusYears(3));
        act(me, E, ev, "recalc", null).andExpect(status().isOk());
        assertThat((String) JsonPath.read(body(me, E + "/" + ev), "$.data.scores[2].score")).isEqualTo("100");
        // 직접 SQL: 점수 넣고 틀린 총점으로 확정 → 트리거가 다시 계산
        accounts.exec(comp, "update srm_eval_score set score = 7 where eval_id = ? and item_id = ?", ev, itemId(ev, 0));
        accounts.exec(comp, "update srm_eval_score set score = 5 where eval_id = ? and item_id = ?", ev, itemId(ev, 1));
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(comp, """
                update srm_eval set status = 'CONFIRMED', total_score = 99, grade_cd = 'A', fail_yn = 'N', confirmed_by = 't', confirmed_at = now()
                 where eval_id = ?""", ev)))).contains("ck_srm_eval_total");
        // 배점 초과 점수·다른 템플릿 항목·점수 삭제
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(comp,
                "update srm_eval_score set score = 11 where eval_id = ? and item_id = ?", ev, itemId(ev, 0))))).contains("ck_srm_eval_score_max");
        assertThat(catchThrowable(() -> accounts.exec(comp, "delete from srm_eval_score where eval_id = ?", ev))).rootCause()
                .hasMessageContaining("permission denied");
        // 취소(사유 필수) → 같은 기간 다시 만들 수 있다
        act(me, E, ev, "cancel", null).andExpect(jsonPath("$.errors[0].field").value("reason"));
        act(me, E, ev, "cancel", "기간 잘못").andExpect(status().isOk());
        id(evalCreate(me, "V100", "SQ", today.minusDays(5).toString(), today.toString()));
        mvc.perform(get(E + "?from=" + today.minusDays(1) + "&to=" + today).with(me))
                .andExpect(jsonPath("$.data[*].status").value(Matchers.containsInAnyOrder("DRAFT", "CANCELLED")));
    }

    // ---------- A사로 B사 · 권한 ----------

    @Test
    void B사로_A사_템플릿_평가_조회수정_막힘_직접SQL_회사확인_권한() throws Exception {
        long aT = released(me, "SQ");
        long aEv = id(evalCreate(me, "V100", "SQ", today.minusDays(5).toString(), today.toString()));
        long aItem = itemId(aEv, 0);
        String other = accounts.newComp("EB");
        long bu = accounts.newUser(other, "buyer1", PW);
        grantAll(other, bu);
        RequestPostProcessor b = accounts.as(other, bu, "buyer1");
        long bCust = seed(other);
        mvc.perform(get(T + "/" + aT).with(b)).andExpect(status().isNotFound());
        mvc.perform(get(E + "/" + aEv).with(b)).andExpect(status().isNotFound());
        postAs(b, T + "/" + aT + "/new-rev", "{}").andExpect(status().isNotFound());
        putAs(b, E + "/" + aEv + "/scores", "{\"rowVer\":0,\"scores\":[]}").andExpect(status().isNotFound());
        postAs(b, E + "/" + aEv + "/confirm", "{\"rowVer\":0}").andExpect(status().isNotFound());
        // B사는 같은 코드 템플릿이 없다 → 평가 못 만듦, B사 목록에 A사 템플릿 없음
        evalCreate(b, "V100", "SQ", today.minusDays(5).toString(), today.toString()).andExpect(jsonPath("$.errors[0].code").value("SRM_EVAL_NO_TMPL"));
        mvc.perform(get(T).with(b)).andExpect(jsonPath("$.data.length()").value(0));
        // B사 평가에 A사 항목 id → 400
        released(b, "SQ");
        long bEv = id(evalCreate(b, "V100", "SQ", today.minusDays(5).toString(), today.toString()));
        scores(b, bEv, "[{\"itemId\":" + aItem + ",\"score\":\"1\"}]").andExpect(status().isBadRequest());
        // 직접 SQL(B사 범위): A사 템플릿으로 평가 / B사 평가 점수 행에 A사 항목 / A사 템플릿에 항목 추가
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(other,
                "insert into srm_eval (comp_cd, eval_no, cust_id, tmpl_id, period_from, period_to, created_by, updated_by) values (?, 'X1', ?, ?, " + TestAccounts.COMP_TODAY + ", " + TestAccounts.COMP_TODAY + ", 't', 't')",
                other, bCust, aT)))).contains("fk_srm_eval_tmpl_comp");
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(other,
                "insert into srm_eval_score (comp_cd, eval_id, item_id, created_by, updated_by) values (?, ?, ?, 't', 't')",
                other, bEv, aItem)))).contains("fk_srm_eval_score_item_tmpl");
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(other,
                "insert into srm_eval_item (comp_cd, tmpl_id, item_no, item_nm, max_score, weight, created_by, updated_by) values (?, ?, 9, 'x', 1, 1, 't', 't')",
                other, aT)))).contains("fk_srm_eval_tmpl_child_comp");
        // 직접 SQL(B사 범위): A사 협력사로 평가
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(other,
                "insert into srm_eval (comp_cd, eval_no, cust_id, tmpl_id, period_from, period_to, created_by, updated_by) values (?, 'X2', ?, ?, " + TestAccounts.COMP_TODAY + ", " + TestAccounts.COMP_TODAY + ", 't', 't')",
                other, vCust, aT)))).contains("fk_srm_eval_supplier_comp");
        // 권한: 저장만(APPROVE 없음) → 확정·템플릿 확정 403, 조회만 → 점수 저장 403
        long clerk = accounts.newUser(comp, "clerk1", PW);
        accounts.grant(comp, clerk, "CLERK", "srm-eval", "SEARCH", "SAVE");
        accounts.grant(comp, clerk, "CLERK", "srm-eval-tmpl", "SEARCH", "SAVE");
        RequestPostProcessor c = accounts.as(comp, clerk, "clerk1");
        postAs(c, E + "/" + aEv + "/confirm", "{\"rowVer\":" + ver(E + "/" + aEv) + "}").andExpect(status().isForbidden());
        long draftT = template(c, "QX", "2020-01-01", "25", GRADES);
        postAs(c, T + "/" + draftT + "/release", "{\"rowVer\":0}").andExpect(status().isForbidden());
        long viewer = accounts.newUser(comp, "viewer1", PW);
        accounts.grant(comp, viewer, "VIEW", "srm-eval", "SEARCH");
        RequestPostProcessor v = accounts.as(comp, viewer, "viewer1");
        mvc.perform(get(E + "/" + aEv).with(v)).andExpect(status().isOk());
        putAs(v, E + "/" + aEv + "/scores", "{\"rowVer\":0,\"scores\":[]}").andExpect(status().isForbidden());
        mvc.perform(get(T).with(v)).andExpect(status().isForbidden());
    }
}
