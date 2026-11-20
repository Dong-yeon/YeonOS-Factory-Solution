package io.smarthappy.module.qms;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

/**
 * 검사 판정 — 순수 함수 (docs/29_P3 12.2 Q6-6). 살아 있는 측정값만 받는다(무효 처리된 값은 부르는 쪽이 뺀다).
 *
 * - 불합격 = 계량은 규격 밖(lsl·usl 중 있는 쪽만 본다, 경계값은 합격), 계수는 NG
 * - 불합격 수 ≥ Re → NG(샘플을 다 재지 않아도), 샘플을 다 쟀고 불합격 수 ≤ Ac → OK, 그 밖 OPEN(미정)
 * - 검사 = 행이 하나라도 OPEN 이면 OPEN, 아니면 하나라도 NG 면 NG, 모두 OK 면 OK
 */
public final class InspJudge {

    private InspJudge() {
    }

    public enum Verdict {
        OK, NG, OPEN
    }

    /** 행 판정 기준(검사 행 스냅샷) */
    public record Spec(String dataType, BigDecimal lsl, BigDecimal usl, int sampleSize, int ac, int re) {
        public Spec {
            Objects.requireNonNull(dataType);
            if (sampleSize < 1 || ac < 0 || re <= ac) {
                throw new IllegalArgumentException("sampleSize >= 1, ac >= 0, re > ac");
            }
        }
    }

    /** 측정값 하나 — 계량은 value, 계수는 ok */
    public record Meas(BigDecimal value, Boolean ok) {
    }

    /** 행 결과: 잰 샘플 수, 불합격 수, 판정 */
    public record LineResult(int measured, int nonconforming, Verdict verdict) {
    }

    public static boolean nonconforming(Spec spec, Meas m) {
        if ("ATTRIBUTE".equals(spec.dataType())) {
            return !Boolean.TRUE.equals(m.ok());
        }
        BigDecimal v = Objects.requireNonNull(m.value(), "value");
        return (spec.lsl() != null && v.compareTo(spec.lsl()) < 0) || (spec.usl() != null && v.compareTo(spec.usl()) > 0);
    }

    public static LineResult line(Spec spec, List<Meas> live) {
        int bad = (int) live.stream().filter(m -> nonconforming(spec, m)).count();
        Verdict v = bad >= spec.re() ? Verdict.NG : live.size() >= spec.sampleSize() && bad <= spec.ac() ? Verdict.OK : Verdict.OPEN;
        return new LineResult(live.size(), bad, v);
    }

    public static Verdict head(List<Verdict> lines) {
        if (lines.isEmpty() || lines.contains(Verdict.OPEN)) {
            return Verdict.OPEN;
        }
        return lines.contains(Verdict.NG) ? Verdict.NG : Verdict.OK;
    }
}
