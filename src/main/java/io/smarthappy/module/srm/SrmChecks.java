package io.smarthappy.module.srm;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.regex.Pattern;

import io.smarthappy.module.master.bom.BomService;
import io.smarthappy.platform.common.web.BusinessException;
import io.smarthappy.platform.common.web.FieldErrors;
import io.smarthappy.platform.common.web.HbErrorCode;
import io.smarthappy.platform.security.CurrentUser;
import io.smarthappy.platform.security.LoginUser;

/** SRM 화면 공통 입력 검사 — P1·P2 서비스와 같은 규칙(칸 오류 코드 `valid.*`)을 SRM 패키지 안에서 쓴다 (docs/31 3장) */
public final class SrmChecks {

    private static final Pattern CONTROL = Pattern.compile("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F\\x7F]");

    private SrmChecks() {
    }

    public static LoginUser me() {
        return CurrentUser.get().orElseThrow(() -> new BusinessException(HbErrorCode.UNAUTHENTICATED));
    }

    public static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    public static BusinessException fieldError(String field, String code, Object... args) {
        return new BusinessException(HbErrorCode.INVALID_VALUE).withFieldErrors(FieldErrors.create().add(field, code, args).list());
    }

    /** 코드 칸: 앞뒤 공백 제거 + 대문자, 비면 null */
    public static String code(String v) {
        return blank(v) ? null : v.strip().toUpperCase(Locale.ROOT);
    }

    public static String text(String v, String field, int max, boolean required, FieldErrors e) {
        if (blank(v)) {
            if (required) {
                e.add(field, "REQUIRED");
            }
            return null;
        }
        String s = v.strip();
        if (CONTROL.matcher(s).find()) {
            e.add(field, "FORMAT");
        } else if (s.length() > max) {
            e.add(field, "TOO_LONG", max);
        }
        return s;
    }

    public static LocalDate date(String v, String field, boolean required, FieldErrors e) {
        if (blank(v)) {
            if (required) {
                e.add(field, "REQUIRED");
            }
            return null;
        }
        try {
            LocalDate d = LocalDate.parse(v.strip());
            if (d.getYear() < 1950 || d.getYear() > 2999) {
                e.add(field, "FORMAT");
                return null;
            }
            return d;
        } catch (DateTimeParseException ex) {
            e.add(field, "FORMAT");
            return null;
        }
    }

    /** 10진 문자열 → BigDecimal (반올림하지 않는다, Double 금지 — SKILL 2장) */
    public static BigDecimal decimal(String raw, String field, int scale, int intDigits, boolean required, FieldErrors e) {
        if (blank(raw)) {
            if (required) {
                e.add(field, "REQUIRED");
            }
            return null;
        }
        return BomService.decimal(raw, field, scale, intDigits, true, e);
    }

    /** 0 이상 10진 문자열(점수·등급 최소 점수 — 0 허용) */
    public static BigDecimal nonNegative(String raw, String field, int scale, int intDigits, boolean required, FieldErrors e) {
        if (blank(raw)) {
            if (required) {
                e.add(field, "REQUIRED");
            }
            return null;
        }
        BigDecimal v = BomService.decimal(raw, field, scale, intDigits, false, e);
        if (v != null && v.signum() < 0) {
            e.add(field, "NOT_NEGATIVE");
            return null;
        }
        return v;
    }

    public static String useYn(String v, FieldErrors e) {
        String use = blank(v) ? "Y" : v;
        if (!"Y".equals(use) && !"N".equals(use)) {
            e.add("useYn", "CODE");
        }
        return use;
    }

    public static String like(String s) {
        return s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
