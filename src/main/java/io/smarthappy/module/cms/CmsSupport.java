package io.smarthappy.module.cms;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import io.smarthappy.platform.common.web.BusinessException;
import io.smarthappy.platform.common.web.FieldErrors;
import io.smarthappy.platform.common.web.HbErrorCode;
import io.smarthappy.platform.grid.ColumnSpec;
import io.smarthappy.platform.security.CurrentUser;
import io.smarthappy.platform.security.LoginUser;
import io.smarthappy.platform.time.BizClock;

/** CMS 서비스 공용 입력 검사 — P1·P2 서비스의 같은 이름 헬퍼와 같은 규칙(공통화는 병합 뒤, docs/33 6장) */
final class CmsSupport {

    /** 미래 시각 허용 오차(분) — 시계 차이. P1 설치 이력(AssetService)과 같은 값 */
    static final int FUTURE_TOLERANCE_MIN = 5;
    private static final Pattern CONTROL = Pattern.compile("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F\\x7F]");

    private CmsSupport() {
    }

    static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    static LoginUser me() {
        return CurrentUser.get().orElseThrow(() -> new BusinessException(HbErrorCode.UNAUTHENTICATED));
    }

    static BusinessException fieldError(String field, String code, Object... args) {
        return new BusinessException(HbErrorCode.INVALID_VALUE).withFieldErrors(FieldErrors.create().add(field, code, args).list());
    }

    static String like(String s) {
        return "%" + s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
    }

    /** 앞뒤 공백 제거, 제어문자 FORMAT, 길이 TOO_LONG. 줄바꿈(\n \r)은 허용 — 작업 내용은 여러 줄 */
    static String text(String v, String field, int max, boolean required, FieldErrors e) {
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

    static LocalDate date(String v, String field, FieldErrors e) {
        if (blank(v)) {
            return null;
        }
        try {
            LocalDate d = LocalDate.parse(v.strip());
            if (d.getYear() < 2000 || d.getYear() > 2999) {
                e.add(field, "FORMAT");
                return null;
            }
            return d;
        } catch (DateTimeParseException ex) {
            e.add(field, "FORMAT");
            return null;
        }
    }

    /**
     * 10진 글자 → BigDecimal (numeric(18,6)). 반올림하지 않는다: 소수가 scale 을 넘으면 SCALE, 정수부 12자리 넘으면 INT_DIGITS.
     * 지수 표기·천 단위 쉼표는 FORMAT
     */
    static java.math.BigDecimal decimal(String raw, String field, int scale, FieldErrors e) {
        if (blank(raw)) {
            return null;
        }
        String s = raw.strip();
        if (!s.matches("^-?\\d+(\\.\\d+)?$")) {
            e.add(field, "FORMAT");
            return null;
        }
        java.math.BigDecimal v = new java.math.BigDecimal(s);
        java.math.BigDecimal t = v.stripTrailingZeros();
        if (t.scale() > scale) {
            e.add(field, "SCALE", scale);
            return null;
        }
        if (t.precision() - t.scale() > 12) {
            e.add(field, "INT_DIGITS", 12);
            return null;
        }
        return v;
    }

    static String yn(String v, String dflt, String field, FieldErrors e) {
        String s = blank(v) ? dflt : v.strip();
        if (!"Y".equals(s) && !"N".equals(s)) {
            e.add(field, "CODE");
        }
        return s;
    }

    /**
     * "yyyy-MM-ddTHH:mm"(대상 공장 시간대) → 시각. 비면 null. DST 로 없는 시각 DST_GAP, 미래(허용 오차 넘음) FUTURE,
     * 허용 오차 안의 미래는 지금으로 당긴다. DB 와 같은 µs 로 자른다 — P1 AssetService.dateTime 과 같은 규칙
     */
    static OffsetDateTime dateTime(String raw, String field, FieldErrors e, ZoneId zone, BizClock clock) {
        if (blank(raw)) {
            return null;
        }
        try {
            Instant inst = BizClock.toInstant(LocalDateTime.parse(raw.strip()), zone);
            if (inst == null) {
                e.add(field, "DST_GAP");
                return null;
            }
            OffsetDateTime t = inst.atZone(zone).toOffsetDateTime().truncatedTo(ChronoUnit.MICROS);
            OffsetDateTime now = now(clock, zone);
            if (t.isAfter(now.plusMinutes(FUTURE_TOLERANCE_MIN))) {
                e.add(field, "FUTURE");
                return null;
            }
            if (t.isAfter(now)) {
                t = now;
            }
            if (t.getYear() < 2000) {
                e.add(field, "FORMAT");
                return null;
            }
            return t;
        } catch (DateTimeParseException ex) {
            e.add(field, "FORMAT");
            return null;
        }
    }

    static OffsetDateTime now(BizClock clock, ZoneId zone) {
        return clock.instant().atZone(zone).toOffsetDateTime().truncatedTo(ChronoUnit.MICROS);
    }

    /** 응답 시각을 대상 공장 시간대로 — 화면은 앞 16자(yyyy-MM-ddTHH:mm)를 그대로 보여 준다 */
    static OffsetDateTime at(OffsetDateTime t, ZoneId zone) {
        return t == null ? null : t.atZoneSameInstant(zone).toOffsetDateTime();
    }

    /**
     * 엑셀 열(C16) — grid 가 보낸 열 이름을 grid 열 정의로 검사(없거나 중복·내보내기 불가면 400)한 뒤,
     * 화면이 행 값으로 꾸미는 칸은 사람이 읽는 엑셀 열로 바꾼다(한 칸 → 여러 칸 가능). 요청 없으면 grid 전체 열 기준
     */
    static List<ColumnSpec> exportColumns(List<ColumnSpec> grid, List<String> requested, Map<String, List<ColumnSpec>> replace) {
        return ColumnSpec.selectExportable(grid, requested).stream().flatMap(c -> replace.getOrDefault(c.field(), List.of(c)).stream()).toList();
    }

    private static final java.time.format.DateTimeFormatter HM = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    /** 엑셀 시각 글자(C21) — 조회가 이미 공장 시간대로 바꿔 둔 값을 화면과 같은 "yyyy-MM-dd HH:mm" 로. null 이면 null */
    static String hm(OffsetDateTime t) {
        return t == null ? null : t.format(HM);
    }
}
