package io.smarthappy.module.cms;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import io.smarthappy.platform.time.BizClock;
import lombok.RequiredArgsConstructor;

/**
 * 설비 이벤트 수신 멱등 기록(cms_equip_inbox) 보존 정리 (docs/70 W2·4.6, R2).
 * 기간 = 보존 클래스 EQUIP_INBOX(기본 7일·최소 3일) + 회사 보존 기간(sys_retention_policy), 해제 안 된 법적 보류의 시작보다 새 행은 남긴다.
 * DB 트리거가 최소 기간·보류를 한 번 더 본다. 회사 범위 트랜잭션 안에서만(CmsJobs 가 TenantRunner 로 부른다).
 */
@Service
@RequiredArgsConstructor
public class EquipInboxPurge {

    /** 한 문장에 지우는 행 수 */
    static final int CHUNK = 5_000;
    /** 트랜잭션(회사) 하나에 지우는 최대 행 수 — 넘으면 작업이 다음 바퀴에서 이어 간다 */
    static final int PER_TX = 50_000;

    private final CollectorMapper mapper;
    private final BizClock clock;

    /** @return 지운 행 수 (PER_TX 와 같으면 남은 것이 더 있을 수 있다) */
    @Transactional(propagation = Propagation.MANDATORY)
    public int purge() {
        Instant cutoff = cutoff(clock.instant());
        int total = 0;
        while (total < PER_TX) {
            int n = mapper.purgeInbox(cutoff, Math.min(CHUNK, PER_TX - total));
            total += n;
            if (n < CHUNK) {
                break;
            }
        }
        return total;
    }

    /** 이 시각보다 먼저 받은 행만 지운다 */
    Instant cutoff(Instant now) {
        Instant cutoff = now.minus(Duration.ofDays(mapper.inboxKeepDays()));
        OffsetDateTime hold = mapper.inboxHoldFrom();
        if (hold != null && hold.toInstant().isBefore(cutoff)) {
            cutoff = hold.toInstant();     // 보류 기간에 걸친 행은 보류가 풀릴 때까지(보수적으로 보류 시작 이후는 모두)
        }
        return cutoff;
    }
}
