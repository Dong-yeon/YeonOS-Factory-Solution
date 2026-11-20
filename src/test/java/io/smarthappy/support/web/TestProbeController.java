package io.smarthappy.support.web;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.smarthappy.platform.tenant.TenantContext;
import io.smarthappy.platform.tenant.TenantRunner;

/** 테스트 전용 엔드포인트 (src/test 에만 있음). 보안 설정상 로그인 필요 */
@RestController
@RequestMapping("/test-probe")
class TestProbeController {

    private static final AtomicInteger SEQ = new AtomicInteger();

    private final TenantRunner runner;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;

    TestProbeController(TenantRunner runner, JdbcTemplate jdbc, PlatformTransactionManager tm) {
        this.runner = runner;
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(tm);
    }

    @PostMapping("/rls-violation")
    void rlsViolation() {
        String[] comps = twoComps();
        runner.run(comps[0], () -> jdbc.update(
                "insert into sys_plant (comp_cd, plant_cd, plant_nm, created_by, updated_by) values (?, 'P9', '몰래', 't', 't')",
                comps[1]));
    }

    @PostMapping("/rls-violation-update")
    void rlsViolationUpdate() {
        String[] comps = twoComps();
        runner.run(comps[0], () -> jdbc.update(
                "insert into sys_plant (comp_cd, plant_cd, plant_nm, created_by, updated_by) values (?, 'P1', 'A', 't', 't')",
                comps[0]));
        runner.run(comps[0], () -> jdbc.update("update sys_plant set comp_cd = ? where plant_cd = 'P1'", comps[1]));
    }

    /** 요청 범위(로그인 사용자의 회사)로 조회 — 웹 요청 격리 확인용 */
    @GetMapping("/my-plants")
    List<String> myPlants() {
        return tx.execute(s -> jdbc.queryForList("select plant_nm from sys_plant order by plant_cd", String.class));
    }

    @GetMapping("/my-comp")
    String myComp() {
        return TenantContext.requireCompCd();
    }

    /** 회사 범위가 없는 스레드에서 회사 필요 기능 호출 → TenantNotSetException */
    @GetMapping("/tenant-required-offthread")
    String tenantRequiredOffThread() {
        try {
            return CompletableFuture.supplyAsync(TenantContext::requireCompCd).join();
        } catch (CompletionException e) {
            throw (RuntimeException) e.getCause();
        }
    }

    @GetMapping("/db-error")
    void dbError() {
        String comp = twoComps()[0];
        runner.run(comp, () -> jdbc.queryForObject("select count(*) from no_such_table", Integer.class));
    }

    /** 같은 공장 두 번 → unique 위반(23505) */
    @PostMapping("/duplicate")
    void duplicate() {
        String comp = twoComps()[0];
        for (int i = 0; i < 2; i++) {
            runner.run(comp, () -> jdbc.update(
                    "insert into sys_plant (comp_cd, plant_cd, plant_nm, created_by, updated_by) values (?, 'P1', 'A', 't', 't')",
                    comp));
        }
    }

    /** 없는 권한그룹 참조 → 참조 위반(23503) */
    @PostMapping("/fk")
    void fk() {
        String comp = twoComps()[0];
        runner.run(comp, () -> jdbc.update(
                "insert into sys_auth_prog (comp_cd, auth_grp_cd, prog_cd, action_cd, created_by) values (?, 'NOGROUP', 'sys-my-info', 'SEARCH', 't')",
                comp));
    }

    /** 공백 이름 → check 위반(23514) */
    @PostMapping("/check")
    void check() {
        String comp = twoComps()[0];
        runner.run(comp, () -> jdbc.update(
                "insert into sys_plant (comp_cd, plant_cd, plant_nm, created_by, updated_by) values (?, 'P1', '   ', 't', 't')",
                comp));
    }

    /** 길이 초과 → 22001 */
    @PostMapping("/too-long")
    void tooLong() {
        String comp = twoComps()[0];
        runner.run(comp, () -> jdbc.update(
                "insert into sys_plant (comp_cd, plant_cd, plant_nm, created_by, updated_by) values (?, 'P1', ?, 't', 't')",
                comp, "가".repeat(101)));
    }

    @PostMapping("/ddl")
    void ddl() {
        String comp = twoComps()[0];
        runner.run(comp, () -> jdbc.execute("create table probe_hack (id int)"));
    }

    private String[] twoComps() {
        int n = SEQ.incrementAndGet();
        // TestAccounts.newComp("PB") 와 같은 이름(PB{n})이 나오지 않게 이 컨트롤러만 쓰는 접두어 — 시험 순서에 따라 sys_comp 키 충돌(23505)이 났다
        String a = "TPA" + n;
        String b = "TPB" + n;
        for (String c : new String[] {a, b}) {
            runner.run(c, () -> jdbc.update(
                    "insert into sys_comp (comp_cd, comp_nm, created_by, updated_by) values (?, ?, 't', 't')", c, c));
        }
        return new String[] {a, b};
    }
}
