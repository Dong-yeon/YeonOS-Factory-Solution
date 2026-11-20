package io.smarthappy.module.wms.labeler;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import io.smarthappy.platform.job.JobLock;
import io.smarthappy.platform.tenant.TenantRunner;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/** 오토라벨러 정기 작업 (docs/65 21.3) — 미리 받고 안 쓴 SSCC 결번(재사용·이월 없음). 스케줄이 꺼져 있으면(테스트) 직접 부른다 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LabelerJobs {

    static final String JOB = "wms-labeler-prefetch";

    private final TenantRunner runner;
    private final JobLock lock;
    private final LabelerDeviceService device;

    @Scheduled(fixedDelayString = "${happyboot.jobs.wms-labeler-ms:600000}", initialDelayString = "${happyboot.jobs.wms-labeler-ms:600000}")
    public int voidStalePrefetch() {
        int[] n = {0};
        var result = runner.forEachActiveComp(compCd -> {
            if (!lock.tryLock(JOB)) {
                return;
            }
            int k = device.voidStalePrefetch();
            n[0] += k;
            if (k > 0) {
                log.info("라벨러 미리 받기 결번 compCd={} {}건", compCd, k);
            }
        });
        if (!result.allSucceeded()) {
            log.error("라벨러 미리 받기 결번 실패 회사 {}", result.failed().keySet());
        }
        return n[0];
    }
}
