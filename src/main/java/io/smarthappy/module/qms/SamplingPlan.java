package io.smarthappy.module.qms;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * 샘플링 계획 판정 — 순수 함수 (docs/29 3장 "판정 함수", 26 채택 3번). 2부 검사 실행은 이 함수만 부른다.
 *
 * - FIXED: n 고정(로트보다 크면 로트 전체), Ac 직접
 * - ALL: n = 로트, Ac 직접(보통 0)
 * - PERCENT: n = 올림(로트 × 비율 / 100), 최소 1, 최대 로트
 * - AQL: 표 1(검사 수준·로트 → 시료 문자) → 표 2(엄격도·문자·AQL → n·Ac·Re). n ≥ 로트면 전수(같은 Ac·Re — ISO 2859-1 의 관행)
 *
 * 1회 샘플링만. 판정: 불량 ≤ Ac 합격, ≥ Re 불합격, 그 사이(수월한 검사의 간격)는 "합격 + 보통 검사 복귀".
 */
public final class SamplingPlan {

    /** 로트 크기 상한 — 전수 시료가 int 를 넘지 않게 */
    public static final long MAX_LOT = 1_000_000_000L;

    public enum Verdict {
        ACCEPT, REJECT, ACCEPT_TO_NORMAL
    }

    /** 표에 맞는 줄이 없거나 입력이 범위 밖 — code 는 화면 칸 오류 코드로 쓴다 */
    public static final class SamplingException extends RuntimeException {
        private static final long serialVersionUID = 1L;
        private final String code;

        public SamplingException(String code) {
            super(code);
            this.code = code;
        }

        public String code() {
            return code;
        }
    }

    /** 로트 하나에 정해진 시료 — n·Ac·Re 와 AQL 이면 시료 문자 */
    public record Sample(long lotQty, int sampleSize, int ac, int re, String codeLetter, boolean all) {

        public Verdict judge(int defects) {
            if (defects < 0 || defects > sampleSize) {
                throw new SamplingException("DEFECTS");
            }
            if (defects <= ac) {
                return Verdict.ACCEPT;
            }
            return defects >= re ? Verdict.REJECT : Verdict.ACCEPT_TO_NORMAL;
        }
    }

    private final String method;
    private final Integer fixedN;
    private final BigDecimal pct;
    private final Integer ac;
    private final String inspLevel;
    private final String severity;
    private final BigDecimal aql;
    private final AqlTable table;

    private SamplingPlan(String method, Integer fixedN, BigDecimal pct, Integer ac, String inspLevel, String severity,
            BigDecimal aql, AqlTable table) {
        this.method = method;
        this.fixedN = fixedN;
        this.pct = pct;
        this.ac = ac;
        this.inspLevel = inspLevel;
        this.severity = severity;
        this.aql = aql;
        this.table = table;
    }

    public static SamplingPlan fixed(int n, int ac) {
        return new SamplingPlan("FIXED", n, null, ac, null, null, null, null);
    }

    public static SamplingPlan all(int ac) {
        return new SamplingPlan("ALL", null, null, ac, null, null, null, null);
    }

    public static SamplingPlan percent(BigDecimal pct, int ac) {
        return new SamplingPlan("PERCENT", null, Objects.requireNonNull(pct), ac, null, null, null, null);
    }

    public static SamplingPlan aql(AqlTable table, String inspLevel, String severity, BigDecimal aql) {
        return new SamplingPlan("AQL", null, null, null, inspLevel, severity, Objects.requireNonNull(aql),
                Objects.requireNonNull(table));
    }

    /** 저장된 계획 값으로 — AQL 이면 그 표준의 표가 필요하다(없으면 빈 표: 판정 때 NO_AQL_LETTER) */
    public static SamplingPlan of(String method, Integer fixedN, BigDecimal pct, Integer ac, String inspLevel, String severity,
            BigDecimal aql, AqlTable table) {
        return switch (method) {
            case "FIXED" -> fixed(fixedN, ac);
            case "ALL" -> all(ac);
            case "PERCENT" -> percent(pct, ac);
            case "AQL" -> aql(table, inspLevel, severity, aql);
            default -> throw new IllegalArgumentException("unknown sampling method " + method);
        };
    }

    public String method() {
        return method;
    }

    /** 로트 크기 → 시료 */
    public Sample sampleSize(long lotQty) {
        if (lotQty < 1 || lotQty > MAX_LOT) {
            throw new SamplingException("LOT_QTY");
        }
        return switch (method) {
            case "FIXED" -> capped(lotQty, fixedN, ac, ac + 1, null);
            case "ALL" -> new Sample(lotQty, (int) lotQty, ac, ac + 1, null, true);
            case "PERCENT" -> {
                long n = new BigDecimal(lotQty).multiply(pct).divide(BigDecimal.valueOf(100), 0, RoundingMode.CEILING).longValueExact();
                yield capped(lotQty, Math.max(1, n), ac, ac + 1, null);
            }
            default -> {
                String letter = table.letter(inspLevel, lotQty).orElseThrow(() -> new SamplingException("NO_AQL_LETTER"));
                AqlTable.PlanRow r = table.row(severity, letter, aql).orElseThrow(() -> new SamplingException("NO_AQL_PLAN"));
                yield capped(lotQty, r.sampleSize(), r.ac(), r.re(), letter);
            }
        };
    }

    /** 로트 크기와 불량 수 → 판정 */
    public Verdict judge(long lotQty, int defects) {
        return sampleSize(lotQty).judge(defects);
    }

    private static Sample capped(long lotQty, long n, int ac, int re, String letter) {
        return n >= lotQty ? new Sample(lotQty, (int) lotQty, ac, re, letter, true) : new Sample(lotQty, (int) n, ac, re, letter, false);
    }
}
