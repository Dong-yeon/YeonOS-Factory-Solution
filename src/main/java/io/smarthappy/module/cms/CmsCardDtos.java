package io.smarthappy.module.cms;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

import io.smarthappy.module.cms.CmsCounterDtos.CounterRow;
import io.smarthappy.module.cms.CmsDtos.InstalledAsset;
import io.smarthappy.module.cms.CmsDtos.ProfileRow;
import io.smarthappy.platform.common.query.SortField;
import lombok.Getter;
import lombok.Setter;

/** CMS C17 DTO — 설비 이력 카드 (docs/33 13.5). 설비 한 대 × 기간(공장 현지 날짜) */
public final class CmsCardDtos {

    private CmsCardDtos() {
    }

    /** 설비 고르기 — 사용 중지 설비도 이력은 본다(보존 MAINT_RECORD KEEP) */
    @Getter
    @Setter
    public static class EquipOption {
        private long equipId;
        private String equipCd;
        private String equipNm;
        private String plantCd;
        private String useYn;
    }

    /** 기간 안 작업지시 집계 — 취소 제외 */
    @Getter
    @Setter
    public static class MwoSum {
        private long total;
        private long bm;
        private long cm;
        private long pm;
        /** 아직 끝나지 않은 것(신고·접수·작업 중) */
        private long open;
        /** 정지 시작·끝이 둘 다 있는 작업지시의 정지 분 합(내림) */
        private long downMin;
    }

    /** 기간 안 점검 실행 집계(예정일 기준). 지연 = 지금 열린 것 중 예정일이 오늘(공장 현지) 전 */
    @Getter
    @Setter
    public static class ChkSum {
        private long total;
        private long ok;
        private long ng;
        private long skipped;
        private long open;
        private long overdue;
    }

    /** 기간 안 사용 예비품 — 기록 건수·품목 수 */
    @Getter
    @Setter
    public static class PartSum {
        private long lines;
        private long items;
    }

    /** 카드 머리 — 프로필·설치 자산·집계·카운터(설비 것 + 지금 설치된 금형·지그 것) */
    public record CardSummary(ProfileRow profile, String from, String to, String timeZone, List<InstalledAsset> installed, MwoSum mwo,
            ChkSum chk, PartSum part, long resets, List<CounterRow> counters) {
    }

    /** 이력 한 줄 — 작업지시·점검 실행·사용 예비품·카운터 리셋을 시각순으로 합친다 */
    @Getter
    @Setter
    public static class HistRow {
        /** MWO / CHK / PART / RESET (CMS_HIST_KIND) */
        private String kind;
        /** 원본 행 id(종류마다 표가 다르다 — 종류 + id 가 행 키) */
        private long srcId;
        private OffsetDateTime at;
        private String refNo;
        private String title;
        /** 원값 — 화면 글자는 서버가 detail 로 만든다 */
        private String status;
        private String subType;
        private String result;
        private BigDecimal qty;
        private String unit;
        private Integer downMin;
        private String byNm;
        /** 작업지시로 가는 링크(작업지시·작업지시에서 만든 점검·작업지시 사용 예비품) */
        private Long mwoId;
        /** 화면 글자(사용자 언어): 시각(공장 현지 yyyy-MM-dd HH:mm)·내용 */
        private String atText;
        private String detail;

        public String getRowKey() {
            return kind + "-" + srcId;
        }
    }

    public record HistPage(List<HistRow> list, Long total) {
    }

    /** 이력 grid 정렬 허용 목록 — field = grid 열 이름, enum 이름 = 매퍼 choose 키 */
    public enum HistSort implements SortField {
        AT("atText"), KIND("kind"), REF_NO("refNo");

        private final String field;

        HistSort(String field) {
            this.field = field;
        }

        @Override
        public String field() {
            return field;
        }
    }

    /** 이력 엑셀 — grid 조건 + 정렬 + 보이는 열 */
    public record HistExport(String equipCd, String from, String to, String kind, List<String> sort, List<String> columns) {
    }
}
