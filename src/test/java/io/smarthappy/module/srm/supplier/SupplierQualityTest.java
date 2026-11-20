package io.smarthappy.module.srm.supplier;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import io.smarthappy.module.qms.link.SupplierQualityQuery;
import io.smarthappy.module.qms.link.SupplierQualityQuery.ItemQuality;
import io.smarthappy.module.qms.link.SupplierQualityQuery.NcrDetail;
import io.smarthappy.module.qms.link.SupplierQualityQuery.NcrPage;
import io.smarthappy.support.PostgresTestBase;
import io.smarthappy.support.TestAccounts;

/**
 * 협력사 상세 품질 요약 카드 (srm-supplier, 최근 90일): 입고 PPM(평가 RECV_PPM 과 같은 계산·단위 규칙) · 처리 중 NCR(QMS 계약 대역) ·
 * 열린 클레임 · 출고 대기 반품. 이상 사용: 미등록·A사로 B사 404, 권한 없음 403, 단위 둘 이상·검사 없음은 PPM 없음.
 */
@AutoConfigureMockMvc
class SupplierQualityTest extends PostgresTestBase {

    private static final String PW = "Maple-anchor-63";
    private static final String API = "/api/srm/suppliers";

    @Autowired
    MockMvc mvc;

    @Autowired
    TestAccounts accounts;

    @MockitoBean
    SupplierQualityQuery quality;

    String comp;
    RequestPostProcessor me;
    long cust;
    long other;
    LocalDate today;

    @BeforeEach
    void setUp() {
        comp = accounts.newComp("SQ");
        long u = accounts.newUser(comp, "buyer1", PW);
        accounts.grant(comp, u, "PUR", "srm-supplier", "SEARCH");
        me = accounts.as(comp, u, "buyer1");
        cust = custOf(comp, "V100", true);
        other = custOf(comp, "V900", false);           // 구매 거래처지만 협력사 미등록
        today = LocalDate.now(ZoneId.of("Asia/Seoul"));
        when(quality.receiptQuality(anyLong(), any(), any())).thenReturn(List.of());
        when(quality.ncrsOfSupplier(anyLong(), any(), any())).thenReturn(NcrPage.EMPTY);
    }

    private long custOf(String c, String cd, boolean registered) {
        long id = accounts.query(c, """
                insert into mst_cust (comp_cd, cust_cd, cust_nm, cust_type, created_by, updated_by)
                values (?, ?, ?, 'PURCHASE', 't', 't') returning cust_id""", Long.class, c, cd, cd + " 상사");
        if (registered) {
            accounts.exec(c, "insert into srm_supplier (cust_id, comp_cd, currency_cd, created_by, updated_by) values (?, ?, 'KRW', 't', 't')", id, c);
        }
        return id;
    }

    private static ItemQuality item(long id, String unit, String insp, String rej, int cnt, int ng) {
        return new ItemQuality(id, "I" + id, unit, new BigDecimal(insp), new BigDecimal(rej), cnt, ng, 0, BigDecimal.ZERO);
    }

    private static NcrDetail ncr(long id, String status) {
        return new NcrDetail(id, "NCR-" + id, status, "INSP", 1, "L1", null, 0L, 1, "I1", BigDecimal.ONE, BigDecimal.ZERO, "EA", null, null, null,
                null, OffsetDateTime.now(), null, null);
    }

    private void claim(String no, boolean cancelled) {
        accounts.exec(comp, """
                insert into srm_claim (comp_cd, claim_no, cust_id, action_cd, title, request_text, created_by, updated_by)
                values (?, ?, ?, 'CAPA', '녹', '원인·대책 회신', 't', 't')""", comp, no, cust);
        if (cancelled) {
            accounts.exec(comp, "update srm_claim set status = 'CANCELLED', cancel_reason = 'x' where claim_no = ?", no);
        }
    }

    private void ret(String no, long ncrId, boolean confirm) {
        accounts.exec(comp, """
                insert into srm_return (comp_cd, return_no, cust_id, plant_cd, ncr_id, ncr_no, ncr_line_no, lot_id, lot_no, item_id, unit_cd, qty,
                                        created_by, updated_by)
                select ?, ?, ?, 'P1', ?, 'NCR', 1, 1, 'L1', item_id, 'EA', 1, 't', 't' from mst_item where item_cd = 'BOLT'""", comp, no, cust, ncrId);
        if (confirm) {
            accounts.exec(comp, "update srm_return set status = 'CONFIRMED', confirmed_at = now(), confirmed_by = 't' where return_no = ?", no);
        }
    }

    @Test
    void PPM_검사_처리중_NCR_열린_클레임_출고대기_반품을_모아_보인다() throws Exception {
        accounts.addPlant(comp, "P1", "1공장");
        accounts.exec(comp, """
                insert into mst_item (comp_cd, item_cd, item_nm, item_type, proc_type, unit_cd, lot_ctrl_yn, created_by, updated_by)
                values (?, 'BOLT', '볼트', 'RM', 'BUY', 'EA', 'N', 't', 't')""", comp);
        when(quality.receiptQuality(eq(cust), any(), any())).thenReturn(List.of(item(1, "EA", "1000", "3", 4, 1), item(2, "EA", "1000", "1", 2, 0)));
        when(quality.ncrsOfSupplier(eq(cust), any(), any())).thenReturn(new NcrPage(List.of(ncr(1, "OPEN"), ncr(2, "PENDING"), ncr(3, "CLOSED")), true));
        claim("CL-1", false);
        claim("CL-2", true);
        ret("RT-1", 11, true);
        ret("RT-2", 12, false);
        mvc.perform(get(API + "/" + cust + "/quality").with(me)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.from").value(today.minusDays(89).toString()))
                .andExpect(jsonPath("$.data.to").value(today.toString()))
                .andExpect(jsonPath("$.data.ppm").value("2000"))
                .andExpect(jsonPath("$.data.ppmNote").value(Matchers.nullValue()))
                .andExpect(jsonPath("$.data.inspectedQty").value("2000"))
                .andExpect(jsonPath("$.data.unitCd").value("EA"))
                .andExpect(jsonPath("$.data.inspectionCnt").value(6))
                .andExpect(jsonPath("$.data.ngCnt").value(1))
                .andExpect(jsonPath("$.data.openNcrCnt").value(2))
                .andExpect(jsonPath("$.data.ncrTruncated").value(true))
                .andExpect(jsonPath("$.data.openClaimCnt").value(1))
                .andExpect(jsonPath("$.data.returnsToShipCnt").value(1));
        verify(quality).receiptQuality(cust, today.minusDays(89), today);
        verify(quality).ncrsOfSupplier(cust, today.minusDays(89), today);
    }

    @Test
    void 단위가_둘_이상이거나_검사가_없으면_PPM_없음() throws Exception {
        when(quality.receiptQuality(eq(cust), any(), any())).thenReturn(List.of(item(1, "EA", "10", "1", 1, 1), item(2, "KG", "5", "0", 1, 0)));
        mvc.perform(get(API + "/" + cust + "/quality").with(me)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.ppm").value(Matchers.nullValue())).andExpect(jsonPath("$.data.ppmNote").value("UNITS"))
                .andExpect(jsonPath("$.data.inspectedQty").value(Matchers.nullValue()));
        when(quality.receiptQuality(eq(cust), any(), any())).thenReturn(List.of());
        mvc.perform(get(API + "/" + cust + "/quality").with(me)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.ppmNote").value("NONE")).andExpect(jsonPath("$.data.openClaimCnt").value(0));
    }

    @Test
    void 미등록_A사로_B사_404_권한_없으면_403() throws Exception {
        mvc.perform(get(API + "/" + other + "/quality").with(me)).andExpect(status().isNotFound());
        mvc.perform(get(API + "/99999999/quality").with(me)).andExpect(status().isNotFound());
        String b = accounts.newComp("SR");
        long bu = accounts.newUser(b, "buyer1", PW);
        accounts.grant(b, bu, "PUR", "srm-supplier", "SEARCH");
        mvc.perform(get(API + "/" + cust + "/quality").with(accounts.as(b, bu, "buyer1"))).andExpect(status().isNotFound());
        long plain = accounts.newUser(comp, "plain1", PW);
        mvc.perform(get(API + "/" + cust + "/quality").with(accounts.as(comp, plain, "plain1"))).andExpect(status().isForbidden());
    }
}
