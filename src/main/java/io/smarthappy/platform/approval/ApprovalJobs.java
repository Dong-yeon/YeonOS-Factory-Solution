package io.smarthappy.platform.approval;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import io.smarthappy.platform.job.JobLock;
import io.smarthappy.platform.tenant.TenantRunner;
import io.smarthappy.platform.tenant.TenantRunner.BatchResult;
import io.smarthappy.platform.time.BizClock;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/** 결재 정기 작업 — 스케줄이 꺼져 있으면(테스트) 직접 부른다 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ApprovalJobs {

    static final String DELEG_START = "apr-deleg-start";

    private final TenantRunner runner;
    private final JobLock lock;
    private final ApprovalDelegateService delegates;
    private final BizClock clock;

    /** 매시 5분: 회사 날짜로 오늘 시작한(사용 중인) 위임의 대리에게 시작 알림 한 번 (docs/36 14.8, 중복 키로 멱등) */
    @Scheduled(cron = "${happyboot.jobs.apr-deleg-start-cron:0 5 * * * *}")
    public BatchResult delegationStart() {
        BatchResult result = runner.forEachActiveComp(compCd -> {
            if (lock.tryLock(DELEG_START)) {
                int n = delegates.notifyStarted(clock.today());
                if (n > 0) {
                    log.info("결재 위임 시작 알림 {}건 compCd={}", n, compCd);
                }
            }
        });
        if (!result.allSucceeded()) {
            log.error("결재 위임 시작 알림 실패 회사 {}", result.failed().keySet());
        }
        return result;
    }
}
