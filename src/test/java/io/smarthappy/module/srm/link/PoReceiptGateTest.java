package io.smarthappy.module.srm.link;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.IllegalTransactionStateException;

import com.jayway.jsonpath.JsonPath;

import io.smarthappy.module.srm.link.PoReceiptGate.Receipt;
import io.smarthappy.module.srm.link.PoSupplyQuery.Supply;
import io.smarthappy.platform.common.db.PgErrors;
import io.smarthappy.platform.common.web.BusinessException;
import io.smarthappy.platform.common.web.HbErrorCode;
import io.smarthappy.platform.tenant.TenantRunner;
import io.smarthappy.support.PostgresTestBase;
import io.smarthappy.support.TestAccounts;

/**
 * 구매 입고 게이트 (SRM 2부 R1, docs/31 4.9 · WMS docs/30 12.2 D-B3) — 부분·완료·되돌림 상태, 허용 초과율, 되돌림 초과,
 * 수량 자릿수, 상태·ERP 원천 거부, 기본 단위 환산(없으면 거부), 입고 중 개정·취소 금지와 마감, 공급 조회·진행 현황 반영,
 * 직접 SQL(상태만 바꾸기·작성 중 입고·음수), 동시 입고, A사로 B사, 트랜잭션 밖·빈 없음.
 */
@AutoConfigureMockMvc
class PoReceiptGateTest extends PostgresTestBase {

    private static final String PW = "Maple-anchor-63";
    private static final String PO = "/api/srm/pos";
    private static final AtomicInteger REF = new AtomicInteger();

    @Autowired
    MockMvc mvc;

    @Autowired
    TestAccounts accounts;

    @Autowired
    TenantRunner runner;

    @Autowired
    ObjectProvider<PoReceiptGate> provider;

    @Autowired
    ObjectProvider<PoSupplyQuery> supply;

    String comp;
    RequestPostProcessor me;
    long bolt;

    @BeforeEach
    void setUp() {
        comp = accounts.newComp("RG");
        me = buyer(comp);
        seed(comp);
        bolt = accounts.query(comp, "select item_id from mst_item where item_cd = 'BOLT-M8'", Long.class);
    }

    private RequestPostProcessor buyer(String c) {
        long u = accounts.newUser(c, "buyer1", PW);
        accounts.grant(c, u, "PUR", "srm-po", "SEARCH", "SAVE", "APPROVE");
        accounts.grant(c, u, "PUR", "srm-ack", "SEARCH", "SAVE");
        accounts.grant(c, u, "PUR", "srm-po-status", "SEARCH");
        return accounts.as(c, u, "buyer1");
    }

    private void seed(String c) {
        accounts.addPlant(c, "P1", "1공장");
        accounts.exec(c, "insert into sys_policy (comp_cd, policy_key, policy_value, created_by, updated_by) values (?, 'SRM_PO_APPROVAL', 'N', 't', 't')", c);
        accounts.exec(c, """
                insert into mst_item (comp_cd, item_cd, item_nm, item_type, proc_type, unit_cd, lot_ctrl_yn, created_by, updated_by)
                values (?, 'BOLT-M8', '볼트', 'RM', 'BUY', 'EA', 'N', 't', 't'), (?, 'RESIN-A', '수지', 'RM', 'BUY', 'KG', 'N', 't', 't')""", c, c);
        long id = accounts.query(c, """
                insert into mst_cust (comp_cd, cust_cd, cust_nm, cust_type, created_by, updated_by)
                values (?, 'V100', 'V100 상사', 'PURCHASE', 't', 't') returning cust_id""", Long.class, c);
        accounts.exec(c, "insert into srm_supplier (cust_id, comp_cd, currency_cd, created_by, updated_by) values (?, ?, 'KRW', 't', 't')", id, c);
    }

    private ResultActions postAs(RequestPostProcessor who, String url, String body) throws Exception {
        return mvc.perform(post(url).with(csrf()).with(who).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private ResultActions act(RequestPostProcessor who, long id, String action) throws Exception {
        int rv = JsonPath.read(mvc.perform(get(PO + "/" + id).with(who)).andReturn().getResponse().getContentAsString(), "$.data.po.rowVer");
        return postAs(who, PO + "/" + id + "/" + action, "{\"rowVer\":" + rv + (action.equals("cancel") ? ",\"reason\":\"x\"" : "") + "}");
    }

    /** 발행된 발주(행 1: BOLT 100 EA, 행 2: RESIN 2.5 KG, 요구 납기 +20) */
    private long issued(RequestPostProcessor who) throws Exception {
        String due = accounts.query(comp, "select (" + TestAccounts.COMP_TODAY + " + 20)::text", String.class);
        String body = postAs(who, PO, "{\"custCd\":\"V100\",\"plantCd\":\"P1\",\"lines\":[{\"itemCd\":\"BOLT-M8\",\"qty\":\"100\",\"unitPrice\":\"1\",\"dueDate\":\""
                + due + "\"},{\"itemCd\":\"RESIN-A\",\"qty\":\"2.5\",\"unitPrice\":\"1\",\"dueDate\":\"" + due + "\"}]}")
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        long id = ((Number) JsonPath.read(body, "$.data")).longValue();
        act(who, id, "confirm").andExpect(status().isOk());
        act(who, id, "issue").andExpect(status().isOk());
        return id;
    }

    private long line(String c, long poId, int lineNo) {
        return accounts.query(c, "select po_line_id from srm_po_line where po_id = ? and line_no = ?", Long.class, poId, lineNo);
    }

    private String poStatus(long poId) {
        return accounts.query(comp, "select status from srm_po where po_id = ?", String.class, poId);
    }

    private String recvOf(long lineId) {
        return accounts.query(comp, "select trim_scale(recv_qty)::text from srm_po_line where po_line_id = ?", String.class, lineId);
    }

    private Receipt recv(String c, long lineId, String qty) {
        return recvOn(c, lineId, qty, null);
    }

    /** 근거는 호출마다 새로(회사 안 유일 — R2) */
    private Receipt recvOn(String c, long lineId, String qty, LocalDate on) {
        String ref = "TEST:" + lineId + ":" + REF.incrementAndGet();
        return runner.call(c, () -> PoReceiptGate.of(provider).receive(lineId, new BigDecimal(qty), ref, "wms1", on));
    }

    private HbErrorCode fail(String c, long lineId, String qty) {
        Throwable t = catchThrowable(() -> recv(c, lineId, qty));
        assertThat(t).isInstanceOf(BusinessException.class);
        return ((BusinessException) t).code();
    }

    @Test
    void 부분입고_완료_되돌림으로_상태가_따라가고_유효회신이_있으면_ACKED로_돌아간다() throws Exception {
        long po = issued(me);
        long l1 = line(comp, po, 1);
        long l2 = line(comp, po, 2);
        Receipt r = recv(comp, l1, "40");
        assertThat(r.poStatus()).isEqualTo("PARTIAL_RECEIVED");
        assertThat(r.recvQty().toPlainString() + ":" + r.openQty().toPlainString() + ":" + r.baseQty().toPlainString() + ":" + r.baseUnitCd())
                .isEqualTo("40:60:40:EA");
        assertThat(r.poNo()).startsWith("PO");
        assertThat(r.plantCd()).isEqualTo("P1");
        recv(comp, l1, "60");
        assertThat(poStatus(po)).isEqualTo("PARTIAL_RECEIVED");                 // 행 2 가 남음
        assertThat(recv(comp, l2, "2.5").poStatus()).isEqualTo("RECEIVED");
        assertThat(recv(comp, l2, "-0.5").poStatus()).isEqualTo("PARTIAL_RECEIVED");
        recv(comp, l2, "-2");
        assertThat(recv(comp, l1, "-100").poStatus()).isEqualTo("ISSUED");    // 0 이고 회신 없음
        assertThat(recvOf(l1) + ":" + recvOf(l2)).isEqualTo("0:0");
        // 회신(수락) 뒤 받고 다 되돌리면 ACKED 로
        postAs(me, "/api/srm/acks/pos/" + po, "{\"rev\":0,\"lines\":[{\"poLineId\":" + l1 + ",\"promisedDate\":\""
                + accounts.query(comp, "select (" + TestAccounts.COMP_TODAY + " + 5)::text", String.class)
                + "\",\"promisedQty\":\"100\"},{\"poLineId\":" + l2 + ",\"promisedDate\":\""
                + accounts.query(comp, "select (" + TestAccounts.COMP_TODAY + " + 5)::text", String.class) + "\",\"promisedQty\":\"2.5\"}]}")
                .andExpect(status().isOk());
        assertThat(poStatus(po)).isEqualTo("ACKED");
        recv(comp, l1, "30");
        // 공급 조회: 받은 30 을 약속에서 지우고 남은 70, 진행 현황: 열린 70 · 입고 30
        List<Supply> s = runner.call(comp, () -> PoSupplyQuery.of(supply).open("P1", List.of(bolt), null));
        assertThat(s).extracting(x -> x.qty().toPlainString() + ":" + x.promised()).containsExactly("70:true");
        String today = accounts.query(comp, "select (" + TestAccounts.COMP_TODAY + ")::text", String.class);
        mvc.perform(get("/api/srm/po-status").param("from", today).param("to", today).with(me)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data[?(@.lineNo == 1)].openQty").value("70"))
                .andExpect(jsonPath("$.data[?(@.lineNo == 1)].recvQty").value("30"));
        assertThat(recv(comp, l1, "-30").poStatus()).isEqualTo("ACKED");
        assertThat(accounts.query(comp, "select count(*) from sys_audit_log where table_nm = 'srm_po_line' and row_key = ?", Long.class, String.valueOf(l1)))
                .isGreaterThan(0);
    }

    @Test
    void 허용초과율_되돌림초과_자릿수_상태와_ERP원천은_거부되고_아무것도_바뀌지_않는다() throws Exception {
        long po = issued(me);
        long l1 = line(comp, po, 1);
        assertThat(fail(comp, l1, "101")).isEqualTo(HbErrorCode.SRM_RECV_OVER);   // 기본 0%
        Throwable t = catchThrowable(() -> recv(comp, l1, "101"));
        assertThat(((BusinessException) t).args()).containsExactly("100");
        accounts.exec(comp, "insert into sys_policy (comp_cd, policy_key, policy_value, created_by, updated_by) values (?, 'SRM_RECV_OVER_PCT', '10', 't', 't')", comp);
        recv(comp, l1, "110");
        assertThat(poStatus(po)).isEqualTo("PARTIAL_RECEIVED");                  // 행 2 는 0
        assertThat(fail(comp, l1, "1")).isEqualTo(HbErrorCode.SRM_RECV_OVER);
        assertThat(fail(comp, l1, "-111")).isEqualTo(HbErrorCode.SRM_RECV_REVERSE);
        assertThat(fail(comp, l1, "-0.5")).isEqualTo(HbErrorCode.INVALID_VALUE);    // EA 자릿수 0
        assertThat(fail(comp, line(comp, po, 2), "0.0001")).isEqualTo(HbErrorCode.INVALID_VALUE); // KG 자릿수 3
        assertThat(recvOf(l1)).isEqualTo("110");
        // 입고 중 발주는 개정·취소 불가, 마감은 된다 → 마감 뒤 입고 거부·되돌림은 되고 상태는 마감 그대로
        act(me, po, "revise").andExpect(status().isConflict());
        act(me, po, "cancel").andExpect(status().isConflict());
        act(me, po, "close").andExpect(status().isOk());
        assertThat(fail(comp, l1, "1")).isEqualTo(HbErrorCode.SRM_STATE);
        assertThat(recv(comp, l1, "-10").poStatus()).isEqualTo("CLOSED");
        // 작성 중 발주
        String body = postAs(me, PO, "{\"custCd\":\"V100\",\"plantCd\":\"P1\",\"lines\":[{\"itemCd\":\"BOLT-M8\",\"qty\":\"5\",\"unitPrice\":\"1\",\"dueDate\":\""
                + accounts.query(comp, "select (" + TestAccounts.COMP_TODAY + " + 3)::text", String.class) + "\"}]}")
                .andReturn().getResponse().getContentAsString();
        long draft = ((Number) JsonPath.read(body, "$.data")).longValue();
        assertThat(fail(comp, line(comp, draft, 1), "1")).isEqualTo(HbErrorCode.SRM_STATE);
        assertThat(fail(comp, line(comp, draft, 1), "-1")).isEqualTo(HbErrorCode.SRM_STATE);
        // ERP 원천 발주(직접 SQL 로 발행 상태까지) — 입고는 ERP 가 주인
        long user = accounts.query(comp, "select user_id from sys_user where login_id = 'buyer1'", Long.class);
        long erp = accounts.query(comp, """
                insert into srm_po (comp_cd, po_no, po_date, cust_id, plant_cd, currency_cd, buyer_user_id, source, created_by, updated_by)
                values (?, 'PO-ERP-1', %s, (select cust_id from mst_cust where cust_cd = 'V100'), 'P1', 'KRW', ?, 'ERP', 't', 't')
                returning po_id""".formatted(TestAccounts.COMP_TODAY), Long.class, comp, user);
        accounts.exec(comp, """
                insert into srm_po_line (comp_cd, po_id, line_no, item_id, item_nm, unit_cd, qty, unit_price, amt, due_date, created_by, updated_by)
                values (?, ?, 1, ?, '볼트', 'EA', 10, 1, 10, %s + 3, 't', 't')""".formatted(TestAccounts.COMP_TODAY), comp, erp, bolt);
        accounts.exec(comp, "update srm_po set status = 'APPROVED' where po_id = ?", erp);
        accounts.exec(comp, "update srm_po set status = 'ISSUED' where po_id = ?", erp);
        assertThat(fail(comp, line(comp, erp, 1), "1")).isEqualTo(HbErrorCode.SRM_ERP_OWNED);
        assertThat(recvOf(line(comp, erp, 1))).isEqualTo("0");
    }

    @Test
    void 발주단위가_기본단위와_다르면_환산해_돌려주고_환산이_없거나_기본단위_자릿수를_넘으면_거부() throws Exception {
        long po = issued(me);
        long resin = accounts.query(comp, "select item_id from mst_item where item_cd = 'RESIN-A'", Long.class);
        // 볼트 기본 단위를 BOX(자릿수 0)로(발주 행은 EA 스냅샷), EA → BOX 0.01 / 수지는 BOX 로 바꾸고 환산 없음
        accounts.exec(comp, "update mst_item set unit_cd = 'BOX' where item_id in (?, ?)", bolt, resin);
        accounts.exec(comp, "insert into mst_unit_conv (comp_cd, item_id, from_unit, to_unit, factor, created_by, updated_by) "
                + "values (?, ?, 'EA', 'BOX', 0.01, 't', 't')", comp, bolt);
        Receipt r = recv(comp, line(comp, po, 1), "100");
        assertThat(r.qty().toPlainString() + " " + r.poUnitCd() + " = " + r.baseQty().toPlainString() + " " + r.baseUnitCd()).isEqualTo("100 EA = 1 BOX");
        assertThat(recv(comp, line(comp, po, 1), "-100").baseQty().toPlainString()).isEqualTo("-1");
        // 40 EA = 0.4 BOX — 기본 단위 자릿수 0 을 넘음: 반올림하지 않고 원장보다 먼저 거부, 입고 그대로
        Throwable t = catchThrowable(() -> recv(comp, line(comp, po, 1), "40"));
        assertThat(((BusinessException) t).code()).isEqualTo(HbErrorCode.SRM_RECV_BASE_SCALE);
        assertThat(((BusinessException) t).args()).containsExactly("40", "EA", "0.4", "BOX", 0);
        assertThat(recvOf(line(comp, po, 1))).isEqualTo("0");
        t = catchThrowable(() -> recv(comp, line(comp, po, 2), "1"));
        assertThat(((BusinessException) t).code()).isEqualTo(HbErrorCode.SRM_RECV_NO_CONV);
        assertThat(((BusinessException) t).args()).containsExactly("KG", "BOX");
        assertThat(recvOf(line(comp, po, 2))).isEqualTo("0");
        assertThat(poStatus(po)).isEqualTo("ISSUED");
    }

    @Test
    void 큰_발주단위가_나누어떨어지지_않는_기본단위_수량이_되면_409_입고수량_그대로() throws Exception {
        // 볼트를 BOX 로 발주(그때 기본 단위 BOX → 행 단위 스냅샷 BOX), 뒤에 기본 단위 EA·환산 1 BOX = 2.5 EA
        // — 1 BOX 는 2.5 EA(EA 자릿수 0) → 거부, 2 BOX = 5 EA 는 통과
        accounts.exec(comp, "update mst_item set unit_cd = 'BOX' where item_id = ?", bolt);
        String due = accounts.query(comp, "select (" + TestAccounts.COMP_TODAY + " + 20)::text", String.class);
        String body = postAs(me, PO, "{\"custCd\":\"V100\",\"plantCd\":\"P1\",\"lines\":[{\"itemCd\":\"BOLT-M8\",\"qty\":\"10\",\"unitPrice\":\"1\",\"dueDate\":\""
                + due + "\"}]}").andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        long po = ((Number) JsonPath.read(body, "$.data")).longValue();
        act(me, po, "confirm").andExpect(status().isOk());
        act(me, po, "issue").andExpect(status().isOk());
        accounts.exec(comp, "update mst_item set unit_cd = 'EA' where item_id = ?", bolt);
        accounts.exec(comp, "insert into mst_unit_conv (comp_cd, item_id, from_unit, to_unit, factor, created_by, updated_by) "
                + "values (?, ?, 'BOX', 'EA', 2.5, 't', 't')", comp, bolt);
        long l1 = line(comp, po, 1);
        assertThat(accounts.query(comp, "select unit_cd from srm_po_line where po_line_id = ?", String.class, l1)).isEqualTo("BOX");
        assertThat(fail(comp, l1, "1")).isEqualTo(HbErrorCode.SRM_RECV_BASE_SCALE);
        assertThat(recvOf(l1)).isEqualTo("0");
        assertThat(poStatus(po)).isEqualTo("ISSUED");
        Receipt r = recv(comp, l1, "2");
        assertThat(r.baseQty().toPlainString() + " " + r.baseUnitCd() + ":" + r.recvQty().toPlainString()).isEqualTo("5 EA:2");
        assertThat(fail(comp, l1, "-1")).isEqualTo(HbErrorCode.SRM_RECV_BASE_SCALE);   // 되돌림도 같은 규칙
        assertThat(recvOf(l1)).isEqualTo("2");
    }

    @Test
    void 직접SQL_행과_맞지_않는_상태_작성중_입고_음수_입고있는_행_삭제는_DB가_막는다() throws Exception {
        long po = issued(me);
        long l1 = line(comp, po, 1);
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(comp,
                "update srm_po set status = 'RECEIVED' where po_id = ?", po)))).contains("ck_srm_po_recv_status");
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(comp,
                "update srm_po set status = 'PARTIAL_RECEIVED' where po_id = ?", po)))).contains("ck_srm_po_recv_status");
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(comp,
                "update srm_po_line set recv_qty = -1 where po_line_id = ?", l1)))).contains("ck_srm_po_line_recv");
        // (R2) 합계 = 이력: 이력 없이 입고 수량만 / 입고 수량 없이 이력만 → 커밋 때 거부
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(comp,
                "update srm_po_line set recv_qty = 1 where po_line_id = ?", l1)))).contains("ck_srm_po_receipt_sum");
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(comp,
                "insert into srm_po_receipt (comp_cd, po_id, po_line_id, occurred_on, qty, trx_ref, created_by) values (?, ?, ?, "
                        + TestAccounts.COMP_TODAY + ", 1, 'SQL-1', 't')", comp, po, l1)))).contains("ck_srm_po_receipt_sum");
        // 다른 발주의 행으로 이력
        long po2 = issued(me);
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(comp,
                "insert into srm_po_receipt (comp_cd, po_id, po_line_id, occurred_on, qty, trx_ref, created_by) values (?, ?, ?, "
                        + TestAccounts.COMP_TODAY + ", 1, 'SQL-2', 't')", comp, po2, l1)))).contains("fk_srm_po_receipt_line_comp");
        // 게이트로 다 받고 머리를 PARTIAL 로 — 행과 안 맞음 / 이력 고치기·지우기
        recv(comp, l1, "100");
        recv(comp, line(comp, po, 2), "2.5");
        assertThat(poStatus(po)).isEqualTo("RECEIVED");
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(comp,
                "update srm_po set status = 'PARTIAL_RECEIVED' where po_id = ?", po)))).contains("ck_srm_po_recv_status");
        assertThat(catchThrowable(() -> accounts.exec(comp, "update srm_po_receipt set qty = 1"))).rootCause()
                .hasMessageContaining("permission denied");
        assertThat(catchThrowable(() -> accounts.exec(comp, "delete from srm_po_receipt"))).rootCause()
                .hasMessageContaining("permission denied");
        // 입고 상태에서 회신 상태로(받은 게 남아 있음), 개정(작성 중으로)
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(comp,
                "update srm_po set status = 'ISSUED' where po_id = ?", po)))).contains("ck_srm_po_recv_status");
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(comp,
                "update srm_po set status = 'DRAFT', rev = rev + 1 where po_id = ?", po)))).contains("ck_srm_po_transition");
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(comp,
                "update srm_po set status = 'CANCELLED', cancel_reason = 'x' where po_id = ?", po)))).contains("ck_srm_po_transition");
        // 마감된 발주는 입고 수량이 늘 수 없다
        accounts.exec(comp, "update srm_po set status = 'CLOSED' where po_id = ?", po);
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(comp,
                "update srm_po_line set recv_qty = recv_qty + 1 where po_line_id = ?", l1)))).contains("ck_srm_po_line_recv_state");
        // 작성 중 발주 행: 입고 수량 넣기·새 행에 입고 수량
        String body = postAs(me, PO, "{\"custCd\":\"V100\",\"plantCd\":\"P1\",\"lines\":[{\"itemCd\":\"BOLT-M8\",\"qty\":\"5\",\"unitPrice\":\"1\",\"dueDate\":\""
                + accounts.query(comp, "select (" + TestAccounts.COMP_TODAY + " + 3)::text", String.class) + "\"}]}")
                .andReturn().getResponse().getContentAsString();
        long draft = ((Number) JsonPath.read(body, "$.data")).longValue();
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(comp,
                "update srm_po_line set recv_qty = 1 where po_id = ?", draft)))).contains("ck_srm_po_line_recv_state");
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(comp, """
                insert into srm_po_line (comp_cd, po_id, line_no, item_id, item_nm, unit_cd, qty, unit_price, amt, due_date, recv_qty, created_by, updated_by)
                values (?, ?, 2, ?, '볼트', 'EA', 1, 1, 1, %s + 3, 1, 't', 't')""".formatted(TestAccounts.COMP_TODAY), comp, draft, bolt))))
                .contains("ck_srm_po_line_recv_state");
    }

    @Test
    void 같은_행을_동시에_받으면_잠금으로_순서대로_하나만_넘지_않는다() throws Exception {
        long po = issued(me);
        long l1 = line(comp, po, 1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Object>> fs = new ArrayList<>();
        try {
            for (int i = 0; i < 2; i++) {
                fs.add(pool.submit(() -> {
                    go.await();
                    try {
                        return recv(comp, l1, "60");
                    } catch (BusinessException e) {
                        return e.code();
                    }
                }));
            }
            go.countDown();
            List<Object> out = new ArrayList<>();
            for (Future<Object> f : fs) {
                out.add(f.get(30, TimeUnit.SECONDS));
            }
            assertThat(out).filteredOn(Receipt.class::isInstance).hasSize(1);
            assertThat(out).filteredOn(HbErrorCode.SRM_RECV_OVER::equals).hasSize(1);
        } finally {
            pool.shutdownNow();
        }
        assertThat(recvOf(l1)).isEqualTo("60");
        assertThat(poStatus(po)).isEqualTo("PARTIAL_RECEIVED");
    }

    @Test
    void A사로_B사_발주행에_입고할_수_없고_트랜잭션_밖과_빈_없음은_거부() throws Exception {
        long po = issued(me);
        long l1 = line(comp, po, 1);
        String other = accounts.newComp("RB");
        buyer(other);
        seed(other);
        assertThat(fail(other, l1, "10")).isEqualTo(HbErrorCode.NOT_FOUND);
        assertThat(fail(other, l1, "-10")).isEqualTo(HbErrorCode.NOT_FOUND);
        assertThat(recvOf(l1)).isEqualTo("0");
        assertThat(poStatus(po)).isEqualTo("ISSUED");
        // 부르는 쪽 버그: 0·근거 없음
        assertThatThrownBy(() -> recv(comp, l1, "0")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> runner.call(comp, () -> PoReceiptGate.of(provider).receive(l1, BigDecimal.ONE, " ", "wms1")))
                .isInstanceOf(IllegalArgumentException.class);
        // 트랜잭션 밖(MANDATORY)
        assertThatThrownBy(() -> PoReceiptGate.of(provider).receive(l1, BigDecimal.ONE, "TEST", "wms1"))
                .isInstanceOf(IllegalTransactionStateException.class);
        // SRM 빈이 없는 설치본 → NONE 은 거부
        ObjectProvider<PoReceiptGate> none = new StaticListableBeanFactory().getBeanProvider(PoReceiptGate.class);
        assertThat(PoReceiptGate.of(none)).isSameAs(PoReceiptGate.NONE);
        assertThatThrownBy(() -> PoReceiptGate.NONE.receive(l1, BigDecimal.ONE, "TEST", "wms1")).isInstanceOf(IllegalStateException.class);
        assertThat(PoReceiptGate.of(provider)).isInstanceOf(SrmPoReceiptGate.class);
    }
}
