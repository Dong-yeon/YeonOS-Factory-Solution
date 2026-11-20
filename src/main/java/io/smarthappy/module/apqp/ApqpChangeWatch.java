package io.smarthappy.module.apqp;

import static io.smarthappy.module.apqp.jooq.Tables.APQP_QMS_DOC;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.jooq.DSLContext;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import io.smarthappy.module.apqp.ApqpDtos.ChangeNote;
import io.smarthappy.module.apqp.ApqpDtos.ChangeNotes;
import io.smarthappy.module.apqp.ApqpDtos.CharRef;
import io.smarthappy.module.apqp.ApqpDtos.PlanOverlap;
import io.smarthappy.module.apqp.ApqpDtos.PlanRef;
import io.smarthappy.module.plm.link.EcoLookup;
import io.smarthappy.module.plm.link.EcoLookup.EcoPage;
import io.smarthappy.module.plm.link.EcoLookup.EcoView;
import io.smarthappy.module.qms.link.ChangeLookup;
import io.smarthappy.module.qms.link.ChangeLookup.ChangeDetail;
import io.smarthappy.module.qms.link.InspPlanLookup;
import io.smarthappy.module.qms.link.InspPlanLookup.PlanKey;
import io.smarthappy.platform.time.BizClock;
import lombok.RequiredArgsConstructor;

/**
 * 재검토 표시 ②③ (docs/41 11.6, A3-2) — 확정 PFMEA·관리계획서의 확정일(회사 날짜, 포함) 이후
 * ② 그 품목에 적용(RELEASED)된 ECO(PLM {@link EcoLookup#releasedFor}) ③ 승인된 4M ECO(PLM {@link EcoLookup#approvedFor} 중 PROCESS_4M,
 * 4M 세부는 QMS {@link ChangeLookup#details}) 를 ECO 하나에 한 줄로 합친다. 표시만(L5) — 상태·편집은 그대로.
 * M4: 4M 세부의 영향 특성(charIds)을 이 문서의 특성과 같은 id 로 비교해 겹침을 붙인다(저장하지 않음).
 * E-3(11.10): 이 리비전을 올린 QMS 문서 리비전(apqp_qms_doc)을 DOC 대상으로 담은 ECO 는 재검토가 아니라 "이 리비전을 배포한 ECO" 로 나눈다.
 * 두 계약 모두 예외 없음·없는 모듈 = NONE(빈 목록). 상세 한 건에만 부른다(목록은 행마다 모듈 간 조회가 되므로 하지 않음 — M2)
 */
@Component
@RequiredArgsConstructor
class ApqpChangeWatch {

    static final String KIND_4M = "PROCESS_4M";

    private final ObjectProvider<EcoLookup> ecos;
    private final ObjectProvider<ChangeLookup> changes;
    private final ObjectProvider<InspPlanLookup> inspPlans;
    private final BizClock clock;
    private final DSLContext dsl;

    /**
     * 현행 확정본만 — 작성 중·결재 중·폐기는 빈 값. kind·id = 원본(ApqpDocService.FMEA·CP), chars = 이 문서의 특성(순서 = 표시 순서, 같은 id 는 한 번),
     * plans = 관리계획서가 대조(C5)에서 본 검사계획(PFMEA 는 null — 검사계획 겹침을 보지 않음)
     */
    ChangeNotes since(String kind, long id, String status, long itemId, OffsetDateTime releasedAt, Collection<CharRef> chars,
            Collection<PlanRef> plans) {
        if (!"RELEASED".equals(status) || releasedAt == null) {
            return ChangeNotes.EMPTY;
        }
        LocalDate since = releasedAt.atZoneSameInstant(clock.zone()).toLocalDate();
        EcoLookup eco = EcoLookup.of(ecos);
        EcoPage released = eco.releasedFor(itemId, since);
        EcoPage approved = eco.approvedFor(itemId, since);
        boolean truncated = released.truncated() || approved.truncated();
        Map<Long, EcoView> rows = new LinkedHashMap<>();
        released.rows().forEach(e -> rows.put(e.ecoId(), e));
        approved.rows().stream().filter(e -> KIND_4M.equals(e.changeKind())).forEach(e -> rows.putIfAbsent(e.ecoId(), e));
        if (rows.isEmpty()) {
            return new ChangeNotes(List.of(), truncated, List.of());
        }
        Long ownRev = dsl.select(APQP_QMS_DOC.DOC_REV_ID).from(APQP_QMS_DOC)
                .where(APQP_QMS_DOC.SRC_KIND.eq(kind)).and(APQP_QMS_DOC.SRC_ID.eq(id)).fetchOne(APQP_QMS_DOC.DOC_REV_ID);
        List<Long> ids4m = rows.values().stream().filter(e -> KIND_4M.equals(e.changeKind())).map(EcoView::ecoId).toList();
        Map<Long, ChangeDetail> details = ids4m.isEmpty() ? Map.of()
                : ChangeLookup.of(changes).details(ids4m).stream()
                        .collect(Collectors.toMap(ChangeDetail::ecoId, Function.identity(), (a, b) -> a));
        // 검사계획 키(11.6.2 A안) — 관리계획서만, 4M 영향 계획 + 대조가 본 계획을 한 번에
        Map<Long, PlanKey> keys = Map.of();
        if (plans != null) {
            Set<Long> ids = new HashSet<>();
            details.values().forEach(d -> ids.addAll(d.inspPlanIds()));
            if (!ids.isEmpty()) {
                plans.forEach(p -> ids.add(p.inspPlanId()));
                keys = InspPlanLookup.of(inspPlans).keys(ids).stream()
                        .collect(Collectors.toMap(PlanKey::inspPlanId, Function.identity(), (a, b) -> a));
            }
        }
        Map<Long, PlanKey> planKeys = keys;
        Map<Long, CharRef> mine = new LinkedHashMap<>();
        chars.forEach(c -> mine.putIfAbsent(c.charId(), c));
        List<ChangeNote> review = new ArrayList<>();
        List<ChangeNote> own = new ArrayList<>();
        rows.values().stream()
                .map(e -> note(e, details.get(e.ecoId()), mine, plans, planKeys))
                .sorted(Comparator.comparing(ApqpChangeWatch::day).thenComparingLong(ChangeNote::ecoId))
                .forEach(n -> (ownRev != null && rows.get(n.ecoId()).docRevIds().contains(ownRev) ? own : review).add(n));
        return new ChangeNotes(List.copyOf(review), truncated, List.copyOf(own));
    }

    private static ChangeNote note(EcoView e, ChangeDetail c, Map<Long, CharRef> mine, Collection<PlanRef> plans,
            Map<Long, PlanKey> keys) {
        List<CharRef> overlap = null;
        if (c != null) {
            var hit = new HashSet<>(c.charIds());
            overlap = mine.values().stream().filter(m -> hit.contains(m.charId())).toList();
        }
        return new ChangeNote(e.ecoId(), e.ecoNo(), e.title(), e.changeKind(), e.status(), e.approvedOn(), e.releasedOn(),
                c == null ? List.of() : c.m4Types(), overlap, plans == null || c == null ? null : planOverlap(c.inspPlanIds(), plans, keys));
    }

    /**
     * 검사계획 겹침(docs/41 11.6.2 A안) — 대조가 본 확정본과 <b>같은 id</b> 또는 <b>같은 키</b>(검사 유형·품목·품목 클래스·공정·대체 번호 —
     * QMS InspPlanLookup.keys) = hits(다른 리비전이면 4M 이 가리킨 그 리비전으로), 키가 다르면 unrelated, 키를 못 읽으면(QMS 없음·없는 id)
     * unknown — 틀린 "겹침 없음" 을 내지 않는다
     */
    static PlanOverlap planOverlap(List<Long> inspPlanIds, Collection<PlanRef> plans, Map<Long, PlanKey> keys) {
        if (inspPlanIds == null || inspPlanIds.isEmpty()) {
            return null;
        }
        Map<Long, PlanRef> byId = new LinkedHashMap<>();
        plans.forEach(p -> byId.putIfAbsent(p.inspPlanId(), p));
        Map<List<Object>, PlanRef> byKey = new HashMap<>();
        plans.forEach(p -> {
            PlanKey k = keys.get(p.inspPlanId());
            if (k != null) {
                byKey.putIfAbsent(key(k), p);
            }
        });
        List<PlanRef> hits = new ArrayList<>();
        int unknown = 0;
        int unrelated = 0;
        for (Long pid : new LinkedHashSet<>(inspPlanIds)) {
            PlanRef same = byId.get(pid);
            PlanKey k = keys.get(pid);
            if (same != null) {
                hits.add(same);
            } else if (k == null) {
                unknown++;
            } else if (byKey.containsKey(key(k))) {
                PlanRef base = byKey.get(key(k));
                hits.add(new PlanRef(pid, k.rev(), base.inspType(), base.procCd()));
            } else {
                unrelated++;
            }
        }
        return new PlanOverlap(List.copyOf(hits), unknown, unrelated);
    }

    /** 같은 검사계획(리비전과 무관)을 가르는 키 */
    private static List<Object> key(PlanKey k) {
        return Arrays.asList(k.inspType(), k.itemId(), k.itemClassId(), k.procId(), k.altNo());
    }

    /** 정렬 기준 = 적용일, 아직 적용 전(승인만)이면 승인일 */
    private static LocalDate day(ChangeNote n) {
        return n.releasedOn() != null ? n.releasedOn() : n.approvedOn() != null ? n.approvedOn() : LocalDate.MIN;
    }
}
