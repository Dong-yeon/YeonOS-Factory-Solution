package io.smarthappy.module.qms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.MessageSource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import io.smarthappy.module.production.link.LotHoldPort;
import io.smarthappy.module.production.link.StartGate;
import io.smarthappy.module.production.link.StartGate.GateVerdict;
import io.smarthappy.module.production.link.StartGate.StartContext;
import io.smarthappy.platform.tenant.TenantRunner;

/**
 * P3 QMS SPC 위반 이벤트 원장 (docs/29_P3 19장) — 판정 확정 때 고정 한계 단위만·새 점의 규칙만 기록(손 계산), 검사 무효 → VOID,
 * 한계 무효는 스냅샷 유지, 처리(ACK·ACTION·CLOSE·VOID) 흐름, 같은 단위 동시 판정, 알림(기본 꺼짐), 착수 게이트 Q9-3,
 * 이상 사용(입력·권한·A사로 B사·직접 SQL).
 */
class SpcEventTest extends QmsTestBase {

    static final String S = "/api/qms/spc";
    static final String E = "/api/qms/spc/events";
    static final String I = "/api/qms/insps";
    static final String G = "/api/qms/gauges";
    /** 부분군 값: 평균 10.00, 범위 0.04 → σ̂ = 0.04/2.326 = 0.017197 (SpcApiTest 와 같은 값) */
    static final String[] BASE = {"9.98", "10.00", "10.02", "10.00", "10.00"};
    /** 평균 10.05 → z = 0.05/(0.017197/√5) = 6.50 > 3 → WE1 */
    static final String[] HIGH = {"10.03", "10.05", "10.07", "10.05", "10.05"};
    /** 평균 10.00, 범위 0.10 > R 상한 (2.326 + 3·0.864)·0.017197 = 0.0846 → R1 만 */
    static final String[] WIDE = {"9.95", "10.00", "10.05", "10.00", "10.00"};

    @Autowired
    StartGate gate;

    @Autowired
    TenantRunner runner;

    @Autowired
    PlatformTransactionManager txm;

    @Autowired
    MessageSource messages;

    /** 실제 MES 보류 계약 그대로 — MES 없는 설치본(NONE 처럼 거부) 시험에서만 hold 를 막는다 */
    @MockitoSpyBean
    LotHoldPort holdPort;

    long gauge;
    long item;
    int lotSeq;

    @BeforeEach
    void eventSetUp() throws Exception {
        accounts.grant(comp, userId, "QA", "qms-insp", "SEARCH", "SAVE", "DELETE");
        accounts.grant(comp, userId, "QA", "qms-gauge-calib", "SEARCH", "SAVE");
        accounts.grant(comp, userId, "QA", "qms-spc", "SEARCH", "SAVE", "DELETE");
        accounts.exec(comp, """
                insert into mst_phys_asset (comp_cd, asset_cd, asset_type, asset_nm, gauge_class, calib_cycle_days, created_by, updated_by)
                values (?, 'GSPC', 'GAUGE', 'SPC 캘리퍼', 'CALIPER', 365, 't', 't')""", comp);
        gauge = accounts.query(comp, "select asset_id from mst_phys_asset where asset_cd = 'GSPC'", Long.class);
        postJson(G + "/" + gauge + "/calibs", "{\"calibDate\":" + q(today.minusDays(10)) + ",\"result\":\"OK\",\"agency\":\"INTERNAL\"}")
                .andExpect(status().isOk());
        long p = plan("FINAL", "FG1", null, null);
        lines(p, line("a", null, 10, fgChar1, "S5", "CALIPER"), line("b", null, 20, fgChar2, "S5", "VISUAL")).andExpect(status().isOk());
        action(p, "release").andExpect(status().isOk());
        item = accounts.query(comp, "select item_id from mst_item where item_cd = 'FG1'", Long.class);
    }

    /** 검사 하나: 값 입력(계량 5 + 계수 OK), judge = true 면 판정 확정 → 검사 id */
    long insp(boolean judge, String... values) throws Exception {
        return insp(admin, judge, values);
    }

    long insp(RequestPostProcessor who, boolean judge, String... values) throws Exception {
        String no = "L-SPE-" + (++lotSeq);
        accounts.exec(comp, """
                insert into lot_master (comp_cd, lot_no, item_id, origin, qty_created, unit_cd, occurred_at, created_by, updated_by)
                select ?, ?, item_id, 'RECEIVED', 50, 'EA', now(), 't', 't' from mst_item where item_cd = 'FG1'""", comp, no);
        long id = id(mvc.perform(json(post(I), "{\"lotNo\":" + q(no) + ",\"inspType\":\"FINAL\"}").with(who)));
        StringBuilder v = new StringBuilder();
        StringBuilder a = new StringBuilder();
        for (int i = 0; i < values.length; i++) {
            v.append(i == 0 ? "" : ",").append("{\"sampleNo\":").append(i + 1).append(",\"value\":").append(values[i])
                    .append(",\"gaugeAssetId\":").append(gauge).append("}");
            a.append(i == 0 ? "" : ",").append("{\"sampleNo\":").append(i + 1).append(",\"okYn\":\"Y\"}");
        }
        mvc.perform(json(post(I + "/" + id + "/meas"), "{\"inspLineId\":" + line(id, 10) + ",\"entries\":[" + v + "]}").with(who))
                .andExpect(status().isOk());
        mvc.perform(json(post(I + "/" + id + "/meas"), "{\"inspLineId\":" + line(id, 20) + ",\"entries\":[" + a + "]}").with(who))
                .andExpect(status().isOk());
        if (judge) {
            judge(id).andExpect(status().isOk());
        }
        return id;
    }

    long line(long insp, int no) {
        return accounts.query(comp, "select insp_line_id from qms_insp_line where insp_id = ? and line_no = ?", Long.class, insp, no);
    }

    ResultActions judge(long insp) throws Exception {
        return postJson(I + "/" + insp + "/judge", "{\"rowVer\":" + ver(insp) + "}");
    }

    int ver(long insp) {
        return accounts.query(comp, "select row_ver from qms_insp where insp_id = ?", Integer.class, insp);
    }

    /** 기준 20군 + 고정 한계(WE1,WE2) → limit id */
    long baseline() throws Exception {
        for (int i = 0; i < 20; i++) {
            insp(true, BASE);
        }
        return id(postJson(S + "/limits", "{\"itemId\":" + item + ",\"charId\":" + fgChar1 + ",\"inspType\":\"FINAL\"}"));
    }

    /** 그 검사의 이벤트 규칙(규칙 순) */
    List<String> rules(long insp) {
        String s = accounts.query(comp, "select coalesce(string_agg(rule_cd, ',' order by rule_cd), '') from qms_spc_event where insp_id = ?",
                String.class, insp);
        return s.isEmpty() ? List.of() : List.of(s.split(","));
    }

    long event(long insp, String rule) {
        return accounts.query(comp, "select event_id from qms_spc_event where insp_id = ? and rule_cd = ?", Long.class, insp, rule);
    }

    ResultActions act(long eventId, String body) throws Exception {
        return postJson(E + "/" + eventId + "/acts", body);
    }

    static void owner(String sql, Object... args) throws Exception {
        try (Connection c = DriverManager.getConnection(jdbcUrl(), OWNER_USER, testPassword())) {
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                for (int i = 0; i < args.length; i++) {
                    ps.setObject(i + 1, args[i]);
                }
                ps.execute();
            }
        }
    }

    /** 소유자·replica(트리거·FK 건너뜀) — 게이트 시험용으로 공정이 붙은 이벤트를 만든다 */
    static void replica(String sql, Object... args) throws Exception {
        try (Connection c = DriverManager.getConnection(jdbcUrl(), OWNER_USER, testPassword())) {
            try (Statement st = c.createStatement()) {
                st.execute("set session_replication_role = replica");
            }
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                for (int i = 0; i < args.length; i++) {
                    ps.setObject(i + 1, args[i]);
                }
                ps.execute();
            }
        }
    }

    // ---------- 기록 ----------

    @Test
    void 고정한계_단위만_새_점의_규칙만_기록하고_손계산과_같다() throws Exception {
        // 한계 전: 튀는 검사도 이벤트 없음(Q-n)
        long before = insp(true, HIGH);
        assertThat(rules(before)).isEmpty();
        for (int i = 0; i < 19; i++) {
            insp(true, BASE);
        }
        // 기준 20군 = before(HIGH) + BASE 19 → 한계를 정하면 center 가 달라지므로 HIGH 는 쓰지 않는다: 무효 처리 후 BASE 하나 더
        postJson(I + "/" + before + "/void", "{\"rowVer\":" + ver(before) + ",\"reason\":\"시험 데이터\"}").andExpect(status().isOk());
        insp(true, BASE);
        long lim = id(postJson(S + "/limits", "{\"itemId\":" + item + ",\"charId\":" + fgChar1 + ",\"inspType\":\"FINAL\"}"));

        // 1) HIGH → WE1 만 (직전 점은 모두 z = 0 → WE2 아님)
        long a = insp(true, HIGH);
        assertThat(rules(a)).containsExactly("WE1");
        assertThat(accounts.query(comp, "select n || ':' || round(point_mean, 4) || ':' || round(point_range, 4) || ':' || round(z, 3) || ':' "
                + "|| round(center, 4) || ':' || round(sigma, 6) || ':' || limit_id from qms_spc_event where insp_id = ?", String.class, a))
                .isEqualTo("5:10.0500:0.0400:6.501:10.0000:0.017197:" + lim);
        // 2) HIGH 또 → WE1 + WE2(3점 중 2점이 2σ 밖, 같은 쪽). 앞 검사 이벤트는 그대로(새 점만)
        long b = insp(true, HIGH);
        assertThat(rules(b)).containsExactly("WE1", "WE2");
        assertThat(rules(a)).containsExactly("WE1");
        // 3) 범위만 넓음 → R1 만
        long c = insp(true, WIDE);
        assertThat(rules(c)).containsExactly("R1");
        // 4) 진행 중(판정 전) 검사는 기록 없음, 계수형 행은 볼 일 없음
        long open = insp(false, HIGH);
        assertThat(rules(open)).isEmpty();
        assertThat(accounts.query(comp, "select count(*) from qms_spc_event e join qms_insp_line x on x.insp_line_id = e.insp_line_id "
                + "where x.data_type <> 'VARIABLE'", Long.class)).isZero();
        // 발생 시각 = 판정 시각, 관리도의 점과 같은 규칙
        assertThat(accounts.query(comp, "select count(*) from qms_spc_event e join qms_insp h on h.insp_id = e.insp_id "
                + "where e.occurred_at <> h.judged_at", Long.class)).isZero();
        mvc.perform(get(S + "/chart").param("itemId", String.valueOf(item)).param("charId", String.valueOf(fgChar1)).param("inspType", "FINAL")
                .with(admin)).andExpect(jsonPath("$.data.points[21].rules", contains("WE1", "WE2")))
                .andExpect(jsonPath("$.data.points[22].rules", contains("R1")));
    }

    @Test
    void 검사_무효면_열린_이벤트에_VOID_한계_무효는_스냅샷_유지() throws Exception {
        long lim = baseline();
        long a = insp(true, HIGH);
        long b = insp(true, HIGH);
        long ev = event(a, "WE1");
        // a 는 종결해 둔다 → 검사 무효 때 VOID 를 더하지 않는다
        act(ev, "{\"actType\":\"CLOSE\",\"note\":\"원인 확인\"}").andExpect(status().isOk());
        postJson(I + "/" + b + "/void", "{\"rowVer\":" + ver(b) + ",\"reason\":\"측정 오류\"}").andExpect(status().isOk());
        mvc.perform(get(E + "/" + event(b, "WE2")).with(admin)).andExpect(jsonPath("$.data.event.status").value("VOID"))
                .andExpect(jsonPath("$.data.acts[0].actType").value("VOID"))
                .andExpect(jsonPath("$.data.acts[0].note").value(org.hamcrest.Matchers.startsWith("근거 검사 무효 ")));
        postJson(I + "/" + a + "/void", "{\"rowVer\":" + ver(a) + ",\"reason\":\"측정 오류\"}").andExpect(status().isOk());
        assertThat(accounts.query(comp, "select string_agg(act_type, ',' order by act_id) from qms_spc_event_act where event_id = ?",
                String.class, ev)).isEqualTo("CLOSE");
        // 한계 무효: 이벤트·스냅샷 그대로, 이후 판정은 이벤트 없음
        postJson(S + "/limits/" + lim + "/void", "{\"rowVer\":0,\"reason\":\"공정 변경\"}").andExpect(status().isOk());
        assertThat(accounts.query(comp, "select count(*) from qms_spc_event where limit_id = ?", Long.class, lim)).isEqualTo(3);
        assertThat(rules(insp(true, HIGH))).isEmpty();
    }

    @Test
    void 같은_단위_동시_판정도_중복없이_기록한다() throws Exception {
        baseline();
        long a = insp(false, HIGH);
        long b = insp(false, HIGH);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Callable<Integer>> jobs = List.of(() -> judge(a).andReturn().getResponse().getStatus(),
                    () -> judge(b).andReturn().getResponse().getStatus());
            for (Future<Integer> f : pool.invokeAll(jobs)) {
                assertThat(f.get()).isEqualTo(200);
            }
        } finally {
            pool.shutdown();
        }
        // a 는 먼저 측정 → 창에 b 없음 → WE1 만. b 는 a 가 먼저 확정됐으면 WE2 도(판정 순서에 따름) — 어느 쪽이든 WE1 은 있다
        assertThat(rules(a)).containsExactly("WE1");
        assertThat(rules(b)).contains("WE1");
        assertThat(accounts.query(comp, "select count(*) from (select insp_line_id, rule_cd from qms_spc_event group by 1, 2 having count(*) > 1) d",
                Long.class)).isZero();
    }

    // ---------- 처리·조회 ----------

    @Test
    void 처리_흐름_ACK_ACTION_CLOSE_뒤에는_없음_목록_필터() throws Exception {
        baseline();
        long a = insp(true, HIGH);
        long ev = event(a, "WE1");
        mvc.perform(get(E + "/grid").param("status", "OPEN").with(admin)).andExpect(jsonPath("$.list[0].eventId").value(ev))
                .andExpect(jsonPath("$.list[0].status").value("OPEN")).andExpect(jsonPath("$.total").value(1));
        // 상세의 LOT 번호·품목 = 그 검사의 LOT — 화면 바로가기(NCR 등록·봉쇄·출하 추적 ?lotNos=, docs/29_P3 35장)가 이 값을 쓴다
        String lotNo = accounts.query(comp, "select lot_no from lot_master where lot_id = ?", String.class, lotOf(a));
        mvc.perform(get(E + "/" + ev).with(admin)).andExpect(jsonPath("$.data.event.lotNo").value(lotNo))
                .andExpect(jsonPath("$.data.event.itemCd").value("FG1"));
        act(ev, "{\"actType\":\"ACK\"}").andExpect(status().isOk());
        // 입력: 메모 없는 조치, 짝 없는 연결, ACK 에 연결, 모르는 유형
        act(ev, "{\"actType\":\"ACTION\"}").andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("note"));
        act(ev, "{\"actType\":\"ACTION\",\"note\":\"x\",\"linkType\":\"NCR\"}").andExpect(jsonPath("$.errors[0].field").value("linkNo"));
        act(ev, "{\"actType\":\"ACK\",\"linkType\":\"NCR\",\"linkNo\":\"N-1\"}").andExpect(jsonPath("$.errors[0].field").value("linkType"));
        act(ev, "{\"actType\":\"ACTION\",\"note\":\"x\",\"linkType\":\"ECO\",\"linkNo\":\"E-1\"}")
                .andExpect(jsonPath("$.errors[0].field").value("linkType"));
        act(ev, "{\"actType\":\"REOPEN\",\"note\":\"x\"}").andExpect(jsonPath("$.errors[0].code").value("CODE"));
        act(ev, "{\"actType\":\"CLOSE\",\"note\":\" \"}").andExpect(jsonPath("$.errors[0].field").value("note"));
        act(ev, "{\"actType\":\"ACTION\",\"note\":\"공구 교체\",\"linkType\":\"ncr\",\"linkNo\":\"nc-26-001\"}").andExpect(status().isOk());
        mvc.perform(get(E + "/grid").param("status", "OPEN_ALL").with(admin)).andExpect(jsonPath("$.list[0].status").value("ACKED"));
        // Q-o: 종결은 메모만으로(연결 선택)
        act(ev, "{\"actType\":\"CLOSE\",\"note\":\"재발 없음\"}").andExpect(status().isOk());
        act(ev, "{\"actType\":\"ACK\"}").andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("QMS_SPC_EVENT_STATE"));
        act(ev, "{\"actType\":\"VOID\",\"note\":\"x\"}").andExpect(status().isConflict());
        mvc.perform(get(E + "/" + ev).with(admin)).andExpect(jsonPath("$.data.event.status").value("CLOSED"))
                .andExpect(jsonPath("$.data.event.lastBy").value("admin1"))
                .andExpect(jsonPath("$.data.acts[*].actType", contains("ACK", "ACTION", "CLOSE")))
                .andExpect(jsonPath("$.data.acts[1].linkType").value("NCR")).andExpect(jsonPath("$.data.acts[1].linkNo").value("NC-26-001"));
        mvc.perform(get(E + "/grid").param("status", "OPEN_ALL").with(admin)).andExpect(jsonPath("$.list", empty()));
        mvc.perform(get(E + "/grid").param("status", "XX").with(admin)).andExpect(jsonPath("$.list", empty()));
        String no = accounts.query(comp, "select insp_no from qms_insp where insp_id = ?", String.class, a);
        mvc.perform(get(E + "/grid").param("keyword", no).with(admin)).andExpect(jsonPath("$.list.length()").value(1));
        // 조회 칸 이름이 "품목·특성" — 특성 이름으로도 찾는다(화면 확인에서 발견)
        mvc.perform(get(E + "/grid").param("keyword", "홀 지").with(admin)).andExpect(jsonPath("$.list.length()").value(1));
        mvc.perform(get(E + "/grid").param("keyword", "없는말").with(admin)).andExpect(jsonPath("$.list", empty()));
        // 관리도에서 연 단위 필터 + 정렬 키 허용 목록
        mvc.perform(get(E + "/grid").param("itemId", String.valueOf(item)).param("charId", String.valueOf(fgChar1)).param("inspType", "FINAL")
                .param("sort", "ruleCd,asc").with(admin)).andExpect(jsonPath("$.list.length()").value(1));
        mvc.perform(get(E + "/grid").param("sort", "note,asc").with(admin)).andExpect(status().isBadRequest());
        mvc.perform(get(E + "/columns").with(admin)).andExpect(jsonPath("$.list[0].field").value("occurredAt"));
        act(999999999L, "{\"actType\":\"ACK\"}").andExpect(status().isNotFound());
        mvc.perform(get(E + "/999999999").with(admin)).andExpect(status().isNotFound());
    }

    @Test
    void 알림은_기본_꺼짐_켠_사람에게만() throws Exception {
        baseline();
        insp(true, HIGH);
        assertThat(accounts.query(comp, "select count(*) from sys_notify where event_cd = 'QMS_SPC_EVENT'", Long.class)).isZero();
        accounts.exec(comp, "insert into sys_notify_sub (comp_cd, user_id, event_cd, inbox_yn, mail_yn, created_by, updated_by) "
                + "values (?, ?, 'QMS_SPC_EVENT', 'Y', 'N', 't', 't')", comp, userId);
        long b = insp(true, HIGH);
        assertThat(accounts.query(comp, "select count(*) from sys_notify where event_cd = 'QMS_SPC_EVENT' and user_id = ?", Long.class, userId))
                .isEqualTo(2);     // WE1·WE2 이벤트마다 한 번
        assertThat(accounts.query(comp, "select link_prog_cd from sys_notify where event_cd = 'QMS_SPC_EVENT' limit 1", String.class))
                .isEqualTo("qms-spc");
        String no = accounts.query(comp, "select insp_no from qms_insp where insp_id = ?", String.class, b);
        assertThat(messages.getMessage("notify.qms.spcEvent", new Object[] {no, "WE1"}, Locale.KOREAN)).contains(no).contains("WE1");
        assertThat(messages.getMessage("notify.qms.spcEvent", new Object[] {no, "WE1"}, Locale.ENGLISH)).contains(no).contains("WE1");
    }

    // ---------- 홈 카드 ----------

    static String card(String json, String path) {
        java.util.List<Object> v = com.jayway.jsonpath.JsonPath.read(json, "$.data.cards[?(@.code == 'qms-spc-open')]" + path);
        return v.isEmpty() ? null : String.valueOf(v.get(0));
    }

    @Test
    void 홈카드_미처리_위반_숫자_미확인_링크목록과_같다_권한_B사() throws Exception {
        accounts.grant(comp, userId, "QA", "home-main", "SEARCH");
        baseline();
        insp(true, HIGH);           // WE1
        insp(true, HIGH);           // WE1 + WE2
        String h = mvc.perform(get("/api/home/cards").with(admin)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(card(h, ".count")).isEqualTo("3");
        assertThat(card(h, ".facts[0].value")).isEqualTo("3");     // 아무도 확인 안 함
        assertThat(card(h, ".items.length()")).isEqualTo("3");
        // 숫자 링크 = 위반 보기 + 미종결 → 위반 목록 API 에 같은 조건이면 건수가 같다(view 는 화면용, API 는 무시)
        @SuppressWarnings("unchecked")
        java.util.Map<String, String> link = (java.util.Map<String, String>) ((java.util.List<Object>) com.jayway.jsonpath.JsonPath.read(h,
                "$.data.cards[?(@.code == 'qms-spc-open')].link")).get(0);
        assertThat(link).containsEntry("view", "events").containsEntry("status", "OPEN_ALL");
        org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder r = get(E + "/grid").with(admin);
        link.forEach(r::param);
        mvc.perform(r).andExpect(jsonPath("$.total").value(3));
        // ACK → 미확인만 줄고, 종결·무효 → 숫자도 준다
        long a = accounts.query(comp, "select min(event_id) from qms_spc_event", Long.class);
        act(a, "{\"actType\":\"ACK\"}").andExpect(status().isOk());
        h = mvc.perform(get("/api/home/cards").with(admin)).andReturn().getResponse().getContentAsString();
        assertThat(card(h, ".count") + "/" + card(h, ".facts[0].value")).isEqualTo("3/2");
        act(a, "{\"actType\":\"CLOSE\",\"note\":\"x\"}").andExpect(status().isOk());
        long b = accounts.query(comp, "select max(event_id) from qms_spc_event", Long.class);
        act(b, "{\"actType\":\"VOID\",\"note\":\"오경보\"}").andExpect(status().isOk());
        h = mvc.perform(get("/api/home/cards").with(admin)).andReturn().getResponse().getContentAsString();
        assertThat(card(h, ".count") + "/" + card(h, ".facts[0].value")).isEqualTo("1/1");
        r = get(E + "/grid").with(admin);
        link.forEach(r::param);
        mvc.perform(r).andExpect(jsonPath("$.total").value(1));
        // 권한: qms-spc 조회가 없으면 카드 없음 / B사 사용자는 0
        long homeOnly = accounts.newUser(comp, "home1", PW);
        accounts.grant(comp, homeOnly, "HM", "home-main", "SEARCH");
        String h2 = mvc.perform(get("/api/home/cards").with(accounts.as(comp, homeOnly, "home1"))).andReturn().getResponse().getContentAsString();
        assertThat(card(h2, ".count")).isNull();
        String other = accounts.newComp("QM");
        long bUser = accounts.newUser(other, "admin1", PW);
        accounts.grant(other, bUser, "QA", "home-main", "SEARCH");
        accounts.grant(other, bUser, "QA", "qms-spc", "SEARCH");
        String h3 = mvc.perform(get("/api/home/cards").with(accounts.as(other, bUser, "admin1"))).andReturn().getResponse().getContentAsString();
        assertThat(card(h3, ".count")).isEqualTo("0");
    }

    // ---------- 착수 게이트 Q9-3 ----------

    GateVerdict check(String c, long procId) {
        StartContext ctx = new StartContext("P1", 0, 1, procId, item, null, userId, 1, today, Instant.now(), false);
        TransactionTemplate tx = new TransactionTemplate(txm);
        return runner.call(c, () -> tx.execute(s -> gate.check(ctx)));
    }

    @Test
    void 착수게이트_이품목_이공정의_열린_위반_정책_OFF_WARN_BLOCK() throws Exception {
        long draw = accounts.query(comp, "select proc_id from mst_proc where proc_cd = 'DRAW'", Long.class);
        baseline();
        long a = insp(true, HIGH);
        // 공정이 붙은 같은 검사 행 사본(규칙만 다르게) — 출하검사 계획에는 공정이 없어 소유자·replica 로 만든다
        for (String r : new String[] {"WE2", "WE3", "WE4", "R1"}) {
            replica("insert into qms_spc_event (comp_cd, limit_id, item_id, char_id, insp_type, proc_id, insp_id, insp_line_id, rule_cd, n, "
                    + "point_mean, point_range, z, center, sigma, occurred_at, created_by) select comp_cd, limit_id, item_id, char_id, insp_type, ?, "
                    + "insp_id, insp_line_id, ?, n, point_mean, point_range, z, center, sigma, occurred_at, created_by from qms_spc_event "
                    + "where insp_id = ? and rule_cd = 'WE1'", draw, r, a);
        }
        // replica 는 트리거를 끄므로 투영(상태 표)도 같이 — 원장 트리거가 했을 그대로 OPEN 행
        replica("insert into qms_spc_event_state (event_id, comp_cd, status, item_id, char_id, insp_type, proc_id, rule_cd, occurred_at) "
                + "select event_id, comp_cd, 'OPEN', item_id, char_id, insp_type, proc_id, rule_cd, occurred_at from qms_spc_event where proc_id = ?", draw);
        assertThat(check(comp, draw + 1000)).isEqualTo(GateVerdict.PASS);    // 다른 공정
        GateVerdict v = check(comp, draw);
        assertThat(v.mode()).isEqualTo(StartGate.WARN);      // 기본 WARN
        assertThat(v.reasons()).hasSize(4);
        assertThat(v.reasons().get(0).code()).isEqualTo("QMS_SPC_OPEN");
        assertThat(v.reasons().get(0).refType()).isEqualTo("QMS_SPC_EVENT");
        assertThat(v.reasons().get(3).messageKey()).isEqualTo("qms.gate.spcOpenMore");
        assertThat(v.reasons().get(3).args()).containsExactly(1);
        var r0 = v.reasons().get(0);
        assertThat(messages.getMessage(r0.messageKey(), r0.args().toArray(), Locale.KOREAN)).contains("1");
        assertThat(messages.getMessage(r0.messageKey(), r0.args().toArray(), Locale.ENGLISH)).contains("SPC");
        // ACK 는 아직 열림, 종결·무효는 빠진다
        List<Long> ids = java.util.Arrays.stream(accounts.query(comp, "select string_agg(event_id::text, ',' order by event_id) from qms_spc_event "
                + "where proc_id = ?", String.class, draw).split(",")).map(Long::valueOf).toList();
        act(ids.get(0), "{\"actType\":\"ACK\"}").andExpect(status().isOk());
        act(ids.get(1), "{\"actType\":\"CLOSE\",\"note\":\"x\"}").andExpect(status().isOk());
        act(ids.get(2), "{\"actType\":\"VOID\",\"note\":\"x\"}").andExpect(status().isOk());
        assertThat(check(comp, draw).reasons()).hasSize(2);
        policy("QMS_GATE_SPC", "BLOCK");
        assertThat(check(comp, draw).mode()).isEqualTo(StartGate.BLOCK);
        accounts.exec(comp, "update sys_policy set policy_value = 'OFF' where policy_key = 'QMS_GATE_SPC'");
        assertThat(check(comp, draw)).isEqualTo(GateVerdict.PASS);
        // A사로 B사: 같은 품목·공정 id 로 B사 범위 → 없음
        accounts.exec(comp, "update sys_policy set policy_value = 'BLOCK' where policy_key = 'QMS_GATE_SPC'");
        assertThat(check(accounts.newComp("QZ"), draw)).isEqualTo(GateVerdict.PASS);
    }

    // ---------- 투영·요약 표 (docs/29_P3 22장 H3·H5) ----------

    /** 지금 표와 원장에서 다시 계산한 값의 차이 행 수(양쪽) — 0 이면 같다 */
    long stateDiff(String c) {
        return accounts.query(c, """
                select count(*) from ((select event_id, comp_cd, status, item_id, char_id, insp_type, proc_id, rule_cd, occurred_at, last_by, last_at
                                         from qms_spc_event_state except select * from hb_qms_spc_event_state_calc())
                                      union all
                                      (select * from hb_qms_spc_event_state_calc() except select event_id, comp_cd, status, item_id, char_id,
                                         insp_type, proc_id, rule_cd, occurred_at, last_by, last_at from qms_spc_event_state)) d""", Long.class);
    }

    long unitDiff(String c) {
        return accounts.query(c, """
                select count(*) from ((select comp_cd, item_id, char_id, insp_type, proc_id, subgroup_cnt, last_at from qms_spc_unit
                                       except select * from hb_qms_spc_unit_calc())
                                      union all
                                      (select * from hb_qms_spc_unit_calc() except select comp_cd, item_id, char_id, insp_type, proc_id,
                                       subgroup_cnt, last_at from qms_spc_unit)) d""", Long.class);
    }

    /** 계량형 특성(fgChar1) 단위 요약 — 계수형(fgChar2)은 p 관리도 단위로 따로 있다(28장) */
    String unitRow() {
        return accounts.query(comp, "select coalesce(string_agg(subgroup_cnt::text, ',' order by char_id), '-') from qms_spc_unit where item_id = ? "
                + "and insp_type = 'FINAL' and char_id = ?", String.class, item, fgChar1);
    }

    @Test
    void 투영_요약_표는_원장에서_다시_계산한_값과_같고_앱이_직접_못_쓴다_B사_안_보임() throws Exception {
        assertThat(unitRow()).isEqualTo("-");
        baseline();
        // 단위 요약: 계량형 특성(fgChar1) 판정 20군, 계수형(fgChar2)도 p 관리도 단위로 20군(28장)
        assertThat(unitRow()).isEqualTo("20");
        assertThat(accounts.query(comp, "select subgroup_cnt from qms_spc_unit where item_id = ? and insp_type = 'FINAL' and char_id = ?", Integer.class,
                item, fgChar2)).isEqualTo(20);
        long a = insp(true, HIGH);
        long b = insp(true, HIGH);
        insp(false, HIGH);      // 판정 전은 세지 않는다
        assertThat(unitRow()).isEqualTo("22");
        long ev = event(a, "WE1");
        act(ev, "{\"actType\":\"ACK\"}").andExpect(status().isOk());
        act(ev, "{\"actType\":\"ACTION\",\"note\":\"게이지 확인\"}").andExpect(status().isOk());
        assertThat(accounts.query(comp, "select status || ':' || last_by from qms_spc_event_state where event_id = ?", String.class, ev))
                .isEqualTo("ACKED:admin1");
        act(ev, "{\"actType\":\"CLOSE\",\"note\":\"원인 제거\"}").andExpect(status().isOk());
        // 검사 무효 → 그 검사 열린 이벤트 VOID(투영도) + 단위 부분군 하나 빠짐
        postJson(I + "/" + b + "/void", "{\"rowVer\":" + ver(b) + ",\"reason\":\"측정 오류\"}").andExpect(status().isOk());
        assertThat(accounts.query(comp, "select string_agg(status, ',' order by event_id) from qms_spc_event_state where event_id in "
                + "(select event_id from qms_spc_event where insp_id in (?, ?))", String.class, a, b)).isEqualTo("CLOSED,VOID,VOID");
        assertThat(unitRow()).isEqualTo("21");
        // 다시 계산 = 지금 표 (어긋나면 고치는 길이 이 함수)
        assertThat(stateDiff(comp)).isZero();
        assertThat(unitDiff(comp)).isZero();
        // 목록 건수 = 투영 표, 홈 카드도 같은 건수 SQL
        mvc.perform(get(E + "/grid").param("status", "OPEN_ALL").param("total", "true").with(admin))
                .andExpect(jsonPath("$.total").value(accounts.query(comp, "select count(*) from hb_qms_spc_event_state_calc() where status in "
                        + "('OPEN', 'ACKED')", Integer.class)));
        // 지연 조인 분기: 품목 코드 정렬(품목 조인)·규칙 정렬(투영 칸)·검색어(표시 칸 조인) 모두 같은 행 집합
        mvc.perform(get(E + "/grid").param("sort", "itemCd,asc").param("total", "true").with(admin)).andExpect(jsonPath("$.total").value(3))
                .andExpect(jsonPath("$.list[0].itemCd").value(accounts.query(comp, "select item_cd from mst_item where item_id = ?", String.class, item)));
        mvc.perform(get(E + "/grid").param("sort", "ruleCd,desc").with(admin)).andExpect(jsonPath("$.list[0].ruleCd").value("WE2"));
        mvc.perform(get(E + "/grid").param("keyword", accounts.query(comp, "select insp_no from qms_insp where insp_id = ?", String.class, b))
                .param("total", "true").with(admin)).andExpect(jsonPath("$.total").value(2)).andExpect(jsonPath("$.list[0].status").value("VOID"));
        // 직접 SQL(앱 계정): 투영·요약 표는 트리거 밖에서 못 쓴다 — 원장을 쓰라는 가드, 투영 행 지우기는 권한 없음
        constraint(comp, "ck_qms_spc_projection", () -> accounts.exec(comp, "update qms_spc_event_state set status = 'OPEN' where event_id = ?", ev));
        constraint(comp, "ck_qms_spc_projection", () -> accounts.exec(comp, "update qms_spc_unit set subgroup_cnt = 99"));
        constraint(comp, "ck_qms_spc_projection", () -> accounts.exec(comp, "delete from qms_spc_unit"));
        constraint(comp, "ck_qms_spc_projection", () -> accounts.exec(comp, "insert into qms_spc_unit (comp_cd, item_id, char_id, insp_type, "
                + "subgroup_cnt, last_at) values (?, ?, ?, 'PATROL', 1, now())", comp, item, fgChar1));
        sqlState("42501", () -> accounts.exec(comp, "delete from qms_spc_event_state where event_id = ?", ev));
        assertThat(stateDiff(comp)).isZero();
        assertThat(unitDiff(comp)).isZero();
        // A사로 B사: 두 표 모두 안 보이고, B사 기준 다시 계산도 비어 있다
        String other = accounts.newComp("QP");
        assertThat(accounts.query(other, "select (select count(*) from qms_spc_event_state) + (select count(*) from qms_spc_unit)", Long.class))
                .isZero();
        assertThat(stateDiff(other)).isZero();
        assertThat(unitDiff(other)).isZero();
    }

    // ---------- 자동 보류 (SV-6, docs/29_P3 23장 — 사용자 결정 2026-10-26 Q-u·Q-y·Q-z) ----------

    long lotOf(long insp) {
        return accounts.query(comp, "select lot_id from qms_insp where insp_id = ?", Long.class, insp);
    }

    /** "hold_yn/살아 있는 QMS_SPC 근거 수" */
    String spcHold(long insp) {
        return accounts.query(comp, """
                select l.hold_yn || '/' || (select count(*) from lot_hold h where h.lot_id = l.lot_id and h.source = 'QMS_SPC' and h.released_at is null)
                  from lot_master l where l.lot_id = ?""", String.class, lotOf(insp));
    }

    List<Long> events(long insp) {
        return accounts.query(comp, "select coalesce(string_agg(event_id::text, ',' order by event_id), '') from qms_spc_event where insp_id = ?",
                String.class, insp).transform(x -> x.isEmpty() ? List.<Long>of() : java.util.Arrays.stream(x.split(",")).map(Long::valueOf).toList());
    }

    @Test
    void 자동보류_정책_OFF_LIMIT_ALL_해제는_종결_무효만_A사로_B사() throws Exception {
        baseline();
        // OFF(기본): 위반은 기록되지만 보류 없음
        long off = insp(true, HIGH);
        assertThat(events(off)).isNotEmpty();
        assertThat(spcHold(off)).isEqualTo("N/0");
        // LIMIT: 한계 밖 규칙(WE1·R1)만 근거 — 같은 검사의 WE2 는 보류 안 함
        policy("QMS_SPC_AUTO_HOLD", "LIMIT");
        long lim = insp(true, HIGH);
        assertThat(rules(lim)).contains("WE1", "WE2");
        assertThat(spcHold(lim)).isEqualTo("Y/1");
        assertThat(accounts.query(comp, "select ref_id || ':' || reason from lot_hold where source = 'QMS_SPC' and lot_id = ?", String.class,
                lotOf(lim))).isEqualTo(event(lim, "WE1") + ":SPC 위반 WE1 — 검사 " + accounts.query(comp,
                "select insp_no from qms_insp where insp_id = ?", String.class, lim));
        // ALL: 켜 둔 규칙 전부 — 이벤트마다 근거 하나
        accounts.exec(comp, "update sys_policy set policy_value = 'ALL' where policy_key = 'QMS_SPC_AUTO_HOLD'");
        long all = insp(true, HIGH);
        List<Long> evs = events(all);
        assertThat(evs).hasSizeGreaterThanOrEqualTo(2);
        assertThat(spcHold(all)).isEqualTo("Y/" + evs.size());
        // 상세: 보류 중 표시 / ACK·ACTION 은 안 풂
        mvc.perform(get(E + "/" + evs.get(0)).with(admin)).andExpect(jsonPath("$.data.held").value(true));
        act(evs.get(0), "{\"actType\":\"ACK\"}").andExpect(status().isOk());
        act(evs.get(0), "{\"actType\":\"ACTION\",\"note\":\"게이지 확인\"}").andExpect(status().isOk());
        assertThat(spcHold(all)).isEqualTo("Y/" + evs.size());
        // A사로 B사: 남의 이벤트 종결 404, 보류 그대로
        String other = accounts.newComp("QH");
        long bUser = accounts.newUser(other, "admin1", PW);
        accounts.grant(other, bUser, "QA", "qms-spc", "SEARCH", "SAVE");
        mvc.perform(json(post(E + "/" + evs.get(0) + "/acts"), "{\"actType\":\"CLOSE\",\"note\":\"남의 것\"}")
                .with(accounts.as(other, bUser, "admin1"))).andExpect(status().isNotFound());
        assertThat(spcHold(all)).isEqualTo("Y/" + evs.size());
        // 종결 하나 → 그 근거만 풀림(다른 근거가 남아 LOT 은 보류), 나머지 무효 → 풀림
        act(evs.get(0), "{\"actType\":\"CLOSE\",\"note\":\"원인 제거\"}").andExpect(status().isOk());
        assertThat(spcHold(all)).isEqualTo("Y/" + (evs.size() - 1));
        mvc.perform(get(E + "/" + evs.get(0)).with(admin)).andExpect(jsonPath("$.data.held").value(false));
        assertThat(accounts.query(comp, "select released_by || ':' || release_reason from lot_hold where source = 'QMS_SPC' and ref_id = ?",
                String.class, evs.get(0))).isEqualTo("admin1:SPC 위반 종결 " + accounts.query(comp,
                "select rule_cd from qms_spc_event where event_id = ?", String.class, evs.get(0)) + " — 원인 제거");
        for (Long ev : evs.subList(1, evs.size())) {
            act(ev, "{\"actType\":\"VOID\",\"note\":\"측정 실수\"}").andExpect(status().isOk());
        }
        assertThat(spcHold(all)).isEqualTo("N/0");
        // 검사 무효 → 열린 이벤트 자동 VOID + 그 근거 해제(같은 트랜잭션)
        postJson(I + "/" + lim + "/void", "{\"rowVer\":" + ver(lim) + ",\"reason\":\"측정 오류\"}").andExpect(status().isOk());
        assertThat(spcHold(lim)).isEqualTo("N/0");
        // 정책을 OFF 로 돌려도 이미 잡힌 보류는 근거가 살아 있는 동안 그대로 — 새 위반은 보류 안 함
        accounts.exec(comp, "update sys_policy set policy_value = 'ALL' where policy_key = 'QMS_SPC_AUTO_HOLD'");
        long keep = insp(true, HIGH);
        accounts.exec(comp, "update sys_policy set policy_value = 'OFF' where policy_key = 'QMS_SPC_AUTO_HOLD'");
        assertThat(spcHold(keep)).startsWith("Y/");
        assertThat(spcHold(insp(true, HIGH))).isEqualTo("N/0");
    }

    @Test
    void D_자동보류_판정과_같은_LOT_근거_NCR_등록이_동시여도_교착_없음() throws Exception {
        // 판정 = 검사 머리 → 이벤트 → LOT(포트), NCR 등록 = 근거 검사 FOR SHARE → LOT — 같은 방향(23.3)이라 둘 다 LOT 을 기다렸다 끝난다
        accounts.grant(comp, userId, "QA", "qms-ncr", "SEARCH", "SAVE");
        accounts.exec(comp, "insert into qms_defect (comp_cd, defect_cd, defect_nm, cat_cd, created_by, updated_by) "
                + "values (?, 'D-SPC', '치수', 'DIMENSION', 't', 't')", comp);
        long defect = accounts.query(comp, "select defect_id from qms_defect where defect_cd = 'D-SPC'", Long.class);
        baseline();
        policy("QMS_SPC_AUTO_HOLD", "LIMIT");
        long first = insp(true, HIGH);                  // 같은 LOT 의 판정된 근거 검사(NCR 근거)
        String lotNo = accounts.query(comp, "select lot_no from lot_master where lot_id = ?", String.class, lotOf(first));
        String firstNo = accounts.query(comp, "select insp_no from qms_insp where insp_id = ?", String.class, first);
        long second = id(postJson(I, "{\"lotNo\":" + q(lotNo) + ",\"inspType\":\"FINAL\"}"));
        StringBuilder v = new StringBuilder();
        StringBuilder a = new StringBuilder();
        for (int i = 0; i < HIGH.length; i++) {
            v.append(i == 0 ? "" : ",").append("{\"sampleNo\":").append(i + 1).append(",\"value\":").append(HIGH[i])
                    .append(",\"gaugeAssetId\":").append(gauge).append("}");
            a.append(i == 0 ? "" : ",").append("{\"sampleNo\":").append(i + 1).append(",\"okYn\":\"Y\"}");
        }
        postJson(I + "/" + second + "/meas", "{\"inspLineId\":" + line(second, 10) + ",\"entries\":[" + v + "]}").andExpect(status().isOk());
        postJson(I + "/" + second + "/meas", "{\"inspLineId\":" + line(second, 20) + ",\"entries\":[" + a + "]}").andExpect(status().isOk());
        long lot = lotOf(first);
        java.util.concurrent.CountDownLatch locked = new java.util.concurrent.CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(3);
        try {
            Future<?> blocker = pool.submit(() -> runner.run(comp, () -> new TransactionTemplate(txm).executeWithoutResult(st -> {
                jdbcT.queryForObject("select lot_id from lot_master where lot_id = ? for update", Long.class, lot);
                locked.countDown();
                try {
                    Thread.sleep(700);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            })));
            assertThat(locked.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            int ver = ver(second);
            Future<Integer> judged = pool.submit(() -> mvc.perform(json(post(I + "/" + second + "/judge"), "{\"rowVer\":" + ver + "}").with(admin))
                    .andReturn().getResponse().getStatus());
            Future<Integer> ncr = pool.submit(() -> mvc.perform(json(post("/api/qms/ncrs"), "{\"lotNo\":" + q(lotNo) + ",\"source\":\"INSP\","
                    + "\"inspNo\":" + q(firstNo) + ",\"defectId\":" + defect + ",\"ncQty\":1,\"description\":\"치수\"}").with(admin))
                    .andReturn().getResponse().getStatus());
            assertThat(judged.get(30, java.util.concurrent.TimeUnit.SECONDS)).isEqualTo(200);
            assertThat(ncr.get(30, java.util.concurrent.TimeUnit.SECONDS)).isEqualTo(200);
            blocker.get(20, java.util.concurrent.TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
        // 근거: 첫 판정 WE1(1) + 둘째 판정 WE1(1) + NCR(정책 자동 보류 기본 Y)
        assertThat(accounts.query(comp, "select string_agg(source, ',' order by hold_id) from lot_hold where lot_id = ? and released_at is null",
                String.class, lot)).contains("QMS_SPC").contains("QMS_NCR");
    }

    @Test
    void 자동보류_MES_없으면_판정_거부_검사는_그대로() throws Exception {
        baseline();
        policy("QMS_SPC_AUTO_HOLD", "LIMIT");
        // 스파이는 트랜잭션 프록시 안쪽에 있다 — 프록시 너머 실제 스파이에 stub
        LotHoldPort spy = org.springframework.test.util.AopTestUtils.getUltimateTargetObject(holdPort);
        org.mockito.Mockito.doThrow(new IllegalStateException("MES 없음 — LOT 보류 불가")).when(spy)
                .hold(org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.eq(LotHoldPort.QMS_SPC),
                        org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        long a = insp(false, HIGH);
        judge(a).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("QMS_SPC_HOLD_UNAVAILABLE"));
        assertThat(accounts.query(comp, "select status from qms_insp where insp_id = ?", String.class, a)).isEqualTo("OPEN");
        assertThat(events(a)).isEmpty();
        org.mockito.Mockito.reset(spy);
    }

    // ---------- 이상 사용 ----------

    @Test
    void 권한_A사로_B사_직접SQL() throws Exception {
        long lim = baseline();
        long a = insp(true, HIGH);
        long ev = event(a, "WE1");
        long line = line(a, 10);
        // 권한: 없음 403, 조회만은 처리 403
        long viewer = accounts.newUser(comp, "viewer1", PW);
        accounts.grant(comp, viewer, "QV", "qms-spc", "SEARCH");
        RequestPostProcessor vw = accounts.as(comp, viewer, "viewer1");
        mvc.perform(get(E + "/" + ev).with(vw)).andExpect(status().isOk());
        mvc.perform(json(post(E + "/" + ev + "/acts"), "{\"actType\":\"ACK\"}").with(vw)).andExpect(status().isForbidden());
        long reader = accounts.newUser(comp, "reader1", PW);
        RequestPostProcessor rd = accounts.as(comp, reader, "reader1");
        mvc.perform(get(E + "/grid").with(rd)).andExpect(status().isForbidden());
        // A사로 B사: 목록 0, 상세·처리 404
        String other = accounts.newComp("QM");
        long bUser = accounts.newUser(other, "admin1", PW);
        accounts.grant(other, bUser, "QA", "qms-spc", "SEARCH", "SAVE", "DELETE");
        RequestPostProcessor b = accounts.as(other, bUser, "admin1");
        mvc.perform(get(E + "/grid").with(b)).andExpect(jsonPath("$.list", empty()));
        mvc.perform(get(E + "/" + ev).with(b)).andExpect(status().isNotFound());
        mvc.perform(json(post(E + "/" + ev + "/acts"), "{\"actType\":\"CLOSE\",\"note\":\"남의 것\"}").with(b)).andExpect(status().isNotFound());
        assertThat(accounts.query(comp, "select count(*) from qms_spc_event_act where event_id = ?", Long.class, ev)).isZero();
        // 직접 SQL(앱 계정): 고치기·지우기 권한 없음, B사 범위로는 안 보이고 남의 이벤트에 처리도 못 단다
        sqlState("42501", () -> accounts.exec(comp, "update qms_spc_event set rule_cd = 'WE2' where event_id = ?", ev));
        sqlState("42501", () -> accounts.exec(comp, "delete from qms_spc_event where event_id = ?", ev));
        assertThat(accounts.query(other, "select count(*) from qms_spc_event", Long.class)).isZero();
        constraint(other, "fk_qms_spc_event_act_event_comp", () -> accounts.exec(other,
                "insert into qms_spc_event_act (comp_cd, event_id, act_type, acted_by) values (?, ?, 'ACK', 't')", other, ev));
        // 원장 검사: 판정 안 된 검사·다른 단위의 한계·다른 회사 행
        String ins = "insert into qms_spc_event (comp_cd, limit_id, item_id, char_id, insp_type, insp_id, insp_line_id, rule_cd, n, point_mean, "
                + "point_range, center, sigma, occurred_at, created_by) values (?, ?, ?, ?, 'FINAL', ?, ?, ?, 5, 10, 0.04, 10, 0.017, now(), 't')";
        long open = insp(false, HIGH);
        constraint(comp, "ck_qms_spc_event_insp", () -> accounts.exec(comp, ins, comp, lim, item, fgChar1, open, line(open, 10), "WE1"));
        constraint(comp, "fk_qms_spc_event_line_comp", () -> accounts.exec(comp, ins, comp, lim, item, fgChar1, a, line(a, 20), "WE3"));   // 계수 행 + 계량 특성
        constraint(comp, "ck_qms_spc_event_rule", () -> accounts.exec(comp, ins, comp, lim, item, fgChar1, a, line, "WE9"));
        constraint(comp, "uq_qms_spc_event_line_rule", () -> accounts.exec(comp, ins, comp, lim, item, fgChar1, a, line, "WE1"));
        constraint(other, "fk_qms_spc_event_line_comp", () -> accounts.exec(other, ins, other, lim, item, fgChar1, a, line, "WE3"));
        // 처리 기록: 메모 없는 종결·ACK 에 연결 → CHECK, 종결 뒤 더하기 → 가드
        String act = "insert into qms_spc_event_act (comp_cd, event_id, act_type, note, link_type, link_no, acted_by) values (?, ?, ?, ?, ?, ?, 't')";
        constraint(comp, "ck_qms_spc_event_act_note", () -> accounts.exec(comp, act, comp, ev, "CLOSE", " ", null, null));
        constraint(comp, "ck_qms_spc_event_act_link", () -> accounts.exec(comp, act, comp, ev, "ACK", null, "NCR", "N-1"));
        accounts.exec(comp, act, comp, ev, "CLOSE", "끝", null, null);
        constraint(comp, "ck_qms_spc_event_act_done", () -> accounts.exec(comp, act, comp, ev, "ACK", null, null, null));
        sqlState("42501", () -> accounts.exec(comp, "delete from qms_spc_event_act where event_id = ?", ev));
        // 소유자 계정도 원장은 못 고친다(트리거)
        Throwable t = org.assertj.core.api.Assertions.catchThrowable(() -> owner("update qms_spc_event set rule_cd = 'WE2' where event_id = ?", ev));
        assertThat(String.valueOf(t)).contains("append-only");
        t = org.assertj.core.api.Assertions.catchThrowable(() -> owner("delete from qms_spc_event_act where event_id = ?", ev));
        assertThat(String.valueOf(t)).contains("append-only");
    }

    // ---------- 실측 (평소 건너뜀: -Dhb.perf=true) — QA 깊은 offset H3·H5 설계 메모(docs/29_P3 22장) 근거 ----------

    static final int EVENTS = 200_000;
    static final int INSPS = 100_000;   // × 계량 행 1 + 계수 행 1

    @Autowired
    org.springframework.jdbc.core.JdbcTemplate jdbcT;

    static void ownerSql(String... sqls) throws Exception {
        try (Connection c = DriverManager.getConnection(jdbcUrl(), OWNER_USER, testPassword()); Statement st = c.createStatement()) {
            st.execute("set session_replication_role = replica");
            for (String s : sqls) {
                st.execute(s);
            }
            st.execute("analyze");
        }
    }

    long perfMs(Runnable r) {
        java.util.List<Long> t = new java.util.ArrayList<>();
        for (int i = 0; i < 3; i++) {
            long s = System.nanoTime();
            try {
                r.run();
            } catch (AssertionError | RuntimeException e) {
                return -((System.nanoTime() - s) / 1_000_000);     // 음수 = 실패(쿼리 시간 제한 30초 등) — 걸린 시간
            }
            t.add((System.nanoTime() - s) / 1_000_000);
        }
        java.util.Collections.sort(t);
        return t.get(1);
    }

    <T> T perfTx(java.util.function.Supplier<T> s) {
        TransactionTemplate t = new TransactionTemplate(txm);
        return runner.call(comp, () -> t.execute(st -> s.get()));
    }

    void api(String url) {
        try {
            mvc.perform(get(url).with(admin)).andExpect(status().isOk());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    @org.junit.jupiter.api.condition.EnabledIfSystemProperty(named = "hb.perf", matches = "true")
    void 실측_H3_위반목록_H5_단위목록() throws Exception {
        baseline();
        long a = insp(true, HIGH);
        long ev = event(a, "WE1");
        long tplInsp = a;
        // ---------- 데이터: 위반 20만(2년에 고루), 처리 = 50% ACK, 그중 40% CLOSE / 판정 검사 10만 ----------
        ownerSql("insert into qms_spc_event (comp_cd, limit_id, item_id, char_id, insp_type, proc_id, insp_id, insp_line_id, rule_cd, n, point_mean, "
                + "point_range, z, center, sigma, occurred_at, created_by) select e.comp_cd, e.limit_id, e.item_id, e.char_id, e.insp_type, e.proc_id, "
                + "e.insp_id, 900000000 + g, (array['WE1','WE2','WE3','WE4','R1'])[1 + g % 5], e.n, e.point_mean, e.point_range, e.z, e.center, e.sigma, "
                + "now() - (g % 730) * interval '1 day' - (g % 1440) * interval '1 minute', 'perf' from qms_spc_event e, generate_series(1, " + EVENTS
                + ") g where e.event_id = " + ev,
                "insert into qms_spc_event_act (comp_cd, event_id, act_type, note, acted_by) select comp_cd, event_id, 'ACK', null, 'perf' "
                        + "from qms_spc_event where created_by = 'perf' and event_id % 2 = 0",
                "insert into qms_spc_event_act (comp_cd, event_id, act_type, note, acted_by) select comp_cd, event_id, 'CLOSE', '끝', 'perf' "
                        + "from qms_spc_event where created_by = 'perf' and event_id % 5 = 0 and event_id % 2 = 0",
                "insert into qms_insp (comp_cd, insp_no, insp_type, lot_id, item_id, proc_id, insp_plan_id, lot_qty, status, result, occurred_at, "
                        + "judged_at, judged_by, created_by, updated_by) select h.comp_cd, 'PF' || g, h.insp_type, h.lot_id, h.item_id, h.proc_id, "
                        + "h.insp_plan_id, h.lot_qty, 'JUDGED', 'OK', now() - (g % 730) * interval '1 day', now() - (g % 730) * interval '1 day', 'perf', "
                        + "'perf', 'perf' from qms_insp h, generate_series(1, " + INSPS + ") g where h.insp_id = " + tplInsp,
                "insert into qms_insp_line (comp_cd, insp_id, line_no, plan_line_id, char_id, data_type, gauge_class, sample_size, ac, re, created_by) "
                        + "select h.comp_cd, h.insp_id, x.line_no, x.plan_line_id, x.char_id, x.data_type, x.gauge_class, x.sample_size, x.ac, x.re, 'perf' "
                        + "from qms_insp h "
                        + "join qms_insp_line x on x.insp_id = " + tplInsp + " where h.insp_no like 'PF%'",
                // replica 는 트리거를 끄므로 투영·요약 표는 원장에서 다시 계산해 채운다(22장 — 고치는 길과 같은 함수)
                "insert into qms_spc_event_state (event_id, comp_cd, status, item_id, char_id, insp_type, proc_id, rule_cd, occurred_at, last_by, last_at) "
                        + "select * from hb_qms_spc_event_state_calc() c where not exists (select 1 from qms_spc_event_state s where s.event_id = c.event_id)",
                "delete from qms_spc_unit",
                "insert into qms_spc_unit (comp_cd, item_id, char_id, insp_type, proc_id, subgroup_cnt, last_at) select * from hb_qms_spc_unit_calc()");
        StringBuilder out = new StringBuilder("\n===== SPC 목록 실측 (위반 " + EVENTS + ", 판정 검사 " + INSPS + ") =====\n");
        String E = "/api/qms/spc/events/grid?size=50&status=OPEN_ALL";
        out.append("H3 API   첫 쪽(건수 포함) ").append(perfMs(() -> api(E + "&page=1&total=true"))).append(" ms\n");
        out.append("H3 API   첫 쪽(건수 없음) ").append(perfMs(() -> api(E + "&page=1&total=false"))).append(" ms\n");
        out.append("H3 API   2,000쪽(offset 99,950) ").append(perfMs(() -> api(E + "&page=2000&total=false"))).append(" ms\n");
        // 후보 A — 상태 투영 표(이벤트 1:1, 처리 기록 INSERT 때 갱신) + 지연 조인
        perfTx(() -> {
            jdbcT.execute("create temp table st as select e.event_id, e.occurred_at, case when exists (select 1 from qms_spc_event_act a where "
                    + "a.event_id = e.event_id and a.act_type = 'VOID') then 'VOID' when exists (select 1 from qms_spc_event_act a where a.event_id = "
                    + "e.event_id and a.act_type = 'CLOSE') then 'CLOSED' when exists (select 1 from qms_spc_event_act a where a.event_id = e.event_id) "
                    + "then 'ACKED' else 'OPEN' end as status from qms_spc_event e");
            jdbcT.execute("create index on st (status, occurred_at desc, event_id desc)");
            jdbcT.execute("analyze st");
            String q = "select v.event_id, i.item_cd, h.insp_no, l.lot_no from (select event_id, occurred_at from st where status in ('OPEN','ACKED') "
                    + "order by occurred_at desc, event_id desc limit 50 offset %d) v join qms_spc_event e on e.event_id = v.event_id "
                    + "join mst_item i on i.item_id = e.item_id join qms_insp h on h.insp_id = e.insp_id join lot_master l on l.lot_id = h.lot_id "
                    + "order by v.occurred_at desc, v.event_id desc";
            out.append("H3 안A   첫 쪽 ").append(perfMs(() -> jdbcT.queryForList(String.format(q, 0)))).append(" ms\n");
            out.append("H3 안A   2,000쪽 ").append(perfMs(() -> jdbcT.queryForList(String.format(q, 99_950)))).append(" ms\n");
            out.append("H3 안A   건수 ").append(perfMs(() -> jdbcT.queryForObject("select count(*) from st where status in ('OPEN','ACKED')", Long.class)))
                    .append(" ms\n");
            // 후보 B — 기간 필수(최근 92일) + 지연 조인, 상태는 계산 그대로
            String b = "select count(*) from qms_spc_event e where e.occurred_at >= now() - interval '92 days' and not exists (select 1 from "
                    + "qms_spc_event_act a where a.event_id = e.event_id and a.act_type in ('CLOSE','VOID'))";
            out.append("H3 안B   92일 건수 ").append(perfMs(() -> jdbcT.queryForObject(b, Long.class))).append(" ms\n");
            return null;
        });
        String U = "/api/qms/spc/units/grid?size=50";
        out.append("H5 API   첫 쪽(건수 포함) ").append(perfMs(() -> api(U + "&page=1&total=true"))).append(" ms\n");
        out.append("H5 API   첫 쪽(건수 없음) ").append(perfMs(() -> api(U + "&page=1&total=false"))).append(" ms\n");
        perfTx(() -> {
            // 후보 A — 단위 요약 표(판정·무효 때 갱신)
            jdbcT.execute("create temp table su as select h.item_id, x.char_id, h.insp_type, h.proc_id, count(*) as subgroup_cnt, max(h.occurred_at) "
                    + "as last_at from qms_insp h join qms_insp_line x on x.insp_id = h.insp_id and x.data_type = 'VARIABLE' where h.status = 'JUDGED' "
                    + "group by 1, 2, 3, 4");
            jdbcT.execute("create index on su (last_at desc)");
            jdbcT.execute("analyze su");
            out.append("H5 안A   첫 쪽 ").append(perfMs(() -> jdbcT.queryForList("select s.*, i.item_cd from su s join mst_item i on i.item_id = s.item_id "
                    + "order by s.last_at desc limit 50"))).append(" ms\n");
            // 후보 B — 기간 필수(최근 365일)
            out.append("H5 안B   365일 묶음 ").append(perfMs(() -> jdbcT.queryForList("select h.item_id, x.char_id, h.insp_type, h.proc_id, count(*), "
                    + "max(h.occurred_at) from qms_insp h join qms_insp_line x on x.insp_id = h.insp_id and x.data_type = 'VARIABLE' where "
                    + "h.status = 'JUDGED' and h.occurred_at >= now() - interval '365 days' group by 1, 2, 3, 4 order by 6 desc limit 50"))).append(" ms\n");
            return null;
        });
        System.out.println(out);
        java.nio.file.Files.writeString(java.nio.file.Path.of("build", "spc-grid-perf.txt"), out.toString());
    }
}
