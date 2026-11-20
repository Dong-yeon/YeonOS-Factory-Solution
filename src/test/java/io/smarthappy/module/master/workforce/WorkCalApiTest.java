package io.smarthappy.module.master.workforce;

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

import io.smarthappy.module.master.workforce.WorkCalendar.ShiftDay;
import io.smarthappy.platform.common.db.PgErrors;
import io.smarthappy.platform.tenant.TenantRunner;
import io.smarthappy.support.PostgresTestBase;
import io.smarthappy.support.TestAccounts;

/**
 * 공장 휴일 달력 (docs/25 4.15) — 한 달 조회(층 판정·정책 공장 덮어쓰기), 저장·고치기·사용 중지, 읽기 계약 WorkCalendar,
 * 이상 사용(값·중복·rowVer·대상 고정·직접 SQL·삭제 금지), A사로 B사. 2026-11-01 = 일요일 → days[n] = 11월 (n+1)일.
 */
@AutoConfigureMockMvc
class WorkCalApiTest extends PostgresTestBase {

    private static final String PW = "Cobalt-river-82";
    private static final String CAL = "/api/master/work-cal";

    @Autowired
    MockMvc mvc;

    @Autowired
    TestAccounts accounts;

    @Autowired
    TenantRunner runner;

    @Autowired
    WorkCalendar calendar;

    String comp;
    RequestPostProcessor admin;

    @BeforeEach
    void setUp() {
        comp = accounts.newComp("WCL");
        admin = user(comp, "admin1");
        seed(comp);
    }

    private RequestPostProcessor user(String c, String loginId) {
        long u = accounts.newUser(c, loginId, PW);
        accounts.grant(c, u, "MASTER", WorkCalService.PROG, "SEARCH", "SAVE");
        return accounts.as(c, u, loginId);
    }

    /** 공장 P1(주간 D·야간 N·사용 중지 X), P2(근무조 없음) */
    private void seed(String c) {
        accounts.addPlant(c, "P1", "1공장");
        accounts.addPlant(c, "P2", "2공장");
        accounts.exec(c, """
                insert into mst_shift (comp_cd, plant_cd, shift_cd, shift_nm, start_time, end_time, use_yn, created_by, updated_by)
                values (?, 'P1', 'D', '주간', '08:00', '20:00', 'Y', 't', 't'), (?, 'P1', 'N', '야간', '20:00', '08:00', 'Y', 't', 't'),
                       (?, 'P1', 'X', '옛 조', '06:00', '07:00', 'N', 't', 't')""", c, c, c);
    }

    private MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder b, String body) {
        return b.with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private ResultActions create(RequestPostProcessor who, String plant, String date, String shift, String type, String reason) throws Exception {
        return mvc.perform(json(post(CAL), "{\"plantCd\":" + q(plant) + ",\"calDate\":" + q(date) + ",\"shiftCd\":" + q(shift)
                + ",\"dayType\":" + q(type) + ",\"reason\":" + q(reason) + "}").with(who));
    }

    private static String q(String v) {
        return v == null ? "null" : "\"" + v + "\"";
    }

    private static long id(ResultActions r) throws Exception {
        MvcResult m = r.andExpect(status().isOk()).andReturn();
        return ((Number) JsonPath.read(m.getResponse().getContentAsString(), "$.data")).longValue();
    }

    private int rowVer(long calId) {
        return accounts.query(comp, "select row_ver from mst_work_cal where cal_id = ?", Integer.class, calId);
    }

    private void policy(String c, String plant, String value) {
        accounts.exec(c, "insert into sys_policy (comp_cd, plant_cd, policy_key, policy_value, created_by, updated_by) values (?, ?, 'WEEKLY_OFF', ?, 't', 't')",
                c, plant, value);
    }

    // ---------- 정상 ----------

    @Test
    void 한_달_조회는_층_우선순위와_공장_정책을_따르고_읽기_계약도_같다() throws Exception {
        policy(comp, "*", "SAT_SUN");      // 회사: 주 5일
        policy(comp, "P1", "SUN");         // P1: 일요일만 쉼
        id(create(admin, null, "2026-11-02", null, "OFF", "회사 창립일"));
        id(create(admin, "P1", "2026-11-02", null, "WORK", "대체 근무"));
        id(create(admin, "p1", "2026-11-08", "d", "work", "주말 특근"));   // 코드는 대문자로 정리

        mvc.perform(get(CAL).param("month", "2026-11").with(admin)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.weeklyOff").value("SAT_SUN"))
                .andExpect(jsonPath("$.data.days.length()").value(30))
                .andExpect(jsonPath("$.data.days[0].source").value("WEEKLY"))      // 11-01 일
                .andExpect(jsonPath("$.data.days[1].workday").value(false))       // 11-02 월 — 회사 휴일
                .andExpect(jsonPath("$.data.days[1].source").value("COMP_CAL"))
                .andExpect(jsonPath("$.data.days[2].workday").value(true))
                .andExpect(jsonPath("$.data.days[6].workday").value(false))       // 11-07 토
                .andExpect(jsonPath("$.data.rows.length()").value(1))
                .andExpect(jsonPath("$.data.shifts.length()").value(0));
        mvc.perform(get(CAL).param("plantCd", "P1").param("month", "2026-11").with(admin)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.weeklyOff").value("SUN"))
                .andExpect(jsonPath("$.data.days[1].workday").value(true))
                .andExpect(jsonPath("$.data.days[1].source").value("WORK_CAL"))
                .andExpect(jsonPath("$.data.days[1].reason").value("대체 근무"))
                .andExpect(jsonPath("$.data.days[6].workday").value(true))                  // 토요일 — P1 은 일요일만 쉼
                .andExpect(jsonPath("$.data.days[0].workday").value(false))                 // 11-01 일
                .andExpect(jsonPath("$.data.days[7].shifts[*].shiftCd", Matchers.contains("D")))   // 11-08 일 — 주간조 특근
                .andExpect(jsonPath("$.data.rows[*].reason", Matchers.contains("회사 창립일", "대체 근무", "주말 특근")))
                .andExpect(jsonPath("$.data.shifts[*].shiftCd", Matchers.contains("D", "N")));   // 사용 중지 X 는 선택지에 없음

        // 다른 모듈이 읽는 계약
        assertThat(runner.call(comp, () -> calendar.isWorkday(null, LocalDate.parse("2026-11-02")))).isFalse();
        assertThat(runner.call(comp, () -> calendar.isWorkday("P1", LocalDate.parse("2026-11-02")))).isTrue();
        assertThat(runner.call(comp, () -> calendar.isWorkday("P2", LocalDate.parse("2026-11-02")))).isFalse();   // P2 는 회사 휴일
        assertThat(runner.call(comp, () -> calendar.shiftsOn("P1", LocalDate.parse("2026-11-08")))).extracting(ShiftDay::shiftCd)
                .containsExactly("D");
        assertThat(runner.call(comp, () -> calendar.range("P1", LocalDate.parse("2026-01-01"), LocalDate.parse("2027-02-04")))).hasSize(400);
        Throwable tooLong = catchThrowable(() -> runner.call(comp,
                () -> calendar.range("P1", LocalDate.parse("2026-01-01"), LocalDate.parse("2027-02-05"))));
        assertThat(tooLong).isNotNull();
    }

    @Test
    void 고치기는_구분과_사유만_사용_중지_뒤에는_같은_날을_다시_넣을_수_있다() throws Exception {
        long id = id(create(admin, "P1", "2026-12-24", null, "OFF", "성탄 전야"));
        // 대상(날짜)을 바꾸려 해도 무시 — 구분·사유만
        mvc.perform(json(put(CAL + "/" + id), "{\"calDate\":\"2026-12-25\",\"dayType\":\"WORK\",\"reason\":\"납기 대응 근무\",\"rowVer\":"
                + rowVer(id) + "}").with(admin)).andExpect(status().isOk());
        assertThat(accounts.query(comp, "select cal_date::text || '/' || day_type || '/' || reason from mst_work_cal where cal_id = ?",
                String.class, id)).isEqualTo("2026-12-24/WORK/납기 대응 근무");
        // 옛 rowVer → 409
        mvc.perform(json(put(CAL + "/" + id), "{\"dayType\":\"OFF\",\"reason\":\"x\",\"rowVer\":0}").with(admin)).andExpect(status().isConflict());
        mvc.perform(json(post(CAL + "/" + id + "/disable"), "{\"rowVer\":" + rowVer(id) + "}").with(admin)).andExpect(status().isOk());
        mvc.perform(get(CAL).param("plantCd", "P1").param("month", "2026-12").with(admin)).andExpect(jsonPath("$.data.rows.length()").value(0));
        // 사용 중지된 행은 다시 고치거나 끌 수 없다(409), 같은 날 새 행은 된다
        mvc.perform(json(post(CAL + "/" + id + "/disable"), "{\"rowVer\":" + rowVer(id) + "}").with(admin)).andExpect(status().isConflict());
        id(create(admin, "P1", "2026-12-24", null, "OFF", "성탄 전야(다시)"));
    }

    // ---------- 이상 사용 ----------

    @Test
    void A_값_검사와_중복() throws Exception {
        create(admin, null, "2026-11-02", "D", "OFF", "회사인데 근무조").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("shiftCd")).andExpect(jsonPath("$.errors[0].code").value("SHIFT_NEEDS_PLANT"));
        create(admin, "P1", "2026-11-02", "Z", "OFF", "없는 조").andExpect(jsonPath("$.errors[0].code").value("NO_SUCH_CODE"));
        create(admin, "P1", "2026-11-02", "X", "OFF", "끈 조").andExpect(jsonPath("$.errors[0].code").value("INACTIVE"));
        create(admin, "P2", "2026-11-02", "D", "OFF", "다른 공장 조").andExpect(jsonPath("$.errors[0].code").value("NO_SUCH_CODE"));
        create(admin, "PX", "2026-11-02", null, "OFF", "없는 공장").andExpect(jsonPath("$.errors[0].field").value("plantCd"))
                .andExpect(jsonPath("$.errors[0].code").value("NO_SUCH_CODE"));
        create(admin, "P1", "2026-13-01", null, "HALF", " ").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[*].code", Matchers.containsInAnyOrder("FORMAT", "CODE", "REQUIRED")));
        create(admin, "P1", "2026-11-02", null, "OFF", "가".repeat(201)).andExpect(jsonPath("$.errors[0].code").value("TOO_LONG"));
        mvc.perform(get(CAL).param("month", "2026/11").with(admin)).andExpect(jsonPath("$.errors[0].code").value("FORMAT"));
        mvc.perform(get(CAL).with(admin)).andExpect(jsonPath("$.errors[0].code").value("REQUIRED"));
        // 같은 범위·날짜·근무조는 하나 — 회사 행과 공장 행, 하루와 근무조는 서로 다른 칸
        id(create(admin, "P1", "2026-11-02", null, "OFF", "휴무"));
        create(admin, "P1", "2026-11-02", null, "WORK", "또").andExpect(jsonPath("$.errors[0].field").value("calDate"))
                .andExpect(jsonPath("$.errors[0].code").value("DUPLICATE"));
        id(create(admin, null, "2026-11-02", null, "OFF", "회사"));
        id(create(admin, "P1", "2026-11-02", "N", "WORK", "야간 특근"));
        // 권한 없는 사용자
        long v = accounts.newUser(comp, "viewer", PW);
        mvc.perform(get(CAL).param("month", "2026-11").with(accounts.as(comp, v, "viewer"))).andExpect(status().isForbidden());
    }

    @Test
    void C_직접_SQL_대상_고정_근무조_소속_삭제_금지() throws Exception {
        long id = id(create(admin, "P1", "2026-11-03", null, "OFF", "휴무"));
        Throwable t = catchThrowable(() -> accounts.exec(comp, "update mst_work_cal set cal_date = '2026-11-04' where cal_id = ?", id));
        assertThat(PgErrors.constraint(t)).contains("ck_mst_work_cal_key_fixed");
        t = catchThrowable(() -> accounts.exec(comp, """
                insert into mst_work_cal (comp_cd, plant_cd, cal_date, shift_cd, day_type, reason, created_by, updated_by)
                values (?, 'P2', '2026-11-03', 'D', 'OFF', 'x', 't', 't')""", comp));
        assertThat(PgErrors.constraint(t)).contains("fk_mst_work_cal_shift");
        t = catchThrowable(() -> accounts.exec(comp, """
                insert into mst_work_cal (comp_cd, plant_cd, cal_date, shift_cd, day_type, reason, created_by, updated_by)
                values (?, null, '2026-11-03', 'D', 'OFF', 'x', 't', 't')""", comp));
        assertThat(PgErrors.constraint(t)).contains("ck_mst_work_cal_shift_plant");
        t = catchThrowable(() -> accounts.exec(comp, "delete from mst_work_cal where cal_id = ?", id));
        assertThat(t).isNotNull();
        assertThat(accounts.query(comp, "select count(*) from mst_work_cal", Integer.class)).isEqualTo(1);
    }

    @Test
    void B_A사로_B사_조회_고치기_사용_중지_직접_SQL() throws Exception {
        long aRow = id(create(admin, "P1", "2026-11-03", null, "OFF", "A 휴무"));
        String other = accounts.newComp("WCX");
        RequestPostProcessor b = user(other, "u1");
        seed(other);
        mvc.perform(get(CAL).param("plantCd", "P1").param("month", "2026-11").with(b)).andExpect(jsonPath("$.data.rows.length()").value(0))
                .andExpect(jsonPath("$.data.days[2].workday").value(true));
        mvc.perform(json(put(CAL + "/" + aRow), "{\"dayType\":\"WORK\",\"reason\":\"B 가 바꿈\",\"rowVer\":0}").with(b))
                .andExpect(status().isNotFound());
        mvc.perform(json(post(CAL + "/" + aRow + "/disable"), "{\"rowVer\":0}").with(b)).andExpect(status().isNotFound());
        assertThat(runner.call(other, () -> calendar.isWorkday("P1", LocalDate.parse("2026-11-03")))).isTrue();
        // B 범위에서 A 회사 코드로 넣기 → 거부(RLS 또는 회사 FK)
        Throwable t = catchThrowable(() -> accounts.exec(other, """
                insert into mst_work_cal (comp_cd, plant_cd, cal_date, day_type, reason, created_by, updated_by)
                values (?, null, '2026-11-04', 'OFF', 'x', 't', 't')""", comp));
        assertThat(t).isNotNull();
        assertThat(accounts.query(comp, "select reason from mst_work_cal where cal_id = ?", String.class, aRow)).isEqualTo("A 휴무");
    }
}
