package io.smarthappy.qa;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.jayway.jsonpath.JsonPath;

import io.smarthappy.platform.common.web.BusinessException;
import io.smarthappy.platform.common.web.HbErrorCode;
import io.smarthappy.platform.esign.ESignService;
import io.smarthappy.platform.security.LoginUser;
import io.smarthappy.platform.tenant.TenantRunner;
import io.smarthappy.support.PostgresTestBase;
import io.smarthappy.support.TestAccounts;

/**
 * QA 2026-10-22 ③ — 시스템관리 전자서명 재인증(ESignService, V116). 모듈 시험(ESignTest)이 재사용·만료·다른 사용자·5회·동시·연결 풀을 본다.
 * 여기서는 그 사이: 토큰 개수 상한(6번째 발급), 잘못 내민 토큰의 소모, 끊긴 세션으로 서명. 모두 관찰(설계 확인) — 결함 없음.
 */
@AutoConfigureMockMvc
class ESignQaTest extends PostgresTestBase {

    static final String PW = "Linen-meadow-29";
    static final String API = "/api/platform/esign";
    static final String HASH = ESignService.hash("QA|1");

    @Autowired
    MockMvc mvc;

    @Autowired
    TestAccounts accounts;

    @Autowired
    ESignService esign;

    @Autowired
    TenantRunner runner;

    String comp;
    long qaId;

    @BeforeEach
    void setUp() {
        comp = accounts.newComp("QE");
        qaId = accounts.newUser(comp, "qa", PW);
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private ResultActions token(MockHttpSession s, long target, String pw) throws Exception {
        return mvc.perform(post(API + "/token").with(csrf()).with(accounts.as(comp, qaId, "qa")).session(s)
                .contentType(MediaType.APPLICATION_JSON).content("{\"meaningCd\":\"APPROVE\",\"targetType\":\"qa_doc\",\"targetId\":" + target
                        + ",\"password\":\"" + pw + "\"}"));
    }

    private String issued(MockHttpSession s, long target) throws Exception {
        return JsonPath.read(token(s, target, PW).andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.data.token");
    }

    /** 업무 트랜잭션 안에서 consume → 서명 id, 거부면 오류 코드 */
    private Object consume(MockHttpSession s, String tok, long target) {
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                new LoginUser(qaId, comp, "qa", "qa", "ko", false), null, List.of(new SimpleGrantedAuthority("ROLE_USER"))));
        try {
            Supplier<Object> work = () -> esign.consume(s, tok, "APPROVE", "qa_doc", target, HASH);
            return runner.call(comp, work);
        } catch (BusinessException e) {
            return e.code();
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    @Test
    void 여섯_번째_토큰을_받으면_가장_오래된_하나만_사라진다() throws Exception {
        MockHttpSession s = new MockHttpSession();
        List<String> toks = new ArrayList<>();
        for (long t = 1; t <= 6; t++) {
            toks.add(issued(s, t));
        }
        // 상한 5개를 넘으면 가장 오래된 것 하나만 — 여러 탭에서 연 서명 대화상자 2~5 는 그대로 쓴다(QA O-2 수정, docs/36 16.6)
        assertThat(consume(s, toks.get(0), 1)).isEqualTo(HbErrorCode.ESIGN_TOKEN);
        for (int i = 1; i < 6; i++) {
            assertThat(consume(s, toks.get(i), i + 1)).isInstanceOf(Long.class);
        }
    }

    @Test
    void 관찰_다른_대상으로_내민_토큰은_소모되어_맞는_대상으로도_다시_쓸_수_없다() throws Exception {
        MockHttpSession s = new MockHttpSession();
        String t = issued(s, 10);
        assertThat(consume(s, t, 11)).isEqualTo(HbErrorCode.ESIGN_TOKEN);
        assertThat(consume(s, t, 10)).isEqualTo(HbErrorCode.ESIGN_TOKEN);       // 실패 닫힘 — 다시 재인증
        assertThat(accounts.query(comp, "select count(*) from sys_esign", Integer.class)).isZero();
    }

    @Test
    void 끊긴_세션의_토큰으로_서명하면_500_이_아니라_ESIGN_TOKEN() throws Exception {
        MockHttpSession s = new MockHttpSession();
        String t = issued(s, 7);
        s.invalidate();
        assertThat(consume(s, t, 7)).isEqualTo(HbErrorCode.ESIGN_TOKEN);
    }
}
