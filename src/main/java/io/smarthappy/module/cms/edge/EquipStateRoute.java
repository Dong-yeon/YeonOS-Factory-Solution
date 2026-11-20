package io.smarthappy.module.cms.edge;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import io.smarthappy.module.cms.EquipEventService;
import io.smarthappy.module.cms.edge.EdgeDtos.LinkRef;
import io.smarthappy.platform.messaging.InboundMessage;
import io.smarthappy.platform.messaging.InboundResult;
import io.smarthappy.platform.messaging.InboundRoute;
import io.smarthappy.platform.policy.PolicyService;
import io.smarthappy.platform.tenant.TenantContext;
import io.smarthappy.platform.time.BizClock;
import lombok.RequiredArgsConstructor;

/**
 * 수신 경로 EQUIP_STATE (docs/65 16.3·16.5·16.6) — 엣지가 보낸 PackML 상태 한 건.
 *
 * 키 = equipCd + seq(설비별 단조 순번), 내용 = packState·mode·stopReason·occurredAt.
 * 처리 순서: 설비·장치 확인 → seq 비교(뒤로 = REORDER + STALE, 건너뜀 = GAP) → 시각 비교(현재 상태보다 이르면 STALE) →
 * PackML 전이 검사(허용 밖 = DENIED + 알람, 앞에 GAP·첫 상태·snapshot 이면 수렴으로 받아들임) → CMS 상태 원장(분류 = 대응표) → 현재 상태 갱신.
 * 옛 상태는 원장을 되돌리지 않는다.
 */
@Component
@RequiredArgsConstructor
public class EquipStateRoute implements InboundRoute {

    public static final String ROUTE = "EQUIP_STATE";

    private final EdgeMapper mapper;
    private final EdgeEvents events;
    private final EquipEventService ledger;
    private final PolicyService policies;
    private final BizClock clock;

    @Override
    public String routeCd() {
        return ROUTE;
    }

    @Override
    public List<String> keyFields() {
        return List.of("equipCd", "seq");
    }

    @Override
    public List<String> contentFields() {
        return List.of("packState", "mode", "stopReason", "occurredAt");
    }

    @Override
    public String partField() {
        return "equipCd";
    }

    @Override
    public String orderField() {
        return "seq";
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public InboundResult handle(InboundMessage m) {
        String equipCd = EdgeSupport.upper(EdgeSupport.text(m.body(), "equipCd", 40));
        LinkRef link = equipCd == null || !EdgeSupport.EQUIP_CD.matcher(equipCd).matches() ? null : mapper.findLink(equipCd);
        if (link == null || !"Y".equals(link.getUseYn())) {
            throw new Reject("EQUIP");                    // 없는 설비·다른 회사 설비(RLS)·연동 꺼짐
        }
        if (!Objects.equals(link.getTerminalId(), m.sourceId())) {
            throw new Reject("NOT_MY_EQUIP");             // 같은 회사라도 그 설비를 맡지 않은 장치
        }
        String state = PackMl.state(EdgeSupport.text(m.body(), "packState", 20));
        if (state == null) {
            throw new Reject("STATE");
        }
        String mode = PackMl.mode(EdgeSupport.text(m.body(), "mode", 20));
        if (mode == null) {
            throw new Reject("MODE");
        }
        Instant at = m.occurredAt();
        if (at == null) {
            throw new Reject("OCCURRED_AT");
        }
        if (at.isAfter(clock.instant().plus(Duration.ofSeconds(policies.intValue("POP_FUTURE_TOLERANCE_SEC"))))) {
            throw new Reject("FUTURE");                   // 장치 시계가 앞섬 — 원문은 남아 있다(시계 차이 경고는 받을 때)
        }
        boolean snapshot = Boolean.TRUE.equals(EdgeSupport.bool(m.body(), "snapshot"));
        long seq = m.orderNo();
        long equipId = link.getEquipId();
        Long terminalId = m.sourceId();
        link = mapper.lockLink(equipId);

        Long last = link.getLastSeq();
        boolean gapBefore = false;
        if (last != null && seq <= last) {
            events.log(equipId, terminalId, "REORDER", null, null, "seq " + seq + " < " + last, m.inboundId(), at);
            events.log(equipId, terminalId, "STALE", null, null, state + " seq " + seq, m.inboundId(), at);
            return InboundResult.stale("OLDER_SEQ");
        }
        if (last != null && seq > last + 1) {
            events.log(equipId, terminalId, "GAP", last + 1, seq - 1, null, m.inboundId(), at);
            gapBefore = true;
        }
        mapper.updateSeq(equipId, seq);

        Instant cur = link.getPackAt() == null ? null : link.getPackAt().toInstant();
        if (cur != null && at.isBefore(cur)) {
            events.log(equipId, terminalId, "STALE", null, null, state + " at " + at + " < " + cur, m.inboundId(), at);
            return InboundResult.stale("OLDER_TIME");
        }
        String from = link.getPackState();
        if (!snapshot && !gapBefore && !PackMl.allowed(from, state)) {
            String what = from + " → " + state;
            events.log(equipId, terminalId, "DENIED", null, null, what, m.inboundId(), at);
            events.raise(equipId, EdgeEvents.PACKML_TRANSITION, what, at, terminalId, "in:" + m.inboundId());
            return InboundResult.rejected("TRANSITION");
        }
        String cat = PackMl.stateCat(state, mode);
        String applied = ledger.applyPackState(terminalId, TenantContext.currentActor(), equipId, cat, state, at, "in:" + m.inboundId(),
                snapshot);
        if ("LATE".equals(applied)) {
            events.log(equipId, terminalId, "STALE", null, null, state + " before open interval", m.inboundId(), at);
            return InboundResult.stale("LATE");
        }
        mapper.updatePack(equipId, state, mode, at);
        return InboundResult.done(applied);
    }
}
