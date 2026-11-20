package io.smarthappy.module.apqp;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

import io.smarthappy.platform.common.web.BusinessException;
import io.smarthappy.platform.common.web.FieldErrors;
import io.smarthappy.platform.common.web.HbErrorCode;
import io.smarthappy.platform.security.CurrentUser;
import io.smarthappy.platform.security.LoginUser;

/** APQP 입력 정리·검증 공통 (docs/41) — PMS 의 같은 이름 도우미와 같은 규칙(모듈 경계라 복사) */
final class ApqpInputs {

    static final Set<String> M4 = Set.of("MAN", "MACHINE", "MATERIAL", "METHOD", "MEASURE", "ENV");
    static final Set<String> AP = Set.of("H", "M", "L");
    static final LocalDate MIN_DATE = LocalDate.of(1950, 1, 1);
    static final LocalDate MAX_DATE = LocalDate.of(2999, 12, 31);
    private static final Pattern YMD = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}$");

    private ApqpInputs() {
    }

    static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    static String upper(String s) {
        return blank(s) ? null : s.strip().toUpperCase(Locale.ROOT);
    }

    /** 앞뒤 공백 제거, 제어문자는 그대로 두지 않는다(이름 칸에 줄바꿈·탭이 섞여 들어와 표가 깨지지 않게) */
    static String text(String v, String field, int max, boolean required, FieldErrors e) {
        if (blank(v)) {
            if (required) {
                e.add(field, "REQUIRED");
            }
            return null;
        }
        String s = v.strip().replaceAll("\\p{Cntrl}", " ");
        if (s.codePointCount(0, s.length()) > max || s.length() > max) {
            e.add(field, "TOO_LONG", max);
        }
        return s;
    }

    /** "yyyy-MM-dd" → 날짜(1950~2999). 비면 null(required 면 REQUIRED) */
    static LocalDate date(String v, String field, boolean required, FieldErrors e) {
        if (blank(v)) {
            if (required) {
                e.add(field, "REQUIRED");
            }
            return null;
        }
        String s = v.strip();
        if (!YMD.matcher(s).matches()) {
            e.add(field, "FORMAT");
            return null;
        }
        try {
            LocalDate d = LocalDate.parse(s);
            if (d.isBefore(MIN_DATE) || d.isAfter(MAX_DATE)) {
                e.add(field, "RANGE", MIN_DATE.toString(), MAX_DATE.toString());
                return null;
            }
            return d;
        } catch (DateTimeParseException ex) {
            e.add(field, "FORMAT");
            return null;
        }
    }

    /** 1~10 등급(S·O·D) — null 이면 null(required 면 REQUIRED) */
    static Integer rating(Integer v, String field, boolean required, FieldErrors e) {
        if (v == null) {
            if (required) {
                e.add(field, "REQUIRED");
            }
            return null;
        }
        if (v < 1 || v > 10) {
            e.add(field, "RANGE", 1, 10);
            return null;
        }
        return v;
    }

    static BusinessException fieldError(String field, String code, Object... args) {
        return new BusinessException(HbErrorCode.INVALID_VALUE).withFieldErrors(FieldErrors.create().add(field, code, args).list());
    }

    static LoginUser me() {
        return CurrentUser.get().orElseThrow(() -> new BusinessException(HbErrorCode.UNAUTHENTICATED));
    }

    /** LIKE 검색어 — 와일드카드 문자를 글자로 */
    static String like(String keyword) {
        if (blank(keyword)) {
            return null;
        }
        String k = keyword.strip();
        if (k.length() > 100) {
            k = k.substring(0, 100);
        }
        return "%" + k.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
    }
}
