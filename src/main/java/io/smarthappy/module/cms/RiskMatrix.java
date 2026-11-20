package io.smarthappy.module.cms;

import java.time.LocalDate;
import java.util.List;

/**
 * 위험성평가 판정 (docs/33 4.7) — 순수 함수. 화면 미리보기·저장·확정 검사가 모두 이것만 부른다.
 * 빈도·강도법: 위험성 = 가능성 × 중대성, 수준은 행렬별 구간 [확인 필요: 사업장 기준표 — 회사가 바꾸려면 정책을 늘린다].
 *   3X3(1~9): 1~2 LOW · 3~4 MEDIUM · 6~9 HIGH / 5X5(1~25): 1~4 LOW · 5~12 MEDIUM · 15~25 HIGH
 * 3단계 판단법: 가능성·중대성 없이 수준(상·중·하)을 사람이 고른다.
 */
public final class RiskMatrix {

    public enum Level { LOW, MEDIUM, HIGH }

    private RiskMatrix() {
    }

    /** 행렬 크기 3 또는 5 */
    public static int size(String matrixCd) {
        return switch (matrixCd) {
            case "3X3" -> 3;
            case "5X5" -> 5;
            default -> throw new IllegalArgumentException("matrix " + matrixCd);
        };
    }

    /** 가능성·중대성(1~행렬 크기) → 수준. 범위 밖이면 IllegalArgumentException — 호출자가 칸 오류로 먼저 막는다 */
    public static Level level(int likelihood, int severity, String matrixCd) {
        int n = size(matrixCd);
        if (likelihood < 1 || likelihood > n || severity < 1 || severity > n) {
            throw new IllegalArgumentException("out of matrix " + matrixCd);
        }
        int score = likelihood * severity;
        if (n == 3) {
            return score >= 6 ? Level.HIGH : score >= 3 ? Level.MEDIUM : Level.LOW;
        }
        return score >= 15 ? Level.HIGH : score >= 5 ? Level.MEDIUM : Level.LOW;
    }

    /** 개선 대책이 꼭 있어야 하는 수준(허용할 수 없는 위험) — MEDIUM 이상 */
    public static boolean needsPlan(String level) {
        return "MEDIUM".equals(level) || "HIGH".equals(level);
    }

    /** 개선 대책 기한 지연: 기한이 있고, 아직 완료 안 됐고, 오늘 > 기한 */
    public static boolean overdue(LocalDate due, LocalDate done, LocalDate today) {
        return due != null && done == null && today.isAfter(due);
    }

    /** 확정 검사 입력 — 항목 하나 */
    public record ItemCheck(int seq, String level, boolean hasPlan, boolean hasOwner, boolean hasDue) {
    }

    /** 확정 검사 입력 — 참여자 하나 */
    public record MemberCheck(String roleCd, boolean participated) {
    }

    /** 확정 못 하는 이유 하나 — field 는 칸 오류 위치(items·members 또는 항목 순번) */
    public record Problem(String field, Integer seq, String code) {
    }

    /**
     * 확정할 수 있는가: 항목 하나 이상, MEDIUM 이상 항목은 개선 대책·담당·기한, 참여 기록 하나 이상 + 근로자(대표) 참여
     * (근로자 참여는 산안법 위험성평가의 요건 — 미참여는 과태료 사유 [확인 필요: 최신 고시])
     */
    public static List<Problem> releaseProblems(List<ItemCheck> items, List<MemberCheck> members) {
        java.util.ArrayList<Problem> out = new java.util.ArrayList<>();
        if (items.isEmpty()) {
            out.add(new Problem("items", null, "RA_NO_ITEMS"));
        }
        for (ItemCheck i : items) {
            if (needsPlan(i.level()) && (!i.hasPlan() || !i.hasOwner() || !i.hasDue())) {
                out.add(new Problem("planMeasure", i.seq(), "RA_PLAN_REQUIRED"));
            }
        }
        boolean worker = members.stream().anyMatch(m -> m.participated() && ("WORKER".equals(m.roleCd()) || "WORKER_REP".equals(m.roleCd())));
        if (!worker) {
            out.add(new Problem("members", null, "RA_NO_WORKER"));
        }
        return out;
    }
}
