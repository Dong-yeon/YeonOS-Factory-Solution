package io.smarthappy.module.production;

import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import io.smarthappy.module.production.link.LotLookup;
import lombok.RequiredArgsConstructor;

/** LOT 조회 계약 구현 (docs/25 4.21) — 예외 없이 빈 값(부르는 쪽 트랜잭션을 rollback-only 로 만들지 않는다) */
@Component
@RequiredArgsConstructor
class MesLotLookup implements LotLookup {

    private final LotLookupMapper mapper;

    @Override
    @Transactional(readOnly = true)
    public List<LotView> lots(Collection<Long> lotIds) {
        List<Long> ids = ids(lotIds);
        return ids.isEmpty() ? List.of() : mapper.lots(ids);
    }

    /** 중복·null 빼고, MAX_IDS 초과는 호출 코드 오류 */
    private static List<Long> ids(Collection<Long> lotIds) {
        List<Long> ids = lotIds == null ? List.of() : lotIds.stream().filter(Objects::nonNull).distinct().toList();
        if (ids.size() > MAX_IDS) {
            throw new IllegalArgumentException("too many lot ids: " + ids.size() + " > " + MAX_IDS);
        }
        return ids;
    }

    @Override
    @Transactional(readOnly = true)
    public List<LotOrigin> origins(Collection<Long> lotIds) {
        List<Long> ids = ids(lotIds);
        return ids.isEmpty() ? List.of() : mapper.origins(ids);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<LotView> byNo(String lotNo) {
        if (lotNo == null || lotNo.isBlank() || lotNo.strip().length() > 60) {
            return Optional.empty();
        }
        return Optional.ofNullable(mapper.byNo(lotNo.strip()));
    }

    @Override
    @Transactional(readOnly = true)
    public List<LotView> lotsOfWo(long woId, int limit) {
        return mapper.lotsOfWo(woId, LotLookup.clampLimit(limit));
    }
}
