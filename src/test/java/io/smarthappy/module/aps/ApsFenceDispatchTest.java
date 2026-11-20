package io.smarthappy.module.aps;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.jayway.jsonpath.JsonPath;

import io.smarthappy.module.aps.DispatchDtos.BoardRow;
import io.smarthappy.module.aps.DispatchDtos.SlotRow;
import io.smarthappy.module.aps.link.ApsDispatchQuery;
import io.smarthappy.module.aps.link.ApsDispatchQuery.Slot;
import io.smarthappy.platform.tenant.TenantRunner;

/**
 * 9단계 현행 계획·확정 구간(G6)·일일 배정(G5) (docs/38 18장, docs/63 AP-10·AP-11).
 *
 * <p>기반 자료(ApsTestBase): 지시1(FG 3, 발행)·지시2(FG 2, 계획) 계획일 = 내일. 계획 첫날을 모레로 두면 계획이 두 지시를 하루 민다 → 반영할 값이 생긴다.
 * 기반은 구간을 0 으로 꺼 두므로 이 시험이 회사·공장·품목 값으로 켠다.
 */
class ApsFenceDispatchTest extends ApsTestBase {

    @Autowired
    ApsDispatchQuery dispatch;
    @Autowired
    TenantRunner runner;

    LocalDate dayAfter;

    @BeforeEach
    void fenceSetUp() {
        accounts.grant(comp, userId, "APS", "aps-plan", "APPROVE");
        accounts.grant(comp, userId, "APS", "aps-item-param", "SEARCH", "SAVE");
        dayAfter = tomorrow.plusDays(1);
    }

    void companyFence(int days) {
        accounts.exec(comp, "update sys_policy set policy_value = ? where policy_key = 'APS_FENCE_DAYS' and plant_cd = '*'", String.valueOf(days));
    }

    ResultActions preview(long plan) throws Exception {
        return mvc.perform(get(A + "/" + plan + "/apply").with(admin));
    }

    ResultActions apply(long plan) throws Exception {
        int rv = JsonPath.read(mvc.perform(get(A + "/" + plan).with(admin)).andReturn().getResponse().getContentAsString(), "$.data.plan.rowVer");
        return mvc.perform(json(post(A + "/" + plan + "/apply"), "{\"rowVer\":" + rv + "}").with(admin));
    }

    /** 지시 공정 id(순번 순) */
    List<Long> procs(long woId) {
        String s = accounts.query(comp, "select string_agg(wo_proc_id::text, ',' order by op_seq) from prd_wo_proc where wo_id = ?", String.class, woId);
        return s == null ? List.of() : java.util.Arrays.stream(s.split(",")).map(Long::valueOf).toList();
    }

    String starts() {
        return accounts.query(comp, "select string_agg(wo_id || ':' || plan_start, ',' order by wo_id) from prd_wo where wo_id in (?, ?)",
                String.class, wo1, wo2);
    }

    // ---------- G6 확정 구간 ----------

    @Test
    void 구간_안_지시는_반영이_바꾸지_않고_미리보기에_따로_보인다() throws Exception {
        companyFence(3);                                   // 오늘 + 3 → 내일 시작 지시 둘 다 구간 안
        long plan = id(calc(admin, "P1", dayAfter, 3));
        String before = starts();
        preview(plan).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.woCnt").value(0))
                .andExpect(jsonPath("$.data.fenceDays").value(3))
                .andExpect(jsonPath("$.data.fenced.length()").value(2))
                .andExpect(jsonPath("$.data.fenced[0].fromStart").value(tomorrow.toString()))
                .andExpect(jsonPath("$.data.fenced[0].toStart").value(dayAfter.toString()))
                .andExpect(jsonPath("$.data.fenced[0].fenceDays").value(3));
        apply(plan).andExpect(status().isOk()).andExpect(jsonPath("$.data.woCnt").value(0));
        assertThat(starts()).as("구간 안 지시는 그대로").isEqualTo(before);
        // 구간 0 이면(기반 값) 같은 계산이 두 지시를 민다
        companyFence(0);
        long plan2 = id(calc(admin, "P1", dayAfter, 3));
        preview(plan2).andExpect(jsonPath("$.data.woCnt").value(2)).andExpect(jsonPath("$.data.fenced.length()").value(0));
        apply(plan2).andExpect(status().isOk());
        assertThat(starts()).isEqualTo(wo1 + ":" + dayAfter + "," + wo2 + ":" + dayAfter);
    }

    @Test
    void 품목별_계획_기준정보_값이_공장_정책보다_우선이고_공장_값이_회사_값보다_우선() throws Exception {
        companyFence(3);
        // 품목 FG 구간 0 → 공장·회사 3 보다 우선 → 구간 없음
        mvc.perform(json(post("/api/aps/item-params"), "{\"plantCd\":\"P1\",\"itemCd\":\"FG\",\"fenceDays\":0}").with(admin))
                .andExpect(status().isOk());
        long plan = id(calc(admin, "P1", dayAfter, 3));
        preview(plan).andExpect(jsonPath("$.data.woCnt").value(2)).andExpect(jsonPath("$.data.fenced.length()").value(0));
        // 품목 값을 지우면(사용 중지) 공장 값 — 공장 P1 = 1 이면 내일은 구간 밖(오늘 + 1 = 내일, 미포함)
        accounts.exec(comp, "update aps_item_param set use_yn = 'N'");
        accounts.exec(comp, "insert into sys_policy (comp_cd, plant_cd, policy_key, policy_value, created_by, updated_by) values (?, 'P1', 'APS_FENCE_DAYS', '1', 't', 't')", comp);
        preview(plan).andExpect(jsonPath("$.data.fenceDays").value(1)).andExpect(jsonPath("$.data.woCnt").value(2));
        // 공장 값 2 → 내일은 구간 안
        accounts.exec(comp, "update sys_policy set policy_value = '2' where plant_cd = 'P1' and policy_key = 'APS_FENCE_DAYS'");
        preview(plan).andExpect(jsonPath("$.data.fenceDays").value(2)).andExpect(jsonPath("$.data.fenced.length()").value(2));
    }

    @Test
    void 사유_있는_고정을_쓴_지시만_구간을_넘어_반영된다() throws Exception {
        companyFence(3);
        long plan = id(calc(admin, "P1", dayAfter, 3));
        long cut2 = accounts.query(comp, "select plan_op_id from aps_plan_op where plan_id = ? and wo_id = ? and op_seq = 10", Long.class, plan, wo2);
        String m1 = "EQUIP:" + accounts.query(comp, "select equip_id from mst_equip where equip_cd = 'M1'", Long.class);
        String startAt = accounts.query(comp, "select to_char(start_at at time zone 'UTC', 'YYYY-MM-DD\"T\"HH24:MI:SS\"Z\"') from aps_plan_op where plan_op_id = ?",
                String.class, cut2);
        // 사유 없는 고정 → 여전히 구간
        String pin = mvc.perform(json(post("/api/aps/pins"), "{\"planOpId\":" + cut2 + ",\"resKey\":\"" + m1 + "\",\"startAt\":\""
                + startAt + "\"}").with(admin))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        long plan2 = id(calc(admin, "P1", dayAfter, 3));
        preview(plan2).andExpect(jsonPath("$.data.fenced.length()").value(2));
        // 사유를 적은 고정 → 그 지시(지시2)만 반영
        long pinId = ((Number) JsonPath.read(pin, "$.data.pinId")).longValue();
        accounts.exec(comp, "update aps_pin set remark = 'ZT 설비 점검으로 당김' where pin_id = ?", pinId);
        long plan3 = id(calc(admin, "P1", dayAfter, 3));
        preview(plan3).andExpect(jsonPath("$.data.woCnt").value(1))
                .andExpect(jsonPath("$.data.wos[0].woId").value((int) wo2))
                .andExpect(jsonPath("$.data.fenced.length()").value(1))
                .andExpect(jsonPath("$.data.fenced[0].woId").value((int) wo1));
    }

    // ---------- 현행 계획 ----------

    @Test
    void 현행_계획은_공장의_마지막_반영_계획_하나() throws Exception {
        long a = id(calc(admin, "P1", dayAfter, 3));
        apply(a).andExpect(status().isOk());
        String today = LocalDate.parse(accounts.query(comp, "select (" + io.smarthappy.support.TestAccounts.COMP_TODAY + ")::text", String.class))
                .toString();
        mvc.perform(get(A + "/" + a).with(admin)).andExpect(jsonPath("$.data.plan.currentYn").value("Y"));
        long b = id(calc(admin, "P1", dayAfter, 3));
        mvc.perform(get(A + "/" + b).with(admin)).andExpect(jsonPath("$.data.plan.currentYn").value("N"));   // 계산만 — 현행 아님
        apply(b).andExpect(status().isOk());
        mvc.perform(get(A + "/" + a).with(admin)).andExpect(jsonPath("$.data.plan.currentYn").value("N"));
        mvc.perform(get(A + "/" + b).with(admin)).andExpect(jsonPath("$.data.plan.currentYn").value("Y"));
        mvc.perform(get(A + "/grid").param("from", today).param("to", today).with(admin))
                .andExpect(jsonPath("$.list[?(@.currentYn == 'Y')].planId").value(org.hamcrest.Matchers.contains((int) b)));
        // 배정표 머리도 b
        mvc.perform(get("/api/aps/dispatch").param("plantCd", "P1").param("day", dayAfter.toString()).with(admin))
                .andExpect(jsonPath("$.data.planId").value((int) b));
    }

    // ---------- G5 배정 ----------

    @Test
    void 배정표와_계약은_현행_계획의_그날_공정을_자원별_순서로_준다() throws Exception {
        // 반영 전 — 현행 없음
        mvc.perform(get("/api/aps/dispatch").param("plantCd", "P1").with(admin)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.planId").doesNotExist()).andExpect(jsonPath("$.data.rows.length()").value(0));
        long plan = id(calc(admin, "P1", dayAfter, 3));
        apply(plan).andExpect(status().isOk());
        String body = mvc.perform(get("/api/aps/dispatch").param("plantCd", "p1").param("day", dayAfter.toString()).with(admin))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.planId").value((int) plan))
                .andReturn().getResponse().getContentAsString();
        List<Map<String, Object>> rows = JsonPath.read(body, "$.data.rows");
        // 그날 시작한 절단 공정이 설비 행으로 먼저, 순번은 자원 안 1부터 시작 순
        long expected = accounts.query(comp, """
                select count(*) from aps_plan_op o join aps_plan a on a.plan_id = o.plan_id
                 where o.plan_id = ? and o.start_at is not null and (o.start_at at time zone a.time_zone)::date = ?""",
                Long.class, plan, java.sql.Date.valueOf(dayAfter));
        assertThat(rows).hasSize((int) expected);
        assertThat(rows).isNotEmpty();
        assertThat(rows.get(0).get("resKind")).isEqualTo("EQUIP");
        assertThat(rows.get(0).get("seq")).isEqualTo(1);
        assertThat(rows.stream().allMatch(r -> Boolean.FALSE.equals(r.get("outOfOrder")))).isTrue();

        // 계약(트랜잭션 안) — sequence 는 배정표와 같은 순서, slots 는 발행 지시1 공정 자리
        List<Slot> seq = runner.call(comp, () -> dispatch.sequence("P1", dayAfter));
        assertThat(seq).hasSize(rows.size());
        assertThat(seq.get(0).woNo()).isEqualTo(rows.get(0).get("woNo"));
        assertThat(seq.get(0).planId()).isEqualTo(plan);
        List<Long> procs = procs(wo1);
        Map<Long, Slot> slots = runner.call(comp, () -> dispatch.slots("P1", procs));
        assertThat(slots).containsOnlyKeys(procs.stream().filter(id -> accounts.query(comp, """
                select count(*) from aps_plan_op o join prd_wo_proc p on p.wo_id = o.wo_id and p.op_seq = o.op_seq
                 where o.plan_id = ? and p.wo_proc_id = ? and o.start_at is not null""", Long.class, plan, id) > 0).toList());
        assertThat(slots.values()).allMatch(s -> s.woId() == wo1);
        // 다른 날·다른 공장·빈 값
        assertThat(runner.call(comp, () -> dispatch.sequence("P1", dayAfter.plusDays(30)))).isEmpty();
        assertThat(runner.call(comp, () -> dispatch.sequence("P2", dayAfter))).isEmpty();
        assertThat(runner.call(comp, () -> dispatch.slots("P1", List.of()))).isEmpty();
        assertThat(ApsDispatchQuery.NONE.sequence("P1", dayAfter)).isEmpty();
        assertThatThrownBy(() -> runner.call(comp, () -> dispatch.slots("P1",
                java.util.stream.LongStream.rangeClosed(1, 501).boxed().toList()))).isInstanceOf(IllegalArgumentException.class);
        // 읽기 전용 — 지시는 그대로
        String before = woState();
        mvc.perform(get("/api/aps/dispatch").param("plantCd", "P1").with(admin)).andExpect(status().isOk());
        assertThat(woState()).isEqualTo(before);
    }

    @Test
    void 순서_어김은_앞_순번이_대기인데_뒤가_착수하면_표시만() {
        List<BoardRow> out = ApsDispatchService.rows(List.of(
                row("EQUIP", 1L, 1, "WAIT"), row("EQUIP", 1L, 2, "RUN"), row("EQUIP", 1L, 3, "DONE"),
                row("EQUIP", 2L, 1, "DONE"), row("EQUIP", 2L, 2, "RUN"),
                row("OUTSRC", null, 1, null), row("OUTSRC", null, 2, "RUN")));
        assertThat(out.stream().map(BoardRow::outOfOrder).toList()).containsExactly(false, true, true, false, false, false, true);
        assertThat(out.get(0).resCd()).isEqualTo("E1");
        assertThat(out.get(5).resCd()).isNull();
    }

    private static SlotRow row(String kind, Long equipId, int seq, String procStatus) {
        SlotRow r = new SlotRow();
        r.setResKind(kind);
        r.setEquipId(equipId);
        r.setEquipCd(equipId == null ? null : "E" + equipId);
        r.setSeq(seq);
        r.setProcStatus(procStatus);
        r.setWoNo("W" + seq);
        return r;
    }

    @Test
    void 입력_검증과_권한_그리고_B사는_A사_현행_계획을_보지_못한다() throws Exception {
        long plan = id(calc(admin, "P1", dayAfter, 3));
        apply(plan).andExpect(status().isOk());
        mvc.perform(get("/api/aps/dispatch").with(admin)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("plantCd"));
        mvc.perform(get("/api/aps/dispatch").param("plantCd", "P1").param("day", "2026-13-01").with(admin))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("day"));
        // 화면 권한 없음 403, 조회만 있으면 200
        long nobody = accounts.newUser(comp, "nobody", PW);
        mvc.perform(get("/api/aps/dispatch").param("plantCd", "P1").with(accounts.as(comp, nobody, "nobody"))).andExpect(status().isForbidden());
        long viewer = accounts.newUser(comp, "viewer", PW);
        accounts.grant(comp, viewer, "VIEW", "aps-plan", "SEARCH");
        mvc.perform(get("/api/aps/dispatch").param("plantCd", "P1").with(accounts.as(comp, viewer, "viewer"))).andExpect(status().isOk());

        String compB = accounts.newComp("APSD");
        long ub = accounts.newUser(compB, "admin1", PW);
        accounts.grant(compB, ub, "APS", "aps-plan", "SEARCH");
        RequestPostProcessor b = accounts.as(compB, ub, "admin1");
        accounts.addPlant(compB, "P1", "B 1공장");
        mvc.perform(get("/api/aps/dispatch").param("plantCd", "P1").param("day", dayAfter.toString()).with(b)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.planId").doesNotExist()).andExpect(jsonPath("$.data.rows.length()").value(0));
        assertThat(runner.call(compB, () -> dispatch.sequence("P1", dayAfter))).isEmpty();
        List<Long> procs = procs(wo1);
        assertThat(runner.call(compB, () -> dispatch.slots("P1", procs))).isEmpty();
    }
}
