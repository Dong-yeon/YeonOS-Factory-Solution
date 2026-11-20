package io.smarthappy.module.home;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.jayway.jsonpath.JsonPath;

import io.smarthappy.platform.policy.PolicyService;
import io.smarthappy.platform.tenant.TenantRunner;
import io.smarthappy.platform.time.BizClock;
import io.smarthappy.support.PostgresTestBase;
import io.smarthappy.support.TestAccounts;

/**
 * 홈 5단계 (docs/37 17장) — 결재 카드 링크(H19), 공장 선택(검증·공장 카드 거르기·공장 시간대 오늘, H20·H21),
 * 새 카드(시정조치·점검·고장·실사)의 "카드 숫자 = 링크 조건 그대로 부른 목록 건수", 창 밖 보조 숫자(H22), 권한 R1, A사로 B사.
 */
@AutoConfigureMockMvc
class HomeModuleCardTest extends PostgresTestBase {

    private static final String PW = "Quarry-maple-73";
    private static final String CARDS = "/api/home/cards";

    @Autowired
    MockMvc mvc;

    @Autowired
    TestAccounts accounts;

    @Autowired
    BizClock clock;

    @Autowired
    TenantRunner runner;

    @Autowired
    PolicyService policies;

    String comp;
    long userId;
    RequestPostProcessor me;
    LocalDate today;

    @BeforeEach
    void setUp() {
        comp = accounts.newComp("HX");
        userId = accounts.newUser(comp, "user1", PW);
        grant(comp, userId);
        me = accounts.as(comp, userId, "user1");
        today = LocalDate.parse(accounts.query(comp, "select " + TestAccounts.COMP_TODAY + "::text", String.class));
        fixtures(comp);
    }

    @AfterEach
    void resetClock() {
        clock.setClock(Clock.systemUTC());
    }

    private void grant(String c, long u) {
        for (String p : List.of(HomeService.PROG, "qms-capa", "cms-chk-run", "cms-mwo", "wms-count", "cms-state-review", "wms-ship")) {
            accounts.grant(c, u, "OFFICE", p, "SEARCH");
        }
    }

    private void fixtures(String c) {
        accounts.addPlant(c, "P1", "1공장");
        accounts.addPlant(c, "P2", "2공장");
        long cls = accounts.query(c, """
                insert into mst_equip_class (comp_cd, class_cd, class_nm, created_by, updated_by)
                values (?, 'WELD', '용접', 't', 't') returning class_id""", Long.class, c);
        for (String[] e : new String[][] {{"P1", "EQ-1"}, {"P2", "EQ-2"}}) {
            accounts.exec(c, """
                    insert into mst_equip (comp_cd, plant_cd, equip_cd, equip_nm, class_id, created_by, updated_by)
                    values (?, ?, ?, ?, ?, 't', 't')""", c, e[0], e[1], e[1] + " 설비", cls);
        }
        accounts.exec(c, "insert into cms_chk_tmpl (comp_cd, tmpl_cd, tmpl_nm, created_by, updated_by) values (?, 'T1', '일상', 't', 't')", c);
    }

    private long equip(String c, String cd) {
        return accounts.query(c, "select equip_id from mst_equip where equip_cd = ?", Long.class, cd);
    }

    /** 점검 실행 1건(열린 상태) — 계획을 하나씩 만든다(같은 계획·같은 예정일은 유일) */
    private void run(String c, String equipCd, LocalDate due, String status) {
        long eq = equip(c, equipCd);
        long tmpl = accounts.query(c, "select tmpl_id from cms_chk_tmpl where tmpl_cd = 'T1'", Long.class);
        long plan = accounts.query(c, """
                insert into cms_pm_plan (comp_cd, plan_cd, plan_nm, target_type, equip_id, tmpl_id, cal_unit, cal_interval, anchor_date,
                                         next_due_date, created_by, updated_by)
                values (?, ?, '정기 점검', 'EQUIP', ?, ?, 'DAY', 1, ?, ?, 't', 't') returning plan_id""",
                Long.class, c, "PL-" + System.nanoTime(), eq, tmpl, due, due);
        long id = accounts.query(c, """
                insert into cms_chk_run (comp_cd, plan_id, target_type, equip_id, tmpl_id, due_date, created_by, updated_by)
                values (?, ?, 'EQUIP', ?, ?, ?, 't', 't') returning run_id""", Long.class, c, plan, eq, tmpl, due);
        if ("SKIPPED".equals(status)) {
            accounts.exec(c, "update cms_chk_run set status = 'SKIPPED', skip_reason = '시험' where run_id = ?", id);
        }
    }

    /** 고장(BM)·CM 작업 — 신고 daysAgo 일 전 */
    private void mwo(String c, String no, String type, String plant, String equipCd, String st, int daysAgo, String down) {
        accounts.exec(c, """
                insert into cms_mwo (comp_cd, mwo_no, mwo_type, plant_cd, target_type, equip_id, title, status, reported_at, down_yn,
                                     down_start_at, symptom_text, created_by, updated_by)
                values (?, ?, ?, ?, 'EQUIP', ?, ?, ?, now() - make_interval(days => ?), ?,
                        case when ? = 'Y' then now() - make_interval(days => ?) end, '시험 현상', 't', 't')""",
                c, no, type, plant, equip(c, equipCd), no + " 고장", "WIP".equals(st) ? "OPEN" : st, daysAgo, down, down, daysAgo);
        if ("WIP".equals(st)) {   // 새 작업은 REQ·OPEN 으로만(트리거) — 착수로 옮긴다
            accounts.exec(c, "update cms_mwo set status = 'WIP', started_at = now() where mwo_no = ?", no);
        }
    }

    private void capa(String c, String no, LocalDate containDue, LocalDate closeDue) {
        accounts.exec(c, """
                insert into qms_capa (comp_cd, capa_no, source, title, occurred_on, owner_user_id, contain_due, close_due, created_by, updated_by)
                values (?, ?, 'INTERNAL', ?, ?, ?, ?, ?, 't', 't')""", c, no, no + " 시정", containDue.minusDays(3),
                accounts.query(c, "select min(user_id) from sys_user", Long.class), containDue, closeDue);
    }

    private void count(String c, String plant, String no, String status) {
        long node = accounts.query(c, """
                insert into mst_equip_hier (comp_cd, plant_cd, node_type, node_cd, node_nm, created_by, updated_by)
                values (?, ?, 'WH', ?, ?, 't', 't') returning node_id""", Long.class, c, plant, "WH-" + no, "창고 " + no);
        accounts.exec(c, """
                insert into wms_count (comp_cd, plant_cd, count_no, count_type, scope_node_id, tol_pct, status, created_by, updated_by)
                values (?, ?, ?, 'CYCLE', ?, 0, 'DRAFT', 't', 't')""", c, plant, no, node);
        // 새 실사는 DRAFT 로만(트리거) — 상태 전이를 따라 옮긴다
        List<String> path = switch (status) {
            case "COUNTING" -> List.of("COUNTING");
            case "REVIEW" -> List.of("COUNTING", "REVIEW");
            case "CANCELLED" -> List.of("CANCELLED");
            default -> List.of();
        };
        for (String st : path) {
            accounts.exec(c, """
                    update wms_count set status = ?, started_at = coalesce(started_at, now()),
                           cancel_reason = case when ? = 'CANCELLED' then '시험' end
                     where count_no = ?""", st, st, no);
        }
    }

    /** 출하 지시 — 새 지시는 DRAFT 로만(트리거). 고객·공장별 STAGING 칸은 처음 한 번 만든다 */
    private void ship(String c, String plant, String no, String status, LocalDate planDate) {
        accounts.exec(c, """
                insert into mst_cust (comp_cd, cust_cd, cust_nm, cust_type, created_by, updated_by)
                values (?, 'C-1', 'C-1 상사', 'SALES', 't', 't') on conflict do nothing""", c);
        Long stg = accounts.query(c, "select max(node_id) from mst_equip_hier where plant_cd = ? and node_cd = 'STG'", Long.class, plant);
        if (stg == null) {
            long wh = accounts.query(c, """
                    insert into mst_equip_hier (comp_cd, plant_cd, node_type, node_cd, node_nm, created_by, updated_by)
                    values (?, ?, 'WH', 'FG-WH', '제품 창고', 't', 't') returning node_id""", Long.class, c, plant);
            stg = accounts.query(c, """
                    insert into mst_equip_hier (comp_cd, plant_cd, parent_id, node_type, node_cd, node_nm, created_by, updated_by)
                    values (?, ?, ?, 'LOC', 'STG', '출하 대기 칸', 't', 't') returning node_id""", Long.class, c, plant, wh);
            accounts.exec(c, "insert into wms_loc_attr (node_id, comp_cd, loc_usage, created_by, updated_by) values (?, ?, 'STAGING', 't', 't')", stg, c);
        }
        accounts.exec(c, """
                insert into wms_ship (comp_cd, plant_cd, ship_no, cust_id, stage_node_id, plan_date, created_by, updated_by)
                select ?, ?, ?, cust_id, ?, ?, 't', 't' from mst_cust where cust_cd = 'C-1'""", c, plant, no, stg, planDate);
        // DRAFT → PICKING → SHIPPED, 취소는 DRAFT 에서만(트리거 전이)
        if ("PICKING".equals(status) || "SHIPPED".equals(status)) {
            accounts.exec(c, "update wms_ship set status = 'PICKING' where ship_no = ?", no);
        }
        if ("SHIPPED".equals(status)) {
            accounts.exec(c, "update wms_ship set status = 'SHIPPED', shipped_at = now(), shipped_by = 't' where ship_no = ?", no);
        }
        if ("CANCELLED".equals(status)) {
            accounts.exec(c, "update wms_ship set status = 'CANCELLED', cancel_reason = '시험' where ship_no = ?", no);
        }
    }

    // ---------- 도우미 ----------

    private String home(RequestPostProcessor who, String plant) throws Exception {
        MockHttpServletRequestBuilder r = get(CARDS).with(who);
        if (plant != null) {
            r.param("plant", plant);
        }
        return mvc.perform(r).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
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

    /** 링크 조건 그대로 목록 API 를 불러 건수(페이지 목록은 total, 그 밖은 data 길이) */
    private long listCount(String url, Map<String, String> params) throws Exception {
        MockHttpServletRequestBuilder r = get(url).with(me);
        params.forEach(r::param);
        if (url.endsWith("/capas")) {
            r.param("total", "true").param("size", "200");
        }
        String body = mvc.perform(r).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        Object n = url.endsWith("/capas") ? JsonPath.read(body, "$.total")
                : body.contains("\"data\":[") ? JsonPath.read(body, "$.data.length()") : JsonPath.read(body, "$.list.length()");
        return ((Number) n).longValue();
    }

    // ---------- 결재 카드 링크 (H19) ----------

    @Test
    void 결재_카드는_결재함으로_이어지고_권한_없이도_보이며_숫자_링크는_없다() throws Exception {
        long plain = accounts.newUser(comp, "plain1", PW);
        accounts.grant(comp, plain, "HOMEONLY", HomeService.PROG, "SEARCH");
        mvc.perform(get(CARDS).with(accounts.as(comp, plain, "plain1")))
                .andExpect(jsonPath(card("apr-todo") + ".progCd").value(Matchers.contains("sys-approval")))
                .andExpect(jsonPath(card("apr-todo") + ".progTitle").value(Matchers.contains(Matchers.not(Matchers.emptyOrNullString()))))
                .andExpect(jsonPath(card("apr-todo") + ".link").value(Matchers.contains((Object) null)))
                // 모듈 카드는 화면 권한이 없어 안 보인다(R1)
                .andExpect(jsonPath("$.data.cards[*].code").value(Matchers.not(Matchers.hasItem("qms-capa-overdue"))));
    }

    // ---------- 공장 선택 (H20·H21) ----------

    @Test
    void 공장_목록은_사용_중인_우리_공장만_없는_공장_사용중지_다른_회사_공장은_400() throws Exception {
        accounts.addPlant(comp, "P9", "닫은 공장");
        accounts.exec(comp, "update sys_plant set use_yn = 'N' where plant_cd = 'P9'");
        String b = accounts.newComp("HX");
        accounts.addPlant(b, "BP1", "B사 공장");
        mvc.perform(get("/api/home/plants").with(me))
                .andExpect(jsonPath("$.list[*].plantCd").value(Matchers.contains("P1", "P2")));
        for (String bad : new String[] {"P9", "BP1", "NOPE", "p1", "X".repeat(21)}) {
            mvc.perform(get(CARDS).param("plant", bad).with(me)).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors[0].field").value("plant"));
            mvc.perform(get(CARDS + "/cms-bm-open").param("plant", bad).with(me)).andExpect(status().isBadRequest());
        }
        // 빈 값 = 전체
        mvc.perform(get(CARDS).param("plant", "").with(me)).andExpect(status().isOk()).andExpect(jsonPath("$.data.plant").doesNotExist());
        long none = accounts.newUser(comp, "none1", PW);
        mvc.perform(get("/api/home/plants").with(accounts.as(comp, none, "none1"))).andExpect(status().isForbidden());
    }

    @Test
    void 공장을_고르면_공장_카드만_거르고_공장_시간대_오늘을_쓴다() throws Exception {
        mwo(comp, "BM-1", "BM", "P1", "EQ-1", "REQ", 1, "Y");
        mwo(comp, "BM-2", "BM", "P2", "EQ-2", "OPEN", 2, "N");
        capa(comp, "CA-1", today.minusDays(20), today.minusDays(5));
        String all = home(me, null);
        assertThat(num(all, "cms-bm-open")).isEqualTo(2);
        String p1 = home(me, "P1");
        assertThat(num(p1, "cms-bm-open")).isEqualTo(1);
        assertThat(num(p1, "qms-capa-overdue")).as("회사 카드는 공장과 상관없다").isEqualTo(1);
        assertThat(JsonPath.<List<Boolean>>read(p1, card("cms-bm-open") + ".plantScoped")).containsExactly(true);
        assertThat(JsonPath.<List<Boolean>>read(p1, card("qms-capa-overdue") + ".plantScoped")).containsExactly(false);
        assertThat(link(p1, "cms-bm-open")).containsEntry("plantCd", "P1");
        assertThat(listCount("/api/cms/mwos", link(p1, "cms-bm-open"))).isEqualTo(1);
        assertThat(JsonPath.<String>read(p1, "$.data.plant")).isEqualTo("P1");

        // 공장 시간대: 회사(서울) 2026-10-21 08:00 = UTC 2026-10-20 23:00 → 로스앤젤레스 공장은 아직 10-20
        accounts.exec(comp, "update sys_plant set time_zone = 'America/Los_Angeles' where plant_cd = 'P2'");
        clock.setClock(Clock.fixed(Instant.parse("2026-10-20T23:00:00Z"), ZoneOffset.UTC));
        String p2 = home(me, "P2");
        assertThat(JsonPath.<String>read(p2, "$.data.today")).isEqualTo("2026-10-21");
        assertThat(JsonPath.<String>read(p2, "$.data.plantToday")).isEqualTo("2026-10-20");
        // 점검 카드는 공장 날짜로 창을 잡는다: 끝 = 공장 오늘 + 7
        assertThat(link(p2, "cms-chk-due")).containsEntry("to", "2026-10-27").containsEntry("plantCd", "P2");
        // 고장 카드는 목록이 회사 시간대로 자르므로 회사 날짜(H21)
        assertThat(link(p2, "cms-bm-open")).containsEntry("to", "2026-10-21");
    }

    // ---------- 새 카드: 숫자 = 목록 건수 ----------

    @Test
    void 시정조치_카드는_종결_기한_지남만_세고_목록과_같다() throws Exception {
        capa(comp, "CA-OVER", today.minusDays(30), today.minusDays(1));   // 종결 기한 지남
        capa(comp, "CA-CONT", today.minusDays(2), today.plusDays(10));    // 봉쇄 기한만 지남
        capa(comp, "CA-OK", today.plusDays(1), today.plusDays(10));       // 정상
        capa(comp, "CA-CANC", today.minusDays(30), today.minusDays(2));   // 취소 — 세지 않는다
        accounts.exec(comp, "update qms_capa set status = 'CANCELLED', cancel_reason = '시험' where capa_no = 'CA-CANC'");
        String h = home(me, null);
        assertThat(num(h, "qms-capa-overdue")).isEqualTo(1);
        assertThat(JsonPath.<List<Integer>>read(h, card("qms-capa-overdue") + ".facts[0].value")).containsExactly(1);
        assertThat(JsonPath.<List<String>>read(h, card("qms-capa-overdue") + ".items[*].title")).containsExactly("CA-OVER");
        assertThat(listCount("/api/qms/capas", link(h, "qms-capa-overdue"))).as("목록 = 카드 숫자").isEqualTo(1);
    }

    @Test
    void 점검_카드는_창_안_열린_점검을_세고_창_밖은_보조_숫자() throws Exception {
        run(comp, "EQ-1", today.minusDays(3), "OPEN");    // 지남
        run(comp, "EQ-1", today, "OPEN");                 // 오늘
        run(comp, "EQ-2", today.plusDays(5), "OPEN");     // 7일 안
        run(comp, "EQ-2", today.plusDays(9), "OPEN");     // 7일 밖 — 제외
        run(comp, "EQ-1", today.minusDays(1), "SKIPPED"); // 건너뜀 — 제외
        String h = home(me, null);
        assertThat(num(h, "cms-chk-due")).isEqualTo(3);
        assertThat(listCount("/api/cms/chk-runs", link(h, "cms-chk-due"))).as("목록 = 카드 숫자").isEqualTo(3);
        assertThat(num(home(me, "P2"), "cms-chk-due")).isEqualTo(1);
        assertThat(listCount("/api/cms/chk-runs", link(home(me, "P2"), "cms-chk-due"))).isEqualTo(1);
        // 회사 정책이 조회 기간을 30일로 줄이면 창도 30일 — 31일 전 밀린 점검은 숫자 밖·보조 숫자 안, 링크는 여전히 400 없이 같은 건수
        runner.run(comp, () -> policies.set("SEARCH_MAX_RANGE_DAYS", "*", "30", "test"));
        run(comp, "EQ-1", today.minusDays(40), "OPEN");
        String h2 = home(me, null);
        assertThat(num(h2, "cms-chk-due")).isEqualTo(3);
        assertThat(JsonPath.<List<Integer>>read(h2, card("cms-chk-due") + ".facts[2].value")).containsExactly(1);
        assertThat(JsonPath.<List<String>>read(h2, card("cms-chk-due") + ".tone")).containsExactly("ALERT");
        assertThat(listCount("/api/cms/chk-runs", link(h2, "cms-chk-due"))).isEqualTo(3);
    }

    @Test
    void 고장_카드는_진행_중_BM_을_목록_진행_중_묶음과_같게_센다() throws Exception {
        mwo(comp, "BM-REQ", "BM", "P1", "EQ-1", "REQ", 1, "Y");
        mwo(comp, "BM-OPEN", "BM", "P2", "EQ-2", "OPEN", 3, "N");
        mwo(comp, "BM-WIP", "BM", "P1", "EQ-1", "WIP", 2, "Y");     // 작업 중 — 센다(목록 "진행 중" = REQ,OPEN,WIP, H24)
        mwo(comp, "CM-OPEN", "CM", "P1", "EQ-1", "OPEN", 1, "N");   // 고장 아님 — 제외
        String h = home(me, null);
        assertThat(num(h, "cms-bm-open")).isEqualTo(3);
        assertThat(JsonPath.<List<Integer>>read(h, card("cms-bm-open") + ".facts[0].value")).containsExactly(2);   // 아직 손대지 않음
        assertThat(JsonPath.<List<Integer>>read(h, card("cms-bm-open") + ".facts[1].value")).containsExactly(2);   // 설비 정지
        assertThat(JsonPath.<List<String>>read(h, card("cms-bm-open") + ".items[0].title")).containsExactly("BM-REQ");   // 정지·미착수 먼저
        assertThat(link(h, "cms-bm-open")).containsEntry("status", "REQ,OPEN,WIP");
        assertThat(listCount("/api/cms/mwos", link(h, "cms-bm-open"))).as("목록 = 카드 숫자").isEqualTo(3);
    }

    @Test
    void 실사_카드는_마감_안_된_실사를_세고_숫자_링크는_없다() throws Exception {
        count(comp, "P1", "CNT-1", "DRAFT");
        count(comp, "P1", "CNT-2", "REVIEW");
        count(comp, "P2", "CNT-3", "COUNTING");
        count(comp, "P2", "CNT-4", "CANCELLED");
        String h = home(me, null);
        assertThat(num(h, "wms-count-open")).isEqualTo(3);
        assertThat(link(h, "wms-count-open")).as("H25").isNull();
        assertThat(JsonPath.<List<String>>read(h, card("wms-count-open") + ".items[0].title")).containsExactly("CNT-2");   // 검토 먼저
        assertThat(num(home(me, "P2"), "wms-count-open")).isEqualTo(1);
        // 항목 링크 = 그 실사의 공장·상태 → 목록에 그 실사가 있다
        @SuppressWarnings("unchecked")
        Map<String, String> item = ((List<Map<String, String>>) JsonPath.read(h, card("wms-count-open") + ".items[0].link")).get(0);
        assertThat(item).containsEntry("plantCd", "P1").containsEntry("status", "REVIEW");
        assertThat(listCount("/api/wms/counts", item)).isEqualTo(1);
    }

    // ---------- 6단계: 사유 미입력 비가동 (docs/37 18.1, H29) ----------

    /** 상태 구간 — 시작 daysAgo 일 전(시각 = 지금 기준), 길이 hours(null = 열린 구간) */
    private void state(String c, String equipCd, String cat, Long reasonId, int daysAgo, Integer hours) {
        accounts.exec(c, """
                insert into cms_equip_state_log (comp_cd, equip_id, state_cat, reason_id, start_at, end_at, source, occurred_at, entry_method, recorded_by)
                values (?, ?, ?, ?, now() - make_interval(days => ?), case when ?::int is null then null
                        else now() - make_interval(days => ?) + make_interval(hours => ?::int) end, 'AUTO', now() - make_interval(days => ?), 'AUTO', 'col')""",
                c, equip(c, equipCd), cat, reasonId, daysAgo, hours, daysAgo, hours, daysAgo);
    }

    @Test
    void 사유_미입력_비가동은_최근_7일_사유_없는_정지만_세고_공장을_고르면_목록과_같다() throws Exception {
        long reason = accounts.query(comp, """
                insert into cms_state_reason (comp_cd, reason_cd, reason_nm, state_cat, created_by, updated_by)
                values (?, 'R-PM', '계획 보전', 'SDT', 't', 't') returning reason_id""", Long.class, comp);
        state(comp, "EQ-1", "UDT", null, 3, 2);       // 사유 없음 — 센다
        state(comp, "EQ-1", "SDT", reason, 2, 1);     // 원장 사유 있음 — 제외
        state(comp, "EQ-1", "PRD", null, 1, 3);       // 생산 — 제외
        state(comp, "EQ-1", "UDT", null, 20, 2);      // 7일 밖 — 제외
        state(comp, "EQ-2", "SBY", null, 1, null);    // 사유 없는 열린 대기 — 센다(지금도 이어짐)
        String all = home(me, null);
        assertThat(num(all, "cms-state-pending")).isEqualTo(2);
        assertThat(JsonPath.<List<Integer>>read(all, card("cms-state-pending") + ".facts[0].value")).containsExactly(1);
        assertThat(link(all, "cms-state-pending")).as("전체 공장은 링크 없음(목록이 공장 필수)").isNull();
        String p1 = home(me, "P1");
        assertThat(num(p1, "cms-state-pending")).isEqualTo(1);
        Map<String, String> l = link(p1, "cms-state-pending");
        assertThat(l).containsEntry("plantCd", "P1").containsEntry("pending", "Y");
        assertThat(listCount("/api/cms/state-review", l)).as("목록 = 카드 숫자").isEqualTo(1);
        // 항목 링크는 그 행의 공장·그 날 하루 — 전체 공장일 때도
        @SuppressWarnings("unchecked")
        Map<String, String> item = ((List<Map<String, String>>) JsonPath.read(all, card("cms-state-pending") + ".items[0].link")).get(0);
        assertThat(item).containsEntry("pending", "Y").containsKey("plantCd");
        assertThat(listCount("/api/cms/state-review", item)).isGreaterThanOrEqualTo(1);
    }

    // ---------- 8단계: 출하 대기 (docs/37 20장, H32) ----------

    @Test
    void 출하_대기는_작성_피킹_지시를_세고_숫자_링크는_없으며_항목은_목록에_있다() throws Exception {
        ship(comp, "P1", "SH-1", "DRAFT", today);
        ship(comp, "P1", "SH-2", "PICKING", today.minusDays(2));   // 늦은 피킹 — 맨 위
        ship(comp, "P2", "SH-3", "DRAFT", today.plusDays(3));
        ship(comp, "P1", "SH-4", "SHIPPED", today.minusDays(5));   // 나감 — 제외
        ship(comp, "P1", "SH-5", "CANCELLED", today.minusDays(5)); // 취소 — 제외
        String h = home(me, null);
        assertThat(num(h, "wms-ship-open")).isEqualTo(3);
        assertThat(link(h, "wms-ship-open")).as("H32").isNull();
        assertThat(JsonPath.<List<Integer>>read(h, card("wms-ship-open") + ".facts[0].value")).containsExactly(1);   // 피킹 중
        assertThat(JsonPath.<List<Integer>>read(h, card("wms-ship-open") + ".facts[1].value")).containsExactly(1);   // 계획일 지남
        assertThat(JsonPath.<List<String>>read(h, card("wms-ship-open") + ".tone")).containsExactly("WARN");
        assertThat(JsonPath.<List<String>>read(h, card("wms-ship-open") + ".items[0].title")).containsExactly("SH-2");
        assertThat(JsonPath.<List<String>>read(h, card("wms-ship-open") + ".items[0].tone")).containsExactly("WARN");
        assertThat(num(home(me, "P2"), "wms-ship-open")).isEqualTo(1);
        // 항목 링크 = 그 지시의 공장·상태 → 출하 목록에 그 지시가 있다
        @SuppressWarnings("unchecked")
        Map<String, String> item = ((List<Map<String, String>>) JsonPath.read(h, card("wms-ship-open") + ".items[0].link")).get(0);
        assertThat(item).containsOnlyKeys("plantCd", "status").containsEntry("plantCd", "P1").containsEntry("status", "PICKING");
        MockHttpServletRequestBuilder r = get("/api/wms/ships").with(me);
        item.forEach(r::param);
        String list = mvc.perform(r).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<List<String>>read(list, "$.list[*].shipNo")).containsExactly("SH-2");
        // P2 만 고르면 늦은 것 없음 → INFO
        assertThat(JsonPath.<List<String>>read(home(me, "P2"), card("wms-ship-open") + ".tone")).containsExactly("INFO");
    }

    // ---------- 권한·A사로 B사 ----------

    @Test
    void 새_카드는_화면_권한이_있을_때만() throws Exception {
        long u = accounts.newUser(comp, "qc1", PW);
        accounts.grant(comp, u, "QC", HomeService.PROG, "SEARCH");
        accounts.grant(comp, u, "QC", "qms-capa", "SEARCH");
        mvc.perform(get(CARDS).with(accounts.as(comp, u, "qc1")))
                .andExpect(jsonPath("$.data.cards[*].code").value(Matchers.hasItem("qms-capa-overdue")))
                .andExpect(jsonPath("$.data.cards[*].code").value(Matchers.not(Matchers.hasItems("cms-chk-due", "cms-bm-open", "wms-count-open", "wms-ship-open"))));
        mvc.perform(get(CARDS + "/cms-bm-open").with(accounts.as(comp, u, "qc1"))).andExpect(status().isNotFound());
    }

    @Test
    void A사_카드에_B사_건수가_섞이지_않는다() throws Exception {
        String b = accounts.newComp("HX");
        long ub = accounts.newUser(b, "user1", PW);
        grant(b, ub);
        fixtures(b);
        LocalDate bt = today;
        capa(b, "CA-B", bt.minusDays(30), bt.minusDays(1));
        run(b, "EQ-1", bt, "OPEN");
        mwo(b, "BM-B", "BM", "P1", "EQ-1", "REQ", 1, "N");
        count(b, "P1", "CNT-B", "DRAFT");
        ship(b, "P1", "SH-B", "DRAFT", bt);
        String a = home(me, null);
        for (String code : List.of("qms-capa-overdue", "cms-chk-due", "cms-bm-open", "wms-count-open", "wms-ship-open")) {
            assertThat(num(a, code)).as(code).isZero();
        }
        String bh = home(accounts.as(b, ub, "user1"), "P1");
        for (String code : List.of("qms-capa-overdue", "cms-chk-due", "cms-bm-open", "wms-count-open", "wms-ship-open")) {
            assertThat(num(bh, code)).as("B " + code).isEqualTo(1);
        }
        // 같은 공장 코드 P1 이라도 A사 사용자는 A사 P1
        assertThat(num(home(me, "P1"), "cms-bm-open")).isZero();
        assertThatThrownBy(() -> accounts.exec(comp, """
                insert into cms_mwo (comp_cd, mwo_no, mwo_type, plant_cd, target_type, equip_id, title, status, reported_at, created_by, updated_by)
                values (?, 'X', 'BM', 'P1', 'EQUIP', ?, 'x', 'REQ', now(), 't', 't')""", b, equip(b, "EQ-1")))
                .as("A사 범위에서 B사 행 넣기").isNotNull();
        assertThat(accounts.query(b, "select count(*) from cms_mwo where mwo_no = 'X'", Integer.class)).isZero();
    }
}
