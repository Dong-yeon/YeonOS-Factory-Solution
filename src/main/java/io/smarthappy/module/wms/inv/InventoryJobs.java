package io.smarthappy.module.wms.inv;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import io.smarthappy.platform.job.JobLock;
import io.smarthappy.platform.tenant.TenantRunner;
import io.smarthappy.platform.tenant.TenantRunner.BatchResult;
import lombok.RequiredArgsConstructor;

/** 재고 정기 작업 (docs/30 W3) — 매일 새벽 회사마다 대사. 스케줄이 꺼져 있으면(테스트) 직접 부른다 */
@Component
@RequiredArgsConstructor
public class InventoryJobs {

    static final String RECONCILE = "INV_RECONCILE";

    private final TenantRunner runner;
    private final JobLock lock;
    private final StockService stock;

    /** 매일 03:40(서버 시각) — 회사마다 한 트랜잭션, 여러 인스턴스는 작업 잠금으로 한 곳만 */
    @Scheduled(cron = "${happyboot.jobs.inv-reconcile-cron:0 40 3 * * *}")
    public BatchResult reconcile() {
        return runner.forEachActiveComp(compCd -> {
            if (lock.tryLock(RECONCILE)) {
                stock.reconcile();
            }
        });
    }
}
