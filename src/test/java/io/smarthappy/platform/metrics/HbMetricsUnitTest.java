package io.smarthappy.platform.metrics;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.smarthappy.platform.config.HappyBootProperties;
import io.smarthappy.platform.tenant.TenantContext;

/** 지표 단위 시험 — 회사 꼬리표 규칙(Q2: SaaS 에서만, 기본 끔), 수집 끝점 문지기(허용 IP·토큰) */
class HbMetricsUnitTest {

    private static HappyBootProperties saas() {
        return new HappyBootProperties(new HappyBootProperties.Deploy("saas", null), null, null, null);
    }

    private static HappyBootProperties onprem() {
        return new HappyBootProperties(new HappyBootProperties.Deploy("onprem", "ZT1"), null, null, null);
    }

    @Test
    void 회사_꼬리표는_SaaS_에서_켰을_때만() {
        assertThat(new HbMetrics(new SimpleMeterRegistry(), new MetricsProperties(false, null, null), saas()).compTagEnabled()).isFalse();
        assertThat(new HbMetrics(new SimpleMeterRegistry(), new MetricsProperties(true, null, null), saas()).compTagEnabled()).isTrue();
        assertThat(new HbMetrics(new SimpleMeterRegistry(), new MetricsProperties(true, null, null), onprem()).compTagEnabled())
                .as("설치형은 켜도 무시").isFalse();
    }

    @Test
    void 켜면_회사_범위의_회사코드가_꼬리표로_꺼지면_없음() {
        SimpleMeterRegistry on = new SimpleMeterRegistry();
        HbMetrics m = new HbMetrics(on, new MetricsProperties(true, null, null), saas());
        try (TenantContext.Scope ignored = TenantContext.openRequestScope("ZTA", "u1:probe")) {
            m.tenantForbidden();
        }
        m.tenantForbidden();   // 회사 범위 밖 → "-"
        assertThat(on.find("hb.tenant.forbidden").tag("comp", "ZTA").counter().count()).isEqualTo(1.0);
        assertThat(on.find("hb.tenant.forbidden").tag("comp", "-").counter().count()).isEqualTo(1.0);

        SimpleMeterRegistry off = new SimpleMeterRegistry();
        HbMetrics m2 = new HbMetrics(off, new MetricsProperties(false, null, null), saas());
        try (TenantContext.Scope ignored = TenantContext.openRequestScope("ZTA", "u1:probe")) {
            m2.tenantForbidden();
        }
        assertThat(off.find("hb.tenant.forbidden").counter().getId().getTag("comp")).isNull();
    }

    @Test
    void 꼬리표_값은_코드_형식만_나머지는_other() {
        SimpleMeterRegistry r = new SimpleMeterRegistry();
        HbMetrics m = new HbMetrics(r, new MetricsProperties(false, null, null), saas());
        m.popResend("result");
        m.popResend("x\ny");   // 이상 값 → other (시계열 폭발·위조 방지)
        m.inboxDuplicate("API:cust-save");
        assertThat(r.find("hb.pop.resend").tag("kind", "result").counter().count()).isEqualTo(1.0);
        assertThat(r.find("hb.pop.resend").tag("kind", "other").counter().count()).isEqualTo(1.0);
        assertThat(r.find("hb.inbox.duplicate").tag("consumer", "API").counter().count()).isEqualTo(1.0);
    }

    @Test
    void 문지기_허용_IP_와_토큰() throws Exception {
        MetricsSecurityConfig.GateFilter open = new MetricsSecurityConfig.GateFilter(new MetricsProperties(false, "10.0.0.0/8", ""));
        assertThat(pass(open, "10.1.2.3", null, "GET")).isTrue();
        assertThat(pass(open, "203.0.113.5", null, "GET")).isFalse();
        assertThat(pass(open, "10.1.2.3", null, "POST")).isFalse();

        String token = "t".repeat(40);
        MetricsSecurityConfig.GateFilter guarded = new MetricsSecurityConfig.GateFilter(new MetricsProperties(false, "10.0.0.0/8", token));
        assertThat(pass(guarded, "10.1.2.3", null, "GET")).isFalse();
        assertThat(pass(guarded, "10.1.2.3", "Bearer wrong", "GET")).isFalse();
        assertThat(pass(guarded, "10.1.2.3", "Basic " + token, "GET")).isFalse();
        assertThat(pass(guarded, "10.1.2.3", "Bearer " + token, "GET")).isTrue();
        assertThat(pass(guarded, "203.0.113.5", "Bearer " + token, "GET")).as("토큰이 맞아도 밖 IP 는 404").isFalse();
    }

    private static boolean pass(MetricsSecurityConfig.GateFilter f, String ip, String auth, String method) throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest(method, MetricsSecurityConfig.PATH);
        req.setRemoteAddr(ip);
        if (auth != null) {
            req.addHeader("Authorization", auth);
        }
        MockHttpServletResponse res = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        f.doFilter(req, res, chain);
        if (chain.getRequest() == null) {
            assertThat(res.getStatus()).isEqualTo(404);
            return false;
        }
        return true;
    }
}
