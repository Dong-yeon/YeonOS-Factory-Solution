package io.smarthappy.module.cms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import io.smarthappy.module.cms.CmsPmDtos.CalItem;
import io.smarthappy.module.cms.CmsPmDtos.CalView;
import io.smarthappy.platform.tenant.TenantRunner;
import io.smarthappy.platform.time.BizClock;
import io.smarthappy.support.PostgresTestBase;
import io.smarthappy.support.TestAccounts;

/**
 * C27 예방점검 달력 보기 (docs/33 13.14) — 공장 × 월, 읽기 전용. 월 범위·오늘 = 공장 시간대, RUN(그 달 점검 실행, 상태 무관) +
 * FORECAST(사용 중 달력 주기 계획의 아직 없는 예정 — FIXED 는 월말에 날이 밀리지 않고 윤년 2/29, FLOAT 는 지난 예정을 오늘 했다고 보고),
 * 사용량 주기는 RUN 만, 조건 400, SEARCH 권한, A사로 B사.
 */
@AutoConfigureMockMvc
class CmsPmCalendarTest extends PostgresTestBase {

    private static final String PW = "Maple-anchor-63";
    private static final String CAL = "/api/cms/pm-plans/calendar";

    @Autowired
    MockMvc mvc;

    @Autowired
    TestAccounts accounts;

    @Autowired
    TenantRunner runner;

    @Autowired
    PmPlanService plans;

    @Autowired
    BizClock clock;

    String comp;
    long tmpl;
    RequestPostProcessor admin;

    @BeforeEach
    void setUp() {
        comp = accounts.newComp("PC");
        accounts.addPlant(comp, "P1", "1공장");
        accounts.addPlant(comp, "P2", "2공장");
        accounts.exec(comp, "update sys_plant set time_zone = 'Pacific/Kiritimati' where plant_cd = 'P2'");   // UTC+14
        long u = accounts.newUser(comp, "admin1", PW);
        accounts.grant(comp, u, "CMS", "cms-pm-plan", "SEARCH");
        admin = accounts.as(comp, u, "admin1");
        long cls = accounts.query(comp, "insert into mst_equip_class (comp_cd, class_cd, class_nm, created_by, updated_by) values (?, 'PRESS', 'p', 't', 't') returning class_id",
                Long.class, comp);
        accounts.exec(comp, """
                insert into mst_equip (comp_cd, plant_cd, equip_cd, equip_nm, class_id, created_by, updated_by)
                values (?, 'P1', 'E1', '설비 1', ?, 't', 't'), (?, 'P2', 'E2', '설비 2', ?, 't', 't')""", comp, cls, comp, cls);
        tmpl = accounts.query(comp, "insert into cms_chk_tmpl (comp_cd, tmpl_cd, tmpl_nm, created_by, updated_by) values (?, 'T', 't', 't', 't') returning tmpl_id",
                Long.class, comp);
    }

    @AfterEach
    void resetClock() {
        clock.setClock(Clock.systemUTC());
    }

    private void at(String instant) {
        clock.setClock(Clock.fixed(Instant.parse(instant), ZoneOffset.UTC));
    }

    /** 달력 주기 계획(CHECK 방식) */
    private long plan(String cd, String equip, String basis, String unit, int iv, String anchor, String next, String useYn) {
        return accounts.query(comp, """
                insert into cms_pm_plan (comp_cd, plan_cd, plan_nm, target_type, equip_id, tmpl_id, exec_mode, cal_unit, cal_interval,
                                         schedule_basis, anchor_date, next_due_date, use_yn, created_by, updated_by)
                select ?, ?, ?, 'EQUIP', equip_id, ?, 'CHECK', ?, ?, ?, ?::date, ?::date, ?, 't', 't' from mst_equip where equip_cd = ?
                returning plan_id""", Long.class, comp, cd, cd + " 이름", tmpl, unit, iv, basis, anchor, next, useYn, equip);
    }

    private long run(long planId, String due) {
        return accounts.query(comp, """
                insert into cms_chk_run (comp_cd, plan_id, target_type, equip_id, tmpl_id, due_date, created_by, updated_by)
                select ?, plan_id, 'EQUIP', equip_id, tmpl_id, ?::date, 't', 't' from cms_pm_plan where plan_id = ? returning run_id""",
                Long.class, comp, due, planId);
    }

    private CalView view(String plant, String month) {
        return runner.call(comp, () -> plans.calendar(plant, month));
    }

    private static List<String> dates(CalView v, String planCd, String kind) {
        return v.items().stream().filter(i -> i.planCd().equals(planCd) && i.kind().equals(kind)).map(CalItem::date).toList();
    }

    @Test
    void 월을_비우면_공장_오늘의_달_월범위와_오늘은_공장_시간대() {
        at("2026-10-31T11:00:00Z");   // 서울 10/31 20:00, 키리티마티 11/01 01:00
        CalView p1 = view("P1", null);
        CalView p2 = view("P2", "");
        assertThat(p1.month()).isEqualTo("2026-10");
        assertThat(p1.today()).isEqualTo("2026-10-31");
        assertThat(p2.month()).isEqualTo("2026-11");
        assertThat(p2.today()).isEqualTo("2026-11-01");
        // 같은 예정일 10/31 이 서울 공장은 "오늘(DUE)", 키리티마티 공장은 이미 지남(OVERDUE)
        plan("A1", "E1", "FIXED", "MONTH", 1, "2026-10-31", "2026-10-31", "Y");
        plan("A2", "E2", "FIXED", "MONTH", 1, "2026-10-31", "2026-10-31", "Y");
        assertThat(view("P1", "2026-10").items()).extracting(CalItem::planCd, CalItem::state).containsExactly(
                org.assertj.core.groups.Tuple.tuple("A1", "DUE"));
        assertThat(view("P2", "2026-10").items()).extracting(CalItem::planCd, CalItem::state).containsExactly(
                org.assertj.core.groups.Tuple.tuple("A2", "OVERDUE"));
    }

    @Test
    void 고정은_월말에_날이_밀리지_않고_윤년_2월29일_완료기준은_지난_예정을_오늘_했다고_본다() {
        at("2027-12-15T03:00:00Z");   // 서울 12/15
        plan("FX", "E1", "FIXED", "MONTH", 1, "2027-12-31", "2027-12-31", "Y");
        assertThat(dates(view("P1", "2028-02"), "FX", "FORECAST")).containsExactly("2028-02-29");
        assertThat(dates(view("P1", "2028-03"), "FX", "FORECAST")).containsExactly("2028-03-31");
        assertThat(dates(view("P1", "2028-04"), "FX", "FORECAST")).containsExactly("2028-04-30");
        // FLOAT 주 1회, 다음 예정 12/10(지남, 아직 생성 안 됨) → 12/10 OVERDUE, 그다음은 오늘 + 7 부터
        plan("FL", "E1", "FLOAT", "WEEK", 1, "2027-12-03", "2027-12-10", "Y");
        CalView dec = view("P1", "2027-12");
        assertThat(dates(dec, "FL", "FORECAST")).containsExactly("2027-12-10", "2027-12-22", "2027-12-29");
        assertThat(dec.items().stream().filter(i -> i.planCd().equals("FL")).map(CalItem::state).toList())
                .containsExactly("OVERDUE", "DUE", "UPCOMING");   // 선행일 기본 7 — 12/22 − 7 = 오늘
        // FIXED 주 1회 + 열린 예정 12/14(지남) — 그 예정은 RUN, 예상은 오늘 뒤 첫 고정 예정부터(놓친 회차 건너뜀)
        long wk = plan("WK", "E1", "FIXED", "WEEK", 1, "2027-12-07", "2027-12-14", "Y");
        long open = run(wk, "2027-12-14");
        dec = view("P1", "2027-12");
        assertThat(dec.items()).filteredOn(i -> i.planCd().equals("WK") && i.kind().equals("RUN"))
                .extracting(CalItem::runId, CalItem::status, CalItem::state).containsExactly(org.assertj.core.groups.Tuple.tuple(open, "OPEN", "OVERDUE"));
        assertThat(dates(dec, "WK", "FORECAST")).containsExactly("2027-12-21", "2027-12-28");
        // 끝난 예정(DONE)·사용 중지 계획: 실행 행은 그대로 보이고, 사용 중지는 예상 없음
        long off = plan("OF", "E1", "FIXED", "DAY", 1, "2027-12-01", "2027-12-02", "N");
        long done = run(off, "2027-12-01");
        accounts.exec(comp, "update cms_chk_run set status = 'DONE', performed_at = now(), result = 'OK' where run_id = ?", done);
        dec = view("P1", "2027-12");
        assertThat(dec.items()).filteredOn(i -> i.planCd().equals("OF")).extracting(CalItem::kind, CalItem::status, CalItem::state)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("RUN", "DONE", null));
        // 다른 공장(P2) 계획은 P1 달력에 없다
        plan("P2PLAN", "E2", "FIXED", "DAY", 1, "2027-12-16", "2027-12-16", "Y");
        assertThat(view("P1", "2027-12").items()).noneMatch(i -> i.planCd().equals("P2PLAN"));
        assertThat(dates(view("P2", "2027-12"), "P2PLAN", "FORECAST")).hasSize(16);   // 12/16~12/31
        // 날짜순(같은 날은 계획 코드순) — 화면이 그대로 칸에 넣는다
        List<String> all = dec.items().stream().map(CalItem::date).toList();
        assertThat(all).isSorted();
    }

    @Test
    void 사용량_주기는_날짜를_예측하지_않고_생성된_예정만() {
        at("2027-12-15T03:00:00Z");
        long counter = accounts.query(comp, """
                insert into cms_counter (comp_cd, counter_cd, counter_nm, target_type, equip_id, counter_kind, scale, created_by, updated_by)
                select ?, 'C1', 'c', 'EQUIP', equip_id, 'SHOTS', 1, 't', 't' from mst_equip where equip_cd = 'E1' returning counter_id""", Long.class, comp);
        long usage = accounts.query(comp, """
                insert into cms_pm_plan (comp_cd, plan_cd, plan_nm, target_type, equip_id, tmpl_id, exec_mode, cycle_type, counter_id,
                                         usage_interval, anchor_usage, next_due_usage, created_by, updated_by)
                select ?, 'US', 'u', 'EQUIP', equip_id, ?, 'CHECK', 'USAGE', ?, 1000, 1000, 1000, 't', 't' from mst_equip where equip_cd = 'E1'
                returning plan_id""", Long.class, comp, tmpl, counter);
        assertThat(view("P1", "2027-12").items()).isEmpty();
        assertThat(view("P1", "2027-12").usagePlans()).isEqualTo(1);
        accounts.exec(comp, """
                insert into cms_chk_run (comp_cd, plan_id, target_type, equip_id, tmpl_id, due_date, due_usage, created_by, updated_by)
                select ?, plan_id, 'EQUIP', equip_id, tmpl_id, '2027-12-15', 1000, 't', 't' from cms_pm_plan where plan_id = ?""", comp, usage);
        assertThat(view("P1", "2027-12").items()).extracting(CalItem::kind, CalItem::cycleType, CalItem::dueUsageText, CalItem::state)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("RUN", "USAGE", "1000.0", null));
        assertThat(view("P1", "2028-01").items()).isEmpty();
        assertThat(view("P2", "2027-12").usagePlans()).isZero();
    }

    private ResultActions call(RequestPostProcessor who, String query) throws Exception {
        return mvc.perform(get(CAL + query).with(csrf()).with(who));
    }

    @Test
    void 조건_400_SEARCH_권한_B사는_A사_계획을_못본다() throws Exception {
        at("2027-12-15T03:00:00Z");
        plan("A1", "E1", "FIXED", "DAY", 1, "2027-12-20", "2027-12-20", "Y");
        call(admin, "?plantCd=p1&month=2027-12").andExpect(status().isOk()).andExpect(jsonPath("$.data.items.length()").value(12));
        call(admin, "?month=2027-12").andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("plantCd"));
        call(admin, "?plantCd=NOPE").andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].code").value("NO_SUCH_CODE"));
        call(admin, "?plantCd=P1&month=2027-13").andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].code").value("FORMAT"));
        call(admin, "?plantCd=P1&month=2030-01").andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].code").value("RANGE"));
        call(admin, "?plantCd=P1&month=2025-11").andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].code").value("RANGE"));
        call(admin, "?plantCd=P1&month=2025-12").andExpect(status().isOk());   // 24개월 전까지
        long viewer = accounts.newUser(comp, "viewer1", PW);
        accounts.grant(comp, viewer, "VIEW", "cms-chk-run", "SEARCH");
        call(accounts.as(comp, viewer, "viewer1"), "?plantCd=P1").andExpect(status().isForbidden());
        // B사: 같은 공장 코드 P1 이 있어도 A사 계획·실행은 안 보인다, A사에만 있는 공장 P2 는 B사에 없는 코드
        String other = accounts.newComp("PD");
        accounts.addPlant(other, "P1", "B 1공장");
        long bu = accounts.newUser(other, "admin1", PW);
        accounts.grant(other, bu, "CMS", "cms-pm-plan", "SEARCH");
        RequestPostProcessor b = accounts.as(other, bu, "admin1");
        call(b, "?plantCd=P1&month=2027-12").andExpect(status().isOk()).andExpect(jsonPath("$.data.items.length()").value(0));
        call(b, "?plantCd=P2&month=2027-12").andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].code").value("NO_SUCH_CODE"));
    }
}
