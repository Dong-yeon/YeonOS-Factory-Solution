package io.smarthappy.module.cms;

import java.math.BigDecimal;

/**
 * 카운터 계산 (docs/33 4.4) — 순수 함수. 읽기 입력·화면 미리보기·2부 자동 입력이 모두 이것만 부른다.
 * 누적 = Σ 구간 증가량(무효 제외)이고, 합은 SQL 이 낸다(행이 많아도 한 번에) — 여기는 한 행의 증가량과 금형 수명 상태만.
 */
public final class CounterTotals {

    /** 금형 수명 대비: 수명 없음 NONE, 90% 미만 OK, 90% 이상 NEAR, 100% 이상 OVER */
    public enum Life { NONE, OK, NEAR, OVER }

    /** 수명 경고 비율 — 화면 경고만(사용 중지 자동 없음, docs/33 C3) */
    static final BigDecimal NEAR_RATIO = new BigDecimal("0.9");

    private CounterTotals() {
    }

    /**
     * 계기 값 → 구간 증가량. 직전 계기 값이 없으면 0(이 읽기가 기준), 리셋이면 이번 계기 값(0 부터 다시 셈),
     * 줄었는데 리셋이 아니면 null — 호출자가 "리셋 확인" 칸 오류로 돌려준다. DB 트리거(ck_cms_counter_reading_chain)와 같은 식
     */
    public static BigDecimal delta(BigDecimal prevGauge, BigDecimal gauge, boolean reset) {
        if (reset) {
            return gauge;
        }
        if (prevGauge == null) {
            return BigDecimal.ZERO;
        }
        return gauge.compareTo(prevGauge) < 0 ? null : gauge.subtract(prevGauge);
    }

    /** 누적 vs 수명(타수) */
    public static Life life(BigDecimal total, Long lifeShots) {
        if (lifeShots == null || lifeShots <= 0) {
            return Life.NONE;
        }
        BigDecimal life = BigDecimal.valueOf(lifeShots);
        if (total.compareTo(life) >= 0) {
            return Life.OVER;
        }
        return total.compareTo(life.multiply(NEAR_RATIO)) >= 0 ? Life.NEAR : Life.OK;
    }
}
