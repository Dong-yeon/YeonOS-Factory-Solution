package io.smarthappy.module.production.link;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;

import org.springframework.beans.factory.ObjectProvider;

/**
 * 생산 LOT 확장점 — "이 지시의 산출은 새 LOT 이 아니라 정해진 LOT 하나" (시리얼 단위 생산 등, docs/72 2.2).
 * MES 의 {@code LotService.createProduced}(POP 실적·재작업 처리)가 새 LOT 을 만들기 전에 부른다. 빈이 없으면 {@link #NONE} — 지금 동작 그대로.
 *
 * - 대상 지시가 아니면 Optional.empty() → MES 가 새 LOT 을 만든다
 * - 대상 지시면 그 LOT id(없으면 만들어서). 수량이 맞지 않으면 칸 오류(BusinessException)를 던져도 된다
 * - 부르는 쪽 업무 트랜잭션 안(MANDATORY 성격) — 구현은 지시 단위로 줄 세워 같은 지시에 LOT 이 둘 생기지 않게 한다
 */
public interface UnitLotSource {

    Optional<Long> producedLot(long woId, BigDecimal qty, Instant occurredAt, String by);

    UnitLotSource NONE = (woId, qty, occurredAt, by) -> Optional.empty();

    static UnitLotSource of(ObjectProvider<UnitLotSource> provider) {
        return provider.getIfAvailable(() -> NONE);
    }
}
