package io.smarthappy.module.cms.edge;

import java.time.Duration;
import java.time.Instant;

/**
 * "지금 연결돼 있나" 판정식 한 곳 (docs/65 16.7) — 저장하지 않고 조회·정기 작업이 같은 식을 쓴다(SKILL "시간이 만드는 상태는 저장하지 않는다").
 * 끊김 = 마지막으로 본 시각 + 주기 × 놓친 횟수(정책 EQUIP_HB_MISS, OPC UA lifetime ≥ keepalive × 3)를 넘김. 정기 작업의 SQL(lostCandidates)과 같은 식.
 */
public final class EquipLinkJudge {

    /** OFF = 연동 없음·꺼짐, NEVER = 한 번도 못 봄, ONLINE, LOST */
    public static final String OFF = "OFF";
    public static final String NEVER = "NEVER";
    public static final String ONLINE = "ONLINE";
    public static final String LOST = "LOST";

    private EquipLinkJudge() {
    }

    public static String judge(String useYn, Instant lastSeenAt, int hbSec, int miss, Instant now) {
        if (!"Y".equals(useYn)) {
            return OFF;
        }
        if (lastSeenAt == null) {
            return NEVER;
        }
        return now.isAfter(deadline(lastSeenAt, hbSec, miss)) ? LOST : ONLINE;
    }

    /** 이 시각을 넘기면 끊김 */
    public static Instant deadline(Instant lastSeenAt, int hbSec, int miss) {
        return lastSeenAt.plus(Duration.ofSeconds((long) hbSec * miss));
    }
}
