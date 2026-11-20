package io.smarthappy.module.qms;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import io.smarthappy.module.plm.link.EcoTrace;
import io.smarthappy.module.plm.link.EcoTrace.FirstLot;
import io.smarthappy.module.production.link.LotLookup;
import io.smarthappy.module.production.link.LotLookup.LotView;
import io.smarthappy.module.production.link.StartGate;
import io.smarthappy.platform.policy.Policies;
import io.smarthappy.platform.policy.PolicyService;

/**
 * 착수 게이트 QMS 규칙 (P3 QMS 2부 Q9, docs/29_P3 12.9·12.11) — MES {@link StartGate} 구현 빈.
 *
 * - Q9-1 초품 미판정: 이 품목의 FIRST_LOT 표시(PLM EcoTrace R-1) 중 **다른 지시가 만든**(MES LotLookup R-2, woId ≠ 착수 지시) LOT 에
 *   살아 있는 초품 판정(qms_setup_verif ACTIVE)이 없으면, 또는 판정 NG 인 첫 LOT 에 열린 부적합이 있으면(Q9-g) → 정책 QMS_GATE_FIRST_PENDING.
 *   첫 LOT 을 만드는 지시 자신은 미판정으로 막지 않는다
 * - Q9-2 계측기: 이 품목·공정의 작업일에 유효한 공정(PROCESS) 검사계획이 요구하는 계측기 클래스(육안 VISUAL 제외)마다 쓸 수 있는(GaugeStatus.usable) 게이지가
 *   하나도 없으면 → 정책 QMS_GATE_GAUGE. 계획이 없으면 판정하지 않는다(검사 생성 쪽 오류로 따로 드러남)
 * - Q9-3 SPC 위반 미처리: 이 품목·이 공정의 SPC 위반 이벤트(19장) 중 종결·무효 안 된 것(OPEN·ACKED)이 있으면 → 정책 QMS_GATE_SPC
 * - MES 착수 트랜잭션 안(공정 행 FOR UPDATE 뒤): 읽기만·잠금 없음, MES·PLM 표는 계약으로만. 같은 데이터면 같은 결과(ack 재판정, G-g)
 */
@Component
@Order(100)
public class QmsStartGate implements StartGate {

    static final String KEY = "QMS";
    static final String FIRST_PENDING = "QMS_FIRST_PENDING";
    static final String FIRST_NG = "QMS_FIRST_NG";
    static final String NO_GAUGE = "QMS_NO_GAUGE";
    static final String SPC_OPEN = "QMS_SPC_OPEN";
    /** 초품 후보 상한(최근 표시부터) — 착수 한 번의 조회량을 묶는다(G-6) */
    static final int FIRST_LOT_LIMIT = 50;
    /** 이유로 낱낱이 보일 LOT 수 — 나머지는 "n건 더" 한 줄(MES gate_detail 500자) */
    static final int MAX_LOT_REASONS = 3;

    private final QmsGateMapper mapper;
    private final InspPlanMapper planMapper;
    private final PolicyService policies;
    private final ObjectProvider<EcoTrace> ecoTrace;
    private final ObjectProvider<LotLookup> lotLookup;
    private final SpcEventMapper spcEvents;

    public QmsStartGate(QmsGateMapper mapper, InspPlanMapper planMapper, PolicyService policies, ObjectProvider<EcoTrace> ecoTrace,
            ObjectProvider<LotLookup> lotLookup, SpcEventMapper spcEvents) {
        this.mapper = mapper;
        this.planMapper = planMapper;
        this.policies = policies;
        this.ecoTrace = ecoTrace;
        this.lotLookup = lotLookup;
        this.spcEvents = spcEvents;
    }

    @Override
    public String key() {
        return KEY;
    }

    @Override
    public GateVerdict check(StartContext ctx) {
        String mode = OK;
        List<GateReason> reasons = new ArrayList<>();
        String m1 = policy(Policies.QMS_GATE_FIRST_PENDING);
        if (!"OFF".equals(m1)) {
            List<GateReason> r = firstPending(ctx);
            if (!r.isEmpty()) {
                mode = worst(mode, m1);
                reasons.addAll(r);
            }
        }
        String m2 = policy(Policies.QMS_GATE_GAUGE);
        if (!"OFF".equals(m2)) {
            List<GateReason> r = noGauge(ctx);
            if (!r.isEmpty()) {
                mode = worst(mode, m2);
                reasons.addAll(r);
            }
        }
        String m3 = policy(Policies.QMS_GATE_SPC);
        if (!"OFF".equals(m3)) {
            List<GateReason> r = spcOpen(ctx);
            if (!r.isEmpty()) {
                mode = worst(mode, m3);
                reasons.addAll(r);
            }
        }
        return OK.equals(mode) ? GateVerdict.PASS : new GateVerdict(mode, reasons);
    }

    /** Q9-3 — 이 품목·공정의 열린 SPC 위반, 최근 것부터 {@link #MAX_LOT_REASONS} 줄 + "n건 더" */
    List<GateReason> spcOpen(StartContext ctx) {
        List<SpcEventDtos.EventRow> open = spcEvents.findOpenOfItemProc(ctx.itemId(), ctx.procId(), FIRST_LOT_LIMIT);
        List<GateReason> out = new ArrayList<>();
        for (SpcEventDtos.EventRow e : open) {
            if (out.size() == MAX_LOT_REASONS) {
                out.add(new GateReason(SPC_OPEN, "qms.gate.spcOpenMore", List.of(open.size() - MAX_LOT_REASONS), null, null));
                break;
            }
            out.add(new GateReason(SPC_OPEN, "qms.gate.spcOpen", List.of(e.getCharNo(), e.getRuleCd(), e.getInspNo()), "QMS_SPC_EVENT",
                    e.getEventId()));
        }
        return out;
    }

    /**
     * Q9-1 — 표시 순서(최근부터) 그대로, LOT 하나에 한 줄.
     * ① 미판정: 다른 지시가 만든 첫 LOT 의 (ECO, LOT) 에 살아 있는 판정이 없음(같은 LOT 이 ECO 여럿의 첫 LOT 이면 판정 없는 첫 ECO)
     * ② 판정 NG + 열린 부적합(Q9-g, 사용자 결정 2026-10-22): 살아 있는 판정이 NG 인 첫 LOT 에 종결 안 된 NCR(OPEN·PENDING)이 있음 —
     *    착수 지시가 만든 LOT 도 포함(첫 LOT 을 만드는 일은 끝났고 NG 가 났다), NCR 이 종결·취소되면 사라진다
     */
    List<GateReason> firstPending(StartContext ctx) {
        List<FirstLot> marks = EcoTrace.of(ecoTrace).firstLotsOfItem(ctx.itemId(), FIRST_LOT_LIMIT);
        if (marks.isEmpty()) {
            return List.of();
        }
        Set<Long> lotIds = marks.stream().map(FirstLot::lotId).collect(Collectors.toCollection(HashSet::new));
        Map<Long, LotView> lots = LotLookup.of(lotLookup).lots(lotIds).stream().collect(Collectors.toMap(LotView::lotId, v -> v));
        Map<String, String> verdicts = mapper.findLiveVerifs(lotIds).stream()
                .collect(Collectors.toMap(v -> v.getEcoId() + ":" + v.getLotId(), QmsGateMapper.VerifKey::getResult));
        Map<Long, FirstLot> pending = new LinkedHashMap<>();
        Map<Long, FirstLot> ng = new LinkedHashMap<>();
        for (FirstLot f : marks) {
            LotView lot = lots.get(f.lotId());
            if (lot == null) {
                continue;   // 모르는 LOT(다른 회사·MES 없음)
            }
            String verdict = verdicts.get(f.ecoId() + ":" + f.lotId());
            if (verdict == null) {
                if (lot.woId() == null || lot.woId() != ctx.woId()) {
                    pending.putIfAbsent(f.lotId(), f);    // 착수 지시가 만든 첫 LOT 은 막지 않는다
                }
            } else if ("NG".equals(verdict)) {
                ng.putIfAbsent(f.lotId(), f);
            }
        }
        List<GateReason> out = new ArrayList<>(lines(pending.values(), FIRST_PENDING, "qms.gate.firstPendingMore",
                f -> new GateReason(FIRST_PENDING, "qms.gate.firstPending", List.of(f.lotNo(), f.ecoNo()), "LOT", f.lotId())));
        if (!ng.isEmpty()) {
            Map<Long, String> openNcr = new LinkedHashMap<>();
            mapper.findOpenNcrs(ng.keySet()).forEach(n -> openNcr.putIfAbsent(n.getLotId(), n.getNcrNo()));
            List<FirstLot> hit = ng.values().stream().filter(f -> openNcr.containsKey(f.lotId())).toList();
            out.addAll(lines(hit, FIRST_NG, "qms.gate.firstNgMore",
                    f -> new GateReason(FIRST_NG, "qms.gate.firstNg", List.of(f.lotNo(), f.ecoNo(), openNcr.get(f.lotId())), "LOT", f.lotId())));
        }
        return out;
    }

    /** LOT 별 이유 — {@link #MAX_LOT_REASONS} 줄 뒤는 "n건 더" 한 줄 */
    private static List<GateReason> lines(java.util.Collection<FirstLot> lots, String code, String moreKey,
            java.util.function.Function<FirstLot, GateReason> one) {
        List<GateReason> out = new ArrayList<>();
        for (FirstLot f : lots) {
            if (out.size() == MAX_LOT_REASONS) {
                out.add(new GateReason(code, moreKey, List.of(lots.size() - MAX_LOT_REASONS), null, null));
                break;
            }
            out.add(one.apply(f));
        }
        return out;
    }

    /** Q9-2 — 공정 검사계획(작업일에 유효) → 계측기 클래스 → 쓸 수 있는 게이지 수 */
    List<GateReason> noGauge(StartContext ctx) {
        Long classId = mapper.findItemClassId(ctx.itemId());
        List<InspPlanResolver.Candidate> cands = planMapper.findCandidates("PROCESS", ctx.itemId(), classId).stream()
                .map(c -> new InspPlanResolver.Candidate(c.getInspPlanId(), c.getInspType(), c.getItemId(), c.getItemClassId(), c.getProcId(),
                        c.getAltNo(), c.getRev(), c.getStatus(), c.getValidFrom(), c.getValidTo()))
                .toList();
        Long planId = new InspPlanResolver(cands).effective(new InspPlanResolver.ItemRef(ctx.itemId(), classId), "PROCESS", ctx.procId(),
                ctx.workDay()).map(InspPlanResolver.Candidate::inspPlanId).orElse(null);
        if (planId == null) {
            return List.of();
        }
        List<String> classes = mapper.findPlanGaugeClasses(planId);
        if (classes.isEmpty()) {
            return List.of();
        }
        int dueSoon = policies.intValue(Policies.QMS_CALIB_DUE_SOON_DAYS);
        Set<String> usable = mapper.findGauges(classes).stream()
                .filter(g -> GaugeStatus.of("Y".equals(g.getUseYn()), g.getLastResult(), g.getNextDueDate(), ctx.workDay(), dueSoon).status().usable())
                .map(QmsGateMapper.GaugeRef::getGaugeClass).collect(Collectors.toSet());
        return classes.stream().filter(c -> !usable.contains(c))
                .map(c -> new GateReason(NO_GAUGE, "qms.gate.noGauge", List.of(c), null, null)).toList();
    }

    private String policy(String key) {
        String v = policies.value(key);
        return WARN.equals(v) || BLOCK.equals(v) ? v : "OFF".equals(v) ? "OFF" : WARN;
    }

    private static String worst(String a, String b) {
        return BLOCK.equals(a) || BLOCK.equals(b) ? BLOCK : WARN.equals(a) || WARN.equals(b) ? WARN : OK;
    }
}
