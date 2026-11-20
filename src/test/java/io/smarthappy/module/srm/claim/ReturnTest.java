package io.smarthappy.module.srm.claim;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.jayway.jsonpath.JsonPath;

import io.smarthappy.module.qms.link.SupplierQualityQuery;
import io.smarthappy.module.qms.link.SupplierQualityQuery.NcrDetail;
import io.smarthappy.module.qms.link.SupplierQualityQuery.ReturnPage;
import io.smarthappy.module.qms.link.SupplierQualityQuery.SupplierReturn;
import io.smarthappy.support.PostgresTestBase;
import io.smarthappy.support.TestAccounts;

/**
 * SRM 2부 R4c 반품 지시 (docs/31 4.12.4) — QMS 계약을 바꿔 끼워 SRM 이 계약 값을 어떻게 쓰는지 본다(계약 값 자체는 QMS 시험).
 * 반품 대상(살아 있는 반품 번호·잘림 표시) → 만들기(수량 한도·처리 줄당 하나·근거 NCR 상태·협력사·교체 요구 발주 행) → 수정 → 확정(APPROVE,
 * 계약 다시 읽음) → 확정 뒤 수정 막힘 → 취소(사유) → 다시 만들기, grid, A사로 B사, 권한.
 */
@AutoConfigureMockMvc
class ReturnTest extends PostgresTestBase {

    private static final String PW = "Maple-anchor-63";
    private static final String R = "/api/srm/returns";

    @Autowired
    MockMvc mvc;

    @Autowired
    TestAccounts accounts;

    @MockitoBean
    SupplierQualityQuery quality;

    String comp;
    RequestPostProcessor me;
    LocalDate today;
    OffsetDateTime closedAt;
    long vCust;
    long bolt;
    long boltLine;
    long nutLine;

    @BeforeEach
    void setUp() throws Exception {
        comp = accounts.newComp("RT");
        long buyer = accounts.newUser(comp, "buyer1", PW);
        grantAll(comp, buyer);
        me = accounts.as(comp, buyer, "buyer1");
        today = LocalDate.now(ZoneId.of("Asia/Seoul"));
        closedAt = today.atTime(1, 0).atZone(ZoneId.of("Asia/Seoul")).toOffsetDateTime();
        long[] s = seed(comp);
        vCust = s[0];
        bolt = s[1];
        long[] lines = poLines(me);
        boltLine = lines[0];
        nutLine = lines[1];
        // NCR 101: 닫힘·이 협력사·반품 처리 줄 1(BOLT 6 EA) / 102: 처리 중 / 103: 다른 협력사 / 그 밖 없음
        when(quality.ncr(anyLong())).thenReturn(Optional.empty());
        when(quality.ncr(101L)).thenReturn(Optional.of(ncr(101, "CLOSED", vCust)));
        when(quality.ncr(102L)).thenReturn(Optional.of(ncr(102, "OPEN", vCust)));
        when(quality.ncr(103L)).thenReturn(Optional.of(ncr(103, "CLOSED", 999_999L)));
        when(quality.returnsToSupplier(anyLong(), any(), any())).thenReturn(ReturnPage.EMPTY);
        when(quality.returnsToSupplier(eq(vCust), any(), any())).thenReturn(new ReturnPage(List.of(line(101, 1, "6")), false));
    }

    private void grantAll(String c, long user) {
        accounts.grant(c, user, "PUR", "srm-return", "SEARCH", "SAVE", "APPROVE");
        accounts.grant(c, user, "PUR", "srm-po", "SEARCH", "SAVE", "APPROVE");
    }

    /** 공장 P1·품목 BOLT·NUT·협력사 V100 → {cust, bolt} */
    private long[] seed(String c) {
        accounts.addPlant(c, "P1", "1공장");
        accounts.exec(c, "insert into sys_policy (comp_cd, policy_key, policy_value, created_by, updated_by) values (?, 'SRM_PO_APPROVAL', 'N', 't', 't')", c);
        long b = accounts.query(c, """
                insert into mst_item (comp_cd, item_cd, item_nm, item_type, proc_type, unit_cd, lot_ctrl_yn, created_by, updated_by)
                values (?, 'BOLT', '볼트', 'RM', 'BUY', 'EA', 'N', 't', 't') returning item_id""", Long.class, c);
        accounts.exec(c, """
                insert into mst_item (comp_cd, item_cd, item_nm, item_type, proc_type, unit_cd, lot_ctrl_yn, created_by, updated_by)
                values (?, 'NUT', '너트', 'RM', 'BUY', 'EA', 'N', 't', 't')""", c);
        long id = accounts.query(c, """
                insert into mst_cust (comp_cd, cust_cd, cust_nm, cust_type, created_by, updated_by)
                values (?, 'V100', 'V100 상사', 'PURCHASE', 't', 't') returning cust_id""", Long.class, c);
        accounts.exec(c, "insert into srm_supplier (cust_id, comp_cd, currency_cd, created_by, updated_by) values (?, ?, 'KRW', 't', 't')", id, c);
        return new long[] {id, b};
    }

    /** 발주(BOLT·NUT 두 행) → {BOLT 행, NUT 행} */
    private long[] poLines(RequestPostProcessor who) throws Exception {
        String due = today.plusDays(20).toString();
        long po = id(send(who, post("/api/srm/pos"), "{\"custCd\":\"V100\",\"plantCd\":\"P1\",\"lines\":["
                + "{\"itemCd\":\"BOLT\",\"qty\":\"10\",\"unitPrice\":\"1\",\"dueDate\":\"" + due + "\"},"
                + "{\"itemCd\":\"NUT\",\"qty\":\"10\",\"unitPrice\":\"1\",\"dueDate\":\"" + due + "\"}]}"));
        String d = body(who, "/api/srm/pos/" + po);
        return new long[] {((Number) JsonPath.read(d, "$.data.lines[0].poLineId")).longValue(),
            ((Number) JsonPath.read(d, "$.data.lines[1].poLineId")).longValue()};
    }

    private NcrDetail ncr(long id, String status, long cust) {
        return new NcrDetail(id, "NCR-" + id, status, "INSP", 500, "L500", "SL1", cust, bolt, "BOLT", new BigDecimal("10"), new BigDecimal("6"),
                "EA", "D1", "녹", "녹 발생", "INS-1", "CLOSED".equals(status) ? closedAt : null, null);
    }

    private SupplierReturn line(long ncr, int lineNo, String qty) {
        return new SupplierReturn(ncr, "NCR-" + ncr, lineNo, 500, "L500", "SL1", bolt, "BOLT", new BigDecimal(qty), "EA", "D1", "녹", closedAt, null);
    }

    private ResultActions send(RequestPostProcessor who, MockHttpServletRequestBuilder b, String json) throws Exception {
        return mvc.perform(b.with(csrf()).contentType(MediaType.APPLICATION_JSON).content(json).with(who));
    }

    private static long id(ResultActions r) throws Exception {
        MvcResult m = r.andExpect(status().isOk()).andReturn();
        return ((Number) JsonPath.read(m.getResponse().getContentAsString(), "$.data")).longValue();
    }

    private String body(RequestPostProcessor who, String url) throws Exception {
        return mvc.perform(get(url).with(who)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    private int ver(long id) throws Exception {
        return JsonPath.read(body(me, R + "/" + id), "$.data.rowVer");
    }

    private static String form(long ncr, int lineNo, String qty, String replace, Long poLine) {
        return "{\"custCd\":\"V100\",\"plantCd\":\"P1\",\"ncrId\":" + ncr + ",\"lineNo\":" + lineNo + ",\"qty\":\"" + qty + "\",\"replaceYn\":\""
                + replace + "\",\"poLineId\":" + poLine + ",\"remark\":\"녹\"}";
    }

    private ResultActions act(RequestPostProcessor who, long id, String action, String reason) throws Exception {
        return send(who, post(R + "/" + id + "/" + action), "{\"rowVer\":" + ver(id) + (reason == null ? "" : ",\"reason\":\"" + reason + "\"") + "}");
    }

    private String candidates() throws Exception {
        return body(me, R + "/candidates?custCd=V100&from=" + today.minusDays(30) + "&to=" + today);
    }

    @Test
    void 반품대상에서_만들기_수량한도_줄당하나_수정_확정_확정뒤_막힘_취소_다시() throws Exception {
        String c = candidates();
        verify(quality).returnsToSupplier(vCust, today.minusDays(30), today);
        assertThat((Integer) JsonPath.read(c, "$.data.rows.length()")).isEqualTo(1);
        assertThat((String) JsonPath.read(c, "$.data.rows[0].qty")).isEqualTo("6");
        assertThat((Object) JsonPath.read(c, "$.data.rows[0].returnNo")).isNull();
        assertThat((Boolean) JsonPath.read(c, "$.data.truncated")).isFalse();
        // 수량 > 처리 줄 반품 수량 → 400, 6 이하 → 만들어짐(번호 RT…)
        send(me, post(R), form(101, 1, "6.5", "N", null)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("qty")).andExpect(jsonPath("$.errors[0].code").value("SRM_RTN_OVER"));
        long r = id(send(me, post(R), form(101, 1, "4", "N", null)));
        String d = body(me, R + "/" + r);
        assertThat((String) JsonPath.read(d, "$.data.returnNo")).startsWith("RT");
        assertThat((String) JsonPath.read(d, "$.data.ncrNo")).isEqualTo("NCR-101");
        assertThat((String) JsonPath.read(d, "$.data.lotNo")).isEqualTo("L500");
        assertThat((String) JsonPath.read(d, "$.data.itemCd")).isEqualTo("BOLT");
        assertThat((String) JsonPath.read(d, "$.data.status")).isEqualTo("DRAFT");
        // 반품 대상에 살아 있는 반품 번호, 같은 줄 두 번째 → SRM_RTN_DUP
        assertThat((String) JsonPath.read(candidates(), "$.data.rows[0].returnNo")).isEqualTo(JsonPath.read(d, "$.data.returnNo"));
        send(me, post(R), form(101, 1, "1", "N", null)).andExpect(jsonPath("$.errors[0].code").value("SRM_RTN_DUP"));
        // 수정: 6 까지, 교체 요구 + BOLT 발주 행
        send(me, put(R + "/" + r), "{\"plantCd\":\"P1\",\"qty\":\"6\",\"replaceYn\":\"Y\",\"poLineId\":" + boltLine + ",\"rowVer\":" + ver(r) + "}")
                .andExpect(status().isOk());
        d = body(me, R + "/" + r);
        assertThat((String) JsonPath.read(d, "$.data.qty")).isEqualTo("6");
        assertThat((String) JsonPath.read(d, "$.data.replaceYn")).isEqualTo("Y");
        // 확정 → 수정 SRM_STATE, 사유 없는 취소 400, 사유 있는 취소 → 같은 줄로 다시 만들 수 있다
        act(me, r, "confirm", null).andExpect(status().isOk());
        assertThat((String) JsonPath.read(body(me, R + "/" + r), "$.data.status")).isEqualTo("CONFIRMED");
        send(me, put(R + "/" + r), "{\"plantCd\":\"P1\",\"qty\":\"1\",\"replaceYn\":\"N\",\"rowVer\":" + ver(r) + "}")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SRM_STATE"));
        act(me, r, "confirm", null).andExpect(status().isConflict());
        act(me, r, "cancel", null).andExpect(jsonPath("$.errors[0].field").value("reason"));
        act(me, r, "cancel", "수량 잘못").andExpect(status().isOk());
        long r2 = id(send(me, post(R), form(101, 1, "2", "N", null)));
        // 확정 전에 NCR 이 다시 열림(계약 값이 바뀜) → 확정 막힘
        when(quality.ncr(101L)).thenReturn(Optional.of(ncr(101, "OPEN", vCust)));
        act(me, r2, "confirm", null).andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].code").value("SRM_RTN_NCR_STATE"));
        // 낡은 행 버전 → 409
        send(me, post(R + "/" + r2 + "/cancel"), "{\"rowVer\":0,\"reason\":\"x\"}").andExpect(status().isOk());
        send(me, post(R + "/" + r2 + "/cancel"), "{\"rowVer\":0,\"reason\":\"x\"}").andExpect(status().isConflict());
    }

    @Test
    void 입력검사_근거NCR_상태_협력사_없음_줄없음_교체요구_발주행_잘림표시() throws Exception {
        send(me, post(R), form(102, 1, "1", "N", null)).andExpect(jsonPath("$.errors[0].code").value("SRM_RTN_NCR_STATE"));
        send(me, post(R), form(103, 1, "1", "N", null)).andExpect(jsonPath("$.errors[0].code").value("SRM_RTN_NCR_SUPPLIER"));
        send(me, post(R), form(999, 1, "1", "N", null)).andExpect(jsonPath("$.errors[0].field").value("ncrId"))
                .andExpect(jsonPath("$.errors[0].code").value("NO_SUCH_CODE"));
        send(me, post(R), form(101, 2, "1", "N", null)).andExpect(jsonPath("$.errors[0].field").value("lineNo"))
                .andExpect(jsonPath("$.errors[0].code").value("NO_SUCH_CODE"));
        send(me, post(R), form(101, 1, "1", "Y", null)).andExpect(jsonPath("$.errors[0].field").value("poLineId"))
                .andExpect(jsonPath("$.errors[0].code").value("REQUIRED"));
        send(me, post(R), form(101, 1, "1", "Y", nutLine)).andExpect(jsonPath("$.errors[0].code").value("SRM_RTN_PO_ITEM"));
        send(me, post(R), form(101, 1, "0", "X", 99_999_999L)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[*].field").value(Matchers.containsInAnyOrder("qty", "replaceYn", "poLineId")));
        send(me, post(R), "{\"custCd\":\"V999\",\"plantCd\":\"P9\"}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[*].field").value(Matchers.hasItems("custCd", "ncrId", "lineNo")));
        assertThat(accounts.query(comp, "select count(*) from srm_return", Integer.class)).isZero();
        // 잘림 표시는 그대로 넘긴다, 기간 1년 넘음 400
        when(quality.returnsToSupplier(eq(vCust), any(), any())).thenReturn(new ReturnPage(List.of(line(101, 1, "6")), true));
        assertThat((Boolean) JsonPath.read(candidates(), "$.data.truncated")).isTrue();
        mvc.perform(get(R + "/candidates?custCd=V100&from=" + today.minusDays(400) + "&to=" + today).with(me)).andExpect(status().isBadRequest());
        // grid: 기간 필수·정렬 허용 목록
        mvc.perform(get(R + "/grid?from=" + today + "&to=" + today + "&sort=qty,asc").with(me)).andExpect(status().isBadRequest());
        mvc.perform(get(R + "/grid").with(me)).andExpect(status().isBadRequest());
        mvc.perform(get(R + "/columns").with(me)).andExpect(jsonPath("$.list[*].field").value(Matchers.hasItems("returnNo", "status")));
    }

    @Test
    void A사로_B사_조회수정_막힘_다른회사_발주행_권한() throws Exception {
        long r = id(send(me, post(R), form(101, 1, "3", "N", null)));
        String other = accounts.newComp("RU");
        long bu = accounts.newUser(other, "buyer1", PW);
        grantAll(other, bu);
        RequestPostProcessor b = accounts.as(other, bu, "buyer1");
        seed(other);
        mvc.perform(get(R + "/" + r).with(b)).andExpect(status().isNotFound());
        send(b, put(R + "/" + r), "{\"plantCd\":\"P1\",\"qty\":\"1\",\"rowVer\":0}").andExpect(status().isNotFound());
        send(b, post(R + "/" + r + "/confirm"), "{\"rowVer\":0}").andExpect(status().isNotFound());
        send(b, post(R + "/" + r + "/cancel"), "{\"rowVer\":0,\"reason\":\"x\"}").andExpect(status().isNotFound());
        mvc.perform(get(R + "/grid?from=" + today + "&to=" + today).with(b)).andExpect(jsonPath("$.total").value(0));
        mvc.perform(get(R + "/grid?from=" + today + "&to=" + today).with(me)).andExpect(jsonPath("$.total").value(1));
        // B사의 V100 은 다른 협력사 → A사 NCR 은 이 협력사 것이 아님 / A사 발주 행은 B사에서 안 보임
        send(b, post(R), form(101, 1, "1", "N", null)).andExpect(jsonPath("$.errors[0].code").value("SRM_RTN_NCR_SUPPLIER"));
        when(quality.ncr(201L)).thenReturn(Optional.of(new NcrDetail(201, "NCR-201", "CLOSED", "INSP", 600, "L600", null,
                accounts.query(other, "select cust_id from mst_cust where cust_cd = 'V100'", Long.class), bolt, "BOLT", BigDecimal.TEN,
                BigDecimal.ONE, "EA", null, null, null, null, closedAt, null)));
        send(b, post(R), form(201, 1, "1", "Y", boltLine)).andExpect(jsonPath("$.errors[*].field").value(Matchers.hasItem("poLineId")));
        assertThat(accounts.query(other, "select count(*) from srm_return", Integer.class)).isZero();
        // 권한: 저장만 → 확정 403, 조회만 → 만들기·취소 403
        long clerk = accounts.newUser(comp, "clerk1", PW);
        accounts.grant(comp, clerk, "CLERK", "srm-return", "SEARCH", "SAVE");
        RequestPostProcessor c = accounts.as(comp, clerk, "clerk1");
        send(c, post(R + "/" + r + "/confirm"), "{\"rowVer\":" + ver(r) + "}").andExpect(status().isForbidden());
        long viewer = accounts.newUser(comp, "viewer1", PW);
        accounts.grant(comp, viewer, "VIEW", "srm-return", "SEARCH");
        RequestPostProcessor v = accounts.as(comp, viewer, "viewer1");
        mvc.perform(get(R + "/" + r).with(v)).andExpect(status().isOk());
        send(v, post(R), form(101, 1, "1", "N", null)).andExpect(status().isForbidden());
        send(v, post(R + "/" + r + "/cancel"), "{\"rowVer\":0,\"reason\":\"x\"}").andExpect(status().isForbidden());
    }

    @Test
    void 교체요구_발주단위가_달라도_환산되면_만들고_나누어떨어지지_않거나_환산없으면_막힘_확정때_다시() throws Exception {
        // CAP: BOX 로 발주한 뒤 기본 단위 EA(1 BOX = 12 EA) — NCR 처리 줄은 LOT 기본 단위 EA
        long cap = accounts.query(comp, """
                insert into mst_item (comp_cd, item_cd, item_nm, item_type, proc_type, unit_cd, lot_ctrl_yn, created_by, updated_by)
                values (?, 'CAP', '캡', 'RM', 'BUY', 'BOX', 'N', 't', 't') returning item_id""", Long.class, comp);
        long po = id(send(me, post("/api/srm/pos"), "{\"custCd\":\"V100\",\"plantCd\":\"P1\",\"lines\":[{\"itemCd\":\"CAP\",\"qty\":\"5\","
                + "\"unitPrice\":\"1\",\"dueDate\":\"" + today.plusDays(20) + "\"}]}"));
        long capLine = ((Number) JsonPath.read(body(me, "/api/srm/pos/" + po), "$.data.lines[0].poLineId")).longValue();
        accounts.exec(comp, "update mst_item set unit_cd = 'EA' where item_id = ?", cap);
        accounts.exec(comp, "insert into mst_unit_conv (comp_cd, item_id, from_unit, to_unit, factor, created_by, updated_by) values (?, ?, 'BOX', 'EA', 12, 't', 't')",
                comp, cap);
        when(quality.ncr(301L)).thenReturn(Optional.of(new NcrDetail(301, "NCR-301", "CLOSED", "INSP", 700, "L700", null, vCust, cap, "CAP",
                new BigDecimal("30"), new BigDecimal("24"), "EA", "D1", "깨짐", null, null, closedAt, null)));
        when(quality.returnsToSupplier(eq(vCust), any(), any())).thenReturn(new ReturnPage(List.of(line(101, 1, "6"),
                new SupplierReturn(301, "NCR-301", 1, 700, "L700", null, cap, "CAP", new BigDecimal("24"), "EA", "D1", "깨짐", closedAt, null)), false));
        assertThat(accounts.query(comp, "select unit_cd from srm_po_line where po_line_id = ?", String.class, capLine)).isEqualTo("BOX");
        // 12 EA = 1 BOX → 만들어짐(트리거도 단위 차이를 받는다)
        long r = id(send(me, post(R), form(301, 1, "12", "Y", capLine)));
        // 6 EA = 0.5 BOX — BOX 는 소수 0자리 → 400(수량), 그대로
        send(me, put(R + "/" + r), "{\"plantCd\":\"P1\",\"qty\":\"6\",\"replaceYn\":\"Y\",\"poLineId\":" + capLine + ",\"rowVer\":" + ver(r) + "}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("qty"))
                .andExpect(jsonPath("$.errors[0].code").value("SRM_RTN_PO_SCALE"));
        // 교체 요구 없음이면 발주 단위를 보지 않는다
        send(me, put(R + "/" + r), "{\"plantCd\":\"P1\",\"qty\":\"6\",\"replaceYn\":\"N\",\"poLineId\":" + capLine + ",\"rowVer\":" + ver(r) + "}")
                .andExpect(status().isOk());
        // 환산 끔 → 교체 요구 400(발주 행), 다시 켜고 24 EA = 2 BOX
        accounts.exec(comp, "update mst_unit_conv set use_yn = 'N' where item_id = ?", cap);
        send(me, put(R + "/" + r), "{\"plantCd\":\"P1\",\"qty\":\"24\",\"replaceYn\":\"Y\",\"poLineId\":" + capLine + ",\"rowVer\":" + ver(r) + "}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("poLineId"))
                .andExpect(jsonPath("$.errors[0].code").value("SRM_RTN_PO_CONV"));
        accounts.exec(comp, "update mst_unit_conv set use_yn = 'Y' where item_id = ?", cap);
        send(me, put(R + "/" + r), "{\"plantCd\":\"P1\",\"qty\":\"24\",\"replaceYn\":\"Y\",\"poLineId\":" + capLine + ",\"rowVer\":" + ver(r) + "}")
                .andExpect(status().isOk());
        // 만든 뒤 환산이 바뀜(1 BOX = 10 EA → 24 EA = 2.4 BOX) → 확정 400, 되돌리면 확정
        accounts.exec(comp, "update mst_unit_conv set factor = 10 where item_id = ?", cap);
        act(me, r, "confirm", null).andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].code").value("SRM_RTN_PO_SCALE"));
        accounts.exec(comp, "update mst_unit_conv set factor = 12 where item_id = ?", cap);
        act(me, r, "confirm", null).andExpect(status().isOk());
        assertThat(accounts.query(comp, "select unit_cd || ':' || trim_scale(qty) || ':' || replace_yn from srm_return where return_id = ?", String.class, r))
                .isEqualTo("EA:24:Y");
    }
}
