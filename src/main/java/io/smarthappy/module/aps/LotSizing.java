package io.smarthappy.module.aps;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 로트 크기 (docs/38 17.3) — 순수 함수. MPS 제안(16장)과 부족 → 구매 요청(17.4)이 같이 쓴다.
 * <ol>
 * <li>필요 ≤ 0 → 0</li>
 * <li>{@code EX} 그대로 / {@code FX} 로트 수량의 배수로 올림 / {@code MIN} max(필요, 로트 수량)</li>
 * <li>배수가 있으면 그 배수로 올림(SAP 반올림 값 — 로트 크기를 정한 뒤)</li>
 * <li>단위 자릿수로 올림(모자라게 만들지 않는다)</li>
 * </ol>
 * 로트 규칙 값은 시스템 고정(EX·FX·MIN) — 회사가 늘리는 공통코드가 아니다(이 분기가 모르는 값이 생기면 안 됨).
 */
public final class LotSizing {

    public static final String EX = "EX";
    public static final String FX = "FX";
    public static final String MIN = "MIN";

    private LotSizing() {
    }

    public static boolean isRule(String rule) {
        return EX.equals(rule) || FX.equals(rule) || MIN.equals(rule);
    }

    /**
     * @param need    필요(부족) 수량
     * @param rule    EX·FX·MIN (null = EX)
     * @param lotQty  FX·MIN 의 로트 수량(> 0). 없으면 EX 처럼
     * @param mult    배수(> 0, 없으면 null)
     * @param scale   단위 소수 자릿수(0~6)
     */
    public static BigDecimal size(BigDecimal need, String rule, BigDecimal lotQty, BigDecimal mult, int scale) {
        if (need == null || need.signum() <= 0) {
            return BigDecimal.ZERO;
        }
        boolean hasLot = lotQty != null && lotQty.signum() > 0;
        BigDecimal q;
        if (FX.equals(rule) && hasLot) {
            q = lotQty.multiply(need.divide(lotQty, 0, RoundingMode.CEILING));
        } else if (MIN.equals(rule) && hasLot) {
            q = need.max(lotQty);
        } else {
            q = need;
        }
        if (mult != null && mult.signum() > 0) {
            q = mult.multiply(q.divide(mult, 0, RoundingMode.CEILING));
        }
        return q.setScale(Math.max(0, Math.min(scale, 6)), RoundingMode.CEILING);
    }
}
