package io.smarthappy.module.plm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.jayway.jsonpath.JsonPath;

import io.smarthappy.module.home.HomeService;
import io.smarthappy.support.PostgresTestBase;
import io.smarthappy.support.TestAccounts;

/**
 * PLM 홈 카드 (docs/39 20.6) — 적용 대기 설계 변경(숫자 = 목록 status=APPROVED 건수), 고객 응답 대기(PPAP 판정 대기 또는 PCN 기한 지남인 지시,
 * 숫자 = 목록 status=CUST_WAIT 건수 — 30장).
 * 끝난·취소된 것 제외, 화면 권한이 있을 때만(R1), A사로 B사.
 */
@AutoConfigureMockMvc
class PlmHomeCardTest extends PostgresTestBase {

    private static final String PW = "Quarry-maple-73";

    @Autowired
    MockMvc mvc;

    @Autowired
    TestAccounts accounts;

    String comp;
    long userId;
    RequestPostProcessor me;
    LocalDate today;

    @BeforeEach
    void setUp() {
        comp = accounts.newComp("PHC");
        userId = accounts.newUser(comp, "eng1", PW);
        accounts.grant(comp, userId, "ENG", HomeService.PROG, "SEARCH");
        accounts.grant(comp, userId, "ENG2", EcoService.PROG, "SEARCH");
        me = accounts.as(comp, userId, "eng1");
        today = LocalDate.parse(accounts.query(comp, "select " + TestAccounts.COMP_TODAY + "::text", String.class));
        seed(comp);
    }

    private void seed(String c) {
        accounts.addPlant(c, "P1", "1공장");
        accounts.exec(c, """
                insert into mst_item (comp_cd, item_cd, item_nm, item_type, proc_type, unit_cd, created_by, updated_by)
                values (?, 'RM', 'RM', 'RM', 'BUY', 'EA', 't', 't')""", c);
        accounts.exec(c, """
                insert into mst_cust (comp_cd, cust_cd, cust_nm, cust_type, created_by, updated_by)
                values (?, 'C1', 'C1 고객', 'SALES', 't', 't')""", c);
    }

    /** ECO 를 상태까지 직접 만든다(시험 준비) — status = DRAFT·APPROVED·RELEASED·CANCELLED */
    private long eco(String c, long owner, String no, LocalDate eff, String status, String extraCols, String extraVals) {
        long id = accounts.query(c, "insert into plm_eco (comp_cd, eco_no, title, reason_cd, effective_date, owner_id, created_by, updated_by"
                + extraCols + ") values (?, ?, ?, 'QUALITY', ?, ?, 't', 't'" + extraVals + ") returning eco_id",
                Long.class, c, no, no + " 제목", eff, owner);
        return id;
    }

    private void to(String c, long id, String... path) {
        for (String st : path) {
            accounts.exec(c, "update plm_eco set status = ?, approved_at = case when ? = 'APPROVED' then now() else approved_at end, "
                    + "released_at = case when ? = 'RELEASED' then now() else released_at end, "
                    + "cancel_reason = case when ? = 'CANCELLED' then '시험' else cancel_reason end where eco_id = ?", st, st, st, st, id);
        }
    }

    private long ppap(String c, long eco, boolean submitted) {
        long id = accounts.query(c, """
                insert into plm_ppap (comp_cd, eco_id, cust_id, created_by, updated_by)
                values (?, ?, (select cust_id from mst_cust where cust_cd = 'C1'), 't', 't') returning ppap_id""", Long.class, c, eco);
        if (submitted) {
            accounts.exec(c, "update plm_ppap set status = 'SUBMITTED', submitted_on = ? where ppap_id = ?", today.minusDays(3), id);
        }
        return id;
    }

    private void pcn(String c, long eco, String no, LocalDate due) {
        accounts.exec(c, """
                insert into plm_pcn (comp_cd, pcn_no, eco_id, cust_id, notified_on, response_due, created_by, updated_by)
                values (?, ?, ?, (select cust_id from mst_cust where cust_cd = 'C1'), ?, ?, 't', 't')""",
                c, no, eco, today.minusDays(20), due);
    }

    private String home(RequestPostProcessor who) throws Exception {
        return mvc.perform(get("/api/home/cards").with(who)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    private static String card(String code) {
        return "$.data.cards[?(@.code == '" + code + "')]";
    }

    private static long num(String json, String code) {
        List<Object> v = JsonPath.read(json, card(code) + ".count");
        return ((Number) v.get(0)).longValue();
    }

    /** 4가지 경우를 깐다 — 적용 대기 3(유효일 지남 1·고객 승인 대기 1·재고 소진 1), 작성 중·적용됨·취소 각 1 */
    private void fixtures(String c, long owner) {
        long past = eco(c, owner, "E-PAST", today.minusDays(2), null, "", "");
        to(c, past, "PENDING", "APPROVED");
        long cust = eco(c, owner, "E-CUST", today.plusDays(5), null, ", cust_approval_req_yn, cust_id, cust_approval_status",
                ", 'Y', (select cust_id from mst_cust where cust_cd = 'C1'), 'PENDING'");
        to(c, cust, "PENDING", "APPROVED");
        long stock = eco(c, owner, "E-STOCK", today.plusDays(1), null, ", apply_cond, stock_item_id, stock_plant_cd",
                ", 'STOCK_OUT', (select item_id from mst_item where item_cd = 'RM'), 'P1'");
        to(c, stock, "PENDING", "APPROVED");
        eco(c, owner, "E-DRAFT", today, null, "", "");
        long rel = eco(c, owner, "E-REL", today, null, "", "");
        to(c, rel, "PENDING", "APPROVED", "RELEASED");
        long canc = eco(c, owner, "E-CANC", today, null, "", "");
        // 고객 응답 대기: PPAP 판정 대기(E-CUST), PCN 기한 지남(E-PAST), PCN 기한 남음(E-STOCK), 취소된 ECO 의 PPAP·PCN
        ppap(c, cust, true);
        ppap(c, stock, false);
        pcn(c, past, "PCN-LATE", today.minusDays(1));
        pcn(c, stock, "PCN-OK", today.plusDays(3));
        ppap(c, canc, true);
        pcn(c, canc, "PCN-CANC", today.minusDays(5));
        to(c, canc, "CANCELLED");
    }

    @Test
    void 적용_대기_카드는_승인된_미적용_ECO_를_세고_숫자는_목록_건수와_같다() throws Exception {
        fixtures(comp, userId);
        String h = home(me);
        assertThat(num(h, "plm-eco-approved")).isEqualTo(3);
        assertThat(JsonPath.<List<String>>read(h, card("plm-eco-approved") + ".tone")).containsExactly("WARN");
        assertThat(JsonPath.<List<Integer>>read(h, card("plm-eco-approved") + ".facts[*].value")).containsExactly(1, 1, 1);
        assertThat(JsonPath.<List<String>>read(h, card("plm-eco-approved") + ".items[*].title")).containsExactly("E-PAST", "E-STOCK", "E-CUST");
        assertThat(JsonPath.<List<String>>read(h, card("plm-eco-approved") + ".items[0].tone")).containsExactly("WARN");
        List<Map<String, String>> link = JsonPath.read(h, card("plm-eco-approved") + ".link");
        assertThat(link.get(0)).containsExactly(Map.entry("status", "APPROVED"));
        // 숫자 = 링크 조건 그대로 부른 목록 건수
        String list = mvc.perform(get("/api/plm/ecos").param("status", "APPROVED").with(me)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(((Number) JsonPath.read(list, "$.data.rows.length()")).longValue()).isEqualTo(3);
    }

    @Test
    void 고객_응답_대기_카드는_PPAP_판정_대기와_PCN_기한_지남만_세고_취소된_ECO_는_뺀다() throws Exception {
        fixtures(comp, userId);
        String h = home(me);
        assertThat(num(h, "plm-cust-wait")).isEqualTo(2);
        assertThat(JsonPath.<List<String>>read(h, card("plm-cust-wait") + ".tone")).containsExactly("WARN");
        assertThat(JsonPath.<List<Integer>>read(h, card("plm-cust-wait") + ".facts[*].value")).containsExactly(1, 1);
        // PCN 기한 지남 먼저, 그다음 PPAP(ECO 번호)
        assertThat(JsonPath.<List<String>>read(h, card("plm-cust-wait") + ".items[*].title")).containsExactly("PCN-LATE", "E-CUST");
        List<Map<String, String>> link = JsonPath.read(h, card("plm-cust-wait") + ".link");
        assertThat(link.get(0)).containsExactly(Map.entry("status", "CUST_WAIT"));
    }

    @Test
    void 고객_응답_대기_숫자는_지시_수이고_목록_grid_건수와_같다() throws Exception {
        fixtures(comp, userId);
        // 한 지시에 PPAP 판정 대기 + 고객 둘의 기한 지난 PCN — 보조 숫자는 건수(PPAP 1·PCN 2), 숫자는 지시 하나
        accounts.exec(comp, "insert into mst_cust (comp_cd, cust_cd, cust_nm, cust_type, created_by, updated_by) values (?, 'C2', 'C2 고객', 'SALES', 't', 't')", comp);
        long both = eco(comp, userId, "E-BOTH", today.plusDays(2), null, "", "");
        to(comp, both, "PENDING", "APPROVED");
        ppap(comp, both, true);
        pcn(comp, both, "PCN-B1", today.minusDays(2));
        accounts.exec(comp, """
                insert into plm_pcn (comp_cd, pcn_no, eco_id, cust_id, notified_on, response_due, created_by, updated_by)
                values (?, 'PCN-B2', ?, (select cust_id from mst_cust where cust_cd = 'C2'), ?, ?, 't', 't')""",
                comp, both, today.minusDays(20), today.minusDays(1));
        // 응답 받은 PCN·판정 끝난 PPAP 은 빠진다
        long done = eco(comp, userId, "E-DONE", today.plusDays(2), null, "", "");
        to(comp, done, "PENDING", "APPROVED");
        long dp = ppap(comp, done, true);
        accounts.exec(comp, "update plm_ppap set status = 'REJECTED', decided_on = submitted_on where ppap_id = ?", dp);
        pcn(comp, done, "PCN-D", today.minusDays(4));
        accounts.exec(comp, "update plm_pcn set status = 'ACK', responded_on = notified_on where pcn_no = 'PCN-D'");

        String h = home(me);
        assertThat(num(h, "plm-cust-wait")).isEqualTo(3);
        assertThat(JsonPath.<List<Integer>>read(h, card("plm-cust-wait") + ".facts[*].value")).containsExactly(2, 3);
        // 숫자 = 링크 조건 그대로 부른 목록·grid 건수
        String list = mvc.perform(get("/api/plm/ecos").param("status", "CUST_WAIT").with(me)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<List<String>>read(list, "$.data.rows[*].ecoNo")).containsExactlyInAnyOrder("E-PAST", "E-CUST", "E-BOTH");
        String grid = mvc.perform(get("/api/plm/ecos/grid").param("status", "CUST_WAIT").param("total", "true").with(me))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(((Number) JsonPath.read(grid, "$.total")).longValue()).isEqualTo(3);
        // 모르는 묶음 값은 여전히 400
        mvc.perform(get("/api/plm/ecos/grid").param("status", "CUST").with(me)).andExpect(status().isBadRequest());
    }

    @Test
    void 아무것도_없으면_0_이고_상태는_회색() throws Exception {
        String h = home(me);
        assertThat(num(h, "plm-eco-approved")).isZero();
        assertThat(num(h, "plm-cust-wait")).isZero();
        assertThat(JsonPath.<List<String>>read(h, card("plm-eco-approved") + ".tone")).containsExactly("NEUTRAL");
    }

    @Test
    void 설계_변경_화면_권한이_없으면_카드가_안_보인다() throws Exception {
        long plain = accounts.newUser(comp, "plain1", PW);
        accounts.grant(comp, plain, "HOMEONLY", HomeService.PROG, "SEARCH");
        List<String> codes = JsonPath.read(home(accounts.as(comp, plain, "plain1")), "$.data.cards[*].code");
        assertThat(codes).doesNotContain("plm-eco-approved", "plm-cust-wait");
    }

    @Test
    void A사_카드에_B사_건수가_섞이지_않는다() throws Exception {
        String compB = accounts.newComp("PHCB");
        long userB = accounts.newUser(compB, "engB", PW);
        seed(compB);
        fixtures(compB, userB);
        String h = home(me);
        assertThat(num(h, "plm-eco-approved")).isZero();
        assertThat(num(h, "plm-cust-wait")).isZero();
        assertThat(JsonPath.<List<Object>>read(h, card("plm-eco-approved") + ".items[*]")).isEmpty();
    }
}
