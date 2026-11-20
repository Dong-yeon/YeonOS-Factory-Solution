package io.smarthappy.module.cms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.ByteArrayInputStream;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.jayway.jsonpath.JsonPath;

import io.smarthappy.module.pop.PopDtos.PopWorker;
import io.smarthappy.module.pop.PopWorkDtos.CorrectForm;
import io.smarthappy.module.pop.PopWorkService;
import io.smarthappy.module.pop.TerminalPrincipal;
import io.smarthappy.platform.tenant.TenantRunner;
import io.smarthappy.support.PostgresTestBase;
import io.smarthappy.support.TestAccounts;

/**
 * CMS 2부 C9 (docs/33 11.8) — 설비 KPI 를 원장(MES 상태 구간·실적, CMS 사후 사유)에서 손 검산: 하루 경계(공장 시간대 자정)로 자르기,
 * 사후 사유 재분류 반영, UNKNOWN·NST·SDT 를 계획에서 뺌, 수집 없는 설비 빈칸(D19), 표준 사이클 없는 실적 → 성능·OEE 빈칸(D21),
 * 역분개 실적은 순수량, 라인·공장 합계 = 합한 분자 ÷ 합한 분모, 기간 합산 = 일별 합산, 권한, A사로 B사.
 */
@AutoConfigureMockMvc
class CmsKpiTest extends PostgresTestBase {

    private static final String PW = "Aspen-meter-61";
    private static final String K = "/api/cms/kpi";
    private static final String D1 = "2026-03-02";
    private static final String D2 = "2026-03-03";

    @Autowired
    MockMvc mvc;

    @Autowired
    TestAccounts accounts;

    @Autowired
    TenantRunner runner;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PopWorkService pop;

    String comp;
    RequestPostProcessor admin;
    long line1;
    long eqA;
    long eqB;
    long eqC;
    long worker;
    long woCt;
    long procCt;
    long woNoCt;
    long procNoCt;
    final AtomicInteger seq = new AtomicInteger();

    @BeforeEach
    void setUp() throws Exception {
        comp = accounts.newComp("KP");
        long userId = accounts.newUser(comp, "admin1", PW);
        accounts.grant(comp, userId, "ADM", "cms-kpi", "SEARCH", "EXCEL");
        accounts.grant(comp, userId, "ADM2", "prd-wo", "SEARCH", "SAVE");
        admin = accounts.as(comp, userId, "admin1");
        accounts.addPlant(comp, "P1", "1공장");
        line1 = node(comp, "L1");
        long line2 = node(comp, "L2");
        long cls = accounts.query(comp, "insert into mst_equip_class (comp_cd, class_cd, class_nm, created_by, updated_by) values (?, 'PRESS', 'p', 't', 't') returning class_id",
                Long.class, comp);
        eqA = equip(comp, "EQ-A", cls, line1);
        eqB = equip(comp, "EQ-B", cls, line1);
        eqC = equip(comp, "EQ-C", cls, line2);
        worker = accounts.query(comp, """
                insert into mst_worker (comp_cd, worker_cd, worker_nm, plant_cd, created_by, updated_by)
                values (?, 'E1', '작업자', 'P1', 't', 't') returning worker_id""", Long.class, comp);
        accounts.exec(comp, "insert into qms_defect (comp_cd, defect_cd, defect_nm, cat_cd, created_by, updated_by) values (?, 'D-X', '불량', 'WELD', 't', 't')", comp);
        accounts.exec(comp, "insert into mst_proc (comp_cd, proc_cd, proc_nm, proc_cat, created_by, updated_by) values (?, 'PRS', '프레스', 'WELD', 't', 't')", comp);
        long[] a = workOrder("FG1", "36");
        woCt = a[0];
        procCt = a[1];
        long[] b = workOrder("FG2", null);
        woNoCt = b[0];
        procNoCt = b[1];
    }

    private long node(String c, String cd) {
        Long area = accounts.query(c, "select min(node_id) from mst_equip_hier where node_type = 'AREA'", Long.class);
        long parent = area != null ? area : accounts.query(c, """
                insert into mst_equip_hier (comp_cd, plant_cd, parent_id, node_type, node_cd, node_nm, created_by, updated_by)
                values (?, 'P1', null, 'AREA', 'A1', 'A1 구역', 't', 't') returning node_id""", Long.class, c);
        return accounts.query(c, """
                insert into mst_equip_hier (comp_cd, plant_cd, parent_id, node_type, node_cd, node_nm, created_by, updated_by)
                values (?, 'P1', ?, 'LINE', ?, ?, 't', 't') returning node_id""", Long.class, c, parent, cd, cd + " 라인");
    }

    private long equip(String c, String cd, long cls, long nodeId) {
        return accounts.query(c, """
                insert into mst_equip (comp_cd, plant_cd, equip_cd, equip_nm, class_id, node_id, created_by, updated_by)
                values (?, 'P1', ?, ?, ?, ?, 't', 't') returning equip_id""", Long.class, c, cd, cd + " 설비", cls, nodeId);
    }

    /** 품목·라우팅(표준 사이클 ct 초, null 이면 없음)·BOM → 작업지시 발행 → [지시, 공정] */
    private long[] workOrder(String item, String ct) throws Exception {
        accounts.exec(comp, """
                insert into mst_item (comp_cd, item_cd, item_nm, item_type, proc_type, unit_cd, created_by, updated_by)
                values (?, ?, ?, 'FG', 'MAKE', 'EA', 't', 't'), (?, ?, ?, 'RM', 'BUY', 'EA', 't', 't')""",
                comp, item, item, comp, item + "-RM", item + " 자재");
        long rid = accounts.query(comp, """
                insert into mst_routing (comp_cd, item_id, plant_cd, rev, valid_from, created_by, updated_by)
                values (?, (select item_id from mst_item where item_cd = ?), 'P1', 'A0', (now() at time zone 'Asia/Seoul')::date - 30, 't', 't') returning routing_id""",
                Long.class, comp, item);
        accounts.exec(comp, """
                insert into mst_routing_step (comp_cd, routing_id, op_seq, proc_id, node_id, std_ct_sec, created_by, updated_by)
                values (?, ?, 10, (select proc_id from mst_proc where proc_cd = 'PRS'), ?, ?::numeric, 't', 't')""", comp, rid, line1, ct);
        accounts.exec(comp, "update mst_routing set status = 'RELEASED', released_at = now(), released_by = 't' where routing_id = ?", rid);
        long bom = accounts.query(comp, """
                insert into mst_bom (comp_cd, item_id, rev, valid_from, base_qty, created_by, updated_by)
                values (?, (select item_id from mst_item where item_cd = ?), 'A0', (now() at time zone 'Asia/Seoul')::date - 30, 1, 't', 't') returning bom_id""",
                Long.class, comp, item);
        accounts.exec(comp, """
                insert into mst_bom_line (comp_cd, bom_id, line_no, item_id, qty, unit_cd, line_type, issue_method, created_by, updated_by)
                values (?, ?, 10, (select item_id from mst_item where item_cd = ?), 1, 'EA', 'NORMAL', 'BACKFLUSH', 't', 't')""", comp, bom, item + "-RM");
        accounts.exec(comp, "update mst_bom set status = 'RELEASED', released_at = now(), released_by = 't' where bom_id = ?", bom);
        String today = accounts.query(comp, "select " + TestAccounts.COMP_TODAY + "::text", String.class);
        long wo = id(mvc.perform(json(post("/api/production/wos"),
                "{\"plantCd\":\"P1\",\"itemCd\":\"" + item + "\",\"planQty\":\"10000\",\"planStart\":\"" + today + "\"}").with(admin)));
        mvc.perform(json(post("/api/production/wos/" + wo + "/release"), "{\"rowVer\":0}").with(admin)).andExpect(status().isOk());
        long proc = accounts.query(comp, "select wo_proc_id from prd_wo_proc where wo_id = ?", Long.class, wo);
        // 공정 시작(같은 트랜잭션에 START 이벤트)
        runner.call(comp, () -> {
            jdbc.update("""
                    insert into prd_wo_proc_event (comp_cd, wo_id, wo_proc_id, event_cd, gate_mode, work_day, client_req_id, occurred_at,
                                                   entry_method, recorded_by, performed_by)
                    values (?, ?, ?, 'START', 'OK', (now() at time zone 'Asia/Seoul')::date, ?, now(), 'KEY', 'x', ?)""", comp, wo, proc, "REQ-START-" + proc, worker);
            return jdbc.update("update prd_wo_proc set status = 'RUN' where wo_proc_id = ?", proc);
        });
        return new long[] {wo, proc};
    }

    /** 실적(설비·양품·폐기·발생 "dayTHH:mm" +09:00) — 양품은 생산 LOT, 폐기는 불량 내역과 같은 트랜잭션. 반환 = 실적 id */
    private long result(long wo, long proc, long equip, int good, int scrap, String at) {
        String ts = at + ":00+09:00";
        int n = seq.incrementAndGet();
        return runner.call(comp, () -> {
            Long lot = null;
            if (good > 0) {
                lot = jdbc.queryForObject("""
                        insert into lot_master (comp_cd, lot_no, item_id, origin, qty_created, unit_cd, wo_id, occurred_at, created_by, updated_by)
                        values (?, ?, (select item_id from prd_wo where wo_id = ?), 'PRODUCED', ?, 'EA', ?, ?::timestamptz, 't', 't') returning lot_id""",
                        Long.class, comp, "KP-LOT-" + n, wo, good, wo, ts);
            }
            long rid = jdbc.queryForObject("""
                    insert into prd_result (comp_cd, wo_id, wo_proc_id, equip_id, lot_id, good_qty, scrap_qty, rework_qty, repair_qty, unit_cd, work_day,
                                            client_req_id, occurred_at, entry_method, recorded_by, performed_by)
                    values (?, ?, ?, ?, ?, ?, ?, 0, 0, 'EA', (?::timestamptz at time zone 'Asia/Seoul')::date, ?, ?::timestamptz, 'KEY', 'x', ?) returning result_id""",
                    Long.class, comp, wo, proc, equip == 0 ? null : equip, lot, good, scrap, ts, "REQ-RESULT-" + n, ts, worker);
            if (scrap > 0) {
                jdbc.update("insert into prd_result_defect (comp_cd, result_id, kind, defect_id, qty) select ?, ?, 'SCRAP', defect_id, ? from qms_defect",
                        comp, rid, scrap);
            }
            return rid;
        });
    }

    private void state(String c, long equip, String cat, String start, String end) {
        accounts.exec(c, """
                insert into cms_equip_state_log (comp_cd, equip_id, state_cat, start_at, end_at, source, occurred_at, entry_method, recorded_by)
                values (?, ?, ?, ?::timestamptz, ?::timestamptz, 'AUTO', ?::timestamptz, 'AUTO', 'col')""",
                c, equip, cat, start + ":00+09:00", end == null ? null : end + ":00+09:00", start + ":00+09:00");
    }

    private MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder b, String body) {
        return b.with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private static long id(ResultActions r) throws Exception {
        MvcResult m = r.andExpect(status().isOk()).andReturn();
        Object v = JsonPath.read(m.getResponse().getContentAsString(), "$.data");
        return v instanceof Number n ? n.longValue() : ((Number) JsonPath.read(m.getResponse().getContentAsString(), "$.data.woId")).longValue();
    }

    private ResultActions kpi(String from, String to) throws Exception {
        return mvc.perform(get(K).param("plantCd", "P1").param("from", from).param("to", to).with(admin)).andExpect(status().isOk());
    }

    /**
     * EQ-A 3/2: 전날 22:00 ~ 06:00 PRD(자정에서 잘려 6h), 06:00 ~ 07:00 UDT, 07:00 ~ 07:30 UDT(→ 사후 사유로 SDT), 07:30 ~ 08:00 UNKNOWN,
     * 08:00 ~ 09:00 NST, 09:00 ~ 3/3 01:00 PRD(하루 끝에서 잘려 15h). 실적: 36초 공정에서 양품 1000·폐기 50, 역분개 −10 폐기.
     * EQ-B: 수집 없음(실적만). EQ-C(다른 라인): PRD 2h · UDT 2h(고장 1).
     */
    private void fixture() {
        state(comp, eqA, "PRD", "2026-03-01T22:00", D1 + "T06:00");
        state(comp, eqA, "UDT", D1 + "T06:00", D1 + "T07:00");
        state(comp, eqA, "UDT", D1 + "T07:00", D1 + "T07:30");
        state(comp, eqA, "UNKNOWN", D1 + "T07:30", D1 + "T08:00");
        state(comp, eqA, "NST", D1 + "T08:00", D1 + "T09:00");
        state(comp, eqA, "PRD", D1 + "T09:00", D2 + "T01:00");
        accounts.exec(comp, "insert into cms_state_reason (comp_cd, reason_cd, reason_nm, state_cat, planned_yn, created_by, updated_by) values (?, 'PM', '계획 정비', 'SDT', 'Y', 't', 't')", comp);
        long iv = accounts.query(comp, "select state_log_id from cms_equip_state_log where equip_id = ? and start_at = ?::timestamptz", Long.class, eqA,
                D1 + "T07:00:00+09:00");
        accounts.exec(comp, "insert into cms_state_note (comp_cd, state_log_id, reason_id, recorded_by) select ?, ?, reason_id, 't' from cms_state_reason", comp, iv);
        result(woCt, procCt, eqA, 1000, 50, D1 + "T10:00");
        long r2 = result(woCt, procCt, eqA, 0, 10, D1 + "T11:00");
        // 역분개(폐기 10 취소) — MES 정정과 같은 모양: 설비는 원래 실적 그대로(V110 부터, 옛 행은 NULL), 시각 = 정정한 때(다음 날).
        // KPI 에서 원래 실적째 빠져야 한다
        runner.call(comp, () -> {
            long rev = jdbc.queryForObject("""
                    insert into prd_result (comp_cd, wo_id, wo_proc_id, equip_id, good_qty, scrap_qty, rework_qty, repair_qty, unit_cd, work_day,
                                            client_req_id, reason, occurred_at, entry_method, recorded_by, reversal_of_id)
                    select comp_cd, wo_id, wo_proc_id, equip_id, 0, -10, 0, 0, unit_cd, work_day, 'REQ-REVERSE-1', '입력 착오',
                           occurred_at + interval '1 day', 'CORRECT', 'x', result_id
                      from prd_result where result_id = ? returning result_id""", Long.class, r2);
            return jdbc.update("insert into prd_result_defect (comp_cd, result_id, kind, defect_id, qty) select ?, ?, 'SCRAP', defect_id, -10 from qms_defect",
                    comp, rev);
        });
        result(woCt, procCt, eqB, 200, 0, D1 + "T12:00");
        state(comp, eqC, "PRD", D1 + "T10:00", D1 + "T12:00");
        state(comp, eqC, "UDT", D1 + "T12:00", D1 + "T14:00");
    }

    @Test
    void 손검산_하루경계_사후사유_계획에서_빼기_수집없음_라인공장_합계() throws Exception {
        fixture();
        // EQ-A: covered = 6 + 1 + 0.5 + 0.5 + 1 + 15 = 24h, 계획 = 24 − 0.5(UNKNOWN) − 1(NST) − 0.5(SDT, 재분류) = 22h, PRD 21h
        // 가용 = 21/22 = 0.9545, 성능 = 36 × 1050 / 75600 = 0.5000, 품질 = 1000/1050 = 0.9524, OEE = 36000/79200 = 0.4545
        // 고장 1(06:00, 07:00 은 SDT 로 바뀜), MTBF = 21h = 1260분, MTTR = 60분
        kpi(D1, D1)
                .andExpect(jsonPath("$.data.equips[0].code").value("EQ-A"))
                .andExpect(jsonPath("$.data.equips[0].raw.coveredSec").value(86400))
                .andExpect(jsonPath("$.data.equips[0].kpi.plannedSec").value(79200))
                .andExpect(jsonPath("$.data.equips[0].kpi.availability").value(0.9545))
                .andExpect(jsonPath("$.data.equips[0].kpi.performance").value(0.5))
                .andExpect(jsonPath("$.data.equips[0].kpi.quality").value(0.9524))
                .andExpect(jsonPath("$.data.equips[0].kpi.oee").value(0.4545))
                .andExpect(jsonPath("$.data.equips[0].raw.failures").value(1))
                .andExpect(jsonPath("$.data.equips[0].kpi.mtbfMin").value(1260.0))
                .andExpect(jsonPath("$.data.equips[0].kpi.mttrMin").value(60.0))
                // EQ-B: 수집 없음 → 시간 지표 빈칸, 품질은 실적으로 1
                .andExpect(jsonPath("$.data.equips[1].code").value("EQ-B"))
                .andExpect(jsonPath("$.data.equips[1].kpi.collected").value(false))
                .andExpect(jsonPath("$.data.equips[1].kpi.availability").doesNotExist())
                .andExpect(jsonPath("$.data.equips[1].kpi.quality").value(1.0))
                // 라인 L1 = A + B: 품질 = 1200/1250 = 0.96 (평균의 평균 0.9762 아님), 공장: 가용 = (21 + 2)h / (22 + 4)h = 0.8846
                .andExpect(jsonPath("$.data.lines[0].code").value("L1"))
                .andExpect(jsonPath("$.data.lines[0].kpi.quality").value(0.96))
                .andExpect(jsonPath("$.data.plant.kpi.availability").value(0.8846))
                .andExpect(jsonPath("$.data.plant.raw.failures").value(2));
    }

    @Test
    void 실적_정정은_KPI에서_빠진다_설비없는_실적은_설비KPI밖() throws Exception {
        state(comp, eqA, "PRD", D1 + "T00:00", D1 + "T10:00");
        long r = result(woCt, procCt, eqA, 0, 30, D1 + "T05:00");
        kpi(D1, D1).andExpect(jsonPath("$.data.equips[0].raw.totalQty").value(30.0)).andExpect(jsonPath("$.data.equips[0].raw.idealSec").value(1080.0));
        // 실제 POP 정정 경로(PopWorkService.correct) — 역분개 행은 원래 실적의 설비(V110 부터, 옛 행은 NULL)·지금 시각. 3/2 에서도 3/2~3/3 에서도 빠진다
        long term = accounts.query(comp, """
                insert into sys_terminal (comp_cd, terminal_cd, terminal_nm, plant_cd, created_by, updated_by)
                values (?, 'TAB-KPI', 'KPI 단말', 'P1', 't', 't') returning terminal_id""", Long.class, comp);
        TerminalPrincipal t = new TerminalPrincipal(comp, term, "TAB-KPI", "KPI 단말", "P1", null, null, null, "POP");
        PopWorker w = new PopWorker(term, worker, "E1", "작업자", 0L, 0L);
        long rev = runner.call(comp, () -> pop.correct(t, w, r, new CorrectForm("입력 착오")));
        assertThat(accounts.query(comp, "select equip_id from prd_result where result_id = ?", Long.class, rev)).isEqualTo(eqA);
        kpi(D1, D1).andExpect(jsonPath("$.data.equips[0].raw.totalQty").value(0.0))
                .andExpect(jsonPath("$.data.equips[0].kpi.quality").doesNotExist());
        kpi(D1, D2).andExpect(jsonPath("$.data.plant.raw.totalQty").value(0.0)).andExpect(jsonPath("$.data.plant.raw.idealSec").value(0.0));
        // 설비 배정 없이 넣은 실적은 설비 KPI 에 들어가지 않는다(공장 합계에도)
        result(woCt, procCt, 0L, 0, 5, D1 + "T06:00");
        kpi(D1, D1).andExpect(jsonPath("$.data.plant.raw.totalQty").value(0.0));
    }

    @Test
    void 기간합산은_일별합산_표준사이클없는_실적은_성능OEE_빈칸() throws Exception {
        fixture();
        state(comp, eqC, "PRD", D2 + "T00:00", D2 + "T03:00");
        // 3/2 + 3/3 을 따로 본 합 = 이틀 한 번에(EQ-A PRD 3/3 01:00 까지 1h 더)
        long prd1 = ((Number) JsonPath.read(kpi(D1, D1).andReturn().getResponse().getContentAsString(), "$.data.plant.raw.prdSec")).longValue();
        long prd2 = ((Number) JsonPath.read(kpi(D2, D2).andReturn().getResponse().getContentAsString(), "$.data.plant.raw.prdSec")).longValue();
        long prd12 = ((Number) JsonPath.read(kpi(D1, D2).andReturn().getResponse().getContentAsString(), "$.data.plant.raw.prdSec")).longValue();
        assertThat(prd1 + prd2).isEqualTo(prd12);
        assertThat(prd2).isEqualTo(4 * 3600);   // EQ-A 1h + EQ-C 3h
        // 표준 사이클 없는 공정의 실적이 EQ-C 에 생기면 EQ-C 성능·OEE 빈칸, 가용은 그대로
        result(woNoCt, procNoCt, eqC, 10, 0, D1 + "T11:00");
        kpi(D1, D1).andExpect(jsonPath("$.data.equips[2].code").value("EQ-C"))
                .andExpect(jsonPath("$.data.equips[2].raw.ctMissing").value(1))
                .andExpect(jsonPath("$.data.equips[2].kpi.performance").doesNotExist())
                .andExpect(jsonPath("$.data.equips[2].kpi.oee").doesNotExist())
                .andExpect(jsonPath("$.data.equips[2].kpi.availability").value(0.5));
    }

    @Test
    void 열린구간은_지금까지_미래는_세지않음_권한_기간검사() throws Exception {
        // 오늘(공장) 00:00 부터 열린 PRD — 오늘 KPI 의 covered = 지금까지(하루보다 작다)
        String today = accounts.query(comp, "select " + TestAccounts.COMP_TODAY + "::text", String.class);
        state(comp, eqC, "PRD", today + "T00:00", null);
        String body = kpi(today, today).andReturn().getResponse().getContentAsString();
        long covered = ((Number) JsonPath.read(body, "$.data.equips[2].raw.coveredSec")).longValue();
        long period = ((Number) JsonPath.read(body, "$.data.equips[2].raw.periodSec")).longValue();
        assertThat(covered).isEqualTo(period).isLessThanOrEqualTo(86400);
        long viewer = accounts.newUser(comp, "viewer", PW);
        mvc.perform(get(K).param("plantCd", "P1").param("from", today).param("to", today).with(accounts.as(comp, viewer, "viewer")))
                .andExpect(status().isForbidden());
        mvc.perform(get(K).param("plantCd", "P1").param("from", "2025-01-01").param("to", D1).with(admin)).andExpect(status().isBadRequest());
        mvc.perform(get(K).param("from", D1).param("to", D1).with(admin)).andExpect(jsonPath("$.errors[0].field").value("plantCd"));
    }

    @Test
    void A사로_B사_원장이_섞이지_않는다() throws Exception {
        fixture();
        String b = accounts.newComp("KB");
        accounts.addPlant(b, "P1", "B 1공장");
        long bCls = accounts.query(b, "insert into mst_equip_class (comp_cd, class_cd, class_nm, created_by, updated_by) values (?, 'PRESS', 'p', 't', 't') returning class_id",
                Long.class, b);
        long bEq = accounts.query(b, """
                insert into mst_equip (comp_cd, plant_cd, equip_cd, equip_nm, class_id, created_by, updated_by)
                values (?, 'P1', 'EQ-A', 'B 설비', ?, 't', 't') returning equip_id""", Long.class, b, bCls);
        state(b, bEq, "UDT", D1 + "T00:00", D1 + "T23:00");
        // 같은 공장 코드·설비 코드라도 A사 KPI 는 A사 원장만
        kpi(D1, D1).andExpect(jsonPath("$.data.equips.length()").value(3))
                .andExpect(jsonPath("$.data.plant.raw.udtSec").value(3600 + 7200))
                .andExpect(jsonPath("$.data.equips[0].kpi.availability").value(0.9545));
    }

    // ---------- C11 일별 추이·엑셀 ----------

    @Test
    void 일별추이_합은_기간합_설비하나_31일넘음_엑셀은_화면과_같은줄_권한() throws Exception {
        fixture();
        state(comp, eqC, "PRD", D2 + "T00:00", D2 + "T03:00");
        // 공장 합계 3/2·3/3 = 2줄, PRD 합 = 이틀 한 번에(C9 기간 합)
        String body = mvc.perform(get(K + "/daily").param("plantCd", "P1").param("from", D1).param("to", D2).with(admin))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        long d1 = ((Number) JsonPath.read(body, "$.data[0].raw.prdSec")).longValue();
        long d2 = ((Number) JsonPath.read(body, "$.data[1].raw.prdSec")).longValue();
        long both = ((Number) JsonPath.read(kpi(D1, D2).andReturn().getResponse().getContentAsString(), "$.data.plant.raw.prdSec")).longValue();
        assertThat(d1 + d2).isEqualTo(both);
        // 설비 하나: EQ-A 3/2 가용 0.9545(C9 손 검산과 같음), 없는 설비 코드
        mvc.perform(get(K + "/daily").param("plantCd", "P1").param("from", D1).param("to", D1).param("equipCd", "eq-a").with(admin))
                .andExpect(jsonPath("$.data[0].day").value(D1)).andExpect(jsonPath("$.data[0].kpi.availability").value(0.9545));
        mvc.perform(get(K + "/daily").param("plantCd", "P1").param("from", D1).param("to", D1).param("equipCd", "NOPE").with(admin))
                .andExpect(jsonPath("$.errors[0].code").value("NO_SUCH_CODE"));
        mvc.perform(get(K + "/daily").param("plantCd", "P1").param("from", "2026-01-01").param("to", D2).with(admin))
                .andExpect(status().isBadRequest());
        // 엑셀: 공장 합계 → 라인 L1 → EQ-A·EQ-B → 라인 L2 → EQ-C, 가용 % 는 화면과 같은 값
        byte[] x = mvc.perform(post(K + "/export").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"plantCd\":\"P1\",\"from\":\"" + D1 + "\",\"to\":\"" + D1 + "\"}").with(admin))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(x))) {
            Sheet sh = wb.getSheetAt(0);
            assertThat(sh.getLastRowNum()).isEqualTo(6);
            assertThat(sh.getRow(1).getCell(0).getStringCellValue()).isEqualTo("공장 합계");
            assertThat(sh.getRow(2).getCell(1).getStringCellValue()).isEqualTo("L1");
            assertThat(sh.getRow(3).getCell(1).getStringCellValue()).isEqualTo("EQ-A");
            assertThat(sh.getRow(3).getCell(5).getNumericCellValue()).isEqualTo(95.5);
            assertThat(sh.getRow(6).getCell(1).getStringCellValue()).isEqualTo("EQ-C");
        }
        long viewer = accounts.newUser(comp, "viewer2", PW);
        accounts.grant(comp, viewer, "VIEW", "cms-kpi", "SEARCH");
        mvc.perform(post(K + "/export").with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{}").with(accounts.as(comp, viewer, "viewer2")))
                .andExpect(status().isForbidden());
    }
}
