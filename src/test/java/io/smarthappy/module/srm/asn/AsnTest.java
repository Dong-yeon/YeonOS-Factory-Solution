package io.smarthappy.module.srm.asn;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.jayway.jsonpath.JsonPath;

import io.smarthappy.module.srm.link.AsnQuery;
import io.smarthappy.module.srm.link.PoReceiptGate;
import io.smarthappy.module.srm.link.PoReceiptGate.Receipt;
import io.smarthappy.module.srm.link.PoSupplyQuery;
import io.smarthappy.module.srm.link.PoSupplyQuery.Supply;
import io.smarthappy.platform.common.db.PgErrors;
import io.smarthappy.platform.common.web.BusinessException;
import io.smarthappy.platform.common.web.HbErrorCode;
import io.smarthappy.platform.tenant.TenantRunner;
import io.smarthappy.support.PostgresTestBase;
import io.smarthappy.support.TestAccounts;

/**
 * 사전 납품 통지 ASN (SRM 2부 R3a, docs/31 4.11) — 작성·확정·한도(R3-4)·조회 계약 AsnQuery·게이트 asnLineId/reversesRef·
 * 상태(입고 중·다 받음·마감·취소)·APS 공급일(R3-6)·엑셀 올리기·직접 SQL·A사로 B사.
 */
@AutoConfigureMockMvc
class AsnTest extends PostgresTestBase {

    private static final String PW = "Maple-anchor-63";
    private static final String PO = "/api/srm/pos";
    private static final String ASN = "/api/srm/asns";
    private static final String IMP = "/api/platform/import";
    private static final String XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    private static final AtomicInteger REF = new AtomicInteger();

    @Autowired
    MockMvc mvc;

    @Autowired
    TestAccounts accounts;

    @Autowired
    TenantRunner runner;

    @Autowired
    ObjectProvider<PoReceiptGate> gate;

    @Autowired
    ObjectProvider<AsnQuery> query;

    @Autowired
    ObjectProvider<PoSupplyQuery> supply;

    String comp;
    RequestPostProcessor me;
    LocalDate today;
    long bolt;

    @BeforeEach
    void setUp() {
        comp = accounts.newComp("AS");
        me = buyer(comp);
        seed(comp);
        today = LocalDate.now(ZoneId.of("Asia/Seoul"));
        bolt = accounts.query(comp, "select item_id from mst_item where item_cd = 'BOLT-M8'", Long.class);
    }

    private RequestPostProcessor buyer(String c) {
        long u = accounts.newUser(c, "buyer1", PW);
        accounts.grant(c, u, "PUR", "srm-po", "SEARCH", "SAVE", "APPROVE");
        accounts.grant(c, u, "PUR", "srm-asn", "SEARCH", "SAVE");
        return accounts.as(c, u, "buyer1");
    }

    private void seed(String c) {
        accounts.addPlant(c, "P1", "1공장");
        accounts.addPlant(c, "P2", "2공장");
        accounts.exec(c, "insert into sys_policy (comp_cd, policy_key, policy_value, created_by, updated_by) values (?, 'SRM_PO_APPROVAL', 'N', 't', 't')", c);
        accounts.exec(c, """
                insert into mst_item (comp_cd, item_cd, item_nm, item_type, proc_type, unit_cd, lot_ctrl_yn, created_by, updated_by)
                values (?, 'BOLT-M8', '볼트', 'RM', 'BUY', 'EA', 'N', 't', 't'), (?, 'RESIN-A', '수지', 'RM', 'BUY', 'KG', 'N', 't', 't')""", c, c);
        for (String v : List.of("V100", "V200")) {
            long id = accounts.query(c, """
                    insert into mst_cust (comp_cd, cust_cd, cust_nm, cust_type, created_by, updated_by)
                    values (?, ?, ? || ' 상사', 'PURCHASE', 't', 't') returning cust_id""", Long.class, c, v, v);
            accounts.exec(c, "insert into srm_supplier (cust_id, comp_cd, currency_cd, created_by, updated_by) values (?, ?, 'KRW', 't', 't')", id, c);
        }
    }

    private ResultActions send(RequestPostProcessor who, String method, String url, String body) throws Exception {
        var b = "PUT".equals(method) ? put(url) : post(url);
        return mvc.perform(b.with(csrf()).with(who).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private static long id(ResultActions r) throws Exception {
        return ((Number) JsonPath.read(r.andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.data")).longValue();
    }

    /** 발행된 발주(행 1: BOLT 100 EA, 행 2: RESIN 2.5 KG, 요구 납기 +20) */
    private long issued(String cust, String plant) throws Exception {
        long id = id(send(me, "POST", PO, "{\"custCd\":\"" + cust + "\",\"plantCd\":\"" + plant + "\",\"lines\":[{\"itemCd\":\"BOLT-M8\",\"qty\":\"100\","
                + "\"unitPrice\":\"1\",\"dueDate\":\"" + today.plusDays(20) + "\"},{\"itemCd\":\"RESIN-A\",\"qty\":\"2.5\",\"unitPrice\":\"1\",\"dueDate\":\""
                + today.plusDays(20) + "\"}]}"));
        for (String a : List.of("confirm", "issue")) {
            int v = JsonPath.read(mvc.perform(get(PO + "/" + id).with(me)).andReturn().getResponse().getContentAsString(), "$.data.po.rowVer");
            send(me, "POST", PO + "/" + id + "/" + a, "{\"rowVer\":" + v + "}").andExpect(status().isOk());
        }
        return id;
    }

    private long line(long poId, int lineNo) {
        return accounts.query(comp, "select po_line_id from srm_po_line where po_id = ? and line_no = ?", Long.class, poId, lineNo);
    }

    private static String asnBody(String cust, String plant, String ref, String linesJson) {
        return "{\"custCd\":\"" + cust + "\",\"plantCd\":\"" + plant + "\",\"shipDate\":\"" + LocalDate.now(ZoneId.of("Asia/Seoul"))
                + "\",\"etaDate\":\"" + LocalDate.now(ZoneId.of("Asia/Seoul")).plusDays(3) + "\"" + (ref == null ? "" : ",\"supplierRef\":\"" + ref + "\"")
                + ",\"lines\":[" + linesJson + "]}";
    }

    private static String ln(long poLineId, String qty, String lot) {
        return "{\"poLineId\":" + poLineId + ",\"qty\":\"" + qty + "\"" + (lot == null ? "" : ",\"supplierLotNo\":\"" + lot + "\"") + "}";
    }

    private int ver(long asnId) throws Exception {
        return JsonPath.read(mvc.perform(get(ASN + "/" + asnId).with(me)).andReturn().getResponse().getContentAsString(), "$.data.asn.rowVer");
    }

    private ResultActions act(long asnId, String action, String reason) throws Exception {
        return send(me, "POST", ASN + "/" + asnId + "/" + action, "{\"rowVer\":" + ver(asnId) + (reason == null ? "" : ",\"reason\":\"" + reason + "\"") + "}");
    }

    private String asnStatus(long asnId) {
        return accounts.query(comp, "select status from srm_asn where asn_id = ?", String.class, asnId);
    }

    private long asnLine(long asnId, int lineNo) {
        return accounts.query(comp, "select asn_line_id from srm_asn_line where asn_id = ? and line_no = ?", Long.class, asnId, lineNo);
    }

    private Receipt receive(String c, long poLineId, String qty, Long asnLineId, String ref) {
        return runner.call(c, () -> PoReceiptGate.of(gate).receiveAt(poLineId, new BigDecimal(qty), ref, "wms1", null, asnLineId));
    }

    private Receipt reverse(String c, long poLineId, String qty, String ref, String reversesRef) {
        return runner.call(c, () -> PoReceiptGate.of(gate).reverseAt(poLineId, new BigDecimal(qty), ref, "wms1", null, reversesRef));
    }

    private HbErrorCode fail(Runnable r) {
        Throwable t = catchThrowable(r::run);
        assertThat(t).isInstanceOf(BusinessException.class);
        return ((BusinessException) t).code();
    }

    private static String ref() {
        return "RCV_PO:asn-" + REF.incrementAndGet();
    }

    @Test
    void 작성_확정_조회계약_입고중_다받음_되돌림_마감_APS공급일() throws Exception {
        long po = issued("V100", "P1");
        long l1 = line(po, 1);
        long l2 = line(po, 2);
        long asn = id(send(me, "POST", ASN, asnBody("V100", "P1", "DN-1", ln(l1, "40", "L-1") + "," + ln(l1, "20", "L-2"))));
        assertThat(asnStatus(asn)).isEqualTo("DRAFT");
        assertThat(accounts.query(comp, "select asn_no from srm_asn where asn_id = ?", String.class, asn)).startsWith("ASN");
        // 작성 중 고치기: 행 통째로 다시(수지 행 추가)
        send(me, "PUT", ASN + "/" + asn, asnBody("V100", "P1", "DN-1", ln(l1, "40", "L-1") + "," + ln(l1, "20", "L-2") + "," + ln(l2, "2.5", null))
                .replace("\"lines\"", "\"rowVer\":" + ver(asn) + ",\"lines\"")).andExpect(status().isOk());
        assertThat(accounts.query(comp, "select count(*) from srm_asn_line where asn_id = ?", Integer.class, asn)).isEqualTo(3);
        // 작성 중은 조회 계약에 안 보인다 → 확정 뒤 보인다(ASN: 스캔 키·납품서 번호 둘 다)
        String asnNo = accounts.query(comp, "select asn_no from srm_asn where asn_id = ?", String.class, asn);
        assertThat(runner.call(comp, () -> AsnQuery.of(query).find("ASN:" + asnNo))).isEmpty();
        act(asn, "send", null).andExpect(status().isOk());
        AsnQuery.Asn found = runner.call(comp, () -> AsnQuery.of(query).find("ASN:" + asnNo)).orElseThrow();
        assertThat(found.head().plantCd()).isEqualTo("P1");
        assertThat(found.lines()).extracting(x -> x.poLineId() + ":" + x.itemId() + ":" + x.remainQty().toPlainString() + ":" + x.supplierLotNo())
                .containsExactly(l1 + ":" + bolt + ":40:L-1", l1 + ":" + bolt + ":20:L-2", l2 + ":" + found.lines().get(2).itemId() + ":2.5:null");
        assertThat(runner.call(comp, () -> AsnQuery.of(query).find("DN-1")).map(a -> a.head().asnId())).contains(asn);
        assertThat(runner.call(comp, () -> AsnQuery.of(query).expected("P1", null, null))).extracting(AsnQuery.AsnHead::asnId).containsExactly(asn);
        // APS 공급일: ASN 행마다 도착 예정일(40·20 EA) → 나머지 40 은 요구 납기
        List<Supply> s = runner.call(comp, () -> PoSupplyQuery.of(supply).open("P1", List.of(bolt), null));
        assertThat(s).extracting(x -> x.supplyDate() + ":" + x.qty().toPlainString())
                .containsExactly(today.plusDays(3) + ":40", today.plusDays(3) + ":20", today.plusDays(20) + ":40");
        // 입고(ASN 행 1 의 40) → 입고 중, 행 2 의 20 + 수지 → 다 받음
        long a1 = asnLine(asn, 1);
        String r1 = ref();
        assertThat(receive(comp, l1, "40", a1, r1).asnLineId()).isEqualTo(a1);
        assertThat(asnStatus(asn)).isEqualTo("PARTIAL_RECEIVED");
        receive(comp, l1, "20", asnLine(asn, 2), ref());
        receive(comp, l2, "2.5", asnLine(asn, 3), ref());
        assertThat(asnStatus(asn)).isEqualTo("RECEIVED");
        assertThat(runner.call(comp, () -> AsnQuery.of(query).expected("P1", null, null))).isEmpty();
        // 되돌림: 원래 근거로 → 이력에 ASN 행·발생일 복사, ASN 다시 입고 중
        reverse(comp, l1, "-40", "RCV_POR:" + REF.incrementAndGet(), r1);
        assertThat(asnStatus(asn)).isEqualTo("PARTIAL_RECEIVED");
        assertThat(accounts.query(comp, "select asn_line_id from srm_po_receipt where qty < 0", Long.class)).isEqualTo(a1);
        mvc.perform(get(ASN + "/" + asn).with(me)).andExpect(jsonPath("$.data.lines[0].recvQty").value("0"))
                .andExpect(jsonPath("$.data.lines[0].remainQty").value("40"));
        // 마감 — 그 뒤 ASN 행으로 입고는 거부, 되돌림은 받는다(상태는 마감 그대로)
        act(asn, "close", null).andExpect(status().isOk());
        assertThat(fail(() -> receive(comp, l1, "1", a1, ref()))).isEqualTo(HbErrorCode.SRM_STATE);
        reverse(comp, l2, "-2.5", "RCV_POR:" + REF.incrementAndGet(), accounts.query(comp,
                "select trx_ref from srm_po_receipt where asn_line_id = ?", String.class, asnLine(asn, 3)));
        assertThat(asnStatus(asn)).isEqualTo("CLOSED");
        // 목록·늦은 ASN(도착 예정일이 미래라 늦지 않음)
        mvc.perform(get(ASN).param("from", today.toString()).param("to", today.plusDays(5).toString()).with(me))
                .andExpect(jsonPath("$.data[0].asnId").value(asn)).andExpect(jsonPath("$.data[0].late").value(false));
        mvc.perform(get(ASN).param("from", today.toString()).param("to", today.plusDays(5).toString()).param("late", "true").with(me))
                .andExpect(jsonPath("$.data.length()").value(0));
    }

    @Test
    void 한도는_확정때만_입고때_ASN초과는_막지않음_검사_취소_납품서번호_재사용() throws Exception {
        long po = issued("V100", "P1");
        long l1 = line(po, 1);
        long a = id(send(me, "POST", ASN, asnBody("V100", "P1", "DN-9", ln(l1, "100", null))));
        act(a, "send", null).andExpect(status().isOk());
        // 둘째 ASN 10 → 한도(100 − 100 = 0) 초과, 행 오류
        long b = id(send(me, "POST", ASN, asnBody("V100", "P1", "DN-10", ln(l1, "10", null))));
        act(b, "send", null).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].code").value("SRM_ASN_OVER")).andExpect(jsonPath("$.errors[0].rowKey").value("0"));
        // 허용 초과율 10% → 10 까지 가능
        accounts.exec(comp, "insert into sys_policy (comp_cd, policy_key, policy_value, created_by, updated_by) values (?, 'SRM_RECV_OVER_PCT', '10', 't', 't')", comp);
        act(b, "send", null).andExpect(status().isOk());
        // 입고 때 ASN 남은 양 초과는 막지 않음(발주 허용 초과율 110 안) → ASN 행 다 받음
        receive(comp, l1, "105", asnLine(a, 1), ref());
        assertThat(asnStatus(a)).isEqualTo("RECEIVED");
        // 받은 ASN 취소 불가, 받지 않은 ASN 취소(사유 필수) → 납품서 번호 다시 쓸 수 있음
        act(a, "cancel", "x").andExpect(status().isConflict());
        act(b, "cancel", null).andExpect(status().isBadRequest());
        act(b, "cancel", "중복 통지").andExpect(status().isOk());
        assertThat(asnStatus(b)).isEqualTo("CANCELLED");
        // 같은 협력사 살아 있는 ASN 의 납품서 번호 → 중복 / 취소된 것의 번호는 다시
        send(me, "POST", ASN, asnBody("V100", "P1", "DN-9", "")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("supplierRef"));
        id(send(me, "POST", ASN, asnBody("V100", "P1", "DN-10", "")));
        // 행 검사: 다른 협력사 발주 행 / 다른 공장 / 같은 행·LOT 두 번 / 도착 예정일 < 출하일 / 작성 중 발주
        long other = issued("V200", "P1");
        long p2 = issued("V100", "P2");
        send(me, "POST", ASN, asnBody("V100", "P1", null, ln(line(other, 1), "1", null) + "," + ln(line(p2, 1), "1", null) + ","
                + ln(l1, "1", "X") + "," + ln(l1, "1", "X"))).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[?(@.rowKey == '0')].code").value("SRM_OTHER_SUPPLIER"))
                .andExpect(jsonPath("$.errors[?(@.rowKey == '1')].code").value("SRM_ASN_OTHER_PLANT"))
                .andExpect(jsonPath("$.errors[?(@.rowKey == '3')].code").value("SRM_ASN_DUP_LINE"));
        send(me, "POST", ASN, asnBody("V100", "P1", null, "").replace("\"etaDate\":\"" + today.plusDays(3), "\"etaDate\":\"" + today.minusDays(1)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].code").value("SRM_ASN_ETA"));
        long draftPo = id(send(me, "POST", PO, "{\"custCd\":\"V100\",\"plantCd\":\"P1\",\"lines\":[{\"itemCd\":\"BOLT-M8\",\"qty\":\"5\",\"unitPrice\":\"1\",\"dueDate\":\""
                + today.plusDays(5) + "\"}]}"));
        send(me, "POST", ASN, asnBody("V100", "P1", null, ln(line(draftPo, 1), "1", null))).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].code").value("SRM_ASN_PO_STATE"));
        // 확정 뒤 고치기 → 상태 오류, 행 없는 ASN 확정 → 행 필요
        send(me, "PUT", ASN + "/" + a, asnBody("V100", "P1", "DN-9", "").replace("\"lines\"", "\"rowVer\":" + ver(a) + ",\"lines\""))
                .andExpect(status().isConflict());
        long empty = id(send(me, "POST", ASN, asnBody("V100", "P1", null, "")));
        act(empty, "send", null).andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].code").value("LINES_REQUIRED"));
    }

    @Test
    void 게이트_ASN행_규칙_다른발주행_작성중_취소_되돌림근거() throws Exception {
        long po = issued("V100", "P1");
        long l1 = line(po, 1);
        long l2 = line(po, 2);
        long asn = id(send(me, "POST", ASN, asnBody("V100", "P1", null, ln(l1, "50", null))));
        long a1 = asnLine(asn, 1);
        assertThat(fail(() -> receive(comp, l1, "10", a1, ref()))).isEqualTo(HbErrorCode.SRM_STATE);           // 작성 중
        act(asn, "send", null).andExpect(status().isOk());
        assertThat(fail(() -> receive(comp, l2, "1", a1, ref()))).isEqualTo(HbErrorCode.SRM_ASN_LINE_MISMATCH); // 다른 발주 행
        assertThat(accounts.query(comp, "select trim_scale(recv_qty)::text from srm_po_line where po_line_id = ?", String.class, l2)).isEqualTo("0");
        String r = ref();
        receive(comp, l1, "10", a1, r);
        // 되돌림 근거가 다른 발주 행의 것 / 모르는 근거(ASN 없이 오늘 날짜로)
        assertThat(fail(() -> reverse(comp, l2, "-1", "RCV_POR:" + REF.incrementAndGet(), r))).isEqualTo(HbErrorCode.SRM_ASN_LINE_MISMATCH);
        receive(comp, l1, "5", null, ref());                      // ASN 없는 입고
        reverse(comp, l1, "-5", "RCV_POR:" + REF.incrementAndGet(), "RCV_PO:없는-근거");
        assertThat(accounts.query(comp, "select count(*) from srm_po_receipt where asn_line_id is null", Integer.class)).isEqualTo(2);
        // 방향이 틀린 수량: 입고(asnLineId)에 음수 / reverseAt 에 양수 → 부르는 쪽 버그
        assertThat(catchThrowable(() -> receive(comp, l1, "-1", a1, ref()))).isInstanceOf(IllegalArgumentException.class);
        assertThat(catchThrowable(() -> reverse(comp, l1, "1", ref(), r))).isInstanceOf(IllegalArgumentException.class);
        // 빈 없는 설치본
        ObjectProvider<AsnQuery> none = new StaticListableBeanFactory().getBeanProvider(AsnQuery.class);
        assertThat(AsnQuery.of(none)).isSameAs(AsnQuery.NONE);
        assertThat(AsnQuery.NONE.find("ASN:X")).isEmpty();
    }

    @Test
    void 직접SQL_상태만_바꾸기_확정뒤_행수정_삭제_다른협력사_발주행_한도() throws Exception {
        long po = issued("V100", "P1");
        long l1 = line(po, 1);
        long asn = id(send(me, "POST", ASN, asnBody("V100", "P1", null, ln(l1, "30", null))));
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(comp,
                "update srm_asn set status = 'RECEIVED' where asn_id = ?", asn)))).contains("ck_srm_asn_transition");
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(comp, """
                insert into srm_asn (comp_cd, asn_no, cust_id, plant_cd, ship_date, eta_date, status, created_by, updated_by)
                values (?, 'X1', (select cust_id from mst_cust where cust_cd = 'V100'), 'P1', %s, %s, 'SENT', 't', 't')"""
                .formatted(TestAccounts.COMP_TODAY, TestAccounts.COMP_TODAY), comp)))).contains("ck_srm_asn_transition");
        // 다른 협력사 발주 행으로 행
        long other = issued("V200", "P1");
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(comp, """
                insert into srm_asn_line (comp_cd, asn_id, line_no, po_line_id, item_id, unit_cd, qty, created_by, updated_by)
                values (?, ?, 9, ?, ?, 'EA', 1, 't', 't')""", comp, asn, line(other, 1), bolt)))).contains("fk_srm_asn_line_po_line_comp");
        act(asn, "send", null).andExpect(status().isOk());
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(comp,
                "update srm_asn_line set qty = 1 where asn_id = ?", asn)))).contains("ck_srm_asn_line_draft");
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(comp,
                "update srm_asn set eta_date = eta_date + 1 where asn_id = ?", asn)))).contains("ck_srm_asn_draft");
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(comp,
                "update srm_asn set status = 'RECEIVED' where asn_id = ?", asn)))).contains("ck_srm_asn_recv_status");
        assertThat(catchThrowable(() -> accounts.exec(comp, "delete from srm_asn where asn_id = ?", asn))).rootCause()
                .hasMessageContaining("permission denied");
        // 한도: 한 ASN 이 100 중 30 을 잡았는데 둘째가 SQL 로 80 확정
        long b = id(send(me, "POST", ASN, asnBody("V100", "P1", null, ln(l1, "80", null))));
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(comp,
                "update srm_asn set status = 'SENT', sent_at = now(), sent_by = 't' where asn_id = ?", b)))).contains("ck_srm_asn_over");
        // 이력에 다른 발주 행의 ASN 행
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(comp, """
                insert into srm_po_receipt (comp_cd, po_id, po_line_id, occurred_on, qty, trx_ref, asn_line_id, created_by)
                values (?, ?, ?, %s, 1, 'SQL-ASN', ?, 't')""".formatted(TestAccounts.COMP_TODAY), comp, po, line(po, 2), asnLine(asn, 1)))))
                .contains("fk_srm_po_receipt_asn_line_comp");
    }

    @Test
    void A사로_B사_ASN_조회_수정_발주행_게이트() throws Exception {
        long po = issued("V100", "P1");
        long l1 = line(po, 1);
        long asn = id(send(me, "POST", ASN, asnBody("V100", "P1", "DN-A", ln(l1, "10", null))));
        act(asn, "send", null).andExpect(status().isOk());
        String asnNo = accounts.query(comp, "select asn_no from srm_asn where asn_id = ?", String.class, asn);
        String other = accounts.newComp("AB");
        RequestPostProcessor b = buyer(other);
        seed(other);
        // B사 범위: A사 ASN 번호·납품서 번호 조회 → 빈 값, API 상세·고치기·취소 → 404
        assertThat(runner.call(other, () -> AsnQuery.of(query).find("ASN:" + asnNo))).isEmpty();
        assertThat(runner.call(other, () -> AsnQuery.of(query).find("DN-A"))).isEmpty();
        assertThat(runner.call(other, () -> AsnQuery.of(query).expected("P1", null, null))).isEmpty();
        mvc.perform(get(ASN + "/" + asn).with(b)).andExpect(status().isNotFound());
        send(b, "POST", ASN + "/" + asn + "/cancel", "{\"rowVer\":1,\"reason\":\"x\"}").andExpect(status().isNotFound());
        // B사 ASN 에 A사 발주 행 → 없는 행 / 게이트에 A사 ASN 행(B사 범위) → 404
        send(b, "POST", ASN, asnBody("V100", "P1", null, ln(l1, "1", null))).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].code").value("NO_SUCH_CODE"));
        long a1 = asnLine(asn, 1);
        assertThat(fail(() -> receive(other, l1, "1", a1, ref()))).isEqualTo(HbErrorCode.NOT_FOUND);
        assertThat(accounts.query(comp, "select count(*) from srm_po_receipt", Integer.class)).isZero();
    }

    @Test
    void 엑셀_같은_납품서는_ASN_하나_확정까지_행오류_머리불일치_합초과_중복번호() throws Exception {
        long po = issued("V100", "P1");
        String poNo = accounts.query(comp, "select po_no from srm_po where po_id = ?", String.class, po);
        String ship = today.toString(), eta = today.plusDays(2).toString();
        // custCd, supplierRef, plantCd, shipDate, etaDate, poNo, lineNo, qty, lot, mfg, expiry, pack, remark
        long job = upload(fill(List.of(
                new Object[] {"V100", "DN-X1", "P1", ship, eta, poNo, 1, 60, "L1", null, null, 3, null},
                new Object[] {"V100", "DN-X1", "P1", ship, eta, poNo, 2, 2.5, null, null, null, null, null},
                new Object[] {"V100", "DN-X2", "P1", ship, eta, poNo, 1, 50, "L9", null, null, null, null},          // 60 + 50 > 100
                new Object[] {"V100", "DN-X1", "P1", ship, today.plusDays(9).toString(), poNo, 1, 1, "L2", null, null, null, null}, // 머리 다름
                new Object[] {"V200", "DN-X3", "P1", ship, eta, poNo, 1, 1, null, null, null, null, null})));          // 다른 협력사
        assertThat(errorCodes(job)).containsExactlyInAnyOrder("SRM_ASN_OVER", "SRM_ASN_HEAD_MISMATCH", "SRM_OTHER_SUPPLIER");
        execute(job).andExpect(status().isOk()).andExpect(jsonPath("$.data.result.inserted").value(2));
        assertThat(accounts.query(comp, "select string_agg(status || ':' || source || ':' || (select count(*) from srm_asn_line l where l.asn_id = a.asn_id), ',') "
                + "from srm_asn a", String.class)).isEqualTo("SENT:IMPORT:2");
        // 같은 납품서 번호를 다시 → 중복
        long again = upload(fill(List.<Object[]>of(new Object[] {"V100", "DN-X1", "P1", ship, eta, poNo, 1, 1, "L5", null, null, null, null})));
        assertThat(errorCodes(again)).contains("DUPLICATE");
    }

    private byte[] fill(List<Object[]> rows) throws Exception {
        byte[] tpl = mvc.perform(post(IMP + "/templates/SRM_ASN/file").with(csrf()).with(me))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(tpl))) {
            Sheet s = wb.getSheetAt(0);
            int r = 1;
            for (Object[] values : rows) {
                Row row = s.createRow(r++);
                for (int c = 0; c < values.length; c++) {
                    if (values[c] instanceof Number n) {
                        row.createCell(c).setCellValue(n.doubleValue());
                    } else if (values[c] != null) {
                        row.createCell(c).setCellValue(String.valueOf(values[c]));
                    }
                }
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            wb.write(out);
            return out.toByteArray();
        }
    }

    private long upload(byte[] file) throws Exception {
        MvcResult m = mvc.perform(multipart(IMP + "/templates/SRM_ASN/jobs").file(new MockMultipartFile("file", "asn.xlsx", XLSX, file))
                .with(csrf()).with(me)).andExpect(status().isOk()).andReturn();
        return ((Number) JsonPath.read(m.getResponse().getContentAsString(), "$.data.jobId")).longValue();
    }

    private ResultActions execute(long job) throws Exception {
        int ver = accounts.query(comp, "select row_ver from sys_import_job where job_id = ?", Integer.class, job);
        return mvc.perform(post(IMP + "/jobs/" + job + "/execute").with(csrf()).with(me)
                .contentType(MediaType.APPLICATION_JSON).content("{\"rowVer\":" + ver + ",\"skipErrors\":true}"));
    }

    private List<String> errorCodes(long job) throws Exception {
        return JsonPath.read(mvc.perform(get(IMP + "/jobs/" + job + "/rows?status=ERROR").with(me))
                .andReturn().getResponse().getContentAsString(), "$.list[*].errors[*].code");
    }
}
