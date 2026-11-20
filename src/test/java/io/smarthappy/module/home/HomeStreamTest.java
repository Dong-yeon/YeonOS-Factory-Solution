package io.smarthappy.module.home;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import io.smarthappy.platform.notify.NotificationService;
import io.smarthappy.platform.notify.NotifyMessage;
import io.smarthappy.platform.notify.PlatformNotifyEvents;
import io.smarthappy.platform.realtime.SseHub;
import io.smarthappy.platform.tenant.TenantRunner;
import io.smarthappy.support.PostgresTestBase;
import io.smarthappy.support.TestAccounts;

/**
 * 홈 실시간 신호(SSE 시범, docs/70 3장) — 알림이 들어가면 그 사용자의 홈 연결에만 "inbox" 신호, 본문 없음.
 * 이상 사용: A사로 B사(다른 회사·같은 로그인 아이디), 다른 사용자, 롤백된 알림, 권한 없음·로그인 없음, 탭 많이 열기.
 */
@AutoConfigureMockMvc
class HomeStreamTest extends PostgresTestBase {

    private static final String PW = "Cobalt-meadow-28";

    @Autowired
    MockMvc mvc;

    @Autowired
    TestAccounts accounts;

    @Autowired
    TenantRunner runner;

    @Autowired
    NotificationService notify;

    @Autowired
    SseHub hub;

    String compA;
    long userA;
    long userA2;
    String compB;
    long userB;
    RequestPostProcessor asA;
    RequestPostProcessor asB;

    @BeforeEach
    void setUp() {
        compA = accounts.newComp("SA");
        userA = accounts.newUser(compA, "kim", PW);
        userA2 = accounts.newUser(compA, "lee", PW);
        accounts.grant(compA, userA, "HM", HomeService.PROG, "SEARCH");
        accounts.grant(compA, userA2, "HM", HomeService.PROG, "SEARCH");
        asA = accounts.as(compA, userA, "kim");
        compB = accounts.newComp("SB");
        userB = accounts.newUser(compB, "kim", PW);
        accounts.grant(compB, userB, "HM", HomeService.PROG, "SEARCH");
        asB = accounts.as(compB, userB, "kim");
    }

    private void sendTodo(String comp, long user, String dedup) {
        runner.run(comp, () -> notify.send(NotifyMessage.of(PlatformNotifyEvents.APR_TODO, user, dedup, "notify.apr.todo", "문서", "요청자")));
    }

    private MvcResult open(RequestPostProcessor who) throws Exception {
        return mvc.perform(get("/api/home/stream").with(who))
                .andExpect(request().asyncStarted())
                .andExpect(header().string("Content-Type", org.hamcrest.Matchers.startsWith("text/event-stream")))
                .andReturn();
    }

    private static String body(MvcResult r) throws Exception {
        return r.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
    }

    @Test
    void 알림이_들어가면_그_사용자에게만_본문_없는_신호() throws Exception {
        MvcResult a = open(asA);
        MvcResult b = open(asB);
        assertThat(body(a)).contains("event:ready");
        sendTodo(compA, userA, "S1");
        assertThat(body(a)).contains("event:inbox").contains("data:1");
        // 알림 제목·요청자 같은 내용은 신호에 실리지 않는다
        assertThat(body(a)).doesNotContain("문서").doesNotContain("요청자");
        // A사로 B사: B사의 같은 로그인 아이디(kim) 연결은 신호를 받지 않는다
        assertThat(body(b)).doesNotContain("event:inbox");
        // 같은 회사 다른 사용자에게 간 알림은 A 에게 오지 않는다
        String before = body(a);
        sendTodo(compA, userA2, "S2");
        assertThat(body(a)).isEqualTo(before);
        // B사 사용자 알림은 B 에게만
        sendTodo(compB, userB, "S3");
        assertThat(body(b)).contains("event:inbox");
        assertThat(body(a)).isEqualTo(before);
    }

    @Test
    void 롤백된_알림은_신호도_없다() throws Exception {
        MvcResult a = open(asA);
        Throwable t = catchThrowable(() -> runner.run(compA, () -> {
            notify.send(NotifyMessage.of(PlatformNotifyEvents.APR_TODO, userA, "R1", "notify.apr.todo", "문서", "요청자"));
            throw new IllegalStateException("업무 실패");
        }));
        assertThat(t).isInstanceOf(IllegalStateException.class);
        assertThat(body(a)).doesNotContain("event:inbox");
    }

    @Test
    void 홈_권한이_없거나_로그인이_없으면_열리지_않는다() throws Exception {
        long noPerm = accounts.newUser(compA, "park", PW);
        mvc.perform(get("/api/home/stream").with(accounts.as(compA, noPerm, "park"))).andExpect(status().isForbidden());
        mvc.perform(get("/api/home/stream")).andExpect(status().isUnauthorized());
    }

    @Test
    void 탭을_많이_열어도_사용자당_상한() throws Exception {
        int before = hub.size();
        for (int i = 0; i < 8; i++) {
            open(asA);
        }
        // 사용자당 5개까지 — 넘친 것은 오래된 것부터 닫힘
        assertThat(hub.size() - before).isLessThanOrEqualTo(5);
    }
}
