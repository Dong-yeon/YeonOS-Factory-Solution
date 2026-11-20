package io.smarthappy.module.sales;

import java.time.LocalDate;
import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import io.smarthappy.module.sales.link.ForecastQuery.ForecastLineView;
import io.smarthappy.module.sales.link.OrderLineQuery.OrderLineView;

/** 수주 행·내시 읽기 계약 (docs/25 4.23, docs/38 16.3) — 회사 조건은 RLS, 잠금 없음 */
@Mapper
interface OrderLineQueryMapper {

    List<OrderLineView> openLines(@Param("custId") long custId);

    List<OrderLineView> lines(@Param("ids") List<Long> ids);

    List<OrderLineView> openLinesByItems(@Param("itemIds") List<Long> itemIds, @Param("dueTo") LocalDate dueTo);

    List<ForecastLineView> latestForecast(@Param("itemIds") List<Long> itemIds, @Param("from") LocalDate from, @Param("to") LocalDate to);
}
