package io.smarthappy.module.production;

import java.time.LocalDate;
import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 홈 카드용 집계 (docs/37 6장) — 지시·LOT 매퍼와 나눠 둔다(홈 카드가 업무 화면 쿼리를 바꾸지 않게) */
@Mapper
public interface ProductionHomeMapper {

    /** 오늘 할 지시(발행·진행, 계획 시작 ≤ 오늘) 건수와 진행·일시정지 공정, 계획 종료가 지난 지시 */
    /** plantCd null = 전체 공장 */
    WoTodaySummary woTodaySummary(@Param("today") LocalDate today, @Param("plantCd") String plantCd);

    List<WoTodayRow> woTodayTop(@Param("today") LocalDate today, @Param("plantCd") String plantCd, @Param("limit") int limit);

    /** 잔량이 남은 보류 LOT */
    long lotHoldCount();

    List<LotHoldRow> lotHoldTop(@Param("zone") String zone, @Param("limit") int limit);

    record WoTodaySummary(long woCnt, long overdueCnt, long runCnt, long pauseCnt) {
    }

    record WoTodayRow(String woNo, String itemCd, String itemNm, String planQty, String unitCd, String status,
            LocalDate planEnd, LocalDate planStart) {
    }

    /** createdOn = LOT 목록 조회 기간과 같은 기준(DB 세션 날짜 created_at::date) — 링크 조건 */
    record LotHoldRow(String lotNo, String itemCd, String balance, String unitCd, String holdReason, LocalDate createdOn) {
    }
}
