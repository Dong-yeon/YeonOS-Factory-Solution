package io.smarthappy.module.qms;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 기준일에 유효한 검사계획 고르기 — 순수 함수 (docs/29 3장 "판정 함수", D2). 2부 검사 실행이 부른다.
 *
 * 후보 = 확정본 · 같은 검사 유형 · 기간 [시작, 종료) 안 · 대상이 맞음(품목 계획 = 같은 품목, 클래스 계획 = 품목의 클래스)
 *        · 공정이 맞음(계획 공정 = 요청 공정, 또는 계획에 공정 없음 = 공정 무관).
 * 순서 = 품목 계획 > 클래스 계획 (D2 "품목이 우선") → 같은 공정 > 공정 무관 → 대체 번호 작은 것.
 * 같은 키의 확정본 기간 겹침은 DB 배제 제약이 막으므로, 키마다 후보는 하나다.
 */
public final class InspPlanResolver {

    /** 판정에 필요한 머리 값만 */
    public record Candidate(long inspPlanId, String inspType, Long itemId, Long itemClassId, Long procId, int altNo, String rev,
            String status, LocalDate validFrom, LocalDate validTo) {
    }

    /** 검사 대상 품목 — 클래스가 없으면 classId null */
    public record ItemRef(long itemId, Long itemClassId) {
    }

    private final List<Candidate> plans;

    public InspPlanResolver(List<Candidate> plans) {
        this.plans = List.copyOf(plans);
    }

    public Optional<Candidate> effective(ItemRef item, String inspType, Long procId, LocalDate date) {
        Objects.requireNonNull(item);
        Objects.requireNonNull(inspType);
        Objects.requireNonNull(date);
        return plans.stream()
                .filter(p -> "RELEASED".equals(p.status()) && inspType.equals(p.inspType()))
                .filter(p -> !p.validFrom().isAfter(date) && (p.validTo() == null || date.isBefore(p.validTo())))
                .filter(p -> p.itemId() != null ? p.itemId() == item.itemId()
                        : item.itemClassId() != null && item.itemClassId().equals(p.itemClassId()))
                .filter(p -> p.procId() == null || p.procId().equals(procId))
                .min(Comparator.<Candidate>comparingInt(p -> p.itemId() != null ? 0 : 1)
                        .thenComparingInt(p -> p.procId() != null ? 0 : 1)
                        .thenComparingInt(Candidate::altNo));
    }
}
