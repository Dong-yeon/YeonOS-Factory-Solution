package io.smarthappy.module.cms;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;

import org.junit.jupiter.api.Test;

import io.smarthappy.module.cms.SecWarn.Input;
import io.smarthappy.module.cms.SecWarn.Warn;

/** 설비 보안 경고 표 기반 검산 (docs/33 4.6) — 날짜 경계는 "오늘 = 기한이면 아직 아님". DB 없음 */
class SecWarnTest {

    private static final LocalDate TODAY = LocalDate.parse("2026-10-21");

    private static Input row(String net, String eos, String scan, String target, String current, String review) {
        return new Input(true, net, eos == null ? null : LocalDate.parse(eos), scan == null ? null : LocalDate.parse(scan), target, current,
                review == null ? null : LocalDate.parse(review));
    }

    @Test
    void 미등록은_그것_하나() {
        assertThat(SecWarn.of(new Input(false, null, null, null, null, null, null), TODAY, 90, 365)).containsExactly(Warn.UNREGISTERED);
    }

    @Test
    void 지원종료일은_지원되는_마지막날_다음날부터_경고() {
        assertThat(SecWarn.of(row("N", "2026-10-21", null, null, null, "2026-10-21"), TODAY, 90, 365)).isEmpty();
        assertThat(SecWarn.of(row("N", "2026-10-20", null, null, null, "2026-10-21"), TODAY, 90, 365)).containsExactly(Warn.EOS);
    }

    @Test
    void 검사기한은_네트워크_연결_설비만_검사일_더하기_주기까지() {
        assertThat(SecWarn.of(row("Y", null, "2026-07-23", null, null, "2026-10-21"), TODAY, 90, 365)).isEmpty();          // +90 = 오늘
        assertThat(SecWarn.of(row("Y", null, "2026-07-22", null, null, "2026-10-21"), TODAY, 90, 365)).containsExactly(Warn.SCAN_OVERDUE);
        assertThat(SecWarn.of(row("Y", null, null, null, null, "2026-10-21"), TODAY, 90, 365)).containsExactly(Warn.SCAN_OVERDUE);
        assertThat(SecWarn.of(row("N", null, null, null, null, "2026-10-21"), TODAY, 90, 365)).isEmpty();                  // 오프라인 = 면제
        assertThat(SecWarn.of(row("Y", null, "2026-09-21", null, null, "2026-10-21"), TODAY, 29, 365)).containsExactly(Warn.SCAN_OVERDUE);
    }

    @Test
    void 등급미달과_미검토_여러경고() {
        assertThat(SecWarn.of(row("N", null, null, "SL2", "SL1", "2026-10-21"), TODAY, 90, 365)).containsExactly(Warn.SL_GAP);
        assertThat(SecWarn.of(row("N", null, null, "SL2", "SL3", "2026-10-21"), TODAY, 90, 365)).isEmpty();
        assertThat(SecWarn.of(row("N", null, null, "SL2", null, "2026-10-21"), TODAY, 90, 365)).isEmpty();                // 현재 모름 = 판정 안 함
        assertThat(SecWarn.of(row("N", null, null, null, null, "2025-10-21"), TODAY, 90, 365)).isEmpty();                 // +365 = 오늘
        assertThat(SecWarn.of(row("N", null, null, null, null, "2025-10-20"), TODAY, 90, 365)).containsExactly(Warn.UNREVIEWED);
        assertThat(SecWarn.of(row("Y", "2020-01-14", null, "SL2", "SL0", null), TODAY, 90, 365))
                .containsExactly(Warn.EOS, Warn.SCAN_OVERDUE, Warn.SL_GAP, Warn.UNREVIEWED);
    }
}
