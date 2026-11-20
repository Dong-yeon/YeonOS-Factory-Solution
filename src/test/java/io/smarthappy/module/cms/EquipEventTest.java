package io.smarthappy.module.cms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.jayway.jsonpath.JsonPath;

import io.smarthappy.module.cms.link.AlarmSink;
import io.smarthappy.module.master.workforce.WorkerService;
import io.smarthappy.module.pop.PopAuthService;
import io.smarthappy.module.pop.PopDtos.PopWorker;
import io.smarthappy.module.pop.PopWorkDtos.DefectLine;
import io.smarthappy.module.pop.PopWorkDtos.EventForm;
import io.smarthappy.module.pop.PopWorkDtos.ResultForm;
import io.smarthappy.module.pop.PopWorkService;
import io.smarthappy.module.pop.TerminalPrincipal;
import io.smarthappy.platform.common.db.PgErrors;
import io.smarthappy.platform.tenant.TenantRunner;
import io.smarthappy.support.PostgresTestBase;
import io.smarthappy.support.TestAccounts;
import jakarta.servlet.http.Cookie;

/**
 * 설비 상태·연결·Collector (P2 6단계, docs/25 4.8) — 상태 구간(같은 신호 유지·바뀌면 닫고 열기), 통신 끊김 = UNKNOWN(비가동 아님),
 * 늦게 온·순서 뒤바뀐 이벤트 LATE, 중복 eventId, seq 누락 GAP, 진행 중 공정 파라미터·지시 연결, 에너지 구간, 건별 거부(나머지 저장),
 * 인증(키 없음·틀림·폐기·다시 발급·POP 쿠키와 서로 못 씀·유형 변경), 장치 범위 밖 설비, 동시 이벤트, A사 장치로 B사, 원장 직접 SQL,
 * ALARM → CMS AlarmSink(docs/25 4.17 — 가짜 빈으로 호출 인자 확인).
 */
@AutoConfigureMockMvc
class EquipEventTest extends PostgresTestBase {

    private static final String PW = "Cedar-lantern-41";
    private static final String EXT = "/ext/v1/equip-events";
    private static final String TERM = "/api/production/terminals";

    @Autowired
    MockMvc mvc;

    @Autowired
    TestAccounts accounts;

    @Autowired
    TenantRunner runner;

    @Autowired
    PopWorkService popWork;

    @Autowired
    CmsJobs cmsJobs;

    @Autowired
    EquipInboxPurge inboxPurge;

    /** CMS 알람 계약 — 가짜 빈(호출 인자 확인). C8 구현 빈이 생겨도 이 시험에서는 대신 들어간다 */
    @MockitoBean
    AlarmSink alarmSink;

    String comp;
    RequestPostProcessor admin;
    long line1;
    long cell1;
    long eq1;
    long devId;
    String key;
    long spot;
    long woId;
    long popTerminal;
    long workerId;
    /** 시험 기준 시각 — 지금보다 2시간 앞(미래 허용 오차 밖으로 나가지 않게) */
    Instant t0;

    @BeforeEach
    void setUp() throws Exception {
        comp = accounts.newComp("EQ");
        long userId = accounts.newUser(comp, "admin1", PW);
        accounts.grant(comp, userId, "ADM", "sys-terminal", "SEARCH", "SAVE");
        accounts.grant(comp, userId, "ADM", "prd-wo", "SEARCH", "SAVE");
        accounts.grant(comp, userId, "ADM", "cms-state", "SEARCH");
        admin = accounts.as(comp, userId, "admin1");
        accounts.addPlant(comp, "P1", "1공장");
        // 어제 10:00(회사 시간대) — 늘 과거이고, 시험 이벤트(t0 ~ t0+62분)와 타임라인 조회일(t0 의 날짜)이 같은 날에 든다.
        // now − 2h 였을 때는 KST 00:00~02:30 사이에 t0 가 전날 23시대가 되어 이후 이벤트가 다음 날로 넘어갔다
        t0 = java.time.LocalDate.now(ZoneId.of("Asia/Seoul")).minusDays(1).atTime(10, 0).atZone(ZoneId.of("Asia/Seoul")).toInstant();
        long area = node(null, "AREA", "A1");
        line1 = node(area, "LINE", "L1");
        cell1 = node(line1, "CELL", "C1");
        long line2 = node(area, "LINE", "L2");
        accounts.exec(comp, "insert into mst_equip_class (comp_cd, class_cd, class_nm, created_by, updated_by) values (?, 'WELDER', '용접기', 't', 't')",
                comp);
        eq1 = equip("EQ1", cell1, "Y");
        equip("EQ2", line2, "Y");      // 같은 공장, 장치 작업장(L1) 밖
        equip("EQ9", cell1, "N");      // 사용 중지
        String[] dev = collector("COL-01", line1);
        devId = Long.parseLong(dev[0]);
        key = dev[1];
        accounts.exec(comp, """
                insert into cms_state_reason (comp_cd, reason_cd, reason_nm, state_cat, created_by, updated_by)
                values (?, 'JAM', '자재 걸림', 'UDT', 't', 't'), (?, 'PM', '예방 보전', 'SDT', 't', 't')""", comp, comp);
    }

    // ---------- 도우미 ----------

    private long node(Long parent, String type, String cd) {
        return accounts.query(comp, """
                insert into mst_equip_hier (comp_cd, plant_cd, parent_id, node_type, node_cd, node_nm, created_by, updated_by)
                values (?, 'P1', ?, ?, ?, ?, 't', 't') returning node_id""", Long.class, comp, parent, type, cd, cd + " 작업장");
    }

    private long equip(String cd, long nodeId, String useYn) {
        return accounts.query(comp, """
                insert into mst_equip (comp_cd, plant_cd, equip_cd, equip_nm, class_id, node_id, use_yn, created_by, updated_by)
                values (?, 'P1', ?, ?, (select class_id from mst_equip_class where class_cd = 'WELDER'), ?, ?, 't', 't')
                returning equip_id""", Long.class, comp, cd, cd + " 설비", nodeId, useYn);
    }

    private MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder b, String body) {
        return b.with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body);
    }

    /** 수집 장치 등록 → [id, API 키] */
    private String[] collector(String cd, Long nodeId) throws Exception {
        MvcResult m = mvc.perform(json(post(TERM), "{\"terminalCd\":\"" + cd + "\",\"terminalNm\":\"" + cd
                + "\",\"plantCd\":\"P1\",\"terminalType\":\"collector\"" + (nodeId == null ? "" : ",\"nodeId\":" + nodeId) + "}").with(admin))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.expiresAt").doesNotExist()).andReturn();
        String body = m.getResponse().getContentAsString();
        return new String[] {String.valueOf(((Number) JsonPath.read(body, "$.data.terminalId")).longValue()),
                JsonPath.read(body, "$.data.code")};
    }

    private ResultActions send(String apiKey, String body) throws Exception {
        MockHttpServletRequestBuilder b = post(EXT).contentType(MediaType.APPLICATION_JSON).content(body);
        if (apiKey != null) {
            b.header("Authorization", "Bearer " + apiKey);
        }
        return mvc.perform(b);
    }

    private ResultActions send(String body) throws Exception {
        return send(key, body);
    }

    private static String m(String name, String value) {
        return "{\"name\":\"" + name + "\",\"value\":\"" + value + "\"}";
    }

    private static String ev(String id, String type, String equipCd, Instant at, Long seq, String... metrics) {
        return "{\"eventId\":\"" + id + "\",\"type\":\"" + type + "\"" + (equipCd == null ? "" : ",\"equipCd\":\"" + equipCd + "\"")
                + ",\"occurredAt\":\"" + at + "\"" + (seq == null ? "" : ",\"seq\":" + seq)
                + ",\"metrics\":[" + String.join(",", metrics) + "]}";
    }

    private static String id() {
        return "ev-" + UUID.randomUUID();
    }

    private Instant at(int minutes) {
        return t0.plus(minutes, ChronoUnit.MINUTES);
    }

    private ResultActions state(String equipCd, int minute, String cat, String reason) throws Exception {
        return send(ev(id(), "STATE", equipCd, at(minute), null, m("state", cat) + (reason == null ? "" : "," + m("reason", reason))));
    }

    private ResultActions conn(String equipCd, int minute, boolean online) throws Exception {
        return send(ev(id(), "CONN", equipCd, at(minute), null, m("online", String.valueOf(online))));
    }

    /** 설비 구간 요약 "분류[@시작분~끝분]" — 열린 구간은 끝 '…' */
    private List<String> intervals(long equipId) {
        String s = accounts.query(comp, """
                select coalesce(string_agg(state_cat || '@' || (extract(epoch from start_at - ?::timestamptz)::bigint / 60)::text || '~'
                       || coalesce((extract(epoch from end_at - ?::timestamptz)::bigint / 60)::text, '…'), ',' order by start_at), '')
                  from cms_equip_state_log where equip_id = ?""", String.class, t0.toString(), t0.toString(), equipId);
        return s.isEmpty() ? List.of() : List.of(s.split(","));
    }

    private int count(String sql, Object... args) {
        return accounts.query(comp, sql, Integer.class, args);
    }

    /** 작업지시 1건(공정 1단계, 파라미터 WELD_CURRENT 8.5~9.5) 발행 + EQ1 배정 */
    private void workOrder() throws Exception {
        String today = accounts.query(comp, "select " + TestAccounts.COMP_TODAY + "::text", String.class);
        accounts.exec(comp, """
                insert into mst_item (comp_cd, item_cd, item_nm, item_type, proc_type, unit_cd, created_by, updated_by)
                values (?, 'FG', 'FG 품목', 'FG', 'MAKE', 'EA', 't', 't'), (?, 'RM', 'RM 품목', 'FG', 'MAKE', 'KG', 't', 't')""", comp, comp);
        accounts.exec(comp, """
                insert into mst_proc (comp_cd, proc_cd, proc_nm, proc_cat, created_by, updated_by)
                values (?, 'SPOT', '스폿 용접', 'WELD', 't', 't')""", comp);
        long rid = accounts.query(comp, """
                insert into mst_routing (comp_cd, item_id, plant_cd, rev, valid_from, created_by, updated_by)
                values (?, (select item_id from mst_item where item_cd = 'FG'), 'P1', 'A0', (now() at time zone 'Asia/Seoul')::date - 30, 't', 't')
                returning routing_id""", Long.class, comp);
        long s1 = accounts.query(comp, """
                insert into mst_routing_step (comp_cd, routing_id, op_seq, proc_id, node_id, std_ct_sec, created_by, updated_by)
                values (?, ?, 10, (select proc_id from mst_proc where proc_cd = 'SPOT'), ?, 30, 't', 't') returning step_id""",
                Long.class, comp, rid, cell1);
        accounts.exec(comp, """
                insert into mst_routing_param (comp_cd, step_id, param_cd, param_nm, unit_nm, target_val, lsl, usl, sort_no,
                                               created_by, updated_by)
                values (?, ?, 'WELD_CURRENT', '용접 전류', 'kA', 9.0, 8.5, 9.5, 10, 't', 't')""", comp, s1);
        accounts.exec(comp, "update mst_routing set status = 'RELEASED', released_at = now(), released_by = 't' where routing_id = ?", rid);
        long bom = accounts.query(comp, """
                insert into mst_bom (comp_cd, item_id, rev, valid_from, base_qty, created_by, updated_by)
                values (?, (select item_id from mst_item where item_cd = 'FG'), 'A0', (now() at time zone 'Asia/Seoul')::date - 30, 1, 't', 't')
                returning bom_id""", Long.class, comp);
        accounts.exec(comp, """
                insert into mst_bom_line (comp_cd, bom_id, line_no, item_id, qty, unit_cd, line_type, issue_method, created_by, updated_by)
                values (?, ?, 10, (select item_id from mst_item where item_cd = 'RM'), 1, 'KG', 'NORMAL', 'BACKFLUSH', 't', 't')""",
                comp, bom);
        accounts.exec(comp, "update mst_bom set status = 'RELEASED', released_at = now(), released_by = 't' where bom_id = ?", bom);
        MvcResult r = mvc.perform(json(post("/api/production/wos"),
                "{\"plantCd\":\"P1\",\"itemCd\":\"FG\",\"planQty\":\"100\",\"planStart\":\"" + today + "\"}").with(admin))
                .andExpect(status().isOk()).andReturn();
        woId = ((Number) JsonPath.read(r.getResponse().getContentAsString(), "$.data")).longValue();
        mvc.perform(json(post("/api/production/wos/" + woId + "/release"), "{\"rowVer\":0}").with(admin)).andExpect(status().isOk());
        spot = accounts.query(comp, "select wo_proc_id from prd_wo_proc where wo_id = ?", Long.class, woId);
        accounts.exec(comp, "update prd_wo_proc set equip_id = ? where wo_proc_id = ?", eq1, spot);
        // POP 단말·작업자(착수에 필요 — 착수 자체는 서비스로)
        MvcResult t = mvc.perform(json(post(TERM), "{\"terminalCd\":\"TAB-01\",\"terminalNm\":\"TAB\",\"plantCd\":\"P1\"}").with(admin))
                .andExpect(status().isOk()).andReturn();
        popTerminal = ((Number) JsonPath.read(t.getResponse().getContentAsString(), "$.data.terminalId")).longValue();
        workerId = accounts.query(comp, """
                insert into mst_worker (comp_cd, worker_cd, worker_nm, plant_cd, badge_hash, created_by, updated_by)
                values (?, 'E1', '홍작업', 'P1', ?, 't', 't') returning worker_id""", Long.class, comp, WorkerService.badgeHash(comp, "BADGE-0001"));
    }

    private void popEvent(String eventCd) {
        TerminalPrincipal t = new TerminalPrincipal(comp, popTerminal, "TAB-01", "TAB", "P1", null, null, null, "POP");
        PopWorker w = new PopWorker(popTerminal, workerId, "E1", "홍작업", 0, 0);
        runner.call(comp, () -> popWork.event(t, w, spot, eventCd, new EventForm(UUID.randomUUID().toString(), null, true)));
    }

    // ---------- 흐름 ----------

    @Test
    void 상태_구간_끊김은_UNKNOWN_타임라인() throws Exception {
        state("eq1", 0, "prd", null).andExpect(status().isAccepted()).andExpect(jsonPath("$.data.accepted").value(1))
                .andExpect(jsonPath("$.data.rejected.length()").value(0));
        // 같은 분류 신호 반복 → 구간 그대로
        state("EQ1", 5, "PRD", null).andExpect(jsonPath("$.data.accepted").value(1));
        assertThat(intervals(eq1)).containsExactly("PRD@0~…");
        // 사유 있는 고장 → 닫고 새로. 사유의 분류와 다르면 REASON, 없는 분류 STATE
        state("EQ1", 10, "SBY", "JAM").andExpect(jsonPath("$.data.rejected[0].code").value("REASON"));
        state("EQ1", 10, "BROKEN", null).andExpect(jsonPath("$.data.rejected[0].code").value("STATE"));
        state("EQ1", 10, "UDT", "jam").andExpect(jsonPath("$.data.accepted").value(1));
        // 같은 분류라도 사유가 다르면 새 구간
        state("EQ1", 15, "UDT", null).andExpect(jsonPath("$.data.accepted").value(1));
        // 통신 끊김 → UNKNOWN(비가동 아님), 끊긴 중 OFFLINE 반복은 그대로, 다시 연결 → UNKNOWN 닫힘(다음 STATE 까지 구간 없음)
        conn("EQ1", 20, false).andExpect(jsonPath("$.data.accepted").value(1));
        conn("EQ1", 22, false).andExpect(jsonPath("$.data.accepted").value(1));
        conn("EQ1", 30, true).andExpect(jsonPath("$.data.accepted").value(1));
        state("EQ1", 31, "PRD", null).andExpect(jsonPath("$.data.accepted").value(1));
        assertThat(intervals(eq1)).containsExactly("PRD@0~10", "UDT@10~15", "UDT@15~20", "UNKNOWN@20~30", "PRD@31~…");
        assertThat(count("select count(*) from cms_equip_state_log s join cms_state_reason r on r.reason_id = s.reason_id"
                + " where s.equip_id = ? and r.reason_cd = 'JAM'", eq1)).isEqualTo(1);
        // 원장 칸: 기록 = 장치(d{id}:{코드}), 입력 AUTO, 출처 AUTO
        assertThat(count("select count(*) from cms_equip_state_log where equip_id = ? and recorded_by = ? and entry_method = 'AUTO'"
                + " and source = 'AUTO' and terminal_id = ?", eq1, "d" + devId + ":COL-01", devId)).isEqualTo(5);
        assertThat(count("select count(*) from cms_equip_conn_log where equip_id = ? and conn = 'OFFLINE'", eq1)).isEqualTo(2);
        assertThat(count("select count(*) from cms_equip_conn_log where equip_id = ? and conn = 'ONLINE'", eq1)).isEqualTo(1);

        // 타임라인(사무): 공장 현지 하루 — 설비·구간·연결 로그
        String day = t0.atZone(ZoneId.of("Asia/Seoul")).toLocalDate().toString();
        mvc.perform(get("/api/cms/state").param("plantCd", "P1").param("date", day).with(admin)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.zone").value("Asia/Seoul"))
                .andExpect(jsonPath("$.data.equips[*].equipCd").value(Matchers.contains("EQ1", "EQ2")))
                .andExpect(jsonPath("$.data.intervals[*].stateCat").value(Matchers.hasItem("UNKNOWN")))
                .andExpect(jsonPath("$.data.intervals[?(@.reasonCd == 'JAM')].reasonNm").value(Matchers.contains("자재 걸림")))
                .andExpect(jsonPath("$.data.conns[*].conn").value(Matchers.hasItems("ONLINE", "OFFLINE")));
        mvc.perform(get("/api/cms/state").param("plantCd", "P1").param("date", "2026-13-01").with(admin))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("date"));
        mvc.perform(get("/api/cms/state").with(admin)).andExpect(jsonPath("$.errors[0].field").value("plantCd"));
        mvc.perform(get("/api/cms/state/plants").with(admin)).andExpect(jsonPath("$.data[0].plantCd").value("P1"));
    }

    @Test
    void 늦게_온_이벤트_LATE_순서_뒤바뀜_중복() throws Exception {
        state("EQ1", 10, "PRD", null).andExpect(status().isAccepted());
        state("EQ1", 20, "SBY", null).andExpect(status().isAccepted());
        // 열린 구간(SBY@20) 시작보다 이른 이벤트 — 다른 분류든 같은 분류든 과거를 다시 쓰지 않는다
        state("EQ1", 15, "UDT", null).andExpect(jsonPath("$.data.rejected[0].code").value("LATE"));
        state("EQ1", 15, "SBY", null).andExpect(jsonPath("$.data.rejected[0].code").value("LATE"));
        state("EQ1", 20, "UDT", null).andExpect(jsonPath("$.data.rejected[0].code").value("LATE"));   // 같은 시각도
        assertThat(intervals(eq1)).containsExactly("PRD@10~20", "SBY@20~…");
        // 끊김 → 다시 연결로 열린 구간이 없을 때: 마지막 끝보다 이르면 LATE
        conn("EQ1", 30, false);
        conn("EQ1", 40, true);
        state("EQ1", 35, "PRD", null).andExpect(jsonPath("$.data.rejected[0].code").value("LATE"));
        state("EQ1", 40, "PRD", null).andExpect(jsonPath("$.data.accepted").value(1));
        assertThat(intervals(eq1)).containsExactly("PRD@10~20", "SBY@20~30", "UNKNOWN@30~40", "PRD@40~…");

        // 중복: 같은 eventId 재전송 → duplicates, 원장 그대로. 거부(롤백)된 eventId 는 기록이 없어 고쳐서 다시 보낼 수 있다
        String same = id();
        String body = ev(same, "STATE", "EQ1", at(50), null, m("state", "SBY"));
        send(body).andExpect(jsonPath("$.data.accepted").value(1));
        send(body).andExpect(jsonPath("$.data.accepted").value(0)).andExpect(jsonPath("$.data.duplicates").value(1));
        String late = id();
        send(ev(late, "STATE", "EQ1", at(45), null, m("state", "UDT"))).andExpect(jsonPath("$.data.rejected[0].eventId").value(late));
        send(ev(late, "STATE", "EQ1", at(55), null, m("state", "UDT"))).andExpect(jsonPath("$.data.accepted").value(1));
        assertThat(count("select count(*) from cms_equip_state_log where equip_id = ?", eq1)).isEqualTo(6);

        // 배열: 한 건이 틀려도 나머지는 저장(건별 트랜잭션), 배열 안 같은 eventId 두 번 → 하나는 중복
        String a = id();
        send("[" + ev(a, "STATE", "EQ1", at(60), null, m("state", "PRD")) + ","
                + ev(id(), "STATE", "NOPE", at(61), null, m("state", "PRD")) + ","
                + ev(a, "STATE", "EQ1", at(60), null, m("state", "PRD")) + ","
                + ev(id(), "STATE", "EQ1", at(62), null, m("state", "SBY")) + ",\"x\"]")
                .andExpect(jsonPath("$.data.accepted").value(2)).andExpect(jsonPath("$.data.duplicates").value(1))
                .andExpect(jsonPath("$.data.rejected[*].code").value(Matchers.contains("EQUIP", "FORMAT")));
        assertThat(intervals(eq1)).endsWith("PRD@60~62", "SBY@62~…");
    }

    @Test
    void seq_누락은_GAP_재전송은_그대로() throws Exception {
        for (long s : new long[] {1, 2, 5, 3, 5, 9}) {
            send(ev(id(), "COUNT", "EQ1", at((int) s), s, m("count", "1"))).andExpect(jsonPath("$.data.accepted").value(1));
        }
        assertThat(accounts.query(comp, """
                select string_agg(seq_from || '-' || seq_to, ',' order by seq_from) from cms_equip_conn_log
                 where terminal_id = ? and conn = 'GAP' and equip_id is null""", String.class, devId)).isEqualTo("3-4,6-8");
        assertThat(count("select last_seq from cms_collector_seq where terminal_id = ?", devId)).isEqualTo(9);
        // 거부된 이벤트도 도착은 했다 — seq 는 올라간다(누락 아님)
        send(ev(id(), "STATE", "NOPE", at(10), 10L, m("state", "PRD"))).andExpect(jsonPath("$.data.rejected[0].code").value("EQUIP"));
        send(ev(id(), "COUNT", "EQ1", at(11), 11L, m("count", "1")));
        assertThat(count("select count(*) from cms_equip_conn_log where conn = 'GAP'")).isEqualTo(2);
        // COUNT 는 원장(cms_equip_count, docs/25 4.27), ALARM 은 계약으로(멱등 기록 cms_equip_inbox 는 둘 다)
        send(ev(id(), "ALARM", "EQ1", at(12), null, m("code", "E-100"))).andExpect(jsonPath("$.data.accepted").value(1));
        assertThat(count("select count(*) from cms_equip_inbox")).isEqualTo(8);
    }

    @Test
    void COUNT_는_원장에_쌓이고_POP_실적_제안은_마지막_실적_뒤_합_설비에_공정이_둘이면_제안_없음() throws Exception {
        workOrder();
        TerminalPrincipal t = new TerminalPrincipal(comp, popTerminal, "TAB-01", "TAB", "P1", null, null, null, "POP");
        PopWorker w = new PopWorker(popTerminal, workerId, "E1", "홍작업", 0, 0);
        // 착수 전 COUNT 는 원장에는 쌓이지만 제안에는 들지 않는다(since = 착수)
        send(ev(id(), "COUNT", "EQ1", Instant.now().minusSeconds(60), null, m("goodCount", "99"))).andExpect(jsonPath("$.data.accepted").value(1));
        assertThat(runner.call(comp, () -> popWork.view(t, w, spot)).countSuggest()).isNull();          // 착수 전(WAIT) — 제안 없음
        popEvent("START");
        send(ev(id(), "COUNT", "EQ1", Instant.now(), null, m("goodCount", "7"), m("scrapCount", "1"))).andExpect(jsonPath("$.data.accepted").value(1));
        send(ev(id(), "COUNT", "EQ1", Instant.now(), null, m("count", "3"))).andExpect(jsonPath("$.data.accepted").value(1));   // count = goodCount
        assertThat(count("select count(*) from cms_equip_count where equip_id = ?", eq1)).isEqualTo(4);
        var cs = runner.call(comp, () -> popWork.view(t, w, spot)).countSuggest();
        assertThat(cs.getGoodQty()).isEqualTo("10");
        assertThat(cs.getScrapQty()).isEqualTo("1");
        // 제안 그대로 확정 → entry_method AUTO, 다음 제안은 그 실적 뒤부터
        accounts.exec(comp, """
                insert into qms_defect (comp_cd, defect_cd, defect_nm, cat_cd, created_by, updated_by)
                values (?, 'D-PIT', '기공', 'WELD', 't', 't')""", comp);
        String defect = "D-PIT";
        runner.call(comp, () -> popWork.result(t, w, spot, new ResultForm(UUID.randomUUID().toString(), null, "10", "1", null, null,
                List.of(new DefectLine("SCRAP", defect, "1")), List.of(), true)));
        assertThat(accounts.query(comp, "select entry_method from prd_result where wo_proc_id = ?", String.class, spot)).isEqualTo("AUTO");
        assertThat(runner.call(comp, () -> popWork.view(t, w, spot)).countSuggest().getGoodQty()).isEqualTo("0");
        send(ev(id(), "COUNT", "EQ1", Instant.now(), null, m("goodCount", "4"))).andExpect(jsonPath("$.data.accepted").value(1));
        assertThat(runner.call(comp, () -> popWork.view(t, w, spot)).countSuggest().getGoodQty()).isEqualTo("4");
        // 사람이 고쳐 보내면(fromCount 없음) KEY
        runner.call(comp, () -> popWork.result(t, w, spot, new ResultForm(UUID.randomUUID().toString(), null, "5", null, null, null,
                List.of(), List.of(), null)));
        assertThat(accounts.query(comp, "select string_agg(entry_method, ',' order by result_id) from prd_result where wo_proc_id = ?",
                String.class, spot)).isEqualTo("AUTO,KEY");
        // 같은 설비에 진행 중 공정이 하나 더 → 어느 공정 몫인지 모르니 제안 없음
        String today = accounts.query(comp, "select " + TestAccounts.COMP_TODAY + "::text", String.class);
        MvcResult r2 = mvc.perform(json(post("/api/production/wos"),
                "{\"plantCd\":\"P1\",\"itemCd\":\"FG\",\"planQty\":\"10\",\"planStart\":\"" + today + "\"}").with(admin))
                .andExpect(status().isOk()).andReturn();
        long wo2 = ((Number) JsonPath.read(r2.getResponse().getContentAsString(), "$.data")).longValue();
        mvc.perform(json(post("/api/production/wos/" + wo2 + "/release"), "{\"rowVer\":0}").with(admin)).andExpect(status().isOk());
        long spot2 = accounts.query(comp, "select wo_proc_id from prd_wo_proc where wo_id = ?", Long.class, wo2);
        accounts.exec(comp, "update prd_wo_proc set equip_id = ? where wo_proc_id = ?", eq1, spot2);
        runner.call(comp, () -> popWork.event(t, w, spot2, "START", new EventForm(UUID.randomUUID().toString(), null, true)));
        assertThat(runner.call(comp, () -> popWork.view(t, w, spot)).countSuggest()).isNull();
        // 형식: 음수·이름 없음 → METRIC(원장 그대로)
        send(ev(id(), "COUNT", "EQ1", Instant.now(), null, m("goodCount", "-1"))).andExpect(jsonPath("$.data.rejected[0].code").value("METRIC"));
        send(ev(id(), "COUNT", "EQ1", Instant.now(), null, m("speed", "3"))).andExpect(jsonPath("$.data.rejected[0].code").value("METRIC"));
        assertThat(count("select count(*) from cms_equip_count where equip_id = ?", eq1)).isEqualTo(5);
        // 직접 SQL: 고치기·지우기 금지, 다른 회사 설비로 넣기 금지
        assertThat(catchThrowable(() -> accounts.exec(comp, "update cms_equip_count set qty = 0 where equip_id = ?", eq1))).isNotNull();
        assertThat(catchThrowable(() -> accounts.exec(comp, "delete from cms_equip_count where equip_id = ?", eq1))).isNotNull();
        String other = accounts.newComp("EQB");
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(other, """
                insert into cms_equip_count (comp_cd, equip_id, metric_cd, qty, occurred_at, entry_method, recorded_by)
                values (?, ?, 'GOOD', 1, now(), 'AUTO', 't')""", other, eq1)))).contains("fk_cms_equip_count_ctx_comp");
        assertThat(accounts.query(other, "select count(*) from cms_equip_count", Integer.class)).isZero();
    }

    @Test
    void 파라미터는_진행_중_공정만_지시가_구간에_남는다_에너지_구간() throws Exception {
        workOrder();
        send(ev(id(), "PARAM", "EQ1", at(0), null, m("WELD_CURRENT", "9.1")))
                .andExpect(jsonPath("$.data.rejected[0].code").value("NO_RUNNING_PROC"));
        popEvent("START");
        // 스냅샷 파라미터만(이름 대소문자 무시), 판정 = 상하한, 모르는 메트릭은 건너뜀, 하나도 없으면 NO_PARAM
        send(ev(id(), "PARAM", "EQ1", at(1), null, "{\"name\":\"weld_current\",\"value\":9.2}", m("PRESSURE", "3")))
                .andExpect(jsonPath("$.data.accepted").value(1));
        send(ev(id(), "PARAM", "EQ1", at(2), null, m("WELD_CURRENT", "9.6"))).andExpect(jsonPath("$.data.accepted").value(1));
        send(ev(id(), "PARAM", "EQ1", at(3), null, m("PRESSURE", "3"))).andExpect(jsonPath("$.data.rejected[0].code").value("NO_PARAM"));
        send(ev(id(), "PARAM", "EQ1", at(3), null, m("WELD_CURRENT", "1e3"))).andExpect(jsonPath("$.data.rejected[0].code").value("METRIC"));
        assertThat(accounts.query(comp, """
                select string_agg(trim_scale(value)::text || ':' || judge, ',' order by occurred_at) from prd_param_actual
                 where wo_proc_id = ? and source = 'COLLECTOR' and entry_method = 'AUTO' and recorded_by like 'd%:COL-01'
                   and terminal_id = ? and equip_id = ? and performed_by is null""", String.class, spot, devId, eq1)).isEqualTo("9.2:OK,9.6:NG");
        // 진행 중 지시가 상태 구간에 남는다
        state("EQ1", 4, "PRD", null).andExpect(jsonPath("$.data.accepted").value(1));
        assertThat(count("select count(*) from cms_equip_state_log where equip_id = ? and wo_id = ?", eq1, woId)).isEqualTo(1);
        mvc.perform(get("/api/cms/state").param("plantCd", "P1").param("date", t0.atZone(ZoneId.of("Asia/Seoul")).toLocalDate().toString())
                .with(admin)).andExpect(jsonPath("$.data.intervals[0].woNo").isNotEmpty());
        popEvent("END");
        send(ev(id(), "PARAM", "EQ1", at(5), null, m("WELD_CURRENT", "9"))).andExpect(jsonPath("$.data.rejected[0].code").value("NO_RUNNING_PROC"));

        // 에너지: 계측기 구간 사용량(설비 없어도 됨), 같은 계측기 겹침 OVERLAP, 다른 계측기는 됨
        String meter = "{\"eventId\":\"%s\",\"type\":\"METER_READING\",\"periodStart\":\"%s\",\"occurredAt\":\"%s\",\"metrics\":[%s]}";
        send(String.format(meter, id(), at(0), at(15), "{\"name\":\"kwh-l1\",\"value\":12.5,\"unit\":\"kwh\"}"))
                .andExpect(jsonPath("$.data.accepted").value(1));
        send(String.format(meter, id(), at(10), at(20), "{\"name\":\"KWH-L1\",\"value\":1,\"unit\":\"KWH\"}"))
                .andExpect(jsonPath("$.data.rejected[0].code").value("OVERLAP"));
        send(String.format(meter, id(), at(15), at(30), "{\"name\":\"KWH-L1\",\"value\":3,\"unit\":\"KWH\"},{\"name\":\"AIR-L1\",\"value\":2,\"unit\":\"M3\"}"))
                .andExpect(jsonPath("$.data.accepted").value(1));
        send(String.format(meter, id(), at(30), at(30), "{\"name\":\"KWH-L1\",\"value\":1,\"unit\":\"KWH\"}"))
                .andExpect(jsonPath("$.data.rejected[0].code").value("PERIOD"));
        send(String.format(meter, id(), at(30), at(45), "{\"name\":\"KWH-L1\",\"value\":0,\"unit\":\"KWH\"}"))
                .andExpect(jsonPath("$.data.rejected[0].code").value("METRIC"));
        assertThat(accounts.query(comp, """
                select string_agg(meter_cd || '=' || trim_scale(qty)::text || unit_cd, ',' order by meter_cd, period_start)
                  from cms_energy_reading where reading_src = 'COLLECTOR' and plant_cd = 'P1'""", String.class))
                .isEqualTo("AIR-L1=2M3,KWH-L1=12.5KWH,KWH-L1=3KWH");
    }

    // ---------- 검증·인증·범위 ----------

    @Test
    void 이벤트_검증() throws Exception {
        send("{").andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("BAD_REQUEST"));
        send("\"x\"").andExpect(status().isBadRequest());
        send("[]").andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("TOO_MANY_ROWS"));
        List<String> many = new ArrayList<>();
        for (int i = 0; i <= EquipEventController.MAX_EVENTS; i++) {
            many.add(ev(id(), "COUNT", "EQ1", at(0), null, m("count", "1")));
        }
        send("[" + String.join(",", many) + "]").andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("TOO_MANY_ROWS"));
        send("[" + ev("short", "STATE", "EQ1", at(0), null, m("state", "PRD")) + ","
                + ev(id(), "PING", "EQ1", at(0), null) + ","
                + ev(id(), "STATE", "EQ1", Instant.now().plus(1, ChronoUnit.HOURS), null, m("state", "PRD")) + ","
                + "{\"eventId\":\"" + id() + "\",\"type\":\"STATE\",\"equipCd\":\"EQ1\",\"occurredAt\":\"2026-10-20 10:00\"}" + ","
                + ev(id(), "STATE", "EQ1", at(0), null) + ","
                + ev(id(), "CONN", "EQ1", at(0), null, m("online", "maybe")) + "]")
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.rejected[*].code").value(Matchers.contains("EVENT_ID", "TYPE", "FUTURE", "OCCURRED_AT", "STATE", "CONN")));
        // 장치 범위: 작업장(L1) 밖 설비·사용 중지 설비·없는 설비, 설비 없는 STATE
        send("[" + ev(id(), "STATE", "EQ2", at(0), null, m("state", "PRD")) + ","
                + ev(id(), "STATE", "EQ9", at(0), null, m("state", "PRD")) + ","
                + ev(id(), "STATE", "EQX", at(0), null, m("state", "PRD")) + ","
                + ev(id(), "STATE", null, at(0), null, m("state", "PRD")) + "]")
                .andExpect(jsonPath("$.data.rejected[*].code").value(Matchers.contains("EQUIP", "EQUIP", "EQUIP", "EQUIP")));
        assertThat(count("select count(*) from cms_equip_state_log")).isZero();
        assertThat(count("select count(*) from cms_equip_inbox")).isZero();
        // 공장 전체 장치(작업장 없음)는 EQ2 도 된다
        String[] whole = collector("COL-ALL", null);
        send(whole[1], ev(id(), "STATE", "EQ2", at(0), null, m("state", "PRD"))).andExpect(jsonPath("$.data.accepted").value(1));
    }

    @Test
    void 인증_키_없음_틀림_폐기_다시_발급_POP_쿠키와_분리() throws Exception {
        String one = ev(id(), "COUNT", "EQ1", at(0), null, m("count", "1"));
        send(null, one).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
        send("garbage", one).andExpect(status().isUnauthorized());
        send(key.substring(0, key.length() - 1) + (key.endsWith("A") ? "B" : "A"), one).andExpect(status().isUnauthorized());
        mvc.perform(post(EXT).contentType(MediaType.APPLICATION_JSON).content(one).header("Authorization", "Basic " + key))
                .andExpect(status().isUnauthorized());
        // 사람 로그인 세션·POP 쿠키로는 /ext 에 못 들어오고, 수집 장치 키를 POP 쿠키로 써도 못 들어간다
        mvc.perform(post(EXT).contentType(MediaType.APPLICATION_JSON).content(one).with(admin).with(csrf())).andExpect(status().isUnauthorized());
        mvc.perform(post(EXT).contentType(MediaType.APPLICATION_JSON).content(one).cookie(new Cookie(PopAuthService.COOKIE, key)))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/pop/home").cookie(new Cookie(PopAuthService.COOKIE, key))).andExpect(status().isUnauthorized());
        // 응답에 키·세션 없음, 보안 헤더
        send(one).andExpect(status().isAccepted()).andExpect(r -> {
            assertThat(r.getResponse().getHeader("Set-Cookie")).isNull();
            assertThat(r.getResponse().getHeader("Content-Security-Policy")).contains("script-src 'self'");
        });
        // 목록: 유형 COLLECTOR, 키는 응답에 없음
        mvc.perform(get(TERM).with(admin)).andExpect(jsonPath("$.data[?(@.terminalCd == 'COL-01')].terminalType").value(Matchers.contains("COLLECTOR")))
                .andExpect(r -> assertThat(r.getResponse().getContentAsString()).doesNotContain(key.substring(key.lastIndexOf('.') + 1)));
        // 다시 발급: 옛 키 즉시 무효, 새 키는 됨
        int rowVer = accounts.query(comp, "select row_ver from sys_terminal where terminal_id = ?", Integer.class, devId);
        MvcResult re = mvc.perform(json(post(TERM + "/" + devId + "/reissue"), "{\"rowVer\":" + rowVer + "}").with(admin))
                .andExpect(status().isOk()).andReturn();
        String newKey = JsonPath.read(re.getResponse().getContentAsString(), "$.data.code");
        assertThat(newKey).startsWith(comp + "." + devId + ".").isNotEqualTo(key);
        send(one).andExpect(status().isUnauthorized());
        send(newKey, ev(id(), "COUNT", "EQ1", at(1), null, m("count", "1"))).andExpect(status().isAccepted());
        // 유형은 수집 장치 ↔ POP 로 바꿀 수 없다(화면·직접 SQL)
        rowVer = accounts.query(comp, "select row_ver from sys_terminal where terminal_id = ?", Integer.class, devId);
        mvc.perform(json(put(TERM + "/" + devId), "{\"terminalNm\":\"COL\",\"plantCd\":\"P1\",\"terminalType\":\"POP\",\"rowVer\":" + rowVer + "}")
                .with(admin)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("terminalType"))
                .andExpect(jsonPath("$.errors[0].code").value("FIXED"));
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(comp,
                "update sys_terminal set terminal_type = 'POP' where terminal_id = ?", devId)))).contains("ck_sys_terminal_collector_fixed");
        // 폐기: 즉시 끊김, 다시 발급 불가
        mvc.perform(json(post(TERM + "/" + devId + "/revoke"), "{\"reason\":\"교체\",\"rowVer\":" + rowVer + "}").with(admin))
                .andExpect(status().isOk());
        send(newKey, ev(id(), "COUNT", "EQ1", at(2), null, m("count", "1"))).andExpect(status().isUnauthorized());
        mvc.perform(json(post(TERM + "/" + devId + "/reissue"), "{\"rowVer\":" + (rowVer + 1) + "}").with(admin))
                .andExpect(status().isConflict());
        // 거부는 접속 로그에(BEARER)
        assertThat(count("select count(*) from sys_access_log where event_cd = 'TERMINAL_DENIED' and action_cd = 'BEARER'")).isPositive();
    }

    /**
     * docs/67 F9 — 틀린 키를 계속 보내도 접속 로그는 같은 출처에서 1분에 상한까지만 쌓인다.
     * 요청은 막지 않으므로 같은 IP 의 정상 장치는 그대로 들어온다. A사 접두어로 위조한 키는 A사 로그에만(회사 형식이 맞을 때만 기록).
     */
    @Test
    void 틀린_키를_퍼부어도_거부_기록은_출처당_분당_상한까지_정상_장치는_그대로() throws Exception {
        String ip = "10.77." + Math.floorMod(System.nanoTime(), 200) + ".9";   // 다른 시험과 출처가 겹치지 않게
        String other = accounts.newComp("EV");
        int before = count("select count(*) from sys_access_log where event_cd = 'TERMINAL_DENIED' and action_cd = 'BEARER'");
        int beforeOther = accounts.query(other, "select count(*) from sys_access_log where event_cd = 'TERMINAL_DENIED'", Integer.class);
        int n = ExtSecurityConfig.DeniedLogBudget.PER_MINUTE * 3;   // 분 경계를 한 번 넘어도 기록 ≤ 상한×2 < n
        for (int i = 0; i < n; i++) {
            mvc.perform(post(EXT).contentType(MediaType.APPLICATION_JSON).content("{\"events\":[]}")
                    .header("Authorization", "Bearer " + comp + ".1." + "x".repeat(10) + i)
                    .with(r -> { r.setRemoteAddr(ip); return r; }))
                    .andExpect(status().isUnauthorized());
        }
        int logged = count("select count(*) from sys_access_log where event_cd = 'TERMINAL_DENIED' and action_cd = 'BEARER'") - before;
        // 분 경계를 넘으면 새 창이라 상한의 두 배까지 남을 수 있다 — 그래도 보낸 수보다는 적다
        assertThat(logged).isGreaterThanOrEqualTo(ExtSecurityConfig.DeniedLogBudget.PER_MINUTE)
                .isLessThanOrEqualTo(ExtSecurityConfig.DeniedLogBudget.PER_MINUTE * 2).isLessThan(n);
        // 같은 IP 의 정상 장치는 막히지 않는다
        mvc.perform(post(EXT).contentType(MediaType.APPLICATION_JSON).header("Authorization", "Bearer " + key)
                .content(ev(id(), "COUNT", "EQ1", at(1), null, m("count", "1"))).with(r -> { r.setRemoteAddr(ip); return r; }))
                .andExpect(status().isAccepted());
        // A사(이 시험 회사) 접두어로 만든 가짜 키는 B사 로그에 남지 않는다
        assertThat(accounts.query(other, "select count(*) from sys_access_log where event_cd = 'TERMINAL_DENIED'", Integer.class))
                .isEqualTo(beforeOther);
    }

    @Test
    void 동시에_온_이벤트는_설비_단위로_줄_선다() throws Exception {
        for (int round = 0; round < 3; round++) {
            int base = round * 10;
            ExecutorService pool = Executors.newFixedThreadPool(2);
            try {
                Callable<String> a = () -> state("EQ1", base + 1, "PRD", null).andReturn().getResponse().getContentAsString();
                Callable<String> b = () -> state("EQ1", base + 2, "SBY", null).andReturn().getResponse().getContentAsString();
                List<Future<String>> fs = pool.invokeAll(List.of(a, b));
                int accepted = 0;
                for (Future<String> f : fs) {
                    accepted += ((Number) JsonPath.read(f.get(), "$.data.accepted")).intValue();
                }
                // 순서가 뒤집혀 도착하면 늦은 쪽은 LATE — 어느 쪽이든 OVERLAP·ERROR 는 없다
                assertThat(accepted).isBetween(1, 2);
                for (Future<String> f : fs) {
                    assertThat(JsonPath.<List<String>>read(f.get(), "$.data.rejected[*].code")).allMatch("LATE"::equals);
                }
            } finally {
                pool.shutdown();
            }
            // 이번 차례 뒤 끊김 → 다시 연결(다음 차례가 빈 상태에서 시작)
            conn("EQ1", base + 5, false);
            conn("EQ1", base + 6, true);
        }
        assertThat(count("select count(*) from cms_equip_state_log where equip_id = ? and end_at is null", eq1)).isZero();
    }

    @Test
    void 알람은_AlarmSink_로_발생_해제_형식_오류_거부_같은_eventId_한_번() throws Exception {
        String raiseId = id();
        String longText = "가".repeat(250);
        // 발생: 코드 대문자 정리, 문구 200자에서 자름, 장치·eventId 그대로
        send(ev(raiseId, "ALARM", "EQ1", at(1), null, m("code", " e-100 "), m("active", "true"), m("text", longText)))
                .andExpect(jsonPath("$.data.accepted").value(1));
        Mockito.verify(alarmSink).raise(eq1, "E-100", "가".repeat(200), at(1), devId, raiseId);
        // active 생략 = 발생, 문구 없음 = null
        String noFlag = id();
        send(ev(noFlag, "ALARM", "EQ1", at(2), null, m("code", "OVERHEAT:2"))).andExpect(jsonPath("$.data.accepted").value(1));
        Mockito.verify(alarmSink).raise(eq1, "OVERHEAT:2", null, at(2), devId, noFlag);
        // 해제
        String clearId = id();
        send(ev(clearId, "ALARM", "EQ1", at(3), null, m("code", "E-100"), m("active", "FALSE"))).andExpect(jsonPath("$.data.accepted").value(1));
        Mockito.verify(alarmSink).clear(eq1, "E-100", at(3), devId, clearId);
        // 같은 eventId 다시 → 호출 없음(cms_equip_inbox)
        send(ev(raiseId, "ALARM", "EQ1", at(1), null, m("code", "E-100"))).andExpect(jsonPath("$.data.accepted").value(0));
        // 형식 오류: 코드 없음·51자·허용 밖 글자·첫 글자 기호 / active 가 true·false 아님 — 그 이벤트만 거부, 호출 없음
        send(ev(id(), "ALARM", "EQ1", at(4), null, m("text", "x"))).andExpect(jsonPath("$.data.rejected[0].code").value("ALARM_CODE"));
        send(ev(id(), "ALARM", "EQ1", at(4), null, m("code", "A".repeat(51)))).andExpect(jsonPath("$.data.rejected[0].code").value("ALARM_CODE"));
        send(ev(id(), "ALARM", "EQ1", at(4), null, m("code", "E 100"))).andExpect(jsonPath("$.data.rejected[0].code").value("ALARM_CODE"));
        send(ev(id(), "ALARM", "EQ1", at(4), null, m("code", "-E100"))).andExpect(jsonPath("$.data.rejected[0].code").value("ALARM_CODE"));
        send(ev(id(), "ALARM", "EQ1", at(4), null, m("code", "E-1"), m("active", "maybe")))
                .andExpect(jsonPath("$.data.rejected[0].code").value("ALARM_ACTIVE"));
        // 장치 작업장 밖 설비·사용 중지 설비 → EQUIP, 호출 없음
        send(ev(id(), "ALARM", "EQ2", at(4), null, m("code", "E-1"))).andExpect(jsonPath("$.data.rejected[0].code").value("EQUIP"));
        send(ev(id(), "ALARM", "EQ9", at(4), null, m("code", "E-1"))).andExpect(jsonPath("$.data.rejected[0].code").value("EQUIP"));
        Mockito.verifyNoMoreInteractions(alarmSink);
        assertThat(count("select count(*) from cms_equip_inbox")).isEqualTo(3);
    }

    @Test
    void A사_장치가_B사_설비_코드로_알람을_보내면_설비_못_찾음() throws Exception {
        String a = comp;
        comp = accounts.newComp("EX");
        accounts.addPlant(comp, "P1", "B 1공장");
        long bNode = node(node(null, "AREA", "A1"), "LINE", "L1");
        accounts.exec(comp, "insert into mst_equip_class (comp_cd, class_cd, class_nm, created_by, updated_by) values (?, 'WELDER', '용접기', 't', 't')",
                comp);
        equip("EQB", bNode, "Y");
        comp = a;
        send(ev(id(), "ALARM", "EQB", at(1), null, m("code", "E-1"))).andExpect(jsonPath("$.data.rejected[0].code").value("EQUIP"));
        Mockito.verifyNoInteractions(alarmSink);
    }

    @Test
    void 알람_계약_빈이_없으면_NONE_으로_받기만() {
        AlarmSink none = AlarmSink.of(new DefaultListableBeanFactory().getBeanProvider(AlarmSink.class));
        assertThat(none).isSameAs(AlarmSink.NONE);
        none.raise(1L, "E-1", null, Instant.now(), null, "ev-12345678");
        none.clear(1L, "E-1", Instant.now(), null, "ev-12345678");
    }

    @Test
    void A사_장치로_B사() throws Exception {
        state("EQ1", 0, "PRD", null).andExpect(jsonPath("$.data.accepted").value(1));
        String a = comp;
        long aEquip = eq1;
        comp = accounts.newComp("EX");
        long bu = accounts.newUser(comp, "admin1", PW);
        accounts.grant(comp, bu, "ADM", "sys-terminal", "SEARCH", "SAVE");
        accounts.grant(comp, bu, "ADM", "cms-state", "SEARCH");
        RequestPostProcessor bAdmin = accounts.as(comp, bu, "admin1");
        admin = bAdmin;
        accounts.addPlant(comp, "P1", "B 1공장");
        long bNode = node(node(null, "AREA", "A1"), "LINE", "L1");
        accounts.exec(comp, "insert into mst_equip_class (comp_cd, class_cd, class_nm, created_by, updated_by) values (?, 'WELDER', '용접기', 't', 't')",
                comp);
        long bEquip = equip("EQ1", bNode, "Y");
        String b = comp;
        // A사 키로 같은 설비 코드 → A사 설비에만, B사 설비는 그대로
        comp = a;
        state("EQ1", 5, "SBY", null).andExpect(jsonPath("$.data.accepted").value(1));
        assertThat(accounts.query(b, "select count(*) from cms_equip_state_log", Integer.class)).isZero();
        assertThat(count("select count(*) from cms_equip_state_log where equip_id = ?", aEquip)).isEqualTo(2);
        // 키 앞 회사를 B사로 바꾼 위조 키 → 401(B사 범위에서 그 id 의 키 해시가 없다)
        String forged = b + key.substring(key.indexOf('.'));
        send(forged, ev(id(), "STATE", "EQ1", at(6), null, m("state", "PRD"))).andExpect(status().isUnauthorized());
        // B사 화면: 같은 공장 코드 P1 이지만 A사 구간·연결 로그는 안 보인다
        mvc.perform(get("/api/cms/state").param("plantCd", "P1").param("date", t0.atZone(ZoneId.of("Asia/Seoul")).toLocalDate().toString())
                .with(bAdmin)).andExpect(status().isOk()).andExpect(jsonPath("$.data.intervals.length()").value(0))
                .andExpect(jsonPath("$.data.equips[*].equipId").value(Matchers.contains((int) bEquip)));
        // B사 장치 키로 A사 장치 seq 를 건드릴 수 없다(장치별 행, RLS)
        comp = b;
        String[] bDev = collector("COL-01", null);
        send(bDev[1], ev(id(), "STATE", "EQ1", at(0), 100L, m("state", "PRD"))).andExpect(jsonPath("$.data.accepted").value(1));
        assertThat(accounts.query(a, "select count(*) from cms_collector_seq", Integer.class)).isZero();
        assertThat(accounts.query(b, "select last_seq from cms_collector_seq", Long.class)).isEqualTo(100L);
        // 직접 SQL(B사 범위): A사 설비·A사 장치로 원장 행
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(b, """
                insert into cms_equip_state_log (comp_cd, equip_id, state_cat, start_at, source, occurred_at, entry_method, recorded_by)
                values (?, ?, 'PRD', now(), 'MANUAL', now(), 'KEY', 'x')""", b, aEquip)))).contains("fk_cms_equip_state_log_ctx_comp");
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(b, """
                insert into cms_equip_conn_log (comp_cd, terminal_id, equip_id, conn, occurred_at, recorded_by)
                values (?, ?, ?, 'OFFLINE', now(), 'x')""", b, devId, bEquip)))).contains("fk_cms_equip_conn_log_ctx_comp");
        // B사 범위에서 회사 코드를 A사로 쓴 행 → RLS
        assertThat(PgErrors.isRlsViolation(catchThrowable(() -> accounts.exec(b, """
                insert into cms_state_reason (comp_cd, reason_cd, reason_nm, state_cat, created_by, updated_by)
                values (?, 'X', 'x', 'UDT', 't', 't')""", a)))).isTrue();
        comp = a;
    }

    @Test
    void COUNT_역분개_행은_원_행과_같은_회사_설비_메트릭_반대_수량만_A사_행을_B사가_되돌릴_수_없다() throws Exception {
        send(ev(id(), "COUNT", "EQ1", at(1), null, m("goodCount", "5"), m("scrapCount", "2"))).andExpect(jsonPath("$.data.accepted").value(1));
        long good = accounts.query(comp, "select count_id from cms_equip_count where metric_cd = 'GOOD'", Long.class);
        long scrap = accounts.query(comp, "select count_id from cms_equip_count where metric_cd = 'SCRAP'", Long.class);
        long eq2 = accounts.query(comp, "select equip_id from mst_equip where equip_cd = 'EQ2'", Long.class);
        String rev = """
                insert into cms_equip_count (comp_cd, equip_id, metric_cd, qty, reason, occurred_at, entry_method, recorded_by, reversal_of_id)
                values (?, ?, ?, ?, '잘못 셈', now(), 'CORRECT', 't', ?)""";
        // 틀린 수량·메트릭·설비 → ck_cms_equip_count_reversal_match
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(comp, rev, comp, eq1, "GOOD", -4, good))))
                .contains("ck_cms_equip_count_reversal_match");
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(comp, rev, comp, eq1, "SCRAP", -5, good))))
                .contains("ck_cms_equip_count_reversal_match");
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(comp, rev, comp, eq2, "GOOD", -5, good))))
                .contains("ck_cms_equip_count_reversal_match");
        // 맞는 역분개 → 한 번만(두 번째는 uq_cms_equip_count_reversal), 역분개 행을 다시 되돌리기 금지
        accounts.exec(comp, rev, comp, eq1, "GOOD", -5, good);
        long r1 = accounts.query(comp, "select count_id from cms_equip_count where reversal_of_id = ?", Long.class, good);
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(comp, rev, comp, eq1, "GOOD", -5, good))))
                .contains("uq_cms_equip_count_reversal");
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(comp, rev, comp, eq1, "GOOD", 5, r1))))
                .isNotEmpty();                              // qty > 0 이라 V139 CHECK 가 먼저(모양) — 모양이 맞아도 아래
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(comp, rev, comp, eq1, "GOOD", 0, r1))))
                .contains("ck_cms_equip_count_reversal_match");
        // A사로 B사: B사가 자기 설비로 A사 원 행을 가리키는 역분개 → 원 행이 보이지 않아 거부
        String other = accounts.newComp("EQR");
        accounts.addPlant(other, "P1", "1공장");
        accounts.exec(other, "insert into mst_equip_class (comp_cd, class_cd, class_nm, created_by, updated_by) values (?, 'WELDER', '용접기', 't', 't')",
                other);
        long bArea = accounts.query(other, """
                insert into mst_equip_hier (comp_cd, plant_cd, parent_id, node_type, node_cd, node_nm, created_by, updated_by)
                values (?, 'P1', null, 'AREA', 'B1', 'B1', 't', 't') returning node_id""", Long.class, other);
        long bNode = accounts.query(other, """
                insert into mst_equip_hier (comp_cd, plant_cd, parent_id, node_type, node_cd, node_nm, created_by, updated_by)
                values (?, 'P1', ?, 'LINE', 'BL1', 'BL1', 't', 't') returning node_id""", Long.class, other, bArea);
        long bEq = accounts.query(other, """
                insert into mst_equip (comp_cd, plant_cd, equip_cd, equip_nm, class_id, node_id, use_yn, created_by, updated_by)
                values (?, 'P1', 'BEQ', 'B 설비', (select class_id from mst_equip_class where class_cd = 'WELDER'), ?, 'Y', 't', 't')
                returning equip_id""", Long.class, other, bNode);
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(other, rev, other, bEq, "SCRAP", -2, scrap))))
                .contains("ck_cms_equip_count_reversal_match");
        assertThat(accounts.query(comp, "select count(*) from cms_equip_count where reversal_of_id = ?", Integer.class, scrap)).isZero();
        assertThat(accounts.query(comp, "select trim_scale(sum(qty))::text from cms_equip_count where metric_cd = 'GOOD'", String.class))
                .isEqualTo("0");
    }

    @Test
    void 원장_직접_SQL() throws Exception {
        state("EQ1", 0, "PRD", null);
        state("EQ1", 10, "SBY", null);
        long closed = accounts.query(comp, "select state_log_id from cms_equip_state_log where end_at is not null", Long.class);
        long open = accounts.query(comp, "select state_log_id from cms_equip_state_log where end_at is null", Long.class);
        // 끝 시각 말고는 UPDATE 권한이 없다(열 권한) → 42501, DELETE 도
        assertThat(PgErrors.sqlState(catchThrowable(() -> accounts.exec(comp,
                "update cms_equip_state_log set state_cat = 'UDT' where state_log_id = ?", closed)))).contains("42501");
        assertThat(PgErrors.sqlState(catchThrowable(() -> accounts.exec(comp, "delete from cms_equip_state_log")))).contains("42501");
        // 닫힌 구간 다시 닫기·되열기, 열린 구간을 시작보다 앞에서 닫기
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(comp,
                "update cms_equip_state_log set end_at = end_at + interval '1 minute' where state_log_id = ?", closed))))
                .contains("ck_cms_equip_state_log_close_once");
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(comp,
                "update cms_equip_state_log set end_at = null where state_log_id = ?", closed)))).contains("ck_cms_equip_state_log_close_once");
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(comp,
                "update cms_equip_state_log set end_at = start_at - interval '1 minute' where state_log_id = ?", open))))
                .contains("ck_cms_equip_state_log_range");
        // 겹치는 구간·두 번째 열린 구간 → 배제 제약
        String ins = """
                insert into cms_equip_state_log (comp_cd, equip_id, state_cat, start_at, end_at, source, occurred_at, entry_method, recorded_by)
                values (?, ?, 'UDT', ?::timestamptz, ?::timestamptz, 'MANUAL', now(), 'KEY', 'x')""";
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(comp, ins, comp, eq1, at(5).toString(), at(6).toString()))))
                .contains("ex_cms_equip_state_log_overlap");
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(comp, ins, comp, eq1, at(60).toString(), null))))
                .contains("ex_cms_equip_state_log_overlap");
        // 끊김(UNKNOWN)에는 사유를 달 수 없다, 정정 행은 없다(다음 구간으로)
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(comp, """
                insert into cms_equip_state_log (comp_cd, equip_id, state_cat, reason_id, start_at, source, occurred_at, entry_method, recorded_by)
                select ?, ?, 'UNKNOWN', reason_id, now() - interval '30 days', 'MANUAL', now(), 'KEY', 'x' from cms_state_reason where reason_cd = 'JAM'""",
                comp, eq1)))).isPresent();
        // 연결·에너지 원장 UPDATE·DELETE → 42501
        conn("EQ1", 20, false);
        for (String t : new String[] {"cms_equip_conn_log", "cms_energy_reading"}) {
            assertThat(PgErrors.sqlState(catchThrowable(() -> accounts.exec(comp, "update " + t + " set comp_cd = comp_cd")))).contains("42501");
            assertThat(PgErrors.sqlState(catchThrowable(() -> accounts.exec(comp, "delete from " + t)))).contains("42501");
        }
        // GAP 은 장치 단위·범위 필수
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(comp, """
                insert into cms_equip_conn_log (comp_cd, terminal_id, equip_id, conn, seq_from, seq_to, occurred_at, recorded_by)
                values (?, ?, ?, 'GAP', 1, 2, now(), 'x')""", comp, devId, eq1)))).contains("ck_cms_equip_conn_log_gap");
        // 에너지: 역분개 = 음수 + 사유 + CORRECT, 한 번만. 역분개 행은 겹침 대상이 아니다
        long rd = accounts.query(comp, """
                insert into cms_energy_reading (comp_cd, plant_cd, meter_cd, period_start, period_end, qty, unit_cd, reading_src, occurred_at,
                                                entry_method, recorded_by)
                values (?, 'P1', 'KWH-L1', now() - interval '2 hours', now() - interval '1 hour', 10, 'KWH', 'MANUAL', now(), 'KEY', 'x')
                returning reading_id""", Long.class, comp);
        String rev = """
                insert into cms_energy_reading (comp_cd, plant_cd, meter_cd, period_start, period_end, qty, unit_cd, reading_src, reason,
                                                occurred_at, entry_method, recorded_by, reversal_of_id)
                select comp_cd, plant_cd, meter_cd, period_start, period_end, ?, unit_cd, reading_src, ?, now(), ?, 'x', reading_id
                  from cms_energy_reading where reading_id = ?""";
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(comp, rev, 10, "x", "CORRECT", rd)))).contains("ck_cms_energy_reading_qty");
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(comp, rev, -10, null, "CORRECT", rd)))).contains("ck_cms_energy_reading_qty");
        accounts.exec(comp, rev, -10, "계량기 오류", "CORRECT", rd);
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(comp, rev, -10, "또", "CORRECT", rd)))).contains("uq_cms_energy_reading_reversal");
        // 사유: 지우기 금지, 분류는 SEMI E10 6종만(UNKNOWN 은 사유가 아니다)
        assertThat(PgErrors.sqlState(catchThrowable(() -> accounts.exec(comp, "delete from cms_state_reason")))).contains("42501");
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(comp, """
                insert into cms_state_reason (comp_cd, reason_cd, reason_nm, state_cat, created_by, updated_by)
                values (?, 'NET', '통신', 'UNKNOWN', 't', 't')""", comp)))).contains("ck_cms_state_reason_cat");
        // 수집 장치는 등록 코드를 갖지 않는다
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(comp,
                "update sys_terminal set activation_hash = repeat('a', 64), activation_expires_at = now() + interval '1 hour' where terminal_id = ?", devId)))).contains("ck_sys_terminal_collector");
    }

    // ---------- 묶음 = 트랜잭션 하나(docs/70 W1·W3) · 멱등 기록 보존(W2) ----------

    private static String batch(List<String> evs) {
        return "[" + String.join(",", evs) + "]";
    }

    @Test
    void 묶음_안_한_건이_실패하면_그_건만_거부되고_나머지는_저장된다() throws Exception {
        String meter = "{\"eventId\":\"%s\",\"type\":\"METER_READING\",\"periodStart\":\"%s\",\"occurredAt\":\"%s\",\"metrics\":[%s]}";
        String okMeter = id();
        String overlap = id();
        String badCount = id();
        String badReason = id();
        String noProc = id();
        String outside = id();
        List<String> evs = new ArrayList<>();
        evs.add(ev(id(), "COUNT", "EQ1", at(1), null, m("goodCount", "5")));
        evs.add(ev(badCount, "COUNT", "EQ1", at(2), null, m("goodCount", "-1")));                 // 검증(자바) 거부
        evs.add(ev(id(), "STATE", "EQ1", at(3), null, m("state", "PRD")));
        evs.add(ev(badReason, "STATE", "EQ1", at(4), null, m("state", "PRD"), m("reason", "JAM"))); // 분류와 다른 사유(DB 조회 뒤 거부)
        evs.add(String.format(meter, okMeter, at(0), at(15), "{\"name\":\"KWH-L1\",\"value\":2,\"unit\":\"KWH\"}"));
        evs.add(String.format(meter, overlap, at(10), at(20), "{\"name\":\"KWH-L1\",\"value\":1,\"unit\":\"KWH\"}")); // DB 배제 제약 오류 → 저장점
        evs.add(ev(noProc, "PARAM", "EQ1", at(5), null, m("WELD_CURRENT", "9.0")));                  // 진행 중 공정 없음
        evs.add(ev(id(), "COUNT", "EQ1", at(6), null, m("goodCount", "3"), m("scrapCount", "1")));
        evs.add(ev(outside, "COUNT", "EQ2", at(7), null, m("goodCount", "1")));                      // 장치 범위 밖 설비
        send(batch(evs)).andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.accepted").value(4)).andExpect(jsonPath("$.data.duplicates").value(0))
                .andExpect(jsonPath("$.data.rejected[*].eventId").value(Matchers.contains(badCount, badReason, overlap, noProc, outside)))
                .andExpect(jsonPath("$.data.rejected[*].code").value(Matchers.contains("METRIC", "REASON", "OVERLAP", "NO_RUNNING_PROC", "EQUIP")));
        assertThat(count("select coalesce(sum(qty), 0)::int from cms_equip_count where metric_cd = 'GOOD'")).isEqualTo(8);
        assertThat(count("select count(*) from cms_equip_count")).isEqualTo(3);
        assertThat(intervals(eq1)).containsExactly("PRD@3~…");
        assertThat(count("select count(*) from cms_energy_reading")).isEqualTo(1);
        // 멱등 기록은 받은 4건만 — 거부된 건은 고쳐서 다시 보낼 수 있다
        assertThat(count("select count(*) from cms_equip_inbox")).isEqualTo(4);
        send(ev(badCount, "COUNT", "EQ1", at(2), null, m("goodCount", "2"))).andExpect(jsonPath("$.data.accepted").value(1));
        assertThat(count("select coalesce(sum(qty), 0)::int from cms_equip_count where metric_cd = 'GOOD'")).isEqualTo(10);
        // 원장 행 규약 그대로(묶음 한 문장도): AUTO·두 시각·장치·이벤트
        assertThat(count("""
                select count(*) from cms_equip_count where entry_method = 'AUTO' and terminal_id = ? and event_id is not null
                   and recorded_by = ? and recorded_at >= occurred_at""", devId, "d" + devId + ":COL-01")).isEqualTo(4);
    }

    @Test
    void 같은_묶음을_다시_보내면_전부_중복이고_두_번_저장되지_않는다() throws Exception {
        List<String> evs = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            evs.add(ev("ev-re-" + i + "-" + UUID.randomUUID(), "COUNT", "EQ1", at(i), (long) i + 1, m("goodCount", "1")));
        }
        for (int i = 0; i < 10; i++) {
            evs.add(ev("ev-rs-" + i + "-" + UUID.randomUUID(), "STATE", "EQ1", at(40 + i), (long) 41 + i, m("state", i % 2 == 0 ? "PRD" : "SBY")));
        }
        String body = batch(evs);
        send(body).andExpect(jsonPath("$.data.accepted").value(50)).andExpect(jsonPath("$.data.duplicates").value(0))
                .andExpect(jsonPath("$.data.rejected.length()").value(0));
        send(body).andExpect(jsonPath("$.data.accepted").value(0)).andExpect(jsonPath("$.data.duplicates").value(50))
                .andExpect(jsonPath("$.data.rejected.length()").value(0));
        assertThat(count("select count(*) from cms_equip_count")).isEqualTo(40);
        assertThat(count("select count(*) from cms_equip_state_log where equip_id = ?", eq1)).isEqualTo(10);
        assertThat(count("select count(*) from cms_equip_inbox")).isEqualTo(50);
        assertThat(accounts.query(comp, "select last_seq from cms_collector_seq", Long.class)).isEqualTo(50L);
        assertThat(count("select count(*) from cms_equip_conn_log where conn = 'GAP'")).isZero();
        // 같은 묶음 안 같은 eventId: 앞의 것만(COUNT·STATE 섞여도)
        String dupId = id();
        send(batch(List.of(ev(dupId, "COUNT", "EQ1", at(60), null, m("goodCount", "1")),
                ev(dupId, "COUNT", "EQ1", at(61), null, m("goodCount", "1")),
                ev(dupId, "STATE", "EQ1", at(62), null, m("state", "SDT")))))
                .andExpect(jsonPath("$.data.accepted").value(1)).andExpect(jsonPath("$.data.duplicates").value(2));
        String dupState = id();
        send(batch(List.of(ev(dupState, "STATE", "EQ1", at(63), null, m("state", "SDT")),
                ev(dupState, "COUNT", "EQ1", at(64), null, m("goodCount", "1")))))
                .andExpect(jsonPath("$.data.accepted").value(1)).andExpect(jsonPath("$.data.duplicates").value(1));
        assertThat(count("select count(*) from cms_equip_count")).isEqualTo(41);
    }

    @Test
    void 같은_장치_묶음_둘이_동시에_와도_seq_가_꼬이지_않는다() throws Exception {
        send(ev(id(), "COUNT", "EQ1", at(0), 0L, m("goodCount", "1"))).andExpect(jsonPath("$.data.accepted").value(1));
        for (int round = 0; round < 3; round++) {
            long base = round * 600L;
            List<String> first = new ArrayList<>();
            List<String> second = new ArrayList<>();
            for (int i = 1; i <= 300; i++) {
                first.add(ev(id(), "COUNT", "EQ1", at(1), base + i, m("goodCount", "1")));
                second.add(ev(id(), "COUNT", "EQ1", at(2), base + 300 + i, m("goodCount", "1")));
            }
            ExecutorService pool = Executors.newFixedThreadPool(2);
            try {
                List<Future<String>> fs = pool.invokeAll(List.<Callable<String>>of(
                        () -> send(batch(first)).andReturn().getResponse().getContentAsString(),
                        () -> send(batch(second)).andReturn().getResponse().getContentAsString()));
                for (Future<String> f : fs) {
                    assertThat(((Number) JsonPath.read(f.get(), "$.data.accepted")).intValue()).isEqualTo(300);
                }
            } finally {
                pool.shutdown();
            }
            // 마지막 seq = 가장 큰 값(잃어버린 갱신 없음). 뒤 묶음이 먼저 커밋되면 앞 묶음 몫이 GAP 한 줄(그 뒤 앞 묶음은 순서 뒤바뀜)
            assertThat(accounts.query(comp, "select last_seq from cms_collector_seq", Long.class)).isEqualTo(base + 600);
            List<String> gaps = List.of(accounts.query(comp, """
                    select coalesce(string_agg(seq_from || '-' || seq_to, ',' order by seq_from), '') from cms_equip_conn_log
                     where conn = 'GAP' and seq_from > ?""", String.class, base).split(",")).stream().filter(g -> !g.isEmpty()).toList();
            assertThat(gaps).isSubsetOf(List.of((base + 1) + "-" + (base + 300)));
        }
        assertThat(count("select count(*) from cms_equip_count")).isEqualTo(1 + 3 * 600);
    }

    @Test
    void 묶음_한_문장이_실패하면_건마다_다시_해서_조용히_빠지는_건이_없다() throws Exception {
        // 시험 전용 트리거: qty 777 인 원장 행만 거부(묶음 한 문장이 통째로 실패하는 상황)
        try (java.sql.Connection c = java.sql.DriverManager.getConnection(jdbcUrl(), OWNER_USER, testPassword());
                java.sql.Statement st = c.createStatement()) {
            st.execute("""
                    create function zt_fail_777() returns trigger language plpgsql as $$
                    begin if new.qty = 777 then raise exception 'zt 777'; end if; return new; end $$""");
            st.execute("create trigger zt_fail_777 before insert on cms_equip_count for each row execute function zt_fail_777()");
        }
        try {
            String bad = id();
            send(batch(List.of(ev(id(), "COUNT", "EQ1", at(1), null, m("goodCount", "1")),
                    ev(bad, "COUNT", "EQ1", at(2), null, m("goodCount", "777")),
                    ev(id(), "STATE", "EQ1", at(3), null, m("state", "PRD")),
                    ev(id(), "COUNT", "EQ1", at(4), null, m("goodCount", "2")))))
                    .andExpect(jsonPath("$.data.accepted").value(3))
                    .andExpect(jsonPath("$.data.rejected[0].eventId").value(bad)).andExpect(jsonPath("$.data.rejected[0].code").value("ERROR"));
            assertThat(count("select coalesce(sum(qty), 0)::int from cms_equip_count")).isEqualTo(3);
            assertThat(count("select count(*) from cms_equip_inbox where event_id = ?", bad)).isZero();
            assertThat(count("select count(*) from cms_equip_inbox")).isEqualTo(3);
            assertThat(intervals(eq1)).containsExactly("PRD@3~…");
        } finally {
            try (java.sql.Connection c = java.sql.DriverManager.getConnection(jdbcUrl(), OWNER_USER, testPassword());
                    java.sql.Statement st = c.createStatement()) {
                st.execute("drop trigger zt_fail_777 on cms_equip_count");
                st.execute("drop function zt_fail_777()");
            }
        }
    }

    @Test
    void A사_장치_키로_B사_설비는_묶음에서도_그_건만_거부되고_멱등_기록은_회사마다() throws Exception {
        String a = comp;
        String b = accounts.newComp("EB");
        accounts.addPlant(b, "P1", "B 1공장");
        accounts.exec(b, "insert into mst_equip_class (comp_cd, class_cd, class_nm, created_by, updated_by) values (?, 'WELDER', '용접기', 't', 't')", b);
        String hier = """
                insert into mst_equip_hier (comp_cd, plant_cd, parent_id, node_type, node_cd, node_nm, created_by, updated_by)
                values (?, 'P1', ?, ?, ?, ?, 't', 't') returning node_id""";
        long bArea = accounts.query(b, hier, Long.class, b, null, "AREA", "A1", "A1");
        long bNode = accounts.query(b, hier, Long.class, b, bArea, "LINE", "L1", "L1");
        accounts.exec(b, """
                insert into mst_equip (comp_cd, plant_cd, equip_cd, equip_nm, class_id, node_id, use_yn, created_by, updated_by)
                values (?, 'P1', 'EQB', 'B 설비', (select class_id from mst_equip_class where class_cd = 'WELDER'), ?, 'Y', 't', 't')""", b, bNode);
        String shared = id();
        send(batch(List.of(ev(shared, "COUNT", "EQ1", at(1), null, m("goodCount", "1")),
                ev(id(), "COUNT", "EQB", at(2), null, m("goodCount", "9")),
                ev(id(), "STATE", "EQB", at(3), null, m("state", "PRD")))))
                .andExpect(jsonPath("$.data.accepted").value(1))
                .andExpect(jsonPath("$.data.rejected[*].code").value(Matchers.contains("EQUIP", "EQUIP")));
        assertThat(accounts.query(b, "select count(*) from cms_equip_count", Integer.class)).isZero();
        assertThat(accounts.query(b, "select count(*) from cms_equip_state_log", Integer.class)).isZero();
        assertThat(accounts.query(b, "select count(*) from cms_equip_inbox", Integer.class)).isZero();
        // B사 범위에서 A사 멱등 기록을 넣거나 지울 수 없다(RLS)
        assertThat(PgErrors.isRlsViolation(catchThrowable(() -> accounts.exec(b,
                "insert into cms_equip_inbox (comp_cd, event_id) values (?, 'ev-zt-forge-1')", a)))).isTrue();
        assertThat(accounts.exec(b, "delete from cms_equip_inbox where event_id = ?", shared)).isZero();
        assertThat(count("select count(*) from cms_equip_inbox where event_id = ?", shared)).isEqualTo(1);
    }

    @Test
    void 보존_정리는_기간_안_기록과_법적_보류를_지우지_않고_다른_회사를_건드리지_않는다() throws Exception {
        String ins = "insert into cms_equip_inbox (comp_cd, event_id, received_at) values (?, ?, now() - ?::interval)";
        for (String[] r : new String[][] {{"ev-old-10d", "10 days"}, {"ev-old-8d", "8 days"}, {"ev-in-6d", "6 days"}, {"ev-in-1d", "1 day"}}) {
            accounts.exec(comp, ins, comp, r[0], r[1]);
        }
        String b = accounts.newComp("EP");
        accounts.exec(b, ins, b, "ev-b-old-10d", "10 days");
        // 기본 7일: 10·8일 전만 지움(A사만 범위로)
        assertThat(runner.call(comp, () -> inboxPurge.purge())).isEqualTo(2);
        assertThat(accounts.query(comp, "select string_agg(event_id, ',' order by event_id) from cms_equip_inbox", String.class))
                .isEqualTo("ev-in-1d,ev-in-6d");
        assertThat(accounts.query(b, "select count(*) from cms_equip_inbox", Integer.class)).isEqualTo(1);
        // 회사 보존 기간 1일 → 클래스 최소 3일이 이긴다: 6일 전만 지움
        accounts.exec(comp, """
                insert into sys_retention_policy (comp_cd, class_cd, keep_days, created_by, updated_by) values (?, 'EQUIP_INBOX', 1, 't', 't')""", comp);
        accounts.exec(comp, ins, comp, "ev-in-5d", "5 days");
        accounts.exec(comp, """
                insert into sys_legal_hold (comp_cd, class_cd, from_at, to_at, reason, created_by, updated_by)
                values (?, 'EQUIP_INBOX', now() - interval '5 days 12 hours', now() - interval '4 days', '분쟁 자료', 't', 't')""", comp);
        // 법적 보류 시작(5.5일 전)보다 새 행은 남긴다 — 6일 전만
        assertThat(runner.call(comp, () -> inboxPurge.purge())).isEqualTo(1);
        assertThat(accounts.query(comp, "select string_agg(event_id, ',' order by event_id) from cms_equip_inbox", String.class))
                .isEqualTo("ev-in-1d,ev-in-5d");
        // 앱 계정 직접 DELETE: 최소 기간 안·보류 기간 행은 DB 가 거부, 다른 회사 행은 안 보임
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(comp, "delete from cms_equip_inbox where event_id = 'ev-in-1d'"))))
                .contains("ck_cms_equip_inbox_retention");
        accounts.exec(comp, "update sys_retention_policy set keep_days = 3 where class_cd = 'EQUIP_INBOX'");
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(comp, "delete from cms_equip_inbox where event_id = 'ev-in-5d'"))))
                .contains("ck_cms_equip_inbox_hold");
        assertThat(catchThrowable(() -> accounts.exec(comp, "update cms_equip_inbox set received_at = now() - interval '30 days'")))
                .rootCause().hasMessageContaining("permission denied");
        // 보류를 풀면 다음 정리에서(정기 작업 = 회사 전부): A사 5일 전 + B사 10일 전
        accounts.exec(comp, "update sys_legal_hold set released_at = now() where class_cd = 'EQUIP_INBOX'");
        assertThat(cmsJobs.equipInboxPurge()).isGreaterThanOrEqualTo(2);
        assertThat(accounts.query(comp, "select string_agg(event_id, ',') from cms_equip_inbox", String.class)).isEqualTo("ev-in-1d");
        assertThat(accounts.query(b, "select count(*) from cms_equip_inbox", Integer.class)).isZero();
    }

    @Test
    void 보존_기간이_지나_정리된_eventId_는_다시_보내면_다시_처리된다_장치_계약의_재전송_창() throws Exception {
        String old = id();
        send(ev(old, "COUNT", "EQ1", at(1), null, m("goodCount", "1"))).andExpect(jsonPath("$.data.accepted").value(1));
        send(ev(old, "COUNT", "EQ1", at(1), null, m("goodCount", "1"))).andExpect(jsonPath("$.data.duplicates").value(1));
        // 받은 시각을 8일 전으로(소유자 계정 — 앱은 UPDATE 권한 없음) → 정리 → 같은 eventId 는 새 이벤트로 처리(docs/70 R2 한계)
        try (java.sql.Connection c = java.sql.DriverManager.getConnection(jdbcUrl(), OWNER_USER, testPassword());
                java.sql.PreparedStatement scope = c.prepareStatement("select set_config('app.comp_cd', ?, false)");
                java.sql.PreparedStatement ps = c.prepareStatement(
                        "update cms_equip_inbox set received_at = now() - interval '8 days' where comp_cd = ? and event_id = ?")) {
            scope.setString(1, comp);
            scope.execute();
            ps.setString(1, comp);
            ps.setString(2, old);
            assertThat(ps.executeUpdate()).isEqualTo(1);
        }
        assertThat(runner.call(comp, () -> inboxPurge.purge())).isEqualTo(1);
        send(ev(old, "COUNT", "EQ1", at(1), null, m("goodCount", "1"))).andExpect(jsonPath("$.data.accepted").value(1));
        assertThat(count("select count(*) from cms_equip_count where event_id = ?", old)).isEqualTo(2);
    }
}
