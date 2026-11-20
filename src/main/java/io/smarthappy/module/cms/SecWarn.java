package io.smarthappy.module.cms;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * 설비 보안 대장 경고 (docs/33 4.6) — 순수 함수. 저장하지 않고 조회·엑셀 때마다 계산해 날짜가 지나면 저절로 바뀐다.
 * 날짜 경계는 모두 "오늘 = 기한이면 아직 아님": 지원 종료일은 지원되는 마지막 날, 검사·검토는 마지막 날짜 + 주기일까지가 기한.
 */
public final class SecWarn {

    public enum Warn { UNREGISTERED, EOS, SCAN_OVERDUE, SL_GAP, UNREVIEWED }

    /** 대장 한 행의 판정 입력 (registered = 보안 대장 행이 있음) */
    public record Input(boolean registered, String networkYn, LocalDate osEosDate, LocalDate lastScanDate, String targetSl,
            String currentSl, LocalDate reviewDate) {
    }

    private SecWarn() {
    }

    /**
     * 미등록이면 그것 하나. 그 밖: 지원 종료(오늘 > 지원 종료일), 검사 기한 경과(네트워크 연결 설비만 — 검사 기록 없음 또는 오늘 > 검사일 + 주기),
     * 등급 미달(현재 < 목표, 둘 다 있을 때), 미검토(검토일 없음 또는 오늘 > 검토일 + 주기)
     */
    public static List<Warn> of(Input in, LocalDate today, int scanMaxDays, int reviewDays) {
        List<Warn> out = new ArrayList<>();
        if (!in.registered()) {
            out.add(Warn.UNREGISTERED);
            return out;
        }
        if (in.osEosDate() != null && today.isAfter(in.osEosDate())) {
            out.add(Warn.EOS);
        }
        if ("Y".equals(in.networkYn()) && (in.lastScanDate() == null || today.isAfter(in.lastScanDate().plusDays(scanMaxDays)))) {
            out.add(Warn.SCAN_OVERDUE);
        }
        if (in.targetSl() != null && in.currentSl() != null && in.currentSl().compareTo(in.targetSl()) < 0) {
            out.add(Warn.SL_GAP);   // SL0 < SL1 < SL2 < SL3 — 글자 순서가 등급 순서
        }
        if (in.reviewDate() == null || today.isAfter(in.reviewDate().plusDays(reviewDays))) {
            out.add(Warn.UNREVIEWED);
        }
        return out;
    }
}
