package io.smarthappy.module.cms.edge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Function;

import org.junit.jupiter.api.AfterEach;
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
import com.sun.net.httpserver.HttpServer;

import io.smarthappy.platform.common.db.PgErrors;
import io.smarthappy.platform.messaging.InboundProcessor;
import io.smarthappy.platform.messaging.OutboxRelay;
import io.smarthappy.platform.tenant.TenantRunner;
import io.smarthappy.platform.time.BizClock;
import io.smarthappy.support.PostgresTestBase;
import io.smarthappy.support.TestAccounts;

/**
 * 장비 연동 E0 (docs/65 16장) — 정상 흐름 + 이상 사용(TESTING.md A~K).
 *
 * 수신 규약: 원문 먼저 저장 → 200 → 비동기 처리, 같은 콜백 297회(ID 매번 다름) = 행 하나, 같은 키·다른 내용 = 충돌 + 알람,
 * 순서 뒤바뀜(같은 묶음 = 바로잡음, 이미 지난 seq = STALE·되돌리지 않음), seq 누락 GAP, 처리 오류 = 격리(상대에 오류 없음) + 다시 처리,
 * 과부하만 429 + Retry-After, 형식 오류(봉투 400 / 건별 INVALID), 시계 차이 경고, 미래 시각 거부.
 * 상태: PackML 전이 허용표(허용 밖 = 거부 + 알람, snapshot·GAP 뒤 = 수렴), CMS 상태 원장 대응(E10 분류 + pack_state).
 * 하트비트: 끊김 판정(알람 한 번·UNKNOWN 구간) → 복귀(해제·구간 닫기), 소모품 부족 경고는 바뀔 때만, birth.
 * 명령 4단: 1차 검사(쓰기 활성화·범위·인터록·진행 중 하나·요청 키 필수·FN1 권한) → **EDGE(당겨 감)·DIRECT(아웃박스 → 어댑터) 같은 시험** →
 * 쓰기 → 다시 읽기 → 결과(완료 + 오류 = 실패, 다시 읽은 값 불일치 = 실패), 시간 초과(EXPIRED·UNKNOWN) → 늦은 결과·사람 확정.
 * 회사: A사 엣지 키로 B사 설비 상태·명령 결과를 못 보내고 B사 명령을 못 가져간다. 같은 회사라도 맡지 않은 장치는 거부.
 * 원장 직접 SQL(단계·사건 수정·삭제, 원문 칸 고정).
 */
@AutoConfigureMockMvc
class EdgeLinkTest extends PostgresTestBase {

    private static final String PW = "Cedar-lantern-41";
    private static final String MSG = "/ext/v1/edge/messages";
    private static final String HB = "/ext/v1/edge/heartbeat";
    private static final String CLAIM = "/ext/v1/edge/commands/claim";
    private static final String API = "/api/cms/equip-links";

    @Autowired
    MockMvc mvc;

    @Autowired
    TestAccounts accounts;

    @Autowired
    TenantRunner runner;

    @Autowired
    InboundProcessor processor;

    @Autowired
    EquipLinkService links;

    @Autowired
    EquipCommandService commands;

    @Autowired
    OutboxRelay relay;

    @Autowired
    BizClock clock;

    Site a;
    /** 상태 메시지 시각 기준 — 30분 전(미래 허용 오차 밖으로 나가지 않게) */
    Instant t0;

    /** 회사 하나의 시험 자리: 관리자, 공장 P1, 설비 EQ1(EDGE)·EQ2(DIRECT), 엣지·어댑터 장치 키 */
    static final class Site {
        String comp;
        long userId;
        RequestPostProcessor admin;
        long eq1;
        long eq2;
        long eq3;
        String edgeKey;
        long edgeId;
        String adpKey;
        long adpId;
        String otherKey;
    }

    @BeforeEach
    void setUp() throws Exception {
        t0 = Instant.now().minus(Duration.ofMinutes(30));
        a = site("EA", 0);
    }

    @AfterEach
    void resetClock() {
        clock.setClock(Clock.systemUTC());
    }

    // ================================================================== 도우미

    private Site site(String prefix, int adapterPort) throws Exception {
        Site s = new Site();
        s.comp = accounts.newComp(prefix);
        s.userId = accounts.newUser(s.comp, "admin1", PW);
        accounts.grant(s.comp, s.userId, "ADM", "sys-terminal", "SEARCH", "SAVE");
        accounts.grant(s.comp, s.userId, "ADM", EquipLinkService.PROG, "SEARCH", "SAVE", "FN1");
        s.admin = accounts.as(s.comp, s.userId, "admin1");
        accounts.addPlant(s.comp, "P1", "1공장");
        accounts.exec(s.comp, "insert into mst_equip_class (comp_cd, class_cd, class_nm, created_by, updated_by) values (?, 'LBL', '라벨러', 't', 't')",
                s.comp);
        s.eq1 = equip(s.comp, "EQ1");
        s.eq2 = equip(s.comp, "EQ2");
        s.eq3 = equip(s.comp, "EQ3");
        String[] edge = collector(s, "EDGE-01");
        s.edgeId = Long.parseLong(edge[0]);
        s.edgeKey = edge[1];
        String[] adp = collector(s, "ADP-01");
        s.adpId = Long.parseLong(adp[0]);
        s.adpKey = adp[1];
        s.otherKey = collector(s, "COL-99")[1];
        saveLink(s, s.eq1, "{\"connMode\":\"EDGE\",\"terminalCd\":\"EDGE-01\",\"hbSec\":5,\"writeEnabledYn\":\"Y\",\"useYn\":\"Y\"}")
                .andExpect(status().isOk());
        String url = adapterPort > 0 ? "\"adapterUrl\":\"http://127.0.0.1:" + adapterPort + "\"," : "";
        saveLink(s, s.eq2, "{\"connMode\":\"DIRECT\",\"terminalCd\":\"ADP-01\"," + url + "\"hbSec\":5,\"writeEnabledYn\":\"Y\",\"useYn\":\"Y\"}")
                .andExpect(status().isOk());
        return s;
    }

    private long equip(String comp, String cd) {
        return accounts.query(comp, """
                insert into mst_equip (comp_cd, plant_cd, equip_cd, equip_nm, class_id, created_by, updated_by)
                values (?, 'P1', ?, ?, (select class_id from mst_equip_class where class_cd = 'LBL'), 't', 't') returning equip_id""",
                Long.class, comp, cd, cd + " 설비");
    }

    private String[] collector(Site s, String cd) throws Exception {
        MvcResult m = mvc.perform(post("/api/production/terminals").with(csrf()).with(s.admin).contentType(MediaType.APPLICATION_JSON)
                .content("{\"terminalCd\":\"" + cd + "\",\"terminalNm\":\"" + cd + "\",\"plantCd\":\"P1\",\"terminalType\":\"collector\"}"))
                .andExpect(status().isOk()).andReturn();
        String body = m.getResponse().getContentAsString();
        return new String[] {String.valueOf(((Number) JsonPath.read(body, "$.data.terminalId")).longValue()), JsonPath.read(body, "$.data.code")};
    }

    private ResultActions saveLink(Site s, long equipId, String body) throws Exception {
        String cur = accounts.query(s.comp, "select coalesce((select row_ver::text from cms_equip_link where equip_id = ?), 'null')", String.class,
                equipId);
        String b = body.replaceFirst("}$", ",\"rowVer\":" + cur + "}");
        return mvc.perform(put(API + "/" + equipId).with(csrf()).with(s.admin).contentType(MediaType.APPLICATION_JSON).content(b));
    }

    private ResultActions tag(Site s, long equipId, String body) throws Exception {
        return mvc.perform(post(API + "/" + equipId + "/tags").with(csrf()).with(s.admin).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private ResultActions ext(String path, String key, String body) throws Exception {
        MockHttpServletRequestBuilder b = post(path).contentType(MediaType.APPLICATION_JSON).content(body);
        if (key != null) {
            b.header("Authorization", "Bearer " + key);
        }
        return mvc.perform(b);
    }

    private ResultActions msgs(String key, Instant sentAt, String... items) throws Exception {
        return ext(MSG, key, "{" + (sentAt == null ? "" : "\"sentAt\":\"" + sentAt + "\",") + "\"messages\":[" + String.join(",", items) + "]}");
    }

    private ResultActions msgs(String key, String... items) throws Exception {
        return msgs(key, Instant.now(), items);
    }

    /** 상태 메시지 — 분 단위 시각(t0 + min). extra = 덧붙일 칸(상대 메시지 ID 등) */
    private String st(String equipCd, long seq, String pack, int min, String extra) {
        return "{\"route\":\"EQUIP_STATE\",\"equipCd\":\"" + equipCd + "\",\"seq\":" + seq + ",\"packState\":\"" + pack + "\",\"occurredAt\":\""
                + t0.plus(Duration.ofMinutes(min)) + "\"" + (extra == null ? "" : "," + extra) + "}";
    }

    private String st(String equipCd, long seq, String pack, int min) {
        return st(equipCd, seq, pack, min, null);
    }

    private ResultActions hb(String key, String bootId, String equips) throws Exception {
        return ext(HB, key, "{\"bootId\":\"" + bootId + "\",\"sentAt\":\"" + Instant.now() + "\",\"equips\":[" + equips + "]}");
    }

    private InboundProcessor.DrainResult drain(Site s) {
        return processor.drain(s.comp);
    }

    private int count(Site s, String sql, Object... args) {
        return accounts.query(s.comp, sql, Integer.class, args);
    }

    private String str(Site s, String sql, Object... args) {
        return accounts.query(s.comp, sql, String.class, args);
    }

    /** 설비 상태 원장 요약 "분류/PackML" 시작순 */
    private String ledger(Site s, long equipId) {
        return str(s, """
                select coalesce(string_agg(state_cat || '/' || coalesce(pack_state, '-') || case when end_at is null then '…' else '' end, ','
                       order by start_at), '') from cms_equip_state_log where equip_id = ?""", equipId);
    }

    private String inboundStatuses(Site s) {
        return str(s, "select coalesce(string_agg(status || coalesce(':' || result_cd, ''), ',' order by inbound_id), '') from sys_inbound_msg");
    }

    private String events(Site s, long equipId) {
        return str(s, "select coalesce(string_agg(event_cd, ',' order by log_id), '') from cms_equip_link_log where equip_id = ?", equipId);
    }

    private int openAlarms(Site s, long equipId, String code) {
        return count(s, "select count(*)::int from cms_alarm_log where equip_id = ? and alarm_cd = ? and cleared_at is null", equipId, code);
    }

    private int alarms(Site s, long equipId, String code) {
        return count(s, "select count(*)::int from cms_alarm_log where equip_id = ? and alarm_cd = ?", equipId, code);
    }

    private void policy(Site s, String key, String value) {
        accounts.exec(s.comp, "insert into sys_policy (comp_cd, policy_key, policy_value, created_by, updated_by) values (?, ?, ?, 't', 't')",
                s.comp, key, value);
    }

    /** IDLE 로 시작해 둔다(seq 1) */
    private void idle(Site s, String equipCd, String key) throws Exception {
        msgs(key, st(equipCd, 1, "IDLE", 0)).andExpect(status().isOk());
        drain(s);
    }

    // ================================================================== 수신 규약

    @Test
    void 상태_메시지는_원문_저장_후_200_처리는_따로_PackML_이_상태_원장에() throws Exception {
        msgs(a.edgeKey, st("EQ1", 1, "IDLE", 0), st("EQ1", 2, "STARTING", 1), st("EQ1", 3, "EXECUTE", 2))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.stored").value(3))
                .andExpect(jsonPath("$.data.items[2].result").value("STORED"));
        // 200 을 줬지만 아직 처리 전 — 원장은 비어 있다
        assertThat(ledger(a, a.eq1)).isEmpty();
        assertThat(inboundStatuses(a)).isEqualTo("NEW,NEW,NEW");
        assertThat(drain(a).done()).isEqualTo(3);
        // IDLE·STARTING 은 둘 다 대기(SBY)지만 PackML 상태가 달라 구간이 나뉜다
        assertThat(ledger(a, a.eq1)).isEqualTo("SBY/IDLE,SBY/STARTING,PRD/EXECUTE…");
        assertThat(str(a, "select pack_state || ':' || last_seq from cms_equip_link where equip_id = ?", a.eq1)).isEqualTo("EXECUTE:3");
        assertThat(inboundStatuses(a)).isEqualTo("DONE:APPLIED,DONE:APPLIED,DONE:APPLIED");
        // 행위자 = 보낸 장치
        assertThat(str(a, "select recorded_by from cms_equip_state_log where equip_id = ? order by start_at desc limit 1", a.eq1))
                .isEqualTo("d" + a.edgeId + ":EDGE-01");
        // 화면 목록: 연결 방식·PackML·분류·상위 상태(MachineryItemState)
        mvc.perform(get(API).with(a.admin)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data[?(@.equipCd=='EQ1')].packState").value("EXECUTE"))
                .andExpect(jsonPath("$.data[?(@.equipCd=='EQ1')].connMode").value("EDGE"));
    }

    @Test
    void 같은_콜백_297회_메시지_ID_매번_달라도_원문_행_하나_처리_한_번() throws Exception {
        for (int i = 0; i < 297; i++) {
            // 상대 메시지 ID(msgId)는 키도 내용도 아니다 — 현장 실측: 같은 콜백 297회, 모두 다른 ID
            msgs(a.edgeKey, st("EQ1", 1, "IDLE", 0, "\"msgId\":\"" + UUID.randomUUID() + "\""))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.items[0].result").value(i == 0 ? "STORED" : "DUP"));
        }
        assertThat(count(a, "select count(*)::int from sys_inbound_msg")).isEqualTo(1);
        assertThat(count(a, "select dup_count from sys_inbound_msg")).isEqualTo(296);
        drain(a);
        assertThat(ledger(a, a.eq1)).isEqualTo("SBY/IDLE…");
        // 처리 뒤에 또 와도 중복
        msgs(a.edgeKey, st("EQ1", 1, "IDLE", 0, "\"msgId\":\"late\"")).andExpect(jsonPath("$.data.items[0].result").value("DUP"));
        assertThat(drain(a).total()).isZero();
    }

    @Test
    void 같은_메시지가_동시에_10번_와도_원문_하나_나머지는_중복() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(10);
        try {
            List<Callable<String>> jobs = new ArrayList<>();
            for (int i = 0; i < 10; i++) {
                jobs.add(() -> JsonPath.read(msgs(a.edgeKey, st("EQ1", 1, "IDLE", 0)).andReturn().getResponse().getContentAsString(),
                        "$.data.items[0].result"));
            }
            List<String> results = new ArrayList<>();
            for (Future<String> f : pool.invokeAll(jobs)) {
                results.add(f.get());
            }
            assertThat(results).containsOnlyOnce("STORED").filteredOn("DUP"::equals).hasSize(9);
        } finally {
            pool.shutdownNow();
        }
        assertThat(count(a, "select count(*)::int from sys_inbound_msg")).isEqualTo(1);
    }

    @Test
    void 같은_키_다른_내용은_충돌_처리_안_함_사건과_알람() throws Exception {
        msgs(a.edgeKey, st("EQ1", 1, "IDLE", 0)).andExpect(jsonPath("$.data.items[0].result").value("STORED"));
        // 같은 seq 에 다른 상태 — 같은 키·다른 내용(VDA 5050 SAME_ORDER_UPDATE_ID 원칙)
        msgs(a.edgeKey, st("EQ1", 1, "STOPPED", 0)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.conflicts").value(1))
                .andExpect(jsonPath("$.data.items[0].result").value("CONFLICT"));
        assertThat(str(a, "select status || ':' || (conflict_of_id is not null) from sys_inbound_msg order by inbound_id desc limit 1"))
                .isEqualTo("CONFLICT:true");
        assertThat(events(a, a.eq1)).isEqualTo("CONFLICT");
        assertThat(openAlarms(a, a.eq1, "LINK_CONFLICT")).isEqualTo(1);
        drain(a);
        // 원래 것만 처리 — 충돌 증거는 처리하지 않는다
        assertThat(ledger(a, a.eq1)).isEqualTo("SBY/IDLE…");
        assertThat(inboundStatuses(a)).isEqualTo("DONE:APPLIED,CONFLICT");
        // 내용 칸 정규화: 공백·대소문자 같은 값은 같은 내용(충돌 아님) — 시각은 같은 순간이면 같은 글자여야 한다(그대로 비교)
        msgs(a.edgeKey, "{\"route\":\"EQUIP_STATE\",\"equipCd\":\"EQ1\",\"seq\":1.0,\"packState\":\"IDLE\",\"occurredAt\":\"" + t0 + "\"}")
                .andExpect(jsonPath("$.data.items[0].result").value("INVALID:ORDER"));
    }

    @Test
    void 같은_묶음에서_순서가_뒤바뀌면_바로잡고_이미_지난_seq_는_STALE_로_되돌리지_않음() throws Exception {
        // 같은 처리 묶음: 3·2·1 로 와도 1·2·3 으로 처리 — 누락(GAP) 없음
        msgs(a.edgeKey, st("EQ1", 3, "EXECUTE", 2), st("EQ1", 2, "STARTING", 1), st("EQ1", 1, "IDLE", 0)).andExpect(status().isOk());
        drain(a);
        assertThat(ledger(a, a.eq1)).isEqualTo("SBY/IDLE,SBY/STARTING,PRD/EXECUTE…");
        assertThat(events(a, a.eq1)).isEmpty();
        // 5 를 건너뛰고 6 → GAP(5~5) 후 수렴(COMPLETE 은 EXECUTE 에서 허용)
        msgs(a.edgeKey, st("EQ1", 4, "HELD", 3), st("EQ1", 6, "COMPLETE", 5)).andExpect(status().isOk());
        drain(a);
        assertThat(str(a, "select seq_from || '~' || seq_to from cms_equip_link_log where event_cd = 'GAP'")).isEqualTo("5~5");
        // 늦게 온 5(EXECUTE) — 이미 6 을 처리했으니 STALE, 원장은 그대로(COMPLETE)
        msgs(a.edgeKey, st("EQ1", 5, "EXECUTE", 4)).andExpect(status().isOk());
        drain(a);
        assertThat(ledger(a, a.eq1)).endsWith("SBY/COMPLETE…");
        assertThat(str(a, "select status || ':' || result_cd from sys_inbound_msg order by inbound_id desc limit 1")).isEqualTo("STALE:OLDER_SEQ");
        assertThat(events(a, a.eq1)).isEqualTo("GAP,REORDER,STALE");
        // seq 는 앞서지만 시각이 현재 상태보다 이르면(장비 시계 이상) 역시 STALE
        msgs(a.edgeKey, st("EQ1", 7, "IDLE", 1)).andExpect(status().isOk());
        drain(a);
        assertThat(str(a, "select status || ':' || result_cd from sys_inbound_msg order by inbound_id desc limit 1")).isEqualTo("STALE:OLDER_TIME");
        assertThat(ledger(a, a.eq1)).endsWith("SBY/COMPLETE…");
    }

    @Test
    void 허용_안_된_PackML_전이는_거부_사건_알람_snapshot_은_수렴() throws Exception {
        idle(a, "EQ1", a.edgeKey);
        // IDLE → EXECUTE(STARTING 건너뜀, 누락도 없음) = 거부
        msgs(a.edgeKey, st("EQ1", 2, "EXECUTE", 1)).andExpect(status().isOk());
        drain(a);
        assertThat(str(a, "select status || ':' || result_cd from sys_inbound_msg order by inbound_id desc limit 1")).isEqualTo("REJECTED:TRANSITION");
        assertThat(ledger(a, a.eq1)).isEqualTo("SBY/IDLE…");
        assertThat(events(a, a.eq1)).isEqualTo("DENIED");
        assertThat(str(a, "select detail from cms_equip_link_log where event_cd = 'DENIED'")).isEqualTo("IDLE → EXECUTE");
        assertThat(openAlarms(a, a.eq1, "PACKML_TRANSITION")).isEqualTo(1);
        // ABORTED → EXECUTE 도 거부(Clear → Stopped → Reset 을 거쳐야)
        // birth(재기동) 현재값은 snapshot — 중간 전이를 놓쳤을 수 있으니 받아들인다
        hb(a.edgeKey, "boot-2", "{\"equipCd\":\"EQ1\",\"seq\":3,\"packState\":\"EXECUTE\",\"occurredAt\":\"" + t0.plus(Duration.ofMinutes(2)) + "\"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.birth").value(true));
        drain(a);
        assertThat(ledger(a, a.eq1)).isEqualTo("SBY/IDLE,PRD/EXECUTE…");
        // 모드: 같은 EXECUTE 라도 정비 모드면 계획 정지(SDT)
        msgs(a.edgeKey, "{\"route\":\"EQUIP_STATE\",\"equipCd\":\"EQ1\",\"seq\":4,\"packState\":\"STOPPED\",\"mode\":\"MAINTENANCE\",\"occurredAt\":\""
                + t0.plus(Duration.ofMinutes(3)) + "\"}").andExpect(status().isOk());
        msgs(a.edgeKey, st("EQ1", 5, "ABORTED", 4), st("EQ1", 6, "EXECUTE", 5)).andExpect(status().isOk());
        drain(a);
        assertThat(ledger(a, a.eq1)).isEqualTo("SBY/IDLE,PRD/EXECUTE,SDT/STOPPED,UDT/ABORTED…");
        assertThat(str(a, "select string_agg(result_cd, ',' order by inbound_id) from sys_inbound_msg where status = 'REJECTED'"))
                .isEqualTo("TRANSITION,TRANSITION");
    }

    @Test
    void 처리_오류는_상대에게_가지_않고_격리_화면에서_다시_처리() throws Exception {
        policy(a, "EDGE_INBOUND_MAX_ATTEMPTS", "2");
        // 처리기가 예외를 내는 원문(순번 칸이 비어 경로가 NPE) — 엣지 API 로는 못 만드는 깨진 행을 직접 넣는다(일시 오류·프로그램 오류 대역)
        accounts.exec(a.comp, """
                insert into sys_inbound_msg (comp_cd, route_cd, source_id, source_cd, biz_key, key_hash, body_hash, body, part_key, occurred_at)
                values (?, 'EQUIP_STATE', ?, 'test', 'broken', repeat('a', 64), repeat('b', 64), ?::jsonb, 'EQ1', ?)""",
                a.comp, a.edgeId, "{\"equipCd\":\"EQ1\",\"packState\":\"IDLE\"}", java.sql.Timestamp.from(t0));
        // 정상 원문은 같이 처리된다 — 한 건 오류가 나머지를 막지 않는다
        msgs(a.adpKey, st("EQ2", 1, "IDLE", 0)).andExpect(status().isOk());
        assertThat(count(a, "select count(*)::int from cms_equip_link where equip_id = ?", a.eq2)).isEqualTo(1);
        InboundProcessor.DrainResult r1 = drain(a);
        assertThat(r1.retried()).isEqualTo(1);
        long bid = accounts.query(a.comp, "select inbound_id from sys_inbound_msg where biz_key = 'broken'", Long.class);
        assertThat(str(a, "select status || ':' || attempts || ':' || (next_try_at > now()) from sys_inbound_msg where inbound_id = ?", bid))
                .isEqualTo("NEW:1:true");
        // 뒤로 미룬 동안은 집지 않는다
        assertThat(drain(a).total()).isZero();
        accounts.exec(a.comp, "update sys_inbound_msg set next_try_at = now() where inbound_id = ?", bid);
        assertThat(drain(a).quarantined()).isEqualTo(1);
        assertThat(str(a, "select status || ':' || result_cd || ':' || (last_error like 'NullPointerException%') from sys_inbound_msg where inbound_id = ?",
                bid)).isEqualTo("QUARANTINE:ERROR:true");
        // 격리 목록 → 다시 처리(시도 수 초기화) — 격리가 아니면 409
        mvc.perform(get(API + "/inbound?status=QUARANTINE").with(a.admin)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1)).andExpect(jsonPath("$.data[0].biz_key").value("broken"));
        mvc.perform(post(API + "/inbound/" + bid + "/requeue").with(csrf()).with(a.admin)).andExpect(status().isOk());
        mvc.perform(post(API + "/inbound/" + bid + "/requeue").with(csrf()).with(a.admin)).andExpect(status().isConflict());
        assertThat(str(a, "select status || ':' || attempts from sys_inbound_msg where inbound_id = ?", bid)).isEqualTo("NEW:0");
        assertThat(drain(a).retried()).isEqualTo(1);
        // 정상 원문은 처음 묶음에서 처리됐다
        assertThat(ledger(a, a.eq2)).isEqualTo("SBY/IDLE…");
    }

    @Test
    void 과부하만_429_와_Retry_After_그때는_아무것도_저장하지_않음() throws Exception {
        policy(a, "EDGE_INBOUND_MAX_BACKLOG", "100");
        policy(a, "EDGE_INBOUND_RETRY_AFTER_SEC", "7");
        List<String> items = new ArrayList<>();
        for (int i = 1; i <= 100; i++) {
            items.add(st("EQ1", i, "IDLE", 0));
        }
        msgs(a.edgeKey, items.toArray(String[]::new)).andExpect(status().isOk()).andExpect(jsonPath("$.data.stored").value(100));
        msgs(a.edgeKey, st("EQ1", 101, "IDLE", 0)).andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "7"))
                .andExpect(jsonPath("$.code").value("TOO_MANY_REQUESTS"));
        assertThat(count(a, "select count(*)::int from sys_inbound_msg")).isEqualTo(100);
        drain(a);
        msgs(a.edgeKey, st("EQ1", 101, "IDLE", 0)).andExpect(status().isOk());
    }

    @Test
    void 형식_오류는_봉투면_400_건별이면_그_건만_INVALID() throws Exception {
        ext(MSG, a.edgeKey, "not json").andExpect(status().isBadRequest());
        ext(MSG, a.edgeKey, "{\"messages\":{}}").andExpect(status().isBadRequest());
        ext(MSG, a.edgeKey, "{\"messages\":[]}").andExpect(status().isBadRequest());
        ext(MSG, a.edgeKey, "{\"sentAt\":\"어제\",\"messages\":[" + st("EQ1", 1, "IDLE", 0) + "]}").andExpect(status().isBadRequest());
        List<String> many = new ArrayList<>();
        for (int i = 0; i < 501; i++) {
            many.add(st("EQ1", i, "IDLE", 0));
        }
        msgs(a.edgeKey, many.toArray(String[]::new)).andExpect(status().isBadRequest());
        // 한 건씩: 경로 없음·키 칸 없음·순번 형식·시각 형식 → 그 건만, 나머지는 저장
        msgs(a.edgeKey, "{\"route\":\"NOPE\"}", "{\"route\":\"EQUIP_STATE\",\"equipCd\":\"EQ1\",\"packState\":\"IDLE\"}",
                "{\"route\":\"EQUIP_STATE\",\"equipCd\":\"EQ1\",\"seq\":-1,\"packState\":\"IDLE\"}",
                "{\"route\":\"EQUIP_STATE\",\"equipCd\":\"EQ1\",\"seq\":1,\"packState\":\"IDLE\",\"occurredAt\":\"어제\"}", "7",
                st("EQ1", 1, "IDLE", 0))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.invalid").value(5))
                .andExpect(jsonPath("$.data.items[0].result").value("INVALID:ROUTE"))
                .andExpect(jsonPath("$.data.items[1].result").value("INVALID:KEY:seq"))
                .andExpect(jsonPath("$.data.items[2].result").value("INVALID:ORDER"))
                .andExpect(jsonPath("$.data.items[3].result").value("INVALID:OCCURRED_AT"))
                .andExpect(jsonPath("$.data.items[5].result").value("STORED"));
        // 저장은 됐지만 처리에서 거부: 모르는 상태·모드, 시각 없음, 미래(허용 오차 밖)
        msgs(a.edgeKey, "{\"route\":\"EQUIP_STATE\",\"equipCd\":\"EQ1\",\"seq\":2,\"packState\":\"RUNNING\",\"occurredAt\":\"" + t0 + "\"}",
                "{\"route\":\"EQUIP_STATE\",\"equipCd\":\"EQ1\",\"seq\":3,\"packState\":\"IDLE\",\"mode\":\"TURBO\",\"occurredAt\":\"" + t0 + "\"}",
                "{\"route\":\"EQUIP_STATE\",\"equipCd\":\"EQ1\",\"seq\":4,\"packState\":\"IDLE\"}",
                "{\"route\":\"EQUIP_STATE\",\"equipCd\":\"EQ1\",\"seq\":5,\"packState\":\"IDLE\",\"occurredAt\":\"" + Instant.now().plus(Duration.ofHours(1)) + "\"}")
                .andExpect(jsonPath("$.data.stored").value(4));
        drain(a);
        assertThat(str(a, "select string_agg(result_cd, ',' order by inbound_id) from sys_inbound_msg where status = 'REJECTED'"))
                .isEqualTo("STATE,MODE,OCCURRED_AT,FUTURE");
    }

    @Test
    void 시계_차이는_장치당_10분에_한_번_경고() throws Exception {
        msgs(a.edgeKey, Instant.now().minus(Duration.ofMinutes(10)), st("EQ1", 1, "IDLE", 0)).andExpect(status().isOk());
        msgs(a.edgeKey, Instant.now().minus(Duration.ofMinutes(10)), st("EQ1", 2, "STARTING", 1)).andExpect(status().isOk());
        assertThat(count(a, "select count(*)::int from cms_equip_link_log where event_cd = 'CLOCK_SKEW' and terminal_id = ?", a.edgeId))
                .isEqualTo(1);
        assertThat(count(a, "select count(*)::int from sys_inbound_msg where skew_ms between 590000 and 610000")).isEqualTo(2);
        // 시계가 맞으면 경고 없음
        msgs(a.adpKey, st("EQ2", 1, "IDLE", 0)).andExpect(status().isOk());
        assertThat(count(a, "select count(*)::int from cms_equip_link_log where event_cd = 'CLOCK_SKEW' and terminal_id = ?", a.adpId))
                .isZero();
    }

    // ================================================================== 회사·장치 범위

    @Test
    void A사_엣지_키로_B사_설비_상태_명령을_못_보내고_못_가져간다() throws Exception {
        Site b = site("EB", 0);
        // B사만 가진 설비
        long bOnly = equip(b.comp, "BX9");
        saveLink(b, bOnly, "{\"connMode\":\"EDGE\",\"terminalCd\":\"EDGE-01\",\"hbSec\":5,\"writeEnabledYn\":\"Y\",\"useYn\":\"Y\"}")
                .andExpect(status().isOk());
        // A사 키로 같은 설비 코드 EQ1 → A사 설비에만
        msgs(a.edgeKey, st("EQ1", 1, "IDLE", 0), st("BX9", 1, "IDLE", 0)).andExpect(status().isOk());
        drain(a);
        drain(b);
        assertThat(ledger(a, a.eq1)).isEqualTo("SBY/IDLE…");
        assertThat(ledger(b, b.eq1)).isEmpty();
        assertThat(ledger(b, bOnly)).isEmpty();
        assertThat(str(a, "select string_agg(status || ':' || coalesce(result_cd, ''), ',' order by inbound_id) from sys_inbound_msg"))
                .isEqualTo("DONE:APPLIED,REJECTED:EQUIP");
        assertThat(count(b, "select count(*)::int from sys_inbound_msg")).isZero();
        // 하트비트에 B사 설비 코드 → 모름, 아무것도 갱신 안 함
        hb(a.edgeKey, "boot-1", "{\"equipCd\":\"BX9\"}").andExpect(status().isOk()).andExpect(jsonPath("$.data.unknown[0]").value("BX9"));
        assertThat(count(b, "select count(*)::int from cms_equip_link where last_seen_at is not null")).isZero();
        // B사 명령: A사 엣지는 가져가지 못하고, B사 cmdKey 로 결과를 보내도 거부
        tag(b, b.eq1, "{\"tagCd\":\"SPEED_SP\",\"tagNm\":\"속도\",\"dataType\":\"NUM\",\"minValue\":\"0\",\"maxValue\":\"100\",\"useYn\":\"Y\"}")
                .andExpect(status().isOk());
        String bKey = JsonPath.read(command(b, b.eq1, "SPEED_SP", "50").andExpect(status().isOk()).andReturn().getResponse().getContentAsString(),
                "$.data.cmdKey");
        ext(CLAIM, a.edgeKey, "{}").andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(0));
        msgs(a.edgeKey, result(bKey, "RESULT", "DONE", "50", null)).andExpect(status().isOk());
        drain(a);
        assertThat(str(a, "select result_cd from sys_inbound_msg order by inbound_id desc limit 1")).isEqualTo("CMD");
        assertThat(str(b, "select status from cms_equip_cmd")).isEqualTo("REQUESTED");
        // B사 엣지는 자기 것을 가져간다
        ext(CLAIM, b.edgeKey, "{}").andExpect(jsonPath("$.data[0].cmdKey").value(bKey));
        // 같은 회사라도 그 설비를 맡지 않은 장치 → 거부, 명령도 안 보인다
        msgs(a.otherKey, st("EQ1", 2, "STARTING", 1)).andExpect(status().isOk());
        drain(a);
        assertThat(str(a, "select result_cd from sys_inbound_msg order by inbound_id desc limit 1")).isEqualTo("NOT_MY_EQUIP");
        ext(CLAIM, a.otherKey, "{}").andExpect(jsonPath("$.data.length()").value(0));
        // 키 없음·틀린 키·키 앞 회사를 B사로 바꾼 위조 → 401
        ext(MSG, null, "{\"messages\":[" + st("EQ1", 9, "IDLE", 0) + "]}").andExpect(status().isUnauthorized());
        ext(MSG, a.edgeKey + "x", "{\"messages\":[" + st("EQ1", 9, "IDLE", 0) + "]}").andExpect(status().isUnauthorized());
        ext(MSG, b.comp + a.edgeKey.substring(a.edgeKey.indexOf('.')), "{\"messages\":[" + st("EQ1", 9, "IDLE", 0) + "]}")
                .andExpect(status().isUnauthorized());
        // B사 화면에서 A사 설비 연동은 안 보인다(같은 설비 코드여도 B사 것만)
        mvc.perform(get(API).with(b.admin)).andExpect(jsonPath("$.data[?(@.equipId==" + a.eq1 + ")]").isEmpty());
        mvc.perform(get(API + "/" + a.eq1).with(b.admin)).andExpect(jsonPath("$.data.link").doesNotExist());
    }

    // ================================================================== 하트비트·끊김

    @Test
    void 하트비트가_끊기면_알람_한_번_UNKNOWN_구간_다시_오면_해제_복귀() throws Exception {
        idle(a, "EQ1", a.edgeKey);
        hb(a.edgeKey, "boot-1", "{\"equipCd\":\"EQ1\"}").andExpect(status().isOk()).andExpect(jsonPath("$.data.birth").value(true));
        assertThat(events(a, a.eq1)).isEmpty();      // birth 는 장치 사건(설비 없음)
        assertThat(count(a, "select count(*)::int from cms_equip_link_log where event_cd = 'BIRTH' and terminal_id = ?", a.edgeId)).isEqualTo(1);
        mvc.perform(get(API).with(a.admin)).andExpect(jsonPath("$.data[?(@.equipCd=='EQ1')].judge").value("ONLINE"));
        // 기준(5초 × 3) 안이면 그대로
        runner.run(a.comp, () -> assertThat(links.judgeLost()).isZero());
        // 마지막 수신을 1분 전으로 — "안 온 것" 을 사건으로
        accounts.exec(a.comp, "update cms_equip_link set last_seen_at = now() - interval '60 seconds' where equip_id = ?", a.eq1);
        mvc.perform(get(API).with(a.admin)).andExpect(jsonPath("$.data[?(@.equipCd=='EQ1')].judge").value("LOST"))
                .andExpect(jsonPath("$.data[?(@.equipCd=='EQ1')].machineryState").value("NotAvailable"));
        runner.run(a.comp, () -> assertThat(links.judgeLost()).isEqualTo(1));
        runner.run(a.comp, () -> assertThat(links.judgeLost()).isZero());          // 두 번 알리지 않는다
        assertThat(alarms(a, a.eq1, "LINK_LOST")).isEqualTo(1);
        assertThat(openAlarms(a, a.eq1, "LINK_LOST")).isEqualTo(1);
        assertThat(ledger(a, a.eq1)).isEqualTo("SBY/IDLE,UNKNOWN/-…");
        assertThat(events(a, a.eq1)).isEqualTo("LOST");
        // 엣지는 살아 있지만 그 장비와 끊김(online false) — 복귀 아님
        hb(a.edgeKey, "boot-1", "{\"equipCd\":\"EQ1\",\"online\":false}").andExpect(status().isOk());
        assertThat(openAlarms(a, a.eq1, "LINK_LOST")).isEqualTo(1);
        // 다시 옴 → 알람 해제·UNKNOWN 닫기·복귀 사건, 실린 현재값(snapshot)으로 수렴
        hb(a.edgeKey, "boot-1", "{\"equipCd\":\"EQ1\",\"seq\":2,\"packState\":\"STOPPED\",\"occurredAt\":\"" + Instant.now().minusSeconds(5) + "\"}")
                .andExpect(status().isOk());
        assertThat(openAlarms(a, a.eq1, "LINK_LOST")).isZero();
        assertThat(events(a, a.eq1)).isEqualTo("LOST,BACK");
        drain(a);
        assertThat(ledger(a, a.eq1)).isEqualTo("SBY/IDLE,UNKNOWN/-,SBY/STOPPED…");
        assertThat(str(a, "select (lost_at is null)::text from cms_equip_link where equip_id = ?", a.eq1)).isEqualTo("true");
    }

    @Test
    void 소모품_부족_경고는_수준이_바뀔_때만_알람() throws Exception {
        String low = "{\"equipCd\":\"EQ1\",\"consumables\":[{\"cd\":\"RIBBON\",\"level\":\"LOW\"},{\"cd\":\"PAPER\",\"level\":\"OK\"}]}";
        for (int i = 0; i < 3; i++) {
            hb(a.edgeKey, "boot-1", low).andExpect(status().isOk());
        }
        assertThat(alarms(a, a.eq1, "CONS_LOW.RIBBON")).isEqualTo(1);
        assertThat(alarms(a, a.eq1, "CONS_LOW.PAPER")).isZero();
        hb(a.edgeKey, "boot-1", "{\"equipCd\":\"EQ1\",\"consumables\":[{\"cd\":\"RIBBON\",\"level\":\"EMPTY\"}]}").andExpect(status().isOk());
        assertThat(openAlarms(a, a.eq1, "CONS_LOW.RIBBON")).isZero();
        assertThat(openAlarms(a, a.eq1, "CONS_EMPTY.RIBBON")).isEqualTo(1);
        hb(a.edgeKey, "boot-1", "{\"equipCd\":\"EQ1\",\"consumables\":[{\"cd\":\"RIBBON\",\"level\":\"OK\"},{\"cd\":\"bad cd\",\"level\":\"LOW\"}]}")
                .andExpect(status().isOk());
        assertThat(count(a, "select count(*)::int from cms_alarm_log where equip_id = ? and cleared_at is null", a.eq1)).isZero();
        mvc.perform(get(API + "/" + a.eq1 + "/consumables").with(a.admin)).andExpect(jsonPath("$.data.RIBBON").value("OK"));
        // 하트비트 형식 오류
        ext(HB, a.edgeKey, "{\"sentAt\":\"" + Instant.now() + "\"}").andExpect(status().isBadRequest());
        ext(HB, a.edgeKey, "{\"bootId\":\"한글\"}").andExpect(status().isBadRequest());
    }

    // ================================================================== 명령 4단 — 설치형(DIRECT)·SaaS(EDGE) 같은 시험

    private ResultActions command(Site s, long equipId, String tagCd, String value) throws Exception {
        return mvc.perform(post(API + "/" + equipId + "/commands").with(csrf()).with(s.admin).header("X-Idempotency-Key", "k-" + UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"tagCd\":\"" + tagCd + "\",\"value\":\"" + value + "\",\"reason\":\"속도 조정\"}"));
    }

    private static String result(String cmdKey, String phase, String status, String readback, String errors) {
        return "{\"route\":\"CMD_RESULT\",\"cmdKey\":\"" + cmdKey + "\",\"phase\":\"" + phase + "\""
                + (status == null ? "" : ",\"status\":\"" + status + "\"") + (readback == null ? "" : ",\"readback\":\"" + readback + "\"")
                + (phase.equals("WRITE") ? ",\"ok\":true,\"value\":\"" + readback + "\"" : "")
                + (errors == null ? "" : ",\"errors\":" + errors) + ",\"occurredAt\":\"" + Instant.now() + "\"}";
    }

    private String steps(Site s, String cmdKey) {
        return str(s, """
                select string_agg(phase || coalesce(':' || ok_yn, ''), ',' order by step_id) from cms_equip_cmd_step
                 where cmd_id = (select cmd_id from cms_equip_cmd where cmd_key = ?::uuid)""", cmdKey);
    }

    private String cmdStatus(Site s, String cmdKey) {
        return str(s, "select status || coalesce(':' || result_cd, '') from cms_equip_cmd where cmd_key = ?::uuid", cmdKey);
    }

    /**
     * 한 연결 방식의 명령 흐름 전체 — 보내기만 방식별(deliver), 1차 검사·결과 보고·판정은 같다.
     * @param deliver 명령 키 → 장치가 받은 값(EDGE = claim, DIRECT = 아웃박스 릴레이 → 어댑터 POST)
     */
    private void commandFlow(Site s, long equipId, String equipCd, String deviceKey, String sentStatus, Function<String, String> deliver)
            throws Exception {
        tag(s, equipId, "{\"tagCd\":\"SPEED_SP\",\"tagNm\":\"속도 설정\",\"dataType\":\"NUM\",\"minValue\":\"0\",\"maxValue\":\"100\",\"unit\":\"m/min\","
                + "\"allowedStates\":[\"IDLE\",\"STOPPED\"],\"useYn\":\"Y\"}").andExpect(status().isOk());
        // 인터록: 지금 상태를 모르면 거부 → IDLE 이 된 뒤 허용
        command(s, equipId, "SPEED_SP", "55").andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("EQUIP_CMD_INTERLOCK"));
        idle(s, equipCd, deviceKey);
        // 1차 검사: 범위·형식·없는 태그·사유
        command(s, equipId, "SPEED_SP", "101").andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("value"));
        command(s, equipId, "SPEED_SP", "abc").andExpect(status().isBadRequest());
        command(s, equipId, "NOPE", "1").andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("tagCd"));
        mvc.perform(post(API + "/" + equipId + "/commands").with(csrf()).with(s.admin).header("X-Idempotency-Key", "k-reason-" + equipId)
                .contentType(MediaType.APPLICATION_JSON).content("{\"tagCd\":\"SPEED_SP\",\"value\":\"5\",\"reason\":\" \"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("reason"));
        // 요청 키 없으면 400, 같은 키 두 번 = 409
        mvc.perform(post(API + "/" + equipId + "/commands").with(csrf()).with(s.admin).contentType(MediaType.APPLICATION_JSON)
                .content("{\"tagCd\":\"SPEED_SP\",\"value\":\"55\",\"reason\":\"r\"}")).andExpect(status().isBadRequest());
        String body = mvc.perform(post(API + "/" + equipId + "/commands").with(csrf()).with(s.admin).header("X-Idempotency-Key", "cmd-same-key-" + equipId)
                .contentType(MediaType.APPLICATION_JSON).content("{\"tagCd\":\"spead_sp\",\"value\":\"055.0\",\"reason\":\"속도 조정\"}"))
                .andExpect(status().isBadRequest()).andReturn().getResponse().getContentAsString();
        assertThat(body).contains("tagCd");
        String created = mvc.perform(post(API + "/" + equipId + "/commands").with(csrf()).with(s.admin).header("X-Idempotency-Key", "cmd-ok-key-" + equipId)
                .contentType(MediaType.APPLICATION_JSON).content("{\"tagCd\":\"speed_sp\",\"value\":\"055.0\",\"reason\":\"속도 조정\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.cmdValue").value("55"))
                .andExpect(jsonPath("$.data.status").value(sentStatus))
                .andReturn().getResponse().getContentAsString();
        mvc.perform(post(API + "/" + equipId + "/commands").with(csrf()).with(s.admin).header("X-Idempotency-Key", "cmd-ok-key-" + equipId)
                .contentType(MediaType.APPLICATION_JSON).content("{\"tagCd\":\"speed_sp\",\"value\":\"60\",\"reason\":\"다시\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DUPLICATE_REQUEST"));
        String key = JsonPath.read(created, "$.data.cmdKey");
        // 같은 태그에 진행 중 명령은 하나
        command(s, equipId, "SPEED_SP", "60").andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("EQUIP_CMD_BUSY"));
        // 보내기(방식별) — 장치가 받은 값은 정규화된 의도 값
        assertThat(deliver.apply(key)).isEqualTo("55");
        // 결과 보고는 두 방식 모두 같은 수신 API: 쓰기 → 다시 읽기 → 결과(서버가 다시 대조)
        msgs(deviceKey, result(key, "WRITE", null, "55", null), result(key, "READBACK", null, "55.00", null),
                result(key, "RESULT", "DONE", "55.000", null)).andExpect(status().isOk());
        drain(s);
        assertThat(cmdStatus(s, key)).isEqualTo("DONE");
        assertThat(str(s, "select readback from cms_equip_cmd where cmd_key = ?::uuid", key)).isEqualTo("55");
        assertThat(steps(s, key)).endsWith("WRITE:Y,READBACK:Y,RESULT:Y").startsWith("INTENT");
        // 같은 결과가 또 와도(재전송) 중복
        msgs(deviceKey, result(key, "RESULT", "DONE", "55.000", null)).andExpect(jsonPath("$.data.items[0].result").value("DUP"));

        // 완료 + 오류 동반 = 실패
        String k2 = JsonPath.read(command(s, equipId, "SPEED_SP", "40").andExpect(status().isOk()).andReturn().getResponse().getContentAsString(),
                "$.data.cmdKey");
        deliver.apply(k2);
        msgs(deviceKey, result(k2, "RESULT", "FINISHED", "40", "[{\"errorType\":\"noRouteError\"}]")).andExpect(status().isOk());
        drain(s);
        assertThat(cmdStatus(s, k2)).isEqualTo("FAILED:ERRORS");
        // 엣지가 완료라 해도 다시 읽은 값이 다르면 실패
        String k3 = JsonPath.read(command(s, equipId, "SPEED_SP", "41").andExpect(status().isOk()).andReturn().getResponse().getContentAsString(),
                "$.data.cmdKey");
        deliver.apply(k3);
        msgs(deviceKey, result(k3, "RESULT", "DONE", "40", null)).andExpect(status().isOk());
        drain(s);
        assertThat(cmdStatus(s, k3)).isEqualTo("FAILED:VERIFY_MISMATCH");
        // 엣지 2차 검사 거부(자체 허용 목록·인터록)
        String k4 = JsonPath.read(command(s, equipId, "SPEED_SP", "42").andExpect(status().isOk()).andReturn().getResponse().getContentAsString(),
                "$.data.cmdKey");
        deliver.apply(k4);
        msgs(deviceKey, "{\"route\":\"CMD_RESULT\",\"cmdKey\":\"" + k4 + "\",\"phase\":\"RESULT\",\"status\":\"REJECTED\",\"reason\":\"INTERLOCK\"}")
                .andExpect(status().isOk());
        drain(s);
        assertThat(cmdStatus(s, k4)).isEqualTo("REJECTED:INTERLOCK");
        // 순서 뒤바뀜: 결과가 먼저, 쓰기가 나중 — 단계는 모두 남고 끝난 상태는 그대로
        String k5 = JsonPath.read(command(s, equipId, "SPEED_SP", "43").andExpect(status().isOk()).andReturn().getResponse().getContentAsString(),
                "$.data.cmdKey");
        deliver.apply(k5);
        msgs(deviceKey, result(k5, "RESULT", "DONE", "43", null)).andExpect(status().isOk());
        drain(s);
        msgs(deviceKey, result(k5, "WRITE", null, "43", null)).andExpect(status().isOk());
        drain(s);
        assertThat(cmdStatus(s, k5)).isEqualTo("DONE");
        assertThat(steps(s, k5)).endsWith("RESULT:Y,WRITE:Y");
        // 명령 목록 API(단계 포함)
        mvc.perform(get(API + "/" + equipId + "/commands").with(s.admin)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].steps.length()").value(org.hamcrest.Matchers.greaterThan(1)));
    }

    @Test
    void 명령_4단_SaaS_EDGE_엣지가_당겨_감() throws Exception {
        commandFlow(a, a.eq1, "EQ1", a.edgeKey, "REQUESTED", key -> {
            try {
                String body = ext(CLAIM, a.edgeKey, "{\"max\":5}").andExpect(status().isOk())
                        .andExpect(jsonPath("$.data[0].cmdKey").value(key))
                        .andExpect(jsonPath("$.data[0].tagCd").value("SPEED_SP"))
                        .andReturn().getResponse().getContentAsString();
                // lease 를 잡았으니 다시 당겨도 없다
                ext(CLAIM, a.edgeKey, "{}").andExpect(jsonPath("$.data.length()").value(0));
                assertThat(cmdStatus(a, key)).isEqualTo("CLAIMED");
                return JsonPath.read(body, "$.data[0].value");
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        // 서버에는 장비 주소(Modbus 등)가 없다 — 논리 태그만
        assertThat(count(a, "select count(*)::int from information_schema.columns where table_name = 'cms_equip_tag' and column_name like '%addr%'"))
                .isZero();
    }

    @Test
    void 명령_4단_설치형_DIRECT_아웃박스가_어댑터로_보냄() throws Exception {
        List<String> received = new CopyOnWriteArrayList<>();
        HttpServer adapter = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        adapter.createContext("/commands", ex -> {
            try (InputStream in = ex.getRequestBody()) {
                received.add(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            }
            ex.sendResponseHeaders(202, -1);
            ex.close();
        });
        adapter.start();
        try {
            Site d = site("ED", adapter.getAddress().getPort());
            commandFlow(d, d.eq2, "EQ2", d.adpKey, "SENT", key -> {
                relay.relay(d.comp);
                String last = received.get(received.size() - 1);
                assertThat(last).contains("\"cmdKey\":\"" + key + "\"").contains("\"tagCd\":\"SPEED_SP\"").doesNotContain("url");
                assertThat(str(d, "select status from sys_outbox where biz_key = (select 'cmd:' || cmd_id from cms_equip_cmd where cmd_key = ?::uuid)",
                        key)).isEqualTo("SENT");
                return JsonPath.read(last, "$.value");
            });
            // DIRECT 어댑터는 EDGE 큐에서 가져갈 것이 없다
            ext(CLAIM, d.adpKey, "{}").andExpect(jsonPath("$.data.length()").value(0));
        } finally {
            adapter.stop(0);
        }
    }

    @Autowired
    EquipCmdHandler cmdHandler;

    /** docs/67 F3 — SaaS(사설망 직접 연결 끔)에서는 DIRECT 저장이 막히고, 예전에 저장된 DIRECT 연동의 명령도 어댑터로 가지 않는다 */
    @Test
    void F3_사설망_직접_연결이_꺼지면_DIRECT_저장과_어댑터_송신이_막힌다() throws Exception {
        List<String> received = new CopyOnWriteArrayList<>();
        HttpServer adapter = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        adapter.createContext("/commands", ex -> {
            try (InputStream in = ex.getRequestBody()) {
                received.add(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            }
            ex.sendResponseHeaders(202, -1);
            ex.close();
        });
        adapter.start();
        io.smarthappy.platform.config.LanDirectGate on = io.smarthappy.platform.config.LanDirectGate.fixed(true);
        io.smarthappy.platform.config.LanDirectGate off = io.smarthappy.platform.config.LanDirectGate.fixed(false);
        try {
            Site d = site("EF", adapter.getAddress().getPort());   // 켜진 상태에서 저장된 DIRECT 연동(옛 데이터)
            tag(d, d.eq2, "{\"tagCd\":\"SPEED_SP\",\"tagNm\":\"속도\",\"dataType\":\"NUM\",\"minValue\":\"0\",\"maxValue\":\"100\","
                    + "\"allowedStates\":[\"IDLE\",\"STOPPED\"],\"useYn\":\"Y\"}").andExpect(status().isOk());
            idle(d, "EQ2", d.adpKey);
            links.setLanDirect(off);
            cmdHandler.setLanDirect(off);
            // 새 DIRECT 저장 거부, EDGE 는 그대로
            saveLink(d, d.eq3, "{\"connMode\":\"DIRECT\",\"terminalCd\":\"ADP-01\",\"adapterUrl\":\"http://10.0.0.5:8088\",\"useYn\":\"Y\"}")
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("connMode"))
                    .andExpect(jsonPath("$.errors[0].code").value("LAN_DIRECT_OFF"));
            saveLink(d, d.eq3, "{\"connMode\":\"EDGE\",\"terminalCd\":\"EDGE-01\",\"useYn\":\"Y\"}").andExpect(status().isOk());
            // 옛 DIRECT 연동의 명령: 아웃박스는 생기지만 송신기가 재시도 없이 FAIL — 어댑터는 아무것도 받지 않는다
            String created = command(d, d.eq2, "SPEED_SP", "55").andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            String key = JsonPath.read(created, "$.data.cmdKey");
            relay.relay(d.comp);
            assertThat(str(d, "select status from sys_outbox where biz_key = (select 'cmd:' || cmd_id from cms_equip_cmd where cmd_key = ?::uuid)",
                    key)).isEqualTo("FAIL");
            assertThat(received).isEmpty();
        } finally {
            links.setLanDirect(on);
            cmdHandler.setLanDirect(on);
            adapter.stop(0);
        }
    }

    @Test
    void 명령_시간_초과_안_보낸_것은_만료_보낸_것은_결과_불명_늦은_결과나_사람이_확정() throws Exception {
        tag(a, a.eq1, "{\"tagCd\":\"SPEED_SP\",\"tagNm\":\"속도\",\"dataType\":\"NUM\",\"minValue\":\"0\",\"maxValue\":\"100\",\"useYn\":\"Y\"}")
                .andExpect(status().isOk());
        tag(a, a.eq1, "{\"tagCd\":\"RUN\",\"tagNm\":\"운전\",\"dataType\":\"BOOL\",\"useYn\":\"Y\"}").andExpect(status().isOk());
        String k1 = JsonPath.read(command(a, a.eq1, "SPEED_SP", "10").andReturn().getResponse().getContentAsString(), "$.data.cmdKey");
        String k2 = JsonPath.read(command(a, a.eq1, "RUN", "1").andExpect(jsonPath("$.data.cmdValue").value("true"))
                .andReturn().getResponse().getContentAsString(), "$.data.cmdKey");
        ext(CLAIM, a.edgeKey, "{\"max\":1}").andExpect(jsonPath("$.data[0].cmdKey").value(k1));
        // 시간 초과(정책 120초) 뒤 판정
        clock.setClock(Clock.fixed(Instant.now().plus(Duration.ofMinutes(10)), ZoneOffset.UTC));
        runner.run(a.comp, () -> assertThat(commands.expireOverdue()).isEqualTo(2));
        assertThat(cmdStatus(a, k1)).isEqualTo("UNKNOWN:NO_RESULT");       // 가져갔다 — 썼을 수 있다
        assertThat(cmdStatus(a, k2)).isEqualTo("EXPIRED:NOT_DELIVERED");   // 가져가기 전 — 안 썼다
        // 만료된 것은 이제 가져갈 수 없다(자동 재전송 없음)
        clock.setClock(Clock.systemUTC());
        ext(CLAIM, a.edgeKey, "{}").andExpect(jsonPath("$.data.length()").value(0));
        // 늦게 온 결과가 결과 불명을 확정한다
        msgs(a.edgeKey, result(k1, "RESULT", "DONE", "10", null)).andExpect(status().isOk());
        drain(a);
        assertThat(cmdStatus(a, k1)).isEqualTo("DONE");
        // 만료된 명령에 결과가 와도 바뀌지 않는다(단계만)
        msgs(a.edgeKey, result(k2, "RESULT", "DONE", "true", null)).andExpect(status().isOk());
        drain(a);
        assertThat(cmdStatus(a, k2)).isEqualTo("EXPIRED:NOT_DELIVERED");
        assertThat(steps(a, k2)).endsWith("TIMEOUT:N,RESULT:Y");
        // 사람 확정: UNKNOWN 만
        String k3 = JsonPath.read(command(a, a.eq1, "SPEED_SP", "11").andReturn().getResponse().getContentAsString(), "$.data.cmdKey");
        ext(CLAIM, a.edgeKey, "{}").andExpect(status().isOk());
        clock.setClock(Clock.fixed(Instant.now().plus(Duration.ofMinutes(10)), ZoneOffset.UTC));
        runner.run(a.comp, () -> commands.expireOverdue());
        clock.setClock(Clock.systemUTC());
        long id3 = accounts.query(a.comp, "select cmd_id from cms_equip_cmd where cmd_key = ?::uuid", Long.class, k3);
        long id2 = accounts.query(a.comp, "select cmd_id from cms_equip_cmd where cmd_key = ?::uuid", Long.class, k2);
        mvc.perform(post(API + "/" + a.eq1 + "/commands/" + id2 + "/resolve").with(csrf()).with(a.admin).contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"DONE\",\"note\":\"현장 확인\"}")).andExpect(status().isConflict());
        mvc.perform(post(API + "/" + a.eq1 + "/commands/" + id3 + "/resolve").with(csrf()).with(a.admin).contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"FAILED\",\"note\":\"\"}")).andExpect(status().isBadRequest());
        mvc.perform(post(API + "/" + a.eq1 + "/commands/" + id3 + "/resolve").with(csrf()).with(a.admin).contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"FAILED\",\"note\":\"HMI 값 그대로\"}")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("FAILED"));
        assertThat(steps(a, k3)).endsWith("TIMEOUT:N,RESOLVE:N");
    }

    @Test
    void 명령_쓰기는_FN1_권한과_쓰기_활성화가_따로_필요() throws Exception {
        tag(a, a.eq1, "{\"tagCd\":\"SPEED_SP\",\"tagNm\":\"속도\",\"dataType\":\"NUM\",\"minValue\":\"0\",\"maxValue\":\"100\",\"useYn\":\"Y\"}")
                .andExpect(status().isOk());
        long viewer = accounts.newUser(a.comp, "viewer1", PW);
        accounts.grant(a.comp, viewer, "VIEW", EquipLinkService.PROG, "SEARCH", "SAVE");
        RequestPostProcessor v = accounts.as(a.comp, viewer, "viewer1");
        // 화면에서 숨긴 버튼은 막은 것이 아니다 — 같은 요청을 API 로
        mvc.perform(post(API + "/" + a.eq1 + "/commands").with(csrf()).with(v).header("X-Idempotency-Key", "k-viewer-1")
                .contentType(MediaType.APPLICATION_JSON).content("{\"tagCd\":\"SPEED_SP\",\"value\":\"5\",\"reason\":\"r\"}"))
                .andExpect(status().isForbidden());
        // 쓰기 활성화를 끄면 FN1 이 있어도 거부
        saveLink(a, a.eq1, "{\"connMode\":\"EDGE\",\"terminalCd\":\"EDGE-01\",\"hbSec\":5,\"writeEnabledYn\":\"N\",\"useYn\":\"Y\"}")
                .andExpect(status().isOk());
        command(a, a.eq1, "SPEED_SP", "5").andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("EQUIP_WRITE_DISABLED"));
        // DIRECT 인데 어댑터 주소 없음
        tag(a, a.eq2, "{\"tagCd\":\"SPEED_SP\",\"tagNm\":\"속도\",\"dataType\":\"NUM\",\"minValue\":\"0\",\"maxValue\":\"100\",\"useYn\":\"Y\"}")
                .andExpect(status().isOk());
        command(a, a.eq2, "SPEED_SP", "5").andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("EQUIP_NO_ADAPTER"));
    }

    // ================================================================== 설정 저장

    @Test
    void 연동_설정_검증_사설_대역_장치_유형_동시_편집() throws Exception {
        // 어댑터 주소는 사설 대역만, DIRECT 만
        saveLink(a, a.eq3, "{\"connMode\":\"DIRECT\",\"terminalCd\":\"ADP-01\",\"adapterUrl\":\"http://8.8.8.8:80\",\"useYn\":\"Y\"}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].code").value("EQUIP_URL_PRIVATE"));
        saveLink(a, a.eq3, "{\"connMode\":\"DIRECT\",\"terminalCd\":\"ADP-01\",\"adapterUrl\":\"http://169.254.169.254/latest\",\"useYn\":\"Y\"}")
                .andExpect(status().isBadRequest());
        saveLink(a, a.eq3, "{\"connMode\":\"EDGE\",\"terminalCd\":\"EDGE-01\",\"adapterUrl\":\"http://10.0.0.5:8088\",\"useYn\":\"Y\"}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].code").value("EQUIP_URL_DIRECT_ONLY"));
        saveLink(a, a.eq3, "{\"connMode\":\"MQTT\",\"terminalCd\":\"NOPE\",\"hbSec\":0,\"useYn\":\"Y\"}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.length()").value(3));
        saveLink(a, a.eq3, "{\"connMode\":\"DIRECT\",\"terminalCd\":\"ADP-01\",\"adapterUrl\":\"http://10.0.0.5:8088\",\"useYn\":\"Y\"}")
                .andExpect(status().isOk());
        // 낡은 rowVer = 409
        mvc.perform(put(API + "/" + a.eq3).with(csrf()).with(a.admin).contentType(MediaType.APPLICATION_JSON)
                .content("{\"connMode\":\"EDGE\",\"terminalCd\":\"EDGE-01\",\"useYn\":\"Y\",\"rowVer\":99}")).andExpect(status().isConflict());
        // POP 단말(쿠키 키)은 대신 말하는 장치가 될 수 없다 — 트리거가 최종
        Throwable t = catchThrowable(() -> accounts.exec(a.comp, """
                insert into sys_terminal (comp_cd, terminal_cd, terminal_nm, plant_cd, terminal_type, created_by, updated_by)
                values (?, 'POP-1', 'POP', 'P1', 'POP', 't', 't')""", a.comp));
        assertThat(t).isNull();
        Throwable t2 = catchThrowable(() -> accounts.exec(a.comp, """
                update cms_equip_link set terminal_id = (select terminal_id from sys_terminal where terminal_cd = 'POP-1') where equip_id = ?""",
                a.eq3));
        assertThat(PgErrors.constraint(t2)).contains("fk_cms_equip_link_terminal_comp");
        // 태그: 코드·형식 고정, 범위 검증
        tag(a, a.eq3, "{\"tagCd\":\"SP\",\"tagNm\":\"x\",\"dataType\":\"NUM\",\"minValue\":\"5\",\"maxValue\":\"1\",\"useYn\":\"Y\"}")
                .andExpect(status().isBadRequest());
        tag(a, a.eq3, "{\"tagCd\":\"SP\",\"tagNm\":\"x\",\"dataType\":\"NUM\",\"minValue\":\"0\",\"maxValue\":\"1\",\"allowedStates\":[\"RUN\"]}")
                .andExpect(status().isBadRequest());
        tag(a, a.eq3, "{\"tagCd\":\"SP\",\"tagNm\":\"x\",\"dataType\":\"NUM\",\"minValue\":\"0\",\"maxValue\":\"1\"}").andExpect(status().isOk());
        tag(a, a.eq3, "{\"tagCd\":\"SP\",\"tagNm\":\"x\",\"dataType\":\"NUM\",\"minValue\":\"0\",\"maxValue\":\"1\"}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].code").value("DUPLICATE"));
        Throwable t3 = catchThrowable(() -> accounts.exec(a.comp, "update cms_equip_tag set data_type = 'BOOL', min_value = null, max_value = null where tag_cd = 'SP'"));
        assertThat(PgErrors.constraint(t3)).contains("ck_cms_equip_tag_fixed");
    }

    @Test
    void 원장_직접_SQL_단계_사건_수정_삭제_원문_칸_고정() throws Exception {
        idle(a, "EQ1", a.edgeKey);
        msgs(a.edgeKey, st("EQ1", 3, "STOPPED", 1)).andExpect(status().isOk());
        drain(a);
        assertThat(events(a, a.eq1)).isEqualTo("GAP");
        assertThat(catchThrowable(() -> accounts.exec(a.comp, "update cms_equip_link_log set detail = 'x'"))).isNotNull();
        assertThat(catchThrowable(() -> accounts.exec(a.comp, "delete from cms_equip_link_log"))).isNotNull();
        assertThat(catchThrowable(() -> accounts.exec(a.comp, "update sys_inbound_msg set body = '{}'::jsonb"))).isNotNull();
        assertThat(catchThrowable(() -> accounts.exec(a.comp, "update sys_inbound_msg set key_hash = repeat('0', 64)"))).isNotNull();
        assertThat(catchThrowable(() -> accounts.exec(a.comp, "delete from sys_inbound_msg"))).isNotNull();
        tag(a, a.eq1, "{\"tagCd\":\"SPEED_SP\",\"tagNm\":\"속도\",\"dataType\":\"NUM\",\"minValue\":\"0\",\"maxValue\":\"100\"}").andExpect(status().isOk());
        command(a, a.eq1, "SPEED_SP", "5").andExpect(status().isOk());
        assertThat(catchThrowable(() -> accounts.exec(a.comp, "update cms_equip_cmd_step set detail = 'x'"))).isNotNull();
        assertThat(catchThrowable(() -> accounts.exec(a.comp, "delete from cms_equip_cmd_step"))).isNotNull();
        // 의도 칸 고정 · 끝난 명령 고정
        Throwable t = catchThrowable(() -> accounts.exec(a.comp, "update cms_equip_cmd set cmd_value = '99'"));
        assertThat(PgErrors.constraint(t)).contains("ck_cms_equip_cmd_fixed");
        accounts.exec(a.comp, "update cms_equip_cmd set status = 'DONE', finished_at = now()");
        Throwable t2 = catchThrowable(() -> accounts.exec(a.comp, "update cms_equip_cmd set status = 'FAILED'"));
        assertThat(PgErrors.constraint(t2)).contains("ck_cms_equip_cmd_finished_fixed");
    }
}
