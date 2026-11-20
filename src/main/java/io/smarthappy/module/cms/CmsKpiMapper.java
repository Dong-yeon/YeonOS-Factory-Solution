package io.smarthappy.module.cms;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import lombok.Getter;
import lombok.Setter;

/** CMS 2부 C9 — 설비 KPI 원값(상태 구간·실적·예방점검). MES 원장은 읽기만 */
@Mapper
public interface CmsKpiMapper {

    /** 설비 한 대의 기간 원값 — 상태 구간은 [from, until) 로 자르고 실효 분류(사후 사유 우선), 실적은 [from, until) 발생 */
    @Getter
    @Setter
    class RawRow {
        private long equipId;
        private String equipCd;
        private String equipNm;
        private String nodeCd;
        private String nodeNm;
        private long coveredSec;
        private long unknownSec;
        private long nstSec;
        private long sdtSec;
        private long udtSec;
        private long prdSec;
        private long failures;
        private BigDecimal idealSec;
        private BigDecimal idealGoodSec;
        private long ctMissing;
        private BigDecimal goodQty;
        private BigDecimal totalQty;
        /** 설비 프로필 개당 이론 초(수집 기준 성능·OEE) — 없으면 null */
        private BigDecimal idealCtSec;
    }

    /** 끝난 예방점검(점검 실행 CHECK 방식 + PM 작업지시) — 준수 판정은 Java(PmDue) */
    @Getter
    @Setter
    class PmRow {
        private long equipId;
        private LocalDate dueDate;
        private OffsetDateTime doneAt;
        private BigDecimal dueUsage;
        private BigDecimal doneUsage;
    }

    List<RawRow> raw(@Param("plantCd") String plantCd, @Param("from") OffsetDateTime from, @Param("until") OffsetDateTime until,
            @Param("keyword") String keyword);

    List<PmRow> pmDone(@Param("plantCd") String plantCd, @Param("from") OffsetDateTime from, @Param("until") OffsetDateTime until);

    /** 수집 COUNT 순합(설비별) — 원 행 + 그 역분개, 둘 다 원 행 발생 시각으로 (docs/33 14장) */
    @Getter
    @Setter
    class CountRow {
        private long equipId;
        private BigDecimal goodQty;
        private BigDecimal scrapQty;
    }

    List<CountRow> countSums(@Param("plantCd") String plantCd, @Param("from") OffsetDateTime from, @Param("until") OffsetDateTime until);
}
