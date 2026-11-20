package io.smarthappy.module.qms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.jayway.jsonpath.JsonPath;

import io.smarthappy.module.master.workforce.WorkerService;
import io.smarthappy.module.pop.PopAuthService;
import io.smarthappy.module.qms.link.FloorInspPort;
import io.smarthappy.module.qms.link.FloorInspPort.ConfirmForm;
import io.smarthappy.module.qms.link.FloorInspPort.DueInsp;
import io.smarthappy.module.qms.link.FloorInspPort.DueQuery;
import io.smarthappy.module.qms.link.FloorInspPort.Entry;
import io.smarthappy.module.qms.link.FloorInspPort.FloorInsp;
import io.smarthappy.module.qms.link.FloorInspPort.InspRef;
import io.smarthappy.module.qms.link.FloorInspPort.MeasureForm;
import io.smarthappy.module.qms.link.FloorInspPort.MeasureResult;
import io.smarthappy.module.qms.link.FloorInspPort.OpenForm;
import io.smarthappy.platform.common.web.BusinessException;
import io.smarthappy.platform.common.web.HbErrorCode;
import io.smarthappy.platform.tenant.TenantRunner;
import jakarta.servlet.http.Cookie;

/**
 * 현장 단말(POP) 검사 입력 계약 FloorInspPort (docs/29_P3 53장) — 실제 POP 실적으로 만든 산출 LOT 위에서
 * 정상(due → 자동 시작 → 측정 → 전부 OK 자동 확정 / NG → 불합격 확정) · 열린 검사 재사용 · 멱등 · 이상 사용(폐기·없는 단말, 사용 중지 작업자,
 * 다른 공장, 이 지시 LOT 아님, 단말 유형 밖, 형식) · A사 단말로 B사 검사 · 직접 SQL(단말 칸 CHECK·다른 회사 작업자).
 */
class FloorInspPortTest extends QmsTestBase {

    static final String WO = "/api/production/wos";

    @Autowired
    TenantRunner runner;

    @Autowired
    FloorInspPort port;

    @Autowired
    org.springframework.jdbc.core.JdbcTemplate jdbc;

    long woProc1;
    long woProc2;
    long wo1;
    long tP1;
    long tP2;
    long worker;
    long workerOff;
    String lot1;
    String lot2;
    Pop pop1;
    int seq;

    record Pop(String cookie, MockHttpSession session) {
    }

    @BeforeEach
    void floorSetUp() throws Exception {
        accounts.exec(comp, "update sys_comp set time_zone = 'Asia/Seoul' where comp_cd = ?", comp);
        accounts.grant(comp, userId, "QA", "sys-terminal", "SEARCH", "SAVE");
        accounts.grant(comp, userId, "QA", "prd-wo", "SEARCH", "SAVE");
        accounts.grant(comp, userId, "QA", "qms-insp", "SEARCH", "SAVE", "DELETE");
        master(comp);
        // 검사계획: 초물·순회(공정 DRAW), 출하(공정 무관) — FG1-1 계량(9.95~10.05)·FG1-2 계수, 고정 5
        for (String[] p : new String[][] {{"FIRST_OFF", "DRAW"}, {"PATROL", "DRAW"}, {"FINAL", null}}) {
            long id = plan(p[0], "FG1", null, p[1]);
            lines(id, line("a", null, 10, fgChar1, "S5", "VISUAL"), line("b", null, 20, fgChar2, "S5", "VISUAL")).andExpect(status().isOk());
            action(id, "release").andExpect(status().isOk());
        }
        wo1 = wo("P1");
        woProc1 = accounts.query(comp, "select wo_proc_id from prd_wo_proc where wo_id = ? and op_seq = 10", Long.class, wo1);
        long wo2 = wo("P2");
        woProc2 = accounts.query(comp, "select wo_proc_id from prd_wo_proc where wo_id = ? and op_seq = 10", Long.class, wo2);
        pop1 = pop("P1");
        pop("P2");
        tP1 = accounts.query(comp, "select terminal_id from sys_terminal where terminal_cd = 'TAB-P1'", Long.class);
        tP2 = accounts.query(comp, "select terminal_id from sys_terminal where terminal_cd = 'TAB-P2'", Long.class);
        worker = accounts.query(comp, "select worker_id from mst_worker where worker_cd = 'E1'", Long.class);
        workerOff = accounts.query(comp, """
                insert into mst_worker (comp_cd, worker_cd, worker_nm, plant_cd, use_yn, created_by, updated_by)
                values (?, 'E9', '퇴사', 'P1', 'N', 't', 't') returning worker_id""", Long.class, comp);
        call(pop1, post("/api/pop/procs/" + woProc1 + "/events/start"), "{\"clientReqId\":\"" + UUID.randomUUID() + "\",\"ack\":true}")
                .andExpect(status().isOk());
        lot1 = result(pop1, woProc1, 5);
        lot2 = result(pop1, woProc1, 5);
    }

    /** 공장 P1·P2, 작업장, 작업자 E1(P1, 배지), FG1 × DRAW 한 공정 라우팅(공장마다) */
    void master(String c) {
        accounts.addPlant(c, "P1", "1공장");
        accounts.addPlant(c, "P2", "2공장");
        accounts.exec(c, """
                insert into mst_worker (comp_cd, worker_cd, worker_nm, plant_cd, badge_hash, created_by, updated_by)
                values (?, 'E1', '홍작업', 'P1', ?, 't', 't')""", c, WorkerService.badgeHash(c, "BADGE-0001"));
        for (String plant : List.of("P1", "P2")) {
            long area = node(c, plant, null, "AREA", "A-" + plant);
            long line = node(c, plant, area, "LINE", "L-" + plant);
            long cell = node(c, plant, line, "CELL", "C-" + plant);
            long rid = accounts.query(c, """
                    insert into mst_routing (comp_cd, item_id, plant_cd, rev, valid_from, created_by, updated_by)
                    values (?, (select item_id from mst_item where item_cd = 'FG1'), ?, 'A0', (now() at time zone 'Asia/Seoul')::date - 30, 't', 't')
                    returning routing_id""", Long.class, c, plant);
            accounts.exec(c, """
                    insert into mst_routing_step (comp_cd, routing_id, op_seq, proc_id, node_id, std_ct_sec, created_by, updated_by)
                    values (?, ?, 10, (select proc_id from mst_proc where proc_cd = 'DRAW'), ?, 30, 't', 't')""", c, rid, cell);
            accounts.exec(c, "update mst_routing set status = 'RELEASED', released_at = now(), released_by = 't' where routing_id = ?", rid);
        }
    }

    long node(String c, String plant, Long parent, String type, String cd) {
        return accounts.query(c, """
                insert into mst_equip_hier (comp_cd, plant_cd, parent_id, node_type, node_cd, node_nm, created_by, updated_by)
                values (?, ?, ?, ?, ?, ?, 't', 't') returning node_id""", Long.class, c, plant, parent, type, cd, cd + " 노드");
    }

    long wo(String plant) throws Exception {
        long id = id(postJson(WO, "{\"plantCd\":\"" + plant + "\",\"itemCd\":\"FG1\",\"planQty\":\"100\",\"planStart\":\"" + today + "\"}"));
        postJson(WO + "/" + id + "/release", "{\"rowVer\":0}").andExpect(status().isOk());
        return id;
    }

    /** 공장 단말 등록·활성화 + 배지 로그인 */
    Pop pop(String plant) throws Exception {
        String cd = "TAB-" + plant;
        MvcResult m = postJson("/api/production/terminals", "{\"terminalCd\":\"" + cd + "\",\"terminalNm\":\"" + cd + "\",\"plantCd\":\"" + plant + "\"}")
                .andExpect(status().isOk()).andReturn();
        String code = JsonPath.read(m.getResponse().getContentAsString(), "$.data.code");
        String ip = "10.84." + (System.nanoTime() % 200) + "." + (System.nanoTime() % 250 + 1);
        MvcResult a = mvc.perform(json(post("/api/pop/activate"), "{\"compCd\":\"" + comp + "\",\"code\":\"" + code + "\"}")
                .with(r -> { r.setRemoteAddr(ip); return r; })).andExpect(status().isOk()).andReturn();
        String set = a.getResponse().getHeaders("Set-Cookie").stream().filter(h -> h.startsWith(PopAuthService.COOKIE + "="))
                .findFirst().orElseThrow();
        Pop p = new Pop(set.substring(set.indexOf('=') + 1, set.indexOf(';')), new MockHttpSession());
        if ("P1".equals(plant)) {
            call(p, post("/api/pop/badge"), "{\"badge\":\"BADGE-0001\"}").andExpect(status().isOk());
        }
        return p;
    }

    ResultActions call(Pop p, MockHttpServletRequestBuilder b, String body) throws Exception {
        return mvc.perform(json(b, body).cookie(new Cookie(PopAuthService.COOKIE, p.cookie())).session(p.session()));
    }

    /** POP 실적(양품 good) → 산출 LOT 번호 */
    String result(Pop p, long proc, int good) throws Exception {
        String r = UUID.randomUUID().toString();
        call(p, post("/api/pop/procs/" + proc + "/results"), "{\"clientReqId\":\"" + r + "\",\"goodQty\":\"" + good
                + "\",\"scrapQty\":\"0\",\"reworkQty\":\"0\",\"repairQty\":\"0\",\"defects\":[]}").andExpect(status().isOk());
        return accounts.query(comp, "select l.lot_no from prd_result x join lot_master l on l.lot_id = x.lot_id where x.client_req_id = ?",
                String.class, r);
    }

    String req() {
        return "req-floor-" + (++seq) + "-" + System.nanoTime();
    }

    <T> T asTerminal(long terminal, java.util.function.Supplier<T> body) {
        return runner.callAs(comp, "t" + terminal + ":TAB", body);
    }

    BusinessException fail(java.util.function.Supplier<?> body) {
        return catchThrowableOfType(BusinessException.class, () -> asTerminal(tP1, body));
    }

    static String field(BusinessException e) {
        return e.fieldErrors().get(0).field() + ":" + e.fieldErrors().get(0).code();
    }

    static List<Entry> vals(String... v) {
        return java.util.stream.IntStream.range(0, v.length).mapToObj(i -> new Entry(i + 1, new BigDecimal(v[i]), null, null)).toList();
    }

    static List<Entry> oks(String... v) {
        return java.util.stream.IntStream.range(0, v.length).mapToObj(i -> new Entry(i + 1, null, v[i], null)).toList();
    }

    long lineId(long insp, int no) {
        return accounts.query(comp, "select insp_line_id from qms_insp_line where insp_id = ? and line_no = ?", Long.class, insp, no);
    }

    MeasureResult measure(long insp, int lineNo, List<Entry> entries, String method, String reqId) {
        long li = lineId(insp, lineNo);
        return asTerminal(tP1, () -> port.measure(new MeasureForm(insp, li, entries, method, worker, tP1, reqId)));
    }

    // ---------- 정상 ----------

    @Test
    void 자동시작_열린검사_재사용_측정마다_미리보기_전부OK면_자동확정() {
        List<DueInsp> due = asTerminal(tP1, () -> port.due(new DueQuery(woProc1, tP1)));
        assertThat(due).extracting(DueInsp::inspType).containsExactly("FIRST_OFF", "PATROL");    // 출하(FINAL)·계획 없는 유형은 단말에 없음
        assertThat(due.get(0).suggestedLotNo()).as("초물 = 첫 실적 LOT").isEqualTo(lot1);
        assertThat(due.get(1).suggestedLotNo()).as("순회 = 최근 실적 LOT").isEqualTo(lot2);
        assertThat(due.get(0).openInspId()).isNull();

        String r1 = req();
        InspRef ref = asTerminal(tP1, () -> port.open(new OpenForm("FIRST_OFF", lot1, woProc1, worker, tP1, r1)));
        assertThat(ref.existing()).isFalse();
        assertThat(ref.duplicate()).isFalse();
        InspRef again = asTerminal(tP1, () -> port.open(new OpenForm("FIRST_OFF", lot1, woProc1, worker, tP1, req())));
        assertThat(again.inspId()).as("열린 검사 재사용 — 새로 만들지 않음").isEqualTo(ref.inspId());
        assertThat(again.existing()).isTrue();
        InspRef dup = asTerminal(tP1, () -> port.open(new OpenForm("FIRST_OFF", lot1, woProc1, worker, tP1, r1)));
        assertThat(dup.inspId()).isEqualTo(ref.inspId());
        assertThat(dup.duplicate()).isTrue();
        assertThat(accounts.query(comp, "select count(*) from qms_insp", Integer.class)).isEqualTo(1);
        Map<String, Object> head = runner.call(comp, () -> jdbc.queryForMap(
                "select open_worker_id, open_terminal_id, open_req_id, created_by from qms_insp where insp_id = ?", ref.inspId()));
        assertThat(head).containsEntry("open_worker_id", worker).containsEntry("open_terminal_id", tP1).containsEntry("open_req_id", r1)
                .containsEntry("created_by", "t" + tP1 + ":TAB-P1");
        assertThat(asTerminal(tP1, () -> port.due(new DueQuery(woProc1, tP1))).get(0).openInspId()).isEqualTo(ref.inspId());

        FloorInsp d = asTerminal(tP1, () -> port.detail(ref.inspId(), tP1));
        assertThat(d.lines()).hasSize(2);
        assertThat(d.lines().get(0).sampleSize()).isEqualTo(5);
        assertThat(d.nextAction()).isEqualTo("MEASURE");
        assertThat(d.preview()).isEqualTo("OPEN");

        // 계량 행 5개(경계 10.05 는 합격) — 계수 행이 남아 확정 안 됨
        String rm = req();
        MeasureResult m1 = measure(ref.inspId(), 10, vals("10.00", "10.01", "9.99", "10.05", "9.95"), "SCAN", rm);
        assertThat(m1.judged()).isFalse();
        assertThat(m1.insp().lines().get(0).verdict()).isEqualTo("OK");
        assertThat(m1.insp().nextAction()).isEqualTo("MEASURE");
        // 같은 요청 다시 = 처음 결과(원장 그대로)
        assertThat(measure(ref.inspId(), 10, vals("10.00", "10.01", "9.99", "10.05", "9.95"), "SCAN", rm).duplicate()).isTrue();
        assertThat(accounts.query(comp, "select count(*) from qms_insp_meas", Integer.class)).isEqualTo(5);
        // 계수 행 5개 OK → 전부 OK → 자동 확정
        MeasureResult m2 = measure(ref.inspId(), 20, oks("Y", "Y", "Y", "Y", "Y"), null, req());
        assertThat(m2.judged()).isTrue();
        assertThat(m2.insp().status()).isEqualTo("JUDGED");
        assertThat(m2.insp().result()).isEqualTo("OK");
        assertThat(m2.insp().nextAction()).isEqualTo("DONE");
        Map<String, Object> j = runner.call(comp, () -> jdbc.queryForMap("select status, result, judged_by, judged_worker_id from qms_insp where insp_id = ?", ref.inspId()));
        assertThat(j).containsEntry("status", "JUDGED").containsEntry("result", "OK").containsEntry("judged_by", "t" + tP1 + ":TAB-P1")
                .containsEntry("judged_worker_id", worker);
        assertThat(accounts.query(comp, "select string_agg(distinct entry_method || ':' || performed_by || ':' || terminal_id || ':' || recorded_by, ',') "
                + "from qms_insp_meas", String.class)).isEqualTo("KEY:" + worker + ":" + tP1 + ":t" + tP1 + ":TAB-P1,SCAN:" + worker + ":" + tP1
                        + ":t" + tP1 + ":TAB-P1");
        // 확정 뒤 측정 409, 단말 화면 DONE
        long l10 = lineId(ref.inspId(), 10);
        BusinessException after = fail(() -> port.measure(new MeasureForm(ref.inspId(), l10, vals("10.00"), null, worker, tP1, req())));
        assertThat(after.code()).isEqualTo(HbErrorCode.QMS_INSP_STATE);
        assertThat(asTerminal(tP1, () -> port.due(new DueQuery(woProc1, tP1))).get(0).lastResult()).isEqualTo("OK");
    }

    @Test
    void NG가_섞이면_자동확정_안함_불합격확정_한번_조건() {
        InspRef ref = asTerminal(tP1, () -> port.open(new OpenForm("PATROL", lot2, woProc1, worker, tP1, req())));
        // 계량 행: 10.06 하나(Ac 0) → 행 NG(다 재지 않아도), 계수 행은 아직 → MEASURE
        MeasureResult m1 = measure(ref.inspId(), 10, vals("10.06"), null, req());
        assertThat(m1.insp().lines().get(0).verdict()).isEqualTo("NG");
        assertThat(m1.insp().lines().get(0).meas().get(0).outOfSpec()).isTrue();
        assertThat(m1.insp().nextAction()).isEqualTo("MEASURE");
        // 미완(계수 행 열림)에서 불합격 확정 → 400 INCOMPLETE
        BusinessException early = fail(() -> port.confirmNg(new ConfirmForm(ref.inspId(), worker, tP1, req())));
        assertThat(early.code()).isEqualTo(HbErrorCode.QMS_INSP_INCOMPLETE);
        // 계수 행 다 OK → 모든 행 판정·NG → 자동 확정 안 함, CONFIRM_NG
        MeasureResult m2 = measure(ref.inspId(), 20, oks("Y", "Y", "Y", "Y", "Y"), null, req());
        assertThat(m2.judged()).isFalse();
        assertThat(m2.insp().status()).isEqualTo("OPEN");
        assertThat(m2.insp().preview()).isEqualTo("NG");
        assertThat(m2.insp().nextAction()).isEqualTo("CONFIRM_NG");
        String rn = req();
        FloorInsp done = asTerminal(tP1, () -> port.confirmNg(new ConfirmForm(ref.inspId(), worker, tP1, rn)));
        assertThat(done.status()).isEqualTo("JUDGED");
        assertThat(done.result()).isEqualTo("NG");
        assertThat(asTerminal(tP1, () -> port.confirmNg(new ConfirmForm(ref.inspId(), worker, tP1, rn))).result()).as("멱등").isEqualTo("NG");
        assertThat(accounts.query(comp, "select judged_worker_id from qms_insp where insp_id = ?", Long.class, ref.inspId())).isEqualTo(worker);
        // OK 미리보기 검사에 불합격 확정 → 409(자동 확정 대상이라 여기 오지 않음 — 일부만 재서 OPEN 인 OK 쪽)
        InspRef ok = asTerminal(tP1, () -> port.open(new OpenForm("FIRST_OFF", lot1, woProc1, worker, tP1, req())));
        measure(ok.inspId(), 10, vals("10.00", "10.00", "10.00", "10.00", "10.00"), null, req());
        BusinessException notNg = fail(() -> port.confirmNg(new ConfirmForm(ok.inspId(), worker, tP1, req())));
        assertThat(notNg.code()).isEqualTo(HbErrorCode.QMS_INSP_INCOMPLETE);
    }

    // ---------- 이상 사용 ----------

    @Test
    void 이상_단말_작업자_공장_LOT_유형_형식_단말범위() throws Exception {
        assertThat(field(fail(() -> port.open(new OpenForm("FIRST_OFF", lot1, woProc1, worker, 999_999_999L, req()))))).isEqualTo("terminalId:NO_SUCH_CODE");
        assertThat(field(fail(() -> port.open(new OpenForm("FIRST_OFF", lot1, woProc1, workerOff, tP1, req()))))).isEqualTo("workerId:NO_SUCH_CODE");
        assertThat(field(fail(() -> port.open(new OpenForm("FIRST_OFF", lot1, woProc2, worker, tP1, req()))))).isEqualTo("woProcId:FLOOR_PLANT");
        assertThat(field(fail(() -> port.open(new OpenForm("FIRST_OFF", "NO-SUCH-LOT", woProc1, worker, tP1, req()))))).isEqualTo("lotNo:NOT_WO_LOT");
        assertThat(field(fail(() -> port.open(new OpenForm("RECEIVE", lot1, woProc1, worker, tP1, req()))))).isEqualTo("inspType:CODE");
        assertThat(field(fail(() -> port.open(new OpenForm("FIRST_OFF", lot1, woProc1, worker, tP1, "short"))))).isEqualTo("clientReqId:FORMAT");
        assertThat(asTerminal(tP2, () -> port.due(new DueQuery(woProc1, tP2)))).as("다른 공장 단말 = 빈 목록").isEmpty();
        assertThat(asTerminal(tP1, () -> port.due(new DueQuery(999_999_999L, tP1)))).isEmpty();
        InspRef ref = asTerminal(tP1, () -> port.open(new OpenForm("FIRST_OFF", lot1, woProc1, worker, tP1, req())));
        assertThat(catchThrowableOfType(BusinessException.class, () -> asTerminal(tP2, () -> port.detail(ref.inspId(), tP2))).code())
                .as("다른 공장 단말로 검사 화면").isEqualTo(HbErrorCode.NOT_FOUND);
        long l10 = lineId(ref.inspId(), 10);
        assertThat(field(fail(() -> port.measure(new MeasureForm(ref.inspId(), l10, vals("10"), "IMPORT", worker, tP1, req())))))
                .isEqualTo("entryMethod:CODE");
        // 측정 칸 오류는 사무와 같은 검사(샘플 번호 범위)
        BusinessException range = fail(() -> port.measure(new MeasureForm(ref.inspId(), l10,
                List.of(new Entry(6, new BigDecimal("10"), null, null)), null, worker, tP1, req())));
        assertThat(range.fieldErrors().get(0).field()).isEqualTo("sampleNo");
        // 단말 유형 밖(출하 검사 — 사무로 연 것)은 단말로 볼 수도 잴 수도 없다
        long fin = id(postJson("/api/qms/insps", "{\"lotNo\":" + q(lot1) + ",\"inspType\":\"FINAL\"}"));
        assertThat(fail(() -> port.detail(fin, tP1)).code()).isEqualTo(HbErrorCode.NOT_FOUND);
        assertThat(fail(() -> port.confirmNg(new ConfirmForm(fin, worker, tP1, req()))).code()).isEqualTo(HbErrorCode.NOT_FOUND);
        // 폐기한 단말
        accounts.exec(comp, "update sys_terminal set revoked_at = now(), revoke_reason = '분실', key_hash = null, activation_hash = null where terminal_id = ?", tP1);
        assertThat(field(fail(() -> port.open(new OpenForm("FIRST_OFF", lot1, woProc1, worker, tP1, req()))))).isEqualTo("terminalId:NO_SUCH_CODE");
        // 사무 API 는 그대로(사람 로그인 — 단말 칸 없음)
        assertThat(accounts.query(comp, "select count(*) from qms_insp where insp_id = ? and open_terminal_id is null", Integer.class, fin)).isEqualTo(1);
    }

    // ---------- A사로 B사 · 직접 SQL ----------

    @Test
    void A사_단말로_B사_검사_못봄_직접SQL_단말칸_CHECK_다른회사작업자() {
        InspRef ref = asTerminal(tP1, () -> port.open(new OpenForm("FIRST_OFF", lot1, woProc1, worker, tP1, req())));
        String other = accounts.newComp("QF");
        accounts.addPlant(other, "P1", "1공장");
        long bTerm = accounts.query(other, """
                insert into sys_terminal (comp_cd, terminal_cd, terminal_nm, plant_cd, created_by, updated_by) values (?, 'TAB-B', 'B', 'P1', 't', 't')
                returning terminal_id""", Long.class, other);
        long bWorker = accounts.query(other, """
                insert into mst_worker (comp_cd, worker_cd, worker_nm, plant_cd, created_by, updated_by) values (?, 'B1', '비사', 'P1', 't', 't')
                returning worker_id""", Long.class, other);
        BusinessException view = catchThrowableOfType(BusinessException.class,
                () -> runner.callAs(other, "t" + bTerm + ":TAB-B", () -> port.detail(ref.inspId(), bTerm)));
        assertThat(view.code()).isEqualTo(HbErrorCode.NOT_FOUND);
        BusinessException open = catchThrowableOfType(BusinessException.class, () -> runner.callAs(other, "t" + bTerm + ":TAB-B",
                () -> port.open(new OpenForm("FIRST_OFF", lot1, woProc1, bWorker, bTerm, req()))));
        assertThat(field(open)).isEqualTo("woProcId:NO_SUCH_CODE");
        BusinessException meas = catchThrowableOfType(BusinessException.class, () -> runner.callAs(other, "t" + bTerm + ":TAB-B",
                () -> port.measure(new MeasureForm(ref.inspId(), 1L, vals("10"), null, bWorker, bTerm, req()))));
        assertThat(meas.code()).isEqualTo(HbErrorCode.NOT_FOUND);
        assertThat(runner.call(other, () -> port.due(new DueQuery(woProc1, bTerm)))).isEmpty();
        // A사 단말 + A사 작업자 id 를 B사 범위에서 → 단말·작업자 없음
        BusinessException cross = catchThrowableOfType(BusinessException.class, () -> runner.callAs(other, "x",
                () -> port.open(new OpenForm("FIRST_OFF", lot1, woProc1, worker, tP1, req()))));
        assertThat(cross.fieldErrors()).extracting(e -> e.field() + ":" + e.code()).contains("terminalId:NO_SUCH_CODE", "workerId:NO_SUCH_CODE");
        // 직접 SQL: 단말 칸 셋은 함께, 다른 회사 작업자 거부
        long line = lineId(ref.inspId(), 10);
        String ins = "insert into qms_insp_meas (comp_cd, insp_line_id, sample_no, value, occurred_at, recorded_by, entry_method, performed_by, terminal_id, client_req_id) "
                + "values (?, ?, ?, 10, now(), 't', 'SCAN', ?, ?, ?)";
        constraint(comp, "ck_qms_insp_meas_floor", () -> accounts.exec(comp, ins, comp, line, 1, null, tP1, "req-sql-0001"));
        constraint(comp, "ck_qms_insp_meas_req_fmt", () -> accounts.exec(comp, ins, comp, line, 1, worker, tP1, "bad id"));
        constraint(comp, "fk_qms_insp_floor_worker_comp", () -> accounts.exec(comp, ins, comp, line, 1, bWorker, tP1, "req-sql-0002"));
        constraint(comp, "fk_qms_insp_floor_terminal_comp", () -> accounts.exec(comp, ins, comp, line, 1, worker, bTerm, "req-sql-0003"));
        constraint(comp, "ck_qms_insp_meas_entry_method", () -> accounts.exec(comp,
                "insert into qms_insp_meas (comp_cd, insp_line_id, sample_no, value, occurred_at, recorded_by, entry_method) values (?, ?, 1, 10, now(), 't', 'VOICE')",
                comp, line));
    }

    RequestPostProcessor me() {
        return admin;
    }
}
