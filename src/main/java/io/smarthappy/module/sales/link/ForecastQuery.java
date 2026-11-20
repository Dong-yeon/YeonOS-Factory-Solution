package io.smarthappy.module.sales.link;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;

import org.springframework.beans.factory.ObjectProvider;

/**
 * 내시(고객 예측) 읽기 계약 (docs/38 16.3 — APS MPS 수요) — 다른 모듈이 {@code sal_forecast_rev}·{@code sal_forecast_line} 을 직접 읽지 않게.
 * 읽기만, 잠금 없음, 회사는 RLS. 예외 없음(부르는 쪽 트랜잭션을 rollback-only 로 만들지 않는다). 영업이 없는 설치본은 {@link #NONE}.
 */
public interface ForecastQuery {

    /** 한 번에 받을 수 있는 품목 수 — 넘으면 IllegalArgumentException */
    int MAX_IDS = 500;

    /**
     * 내시 줄 — 수량은 품목 기본 단위. 기간 = [bucketStart, 다음 기간 시작) (bucketType DAY 1일·WEEK 7일·MONTH 1개월).
     * firmYn = 고객이 확정이라 표시한 줄
     */
    record ForecastLineView(long custId, int revNo, long itemId, String bucketType, LocalDate bucketStart, BigDecimal qty,
            String firmYn) {

        /** 기간 끝(미포함) */
        public LocalDate bucketEnd() {
            return switch (bucketType) {
                case "DAY" -> bucketStart.plusDays(1);
                case "WEEK" -> bucketStart.plusDays(7);
                default -> bucketStart.plusMonths(1);
            };
        }
    }

    /** 조회 없음 — 영업 모듈이 없는 설치본(빈 없음) */
    ForecastQuery NONE = (itemIds, from, to) -> List.of();

    /**
     * 거래처마다 **최신 버전**(rev_no 최대)의 줄 중 품목이 itemIds 에 있고 기간이 [from, to] 와 겹치는 것.
     * 순서 = 품목 → 거래처 → 기간 시작 → 기간 유형. 중복·null 품목 id 는 한 번·무시, 다른 회사 품목은 빠진다
     */
    List<ForecastLineView> latestLines(Collection<Long> itemIds, LocalDate from, LocalDate to);

    /** 빈이 있으면 그것, 없으면 {@link #NONE} */
    static ForecastQuery of(ObjectProvider<ForecastQuery> provider) {
        return provider.getIfAvailable(() -> NONE);
    }
}
