package io.smarthappy.module.srm.pr;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.startsWith;
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
 * SRM S2 구매요청 (docs/31) — 한 벌 저장(행 추가·수정·삭제·행 오류 키), 채번(기본 규칙 자동), 상태 전이 표(서비스 + 트리거),
 * 본인 승인 정책, 동시 승인, 기간 조회, A사로 B사, 권한.
 */
@AutoConfigureMockMvc
class PrTest extends PostgresTestBase {

    private static final String PW = "Maple-anchor-63";
    private static final String API = "/api/srm/prs";

    @Autowired
    MockMvc mvc;

    @Autowired
    TestAccounts accounts;

    String comp;
    long reqUser;
    long approver;
    RequestPostProcessor req;
    RequestPostProcessor apr;
    LocalDate today;

    @BeforeEach
    void setUp() {
        comp = accounts.newComp("PR");
        reqUser = accounts.newUser(comp, "req1", PW);
        approver = accounts.newUser(comp, "boss1", PW);
        accounts.grant(comp, reqUser, "PUR", "srm-pr", "SEARCH", "SAVE", "APPROVE");
        accounts.grant(comp, approver, "PUR", "srm-pr", "SEARCH", "SAVE", "APPROVE");
        req = accounts.as(comp, reqUser, "req1");
        apr = accounts.as(comp, approver, "boss1");
        accounts.addPlant(comp, "P1", "1공장");
        item(comp, "BOLT-M8", "EA");
        item(comp, "RESIN-A", "KG");
        today = LocalDate.now(ZoneId.of("Asia/Seoul"));
    }

    private void item(String c, String cd, String unit) {
        accounts.exec(c, """
                insert into mst_item (comp_cd, item_cd, item_nm, item_type, proc_type, unit_cd, lot_ctrl_yn, created_by, updated_by)
                values (?, ?, ?, 'RM', 'BUY', ?, 'N', 't', 't')""", c, cd, cd + " 품목", unit);
    }

    private long supplier(String c, String cd, String status) {
        long id = accounts.query(c, """
                insert into mst_cust (comp_cd, cust_cd, cust_nm, cust_type, created_by, updated_by)
                values (?, ?, ?, 'PURCHASE', 't', 't') returning cust_id""", Long.class, c, cd, cd + " 상사");
        accounts.exec(c, """
                insert into srm_supplier (cust_id, comp_cd, trade_status, status_reason, created_by, updated_by)
                values (?, ?, ?, case when ? = 'ACTIVE' then null else '테스트' end, 't', 't')""", id, c, status, status);
        return id;
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
        return mvc.perform(get(API + "/" + id).with(req)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    private int rowVer(long id) throws Exception {
        return JsonPath.read(detail(id), "$.data.pr.rowVer");
    }

    private ResultActions act(RequestPostProcessor who, long id, String action, String reason) throws Exception {
        return postAs(who, API + "/" + id + "/" + action, "{\"rowVer\":" + rowVer(id) + (reason == null ? "" : ",\"reason\":\"" + reason + "\"") + "}");
    }

    private String need() {
        return today.plusDays(14).toString();
    }

    private long simplePr() throws Exception {
        return id(postAs(req, API, "{\"plantCd\":\"P1\",\"needDate\":\"" + need() + "\",\"lines\":[{\"itemCd\":\"BOLT-M8\",\"qty\":\"100\"}]}"));
    }

    // ---------- 한 벌 저장 ----------

    @Test
    void 한벌저장은_행추가수정삭제_채번은_기본규칙으로_이어서_행오류는_행키로() throws Exception {
        long a = id(postAs(req, API, "{\"plantCd\":\"p1\",\"deptNm\":\"생산팀\",\"needDate\":\"" + need() + "\",\"lines\":["
                + "{\"itemCd\":\"bolt-m8\",\"qty\":\"1000\"},{\"itemCd\":\"RESIN-A\",\"qty\":\"12.5\",\"needDate\":\"" + need() + "\"}]}"));
        long b = simplePr();
        String yymm = today.format(DateTimeFormatter.ofPattern("yyMM"));
        String da = detail(a);
        assertThat((String) JsonPath.read(da, "$.data.pr.prNo")).isEqualTo("PR" + yymm + "0001");
        assertThat((String) JsonPath.read(detail(b), "$.data.pr.prNo")).isEqualTo("PR" + yymm + "0002");
        assertThat((String) JsonPath.read(da, "$.data.pr.status")).isEqualTo("DRAFT");
        assertThat((String) JsonPath.read(da, "$.data.lines[1].qty")).isEqualTo("12.5");
        assertThat((String) JsonPath.read(da, "$.data.lines[1].unitCd")).isEqualTo("KG");
        long l1 = ((Number) JsonPath.read(da, "$.data.lines[0].prLineId")).longValue();
        // 1행 수량 변경, 2행 삭제, 새 행 추가 → 새 행은 남은 행 다음 번호(작성 중엔 지운 번호를 다시 쓸 수 있다)
        putAs(req, API + "/" + a, "{\"plantCd\":\"P1\",\"needDate\":\"" + need() + "\",\"rowVer\":0,\"lines\":["
                + "{\"prLineId\":" + l1 + ",\"itemCd\":\"BOLT-M8\",\"qty\":\"1200\"},{\"itemCd\":\"RESIN-A\",\"qty\":\"3\"}]}")
                .andExpect(status().isOk());
        String d2 = detail(a);
        assertThat((List<Integer>) JsonPath.read(d2, "$.data.lines[*].lineNo")).containsExactly(1, 2);
        assertThat((String) JsonPath.read(d2, "$.data.lines[0].qty")).isEqualTo("1200");
        assertThat((Integer) JsonPath.read(d2, "$.data.pr.rowVer")).isEqualTo(1);
        // 옛 rowVer → 409, 다른 요청의 행 id → 400
        putAs(req, API + "/" + a, "{\"plantCd\":\"P1\",\"rowVer\":0,\"lines\":[]}").andExpect(status().isConflict());
        long other = ((Number) JsonPath.read(detail(b), "$.data.lines[0].prLineId")).longValue();
        putAs(req, API + "/" + a, "{\"plantCd\":\"P1\",\"rowVer\":1,\"lines\":[{\"prLineId\":" + other + ",\"itemCd\":\"BOLT-M8\",\"qty\":\"1\"}]}")
                .andExpect(status().isBadRequest());
        assertThat(accounts.query(comp, "select count(*) from srm_pr_line where pr_id = ?", Integer.class, b)).isEqualTo(1);
        // 행 오류: EA 소수·품목 없음·없는 품목·사용 중지 품목·없는 협력사·거래 정지 협력사 — 행 키(순번)로 끝까지
        supplier(comp, "V-STOP", "BLOCKED");
        item(comp, "OLD-1", "EA");
        accounts.exec(comp, "update mst_item set use_yn = 'N' where item_cd = 'OLD-1'");
        postAs(req, API, "{\"plantCd\":\"P1\",\"lines\":[{\"itemCd\":\"BOLT-M8\",\"qty\":\"0.5\"},{\"qty\":\"1\"},"
                + "{\"itemCd\":\"NOPE\",\"qty\":\"1\"},{\"itemCd\":\"OLD-1\",\"qty\":\"1\"},{\"itemCd\":\"BOLT-M8\",\"qty\":\"1\",\"prefCustCd\":\"V-X\"},"
                + "{\"itemCd\":\"BOLT-M8\",\"qty\":\"1\",\"prefCustCd\":\"V-STOP\"},{\"itemCd\":\"BOLT-M8\",\"qty\":\"0\"}]}")
                .andExpect(jsonPath("$.errors[*].rowKey").value(containsInAnyOrder("0", "1", "2", "3", "4", "5", "6")))
                .andExpect(jsonPath("$.errors[*].code").value(containsInAnyOrder("SCALE", "REQUIRED", "NO_SUCH_CODE", "INACTIVE",
                        "NO_SUCH_CODE", "SUPPLIER_BLOCKED", "POSITIVE")));
        // 머리: 공장 없음·사용 중지 공장, 행 201개
        postAs(req, API, "{\"lines\":[]}").andExpect(jsonPath("$.errors[0].field").value("plantCd"));
        accounts.addPlant(comp, "P9", "닫은 공장");
        accounts.exec(comp, "update sys_plant set use_yn = 'N' where plant_cd = 'P9'");
        postAs(req, API, "{\"plantCd\":\"P9\"}").andExpect(jsonPath("$.errors[0].code").value("INACTIVE"));
        StringBuilder many = new StringBuilder("{\"plantCd\":\"P1\",\"lines\":[");
        for (int i = 0; i < 201; i++) {
            many.append(i == 0 ? "" : ",").append("{\"itemCd\":\"BOLT-M8\",\"qty\":\"1\"}");
        }
        postAs(req, API, many + "]}").andExpect(jsonPath("$.errors[0].code").value("TOO_MANY_LINES"));
        // 그대로 둔 행은 품목이 사용 중지돼도 저장된다(새 참조만 차단)
        accounts.exec(comp, "update mst_item set use_yn = 'N' where item_cd = 'BOLT-M8'");
        putAs(req, API + "/" + a, "{\"plantCd\":\"P1\",\"deptNm\":\"구매팀\",\"rowVer\":1,\"lines\":[{\"prLineId\":" + l1
                + ",\"itemCd\":\"BOLT-M8\",\"qty\":\"1200\"}]}").andExpect(status().isOk());
    }

    // ---------- 상태 전이 ----------

    @Test
    void 상태전이_요청검사_작성중만편집_반려사유_다시작성_마감_트리거가_최종() throws Exception {
        long empty = id(postAs(req, API, "{\"plantCd\":\"P1\",\"needDate\":\"" + need() + "\"}"));
        act(req, empty, "request", null).andExpect(jsonPath("$.errors[0].code").value("LINES_REQUIRED"));
        long past = id(postAs(req, API, "{\"plantCd\":\"P1\",\"needDate\":\"" + today.minusDays(1) + "\",\"lines\":[{\"itemCd\":\"BOLT-M8\",\"qty\":\"1\"}]}"));
        act(req, past, "request", null).andExpect(jsonPath("$.errors[*].code").value(containsInAnyOrder("PAST", "PAST")));
        long noNeed = id(postAs(req, API, "{\"plantCd\":\"P1\",\"lines\":[{\"itemCd\":\"BOLT-M8\",\"qty\":\"1\"}]}"));
        act(req, noNeed, "request", null).andExpect(jsonPath("$.errors[0].code").value("REQUIRED"));

        long a = simplePr();
        act(req, a, "request", null).andExpect(status().isOk());
        // 요청 뒤 수정·다시 요청·마감 → SRM_STATE, 직접 SQL 머리·행 수정 → 트리거
        putAs(req, API + "/" + a, "{\"plantCd\":\"P1\",\"rowVer\":1,\"lines\":[]}").andExpect(jsonPath("$.code").value("SRM_STATE"));
        act(req, a, "request", null).andExpect(jsonPath("$.code").value("SRM_STATE"));
        act(apr, a, "close", null).andExpect(jsonPath("$.code").value("SRM_STATE"));
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(comp, "update srm_pr set reason = 'x' where pr_id = ?", a))))
                .contains("ck_srm_pr_draft");
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(comp, "update srm_pr_line set qty = 1 where pr_id = ?", a))))
                .contains("ck_srm_pr_line_draft");
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(comp, "delete from srm_pr_line where pr_id = ?", a))))
                .contains("ck_srm_pr_line_draft");
        // 회수 → 작성 중 → 다시 요청
        act(req, a, "withdraw", null).andExpect(status().isOk());
        act(req, a, "request", null).andExpect(status().isOk());
        // 본인 승인 금지(기본) → 정책 Y 면 허용 / 반려는 사유 필수 / 반려 → 다시 작성 → 요청 → 승인
        act(req, a, "approve", null).andExpect(jsonPath("$.code").value("SRM_SELF_APPROVE"));
        act(apr, a, "reject", "").andExpect(jsonPath("$.errors[0].field").value("reason"));
        act(apr, a, "reject", "수량 과다").andExpect(status().isOk());
        assertThat((String) JsonPath.read(detail(a), "$.data.pr.rejectReason")).isEqualTo("수량 과다");
        assertThat(((Number) JsonPath.read(detail(a), "$.data.pr.decidedBy")).longValue()).isEqualTo(approver);
        act(apr, a, "approve", null).andExpect(jsonPath("$.code").value("SRM_STATE"));
        act(req, a, "reopen", null).andExpect(status().isOk());
        act(req, a, "request", null).andExpect(status().isOk());
        accounts.exec(comp, "insert into sys_policy (comp_cd, policy_key, policy_value, created_by, updated_by) values (?, 'SRM_PR_SELF_APPROVE', 'Y', 't', 't')", comp);
        act(req, a, "approve", null).andExpect(status().isOk());
        // 승인 뒤: 수정 불가, 마감 → 끝(취소·다시 열기 불가), 직접 SQL 로 되돌리기·삭제 불가
        act(apr, a, "close", null).andExpect(status().isOk());
        act(req, a, "cancel", "필요 없음").andExpect(jsonPath("$.code").value("SRM_STATE"));
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(comp, "update srm_pr set status = 'DRAFT' where pr_id = ?", a))))
                .contains("ck_srm_pr_transition");
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(comp, "update srm_pr set pr_no = 'X' where pr_id = ?", a))))
                .contains("ck_srm_pr_fixed");
        assertThat(PgErrors.sqlState(catchThrowable(() -> accounts.exec(comp, "delete from srm_pr where pr_id = ?", a)))).contains("42501");
        // 취소는 사유 필수, 작성 중에서 바로 취소
        long c = simplePr();
        act(req, c, "cancel", null).andExpect(jsonPath("$.errors[0].code").value("REQUIRED"));
        act(req, c, "cancel", "중복 요청").andExpect(status().isOk());
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(comp,
                "insert into srm_pr (comp_cd, pr_no, pr_date, plant_cd, req_user_id, status, created_by, updated_by) values (?, 'X1', " + TestAccounts.COMP_TODAY + ", 'P1', ?, 'APPROVED', 't', 't')",
                comp, reqUser)))).contains("ck_srm_pr_transition");
        // 옛 rowVer 로 상태 전이 → 409
        long d = simplePr();
        postAs(req, API + "/" + d + "/request", "{\"rowVer\":5}").andExpect(status().isConflict());
    }

    @Test
    void 같은_요청을_두_사람이_동시에_승인하면_하나만() throws Exception {
        long a = simplePr();
        act(req, a, "request", null).andExpect(status().isOk());
        long third = accounts.newUser(comp, "boss2", PW);
        accounts.grant(comp, third, "PUR2", "srm-pr", "SEARCH", "APPROVE");
        RequestPostProcessor apr2 = accounts.as(comp, third, "boss2");
        int ver = rowVer(a);
        List<CompletableFuture<Integer>> runs = List.of(apr, apr2).stream().map(who -> CompletableFuture.supplyAsync(() -> {
            try {
                return mvc.perform(json(post(API + "/" + a + "/approve"), "{\"rowVer\":" + ver + "}").with(who)).andReturn()
                        .getResponse().getStatus();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        })).toList();
        assertThat(runs.stream().map(CompletableFuture::join).toList()).containsExactlyInAnyOrder(200, 409);
        assertThat(accounts.query(comp, "select count(*) from sys_audit_log where table_nm = 'srm_pr' and row_key = ? and after_data ->> 'status' = 'APPROVED'",
                Integer.class, String.valueOf(a))).isEqualTo(1);
    }

    // ---------- 조회 ----------

    @Test
    void 조회는_기간필수_상한_품목코드로도_찾고_상태로_거른다() throws Exception {
        long a = simplePr();
        id(postAs(req, API, "{\"plantCd\":\"P1\",\"reason\":\"금형 수리\",\"lines\":[{\"itemCd\":\"RESIN-A\",\"qty\":\"1\"}]}"));
        act(req, a, "request", null).andExpect(status().isOk());
        String f = today.minusDays(7).toString();
        String t = today.toString();
        mvc.perform(get(API).with(req)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("from"));
        mvc.perform(get(API).param("from", "2020-01-01").param("to", t).with(req)).andExpect(jsonPath("$.code").value("RANGE_TOO_LONG"));
        mvc.perform(get(API).param("from", t).param("to", f).with(req)).andExpect(jsonPath("$.code").value("INVALID_RANGE"));
        mvc.perform(get(API).param("from", f).param("to", t).with(req)).andExpect(jsonPath("$.data.length()").value(2));
        mvc.perform(get(API).param("from", f).param("to", t).param("keyword", "resin").with(req)).andExpect(jsonPath("$.data.length()").value(1));
        mvc.perform(get(API).param("from", f).param("to", t).param("keyword", "금형").with(req)).andExpect(jsonPath("$.data.length()").value(1));
        mvc.perform(get(API).param("from", f).param("to", t).param("status", "requested").with(req))
                .andExpect(jsonPath("$.data[0].prNo").value(startsWith("PR"))).andExpect(jsonPath("$.data.length()").value(1));
        mvc.perform(get(API).param("from", f).param("to", t).param("keyword", "100%_\\").with(req)).andExpect(jsonPath("$.data.length()").value(0));
    }

    // ---------- A사로 B사 · 권한 ----------

    @Test
    void B사로_A사_요청_조회수정전이_막힘_직접SQL_다른회사참조_거부_권한() throws Exception {
        long vA = supplier(comp, "V100", "ACTIVE");
        long a = id(postAs(req, API, "{\"plantCd\":\"P1\",\"needDate\":\"" + need() + "\",\"lines\":[{\"itemCd\":\"BOLT-M8\",\"qty\":\"1\",\"prefCustCd\":\"V100\"}]}"));
        long aLine = ((Number) JsonPath.read(detail(a), "$.data.lines[0].prLineId")).longValue();
        String other = accounts.newComp("PB");
        long bu = accounts.newUser(other, "req1", PW);
        accounts.grant(other, bu, "PUR", "srm-pr", "SEARCH", "SAVE", "APPROVE");
        RequestPostProcessor b = accounts.as(other, bu, "req1");
        accounts.addPlant(other, "P1", "B 1공장");
        item(other, "BOLT-M8", "EA");
        long bPr = id(postAs(b, API, "{\"plantCd\":\"P1\",\"lines\":[{\"itemCd\":\"BOLT-M8\",\"qty\":\"1\"}]}"));
        String f = today.minusDays(1).toString();
        String t = today.toString();
        mvc.perform(get(API).param("from", f).param("to", t).with(b)).andExpect(jsonPath("$.data.length()").value(1));
        mvc.perform(get(API + "/" + a).with(b)).andExpect(status().isNotFound());
        putAs(b, API + "/" + a, "{\"plantCd\":\"P1\",\"rowVer\":0,\"lines\":[]}").andExpect(status().isNotFound());
        for (String action : new String[] {"request", "approve", "reject", "cancel", "close", "withdraw", "reopen"}) {
            postAs(b, API + "/" + a + "/" + action, "{\"rowVer\":0,\"reason\":\"x\"}").andExpect(status().isNotFound());
        }
        // 자기 요청에 A사 행 id → 400, A사 협력사 코드 → 없는 코드
        putAs(b, API + "/" + bPr, "{\"plantCd\":\"P1\",\"rowVer\":0,\"lines\":[{\"prLineId\":" + aLine + ",\"itemCd\":\"BOLT-M8\",\"qty\":\"1\"}]}")
                .andExpect(status().isBadRequest());
        putAs(b, API + "/" + bPr, "{\"plantCd\":\"P1\",\"rowVer\":0,\"lines\":[{\"itemCd\":\"BOLT-M8\",\"qty\":\"1\",\"prefCustCd\":\"V100\"}]}")
                .andExpect(jsonPath("$.errors[0].code").value("NO_SUCH_CODE"));
        assertThat(accounts.query(comp, "select count(*) from srm_pr_line where pr_id = ?", Integer.class, a)).isEqualTo(1);
        // 직접 SQL(B사 범위): B사 요청 행이 A사 품목·A사 협력사·A사 요청을 가리키기
        long aItem = accounts.query(comp, "select item_id from mst_item where item_cd = 'BOLT-M8'", Long.class);
        long bItem = accounts.query(other, "select item_id from mst_item where item_cd = 'BOLT-M8'", Long.class);
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(other, """
                insert into srm_pr_line (comp_cd, pr_id, line_no, item_id, qty, unit_cd, created_by, updated_by)
                values (?, ?, 9, ?, 1, 'EA', 't', 't')""", other, bPr, aItem)))).contains("fk_srm_pr_line_item_comp");
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(other, """
                insert into srm_pr_line (comp_cd, pr_id, line_no, item_id, qty, unit_cd, pref_cust_id, created_by, updated_by)
                values (?, ?, 9, ?, 1, 'EA', ?, 't', 't')""", other, bPr, bItem, vA)))).contains("fk_srm_pr_line_pref_comp");
        assertThat(PgErrors.constraint(catchThrowable(() -> accounts.exec(other, """
                insert into srm_pr_line (comp_cd, pr_id, line_no, item_id, qty, unit_cd, created_by, updated_by)
                values (?, ?, 9, ?, 1, 'EA', 't', 't')""", other, a, bItem)))).contains("fk_srm_pr_line_pr_comp");
        // 권한: 저장만 있는 사람은 승인·반려·마감 403, 조회만 있는 사람은 저장 403
        long saver = accounts.newUser(comp, "saver1", PW);
        accounts.grant(comp, saver, "SAVER", "srm-pr", "SEARCH", "SAVE");
        RequestPostProcessor s = accounts.as(comp, saver, "saver1");
        act(req, a, "request", null).andExpect(status().isOk());
        act(s, a, "approve", null).andExpect(status().isForbidden());
        act(s, a, "reject", "x").andExpect(status().isForbidden());
        long reader = accounts.newUser(comp, "reader1", PW);
        accounts.grant(comp, reader, "READER", "srm-pr", "SEARCH");
        postAs(accounts.as(comp, reader, "reader1"), API, "{\"plantCd\":\"P1\"}").andExpect(status().isForbidden());
        mvc.perform(get(API + "/plants").with(accounts.as(comp, reader, "reader1"))).andExpect(jsonPath("$.data.length()").value(1));
    }
}
