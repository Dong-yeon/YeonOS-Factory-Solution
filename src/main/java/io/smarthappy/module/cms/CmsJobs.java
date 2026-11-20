package io.smarthappy.module.cms;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import io.smarthappy.platform.job.JobLock;
import io.smarthappy.platform.tenant.TenantRunner;
import io.smarthappy.platform.tenant.TenantRunner.BatchResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * CMS 정기 작업 (docs/33 4.3). 스케줄이 꺼져 있으면(테스트) 메서드를 직접 부른다.
 * 매시 — 공장마다 시간대가 달라 "오늘"이 바뀌는 시각이 다르므로 하루 한 번이 아니라 매시 돌린다(이미 만든 예정은 건너뜀).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CmsJobs {

    static final String PM_GENERATE = "cms-pm-generate";
    static final String COUNTER_AUTO = "cms-counter-auto";
    static final String EQUIP_INBOX_PURGE = "cms-equip-inbox-purge";
    /** 정리 한 번에 회사마다 도는 최대 바퀴(바퀴 = 트랜잭션 하나, 최대 EquipInboxPurge.PER_TX 행) */
    static final int PURGE_ROUNDS = 20;

    private final TenantRunner runner;
    private final JobLock lock;
    private final PmGenerator generator;
    private final CounterAutoService counterAuto;
    private final EquipInboxPurge inboxPurge;

    @Scheduled(cron = "${happyboot.jobs.cms-pm-cron:0 5 * * * *}")
    public BatchResult pmGenerate() {
        BatchResult result = runner.forEachActiveComp(compCd -> {
            if (!lock.tryLock(PM_GENERATE)) {
                log.info("예방점검 예정 생성: 다른 인스턴스가 실행 중 compCd={}", compCd);
                return;
            }
            CmsPmDtos.GenResult g = generator.generate("SYSTEM");
            if (g.runs() > 0) {
                log.info("예방점검 예정 {}건(작업지시 {}) compCd={}", g.runs(), g.workOrders(), compCd);
            }
        });
        if (!result.allSucceeded()) {
            log.error("예방점검 예정 생성 실패 회사 {}", result.failed().keySet());
        }
        return result;
    }

    /** 카운터 자동 입력 (C10, D23) — 매시. 원장 행 키로 두 번 세지 않으니 겹쳐 돌아도 안전하지만 JobLock 으로 한 인스턴스만 */
    @Scheduled(cron = "${happyboot.jobs.cms-counter-auto-cron:0 20 * * * *}")
    public BatchResult counterAuto() {
        BatchResult result = runner.forEachActiveComp(compCd -> {
            if (!lock.tryLock(COUNTER_AUTO)) {
                log.info("카운터 자동 입력: 다른 인스턴스가 실행 중 compCd={}", compCd);
                return;
            }
            CounterAutoService.AutoResult r = counterAuto.run();
            if (r.sources() > 0) {
                log.info("카운터 자동 입력 카운터 {}개·출처 {}건·예방점검 {}건 compCd={}", r.counters(), r.sources(), r.pmRuns(), compCd);
            }
        });
        if (!result.allSucceeded()) {
            log.error("카운터 자동 입력 실패 회사 {}", result.failed().keySet());
        }
        return result;
    }

    /**
     * 설비 이벤트 수신 멱등 기록 보존 정리 (docs/70 W2) — 매시 40분(보존 판정은 일 단위, 많이 쌓이는 공장은 시간마다 나눠 지운다).
     * 회사마다 트랜잭션 하나에 최대 PER_TX 행, 다 못 지운 회사만 다음 바퀴(최대 PURGE_ROUNDS). JobLock 으로 한 인스턴스만
     *
     * @return 회사별 지운 행 수 합(시험용)
     */
    @Scheduled(cron = "${happyboot.jobs.cms-equip-inbox-purge-cron:0 40 * * * *}")
    public long equipInboxPurge() {
        Map<String, Long> deleted = new TreeMap<>();
        Set<String> more = new HashSet<>(runner.activeCompCodes());
        for (int round = 0; round < PURGE_ROUNDS && !more.isEmpty(); round++) {
            Set<String> next = new HashSet<>();
            for (String compCd : more) {
                try {
                    int n = runner.call(compCd, () -> lock.tryLock(EQUIP_INBOX_PURGE) ? inboxPurge.purge() : 0);
                    deleted.merge(compCd, (long) n, Long::sum);
                    if (n >= EquipInboxPurge.PER_TX) {
                        next.add(compCd);
                    }
                } catch (RuntimeException e) {
                    log.error("설비 이벤트 수신 기록 정리 실패 compCd={}", compCd, e);
                }
            }
            more = next;
        }
        deleted.forEach((compCd, n) -> {
            if (n > 0) {
                log.info("설비 이벤트 수신 기록 정리 {}행 compCd={}", n, compCd);
            }
        });
        if (!more.isEmpty()) {
            log.warn("설비 이벤트 수신 기록 정리: 바퀴 상한까지 지우고도 남음 회사 {} — 다음 실행에서 이어 감(docs/70 W4 파티션 검토)", more);
        }
        return deleted.values().stream().mapToLong(Long::longValue).sum();
    }
}
