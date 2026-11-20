package io.smarthappy.module.aps;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Date;
import java.time.LocalDate;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import io.smarthappy.platform.common.db.PgErrors;

/**
 * APS 4단계 (docs/38 13장) — 공장 휴일 달력 반영: 공장 휴일·회사 휴일 건너뛰기, 휴일 특근 근무조, 쉬는 날 사본(고정·달력 변경 뒤에도 그대로),
 * A사로 B사(B사 회사 휴일이 A사 계산에 안 먹음).
 */
class ApsCalendarTest extends ApsTestBase {

    private void cal(String compCd, String plant, LocalDate day, String shift, String type, String reason) {
        accounts.exec(compCd, """
                insert into mst_work_cal (comp_cd, plant_cd, cal_date, shift_cd, day_type, reason, created_by, updated_by)
                values (?, ?, ?, ?, ?, ?, 't', 't')""", compCd, plant, Date.valueOf(day), shift, type, reason);
    }

    private String cutStart(long plan, long woId) {
        return accounts.query(comp, "select (start_at at time zone 'Asia/Seoul')::text from aps_plan_op where plan_id = ? and wo_id = ? and op_seq = 10",
                String.class, plan, woId);
    }

    @Test
    void 공장_휴일은_건너뛰고_쉬는_날_사본이_남는다() throws Exception {
        long before = id(calc(admin, "P1", tomorrow, 3));
        assertThat(cutStart(before, wo1)).isEqualTo(tomorrow + " 08:00:00");
        cal(comp, "P1", tomorrow, null, "OFF", "ZT 창립기념일");
        long plan = id(calc(admin, "P1", tomorrow, 3));
        assertThat(cutStart(plan, wo1)).isEqualTo(tomorrow.plusDays(1) + " 08:00:00");
        mvc.perform(get(A + "/" + plan).with(admin)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.plan.offDayCnt").value(1));
        mvc.perform(get(A + "/" + plan + "/gantt").with(admin)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.offDays[0].day").value(tomorrow.toString()))
                .andExpect(jsonPath("$.data.offDays[0].source").value("WORK_CAL"))
                .andExpect(jsonPath("$.data.offDays[0].reason").value("ZT 창립기념일"))
                .andExpect(jsonPath("$.data.windows.length()").value(2));
        // 달력을 나중에 바꿔도 계산 때 사본 그대로, 사본은 계산 칸이라 직접 SQL 로 못 바꾼다
        accounts.exec(comp, "update mst_work_cal set use_yn = 'N'");
        mvc.perform(get(A + "/" + plan + "/gantt").with(admin)).andExpect(jsonPath("$.data.offDays.length()").value(1));
        Throwable fixed = catchThrowable(() -> accounts.exec(comp, "update aps_plan set off_days = '[]' where plan_id = ?", plan));
        assertThat(PgErrors.constraint(fixed)).contains("ck_aps_plan_fixed");
        // 이전 계획(휴일 전)은 사본 0
        mvc.perform(get(A + "/" + before).with(admin)).andExpect(jsonPath("$.data.plan.offDayCnt").value(0));
    }

    @Test
    void 회사_휴일과_휴일_특근_근무조() throws Exception {
        cal(comp, null, tomorrow, null, "OFF", "ZT 회사 휴일");
        cal(comp, "P1", tomorrow, "D", "WORK", "ZT 특근");      // 공장·근무조 예외가 회사 휴일보다 이긴다
        long plan = id(calc(admin, "P1", tomorrow, 3));
        assertThat(cutStart(plan, wo1)).isEqualTo(tomorrow + " 08:00:00");
        mvc.perform(get(A + "/" + plan).with(admin)).andExpect(jsonPath("$.data.plan.offDayCnt").value(0));
        accounts.exec(comp, "update mst_work_cal set use_yn = 'N' where shift_cd = 'D'");
        long plan2 = id(calc(admin, "P1", tomorrow, 3));
        assertThat(cutStart(plan2, wo1)).isEqualTo(tomorrow.plusDays(1) + " 08:00:00");
        mvc.perform(get(A + "/" + plan2 + "/gantt").with(admin))
                .andExpect(jsonPath("$.data.offDays[0].source").value("COMP_CAL"));
    }

    @Test
    void B사_회사_휴일은_A사_계산에_먹지_않는다() throws Exception {
        String compB = accounts.newComp("APSB");
        long ub = accounts.newUser(compB, "admin1", PW);
        accounts.grant(compB, ub, "APS", "aps-plan", "SEARCH", "SAVE");
        RequestPostProcessor b = accounts.as(compB, ub, "admin1");
        cal(compB, null, tomorrow, null, "OFF", "B사 휴일");
        long plan = id(calc(admin, "P1", tomorrow, 3));
        assertThat(cutStart(plan, wo1)).isEqualTo(tomorrow + " 08:00:00");
        mvc.perform(get(A + "/" + plan).with(admin)).andExpect(jsonPath("$.data.plan.offDayCnt").value(0));
        mvc.perform(get(A + "/" + plan + "/gantt").with(b)).andExpect(status().isNotFound());
    }
}
