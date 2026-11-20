package io.smarthappy.module.cms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;

import io.smarthappy.module.cms.RiskMatrix.ItemCheck;
import io.smarthappy.module.cms.RiskMatrix.Level;
import io.smarthappy.module.cms.RiskMatrix.MemberCheck;

/** 위험성 행렬·확정 검사 손 검산 (docs/33 4.7) — DB 없음 */
class RiskMatrixTest {

    @Test
    void 행렬_3X3_모든칸() {
        // 가능성 \ 중대성:  1      2       3
        // 1               L(1)   L(2)    M(3)
        // 2               L(2)   M(4)    H(6)
        // 3               M(3)   H(6)    H(9)
        Level[][] want = {
                {Level.LOW, Level.LOW, Level.MEDIUM},
                {Level.LOW, Level.MEDIUM, Level.HIGH},
                {Level.MEDIUM, Level.HIGH, Level.HIGH}};
        for (int l = 1; l <= 3; l++) {
            for (int s = 1; s <= 3; s++) {
                assertThat(RiskMatrix.level(l, s, "3X3")).as(l + "x" + s).isEqualTo(want[l - 1][s - 1]);
            }
        }
    }

    @Test
    void 행렬_5X5_구간경계() {
        assertThat(RiskMatrix.level(2, 2, "5X5")).isEqualTo(Level.LOW);      // 4
        assertThat(RiskMatrix.level(1, 5, "5X5")).isEqualTo(Level.MEDIUM);   // 5
        assertThat(RiskMatrix.level(4, 3, "5X5")).isEqualTo(Level.MEDIUM);   // 12
        assertThat(RiskMatrix.level(3, 5, "5X5")).isEqualTo(Level.HIGH);     // 15
        assertThat(RiskMatrix.level(5, 5, "5X5")).isEqualTo(Level.HIGH);     // 25
        assertThatThrownBy(() -> RiskMatrix.level(4, 1, "3X3")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RiskMatrix.level(0, 1, "5X5")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 확정검사_항목없음_대책없는_중간이상_근로자참여() {
        assertThat(RiskMatrix.releaseProblems(List.of(), List.of())).extracting(RiskMatrix.Problem::code)
                .containsExactly("RA_NO_ITEMS", "RA_NO_WORKER");
        List<ItemCheck> items = List.of(new ItemCheck(1, "LOW", false, false, false), new ItemCheck(2, "MEDIUM", true, true, false),
                new ItemCheck(3, "HIGH", true, true, true));
        // 관리감독자만 참여, 근로자는 참여일 없음
        List<MemberCheck> members = List.of(new MemberCheck("SUPERVISOR", true), new MemberCheck("WORKER", false));
        assertThat(RiskMatrix.releaseProblems(items, members)).extracting(p -> p.code() + (p.seq() == null ? "" : "#" + p.seq()))
                .containsExactly("RA_PLAN_REQUIRED#2", "RA_NO_WORKER");
        assertThat(RiskMatrix.releaseProblems(items.subList(2, 3), List.of(new MemberCheck("WORKER_REP", true)))).isEmpty();
    }

    @Test
    void 개선기한_지연은_오늘이_기한을_넘고_미완료() {
        LocalDate today = LocalDate.parse("2026-10-21");
        assertThat(RiskMatrix.overdue(LocalDate.parse("2026-10-21"), null, today)).isFalse();
        assertThat(RiskMatrix.overdue(LocalDate.parse("2026-10-20"), null, today)).isTrue();
        assertThat(RiskMatrix.overdue(LocalDate.parse("2026-10-20"), LocalDate.parse("2026-10-21"), today)).isFalse();
        assertThat(RiskMatrix.overdue(null, null, today)).isFalse();
    }
}
