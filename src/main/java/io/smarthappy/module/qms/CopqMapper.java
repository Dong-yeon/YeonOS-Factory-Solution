package io.smarthappy.module.qms;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import io.smarthappy.module.qms.CopqDtos.CostRow;
import io.smarthappy.module.qms.CopqDtos.CostValue;
import io.smarthappy.module.qms.CopqDtos.LineRow;

@Mapper
public interface CopqMapper {

    /** 종결 NCR 처리 행(폐기·재작업·선별) — 종결 시각이 [fromAt, toAt), 월은 회사 시간대, 단가는 종결 시각의 표준단가 이력 */
    List<LineRow> findNcrLines(@Param("fromAt") OffsetDateTime fromAt, @Param("toAt") OffsetDateTime toAt, @Param("zone") String zone);

    /** 품목들의 달마다 그 달 말(다음 달 1일 0시, 회사 시간대) 직전 표준단가 — from·to 는 달 1일(양끝 포함). 다른 회사 품목은 빠진다(RLS) */
    List<CopqDtos.ItemPrice> findMonthEndPrices(@Param("itemIds") List<Long> itemIds, @Param("from") LocalDate from, @Param("to") LocalDate to,
            @Param("zone") String zone);

    /** 수동 비용 행 — cost_month 가 [from, to) */
    List<CostRow> findCosts(@Param("from") LocalDate from, @Param("to") LocalDate to);

    CostRow findCost(@Param("id") long id);

    long insertCost(@Param("f") CostValue f, @Param("by") String by);

    int updateCost(@Param("id") long id, @Param("f") CostValue f, @Param("by") String by);

    int deleteCost(@Param("id") long id, @Param("rowVer") int rowVer);
}
