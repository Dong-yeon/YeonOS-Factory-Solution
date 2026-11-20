package io.smarthappy.module.production;

import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import io.smarthappy.module.production.link.LotHoldPort;
import io.smarthappy.platform.common.text.VisibleText;
import lombok.RequiredArgsConstructor;

/** LOT 보류 근거 계약 구현 (docs/25 4.19) — QMS 출처만, 잠금 = LOT 행 → 근거 행 */
@Component
@RequiredArgsConstructor
class MesLotHoldPort implements LotHoldPort {

    private static final Set<String> SOURCES = Set.of(QMS_NCR, QMS_CONTAIN, QMS_SPC);

    private final LotHoldService holds;
    private final LotHoldMapper mapper;

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean hold(long lotId, String source, long refId, String reason, String by) {
        check(lotId, source, reason, by);
        return holds.addLocked(lotId, source, refId, VisibleText.strip(reason), by);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean release(long lotId, String source, long refId, String reason, String by) {
        check(lotId, source, reason, by);
        return holds.releaseLocked(lotId, source, refId, VisibleText.strip(reason), by);
    }

    @Override
    @Transactional(readOnly = true)
    public List<HoldView> holds(Collection<Long> lotIds) {
        List<Long> ids = lotIds == null ? List.of() : lotIds.stream().filter(Objects::nonNull).distinct().toList();
        if (ids.size() > MAX_IDS) {
            throw new IllegalArgumentException("too many lot ids: " + ids.size() + " > " + MAX_IDS);
        }
        if (ids.isEmpty()) {
            return List.of();
        }
        return mapper.liveHolds(ids).stream()
                .map(h -> new HoldView(h.getHoldId(), h.getLotId(), h.getSource(), h.getRefId(), h.getReason(), h.getHeldAt(), h.getHeldBy()))
                .toList();
    }

    /** 출처·사유·사람 검사 후 LOT 잠금 — 없는·다른 회사 LOT 은 IllegalArgumentException */
    private void check(long lotId, String source, String reason, String by) {
        if (!SOURCES.contains(source)) {
            throw new IllegalArgumentException("source must be QMS_NCR, QMS_CONTAIN or QMS_SPC: " + source);
        }
        String r = VisibleText.strip(reason);   // 보이지 않는 글자만이면 null(QA R4-a)
        if (r == null || r.codePointCount(0, r.length()) > 500) {
            throw new IllegalArgumentException("reason is required (<= 500)");
        }
        if (by == null || by.isBlank() || by.length() > 50) {
            throw new IllegalArgumentException("by is required (<= 50)");
        }
        if (holds.lock(lotId) == null) {
            throw new IllegalArgumentException("lot " + lotId + " is not a lot of this company");
        }
    }
}
