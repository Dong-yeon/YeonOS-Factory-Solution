package io.smarthappy.module.master.workforce;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

import io.smarthappy.module.master.workforce.WorkCalendar.DayPlan;
import io.smarthappy.module.master.workforce.WorkCalendar.ShiftDay;
import io.smarthappy.platform.common.query.SortField;
import lombok.Getter;
import lombok.Setter;

/** 공장 휴일 달력 DTO (docs/25 4.15) */
public final class WorkCalDtos {

    private WorkCalDtos() {
    }

    /** 예외 한 행 — plantCd null = 회사 공통, shiftCd null = 하루 전체 */
    @Getter
    @Setter
    public static class CalRow {
        private long calId;
        private String plantCd;
        private LocalDate calDate;
        private String shiftCd;
        private String shiftNm;
        private String dayType;
        private String reason;
        private String useYn;
        private int rowVer;
        private OffsetDateTime updatedAt;
        private String updatedBy;
    }

    /** 저장 — plantCd 비우면 회사 공통. 날짜·공장·근무조는 만든 뒤 고정(바꾸려면 사용 중지하고 새로) */
    public record CalForm(String plantCd, String calDate, String shiftCd, String dayType, String reason, Integer rowVer) {
    }

    public record CalValue(String plantCd, LocalDate calDate, String shiftCd, String dayType, String reason) {
    }

    /** 사용 중지 */
    public record CalDisable(Integer rowVer) {
    }

    /** 한 달 화면 — 주간 쉬는 날(정책)·날마다 판정·그 달 예외(회사 + 그 공장)·그 공장 사용 중 근무조(폼 선택지) */
    public record MonthView(String plantCd, String month, String weeklyOff, List<DayPlan> days, List<CalRow> rows,
            List<ShiftDay> shifts) {
    }

    /** 예외 목록 그리드 정렬 허용 열(UI 단계 3 ①) — 기본은 기존 목록과 같은 날짜 → 범위(회사 공통 먼저) → 근무조(하루 전체 먼저) */
    public enum CalSort implements SortField {
        CAL_DATE("calDate"), PLANT_CD("plantCd"), SHIFT_CD("shiftCd"), DAY_TYPE("dayType");

        private final String field;

        CalSort(String field) {
            this.field = field;
        }

        @Override
        public String field() {
            return field;
        }
    }
}
