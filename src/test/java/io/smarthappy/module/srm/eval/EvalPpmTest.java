package io.smarthappy.module.srm.eval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.jayway.jsonpath.JsonPath;

import io.smarthappy.module.qms.link.SupplierQualityQuery;
import io.smarthappy.module.qms.link.SupplierQualityQuery.ItemQuality;
import io.smarthappy.support.PostgresTestBase;
import io.smarthappy.support.TestAccounts;

/**
 * SRM 2부 R4 입고 PPM 자동 계산 (docs/31 4.12.3) — QMS 계약 {@link SupplierQualityQuery#receiptQuality} 를 바꿔 끼워
 * SRM 이 그 값을 어떻게 읽는지만 본다(계약 값 자체는 QMS SupplierQualityQueryTest).
 * Σ불량 ÷ Σ검사 × 10⁶ → 구간 점수(경계 포함), 근거·원값, 검사 없음·단위 여럿 → 사람 입력, 다시 계산, 손 검산 총점, 부르는 인자(자기 협력사·기간).
 */
@AutoConfigureMockMvc
class EvalPpmTest extends PostgresTestBase {

    private static final String PW = "Maple-anchor-63";
    private static final String T = "/api/srm/eval-tmpls";
    private static final String E = "/api/srm/evals";

    @Autowired
    MockMvc mvc;

    @Autowired
    TestAccounts accounts;

    @MockitoBean
    SupplierQualityQuery quality;

    String comp;
    RequestPostProcessor me;
    LocalDate today;
    long vCust;

    @BeforeEach
    void setUp() throws Exception {
        comp = accounts.newComp("EP");
        long buyer = accounts.newUser(comp, "buyer1", PW);
        accounts.grant(comp, buyer, "PUR", "srm-eval-tmpl", "SEARCH", "SAVE", "APPROVE");
        accounts.grant(comp, buyer, "PUR", "srm-eval", "SEARCH", "SAVE", "APPROVE");
        me = accounts.as(comp, buyer, "buyer1");
        today = LocalDate.now(ZoneId.of("Asia/Seoul"));
        vCust = accounts.query(comp, """
                insert into mst_cust (comp_cd, cust_cd, cust_nm, cust_type, created_by, updated_by)
                values (?, 'V100', 'V100 상사', 'PURCHASE', 't', 't') returning cust_id""", Long.class, comp);
        accounts.exec(comp, "insert into srm_supplier (cust_id, comp_cd, currency_cd, created_by, updated_by) values (?, ?, 'KRW', 't', 't')", vCust, comp);
        // 품질(사람, 10점, 60) + 입고 PPM(AUTO, 20점, 40): 0 이하 100% · 500 이하 80% · 2000 이하 50% · 그 위 0%
        long t = id(send(post(T), "{\"tmplCd\":\"PPM\",\"tmplNm\":\"PPM 평가\",\"validFrom\":\"2020-01-01\",\"items\":["
                + "{\"itemNm\":\"불량 대응\",\"maxScore\":\"10\",\"weight\":\"60\"},"
                + "{\"itemNm\":\"입고 PPM\",\"maxScore\":\"20\",\"weight\":\"40\",\"inputType\":\"AUTO\",\"metricCd\":\"RECV_PPM\",\"bands\":["
                + "{\"upperPpm\":\"0\",\"scorePct\":\"100\"},{\"upperPpm\":\"500\",\"scorePct\":\"80\"},{\"upperPpm\":\"2000\",\"scorePct\":\"50\"},"
                + "{\"scorePct\":\"0\"}]}],"
                + "\"grades\":[{\"gradeCd\":\"A\",\"minScore\":\"90\"},{\"gradeCd\":\"C\",\"minScore\":\"0\"}]}"));
        send(post(T + "/" + t + "/release"), "{\"rowVer\":" + JsonPath.read(body(T + "/" + t), "$.data.tmpl.rowVer") + "}")
                .andExpect(status().isOk());
    }

    private ResultActions send(MockHttpServletRequestBuilder b, String json) throws Exception {
        return mvc.perform(b.with(csrf()).contentType(MediaType.APPLICATION_JSON).content(json).with(me));
    }

    private static long id(ResultActions r) throws Exception {
        MvcResult m = r.andExpect(status().isOk()).andReturn();
        return ((Number) JsonPath.read(m.getResponse().getContentAsString(), "$.data")).longValue();
    }

    private String body(String url) throws Exception {
        return mvc.perform(get(url).with(me)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    private static ItemQuality row(long item, String unit, String inspected, String rejected, int open, String other) {
        return new ItemQuality(item, "I" + item, unit, new BigDecimal(inspected), new BigDecimal(rejected), 1, 0, open, new BigDecimal(other));
    }

    private void qms(ItemQuality... rows) {
        when(quality.receiptQuality(anyLong(), any(), any())).thenReturn(List.of(rows));
    }

    private long create(int daysBack) throws Exception {
        return id(send(post(E), "{\"custCd\":\"V100\",\"tmplCd\":\"PPM\",\"periodFrom\":\"" + today.minusDays(daysBack) + "\",\"periodTo\":\""
                + today.minusDays(daysBack - 10) + "\"}"));
    }

    private void act(long ev, String action) throws Exception {
        send(post(E + "/" + ev + "/" + action), "{\"rowVer\":" + JsonPath.read(body(E + "/" + ev), "$.data.eval.rowVer") + "}")
                .andExpect(status().isOk());
    }

    private String ppm(long ev, String field) throws Exception {
        return JsonPath.read(body(E + "/" + ev), "$.data.scores[1]." + field);
    }

    @Test
    void PPM_은_전체불량_나누기_전체검사_구간점수_경계포함_근거에_처리중과_검사밖NCR_손검산_총점() throws Exception {
        // 4,000 중 2 → 500 PPM(경계 포함) → 80% × 20 = 16. 처리 중 1건·검사 밖 2 는 근거에만
        qms(row(1, "EA", "3000", "1", 1, "2"), row(2, "EA", "1000", "1", 0, "0"));
        long ev = create(20);
        verify(quality).receiptQuality(vCust, today.minusDays(20), today.minusDays(10));
        assertThat(ppm(ev, "autoYn")).isEqualTo("Y");
        assertThat(ppm(ev, "score")).isEqualTo("16");
        assertThat(ppm(ev, "rawValue")).isEqualTo("500");
        assertThat(ppm(ev, "basis")).isEqualTo("PPM:4000:2:1:2");
        // 사람 항목 6 → 6/10×60 + 16/20×40 = 36 + 32 = 68 → C
        long q = ((Number) JsonPath.read(body(E + "/" + ev), "$.data.scores[0].itemId")).longValue();
        send(put(E + "/" + ev + "/scores"), "{\"rowVer\":" + JsonPath.read(body(E + "/" + ev), "$.data.eval.rowVer")
                + ",\"scores\":[{\"itemId\":" + q + ",\"score\":\"6\"}]}").andExpect(status().isOk());
        act(ev, "confirm");
        String e = body(E + "/" + ev);
        assertThat((String) JsonPath.read(e, "$.data.eval.totalScore")).isEqualTo("68");
        assertThat((String) JsonPath.read(e, "$.data.eval.gradeCd")).isEqualTo("C");
        // 경계 바로 위: 1,999 중 1 → 500.25 → 2000 이하 50% → 10, 다시 계산하면 바뀐다(작성 중)
        qms(row(1, "EA", "1999", "1", 0, "0"));
        long ev2 = create(40);
        assertThat(ppm(ev2, "rawValue")).isEqualTo("500.25");
        assertThat(ppm(ev2, "score")).isEqualTo("10");
        qms(row(1, "EA", "100", "0", 0, "0"), row(9, "EA", "0", "0", 0, "3"));
        act(ev2, "recalc");
        assertThat(ppm(ev2, "score")).isEqualTo("20");
        assertThat(ppm(ev2, "basis")).isEqualTo("PPM:100:0:0:3");
        // 불량이 검사보다 커도(정정 중 데이터) 그 위 전부 구간 → 0점
        qms(row(1, "EA", "10", "20", 0, "0"));
        long ev3 = create(60);
        assertThat(ppm(ev3, "rawValue")).isEqualTo("2000000");
        assertThat(ppm(ev3, "score")).isEqualTo("0");
    }

    @Test
    void 검사없음_단위여럿은_사람입력_QMS_빈값도_같음() throws Exception {
        qms();
        long ev = create(20);
        assertThat(ppm(ev, "autoYn")).isEqualTo("N");
        assertThat(ppm(ev, "basis")).isEqualTo("PPM_NONE");
        assertThat((Object) JsonPath.read(body(E + "/" + ev), "$.data.scores[1].score")).isNull();
        // 검사 수량 0 인 줄만(검사 밖 NCR 만 있는 협력사)
        qms(row(1, "EA", "0", "0", 0, "4"));
        assertThat(ppm(create(40), "basis")).isEqualTo("PPM_NONE");
        // 단위가 둘(검사 수량 있는 줄 기준) → 더할 수 없음
        qms(row(1, "KG", "50", "1", 0, "0"), row(2, "EA", "100", "0", 0, "0"), row(3, "M", "0", "0", 0, "1"));
        long ev3 = create(60);
        assertThat(ppm(ev3, "autoYn")).isEqualTo("N");
        assertThat(ppm(ev3, "basis")).isEqualTo("PPM_UNITS:EA,KG");
        // 사람 입력 항목이 되었으니 점수를 넣을 수 있다
        long p = ((Number) JsonPath.read(body(E + "/" + ev3), "$.data.scores[1].itemId")).longValue();
        send(put(E + "/" + ev3 + "/scores"), "{\"rowVer\":" + JsonPath.read(body(E + "/" + ev3), "$.data.eval.rowVer")
                + ",\"scores\":[{\"itemId\":" + p + ",\"score\":\"15\"}]}").andExpect(status().isOk());
        assertThat(ppm(ev3, "score")).isEqualTo("15");
    }
}
