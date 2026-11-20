package io.smarthappy.module.production;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

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

import io.smarthappy.platform.common.db.PgErrors;
import io.smarthappy.support.PostgresTestBase;
import io.smarthappy.support.TestAccounts;

/**
 * 작업지시 (P2 3단계, docs/25 4.4) — 채번·라우팅/BOM 자동 선택, 발행 스냅샷(공정·파라미터·자재 소요: 기준 수량·스크랩·환산·
 * 가상 조립 펼침·대체 비율), 발행 뒤 기준정보 변경과 무관, 발행 때 재확인(폐기·환산 끊김·가상 조립 BOM 없음), 상태 전이(서비스·트리거),
 * 스냅샷은 발행 트랜잭션에서만, 동시 발행, 설비 배정, A사로 B사, 권한.
 */
@AutoConfigureMockMvc
class WorkOrderTest extends PostgresTestBase {

    private static final String PW = "Maple-anchor-63";
    private static final String W = "/api/production/wos";

    @Autowired
    MockMvc mvc;

    @Autowired
    TestAccounts accounts;

    String comp;
    long userId;
    RequestPostProcessor admin;
    long routingId;
    long bomId;
    long phBomId;
    String today;

    @BeforeEach
    void setUp() {
        comp = accounts.newComp("WO");
        userId = accounts.newUser(comp, "admin1", PW);
        accounts.grant(comp, userId, "PRD", "prd-wo", "SEARCH", "SAVE");
        admin = accounts.as(comp, userId, "admin1");
        accounts.addPlant(comp, "P1", "1공장");
        accounts.addPlant(comp, "P2", "2공장");
        today = accounts.query(comp, "select " + TestAccounts.COMP_TODAY + "::text", String.class);
        for (String[] i : new String[][] {{"FG", "EA"}, {"SUB", "EA"}, {"RM", "KG"}, {"PH", "EA"}, {"RM2", "KG"}, {"SCRAP", "KG"},
            {"ALT-A", "EA"}, {"ALT-B", "EA"}, {"NOBOM", "EA"}}) {
            accounts.exec(comp, """
                    insert into mst_item (comp_cd, item_cd, item_nm, item_type, proc_type, unit_cd, created_by, updated_by)
                    values (?, ?, ?, 'FG', 'MAKE', ?, 't', 't')""", comp, i[0], i[0] + " 품목", i[1]);
        }
        accounts.exec(comp, """
                insert into mst_unit_conv (comp_cd, item_id, from_unit, to_unit, factor, created_by, updated_by)
                values (?, (select item_id from mst_item where item_cd = 'RM'), 'ROLL', 'KG', 8500, 't', 't')""", comp);
        accounts.exec(comp, """
                insert into mst_proc (comp_cd, proc_cd, proc_nm, proc_cat, created_by, updated_by)
                values (?, 'SPOT', '스폿 용접', 'WELD', 't', 't'), (?, 'INSP', '검사', 'INSPECT', 't', 't')""", comp, comp);
        routingId = routing("FG", "P1");
        // FG BOM: 기준 수량 2 — SUB 4(→ 지시 1개당 2), RM 3 KG 스크랩 10%, RM 1 ROLL 고정, PH 2(가상), SCRAP 부산물 0.5 KG,
        //         ALT-A 2 (대체 60%) / ALT-B 2 (대체 40%)
        bomId = bom("FG", "2", new String[][] {{"SUB", "4", "EA", "NORMAL", "0", "N", null, null, null},
            {"RM", "3", "KG", "NORMAL", "10", "N", null, null, null}, {"RM", "1", "ROLL", "NORMAL", "0", "Y", null, null, null},
            {"PH", "2", "EA", "PHANTOM", "0", "N", null, null, null}, {"SCRAP", "0.5", "KG", "BYPRODUCT", "0", "N", null, null, null},
            {"ALT-A", "2", "EA", "NORMAL", "0", "N", "G1", "1", "60"}, {"ALT-B", "2", "EA", "NORMAL", "0", "N", "G1", "2", "40"}});
        phBomId = bom("PH", "1", new String[][] {{"RM2", "0.25", "KG", "NORMAL", "0", "N", null, null, null}});
    }

    // ---------- 도우미 ----------

    private long routing(String item, String plant) {
        long rid = accounts.query(comp, """
                insert into mst_routing (comp_cd, item_id, plant_cd, rev, valid_from, created_by, updated_by)
                values (?, (select item_id from mst_item where item_cd = ?), ?, 'A0', (now() at time zone 'Asia/Seoul')::date - 30, 't', 't')
                returning routing_id""", Long.class, comp, item, plant);
        long s1 = accounts.query(comp, """
                insert into mst_routing_step (comp_cd, routing_id, op_seq, proc_id, std_ct_sec, units_per_cycle, setup_min,
                                              created_by, updated_by)
                values (?, ?, 10, (select proc_id from mst_proc where proc_cd = 'SPOT'), 45.5, 2, 10, 't', 't') returning step_id""",
                Long.class, comp, rid);
        accounts.exec(comp, """
                insert into mst_routing_step (comp_cd, routing_id, op_seq, proc_id, std_ct_sec, created_by, updated_by)
                values (?, ?, 20, (select proc_id from mst_proc where proc_cd = 'INSP'), 20, 't', 't')""", comp, rid);
        accounts.exec(comp, """
                insert into mst_routing_param (comp_cd, step_id, param_cd, param_nm, unit_nm, target_val, lsl, usl, sort_no,
                                               created_by, updated_by)
                values (?, ?, 'WELD_CURRENT', '용접 전류', 'kA', 9.0, 8.5, 9.5, 10, 't', 't'),
                       (?, ?, 'WELD_TIME', '통전 시간', 'ms', 300, 280, 320, 20, 't', 't')""", comp, s1, comp, s1);
        accounts.exec(comp, "update mst_routing set status = 'RELEASED', released_at = now(), released_by = 't' where routing_id = ?", rid);
        return rid;
    }

    /** 행: 품목, 수량, 단위, 유형, 스크랩%, 고정, 대체 그룹, 우선순위, 비율 */
    private long bom(String item, String baseQty, String[][] lines) {
        long id = accounts.query(comp, """
                insert into mst_bom (comp_cd, item_id, rev, valid_from, base_qty, created_by, updated_by)
                values (?, (select item_id from mst_item where item_cd = ?), 'A0', (now() at time zone 'Asia/Seoul')::date - 30, ?::numeric, 't', 't')
                returning bom_id""", Long.class, comp, item, baseQty);
        int no = 10;
        for (String[] l : lines) {
            accounts.exec(comp, """
                    insert into mst_bom_line (comp_cd, bom_id, line_no, item_id, qty, unit_cd, line_type, scrap_pct, fixed_yn,
                                              alt_grp, alt_priority, alt_pct, issue_method, created_by, updated_by)
                    values (?, ?, ?, (select item_id from mst_item where item_cd = ?), ?::numeric, ?, ?, ?::numeric, ?, ?,
                            ?::smallint, ?::numeric, ?, 't', 't')""",
                    comp, id, no, l[0], l[1], l[2], l[3], l[4], l[5], l[6], l[7], l[8], "NORMAL".equals(l[3]) ? "BACKFLUSH" : null);
            no += 10;
        }
        accounts.exec(comp, "update mst_bom set status = 'RELEASED', released_at = now(), released_by = 't' where bom_id = ?", id);
        return id;
    }

    private MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder b, String body) {
        return b.with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private ResultActions postJson(String url, String body) throws Exception {
        return mvc.perform(json(post(url), body).with(admin));
    }

    private ResultActions putJson(String url, String body) throws Exception {
        return mvc.perform(json(put(url), body).with(admin));
    }

    private static long id(ResultActions r) throws Exception {
        MvcResult m = r.andExpect(status().isOk()).andReturn();
        return ((Number) JsonPath.read(m.getResponse().getContentAsString(), "$.data")).longValue();
    }

    private long wo(String item, String qty) throws Exception {
        return id(postJson(W, "{\"plantCd\":\"P1\",\"itemCd\":\"" + item + "\",\"planQty\":\"" + qty + "\",\"planStart\":\"" + today + "\"}"));
    }

    private int ver(long woId) {
        return accounts.query(comp, "select row_ver from prd_wo where wo_id = ?", Integer.class, woId);
    }

    private ResultActions release(long woId) throws Exception {
        return postJson(W + "/" + woId + "/release", "{\"rowVer\":" + ver(woId) + "}");
    }

    private BigDecimal req(long woId, String item) {
        return accounts.query(comp, """
                select sum(b.req_qty) from prd_wo_bom b join mst_item i on i.item_id = b.item_id
                 where b.wo_id = ? and i.item_cd = ?""", BigDecimal.class, woId, item);
    }

    // ---------- 정상 ----------

    @Test
    void 만들면_채번_라우팅_BOM_자동_발행하면_공정_파라미터_자재_소요_스냅샷() throws Exception {
        long w = wo("FG", "10");
        mvc.perform(get(W + "/" + w).with(admin))
                .andExpect(jsonPath("$.data.wo.woNo").value(Matchers.matchesPattern("^WO\\d{4}-0001$")))
                .andExpect(jsonPath("$.data.wo.status").value("PLANNED"))
                .andExpect(jsonPath("$.data.wo.routingId").value((int) routingId))
                .andExpect(jsonPath("$.data.wo.bomId").value((int) bomId))
                .andExpect(jsonPath("$.data.wo.unitCd").value("EA"))
                .andExpect(jsonPath("$.data.procs.length()").value(0));
        release(w).andExpect(status().isOk());
        mvc.perform(get(W + "/" + w).with(admin))
                .andExpect(jsonPath("$.data.wo.status").value("RELEASED"))
                .andExpect(jsonPath("$.data.wo.releasedBy").value("admin1"))
                .andExpect(jsonPath("$.data.procs[*].procCd").value(Matchers.contains("SPOT", "INSP")))
                .andExpect(jsonPath("$.data.procs[0].stdCtSec").value("45.5"))
                .andExpect(jsonPath("$.data.procs[0].unitsPerCycle").value(2))
                .andExpect(jsonPath("$.data.procs[0].params[*].paramCd").value(Matchers.contains("WELD_CURRENT", "WELD_TIME")))
                .andExpect(jsonPath("$.data.procs[0].params[0].targetVal").value("9"))
                .andExpect(jsonPath("$.data.procs[1].params.length()").value(0))
                .andExpect(jsonPath("$.data.boms.length()").value(7));
        // 지시 10개, 기준 수량 2: SUB 4/2×10 = 20 · RM 3/2×10×1.1 = 16.5 KG + 고정 1 ROLL × 8500 = 8500 KG
        // · PH 2/2×10 = 10 → RM2 0.25×10 = 2.5 KG(가상 조립 펼침) · 부산물 2.5 KG · 대체 A 60% 6 · B 40% 4
        assertThat(req(w, "SUB")).isEqualByComparingTo("20");
        assertThat(req(w, "RM")).isEqualByComparingTo("8516.5");
        assertThat(req(w, "RM2")).isEqualByComparingTo("2.5");
        assertThat(req(w, "PH")).isNull();
        assertThat(req(w, "SCRAP")).isEqualByComparingTo("2.5");
        assertThat(req(w, "ALT-A")).isEqualByComparingTo("6");
        assertThat(req(w, "ALT-B")).isEqualByComparingTo("4");
        assertThat(accounts.query(comp, "select phantom_path from prd_wo_bom where wo_id = ? and item_id = "
                + "(select item_id from mst_item where item_cd = 'RM2')", String.class, w)).isEqualTo("PH");
        assertThat(accounts.query(comp, "select string_agg(src_unit_cd || ':' || unit_cd, ',' order by seq) from prd_wo_bom "
                + "where wo_id = ? and item_id = (select item_id from mst_item where item_cd = 'RM')", String.class, w))
                .isEqualTo("KG:KG,ROLL:KG");
        // 발행 뒤 기준정보가 바뀌어도(폐기) 지시 스냅샷은 그대로
        accounts.exec(comp, "update mst_bom set status = 'OBSOLETE' where bom_id = ?", bomId);
        accounts.exec(comp, "update mst_routing set status = 'OBSOLETE' where routing_id = ?", routingId);
        mvc.perform(get(W + "/" + w).with(admin))
                .andExpect(jsonPath("$.data.boms.length()").value(7))
                .andExpect(jsonPath("$.data.procs.length()").value(2));
    }

    @Test
    void 소수_소요량은_6자리_올림() throws Exception {
        long w = wo("FG", "3");
        release(w).andExpect(status().isOk());
        // SUB 4/2×3 = 6, RM 3/2×3×1.1 = 4.95(+8500 고정), ALT-A 2/2×3×0.6 = 1.8
        assertThat(req(w, "RM")).isEqualByComparingTo("8504.95");
        assertThat(req(w, "ALT-A")).isEqualByComparingTo("1.8");
        long b = bom("SUB", "3", new String[][] {{"RM2", "1", "KG", "NORMAL", "0", "N", null, null, null}});
        accounts.exec(comp, "insert into mst_routing (comp_cd, item_id, plant_cd, rev, valid_from, created_by, updated_by) "
                + "select ?, item_id, 'P1', 'A0', " + TestAccounts.COMP_TODAY + " - 30, 't', 't' from mst_item where item_cd = 'SUB'", comp);
        accounts.exec(comp, """
                insert into mst_routing_step (comp_cd, routing_id, op_seq, proc_id, created_by, updated_by)
                select ?, routing_id, 10, (select proc_id from mst_proc where proc_cd = 'SPOT'), 't', 't'
                  from mst_routing where item_id = (select item_id from mst_item where item_cd = 'SUB')""", comp);
        accounts.exec(comp, "update mst_routing set status = 'RELEASED', released_at = now(), released_by = 't' "
                + "where item_id = (select item_id from mst_item where item_cd = 'SUB')");
        long w2 = wo("SUB", "1");
        release(w2).andExpect(status().isOk());
        assertThat(req(w2, "RM2")).isEqualByComparingTo("0.333334");       // 1/3 → 올림
        assertThat(b).isPositive();
    }

    // ---------- 검증 (B) ----------

    @Test
    void 입력_검증() throws Exception {
        accounts.exec(comp, "update sys_plant set use_yn = 'N' where plant_cd = 'P2'");
        postJson(W, "{\"plantCd\":\"P1\",\"itemCd\":\"FG\",\"planQty\":\"1.5\",\"planStart\":\"" + today + "\","
                + "\"planEnd\":\"2001-01-01\",\"priority\":0}")
                .andExpect(jsonPath("$.errors[*].code").value(Matchers.contains("SCALE", "INVALID_RANGE", "RANGE")));
        postJson(W, "{\"plantCd\":\"P2\",\"itemCd\":\"FG\",\"planQty\":\"1\",\"planStart\":\"" + today + "\"}")
                .andExpect(jsonPath("$.errors[0].code").value("INACTIVE"));
        postJson(W, "{\"plantCd\":\"P1\",\"itemCd\":\"NOBOM\",\"planQty\":\"1\",\"planStart\":\"" + today + "\"}")
                .andExpect(jsonPath("$.errors[0].field").value("routingId"))
                .andExpect(jsonPath("$.errors[0].code").value("NO_ROUTING"));
        // 다른 품목의 리비전·과거(유효 전) 기준일·남의 수주 행
        postJson(W, "{\"plantCd\":\"P1\",\"itemCd\":\"FG\",\"planQty\":\"1\",\"planStart\":\"" + today + "\",\"bomId\":" + phBomId + "}")
                .andExpect(jsonPath("$.errors[0].code").value("STALE_REV"));
        postJson(W, "{\"plantCd\":\"P1\",\"itemCd\":\"FG\",\"planQty\":\"1\",\"planStart\":\"2001-01-01\"}")
                .andExpect(jsonPath("$.errors[0].code").value("NO_ROUTING"));
        postJson(W, "{\"plantCd\":\"P1\",\"itemCd\":\"FG\",\"planQty\":\"1\",\"planStart\":\"" + today + "\",\"orderLineId\":999999}")
                .andExpect(jsonPath("$.errors[0].code").value("ORDER_LINE"));
        postJson(W, "{\"plantCd\":\"P1\",\"itemCd\":\"FG\",\"planQty\":\"1e2\",\"planStart\":\"" + today + "\"}")
                .andExpect(jsonPath("$.errors[0].code").value("FORMAT"));
    }

    @Test
    void 수주_행을_연결하면_같은_품목의_진행_중_행만() throws Exception {
        accounts.exec(comp, "insert into mst_cust (comp_cd, cust_cd, cust_nm, cust_type, created_by, updated_by) "
                + "values (?, 'C1', '고객', 'SALES', 't', 't')", comp);
        long o = accounts.query(comp, """
                insert into sal_order (comp_cd, order_no, cust_id, order_date, created_by, updated_by)
                values (?, 'SO-T1', (select cust_id from mst_cust where cust_cd = 'C1'), (now() at time zone 'Asia/Seoul')::date, 't', 't') returning order_id""",
                Long.class, comp);
        long lFg = accounts.query(comp, """
                insert into sal_order_line (comp_cd, order_id, line_no, item_id, qty, due_date, created_by, updated_by)
                values (?, ?, 10, (select item_id from mst_item where item_cd = 'FG'), 100, (now() at time zone 'Asia/Seoul')::date + 10, 't', 't')
                returning line_id""", Long.class, comp, o);
        long lSub = accounts.query(comp, """
                insert into sal_order_line (comp_cd, order_id, line_no, item_id, qty, due_date, created_by, updated_by)
                values (?, ?, 20, (select item_id from mst_item where item_cd = 'SUB'), 100, (now() at time zone 'Asia/Seoul')::date + 10, 't', 't')
                returning line_id""", Long.class, comp, o);
        mvc.perform(get(W + "/options?itemCd=fg&plantCd=P1&date=" + today).with(admin))
                .andExpect(jsonPath("$.data.unitCd").value("EA"))
                .andExpect(jsonPath("$.data.routings[0].id").value((int) routingId))
                .andExpect(jsonPath("$.data.orderLines[*].lineId").value(Matchers.contains((int) lFg)));
        postJson(W, "{\"plantCd\":\"P1\",\"itemCd\":\"FG\",\"planQty\":\"1\",\"planStart\":\"" + today + "\",\"orderLineId\":" + lSub + "}")
                .andExpect(jsonPath("$.errors[0].code").value("ORDER_LINE"));
        long w = id(postJson(W, "{\"plantCd\":\"P1\",\"itemCd\":\"FG\",\"planQty\":\"40\",\"planStart\":\"" + today
                + "\",\"orderLineId\":" + lFg + "}"));
        mvc.perform(get(W + "/" + w).with(admin)).andExpect(jsonPath("$.data.wo.orderNo").value("SO-T1"))
                .andExpect(jsonPath("$.data.wo.orderLineNo").value(10));
        // 연결한 수주 행이 발행 전에 끝나면 발행을 막는다
        accounts.exec(comp, "update sal_order_line set status = 'DONE' where line_id = ?", lFg);
        release(w).andExpect(jsonPath("$.errors[0].code").value("ORDER_LINE"));
    }

    // ---------- 발행 때 재확인 (F) ----------

    @Test
    void 발행_때_기준정보를_다시_본다_폐기_환산_끊김_가상조립_BOM_없음() throws Exception {
        long w1 = wo("FG", "10");
        long w2 = wo("FG", "10");
        long w3 = wo("FG", "10");
        accounts.exec(comp, "update mst_unit_conv set use_yn = 'N' where from_unit = 'ROLL'");
        release(w1).andExpect(jsonPath("$.errors[0].code").value("WO_NO_CONV"))
                .andExpect(jsonPath("$.errors[0].message").value(Matchers.containsString("RM")));
        accounts.exec(comp, "update mst_unit_conv set use_yn = 'Y' where from_unit = 'ROLL'");
        accounts.exec(comp, "update mst_bom set status = 'OBSOLETE' where bom_id = ?", phBomId);
        release(w2).andExpect(jsonPath("$.errors[0].code").value("PHANTOM_NO_BOM"));
        accounts.exec(comp, "update mst_routing set status = 'OBSOLETE' where routing_id = ?", routingId);
        release(w3).andExpect(jsonPath("$.errors[0].code").value("STALE_REV"));
        // 실패한 발행은 아무것도 남기지 않는다
        assertThat(accounts.query(comp, "select count(*) from prd_wo where status = 'PLANNED'", Integer.class)).isEqualTo(3);
        assertThat(accounts.query(comp, "select count(*) from prd_wo_proc", Integer.class)).isZero();
        assertThat(accounts.query(comp, "select count(*) from prd_wo_bom", Integer.class)).isZero();
    }

    // ---------- 상태 전이 · 고정 (F) ----------

    @Test
    void 발행_뒤에는_수량_기준정보_고정_일정만_취소는_사유_끝난_지시는_못_바꾼다() throws Exception {
        long w = wo("FG", "10");
        putJson(W + "/" + w, "{\"planQty\":\"12\",\"planStart\":\"" + today + "\",\"priority\":2,\"rowVer\":0}").andExpect(status().isOk());
        release(w).andExpect(status().isOk());
        release(w).andExpect(jsonPath("$.errors[0].code").value("STATE"));
        putJson(W + "/" + w, "{\"planQty\":\"15\",\"planStart\":\"" + today + "\",\"rowVer\":" + ver(w) + "}")
                .andExpect(jsonPath("$.errors[0].code").value("FIXED"));
        putJson(W + "/" + w, "{\"itemCd\":\"SUB\",\"planStart\":\"" + today + "\",\"rowVer\":" + ver(w) + "}")
                .andExpect(jsonPath("$.errors[0].code").value("FIXED"));
        putJson(W + "/" + w, "{\"planStart\":\"" + today + "\",\"planEnd\":\"2099-12-31\",\"priority\":1,\"remark\":\"앞당김\",\"rowVer\":"
                + ver(w) + "}").andExpect(status().isOk());
        assertThat(accounts.query(comp, "select plan_qty from prd_wo where wo_id = ?", BigDecimal.class, w)).isEqualByComparingTo("12");
        postJson(W + "/" + w + "/cancel", "{\"reason\":\"\",\"rowVer\":" + ver(w) + "}")
                .andExpect(jsonPath("$.errors[0].code").value("REQUIRED"));
        postJson(W + "/" + w + "/cancel", "{\"reason\":\"고객 취소\",\"rowVer\":" + ver(w) + "}").andExpect(status().isOk());
        postJson(W + "/" + w + "/cancel", "{\"reason\":\"또\",\"rowVer\":" + ver(w) + "}")
                .andExpect(jsonPath("$.errors[0].code").value("STATE"));
        putJson(W + "/" + w, "{\"planStart\":\"" + today + "\",\"rowVer\":" + ver(w) + "}")
                .andExpect(jsonPath("$.errors[0].code").value("STATE"));
    }

    @Test
    void 직접_SQL_도_전이_고정_스냅샷_규칙을_지킨다() throws Exception {
        long w = wo("FG", "10");
        long planned = wo("FG", "5");
        // 계획 상태 지시에 스냅샷 넣기·발행 트랜잭션이 아닌 때 넣기
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(comp, """
                insert into prd_wo_bom (comp_cd, wo_id, seq, item_id, line_type, req_qty, unit_cd, src_bom_id, src_line_id, src_qty,
                                        src_unit_cd, scrap_pct)
                values (?, ?, 99, (select item_id from mst_item where item_cd = 'SUB'), 'NORMAL', 1, 'EA', ?, 1, 1, 'EA', 0)""",
                comp, planned, bomId)))).contains("ck_prd_wo_snapshot_release");
        release(w).andExpect(status().isOk());
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(comp, """
                insert into prd_wo_bom (comp_cd, wo_id, seq, item_id, line_type, req_qty, unit_cd, src_bom_id, src_line_id, src_qty,
                                        src_unit_cd, scrap_pct)
                values (?, ?, 99, (select item_id from mst_item where item_cd = 'SUB'), 'NORMAL', 1, 'EA', ?, 1, 1, 'EA', 0)""",
                comp, w, bomId)))).contains("ck_prd_wo_snapshot_release");
        for (String sql : new String[] {"update prd_wo_bom set req_qty = 1 where wo_id = " + w,
            "delete from prd_wo_bom where wo_id = " + w,
            "update prd_wo_param set target_val = 1",
            "delete from prd_wo_proc where wo_id = " + w,
            "delete from prd_wo where wo_id = " + w}) {
            assertThat(PgErrors.sqlState(catchThrowable(() -> accounts.exec(comp, sql)))).as(sql).contains("42501");
        }
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(comp,
                "update prd_wo_proc set std_ct_sec = 1 where wo_id = ?", w)))).contains("ck_prd_wo_proc_fixed");
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(comp,
                "update prd_wo set status = 'PLANNED', released_at = null, released_by = null where wo_id = ?", w))))
                .contains("ck_prd_wo_transition");
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(comp,
                "update prd_wo set plan_qty = 99 where wo_id = ?", w)))).contains("ck_prd_wo_released_fixed");
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(comp,
                "update prd_wo set status = 'DONE' where wo_id = ?", w)))).contains("ck_prd_wo_transition");
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(comp,
                "update prd_wo set item_id = (select item_id from mst_item where item_cd = 'SUB') where wo_id = ?", planned))))
                .contains("ck_prd_wo_fixed");
        // 계획 상태에서 다른 품목의 라우팅으로 바꾸기
        long otherRouting = accounts.query(comp, """
                insert into mst_routing (comp_cd, item_id, plant_cd, rev, valid_from, created_by, updated_by)
                select ?, item_id, 'P1', 'A0', (now() at time zone 'Asia/Seoul')::date, 't', 't' from mst_item where item_cd = 'SUB' returning routing_id""",
                Long.class, comp);
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(comp,
                "update prd_wo set routing_id = ? where wo_id = ?", otherRouting, planned)))).contains("fk_prd_wo_routing_item");
    }

    // ---------- 동시성 (C) ----------

    @Test
    void 같은_지시를_동시에_발행해도_스냅샷은_한_번() throws Exception {
        for (int round = 0; round < 3; round++) {
            long w = wo("FG", "10");
            int v = ver(w);
            ExecutorService pool = Executors.newFixedThreadPool(2);
            try {
                Callable<Integer> c = () -> postJson(W + "/" + w + "/release", "{\"rowVer\":" + v + "}").andReturn().getResponse().getStatus();
                List<Future<Integer>> fs = pool.invokeAll(List.of(c, c));
                assertThat(List.of(fs.get(0).get(), fs.get(1).get())).contains(200).doesNotContain(500);
            } finally {
                pool.shutdown();
            }
            assertThat(accounts.query(comp, "select count(*) from prd_wo_proc where wo_id = ?", Integer.class, w)).isEqualTo(2);
            assertThat(accounts.query(comp, "select count(*) from prd_wo_bom where wo_id = ?", Integer.class, w)).isEqualTo(7);
        }
    }

    // ---------- 설비 배정 (D1) ----------

    @Test
    void 공정에_같은_공장의_사용_중_설비만_배정() throws Exception {
        accounts.exec(comp, "insert into mst_equip_class (comp_cd, class_cd, class_nm, created_by, updated_by) values (?, 'WELD', '용접', 't', 't')", comp);
        for (String[] e : new String[][] {{"P1", "SPOT-1", "Y"}, {"P2", "SPOT-9", "Y"}, {"P1", "SPOT-OFF", "N"}}) {
            accounts.exec(comp, """
                    insert into mst_equip (comp_cd, plant_cd, equip_cd, equip_nm, class_id, use_yn, created_by, updated_by)
                    values (?, ?, ?, ?, (select class_id from mst_equip_class where class_cd = 'WELD'), ?, 't', 't')""",
                    comp, e[0], e[1], e[1], e[2]);
        }
        long w = wo("FG", "1");
        long wp0 = -1;
        release(w).andExpect(status().isOk());
        long wp = accounts.query(comp, "select wo_proc_id from prd_wo_proc where wo_id = ? and op_seq = 10", Long.class, w);
        putJson(W + "/procs/" + wp + "/equip", "{\"equipCd\":\"spot-1\",\"rowVer\":0}").andExpect(status().isOk());
        putJson(W + "/procs/" + wp + "/equip", "{\"equipCd\":\"SPOT-9\",\"rowVer\":1}").andExpect(jsonPath("$.errors[0].code").value("NO_SUCH_CODE"));
        putJson(W + "/procs/" + wp + "/equip", "{\"equipCd\":\"SPOT-OFF\",\"rowVer\":1}").andExpect(jsonPath("$.errors[0].code").value("NO_SUCH_CODE"));
        mvc.perform(get(W + "/" + w).with(admin)).andExpect(jsonPath("$.data.procs[0].equipCd").value("SPOT-1"));
        putJson(W + "/procs/" + wp + "/equip", "{\"equipCd\":\"\",\"rowVer\":1}").andExpect(status().isOk());
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(comp,
                "update prd_wo_proc set equip_id = (select equip_id from mst_equip where equip_cd = 'SPOT-9') where wo_proc_id = ?", wp))))
                .contains("fk_prd_wo_proc_equip_plant");
        mvc.perform(get(W + "/equips?plantCd=P1").with(admin)).andExpect(jsonPath("$.data[*].equipCd").value(Matchers.contains("SPOT-1")));
        assertThat(wp0).isNegative();
    }

    // ---------- 조회 ----------

    @Test
    void 목록은_열린_기간_상태_품목_번호로() throws Exception {
        long a = wo("FG", "1");
        wo("FG", "2");
        release(a).andExpect(status().isOk());
        // 기간을 비우면 그쪽 끝이 없다(홈 링크 — docs/25 4.13). 둘 다 비우면 최근 순 전체(상한 500)
        mvc.perform(get(W).with(admin)).andExpect(status().isOk()).andExpect(jsonPath("$.data.rows.length()").value(2));
        mvc.perform(get(W).param("from", today).param("to", today).param("status", "RELEASED").with(admin))
                .andExpect(jsonPath("$.data.rows.length()").value(1))
                .andExpect(jsonPath("$.data.rows[0].procCnt").value(2));
        mvc.perform(get(W).param("from", today).param("to", today).param("itemCd", "fg").param("keyword", "_").with(admin))
                .andExpect(jsonPath("$.data.rows.length()").value(0));
        mvc.perform(get(W).param("from", today).param("to", today).param("plantCd", "P1").param("keyword", "-0002").with(admin))
                .andExpect(jsonPath("$.data.rows.length()").value(1));
    }

    // ---------- A사로 B사 · 권한 ----------

    @Test
    void B사는_A사_지시를_보지도_발행_취소_배정하지도_못한다() throws Exception {
        long w = wo("FG", "10");
        String other = accounts.newComp("WB");
        long ou = accounts.newUser(other, "b1", PW);
        accounts.grant(other, ou, "PRD", "prd-wo", "SEARCH", "SAVE");
        accounts.addPlant(other, "P1", "B 1공장");
        RequestPostProcessor b = accounts.as(other, ou, "b1");
        mvc.perform(get(W + "/" + w).with(b)).andExpect(status().isNotFound());
        mvc.perform(json(post(W + "/" + w + "/release"), "{\"rowVer\":0}").with(b)).andExpect(status().isNotFound());
        mvc.perform(json(post(W + "/" + w + "/cancel"), "{\"reason\":\"x\",\"rowVer\":0}").with(b)).andExpect(status().isNotFound());
        mvc.perform(json(put(W + "/" + w), "{\"planStart\":\"" + today + "\",\"rowVer\":0}").with(b)).andExpect(status().isNotFound());
        mvc.perform(json(post(W), "{\"plantCd\":\"P1\",\"itemCd\":\"FG\",\"planQty\":\"1\",\"planStart\":\"" + today + "\"}").with(b))
                .andExpect(jsonPath("$.errors[0].code").value("NO_SUCH_CODE"));
        release(w).andExpect(status().isOk());
        long wp = accounts.query(comp, "select wo_proc_id from prd_wo_proc where wo_id = ? and op_seq = 10", Long.class, w);
        mvc.perform(json(put(W + "/procs/" + wp + "/equip"), "{\"equipCd\":\"\",\"rowVer\":0}").with(b)).andExpect(status().isNotFound());
        // 직접 SQL(B사 범위): A사 품목·라우팅으로 지시
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(other, """
                insert into prd_wo (comp_cd, wo_no, plant_cd, item_id, plan_qty, unit_cd, plan_start, plan_end, routing_id,
                                    created_by, updated_by)
                values (?, 'X', 'P1', (select item_id from prd_wo where wo_id = ?), 1, 'EA', (now() at time zone 'Asia/Seoul')::date, (now() at time zone 'Asia/Seoul')::date, ?, 't', 't')""",
                other, w, routingId)))).isPresent();
        assertThat(accounts.query(other, "select count(*) from prd_wo", Integer.class)).isZero();
    }

    @Test
    void 조회_권한만_있으면_만들기_발행은_403() throws Exception {
        long viewer = accounts.newUser(comp, "viewer", PW);
        accounts.grant(comp, viewer, "VIEW", "prd-wo", "SEARCH");
        RequestPostProcessor v = accounts.as(comp, viewer, "viewer");
        long w = wo("FG", "1");
        mvc.perform(get(W + "/" + w).with(v)).andExpect(status().isOk());
        mvc.perform(json(post(W + "/" + w + "/release"), "{\"rowVer\":0}").with(v)).andExpect(status().isForbidden());
        mvc.perform(json(post(W), "{\"plantCd\":\"P1\",\"itemCd\":\"FG\",\"planQty\":\"1\",\"planStart\":\"" + today + "\"}").with(v))
                .andExpect(status().isForbidden());
    }
}
