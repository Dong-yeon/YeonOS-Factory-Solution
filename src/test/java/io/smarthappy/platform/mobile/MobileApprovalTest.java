package io.smarthappy.platform.mobile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import io.smarthappy.platform.approval.ApprovalService;
import io.smarthappy.platform.approval.ApprovalService.LineSpec;
import io.smarthappy.platform.approval.ApprovalService.LineType;
import io.smarthappy.platform.security.LoginUser;
import io.smarthappy.platform.tenant.TenantRunner;
import io.smarthappy.support.PostgresTestBase;
import io.smarthappy.support.TestAccounts;
import io.smarthappy.support.TestMessaging.TestDocType;

/**
 * 모바일 결재 승인 (/m/approval, docs/74 화면 2·M7) — 화면이 쓰는 기존 결재 API 를 모바일 화면의 순서대로:
 * 내 결재함 목록 → 상세 → 승인·반려. 이상 사용: A사 계정으로 B사 결재(404), 내 차례가 아닌 사람(거부),
 * 같은 승인 두 번(같은 키 409 / 새 키 거부 — 이력 한 줄), 사유 없는 반려(400), CSRF 없는 처리(403).
 */
@AutoConfigureMockMvc
class MobileApprovalTest extends PostgresTestBase {

    private static final String PW = "Cedar-lantern-52";
    private static final String API = "/api/platform/approvals";

    @Autowired
    MockMvc mvc;

    @Autowired
    TestAccounts accounts;

    @Autowired
    TenantRunner runner;

    @Autowired
    ApprovalService approvals;

    String comp;
    long kim;
    long lee;
    long park;
    RequestPostProcessor asKim;
    RequestPostProcessor asLee;
    RequestPostProcessor asPark;

    @BeforeEach
    void setUp() {
        TestDocType.ACTS.clear();
        comp = accounts.newComp("MA");
        kim = accounts.newUser(comp, "kim", PW);    // 요청자
        lee = accounts.newUser(comp, "lee", PW);    // 1차 결재
        park = accounts.newUser(comp, "park", PW);  // 2차 결재
        asKim = accounts.as(comp, kim, "kim");
        asLee = accounts.as(comp, lee, "lee");
        asPark = accounts.as(comp, park, "park");
    }

    /** 결재 대상 업무 문서 = 사용 안 함 상태의 권한그룹(ApprovalTest 와 같은 시험 문서 유형) → 상신 */
    private long submit(String c, long requester, String login, String code, long... approvers) {
        accounts.exec(c, """
                insert into sys_auth_group (comp_cd, auth_grp_cd, auth_grp_nm, use_yn, created_by, updated_by)
                values (?, ?, ?, 'N', 't', 't')""", c, code, code);
        List<LineSpec> lines = java.util.Arrays.stream(approvers).mapToObj(a -> new LineSpec(a, LineType.APPROVE)).toList();
        LoginUser user = new LoginUser(requester, c, login, login, "ko", false);
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                user, null, List.of(new SimpleGrantedAuthority("ROLE_USER"))));
        try {
            return runner.call(c, () -> approvals.submit(TestDocType.DOC_TYPE, code, "권한그룹 " + code + " 사용 승인", lines));
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private ResultActions act(long aprId, String action, RequestPostProcessor who, String key, String body) throws Exception {
        var req = post(API + "/" + aprId + "/" + action).with(who).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body);
        return mvc.perform(key == null ? req : req.header("X-Idempotency-Key", key));
    }

    private String reqStatus(String c, long aprId) {
        return accounts.query(c, "select status from apr_request where apr_id = ?", String.class, aprId);
    }

    private int history(long aprId, String action) {
        return accounts.query(comp, "select count(*) from apr_history where apr_id = ? and action = ?", Integer.class, aprId, action);
    }

    @Test
    void 화면_순서대로_목록_상세_승인_다음_결재자_승인_끝() throws Exception {
        long apr = submit(comp, kim, "kim", "M1", lee, park);
        mvc.perform(get("/m/approval").with(asLee)).andExpect(status().isOk());
        // 내 차례 목록 — 1차 결재자에게만
        mvc.perform(get(API + "/box").param("box", "TODO").with(asLee)).andExpect(jsonPath("$.list.length()").value(1))
                .andExpect(jsonPath("$.list[0].aprId").value(apr)).andExpect(jsonPath("$.list[0].myTurn").value(true))
                .andExpect(jsonPath("$.list[0].requesterNm").value("kim 사용자"));
        mvc.perform(get(API + "/box").param("box", "TODO").with(asPark)).andExpect(jsonPath("$.list.length()").value(0));
        mvc.perform(get(API + "/" + apr).with(asLee)).andExpect(jsonPath("$.data.request.status").value("PENDING"))
                .andExpect(jsonPath("$.data.lines.length()").value(2)).andExpect(jsonPath("$.data.lines[0].status").value("CURRENT"));

        act(apr, "approve", asLee, "m-apr-" + apr + "-approve-1", "{\"comment\":\"확인\"}").andExpect(status().isOk());
        assertThat(reqStatus(comp, apr)).isEqualTo("PENDING");
        mvc.perform(get(API + "/box").param("box", "TODO").with(asLee)).andExpect(jsonPath("$.list.length()").value(0));
        mvc.perform(get(API + "/box").param("box", "TODO").with(asPark)).andExpect(jsonPath("$.list[0].aprId").value(apr));
        act(apr, "approve", asPark, "m-apr-" + apr + "-approve-2", "{\"comment\":\"\"}").andExpect(status().isOk());
        assertThat(reqStatus(comp, apr)).isEqualTo("APPROVED");
        // 요청자의 "내가 올린 것" 에 결과가 보인다
        mvc.perform(get(API + "/box").param("box", "REQUESTED").with(asKim)).andExpect(jsonPath("$.list[0].status").value("APPROVED"));
    }

    @Test
    void 같은_승인_두_번_같은_키는_409_새_키는_거부_이력은_한_줄() throws Exception {
        long apr = submit(comp, kim, "kim", "M2", lee, park);
        String key = "m-apr-" + apr + "-approve-0001";
        act(apr, "approve", asLee, key, "{}").andExpect(status().isOk());
        // 같은 누름의 재전송(같은 키)
        act(apr, "approve", asLee, key, "{}").andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DUPLICATE_REQUEST"));
        // 화면을 다시 읽지 않고 한 번 더 누름(새 키) — 이제 내 차례가 아니다
        int again = act(apr, "approve", asLee, "m-apr-" + apr + "-approve-0002", "{}").andReturn().getResponse().getStatus();
        assertThat(again).isBetween(400, 499);
        // 키 없이 직접 한 번 더
        assertThat(act(apr, "approve", asLee, null, "{}").andReturn().getResponse().getStatus()).isBetween(400, 499);
        assertThat(history(apr, "APPROVE")).isEqualTo(1);
        assertThat(reqStatus(comp, apr)).isEqualTo("PENDING");   // 2차 결재자 차례 그대로
        assertThat(accounts.query(comp, "select count(*) from apr_line where apr_id = ? and status = 'APPROVED'", Integer.class, apr)).isEqualTo(1);
    }

    @Test
    void 반려는_사유가_있어야_하고_반려하면_끝난다() throws Exception {
        long apr = submit(comp, kim, "kim", "M3", lee, park);
        act(apr, "reject", asLee, "key-0000-k1", "{\"comment\":\"\"}").andExpect(status().isBadRequest());
        act(apr, "reject", asLee, "key-0000-k2", "{\"comment\":\"\\u200b \\u00a0\"}").andExpect(status().isBadRequest());   // 보이지 않는 글자뿐
        act(apr, "reject", asLee, "key-0000-k3", "{}").andExpect(status().isBadRequest());
        assertThat(reqStatus(comp, apr)).isEqualTo("PENDING");
        act(apr, "reject", asLee, "key-0000-k4", "{\"comment\":\"수량 다시 확인\"}").andExpect(status().isOk());
        assertThat(reqStatus(comp, apr)).isEqualTo("REJECTED");
        assertThat(history(apr, "REJECT")).isEqualTo(1);
        // 끝난 결재를 다시 처리할 수 없다
        assertThat(act(apr, "approve", asPark, "key-0000-k5", "{}").andReturn().getResponse().getStatus()).isBetween(400, 499);
        assertThat(act(apr, "reject", asLee, "key-0000-k6", "{\"comment\":\"한 번 더\"}").andReturn().getResponse().getStatus()).isBetween(400, 499);
        assertThat(history(apr, "REJECT")).isEqualTo(1);
    }

    @Test
    void 내_차례가_아닌_사람_결재선_밖_사람은_처리할_수_없다() throws Exception {
        long apr = submit(comp, kim, "kim", "M4", lee, park);
        long choi = accounts.newUser(comp, "choi", PW);   // 결재선 밖
        RequestPostProcessor asChoi = accounts.as(comp, choi, "choi");
        assertThat(act(apr, "approve", asPark, "key-0000-p1", "{}").andReturn().getResponse().getStatus()).isBetween(400, 499);   // 아직 2차 차례 아님
        assertThat(act(apr, "approve", asKim, "key-0000-p2", "{}").andReturn().getResponse().getStatus()).isBetween(400, 499);    // 요청자 본인
        act(apr, "approve", asChoi, "key-0000-p3", "{}").andExpect(status().isForbidden());   // 같은 회사·결재선 밖 = 처리 거부(상세는 404)
        mvc.perform(get(API + "/" + apr).with(asChoi)).andExpect(status().isNotFound());
        act(apr, "reject", asChoi, "key-0000-p4", "{\"comment\":\"남의 결재\"}").andExpect(status().isForbidden());
        // CSRF 없는 처리
        mvc.perform(post(API + "/" + apr + "/approve").with(asLee).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
        assertThat(history(apr, "APPROVE")).isZero();
        assertThat(reqStatus(comp, apr)).isEqualTo("PENDING");
    }

    @Test
    void A사_계정으로_B사_결재는_목록에_없고_상세_승인_반려_모두_404() throws Exception {
        String other = accounts.newComp("MZ");
        long bKim = accounts.newUser(other, "kim", PW);
        long bLee = accounts.newUser(other, "lee", PW);
        long bApr = submit(other, bKim, "kim", "B1", bLee);
        long aApr = submit(comp, kim, "kim", "A1", lee);

        // A사 lee 의 목록에는 A사 것만
        mvc.perform(get(API + "/box").param("box", "TODO").with(asLee)).andExpect(jsonPath("$.list.length()").value(1))
                .andExpect(jsonPath("$.list[0].aprId").value(aApr));
        mvc.perform(get(API + "/" + bApr).with(asLee)).andExpect(status().isNotFound());
        act(bApr, "approve", asLee, "key-0000-x1", "{}").andExpect(status().isNotFound());
        act(bApr, "reject", asLee, "key-0000-x2", "{\"comment\":\"다른 회사\"}").andExpect(status().isNotFound());
        assertThat(reqStatus(other, bApr)).isEqualTo("PENDING");
        assertThat(accounts.query(other, "select count(*) from apr_history where apr_id = ? and action in ('APPROVE', 'REJECT')",
                Integer.class, bApr)).isZero();
        // B사 결재자는 자기 것을 정상 처리
        act(bApr, "approve", accounts.as(other, bLee, "lee"), "key-0000-x3", "{}").andExpect(status().isOk());
        assertThat(reqStatus(other, bApr)).isEqualTo("APPROVED");
        assertThat(reqStatus(comp, aApr)).isEqualTo("PENDING");
    }
}
