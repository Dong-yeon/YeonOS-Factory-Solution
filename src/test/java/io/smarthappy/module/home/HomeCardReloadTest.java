package io.smarthappy.module.home;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

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
import io.smarthappy.module.home.HomeService.CardReload;
import io.smarthappy.module.home.HomeService.HomeView;
import io.smarthappy.platform.auth.AuthMapperFacade;
import io.smarthappy.platform.auth.PermissionService;
import io.smarthappy.platform.common.web.BusinessException;
import io.smarthappy.platform.security.LoginUser;
import io.smarthappy.platform.tenant.TenantContext;
import io.smarthappy.platform.tenant.TenantRunner;
import io.smarthappy.platform.time.BizClock;
import io.smarthappy.support.PostgresTestBase;
import io.smarthappy.support.TestAccounts;

/**
 * 홈 4단계 (docs/37 16장) — 카드 하나 다시 읽기 API(권한 판정 = 홈 전체와 같음, 볼 수 없는 카드·없는 코드 같은 404, 숨긴 카드도 읽음,
 * 실패 격리·시간 예산 그대로, A사로 B사) + 카드 수준 링크(형식 검사 safeLink, 링크 화면 없는 카드는 버림).
 */
@AutoConfigureMockMvc
class HomeCardReloadTest extends PostgresTestBase {

    private static final String PW = "Lantern-oak-62";
    private static final String C = "/api/home/cards/";

    @Autowired
    MockMvc mvc;

    @Autowired
    TestAccounts accounts;

    @Autowired
    TenantRunner runner;

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
    RequestPostProcessor me;

    @BeforeEach
    void setUp() {
        comp = accounts.newComp("HR");
        userId = accounts.newUser(comp, "user1", PW);
        accounts.grant(comp, userId, "OFFICE", HomeService.PROG, "SEARCH");
        accounts.grant(comp, userId, "OFFICE", "prd-lot", "SEARCH");
        me = accounts.as(comp, userId, "user1");
    }

    @AfterEach
    void clearSecurity() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void 카드_하나만_다시_읽으면_홈과_같은_카드가_온다() throws Exception {
        mvc.perform(get(C + "prd-lot-hold").with(me))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.asOf").isNotEmpty())
                .andExpect(jsonPath("$.data.card.code").value("prd-lot-hold"))
                .andExpect(jsonPath("$.data.card.progCd").value("prd-lot"))
                .andExpect(jsonPath("$.data.card.count").value(0))
                .andExpect(jsonPath("$.data.card.error").value(false));
        mvc.perform(get(C + "apr-todo").with(me)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.card.title").value("결재 대기"));
    }

    @Test
    void 숨긴_카드도_읽고_권한_없는_카드와_없는_코드는_같은_404() throws Exception {
        accounts.exec(comp, """
                insert into sys_home_user_card (comp_cd, user_id, card_cd, sort_no, hidden_yn, created_by, updated_by)
                values (?, ?, 'prd-lot-hold', 1, 'Y', 't', 't')""", comp, userId);
        mvc.perform(get(C + "prd-lot-hold").with(me)).andExpect(status().isOk());
        // 작업지시·납기 화면 권한 없음 → 없는 카드와 구별되지 않는 404
        String forbidden = mvc.perform(get(C + "prd-wo-today").with(me)).andExpect(status().isNotFound())
                .andReturn().getResponse().getContentAsString();
        String missing = mvc.perform(get(C + "no-such-card").with(me)).andExpect(status().isNotFound())
                .andReturn().getResponse().getContentAsString();
        assertThat(forbidden).isEqualTo(missing);
        mvc.perform(get(C + "x".repeat(300)).with(me)).andExpect(status().isNotFound());
        mvc.perform(get(C + "%3Cscript%3E").with(me)).andExpect(status().is4xxClientError());   // 방화벽 400 또는 404
        // 권한을 받으면 읽힌다
        accounts.grant(comp, userId, "OFFICE", "prd-wo", "SEARCH");
        mvc.perform(get(C + "prd-wo-today").with(me)).andExpect(status().isOk());
        // 홈 권한이 없으면 카드 권한이 있어도 403
        long none = accounts.newUser(comp, "none1", PW);
        accounts.grant(comp, none, "LOTONLY", "prd-lot", "SEARCH");
        mvc.perform(get(C + "prd-lot-hold").with(accounts.as(comp, none, "none1"))).andExpect(status().isForbidden());
    }

    /** 보류 LOT 1건 (원자재 품목 + LOT, 직접 SQL — 그 회사 범위) */
    private void heldLot(String c, String lotNo) {
        accounts.exec(c, """
                insert into mst_item (comp_cd, item_cd, item_nm, item_type, proc_type, unit_cd, created_by, updated_by)
                values (?, 'RM', 'RM 품목', 'FG', 'MAKE', 'KG', 't', 't') on conflict do nothing""", c);
        long id = accounts.query(c, """
                insert into lot_master (comp_cd, lot_no, item_id, origin, qty_created, unit_cd, occurred_at, created_by, updated_by)
                values (?, ?, (select item_id from mst_item where item_cd = 'RM'), 'RECEIVED', 5, 'KG', now(), 't', 't')
                returning lot_id""", Long.class, c, lotNo);
        accounts.exec(c, "insert into lot_hold (comp_cd, lot_id, source, reason, held_by) select comp_cd, lot_id, 'MANUAL', '시험', 't' from lot_master where lot_id = ?", id);
    }

    @Test
    void A사_사용자가_다시_읽어도_B사_건수는_섞이지_않는다() throws Exception {
        String b = accounts.newComp("HR");
        heldLot(b, "ZT-HR-B1");
        heldLot(b, "ZT-HR-B2");
        heldLot(comp, "ZT-HR-A1");
        long ub = accounts.newUser(b, "user1", PW);
        accounts.grant(b, ub, "OFFICE", HomeService.PROG, "SEARCH");
        accounts.grant(b, ub, "OFFICE", "prd-lot", "SEARCH");
        assertThat(count(me)).isEqualTo(1);
        assertThat(count(accounts.as(b, ub, "user1"))).isEqualTo(2);
        mvc.perform(get(C + "prd-lot-hold").with(me))
                .andExpect(jsonPath("$.data.card.items[*].title").value(org.hamcrest.Matchers.contains("ZT-HR-A1")));
    }

    private long count(RequestPostProcessor who) throws Exception {
        String body = mvc.perform(get(C + "prd-lot-hold").with(who)).andReturn().getResponse().getContentAsString();
        return ((Number) com.jayway.jsonpath.JsonPath.read(body, "$.data.card.count")).longValue();
    }

    // ---------- 서비스 단위: 실패 격리·카드 링크 ----------

    private HomeService serviceWith(HomeCardProvider... ps) {
        StaticListableBeanFactory f = new StaticListableBeanFactory();
        int i = 0;
        for (HomeCardProvider p : ps) {
            f.addBean("p" + i++, p);
        }
        return new HomeService(f.getBeanProvider(HomeCardProvider.class), permissions, progs, messages, clock, jdbc, tm, homeMapper);
    }

    private static HomeCardProvider fake(String code, String progCd, Function<HomeCardContext, HomeCard> fn) {
        return new HomeCardProvider() {
            public String code() { return code; }
            public String titleKey() { return "home.card.prdLotHold"; }
            public String progCd() { return progCd; }
            public int sortNo() { return 1; }
            public HomeCard load(HomeCardContext ctx) { return fn.apply(ctx); }
        };
    }

    private <T> T as(Function<Locale, T> fn) {
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                new LoginUser(userId, comp, "user1", "user1", "ko", false), null, List.of(new SimpleGrantedAuthority("ROLE_USER"))));
        try (var scope = TenantContext.openRequestScope(comp, "u" + userId + ":user1")) {
            return fn.apply(Locale.KOREAN);
        }
    }

    @Test
    void 다시_읽기도_실패_격리와_시간_예산을_지킨다() {
        HomeService s = serviceWith(
                fake("t-sql-error", null, ctx -> { jdbc.queryForObject("select 1 / 0", Integer.class); return null; }),
                fake("t-timeout", null, ctx -> { jdbc.queryForObject("select pg_sleep(5)::text", String.class); return null; }));
        CardReload r = as(l -> s.card("t-sql-error", l));
        assertThat(r.card().error()).isTrue();
        assertThat(r.card().count()).isNull();
        long t0 = System.nanoTime();
        assertThat(as(l -> s.card("t-timeout", l)).card().error()).isTrue();
        assertThat((System.nanoTime() - t0) / 1_000_000).as("2초에서 끊긴다").isLessThan(4500);
        // 트랜잭션 안에서 부르면 거부(카드마다 트랜잭션을 연다)
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                new LoginUser(userId, comp, "user1", "user1", "ko", false), null, List.of(new SimpleGrantedAuthority("ROLE_USER"))));
        assertThatThrownBy(() -> runner.run(comp, () -> s.card("t-sql-error", Locale.KOREAN))).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> as(l -> s.card(null, l))).isInstanceOf(BusinessException.class);
    }

    @Test
    void 카드_링크는_형식이_맞고_링크_화면이_있을_때만_내려간다() {
        HomeService s = serviceWith(
                fake("t-link", "prd-lot", ctx -> new HomeCard(3, Tone.WARN, null, null, Map.of("keyword", "ZT-", "from", "2026-01-01"))),
                fake("t-bad", "prd-lot", ctx -> new HomeCard(3, Tone.WARN, null, null, Map.of("bad name", "<script>"))),
                fake("t-long", "prd-lot", ctx -> new HomeCard(3, Tone.WARN, null, null, Map.of("keyword", "x".repeat(201)))),
                fake("t-personal", null, ctx -> new HomeCard(3, Tone.WARN, null, null, Map.of("keyword", "x"))),
                fake("t-none", "prd-lot", ctx -> new HomeCard(3, Tone.WARN, null, null)));
        HomeView v = as(s::cards);
        Map<String, Map<String, String>> links = new java.util.HashMap<>();
        v.cards().forEach(c -> links.put(c.code(), c.link()));
        assertThat(links.get("t-link")).containsEntry("keyword", "ZT-").hasSize(2);
        assertThat(links.get("t-bad")).as("이름 형식").isNull();
        assertThat(links.get("t-long")).as("값 200자 초과").isNull();
        assertThat(links.get("t-personal")).as("링크 화면 없는 개인 카드").isNull();
        assertThat(links.get("t-none")).isNull();
        assertThat(as(l -> s.card("t-link", l)).card().link()).containsEntry("from", "2026-01-01");
    }
}
