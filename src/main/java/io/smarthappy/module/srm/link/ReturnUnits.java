package io.smarthappy.module.srm.link;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 교체 요구 반품의 수량 → 발주 단위 (SRM 2부 R4c-단위, docs/31 4.12.7). 반품은 LOT(기본) 단위, 되돌릴 입고는 발주 단위라
 * 품목 단위 환산표(입고 게이트와 같은 표)로 기본 단위를 거쳐 바꾼다. 나누어떨어지지 않거나 발주 단위 자릿수를 넘으면 쓰지 않는다.
 * 반품 서비스(만들기·수정·확정 — 입력 오류)와 출고 게이트(되돌릴 때 — 그때의 환산표)가 같은 계산을 쓴다.
 */
public final class ReturnUnits {

    private static final int WORK_SCALE = 12;

    private ReturnUnits() {
    }

    /** 발주 단위 값 — exact = 나누어떨어짐(작업 자릿수 12 안에서) */
    public record PoQty(BigDecimal value, boolean exact) {

        /** 정확하고 발주 단위 자릿수(null 이면 수량 칸 자릿수 6) 안인가 */
        public boolean fits(Integer poScale) {
            return exact && value.scale() <= scale(poScale);
        }

        /** 오류 문구에 보일 값 — 나누어떨어지지 않으면 발주 단위 자릿수 + 2 에서 자르고 … */
        public String show(Integer poScale) {
            return exact ? value.toPlainString() : strip(value.setScale(scale(poScale) + 2, RoundingMode.DOWN)).toPlainString() + "…";
        }
    }

    /**
     * 반품 수량의 발주 단위 값 — 환산이 없으면(factor 둘 중 하나 null) null.
     *
     * @param factor   반품 단위 → 기본 단위(같으면 1)
     * @param poFactor 발주 단위 → 기본 단위(같으면 1)
     */
    public static PoQty poQty(BigDecimal qty, BigDecimal factor, BigDecimal poFactor) {
        if (factor == null || poFactor == null) {
            return null;
        }
        BigDecimal base = qty.multiply(factor);
        BigDecimal q = base.divide(poFactor, WORK_SCALE, RoundingMode.DOWN);
        return new PoQty(strip(q), q.multiply(poFactor).compareTo(base) == 0);
    }

    public static int scale(Integer poScale) {
        return poScale == null ? SrmPoReceiptGate.QTY_SCALE : Math.min(poScale, SrmPoReceiptGate.QTY_SCALE);
    }

    private static BigDecimal strip(BigDecimal v) {
        BigDecimal s = v.stripTrailingZeros();
        return s.scale() < 0 ? s.setScale(0) : s;
    }
}
