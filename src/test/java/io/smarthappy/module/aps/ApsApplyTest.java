package io.smarthappy.module.aps;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import io.smarthappy.module.aps.ApsDtos.ApplyPreview;
import io.smarthappy.module.aps.ApsDtos.PlanRow;
import io.smarthappy.platform.common.db.PgErrors;
import io.smarthappy.platform.tenant.TenantRunner;

/**
 * APS 2단계 (docs/38 11장) — 반영 미리보기·반영(MES 서비스 경유, 감사 이력), 낡은 계획 거부(지시·공정 변경, 새 지시), 상태 규칙,
 * MES 검사 실패 시 전부 취소(지시 번호 표시), 권한(APPROVE + 작업지시 SAVE), 동시 반영, 직접 SQL, A사로 B사, 계획 비교.
 */
class ApsApplyTest extends ApsTestBase {

    @Autowired
    ApsApplyService applies;

    @Autowired
    ApsMapper mapper;

    @Autowired
    TenantRunner runner;

    String wo1No;
    String wo2No;
    long cutProc1;

    @BeforeEach
    void grantApply() {
        accounts.grant(comp, userId, "APS", "aps-plan", "APPROVE");
        wo1No = accounts.query(comp, "select wo_no from prd_wo where wo_id = ?", String.class, wo1);
        wo2No = accounts.query(comp, "select wo_no from prd_wo where wo_id = ?", String.class, wo2);
        cutProc1 = accounts.query(comp, "select wo_proc_id from prd_wo_proc where wo_id = ? and op_seq = 10", Long.class, wo1);
    }

    private ResultActions apply(RequestPostProcessor who, long plan, int rowVer) throws Exception {
        return mvc.perform(json(post(A + "/" + plan + "/apply"), "{\"rowVer\":" + rowVer + "}").with(who));
    }

    private String planStatus(long plan) {
        return accounts.query(comp, "select status from aps_plan where plan_id = ?", String.class, plan);
    }

    // ---------- 정상 ----------

    @Test
    void 미리보기는_바뀔_값만_보이고_반영하면_지시_일정과_설비가_MES_를_거쳐_바뀌며_감사가_남는다() throws Exception {
        long plan = id(calc(admin, "P1", tomorrow, 3));
        String before = woState();
        // 지시1(발행): 절단 M1 08~11 → 도금 24시간 → 모레 11시 끝 / 지시2(계획): M2 08~10 → 모레 10시. 둘 다 완료일 밀림
        mvc.perform(get(A + "/" + plan + "/apply").with(admin)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.stale.length()").value(0))
                .andExpect(jsonPath("$.data.expired").value(false))
                .andExpect(jsonPath("$.data.woCnt").value(2))
                .andExpect(jsonPath("$.data.opCnt").value(1))
                .andExpect(jsonPath("$.data.pushedCnt").value(2))
                .andExpect(jsonPath("$.data.wos[0].woNo").value(wo1No))
                .andExpect(jsonPath("$.data.wos[0].toStart").value(tomorrow.toString()))
                .andExpect(jsonPath("$.data.wos[0].toEnd").value(tomorrow.plusDays(1).toString()))
                .andExpect(jsonPath("$.data.wos[0].equips[0].procCd").value("CUT"))
                .andExpect(jsonPath("$.data.wos[0].equips[0].fromEquipCd").doesNotExist())
                .andExpect(jsonPath("$.data.wos[0].equips[0].toEquipCd").value("M1"))
                .andExpect(jsonPath("$.data.wos[1].equips.length()").value(0));     // 계획 지시는 공정 스냅샷이 없다(A16)
        assertThat(woState()).as("미리보기는 지시를 바꾸지 않는다").isEqualTo(before);

        apply(admin, plan, 0).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.woCnt").value(2)).andExpect(jsonPath("$.data.opCnt").value(1));
        assertThat(accounts.query(comp, "select plan_end::text from prd_wo where wo_id = ?", String.class, wo1))
                .isEqualTo(tomorrow.plusDays(1).toString());
        assertThat(accounts.query(comp, "select plan_end::text from prd_wo where wo_id = ?", String.class, wo2))
                .isEqualTo(tomorrow.plusDays(1).toString());
        assertThat(accounts.query(comp, """
                select e.equip_cd from prd_wo_proc p join mst_equip e on e.equip_id = p.equip_id where p.wo_proc_id = ?""",
                String.class, cutProc1)).isEqualTo("M1");
        mvc.perform(get(A + "/" + plan).with(admin))
                .andExpect(jsonPath("$.data.plan.status").value("APPLIED"))
                .andExpect(jsonPath("$.data.plan.appliedBy").value("admin1"))
                .andExpect(jsonPath("$.data.plan.appliedWoCnt").value(2))
                .andExpect(jsonPath("$.data.plan.appliedOpCnt").value(1))
                .andExpect(jsonPath("$.data.plan.appliedAt").isNotEmpty());
        // 지시 감사 이력: 반영한 사람으로, 전후 완료일
        assertThat(accounts.query(comp, """
                select count(*) from sys_audit_log
                 where table_nm = 'prd_wo' and row_key = ? and action = 'UPDATE' and actor like 'u%:admin1'
                   and before_data ->> 'plan_end' = ? and after_data ->> 'plan_end' = ?""",
                Long.class, String.valueOf(wo1), tomorrow.toString(), tomorrow.plusDays(1).toString())).isEqualTo(1L);
        assertThat(accounts.query(comp, """
                select count(*) from sys_audit_log where table_nm = 'prd_wo_proc' and row_key = ? and action = 'UPDATE'
                   and actor like 'u%:admin1'""", Long.class, String.valueOf(cutProc1))).isEqualTo(1L);
    }

    @Test
    void 바뀔_것이_없는_지시는_건드리지_않는다() throws Exception {
        long p1 = id(calc(admin, "P1", tomorrow, 3));
        apply(admin, p1, 0).andExpect(status().isOk());
        String after = woState();
        // 같은 입력으로 다시 계산 → 반영해도 바뀌는 지시 없음(row_ver 를 올리지 않는다)
        long p2 = id(calc(admin, "P1", tomorrow, 3));
        mvc.perform(get(A + "/" + p2 + "/apply").with(admin))
                .andExpect(jsonPath("$.data.woCnt").value(0)).andExpect(jsonPath("$.data.stale.length()").value(0));
        apply(admin, p2, 0).andExpect(status().isOk()).andExpect(jsonPath("$.data.woCnt").value(0));
        assertThat(woState()).isEqualTo(after);
        assertThat(planStatus(p2)).isEqualTo("APPLIED");
    }

    // ---------- 낡은 계획 ----------

    @Test
    void 계산_뒤_지시나_공정이_바뀌거나_새_지시가_생기면_반영하지_않고_아무것도_바꾸지_않는다() throws Exception {
        long plan = id(calc(admin, "P1", tomorrow, 3));
        // 지시2 우선순위 변경(MES 화면), 지시1 절단 공정 설비 배정, 새 지시
        mvc.perform(json(put("/api/production/wos/" + wo2), "{\"plantCd\":\"P1\",\"itemCd\":\"FG\",\"planQty\":\"2\",\"planStart\":\""
                + tomorrow + "\",\"planEnd\":\"" + tomorrow + "\",\"priority\":4,\"rowVer\":0}").with(admin)).andExpect(status().isOk());
        mvc.perform(json(put("/api/production/wos/procs/" + cutProc1 + "/equip"), "{\"equipCd\":\"M2\",\"rowVer\":0}").with(admin))
                .andExpect(status().isOk());
        long wo3 = wo("FG2", "1", 9);
        String wo3No = accounts.query(comp, "select wo_no from prd_wo where wo_id = ?", String.class, wo3);
        String before = woState();

        mvc.perform(get(A + "/" + plan + "/apply").with(admin)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.stale[?(@.woNo == '" + wo1No + "')].code", Matchers.contains("APS_PROC_CHANGED")))
                .andExpect(jsonPath("$.data.stale[?(@.woNo == '" + wo2No + "')].code", Matchers.contains("APS_WO_CHANGED")))
                .andExpect(jsonPath("$.data.stale[?(@.woNo == '" + wo3No + "')].code", Matchers.contains("APS_NEW_WO")));
        apply(admin, plan, 0).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("APS_PLAN_STALE"))
                .andExpect(jsonPath("$.errors.length()").value(3))
                .andExpect(jsonPath("$.errors[?(@.rowKey == '" + wo2No + "')].code", Matchers.contains("APS_WO_CHANGED")));
        assertThat(woState()).isEqualTo(before);
        assertThat(planStatus(plan)).isEqualTo("CALCULATED");
    }

    @Test
    void MES_검사에_걸리면_전부_취소되고_어느_지시인지_알린다() throws Exception {
        long plan = id(calc(admin, "P1", tomorrow, 3));
        // 계산 뒤 FG 라우팅을 내일부터 끊는다 — 계획 지시(지시2)는 새 일정에서 라우팅을 다시 확인한다(지시 row_ver 는 그대로)
        accounts.exec(comp, "update mst_routing set valid_to = ? where item_id = (select item_id from mst_item where item_cd = 'FG')",
                java.sql.Date.valueOf(tomorrow));
        String before = woState();
        apply(admin, plan, 0).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].rowKey").value(wo2No))
                .andExpect(jsonPath("$.errors[0].field").value("routingId"))
                .andExpect(jsonPath("$.errors[0].code").value("STALE_REV"));
        assertThat(woState()).as("지시1 은 먼저 반영됐어도 함께 취소").isEqualTo(before);
        assertThat(planStatus(plan)).isEqualTo("CALCULATED");
    }

    @Test
    void 계획_기간이_지난_계획은_반영할_수_없다() throws Exception {
        long plan = id(calc(admin, "P1", tomorrow, 3));
        ApplyPreview pv = runner.call(comp, () -> {
            PlanRow p = mapper.findPlan(plan);
            p.setSchedTo(OffsetDateTime.now().minusMinutes(1));   // 기간 끝이 지난 것으로
            return applies.build(p);
        });
        assertThat(pv.expired()).isTrue();
    }

    // ---------- 상태·동시 ----------

    @Test
    void 반영은_한_번이고_반영된_계획은_폐기할_수_없으며_폐기된_계획은_반영할_수_없다() throws Exception {
        long p1 = id(calc(admin, "P1", tomorrow, 3));
        long p2 = id(calc(admin, "P1", tomorrow, 3));
        apply(admin, p1, 7).andExpect(status().isConflict());
        apply(admin, p1, 0).andExpect(status().isOk());
        apply(admin, p1, 1).andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].code").value("STATE"));
        mvc.perform(json(post(A + "/" + p1 + "/discard"), "{\"rowVer\":1,\"reason\":\"ZT\"}").with(admin))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].code").value("STATE"));
        // p2 는 p1 반영으로 지시가 바뀌어 낡았다(A17) — 폐기는 된다
        apply(admin, p2, 0).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("APS_PLAN_STALE"));
        mvc.perform(json(post(A + "/" + p2 + "/discard"), "{\"rowVer\":0,\"reason\":\"ZT\"}").with(admin)).andExpect(status().isOk());
        apply(admin, p2, 1).andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].code").value("STATE"));
        mvc.perform(json(post(A + "/" + p1 + "/apply"), "{}").with(admin)).andExpect(status().isBadRequest());
        mvc.perform(get(A + "/999999999/apply").with(admin)).andExpect(status().isNotFound());
    }

    @Test
    void 동시에_두_번_반영하면_하나만_된다_그리고_같은_요청_키는_한_번만() throws Exception {
        long plan = id(calc(admin, "P1", tomorrow, 3));
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Callable<Integer>> calls = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                calls.add(() -> apply(admin, plan, 0).andReturn().getResponse().getStatus());
            }
            List<Integer> codes = new ArrayList<>();
            for (Future<Integer> f : pool.invokeAll(calls)) {
                codes.add(f.get());
            }
            assertThat(codes).containsExactlyInAnyOrder(200, 400);
        } finally {
            pool.shutdownNow();
        }
        assertThat(accounts.query(comp, """
                select count(*) from sys_audit_log where table_nm = 'prd_wo_proc' and row_key = ? and action = 'UPDATE'""",
                Long.class, String.valueOf(cutProc1))).as("배정은 한 번").isEqualTo(1L);
        long p2 = id(calc(admin, "P1", tomorrow, 3));
        mvc.perform(json(post(A + "/" + p2 + "/apply"), "{\"rowVer\":0}").header("X-Idempotency-Key", "zt-aps-apply").with(admin))
                .andExpect(status().isOk());
        mvc.perform(json(post(A + "/" + p2 + "/apply"), "{\"rowVer\":1}").header("X-Idempotency-Key", "zt-aps-apply").with(admin))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DUPLICATE_REQUEST"));
    }

    // ---------- 권한 ----------

    @Test
    void 반영은_APPROVE_와_작업지시_SAVE_가_둘_다_있어야_한다() throws Exception {
        long plan = id(calc(admin, "P1", tomorrow, 3));
        long planner = accounts.newUser(comp, "planner", PW);       // 계산만
        accounts.grant(comp, planner, "PLAN", "aps-plan", "SEARCH", "SAVE");
        RequestPostProcessor pl = accounts.as(comp, planner, "planner");
        mvc.perform(get(A + "/" + plan + "/apply").with(pl)).andExpect(status().isForbidden());
        apply(pl, plan, 0).andExpect(status().isForbidden());
        long approver = accounts.newUser(comp, "approver", PW);     // 계획 APPROVE 는 있지만 작업지시 권한 없음
        accounts.grant(comp, approver, "APR", "aps-plan", "SEARCH", "APPROVE");
        RequestPostProcessor ap = accounts.as(comp, approver, "approver");
        mvc.perform(get(A + "/" + plan + "/apply").with(ap)).andExpect(status().isOk());
        apply(ap, plan, 0).andExpect(status().isForbidden());
        assertThat(planStatus(plan)).isEqualTo("CALCULATED");
        accounts.grant(comp, approver, "APR", "prd-wo", "SEARCH", "SAVE");
        apply(ap, plan, 0).andExpect(status().isOk());
    }

    // ---------- 직접 SQL ----------

    @Test
    void 반영_표식은_그_트랜잭션에서만이고_반영된_계획은_고정되며_새_결과_행은_row_ver_사본이_필수() throws Exception {
        long plan = id(calc(admin, "P1", tomorrow, 3));
        Throwable past = catchThrowable(() -> accounts.exec(comp, """
                update aps_plan set status = 'APPLIED', applied_at = now() - interval '1 day', applied_by = 'x',
                       applied_wo_cnt = 0, applied_op_cnt = 0 where plan_id = ?""", plan));
        assertThat(PgErrors.constraint(past)).contains("ck_aps_plan_applied_now");
        Throwable half = catchThrowable(() -> accounts.exec(comp, "update aps_plan set applied_by = 'x' where plan_id = ?", plan));
        assertThat(PgErrors.constraint(half)).contains("ck_aps_plan_applied");
        apply(admin, plan, 0).andExpect(status().isOk());
        Throwable back = catchThrowable(() -> accounts.exec(comp,
                "update aps_plan set status = 'DISCARDED', discard_reason = 'x' where plan_id = ?", plan));
        assertThat(PgErrors.constraint(back)).contains("ck_aps_plan_applied_fixed");
        // 계산 트랜잭션을 흉내 내도(같은 트랜잭션 now()) 지시 row_ver 사본 없는 결과 행은 넣을 수 없다
        Throwable noVer = catchThrowable(() -> accounts.exec(comp, resultRowSql("ZT-SQL1", "w.plan_end, 'WO', null")));
        assertThat(PgErrors.constraint(noVer)).contains("ck_aps_plan_wo_row_ver");
        // 3단계: 납기 사본도 새 행부터 필수
        Throwable noDue = catchThrowable(() -> accounts.exec(comp, resultRowSql("ZT-SQL2", "null, null, w.row_ver")));
        assertThat(PgErrors.constraint(noDue)).contains("ck_aps_plan_wo_due");
    }

    /** 계산 트랜잭션을 흉내 낸 DO 블록(같은 트랜잭션 now()) — 계획 머리 + 결과 행 1개, tail = due_date, due_src, wo_row_ver 값 */
    private static String resultRowSql(String planNo, String tail) {
        return """
                DO $$
                DECLARE pid bigint;
                BEGIN
                    INSERT INTO aps_plan (comp_cd, plan_no, plant_cd, horizon_start, horizon_days, time_zone, sched_from, sched_to,
                                          work_windows, calculated_by, wo_cnt, op_cnt, late_wo_cnt, unsched_op_cnt, calc_ms,
                                          created_by, updated_by)
                    VALUES (hb_current_comp(), '%s', 'P1', (now() at time zone 'Asia/Seoul')::date + 1, 1, 'Asia/Seoul', now(), now() + interval '1 day',
                            '[]', 'x', 1, 0, 0, 0, 0, 'x', 'x')
                    RETURNING plan_id INTO pid;
                    INSERT INTO aps_plan_wo (comp_cd, plan_id, seq, wo_id, wo_no, wo_status, item_id, plan_qty, unit_cd, priority,
                                             plan_start, plan_end, due_date, due_src, wo_row_ver)
                    SELECT w.comp_cd, pid, 1, w.wo_id, w.wo_no, w.status, w.item_id, w.plan_qty, w.unit_cd, w.priority,
                           w.plan_start, w.plan_end, %s
                      FROM prd_wo w ORDER BY w.wo_id LIMIT 1;
                END $$""".formatted(planNo, tail);
    }

    // ---------- A사로 B사 ----------

    @Test
    void B사는_A사_계획을_미리보거나_반영하거나_비교할_수_없다() throws Exception {
        long plan = id(calc(admin, "P1", tomorrow, 3));
        String compB = accounts.newComp("APSB");
        long ub = accounts.newUser(compB, "admin1", PW);
        accounts.grant(compB, ub, "APS", "aps-plan", "SEARCH", "SAVE", "APPROVE");
        accounts.grant(compB, ub, "APS", "prd-wo", "SEARCH", "SAVE");
        RequestPostProcessor b = accounts.as(compB, ub, "admin1");
        accounts.addPlant(compB, "P1", "B 1공장");
        accounts.exec(compB, """
                insert into mst_shift (comp_cd, plant_cd, shift_cd, shift_nm, start_time, end_time, created_by, updated_by)
                values (?, 'P1', 'D', '주간', '08:00', '17:00', 't', 't')""", compB);
        long planB = id(calc(b, "P1", tomorrow, 3));
        String before = woState();
        mvc.perform(get(A + "/" + plan + "/apply").with(b)).andExpect(status().isNotFound());
        apply(b, plan, 0).andExpect(status().isNotFound());
        mvc.perform(get(A + "/" + plan + "/compare?other=" + planB).with(b)).andExpect(status().isNotFound());
        mvc.perform(get(A + "/" + planB + "/compare?other=" + plan).with(b)).andExpect(status().isNotFound());
        mvc.perform(get(A + "/" + plan + "/compare?other=" + planB).with(admin)).andExpect(status().isNotFound());
        assertThat(accounts.exec(compB, "update aps_plan set status = 'DISCARDED', discard_reason = 'x' where plan_id = ?", plan)).isZero();
        assertThat(woState()).isEqualTo(before);
        assertThat(planStatus(plan)).isEqualTo("CALCULATED");
    }

    // ---------- 비교 ----------

    @Test
    void 같은_공장_두_계획을_공정별로_비교한다() throws Exception {
        long p1 = id(calc(admin, "P1", tomorrow, 3));
        long wo3 = wo("FG", "1", 1);      // 우선 1 — 지시1 과 같은 순위, 번호가 뒤라 그 뒤. M2 를 먼저 잡아 지시2 를 민다
        String wo3No = accounts.query(comp, "select wo_no from prd_wo where wo_id = ?", String.class, wo3);
        long p2 = id(calc(admin, "P1", tomorrow, 3));
        mvc.perform(get(A + "/" + p1 + "/compare?other=" + p2).with(admin)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.kinds.ONLY_B").value(2))                 // 새 지시의 절단·도금
                .andExpect(jsonPath("$.data.kinds.SAME").value(2))                   // 지시1 그대로
                .andExpect(jsonPath("$.data.kinds.MOVED").value(2))                  // 지시2 절단·도금이 1시간 밀림
                .andExpect(jsonPath("$.data.rows[?(@.woNo == '" + wo3No + "')].kind", Matchers.everyItem(Matchers.is("ONLY_B"))))
                .andExpect(jsonPath("$.data.rows[?(@.woNo == '" + wo2No + "' && @.opSeq == 10)].shiftSec", Matchers.contains(3600)));
        mvc.perform(get(A + "/" + p1 + "/compare?other=" + p1).with(admin))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].code").value("APS_SAME_PLAN"));
        mvc.perform(get(A + "/" + p1 + "/compare").with(admin))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].code").value("REQUIRED"));
        accounts.exec(comp, """
                insert into mst_shift (comp_cd, plant_cd, shift_cd, shift_nm, start_time, end_time, created_by, updated_by)
                values (?, 'P2', 'D', '주간', '08:00', '17:00', 't', 't')""", comp);
        long other = id(calc(admin, "P2", tomorrow, 3));
        mvc.perform(get(A + "/" + p1 + "/compare?other=" + other).with(admin))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].code").value("APS_OTHER_PLANT"));
        long viewer = accounts.newUser(comp, "viewer", PW);
        accounts.grant(comp, viewer, "VIEW", "aps-plan", "SEARCH");
        mvc.perform(get(A + "/" + p1 + "/compare?other=" + p2).with(accounts.as(comp, viewer, "viewer"))).andExpect(status().isOk());
    }
}
