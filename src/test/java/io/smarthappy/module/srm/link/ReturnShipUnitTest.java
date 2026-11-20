package io.smarthappy.module.srm.link;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.jayway.jsonpath.JsonPath;

import io.smarthappy.platform.common.db.PgErrors;
import io.smarthappy.platform.common.web.BusinessException;
import io.smarthappy.platform.common.web.HbErrorCode;
import io.smarthappy.platform.tenant.TenantRunner;
import io.smarthappy.support.PostgresTestBase;
import io.smarthappy.support.TestAccounts;

/**
 * 교체 요구 반품 — 반품 단위(LOT 기본 단위 EA)와 발주 단위(BOX)가 다를 때 (SRM 2부 R4c-단위, docs/31 4.12.7).
 * 출고는 발주 단위로 환산한 양만큼 입고를 되돌리고, 역분개는 되돌린 양 그대로 다시 입고(그 사이 환산표가 바뀌어도).
 * 나누어떨어지지 않음·환산 없음·LOT 순입고 넘음은 게이트가 막고 모두 그대로. 구매 입고·반품 출고는 WMS 화면 API 로(그 모듈 코드는 그대로).
 */
@AutoConfigureMockMvc
class ReturnShipUnitTest extends PostgresTestBase {

    private static final String PW = "Maple-anchor-63";
    private static final String PO = "/api/srm/pos";
    private static final String RTN = "/api/wms/rtn-issue";

    @Autowired
    MockMvc mvc;

    @Autowired
    TestAccounts accounts;

    @Autowired
    TenantRunner runner;

    @Autowired
    ObjectProvider<ReturnShipGate> gate;

    String comp;
    RequestPostProcessor admin;
    long a1;
    long resin;
    long vCust;
    long line;
    int seq;
    int rtnSeq;

    @BeforeEach
    void setUp() throws Exception {
        comp = accounts.newComp("RW");
        long u = accounts.newUser(comp, "admin1", PW);
        accounts.grant(comp, u, "PUR", "srm-po", "SEARCH", "SAVE", "APPROVE");
        accounts.grant(comp, u, "WMS", "wms-rcv", "SEARCH", "SAVE");
        accounts.grant(comp, u, "WMS", "wms-trx", "SEARCH", "SAVE");
        accounts.grant(comp, u, "RTN", "wms-rtn-issue", "SEARCH", "SAVE");
        accounts.grant(comp, u, "SRTN", "srm-return", "SEARCH");
        admin = accounts.as(comp, u, "admin1");
        accounts.addPlant(comp, "P1", "1공장");
        accounts.exec(comp, "insert into sys_policy (comp_cd, policy_key, policy_value, created_by, updated_by) values (?, 'SRM_PO_APPROVAL', 'N', 't', 't')", comp);
        resin = accounts.query(comp, """
                insert into mst_item (comp_cd, item_cd, item_nm, item_type, proc_type, unit_cd, lot_ctrl_yn, created_by, updated_by)
                values (?, 'RESIN-L', '수지', 'RM', 'BUY', 'BOX', 'Y', 't', 't') returning item_id""", Long.class, comp);
        vCust = accounts.query(comp, """
                insert into mst_cust (comp_cd, cust_cd, cust_nm, cust_type, created_by, updated_by)
                values (?, 'V100', 'V100 상사', 'PURCHASE', 't', 't') returning cust_id""", Long.class, comp);
        accounts.exec(comp, "insert into srm_supplier (cust_id, comp_cd, currency_cd, created_by, updated_by) values (?, ?, 'KRW', 't', 't')", vCust, comp);
        long wh = node(null, "WH", "RM-WH");
        a1 = node(wh, "LOC", "A-01");
        // 수지 10 BOX 발주·발행 → 기본 단위 EA(1 BOX = 12 EA)
        String due = accounts.query(comp, "select (" + TestAccounts.COMP_TODAY + " + 20)::text", String.class);
        long po = ((Number) JsonPath.read(postJson(PO, "{\"custCd\":\"V100\",\"plantCd\":\"P1\",\"lines\":[{\"itemCd\":\"RESIN-L\",\"qty\":\"10\","
                + "\"unitPrice\":\"1\",\"dueDate\":\"" + due + "\"}]}").andExpect(status().isOk()).andReturn().getResponse().getContentAsString(),
                "$.data")).longValue();
        act(po, "confirm");
        act(po, "issue");
        accounts.exec(comp, "update mst_item set unit_cd = 'EA' where item_id = ?", resin);
        accounts.exec(comp, "insert into mst_unit_conv (comp_cd, item_id, from_unit, to_unit, factor, created_by, updated_by) values (?, ?, 'BOX', 'EA', 12, 't', 't')",
                comp, resin);
        line = accounts.query(comp, "select po_line_id from srm_po_line where po_id = ?", Long.class, po);
    }

    private long node(Long parent, String type, String cd) {
        return accounts.query(comp, """
                insert into mst_equip_hier (comp_cd, plant_cd, parent_id, node_type, node_cd, node_nm, created_by, updated_by)
                values (?, 'P1', ?, ?, ?, ?, 't', 't') returning node_id""", Long.class, comp, parent, type, cd, cd);
    }

    private ResultActions postJson(String url, String body) throws Exception {
        return mvc.perform(post(url).with(csrf()).with(admin).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private void act(long id, String action) throws Exception {
        int rv = JsonPath.read(mvc.perform(get(PO + "/" + id).with(admin)).andReturn().getResponse().getContentAsString(), "$.data.po.rowVer");
        postJson(PO + "/" + id + "/" + action, "{\"rowVer\":" + rv + "}").andExpect(status().isOk());
    }

    private String req() {
        return "rtnunit-" + (++seq) + "-" + System.nanoTime();
    }

    /** 구매 입고(WMS, 발주 단위 BOX) → LOT id */
    private long receive(String boxes) throws Exception {
        MvcResult m = postJson("/api/wms/rcv", "{\"plantCd\":\"P1\",\"nodeId\":" + a1 + ",\"poLineId\":" + line + ",\"qty\":" + boxes
                + ",\"stockStatus\":\"HOLD\",\"clientReqId\":\"" + req() + "\"}").andExpect(status().isOk()).andReturn();
        long trx = ((Number) JsonPath.read(m.getResponse().getContentAsString(), "$.data.trxId")).longValue();
        return accounts.query(comp, "select src_id from inv_trx where trx_id = ?", Long.class, trx);
    }

    /** 확정된 교체 요구 반품 — 단위 EA(LOT 기본 단위), 발주 행 BOX. 직접 넣는다(트리거가 단위 차이를 받는지도 본다) */
    private long confirmed(long lot, String eaQty) {
        String lotNo = accounts.query(comp, "select lot_no from lot_master where lot_id = ?", String.class, lot);
        long id = accounts.query(comp, """
                insert into srm_return (comp_cd, return_no, cust_id, plant_cd, ncr_id, ncr_no, ncr_line_no, lot_id, lot_no, po_line_id, item_id,
                                        unit_cd, qty, replace_yn, created_by, updated_by)
                values (?, ?, ?, 'P1', ?, ?, 1, ?, ?, ?, ?, 'EA', ?::numeric, 'Y', 't', 't') returning return_id""", Long.class,
                comp, "RT-U" + (++rtnSeq), vCust, 800L + rtnSeq, "NCR-U" + rtnSeq, lot, lotNo, line, resin, eaQty);
        accounts.exec(comp, "update srm_return set status = 'CONFIRMED', confirmed_at = now(), confirmed_by = 't' where return_id = ?", id);
        return id;
    }

    private String no(long id) {
        return accounts.query(comp, "select return_no from srm_return where return_id = ?", String.class, id);
    }

    private String recv() {
        return accounts.query(comp, "select trim_scale(recv_qty)::text from srm_po_line where po_line_id = ?", String.class, line);
    }

    private String retStatus(long id) {
        return accounts.query(comp, "select status from srm_return where return_id = ?", String.class, id);
    }

    private HbErrorCode fail(Runnable r) {
        Throwable t = catchThrowable(r::run);
        assertThat(t).isInstanceOf(BusinessException.class);
        return ((BusinessException) t).code();
    }

    @Test
    void EA_반품은_BOX_발주_입고를_환산한_양만큼_되돌리고_역분개는_되돌린_양_그대로_다시_입고() throws Exception {
        long lot = receive("2");                                                  // 2 BOX = 24 EA
        long id = confirmed(lot, "12");
        String r = req();
        MvcResult ok = postJson(RTN, "{\"returnNo\":\"" + no(id) + "\",\"rows\":[{\"nodeId\":" + a1 + ",\"stockStatus\":\"HOLD\",\"qty\":12}],"
                + "\"clientReqId\":\"" + r + "\"}").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.shipped.openQty").value(9)).andReturn();
        assertThat(recv()).isEqualTo("1");                                        // 12 EA = 1 BOX 되돌림
        // 반품 상세(SRM 화면): 출고 근거 + 교체품 대기(그 발주 행 열린 수량, 발주 단위)
        mvc.perform(get("/api/srm/returns/" + id).with(admin)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("SHIPPED")).andExpect(jsonPath("$.data.shipRef").value("RTN_ISS:" + r))
                .andExpect(jsonPath("$.data.poOpenQty").value("9")).andExpect(jsonPath("$.data.poUnitCd").value("BOX"));
        assertThat(accounts.query(comp, "select trim_scale(qty) || ':' || lot_id from srm_po_receipt where trx_ref = ?", String.class, "RTN_ISS:" + r))
                .isEqualTo("-1:" + lot);
        // 그 사이 환산이 바뀌어도(1 BOX = 6 EA) 역분개는 되돌린 1 BOX 를 그대로 다시 입고
        accounts.exec(comp, "update mst_unit_conv set factor = 6 where item_id = ?", resin);
        long t = ((Number) JsonPath.read(ok.getResponse().getContentAsString(), "$.data.trxIds[0]")).longValue();
        postJson("/api/wms/trx/" + t + "/reverse", "{\"reason\":\"잘못 출고\"}").andExpect(status().isOk());
        assertThat(retStatus(id)).isEqualTo("CONFIRMED");
        assertThat(recv()).isEqualTo("2");
        assertThat(accounts.query(comp, "select trim_scale(qty) || ':' || lot_id from srm_po_receipt where trx_ref = ?", String.class, "RTN_ISSR:" + t))
                .isEqualTo("1:" + lot);
    }

    @Test
    void 나누어떨어지지_않음_환산_없음_LOT_순입고_넘음은_게이트가_막고_모두_그대로() throws Exception {
        long lot = receive("1");                                                  // 1 BOX = 12 EA
        ReturnShipGate g = ReturnShipGate.of(gate);
        // 6 EA = 0.5 BOX(BOX 소수 0자리) → 409
        long half = confirmed(lot, "6");
        assertThat(fail(() -> runner.run(comp, () -> g.ship(half, lot, new BigDecimal("6"), "U:1", "t", null)))).isEqualTo(HbErrorCode.SRM_RTN_PO_SCALE);
        // 1 BOX = 5 EA 면 12 EA = 2.4 BOX — 역시 409(서비스 확정 뒤 환산이 바뀐 경우)
        long full = confirmed(lot, "12");
        accounts.exec(comp, "update mst_unit_conv set factor = 5 where item_id = ?", resin);
        assertThat(fail(() -> runner.run(comp, () -> g.ship(full, lot, new BigDecimal("12"), "U:2", "t", null)))).isEqualTo(HbErrorCode.SRM_RTN_PO_SCALE);
        // 환산 끔 → 입고 게이트와 같은 오류(발주 단위 BOX → EA 없음)
        accounts.exec(comp, "update mst_unit_conv set factor = 12, use_yn = 'N' where item_id = ?", resin);
        Throwable t = catchThrowable(() -> runner.run(comp, () -> g.ship(full, lot, new BigDecimal("12"), "U:3", "t", null)));
        assertThat(((BusinessException) t).code()).isEqualTo(HbErrorCode.SRM_RECV_NO_CONV);
        assertThat(((BusinessException) t).args()).containsExactly("BOX", "EA");
        accounts.exec(comp, "update mst_unit_conv set use_yn = 'Y' where item_id = ?", resin);
        // 24 EA = 2 BOX > 그 LOT 순입고 1 BOX → 409
        long over = confirmed(lot, "24");
        assertThat(fail(() -> runner.run(comp, () -> g.ship(over, lot, new BigDecimal("24"), "U:4", "t", null)))).isEqualTo(HbErrorCode.SRM_RECV_REVERSE);
        assertThat(recv()).isEqualTo("1");
        assertThat(accounts.query(comp, "select count(*) from srm_po_receipt where qty < 0", Integer.class)).isZero();
        assertThat(retStatus(half) + retStatus(full) + retStatus(over)).isEqualTo("CONFIRMEDCONFIRMEDCONFIRMED");
        // 같은 반품을 맞는 양으로(WMS 반품 출고 — 게이트만 부르면 원장이 없어 커밋 거부, V161) — 12 EA = 1 BOX 출고됨
        postJson(RTN, "{\"returnNo\":\"" + no(full) + "\",\"rows\":[{\"nodeId\":" + a1 + ",\"stockStatus\":\"HOLD\",\"qty\":12}],"
                + "\"clientReqId\":\"" + req() + "\"}").andExpect(status().isOk());
        assertThat(recv()).isEqualTo("0");
        assertThat(retStatus(full)).isEqualTo("SHIPPED");
    }

    @Test
    void 직접_SQL_다른_단위는_받고_다른_품목은_DB가_막는다() {
        long other = accounts.query(comp, """
                insert into mst_item (comp_cd, item_cd, item_nm, item_type, proc_type, unit_cd, lot_ctrl_yn, created_by, updated_by)
                values (?, 'OTHER', '다른', 'RM', 'BUY', 'BOX', 'N', 't', 't') returning item_id""", Long.class, comp);
        Throwable t = catchThrowable(() -> accounts.exec(comp, """
                insert into srm_return (comp_cd, return_no, cust_id, plant_cd, ncr_id, ncr_no, ncr_line_no, lot_id, lot_no, po_line_id, item_id,
                                        unit_cd, qty, replace_yn, created_by, updated_by)
                values (?, 'RT-X', ?, 'P1', 1, 'NCR-X', 1, 1, 'L1', ?, ?, 'BOX', 1, 'Y', 't', 't')""", comp, vCust, line, other));
        assertThat(PgErrors.constraint(t).orElse(String.valueOf(t))).isEqualTo("ck_srm_return_po_item");
    }
}
