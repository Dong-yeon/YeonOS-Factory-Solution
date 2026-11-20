package io.smarthappy.module.production;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.YearMonth;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.jayway.jsonpath.JsonPath;

import io.smarthappy.module.master.workforce.WorkerService;
import io.smarthappy.module.pop.PopAuthService;
import io.smarthappy.module.production.link.DefectQtyQuery;
import io.smarthappy.module.production.link.DefectQtyQuery.DefectQty;
import io.smarthappy.platform.tenant.TenantRunner;
import io.smarthappy.support.PostgresTestBase;
import io.smarthappy.support.TestAccounts;
import jakarta.servlet.http.Cookie;

/**
 * 월별 불량 수량 계약 DefectQtyQuery (docs/25 4.48 — QMS COPQ 의 MES 몫). 지금 달 실적·정정·재작업 처리는 실제 POP·사무 API 로,
 * 지난 달 경계 행은 진행 중 공정에 직접 SQL 로 넣는다(발생 시각을 정하려고 — 트리거 검사는 그대로 받는다).
 * 이상 사용: 정정한 실적(같은 달은 상쇄, 달을 넘기면 원래 달 그대로·정정한 달에 음수 — 사용자 결정 C-1 (라)), 월 경계 23:59/00:00(회사 시간대 — 시간대를 바꾸면 달이 옮겨 감), 공장 거르기,
 * A사로 B사(RLS), NONE, 잘못된 기간.
 */
@AutoConfigureMockMvc
class DefectQtyQueryTest extends PostgresTestBase {

    private static final String PW = "Cedar-lantern-41";
    private static final String WO = "/api/production/wos";

    @Autowired
    MockMvc mvc;

    @Autowired
    TestAccounts accounts;

    @Autowired
    TenantRunner runner;

    @Autowired
    ObjectProvider<DefectQtyQuery> queries;

    String comp;
    RequestPostProcessor admin;
    long item;
    long worker;
    long defect;
    long wo1;
    long proc1;
    long wo2;
    long proc2;
    YearMonth now;

    @BeforeEach
    void setUp() throws Exception {
        comp = accounts.newComp("DQ");
        accounts.exec(comp, "update sys_comp set time_zone = 'Asia/Seoul' where comp_cd = ?", comp);
        admin = admin(comp);
        master(comp);
        item = accounts.query(comp, "select item_id from mst_item where item_cd = 'FG'", Long.class);
        worker = accounts.query(comp, "select worker_id from mst_worker where worker_cd = 'E1'", Long.class);
        defect = accounts.query(comp, "select defect_id from qms_defect where defect_cd = 'D-PIT'", Long.class);
        wo1 = wo(comp, admin, "P1");
        proc1 = proc(comp, wo1);
        wo2 = wo(comp, admin, "P2");
        proc2 = proc(comp, wo2);
        now = YearMonth.parse(accounts.query(comp, "select to_char(now() at time zone 'Asia/Seoul', 'YYYY-MM')", String.class));
    }

    // ---------- 도우미 ----------

    private RequestPostProcessor admin(String c) {
        long userId = accounts.newUser(c, "admin1", PW);
        accounts.grant(c, userId, "ADM", "sys-terminal", "SEARCH", "SAVE");
        accounts.grant(c, userId, "ADM", "prd-wo", "SEARCH", "SAVE");
        return accounts.as(c, userId, "admin1");
    }

    /** 공장 P1·P2, 작업장 노드, 작업자(배지 BADGE-0001), 품목 FG, 공정 SPOT(한 공정 라우팅 — 공장마다), 불량 D-PIT */
    private void master(String c) {
        accounts.addPlant(c, "P1", "1공장");
        accounts.addPlant(c, "P2", "2공장");
        accounts.exec(c, """
                insert into mst_worker (comp_cd, worker_cd, worker_nm, plant_cd, badge_hash, created_by, updated_by)
                values (?, 'E1', '홍작업', 'P1', ?, 't', 't')""", c, WorkerService.badgeHash(c, "BADGE-0001"));
        accounts.exec(c, """
                insert into mst_item (comp_cd, item_cd, item_nm, item_type, proc_type, unit_cd, created_by, updated_by)
                values (?, 'FG', 'FG 품목', 'FG', 'MAKE', 'EA', 't', 't')""", c);
        accounts.exec(c, """
                insert into mst_proc (comp_cd, proc_cd, proc_nm, proc_cat, created_by, updated_by)
                values (?, 'SPOT', '스폿 용접', 'WELD', 't', 't')""", c);
        accounts.exec(c, """
                insert into qms_defect (comp_cd, defect_cd, defect_nm, cat_cd, created_by, updated_by)
                values (?, 'D-PIT', '기공', 'WELD', 't', 't')""", c);
        for (String plant : List.of("P1", "P2")) {
            long area = node(c, plant, null, "AREA", "A-" + plant);
            long line = node(c, plant, area, "LINE", "L-" + plant);
            long cell = node(c, plant, line, "CELL", "C-" + plant);
            long rid = accounts.query(c, """
                    insert into mst_routing (comp_cd, item_id, plant_cd, rev, valid_from, created_by, updated_by)
                    values (?, (select item_id from mst_item where item_cd = 'FG'), ?, 'A0', (now() at time zone 'Asia/Seoul')::date - 30, 't', 't')
                    returning routing_id""", Long.class, c, plant);
            accounts.exec(c, """
                    insert into mst_routing_step (comp_cd, routing_id, op_seq, proc_id, node_id, std_ct_sec, created_by, updated_by)
                    values (?, ?, 10, (select proc_id from mst_proc where proc_cd = 'SPOT'), ?, 30, 't', 't')""", c, rid, cell);
            accounts.exec(c, "update mst_routing set status = 'RELEASED', released_at = now(), released_by = 't' where routing_id = ?", rid);
        }
    }

    private long node(String c, String plant, Long parent, String type, String cd) {
        return accounts.query(c, """
                insert into mst_equip_hier (comp_cd, plant_cd, parent_id, node_type, node_cd, node_nm, created_by, updated_by)
                values (?, ?, ?, ?, ?, ?, 't', 't') returning node_id""", Long.class, c, plant, parent, type, cd, cd + " 노드");
    }

    private long wo(String c, RequestPostProcessor who, String plant) throws Exception {
        String today = accounts.query(c, "select " + TestAccounts.COMP_TODAY + "::text", String.class);
        long id = id(mvc.perform(json(post(WO), "{\"plantCd\":\"" + plant + "\",\"itemCd\":\"FG\",\"planQty\":\"100\",\"planStart\":\""
                + today + "\"}").with(who)));
        mvc.perform(json(post(WO + "/" + id + "/release"), "{\"rowVer\":0}").with(who)).andExpect(status().isOk());
        return id;
    }

    private long proc(String c, long woId) {
        return accounts.query(c, "select wo_proc_id from prd_wo_proc where wo_id = ? and op_seq = 10", Long.class, woId);
    }

    private MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder b, String body) {
        return b.with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private static long id(ResultActions r) throws Exception {
        MvcResult m = r.andExpect(status().isOk()).andReturn();
        return ((Number) JsonPath.read(m.getResponse().getContentAsString(), "$.data")).longValue();
    }

    /** 공장 단말(POP) 쿠키 + 배지 로그인 세션 */
    private record Pop(String cookie, MockHttpSession session) {
    }

    private Pop pop(String c, RequestPostProcessor who, String plant) throws Exception {
        String cd = "TAB-" + plant;
        MvcResult m = mvc.perform(json(post("/api/production/terminals"), "{\"terminalCd\":\"" + cd + "\",\"terminalNm\":\"" + cd
                + "\",\"plantCd\":\"" + plant + "\"}").with(who)).andExpect(status().isOk()).andReturn();
        String code = JsonPath.read(m.getResponse().getContentAsString(), "$.data.code");
        String ip = "10.83." + (System.nanoTime() % 200) + "." + (System.nanoTime() % 250 + 1);
        MvcResult a = mvc.perform(json(post("/api/pop/activate"), "{\"compCd\":\"" + c + "\",\"code\":\"" + code + "\"}")
                .with(r -> { r.setRemoteAddr(ip); return r; })).andExpect(status().isOk()).andReturn();
        String set = a.getResponse().getHeaders("Set-Cookie").stream().filter(h -> h.startsWith(PopAuthService.COOKIE + "="))
                .findFirst().orElseThrow();
        Pop p = new Pop(set.substring(set.indexOf('=') + 1, set.indexOf(';')), new MockHttpSession());
        call(p, post("/api/pop/badge"), "{\"badge\":\"BADGE-0001\"}").andExpect(status().isOk());
        return p;
    }

    private ResultActions call(Pop p, MockHttpServletRequestBuilder b, String body) throws Exception {
        return mvc.perform(json(b, body).cookie(new Cookie(PopAuthService.COOKIE, p.cookie())).session(p.session()));
    }

    private void start(Pop p, long proc) throws Exception {
        call(p, post("/api/pop/procs/" + proc + "/events/start"), "{\"clientReqId\":\"" + UUID.randomUUID() + "\",\"ack\":true}")
                .andExpect(status().isOk());
    }

    /** 실제 POP 실적(지금) — 폐기·재작업은 D-PIT 로. 반환 result_id */
    private long popResult(Pop p, long proc, int good, int scrap, int rework) throws Exception {
        String r = UUID.randomUUID().toString();
        StringBuilder d = new StringBuilder();
        if (scrap > 0) {
            d.append("{\"kind\":\"SCRAP\",\"defectCd\":\"D-PIT\",\"qty\":\"").append(scrap).append("\"}");
        }
        if (rework > 0) {
            d.append(d.length() == 0 ? "" : ",").append("{\"kind\":\"REWORK\",\"defectCd\":\"D-PIT\",\"qty\":\"").append(rework).append("\"}");
        }
        call(p, post("/api/pop/procs/" + proc + "/results"), "{\"clientReqId\":\"" + r + "\",\"goodQty\":\"" + good + "\",\"scrapQty\":\""
                + scrap + "\",\"reworkQty\":\"" + rework + "\",\"repairQty\":\"0\",\"defects\":[" + d + "]}").andExpect(status().isOk());
        return accounts.query(comp, "select result_id from prd_result where client_req_id = ?", Long.class, r);
    }

    /** 진행 중 공정에 정한 발생 시각(회사 현지 'YYYY-MM-DD HH:MI:SS', Asia/Seoul)으로 폐기·수리 실적을 직접 넣는다 */
    private void pastResult(String c, long woId, long proc, String localKst, int scrap, int repair) {
        accounts.exec(c, """
                with r as (
                    insert into prd_result (comp_cd, wo_id, wo_proc_id, good_qty, scrap_qty, rework_qty, repair_qty, unit_cd, work_day,
                                            client_req_id, occurred_at, entry_method, recorded_by, performed_by)
                    values (hb_current_comp(), ?, ?, 0, ?, 0, ?, 'EA', (?::timestamp)::date, ?, (?::timestamp) at time zone 'Asia/Seoul', 'KEY', 't',
                            (select worker_id from mst_worker where worker_cd = 'E1'))
                    returning result_id)
                insert into prd_result_defect (comp_cd, result_id, kind, defect_id, qty)
                select hb_current_comp(), r.result_id, k.kind, (select defect_id from qms_defect where defect_cd = 'D-PIT'), k.qty
                  from r cross join (values ('SCRAP', ?::numeric), ('REPAIR', ?::numeric)) k(kind, qty) where k.qty > 0""",
                woId, proc, scrap, repair, localKst, UUID.randomUUID().toString(), localKst, scrap, repair);
    }

    private List<DefectQty> monthly(String c, String plant, YearMonth from, YearMonth to) {
        return runner.call(c, () -> DefectQtyQuery.of(queries).monthly(plant, from, to));
    }

    /** 줄 → "월/품목/종류/수량/단위/원천" (품목은 이 회사 FG 면 FG) */
    private List<String> lines(List<DefectQty> rows) {
        return rows.stream().map(r -> r.month() + "/" + (r.itemId() == item ? "FG" : r.itemId()) + "/" + r.kind() + "/"
                + r.qty().stripTrailingZeros().toPlainString() + "/" + r.unitCd() + "/" + r.source()).toList();
    }

    // ---------- 시험 ----------

    @Test
    void 월_경계는_회사_시간대_23시59분과_0시로_나뉘고_시간대를_바꾸면_달이_옮겨_간다() throws Exception {
        Pop p1 = pop(comp, admin, "P1");
        start(p1, proc1);
        pastResult(comp, wo1, proc1, "2026-01-31 23:59:00", 2, 1);
        pastResult(comp, wo1, proc1, "2026-02-01 00:00:00", 3, 0);
        assertThat(lines(monthly(comp, null, YearMonth.of(2026, 1), YearMonth.of(2026, 2)))).containsExactly(
                "2026-01/FG/REPAIR/1/EA/RESULT", "2026-01/FG/SCRAP/2/EA/RESULT", "2026-02/FG/SCRAP/3/EA/RESULT");
        // 한 달만 물으면 그 달만
        assertThat(lines(monthly(comp, null, YearMonth.of(2026, 2), YearMonth.of(2026, 2)))).containsExactly("2026-02/FG/SCRAP/3/EA/RESULT");
        // 회사 시간대 LA — 서울 2/1 0시 = LA 1/31 아침 → 둘 다 1월
        accounts.exec(comp, "update sys_comp set time_zone = 'America/Los_Angeles' where comp_cd = ?", comp);
        assertThat(lines(monthly(comp, null, YearMonth.of(2026, 1), YearMonth.of(2026, 2)))).containsExactly(
                "2026-01/FG/REPAIR/1/EA/RESULT", "2026-01/FG/SCRAP/5/EA/RESULT");
    }

    @Test
    void 같은_달_안_정정은_상쇄되어_다시_넣은_값만_남는다() throws Exception {
        Pop p1 = pop(comp, admin, "P1");
        start(p1, proc1);
        long rid = popResult(p1, proc1, 5, 4, 0);
        assertThat(lines(monthly(comp, null, now, now))).containsExactly(now + "/FG/SCRAP/4/EA/RESULT");
        call(p1, post("/api/pop/results/" + rid + "/correct"), "{\"reason\":\"폐기 수량 잘못\"}").andExpect(status().isOk());
        assertThat(accounts.query(comp, "select count(*) from prd_result where reversal_of_id = ?", Integer.class, rid)).isEqualTo(1);
        assertThat(monthly(comp, null, now, now)).isEmpty();
        popResult(p1, proc1, 5, 1, 0);
        assertThat(lines(monthly(comp, null, now, now))).containsExactly(now + "/FG/SCRAP/1/EA/RESULT");
    }

    @Test
    void 달을_넘긴_정정은_원래_달_그대로_정정한_달에_음수_다시_넣은_값은_넣은_달로() throws Exception {
        Pop p1 = pop(comp, admin, "P1");
        start(p1, proc1);
        pastResult(comp, wo1, proc1, "2026-01-15 10:00:00", 6, 0);
        long old = accounts.query(comp, "select max(result_id) from prd_result where wo_proc_id = ?", Long.class, proc1);
        YearMonth jan = YearMonth.of(2026, 1);
        assertThat(lines(monthly(comp, null, jan, jan))).containsExactly("2026-01/FG/SCRAP/6/EA/RESULT");
        // 지금 정정(역분개 행 발생 시각 = 지금) → 1월은 그대로 6, 이번 달에 −6 (C-1 (라) — 지난 달 COPQ 가 조용히 바뀌지 않는다)
        call(p1, post("/api/pop/results/" + old + "/correct"), "{\"reason\":\"폐기 수량 잘못\"}").andExpect(status().isOk());
        assertThat(lines(monthly(comp, null, jan, jan))).containsExactly("2026-01/FG/SCRAP/6/EA/RESULT");
        assertThat(lines(monthly(comp, null, now, now))).containsExactly(now + "/FG/SCRAP/-6/EA/RESULT");
        // 다시 넣은 값은 넣은 때(POP 화면은 지금 시각을 보낸다)의 달로 → 이번 달 = −6 + 2
        popResult(p1, proc1, 0, 2, 0);
        assertThat(lines(monthly(comp, null, jan, now))).containsExactly("2026-01/FG/SCRAP/6/EA/RESULT", now + "/FG/SCRAP/-4/EA/RESULT");
        // 원래 시각으로 다시 넣으려 해도 지시 발행 전 시각은 거부(occurredAt RANGE) — 지시가 지난 달에 발행됐을 때만 가능
        call(p1, post("/api/pop/procs/" + proc1 + "/results"), "{\"clientReqId\":\"" + UUID.randomUUID()
                + "\",\"occurredAt\":\"2026-01-15T10:00:00+09:00\",\"goodQty\":\"0\",\"scrapQty\":\"2\",\"reworkQty\":\"0\",\"repairQty\":\"0\""
                + ",\"defects\":[{\"kind\":\"SCRAP\",\"defectCd\":\"D-PIT\",\"qty\":\"2\"}]}").andExpect(status().isBadRequest());
    }

    @Test
    void 재작업_처리의_폐기는_원천_REWORK_로_따로() throws Exception {
        Pop p1 = pop(comp, admin, "P1");
        start(p1, proc1);
        popResult(p1, proc1, 5, 0, 3);
        mvc.perform(json(post(WO + "/procs/" + proc1 + "/rework"), "{\"clientReqId\":\"" + UUID.randomUUID() + "\",\"kind\":\"REWORK\""
                + ",\"goodQty\":\"1\",\"scrapQty\":\"2\",\"reason\":\"재용접\"}").with(admin)).andExpect(status().isOk());
        // 처리 폐기 0 인 처리는 줄을 만들지 않는다
        popResult(p1, proc1, 0, 0, 1);
        mvc.perform(json(post(WO + "/procs/" + proc1 + "/rework"), "{\"clientReqId\":\"" + UUID.randomUUID() + "\",\"kind\":\"REWORK\""
                + ",\"goodQty\":\"1\",\"scrapQty\":\"0\",\"reason\":\"재용접\"}").with(admin)).andExpect(status().isOk());
        assertThat(lines(monthly(comp, null, now, now))).containsExactly(now + "/FG/REWORK/4/EA/RESULT", now + "/FG/SCRAP/2/EA/REWORK");
    }

    @Test
    void 공장으로_거른다() throws Exception {
        Pop p1 = pop(comp, admin, "P1");
        start(p1, proc1);
        Pop p2 = pop(comp, admin, "P2");
        start(p2, proc2);
        pastResult(comp, wo1, proc1, "2026-03-10 10:00:00", 2, 0);
        pastResult(comp, wo2, proc2, "2026-03-11 10:00:00", 7, 0);
        YearMonth m = YearMonth.of(2026, 3);
        assertThat(lines(monthly(comp, null, m, m))).containsExactly("2026-03/FG/SCRAP/9/EA/RESULT");
        assertThat(lines(monthly(comp, "P1", m, m))).containsExactly("2026-03/FG/SCRAP/2/EA/RESULT");
        assertThat(lines(monthly(comp, " P2 ", m, m))).containsExactly("2026-03/FG/SCRAP/7/EA/RESULT");
        assertThat(monthly(comp, "NOPE", m, m)).isEmpty();
    }

    @Test
    void A사로_B사_불량은_보이지_않는다() throws Exception {
        Pop p1 = pop(comp, admin, "P1");
        start(p1, proc1);
        pastResult(comp, wo1, proc1, "2026-04-02 09:00:00", 2, 0);
        String b = accounts.newComp("DQB");
        accounts.exec(b, "update sys_comp set time_zone = 'Asia/Seoul' where comp_cd = ?", b);
        RequestPostProcessor bAdmin = admin(b);
        master(b);
        long bWo = wo(b, bAdmin, "P1");
        long bProc = proc(b, bWo);
        Pop bp = pop(b, bAdmin, "P1");
        start(bp, bProc);
        pastResult(b, bWo, bProc, "2026-04-03 09:00:00", 9, 0);
        YearMonth m = YearMonth.of(2026, 4);
        assertThat(lines(monthly(comp, null, m, m))).containsExactly("2026-04/FG/SCRAP/2/EA/RESULT");
        assertThat(monthly(b, null, m, m)).hasSize(1).first().satisfies(r -> assertThat(r.qty()).isEqualByComparingTo("9"));
        assertThat(monthly(b, null, m, m).get(0).itemId()).isNotEqualTo(item);
    }

    @Test
    void NONE_과_잘못된_기간() {
        assertThat(DefectQtyQuery.of(new StaticListableBeanFactory().getBeanProvider(DefectQtyQuery.class))).isSameAs(DefectQtyQuery.NONE);
        assertThat(DefectQtyQuery.NONE.monthly(null, YearMonth.of(2026, 1), YearMonth.of(2026, 1))).isEmpty();
        assertThatThrownBy(() -> monthly(comp, null, YearMonth.of(2026, 2), YearMonth.of(2026, 1))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> monthly(comp, null, null, YearMonth.of(2026, 1))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> monthly(comp, null, YearMonth.of(2023, 1), YearMonth.of(2026, 1))).isInstanceOf(IllegalArgumentException.class);
        assertThat(monthly(comp, null, YearMonth.of(2024, 1), YearMonth.of(2026, 12))).isEmpty();   // 36개월은 된다
    }
}
