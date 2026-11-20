package io.smarthappy.module.qms;

import java.math.BigDecimal;
import java.util.List;

/**
 * 변경 전후 비교 계산 (docs/29_P3 51.3 EF-2·EF-4) — 순수 함수, 손 검산 시험(ChangeEffectCalcTest).
 *
 * - 계량형: 개수·평균·표본 표준편차(n−1)·규격 밖 개수·Ppk(전체 σ — 개별값이라 부분군 σ 대신)
 * - 계수형: 판정 수·NG 수·NG 율
 * - "모름 ≠ 0": 값이 없거나(n = 0) 표준편차를 못 내면(n &lt; 2) null. 규격이 한쪽만이면 Ppk 는 그쪽만으로
 */
public final class ChangeEffectCalc {

    private ChangeEffectCalc() {
    }

    /** 계량형 한쪽(앞 또는 뒤) — n 외에는 없으면 null */
    public record VarStats(int n, Double mean, Double sd, Double ppk, int outOfSpec) {
    }

    /** 계수형 한쪽 — rate = ng / n (n = 0 이면 null) */
    public record AttrStats(int n, int ng, Double rate) {
    }

    public static VarStats variable(List<BigDecimal> values, BigDecimal lsl, BigDecimal usl) {
        int n = values.size();
        if (n == 0) {
            return new VarStats(0, null, null, null, 0);
        }
        double s = 0;
        int out = 0;
        for (BigDecimal v : values) {
            s += v.doubleValue();
            if ((lsl != null && v.compareTo(lsl) < 0) || (usl != null && v.compareTo(usl) > 0)) {
                out++;
            }
        }
        double mean = s / n;
        if (n < 2) {
            return new VarStats(n, mean, null, null, out);
        }
        double ss = 0;
        for (BigDecimal v : values) {
            double d = v.doubleValue() - mean;
            ss += d * d;
        }
        double sd = Math.sqrt(ss / (n - 1));
        return new VarStats(n, mean, sd, ppk(lsl, usl, mean, sd), out);
    }

    public static AttrStats attribute(int n, int ng) {
        return new AttrStats(n, ng, rate(ng, n));
    }

    /** 분모 0 = 모름(null) */
    public static Double rate(long num, long den) {
        return den <= 0 ? null : (double) num / den;
    }

    static Double ppk(BigDecimal lsl, BigDecimal usl, double mean, double sd) {
        if (sd <= 0 || (lsl == null && usl == null)) {
            return null;
        }
        double u = usl == null ? Double.POSITIVE_INFINITY : (usl.doubleValue() - mean) / (3 * sd);
        double l = lsl == null ? Double.POSITIVE_INFINITY : (mean - lsl.doubleValue()) / (3 * sd);
        return Math.min(u, l);
    }
}
