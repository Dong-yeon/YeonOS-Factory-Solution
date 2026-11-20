package io.smarthappy.module.cms;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import lombok.Getter;
import lombok.Setter;

/**
 * 설비·생산 대시보드(cms-dashboard, docs/51 C2) — KPI 원값은 {@link CmsKpiMapper} 그대로 쓰고, 여기는 대시보드에만 있는 두 묶음:
 * 비가동 사유별 시간(Pareto)·지시별 계획 대비 양품. 조건은 드릴다운할 기존 목록 화면의 조건과 같다(비가동 사유 검토·작업지시)
 */
@Mapper
public interface CmsDashMapper {

    /** 실효 분류 × 실효 사유(사후 사유 우선, 없으면 원장 사유)별 [from, until) 안 시간·구간 수. PRD·UNKNOWN 제외 */
    @Getter
    @Setter
    class LossRow {
        private String cat;
        private String reasonCd;
        private String reasonNm;
        private long sec;
        private long intervals;
    }

    List<LossRow> losses(@Param("plantCd") String plantCd, @Param("from") OffsetDateTime from, @Param("until") OffsetDateTime until,
            @Param("keyword") String keyword);

    /** 계획 시작일이 [from, to] 인 발행 이후 지시 — 계획 수량 vs 마지막 공정 양품(살아 있는 실적만) */
    @Getter
    @Setter
    class WoRow {
        private long woId;
        private String woNo;
        private String itemCd;
        private String itemNm;
        private String planQty;
        private String goodQty;
        private BigDecimal planNum;
        private BigDecimal goodNum;
        private String unitCd;
        private LocalDate planStart;
        private String status;
    }

    List<WoRow> wos(@Param("plantCd") String plantCd, @Param("from") LocalDate from, @Param("to") LocalDate to, @Param("limit") int limit);
}
