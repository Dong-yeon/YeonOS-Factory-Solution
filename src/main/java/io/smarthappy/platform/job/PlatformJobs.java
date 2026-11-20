package io.smarthappy.platform.job;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import io.smarthappy.platform.log.LogDigestService;
import io.smarthappy.platform.log.UsageDailyService;
import io.smarthappy.platform.messaging.OutboxRelay;
import io.smarthappy.platform.tenant.TenantRunner;
import io.smarthappy.platform.tenant.TenantRunner.BatchResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 플랫폼 정기 작업. 스케줄이 꺼져 있으면(테스트) 메서드를 직접 부른다.
 * 로그 정리(hb_purge_logs)는 여기 없다 — 앱 계정은 로그를 지울 권한이 없고, 운영 도구가 소유자 계정으로 실행한다(01 7.1).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PlatformJobs {

    static final String LOG_DIGEST = "log-digest";
    static final String USAGE_DAILY = "usage-daily";

    private final TenantRunner runner;
    private final JobLock lock;
    private final LogDigestService digests;
    private final OutboxRelay outboxRelay;
    private final UsageDailyService usage;

    /** 매시 25분: 확정 가능한 날의 활용 일 집계 (docs/11 D2) */
    @Scheduled(cron = "${happyboot.jobs.usage-daily-cron:0 25 * * * *}")
    public BatchResult usageDaily() {
        return runner.forEachActiveComp(compCd -> {
            if (lock.tryLock(USAGE_DAILY)) {
                usage.aggregatePending();
            }
        });
    }

    /** 아웃박스 릴레이 — 끝난 뒤 5초 쉬고 다시 (여러 인스턴스는 skip locked 로 나눠 가진다) */
    @Scheduled(fixedDelayString = "${happyboot.jobs.outbox-relay-delay-ms:5000}", initialDelay = 10000)
    public void outboxRelay() {
        outboxRelay.relayAll();
    }

    /** 매시 15분: 확정 가능한 날의 로그 다이제스트 (회사 시간대가 달라도 하루 안에 따라잡는다, 이미 한 날은 건너뜀) */
    @Scheduled(cron = "${happyboot.jobs.log-digest-cron:0 15 * * * *}")
    public BatchResult logDigest() {
        BatchResult result = runner.forEachActiveComp(compCd -> {
            if (!lock.tryLock(LOG_DIGEST)) {
                log.info("로그 다이제스트: 다른 인스턴스가 실행 중 compCd={}", compCd);
                return;
            }
            int n = digests.digestPending();
            if (n > 0) {
                log.info("로그 다이제스트 {}건 compCd={}", n, compCd);
            }
        });
        if (!result.allSucceeded()) {
            log.error("로그 다이제스트 실패 회사 {}", result.failed().keySet());
        }
        return result;
    }
}
