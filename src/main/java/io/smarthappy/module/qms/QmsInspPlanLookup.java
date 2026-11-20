package io.smarthappy.module.qms;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;

import org.springframework.stereotype.Component;

import io.smarthappy.module.qms.link.InspPlanLookup;

/**
 * 계약 {@link InspPlanLookup} 구현 (docs/29_P3 18장) — 매퍼만, 트랜잭션을 따로 열지 않는다(부르는 쪽 트랜잭션에서 읽기, 예외 없음).
 * 계획 고르기는 검사 실행·착수 게이트와 같은 {@link InspPlanResolver}. 회사 범위는 RLS(다른 회사 품목은 후보가 없어 빈 목록)
 */
@Component
public class QmsInspPlanLookup implements InspPlanLookup {

    private final InspPlanMapper planMapper;
    private final QmsGateMapper gateMapper;
    private final QmsLinkMapper linkMapper;

    public QmsInspPlanLookup(InspPlanMapper planMapper, QmsGateMapper gateMapper, QmsLinkMapper linkMapper) {
        this.planMapper = planMapper;
        this.gateMapper = gateMapper;
        this.linkMapper = linkMapper;
    }

    @Override
    public List<PlanLine> lines(long itemId, Long procId, String inspType, LocalDate onDate) {
        if (inspType == null || !INSP_TYPES.contains(inspType) || onDate == null) {
            return List.of();
        }
        Long classId = gateMapper.findItemClassId(itemId);
        List<InspPlanResolver.Candidate> cands = planMapper.findCandidates(inspType, itemId, classId).stream()
                .map(c -> new InspPlanResolver.Candidate(c.getInspPlanId(), c.getInspType(), c.getItemId(), c.getItemClassId(), c.getProcId(),
                        c.getAltNo(), c.getRev(), c.getStatus(), c.getValidFrom(), c.getValidTo()))
                .toList();
        return new InspPlanResolver(cands).effective(new InspPlanResolver.ItemRef(itemId, classId), inspType, procId, onDate)
                .map(p -> linkMapper.planLines(p.inspPlanId()).stream()
                        .map(r -> new PlanLine(p.inspPlanId(), p.rev(), r.getLineNo(), r.getCharId(), r.getCharNo(), r.getCharNm(), r.getSpecialCls(),
                                r.getDataType(), r.getNominal(), r.getLsl(), r.getUsl(), r.getUnitNm(), r.getScale(), r.getMethodNm(),
                                r.getGaugeClass(), r.getSamplingPlanCd(), r.getFreqType(), r.getFreqN(), r.getReactionPlan()))
                        .toList())
                .orElse(List.of());
    }

    @Override
    public List<PlanKey> keys(Collection<Long> inspPlanIds) {
        if (inspPlanIds == null || inspPlanIds.isEmpty()) {
            return List.of();
        }
        List<Long> ids = inspPlanIds.stream().filter(Objects::nonNull).distinct().sorted().toList();
        List<PlanKey> out = new ArrayList<>();
        for (int i = 0; i < ids.size(); i += KEYS_CHUNK) {
            linkMapper.planKeys(ids.subList(i, Math.min(i + KEYS_CHUNK, ids.size()))).forEach(r -> out.add(new PlanKey(r.getInspPlanId(),
                    r.getInspType(), r.getItemId(), r.getItemClassId(), r.getProcId(), r.getAltNo(), r.getRev(), r.getStatus())));
        }
        return List.copyOf(out);
    }
}
