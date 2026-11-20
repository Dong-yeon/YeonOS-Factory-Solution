package io.smarthappy.platform.excel;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeParseException;

import io.smarthappy.platform.grid.ColumnSpec.ColumnType;

/**
 * 행 값 → 엑셀 셀에 넣을 값 (docs/73 3장). 목록 API 의 행은 화면(JSON)용이라 수량이 글자("12.5")이거나 일시가 ISO 글자일 수 있다 —
 * 열 유형에 맞게 바꿔 타입 셀(숫자·날짜)로 넣고, 못 바꾸는 값은 버리지 않고 글자 그대로 돌려준다(화면에 보이는 것과 같게, 조용히 비우지 않는다).
 *
 * 돌려주는 형: INT·DEC = Number, DATE = LocalDate, DATETIME = LocalDateTime(회사 시간대) — 그 밖 형이면 호출 쪽이 글자 셀로 쓴다.
 */
final class CellValues {

    private CellValues() {
    }

    static Object coerce(ColumnType type, Object v, ZoneId zone) {
        if (v == null) {
            return null;
        }
        if (v instanceof CharSequence cs && cs.toString().isBlank()) {
            return type == ColumnType.TEXT ? v : null;   // 빈 글자는 빈 칸(숫자·날짜 칸에 '' 글자 셀을 만들지 않는다)
        }
        return switch (type) {
            case INT, DEC -> number(v);
            case DATE -> date(v, zone);
            case DATETIME -> dateTime(v, zone);
            default -> v;
        };
    }

    private static Object number(Object v) {
        if (v instanceof Number) {
            return v;
        }
        if (v instanceof CharSequence cs) {
            try {
                return new BigDecimal(cs.toString().strip());
            } catch (NumberFormatException e) {
                return v;
            }
        }
        return v;
    }

    private static Object date(Object v, ZoneId zone) {
        if (v instanceof LocalDate) {
            return v;
        }
        if (v instanceof java.sql.Date d) {
            return d.toLocalDate();
        }
        if (v instanceof CharSequence cs) {
            try {
                return LocalDate.parse(cs.toString().strip());
            } catch (DateTimeParseException e) {
                return v;
            }
        }
        Object dt = dateTime(v, zone);
        return dt instanceof LocalDateTime l ? l.toLocalDate() : v;
    }

    private static Object dateTime(Object v, ZoneId zone) {
        if (v instanceof LocalDateTime) {
            return v;
        }
        if (v instanceof OffsetDateTime o) {
            return o.atZoneSameInstant(zone).toLocalDateTime();
        }
        if (v instanceof ZonedDateTime z) {
            return z.withZoneSameInstant(zone).toLocalDateTime();
        }
        if (v instanceof Timestamp ts) {
            return ts.toInstant().atZone(zone).toLocalDateTime();
        }
        if (v instanceof Instant in) {
            return in.atZone(zone).toLocalDateTime();
        }
        if (v instanceof java.util.Date d) {
            return d.toInstant().atZone(zone).toLocalDateTime();
        }
        if (v instanceof CharSequence cs) {
            String s = cs.toString().strip();
            try {
                return OffsetDateTime.parse(s).atZoneSameInstant(zone).toLocalDateTime();
            } catch (DateTimeParseException e) {
                // 시간대 없는 글자는 이미 현지 시각으로 만든 값
            }
            try {
                return LocalDateTime.parse(s.replace(' ', 'T'));
            } catch (DateTimeParseException e) {
                return v;
            }
        }
        return v;
    }
}
