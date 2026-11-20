package io.smarthappy.module.plm.link;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Optional;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import io.smarthappy.module.plm.link.PlmLinkMapper.LotRef;
import io.smarthappy.module.plm.link.PlmLinkMapper.MarkEco;
import io.smarthappy.platform.policy.PolicyService;
import io.smarthappy.platform.security.CurrentUser;
import io.smarthappy.platform.security.LoginUser;
import io.smarthappy.platform.time.BizClock;
import lombok.RequiredArgsConstructor;

/**
 * 변경 표시 (PLM 3b, docs/39 13.2) — 3a no-op 빈을 대신한다. MES 트랜잭션 안에서만(MANDATORY).
 *
 * - 후보 = 이 LOT 에 맞는 대상이 있는 RELEASED ECO. eco_id 오름차순으로 하나씩 잠그고(NO KEY UPDATE) 센 뒤 넣는다 → 첫 N 이 정확히 N
 * - 표시 끝(mark_until)이 있으면 구간 안 LOT 모두(4M_WINDOW·TEMP), 없으면 첫 N(FIRST_LOT, N = ECO 값 또는 정책)
 * - 지시·설비가 이 회사 것이 아니면 없는 것으로 본다 — 표시는 실적을 막지 않는다
 */
@Component
@RequiredArgsConstructor
class EcoChangeMarker implements ChangeMarker {

    static final String POLICY_FIRST_LOTS = "PLM_MARK_FIRST_LOTS";

    private final PlmLinkMapper mapper;
    private final BizClock clock;
    private final PolicyService policies;

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<Long> mark(long lotId, Long woId, Long equipId, OffsetDateTime occurredAt) {
        if (occurredAt == null) {
            throw new IllegalArgumentException("occurredAt");
        }
        LotRef lot = mapper.findLot(lotId);
        if (lot == null) {
            return Optional.empty();
        }
        Long wo = woId != null ? woId : lot.getWoId();
        LotRef rev = wo == null ? null : mapper.findWo(wo);
        if (rev == null) {
            wo = null;
        }
        Long equip = equipId != null && mapper.equipVisible(equipId) ? equipId : null;
        LocalDate day = occurredAt.atZoneSameInstant(clock.zone()).toLocalDate();
        String by = CurrentUser.get().map(LoginUser::loginId).orElse("SYSTEM");
        Integer policyN = null;
        for (long ecoId : mapper.candidates(lotId, lot.getItemId(), wo, rev == null ? null : rev.getBomId(),
                rev == null ? null : rev.getRoutingId(), equip, day)) {
            MarkEco eco = mapper.lockReleased(ecoId);
            if (eco == null || mapper.marked(ecoId, lotId)) {
                continue;
            }
            int count = mapper.countMarks(ecoId);
            String reason;
            if (eco.getMarkUntil() != null) {
                reason = "TEMP".equals(eco.getChangeKind()) ? "TEMP" : "4M_WINDOW";
            } else {
                if (eco.getMarkLots() == null && policyN == null) {
                    policyN = policies.intValue(POLICY_FIRST_LOTS);
                }
                if (count >= (eco.getMarkLots() != null ? eco.getMarkLots() : policyN)) {
                    continue;
                }
                reason = "FIRST_LOT";
            }
            mapper.insertMark(ecoId, lotId, reason, count + 1, wo, equip, occurredAt, by);
        }
        return Optional.ofNullable(mapper.latestMark(lotId));
    }
}
