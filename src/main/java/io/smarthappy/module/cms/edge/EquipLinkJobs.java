package io.smarthappy.module.cms.edge;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import io.smarthappy.platform.job.JobLock;
import io.smarthappy.platform.tenant.TenantRunner;
import io.smarthappy.platform.tenant.TenantRunner.BatchResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 장비 연동 정기 작업 (docs/65 16.4·16.7) — "안 온 것"을 사건으로: 하트비트 끊김 판정 + 명령 시간 초과.
 * 회사마다 `TenantRunner` 트랜잭션 + `JobLock`(한 인스턴스만). 스케줄이 꺼져 있으면(테스트) {@link #check()} 를 직접 부른다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EquipLinkJobs {

    static final String JOB = "cms-equip-link";

    private final TenantRunner runner;
    private final JobLock lock;
    private final EquipLinkService links;
    private final EquipCommandService commands;

    @Scheduled(fixedDelayString = "${happyboot.jobs.equip-link-ms:10000}", initialDelayString = "${happyboot.jobs.equip-link-ms:10000}")
    public BatchResult check() {
        BatchResult result = runner.forEachActiveComp(compCd -> {
            if (!lock.tryLock(JOB)) {
                return;
            }
            int lost = links.judgeLost();
            int expired = commands.expireOverdue();
            if (lost + expired > 0) {
                log.info("장비 연동 판정 compCd={} 끊김 {} · 명령 시간 초과 {}", compCd, lost, expired);
            }
        });
        if (!result.allSucceeded()) {
            log.error("장비 연동 판정 실패 회사 {}", result.failed().keySet());
        }
        return result;
    }
}
