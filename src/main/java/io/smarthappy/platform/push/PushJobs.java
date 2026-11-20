package io.smarthappy.platform.push;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import io.smarthappy.platform.job.JobLock;
import io.smarthappy.platform.tenant.TenantRunner;
import io.smarthappy.platform.tenant.TenantRunner.BatchResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/** Web Push 구독 정리(docs/45 9장 N2·N8) — 매일 03:17 UTC. 스케줄이 꺼져 있으면(테스트) 직접 부른다 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PushJobs {

    static final String CLEANUP = "push-cleanup";

    private final TenantRunner runner;
    private final JobLock lock;
    private final PushService push;

    @Scheduled(cron = "${happyboot.jobs.push-cleanup-cron:0 17 3 * * *}", zone = "UTC")
    public BatchResult cleanup() {
        BatchResult result = runner.forEachActiveComp(compCd -> {
            if (lock.tryLock(CLEANUP)) {
                int[] r = push.cleanup();
                if (r[0] + r[1] > 0) {
                    log.info("푸시 구독 정리 compCd={} 지움={} 종결={}", compCd, r[0], r[1]);
                }
            }
        });
        if (!result.allSucceeded()) {
            log.error("푸시 구독 정리 실패 회사 {}", result.failed().keySet());
        }
        return result;
    }
}
