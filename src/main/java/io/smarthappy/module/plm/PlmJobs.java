package io.smarthappy.module.plm;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import io.smarthappy.platform.job.JobLock;
import io.smarthappy.platform.tenant.TenantRunner;
import io.smarthappy.platform.tenant.TenantRunner.BatchResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * PLM 정기 작업 (docs/39 26장) — 기한 지남 알림. 매시: 회사 시간대마다 "오늘" 이 바뀌는 시각이 달라 하루 한 번이 아니라 매시 돌리고,
 * 이미 보낸 알림은 중복 키로 건너뛴다(CmsJobs 와 같은 방식). 스케줄이 꺼져 있으면(테스트) 메서드를 직접 부른다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PlmJobs {

    static final String OVERDUE = "plm-overdue-notify";

    private final TenantRunner runner;
    private final JobLock lock;
    private final PlmOverdueNotifier notifier;

    @Scheduled(cron = "${happyboot.jobs.plm-overdue-cron:0 40 * * * *}")
    public BatchResult overdue() {
        BatchResult result = runner.forEachActiveComp(compCd -> {
            if (!lock.tryLock(OVERDUE)) {
                log.info("PLM 기한 지남 알림: 다른 인스턴스가 실행 중 compCd={}", compCd);
                return;
            }
            PlmOverdueNotifier.Sent s = notifier.run();
            if (s.ecos() + s.pcns() > 0) {
                log.info("PLM 기한 지남 알림 지시 {}·통지 {} compCd={}", s.ecos(), s.pcns(), compCd);
            }
        });
        if (!result.allSucceeded()) {
            log.error("PLM 기한 지남 알림 실패 회사 {}", result.failed().keySet());
        }
        return result;
    }
}
