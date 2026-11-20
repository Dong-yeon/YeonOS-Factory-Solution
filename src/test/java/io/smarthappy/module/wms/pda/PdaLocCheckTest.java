package io.smarthappy.module.wms.pda;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.ZonedDateTime;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.MessageSource;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.jayway.jsonpath.JsonPath;

import io.smarthappy.module.home.HomeCard;
import io.smarthappy.module.home.HomeCardContext;
import io.smarthappy.module.master.workforce.WorkerService;
import io.smarthappy.module.pop.PopAuthService;
import io.smarthappy.module.wms.inv.InvDtos.ScanForm;
import io.smarthappy.module.wms.inv.StockService;
import io.smarthappy.module.wms.loccheck.LocCheckCard;
import io.smarthappy.module.wms.loccheck.LocCheckDtos.CorrectForm;
import io.smarthappy.module.wms.loccheck.LocCheckService;
import io.smarthappy.platform.common.web.BusinessException;
import io.smarthappy.platform.common.web.HbErrorCode;
import io.smarthappy.platform.tenant.TenantRunner;
import io.smarthappy.platform.time.BizClock;
import io.smarthappy.support.PostgresTestBase;
import io.smarthappy.support.TestAccounts;
import jakarta.servlet.http.Cookie;

/**
 * 정위치 점검 (docs/55, docs/49 W-3) — PDA 위치 스캔 → 물건 스캔 판정(정상·다른 위치·미등록) → 전수 확인 완료(없음) → 사무 보정 이동·확인만.
 * 온라인만(대기열 안 씀 — 화면 시험), 같은 날 같은 칸 1회, 동시 완료·완료 중 줄 추가·동시 보정, 삭제 금지, A사로 B사, 권한, 홈 카드
 */
@AutoConfigureMockMvc
class PdaLocCheckTest extends PostgresTestBase {

    private static final String PW = "Cedar-lantern-58";
    private static final String WH = "/api/pop/wh";
    private static final String API = "/api/wms/loc-checks";

    @Autowired
    MockMvc mvc;

    @Autowired
    TestAccounts accounts;

    @Autowired
    TenantRunner runner;

    @Autowired
    LocCheckService service;

    @Autowired
    StockService stock;

    @Autowired
    LocCheckCard card;

    @Autowired
    MessageSource messages;

    @Autowired
    BizClock clock;

    @Autowired
    PlatformTransactionManager tx;

    String comp;
    String ip;
    RequestPostProcessor admin;
    long a1;
    long a2;
    long stg;
    long fg;
    long fgLot;
    long l1;
    long l2;
    long l4;
    Cookie pda;
    MockHttpSession s;

    @BeforeEach
    void setUp() throws Exception {
        comp = accounts.newComp("LC");
        ip = "10.87." + (System.nanoTime() % 200) + "." + (System.nanoTime() % 250 + 1);
        long u = accounts.newUser(comp, "admin1", PW);
        accounts.grant(comp, u, "ADM", "sys-terminal", "SEARCH", "SAVE");
        accounts.grant(comp, u, "ADM", "wms-loc-check", "SEARCH", "SAVE");
        admin = accounts.as(comp, u, "admin1");
        accounts.addPlant(comp, "P1", "P1 공장");
        accounts.exec(comp, """
                insert into mst_item (comp_cd, item_cd, item_nm, item_type, proc_type, unit_cd, lot_ctrl_yn, created_by, updated_by)
                values (?, 'FG-100', 'FG 품목', 'FG', 'MAKE', 'EA', 'N', 't', 't'), (?, 'FG-L', 'FG LOT 품목', 'FG', 'MAKE', 'EA', 'Y', 't', 't')""",
                comp, comp);
        long wh = accounts.query(comp, """
                insert into mst_equip_hier (comp_cd, plant_cd, node_type, node_cd, node_nm, created_by, updated_by)
                values (?, 'P1', 'WH', 'WH-P1', 'WH-P1', 't', 't') returning node_id""", Long.class, comp);
        a1 = node(wh, "A-01");
        a2 = node(wh, "A-02");
        stg = node(wh, "STG-01");
        accounts.exec(comp, "insert into wms_loc_attr (node_id, comp_cd, loc_usage, created_by, updated_by) values (?, ?, 'STAGING', 't', 't')",
                stg, comp);
        fg = accounts.query(comp, "select item_id from mst_item where item_cd = 'FG-100'", Long.class);
        fgLot = accounts.query(comp, "select item_id from mst_item where item_cd = 'FG-L'", Long.class);
        l1 = lot("L1");
        l2 = lot("L2");
        lot("L3");                                          // 재고 없음
        l4 = lot("L4");
        adjIn(fg, a1, null, "30");                          // 비관리 품목 — A-01
        adjIn(fgLot, a2, l1, "5");                          // L1 — A-02(다른 위치)
        adjIn(fgLot, a1, l2, "4");                          // L2 — A-01(스캔 안 하면 없음)
        adjIn(fgLot, stg, l4, "2");                         // L4 — 출하 대기 칸
        accounts.exec(comp, """
                insert into mst_worker (comp_cd, worker_cd, worker_nm, plant_cd, badge_hash, created_by, updated_by)
                values (?, 'W-001', '작업자', 'P1', ?, 't', 't')""", comp, WorkerService.badgeHash(comp, "BADGE-0001"));
        pda = terminal("PDA-01");
        s = new MockHttpSession();
        badgeIn();
    }

    // ---------- 도우미 ----------

    long node(long wh, String cd) {
        return accounts.query(comp, """
                insert into mst_equip_hier (comp_cd, plant_cd, parent_id, node_type, node_cd, node_nm, created_by, updated_by)
                values (?, 'P1', ?, 'LOC', ?, ?, 't', 't') returning node_id""", Long.class, comp, wh, cd, cd);
    }

    long lot(String no) {
        return accounts.query(comp, """
                insert into lot_master (comp_cd, lot_no, item_id, origin, qty_created, unit_cd, occurred_at, created_by, updated_by)
                values (?, ?, ?, 'RECEIVED', 100, 'EA', now(), 't', 't') returning lot_id""", Long.class, comp, no, fgLot);
    }

    void adjIn(long item, long node, Long lot, String qty) {
        accounts.exec(comp, """
                insert into inv_trx (comp_cd, plant_cd, trx_type, item_id, node_id, lot_id, stock_status, qty, unit_cd, src_type, occurred_at,
                                     entry_method, recorded_by)
                values (?, 'P1', 'ADJ_IN', ?, ?, ?, 'AVAILABLE', ?::numeric, 'EA', 'MANUAL', now() - interval '1 hour', 'KEY', 't')""",
                comp, item, node, lot, qty);
    }

    BigDecimal stockOf(long node, long item, Long lot) {
        return accounts.query(comp, "select coalesce(sum(qty), 0) from inv_stock where node_id = ? and item_id = ? and lot_id is not distinct from ?",
                BigDecimal.class, node, item, lot).stripTrailingZeros();
    }

    MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder b, String body) {
        return b.with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body);
    }

    Cookie terminal(String cd) throws Exception {
        MvcResult m = mvc.perform(json(post("/api/production/terminals"), "{\"terminalCd\":\"" + cd + "\",\"terminalNm\":\"" + cd
                + "\",\"plantCd\":\"P1\",\"terminalType\":\"PDA\"}").with(admin)).andExpect(status().isOk()).andReturn();
        String code = JsonPath.read(m.getResponse().getContentAsString(), "$.data.code");
        MvcResult a = mvc.perform(json(post("/api/pop/activate"), "{\"compCd\":\"" + comp + "\",\"code\":\"" + code + "\"}")
                .with(r -> { r.setRemoteAddr(ip); return r; })).andExpect(status().isOk()).andReturn();
        String set = a.getResponse().getHeaders("Set-Cookie").stream().filter(h -> h.startsWith(PopAuthService.COOKIE + "="))
                .findFirst().orElseThrow();
        return new Cookie(PopAuthService.COOKIE, set.substring(set.indexOf('=') + 1, set.indexOf(';')));
    }

    void badgeIn() throws Exception {
        mvc.perform(json(post("/api/pop/badge"), "{\"badge\":\"BADGE-0001\"}").cookie(pda).session(s)).andExpect(status().isOk());
    }

    ResultActions pdaPost(String url, String body) throws Exception {
        return mvc.perform(json(post(url), body).cookie(pda).session(s));
    }

    long start(String loc) throws Exception {
        MvcResult m = pdaPost(WH + "/loc-checks/scan", "{\"text\":\"" + loc + "\"}").andExpect(status().isOk()).andReturn();
        return ((Number) JsonPath.read(m.getResponse().getContentAsString(), "$.data.head.checkId")).longValue();
    }

    ResultActions scan(long id, String text, String req) throws Exception {
        return pdaPost(WH + "/loc-checks/" + id + "/lines", "{\"text\":\"" + text + "\",\"clientReqId\":" + (req == null ? "null" : "\"" + req + "\"")
                + ",\"scannedAt\":\"2026-10-29T10:00:00+09:00\"}");
    }

    long lineOf(long id, String result, Long lot) {
        return accounts.query(comp, "select min(line_id) from wms_loc_check_line where check_id = ? and result = ? and lot_id is not distinct from ?",
                Long.class, id, result, lot);
    }

    ResultActions office(String url, String body) throws Exception {
        return mvc.perform(json(post(API + url), body).with(admin));
    }

    static HbErrorCode code(Throwable t) {
        return t instanceof BusinessException b ? b.code() : null;
    }

    // ---------- 판정·완료 ----------

    @Test
    void 판정_정상_다른위치_미등록과_완료의_없음_집계_같은날_다시는_409() throws Exception {
        long id = start("LOC:P1:A-01");
        scan(id, "ITEM:FG-100", "r1").andExpect(status().isOk()).andExpect(jsonPath("$.data.line.result").value("OK"));
        scan(id, "LOT:L1", "r2").andExpect(jsonPath("$.data.line.result").value("MISPLACED"))
                .andExpect(jsonPath("$.data.line.bookJson[0].nodeId").value(a2)).andExpect(jsonPath("$.data.line.bookJson[0].qty").value("5"))
                .andExpect(jsonPath("$.data.line.bookJson[0].path").value("P1/WH-P1/A-02"));
        scan(id, "LOT:L3", "r3").andExpect(jsonPath("$.data.line.result").value("UNKNOWN")).andExpect(jsonPath("$.data.line.reasonCd").value("NO_STOCK"));
        scan(id, "ITEM:FG-L", "r4").andExpect(jsonPath("$.data.line.reasonCd").value("LOT_REQUIRED"));
        scan(id, "NOPE-XYZ", "r5").andExpect(jsonPath("$.data.line.result").value("UNKNOWN")).andExpect(jsonPath("$.data.line.reasonCd").value("NOT_FOUND"));
        // 같은 LOT 두 번 → 앞 줄(duplicate), 같은 요청 id 다시 → 같은 줄
        long first = lineOf(id, "MISPLACED", l1);
        scan(id, "LOT:L1", "r6").andExpect(jsonPath("$.data.duplicate").value(true)).andExpect(jsonPath("$.data.line.lineId").value(first));
        scan(id, "LOT:L1", "r2").andExpect(jsonPath("$.data.duplicate").value(false)).andExpect(jsonPath("$.data.line.lineId").value(first));
        assertThat(accounts.query(comp, "select count(*) from wms_loc_check_line where check_id = ?", Integer.class, id)).isEqualTo(5);
        // 완료 → 스캔 안 된 L2(A-01) = 없음, 집계
        pdaPost(WH + "/loc-checks/" + id + "/done", "{}").andExpect(status().isOk()).andExpect(jsonPath("$.data.head.status").value("DONE"))
                .andExpect(jsonPath("$.data.head.okCnt").value(1)).andExpect(jsonPath("$.data.head.misplacedCnt").value(1))
                .andExpect(jsonPath("$.data.head.missingCnt").value(1)).andExpect(jsonPath("$.data.head.unknownCnt").value(3))
                .andExpect(jsonPath("$.data.head.accurateYn").value("N"));
        assertThat(lineOf(id, "MISSING", l2)).isNotNull();
        // DONE 뒤 스캔·완료·같은 날 같은 칸 다시 → 409
        scan(id, "ITEM:FG-100", "r7").andExpect(status().isConflict());
        pdaPost(WH + "/loc-checks/" + id + "/done", "{}").andExpect(status().isConflict());
        pdaPost(WH + "/loc-checks/scan", "{\"text\":\"LOC:P1:A-01\"}").andExpect(status().isConflict());
        // 위치가 아닌 것으로 시작 → 400
        pdaPost(WH + "/loc-checks/scan", "{\"text\":\"ITEM:FG-100\"}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].code").value("NOT_LOCATION"));
        // 모두 맞는 칸 → 일치
        long ok = start("LOC:P1:A-02");
        scan(ok, "LOT:L1", "r8").andExpect(jsonPath("$.data.line.result").value("OK"));
        pdaPost(WH + "/loc-checks/" + ok + "/done", "{}").andExpect(jsonPath("$.data.head.accurateYn").value("Y"));
        // 홈 카드 — 확인 2칸·일치 1, 처리 안 된 불일치 5(다른 위치 1 + 미등록 3 + 없음 1)
        HomeCard c = card("P1");
        assertThat(c.count()).isEqualTo(5);
        assertThat(c.facts().get(0).value()).isEqualTo(2);
        assertThat(c.facts().get(1).value()).isEqualTo(1);
        assertThat(c.items().get(0).title()).contains("50");
        assertThat(card("P2").count()).isZero();
    }

    @Test
    void 홈_카드_항목_링크는_오래된_점검도_목록에_든다() throws Exception {
        // 40일 전 점검의 미처리 줄 — 대상 화면 기본 기간(최근 7일)·API 기본(30일) 밖. 항목 링크가 그 점검일을 함께 넘겨야 목록에 든다(docs/37 22장)
        long old;
        try {
            clock.setClock(java.time.Clock.fixed(java.time.Instant.now().minus(java.time.Duration.ofDays(40)), java.time.ZoneOffset.UTC));
            old = start("LOC:P1:A-01");
            scan(old, "NOPE-XYZ", "o1").andExpect(jsonPath("$.data.line.result").value("UNKNOWN"));
            pdaPost(WH + "/loc-checks/" + old + "/done", "{}").andExpect(status().isOk());
        } finally {
            clock.setClock(java.time.Clock.systemUTC());
        }
        HomeCard c = card("P1");
        HomeCard.Item item = c.items().get(1);   // 0 = 정확도 줄, 1 = 가장 오래된 미처리 줄
        assertThat(item.link()).containsEntry("plantCd", "P1").containsEntry("openOnly", "true");
        var r = get(API + "/grid").with(admin).param("total", "true");
        item.link().forEach(r::param);
        String body = mvc.perform(r).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<java.util.List<Integer>>read(body, "$.list[*].checkId")).as("항목 링크 목록에 그 점검").contains((int) old);
    }

    HomeCard card(String plant) {
        // 공장 시각으로 — 점검일(check_date)은 공장 시간대 날짜라 시험 JVM(UTC) 시각을 넣으면 UTC 15시~24시에 날짜가 하루 어긋난다
        return runner.call(comp, () -> {
            ZonedDateTime now = ZonedDateTime.now(clock.zone());
            return card.load(new HomeCardContext(now, 0L, Locale.KOREAN, messages, plant, ZonedDateTime.now(clock.zone(plant))));
        });
    }

    // ---------- 보정·확인 ----------

    @Test
    void 보정_이동은_다른_위치만_한_번_장부_칸에서_점검한_칸으로_확인만은_사유_필수() throws Exception {
        long id = start("LOC:P1:A-01");
        scan(id, "ITEM:FG-100", "c1");
        scan(id, "LOT:L1", "c2");
        scan(id, "LOT:L4", "c3").andExpect(jsonPath("$.data.line.result").value("MISPLACED"));    // 장부 = 출하 대기 칸
        // 읽을 때 장부 칸의 지금 용도가 붙는다 — 화면은 출하 대기 칸에 [보정 이동]을 보이지 않는다(서버 400 은 그대로)
        mvc.perform(get(API + "/" + id).with(admin)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.lines[?(@.lotNo == 'L4')].bookJson[0].usage").value(org.hamcrest.Matchers.contains("STAGING")))
                .andExpect(jsonPath("$.data.lines[?(@.lotNo == 'L1')].bookJson[0].nodeId").isNotEmpty());
        pdaPost(WH + "/loc-checks/" + id + "/done", "{}").andExpect(status().isOk());
        long mis = lineOf(id, "MISPLACED", l1);
        long okLine = lineOf(id, "OK", null);
        long missing = lineOf(id, "MISSING", l2);
        long staged = lineOf(id, "MISPLACED", l4);
        office("/lines/" + okLine + "/correct", "{\"fromNodeId\":" + a1 + ",\"qty\":1}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].code").value("NOT_MISPLACED"));
        office("/lines/" + mis + "/correct", "{\"fromNodeId\":" + stg + ",\"qty\":1}").andExpect(jsonPath("$.errors[0].code").value("NOT_IN_BOOK"));
        office("/lines/" + mis + "/correct", "{\"fromNodeId\":" + a2 + ",\"qty\":6}").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_STOCK"));
        office("/lines/" + staged + "/correct", "{\"fromNodeId\":" + stg + ",\"qty\":1}").andExpect(jsonPath("$.errors[0].code").value("STAGING_LOCKED"));
        office("/lines/" + mis + "/correct", "{\"fromNodeId\":" + a2 + ",\"fromStatus\":\"AVAILABLE\",\"qty\":5,\"remark\":\"A-02 에서 발견\"}")
                .andExpect(status().isOk());
        assertThat(stockOf(a2, fgLot, l1)).isEqualByComparingTo("0");
        assertThat(stockOf(a1, fgLot, l1)).isEqualByComparingTo("5");
        assertThat(accounts.query(comp, """
                select string_agg(trx_type || ':' || reason_cd, ',' order by trx_type) from inv_trx where src_type = 'LOC_CHECK' and src_id = ?""",
                String.class, mis)).isEqualTo("MOVE_IN:LOC_CORRECT,MOVE_OUT:LOC_CORRECT");
        assertThat(accounts.query(comp, "select review_action from wms_loc_check_line where line_id = ?", String.class, mis)).isEqualTo("CORRECTED");
        office("/lines/" + mis + "/correct", "{\"fromNodeId\":" + a2 + ",\"qty\":1}").andExpect(status().isConflict());     // 두 번
        office("/lines/" + missing + "/confirm", "{\"reason\":\" \"}").andExpect(jsonPath("$.errors[0].code").value("REQUIRED"));
        office("/lines/" + okLine + "/confirm", "{\"reason\":\"x\"}").andExpect(jsonPath("$.errors[0].code").value("NOT_MISMATCH"));
        office("/lines/" + missing + "/confirm", "{\"reason\":\"실사로 맞춤\"}").andExpect(status().isOk());
        office("/lines/" + missing + "/confirm", "{\"reason\":\"다시\"}").andExpect(status().isConflict());
        // 목록·미처리 필터
        mvc.perform(get(API + "/grid").param("plantCd", "P1").with(admin)).andExpect(jsonPath("$.list[0].openCnt").value(1));
        mvc.perform(get(API + "/grid").param("plantCd", "P1").param("openOnly", "true").with(admin)).andExpect(jsonPath("$.list.length()").value(1));
        office("/lines/" + staged + "/confirm", "{\"reason\":\"피킹분\"}").andExpect(status().isOk());
        mvc.perform(get(API + "/grid").param("plantCd", "P1").param("openOnly", "true").with(admin)).andExpect(jsonPath("$.list.length()").value(0));
        // 삭제·처리 바꾸기 → DB 거부(보정 이력 보존)
        assertThat(catchThrowable(() -> accounts.exec(comp, "delete from wms_loc_check_line where line_id = ?", mis))).isNotNull();
        assertThat(catchThrowable(() -> accounts.exec(comp, "update wms_loc_check_line set review_reason = 'x' where line_id = ?", mis))).isNotNull();
        assertThat(catchThrowable(() -> accounts.exec(comp, "delete from wms_loc_check where check_id = ?", id))).isNotNull();
    }

    // ---------- A사로 B사·권한 ----------

    @Test
    void A사로_B사_점검_보정은_404_권한_없으면_403() throws Exception {
        long id = start("LOC:P1:A-01");
        scan(id, "LOT:L1", "b1");
        long mis = lineOf(id, "MISPLACED", l1);
        String b = accounts.newComp("LB");
        long bu = accounts.newUser(b, "badmin", PW);
        accounts.grant(b, bu, "ADM", "wms-loc-check", "SEARCH", "SAVE");
        RequestPostProcessor bAdmin = accounts.as(b, bu, "badmin");
        mvc.perform(get(API + "/" + id).with(bAdmin)).andExpect(status().isNotFound());
        mvc.perform(json(post(API + "/lines/" + mis + "/correct"), "{\"fromNodeId\":" + a2 + ",\"qty\":1}").with(bAdmin))
                .andExpect(status().isNotFound());
        assertThat(stockOf(a2, fgLot, l1)).isEqualByComparingTo("5");
        long nu = accounts.newUser(comp, "viewer", PW);
        RequestPostProcessor viewer = accounts.as(comp, nu, "viewer");
        mvc.perform(get(API + "/grid").param("plantCd", "P1").with(viewer)).andExpect(status().isForbidden());
        mvc.perform(json(post(API + "/lines/" + mis + "/confirm"), "{\"reason\":\"x\"}").with(viewer)).andExpect(status().isForbidden());
        // B사 칸 id 로 A사 PDA 시작 → 칸 아님
        accounts.addPlant(b, "P1", "B");
        long bNode = accounts.query(b, """
                insert into mst_equip_hier (comp_cd, plant_cd, node_type, node_cd, node_nm, created_by, updated_by)
                values (?, 'P1', 'WH', 'B-WH', 'B-WH', 't', 't') returning node_id""", Long.class, b);
        Throwable t = catchThrowable(() -> runner.callAs(comp, "pda", () -> service.start("P1", bNode, null)));
        assertThat(code(t)).isEqualTo(HbErrorCode.INVALID_VALUE);
        // 홈 카드(docs/37 22.2 ⑦) — B사 범위로 같은 공장 코드 P1: A사의 미처리 줄을 세지 않고 항목도 없다(정확도 줄만)
        assertThat(card("P1").count()).isEqualTo(1);
        HomeCard bc = runner.call(b, () -> card.load(new HomeCardContext(ZonedDateTime.now(clock.zone()), 0L, Locale.KOREAN, messages, "P1",
                ZonedDateTime.now(clock.zone()))));
        assertThat(bc.count()).isZero();
        assertThat(bc.items()).hasSize(1);                     // 정확도 줄(확인 0 → "-")만
        assertThat(bc.facts()).extracting(HomeCard.Fact::value).containsOnly(0L);
    }

    /** 홈 카드 R1(docs/37 22.2 ⑦) — 점검 화면 조회 권한이 있어야 카드가 보인다. 저장만으로는 안 보인다 */
    @Test
    void 홈_카드는_정위치_점검_조회_권한이_있을_때만() throws Exception {
        long u = accounts.newUser(comp, "home1", PW);
        accounts.grant(comp, u, "HOME_ONLY", io.smarthappy.module.home.HomeService.PROG, "SEARCH");
        RequestPostProcessor who = accounts.as(comp, u, "home1");
        mvc.perform(get("/api/home/cards").param("plant", "P1").with(who)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.cards[*].code").value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.hasItem("wms-loc-accuracy"))));
        accounts.grant(comp, u, "HOME_ONLY", "wms-loc-check", "SAVE");
        mvc.perform(get("/api/home/cards").param("plant", "P1").with(who))
                .andExpect(jsonPath("$.data.cards[*].code").value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.hasItem("wms-loc-accuracy"))));
        accounts.grant(comp, u, "HOME_ONLY", "wms-loc-check", "SEARCH");
        mvc.perform(get("/api/home/cards").param("plant", "P1").with(who))
                .andExpect(jsonPath("$.data.cards[*].code").value(org.hamcrest.Matchers.hasItem("wms-loc-accuracy")));
    }

    // ---------- 동시성 ----------

    /** 한쪽이 머리 잠금을 쥔 채 완료(커밋 전) → 다른 쪽 완료·줄 추가는 기다렸다 DONE 을 보고 409. 없음은 한 벌 */
    @Test
    void 동시_완료_2와_완료_중_줄_추가는_409_없음은_한_벌() throws Exception {
        long id = start("LOC:P1:A-01");
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(3);
        try {
            Future<?> first = pool.submit(() -> runner.callAs(comp, "pda1", () -> new TransactionTemplate(tx).execute(st -> {
                service.done(id, "P1");
                held.countDown();
                await(go);
                return null;
            })));
            held.await(10, TimeUnit.SECONDS);
            Future<Throwable> second = pool.submit(() -> catchThrowable(() -> runner.callAs(comp, "pda2", () -> service.done(id, "P1"))));
            Future<Throwable> line = pool.submit(() -> catchThrowable(() -> runner.callAs(comp, "pda3", () -> service.scan(id, "P1", "ITEM:FG-100",
                    stock.scan(new ScanForm("P1", "ITEM:FG-100")), "late-1", null, null))));
            Thread.sleep(500);                               // 둘 다 머리 잠금을 기다리는 중
            go.countDown();
            first.get(20, TimeUnit.SECONDS);
            assertThat(code(second.get(20, TimeUnit.SECONDS))).isEqualTo(HbErrorCode.CONFLICT);
            assertThat(code(line.get(20, TimeUnit.SECONDS))).isEqualTo(HbErrorCode.CONFLICT);
        } finally {
            pool.shutdownNow();
        }
        assertThat(accounts.query(comp, "select count(*) from wms_loc_check_line where check_id = ? and result = 'MISSING'", Integer.class, id))
                .isEqualTo(2);                               // FG-100·L2 — 한 벌
        assertThat(accounts.query(comp, "select count(*) from wms_loc_check_line where client_req_id = 'late-1'", Integer.class)).isZero();
    }

    /** 같은 줄 보정 둘 — 줄 잠금 뒤 처리 확인이라 하나만, 원장 한 짝 */
    @Test
    void 같은_줄_동시_보정_2는_하나만_원장_한_짝() throws Exception {
        long id = start("LOC:P1:A-01");
        scan(id, "LOT:L1", "p1");
        long mis = lineOf(id, "MISPLACED", l1);
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> first = pool.submit(() -> runner.callAs(comp, "u1", () -> new TransactionTemplate(tx).execute(st -> {
                service.correct(mis, new CorrectForm(a2, "AVAILABLE", new BigDecimal("2"), null));
                held.countDown();
                await(go);
                return null;
            })));
            held.await(10, TimeUnit.SECONDS);
            Future<Throwable> second = pool.submit(() -> catchThrowable(() -> runner.callAs(comp, "u2",
                    () -> service.correct(mis, new CorrectForm(a2, "AVAILABLE", new BigDecimal("3"), null)))));
            Thread.sleep(500);
            go.countDown();
            first.get(20, TimeUnit.SECONDS);
            assertThat(code(second.get(20, TimeUnit.SECONDS))).isEqualTo(HbErrorCode.CONFLICT);
        } finally {
            pool.shutdownNow();
        }
        assertThat(accounts.query(comp, "select count(*) from inv_trx where src_type = 'LOC_CHECK' and src_id = ?", Integer.class, mis)).isEqualTo(2);
        assertThat(stockOf(a2, fgLot, l1)).isEqualByComparingTo("3");
    }

    // ---------- 날짜 지난 열린 점검 (L-1, 사용자 결정 2026-10-30) ----------

    @Test
    void 자정을_넘긴_열린_점검은_스캔_완료_409_위치를_다시_스캔하면_오늘_점검() throws Exception {
        long id = start("LOC:P1:A-01");
        scan(id, "ITEM:FG-100", "s1").andExpect(status().isOk());
        java.time.LocalDate day = accounts.query(comp, "select check_date from wms_loc_check where check_id = ?", java.time.LocalDate.class, id);
        try {
            // 공장 시간대로 다음 날 00:05 — 자정을 넘긴 PDA
            java.time.ZoneId zone = runner.call(comp, () -> clock.zone("P1"));
            java.time.Instant next = day.plusDays(1).atTime(0, 5).atZone(zone).toInstant();
            clock.setClock(java.time.Clock.fixed(next, java.time.ZoneOffset.UTC));
            scan(id, "LOT:L2", "s2").andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("WMS_LOC_CHECK_STALE"));
            pdaPost(WH + "/loc-checks/" + id + "/done", "{}").andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("WMS_LOC_CHECK_STALE"));
            // 어제 머리는 그대로 OPEN·줄 1, 정확도 분모(DONE)에 들어가지 않는다
            assertThat(accounts.query(comp, "select status from wms_loc_check where check_id = ?", String.class, id)).isEqualTo("OPEN");
            assertThat(accounts.query(comp, "select count(*) from wms_loc_check_line where check_id = ?", Integer.class, id)).isEqualTo(1);
            // 같은 요청 id 다시(응답이 끊겼던 어제 스캔) = 처음 줄 그대로 — 쓰지 않으므로 막지 않는다
            scan(id, "ITEM:FG-100", "s1").andExpect(status().isOk());
            // 위치를 다시 스캔 → 오늘 점검(새 머리)
            long today = start("LOC:P1:A-01");
            assertThat(today).isNotEqualTo(id);
            assertThat(accounts.query(comp, "select check_date from wms_loc_check where check_id = ?", java.time.LocalDate.class, today))
                    .isEqualTo(day.plusDays(1));
            scan(today, "ITEM:FG-100", "s3").andExpect(status().isOk());
        } finally {
            clock.setClock(java.time.Clock.systemUTC());
        }
    }

    static void await(CountDownLatch l) {
        try {
            l.await(20, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
