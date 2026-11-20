package io.smarthappy.module.aps;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import io.smarthappy.platform.common.db.PgErrors;

/**
 * APS 5단계 ① 보존 정리 (docs/38 14.4, A35·A36) — 보존 기간 지난 폐기·미반영 계획의 결과만 정리(머리 남김), 반영 계획·최근 계획·
 * 고정 출처·법적 보류 보존, 회사 보존 기간(최소 30일), 정리된 계획은 반영·비교 불가, 앱 계정은 실행·우회 불가, A사로 B사.
 */
class ApsPurgeTest extends ApsTestBase {

    private static Connection owner() throws Exception {
        return DriverManager.getConnection(jdbcUrl(), OWNER_USER, testPassword());
    }

    /** 소유자 계정으로 정리 — 기준 시각 = 지금 + days. 회사별 (계획 수, 행 수) */
    private static Map<String, long[]> purge(int days) throws Exception {
        Map<String, long[]> out = new HashMap<>();
        try (Connection c = owner(); Statement st = c.createStatement();
                ResultSet rs = st.executeQuery("select * from hb_purge_aps_results(now() + make_interval(days => " + days + "))")) {
            while (rs.next()) {
                out.put(rs.getString(1), new long[] {rs.getLong(2), rs.getLong(3)});
            }
        }
        return out;
    }

    private long results(long plan) {
        return accounts.query(comp, "select (select count(*) from aps_plan_wo where plan_id = ?) + (select count(*) from aps_plan_op where plan_id = ?)",
                Long.class, plan, plan);
    }

    private boolean purged(long plan) {
        return accounts.query(comp, "select purged_at is not null from aps_plan where plan_id = ?", Boolean.class, plan);
    }

    @Test
    void 보존_기간이_지난_폐기_미반영_계획의_결과만_정리하고_머리는_남긴다() throws Exception {
        accounts.grant(comp, userId, "APS", "aps-plan", "APPROVE");
        long calcOnly = id(calc(admin, "P1", tomorrow, 3));
        long discarded = id(calc(admin, "P1", tomorrow, 3));
        mvc.perform(json(post(A + "/" + discarded + "/discard"), "{\"rowVer\":0,\"reason\":\"ZT\"}").with(admin)).andExpect(status().isOk());
        long applied = id(calc(admin, "P1", tomorrow, 3));
        mvc.perform(json(post(A + "/" + applied + "/apply"), "{\"rowVer\":0}").with(admin)).andExpect(status().isOk());
        long before = results(calcOnly);
        assertThat(before).isPositive();

        assertThat(purge(10)).doesNotContainKey(comp);              // 기본 90일 안 — 그대로
        assertThat(results(calcOnly)).isEqualTo(before);
        long[] r = purge(100).get(comp);
        assertThat(r[0]).isEqualTo(2L);                                // 계산만·폐기
        assertThat(r[1]).isEqualTo(before * 2);
        assertThat(results(calcOnly)).isZero();
        assertThat(results(discarded)).isZero();
        assertThat(results(applied)).as("반영한 계획은 결과도 보존").isPositive();
        assertThat(purged(calcOnly)).isTrue();
        assertThat(purged(applied)).isFalse();
        // 머리·요약은 남는다, 정리된 계획은 반영·비교 불가
        mvc.perform(get(A + "/" + calcOnly).with(admin)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.plan.woCnt").value(2))
                .andExpect(jsonPath("$.data.plan.purgedAt").isNotEmpty())
                .andExpect(jsonPath("$.data.wos.length()").value(0));
        mvc.perform(get(A + "/" + calcOnly + "/apply").with(admin)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].code").value("APS_PURGED"));
        mvc.perform(json(post(A + "/" + calcOnly + "/apply"), "{\"rowVer\":1}").with(admin)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].code").value("APS_PURGED"));
        mvc.perform(get(A + "/" + applied + "/compare?other=" + calcOnly).with(admin)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].code").value("APS_PURGED"));
        // 다시 돌려도 이미 정리된 계획은 대상 아님
        assertThat(purge(200)).doesNotContainKey(comp);
        // 정리 감사: 행위자 기록
        assertThat(accounts.query(comp, "select count(*) from sys_audit_log where table_nm = 'aps_plan' and row_key = ? and action = 'UPDATE'",
                Long.class, String.valueOf(calcOnly))).isEqualTo(1L);
    }

    @Test
    void 고정의_출처_계획과_법적_보류_기간은_보존하고_회사_보존_기간은_최소_30일() throws Exception {
        long pinned = id(calc(admin, "P1", tomorrow, 3));
        long held = id(calc(admin, "P1", tomorrow, 3));
        long op = accounts.query(comp, "select plan_op_id from aps_plan_op where plan_id = ? and wo_id = ? and op_seq = 10", Long.class, pinned, wo2);
        String m1 = "EQUIP:" + accounts.query(comp, "select equip_id from mst_equip where equip_cd = 'M1'", Long.class);
        mvc.perform(json(post("/api/aps/pins"), "{\"planOpId\":" + op + ",\"resKey\":\"" + m1 + "\",\"startAt\":\"" + at(tomorrow, 13) + "\"}")
                .with(admin)).andExpect(status().isOk());
        accounts.exec(comp, """
                insert into sys_legal_hold (comp_cd, class_cd, from_at, to_at, reason, created_by, updated_by)
                select ?, 'PLAN_RESULT', calculated_at, calculated_at + interval '1 millisecond', 'ZT 분쟁', 't', 't'
                  from aps_plan where plan_id = ?""", comp, held);
        // 회사 보존 10일로 줄여도 최소 30일 — 20일 뒤는 그대로, 40일 뒤 정리(고정 출처·보류 제외)
        accounts.exec(comp, "insert into sys_retention_policy (comp_cd, class_cd, keep_days, created_by, updated_by) values (?, 'PLAN_RESULT', 10, 't', 't')", comp);
        long other = id(calc(admin, "P1", tomorrow, 3));
        assertThat(purge(20)).doesNotContainKey(comp);
        assertThat(purge(40).get(comp)[0]).isEqualTo(1L);
        assertThat(purged(other)).isTrue();
        assertThat(purged(pinned)).as("사용 중 고정의 출처").isFalse();
        assertThat(purged(held)).as("법적 보류").isFalse();
        // 고정 해제 뒤에는 정리
        long pinId = accounts.query(comp, "select pin_id from aps_pin", Long.class);
        mvc.perform(json(post("/api/aps/pins/" + pinId + "/release"), "{\"rowVer\":0}").with(admin)).andExpect(status().isOk());
        purge(40);
        assertThat(purged(pinned)).isTrue();
    }

    @Test
    void 앱_계정은_정리_프로시저를_실행하거나_표식으로_우회할_수_없다() throws Exception {
        long plan = id(calc(admin, "P1", tomorrow, 3));
        assertThatThrownBy(() -> accounts.query(comp, "select count(*) from hb_purge_aps_results()", Long.class))
                .rootCause().hasMessageContaining("permission denied");
        // 앱이 표식을 세워도 결과 DELETE 권한이 없고, 머리에 정리 표시도 못 한다(결과가 남아 있음)
        assertThatThrownBy(() -> accounts.exec(comp,
                "select set_config('hb.aps_purge', 'Y', false); delete from aps_plan_op where plan_id = " + plan))
                .rootCause().hasMessageContaining("permission denied");
        Throwable mark = catchThrowable(() -> accounts.exec(comp, """
                with f as (select set_config('hb.aps_purge', 'Y', true))
                update aps_plan set purged_at = now() from f where plan_id = ?""", plan));
        assertThat(PgErrors.constraint(mark)).contains("ck_aps_plan_purge");
        assertThat(results(plan)).isPositive();
        // 소유자라도 표식 없이 지우면 추가만 트리거
        try (Connection c = owner(); Statement st = c.createStatement()) {
            Throwable t = catchThrowable(() -> st.execute("delete from aps_plan_op where plan_id = " + plan));
            assertThat(t).hasMessageContaining("append-only");
        }
    }

    @Test
    void 회사마다_자기_보존_기간으로_정리한다_A사로_B사() throws Exception {
        long planA = id(calc(admin, "P1", tomorrow, 3));
        String compB = accounts.newComp("APSB");
        long ub = accounts.newUser(compB, "admin1", PW);
        accounts.grant(compB, ub, "APS", "aps-plan", "SEARCH", "SAVE");
        RequestPostProcessor b = accounts.as(compB, ub, "admin1");
        accounts.addPlant(compB, "P1", "B 1공장");
        accounts.exec(compB, """
                insert into mst_shift (comp_cd, plant_cd, shift_cd, shift_nm, start_time, end_time, created_by, updated_by)
                values (?, 'P1', 'D', '주간', '08:00', '17:00', 't', 't')""", compB);
        long planB = id(calc(b, "P1", tomorrow, 3));
        // A사만 보존 200일 — 100일 뒤 B사만 정리
        accounts.exec(comp, "insert into sys_retention_policy (comp_cd, class_cd, keep_days, created_by, updated_by) values (?, 'PLAN_RESULT', 200, 't', 't')", comp);
        Map<String, long[]> r = purge(100);
        assertThat(r).containsKey(compB).doesNotContainKey(comp);
        assertThat(purged(planA)).isFalse();
        assertThat(accounts.query(compB, "select purged_at is not null from aps_plan where plan_id = ?", Boolean.class, planB)).isTrue();
        // B사 범위에서 A사 계획 정리 표시 → 0행(RLS)
        assertThat(accounts.exec(compB, "update aps_plan set purged_at = now() where plan_id = ?", planA)).isZero();
    }
}
