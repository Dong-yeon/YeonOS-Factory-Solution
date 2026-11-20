package io.smarthappy.module.aps;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.jayway.jsonpath.JsonPath;

import io.smarthappy.platform.common.db.PgErrors;

/**
 * APS 3단계 (docs/38 12장) — 수동 고정 저장(후보 자원·닫힌 지시·시각 검증), 다음 계산이 고정을 존중, 옮기기·해제(rowVer),
 * 동시 고정, 권한, 직접 SQL, A사로 B사, 수주 납기로 순서·늦음, 계획 결과의 납기 사본.
 */
class ApsPinTest extends ApsTestBase {

    private static final String PINS = "/api/aps/pins";

    /** 계획에서 지시·순번의 계획 공정 id */
    private long planOp(long plan, long woId, int opSeq) {
        return accounts.query(comp, "select plan_op_id from aps_plan_op where plan_id = ? and wo_id = ? and op_seq = ?", Long.class,
                plan, woId, opSeq);
    }

    private String equipKey(String equipCd) {
        return "EQUIP:" + accounts.query(comp, "select equip_id from mst_equip where equip_cd = ?", Long.class, equipCd);
    }

    private ResultActions pin(RequestPostProcessor who, long planOpId, String resKey, Instant start) throws Exception {
        return mvc.perform(json(post(PINS), "{\"planOpId\":" + planOpId + ",\"resKey\":\"" + resKey + "\",\"startAt\":\"" + start
                + "\",\"remark\":\"ZT 고정\"}").with(who));
    }

    /** 계산 결과 공정 한 줄: 설비 코드|시작|고정 id|알림 */
    private String opResult(long plan, long woId, int opSeq) {
        return accounts.query(comp, """
                select coalesce(e.equip_cd, o.res_kind) || '|' || coalesce(o.start_at::text, '-') || '|'
                       || coalesce(o.pin_id::text, '-') || '|' || coalesce(o.warn_cd, '-')
                  from aps_plan_op o left join mst_equip e on e.equip_id = o.equip_id
                 where o.plan_id = ? and o.wo_id = ? and o.op_seq = ?""", String.class, plan, woId, opSeq);
    }

    private String ts(Instant t) {
        return accounts.query(comp, "select ?::timestamptz::text", String.class, t.toString());
    }

    // ---------- 정상 ----------

    @Test
    void 고정하면_다음_계산이_그_자리를_먼저_잡고_계획과_지시는_그대로다() throws Exception {
        long p1 = id(calc(admin, "P1", tomorrow, 3));
        // 지시2 절단은 M2 08~10 — M1 13시로 고정
        String before = woState();
        MvcResult r = pin(admin, planOp(p1, wo2, 10), equipKey("M1"), at(tomorrow, 13)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.rowVer").value(0)).andReturn();
        long pinId = ((Number) JsonPath.read(r.getResponse().getContentAsString(), "$.data.pinId")).longValue();
        assertThat(woState()).as("고정은 지시를 바꾸지 않는다").isEqualTo(before);
        assertThat(opResult(p1, wo2, 10)).as("이미 계산된 계획도 그대로").startsWith("M2|");

        mvc.perform(get(PINS + "?plantCd=P1").with(admin)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].resCd").value("M1"))
                .andExpect(jsonPath("$.data[0].procCd").value("CUT"))
                .andExpect(jsonPath("$.data[0].srcPlanId").value((int) p1));

        long p2 = id(calc(admin, "P1", tomorrow, 3));
        assertThat(opResult(p2, wo2, 10)).isEqualTo("M1|" + ts(at(tomorrow, 13)) + "|" + pinId + "|-");
        assertThat(opResult(p2, wo1, 10)).as("지시1 은 M1 08~11 그대로(고정 13~15 와 안 겹침)").startsWith("M1|" + ts(at(tomorrow, 8)));
        // 간트: 끌어 놓기 후보·지금 고정
        mvc.perform(get(A + "/" + p2 + "/gantt").with(admin)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.pins.length()").value(1))
                .andExpect(jsonPath("$.data.ops[?(@.woId == " + wo2 + " && @.opSeq == 10)].pinId", Matchers.contains((int) pinId)))
                .andExpect(jsonPath("$.data.ops[?(@.woId == " + wo2 + " && @.opSeq == 10)].cands[*]",
                        Matchers.containsInAnyOrder(equipKey("M1"), equipKey("M2"))))
                .andExpect(jsonPath("$.data.ops[?(@.woId == " + wo1 + " && @.opSeq == 20)].cands[*]", Matchers.contains("OUTSRC")));

        // 옮기기(근무 외 06시 → 계산이 08시로 밀고 알림), 낡은 rowVer 409
        mvc.perform(json(put(PINS + "/" + pinId), "{\"resKey\":\"" + equipKey("M2") + "\",\"startAt\":\"" + at(tomorrow, 6)
                + "\",\"rowVer\":0}").with(admin)).andExpect(status().isOk()).andExpect(jsonPath("$.data.rowVer").value(1));
        mvc.perform(json(put(PINS + "/" + pinId), "{\"resKey\":\"" + equipKey("M2") + "\",\"startAt\":\"" + at(tomorrow, 9)
                + "\",\"rowVer\":0}").with(admin)).andExpect(status().isConflict());
        long p3 = id(calc(admin, "P1", tomorrow, 3));
        assertThat(opResult(p3, wo2, 10)).isEqualTo("M2|" + ts(at(tomorrow, 8)) + "|" + pinId + "|PIN_MOVED");

        // 해제 → 다음 계산은 고정 없음, 다시 해제 404
        mvc.perform(json(post(PINS + "/" + pinId + "/release"), "{\"rowVer\":1}").with(admin)).andExpect(status().isOk());
        mvc.perform(json(post(PINS + "/" + pinId + "/release"), "{\"rowVer\":2}").with(admin)).andExpect(status().isNotFound());
        long p4 = id(calc(admin, "P1", tomorrow, 3));
        assertThat(opResult(p4, wo2, 10)).endsWith("|-|-");
        mvc.perform(get(PINS + "?plantCd=P1").with(admin)).andExpect(jsonPath("$.data.length()").value(0));
        // 해제한 고정은 사용 중지로 남고 감사 이력이 있다
        assertThat(accounts.query(comp, "select use_yn from aps_pin where pin_id = ?", String.class, pinId)).isEqualTo("N");
        assertThat(accounts.query(comp, "select count(*) from sys_audit_log where table_nm = 'aps_pin' and row_key = ?", Long.class,
                String.valueOf(pinId))).isEqualTo(3L);
    }

    @Test
    void 납기는_수주_행_납기이고_계획_결과에_사본이_남는다() throws Exception {
        long cust = accounts.query(comp, """
                insert into mst_cust (comp_cd, cust_cd, cust_nm, cust_type, created_by, updated_by)
                values (?, 'C1', '고객', 'SALES', 't', 't') returning cust_id""", Long.class, comp);
        long order = accounts.query(comp, """
                insert into sal_order (comp_cd, order_no, cust_id, order_date, created_by, updated_by)
                values (?, 'SO-ZT1', ?, (now() at time zone 'Asia/Seoul')::date, 't', 't') returning order_id""", Long.class, comp, cust);
        // 납기 = 사흘 뒤 — 지시 완료일(내일)보다 늦다
        long line = accounts.query(comp, """
                insert into sal_order_line (comp_cd, order_id, line_no, item_id, qty, due_date, created_by, updated_by)
                values (?, ?, 1, (select item_id from mst_item where item_cd = 'FG'), 3, ?, 't', 't') returning line_id""",
                Long.class, comp, order, java.sql.Date.valueOf(tomorrow.plusDays(2)));
        long wo3 = id(mvc.perform(json(post("/api/production/wos"), "{\"plantCd\":\"P1\",\"itemCd\":\"FG\",\"planQty\":\"1\",\"planStart\":\""
                + tomorrow + "\",\"planEnd\":\"" + tomorrow + "\",\"priority\":1,\"orderLineId\":" + line + "}").with(admin)));
        long plan = id(calc(admin, "P1", tomorrow, 3));
        mvc.perform(get(A + "/" + plan).with(admin)).andExpect(status().isOk())
                // 같은 우선 1 이지만 지시1(완료일 내일)이 수주 납기(모레+1)인 지시3 보다 먼저
                .andExpect(jsonPath("$.data.wos[0].woId").value((int) wo1))
                .andExpect(jsonPath("$.data.wos[1].woId").value((int) wo3))
                .andExpect(jsonPath("$.data.wos[1].dueDate").value(tomorrow.plusDays(2).toString()))
                .andExpect(jsonPath("$.data.wos[1].dueSrc").value("ORDER"))
                .andExpect(jsonPath("$.data.wos[1].lateYn").value("N"))          // 지시 완료일(내일) 기준이었다면 늦음
                .andExpect(jsonPath("$.data.wos[0].dueSrc").value("WO"))
                .andExpect(jsonPath("$.data.wos[0].lateYn").value("Y"));
    }

    // ---------- 검증·권한 ----------

    @Test
    void 후보가_아닌_자원_닫힌_지시_잘못된_시각은_고정할_수_없다() throws Exception {
        long plan = id(calc(admin, "P1", tomorrow, 3));
        long cut2 = planOp(plan, wo2, 10);
        pin(admin, cut2, "NODE:" + line2, at(tomorrow, 9)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("resKey")).andExpect(jsonPath("$.errors[0].code").value("APS_PIN_RES"));
        pin(admin, cut2, "OUTSRC", at(tomorrow, 9)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].code").value("APS_PIN_RES"));
        pin(admin, planOp(plan, wo1, 20), equipKey("M1"), at(tomorrow, 9)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].code").value("APS_PIN_RES"));         // 외주 공정은 외주만
        // 발행 지시1 절단에 M2 배정 → 그 공정 후보는 M2 만
        long cutProc1 = accounts.query(comp, "select wo_proc_id from prd_wo_proc where wo_id = ? and op_seq = 10", Long.class, wo1);
        mvc.perform(json(put("/api/production/wos/procs/" + cutProc1 + "/equip"), "{\"equipCd\":\"M2\",\"rowVer\":0}").with(admin))
                .andExpect(status().isOk());
        pin(admin, planOp(plan, wo1, 10), equipKey("M1"), at(tomorrow, 9)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].code").value("APS_PIN_RES"));
        pin(admin, planOp(plan, wo1, 10), equipKey("M2"), at(tomorrow, 9)).andExpect(status().isOk());
        mvc.perform(json(post(PINS), "{\"planOpId\":" + cut2 + ",\"resKey\":\"" + equipKey("M1") + "\",\"startAt\":\"2026-13-01T00:00Z\"}")
                .with(admin)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].code").value("FORMAT"));
        mvc.perform(json(post(PINS), "{\"planOpId\":" + cut2 + ",\"resKey\":\"" + equipKey("M1") + "\",\"startAt\":\"2026-11-01T09:00\"}")
                .with(admin)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].code").value("FORMAT"));   // 오프셋 없음
        mvc.perform(json(post(PINS), "{\"planOpId\":" + cut2 + ",\"resKey\":\"" + equipKey("M1") + "\"}")
                .with(admin)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("startAt"));
        mvc.perform(json(post(PINS), "{\"resKey\":\"OUTSRC\"}").with(admin)).andExpect(status().isBadRequest());
        pin(admin, 999999999L, equipKey("M1"), at(tomorrow, 9)).andExpect(status().isNotFound());
        // 취소한 지시
        mvc.perform(json(post("/api/production/wos/" + wo2 + "/cancel"), "{\"rowVer\":0,\"reason\":\"ZT\"}").with(admin))
                .andExpect(status().isOk());
        pin(admin, cut2, equipKey("M1"), at(tomorrow, 9)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].code").value("APS_PIN_CLOSED"));
    }

    @Test
    void 조회만_권한이면_고정할_수_없다() throws Exception {
        long plan = id(calc(admin, "P1", tomorrow, 3));
        long viewer = accounts.newUser(comp, "viewer", PW);
        accounts.grant(comp, viewer, "VIEW", "aps-plan", "SEARCH");
        RequestPostProcessor v = accounts.as(comp, viewer, "viewer");
        pin(v, planOp(plan, wo2, 10), equipKey("M1"), at(tomorrow, 13)).andExpect(status().isForbidden());
        mvc.perform(get(PINS).with(v)).andExpect(status().isOk());
    }

    // ---------- 동시 ----------

    @Test
    void 같은_공정을_동시에_고정하면_하나만_된다() throws Exception {
        long plan = id(calc(admin, "P1", tomorrow, 3));
        long op = planOp(plan, wo2, 10);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        List<Integer> codes = new ArrayList<>();
        try {
            List<Callable<Integer>> calls = new ArrayList<>();
            for (int h : new int[] {13, 14}) {
                calls.add(() -> pin(admin, op, equipKey("M1"), at(tomorrow, h)).andReturn().getResponse().getStatus());
            }
            for (Future<Integer> f : pool.invokeAll(calls)) {
                codes.add(f.get());
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(codes).containsExactlyInAnyOrder(200, 409);
        assertThat(accounts.query(comp, "select count(*) from aps_pin where use_yn = 'Y'", Long.class)).isEqualTo(1L);
        pin(admin, op, equipKey("M1"), at(tomorrow, 15)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("APS_PIN_EXISTS"));
        // 같은 고정을 동시에 옮기기 → rowVer 로 하나만
        long pinId = accounts.query(comp, "select pin_id from aps_pin where use_yn = 'Y'", Long.class);
        ExecutorService pool2 = Executors.newFixedThreadPool(2);
        List<Integer> codes2 = new ArrayList<>();
        try {
            List<Callable<Integer>> calls = new ArrayList<>();
            for (int h : new int[] {9, 10}) {
                calls.add(() -> mvc.perform(json(put(PINS + "/" + pinId), "{\"resKey\":\"" + equipKey("M2") + "\",\"startAt\":\""
                        + at(tomorrow, h) + "\",\"rowVer\":0}").with(admin)).andReturn().getResponse().getStatus());
            }
            for (Future<Integer> f : pool2.invokeAll(calls)) {
                codes2.add(f.get());
            }
        } finally {
            pool2.shutdownNow();
        }
        assertThat(codes2).containsExactlyInAnyOrder(200, 409);
    }

    // ---------- 직접 SQL ----------

    @Test
    void 고정의_공정은_바꿀_수_없고_지울_수_없다() throws Exception {
        long plan = id(calc(admin, "P1", tomorrow, 3));
        pin(admin, planOp(plan, wo2, 10), equipKey("M1"), at(tomorrow, 13)).andExpect(status().isOk());
        Throwable fixed = catchThrowable(() -> accounts.exec(comp, "update aps_pin set wo_id = ?", wo1));
        assertThat(PgErrors.constraint(fixed)).contains("ck_aps_pin_fixed");
        assertThatThrownBy(() -> accounts.exec(comp, "delete from aps_pin")).rootCause().hasMessageContaining("permission denied");
        Throwable res = catchThrowable(() -> accounts.exec(comp, "update aps_pin set res_kind = 'NODE'"));
        assertThat(PgErrors.constraint(res)).contains("ck_aps_pin_res");
        // 같은 공정 두 번째 사용 중 고정(직접 SQL)도 부분 유일로 막힌다
        Throwable dup = catchThrowable(() -> accounts.exec(comp, """
                insert into aps_pin (comp_cd, plant_cd, wo_id, op_seq, res_kind, start_at, src_plan_id, created_by, updated_by)
                select comp_cd, plant_cd, wo_id, op_seq, 'OUTSRC', start_at, src_plan_id, 'x', 'x' from aps_pin"""));
        assertThat(PgErrors.constraint(dup)).contains("uq_aps_pin_op");
    }

    // ---------- A사로 B사 ----------

    @Test
    void B사는_A사_계획_공정을_고정하거나_A사_고정을_보거나_옮기거나_해제할_수_없다() throws Exception {
        long plan = id(calc(admin, "P1", tomorrow, 3));
        long op = planOp(plan, wo2, 10);
        MvcResult r = pin(admin, op, equipKey("M1"), at(tomorrow, 13)).andExpect(status().isOk()).andReturn();
        long pinId = ((Number) JsonPath.read(r.getResponse().getContentAsString(), "$.data.pinId")).longValue();
        String compB = accounts.newComp("APSB");
        long ub = accounts.newUser(compB, "admin1", PW);
        accounts.grant(compB, ub, "APS", "aps-plan", "SEARCH", "SAVE");
        RequestPostProcessor b = accounts.as(compB, ub, "admin1");
        accounts.addPlant(compB, "P1", "B 1공장");
        pin(b, op, equipKey("M1"), at(tomorrow, 9)).andExpect(status().isNotFound());
        mvc.perform(get(PINS).with(b)).andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(0));
        mvc.perform(json(put(PINS + "/" + pinId), "{\"resKey\":\"" + equipKey("M2") + "\",\"startAt\":\"" + at(tomorrow, 9)
                + "\",\"rowVer\":0}").with(b)).andExpect(status().isNotFound());
        mvc.perform(json(post(PINS + "/" + pinId + "/release"), "{\"rowVer\":0}").with(b)).andExpect(status().isNotFound());
        // B사 범위 직접 SQL: A사 고정 변경 0행, A사 지시·계획을 가리키는 고정 → 같은 회사 트리거
        assertThat(accounts.exec(compB, "update aps_pin set start_at = now() where pin_id = ?", pinId)).isZero();
        Throwable cross = catchThrowable(() -> accounts.exec(compB, """
                insert into aps_pin (comp_cd, plant_cd, wo_id, op_seq, res_kind, start_at, src_plan_id, created_by, updated_by)
                values (?, 'P1', ?, 10, 'OUTSRC', now(), ?, 'x', 'x')""", compB, wo2, plan));
        assertThat(PgErrors.constraint(cross)).contains("fk_aps_pin_ref_comp");
        assertThat(accounts.query(comp, "select row_ver from aps_pin where pin_id = ?", Integer.class, pinId)).isZero();
    }
}
