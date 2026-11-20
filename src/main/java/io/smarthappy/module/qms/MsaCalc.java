package io.smarthappy.module.qms;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * GRR 계산 (P3 QMS Q2b, docs/29 D9) — 순수 함수. 저장·화면·2부가 같은 계산을 부른다.
 *
 * 평균·범위법(AIAG MSA 4판의 계산 순서): 측정자마다 부품별 반복 범위의 평균 → R̿, EV = R̿·K1(반복 수),
 * 측정자 평균의 최대 − 최소 = X̄diff, AV = √((X̄diff·K2)² − EV²/(n·r)) (음수면 0), GRR = √(EV² + AV²),
 * 부품 평균의 범위 Rp, PV = Rp·K3(부품 수), TV = √(GRR² + PV²). 기준:
 * - PROCESS(공정 변동) — process σ 를 주면 TV = σ, PV = √(TV² − GRR²)(음수면 0). 안 주면 연구 부품으로 TV
 * - TOLERANCE(공차) — %는 공차/6 에 대한 비율(공차 = 특성 상한 − 하한)
 * ndc = ⌊1.41·PV/GRR⌋ (최소 1). 판정은 %GRR 을 정책 구간(10·30)과 비교.
 * 계수형(ATTRIBUTE) = 일치율만: 측정자 안(부품마다 반복이 모두 같음), 측정자 간(모든 측정자·반복이 같음), 기준값이 있으면 기준 대비.
 * 계수 K1·K2·K3 은 d2* 의 역수(공개 상수표) — 표준 원문 표가 아니라 수학 상수라 코드에 둔다.
 *
 * ANOVA(분산분석, D9 2차 — docs/29_P3 45장): 같은 원자료로 2원 분산분석(측정자 × 부품, 반복 = 오차). 상호작용 F 검정의 p 값이
 * {@link #POOL_ALPHA}(AIAG 관례 0.25) 보다 크면 상호작용을 오차에 합친다(풀링). 분산 성분 = (평균제곱 차) / 칸 수, 음수면 0.
 * EV = √σ²반복, AV = √σ²측정자, INT = √σ²상호작용, GRR = √(EV² + AV² + INT²), PV = √σ²부품 — 기준·%·ndc·판정은 평균·범위법과 같다.
 * F 분포 꼬리 확률은 정칙 불완전 베타(연분수, 외부 라이브러리 없이)로 계산한다.
 */
public final class MsaCalc {

    /** 반복 수 → K1 */
    static final Map<Integer, Double> K1 = Map.of(2, 0.8862, 3, 0.5908);
    /** 측정자 수 → K2 */
    static final Map<Integer, Double> K2 = Map.of(2, 0.7071, 3, 0.5231);
    /** 부품 수 → K3 */
    static final Map<Integer, Double> K3 = Map.of(2, 0.7071, 3, 0.5231, 4, 0.4467, 5, 0.4030, 6, 0.3742, 7, 0.3534,
            8, 0.3375, 9, 0.3249, 10, 0.3146);

    public static final int MIN_APPRAISER = 2;
    public static final int MAX_APPRAISER = 3;
    public static final int MIN_PART = 2;
    public static final int MAX_PART_VARIABLE = 10;
    public static final int MAX_PART_ATTRIBUTE = 50;
    public static final int MIN_TRIAL = 2;
    public static final int MAX_TRIAL = 3;
    /** ANOVA 상호작용 풀링 기준 — p 값이 이보다 크면 상호작용을 오차에 합친다(AIAG MSA 관례) */
    public static final double POOL_ALPHA = 0.25;

    private MsaCalc() {
    }

    /** 계산할 수 없는 입력 — code 는 valid.{code} 문구 */
    public static final class MsaException extends RuntimeException {
        private static final long serialVersionUID = 1L;
        private final String code;

        MsaException(String code) {
            super(code);
            this.code = code;
        }

        public String code() {
            return code;
        }
    }

    public enum Verdict {
        ACCEPT, CONDITIONAL, REJECT
    }

    /**
     * 계량형 결과. 값은 소수 6자리(%는 2자리) 반올림. tolerance·processSigma 가 없으면 해당 % 는 null
     */
    public record VariableResult(BigDecimal rbar, BigDecimal xdiff, BigDecimal rp, BigDecimal ev, BigDecimal av, BigDecimal grr,
            BigDecimal pv, BigDecimal tv, BigDecimal pctEv, BigDecimal pctAv, BigDecimal pctGrr, BigDecimal pctPv, int ndc,
            String basis, BigDecimal tolerance, BigDecimal processSigma, Verdict verdict, List<BigDecimal> appraiserAvg,
            List<BigDecimal> partAvg) {
    }

    /** ANOVA 표 한 줄 — source = PART·APPRAISER·INTERACTION·REPEATABILITY·TOTAL. f·p 는 검정이 없는 줄(반복·합계)이면 null */
    public record AnovaRow(String source, int df, BigDecimal ss, BigDecimal ms, BigDecimal f, BigDecimal p) {
    }

    /**
     * ANOVA 결과 — core 는 평균·범위법과 같은 모양(EV·AV·GRR·PV·TV·%·ndc·판정은 ANOVA 성분, R̿·X̄diff·Rp·평균은 같은 원자료의 기술 통계),
     * iv = 상호작용 σ(풀링이면 0), pooled = 상호작용을 오차에 합쳤는지
     */
    public record AnovaResult(VariableResult core, BigDecimal iv, BigDecimal pctIv, boolean pooled, List<AnovaRow> table) {
    }

    /** 계수형 결과(%, 소수 2자리). 기준값이 없으면 vsRef 칸은 null */
    public record AttributeResult(List<BigDecimal> withinPct, BigDecimal betweenPct, List<BigDecimal> vsRefPct, BigDecimal allVsRefPct) {
    }

    /**
     * 계량형 평균·범위법.
     *
     * @param data       [측정자][부품][반복] — 빈 칸 없음
     * @param basis      PROCESS | TOLERANCE
     * @param tolerance  공차(상한 − 하한) — TOLERANCE 면 필수
     * @param processSigma 공정 표준편차 — PROCESS 에서 선택
     * @param okPct      판정 구간: %GRR ≤ okPct 면 ACCEPT
     * @param limitPct   ≤ limitPct 면 CONDITIONAL, 넘으면 REJECT
     */
    public static VariableResult averageRange(double[][][] data, String basis, BigDecimal tolerance, BigDecimal processSigma,
            int okPct, int limitPct) {
        int k = data.length;
        if (k < MIN_APPRAISER || k > MAX_APPRAISER) {
            throw new MsaException("MSA_SHAPE");
        }
        int n = data[0].length;
        if (n < MIN_PART || n > MAX_PART_VARIABLE) {
            throw new MsaException("MSA_SHAPE");
        }
        int r = data[0][0].length;
        if (r < MIN_TRIAL || r > MAX_TRIAL) {
            throw new MsaException("MSA_SHAPE");
        }
        for (double[][] a : data) {
            if (a.length != n) {
                throw new MsaException("MSA_SHAPE");
            }
            for (double[] p : a) {
                if (p.length != r) {
                    throw new MsaException("MSA_SHAPE");
                }
            }
        }
        if ("TOLERANCE".equals(basis) && (tolerance == null || tolerance.signum() <= 0)) {
            throw new MsaException("MSA_NO_TOLERANCE");
        }
        if (processSigma != null && processSigma.signum() <= 0) {
            throw new MsaException("MSA_SIGMA");
        }
        double[] appAvg = new double[k];
        double[] appRbar = new double[k];
        double[] partSum = new double[n];
        for (int a = 0; a < k; a++) {
            double sum = 0;
            double rsum = 0;
            for (int p = 0; p < n; p++) {
                double max = Double.NEGATIVE_INFINITY;
                double min = Double.POSITIVE_INFINITY;
                for (int t = 0; t < r; t++) {
                    double v = data[a][p][t];
                    sum += v;
                    partSum[p] += v;
                    max = Math.max(max, v);
                    min = Math.min(min, v);
                }
                rsum += max - min;
            }
            appAvg[a] = sum / (n * r);
            appRbar[a] = rsum / n;
        }
        double rbar = 0;
        double maxAvg = Double.NEGATIVE_INFINITY;
        double minAvg = Double.POSITIVE_INFINITY;
        for (int a = 0; a < k; a++) {
            rbar += appRbar[a];
            maxAvg = Math.max(maxAvg, appAvg[a]);
            minAvg = Math.min(minAvg, appAvg[a]);
        }
        rbar /= k;
        double xdiff = maxAvg - minAvg;
        double[] partAvg = new double[n];
        double maxP = Double.NEGATIVE_INFINITY;
        double minP = Double.POSITIVE_INFINITY;
        for (int p = 0; p < n; p++) {
            partAvg[p] = partSum[p] / (k * r);
            maxP = Math.max(maxP, partAvg[p]);
            minP = Math.min(minP, partAvg[p]);
        }
        double rp = maxP - minP;

        double ev = rbar * K1.get(r);
        double avSq = Math.pow(xdiff * K2.get(k), 2) - ev * ev / (n * r);
        double av = avSq > 0 ? Math.sqrt(avSq) : 0;
        double grr = Math.sqrt(ev * ev + av * av);
        double pv;
        double tv;
        if (processSigma != null && !"TOLERANCE".equals(basis)) {
            tv = processSigma.doubleValue();
            double pvSq = tv * tv - grr * grr;
            pv = pvSq > 0 ? Math.sqrt(pvSq) : 0;
        } else {
            pv = rp * K3.get(n);
            tv = Math.sqrt(grr * grr + pv * pv);
        }
        // % 의 분모: 공차 기준이면 공차/6, 아니면 TV
        double denom = "TOLERANCE".equals(basis) ? tolerance.doubleValue() / 6 : tv;
        if (denom <= 0) {
            throw new MsaException("MSA_NO_VARIATION");
        }
        double pctGrr = 100 * grr / denom;
        int ndc = grr == 0 ? 1 : Math.max(1, (int) Math.floor(1.41 * pv / grr));
        Verdict verdict = pctGrr <= okPct ? Verdict.ACCEPT : pctGrr <= limitPct ? Verdict.CONDITIONAL : Verdict.REJECT;
        List<BigDecimal> aAvg = new ArrayList<>();
        for (double v : appAvg) {
            aAvg.add(d6(v));
        }
        List<BigDecimal> pAvg = new ArrayList<>();
        for (double v : partAvg) {
            pAvg.add(d6(v));
        }
        return new VariableResult(d6(rbar), d6(xdiff), d6(rp), d6(ev), d6(av), d6(grr), d6(pv), d6(tv), pct(100 * ev / denom),
                pct(100 * av / denom), pct(pctGrr), pct(100 * pv / denom), ndc, basis,
                "TOLERANCE".equals(basis) ? tolerance : null, "TOLERANCE".equals(basis) ? null : processSigma, verdict, aAvg, pAvg);
    }

    /**
     * 계량형 ANOVA(D9 2차). 입력·모양·기준 규칙은 {@link #averageRange} 와 같다(같은 연구를 두 방법으로 비교할 수 있게).
     */
    public static AnovaResult anova(double[][][] data, String basis, BigDecimal tolerance, BigDecimal processSigma, int okPct, int limitPct) {
        VariableResult desc = averageRange(data, basis, tolerance, processSigma, okPct, limitPct); // 모양·기준 검사 + 기술 통계
        int k = data.length;
        int n = data[0].length;
        int r = data[0][0].length;
        double g = 0;
        double[] am = new double[k];
        double[] pm = new double[n];
        double[][] cm = new double[k][n];
        for (int a = 0; a < k; a++) {
            for (int p = 0; p < n; p++) {
                for (int t = 0; t < r; t++) {
                    double v = data[a][p][t];
                    g += v;
                    am[a] += v;
                    pm[p] += v;
                    cm[a][p] += v;
                }
            }
        }
        g /= k * n * r;
        double ssA = 0;
        for (int a = 0; a < k; a++) {
            am[a] /= n * r;
            ssA += n * r * Math.pow(am[a] - g, 2);
        }
        double ssP = 0;
        for (int p = 0; p < n; p++) {
            pm[p] /= k * r;
            ssP += k * r * Math.pow(pm[p] - g, 2);
        }
        double ssT = 0;
        double ssE = 0;
        for (int a = 0; a < k; a++) {
            for (int p = 0; p < n; p++) {
                cm[a][p] /= r;
                for (int t = 0; t < r; t++) {
                    ssT += Math.pow(data[a][p][t] - g, 2);
                    ssE += Math.pow(data[a][p][t] - cm[a][p], 2);
                }
            }
        }
        double ssAP = Math.max(0, ssT - ssA - ssP - ssE);
        int dA = k - 1;
        int dP = n - 1;
        int dAP = (k - 1) * (n - 1);
        int dE = n * k * (r - 1);
        double msA = ssA / dA;
        double msP = ssP / dP;
        double msAP = ssAP / dAP;
        double msE = ssE / dE;
        double fAP = ratio(msAP, msE);
        double pAP = fSurvival(fAP, dAP, dE);
        boolean pooled = pAP > POOL_ALPHA;
        double vE;
        double vAP;
        double denomMs;
        int denomDf;
        if (pooled) {
            vE = (ssAP + ssE) / (dAP + dE);
            vAP = 0;
            denomMs = vE;
            denomDf = dAP + dE;
        } else {
            vE = msE;
            vAP = Math.max(0, (msAP - msE) / r);
            denomMs = msAP;
            denomDf = dAP;
        }
        double vA = Math.max(0, (msA - denomMs) / (n * r));
        double vP = Math.max(0, (msP - denomMs) / (k * r));
        double fA = ratio(msA, denomMs);
        double fP = ratio(msP, denomMs);

        double ev = Math.sqrt(vE);
        double av = Math.sqrt(vA);
        double iv = Math.sqrt(vAP);
        double grr = Math.sqrt(vE + vA + vAP);
        double pv;
        double tv;
        if (processSigma != null && !"TOLERANCE".equals(basis)) {
            tv = processSigma.doubleValue();
            double pvSq = tv * tv - grr * grr;
            pv = pvSq > 0 ? Math.sqrt(pvSq) : 0;
        } else {
            pv = Math.sqrt(vP);
            tv = Math.sqrt(grr * grr + pv * pv);
        }
        double denom = "TOLERANCE".equals(basis) ? tolerance.doubleValue() / 6 : tv;
        if (denom <= 0) {
            throw new MsaException("MSA_NO_VARIATION");
        }
        double pctGrr = 100 * grr / denom;
        int ndc = grr == 0 ? 1 : Math.max(1, (int) Math.floor(1.41 * pv / grr));
        Verdict verdict = pctGrr <= okPct ? Verdict.ACCEPT : pctGrr <= limitPct ? Verdict.CONDITIONAL : Verdict.REJECT;
        VariableResult core = new VariableResult(desc.rbar(), desc.xdiff(), desc.rp(), d6(ev), d6(av), d6(grr), d6(pv), d6(tv),
                pct(100 * ev / denom), pct(100 * av / denom), pct(pctGrr), pct(100 * pv / denom), ndc, basis, desc.tolerance(),
                desc.processSigma(), verdict, desc.appraiserAvg(), desc.partAvg());
        List<AnovaRow> table = List.of(
                new AnovaRow("PART", dP, d6(ssP), d6(msP), d6f(fP), d6(fSurvival(fP, dP, denomDf))),
                new AnovaRow("APPRAISER", dA, d6(ssA), d6(msA), d6f(fA), d6(fSurvival(fA, dA, denomDf))),
                new AnovaRow("INTERACTION", dAP, d6(ssAP), d6(msAP), d6f(fAP), d6(pAP)),
                new AnovaRow("REPEATABILITY", dE, d6(ssE), d6(msE), null, null),
                new AnovaRow("TOTAL", k * n * r - 1, d6(ssT), null, null, null));
        return new AnovaResult(core, d6(iv), pct(100 * iv / denom), pooled, table);
    }

    /** 평균제곱 비 — 분모 0 이면 분자가 있으면 무한대(유의), 없으면 0(유의 아님) */
    private static double ratio(double num, double den) {
        if (den > 0) {
            return num / den;
        }
        return num > 0 ? Double.POSITIVE_INFINITY : 0;
    }

    /** F(d1, d2) 분포의 꼬리 확률 P(F &gt; f) = I_{d2/(d2+d1·f)}(d2/2, d1/2) */
    static double fSurvival(double f, int d1, int d2) {
        if (!(f > 0)) {
            return 1;
        }
        if (Double.isInfinite(f)) {
            return 0;
        }
        return regIncBeta(d2 / (d2 + d1 * f), d2 / 2.0, d1 / 2.0);
    }

    /** 정칙 불완전 베타 I_x(a, b) — 연분수(Lentz). 수렴이 빠른 쪽으로 대칭 변환 */
    static double regIncBeta(double x, double a, double b) {
        if (x <= 0) {
            return 0;
        }
        if (x >= 1) {
            return 1;
        }
        double front = Math.exp(lnGamma(a + b) - lnGamma(a) - lnGamma(b) + a * Math.log(x) + b * Math.log1p(-x));
        if (x < (a + 1) / (a + b + 2)) {
            return front * betaCf(x, a, b) / a;
        }
        return 1 - front * betaCf(1 - x, b, a) / b;
    }

    private static double betaCf(double x, double a, double b) {
        final double tiny = 1e-300;
        final double eps = 1e-15;
        double qab = a + b;
        double qap = a + 1;
        double qam = a - 1;
        double c = 1;
        double d = 1 - qab * x / qap;
        if (Math.abs(d) < tiny) {
            d = tiny;
        }
        d = 1 / d;
        double h = d;
        for (int m = 1; m <= 500; m++) {
            int m2 = 2 * m;
            double aa = m * (b - m) * x / ((qam + m2) * (a + m2));
            d = 1 + aa * d;
            d = Math.abs(d) < tiny ? tiny : d;
            c = 1 + aa / c;
            c = Math.abs(c) < tiny ? tiny : c;
            d = 1 / d;
            h *= d * c;
            aa = -(a + m) * (qab + m) * x / ((a + m2) * (qap + m2));
            d = 1 + aa * d;
            d = Math.abs(d) < tiny ? tiny : d;
            c = 1 + aa / c;
            c = Math.abs(c) < tiny ? tiny : c;
            d = 1 / d;
            double del = d * c;
            h *= del;
            if (Math.abs(del - 1) < eps) {
                break;
            }
        }
        return h;
    }

    /** ln Γ(x), x &gt; 0 — Lanczos(g = 7, 9 항) */
    static double lnGamma(double x) {
        final double[] c = {0.99999999999980993, 676.5203681218851, -1259.1392167224028, 771.32342877765313, -176.61502916214059,
            12.507343278686905, -0.13857109526572012, 9.9843695780195716e-6, 1.5056327351493116e-7};
        if (x < 0.5) {
            return Math.log(Math.PI / Math.abs(Math.sin(Math.PI * x))) - lnGamma(1 - x);
        }
        double xx = x - 1;
        double sum = c[0];
        for (int i = 1; i < 9; i++) {
            sum += c[i] / (xx + i);
        }
        double t = xx + 7.5;
        return 0.5 * Math.log(2 * Math.PI) + (xx + 0.5) * Math.log(t) - t + Math.log(sum);
    }

    /**
     * 계수형 일치율.
     *
     * @param data [측정자][부품][반복] = OK | NG
     * @param ref  [부품] 기준값(OK | NG) — 없으면 null
     */
    public static AttributeResult attribute(String[][][] data, String[] ref) {
        int k = data.length;
        if (k < MIN_APPRAISER || k > MAX_APPRAISER) {
            throw new MsaException("MSA_SHAPE");
        }
        int n = data[0].length;
        if (n < MIN_PART || n > MAX_PART_ATTRIBUTE) {
            throw new MsaException("MSA_SHAPE");
        }
        int r = data[0][0].length;
        if (r < MIN_TRIAL || r > MAX_TRIAL || (ref != null && ref.length != n)) {
            throw new MsaException("MSA_SHAPE");
        }
        List<BigDecimal> within = new ArrayList<>();
        List<BigDecimal> vsRef = ref == null ? null : new ArrayList<>();
        int between = 0;
        int allRef = 0;
        for (int a = 0; a < k; a++) {
            if (data[a].length != n) {
                throw new MsaException("MSA_SHAPE");
            }
            int same = 0;
            int match = 0;
            for (int p = 0; p < n; p++) {
                if (data[a][p].length != r) {
                    throw new MsaException("MSA_SHAPE");
                }
                boolean allSame = true;
                boolean allMatch = true;
                for (int t = 0; t < r; t++) {
                    allSame &= data[a][p][t].equals(data[a][p][0]);
                    allMatch &= ref != null && data[a][p][t].equals(ref[p]);
                }
                same += allSame ? 1 : 0;
                match += allMatch ? 1 : 0;
            }
            within.add(pct(100.0 * same / n));
            if (vsRef != null) {
                vsRef.add(pct(100.0 * match / n));
            }
        }
        for (int p = 0; p < n; p++) {
            String first = data[0][p][0];
            boolean all = true;
            boolean allMatch = ref != null;
            for (int a = 0; a < k; a++) {
                for (int t = 0; t < r; t++) {
                    all &= data[a][p][t].equals(first);
                    allMatch &= ref != null && data[a][p][t].equals(ref[p]);
                }
            }
            between += all ? 1 : 0;
            allRef += allMatch ? 1 : 0;
        }
        return new AttributeResult(within, pct(100.0 * between / n), vsRef, ref == null ? null : pct(100.0 * allRef / n));
    }

    /** 결과 스냅샷(jsonb) 용 — 화면이 이름으로 읽는다 */
    public static Map<String, Object> toMap(VariableResult v) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("rbar", v.rbar());
        m.put("xdiff", v.xdiff());
        m.put("rp", v.rp());
        m.put("ev", v.ev());
        m.put("av", v.av());
        m.put("grr", v.grr());
        m.put("pv", v.pv());
        m.put("tv", v.tv());
        m.put("pctEv", v.pctEv());
        m.put("pctAv", v.pctAv());
        m.put("pctGrr", v.pctGrr());
        m.put("pctPv", v.pctPv());
        m.put("ndc", v.ndc());
        m.put("basis", v.basis());
        m.put("tolerance", v.tolerance());
        m.put("processSigma", v.processSigma());
        m.put("verdict", v.verdict().name());
        m.put("appraiserAvg", v.appraiserAvg());
        m.put("partAvg", v.partAvg());
        return m;
    }

    /** ANOVA 스냅샷 — 평균·범위법 칸 + iv·pctIv·pooled·anova(표) */
    public static Map<String, Object> toMap(AnovaResult r) {
        Map<String, Object> m = toMap(r.core());
        m.put("iv", r.iv());
        m.put("pctIv", r.pctIv());
        m.put("pooled", r.pooled());
        List<Map<String, Object>> rows = new ArrayList<>();
        for (AnovaRow x : r.table()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("source", x.source());
            row.put("df", x.df());
            row.put("ss", x.ss());
            row.put("ms", x.ms());
            row.put("f", x.f());
            row.put("p", x.p());
            rows.add(row);
        }
        m.put("anova", rows);
        return m;
    }

    public static Map<String, Object> toMap(AttributeResult a) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("withinPct", a.withinPct());
        m.put("betweenPct", a.betweenPct());
        m.put("vsRefPct", a.vsRefPct());
        m.put("allVsRefPct", a.allVsRefPct());
        return m;
    }

    static BigDecimal d6(double v) {
        return BigDecimal.valueOf(v).setScale(6, RoundingMode.HALF_UP);
    }

    /** F 값 — 무한대(반복 오차 0 인데 효과가 있음)면 null(화면은 빈칸, p 는 0) */
    static BigDecimal d6f(double v) {
        return Double.isFinite(v) ? d6(v) : null;
    }

    static BigDecimal pct(double v) {
        return BigDecimal.valueOf(v).setScale(2, RoundingMode.HALF_UP);
    }
}
