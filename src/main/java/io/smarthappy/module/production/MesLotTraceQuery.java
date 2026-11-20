package io.smarthappy.module.production;

import java.util.Collection;
import java.util.List;
import java.util.Objects;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import io.smarthappy.module.production.link.LotTraceQuery;
import lombok.RequiredArgsConstructor;

/**
 * LOT 계보 추적 계약 구현 (docs/25 4.49). 줄은 MAX_ROWS + 1 까지 받아 넘으면 잘림, 깊이 maxDepth 에 닿은 LOT(순환 줄 제외)에서
 * 더 이어지는 살아 있는 계보가 있으면 잘림 — 조용히 덜 보이지 않게
 */
@Component
@RequiredArgsConstructor
class MesLotTraceQuery implements LotTraceQuery {

    private final LotTraceMapper mapper;

    @Override
    @Transactional(readOnly = true)
    public TraceResult forward(Collection<Long> lotIds, int maxDepth) {
        return trace(lotIds, maxDepth, false);
    }

    @Override
    @Transactional(readOnly = true)
    public TraceResult backward(Collection<Long> lotIds, int maxDepth) {
        return trace(lotIds, maxDepth, true);
    }

    private TraceResult trace(Collection<Long> lotIds, int maxDepth, boolean back) {
        List<Long> ids = lotIds == null ? List.of() : lotIds.stream().filter(Objects::nonNull).distinct().toList();
        if (ids.size() > MAX_IDS) {
            throw new IllegalArgumentException("too many lot ids: " + ids.size() + " > " + MAX_IDS);
        }
        if (maxDepth < 0 || maxDepth > MAX_DEPTH) {
            throw new IllegalArgumentException("maxDepth out of range: " + maxDepth + " (0~" + MAX_DEPTH + ")");
        }
        if (ids.isEmpty()) {
            return TraceResult.EMPTY;
        }
        List<TraceRow> rows = mapper.trace(ids, back, maxDepth, MAX_ROWS + 1);
        if (rows.size() > MAX_ROWS) {
            return new TraceResult(List.copyOf(rows.subList(0, MAX_ROWS)), true);
        }
        List<Long> edge = rows.stream().filter(r -> r.depth() == maxDepth && !r.cycle()).map(TraceRow::lotId).distinct().toList();
        boolean truncated = false;
        for (int i = 0; i < edge.size() && !truncated; i += MAX_ROWS) {
            truncated = mapper.more(edge.subList(i, Math.min(edge.size(), i + MAX_ROWS)), back);
        }
        return new TraceResult(List.copyOf(rows), truncated);
    }
}
