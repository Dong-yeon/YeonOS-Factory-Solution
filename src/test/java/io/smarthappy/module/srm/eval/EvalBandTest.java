package io.smarthappy.module.srm.eval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
 * SRM 2부 R4b 점수 구간 srm_eval_band (docs/31 4.12.3) — 저장·정렬·다시 읽기, 입력 검사(PPM 아닌 항목·끝 없는 구간 둘·중복·범위),
 * 확정 전 끝 없는 구간 필수(서비스·직접 SQL), 확정본 구간 불변, 새 리비전 복사, 항목 바꾸면 구간 정리, A사로 B사.
 */
@AutoConfigureMockMvc
@SuppressWarnings("unchecked")
class EvalBandTest extends PostgresTestBase {

    private static final String PW = "Maple-anchor-63";
    private static final String T = "/api/srm/eval-tmpls";

    @Autowired
    MockMvc mvc;

    @Autowired
    TestAccounts accounts;

    String comp;
    RequestPostProcessor me;

    @BeforeEach
    void setUp() {
        comp = accounts.newComp("EB");
        long buyer = accounts.newUser(comp, "buyer1", PW);
        accounts.grant(comp, buyer, "PUR", "srm-eval-tmpl", "SEARCH", "SAVE", "APPROVE");
        me = accounts.as(comp, buyer, "buyer1");
    }

    private ResultActions send(RequestPostProcessor who, MockHttpServletRequestBuilder b, String body) throws Exception {
        return mvc.perform(b.with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body).with(who));
    }

    private static long id(ResultActions r) throws Exception {
        MvcResult m = r.andExpect(status().isOk()).andReturn();
        return ((Number) JsonPath.read(m.getResponse().getContentAsString(), "$.data")).longValue();
    }

    private String body(RequestPostProcessor who, long t) throws Exception {
        return mvc.perform(get(T + "/" + t).with(who)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    private int ver(RequestPostProcessor who, long t) throws Exception {
        return JsonPath.read(body(who, t), "$.data.tmpl.rowVer");
    }

    /** 품질(사람, 10점, 가중 60) + 입고 PPM(AUTO, 20점, 40, 구간 bands) */
    private static String items(String bands) {
        return "[{\"sectionNm\":\"품질\",\"itemNm\":\"불량 대응\",\"maxScore\":\"10\",\"weight\":\"60\"},"
                + "{\"sectionNm\":\"품질\",\"itemNm\":\"입고 PPM\",\"maxScore\":\"20\",\"weight\":\"40\",\"inputType\":\"AUTO\",\"metricCd\":\"RECV_PPM\","
                + "\"bands\":" + bands + "}]";
    }

    private static final String GRADES = "[{\"gradeCd\":\"A\",\"minScore\":\"90\"},{\"gradeCd\":\"D\",\"minScore\":\"0\",\"suggestHoldYn\":\"Y\"}]";
    /** 순서를 섞어 보낸다 — 저장은 상한 오름차순, 끝 없는 구간은 마지막 */
    private static final String BANDS = "[{\"upperPpm\":\"2000\",\"scorePct\":\"50\"},{\"upperPpm\":\"\",\"scorePct\":\"0\"},"
            + "{\"upperPpm\":\"0\",\"scorePct\":\"100\"},{\"upperPpm\":\"500.00\",\"scorePct\":\"80\"}]";

    private ResultActions create(RequestPostProcessor who, String cd, String items) throws Exception {
        return send(who, post(T), "{\"tmplCd\":\"" + cd + "\",\"tmplNm\":\"" + cd + " 평가\",\"validFrom\":\"2020-01-01\",\"items\":" + items
                + ",\"grades\":" + GRADES + "}");
    }

    private ResultActions update(RequestPostProcessor who, long t, String items) throws Exception {
        return send(who, put(T + "/" + t), "{\"tmplNm\":\"평가\",\"validFrom\":\"2020-01-01\",\"rowVer\":" + ver(who, t) + ",\"items\":" + items
                + ",\"grades\":" + GRADES + "}");
    }

    private ResultActions release(RequestPostProcessor who, long t) throws Exception {
        return send(who, post(T + "/" + t + "/release"), "{\"rowVer\":" + ver(who, t) + "}");
    }

    private long bandCnt(String c, long t) {
        return accounts.query(c, "select count(*) from srm_eval_band where tmpl_id = ?", Long.class, t);
    }

    @Test
    void 구간_저장은_정렬_다시읽기_수정은_한벌교체_확정_불변_새리비전_복사() throws Exception {
        long t = id(create(me, "PPM", items(BANDS)));
        String d = body(me, t);
        assertThat((List<?>) JsonPath.read(d, "$.data.items[0].bands")).isEmpty();
        assertThat((List<String>) JsonPath.read(d, "$.data.items[1].bands[*].upperPpm")).containsExactly("0", "500", "2000", null);
        assertThat((List<String>) JsonPath.read(d, "$.data.items[1].bands[*].scorePct")).containsExactly("100", "80", "50", "0");
        // 수정 = 한 벌 교체(4 → 2), 항목을 MANUAL 로 바꾸면서 구간을 보내지 않으면 구간도 없어진다
        update(me, t, items("[{\"upperPpm\":\"100\",\"scorePct\":\"100\"},{\"scorePct\":\"30\"}]")).andExpect(status().isOk());
        assertThat(bandCnt(comp, t)).isEqualTo(2);
        update(me, t, items("[]").replace("\"inputType\":\"AUTO\",\"metricCd\":\"RECV_PPM\",", "")).andExpect(status().isOk());
        assertThat(bandCnt(comp, t)).isZero();
        update(me, t, items(BANDS)).andExpect(status().isOk());
        release(me, t).andExpect(status().isOk());
        // 확정본: 직접 SQL 구간 추가·수정·삭제 막힘(공용 가드)
        long item = accounts.query(comp, "select item_id from srm_eval_item where tmpl_id = ? and item_no = 2", Long.class, t);
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(comp,
                "update srm_eval_band set score_pct = 100 where tmpl_id = ?", t)))).contains("ck_srm_eval_tmpl_child_draft");
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(comp,
                "delete from srm_eval_band where tmpl_id = ?", t)))).contains("ck_srm_eval_tmpl_child_draft");
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(comp,
                "insert into srm_eval_band (comp_cd, tmpl_id, item_id, upper_ppm, score_pct, created_by, updated_by) values (?, ?, ?, 9000, 10, 't', 't')",
                comp, t, item)))).contains("ck_srm_eval_tmpl_child_draft");
        // 새 리비전 = 구간까지 복사(새 항목 id 로)
        long r1 = id(send(me, post(T + "/" + t + "/new-rev"), "{}"));
        String d1 = body(me, r1);
        assertThat((List<String>) JsonPath.read(d1, "$.data.items[1].bands[*].upperPpm")).containsExactly("0", "500", "2000", null);
        long newItem = ((Number) JsonPath.read(d1, "$.data.items[1].itemId")).longValue();
        assertThat(newItem).isNotEqualTo(item);
        assertThat(accounts.query(comp, "select count(*) from srm_eval_band where tmpl_id = ? and item_id = ?", Long.class, r1, newItem))
                .isEqualTo(4);
    }

    @Test
    void 구간_입력검사_PPM아닌항목_끝없는구간둘_중복_범위() throws Exception {
        // 행 0(MANUAL) 에 구간, 행 1: 끝 없는 구간 둘·상한 중복(500 = 500.0)·비율 101·음수 상한
        create(me, "BAD", "[{\"itemNm\":\"a\",\"maxScore\":\"10\",\"weight\":\"60\",\"bands\":[{\"scorePct\":\"10\"}]},"
                + "{\"itemNm\":\"b\",\"maxScore\":\"20\",\"weight\":\"40\",\"inputType\":\"AUTO\",\"metricCd\":\"RECV_PPM\",\"bands\":["
                + "{\"scorePct\":\"10\"},{\"upperPpm\":\" \",\"scorePct\":\"20\"},{\"upperPpm\":\"500\",\"scorePct\":\"101\"},"
                + "{\"upperPpm\":\"500.0\",\"scorePct\":\"50\"},{\"upperPpm\":\"-1\",\"scorePct\":\"50\"}]}]")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[?(@.rowKey == '0')].code").value(Matchers.contains("SRM_EVAL_BAND_METRIC")))
                .andExpect(jsonPath("$.errors[?(@.rowKey == '1')].code")
                        .value(Matchers.containsInAnyOrder("RANGE", "DUPLICATE", "NOT_NEGATIVE", "SRM_EVAL_BAND_OPEN")));
        // 11줄 → TOO_MANY_LINES
        StringBuilder many = new StringBuilder("[");
        for (int i = 0; i < 11; i++) {
            many.append(i == 0 ? "" : ",").append("{\"upperPpm\":\"").append(i * 10).append("\",\"scorePct\":\"50\"}");
        }
        create(me, "BAD", items(many + "]")).andExpect(jsonPath("$.errors[0].code").value("TOO_MANY_LINES"));
        assertThat(accounts.query(comp, "select count(*) from srm_eval_tmpl", Integer.class)).isZero();
    }

    @Test
    void 확정은_끝없는구간_필수_서비스와_직접SQL_직접SQL_구간은_PPM항목만() throws Exception {
        // 작성 중엔 구간 없이·끝 없는 구간 없이 저장 가능
        long t = id(create(me, "PPM", items("[{\"upperPpm\":\"100\",\"scorePct\":\"100\"}]")));
        release(me, t).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("items"))
                .andExpect(jsonPath("$.errors[0].code").value("SRM_EVAL_BAND_NEED"));
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(comp,
                "update srm_eval_tmpl set status = 'RELEASED', released_by = 't', released_at = now() where tmpl_id = ?", t))))
                .contains("ck_srm_eval_tmpl_bands");
        // 직접 SQL: MANUAL 항목에 구간 / 같은 항목에 끝 없는 구간 둘
        long manual = accounts.query(comp, "select item_id from srm_eval_item where tmpl_id = ? and item_no = 1", Long.class, t);
        long ppm = accounts.query(comp, "select item_id from srm_eval_item where tmpl_id = ? and item_no = 2", Long.class, t);
        String ins = "insert into srm_eval_band (comp_cd, tmpl_id, item_id, upper_ppm, score_pct, created_by, updated_by) values (?, ?, ?, null, 0, 't', 't')";
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(comp, ins, comp, t, manual)))).contains("ck_srm_eval_band_metric");
        accounts.exec(comp, ins, comp, t, ppm);
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(comp, ins, comp, t, ppm)))).contains("uq_srm_eval_band_upper");
        // 직접 SQL 로 끝 없는 구간을 넣었으니 이제 확정된다
        release(me, t).andExpect(status().isOk());
    }

    @Test
    void A사로_B사_구간_조회_불가_B사범위_직접SQL_A사항목에_구간_막힘() throws Exception {
        long aT = id(create(me, "PPM", items(BANDS)));
        long aItem = accounts.query(comp, "select item_id from srm_eval_item where tmpl_id = ? and item_no = 2", Long.class, aT);
        String other = accounts.newComp("EC");
        long bu = accounts.newUser(other, "buyer1", PW);
        accounts.grant(other, bu, "PUR", "srm-eval-tmpl", "SEARCH", "SAVE", "APPROVE");
        RequestPostProcessor b = accounts.as(other, bu, "buyer1");
        mvc.perform(get(T + "/" + aT).with(b)).andExpect(status().isNotFound());
        assertThat(bandCnt(other, aT)).isZero();   // RLS: B사 범위에서 A사 구간 0건
        long bT = id(create(b, "PPM", items("[]")));
        // B사 템플릿에 A사 항목 / A사 템플릿·A사 항목에 B사 회사로
        String ins = "insert into srm_eval_band (comp_cd, tmpl_id, item_id, upper_ppm, score_pct, created_by, updated_by) values (?, ?, ?, null, 0, 't', 't')";
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(other, ins, other, bT, aItem)))).contains("fk_srm_eval_band_item_tmpl");
        assertThat(catchThrowable(() -> accounts.exec(other, ins, other, aT, aItem))).isNotNull();
        // B사 회사코드로 A사 행 쓰기 → RLS
        assertThat(catchThrowable(() -> accounts.exec(other, ins, comp, aT, aItem))).isNotNull();
        assertThat(bandCnt(comp, aT)).isEqualTo(4);
        assertThat(bandCnt(other, bT)).isZero();
    }
}
