package io.smarthappy.platform.common.query;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;

import org.springframework.stereotype.Component;

import io.smarthappy.platform.common.web.BusinessException;
import io.smarthappy.platform.common.web.FieldErrors;
import io.smarthappy.platform.common.web.HbErrorCode;
import io.smarthappy.platform.policy.Policies;
import io.smarthappy.platform.policy.PolicyService;
import lombok.RequiredArgsConstructor;

/**
 * 조회 기간 공통 검사 (docs/16 I3·I4) — 화면(hb.dateRange)이 먼저 막지만 서버가 최종 판단한다.
 * - 시작·종료 둘 다 필수, 시작 ≤ 종료 → 아니면 400 INVALID_RANGE (필드 오류 from/to)
 * - 일수(종료일 포함) ≤ min(화면 상한, 회사 정책 SEARCH_MAX_RANGE_DAYS) → 아니면 400 RANGE_TOO_LONG
 * 조용히 바꾸거나 잘라 내지 않는다(오타를 숨기지 않게).
 */
@Component
@RequiredArgsConstructor
public class DateRanges {

    private final PolicyService policies;

    /** 종료일을 포함한 일수 (같은 날 = 1) */
    public static long days(LocalDate from, LocalDate to) {
        return ChronoUnit.DAYS.between(from, to) + 1;
    }

    /** 실제 상한 = min(화면 상한, 회사 정책) */
    public int maxDays(int screenMaxDays) {
        return Math.min(screenMaxDays, policies.intValue(Policies.SEARCH_MAX_RANGE_DAYS));
    }

    public void check(LocalDate from, LocalDate to, int screenMaxDays) {
        if (from == null || to == null) {
            throw new BusinessException(HbErrorCode.INVALID_RANGE).withFieldErrors(List.of(
                    new FieldErrors.FieldError(null, from == null ? "from" : "to", "REQUIRED")));
        }
        if (from.isAfter(to)) {
            throw new BusinessException(HbErrorCode.INVALID_RANGE).withFieldErrors(List.of(
                    new FieldErrors.FieldError(null, "to", "INVALID_RANGE")));
        }
        long days = days(from, to);
        int max = maxDays(screenMaxDays);
        if (days > max) {
            throw new BusinessException(HbErrorCode.RANGE_TOO_LONG, days, max).withFieldErrors(List.of(
                    new FieldErrors.FieldError(null, "to", "RANGE_TOO_LONG", days, max)));
        }
    }
}
