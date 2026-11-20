package io.smarthappy.module.qms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.jayway.jsonpath.JsonPath;

/**
 * 홈 6단계 QMS 카드 (docs/37 18.1) — 판정 대기 검사(숫자 = 링크 목록 건수, H27), 열린 부적합(OPEN·PENDING, H30)·봉쇄(링크 없음, H28),
 * 권한 R1, A사로 B사. 픽스처는 QMS 시험 기반(QmsTestBase — 품목·검사 기준 확정본)과 같은 API 흐름으로 만든다.
 */
@AutoConfigureMockMvc
class HomeQmsCardTest extends QmsTestBase {

    static final String I = "/api/qms/insps";
    static final String N = "/api/qms/ncrs";
    static final String C = "/api/qms/contains";
    static final String CARDS = "/api/home/cards";

    @BeforeEach
    void homeSetUp() throws Exception {
        setUpComp(comp, userId);
    }

    /** 홈 + 검사·부적합·봉쇄 권한, 불량 유형, 출하검사 확정본, LOT 3개 */
    void setUpComp(String c, long u) throws Exception {
        accounts.grant(c, u, "QA", "home-main", "SEARCH");
        accounts.grant(c, u, "QA", "qms-insp", "SEARCH", "SAVE");
        accounts.grant(c, u, "QA", "qms-ncr", "SEARCH", "SAVE");
        accounts.grant(c, u, "QA", "qms-contain", "SEARCH", "SAVE");
        accounts.exec(c, """
                insert into qms_defect (comp_cd, defect_cd, defect_nm, cat_cd, created_by, updated_by)
                values (?, 'D-BUR', '버', 'APPEARANCE', 't', 't') on conflict do nothing""", c);
        for (String lot : List.of("L-1", "L-2", "L-3")) {
            accounts.exec(c, """
                    insert into lot_master (comp_cd, lot_no, item_id, origin, qty_created, unit_cd, occurred_at, created_by, updated_by)
                    select ?, ?, item_id, 'RECEIVED', 10, 'EA', now(), 't', 't' from mst_item where item_cd = 'FG1'""", c, lot);
        }
    }

    private String home(RequestPostProcessor who) throws Exception {
        return mvc.perform(get(CARDS).with(who)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    private static String card(String code) {
        return "$.data.cards[?(@.code == '" + code + "')]";
    }

    private static long num(String json, String code) {
        List<Object> v = JsonPath.read(json, card(code) + ".count");
        return ((Number) v.get(0)).longValue();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> link(String json, String code) {
        List<Map<String, String>> v = JsonPath.read(json, card(code) + ".link");
        return v.get(0);
    }

    private void openFinalPlan() throws Exception {
        long p = plan("FINAL", "FG1", null, null);
        lines(p, line("a", null, 10, fgChar2, "S5", "VISUAL")).andExpect(status().isOk());
        action(p, "release").andExpect(status().isOk());
    }

    private long ncr(String lotNo) throws Exception {
        long defect = accounts.query(comp, "select defect_id from qms_defect where defect_cd = 'D-BUR'", Long.class);
        return id(postJson(N, "{\"lotNo\":" + q(lotNo) + ",\"source\":\"PROCESS\",\"defectId\":" + defect
                + ",\"ncQty\":1,\"description\":\"버 발생\"}"));
    }

    @Test
    void 판정_대기_검사는_숫자와_링크_목록_건수가_같다() throws Exception {
        openFinalPlan();
        postJson(I, "{\"lotNo\":\"L-1\",\"inspType\":\"FINAL\"}").andExpect(status().isOk());
        postJson(I, "{\"lotNo\":\"L-2\",\"inspType\":\"FINAL\"}").andExpect(status().isOk());
        String h = home(admin);
        assertThat(num(h, "qms-insp-open")).isEqualTo(2);
        Map<String, String> l = link(h, "qms-insp-open");
        assertThat(l).containsEntry("status", "OPEN");
        MockHttpServletRequestBuilder r = get(I).with(admin);
        l.forEach(r::param);
        String list = mvc.perform(r).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(((Number) JsonPath.read(list, "$.data.length()")).longValue()).as("목록 = 카드 숫자").isEqualTo(2);
        // 항목 링크 = 검사 번호 + OPEN → 그 검사 하나
        @SuppressWarnings("unchecked")
        Map<String, String> item = ((List<Map<String, String>>) JsonPath.read(h, card("qms-insp-open") + ".items[0].link")).get(0);
        MockHttpServletRequestBuilder ri = get(I).with(admin);
        item.forEach(ri::param);
        assertThat(((Number) JsonPath.read(mvc.perform(ri).andReturn().getResponse().getContentAsString(), "$.data.length()")).longValue())
                .isEqualTo(1);
    }

    @Test
    void 열린_부적합과_봉쇄는_세지만_숫자_링크는_없다() throws Exception {
        long n = ncr("L-1");
        String h = home(admin);
        assertThat(num(h, "qms-ncr-open")).isEqualTo(1);
        // 부적합 숫자 링크(H28 후속): 목록 "열린 것" 묶음(OPEN_ALL = OPEN·PENDING) = 카드 숫자
        Map<String, String> nl = link(h, "qms-ncr-open");
        assertThat(nl).containsEntry("status", "OPEN_ALL");
        MockHttpServletRequestBuilder rn = get(N).with(admin);
        nl.forEach(rn::param);
        String nlist = mvc.perform(rn).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(((Number) JsonPath.read(nlist, "$.data.length()")).longValue()).as("부적합 목록 = 카드 숫자").isEqualTo(1);
        assertThat(JsonPath.<List<Object>>read(h, card("qms-ncr-open") + ".items[0].link")).containsExactly((Object) null);
        // 봉쇄: LOT 2개를 묶는다
        long l2 = accounts.query(comp, "select lot_id from lot_master where lot_no = 'L-2'", Long.class);
        long l3 = accounts.query(comp, "select lot_id from lot_master where lot_no = 'L-3'", Long.class);
        String ncrNo = accounts.query(comp, "select ncr_no from qms_ncr where ncr_id = ?", String.class, n);
        postJson(C, "{\"itemCd\":\"FG1\",\"fromDate\":" + q(today.minusDays(7)) + ",\"toDate\":" + q(today) + ",\"ncrNo\":" + q(ncrNo)
                + ",\"reason\":\"버 유출 의심\",\"lotIds\":[" + l2 + "," + l3 + "]}").andExpect(status().isOk());
        String h2 = home(admin);
        assertThat(num(h2, "qms-contain-open")).isEqualTo(1);
        assertThat(JsonPath.<List<Integer>>read(h2, card("qms-contain-open") + ".facts[0].value")).containsExactly(2);
        // 봉쇄 숫자 링크(H28 후속): status=OPEN 목록 = 카드 숫자
        Map<String, String> cl = link(h2, "qms-contain-open");
        assertThat(cl).containsEntry("status", "OPEN");
        MockHttpServletRequestBuilder rc = get(C).with(admin);
        cl.forEach(rc::param);
        String clist = mvc.perform(rc).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(((Number) JsonPath.read(clist, clist.contains("\"data\":[") ? "$.data.length()" : "$.list.length()")).longValue())
                .as("봉쇄 목록 = 카드 숫자").isEqualTo(1);
        // 취소한 부적합은 세지 않는다 — 다른 LOT 부적합 하나 더 만들고 취소
        long n2 = ncr("L-3");
        accounts.exec(comp, "update qms_ncr set status = 'CANCELLED', cancel_reason = '시험' where ncr_id = ?", n2);
        assertThat(num(home(admin), "qms-ncr-open")).isEqualTo(1);
    }

    @Test
    void QMS_카드는_화면_권한이_있을_때만_A사로_B사_건수는_섞이지_않는다() throws Exception {
        ncr("L-1");
        long u = accounts.newUser(comp, "line1", PW);
        accounts.grant(comp, u, "LINE", "home-main", "SEARCH");
        accounts.grant(comp, u, "LINE", "qms-insp", "SEARCH");
        String h = home(accounts.as(comp, u, "line1"));
        assertThat(JsonPath.<List<String>>read(h, "$.data.cards[*].code")).contains("qms-insp-open")
                .doesNotContain("qms-ncr-open", "qms-contain-open");
        // B사: 같은 기준 데이터, 부적합 없음
        String b = accounts.newComp("QM");
        long ub = accounts.newUser(b, "admin1", PW);
        fixtures(b);
        setUpComp(b, ub);
        assertThat(num(home(accounts.as(b, ub, "admin1")), "qms-ncr-open")).isZero();
        assertThat(num(home(admin), "qms-ncr-open")).isEqualTo(1);
    }
}
