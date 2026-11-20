package io.smarthappy.platform.approval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import io.smarthappy.platform.approval.ApprovalService.LineSpec;
import io.smarthappy.platform.approval.ApprovalService.LineType;
import io.smarthappy.platform.common.web.BusinessException;
import io.smarthappy.platform.common.web.HbErrorCode;
import io.smarthappy.platform.policy.Policies;
import io.smarthappy.platform.policy.PolicyService;
import io.smarthappy.platform.security.LoginUser;
import io.smarthappy.platform.tenant.TenantRunner;
import io.smarthappy.support.PostgresTestBase;
import io.smarthappy.support.TestAccounts;
import io.smarthappy.support.TestMessaging.TestDocType;

/** 결재 엔진 (P0 7단계): 상신 → 순차 결재 → 업무 상태 변경(같은 트랜잭션) → 알림(아웃박스) */
@AutoConfigureMockMvc
class ApprovalTest extends PostgresTestBase {

    private static final String PW = "Linen-harvest-84";
    private static final String DOC = TestDocType.DOC_TYPE;

    @Autowired
    MockMvc mvc;

    @Autowired
    TestAccounts accounts;

    @Autowired
    TenantRunner runner;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    ApprovalService approvals;

    @Autowired
    PolicyService policies;

    String comp;
    long kim;
    long lee;
    long park;
    long choi;

    @BeforeEach
    void setUp() {
        TestDocType.ACTS.clear();   // 정적 큐 — 같은 업무 키(SIGN 등)를 쓰는 앞 시험·다른 시험 클래스의 처리가 섞이지 않게
        comp = accounts.newComp("AP");
        kim = accounts.newUser(comp, "kim", PW);   // 요청자
        lee = accounts.newUser(comp, "lee", PW);   // 1차 결재
        park = accounts.newUser(comp, "park", PW); // 2차 결재
        choi = accounts.newUser(comp, "choi", PW); // 참조
    }

    /** 결재 대상 업무 문서 = 사용 안 함 상태의 권한그룹 */
    private String newDoc(String code) {
        accounts.exec(comp, """
                insert into sys_auth_group (comp_cd, auth_grp_cd, auth_grp_nm, use_yn, created_by, updated_by)
                values (?, ?, ?, 'N', 't', 't')""", comp, code, code);
        return code;
    }

    private <T> T as(String compCd, long userId, String loginId, Supplier<T> work) {
        LoginUser user = new LoginUser(userId, compCd, loginId, loginId, "ko", false);
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                user, null, List.of(new SimpleGrantedAuthority("ROLE_USER"))));
        try {
            return runner.call(compCd, work);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private long submit(String bizKey, List<LineSpec> lines) {
        return as(comp, kim, "kim", () -> approvals.submit(DOC, bizKey, "권한그룹 " + bizKey + " 사용 승인", lines));
    }

    private List<LineSpec> standardLine() {
        return List.of(new LineSpec(lee, LineType.APPROVE), new LineSpec(choi, LineType.REF),
                new LineSpec(park, LineType.APPROVE));
    }

    private RequestPostProcessor user(long id, String login) {
        return accounts.as(comp, id, login);
    }

    private ResultActions act(long aprId, String action, RequestPostProcessor who, String comment) throws Exception {
        return mvc.perform(post("/api/platform/approvals/" + aprId + "/" + action).with(who).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(comment == null ? "{}" : "{\"comment\":\"" + comment + "\"}"));
    }

    private String reqStatus(long aprId) {
        return accounts.query(comp, "select status from apr_request where apr_id = ?", String.class, aprId);
    }

    private String docUse(String code) {
        return accounts.query(comp, "select use_yn from sys_auth_group where auth_grp_cd = ?", String.class, code);
    }

    private int notify(long aprId) {
        return accounts.query(comp, "select count(*) from sys_outbox where topic = 'NOTIFY' and biz_key = ?",
                Integer.class, "APR:" + aprId);
    }

    // ---------- 정상 ----------

    @Test
    void 상신_순차_승인_업무_상태_변경_알림() throws Exception {
        String doc = newDoc("QC1");
        long apr = submit(doc, standardLine());
        assertThat(notify(apr)).isEqualTo(1); // 1차 결재자에게

        mvc.perform(get("/api/platform/approvals/todo").with(user(lee, "lee")))
                .andExpect(jsonPath("$.list[0].aprId").value(apr))
                .andExpect(jsonPath("$.list[0].requesterNm").value("kim 사용자"));
        mvc.perform(get("/api/platform/approvals/todo").with(user(park, "park")))
                .andExpect(jsonPath("$.list.length()").value(0));

        act(apr, "approve", user(lee, "lee"), "확인").andExpect(status().isOk());
        assertThat(reqStatus(apr)).isEqualTo("PENDING");
        assertThat(notify(apr)).isEqualTo(2); // 2차 결재자에게
        assertThat(docUse(doc)).isEqualTo("N");

        act(apr, "approve", user(park, "park"), null).andExpect(status().isOk());
        assertThat(reqStatus(apr)).isEqualTo("APPROVED");
        assertThat(docUse(doc)).isEqualTo("Y");
        assertThat(notify(apr)).isEqualTo(3); // 요청자에게 결과

        mvc.perform(get("/api/platform/approvals/" + apr).with(user(choi, "choi")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.request.status").value("APPROVED"))
                .andExpect(jsonPath("$.data.lines[1].status").value("REF"))
                .andExpect(jsonPath("$.data.history.length()").value(3));
    }

    @Test
    void 반려는_사유_필수_남은_결재는_건너뛰고_업무는_반려_처리() throws Exception {
        String doc = newDoc("QC2");
        long apr = submit(doc, standardLine());
        act(apr, "reject", user(lee, "lee"), null).andExpect(status().isBadRequest());
        act(apr, "reject", user(lee, "lee"), "   ").andExpect(status().isBadRequest());
        // 보이지 않는 글자뿐인 의견(QA R4-a) — 제로폭 공백·NBSP+BOM·한글 채움 문자
        for (String invisible : List.of("\\u200B", "\\u00A0\\uFEFF", "\\u3164")) {
            act(apr, "reject", user(lee, "lee"), invisible).andExpect(status().isBadRequest());
        }
        assertThat(reqStatus(apr)).isEqualTo("PENDING");
        act(apr, "reject", user(lee, "lee"), "\\u200B근거 부족\\u00A0").andExpect(status().isOk());
        assertThat(accounts.query(comp, "select comment from apr_line where apr_id = ? and status = 'REJECTED'", String.class, apr))
                .isEqualTo("근거 부족");   // 앞뒤 보이지 않는 글자는 걷어서 남긴다
        assertThat(reqStatus(apr)).isEqualTo("REJECTED");
        assertThat(accounts.query(comp, "select string_agg(status, ',' order by seq) from apr_line where apr_id = ?",
                String.class, apr)).isEqualTo("REJECTED,REF,SKIPPED");
        assertThat(docUse(doc)).isEqualTo("N");
    }

    @Test
    void 아무도_처리하기_전에는_요청자가_회수_이후엔_불가() throws Exception {
        long apr1 = submit(newDoc("QC3"), standardLine());
        act(apr1, "withdraw", user(kim, "kim"), "다시 작성").andExpect(status().isOk());
        assertThat(reqStatus(apr1)).isEqualTo("WITHDRAWN");

        long apr2 = submit(newDoc("QC4"), standardLine());
        act(apr2, "approve", user(lee, "lee"), null).andExpect(status().isOk());
        act(apr2, "withdraw", user(kim, "kim"), null).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("APR_NOT_WITHDRAWABLE"));
    }

    @Test
    void 합의는_순서대로_처리되고_이력에_AGREE_로_남는다() throws Exception {
        long apr = submit(newDoc("QC5"), List.of(new LineSpec(lee, LineType.AGREE), new LineSpec(park, LineType.APPROVE)));
        act(apr, "approve", user(lee, "lee"), "합의").andExpect(status().isOk());
        act(apr, "approve", user(park, "park"), null).andExpect(status().isOk());
        assertThat(accounts.query(comp, "select string_agg(action, ',' order by hist_id) from apr_history where apr_id = ?",
                String.class, apr)).isEqualTo("SUBMIT,AGREE,APPROVE");
    }

    // ---------- 줄 처리 확장점 beforeAct (docs/36 18.2) ----------

    private List<ApprovalDocType.LineAct> acts(String bizKey) {
        return TestDocType.ACTS.stream().filter(e -> e.getKey().equals(bizKey)).map(Map.Entry::getValue).toList();
    }

    private String lines(long aprId) {
        return accounts.query(comp, "select string_agg(status, ',' order by seq) from apr_line where apr_id = ?", String.class, aprId);
    }

    private int hist(long aprId) {
        return accounts.query(comp, "select count(*) from apr_history where apr_id = ?", Integer.class, aprId);
    }

    @Test
    void 줄_처리마다_beforeAct_가_누가_어느_줄을_어떻게_를_받는다() throws Exception {
        String doc = newDoc("QA1");
        long apr = submit(doc, List.of(new LineSpec(lee, LineType.AGREE), new LineSpec(choi, LineType.REF),
                new LineSpec(park, LineType.APPROVE)));
        act(apr, "approve", user(lee, "lee"), null).andExpect(status().isOk());
        act(apr, "approve", user(park, "park"), null).andExpect(status().isOk());
        assertThat(acts(doc)).containsExactly(
                new ApprovalDocType.LineAct(apr, 1, LineType.AGREE, true, false, lee, null, null),
                new ApprovalDocType.LineAct(apr, 3, LineType.APPROVE, true, true, park, null, null));
        // 반려 = approve false·finishes true, 회수는 줄 처리가 아니라 부르지 않는다
        String doc2 = newDoc("QA2");
        long apr2 = submit(doc2, standardLine());
        act(apr2, "reject", user(lee, "lee"), "근거 부족").andExpect(status().isOk());
        assertThat(acts(doc2)).containsExactly(new ApprovalDocType.LineAct(apr2, 1, LineType.APPROVE, false, true, lee, null, null));
        String doc3 = newDoc("QA3");
        act(submit(doc3, standardLine()), "withdraw", user(kim, "kim"), null).andExpect(status().isOk());
        assertThat(acts(doc3)).isEmpty();
        // 차례가 아닌 사람·끝난 결재는 확장점까지 오지 않는다
        act(apr, "approve", user(lee, "lee"), null).andExpect(status().isConflict());
        assertThat(acts(doc)).hasSize(2);
    }

    @Test
    void 위임으로_대신_처리하면_actor_는_대리_onBehalfOf_는_원_결재자() throws Exception {
        accounts.exec(comp, """
                insert into apr_delegate (comp_cd, delegator_id, delegate_id, from_date, to_date, reason, created_by, updated_by)
                values (?, ?, ?, current_date - 2, current_date + 30, 'x', 't', 't')""", comp, lee, choi);
        String doc = newDoc("QA4");
        long apr = submit(doc, List.of(new LineSpec(lee, LineType.APPROVE), new LineSpec(park, LineType.APPROVE)));
        act(apr, "approve", user(choi, "choi"), null).andExpect(status().isOk());
        assertThat(acts(doc)).containsExactly(new ApprovalDocType.LineAct(apr, 1, LineType.APPROVE, true, false, choi, lee, null));
    }

    @Test
    void beforeAct_가_거부하면_줄_이력_알림_업무_상태가_모두_그대로() throws Exception {
        String doc = newDoc("NOACT");
        long apr = submit(doc, standardLine());
        int notifies = notify(apr);
        act(apr, "approve", user(lee, "lee"), null).andExpect(status().isBadRequest());
        act(apr, "reject", user(lee, "lee"), "x").andExpect(status().isBadRequest());
        assertThat(lines(apr)).isEqualTo("CURRENT,REF,WAIT");
        assertThat(hist(apr)).isEqualTo(1);   // SUBMIT 만
        assertThat(reqStatus(apr)).isEqualTo("PENDING");
        assertThat(notify(apr)).isEqualTo(notifies);
        assertThat(docUse(doc)).isEqualTo("N");
        // 중간 합의 줄만 막는 문서 유형 — 합의 줄은 거부, 그 문서의 승인 줄은 통과(docs/39 19.3 S2 의 쓰임)
        String doc2 = newDoc("NOACT_AGREE");
        long apr2 = submit(doc2, List.of(new LineSpec(lee, LineType.AGREE), new LineSpec(park, LineType.APPROVE)));
        act(apr2, "approve", user(lee, "lee"), null).andExpect(status().isBadRequest());
        assertThat(lines(apr2)).isEqualTo("CURRENT,WAIT");
        // 회수는 줄 처리가 아니다 — 확장점이 거부해도 요청자는 회수할 수 있다
        act(apr2, "withdraw", user(kim, "kim"), null).andExpect(status().isOk());
        assertThat(reqStatus(apr2)).isEqualTo("WITHDRAWN");
    }

    @Test
    void 결재_처리_요청의_서명_토큰은_beforeAct_로_넘어가고_문서_유형이_요구하면_없을_때_403() throws Exception {
        String doc = newDoc("SIGN");
        long apr = submit(doc, standardLine());
        // 토큰 없음·빈 토큰 = null → 이 문서 유형은 403, 줄 그대로
        act(apr, "approve", user(lee, "lee"), null).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ESIGN_TOKEN"));
        mvc.perform(post("/api/platform/approvals/" + apr + "/approve").with(user(lee, "lee")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"esignToken\":\"  \"}")).andExpect(status().isForbidden());
        assertThat(lines(apr)).isEqualTo("CURRENT,REF,WAIT");
        // 토큰이 있으면 그대로 넘어간다(검증·소비는 문서 유형 몫)
        mvc.perform(post("/api/platform/approvals/" + apr + "/approve").with(user(lee, "lee")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"comment\":\"ok\",\"esignToken\":\"tok-1\"}"))
                .andExpect(status().isOk());
        mvc.perform(post("/api/platform/approvals/" + apr + "/reject").with(user(park, "park")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"comment\":\"no\",\"esignToken\":\"tok-2\"}"))
                .andExpect(status().isOk());
        assertThat(acts(doc).stream().map(ApprovalDocType.LineAct::esignToken).toList())
                .containsExactly(null, null, "tok-1", "tok-2");
    }

    @Test
    void 상세는_지금_차례_줄의_서명_안내를_싣고_끝난_결재_서명_없는_문서는_null() throws Exception {
        String doc = newDoc("SIGN");
        long apr = submit(doc, List.of(new LineSpec(lee, LineType.AGREE), new LineSpec(park, LineType.APPROVE)));
        mvc.perform(get("/api/platform/approvals/" + apr).with(user(lee, "lee")))
                .andExpect(jsonPath("$.data.esignApprove.meaningCd").value("REVIEW"))
                .andExpect(jsonPath("$.data.esignApprove.targetType").value("test_doc"))
                .andExpect(jsonPath("$.data.esignReject").doesNotExist());
        mvc.perform(post("/api/platform/approvals/" + apr + "/approve").with(user(lee, "lee")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"esignToken\":\"t\"}")).andExpect(status().isOk());
        mvc.perform(get("/api/platform/approvals/" + apr).with(user(park, "park")))
                .andExpect(jsonPath("$.data.esignApprove.meaningCd").value("APPROVE"));
        mvc.perform(post("/api/platform/approvals/" + apr + "/reject").with(user(park, "park")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"comment\":\"x\"}")).andExpect(status().isOk());
        mvc.perform(get("/api/platform/approvals/" + apr).with(user(kim, "kim")))
                .andExpect(jsonPath("$.data.esignApprove").doesNotExist());   // 끝난 결재
        long plain = submit(newDoc("QS1"), standardLine());
        mvc.perform(get("/api/platform/approvals/" + plain).with(user(lee, "lee")))
                .andExpect(jsonPath("$.data.esignApprove").doesNotExist());
    }

    @Test
    void 끝난_결재의_문서는_다시_상신할_수_있다() {
        String doc = newDoc("QC6");
        long apr = submit(doc, standardLine());
        as(comp, kim, "kim", () -> {
            approvals.withdraw(apr, null);
            return null;
        });
        assertThat(submit(doc, standardLine())).isGreaterThan(apr);
    }

    // ---------- 이상 사용: 차례·권한 ----------

    @Test
    void 차례가_아닌_사람_요청자_참조자는_결재할_수_없다() throws Exception {
        long apr = submit(newDoc("QC7"), standardLine());
        act(apr, "approve", user(park, "park"), null).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("APR_NOT_YOUR_TURN"));
        act(apr, "approve", user(kim, "kim"), null).andExpect(status().isForbidden());
        act(apr, "approve", user(choi, "choi"), null).andExpect(status().isForbidden());
        act(apr, "reject", user(park, "park"), "끼어들기").andExpect(status().isForbidden());
        act(apr, "withdraw", user(lee, "lee"), null).andExpect(status().isForbidden());
        assertThat(reqStatus(apr)).isEqualTo("PENDING");
    }

    @Test
    void 끝난_결재를_다시_처리하면_409() throws Exception {
        long apr = submit(newDoc("QC8"), List.of(new LineSpec(lee, LineType.APPROVE)));
        act(apr, "approve", user(lee, "lee"), null).andExpect(status().isOk());
        act(apr, "approve", user(lee, "lee"), null).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("APR_CLOSED"));
        act(apr, "reject", user(lee, "lee"), "뒤늦게").andExpect(status().isConflict());
        act(apr, "withdraw", user(kim, "kim"), null).andExpect(status().isConflict());
    }

    @Test
    void 결재선에_없는_사람은_상세도_404() throws Exception {
        long apr = submit(newDoc("QC9"), standardLine());
        long outsider = accounts.newUser(comp, "outsider", PW);
        mvc.perform(get("/api/platform/approvals/" + apr).with(user(outsider, "outsider")))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/platform/approvals/999999999").with(user(kim, "kim"))).andExpect(status().isNotFound());
    }

    @Test
    void B사_사용자가_A사_결재번호로_조회_승인_반려_회수() throws Exception {
        long apr = submit(newDoc("QCA"), standardLine());
        String other = accounts.newComp("AP");
        long bUser = accounts.newUser(other, "lee", PW);
        RequestPostProcessor b = accounts.as(other, bUser, "lee");
        mvc.perform(get("/api/platform/approvals/" + apr).with(b)).andExpect(status().isNotFound());
        for (String action : List.of("approve", "reject", "withdraw")) {
            act(apr, action, b, "남의 회사").andExpect(status().isNotFound());
        }
        assertThat(reqStatus(apr)).isEqualTo("PENDING");
    }

    // ---------- 이상 사용: 결재선 ----------

    private void expectLineError(List<LineSpec> lines, String detailFragment) {
        String doc = newDoc("L" + System.nanoTime() % 1_000_000);
        assertThatThrownBy(() -> submit(doc, lines))
                .isInstanceOfSatisfying(BusinessException.class, e -> {
                    assertThat(e.code()).isEqualTo(HbErrorCode.APR_INVALID_LINE);
                    assertThat(e.details().get(0).messageKey()).contains(detailFragment);
                });
    }

    @Test
    void 결재선_규칙_위반은_상신_거부() {
        String other = accounts.newComp("AP");
        long foreign = accounts.newUser(other, "foreign", PW);
        long disabled = accounts.newUser(comp, "disabled", PW);
        accounts.setUserUse(comp, disabled, "N");

        expectLineError(List.of(), "empty");
        expectLineError(List.of(new LineSpec(lee, LineType.AGREE), new LineSpec(choi, LineType.REF)), "noApprover");
        expectLineError(List.of(new LineSpec(lee, LineType.APPROVE), new LineSpec(lee, LineType.REF)), "duplicate");
        expectLineError(List.of(new LineSpec(kim, LineType.APPROVE)), "self");
        expectLineError(List.of(new LineSpec(foreign, LineType.APPROVE)), "inactive");
        expectLineError(List.of(new LineSpec(disabled, LineType.APPROVE)), "inactive");
        expectLineError(List.of(new LineSpec(-1, LineType.APPROVE)), "inactive");
        runner.run(comp, () -> policies.set(Policies.APR_MAX_LINES, null, "2", "t"));
        expectLineError(List.of(new LineSpec(lee, LineType.AGREE), new LineSpec(choi, LineType.REF),
                new LineSpec(park, LineType.APPROVE)), "tooMany");
        assertThat(accounts.query(comp, "select count(*) from apr_request", Integer.class)).isZero();
    }

    @Test
    void 회사가_허용하면_본인_결재_가능() {
        runner.run(comp, () -> policies.set(Policies.APR_SELF_APPROVE_ALLOWED, null, "Y", "t"));
        long apr = submit(newDoc("QCB"), List.of(new LineSpec(kim, LineType.APPROVE)));
        as(comp, kim, "kim", () -> {
            approvals.approve(apr, null);
            return null;
        });
        assertThat(reqStatus(apr)).isEqualTo("APPROVED");
    }

    @Test
    void 진행_중인_문서를_다시_상신하면_409_모르는_문서유형_검증거부는_400() {
        String doc = newDoc("QCC");
        submit(doc, standardLine());
        assertThatThrownBy(() -> submit(doc, standardLine())).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.code()).isEqualTo(HbErrorCode.APR_ALREADY_PENDING));
        assertThatThrownBy(() -> as(comp, kim, "kim", () -> approvals.submit("NO_SUCH", "X1", "t", standardLine())))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.code()).isEqualTo(HbErrorCode.INVALID_VALUE));
        assertThatThrownBy(() -> submit("BLOCKED", standardLine())).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> submit("../x", standardLine())).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> as(comp, kim, "kim", () -> approvals.submit(DOC, "X2", "   ", standardLine())))
                .isInstanceOf(BusinessException.class);
        assertThat(accounts.query(comp, "select count(*) from apr_request", Integer.class)).isEqualTo(1);
    }

    // ---------- 이상 사용: 트랜잭션·동시성 ----------

    @Test
    void 업무_상태_변경이_실패하면_결재_처리_전체가_롤백() {
        newDoc("EXPLODE");
        long apr = submit("EXPLODE", List.of(new LineSpec(lee, LineType.APPROVE)));
        int outboxBefore = notify(apr);
        assertThatThrownBy(() -> as(comp, lee, "lee", () -> {
            approvals.approve(apr, null);
            return null;
        })).isInstanceOf(IllegalStateException.class);
        assertThat(reqStatus(apr)).isEqualTo("PENDING");
        assertThat(accounts.query(comp, "select status from apr_line where apr_id = ?", String.class, apr))
                .isEqualTo("CURRENT");
        assertThat(accounts.query(comp, "select count(*) from apr_history where apr_id = ?", Integer.class, apr))
                .isEqualTo(1);
        assertThat(notify(apr)).isEqualTo(outboxBefore);
    }

    @Test
    void 같은_멱등키로_두_번_보내면_두_번째는_409() throws Exception {
        long apr = submit(newDoc("QCD"), standardLine());
        String key = "click-" + apr + "-0001";
        mvc.perform(post("/api/platform/approvals/" + apr + "/approve").with(user(lee, "lee")).with(csrf())
                .header("X-Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk());
        mvc.perform(post("/api/platform/approvals/" + apr + "/approve").with(user(lee, "lee")).with(csrf())
                .header("X-Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DUPLICATE_REQUEST"));
        assertThat(accounts.query(comp, "select count(*) from apr_history where apr_id = ? and action = 'APPROVE'",
                Integer.class, apr)).isEqualTo(1);
    }

    @Test
    void 마지막_결재자가_동시에_두_번_눌러도_업무_처리는_한_번() throws Exception {
        String doc = newDoc("QCE");
        long apr = submit(doc, List.of(new LineSpec(lee, LineType.APPROVE)));
        int before = TestDocType.FINISHED.get();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<String>> fs = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                fs.add(pool.submit(() -> {
                    start.await();
                    try {
                        as(comp, lee, "lee", () -> {
                            approvals.approve(apr, null);
                            return null;
                        });
                        return "OK";
                    } catch (BusinessException e) {
                        return e.code().name();
                    }
                }));
            }
            start.countDown();
            List<String> results = new ArrayList<>();
            for (Future<String> f : fs) {
                results.add(f.get(30, TimeUnit.SECONDS));
            }
            assertThat(results).containsExactlyInAnyOrder("OK", "APR_CLOSED");
        } finally {
            pool.shutdownNow();
        }
        assertThat(TestDocType.FINISHED.get() - before).isEqualTo(1);
    }

    // ---------- 이상 사용: DB 직접 ----------

    @Test
    void 결재_이력은_고칠_수_없고_다른_회사_사용자를_결재선에_넣을_수_없다() {
        long apr = submit(newDoc("QCF"), standardLine());
        assertThatThrownBy(() -> accounts.exec(comp, "update apr_history set comment = '조작'")).rootCause()
                .hasMessageContaining("permission denied");
        assertThatThrownBy(() -> accounts.exec(comp, "delete from apr_request")).rootCause()
                .hasMessageContaining("permission denied");
        String other = accounts.newComp("AP");
        long foreign = accounts.newUser(other, "xuser", PW);
        assertThatThrownBy(() -> accounts.exec(comp, """
                insert into apr_line (comp_cd, apr_id, seq, approver_id, line_type) values (?, ?, 50, ?, 'APPROVE')""",
                comp, apr, foreign)).rootCause().hasMessageContaining("does not belong");
    }

    @Test
    void 결재_변경은_감사로그에_처리자와_함께_남는다() throws Exception {
        long apr = submit(newDoc("QCG"), List.of(new LineSpec(lee, LineType.APPROVE)));
        act(apr, "approve", user(lee, "lee"), null).andExpect(status().isOk());
        assertThat(accounts.query(comp, """
                select count(*) from sys_audit_log where table_nm = 'apr_request' and row_key = ? and actor = ?""",
                Integer.class, String.valueOf(apr), "u" + lee + ":lee")).isEqualTo(1);
    }

    @Test
    void 비로그인은_401() throws Exception {
        mvc.perform(get("/api/platform/approvals/todo")).andExpect(status().isUnauthorized());
    }
}
