package io.smarthappy.module.srm.po;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.jayway.jsonpath.JsonPath;

import io.smarthappy.platform.common.db.PgErrors;
import io.smarthappy.support.PostgresTestBase;
import io.smarthappy.support.TestAccounts;

/**
 * SRM S3 발주 (docs/31) — 한 벌 저장·단가 기본값·금액 반올림(통화 자릿수)·스냅샷, PR 남은 수량(잠금·동시), 결재 SRM_PO(상신·승인·반려·회수),
 * 결재 없는 확정(정책·기준 금액·외화), 발행 스냅샷·개정, 취소·마감, 거래 정지, ERP 주인(D1), A사로 B사, 권한.
 */
@AutoConfigureMockMvc
class PoTest extends PostgresTestBase {

    private static final String PW = "Maple-anchor-63";
    private static final String API = "/api/srm/pos";
    private static final String PR = "/api/srm/prs";

    @Autowired
    MockMvc mvc;

    @Autowired
    TestAccounts accounts;

    String comp;
    long buyer;
    long boss;
    RequestPostProcessor me;
    RequestPostProcessor bossP;
    LocalDate today;
    long vKrw;
    long vUsd;

    @BeforeEach
    void setUp() {
        comp = accounts.newComp("PO");
        buyer = accounts.newUser(comp, "buyer1", PW);
        boss = accounts.newUser(comp, "boss1", PW);
        accounts.grant(comp, buyer, "PUR", "srm-po", "SEARCH", "SAVE", "APPROVE");
        accounts.grant(comp, buyer, "PUR", "srm-pr", "SEARCH", "SAVE", "APPROVE");
        accounts.grant(comp, boss, "BOSS", "srm-po", "SEARCH", "APPROVE");
        accounts.grant(comp, boss, "BOSS", "srm-pr", "SEARCH", "APPROVE");
        me = accounts.as(comp, buyer, "buyer1");
        bossP = accounts.as(comp, boss, "boss1");
        accounts.addPlant(comp, "P1", "1공장");
        today = LocalDate.now(ZoneId.of("Asia/Seoul"));
        item(comp, "BOLT-M8", "EA");
        item(comp, "RESIN-A", "KG");
        vKrw = supplier(comp, "V100", "KRW");
        vUsd = supplier(comp, "V200", "USD");
        price(comp, vKrw, "BOLT-M8", "KRW", "120.5");
        accounts.exec(comp, """
                insert into srm_source (comp_cd, cust_id, item_id, supplier_item_cd, lead_days, moq, order_mult, created_by, updated_by)
                select ?, ?, item_id, 'SUP-M8', 30, 100, 50, 't', 't' from mst_item where item_cd = 'BOLT-M8'""", comp, vKrw);
    }

    private void item(String c, String cd, String unit) {
        accounts.exec(c, """
                insert into mst_item (comp_cd, item_cd, item_nm, item_type, proc_type, unit_cd, lot_ctrl_yn, created_by, updated_by)
                values (?, ?, ?, 'RM', 'BUY', ?, 'N', 't', 't')""", c, cd, cd + " 품목", unit);
    }

    private long supplier(String c, String cd, String currency) {
        long id = accounts.query(c, """
                insert into mst_cust (comp_cd, cust_cd, cust_nm, cust_type, created_by, updated_by)
                values (?, ?, ?, 'PURCHASE', 't', 't') returning cust_id""", Long.class, c, cd, cd + " 상사");
        accounts.exec(c, "insert into srm_supplier (cust_id, comp_cd, currency_cd, pay_terms_cd, created_by, updated_by) values (?, ?, ?, 'NET30', 't', 't')",
                id, c, currency);
        return id;
    }

    private void price(String c, long cust, String itemCd, String currency, String price) {
        accounts.exec(c, """
                insert into srm_price (comp_cd, cust_id, item_id, currency_cd, unit_price, valid_from, created_by, updated_by)
                select ?, ?, item_id, ?, ?::numeric, '2000-01-01', 't', 't' from mst_item where item_cd = ?""", c, cust, currency, price, itemCd);
    }

    private void policy(String key, String value) {
        accounts.exec(comp, "insert into sys_policy (comp_cd, policy_key, policy_value, created_by, updated_by) values (?, ?, ?, 't', 't') "
                + "on conflict do nothing", comp, key, value);
        accounts.exec(comp, "update sys_policy set policy_value = ? where policy_key = ?", value, key);
    }

    private MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder b, String body) {
        return b.with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private ResultActions postAs(RequestPostProcessor who, String url, String body) throws Exception {
        return mvc.perform(json(post(url), body).with(who));
    }

    private ResultActions putAs(RequestPostProcessor who, String url, String body) throws Exception {
        return mvc.perform(json(put(url), body).with(who));
    }

    private static long id(ResultActions r) throws Exception {
        MvcResult m = r.andExpect(status().isOk()).andReturn();
        return ((Number) JsonPath.read(m.getResponse().getContentAsString(), "$.data")).longValue();
    }

    private String detail(long id) throws Exception {
        return mvc.perform(get(API + "/" + id).with(me)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    private int rowVer(long id) throws Exception {
        return JsonPath.read(detail(id), "$.data.po.rowVer");
    }

    private String poStatus(long id) throws Exception {
        return JsonPath.read(detail(id), "$.data.po.status");
    }

    private ResultActions act(RequestPostProcessor who, long id, String action, String reason) throws Exception {
        return postAs(who, API + "/" + id + "/" + action, "{\"rowVer\":" + rowVer(id) + (reason == null ? "" : ",\"reason\":\"" + reason + "\"") + "}");
    }

    private String due() {
        return today.plusDays(40).toString();
    }

    private long po(String cust, String linesJson) throws Exception {
        return id(postAs(me, API, "{\"custCd\":\"" + cust + "\",\"plantCd\":\"P1\",\"lines\":[" + linesJson + "]}"));
    }

    private String line(String item, String qty, String price) {
        return "{\"itemCd\":\"" + item + "\",\"qty\":\"" + qty + "\"" + (price == null ? "" : ",\"unitPrice\":\"" + price + "\"")
                + ",\"dueDate\":\"" + due() + "\"}";
    }

    private long approvedPr(String item, String qty) throws Exception {
        long pr = id(postAs(me, PR, "{\"plantCd\":\"P1\",\"needDate\":\"" + due() + "\",\"lines\":[{\"itemCd\":\"" + item + "\",\"qty\":\"" + qty + "\"}]}"));
        String d = mvc.perform(get(PR + "/" + pr).with(me)).andReturn().getResponse().getContentAsString();
        postAs(me, PR + "/" + pr + "/request", "{\"rowVer\":" + JsonPath.read(d, "$.data.pr.rowVer") + "}").andExpect(status().isOk());
        postAs(bossP, PR + "/" + pr + "/approve", "{\"rowVer\":" + ((Integer) JsonPath.read(d, "$.data.pr.rowVer") + 1) + "}").andExpect(status().isOk());
        return pr;
    }

    private long prLine(long pr) throws Exception {
        return ((Number) JsonPath.read(mvc.perform(get(PR + "/" + pr).with(me)).andReturn().getResponse().getContentAsString(),
                "$.data.lines[0].prLineId")).longValue();
    }

    // ---------- 저장 ----------

    @Test
    void 단가는_단가표_기본값_금액은_통화자릿수로_행마다_반올림_스냅샷과_경고표시() throws Exception {
        long a = po("v100", line("BOLT-M8", "3", null) + "," + line("RESIN-A", "2.5", "10.33"));
        String d = detail(a);
        String yymm = today.format(DateTimeFormatter.ofPattern("yyMM"));
        assertThat((String) JsonPath.read(d, "$.data.po.poNo")).isEqualTo("PO" + yymm + "0001");
        assertThat((String) JsonPath.read(d, "$.data.po.currencyCd")).isEqualTo("KRW");
        assertThat((String) JsonPath.read(d, "$.data.po.payTermsCd")).isEqualTo("NET30");
        assertThat((String) JsonPath.read(d, "$.data.lines[0].unitPrice")).isEqualTo("120.5");
        assertThat((String) JsonPath.read(d, "$.data.lines[0].amt")).isEqualTo("362");        // 361.5 → 362
        assertThat((String) JsonPath.read(d, "$.data.lines[1].amt")).isEqualTo("26");         // 25.825 → 26
        assertThat((String) JsonPath.read(d, "$.data.po.totalAmt")).isEqualTo("388");
        assertThat((String) JsonPath.read(d, "$.data.lines[0].supplierItemCd")).isEqualTo("SUP-M8");
        // 경고: 리드타임 30일 > 40일 남음이면 아님 / 최소 발주 100 미만 / 배수 50 아님
        assertThat((Boolean) JsonPath.read(d, "$.data.lines[0].leadShort")).isFalse();
        assertThat((Boolean) JsonPath.read(d, "$.data.lines[0].moqShort")).isTrue();
        assertThat((Boolean) JsonPath.read(d, "$.data.lines[0].multOff")).isTrue();
        // 단가표에 없는 품목·단가 비움 → NO_PRICE, 외화는 소수 2자리
        postAs(me, API, "{\"custCd\":\"V100\",\"plantCd\":\"P1\",\"lines\":[" + line("RESIN-A", "1", null) + "]}")
                .andExpect(jsonPath("$.errors[0].code").value("NO_PRICE"));
        long u = po("V200", line("RESIN-A", "3", "0.3333"));
        assertThat((String) JsonPath.read(detail(u), "$.data.lines[0].amt")).isEqualTo("1");
        long u2 = po("V200", line("RESIN-A", "1.5", "2.005"));
        assertThat((String) JsonPath.read(detail(u2), "$.data.lines[0].amt")).isEqualTo("3.01");   // 3.0075 → 3.01
        // 품명 스냅샷: 품목 이름이 바뀌어도 발주 행은 그대로
        accounts.exec(comp, "update mst_item set item_nm = '새 이름' where item_cd = 'BOLT-M8'");
        assertThat((String) JsonPath.read(detail(a), "$.data.lines[0].itemNm")).isEqualTo("BOLT-M8 품목");
        // 직접 SQL: 금액을 손으로 틀리게
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(comp,
                "update srm_po_line set amt = 361 where po_id = ? and line_no = 1", a)))).contains("ck_srm_po_line_amt");
        // 칸 검사: 협력사 고정·없는 협력사·없는 창고·납기 없음·수량 0·단가 소수 5자리
        putAs(me, API + "/" + a, "{\"custCd\":\"V200\",\"plantCd\":\"P1\",\"rowVer\":0,\"lines\":[]}")
                .andExpect(jsonPath("$.errors[0].code").value("FIXED"));
        postAs(me, API, "{\"custCd\":\"NOPE\",\"plantCd\":\"P1\"}").andExpect(jsonPath("$.errors[0].code").value("NO_SUCH_CODE"));
        postAs(me, API, "{\"custCd\":\"V100\",\"plantCd\":\"P1\",\"whCd\":\"WH9\",\"lines\":[{\"itemCd\":\"BOLT-M8\",\"qty\":\"0\",\"unitPrice\":\"1.00001\"}]}")
                .andExpect(jsonPath("$.errors.length()").value(4));
    }

    @Test
    void 구매요청_남은수량은_다른발주_합을_빼고_초과거부_자기행은_빼고_다시계산_취소하면_돌아온다() throws Exception {
        long pr = approvedPr("BOLT-M8", "100");
        long pl = prLine(pr);
        String withPr = "{\"itemCd\":\"BOLT-M8\",\"qty\":\"%s\",\"dueDate\":\"" + due() + "\",\"prLineId\":" + pl + "}";
        long a = po("V100", withPr.formatted("60"));
        postAs(me, API, "{\"custCd\":\"V100\",\"plantCd\":\"P1\",\"lines\":[" + withPr.formatted("50") + "]}")
                .andExpect(jsonPath("$.errors[0].code").value("REMAIN_EXCEEDED")).andExpect(jsonPath("$.errors[0].rowKey").value("0"));
        // 같은 발주에 같은 PR 행 두 줄(30 + 20 > 40)
        postAs(me, API, "{\"custCd\":\"V100\",\"plantCd\":\"P1\",\"lines\":[" + withPr.formatted("30") + "," + withPr.formatted("20") + "]}")
                .andExpect(jsonPath("$.errors.length()").value(2));
        long b = po("V100", withPr.formatted("40"));
        mvc.perform(get(API + "/pr-lines").with(me)).andExpect(jsonPath("$.data.length()").value(0));
        // 자기 행은 빼고: a 를 60 → 60 은 통과, 61 은 초과
        long aLine = ((Number) JsonPath.read(detail(a), "$.data.lines[0].poLineId")).longValue();
        putAs(me, API + "/" + a, "{\"plantCd\":\"P1\",\"rowVer\":0,\"lines\":[{\"poLineId\":" + aLine + ",\"itemCd\":\"BOLT-M8\",\"qty\":\"61\",\"dueDate\":\"" + due() + "\",\"prLineId\":" + pl + "}]}")
                .andExpect(jsonPath("$.errors[0].code").value("REMAIN_EXCEEDED"));
        // 요청 취소는 발주가 걸려 있으면 409, 발주 b 취소 → 남은 40 돌아옴 → 요청 마감
        postAs(me, PR + "/" + pr + "/cancel", "{\"rowVer\":2,\"reason\":\"x\"}").andExpect(jsonPath("$.code").value("SRM_PR_HAS_PO"));
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(comp,
                "update srm_pr set status = 'CANCELLED', cancel_reason = 'x' where pr_id = ?", pr)))).contains("ck_srm_pr_has_po");
        act(me, b, "cancel", "중복").andExpect(status().isOk());
        mvc.perform(get(API + "/pr-lines").with(me)).andExpect(jsonPath("$.data[0].remainQty").value("40"));
        // 승인 안 된 요청·다른 품목
        long draftPr = id(postAs(me, PR, "{\"plantCd\":\"P1\",\"lines\":[{\"itemCd\":\"BOLT-M8\",\"qty\":\"5\"}]}"));
        postAs(me, API, "{\"custCd\":\"V100\",\"plantCd\":\"P1\",\"lines\":[" + "{\"itemCd\":\"BOLT-M8\",\"qty\":\"1\",\"dueDate\":\"" + due() + "\",\"prLineId\":" + prLine(draftPr) + "}" + "]}")
                .andExpect(jsonPath("$.errors[0].code").value("PR_NOT_OPEN"));
        price(comp, vKrw, "RESIN-A", "KRW", "5");
        postAs(me, API, "{\"custCd\":\"V100\",\"plantCd\":\"P1\",\"lines\":[{\"itemCd\":\"RESIN-A\",\"qty\":\"1\",\"dueDate\":\"" + due() + "\",\"prLineId\":" + pl + "}]}")
                .andExpect(jsonPath("$.errors[0].code").value("PR_ITEM_MISMATCH"));
        // 정책: 구매요청 필수 → 연결 없는 행 REQUIRED
        policy("SRM_PR_REQUIRED", "Y");
        postAs(me, API, "{\"custCd\":\"V100\",\"plantCd\":\"P1\",\"lines\":[" + line("BOLT-M8", "1", null) + "]}")
                .andExpect(jsonPath("$.errors[0].field").value("prLineId"));
    }

    @Test
    void 같은_구매요청_행을_두_발주가_동시에_가져가면_하나만() throws Exception {
        long pl = prLine(approvedPr("BOLT-M8", "100"));
        String body = "{\"custCd\":\"V100\",\"plantCd\":\"P1\",\"lines\":[{\"itemCd\":\"BOLT-M8\",\"qty\":\"60\",\"dueDate\":\"" + due()
                + "\",\"prLineId\":" + pl + "}]}";
        List<CompletableFuture<Integer>> runs = List.of(1, 2).stream().map(i -> CompletableFuture.supplyAsync(() -> {
            try {
                return mvc.perform(json(post(API), body).with(me)).andReturn().getResponse().getStatus();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        })).toList();
        assertThat(runs.stream().map(CompletableFuture::join).toList()).containsExactlyInAnyOrder(200, 400);
        assertThat(accounts.query(comp, "select coalesce(sum(qty), 0)::int from srm_po_line where pr_line_id = ?", Integer.class, pl)).isEqualTo(60);
    }

    // ---------- 상태 ----------

    @Test
    void 결재없는_확정은_정책과_기준금액_외화는_항상_결재_발행은_스냅샷_개정은_rev증가() throws Exception {
        long a = po("V100", line("BOLT-M8", "100", null));
        act(me, a, "confirm", null).andExpect(jsonPath("$.code").value("SRM_APPROVAL_REQUIRED"));   // 기본 Y, 기준 0
        policy("SRM_PO_APPROVAL_MIN_AMT", "20000");
        assertThat((Boolean) JsonPath.read(detail(a), "$.data.po.approvalRequired")).isFalse();     // 12,050 < 20,000
        act(me, a, "confirm", null).andExpect(status().isOk());
        long u = po("V200", line("RESIN-A", "1", "1"));
        act(me, u, "confirm", null).andExpect(jsonPath("$.code").value("SRM_APPROVAL_REQUIRED"));   // 외화는 기준 금액 무관
        // 승인됨 → 수정 불가 → 발행 → 스냅샷 rev 0
        putAs(me, API + "/" + a, "{\"plantCd\":\"P1\",\"rowVer\":" + rowVer(a) + ",\"lines\":[]}").andExpect(jsonPath("$.code").value("SRM_STATE"));
        act(me, a, "issue", null).andExpect(status().isOk());
        assertThat(poStatus(a)).isEqualTo("ISSUED");
        assertThat(accounts.query(comp, "select jsonb_array_length(snapshot -> 'lines') from srm_po_rev where po_id = ? and rev = 0", Integer.class, a))
                .isEqualTo(1);
        assertThat(accounts.query(comp, "select snapshot #>> '{lines,0,qty}' from srm_po_rev where po_id = ? and rev = 0", String.class, a))
                .isEqualTo("100");
        // 개정: rev 1, 수량 바꿔 확정·발행 → 스냅샷 2개, 옛 스냅샷은 그대로
        act(me, a, "revise", null).andExpect(status().isOk());
        String d = detail(a);
        assertThat((Integer) JsonPath.read(d, "$.data.po.rev")).isEqualTo(1);
        long l1 = ((Number) JsonPath.read(d, "$.data.lines[0].poLineId")).longValue();
        putAs(me, API + "/" + a, "{\"plantCd\":\"P1\",\"rowVer\":" + rowVer(a) + ",\"lines\":[{\"poLineId\":" + l1
                + ",\"itemCd\":\"BOLT-M8\",\"qty\":\"150\",\"unitPrice\":\"120.5\",\"dueDate\":\"" + due() + "\"}]}").andExpect(status().isOk());
        act(me, a, "confirm", null).andExpect(status().isOk());
        act(me, a, "issue", null).andExpect(status().isOk());
        assertThat(accounts.query(comp, "select string_agg(rev || ':' || (snapshot #>> '{lines,0,qty}'), ',' order by rev) from srm_po_rev where po_id = ?",
                String.class, a)).isEqualTo("0:100,1:150");
        mvc.perform(get(API + "/" + a).with(me)).andExpect(jsonPath("$.data.revs.length()").value(2));
        // 스냅샷은 추가만, 개정 번호 건너뛰기·전이 표 밖 → 트리거
        assertThat(PgErrors.sqlState(catchThrowable(() -> accounts.exec(comp, "update srm_po_rev set snapshot = '{}' where po_id = ?", a))))
                .contains("42501");
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(comp, "update srm_po set status = 'DRAFT', rev = rev + 2 where po_id = ?", a))))
                .contains("ck_srm_po_rev_step");
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(comp, "update srm_po set status = 'APPROVED' where po_id = ?", a))))
                .contains("ck_srm_po_transition");
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(comp, "update srm_po set cust_id = ? where po_id = ?", vUsd, a))))
                .contains("ck_srm_po_fixed");
        // 마감·취소
        act(me, u, "close", null).andExpect(jsonPath("$.code").value("SRM_STATE"));
        act(me, u, "cancel", null).andExpect(jsonPath("$.errors[0].field").value("reason"));
        act(me, u, "cancel", "필요 없음").andExpect(status().isOk());
        act(me, a, "close", null).andExpect(status().isOk());
        act(me, a, "revise", null).andExpect(jsonPath("$.code").value("SRM_STATE"));
        assertThat(PgErrors.sqlState(catchThrowable(() -> accounts.exec(comp, "delete from srm_po where po_id = ?", a)))).contains("42501");
    }

    @Test
    void 확정할때_필수_납기지남_사용중지품목_거래정지_새발주도_거래정지면_거부() throws Exception {
        policy("SRM_PO_APPROVAL", "N");
        long empty = id(postAs(me, API, "{\"custCd\":\"V100\",\"plantCd\":\"P1\"}"));
        act(me, empty, "confirm", null).andExpect(jsonPath("$.errors[0].code").value("LINES_REQUIRED"));
        long past = po("V100", "{\"itemCd\":\"BOLT-M8\",\"qty\":\"1\",\"dueDate\":\"" + today.minusDays(1) + "\"}");
        act(me, past, "confirm", null).andExpect(jsonPath("$.errors[0].code").value("PAST")).andExpect(jsonPath("$.errors[0].rowKey").value("0"));
        long a = po("V100", line("BOLT-M8", "1", null));
        accounts.exec(comp, "update srm_supplier set trade_status = 'BLOCKED', status_reason = '품질' where cust_id = ?", vKrw);
        act(me, a, "confirm", null).andExpect(jsonPath("$.errors[0].code").value("SUPPLIER_BLOCKED"));
        postAs(me, API, "{\"custCd\":\"V100\",\"plantCd\":\"P1\"}").andExpect(jsonPath("$.errors[0].code").value("SUPPLIER_BLOCKED"));
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(comp, """
                insert into srm_po (comp_cd, po_no, po_date, cust_id, plant_cd, currency_cd, buyer_user_id, created_by, updated_by)
                values (?, 'X1', (now() at time zone 'Asia/Seoul')::date, ?, 'P1', 'KRW', ?, 't', 't')""", comp, vKrw, buyer)))).contains("ck_srm_po_supplier_blocked");
        mvc.perform(get(API + "/suppliers").with(me)).andExpect(jsonPath("$.data.length()").value(1));
        // 거래 정지가 풀리면 다시 확정 가능, 품목 사용 중지면 INACTIVE
        accounts.exec(comp, "update srm_supplier set trade_status = 'ACTIVE', status_reason = null where cust_id = ?", vKrw);
        accounts.exec(comp, "update mst_item set use_yn = 'N' where item_cd = 'BOLT-M8'");
        act(me, a, "confirm", null).andExpect(jsonPath("$.errors[0].code").value("INACTIVE"));
    }

    // ---------- 결재 ----------

    @Test
    void 결재_상신하면_결재중_수정취소불가_승인되면_APPROVED_반려회수면_DRAFT() throws Exception {
        long a = po("V100", line("BOLT-M8", "100", null));
        postAs(me, API + "/" + a + "/submit", "{\"rowVer\":0,\"approvers\":[]}").andExpect(jsonPath("$.errors[0].field").value("approvers"));
        postAs(me, API + "/" + a + "/submit", "{\"rowVer\":0,\"approvers\":[\"ghost\"]}").andExpect(jsonPath("$.errors[0].code").value("NO_SUCH_CODE"));
        postAs(me, API + "/" + a + "/submit", "{\"rowVer\":0,\"approvers\":[\"buyer1\"]}").andExpect(status().isBadRequest());   // 본인 결재 금지(P0 정책)
        long apr = id(postAs(me, API + "/" + a + "/submit", "{\"rowVer\":0,\"approvers\":[\"BOSS1\"]}"));
        assertThat(poStatus(a)).isEqualTo("IN_APPROVAL");
        assertThat(((Number) JsonPath.read(detail(a), "$.data.po.aprRequestId")).longValue()).isEqualTo(apr);
        putAs(me, API + "/" + a, "{\"plantCd\":\"P1\",\"rowVer\":" + rowVer(a) + ",\"lines\":[]}").andExpect(jsonPath("$.code").value("SRM_STATE"));
        act(me, a, "cancel", "x").andExpect(jsonPath("$.code").value("SRM_STATE"));
        act(me, a, "confirm", null).andExpect(jsonPath("$.code").value("SRM_STATE"));
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(comp, "update srm_po_line set qty = 1 where po_id = ?", a))))
                .contains("ck_srm_po_line_draft");
        // 결재자 승인 → APPROVED
        postAs(bossP, "/api/platform/approvals/" + apr + "/approve", "{\"comment\":\"OK\"}").andExpect(status().isOk());
        assertThat(poStatus(a)).isEqualTo("APPROVED");
        // 다른 발주: 반려 → DRAFT, 다시 상신 → 회수 → DRAFT
        long b = po("V100", line("BOLT-M8", "50", null));
        long apr2 = id(postAs(me, API + "/" + b + "/submit", "{\"rowVer\":0,\"approvers\":[\"boss1\"]}"));
        postAs(bossP, "/api/platform/approvals/" + apr2 + "/reject", "{\"comment\":\"단가 확인\"}").andExpect(status().isOk());
        assertThat(poStatus(b)).isEqualTo("DRAFT");
        long apr3 = id(postAs(me, API + "/" + b + "/submit", "{\"rowVer\":" + rowVer(b) + ",\"approvers\":[\"boss1\"]}"));
        postAs(me, "/api/platform/approvals/" + apr3 + "/withdraw", "{}").andExpect(status().isOk());
        assertThat(poStatus(b)).isEqualTo("DRAFT");
        // 결재 중에 직접 SQL 로 작성 중으로 돌린 뒤 승인 → 결재 처리 전체 롤백
        long apr4 = id(postAs(me, API + "/" + b + "/submit", "{\"rowVer\":" + rowVer(b) + ",\"approvers\":[\"boss1\"]}"));
        accounts.exec(comp, "update srm_po set status = 'DRAFT' where po_id = ?", b);
        postAs(bossP, "/api/platform/approvals/" + apr4 + "/approve", "{}").andExpect(jsonPath("$.code").value("SRM_STATE"));
        assertThat(accounts.query(comp, "select status from apr_request where apr_id = ?", String.class, apr4)).isEqualTo("PENDING");
    }

    // ---------- ERP 주인 · A사로 B사 · 권한 ----------

    @Test
    void ERP가_발주주인이면_만들기와_행수정은_막히고_취소마감은_된다() throws Exception {
        long a = po("V100", line("BOLT-M8", "1", null));
        policy("SRM_PO_SOURCE", "ERP");
        postAs(me, API, "{\"custCd\":\"V100\",\"plantCd\":\"P1\"}").andExpect(jsonPath("$.code").value("SRM_ERP_OWNED"));
        putAs(me, API + "/" + a, "{\"plantCd\":\"P1\",\"rowVer\":0,\"lines\":[]}").andExpect(jsonPath("$.code").value("SRM_ERP_OWNED"));
        act(me, a, "cancel", "ERP 로 이관").andExpect(status().isOk());
    }

    @Test
    void B사로_A사_발주_조회수정전이_막힘_A사_요청행_협력사_창고_참조_거부_권한() throws Exception {
        long pl = prLine(approvedPr("BOLT-M8", "100"));
        long a = po("V100", line("BOLT-M8", "1", null));
        String other = accounts.newComp("PB");
        long bu = accounts.newUser(other, "buyer1", PW);
        accounts.grant(other, bu, "PUR", "srm-po", "SEARCH", "SAVE", "APPROVE");
        RequestPostProcessor b = accounts.as(other, bu, "buyer1");
        accounts.addPlant(other, "P1", "B 1공장");
        item(other, "BOLT-M8", "EA");
        long bv = supplier(other, "V900", "KRW");
        price(other, bv, "BOLT-M8", "KRW", "1");
        long bPo = id(postAs(b, API, "{\"custCd\":\"V900\",\"plantCd\":\"P1\",\"lines\":[{\"itemCd\":\"BOLT-M8\",\"qty\":\"1\",\"dueDate\":\"" + due() + "\"}]}"));
        mvc.perform(get(API).param("from", today.toString()).param("to", today.toString()).with(b)).andExpect(jsonPath("$.data.length()").value(1));
        mvc.perform(get(API + "/" + a).with(b)).andExpect(status().isNotFound());
        putAs(b, API + "/" + a, "{\"plantCd\":\"P1\",\"rowVer\":0,\"lines\":[]}").andExpect(status().isNotFound());
        for (String action : new String[] {"confirm", "issue", "revise", "cancel", "close"}) {
            postAs(b, API + "/" + a + "/" + action, "{\"rowVer\":0,\"reason\":\"x\"}").andExpect(status().isNotFound());
        }
        postAs(b, API + "/" + a + "/submit", "{\"rowVer\":0,\"approvers\":[\"buyer1\"]}").andExpect(status().isNotFound());
        postAs(b, API, "{\"custCd\":\"V100\",\"plantCd\":\"P1\"}").andExpect(jsonPath("$.errors[0].code").value("NO_SUCH_CODE"));
        // A사 요청 행 id 를 B사 발주에 → 없는 코드(RLS 로 안 보임), A사 발주 행 id 로 한 벌 저장 → 400
        postAs(b, API, "{\"custCd\":\"V900\",\"plantCd\":\"P1\",\"lines\":[{\"itemCd\":\"BOLT-M8\",\"qty\":\"1\",\"dueDate\":\"" + due() + "\",\"prLineId\":" + pl + "}]}")
                .andExpect(jsonPath("$.errors[0].code").value("NO_SUCH_CODE"));
        long aLine = ((Number) JsonPath.read(detail(a), "$.data.lines[0].poLineId")).longValue();
        putAs(b, API + "/" + bPo, "{\"plantCd\":\"P1\",\"rowVer\":0,\"lines\":[{\"poLineId\":" + aLine + ",\"itemCd\":\"BOLT-M8\",\"qty\":\"1\",\"dueDate\":\"" + due() + "\"}]}")
                .andExpect(status().isBadRequest());
        mvc.perform(get(API + "/pr-lines").with(b)).andExpect(jsonPath("$.data.length()").value(0));
        // 직접 SQL(B사 범위): B사 발주 행이 A사 요청 행, B사 발주에 A사 창고
        accounts.exec(comp, "insert into mst_equip_hier (comp_cd, plant_cd, node_type, node_cd, node_nm, created_by, updated_by) values (?, 'P1', 'WH', 'WH1', '자재 창고', 't', 't')", comp);
        long aWh = accounts.query(comp, "select node_id from mst_equip_hier where node_cd = 'WH1'", Long.class);
        long bItem = accounts.query(other, "select item_id from mst_item where item_cd = 'BOLT-M8'", Long.class);
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(other, """
                insert into srm_po_line (comp_cd, po_id, line_no, item_id, item_nm, unit_cd, qty, unit_price, amt, due_date, pr_line_id, created_by, updated_by)
                values (?, ?, 9, ?, 'x', 'EA', 1, 1, 1, (now() at time zone 'Asia/Seoul')::date, ?, 't', 't')""", other, bPo, bItem, pl)))).contains("fk_srm_po_line_pr_comp");
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(other, "update srm_po set wh_id = ? where po_id = ?", aWh, bPo))))
                .contains("fk_srm_po_wh_plant");
        // A사 창고는 A사 발주에 지정 가능(코드로)
        putAs(me, API + "/" + a, "{\"plantCd\":\"P1\",\"whCd\":\"wh1\",\"rowVer\":0,\"lines\":[{\"poLineId\":" + aLine
                + ",\"itemCd\":\"BOLT-M8\",\"qty\":\"1\",\"dueDate\":\"" + due() + "\"}]}").andExpect(status().isOk());
        // 권한: 저장만 → 확정·마감 403, 조회만 → 저장 403
        long saver = accounts.newUser(comp, "saver1", PW);
        accounts.grant(comp, saver, "SAVER", "srm-po", "SEARCH", "SAVE");
        RequestPostProcessor s = accounts.as(comp, saver, "saver1");
        act(s, a, "confirm", null).andExpect(status().isForbidden());
        act(s, a, "close", null).andExpect(status().isForbidden());
        long reader = accounts.newUser(comp, "reader1", PW);
        accounts.grant(comp, reader, "READER", "srm-po", "SEARCH");
        postAs(accounts.as(comp, reader, "reader1"), API, "{\"custCd\":\"V100\",\"plantCd\":\"P1\"}").andExpect(status().isForbidden());
        mvc.perform(get(API + "/approvers").with(me)).andExpect(jsonPath("$.data[*].loginId").value(org.hamcrest.Matchers.containsInAnyOrder("boss1", "buyer1")));
    }
}
