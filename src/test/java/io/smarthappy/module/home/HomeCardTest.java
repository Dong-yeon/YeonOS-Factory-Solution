package io.smarthappy.module.home;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.MessageSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.PlatformTransactionManager;

import io.smarthappy.module.home.HomeCard.Tone;
import io.smarthappy.module.home.HomeService.CardView;
import io.smarthappy.module.home.HomeService.HomeView;
import io.smarthappy.module.production.WoDtos.WoForm;
import io.smarthappy.module.production.WoService;
import io.smarthappy.platform.auth.AuthMapperFacade;
import io.smarthappy.platform.auth.PermissionService;
import io.smarthappy.platform.security.LoginUser;
import io.smarthappy.platform.tenant.TenantContext;
import io.smarthappy.platform.tenant.TenantRunner;
import io.smarthappy.platform.time.BizClock;
import io.smarthappy.support.PostgresTestBase;
import io.smarthappy.support.TestAccounts;

/**
 * 홈 "내 할 일" 카드 (docs/37) — 카드 4장 정상(숫자·톤·항목), 화면 권한별 카드 노출(R1), 개인 카드는 본인 것만(R2),
 * A사로 B사, 끝난·취소 제외(R8), 카드 하나 실패·시간 초과·null 이 다른 카드를 깨지 않음(R3·R4), 코드 형식·중복 기동 검사(R6).
 */
@AutoConfigureMockMvc
class HomeCardTest extends PostgresTestBase {

    private static final String PW = "Willow-kettle-58";
    private static final String CARDS = "/api/home/cards";

    @Autowired
    MockMvc mvc;

    @Autowired
    TestAccounts accounts;

    @Autowired
    TenantRunner runner;

    @Autowired
    WoService workOrders;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PermissionService permissions;

    @Autowired
    AuthMapperFacade progs;

    @Autowired
    MessageSource messages;

    @Autowired
    BizClock clock;

    @Autowired
    PlatformTransactionManager tm;

    @Autowired
    HomeMapper homeMapper;

    String comp;
    long userId;
    RequestPostProcessor admin;
    LocalDate today;

    @BeforeEach
    void setUp() {
        comp = accounts.newComp("HM");
        userId = accounts.newUser(comp, "admin1", PW);
        grantAll(comp, userId, "ADMIN");
        admin = accounts.as(comp, userId, "admin1");
        today = LocalDate.parse(accounts.query(comp, "select " + TestAccounts.COMP_TODAY + "::text", String.class));
        fixtures(comp);
    }

    @AfterEach
    void clearSecurity() {
        SecurityContextHolder.clearContext();
    }

    // ---------- 도우미 ----------

    private void grantAll(String c, long u, String grp) {
        accounts.grant(c, u, grp, HomeService.PROG, "SEARCH");
        accounts.grant(c, u, grp, "prd-wo", "SEARCH");
        accounts.grant(c, u, grp, "prd-lot", "SEARCH");
        accounts.grant(c, u, grp, "sal-order", "SEARCH");
    }

    private void fixtures(String c) {
        accounts.addPlant(c, "P1", "1공장");
        for (String[] i : new String[][] {{"FG", "EA"}, {"RM", "KG"}}) {
            accounts.exec(c, """
                    insert into mst_item (comp_cd, item_cd, item_nm, item_type, proc_type, unit_cd, created_by, updated_by)
                    values (?, ?, ?, 'FG', 'MAKE', ?, 't', 't')""", c, i[0], i[0] + " 품목", i[1]);
        }
        accounts.exec(c, "insert into mst_proc (comp_cd, proc_cd, proc_nm, proc_cat, created_by, updated_by) values (?, 'SPOT', '스폿', 'WELD', 't', 't')", c);
        long rid = accounts.query(c, """
                insert into mst_routing (comp_cd, item_id, plant_cd, rev, valid_from, created_by, updated_by)
                values (?, (select item_id from mst_item where item_cd = 'FG'), 'P1', 'A0', (now() at time zone 'Asia/Seoul')::date - 30, 't', 't')
                returning routing_id""", Long.class, c);
        accounts.exec(c, """
                insert into mst_routing_step (comp_cd, routing_id, op_seq, proc_id, std_ct_sec, created_by, updated_by)
                values (?, ?, 10, (select proc_id from mst_proc where proc_cd = 'SPOT'), 30, 't', 't')""", c, rid);
        accounts.exec(c, "update mst_routing set status = 'RELEASED', released_at = now(), released_by = 't' where routing_id = ?", rid);
        accounts.exec(c, "insert into mst_cust (comp_cd, cust_cd, cust_nm, cust_type, created_by, updated_by) values (?, 'C-1', '가나 상사', 'SALES', 't', 't')", c);
        accounts.exec(c, "insert into mst_worker (comp_cd, worker_cd, worker_nm, created_by, updated_by) values (?, 'W1', '작업자', 't', 't')", c);
    }

    /** 지시 만들기(+ 발행). 반환 wo_id */
    private long wo(String c, LocalDate start, LocalDate end, int priority, boolean release) {
        return runner.call(c, () -> {
            long id = workOrders.createAs(new WoForm("P1", "FG", "10", start.toString(), end.toString(), null, null, null, priority,
                    null, null), "t");
            if (release) {
                workOrders.releaseAs(id, "t");
            }
            return id;
        });
    }

    /** 공정 상태 바꾸기 — 같은 트랜잭션의 이벤트 행이 있어야 한다(V036 트리거) */
    private void procEvent(String c, long woId, String event, String status) {
        runner.run(c, () -> {
            long procId = jdbc.queryForObject("select wo_proc_id from prd_wo_proc where wo_id = ? order by op_seq limit 1", Long.class, woId);
            jdbc.update("""
                    insert into prd_wo_proc_event (comp_cd, wo_id, wo_proc_id, event_cd, reason_cd, gate_mode, work_day, client_req_id,
                                                   occurred_at, entry_method, recorded_by, performed_by)
                    values (?, ?, ?, ?, ?, ?, (now() at time zone 'Asia/Seoul')::date, ?, now(), 'KEY', 't', (select worker_id from mst_worker where worker_cd = 'W1'))""",
                    c, woId, procId, event, "PAUSE".equals(event) ? "MAT_WAIT" : null, "START".equals(event) ? "OK" : null,
                    UUID.randomUUID().toString());
            jdbc.update("update prd_wo_proc set status = ? where wo_proc_id = ?", status, procId);
        });
    }

    private long lot(String c, String lotNo, String qty, String holdReason) {
        long id = accounts.query(c, """
                insert into lot_master (comp_cd, lot_no, item_id, origin, qty_created, unit_cd, occurred_at, created_by, updated_by)
                values (?, ?, (select item_id from mst_item where item_cd = 'RM'), 'RECEIVED', ?::numeric, 'KG', now(), 't', 't')
                returning lot_id""", Long.class, c, lotNo, qty);
        if (holdReason != null) {
            accounts.exec(c, "insert into lot_hold (comp_cd, lot_id, source, reason, held_by) select comp_cd, lot_id, 'MANUAL', ?, 't' from lot_master where lot_id = ?", holdReason, id);
        }
        return id;
    }

    /** 수주 1건 + 행(납기들). 반환 order_id */
    private long order(String c, String orderNo, LocalDate... dues) {
        long oid = accounts.query(c, """
                insert into sal_order (comp_cd, order_no, cust_id, order_date, created_by, updated_by)
                values (?, ?, (select cust_id from mst_cust where cust_cd = 'C-1'), (now() at time zone 'Asia/Seoul')::date, 't', 't') returning order_id""",
                Long.class, c, orderNo);
        int no = 1;
        for (LocalDate d : dues) {
            accounts.exec(c, """
                    insert into sal_order_line (comp_cd, order_id, line_no, item_id, qty, due_date, created_by, updated_by)
                    values (?, ?, ?, (select item_id from mst_item where item_cd = 'FG'), 100, ?, 't', 't')""", c, oid, no++, d);
        }
        return oid;
    }

    private long approval(String c, long requester, long approver, String title, String lineStatus, int daysAgo) {
        long id = accounts.query(c, """
                insert into apr_request (comp_cd, doc_type, biz_key, title, requester_id, cur_seq, requested_at, created_by, updated_by)
                values (?, 'TEST_DOC', ?, ?, ?, 1, now() - make_interval(days => ?), 't', 't') returning apr_id""",
                Long.class, c, UUID.randomUUID().toString(), title, requester, daysAgo);
        accounts.exec(c, "insert into apr_line (comp_cd, apr_id, seq, approver_id, line_type, status) values (?, ?, 1, ?, 'APPROVE', ?)",
                c, id, approver, lineStatus);
        return id;
    }

    private static String card(String code) {
        return "$.data.cards[?(@.code == '" + code + "')]";
    }

    // ---------- 정상 ----------

    @Test
    void 카드_4장이_숫자_보조숫자_항목_상태를_돌려준다() throws Exception {
        long w1 = wo(comp, today, today.plusDays(2), 3, true);                 // 오늘 시작
        long w2 = wo(comp, today.minusDays(5), today.minusDays(1), 1, true);   // 계획 종료 지남
        wo(comp, today.plusDays(1), today.plusDays(3), 5, true);               // 내일 시작 → 세지 않음
        wo(comp, today, today, 5, false);                                      // 계획(발행 전) → 세지 않음
        procEvent(comp, w1, "START", "RUN");
        procEvent(comp, w2, "START", "RUN");
        procEvent(comp, w2, "PAUSE", "PAUSE");
        lot(comp, "ZT-HOLD-1", "25.5", "외관 불량 의심");
        lot(comp, "ZT-OK-1", "10", null);
        order(comp, "ZT-SO-1", today.minusDays(2), today, today.plusDays(7), today.plusDays(8));
        long other = accounts.newUser(comp, "req1", PW);
        approval(comp, other, userId, "ZT 단가 변경 승인", "CURRENT", 5);
        approval(comp, other, userId, "ZT 신규 거래처", "CURRENT", 0);

        mvc.perform(get(CARDS).with(admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.today").value(today.toString()))
                .andExpect(jsonPath("$.data.cards[*].code").value(Matchers.contains("apr-todo", "sal-due-soon", "prd-wo-today", "prd-lot-hold")))
                // 오늘 작업지시: 2건, 진행 공정 1·일시정지 1·종료 지남 1 → 확인 필요, 항목은 우선순위 순(1 → 3)
                .andExpect(jsonPath(card("prd-wo-today") + ".count").value(2))
                .andExpect(jsonPath(card("prd-wo-today") + ".tone").value("WARN"))
                .andExpect(jsonPath(card("prd-wo-today") + ".facts[*].value").value(Matchers.contains(1, 1, 1)))
                .andExpect(jsonPath(card("prd-wo-today") + ".progCd").value("prd-wo"))
                .andExpect(jsonPath(card("prd-wo-today") + ".progTitle").value("작업지시 관리"))
                .andExpect(jsonPath(card("prd-wo-today") + ".items[0].tone").value("WARN"))
                .andExpect(jsonPath(card("prd-wo-today") + ".items[1].detail").value(Matchers.contains(Matchers.containsString("발행"))))
                // 보류 LOT: 잔량 남은 보류 1건
                .andExpect(jsonPath(card("prd-lot-hold") + ".count").value(1))
                .andExpect(jsonPath(card("prd-lot-hold") + ".items[0].title").value("ZT-HOLD-1"))
                .andExpect(jsonPath(card("prd-lot-hold") + ".items[0].detail").value(Matchers.contains("RM · 잔량 25.5 KG · 외관 불량 의심")))
                .andExpect(jsonPath(card("prd-lot-hold") + ".items[0].link.keyword").value(Matchers.contains("ZT-HOLD-1")))
                .andExpect(jsonPath(card("sal-due-soon") + ".items[0].link.keyword").value(Matchers.contains("ZT-SO-1")))
                .andExpect(jsonPath(card("prd-wo-today") + ".items[0].link.from").value(Matchers.contains(today.minusDays(5).toString())))
                .andExpect(jsonPath(card("apr-todo") + ".items[0].link").value(Matchers.contains((Object) null)))
                // 납기: 지남 1·오늘 1·7일 안 1(8일 뒤는 제외) → 지연
                .andExpect(jsonPath(card("sal-due-soon") + ".count").value(3))
                .andExpect(jsonPath(card("sal-due-soon") + ".tone").value("ALERT"))
                .andExpect(jsonPath(card("sal-due-soon") + ".facts[*].value").value(Matchers.contains(1, 1, 1)))
                .andExpect(jsonPath(card("sal-due-soon") + ".items[0].title").value("ZT-SO-1-1"))
                .andExpect(jsonPath(card("sal-due-soon") + ".items[0].tone").value("ALERT"))
                // 결재: 내 차례 2건, 3일 넘은 것 1 · 링크 없음(개인 카드)
                .andExpect(jsonPath(card("apr-todo") + ".count").value(2))
                .andExpect(jsonPath(card("apr-todo") + ".progCd").value(Matchers.contains("sys-approval")))   // 5단계 H19 — 결재함
                .andExpect(jsonPath(card("apr-todo") + ".facts[0].value").value(1))
                .andExpect(jsonPath(card("apr-todo") + ".items[0].title").value("ZT 단가 변경 승인"))
                .andExpect(jsonPath(card("apr-todo") + ".desc").value("내 차례인 결재"));
    }

    @Test
    void 납기_임박_항목은_일부_출하된_행의_잔량을_보인다() throws Exception {
        // 7단계(docs/37 19장): 수주 100 중 40 출하(V123 shipped_qty) → 항목은 "남은 60", 숫자(열린 행)는 그대로 1
        long oid = order(comp, "ZT-SO-SHIP", today.plusDays(2));
        accounts.exec(comp, "update sal_order_line set shipped_qty = 40 where order_id = ?", oid);
        mvc.perform(get(CARDS).with(admin))
                .andExpect(jsonPath(card("sal-due-soon") + ".count").value(Matchers.contains(1)))
                .andExpect(jsonPath(card("sal-due-soon") + ".items[0].detail").value(Matchers.contains(Matchers.containsString("남은 60 "))));
    }

    @Test
    void 할_일이_없으면_0_과_회색() throws Exception {
        mvc.perform(get(CARDS).with(admin))
                .andExpect(jsonPath("$.data.cards[*].count").value(Matchers.everyItem(Matchers.is(0))))
                .andExpect(jsonPath("$.data.cards[*].tone").value(Matchers.everyItem(Matchers.is("NEUTRAL"))))
                .andExpect(jsonPath("$.data.cards[*].error").value(Matchers.everyItem(Matchers.is(false))));
    }

    @Test
    void 항목은_5줄까지_숫자는_전체() throws Exception {
        for (int i = 0; i < 7; i++) {
            lot(comp, "ZT-H" + i, "1", "사유 " + i);
        }
        mvc.perform(get(CARDS).with(admin))
                .andExpect(jsonPath(card("prd-lot-hold") + ".count").value(7))
                .andExpect(jsonPath(card("prd-lot-hold") + ".items.length()").value(5));
    }

    @Test
    void 영어_사용자는_영어_문구() throws Exception {
        lot(comp, "ZT-HOLD-EN", "3", "scratch");
        mvc.perform(get(CARDS).with(accounts.as(comp, userId, "admin1", "en")))
                .andExpect(jsonPath(card("prd-lot-hold") + ".title").value("LOTs on hold"))
                .andExpect(jsonPath(card("prd-lot-hold") + ".items[0].detail").value(Matchers.contains("RM · remaining 3 KG · scratch")));
    }

    // ---------- 이상 사용: 흐름(F) — 끝난·취소된 것은 할 일이 아니다 ----------

    @Test
    void 취소_지시_마감_수주행_다_쓴_보류_LOT_끝난_결재는_세지_않는다() throws Exception {
        long w = wo(comp, today, today, 5, true);
        runner.run(comp, () -> jdbc.update("update prd_wo set status = 'CANCELLED', cancel_reason = 'ZT 취소' where wo_id = ?", w));
        long oid = order(comp, "ZT-SO-C", today, today);
        accounts.exec(comp, "update sal_order_line set status = 'DONE' where order_id = ? and line_no = 1", oid);
        accounts.exec(comp, "update sal_order_line set status = 'CANCELLED', cancel_reason = 'ZT' where order_id = ? and line_no = 2", oid);
        long held = lot(comp, "ZT-USED", "5", "보류");
        long child = lot(comp, "ZT-CHILD", "5", null);
        accounts.exec(comp, "update lot_hold set released_at = now(), released_by = 't', release_reason = '시험 해제' where released_at is null and lot_id = ?", held);
        accounts.exec(comp, """
                insert into lot_genealogy (comp_cd, parent_lot_id, child_lot_id, rel_type, qty, unit_cd, occurred_at, entry_method,
                                           recorded_by, performed_by)
                values (?, ?, ?, 'CONSUME', 5, 'KG', now(), 'KEY', 't', null)""", comp, held, child);
        accounts.exec(comp, "insert into lot_hold (comp_cd, lot_id, source, reason, held_by) select comp_cd, lot_id, 'MANUAL', '다 쓴 뒤 보류', 't' from lot_master where lot_id = ?", held);
        long other = accounts.newUser(comp, "req1", PW);
        approval(comp, other, userId, "ZT 내 차례 아님", "WAIT", 0);
        long done = approval(comp, other, userId, "ZT 끝남", "APPROVED", 0);
        accounts.exec(comp, "update apr_request set status = 'APPROVED', finished_at = now() where apr_id = ?", done);
        mvc.perform(get(CARDS).with(admin))
                .andExpect(jsonPath("$.data.cards[*].count").value(Matchers.everyItem(Matchers.is(0))));
    }

    // ---------- 이상 사용: 권한(E) ----------

    @Test
    void 화면_권한이_있는_카드만_보이고_홈_권한이_없으면_403() throws Exception {
        long u = accounts.newUser(comp, "sales1", PW);
        accounts.grant(comp, u, "SALES", HomeService.PROG, "SEARCH");
        accounts.grant(comp, u, "SALES", "sal-order", "SEARCH");
        lot(comp, "ZT-HOLD-X", "1", "보류");
        mvc.perform(get(CARDS).with(accounts.as(comp, u, "sales1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.cards[*].code").value(Matchers.contains("apr-todo", "sal-due-soon")));
        // 저장 권한만 있고 조회가 없으면 카드도 없다
        accounts.grant(comp, u, "SALES", "prd-lot", "SAVE");
        mvc.perform(get(CARDS).with(accounts.as(comp, u, "sales1")))
                .andExpect(jsonPath("$.data.cards[*].code").value(Matchers.not(Matchers.hasItem("prd-lot-hold"))));
        // 권한그룹을 끄면 다음 요청부터 홈 API 자체가 403
        accounts.exec(comp, "update sys_auth_group set use_yn = 'N' where auth_grp_cd = 'SALES'");
        mvc.perform(get(CARDS).with(accounts.as(comp, u, "sales1"))).andExpect(status().isForbidden());
        long none = accounts.newUser(comp, "none1", PW);
        mvc.perform(get(CARDS).with(accounts.as(comp, none, "none1"))).andExpect(status().isForbidden());
        // 비로그인 → 401 JSON
        mvc.perform(get(CARDS)).andExpect(status().isUnauthorized());
    }

    @Test
    void 사용_중지된_화면의_카드는_빠진다() throws Exception {
        lot(comp, "ZT-HOLD-Y", "1", "보류");
        // sys_prog 는 전역(앱 계정 SELECT 만) — 소유자 계정이 없는 시험 DB 라 화면 사용 중지는 이 경로로만 흉내 낼 수 없다:
        // 대신 그 화면 권한을 잃은 경우와 같은 결과임을 확인한다(R1 판정은 PermissionService 가 sys_prog.use_yn 도 본다)
        accounts.exec(comp, "delete from sys_auth_prog where prog_cd = 'prd-lot'");
        mvc.perform(get(CARDS).with(admin))
                .andExpect(jsonPath("$.data.cards[*].code").value(Matchers.not(Matchers.hasItem("prd-lot-hold"))));
    }

    @Test
    void 결재_카드는_내_차례만_남의_결재는_안_보인다() throws Exception {
        long other = accounts.newUser(comp, "admin2", PW);
        grantAll(comp, other, "ADMIN");
        approval(comp, userId, other, "ZT 남의 차례", "CURRENT", 0);   // 내가 요청자, 다른 사람 차례
        mvc.perform(get(CARDS).with(admin)).andExpect(jsonPath(card("apr-todo") + ".count").value(0));
        mvc.perform(get(CARDS).with(accounts.as(comp, other, "admin2"))).andExpect(jsonPath(card("apr-todo") + ".count").value(1));
    }

    @Test
    void A사_사용자는_B사_지시_LOT_수주_결재를_세지_않는다() throws Exception {
        String b = accounts.newComp("HM");
        long ub = accounts.newUser(b, "admin1", PW);
        grantAll(b, ub, "ADMIN");
        fixtures(b);
        wo(b, today, today, 5, true);
        lot(b, "ZT-B-HOLD", "1", "B사 보류");
        order(b, "ZT-B-SO", today);
        long breq = accounts.newUser(b, "req1", PW);
        approval(b, breq, ub, "ZT B사 결재", "CURRENT", 0);
        mvc.perform(get(CARDS).with(admin))
                .andExpect(jsonPath("$.data.cards[*].count").value(Matchers.everyItem(Matchers.is(0))));
        mvc.perform(get(CARDS).with(accounts.as(b, ub, "admin1")))
                .andExpect(jsonPath("$.data.cards[*].count").value(Matchers.everyItem(Matchers.is(1))));
        // A사 회사 코드에 B사 사용자 id 를 붙인 조작 principal → 요청 필터가 계정·회사 불일치로 끊는다(401)
        mvc.perform(get(CARDS).with(accounts.as(comp, ub, "admin1"))).andExpect(status().isUnauthorized());
    }

    @Test
    void 저장형_XSS_글자는_그대로_글자로_돌려준다() throws Exception {
        lot(comp, "ZT-XSS", "1", "<img src=x onerror=alert(1)>");
        mvc.perform(get(CARDS).with(admin))
                .andExpect(jsonPath(card("prd-lot-hold") + ".items[0].detail")
                        .value(Matchers.contains(Matchers.containsString("<img src=x onerror=alert(1)>"))));
    }

    // ---------- 4단계: 카드 숫자 링크 = 그 목록 (docs/37 16.2, H17) ----------

    /** 카드 숫자·링크를 읽어 링크 조건 그대로 목록 API 를 부르고, 목록 건수(수주는 "납기 행 m건")를 돌려준다 */
    @SuppressWarnings("unchecked")
    private long[] cardAndList(String code, String listUrl, String countPath) throws Exception {
        String home = mvc.perform(get(CARDS).with(admin)).andReturn().getResponse().getContentAsString();
        java.util.List<Object> counts = com.jayway.jsonpath.JsonPath.read(home, card(code) + ".count");
        java.util.List<java.util.Map<String, String>> links = com.jayway.jsonpath.JsonPath.read(home, card(code) + ".link");
        java.util.Map<String, String> link = links.get(0);
        assertThat(link).as(code + " 링크").isNotNull();
        var req = get(listUrl).with(admin);
        link.forEach(req::param);
        String list = mvc.perform(req).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        Object n = com.jayway.jsonpath.JsonPath.read(list, countPath);
        return new long[] {((Number) counts.get(0)).longValue(), ((Number) n).longValue()};
    }

    @Test
    void 카드_숫자_링크로_연_목록_건수는_카드_숫자와_같다_기본_기간_밖_문서까지() throws Exception {
        // 작업지시: 45일 전 시작(기본 30일 밖)·오늘 시작·내일 시작(제외)·계획(제외)
        long oldWo = wo(comp, today.minusDays(5), today.plusDays(5), 5, true);
        accounts.exec(comp, "update prd_wo set plan_start = ? where wo_id = ?", today.minusDays(45), oldWo);   // 서비스는 라우팅 유효일 전 시작을 막아 직접
        wo(comp, today, today.plusDays(3), 5, true);
        wo(comp, today.plusDays(1), today.plusDays(3), 5, true);
        wo(comp, today.minusDays(2), today.plusDays(3), 5, false);
        long[] w = cardAndList("prd-wo-today", "/api/production/wos", "$.data.rows.length()");
        assertThat(w[0]).isEqualTo(2);
        assertThat(w[1]).as("작업지시 목록 = 카드 숫자").isEqualTo(w[0]);
        // 보류 LOT: 잔량 있는 보류 2(하나는 60일 전 등록) · 보류 아님 1
        lot(comp, "ZT-L-OLD", "3", "보류 오래됨");
        lot(comp, "ZT-L-NEW", "1", "보류");
        lot(comp, "ZT-L-OK", "1", null);
        accounts.exec(comp, "update lot_master set created_at = now() - interval '60 days' where lot_no = 'ZT-L-OLD'");
        long[] l = cardAndList("prd-lot-hold", "/api/production/lots", "$.data.rows.length()");
        assertThat(l[0]).isEqualTo(2);
        assertThat(l[1]).as("LOT 목록 = 카드 숫자").isEqualTo(l[0]);
        // 수주: 60일 전 수주의 열린 행(납기 지남) + 오늘 수주의 행 2(3일 뒤·20일 뒤 제외) + 마감 수주의 열린 행(수주 상태 무관하게 센다)
        long old = order(comp, "ZT-SO-OLD", today.minusDays(1));
        accounts.exec(comp, "update sal_order set order_date = " + TestAccounts.COMP_TODAY + " - 60 where order_id = ?", old);
        order(comp, "ZT-SO-NEW", today.plusDays(3), today.plusDays(20));
        long[] o = cardAndList("sal-due-soon", "/api/sales/orders", "$.data.dueLineCnt");
        assertThat(o[0]).isEqualTo(2);
        assertThat(o[1]).as("수주 목록 납기 행 = 카드 숫자").isEqualTo(o[0]);
        // 기본 기간(지난 30일)만 걸면 어긋난다 — 링크가 기간을 비우는 이유
        String narrow = mvc.perform(get("/api/production/wos").param("status", "ACTIVE").param("from", today.minusDays(29).toString())
                .param("to", today.toString()).with(admin)).andReturn().getResponse().getContentAsString();
        assertThat(((Number) com.jayway.jsonpath.JsonPath.read(narrow, "$.data.rows.length()")).longValue()).isLessThan(w[0]);
    }

    @Test
    @SuppressWarnings("unchecked")
    void 보류_LOT_항목_링크의_날짜는_회사_날짜_자정_직후_등록도_그_날_목록에서_찾는다() throws Exception {
        // 회사(서울) 오늘 00:30 등록 = UTC 전날 15:30 — DB 세션 시간대로 날짜를 잡으면 전날이 되어 링크 목록이 비었다
        lot(comp, "ZT-L-MIDNIGHT", "2", "보류");
        accounts.exec(comp, "update lot_master set created_at = (" + TestAccounts.COMP_TODAY + " + time '00:30') at time zone 'Asia/Seoul'"
                + " where lot_no = 'ZT-L-MIDNIGHT'");
        String home = mvc.perform(get(CARDS).with(admin)).andReturn().getResponse().getContentAsString();
        java.util.List<java.util.Map<String, String>> links = com.jayway.jsonpath.JsonPath.read(home, card("prd-lot-hold") + ".items[0].link");
        java.util.Map<String, String> link = links.get(0);
        assertThat(link).containsEntry("from", today.toString()).containsEntry("to", today.toString());
        var req = get("/api/production/lots").with(admin);
        link.forEach(req::param);
        mvc.perform(req).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.rows[*].lotNo").value(Matchers.contains("ZT-L-MIDNIGHT")));
    }

    @Test
    void 보류_LOT_항목은_가장_오래된_살아_있는_보류_근거부터() throws Exception {
        // 6단계(docs/37 18.2): updated_at(보류 동기화마다 바뀜)이 아니라 살아 있는 lot_hold 의 가장 이른 held_at 순
        for (String[] l : new String[][] {{"ZT-H-NEW", "1"}, {"ZT-H-OLD", "5"}}) {
            lot(comp, l[0], "3", null);
            accounts.exec(comp, """
                    insert into lot_hold (comp_cd, lot_id, source, reason, held_at, held_by)
                    select comp_cd, lot_id, 'MANUAL', '외관 의심', now() - make_interval(days => ?::int), 't' from lot_master where lot_no = ?""",
                    Integer.parseInt(l[1]), l[0]);
        }
        // 오래된 쪽 LOT 을 나중에 건드려 updated_at 을 새로 만든다 — 옛 순서라면 뒤로 밀렸을 것
        accounts.exec(comp, "update lot_master set row_ver = row_ver + 1 where lot_no = 'ZT-H-OLD'");
        mvc.perform(get(CARDS).with(admin))
                .andExpect(jsonPath(card("prd-lot-hold") + ".items[*].title").value(Matchers.contains("ZT-H-OLD", "ZT-H-NEW")))
                .andExpect(jsonPath(card("prd-lot-hold") + ".items[0].detail").value(Matchers.contains(Matchers.containsString("외관 의심"))));
    }

    // ---------- 실패 격리(R3·R4)·기동 검사(R6) ----------

    private HomeService serviceWith(HomeCardProvider... ps) {
        StaticListableBeanFactory f = new StaticListableBeanFactory();
        int i = 0;
        for (HomeCardProvider p : ps) {
            f.addBean("p" + i++, p);
        }
        return new HomeService(f.getBeanProvider(HomeCardProvider.class), permissions, progs, messages, clock, jdbc, tm, homeMapper);
    }

    private static HomeCardProvider fake(String code, int sort, java.util.function.Function<HomeCardContext, HomeCard> fn) {
        return new HomeCardProvider() {
            public String code() { return code; }
            public String titleKey() { return "home.card.prdLotHold"; }
            public String progCd() { return null; }
            public int sortNo() { return sort; }
            public HomeCard load(HomeCardContext ctx) { return fn.apply(ctx); }
        };
    }

    private HomeView callAs(HomeService s) {
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                new LoginUser(userId, comp, "admin1", "admin1", "ko", false), null, List.of(new SimpleGrantedAuthority("ROLE_USER"))));
        try (var scope = TenantContext.openRequestScope(comp, "u" + userId + ":admin1")) {
            return s.cards(Locale.KOREAN);
        }
    }

    @Test
    @org.junit.jupiter.api.extension.ExtendWith(org.springframework.boot.test.system.OutputCaptureExtension.class)
    void 느린_카드는_카드_코드와_ms_를_경고로_남기고_빠른_카드는_남기지_않는다(org.springframework.boot.test.system.CapturedOutput out) {
        HomeService s = serviceWith(
                fake("t-slow", 1, ctx -> new HomeCard(jdbc.queryForObject("select 1 from pg_sleep(0.6)", Long.class), Tone.NEUTRAL, null, null)),
                fake("t-fast", 2, ctx -> new HomeCard(1L, Tone.NEUTRAL, null, null)));
        HomeView v = callAs(s);
        assertThat(v.cards()).noneMatch(CardView::error);
        assertThat(out.getAll()).contains("홈 카드 느림 card=t-slow ms=").doesNotContain("card=t-fast");
    }

    @Test
    void 카드_하나가_SQL_오류_시간_초과_예외_null_이어도_나머지는_뜬다() {
        lot(comp, "ZT-HOLD-ISO", "1", "보류");
        HomeService s = serviceWith(
                fake("t-sql-error", 1, ctx -> { jdbc.queryForObject("select 1 / 0", Integer.class); return null; }),
                fake("t-timeout", 2, ctx -> { jdbc.queryForObject("select pg_sleep(5)::text", String.class); return null; }),
                fake("t-throw", 3, ctx -> { throw new IllegalStateException("boom"); }),
                fake("t-null", 4, ctx -> null),
                fake("t-ok", 5, ctx -> new HomeCard(jdbc.queryForObject("select count(*) from lot_master where hold_yn = 'Y'", Long.class),
                        Tone.WARN, null, null)));
        long t0 = System.nanoTime();
        HomeView v = callAs(s);
        long ms = (System.nanoTime() - t0) / 1_000_000;
        assertThat(v.cards()).extracting(CardView::code).containsExactly("t-sql-error", "t-timeout", "t-throw", "t-null", "t-ok");
        assertThat(v.cards()).filteredOn(CardView::error).extracting(CardView::code)
                .containsExactly("t-sql-error", "t-timeout", "t-throw", "t-null");
        assertThat(v.cards()).filteredOn(CardView::error).allSatisfy(c -> {
            assertThat(c.count()).isNull();
            assertThat(c.items()).isEmpty();
        });
        CardView ok = v.cards().get(4);
        assertThat(ok.error()).isFalse();
        assertThat(ok.count()).isEqualTo(1L);   // 앞 카드의 SQL 오류가 트랜잭션을 깨뜨리지 않았고 회사 범위도 그대로
        assertThat(ms).as("시간 초과 카드는 2초에서 끊긴다").isLessThan(4500);
    }

    @Test
    void 트랜잭션_안에서_부르면_거부() {
        HomeService s = serviceWith(fake("t-ok", 1, ctx -> new HomeCard(0, null, null, null)));
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                new LoginUser(userId, comp, "admin1", "admin1", "ko", false), null, List.of(new SimpleGrantedAuthority("ROLE_USER"))));
        assertThatThrownBy(() -> runner.run(comp, () -> s.cards(Locale.KOREAN))).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void 제공자가_넘치게_돌려줘도_보조숫자_3_항목_5로_자른다() {
        List<HomeCard.Item> many = java.util.stream.IntStream.range(0, 9).mapToObj(i -> new HomeCard.Item("i" + i, "d", Tone.NEUTRAL)).toList();
        List<HomeCard.Fact> facts = java.util.stream.IntStream.range(0, 6).mapToObj(i -> new HomeCard.Fact("f" + i, i, Tone.NEUTRAL)).toList();
        HomeView v = callAs(serviceWith(fake("t-many", 1, ctx -> new HomeCard(9, Tone.INFO, facts, many))));
        assertThat(v.cards().get(0).items()).hasSize(HomeService.MAX_ITEMS);
        assertThat(v.cards().get(0).facts()).hasSize(HomeService.MAX_FACTS);
    }

    @Test
    void 카드_코드_형식_중복_제목_없음은_기동_실패() {
        assertThatThrownBy(() -> serviceWith(fake("dup", 1, ctx -> null), fake("dup", 2, ctx -> null)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("중복");
        for (String bad : new String[] {"Bad", "1abc", "a", "a_b", "x".repeat(41), "a b"}) {
            assertThatThrownBy(() -> serviceWith(fake(bad, 1, ctx -> null))).as(bad)
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("형식");
        }
        HomeCardProvider noTitle = new HomeCardProvider() {
            public String code() { return "no-title"; }
            public String titleKey() { return " "; }
            public String progCd() { return null; }
            public int sortNo() { return 1; }
            public HomeCard load(HomeCardContext ctx) { return null; }
        };
        assertThatThrownBy(() -> serviceWith(noTitle)).hasMessageContaining("제목");
    }

    @Test
    void 실제_등록된_카드_코드는_규칙을_지킨다() {
        // 기동했다는 것 자체가 검사 통과 — 1단계 카드 4장이 다 등록됐는지만 확인
        HomeView v = callAs(serviceWith());
        assertThat(v.cards()).isEmpty();
        assertThat(v.today()).isEqualTo(today.toString());
    }
}
