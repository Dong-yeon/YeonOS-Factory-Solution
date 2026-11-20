package io.smarthappy.qa;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.jayway.jsonpath.JsonPath;

import io.smarthappy.platform.tenant.TenantRunner;
import io.smarthappy.support.PostgresTestBase;
import io.smarthappy.support.TestAccounts;

/**
 * QA — PLM PCN 메일 변경 통지서 첨부(#518) 이상 사용. 모듈 시험(PlmPcnMailAttachTest)이 덮지 않은 곳: 같은 통지에 동시 발송(사본 재사용의
 * 대상 단위 잠금), 지시 내용이 바뀐 뒤 다시 보내기(새 사본·옛 이력 그대로), 사본의 DB 잠금 표시. 분석 docs/qa/2026-10-24_b3-mail-pcn.md
 */
@AutoConfigureMockMvc
@TestPropertySource(properties = {"happyboot.mail.enabled=true", "happyboot.mail.from=noreply@yeonos.test",
        "happyboot.mail.base-url=https://mes.example.test/", "spring.mail.host=smtp.invalid"})
class PcnNoticeQaTest extends PostgresTestBase {

    static final String PW = "Qa-pcn-notice-37";
    static final ExecutorService POOL = Executors.newFixedThreadPool(3);

    @MockitoBean
    JavaMailSender sender;

    @Autowired
    MockMvc mvc;

    @Autowired
    TestAccounts accounts;

    @Autowired
    TenantRunner runner;

    @Autowired
    JdbcTemplate jdbc;

    String comp;
    RequestPostProcessor owner;
    String today;
    long cA;
    long cB;

    @AfterAll
    static void stop() {
        POOL.shutdownNow();
    }

    @BeforeEach
    void setUp() {
        comp = accounts.newComp("QPN");
        long u = accounts.newUser(comp, "eng1", PW);
        accounts.grant(comp, u, "ENG", "plm-eco", "SEARCH", "SAVE");
        owner = accounts.as(comp, u, "eng1");
        today = accounts.query(comp, "select " + TestAccounts.COMP_TODAY + "::text", String.class);
        accounts.exec(comp, "insert into mst_cust (comp_cd, cust_cd, cust_nm, cust_type, created_by, updated_by) values (?, 'C1', 'C1 고객', 'SALES', 't', 't')", comp);
        cA = contact("가 대표", "a.main@cust.test");
        cB = contact("나 품질", "b.qa@cust.test");
    }

    long contact(String nm, String email) {
        return accounts.query(comp, """
                insert into mst_cust_contact (comp_cd, cust_id, contact_nm, email, main_yn, use_yn, created_by, updated_by)
                select ?, cust_id, ?, ?, 'N', 'Y', 't', 't' from mst_cust where cust_cd = 'C1' returning contact_id""", Long.class, comp, nm, email);
    }

    static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder b, String body) {
        return b.with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body);
    }

    static long data(ResultActions r) throws Exception {
        return ((Number) JsonPath.read(r.andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.data")).longValue();
    }

    long[] pcn() throws Exception {
        long eco = data(mvc.perform(json(post("/api/plm/ecos"), "{\"title\":\"QA 통지\",\"reasonCd\":\"QUALITY\",\"effectiveDate\":\"" + today + "\"}")
                .with(owner)));
        long pcn = data(mvc.perform(json(post("/api/plm/ecos/" + eco + "/pcns"), "{\"custCd\":\"C1\",\"notifiedOn\":\"" + today
                + "\",\"responseDue\":\"" + today + "\"}").with(owner)));
        return new long[] {eco, pcn};
    }

    int mail(long pcn, long contactId) throws Exception {
        return mvc.perform(json(post("/api/plm/pcns/" + pcn + "/mail"), "{\"contactIds\":[" + contactId + "],\"attachNotice\":true}")
                .header("X-Idempotency-Key", UUID.randomUUID().toString()).with(owner)).andReturn().getResponse().getStatus();
    }

    int copies(long pcn) {
        return accounts.query(comp, "select count(*) from sys_file where target_type = 'plm-eco' and target_id = ?", Integer.class, "PCN-" + pcn);
    }

    int lockWaiters() {
        return jdbc.queryForObject("select count(*) from pg_stat_activity where wait_event_type = 'Lock' and datname = current_database()",
                Integer.class);
    }

    // ------------------------------------------------------------------ 시험

    /**
     * 같은 통지에 두 담당자를 따로 동시에 보냄 — 사본 재사용 잠금(대상 단위 advisory)을 시험이 먼저 잡고, 두 요청이 모두 기다리는 것을
     * 관찰한 뒤 놓는다 → 둘 다 성공, 사본은 하나, 두 이력이 같은 사본
     */
    @Test
    void 같은_통지에_동시에_보내도_사본은_하나() throws Exception {
        long[] p = pcn();
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch go = new CountDownLatch(1);
        Future<?> holder = POOL.submit(() -> runner.call(comp, () -> {
            jdbc.queryForObject("select 1 from (select pg_advisory_xact_lock(hashtextextended(?, 0))) x", Integer.class,
                    "sys_file:" + comp + ":plm-eco:PCN-" + p[1]);
            held.countDown();
            try {
                go.await(30, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return null;
        }));
        assertThat(held.await(10, TimeUnit.SECONDS)).isTrue();
        int before = lockWaiters();
        Future<Integer> a = POOL.submit(() -> mail(p[1], cA));
        Future<Integer> b = POOL.submit(() -> mail(p[1], cB));
        for (int i = 0; i < 200 && lockWaiters() - before < 2; i++) {
            Thread.sleep(50);
        }
        assertThat(lockWaiters() - before).as("두 발송이 모두 기다린다(모두 관찰한 뒤 놓음)").isGreaterThanOrEqualTo(2);
        go.countDown();
        holder.get(20, TimeUnit.SECONDS);
        assertThat(a.get(20, TimeUnit.SECONDS)).isEqualTo(200);
        assertThat(b.get(20, TimeUnit.SECONDS)).isEqualTo(200);
        assertThat(copies(p[1])).isEqualTo(1);
        assertThat(accounts.query(comp, "select count(distinct notice_file_id) from plm_pcn_mail where pcn_id = ?", Integer.class, p[1])).isEqualTo(1);
        assertThat(accounts.query(comp, "select count(*) from plm_pcn_mail where pcn_id = ?", Integer.class, p[1])).isEqualTo(2);
    }

    /** 보낸 뒤 지시 제목을 바꾸고 다시 보냄 — 새 사본이 생기고, 먼저 보낸 메일의 이력·사본은 그대로(보낸 것 = 그때 내용) */
    @Test
    void 지시가_바뀐_뒤_다시_보내면_새_사본_옛_이력은_옛_사본() throws Exception {
        long[] p = pcn();
        assertThat(mail(p[1], cA)).isEqualTo(200);
        long first = accounts.query(comp, "select notice_file_id from plm_pcn_mail where pcn_id = ? order by mail_id limit 1", Long.class, p[1]);
        int rowVer = accounts.query(comp, "select row_ver from plm_eco where eco_id = ?", Integer.class, p[0]);
        mvc.perform(json(put("/api/plm/ecos/" + p[0]), "{\"title\":\"QA 통지 — 바꿈\",\"reasonCd\":\"QUALITY\",\"effectiveDate\":\"" + today
                + "\",\"rowVer\":" + rowVer + "}").with(owner)).andExpect(status().isOk());
        assertThat(mail(p[1], cA)).isEqualTo(200);
        assertThat(copies(p[1])).isEqualTo(2);
        long second = accounts.query(comp, "select notice_file_id from plm_pcn_mail where pcn_id = ? order by mail_id desc limit 1", Long.class, p[1]);
        assertThat(second).isNotEqualTo(first);
        assertThat(accounts.query(comp, "select use_yn from sys_file where file_id = ?", String.class, first)).isEqualTo("Y");
        assertThat(accounts.query(comp, "select notice_file_id from plm_pcn_mail where pcn_id = ? order by mail_id limit 1", Long.class, p[1]))
                .isEqualTo(first);
    }

    /**
     * P-1 반영(docs/39 36.1, #537): 보낸 통지서 사본은 앱 규칙(PlmFileLock)에 더해 DB 최종 방어 표시(sys_file.locked_yn = 'Y')로 잠긴다 —
     * QMS 배포 문서처럼 한 방향 트리거라 FileService 를 거치지 않는 코드·도구도 "보낸 증거" 를 지우거나 바꾸지 못한다(관찰 → 고정)
     */
    @Test
    void 보낸_사본은_DB_잠금_표시로_굳는다() throws Exception {
        long[] p = pcn();
        assertThat(mail(p[1], cA)).isEqualTo(200);
        long f = accounts.query(comp, "select notice_file_id from plm_pcn_mail where pcn_id = ?", Long.class, p[1]);
        assertThat(accounts.query(comp, "select locked_yn from sys_file where file_id = ?", String.class, f)).isEqualTo("Y");
        // 앱 계정의 직접 SQL 도 막힌다(트리거) — 이력이 가리키는 사본이 사라지지 않는다
        assertThat(io.smarthappy.platform.common.db.PgErrors.constraint(org.assertj.core.api.Assertions.catchThrowable(() -> accounts.exec(comp,
                "update sys_file set use_yn = 'N', updated_by = 'qa' where file_id = ?", f)))).contains("ck_sys_file_locked");
        assertThat(accounts.query(comp, "select use_yn from sys_file where file_id = ?", String.class, f)).isEqualTo("Y");
    }
}
