package io.smarthappy.platform.tenant;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import io.smarthappy.platform.tenant.TenantContext.Scope;
import io.smarthappy.platform.tenant.TenantContext.TenantScope;
import lombok.extern.slf4j.Slf4j;

/**
 * 요청 밖 실행 경로(스케줄러·@Async·아웃박스 릴레이·배치)의 유일한 DB 진입점.
 * 지정한 회사 범위로 새 트랜잭션을 열어 실행하고, 끝나면 이전 범위로 되돌린다.
 *
 * 규칙
 * - 이미 트랜잭션 안에서는 호출할 수 없다 (한 트랜잭션에 두 회사가 섞이는 것 방지)
 * - 회사코드 형식이 틀리면 DB 에 가기 전에 거부한다
 * - 회사 순회는 회사마다 별도 트랜잭션, 한 회사 실패가 다른 회사를 막지 않는다
 */
@Slf4j
@Component
public class TenantRunner {

    private final TransactionTemplate tx;
    private final TransactionTemplate newTx;
    private final JdbcTemplate jdbc;

    public TenantRunner(PlatformTransactionManager transactionManager, JdbcTemplate jdbc) {
        this.tx = new TransactionTemplate(transactionManager);
        this.newTx = new TransactionTemplate(transactionManager);
        this.newTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.jdbc = jdbc;
    }

    public <T> T call(String compCd, Supplier<T> work) {
        TenantContext.validate(compCd);
        assertNoActiveTransaction();
        try (Scope ignored = TenantContext.open(inherit(compCd))) {
            return tx.execute(status -> work.get());
        }
    }

    /**
     * 본 트랜잭션과 분리된 새 트랜잭션(REQUIRES_NEW)으로 실행한다 — 접속·보안 로그처럼 본 요청이 롤백돼도 남아야 하는 기록 전용.
     * 트랜잭션 안에서도 부를 수 있지만, 이미 열린 회사 범위와 다른 회사로는 열 수 없다(한 요청에 회사가 섞이는 것 방지).
     */
    public void runIsolated(String compCd, Runnable work) {
        TenantContext.validate(compCd);
        TenantContext.currentCompCd().ifPresent(current -> {
            if (!current.equals(compCd)) {
                throw new IllegalStateException("다른 회사 범위 안에서 분리 트랜잭션을 열 수 없습니다");
            }
        });
        try (Scope ignored = TenantContext.open(inherit(compCd))) {
            newTx.executeWithoutResult(status -> work.run());
        }
    }

    /** 같은 회사 범위 안에서 부르면 행위자(감사용)를 이어받는다 */
    private static TenantScope inherit(String compCd) {
        boolean same = TenantContext.currentCompCd().filter(compCd::equals).isPresent();
        return new TenantScope(compCd, "", same ? TenantContext.currentActor() : null);
    }

    /** 행위자(감사 actor)를 지정해 회사 범위로 실행 — 운영자 콘솔이 회사를 만들거나 고칠 때 "ops:{id}" 로 남긴다 */
    public <T> T callAs(String compCd, String actor, Supplier<T> work) {
        TenantContext.validate(compCd);
        assertNoActiveTransaction();
        try (Scope ignored = TenantContext.open(new TenantScope(compCd, "", actor))) {
            return tx.execute(status -> work.get());
        }
    }

    public void run(String compCd, Runnable work) {
        call(compCd, () -> {
            work.run();
            return null;
        });
    }

    /** 사용 중(ACTIVE, use_yn=Y)인 회사 목록. 회사 목록 조회 전용 범위로 sys_comp 만 읽는다 */
    public List<String> activeCompCodes() {
        assertNoActiveTransaction();
        try (Scope ignored = TenantContext.open(TenantScope.systemDirectory())) {
            return tx.execute(status -> jdbc.queryForList(
                    "select comp_cd from sys_comp where status_cd = 'ACTIVE' and use_yn = 'Y' order by comp_cd",
                    String.class));
        }
    }

    /**
     * 운영자 콘솔 범위로 새 트랜잭션 실행 — 회사 범위가 없고, 허용 정책(sys_comp.ops_directory_read,
     * sys_usage_daily.ops_usage_read)으로만 읽는다. 운영자 인증(MFA) 뒤 콘솔 요청에서만 쓴다.
     */
    public <T> T callOps(String actor, Supplier<T> work) {
        assertNoActiveTransaction();
        try (Scope ignored = TenantContext.open(TenantScope.ops(actor))) {
            return tx.execute(status -> work.get());
        }
    }

    /** 사용 중인 모든 회사에 대해 회사별 트랜잭션으로 실행한다 */
    public BatchResult forEachActiveComp(Consumer<String> work) {
        List<String> succeeded = new ArrayList<>();
        Map<String, RuntimeException> failed = new LinkedHashMap<>();
        for (String compCd : activeCompCodes()) {
            try {
                run(compCd, () -> work.accept(compCd));
                succeeded.add(compCd);
            } catch (RuntimeException e) {
                log.error("회사별 실행 실패 compCd={}", compCd, e);
                failed.put(compCd, e);
            }
        }
        return new BatchResult(List.copyOf(succeeded), Map.copyOf(failed));
    }

    private static void assertNoActiveTransaction() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException(
                    "TenantRunner 는 트랜잭션 밖에서만 호출할 수 있습니다 (한 트랜잭션에 회사 범위가 섞이는 것 방지)");
        }
    }

    public record BatchResult(List<String> succeeded, Map<String, RuntimeException> failed) {
        public boolean allSucceeded() {
            return failed.isEmpty();
        }
    }
}
