package io.smarthappy.platform.job;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import io.smarthappy.platform.metrics.HbMetrics;
import io.smarthappy.platform.tenant.TenantContext;
import lombok.RequiredArgsConstructor;

/**
 * 작업별·회사별 실행 잠금 (docs/04 6장 — advisory lock 배치).
 * 인스턴스가 여러 개여도 같은 작업·같은 회사는 한 곳에서만 돈다. 트랜잭션 잠금이라 끝나면(커밋·롤백·연결 끊김) 저절로 풀린다.
 * 잠금 테이블·만료 시각 관리가 필요 없다 — 대신 작업 전체가 한 트랜잭션 안이어야 한다.
 * 잡은 트랜잭션이 커밋되면 작업 성공으로 센다 → 운영 지표 hb_job_lag_seconds(docs/52 Y2).
 */
@Component
@RequiredArgsConstructor
public class JobLock {

    private final JdbcTemplate jdbc;
    private final HbMetrics metrics;

    /** 잡았으면 true, 다른 곳에서 실행 중이면 false (기다리지 않는다). 잡은 트랜잭션이 커밋되면 작업 성공으로 기록 */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean tryLock(String jobName) {
        boolean locked = lock(jobName);
        if (locked && TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    metrics.jobSucceeded(jobName);
                }
            });
        }
        return locked;
    }

    /** 정기 작업이 아닌 자리 잠금(예: 회사별 동시 엑셀 내보내기 자리) — 작업 지연 지표에 넣지 않는다 */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean trySlot(String slotName) {
        return lock(slotName);
    }

    private boolean lock(String name) {
        String key = "job:" + name + ":" + TenantContext.requireCompCd();
        return Boolean.TRUE.equals(jdbc.queryForObject("select pg_try_advisory_xact_lock(hashtextextended(?, 0))",
                Boolean.class, key));
    }
}
