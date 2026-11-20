package io.smarthappy.module.qms;

import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import io.smarthappy.module.qms.link.NcrPort;

/** 계약 {@link NcrPort} 구현 — 만들기·취소는 {@link NcrService} 규칙(채번·보류 정책·보류 해제), 상태는 매퍼만(예외 없음) */
@Component
public class QmsNcrPort implements NcrPort {

    private final NcrService ncrs;
    private final NcrMapper mapper;

    public QmsNcrPort(NcrService ncrs, NcrMapper mapper) {
        this.ncrs = ncrs;
        this.mapper = mapper;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<NcrView> createForClaim(ClaimRequest req) {
        return Optional.of(ncrs.createForClaim(req));
    }

    @Override
    @Transactional(readOnly = true)
    public List<NcrView> ncrStatus(Collection<Long> ncrIds) {
        List<Long> ids = ncrIds == null ? List.of() : ncrIds.stream().filter(Objects::nonNull).distinct().toList();
        if (ids.size() > MAX_IDS) {
            throw new IllegalArgumentException("too many ncr ids: " + ids.size() + " > " + MAX_IDS);
        }
        return ids.isEmpty() ? List.of() : mapper.findViews(ids);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean cancelForClaim(String refType, long refId, String reason, String by) {
        return ncrs.cancelForClaim(refType, refId, reason, by);
    }
}
