package io.smarthappy.module.aps;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;
import java.util.stream.LongStream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.jayway.jsonpath.JsonPath;

import io.smarthappy.platform.common.db.PgErrors;

/**
 * 부족 → 구매 요청 초안 (docs/38 17.4, docs/63 G2·AP-7) — 미리보기(쓰지 않음)·MOQ·배수 올림 손 검산·필요일/발주 필요일, 만들기(PrService DRAFT +
 * APS 연결), 다시 만들기 막기·취소/줄 삭제 뒤 다시, 이상 사용(권한·반제품·부족 아님·빈/초과 목록·없는 id·폐기 계획·같은 요청 키·동시), 직접 SQL, A사로 B사.
 *
 * <p>자료: FG3(절단 L1) BOM = RM 2 + RM2 1 + SFG(반제품) 1, 지시3(5개 발행, 우선 1)·지시4(3개, 2)·지시5(2개, 3), RM 가용 12.
 * WARN 할당: RM 지시3 10/10/0 · 지시4 6/2/4 · 지시5 4/0/4, RM2·SFG 는 재고 없음 → 전부 부족. RM 공급원 V100: MOQ 10·배수 4·리드타임 7일, RM2 공급원 없음.
 */
class ApsPrTest extends ApsTestBase {

    long rm;
    long rm2;
    long wo3;
    long wo4;
    long wo5;
    long loc;
    long plan;

    @BeforeEach
    void prSetUp() throws Exception {
        accounts.grant(comp, userId, "APS", "srm-pr", "SEARCH", "SAVE");
        accounts.grant(comp, userId, "APS", "wms-move", "SEARCH", "SAVE");
        accounts.exec(comp, """
                insert into mst_item (comp_cd, item_cd, item_nm, item_type, proc_type, unit_cd, lot_ctrl_yn, created_by, updated_by)
                values (?, 'FG3', 'FG3 품목', 'FG', 'MAKE', 'EA', 'N', 't', 't'), (?, 'RM', '원자재', 'RM', 'BUY', 'EA', 'N', 't', 't'),
                       (?, 'RM2', '원자재2', 'RM', 'BUY', 'EA', 'N', 't', 't'), (?, 'SFG', '반제품', 'SFG', 'MAKE', 'EA', 'N', 't', 't')""",
                comp, comp, comp, comp);
        rm = item("RM");
        rm2 = item("RM2");
        long line1 = accounts.query(comp, "select node_id from mst_equip_hier where node_cd = 'L1'", Long.class);
        routing("FG3", line1, false);
        long bom = accounts.query(comp, """
                insert into mst_bom (comp_cd, item_id, rev, valid_from, base_qty, created_by, updated_by)
                select ?, item_id, 'A0', (now() at time zone 'Asia/Seoul')::date - 30, 1, 't', 't' from mst_item where item_cd = 'FG3' returning bom_id""",
                Long.class, comp);
        int no = 10;
        for (Object[] l : new Object[][] {{rm, 2}, {rm2, 1}, {item("SFG"), 1}}) {
            accounts.exec(comp, """
                    insert into mst_bom_line (comp_cd, bom_id, line_no, item_id, qty, unit_cd, line_type, op_seq, issue_method, created_by, updated_by)
                    values (?, ?, ?, ?, ?, 'EA', 'NORMAL', 10, 'MANUAL', 't', 't')""", comp, bom, no, l[0], l[1]);
            no += 10;
        }
        accounts.exec(comp, "update mst_bom set status = 'RELEASED', released_at = now(), released_by = 't' where bom_id = ?", bom);
        wo3 = wo("FG3", "5", 1);
        mvc.perform(json(post("/api/production/wos/" + wo3 + "/release"), "{\"rowVer\":0}").with(admin)).andExpect(status().isOk());
        wo4 = wo("FG3", "3", 2);
        wo5 = wo("FG3", "2", 3);
        long wh = node(null, "WH", "WH1");
        loc = node(wh, "LOC", "A-01");
        mvc.perform(json(post("/api/wms/move/adjust"), "{\"plantCd\":\"P1\",\"nodeId\":" + loc + ",\"itemId\":" + rm
                + ",\"stockStatus\":\"AVAILABLE\",\"delta\":12,\"reasonCd\":\"FOUND\"}").with(admin)).andExpect(status().isOk());
        // 공급원: RM ← V100(MOQ 10, 배수 4, 7일). 차단된 협력사 V900 은 우선순위가 더 높아도 고르지 않는다
        long v100 = supplier("V100", "ACTIVE");
        long v900 = supplier("V900", "BLOCKED");
        accounts.exec(comp, """
                insert into srm_source (comp_cd, cust_id, item_id, lead_days, moq, order_mult, priority, created_by, updated_by)
                values (?, ?, ?, 7, 10, 4, 2, 't', 't'), (?, ?, ?, 1, null, null, 1, 't', 't')""", comp, v100, rm, comp, v900, rm);
        plan = id(mvc.perform(json(post(A), "{\"plantCd\":\"P1\",\"horizonStart\":\"" + tomorrow + "\",\"horizonDays\":3,\"planNm\":\"ZT 부족\"}")
                .with(admin)));
    }

    // ---------- 도우미 ----------

    long item(String cd) {
        return accounts.query(comp, "select item_id from mst_item where item_cd = ?", Long.class, cd);
    }

    long supplier(String cd, String st) {
        long c = accounts.query(comp, """
                insert into mst_cust (comp_cd, cust_cd, cust_nm, cust_type, created_by, updated_by)
                values (?, ?, ?, 'PURCHASE', 't', 't') returning cust_id""", Long.class, comp, cd, cd + " 공급사");
        accounts.exec(comp, """
                insert into srm_supplier (cust_id, comp_cd, currency_cd, trade_status, status_reason, created_by, updated_by)
                values (?, ?, 'KRW', ?, ?, 't', 't')""", c, comp, st, "ACTIVE".equals(st) ? null : "ZT 차단");
        return c;
    }

    long mat(long woId, long itemId) {
        return accounts.query(comp, "select plan_mat_id from aps_plan_mat where plan_id = ? and wo_id = ? and item_id = ?", Long.class,
                plan, woId, itemId);
    }

    long sfgMat(long woId) {
        return mat(woId, item("SFG"));
    }

    static String ids(long... ids) {
        return "{\"matIds\":[" + LongStream.of(ids).mapToObj(String::valueOf).collect(Collectors.joining(",")) + "]}";
    }

    ResultActions preview(RequestPostProcessor who, long planId, String body) throws Exception {
        return mvc.perform(json(post(A + "/" + planId + "/pr/preview"), body).with(who));
    }

    ResultActions make(RequestPostProcessor who, long planId, String body) throws Exception {
        return mvc.perform(json(post(A + "/" + planId + "/pr"), body).with(who));
    }

    long prId(ResultActions r) throws Exception {
        String body = r.andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(body, "$.data.prId")).longValue();
    }

    LocalDate needDate(long... matIds) {
        return LocalDate.parse(accounts.query(comp, """
                select min((m.need_at at time zone p.time_zone)::date)::text from aps_plan_mat m join aps_plan p on p.plan_id = m.plan_id
                 where m.plan_mat_id = any(?)""", String.class, (Object) LongStream.of(matIds).boxed().toArray(Long[]::new)));
    }

    long count(String table) {
        return accounts.query(comp, "select count(*) from " + table, Long.class);
    }

    // ---------- 정상 ----------

    @Test
    void 자료_확인_WARN_할당과_부족() {
        assertThat(accounts.query(comp, """
                select string_agg(i.item_cd || ':' || trim_scale(m.need_qty) || '/' || trim_scale(m.alloc_qty) || '/' || trim_scale(m.short_qty), ','
                                  order by i.item_cd, m.wo_id)
                  from aps_plan_mat m join mst_item i on i.item_id = m.item_id where m.plan_id = ?""", String.class, plan))
                .isEqualTo("RM:10/10/0,RM:6/2/4,RM:4/0/4,RM2:5/0/5,RM2:3/0/3,RM2:2/0/2,SFG:5/0/5,SFG:3/0/3,SFG:2/0/2");
    }

    @Test
    void 미리보기는_부족_합을_MOQ_뒤_배수로_올리고_필요일_발주필요일을_내며_아무것도_쓰지_않는다() throws Exception {
        long a = mat(wo4, rm);
        long b = mat(wo5, rm);
        long c = mat(wo3, rm2);
        long d = mat(wo4, rm2);
        LocalDate need = needDate(a, b);
        // RM: 부족 4 + 4 = 8 → MOQ 10 → 배수 4 로 올림 12 (손 검산). RM2: 공급원 없음 → 5 + 3 = 8 그대로
        preview(admin, plan, ids(a, b, c, d)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.plantCd").value("P1"))
                .andExpect(jsonPath("$.data.errors.length()").value(0))
                .andExpect(jsonPath("$.data.lines.length()").value(2))
                .andExpect(jsonPath("$.data.lines[0].itemCd").value("RM"))
                .andExpect(jsonPath("$.data.lines[0].shortQty").value("8"))
                .andExpect(jsonPath("$.data.lines[0].moq").value("10"))
                .andExpect(jsonPath("$.data.lines[0].orderMult").value("4"))
                .andExpect(jsonPath("$.data.lines[0].reqQty").value("12"))
                .andExpect(jsonPath("$.data.lines[0].leadDays").value(7))
                .andExpect(jsonPath("$.data.lines[0].needDate").value(need.toString()))
                .andExpect(jsonPath("$.data.lines[0].orderBy").value(need.minusDays(7).toString()))
                .andExpect(jsonPath("$.data.lines[0].late").value(true))           // 필요일이 내일 근처 − 7일 → 지났음
                .andExpect(jsonPath("$.data.lines[0].supplierCd").value("V100"))   // 차단된 V900(우선 1)은 건너뜀
                .andExpect(jsonPath("$.data.lines[0].woNos.length()").value(2))
                .andExpect(jsonPath("$.data.lines[1].itemCd").value("RM2"))
                .andExpect(jsonPath("$.data.lines[1].reqQty").value("8"))
                .andExpect(jsonPath("$.data.lines[1].noSource").value(true))
                .andExpect(jsonPath("$.data.lines[1].supplierCd").doesNotExist());
        assertThat(count("srm_pr")).isZero();
        assertThat(count("aps_plan_pr")).isZero();
    }

    @Test
    void 만들기는_작성_중_요청_하나와_연결을_남기고_자재_표에_요청_번호가_보이며_같은_줄은_다시_못_만든다() throws Exception {
        long a = mat(wo4, rm);
        long b = mat(wo5, rm);
        long c = mat(wo4, rm2);
        String before = woState();
        long pr = prId(make(admin, plan, ids(a, b, c)));
        assertThat(woState()).as("지시는 그대로").isEqualTo(before);
        String planNo = accounts.query(comp, "select plan_no from aps_plan where plan_id = ?", String.class, plan);
        assertThat(accounts.query(comp, "select status || '/' || plant_cd || '/' || reason || '/' || need_date from srm_pr where pr_id = ?",
                String.class, pr)).isEqualTo("DRAFT/P1/APS " + planNo + " 자재 부족/" + needDate(a, b, c));
        assertThat(accounts.query(comp, """
                select string_agg(i.item_cd || ':' || trim_scale(l.qty) || ':' || coalesce(c.cust_cd, '-'), ',' order by l.line_no)
                  from srm_pr_line l join mst_item i on i.item_id = l.item_id left join mst_cust c on c.cust_id = l.pref_cust_id
                 where l.pr_id = ?""", String.class, pr)).isEqualTo("RM:12:V100,RM2:3:-");
        assertThat(accounts.query(comp, """
                select string_agg(k.plan_mat_id || ':' || trim_scale(k.short_qty) || ':' || (l.item_id = k.item_id), ',' order by k.plan_mat_id)
                  from aps_plan_pr k join srm_pr_line l on l.pr_line_id = k.pr_line_id where k.pr_id = ?""", String.class, pr))
                .isEqualTo(List.of(a + ":4:true", b + ":4:true", c + ":3:true").stream().sorted((x, y) ->
                        Long.compare(Long.parseLong(x.split(":")[0]), Long.parseLong(y.split(":")[0]))).collect(Collectors.joining(",")));
        String prNo = accounts.query(comp, "select pr_no from srm_pr where pr_id = ?", String.class, pr);
        mvc.perform(get(A + "/" + plan).with(admin))
                .andExpect(jsonPath("$.data.mats[?(@.planMatId == " + a + ")].prNo").value(prNo))
                .andExpect(jsonPath("$.data.mats[?(@.planMatId == " + a + ")].procType").value("BUY"))
                .andExpect(jsonPath("$.data.mats[?(@.planMatId == " + mat(wo5, rm2) + ")].prNo").value(org.hamcrest.Matchers.contains(org.hamcrest.Matchers.nullValue())));
        // 같은 줄 다시 → 미리보기는 줄 오류, 만들기는 409(아무것도 안 생김)
        preview(admin, plan, ids(a)).andExpect(status().isOk()).andExpect(jsonPath("$.data.errors[0].code").value("APS_PR_LINKED"))
                .andExpect(jsonPath("$.data.errors[0].matId").value((int) a));
        make(admin, plan, ids(a, mat(wo5, rm2))).andExpect(status().isConflict())
                .andExpect(jsonPath("$.errors[0].rowKey").value(String.valueOf(a)));
        assertThat(count("srm_pr")).isEqualTo(1);
        assertThat(count("aps_plan_pr")).isEqualTo(3);
    }

    @Test
    void 요청을_취소하거나_작성_중에_줄을_지우면_그_줄은_다시_요청할_수_있다() throws Exception {
        long a = mat(wo4, rm);
        long c = mat(wo4, rm2);
        long pr1 = prId(make(admin, plan, ids(a, c)));
        mvc.perform(json(post("/api/srm/prs/" + pr1 + "/cancel"), "{\"rowVer\":0,\"reason\":\"ZT 취소\"}").with(admin)).andExpect(status().isOk());
        long pr2 = prId(make(admin, plan, ids(a, c)));
        assertThat(pr2).isNotEqualTo(pr1);
        // 작성 중 요청에서 RM2 줄을 지운다(SRM 화면 한 벌 저장) → RM2 자재 줄만 다시 풀린다
        long rmLine = accounts.query(comp, "select pr_line_id from srm_pr_line where pr_id = ? and item_id = ?", Long.class, pr2, rm);
        mvc.perform(json(put("/api/srm/prs/" + pr2), "{\"plantCd\":\"P1\",\"rowVer\":0,\"lines\":[{\"prLineId\":" + rmLine
                + ",\"itemCd\":\"RM\",\"qty\":\"12\"}]}").with(admin)).andExpect(status().isOk());
        preview(admin, plan, ids(a, c)).andExpect(jsonPath("$.data.errors.length()").value(1))
                .andExpect(jsonPath("$.data.errors[0].matId").value((int) a)).andExpect(jsonPath("$.data.lines[0].itemCd").value("RM2"));
        prId(make(admin, plan, ids(c)));
        assertThat(count("srm_pr")).isEqualTo(3);
        // 연결 기록은 지우지 않는다(추가만) — 죽은 연결도 남는다
        assertThat(count("aps_plan_pr")).isEqualTo(5);
    }

    // ---------- 이상 사용 ----------

    @Test
    void 구매_요청_권한이_없으면_만들_수_없고_미리보기만_된다() throws Exception {
        long planner = accounts.newUser(comp, "planner", PW);
        accounts.grant(comp, planner, "PLAN", "aps-plan", "SEARCH", "SAVE");
        RequestPostProcessor p = accounts.as(comp, planner, "planner");
        long a = mat(wo4, rm);
        preview(p, plan, ids(a)).andExpect(status().isOk());
        make(p, plan, ids(a)).andExpect(status().isForbidden());
        // 조회만 가진 사람은 만들기 자체가 막힘(API 직접)
        long viewer = accounts.newUser(comp, "viewer", PW);
        accounts.grant(comp, viewer, "VIEW", "aps-plan", "SEARCH");
        accounts.grant(comp, viewer, "VIEW2", "srm-pr", "SEARCH", "SAVE");
        make(accounts.as(comp, viewer, "viewer"), plan, ids(a)).andExpect(status().isForbidden());
        assertThat(count("srm_pr")).isZero();
        assertThat(count("aps_plan_pr")).isZero();
    }

    @Test
    void 반제품_부족_아닌_줄_빈_목록_초과_없는_id_폐기_계획을_막는다() throws Exception {
        make(admin, plan, ids(sfgMat(wo4))).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].code").value("APS_PR_MAKE"));
        make(admin, plan, ids(mat(wo3, rm))).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].code").value("APS_PR_NOT_SHORT"));
        // 섞이면 미리보기는 되는 줄 + 오류 줄, 만들기는 전부 거부
        preview(admin, plan, ids(sfgMat(wo4), mat(wo4, rm))).andExpect(jsonPath("$.data.lines.length()").value(1))
                .andExpect(jsonPath("$.data.errors[0].code").value("APS_PR_MAKE"));
        make(admin, plan, ids(sfgMat(wo4), mat(wo4, rm))).andExpect(status().isBadRequest());
        make(admin, plan, "{\"matIds\":[]}").andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].code").value("REQUIRED"));
        make(admin, plan, "{}").andExpect(status().isBadRequest());
        make(admin, plan, "{\"matIds\":[null]}").andExpect(status().isBadRequest());
        make(admin, plan, ids(LongStream.rangeClosed(1, 201).toArray())).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].code").value("TOO_MANY_LINES"));
        make(admin, plan, ids(mat(wo4, rm), 999_999_999L)).andExpect(status().isNotFound());
        make(admin, 999_999_999L, ids(mat(wo4, rm))).andExpect(status().isNotFound());
        // 같은 id 두 번은 한 번으로
        preview(admin, plan, ids(mat(wo4, rm), mat(wo4, rm))).andExpect(jsonPath("$.data.lines[0].shortQty").value("4"));
        // 다른 계획의 줄 id 는 이 계획에서 404
        long plan2 = id(mvc.perform(json(post(A), "{\"plantCd\":\"P1\",\"horizonStart\":\"" + tomorrow + "\",\"horizonDays\":3}").with(admin)));
        long other = accounts.query(comp, "select plan_mat_id from aps_plan_mat where plan_id = ? and wo_id = ? and item_id = ?", Long.class,
                plan2, wo4, rm);
        make(admin, plan, ids(other)).andExpect(status().isNotFound());
        // 폐기한 계획
        mvc.perform(json(post(A + "/" + plan2 + "/discard"), "{\"rowVer\":0,\"reason\":\"ZT\"}").with(admin)).andExpect(status().isOk());
        make(admin, plan2, ids(other)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].code").value("APS_PR_DISCARDED"));
        assertThat(count("srm_pr")).isZero();
    }

    @Test
    void 같은_요청_키로_두_번_보내면_두_번째는_409_이고_요청은_하나() throws Exception {
        long a = mat(wo4, rm);
        mvc.perform(json(post(A + "/" + plan + "/pr"), ids(a)).header("X-Idempotency-Key", "zt-aps-pr-1").with(admin))
                .andExpect(status().isOk());
        mvc.perform(json(post(A + "/" + plan + "/pr"), ids(mat(wo5, rm))).header("X-Idempotency-Key", "zt-aps-pr-1").with(admin))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DUPLICATE_REQUEST"));
        assertThat(count("srm_pr")).isEqualTo(1);
    }

    @Test
    void 동시에_같은_줄로_만들면_하나만_되고_나머지는_409() throws Exception {
        String body = ids(mat(wo4, rm), mat(wo5, rm));
        int n = 3;
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch go = new CountDownLatch(1);
        try {
            List<Future<Integer>> fs = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                fs.add(pool.submit(() -> {
                    go.await();
                    return make(admin, plan, body).andReturn().getResponse().getStatus();
                }));
            }
            go.countDown();
            List<Integer> codes = new ArrayList<>();
            for (Future<Integer> f : fs) {
                codes.add(f.get());
            }
            Collections.sort(codes);
            assertThat(codes).containsExactly(200, 409, 409);
        } finally {
            pool.shutdownNow();
        }
        assertThat(count("srm_pr")).isEqualTo(1);
        assertThat(count("aps_plan_pr")).isEqualTo(2);
    }

    @Test
    void 직접_SQL_로_연결을_고치거나_지우거나_엉뚱한_줄로_넣지_못한다() throws Exception {
        long a = mat(wo4, rm);
        long pr = prId(make(admin, plan, ids(a)));
        assertThatThrownBy(() -> accounts.exec(comp, "update aps_plan_pr set short_qty = 1 where pr_id = ?", pr))
                .rootCause().hasMessageContaining("permission denied");
        assertThatThrownBy(() -> accounts.exec(comp, "delete from aps_plan_pr where pr_id = ?", pr))
                .rootCause().hasMessageContaining("permission denied");
        long rmLine = accounts.query(comp, "select pr_line_id from srm_pr_line where pr_id = ?", Long.class, pr);
        // 부족이 아닌 줄(지시3 RM 10/10/0)
        long full = mat(wo3, rm);
        Throwable notShort = catchThrowable(() -> accounts.exec(comp, """
                insert into aps_plan_pr (comp_cd, plan_id, plan_mat_id, wo_id, op_seq, item_id, short_qty, pr_id, pr_line_id, created_by)
                values (?, ?, ?, ?, 10, ?, 1, ?, ?, 't')""", comp, plan, full, wo3, rm, pr, rmLine));
        assertThat(PgErrors.constraint(notShort)).contains("fk_aps_plan_pr_mat");
        // 요청 줄 품목이 다름(RM2 자재 줄 ↔ RM 요청 줄)
        long m2 = mat(wo4, rm2);
        Throwable otherItem = catchThrowable(() -> accounts.exec(comp, """
                insert into aps_plan_pr (comp_cd, plan_id, plan_mat_id, wo_id, op_seq, item_id, short_qty, pr_id, pr_line_id, created_by)
                values (?, ?, ?, ?, 10, ?, 3, ?, ?, 't')""", comp, plan, m2, wo4, rm2, pr, rmLine));
        assertThat(PgErrors.constraint(otherItem)).contains("fk_aps_plan_pr_line");
        assertThat(accounts.query(comp, "select count(*) from sys_audit_log where table_nm = 'aps_plan_pr'", Long.class)).isEqualTo(1);
    }

    @Test
    void B사는_A사_계획으로_미리보기나_요청을_만들_수_없고_연결을_보거나_넣지_못한다() throws Exception {
        long a = mat(wo4, rm);
        long pr = prId(make(admin, plan, ids(a)));
        String compB = accounts.newComp("APRB");
        long ub = accounts.newUser(compB, "admin1", PW);
        accounts.grant(compB, ub, "APS", "aps-plan", "SEARCH", "SAVE");
        accounts.grant(compB, ub, "APS", "srm-pr", "SEARCH", "SAVE");
        RequestPostProcessor b = accounts.as(compB, ub, "admin1");
        preview(b, plan, ids(a)).andExpect(status().isNotFound());
        make(b, plan, ids(mat(wo5, rm))).andExpect(status().isNotFound());
        assertThat(accounts.query(compB, "select count(*) from aps_plan_pr", Long.class)).isZero();
        assertThat(accounts.query(compB, "select count(*) from srm_pr", Long.class)).isZero();
        long rmLine = accounts.query(comp, "select pr_line_id from srm_pr_line where pr_id = ?", Long.class, pr);
        Throwable cross = catchThrowable(() -> accounts.exec(compB, """
                insert into aps_plan_pr (comp_cd, plan_id, plan_mat_id, wo_id, op_seq, item_id, short_qty, pr_id, pr_line_id, created_by)
                values (?, ?, ?, ?, 10, ?, 4, ?, ?, 't')""", compB, plan, mat(wo5, rm), wo5, rm, pr, rmLine));
        assertThat(PgErrors.constraint(cross)).isPresent();
        assertThat(count("aps_plan_pr")).isEqualTo(1);
    }
}
