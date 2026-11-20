package io.smarthappy.module.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.lang.reflect.Field;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;
import java.util.Map;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.jayway.jsonpath.JsonPath;

import io.smarthappy.module.admin.IntegrityDtos.Check;
import io.smarthappy.module.admin.IntegrityDtos.Item;
import io.smarthappy.module.admin.IntegrityDtos.Run;
import io.smarthappy.module.admin.IntegrityDtos.Status;
import io.smarthappy.platform.log.AuditCoverage;
import io.smarthappy.platform.log.RetentionTestAccess;
import io.smarthappy.platform.tenant.TenantRunner;
import io.smarthappy.support.PostgresTestBase;
import io.smarthappy.support.TestAccounts;

/**
 * 기록 무결성 점검 (sys-integrity, docs/52 Y1). 정상 흐름 + 이상 사용:
 * 권한 없이 API 직접(B), A사 결과를 B사가 번호로(A사로 B사), 원장 차단 트리거를 소유자로 끄면 FAIL, 다이제스트 변조 FAIL,
 * 서명 대상 없음·계약 없는 유형 WARN, 결과 행은 앱이 고칠 수 없음.
 */
@AutoConfigureMockMvc
class IntegrityCheckTest extends PostgresTestBase {

    private static final String API = "/api/admin/integrity/runs";

    @Autowired
    MockMvc mvc;

    @Autowired
    TestAccounts accounts;

    @Autowired
    TenantRunner runner;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    IntegrityCheckService integrity;

    String comp;
    long adminId;
    RequestPostProcessor admin;

    @BeforeEach
    void setUp() {
        comp = accounts.newComp("IC");
        adminId = accounts.newUser(comp, "qa", "integrity-pw-1!");
        accounts.grant(comp, adminId, "QA", IntegrityCheckService.PROG, "SEARCH", "FN1", "EXCEL");
        admin = accounts.as(comp, adminId, "qa");
    }

    private static Connection owner() throws Exception {
        return DriverManager.getConnection(jdbcUrl(), OWNER_USER, testPassword());
    }

    private static Check check(Run run, String key) {
        return run.checks().stream().filter(c -> c.key().equals(key)).findFirst().orElseThrow();
    }

    // ---------- 정상 ----------

    @Test
    void 점검을_실행하면_네_가지_결과가_남고_이력에서_다시_본다() throws Exception {
        String body = mvc.perform(post(API).with(admin).with(csrf())).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("OK"))
                .andExpect(jsonPath("$.data.checks[*].key").value(Matchers.contains("DIGEST", "LEDGER", "ESIGN", "AUDIT")))
                .andExpect(jsonPath("$.data.checks[?(@.key=='LEDGER')].checked").value(Matchers.contains(Matchers.greaterThan(10))))
                .andReturn().getResponse().getContentAsString();
        int runId = JsonPath.read(body, "$.data.runId");
        mvc.perform(get(API).with(admin)).andExpect(status().isOk()).andExpect(jsonPath("$.data[0].runId").value(runId))
                .andExpect(jsonPath("$.data[0].runBy").value(Matchers.containsString("qa")));
        mvc.perform(get(API + "/" + runId).with(admin)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.checks[1].items[0].code").value("LEDGER_OK"));
        // 실행은 접속 로그 ACTION(FN1)으로 남는다
        assertThat(accounts.query(comp, "select count(*) from sys_access_log where event_cd = 'ACTION' and prog_cd = ? and action_cd = 'FN1'"
                + " and detail like 'status=OK%'", Long.class, IntegrityCheckService.PROG)).isEqualTo(1L);
        // 엑셀(POST) — 출력 정보 시트 포함 xlsx
        mvc.perform(post(API + "/" + runId + "/excel").with(admin).with(csrf())).andExpect(status().isOk())
                .andExpect(header().string("Content-Type", Matchers.startsWith("application/vnd.openxmlformats")));
    }

    // ---------- 권한·회사 경계 ----------

    @Test
    void 권한_없는_사용자는_API_를_직접_불러도_403() throws Exception {
        long u = accounts.newUser(comp, "viewer", "integrity-pw-1!");
        accounts.grant(comp, u, "VIEW", IntegrityCheckService.PROG, "SEARCH");   // 보기만
        RequestPostProcessor viewer = accounts.as(comp, u, "viewer");
        mvc.perform(post(API).with(viewer).with(csrf())).andExpect(status().isForbidden());
        mvc.perform(get(API).with(viewer)).andExpect(status().isOk());
        long none = accounts.newUser(comp, "none", "integrity-pw-1!");
        RequestPostProcessor nobody = accounts.as(comp, none, "none");
        mvc.perform(get(API).with(nobody)).andExpect(status().isForbidden());
        mvc.perform(post(API).with(nobody).with(csrf())).andExpect(status().isForbidden());
        mvc.perform(post(API + "/1/excel").with(viewer).with(csrf())).andExpect(status().isForbidden());
        // CSRF 없는 실행 → 403
        mvc.perform(post(API).with(admin)).andExpect(status().isForbidden());
        assertThat(accounts.query(comp, "select count(*) from sys_integrity_run", Long.class)).isZero();
    }

    @Test
    void A사_결과를_B사가_번호로_열면_404_이력에도_없다() throws Exception {
        int runA = JsonPath.read(mvc.perform(post(API).with(admin).with(csrf())).andReturn().getResponse().getContentAsString(),
                "$.data.runId");
        String b = accounts.newComp("ID");
        long bAdmin = accounts.newUser(b, "qa", "integrity-pw-1!");
        accounts.grant(b, bAdmin, "QA", IntegrityCheckService.PROG, "SEARCH", "FN1", "EXCEL");
        RequestPostProcessor asB = accounts.as(b, bAdmin, "qa");
        mvc.perform(get(API + "/" + runA).with(asB)).andExpect(status().isNotFound());
        mvc.perform(post(API + "/" + runA + "/excel").with(asB).with(csrf())).andExpect(status().isNotFound());
        mvc.perform(get(API).with(asB)).andExpect(jsonPath("$.data[?(@.runId==" + runA + ")]").isEmpty());
        // B사 서명이 A사 점검에 섞이지 않는다
        runner.run(b, () -> jdbc.update("""
                insert into sys_esign (comp_cd, user_id, signer_nm, meaning_cd, target_type, target_id, content_hash, auth_method)
                values (hb_current_comp(), ?, 'B', 'AUTHOR', 'zt_other_comp', 1, ?, 'PWD')""", bAdmin, "a".repeat(64)));
        Run a = runner.call(comp, () -> integrity.run());
        assertThat(check(a, "ESIGN").items()).extracting(Item::subject).doesNotContain("zt_other_comp");
        assertThat(check(a, "ESIGN").checked()).isZero();
    }

    @Test
    void 결과_행은_앱_계정이_고치거나_지울_수_없다() {
        Run r = runner.call(comp, () -> integrity.run());
        assertThat(org.assertj.core.api.Assertions.catchThrowable(() -> runner.run(comp,
                () -> jdbc.update("update sys_integrity_run set status = 'OK' where run_id = ?", r.runId())))).isNotNull();
        assertThat(org.assertj.core.api.Assertions.catchThrowable(() -> runner.run(comp,
                () -> jdbc.update("delete from sys_integrity_run where run_id = ?", r.runId())))).isNotNull();
    }

    // ---------- 이상을 잡는가 ----------

    @Test
    void 원장_차단_트리거를_소유자로_끄면_FAIL_로_보고한다() throws Exception {
        String trigger;
        try (Connection c = owner(); Statement st = c.createStatement();
                var rs = st.executeQuery("""
                        select t.tgname from pg_trigger t join pg_proc p on p.oid = t.tgfoid
                         where t.tgrelid = 'inv_trx'::regclass and p.proname = 'hb_forbid_update_delete'""")) {
            rs.next();
            trigger = rs.getString(1);
        }
        try (Connection c = owner(); Statement st = c.createStatement()) {
            st.execute("alter table inv_trx disable trigger " + trigger);
        }
        try {
            Run r = runner.call(comp, () -> integrity.run());
            assertThat(r.status()).isEqualTo(Status.FAIL);
            Check ledger = check(r, "LEDGER");
            assertThat(ledger.status()).isEqualTo(Status.FAIL);
            assertThat(ledger.items().get(0)).extracting(Item::subject, Item::code).containsExactly("inv_trx", "LEDGER_NO_TRIGGER");
            assertThat(ledger.problems()).isEqualTo(1);
        } finally {
            try (Connection c = owner(); Statement st = c.createStatement()) {
                st.execute("alter table inv_trx enable trigger " + trigger);
            }
        }
        assertThat(check(runner.call(comp, () -> integrity.run()), "LEDGER").status()).isEqualTo(Status.OK);
    }

    @Test
    void 다이제스트가_다시_계산한_값과_다르면_FAIL() {
        // 아무 로그도 없는 날의 다이제스트를 틀린 값으로(앱은 추가만 할 수 있다 — 실제 위조는 DB 관리자 권한이 필요)
        runner.run(comp, () -> jdbc.update("""
                insert into sys_log_digest (comp_cd, table_nm, log_date, row_cnt, digest, prev_digest)
                values (hb_current_comp(), 'sys_access_log', date '2026-01-01', 0, ?, ?)""", "f".repeat(64), "0".repeat(64)));
        Check d = check(runner.call(comp, () -> integrity.run()), "DIGEST");
        assertThat(d.status()).isEqualTo(Status.FAIL);
        assertThat(d.items()).extracting(Item::code, Item::args)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("DIGEST_BROKEN", List.of("sys_access_log", "2026-01-01")));
    }

    @Test
    void 서명_대상이_없거나_계약_없는_유형이면_WARN() {
        runner.run(comp, () -> {
            jdbc.update("""
                    insert into sys_esign (comp_cd, user_id, signer_nm, meaning_cd, target_type, target_id, content_hash, auth_method)
                    values (hb_current_comp(), ?, 'QA', 'APPROVE', 'qms_ncr', 999999999, ?, 'PWD')""", adminId, "b".repeat(64));
            jdbc.update("""
                    insert into sys_esign (comp_cd, user_id, signer_nm, meaning_cd, target_type, target_id, content_hash, auth_method)
                    values (hb_current_comp(), ?, 'QA', 'AUTHOR', 'zt_unknown', 1, ?, 'PWD')""", adminId, "c".repeat(64));
        });
        Check e = check(runner.call(comp, () -> integrity.run()), "ESIGN");
        assertThat(e.status()).isEqualTo(Status.WARN);
        assertThat(e.checked()).isEqualTo(2);
        assertThat(e.items()).extracting(Item::subject, Item::code).contains(
                org.assertj.core.groups.Tuple.tuple("qms_ncr:999999999", "ESIGN_TARGET_MISSING"),
                org.assertj.core.groups.Tuple.tuple("zt_unknown", "ESIGN_NO_SOURCE"));
    }

    // ---------- 목록 맞춤 ----------

    @Test
    void 감사_예외_목록은_RetentionTest_와_같다() throws Exception {
        assertThat(AuditCoverage.EXEMPT).containsExactlyInAnyOrderElementsOf(RetentionTestAccess.auditExempt());
    }

    @Test
    void 서명_내용_해시_계약은_서명하는_유형마다_있다() throws Exception {
        Field f = IntegrityCheckService.class.getDeclaredField("sources");
        f.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, ?> sources = (Map<String, ?>) f.get(org.springframework.test.util.AopTestUtils.getTargetObject(integrity));
        assertThat(sources.keySet()).contains("qms_ncr", "qms_gauge", "plm_eco", "plm_ppap");
    }
}
