package io.smarthappy.module.sales;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Objects;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import io.smarthappy.module.sales.link.OrderLineQuery;
import lombok.RequiredArgsConstructor;

/** 수주 행 읽기 계약 구현 (docs/25 4.23) — 예외 없이 빈 값(부르는 쪽 트랜잭션을 rollback-only 로 만들지 않는다) */
@Component
@RequiredArgsConstructor
class SalesOrderLineQuery implements OrderLineQuery {

    private final OrderLineQueryMapper mapper;

    @Override
    @Transactional(readOnly = true)
    public List<OrderLineView> openLines(long custId) {
        return mapper.openLines(custId);
    }

    @Override
    @Transactional(readOnly = true)
    public List<OrderLineView> lines(Collection<Long> lineIds) {
        List<Long> ids = lineIds == null ? List.of() : lineIds.stream().filter(Objects::nonNull).distinct().toList();
        if (ids.size() > MAX_IDS) {
            throw new IllegalArgumentException("too many order line ids: " + ids.size() + " > " + MAX_IDS);
        }
        return ids.isEmpty() ? List.of() : mapper.lines(ids);
    }

    @Override
    @Transactional(readOnly = true)
    public List<OrderLineView> openLinesByItems(Collection<Long> itemIds, LocalDate dueTo) {
        List<Long> ids = itemIds(itemIds);
        return ids.isEmpty() || dueTo == null ? List.of() : mapper.openLinesByItems(ids, dueTo);
    }

    static List<Long> itemIds(Collection<Long> itemIds) {
        List<Long> ids = itemIds == null ? List.of() : itemIds.stream().filter(Objects::nonNull).distinct().toList();
        if (ids.size() > MAX_IDS) {
            throw new IllegalArgumentException("too many item ids: " + ids.size() + " > " + MAX_IDS);
        }
        return ids;
    }
}
