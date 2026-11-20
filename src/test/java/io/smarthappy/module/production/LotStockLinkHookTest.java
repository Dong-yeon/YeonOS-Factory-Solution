package io.smarthappy.module.production;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.InOrder;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import io.smarthappy.module.wms.link.LotStockLink;
import io.smarthappy.platform.common.web.BusinessException;
import io.smarthappy.platform.common.web.HbErrorCode;
import io.smarthappy.platform.tenant.TenantRunner;
import io.smarthappy.support.PostgresTestBase;
import io.smarthappy.support.TestAccounts;

/**
 * WMS B5 훅 (docs/25 4.20 ④, docs/30 12.4) — 계보 행이 생길 때마다 LotStockLink.onEdge(그 행 id), 역분개면 onReverse(역분개 행 id),
 * 같은 트랜잭션이라 WMS 409 는 업무 전체를 되돌린다, WMS 없는 설치본은 NONE. 계약 빈은 가짜(WMS 구현 빈이 생겨도 이 시험에서는 가짜).
 */
@AutoConfigureMockMvc
class LotStockLinkHookTest extends PostgresTestBase {

    private static final String PW = "Cedar-lantern-41";
    private static final String L = "/api/production/lots";

    @Autowired
    MockMvc mvc;

    @Autowired
    TestAccounts accounts;

    @Autowired
    TenantRunner runner;

    @Autowired
    LotService lots;

    @Autowired
    JdbcTemplate jdbc;

    @MockitoBean
    LotStockLink link;

    String comp;
    RequestPostProcessor admin;

    @BeforeEach
    void setUp() {
        comp = accounts.newComp("SL");
        long u = accounts.newUser(comp, "admin1", PW);
        accounts.grant(comp, u, "PRD", "prd-lot", "SEARCH", "SAVE");
        admin = accounts.as(comp, u, "admin1");
        accounts.exec(comp, """
                insert into mst_item (comp_cd, item_cd, item_nm, item_type, proc_type, unit_cd, created_by, updated_by)
                values (?, 'RM', 'RM 품목', 'RM', 'BUY', 'KG', 't', 't')""", comp);
    }

    private long receive(String qty) {
        return runner.call(comp, () -> lots.receiveAs("RM", new BigDecimal(qty), null, null, "t"));
    }

    private ResultActions postJson(String url, String body) throws Exception {
        return mvc.perform(post(url).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body).with(admin));
    }

    private List<Long> gens(String sql, Object... args) {
        return runner.call(comp, () -> jdbc.queryForList(sql + " order by gen_id", Long.class, args));
    }

    @Test
    void 계보_행마다_onEdge_역분개는_onReverse_LOT_만들기만은_부르지_않는다() throws Exception {
        long a = receive("10");
        Mockito.verifyNoInteractions(link);
        // 분할: 자식 2개 = 계보 2행 → onEdge 2번(그 행 id)
        postJson(L + "/" + a + "/split", "{\"qtys\":[\"2\",\"3\"]}").andExpect(status().isOk());
        List<Long> split = gens("select gen_id from lot_genealogy where parent_lot_id = ? and rel_type = 'SPLIT'", a);
        assertThat(split).hasSize(2);
        InOrder order = Mockito.inOrder(link);
        order.verify(link).onEdge(split.get(0));
        order.verify(link).onEdge(split.get(1));
        // 투입 + 역분개
        long b = receive("5");
        long child = receive("1");
        long g = runner.call(comp, () -> lots.consume(b, child, new BigDecimal("2"), null, null, null, null, "SCAN", Instant.now(), "t"));
        Mockito.verify(link).onEdge(g);
        long rev = runner.call(comp, () -> lots.reverse(g, "다시 스캔", "t"));
        Mockito.verify(link).onReverse(rev);
        Mockito.verifyNoMoreInteractions(link);
    }

    @Test
    void WMS_가_재고_부족을_던지면_계보도_자식_LOT_도_남지_않고_409_그대로() throws Exception {
        long a = receive("10");
        Mockito.doThrow(new BusinessException(HbErrorCode.INSUFFICIENT_STOCK)).when(link).onEdge(ArgumentMatchers.anyLong());
        postJson(L + "/" + a + "/split", "{\"qtys\":[\"2\"]}")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("INSUFFICIENT_STOCK"));
        assertThat(accounts.query(comp, "select count(*) from lot_genealogy where parent_lot_id = ?", Integer.class, a)).isZero();
        assertThat(accounts.query(comp, "select count(*) from lot_master where origin = 'SPLIT'", Integer.class)).isZero();
        assertThat(accounts.query(comp, "select hb_lot_balance(?)", BigDecimal.class, a)).isEqualByComparingTo("10");
        // 역분개도 같다 — WMS 가 거부하면 역분개 행이 남지 않는다
        Mockito.reset(link);
        long child = receive("1");
        long g = runner.call(comp, () -> lots.consume(a, child, new BigDecimal("2"), null, null, null, null, "SCAN", Instant.now(), "t"));
        Mockito.doThrow(new BusinessException(HbErrorCode.PERIOD_CLOSED)).when(link).onReverse(ArgumentMatchers.anyLong());
        Throwable t = catchThrowable(() -> runner.call(comp, () -> lots.reverse(g, "마감 달", "t")));
        assertThat(t).isInstanceOf(BusinessException.class);
        assertThat(((BusinessException) t).fieldErrors()).isEmpty();
        assertThat(accounts.query(comp, "select count(*) from lot_genealogy where reversal_of_id = ?", Integer.class, g)).isZero();
    }

    @Test
    void WMS_없는_설치본은_NONE() {
        LotStockLink none = LotStockLink.of(new DefaultListableBeanFactory().getBeanProvider(LotStockLink.class));
        assertThat(none).isSameAs(LotStockLink.NONE);
        none.onEdge(1L);
        none.onReverse(1L);
    }
}
