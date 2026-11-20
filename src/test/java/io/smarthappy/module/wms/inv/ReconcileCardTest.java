package io.smarthappy.module.wms.inv;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import io.smarthappy.module.home.HomeService;
import io.smarthappy.support.PostgresTestBase;
import io.smarthappy.support.TestAccounts;

/**
 * 홈 카드 "재고 대사 불일치"(docs/44 S1) — 마지막 대사 행의 불일치 합·보조 숫자 셋·오래된 대사 경고. 권한(wms-stock 조회)·A사로 B사
 */
@AutoConfigureMockMvc
class ReconcileCardTest extends PostgresTestBase {

    private static final String PW = "Quiver-lantern-41";
    private static final String CARDS = "/api/home/cards";
    private static final String C = "$.data.cards[?(@.code == 'wms-reconcile')]";

    @Autowired
    MockMvc mvc;

    @Autowired
    TestAccounts accounts;

    String comp;
    RequestPostProcessor admin;

    @BeforeEach
    void setUp() {
        comp = accounts.newComp("RC");
        long u = accounts.newUser(comp, "admin1", PW);
        accounts.grant(comp, u, "WMS", HomeService.PROG, "SEARCH");
        accounts.grant(comp, u, "WMS", "wms-stock", "SEARCH");
        admin = accounts.as(comp, u, "admin1");
    }

    private void run(String c, String ago, int mismatch, Integer poBad, Integer qcJudged, Integer voidLive) {
        accounts.exec(c, "insert into inv_reconcile_run (comp_cd, run_at, key_count, mismatch_count, rcv_po_mismatch, qc_judged, qms_void_live, run_by) "
                + "values (?, now() - ?::interval, 10, ?, ?, ?, ?, 'SYSTEM')", c, ago, mismatch, poBad, qcJudged, voidLive);
    }

    @Test
    void 대사가_없으면_0과_안내_오래되면_경고_불일치는_합과_보조_숫자() throws Exception {
        mvc.perform(get(CARDS).with(admin)).andExpect(status().isOk())
                .andExpect(jsonPath(C + ".count").value(0))
                .andExpect(jsonPath(C + ".items[0].title").value("아직 대사하지 않았습니다"));

        run(comp, "3 days", 0, 0, 0, 0);                                            // 3일 전 — 불일치 없지만 멈춘 듯
        mvc.perform(get(CARDS).with(admin))
                .andExpect(jsonPath(C + ".count").value(0))
                .andExpect(jsonPath(C + ".tone").value("WARN"))
                .andExpect(jsonPath(C + ".items[0].detail").value(Matchers.contains(Matchers.containsString("2일 넘게"))));

        run(comp, "1 hour", 2, 1, 1, null);                                          // 마지막 대사 — 원장 2 · 발주 1 · 품질 1
        mvc.perform(get(CARDS).with(admin))
                .andExpect(jsonPath(C + ".count").value(4))
                .andExpect(jsonPath(C + ".tone").value("ALERT"))
                .andExpect(jsonPath(C + ".progCd").value("wms-stock"))
                .andExpect(jsonPath(C + ".facts[*].value").value(Matchers.contains(2, 1, 1)))
                .andExpect(jsonPath(C + ".items[0].tone").value("NEUTRAL"));
    }

    @Test
    void 현재고_조회_권한이_없으면_카드가_없고_A사로_B사_대사는_섞이지_않는다() throws Exception {
        long other = accounts.newUser(comp, "user2", PW);
        accounts.grant(comp, other, "TRXV", HomeService.PROG, "SEARCH");
        accounts.grant(comp, other, "TRXV", "wms-trx", "SEARCH");
        mvc.perform(get(CARDS).with(accounts.as(comp, other, "user2")))
                .andExpect(jsonPath("$.data.cards[*].code").value(Matchers.not(Matchers.hasItem("wms-reconcile"))));

        String b = accounts.newComp("RB");
        run(b, "1 hour", 5, 0, 0, 0);
        mvc.perform(get(CARDS).with(admin))
                .andExpect(jsonPath(C + ".count").value(0))
                .andExpect(jsonPath(C + ".items[0].title").value("아직 대사하지 않았습니다"));
    }
}
