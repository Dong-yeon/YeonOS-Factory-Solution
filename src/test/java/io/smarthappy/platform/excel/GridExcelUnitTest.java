package io.smarthappy.platform.excel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.smarthappy.platform.common.web.BusinessException;
import io.smarthappy.platform.grid.ColumnSpec.ColumnType;

/** grid 엑셀 공통 도우미의 순수 부분(docs/73 3장) — 요청 본문 읽기, 행 값 읽기, 셀 값 바꾸기. DB 없음 */
class GridExcelUnitTest {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    // ---------- GridExportQuery ----------

    @Test
    void 조건은_목록_파라미터처럼_글자로_꺼내고_숫자_참거짓도_글자가_된다() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("keyword", " abc ");
        body.put("plantId", 12);
        body.put("late", true);
        body.put("blank", "");
        body.put("none", null);
        GridExportQuery q = GridExportQuery.of(body, 500);
        assertThat(q.str("keyword")).isEqualTo(" abc ");          // 다듬기는 목록 서비스 몫(같은 검사)
        assertThat(q.str("plantId")).isEqualTo("12");
        assertThat(q.longValue("plantId")).isEqualTo(12L);
        assertThat(q.intValue("plantId")).isEqualTo(12);
        assertThat(q.bool("late")).isTrue();
        assertThat(q.str("blank")).isNull();                      // 빈 글자 = 없는 조건(hb.grid 가 목록 쿼리에서 빼는 것과 같다)
        assertThat(q.intValue("blank")).isNull();
        assertThat(q.bool("blank")).isNull();
        assertThat(q.date("blank")).isNull();
        assertThat(q.str("none")).isNull();
        assertThat(q.str("missing")).isNull();
        assertThat(q.conditionKeys()).containsExactly("keyword", "late", "plantId");   // 값 있는 조건 이름만, 이름순
    }

    @Test
    void 쪽_정보는_본문에서_받지_않는다() {
        Map<String, Object> body = Map.of("page", 7, "size", 99999, "total", false, "sort", List.of("a,desc"), "columns", List.of("x", "y"));
        GridExportQuery q = GridExportQuery.of(body, 500);
        assertThat(q.page()).isEqualTo(1);
        assertThat(q.size()).isEqualTo(500);
        assertThat(q.total()).isTrue();
        assertThat(q.atPage(3).page()).isEqualTo(3);
        assertThat(q.atPage(3).total()).isFalse();            // 건수는 첫 쪽에서만
        assertThat(q.sort()).containsExactly("a,desc");
        assertThat(q.columns()).containsExactly("x", "y");
        assertThat(q.conditionKeys()).isEmpty();              // 예약 이름은 조건이 아니다
        assertThat(GridExportQuery.of(null, 500).sort()).isNull();
        assertThat(GridExportQuery.of(Map.of("sort", List.of()), 500).sort()).isNull();
    }

    @Test
    void 형이_안_맞는_값은_400() {
        Map<String, Object> body = new HashMap<>();
        body.put("obj", Map.of("a", 1));
        body.put("arr", List.of("a", "b"));
        body.put("nested", List.of(List.of("a")));
        body.put("num", "12x");
        body.put("flag", "yes");
        body.put("day", "2026-13-01");
        body.put("big", 99_999_999_999L);
        body.put("long", "x".repeat(2001));
        GridExportQuery q = GridExportQuery.of(body, 500);
        assertThatThrownBy(() -> q.str("obj")).isInstanceOf(BusinessException.class);
        assertThat(q.str("arr")).isEqualTo("a,b");                // 배열 = 쉼표로 이은 글자(목록 쿼리의 String(값))
        assertThatThrownBy(() -> q.str("nested")).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> q.strs("nested")).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> q.longValue("num")).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> q.bool("flag")).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> q.date("day")).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> q.intValue("big")).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> q.str("long")).isInstanceOf(BusinessException.class);
        assertThat(q.strs("arr")).containsExactly("a", "b");
        assertThat(GridExportQuery.of(Map.of("day", "2026-11-12"), 1).date("day")).isEqualTo(LocalDate.of(2026, 11, 12));
    }

    // ---------- RowReader ----------

    public record Rec(String code, BigDecimal qty) {
    }

    public static class Bean {
        public String getCode() {
            return "B1";
        }
    }

    @Test
    void 행은_레코드_getter객체_Map_모두_읽고_없는_필드는_빈칸() {
        assertThat(RowReader.of(new Rec("R1", BigDecimal.ONE)).get("code")).isEqualTo("R1");
        assertThat(RowReader.of(new Rec("R1", BigDecimal.ONE)).get("qty")).isEqualTo(BigDecimal.ONE);
        assertThat(RowReader.of(new Rec("R1", BigDecimal.ONE)).get("nope")).isNull();
        assertThat(RowReader.of(new Bean()).get("code")).isEqualTo("B1");
        assertThat(RowReader.of(new Bean()).get("nope")).isNull();
        assertThat(RowReader.of(Map.of("code", "M1")).get("code")).isEqualTo("M1");
        assertThat(RowReader.of(Map.of("code", "M1")).get("nope")).isNull();
    }

    // ---------- CellValues ----------

    @Test
    void 글자로_온_수량과_날짜는_타입_값으로_바꾼다() {
        assertThat(CellValues.coerce(ColumnType.DEC, "12.500", SEOUL)).isEqualTo(new BigDecimal("12.500"));
        assertThat(CellValues.coerce(ColumnType.INT, " 7 ", SEOUL)).isEqualTo(new BigDecimal("7"));
        assertThat(CellValues.coerce(ColumnType.INT, 7L, SEOUL)).isEqualTo(7L);
        assertThat(CellValues.coerce(ColumnType.DATE, "2026-11-12", SEOUL)).isEqualTo(LocalDate.of(2026, 11, 12));
        assertThat(CellValues.coerce(ColumnType.DATE, java.sql.Date.valueOf("2026-11-12"), SEOUL)).isEqualTo(LocalDate.of(2026, 11, 12));
        // 일시는 회사 시간대로 — UTC 23:30 은 서울 다음 날 08:30
        LocalDateTime seoul = LocalDateTime.of(2026, 11, 13, 8, 30);
        assertThat(CellValues.coerce(ColumnType.DATETIME, "2026-11-12T23:30:00Z", SEOUL)).isEqualTo(seoul);
        assertThat(CellValues.coerce(ColumnType.DATETIME, OffsetDateTime.parse("2026-11-12T23:30:00Z"), SEOUL)).isEqualTo(seoul);
        assertThat(CellValues.coerce(ColumnType.DATETIME, Instant.parse("2026-11-12T23:30:00Z"), SEOUL)).isEqualTo(seoul);
        assertThat(CellValues.coerce(ColumnType.DATETIME, "2026-11-13 08:30", SEOUL)).isEqualTo(seoul);   // 이미 현지 시각으로 만든 글자
        assertThat(CellValues.coerce(ColumnType.DATE, OffsetDateTime.parse("2026-11-12T23:30:00Z"), SEOUL)).isEqualTo(LocalDate.of(2026, 11, 13));
    }

    @Test
    void 못_바꾸는_값은_버리지_않고_그대로_빈_글자는_빈칸() {
        assertThat(CellValues.coerce(ColumnType.DEC, "12 EA", SEOUL)).isEqualTo("12 EA");
        assertThat(CellValues.coerce(ColumnType.DATE, "11월 12일", SEOUL)).isEqualTo("11월 12일");
        assertThat(CellValues.coerce(ColumnType.DATETIME, "어제", SEOUL)).isEqualTo("어제");
        assertThat(CellValues.coerce(ColumnType.DEC, "", SEOUL)).isNull();
        assertThat(CellValues.coerce(ColumnType.DATE, "  ", SEOUL)).isNull();
        assertThat(CellValues.coerce(ColumnType.TEXT, "", SEOUL)).isEqualTo("");
        assertThat(CellValues.coerce(ColumnType.TEXT, null, SEOUL)).isNull();
        assertThat(CellValues.coerce(ColumnType.CODE, "RUN", SEOUL)).isEqualTo("RUN");
    }
}
