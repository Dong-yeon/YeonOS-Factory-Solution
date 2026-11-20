package io.smarthappy.module.wms.asrs;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import io.smarthappy.platform.job.JobLock;
import io.smarthappy.platform.tenant.TenantRunner;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/** 자동창고 정기 작업 (docs/65 23.7) — 오래 머문 요청 경보(오류 없이 영원히 NOT_STARTED 인 용기). 스케줄이 꺼져 있으면(테스트) 직접 부른다 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AsrsJobs {

    static final String JOB = "wms-asrs-stale";

    private final TenantRunner runner;
    private final JobLock lock;
    private final AsrsDeviceService device;

    @Scheduled(fixedDelayString = "${happyboot.jobs.wms-asrs-ms:60000}", initialDelayString = "${happyboot.jobs.wms-asrs-ms:60000}")
    public int flagStale() {
        int[] n = {0};
        var result = runner.forEachActiveComp(compCd -> {
            if (!lock.tryLock(JOB)) {
                return;
            }
            int k = device.flagStale();
            n[0] += k;
            if (k > 0) {
                log.warn("자동창고 오래 머문 요청 compCd={} {}건", compCd, k);
            }
        });
        if (!result.allSucceeded()) {
            log.error("자동창고 오래 머문 요청 판정 실패 회사 {}", result.failed().keySet());
        }
        return n[0];
    }
}
