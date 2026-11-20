package io.smarthappy.module.sales;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import io.smarthappy.module.sales.link.ForecastQuery;
import lombok.RequiredArgsConstructor;

/** 내시 읽기 계약 구현 (docs/38 16.3) — 예외 없이 빈 값(품목 수 상한만 IllegalArgumentException) */
@Component
@RequiredArgsConstructor
class SalesForecastQuery implements ForecastQuery {

    private final OrderLineQueryMapper mapper;

    @Override
    @Transactional(readOnly = true)
    public List<ForecastLineView> latestLines(Collection<Long> itemIds, LocalDate from, LocalDate to) {
        List<Long> ids = SalesOrderLineQuery.itemIds(itemIds);
        return ids.isEmpty() || from == null || to == null || from.isAfter(to) ? List.of() : mapper.latestForecast(ids, from, to);
    }
}
