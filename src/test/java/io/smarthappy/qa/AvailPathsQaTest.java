package io.smarthappy.qa;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.jayway.jsonpath.JsonPath;

import io.smarthappy.module.wms.link.StockQuery;
import io.smarthappy.platform.tenant.TenantRunner;
import io.smarthappy.support.PostgresTestBase;
import io.smarthappy.support.TestAccounts;

/**
 * QA — 가용 정의 하나(#649, docs/53 2장) 뒤 다섯 경로가 같은 재고에서 같은 숫자인지. 칸 규칙(STAGING·QC·SCRAP)은 다섯 경로가 같은 SQL 조각이라
 * [코드]로 같고 모듈 시험이 덮는다(ShipTest QC 칸·LotStockPostingTest·AvailableCellQaTest). 조각 밖으로 남긴 **LOT 보류** 를 본다 —
 * APS 가용(StockQuery.available)·출하 예약 카드·MRO 는 보류 LOT 을 빼고, 차감 미리보기(그리고 그것을 쓰는 출하 피킹 제안)는 재고 상태만 본다.
 * 분석 docs/qa/2026-10-29_pms-wms-qms-mes.md
 */
@AutoConfigureMockMvc
class AvailPathsQaTest extends PostgresTestBase {

    static final String PW = "Qa-avail-paths-57";

    @Autowired
    MockMvc mvc;

    @Autowired
    TestAccounts accounts;

    @Autowired
    TenantRunner runner;

    @Autowired
    StockQuery stockQuery;

    String comp;
    RequestPostProcessor admin;
    long a1;
    long item;
    int seq;

    @BeforeEach
    void setUp() {
        comp = accounts.newComp("QV");
        long u = accounts.newUser(comp, "qv1", PW);
        accounts.grant(comp, u, "WMS", "wms-rcv", "SEARCH", "SAVE");
        accounts.grant(comp, u, "WMS", "wms-stock", "SEARCH");
        admin = accounts.as(comp, u, "qv1");
        accounts.addPlant(comp, "P1", "1공장");
        accounts.exec(comp, "insert into mst_cust (comp_cd, cust_cd, cust_nm, cust_type, created_by, updated_by) values (?, 'SUP1', '공급사', 'PURCHASE', 't', 't')",
                comp);
        item = accounts.query(comp, """
                insert into mst_item (comp_cd, item_cd, item_nm, item_type, proc_type, unit_cd, lot_ctrl_yn, created_by, updated_by)
                values (?, 'QV-RM', 'QV 자재', 'RM', 'BUY', 'EA', 'Y', 't', 't') returning item_id""", Long.class, comp);
        long wh = accounts.query(comp, """
                insert into mst_equip_hier (comp_cd, plant_cd, node_type, node_cd, node_nm, created_by, updated_by)
                values (?, 'P1', 'WH', 'QV-WH', 'QV-WH', 't', 't') returning node_id""", Long.class, comp);
        a1 = accounts.query(comp, """
                insert into mst_equip_hier (comp_cd, plant_cd, parent_id, node_type, node_cd, node_nm, created_by, updated_by)
                values (?, 'P1', ?, 'LOC', 'A-01', 'A-01', 't', 't') returning node_id""", Long.class, comp, wh);
    }

    String receive(String qty) throws Exception {
        MvcResult m = mvc.perform(post("/api/wms/rcv").with(csrf()).with(admin).contentType(MediaType.APPLICATION_JSON)
                .content("{\"plantCd\":\"P1\",\"nodeId\":" + a1 + ",\"itemId\":" + item + ",\"qty\":" + qty + ",\"stockStatus\":\"AVAILABLE\","
                        + "\"supplierCd\":\"SUP1\",\"clientReqId\":\"qv-" + (++seq) + "-" + System.nanoTime() + "\"}"))
                .andExpect(status().isOk()).andReturn();
        return JsonPath.read(m.getResponse().getContentAsString(), "$.data.lotNo");
    }

    /** 시험 준비: LOT 보류(MES 보류 근거 원장 대신 표시만 — 원장 가드는 replica 로 우회) */
    void hold(String lotNo) throws Exception {
        try (Connection c = DriverManager.getConnection(jdbcUrl(), OWNER_USER, testPassword()); Statement st = c.createStatement()) {
            st.execute("set session_replication_role = replica");
            st.executeUpdate("update lot_master set hold_yn = 'Y', hold_reason = 'QA 보류' where lot_no = '" + lotNo + "' and comp_cd = '" + comp + "'");
        }
    }

    /**
     * 관찰(A-1, 정책 질의): 보류 LOT 8 + 정상 LOT 5(같은 칸, 둘 다 AVAILABLE). APS 가용 = 5, 차감 미리보기(가용만)는 13 을 다 배정하고 모자람 0 —
     * 보류 LOT 을 꺼낼 수 있는 것으로 셈. 같은 미리보기를 쓰는 출하 피킹 제안도 보류 LOT 을 낸다([코드] ShipService.suggest)
     */
    @Test
    void 보류_LOT_은_APS_가용과_차감_미리보기_모두에서_빠진다() throws Exception {   // A-1 사용자 결정(2026-10-30) — 관찰을 기대로 뒤집음
        String held = receive("8");
        receive("5");
        hold(held);
        BigDecimal aps = runner.call(comp, () -> stockQuery.available("P1", List.of(item))).get(item);
        MvcResult m = mvc.perform(post("/api/wms/stock/alloc-preview").with(csrf()).with(admin).contentType(MediaType.APPLICATION_JSON)
                .content("{\"plantCd\":\"P1\",\"itemId\":" + item + ",\"qty\":\"13\",\"availableOnly\":true}")).andExpect(status().isOk()).andReturn();
        String body = m.getResponse().getContentAsString();
        String allocated = JsonPath.read(body, "$.data.allocated");
        String shortage = JsonPath.read(body, "$.data.shortage");
        List<String> lots = JsonPath.read(body, "$.data.picks[*].lotNo");
        System.out.println("[QV] APS 가용=" + aps + " 미리보기 배정=" + allocated + " 모자람=" + shortage + " LOT=" + lots + " 보류=" + held);
        assertThat(aps).isEqualByComparingTo("5");
        assertThat(new BigDecimal(allocated)).as("미리보기도 가용 정의 하나 — 보류 LOT 은 빼고 5").isEqualByComparingTo("5");
        assertThat(new BigDecimal(shortage)).isEqualByComparingTo("8");
        assertThat(lots).as("보류 LOT 은 배정 목록에 없다").doesNotContain(held);
        List<String> skipped = JsonPath.read(body, "$.data.skipped[?(@.lotNo == '" + held + "')].reason");
        assertThat(skipped).containsExactly("HOLD");
    }
}
