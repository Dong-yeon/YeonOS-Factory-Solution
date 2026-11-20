package io.smarthappy.module.aps;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.jayway.jsonpath.JsonPath;

import io.smarthappy.platform.common.db.PgErrors;

/**
 * APS 1단계 (docs/38) — 계산(설비 2대·외주·설비 없는 작업장), 지시 무변경, 새 버전·폐기, 결과 불변(직접 SQL), 계획 기간 넘침,
 * A사로 B사, 입력 검증, 권한, 요청 키 중복.
 */
class ApsPlanTest extends ApsTestBase {

    // ---------- 정상 ----------

    @Test
    void 계산하면_설비_빈자리에_순서대로_넣고_지시는_바꾸지_않는다() throws Exception {
        String before = woState();
        long plan = id(calc(admin, "P1", tomorrow, 3));
        assertThat(woState()).isEqualTo(before);

        MvcResult d = mvc.perform(get(A + "/" + plan).with(admin)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.plan.status").value("CALCULATED"))
                .andExpect(jsonPath("$.data.plan.woCnt").value(2))
                .andExpect(jsonPath("$.data.plan.opCnt").value(4))
                .andExpect(jsonPath("$.data.plan.unschedOpCnt").value(0))
                .andExpect(jsonPath("$.data.plan.lateWoCnt").value(2))     // 둘 다 외주 24시간 → 납기(내일) 넘김
                .andExpect(jsonPath("$.data.wos[0].woId").value((int) wo1))   // 우선순위 1 먼저
                .andExpect(jsonPath("$.data.wos[0].woStatus").value("RELEASED"))
                .andExpect(jsonPath("$.data.wos[1].woStatus").value("PLANNED"))
                .andExpect(jsonPath("$.data.unscheduled.length()").value(0))
                .andReturn();
        String body = d.getResponse().getContentAsString();
        assertThat(Instant.parse(JsonPath.read(body, "$.data.wos[0].endAt"))).isEqualTo(at(tomorrow, 11).plusSeconds(24 * 3600L));

        MvcResult g = mvc.perform(get(A + "/" + plan + "/gantt").with(admin)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.resources[*].code", Matchers.contains("M1", "M2", null)))
                .andExpect(jsonPath("$.data.days.length()").value(4))
                .andExpect(jsonPath("$.data.windows.length()").value(3))
                .andReturn();
        String gb = g.getResponse().getContentAsString();
        List<String> starts = JsonPath.read(gb, "$.data.ops[?(@.opSeq == 10)].startAt");
        List<String> equips = JsonPath.read(gb, "$.data.ops[?(@.opSeq == 10)].equipCd");
        // 지시1(3시간) M1 08~11, 지시2(2시간) 은 가장 빨리 끝나는 M2 08~10
        assertThat(starts).extracting(Instant::parse).containsOnly(at(tomorrow, 8));
        assertThat(equips).containsExactlyInAnyOrder("M1", "M2");
        List<String> kinds = JsonPath.read(gb, "$.data.ops[?(@.opSeq == 20)].resKind");
        assertThat(kinds).containsOnly("OUTSRC");
        // 발행 지시는 지시 공정 id, 계획 지시는 없음(라우팅에서)
        assertThat(accounts.query(comp, "select count(*) from aps_plan_op where plan_id = ? and wo_proc_id is not null", Long.class, plan))
                .isEqualTo(2L);
    }

    @Test
    void 기간_안에_안_끝나면_계획_못_한_공정이고_설비_없는_작업장은_작업장_하나가_자원() throws Exception {
        wo("FG2", "1", 3);
        wo("FG2", "40", 4);    // 40시간 — 3일(하루 9시간)에 못 끝남
        long plan = id(calc(admin, "P1", tomorrow, 3));
        mvc.perform(get(A + "/" + plan).with(admin)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.plan.unschedOpCnt").value(1))
                .andExpect(jsonPath("$.data.unscheduled[0].excCd").value("HORIZON"))
                .andExpect(jsonPath("$.data.unscheduled[0].nodeCd").value("L2"))
                .andExpect(jsonPath("$.data.wos[?(@.excCd == 'HORIZON')].startAt", Matchers.contains((Object) null)));
        mvc.perform(get(A + "/" + plan + "/gantt").with(admin)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.resources[?(@.kind == 'NODE')].code", Matchers.contains("L2")));
        assertThat(accounts.query(comp, "select res_kind from aps_plan_op where plan_id = ? and node_id = ? and start_at is not null",
                String.class, plan, line2)).isEqualTo("NODE");
    }

    @Test
    void 다시_계산하면_새_버전이고_폐기만_된다() throws Exception {
        long p1 = id(calc(admin, "P1", tomorrow, 3));
        long p2 = id(calc(admin, "P1", tomorrow, 3));
        assertThat(p2).isNotEqualTo(p1);
        String today = tomorrow.minusDays(1).toString();
        mvc.perform(get(A + "?from=" + today + "&to=" + today + "&plantCd=P1").with(admin)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.rows.length()").value(2));
        mvc.perform(json(post(A + "/" + p1 + "/discard"), "{\"rowVer\":0,\"reason\":\"ZT 비교 끝\"}").with(admin)).andExpect(status().isOk());
        mvc.perform(get(A + "/" + p1).with(admin)).andExpect(jsonPath("$.data.plan.status").value("DISCARDED"))
                .andExpect(jsonPath("$.data.plan.discardReason").value("ZT 비교 끝"));
        // 이미 폐기 → 상태, 낡은 rowVer → 409, 사유 없음 → 400
        mvc.perform(json(post(A + "/" + p1 + "/discard"), "{\"rowVer\":1,\"reason\":\"x\"}").with(admin))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].code").value("STATE"));
        mvc.perform(json(post(A + "/" + p2 + "/discard"), "{\"rowVer\":7,\"reason\":\"x\"}").with(admin)).andExpect(status().isConflict());
        mvc.perform(json(post(A + "/" + p2 + "/discard"), "{\"rowVer\":0,\"reason\":\" \"}").with(admin))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("reason"));
    }

    // ---------- 직접 SQL ----------

    @Test
    void 결과는_계산_트랜잭션_밖에서_넣거나_고치거나_지울_수_없다() throws Exception {
        long plan = id(calc(admin, "P1", tomorrow, 3));
        assertThatThrownBy(() -> accounts.exec(comp, "update aps_plan_op set qty = 1 where plan_id = ?", plan))
                .rootCause().hasMessageContaining("permission denied");
        assertThatThrownBy(() -> accounts.exec(comp, "delete from aps_plan_wo where plan_id = ?", plan))
                .rootCause().hasMessageContaining("permission denied");
        assertThatThrownBy(() -> accounts.exec(comp, "delete from aps_plan where plan_id = ?", plan))
                .rootCause().hasMessageContaining("permission denied");
        Throwable fixed = catchThrowable(() -> accounts.exec(comp, "update aps_plan set wo_cnt = 99 where plan_id = ?", plan));
        assertThat(PgErrors.constraint(fixed)).contains("ck_aps_plan_fixed");
        Throwable back = catchThrowable(() -> accounts.exec(comp, """
                insert into aps_plan_wo (comp_cd, plan_id, seq, wo_id, wo_no, wo_status, item_id, plan_qty, unit_cd, priority,
                                         plan_start, plan_end)
                select comp_cd, plan_id, 99, wo_id, wo_no, wo_status, item_id, plan_qty, unit_cd, priority, plan_start, plan_end
                  from aps_plan_wo where plan_id = ? and seq = 1""", plan));
        assertThat(PgErrors.constraint(back)).contains("ck_aps_result_calc");
    }

    // ---------- A사로 B사 ----------

    @Test
    void B사는_A사_계획을_보거나_폐기할_수_없고_A사_근무조로_계산되지_않는다() throws Exception {
        long plan = id(calc(admin, "P1", tomorrow, 3));
        String compB = accounts.newComp("APSB");
        long ub = accounts.newUser(compB, "admin1", PW);
        accounts.grant(compB, ub, "APS", "aps-plan", "SEARCH", "SAVE");
        RequestPostProcessor b = accounts.as(compB, ub, "admin1");
        accounts.addPlant(compB, "P1", "B 1공장");
        mvc.perform(get(A + "/" + plan).with(b)).andExpect(status().isNotFound());
        mvc.perform(get(A + "/" + plan + "/gantt").with(b)).andExpect(status().isNotFound());
        mvc.perform(json(post(A + "/" + plan + "/discard"), "{\"rowVer\":0,\"reason\":\"x\"}").with(b)).andExpect(status().isNotFound());
        String today = tomorrow.minusDays(1).toString();
        mvc.perform(get(A + "?from=" + today + "&to=" + today).with(b)).andExpect(jsonPath("$.data.rows.length()").value(0));
        // B사 P1 에는 근무조가 없다 — A사 P1 근무조를 읽지 않는다
        calc(b, "P1", tomorrow, 3).andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].code").value("APS_NO_SHIFT"));
        assertThat(accounts.query(compB, "select count(*) from aps_plan_op", Long.class)).isZero();
        // B사 범위에서 A사 계획 행 직접 변경 — RLS 로 0행
        assertThat(accounts.exec(compB, "update aps_plan set remark = 'x' where plan_id = ?", plan)).isZero();
        mvc.perform(get(A + "/" + plan).with(admin)).andExpect(jsonPath("$.data.plan.remark").doesNotExist());
    }

    // ---------- 입력·권한 ----------

    @Test
    void 입력_검증() throws Exception {
        mvc.perform(json(post(A), "{\"horizonStart\":\"" + tomorrow + "\"}").with(admin))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("plantCd"));
        calc(admin, "P1", tomorrow, 32).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("horizonDays")).andExpect(jsonPath("$.errors[0].code").value("RANGE"));
        calc(admin, "P1", tomorrow, 0).andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].code").value("RANGE"));
        mvc.perform(json(post(A), "{\"plantCd\":\"P1\",\"horizonStart\":\"" + tomorrow + "\",\"outsrcLeadHr\":721}").with(admin))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("outsrcLeadHr"));
        mvc.perform(json(post(A), "{\"plantCd\":\"P1\",\"horizonStart\":\"2026-13-01\"}").with(admin))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("horizonStart"));
        calc(admin, "P1", tomorrow.minusDays(3), 1).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].code").value("PAST"));
        calc(admin, "P2", tomorrow, 3).andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].code").value("APS_NO_SHIFT"));
        calc(admin, "NOPE", tomorrow, 3).andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].code").value("NO_SUCH_CODE"));
        mvc.perform(json(post(A), "{\"plantCd\":\"P1\",\"horizonStart\":\"" + tomorrow + "\",\"planNm\":\"" + "가".repeat(101) + "\"}")
                .with(admin)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].code").value("TOO_LONG"));
        mvc.perform(get(A + "?from=2026-01-01&to=2027-12-31").with(admin)).andExpect(status().isBadRequest());
        mvc.perform(get(A + "/999999999").with(admin)).andExpect(status().isNotFound());
        assertThat(accounts.query(comp, "select count(*) from aps_plan", Long.class)).isZero();
    }

    @Test
    void 조회만_권한이면_계산할_수_없고_권한_없으면_조회도_안_되며_같은_요청_키는_한_번만() throws Exception {
        long viewer = accounts.newUser(comp, "viewer", PW);
        accounts.grant(comp, viewer, "VIEW", "aps-plan", "SEARCH");
        RequestPostProcessor v = accounts.as(comp, viewer, "viewer");
        calc(v, "P1", tomorrow, 3).andExpect(status().isForbidden());
        long none = accounts.newUser(comp, "none", PW);
        mvc.perform(get(A + "/plants").with(accounts.as(comp, none, "none"))).andExpect(status().isForbidden());
        String body = "{\"plantCd\":\"P1\",\"horizonStart\":\"" + tomorrow + "\"}";
        mvc.perform(json(post(A), body).header("X-Idempotency-Key", "zt-aps-1").with(admin)).andExpect(status().isOk());
        mvc.perform(json(post(A), body).header("X-Idempotency-Key", "zt-aps-1").with(admin))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DUPLICATE_REQUEST"));
        assertThat(accounts.query(comp, "select count(*) from aps_plan", Long.class)).isEqualTo(1L);
    }
}
