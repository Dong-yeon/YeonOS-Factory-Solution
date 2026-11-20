package io.smarthappy.module.srm.link;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.jayway.jsonpath.JsonPath;

import io.smarthappy.module.srm.link.PoReceiptGate.Receipt;
import io.smarthappy.platform.common.db.PgErrors;
import io.smarthappy.platform.common.web.BusinessException;
import io.smarthappy.platform.common.web.HbErrorCode;
import io.smarthappy.platform.tenant.TenantRunner;
import io.smarthappy.platform.time.BizClock;
import io.smarthappy.support.PostgresTestBase;
import io.smarthappy.support.TestAccounts;

/**
 * 입고 이력 + 평가 자동 지표 OTD_RATE (SRM 2부 R2, docs/31 4.10) — 발생일 규칙(오늘·과거·미래·발주일 앞), 근거 중복,
 * 되돌림은 그 입고의 발생일로(그날 순입고 초과 거부), 납기 준수율 손 검산(적시·지연·되돌린 뒤 늦은 재입고·미입고·납기 전 행·ERP 원천 제외),
 * A사로 B사.
 */
@AutoConfigureMockMvc
class PoReceiptLogTest extends PostgresTestBase {

    private static final String PW = "Maple-anchor-63";
    private static final String PO = "/api/srm/pos";
    private static final String T = "/api/srm/eval-tmpls";
    private static final String E = "/api/srm/evals";
    private static final AtomicInteger REF = new AtomicInteger();

    @Autowired
    MockMvc mvc;

    @Autowired
    TestAccounts accounts;

    @Autowired
    TenantRunner runner;

    @Autowired
    BizClock clock;

    @Autowired
    ObjectProvider<PoReceiptGate> provider;

    String comp;
    RequestPostProcessor me;
    LocalDate today;
    long vCust;

    @BeforeEach
    void setUp() {
        comp = accounts.newComp("RL");
        me = buyer(comp);
        vCust = seed(comp);
        today = LocalDate.now(ZoneId.of("Asia/Seoul"));
    }

    @AfterEach
    void resetClock() {
        clock.setClock(Clock.systemUTC());
    }

    private RequestPostProcessor buyer(String c) {
        long u = accounts.newUser(c, "buyer1", PW);
        accounts.grant(c, u, "PUR", "srm-po", "SEARCH", "SAVE", "APPROVE");
        accounts.grant(c, u, "PUR", "srm-eval-tmpl", "SEARCH", "SAVE", "APPROVE");
        accounts.grant(c, u, "PUR", "srm-eval", "SEARCH", "SAVE", "APPROVE");
        return accounts.as(c, u, "buyer1");
    }

    private long seed(String c) {
        accounts.addPlant(c, "P1", "1공장");
        accounts.exec(c, "insert into sys_policy (comp_cd, policy_key, policy_value, created_by, updated_by) values (?, 'SRM_PO_APPROVAL', 'N', 't', 't')", c);
        accounts.exec(c, """
                insert into mst_item (comp_cd, item_cd, item_nm, item_type, proc_type, unit_cd, lot_ctrl_yn, created_by, updated_by)
                values (?, 'BOLT-M8', '볼트', 'RM', 'BUY', 'EA', 'N', 't', 't'), (?, 'RESIN-A', '수지', 'RM', 'BUY', 'KG', 'N', 't', 't')""", c, c);
        long id = accounts.query(c, """
                insert into mst_cust (comp_cd, cust_cd, cust_nm, cust_type, created_by, updated_by)
                values (?, 'V100', 'V100 상사', 'PURCHASE', 't', 't') returning cust_id""", Long.class, c);
        accounts.exec(c, "insert into srm_supplier (cust_id, comp_cd, currency_cd, created_by, updated_by) values (?, ?, 'KRW', 't', 't')", id, c);
        return id;
    }

    private ResultActions postAs(String url, String body) throws Exception {
        return mvc.perform(post(url).with(csrf()).with(me).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private String body(String url) throws Exception {
        return mvc.perform(get(url).with(me)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    private static long id(ResultActions r) throws Exception {
        return ((Number) JsonPath.read(r.andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.data")).longValue();
    }

    /** 발주일 = 오늘 − daysAgo 로 만들어 발행(시계를 돌렸다 되돌림). 행 1 BOLT 100 EA, 행 2 RESIN 2.5 KG, 요구 납기 = 각 due */
    private long issued(int daysAgo, LocalDate due1, LocalDate due2) throws Exception {
        clock.setClock(Clock.offset(Clock.systemUTC(), Duration.ofDays(-daysAgo)));
        try {
            long id = id(postAs(PO, "{\"custCd\":\"V100\",\"plantCd\":\"P1\",\"lines\":[{\"itemCd\":\"BOLT-M8\",\"qty\":\"100\",\"unitPrice\":\"1\",\"dueDate\":\""
                    + due1 + "\"},{\"itemCd\":\"RESIN-A\",\"qty\":\"2.5\",\"unitPrice\":\"1\",\"dueDate\":\"" + due2 + "\"}]}"));
            for (String a : List.of("confirm", "issue")) {
                int v = JsonPath.read(body(PO + "/" + id), "$.data.po.rowVer");
                postAs(PO + "/" + id + "/" + a, "{\"rowVer\":" + v + "}").andExpect(status().isOk());
            }
            return id;
        } finally {
            clock.setClock(Clock.systemUTC());
        }
    }

    private long line(long poId, int lineNo) {
        return accounts.query(comp, "select po_line_id from srm_po_line where po_id = ? and line_no = ?", Long.class, poId, lineNo);
    }

    private Receipt recv(String c, long lineId, String qty, LocalDate on) {
        String ref = "LOG:" + lineId + ":" + REF.incrementAndGet();
        return recvRef(c, lineId, qty, on, ref);
    }

    private Receipt recvRef(String c, long lineId, String qty, LocalDate on, String ref) {
        return runner.call(c, () -> PoReceiptGate.of(provider).receive(lineId, new BigDecimal(qty), ref, "wms1", on));
    }

    private HbErrorCode fail(Runnable r) {
        Throwable t = catchThrowable(r::run);
        assertThat(t).isInstanceOf(BusinessException.class);
        return ((BusinessException) t).code();
    }

    private String log(long lineId) {
        return accounts.query(comp, "select string_agg(occurred_on || ' ' || trim_scale(qty), ', ' order by receipt_id) from srm_po_receipt where po_line_id = ?",
                String.class, lineId);
    }

    @Test
    void 이력_발생일_규칙_근거중복_되돌림은_그날_순입고까지_합계는_이력과_같다() throws Exception {
        long po = issued(10, today.plusDays(5), today.plusDays(5));
        long l1 = line(po, 1);
        LocalDate d7 = today.minusDays(7);
        assertThat(recv(comp, l1, "30", null).occurredOn()).isEqualTo(today);          // 비우면 공장 오늘
        assertThat(recv(comp, l1, "50", d7).occurredOn()).isEqualTo(d7);
        assertThat(fail(() -> recv(comp, l1, "1", today.plusDays(1)))).isEqualTo(HbErrorCode.INVALID_VALUE);   // 미래
        assertThat(fail(() -> recv(comp, l1, "1", today.minusDays(11)))).isEqualTo(HbErrorCode.INVALID_VALUE); // 발주일 앞
        // 같은 근거 두 번 → DUPLICATE, 아무것도 안 바뀜
        recvRef(comp, l1, "5", null, "RCV_PO:77");
        assertThat(fail(() -> recvRef(comp, l1, "5", null, "RCV_PO:77"))).isEqualTo(HbErrorCode.DUPLICATE);
        // 되돌림: d7 에 50 받았으니 d7 로 −60 은 거부(전체 85 는 넉넉해도), −50 은 됨 / 오늘 날짜로 −40 은 오늘 순입고 35 초과
        assertThat(fail(() -> recv(comp, l1, "-60", d7))).isEqualTo(HbErrorCode.SRM_RECV_REVERSE);
        assertThat(fail(() -> recv(comp, l1, "-40", null))).isEqualTo(HbErrorCode.SRM_RECV_REVERSE);
        recv(comp, l1, "-50", d7);
        assertThat(log(l1)).isEqualTo(today + " 30, " + d7 + " 50, " + today + " 5, " + d7 + " -50");
        assertThat(accounts.query(comp, "select trim_scale(recv_qty)::text from srm_po_line where po_line_id = ?", String.class, l1)).isEqualTo("35");
        // A사로 B사: B사 범위에서 A사 이력은 안 보이고, 같은 근거 문자열도 B사에서는 따로(회사 안 유일)
        String other = accounts.newComp("RM");
        assertThat(accounts.query(other, "select count(*) from srm_po_receipt", Long.class)).isZero();
        assertThat(fail(() -> recvRef(other, l1, "1", null, "RCV_PO:78"))).isEqualTo(HbErrorCode.NOT_FOUND);
        assertThat(log(l1)).doesNotContain("RCV_PO:78");
    }

    private long lot(String c, String lotNo) {
        return accounts.query(c, """
                insert into lot_master (comp_cd, lot_no, item_id, origin, qty_created, unit_cd, occurred_at, created_by, updated_by)
                select ?, ?, item_id, 'RECEIVED', 10, 'EA', now(), 't', 't' from mst_item where item_cd = 'BOLT-M8' returning lot_id""",
                Long.class, c, lotNo);
    }

    private void attach(String c, String ref, long lotId) {
        runner.run(c, () -> PoReceiptGate.of(provider).attachLot(ref, lotId));
    }

    private String lotOf(String ref) {
        return accounts.query(comp, "select coalesce(lot_id::text, '-') from srm_po_receipt where trx_ref = ?", String.class, ref);
    }

    private static String constraint(Runnable r) {
        Throwable t = catchThrowable(r::run);
        assertThat(t).isNotNull();
        return PgErrors.constraint(t).orElse(t.getMessage());
    }

    @Test
    void 받은_LOT_는_입고_이력에_한_번만_되돌림은_원래_입고의_LOT_를_받고_없는_근거는_409() throws Exception {
        long po = issued(1, today.plusDays(5), today.plusDays(5));
        long l1 = line(po, 1);
        long lotA = lot(comp, "LA-1");
        long lotB = lot(comp, "LA-2");
        recvRef(comp, l1, "10", null, "RCV_PO:L1");
        attach(comp, "RCV_PO:L1", lotA);
        assertThat(lotOf("RCV_PO:L1")).isEqualTo(String.valueOf(lotA));
        // 두 번째(다른 LOT 로 덮기) → 409, 처음 값 그대로
        assertThat(fail(() -> attach(comp, "RCV_PO:L1", lotB))).isEqualTo(HbErrorCode.SRM_STATE);
        assertThat(lotOf("RCV_PO:L1")).isEqualTo(String.valueOf(lotA));
        // 없는 근거 → 409(조용히 넘어가지 않음)
        assertThat(fail(() -> attach(comp, "RCV_PO:NOPE", lotA))).isEqualTo(HbErrorCode.SRM_STATE);
        // 되돌림 행은 원래 입고의 LOT 를 받고, attachLot 대상이 아니다
        runner.run(comp, () -> PoReceiptGate.of(provider).reverseAt(l1, new BigDecimal("-4"), "RCV_POR:L1", "wms1", null, "RCV_PO:L1"));
        assertThat(lotOf("RCV_POR:L1")).isEqualTo(String.valueOf(lotA));
        assertThat(fail(() -> attach(comp, "RCV_POR:L1", lotB))).isEqualTo(HbErrorCode.SRM_STATE);
        // LOT 없는 옛 입고(이 판 전)를 되돌리면 되돌림 행도 비어 있다
        recvRef(comp, l1, "3", null, "RCV_PO:OLD");
        runner.run(comp, () -> PoReceiptGate.of(provider).reverseAt(l1, new BigDecimal("-3"), "RCV_POR:OLD", "wms1", null, "RCV_PO:OLD"));
        assertThat(lotOf("RCV_POR:OLD")).isEqualTo("-");
    }

    @Test
    void 직접_SQL_은_LOT_한_번_채우기만_되고_다른_칸_바꾸기_LOT_바꾸기_지우기_다른_회사_LOT_는_DB가_막는다() throws Exception {
        long po = issued(1, today.plusDays(5), today.plusDays(5));
        long l1 = line(po, 1);
        long lotA = lot(comp, "LS-1");
        recvRef(comp, l1, "10", null, "RCV_PO:S1");
        // 다른 회사 LOT
        String other = accounts.newComp("RN");
        accounts.addPlant(other, "P1", "1공장");
        accounts.exec(other, """
                insert into mst_item (comp_cd, item_cd, item_nm, item_type, proc_type, unit_cd, lot_ctrl_yn, created_by, updated_by)
                values (?, 'BOLT-M8', '볼트', 'RM', 'BUY', 'EA', 'N', 't', 't')""", other);
        long foreign = lot(other, "LN-1");
        assertThat(constraint(() -> accounts.exec(comp, "update srm_po_receipt set lot_id = ? where trx_ref = 'RCV_PO:S1'", foreign)))
                .isEqualTo("fk_srm_po_receipt_lot_comp");
        assertThat(constraint(() -> attach(comp, "RCV_PO:S1", foreign))).isEqualTo("fk_srm_po_receipt_lot_comp");   // 게이트로도
        assertThat(lotOf("RCV_PO:S1")).isEqualTo("-");
        // 수량 같이 바꾸기 → 권한(칸 단위 GRANT) 또는 트리거
        assertThat(catchThrowable(() -> accounts.exec(comp, "update srm_po_receipt set lot_id = ?, qty = 99 where trx_ref = 'RCV_PO:S1'", lotA)))
                .isNotNull();
        accounts.exec(comp, "update srm_po_receipt set lot_id = ? where trx_ref = 'RCV_PO:S1'", lotA);   // 한 번 채우기는 됨
        long lotB = lot(comp, "LS-2");
        assertThat(constraint(() -> accounts.exec(comp, "update srm_po_receipt set lot_id = ? where trx_ref = 'RCV_PO:S1'", lotB)))
                .isEqualTo("ck_srm_po_receipt_append_only");
        assertThat(constraint(() -> accounts.exec(comp, "update srm_po_receipt set lot_id = null where trx_ref = 'RCV_PO:S1'")))
                .isEqualTo("ck_srm_po_receipt_append_only");
        assertThat(catchThrowable(() -> accounts.exec(comp, "delete from srm_po_receipt where trx_ref = 'RCV_PO:S1'"))).isNotNull();
        assertThat(lotOf("RCV_PO:S1")).isEqualTo(String.valueOf(lotA));
        // A사로 B사: B사 범위에서 A사 근거로 attachLot → 안 보임(409), A사 이력 그대로
        assertThat(fail(() -> attach(other, "RCV_PO:S1", foreign))).isEqualTo(HbErrorCode.SRM_STATE);
        assertThat(lotOf("RCV_PO:S1")).isEqualTo(String.valueOf(lotA));
    }

    @Test
    void 평가_OTD_RATE_손검산_적시_지연_되돌린뒤_늦은재입고_미입고_납기전과_ERP원천은_제외() throws Exception {
        // 템플릿: 품질(사람 10점, 50) + 납기 준수(AUTO OTD_RATE, 20점, 50)
        long t = id(postAs(T, "{\"tmplCd\":\"OTD\",\"tmplNm\":\"OTD 평가\",\"validFrom\":\"2020-01-01\",\"items\":["
                + "{\"sectionNm\":\"품질\",\"itemNm\":\"불량 대응\",\"maxScore\":\"10\",\"weight\":\"50\"},"
                + "{\"sectionNm\":\"납기\",\"itemNm\":\"납기 준수\",\"maxScore\":\"20\",\"weight\":\"50\",\"inputType\":\"AUTO\",\"metricCd\":\"OTD_RATE\"}],"
                + "\"grades\":[{\"gradeCd\":\"A\",\"minScore\":\"80\"},{\"gradeCd\":\"D\",\"minScore\":\"0\"}]}"));
        int v = JsonPath.read(body(T + "/" + t), "$.data.tmpl.rowVer");
        postAs(T + "/" + t + "/release", "{\"rowVer\":" + v + "}").andExpect(status().isOk());
        // 발주 1(30일 전): 행 1 납기 −20 → −22 에 다 받음(적시) / 행 2 납기 −20 → −15 에 다 받음(지연)
        long p1 = issued(30, today.minusDays(20), today.minusDays(20));
        recv(comp, line(p1, 1), "60", today.minusDays(25));
        recv(comp, line(p1, 1), "40", today.minusDays(22));
        recv(comp, line(p1, 2), "2.5", today.minusDays(15));
        // 발주 2(30일 전): 행 1 납기 −18 → −19 에 받았다가 같은 날짜로 되돌리고 −10 에 다시(지연) / 행 2 납기 −18 미입고(지연)
        long p2 = issued(30, today.minusDays(18), today.minusDays(18));
        recv(comp, line(p2, 1), "100", today.minusDays(19));
        recv(comp, line(p2, 1), "-100", today.minusDays(19));
        recv(comp, line(p2, 1), "100", today.minusDays(10));
        // 발주 3: 납기가 아직 안 옴(+5) — 세지 않음
        issued(3, today.plusDays(5), today.plusDays(5));
        // ERP 원천 발주(직접 SQL, 납기 −5 미입고) — 세지 않음(게이트를 지나지 않음)
        long user = accounts.query(comp, "select user_id from sys_user where login_id = 'buyer1'", Long.class);
        long erp = accounts.query(comp, """
                insert into srm_po (comp_cd, po_no, po_date, cust_id, plant_cd, currency_cd, buyer_user_id, source, created_by, updated_by)
                values (?, 'PO-ERP-1', %s - 10, ?, 'P1', 'KRW', ?, 'ERP', 't', 't') returning po_id""".formatted(TestAccounts.COMP_TODAY),
                Long.class, comp, vCust, user);
        accounts.exec(comp, """
                insert into srm_po_line (comp_cd, po_id, line_no, item_id, item_nm, unit_cd, qty, unit_price, amt, due_date, created_by, updated_by)
                values (?, ?, 1, (select item_id from mst_item where item_cd = 'BOLT-M8'), '볼트', 'EA', 10, 1, 10, %s - 5, 't', 't')"""
                .formatted(TestAccounts.COMP_TODAY), comp, erp);
        accounts.exec(comp, "update srm_po set status = 'APPROVED' where po_id = ?", erp);
        accounts.exec(comp, "update srm_po set status = 'ISSUED' where po_id = ?", erp);
        // 기간 = 40일 전 ~ 오늘(기간 끝은 오늘 이하): 대상 4행, 적시 1 → 25% → 20 × 0.25 = 5
        long ev = id(postAs(E, "{\"custCd\":\"V100\",\"tmplCd\":\"OTD\",\"periodFrom\":\"" + today.minusDays(40) + "\",\"periodTo\":\""
                + today + "\"}"));
        String e = body(E + "/" + ev);
        assertThat((String) JsonPath.read(e, "$.data.scores[1].basis")).isEqualTo("OTD:4:1");
        assertThat((String) JsonPath.read(e, "$.data.scores[1].score")).isEqualTo("5");
        assertThat((String) JsonPath.read(e, "$.data.scores[1].rawValue")).isEqualTo("25");
        assertThat((String) JsonPath.read(e, "$.data.scores[1].autoYn")).isEqualTo("Y");
        // 기간에 납기가 온 행이 없음(아주 옛날) → 사람 입력
        long ev2 = id(postAs(E, "{\"custCd\":\"V100\",\"tmplCd\":\"OTD\",\"periodFrom\":\"2021-01-01\",\"periodTo\":\"2021-12-31\"}"));
        String e2 = body(E + "/" + ev2);
        assertThat((String) JsonPath.read(e2, "$.data.scores[1].basis")).isEqualTo("OTD_NONE");
        assertThat((String) JsonPath.read(e2, "$.data.scores[1].autoYn")).isEqualTo("N");
    }
}
