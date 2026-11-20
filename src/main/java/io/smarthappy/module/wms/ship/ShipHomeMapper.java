package io.smarthappy.module.wms.ship;

import java.time.LocalDate;
import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 홈 카드 집계 (docs/37 20장) — 출하 대기. 회사 조건은 RLS, plantCd null = 전체 공장, today = 계획일 지남 기준(공장 오늘) */
@Mapper
public interface ShipHomeMapper {

    ShipOpenSummary shipOpenSummary(@Param("plantCd") String plantCd, @Param("today") LocalDate today);

    List<ShipOpenRow> shipOpenTop(@Param("plantCd") String plantCd, @Param("limit") int limit);

    /** 예약 초과 품목 수(공장 × 품목, 예약 &gt; 가용 — docs/53 (가) L1-4) */
    long reserveOverCount(@Param("plantCd") String plantCd);

    /** 예약 초과 품목 — 모자란 양이 큰 순 */
    List<ReserveOverRow> reserveOverTop(@Param("plantCd") String plantCd, @Param("limit") int limit);

    record ShipOpenSummary(long openCnt, long pickingCnt, long lateCnt) {
    }

    record ReserveOverRow(String plantCd, String itemCd, String itemNm, String unitCd, java.math.BigDecimal reserved,
            java.math.BigDecimal avail) {
    }

    record ShipOpenRow(String shipNo, String plantCd, String custNm, String status, LocalDate planDate) {
    }
}
