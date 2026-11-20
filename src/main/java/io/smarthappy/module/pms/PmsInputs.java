package io.smarthappy.module.pms;

import static io.smarthappy.platform.common.text.VisibleText.isBlank;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

import io.smarthappy.platform.common.text.VisibleText;
import io.smarthappy.platform.common.web.BusinessException;
import io.smarthappy.platform.common.web.FieldErrors;
import io.smarthappy.platform.common.web.HbErrorCode;
import io.smarthappy.platform.security.CurrentUser;
import io.smarthappy.platform.security.LoginUser;

/** PMS 입력 정리·검증 공통 (docs/32 3장) */
final class PmsInputs {

    /** 프로젝트 유형 = 공통코드 PMS_PROJ_TYPE(시스템 5종 + 회사 코드, docs/68) */
    static final String PROJ_TYPE_GRP = "PMS_PROJ_TYPE";
    static final Set<String> TASK_TYPES = Set.of("TASK", "MILESTONE", "GATE");
    static final LocalDate MIN_DATE = LocalDate.of(1950, 1, 1);
    static final LocalDate MAX_DATE = LocalDate.of(2999, 12, 31);
    private static final Pattern YMD = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}$");

    private PmsInputs() {
    }

    static String upper(String s) {
        return isBlank(s) ? null : s.strip().toUpperCase(Locale.ROOT);
    }

    /**
     * 사람이 적는 글 칸(이름·비고·사유) — 빈 값 판정과 앞뒤 정리는 플랫폼 {@link VisibleText}(QA R3-a·R4-a): 보이지 않는 글자
     * (U+200B·U+FEFF·NBSP·U+3000 등)뿐이면 빈 값 = 필수면 지금과 같은 {@code REQUIRED}. 가운데 제어문자는 공백으로 바꾼다(이름 칸에
     * 줄바꿈·탭이 섞여 들어와 표가 깨지지 않게 — 예전 그대로), 가운데 형식 문자(ZWJ 등)는 그대로 둔다.
     */
    static String visible(String v, String field, int max, boolean required, FieldErrors e) {
        String s = VisibleText.strip(v);
        if (s == null) {
            if (required) {
                e.add(field, "REQUIRED");
            }
            return null;
        }
        s = s.replaceAll("\\p{Cntrl}", " ");
        if (s.codePointCount(0, s.length()) > max || s.length() > max) {
            e.add(field, "TOO_LONG", max);
        }
        return s;
    }

    /** 선후행 종류(docs/32 4.27) — 비우면 FS, FS·SS·FF 밖이면 CODE */
    static String depType(String v, FieldErrors e) {
        String t = isBlank(v) ? "FS" : upper(v);
        if (!WbsSchedule.DEP_TYPES.contains(t)) {
            e.add("depType", "CODE");
            return "FS";
        }
        return t;
    }

    /** "yyyy-MM-dd" → 날짜(1950~2999). 비면 null(required 면 REQUIRED) */
    static LocalDate date(String v, String field, boolean required, FieldErrors e) {
        if (isBlank(v)) {
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

    /** 공수(시간, docs/48 R-2) — 비우면 null, 0~99999·소수 첫째 자리까지(반올림하지 않음) */
    static BigDecimal hours(BigDecimal v, String field, FieldErrors e) {
        if (v == null) {
            return null;
        }
        if (v.signum() < 0 || v.compareTo(MAX_HOURS) > 0) {
            e.add(field, "RANGE", 0, MAX_HOURS.intValue());
            return null;
        }
        if (v.stripTrailingZeros().scale() > 1) {
            e.add(field, "SCALE", 1);
            return null;
        }
        return v.setScale(1, java.math.RoundingMode.UNNECESSARY).stripTrailingZeros();
    }

    static final BigDecimal MAX_HOURS = BigDecimal.valueOf(99999);

    static BusinessException fieldError(String field, String code, Object... args) {
        return new BusinessException(HbErrorCode.INVALID_VALUE).withFieldErrors(FieldErrors.create().add(field, code, args).list());
    }

    static LoginUser me() {
        return CurrentUser.get().orElseThrow(() -> new BusinessException(HbErrorCode.UNAUTHENTICATED));
    }

    /** LIKE 검색어 — 와일드카드 문자를 글자로 */
    static String like(String keyword) {
        if (isBlank(keyword)) {
            return null;
        }
        String k = keyword.strip();
        if (k.length() > 100) {
            k = k.substring(0, 100);
        }
        return "%" + k.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
    }
}
