package io.smarthappy.module.plm.link;

import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import io.smarthappy.module.plm.link.PlmLinkMapper.EcoHead;
import io.smarthappy.module.plm.link.PlmLinkMapper.TargetRef;
import io.smarthappy.platform.time.BizClock;
import lombok.RequiredArgsConstructor;

/** ECO 조회 계약 구현 (docs/39 16장·24장) — 머리 + 유형별 대상 id, 품목별 적용·승인 목록 */
@Component
@RequiredArgsConstructor
class PlmEcoLookup implements EcoLookup {

    private final PlmLinkMapper mapper;
    private final BizClock clock;

    @Override
    @Transactional(readOnly = true)
    public Optional<EcoView> eco(long ecoId) {
        EcoHead h = mapper.ecoHead(ecoId);
        return h == null ? Optional.empty() : Optional.of(view(h, mapper.ecoTargets(ecoId)));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<EcoView> byNo(String ecoNo) {
        if (ecoNo == null || ecoNo.isBlank() || ecoNo.strip().length() > 50) {
            return Optional.empty();
        }
        EcoHead h = mapper.ecoHeadByNo(ecoNo.strip().toUpperCase(Locale.ROOT));
        return h == null ? Optional.empty() : Optional.of(view(h, mapper.ecoTargets(h.getEcoId())));
    }

    @Override
    @Transactional(readOnly = true)
    public List<EcoView> ecos(Collection<Long> ecoIds) {
        List<Long> ids = ecoIds == null ? List.of() : ecoIds.stream().filter(Objects::nonNull).distinct().toList();
        if (ids.size() > MAX_IDS) {
            throw new IllegalArgumentException("too many ECO ids: " + ids.size() + " > " + MAX_IDS);
        }
        if (ids.isEmpty()) {
            return List.of();
        }
        Map<Long, List<TargetRef>> targets = mapper.ecoTargetsOf(ids).stream()
                .collect(Collectors.groupingBy(TargetRef::getEcoId));
        return mapper.ecoHeads(ids).stream().map(h -> view(h, targets.getOrDefault(h.getEcoId(), List.of()))).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public EcoPage releasedFor(long itemId, java.time.LocalDate since) {
        return forItem(itemId, since, "RELEASED");
    }

    @Override
    @Transactional(readOnly = true)
    public EcoPage approvedFor(long itemId, java.time.LocalDate since) {
        return forItem(itemId, since, "APPROVED");
    }

    /** 머리 1회(상한 + 1) + 대상 1회 — since 는 회사 자정의 시각으로 바꿔 시각 칸과 비교(색인 그대로) */
    private EcoPage forItem(long itemId, java.time.LocalDate since, String basis) {
        if (since == null) {
            return EcoPage.EMPTY;
        }
        java.time.OffsetDateTime from = since.atStartOfDay(clock.zone()).toOffsetDateTime();
        List<EcoHead> heads = mapper.ecoHeadsForItem(itemId, basis, from, MAX_IDS + 1);
        boolean truncated = heads.size() > MAX_IDS;
        List<EcoHead> page = truncated ? heads.subList(0, MAX_IDS) : heads;
        if (page.isEmpty()) {
            return EcoPage.EMPTY;
        }
        Map<Long, List<TargetRef>> targets = mapper.ecoTargetsOf(page.stream().map(EcoHead::getEcoId).toList()).stream()
                .collect(Collectors.groupingBy(TargetRef::getEcoId));
        return new EcoPage(page.stream().map(h -> view(h, targets.getOrDefault(h.getEcoId(), List.of()))).toList(), truncated);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<PpapView> ppap(long ecoId) {
        PlmLinkMapper.PpapRow r = mapper.ppapOf(ecoId);
        return r == null ? Optional.empty() : Optional.of(new PpapView(r.getPpapId(), r.getStatus(), r.getDecidedOn()));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Long> custId(long ecoId) {
        return Optional.ofNullable(mapper.ecoCustId(ecoId));
    }

    @Override
    @Transactional(readOnly = true)
    public List<PcnView> pcns(long ecoId) {
        return mapper.pcnsOf(ecoId).stream().map(r -> new PcnView(r.getPcnId(), r.getPcnNo(), r.getCustId(), r.getNotifiedOn(),
                r.getResponseDue(), r.getStatus(), r.getRespondedOn())).toList();
    }

    private EcoView view(EcoHead h, List<TargetRef> t) {
        return new EcoView(h.getEcoId(), h.getEcoNo(), h.getTitle(), h.getChangeKind(), h.getStatus(),
                h.getEffectiveDate(), h.getMarkLots(), h.getMarkUntil(), h.getCustApprovalStatus(),
                ids(t, null), ids(t, "EQUIP"), ids(t, "PROC"), ids(t, "DOC"), day(h.getApprovedAt()), day(h.getReleasedAt()));
    }

    /** 시각 → 회사 날짜(docs/39 24장 R4) */
    private java.time.LocalDate day(java.time.OffsetDateTime at) {
        return at == null ? null : at.atZoneSameInstant(clock.zone()).toLocalDate();
    }

    /** type null = 대상 품목 전부(중복 없이, 품목 없는 DOC 제외), 아니면 그 유형의 참조 id */
    private static List<Long> ids(List<TargetRef> targets, String type) {
        return targets.stream().filter(t -> type == null || type.equals(t.getTargetType()))
                .map(t -> type == null ? t.getItemId() : t.getRefId()).filter(Objects::nonNull).distinct().toList();
    }
}
