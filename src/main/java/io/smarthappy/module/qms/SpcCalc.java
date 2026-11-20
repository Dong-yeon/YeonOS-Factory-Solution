package io.smarthappy.module.qms;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * SPC 최소형 계산 (P3 QMS 2부 Q10, docs/29_P3 12.6) — 순수 함수. 조회·고정 한계 저장이 같은 계산을 부른다.
 *
 * X̄-R 관리도(부분군 크기가 달라도 한 식): σ̂(군내) = 평균(Rᵢ / d2(nᵢ)), 중심 X̿ = Σnᵢx̄ᵢ / Σnᵢ,
 * X̄ 한계 X̿ ± 3σ̂/√nᵢ, R 중심 d2(nᵢ)σ̂·한계 (d2 ± 3d3)σ̂(하한 음수면 0). n 이 모두 같으면 X̿ ± A2R̄, D3R̄·D4R̄ 와 같다.
 * 런 규칙 = Western Electric 1~4 를 표준화한 zᵢ = (x̄ᵢ − X̿)/(σ̂/√nᵢ) 위에서, 조건을 채운 마지막 점(그 점도 같은 쪽)에 표시.
 * R 은 한계 밖(R1)만. 공정능력: Cp·Cpk = 군내 σ̂, Pp·Ppk = 전체 표준편차(n − 1), 단측 규격은 단측 지수만.
 * 계수 d2·d3 은 수학 상수(공개 상수표)라 코드에 둔다.
 */
public final class SpcCalc {

    public static final int MIN_N = 2;
    public static final int MAX_N = 25;
    public static final Set<String> ALL_RULES = Set.of("WE1", "WE2", "WE3", "WE4");
    public static final Set<String> DEFAULT_RULES = Set.of("WE1", "WE2");

    /** 부분군 크기 n(2~25) → d2 */
    static final double[] D2 = {0, 0, 1.128, 1.693, 2.059, 2.326, 2.534, 2.704, 2.847, 2.970, 3.078, 3.173, 3.258, 3.336, 3.407,
        3.472, 3.532, 3.588, 3.640, 3.689, 3.735, 3.778, 3.819, 3.858, 3.895, 3.931};
    /** 부분군 크기 n(2~25) → d3 */
    static final double[] D3 = {0, 0, 0.853, 0.888, 0.880, 0.864, 0.848, 0.833, 0.820, 0.808, 0.797, 0.787, 0.778, 0.770, 0.763,
        0.756, 0.750, 0.744, 0.739, 0.734, 0.729, 0.724, 0.720, 0.716, 0.712, 0.708};

    private SpcCalc() {
    }

    /** 관리한계의 바탕: 중심 X̿ 와 군내 σ̂ */
    public record Limits(double center, double sigma) {
    }

    /** 부분군 한 점 — index = 입력 순번(제외된 부분군은 점이 없다) */
    public record Point(int index, int n, double mean, double range, double xUcl, double xLcl, double rCl, double rUcl, double rLcl,
            Double z, Set<String> rules) {
    }

    /** 공정능력 — 계산할 수 없는 지수는 null */
    public record Capability(int n, double mean, double sigmaWithin, double sigmaOverall, Double cp, Double cpk, Double pp, Double ppk) {
    }

    /**
     * @param limits    이 관리도를 그린 한계(고정 한계가 있으면 그것, 없으면 calcLimits)
     * @param calcLimits 보이는 부분군에서 계산한 한계(점이 없으면 null)
     * @param excluded  크기가 2~25 밖이라 뺀 부분군 수
     */
    public record Result(Limits limits, Limits calcLimits, List<Point> points, int excluded, Capability capability) {
    }

    /**
     * @param subgroups 부분군별 값(순서 = 시간 순)
     * @param fixed     고정 관리한계(없으면 null → 보이는 부분군에서 계산)
     * @param rules     활성 규칙(WE1~WE4)
     * @param lsl       규격 하한(없으면 null)
     * @param usl       규격 상한(없으면 null)
     * @param specOk    부분군 규격이 하나로 같을 때만 true — 아니면 공정능력을 계산하지 않는다
     */
    public static Result chart(List<List<BigDecimal>> subgroups, Limits fixed, Set<String> rules, BigDecimal lsl, BigDecimal usl,
            boolean specOk) {
        List<Integer> idx = new ArrayList<>();
        List<double[]> vals = new ArrayList<>();
        int excluded = 0;
        for (int i = 0; i < subgroups.size(); i++) {
            List<BigDecimal> g = subgroups.get(i);
            if (g.size() < MIN_N || g.size() > MAX_N) {
                excluded++;
                continue;
            }
            idx.add(i);
            vals.add(g.stream().mapToDouble(BigDecimal::doubleValue).toArray());
        }
        if (vals.isEmpty()) {
            return new Result(fixed, null, List.of(), excluded, null);
        }
        int k = vals.size();
        double[] means = new double[k];
        double[] ranges = new double[k];
        double sumNx = 0;
        double sumRd2 = 0;
        int total = 0;
        for (int i = 0; i < k; i++) {
            double[] v = vals.get(i);
            double s = 0;
            double min = Double.POSITIVE_INFINITY;
            double max = Double.NEGATIVE_INFINITY;
            for (double x : v) {
                s += x;
                min = Math.min(min, x);
                max = Math.max(max, x);
            }
            means[i] = s / v.length;
            ranges[i] = max - min;
            sumNx += s;
            sumRd2 += ranges[i] / D2[v.length];
            total += v.length;
        }
        Limits calc = new Limits(sumNx / total, sumRd2 / k);
        Limits use = fixed != null ? fixed : calc;
        Double[] z = new Double[k];
        List<Point> points = new ArrayList<>(k);
        for (int i = 0; i < k; i++) {
            int n = vals.get(i).length;
            double se = use.sigma() / Math.sqrt(n);
            z[i] = se > 0 ? (means[i] - use.center()) / se : null;
        }
        List<Set<String>> flags = runRules(z, rules);
        for (int i = 0; i < k; i++) {
            int n = vals.get(i).length;
            double se = use.sigma() / Math.sqrt(n);
            double rCl = D2[n] * use.sigma();
            double rUcl = (D2[n] + 3 * D3[n]) * use.sigma();
            double rLcl = Math.max(0, (D2[n] - 3 * D3[n]) * use.sigma());
            Set<String> f = flags.get(i);
            if (ranges[i] > rUcl || (rLcl > 0 && ranges[i] < rLcl)) {
                f.add("R1");
            }
            points.add(new Point(idx.get(i), n, means[i], ranges[i], use.center() + 3 * se, use.center() - 3 * se, rCl, rUcl, rLcl, z[i],
                    Set.copyOf(f)));
        }
        Capability cap = specOk ? capability(vals, calc, lsl, usl) : null;
        return new Result(use, calc, points, excluded, cap);
    }

    // ------------------------------------------------------------------ p 관리도 (docs/29_P3 28장)

    /** p 관리도에 쓸 수 있는 규칙 — 한계 밖 1점(WE1)·중심 같은 쪽 8점(WE4). 근거 28.1 PC-4 */
    public static final Set<String> P_RULES = Set.of("WE1", "WE4");
    public static final Set<String> P_DEFAULT_RULES = Set.of("WE1");

    /** 부분군 한 점 — n = 살아 있는 OK/NG 샘플 수, d = NG 수, ucl·lcl = 이 점의 한계(n 마다 다름) */
    public record PPoint(int index, int n, int d, double p, double ucl, double lcl, Double z, Set<String> rules) {
    }

    /** pbar = 그린 한계의 p̄(고정 또는 calcPbar), calcPbar = 보이는 부분군 Σd/Σn(점이 없으면 null), excluded = n 이 0 이라 뺀 부분군 수 */
    public record PResult(Double pbar, Double calcPbar, List<PPoint> points, int excluded) {
    }

    /** p̄ 의 표준편차 바탕 √(p̄(1-p̄)) — 한계 = p̄ ± 3·이 값/√n (X̄ 한계 center ± 3σ/√n 과 같은 모양) */
    public static double pSigma(double pbar) {
        return Math.sqrt(pbar * (1 - pbar));
    }

    /**
     * p 관리도 — 부분군마다 한계 p̄ ± 3√(p̄(1-p̄)/n_i)(하한 0·상한 1 에서 자름). 규칙은 표준화 z_i = (p_i - p̄)/√(p̄(1-p̄)/n_i) 로 본다
     * (부분군 크기가 달라도 같은 눈금 — 표준화 p 관리도). p̄ 가 0 또는 1 이면 σ = 0 이라 z 없음(규칙 밖)
     *
     * @param subgroups 부분군별 {n, d}(순서 = 시간 순)
     * @param fixedPbar 고정 관리한계의 p̄(없으면 null → 보이는 부분군에서 Σd/Σn)
     * @param rules     활성 규칙(P_RULES 의 부분집합)
     */
    public static PResult pChart(List<int[]> subgroups, Double fixedPbar, Set<String> rules) {
        List<Integer> idx = new ArrayList<>();
        List<int[]> vals = new ArrayList<>();
        int excluded = 0;
        long sumN = 0;
        long sumD = 0;
        for (int i = 0; i < subgroups.size(); i++) {
            int[] g = subgroups.get(i);
            if (g[0] <= 0) {
                excluded++;
                continue;
            }
            idx.add(i);
            vals.add(g);
            sumN += g[0];
            sumD += g[1];
        }
        if (vals.isEmpty()) {
            return new PResult(fixedPbar, null, List.of(), excluded);
        }
        double calc = (double) sumD / sumN;
        double pbar = fixedPbar != null ? fixedPbar : calc;
        double sd = pSigma(pbar);
        int k = vals.size();
        Double[] z = new Double[k];
        for (int i = 0; i < k; i++) {
            int[] g = vals.get(i);
            double se = sd / Math.sqrt(g[0]);
            z[i] = se > 0 ? ((double) g[1] / g[0] - pbar) / se : null;
        }
        Set<String> use = new LinkedHashSet<>(rules);
        use.retainAll(P_RULES);
        List<Set<String>> flags = runRules(z, use);
        List<PPoint> points = new ArrayList<>(k);
        for (int i = 0; i < k; i++) {
            int[] g = vals.get(i);
            double se = sd / Math.sqrt(g[0]);
            points.add(new PPoint(idx.get(i), g[0], g[1], (double) g[1] / g[0], Math.min(1, pbar + 3 * se), Math.max(0, pbar - 3 * se), z[i],
                    Set.copyOf(flags.get(i))));
        }
        return new PResult(pbar, calc, points, excluded);
    }

    /** WE1~4 — 각 점에 걸린 규칙(조건을 채운 창의 마지막 점, 그 점도 같은 쪽일 때). z 가 없는 점(σ̂ = 0)은 규칙 밖 */
    static List<Set<String>> runRules(Double[] z, Set<String> rules) {
        List<Set<String>> out = new ArrayList<>(z.length);
        for (int i = 0; i < z.length; i++) {
            Set<String> f = new LinkedHashSet<>();
            if (z[i] != null) {
                if (rules.contains("WE1") && Math.abs(z[i]) > 3) {
                    f.add("WE1");
                }
                if (rules.contains("WE2") && beyond(z, i, 3, 2, 2)) {
                    f.add("WE2");
                }
                if (rules.contains("WE3") && beyond(z, i, 5, 4, 1)) {
                    f.add("WE3");
                }
                if (rules.contains("WE4") && beyond(z, i, 8, 8, 0)) {
                    f.add("WE4");
                }
            }
            out.add(f);
        }
        return out;
    }

    /** i 로 끝나는 창(크기 w)에서 같은 쪽으로 |z| > limit 인 점이 need 개 이상이고 i 자신도 그쪽인가 (limit 0 = 중심선 같은 쪽) */
    private static boolean beyond(Double[] z, int i, int w, int need, double limit) {
        if (i + 1 < w) {
            return false;
        }
        for (int side : new int[] {1, -1}) {
            if (z[i] * side <= limit) {
                continue;
            }
            int c = 0;
            for (int j = i - w + 1; j <= i; j++) {
                if (z[j] != null && z[j] * side > limit) {
                    c++;
                }
            }
            if (c >= need) {
                return true;
            }
        }
        return false;
    }

    static Capability capability(List<double[]> vals, Limits calc, BigDecimal lsl, BigDecimal usl) {
        int n = 0;
        double s = 0;
        for (double[] v : vals) {
            for (double x : v) {
                s += x;
                n++;
            }
        }
        double mean = s / n;
        double ss = 0;
        for (double[] v : vals) {
            for (double x : v) {
                ss += (x - mean) * (x - mean);
            }
        }
        double overall = n > 1 ? Math.sqrt(ss / (n - 1)) : 0;
        double within = calc.sigma();
        Double lo = lsl == null ? null : lsl.doubleValue();
        Double hi = usl == null ? null : usl.doubleValue();
        return new Capability(n, mean, within, overall, cp(lo, hi, within), cpk(lo, hi, mean, within), cp(lo, hi, overall),
                cpk(lo, hi, mean, overall));
    }

    private static Double cp(Double lo, Double hi, double sigma) {
        return lo == null || hi == null || sigma <= 0 ? null : (hi - lo) / (6 * sigma);
    }

    private static Double cpk(Double lo, Double hi, double mean, double sigma) {
        if (sigma <= 0 || (lo == null && hi == null)) {
            return null;
        }
        double u = hi == null ? Double.POSITIVE_INFINITY : (hi - mean) / (3 * sigma);
        double l = lo == null ? Double.POSITIVE_INFINITY : (mean - lo) / (3 * sigma);
        return Math.min(u, l);
    }
}
