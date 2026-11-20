package io.smarthappy.platform.metrics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.smarthappy.platform.common.web.RequestIdFilter;
import io.smarthappy.platform.job.JobLock;
import io.smarthappy.platform.messaging.InboxService;
import io.smarthappy.platform.tenant.TenantRunner;
import io.smarthappy.support.PostgresTestBase;
import io.smarthappy.support.TestAccounts;

/**
 * 운영 지표 (docs/52 Y2) — 끝점 노출 범위 + 업무 지표가 "한 번 하면 정확히 +1" + 회사 경계.
 * 이상 사용: 밖 IP·로그인한 회사 관리자·POST 로 끝점 접근(B·C), 아웃박스 집계에 다른 회사 행이 섞이는지(A사로 B사), 요청 ID 위조(D).
 */
@AutoConfigureMockMvc
class OpsMetricsTest extends PostgresTestBase {

    private static final AtomicInteger SEQ = new AtomicInteger();

    @Autowired
    MockMvc mvc;

    @Autowired
    TestAccounts accounts;

    @Autowired
    MeterRegistry registry;

    @Autowired
    TenantRunner runner;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    InboxService inbox;


    @Autowired
    JobLock jobLock;

    @Autowired
    OpsGauges gauges;

    private static RequestPostProcessor from(String ip) {
        return r -> {
            r.setRemoteAddr(ip);
            return r;
        };
    }

    private double count(String name, String... tags) {
        Counter c = registry.find(name).tags(tags).counter();
        return c == null ? 0 : c.count();
    }

    // ---------- 끝점 노출 ----------

    @Test
    void 지표_끝점은_허용_IP_에서만_열린다() throws Exception {
        gauges.refresh();
        mvc.perform(get("/actuator/prometheus")).andExpect(status().isOk())
                .andExpect(content().string(containsString("hb_backup_age_hours")))
                .andExpect(content().string(containsString("jvm_memory_used_bytes")));
        // 밖 IP → 404(있는지도 알리지 않음)
        mvc.perform(get("/actuator/prometheus").with(from("203.0.113.7"))).andExpect(status().isNotFound());
        mvc.perform(get("/actuator/prometheus").with(from("10.99.1.1"))).andExpect(status().isNotFound());   // 운영자 콘솔 허용 대역이어도
    }

    @Test
    void 로그인한_회사_관리자도_밖에서는_못_본다_POST_도_404() throws Exception {
        String comp = accounts.newComp("MX");
        long admin = accounts.newUser(comp, "admin", "metrics-pw-1!");
        mvc.perform(get("/actuator/prometheus").with(accounts.as(comp, admin, "admin")).with(from("198.51.100.9")))
                .andExpect(status().isNotFound());
        mvc.perform(post("/actuator/prometheus").with(csrf())).andExpect(status().isNotFound());
    }

    @Test
    void 회사_꼬리표는_기본_끔() throws Exception {
        String comp = accounts.newComp("MT");
        long u = accounts.newUser(comp, "probe", "metrics-pw-1!");
        mvc.perform(post("/test-probe/rls-violation").with(accounts.as(comp, u, "probe")).with(csrf()))
                .andExpect(status().isForbidden());
        mvc.perform(get("/actuator/prometheus")).andExpect(status().isOk())
                .andExpect(content().string(containsString("hb_tenant_forbidden_total")))
                .andExpect(content().string(not(containsString("comp=\""))))
                .andExpect(content().string(not(containsString(comp))));
    }

    // ---------- 업무 지표: 한 번 = +1 ----------

    @Test
    void RLS_위반_차단은_정확히_1씩() throws Exception {
        String comp = accounts.newComp("MR");
        long u = accounts.newUser(comp, "probe", "metrics-pw-1!");
        double before = count("hb.tenant.forbidden");
        mvc.perform(post("/test-probe/rls-violation").with(accounts.as(comp, u, "probe")).with(csrf()))
                .andExpect(status().isForbidden());
        assertThat(count("hb.tenant.forbidden") - before).isEqualTo(1.0);
    }

    @Test
    void 로그인_실패는_정확히_1씩_없는_회사코드도_센다() throws Exception {
        String comp = accounts.newComp("ML");
        accounts.newUser(comp, "kim", "metrics-pw-1!");
        double before = count("hb.login.fail");
        mvc.perform(post("/login").param("compCd", comp).param("loginId", "kim").param("password", "wrong-pw-1!")
                .with(csrf()).with(from("192.0.2." + (10 + SEQ.incrementAndGet() % 200))));
        assertThat(count("hb.login.fail") - before).isEqualTo(1.0);
        mvc.perform(post("/login").param("compCd", "NOPE" + SEQ.incrementAndGet()).param("loginId", "kim")
                .param("password", "wrong-pw-1!").with(csrf()).with(from("192.0.2." + (10 + SEQ.incrementAndGet() % 200))));
        assertThat(count("hb.login.fail") - before).isEqualTo(2.0);
    }

    @Test
    void 수신_중복은_두번째부터_1씩() {
        String comp = accounts.newComp("MI");
        double before = count("hb.inbox.duplicate", "consumer", "ZT_METRIC");
        String id = "msg-" + System.nanoTime();
        assertThat(runner.call(comp, () -> inbox.firstTime("ZT_METRIC", id))).isTrue();
        assertThat(count("hb.inbox.duplicate", "consumer", "ZT_METRIC") - before).isZero();
        assertThat(runner.call(comp, () -> inbox.firstTime("ZT_METRIC", id))).isFalse();
        assertThat(runner.call(comp, () -> inbox.firstTime("ZT_METRIC", id))).isFalse();
        assertThat(count("hb.inbox.duplicate", "consumer", "ZT_METRIC") - before).isEqualTo(2.0);
    }

    @Test
    void 아웃박스_집계는_회사별_RLS_로_세서_합친다_A사_B사() {
        String a = accounts.newComp("MA");
        String b = accounts.newComp("MB");
        String topic = "ZT_METRIC_" + SEQ.incrementAndGet();
        // 시험 토픽은 송신기가 없어 publish 가 거부한다 → 앱 계정으로 행을 직접(릴레이가 쓰는 표 그대로)
        String insert = """
                insert into sys_outbox (comp_cd, message_id, topic, biz_key, payload, status, created_by)
                values (hb_current_comp(), gen_random_uuid(), ?, ?, '{}'::jsonb, ?, 'zt')""";
        runner.run(a, () -> {
            jdbc.update(insert, topic, "k1", "NEW");
            jdbc.update(insert, topic, "k2", "NEW");
        });
        runner.run(b, () -> jdbc.update(insert, topic, "k3", "FAIL"));   // B사 한 건은 실패로 멈춤
        gauges.refresh();
        assertThat(gauge("hb.outbox.pending", topic)).isEqualTo(2.0);
        assertThat(gauge("hb.outbox.failed", topic)).isEqualTo(1.0);
        assertThat(registry.find("hb.outbox.pending").tag("topic", topic).gauges()).hasSize(1);   // 회사 꼬리표 없음 → 한 줄
        // A사 범위로는 B사 행이 안 보인다(집계가 RLS 를 우회하지 않는다)
        assertThat(runner.call(a, () -> jdbc.queryForObject("select count(*) from sys_outbox where topic = ?", Long.class, topic)))
                .isEqualTo(2L);
    }

    @Test
    void 작업_지연은_커밋된_작업만_기준으로() {
        String comp = accounts.newComp("MJ");
        String ok = "zt-metric-ok-" + SEQ.incrementAndGet();
        String rolled = "zt-metric-rb-" + SEQ.incrementAndGet();
        runner.run(comp, () -> assertThat(jobLock.tryLock(ok)).isTrue());
        try {
            runner.run(comp, () -> {
                jobLock.tryLock(rolled);
                throw new IllegalStateException("작업 실패");
            });
        } catch (RuntimeException expected) {
            // 롤백
        }
        Gauge g = registry.find("hb.job.lag").tag("job", ok).gauge();
        assertThat(g).isNotNull();
        assertThat(g.value()).isBetween(0.0, 60.0);
        assertThat(registry.find("hb.job.lag").tag("job", rolled).gauge()).as("롤백된 작업은 성공이 아니다").isNull();
    }

    private double gauge(String name, String topic) {
        return registry.find(name).tag("topic", topic).gauges().stream().mapToDouble(Gauge::value).sum();
    }

    // ---------- 요청 ID ----------

    @Test
    void 요청_ID_는_응답에_실리고_형식이_틀린_값은_새로_만든다() throws Exception {
        mvc.perform(get("/login")).andExpect(header().string(RequestIdFilter.HEADER, org.hamcrest.Matchers.matchesPattern(
                "^[0-9a-f-]{36}$")));
        mvc.perform(get("/login").header(RequestIdFilter.HEADER, "proxy-req-12345"))
                .andExpect(header().string(RequestIdFilter.HEADER, "proxy-req-12345"));
        // 로그 줄 위조(줄바꿈)·짧은 값 → 버리고 새로
        mvc.perform(get("/login").header(RequestIdFilter.HEADER, "abc\nFAKE LOG"))
                .andExpect(header().string(RequestIdFilter.HEADER, org.hamcrest.Matchers.matchesPattern("^[0-9a-f-]{36}$")));
        mvc.perform(get("/login").header(RequestIdFilter.HEADER, "x"))
                .andExpect(header().string(RequestIdFilter.HEADER, org.hamcrest.Matchers.matchesPattern("^[0-9a-f-]{36}$")));
    }
}
