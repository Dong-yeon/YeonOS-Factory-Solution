package io.smarthappy.module.srm.eval;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * 협력사 평가 점수 계산 (docs/31 S6, D10) — 순수 함수. 화면 미리보기·확정·(2부) 자동 항목이 같은 함수를 쓰고,
 * DB 트리거(`ck_srm_eval_total`)가 같은 식으로 다시 계산해 확정 값을 확인한다.
 * <ul>
 * <li>총점 = Σ(점수 × 가중치 / 배점) — 항목마다 소수 20자리, 합을 소수 2자리 HALF_UP(가중치 합 100 이면 0~100)</li>
 * <li>과락 = 과락 점수가 있는 항목 중 점수 &lt; 과락 점수 하나라도 → 등급과 관계없이 과락</li>
 * <li>등급 = 최소 점수 ≤ 총점인 구간 중 가장 높은 것(경계 포함). 과락이거나 그 등급이 HOLD 제안이면 거래 보류 제안</li>
 * </ul>
 * 점수가 빠진 항목이 있으면 총점·등급은 null(과락은 넣은 항목까지로 본다).
 */
public final class EvalScorer {

    private static final int TERM_SCALE = 20;

    private EvalScorer() {
    }

    public record Item(long itemId, BigDecimal maxScore, BigDecimal weight, BigDecimal failBelow) {
    }

    public record Grade(String gradeCd, BigDecimal minScore, boolean suggestHold) {
    }

    /** complete = 모든 항목에 점수, failedItems = 과락 항목 id */
    public record Result(BigDecimal total, String gradeCd, boolean fail, boolean suggestHold, boolean complete,
            List<Long> failedItems) {
    }

    /** 점수 구간 한 줄(입고 PPM, docs/31 4.12.3) — upperPpm null = 그 위 전부, scorePct 0~100 */
    public record Band(BigDecimal upperPpm, BigDecimal scorePct) {
    }

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    /**
     * 입고 PPM → 점수: PPM ≤ 상한인 구간 중 상한이 가장 낮은 것(경계 포함, 끝 없는 구간은 무한대)의 비율 × 배점,
     * 소수 2자리 HALF_UP. 맞는 구간이 없으면(끝 없는 구간이 빠진 작성 중 템플릿) null.
     */
    public static BigDecimal ppmScore(List<Band> bands, BigDecimal maxScore, BigDecimal ppm) {
        return bands.stream().filter(b -> b.upperPpm() == null || ppm.compareTo(b.upperPpm()) <= 0)
                .min(Comparator.comparing(Band::upperPpm, Comparator.nullsLast(Comparator.naturalOrder())))
                .map(b -> maxScore.multiply(b.scorePct()).divide(HUNDRED, 2, RoundingMode.HALF_UP))
                .orElse(null);
    }

    public static BigDecimal weightSum(List<Item> items) {
        return items.stream().map(Item::weight).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    public static Result score(List<Item> items, Map<Long, BigDecimal> scores, List<Grade> grades) {
        BigDecimal sum = BigDecimal.ZERO;
        boolean complete = true;
        List<Long> failed = new ArrayList<>();
        for (Item i : items) {
            BigDecimal s = scores.get(i.itemId());
            if (s == null) {
                complete = false;
                continue;
            }
            sum = sum.add(s.multiply(i.weight()).divide(i.maxScore(), TERM_SCALE, RoundingMode.HALF_UP));
            if (i.failBelow() != null && s.compareTo(i.failBelow()) < 0) {
                failed.add(i.itemId());
            }
        }
        boolean fail = !failed.isEmpty();
        if (!complete) {
            return new Result(null, null, fail, fail, false, failed);
        }
        BigDecimal total = sum.setScale(2, RoundingMode.HALF_UP);
        Grade g = grades.stream().filter(x -> x.minScore().compareTo(total) <= 0)
                .max(Comparator.comparing(Grade::minScore)).orElse(null);
        return new Result(total, g == null ? null : g.gradeCd(), fail, fail || g != null && g.suggestHold(), true, failed);
    }
}
