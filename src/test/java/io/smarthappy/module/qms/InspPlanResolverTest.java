package io.smarthappy.module.qms;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import io.smarthappy.module.qms.InspPlanResolver.Candidate;
import io.smarthappy.module.qms.InspPlanResolver.ItemRef;

/**
 * 유효 검사계획 고르기 순수 함수 (docs/29 D2 "품목이 우선") — 표 기반.
 * 품목 10(클래스 7), 품목 11(클래스 7), 품목 12(클래스 없음), 공정 5·9.
 */
class InspPlanResolverTest {

    static final LocalDate JAN = LocalDate.of(2026, 1, 1);
    static final LocalDate JUL = LocalDate.of(2026, 7, 1);

    static final List<Candidate> PLANS = List.of(
            new Candidate(1, "PROCESS", 10L, null, null, 1, "A0", "RELEASED", JAN, JUL),        // 품목 · 공정 무관 · 7월 1일 끝
            new Candidate(2, "PROCESS", 10L, null, 5L, 1, "A0", "RELEASED", JAN, null),         // 품목 · 공정 5
            new Candidate(3, "PROCESS", null, 7L, null, 1, "A0", "RELEASED", JAN, null),        // 클래스 · 공정 무관
            new Candidate(4, "PROCESS", null, 7L, 5L, 1, "A0", "RELEASED", JAN, null),          // 클래스 · 공정 5
            new Candidate(5, "PROCESS", 10L, null, null, 2, "A0", "RELEASED", JAN, null),       // 품목 · 공정 무관 · 대체 2
            new Candidate(6, "PROCESS", 11L, null, null, 1, "A1", "DRAFT", JAN, null),          // 작성 중 — 무시
            new Candidate(7, "FINAL", 10L, null, null, 1, "A0", "RELEASED", JAN, null),
            new Candidate(8, "FINAL", 11L, null, null, 1, "A0", "OBSOLETE", JAN, null),         // 폐기 — 무시
            new Candidate(9, "RECEIVE", null, 7L, null, 1, "A0", "RELEASED", LocalDate.of(2026, 3, 1), null));

    @ParameterizedTest(name = "품목 {0}(클래스 {1}) {2} 공정 {3} {4} → 계획 {5}")
    @CsvSource(nullValues = "-", value = {
            "10, 7, PROCESS, 5, 2026-03-01, 2",       // 품목+같은 공정 > 품목 공정 무관
            "10, 7, PROCESS, -, 2026-03-01, 1",       // 공정 없이 → 공정 무관 계획, 대체 1 > 대체 2
            "10, 7, PROCESS, 9, 2026-03-01, 1",       // 계획 없는 공정 → 공정 무관 계획
            "10, 7, PROCESS, -, 2026-07-01, 5",       // 1번은 [시작, 끝) 이라 7월 1일엔 끝 → 대체 2
            "10, 7, PROCESS, -, 2026-06-30, 1",       // 끝 전날
            "10, 7, PROCESS, 5, 2025-12-31, -",       // 시작 전 → 없음
            "10, -, PROCESS, 5, 2026-03-01, 2",       // 클래스 몰라도 품목 계획
            "11, 7, PROCESS, 5, 2026-03-01, 4",       // 품목 계획 없음(작성 중만) → 클래스 + 공정
            "11, 7, PROCESS, -, 2026-03-01, 3",
            "11, 7, PROCESS, 9, 2026-03-01, 3",       // 클래스 + 다른 공정 → 클래스 공정 무관
            "12, -, PROCESS, 5, 2026-03-01, -",       // 클래스 없는 품목·품목 계획 없음
            "10, 7, FINAL, -, 2026-03-01, 7",         // 유형이 다르면 그 유형만
            "11, 7, FINAL, -, 2026-03-01, -",         // 폐기본은 없음
            "11, 7, RECEIVE, -, 2026-03-01, 9",       // 시작 당일
            "11, 7, RECEIVE, -, 2026-02-28, -",
            "10, 7, PATROL, -, 2026-03-01, -"})
    void 품목_우선_같은_공정_우선_대체_작은것_기간은_시작포함_끝제외(long item, Long cls, String type, Long proc, LocalDate date,
            Long expect) {
        var got = new InspPlanResolver(PLANS).effective(new ItemRef(item, cls), type, proc, date);
        assertThat(got.map(Candidate::inspPlanId).orElse(null)).isEqualTo(expect);
    }
}
