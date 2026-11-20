package io.smarthappy.platform.notify;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import io.smarthappy.platform.job.JobLock;
import io.smarthappy.platform.tenant.TenantRunner;
import io.smarthappy.platform.tenant.TenantRunner.BatchResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/** 알림 정기 작업 — 스케줄이 꺼져 있으면(테스트) 직접 부른다 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NotifyJobs {

    static final String DIGEST = "notify-digest";

    private final TenantRunner runner;
    private final JobLock lock;
    private final NotifyDigestService digest;

    /** 매시 40분: 회사 시간대 07시 뒤 첫 실행에 하루 요약 */
    @Scheduled(cron = "${happyboot.jobs.notify-digest-cron:0 40 * * * *}")
    public BatchResult digest() {
        BatchResult result = runner.forEachActiveComp(compCd -> {
            if (lock.tryLock(DIGEST)) {
                int n = digest.runForCompany();
                if (n > 0) {
                    log.info("하루 요약 {}명 compCd={}", n, compCd);
                }
            }
        });
        if (!result.allSucceeded()) {
            log.error("하루 요약 실패 회사 {}", result.failed().keySet());
        }
        return result;
    }
}
