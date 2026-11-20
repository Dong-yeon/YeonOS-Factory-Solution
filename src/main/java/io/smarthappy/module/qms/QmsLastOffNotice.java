package io.smarthappy.module.qms;

import java.util.Comparator;
import java.util.List;

import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import io.smarthappy.module.production.link.CloseNotice;
import io.smarthappy.module.qms.InspDtos.OffRow;
import io.smarthappy.module.qms.InspDtos.OffSide;
import io.smarthappy.platform.time.BizClock;

/**
 * 공정 완료판정 때 종품 경고 (docs/29_P3 27.3 — MES {@link CloseNotice} 구현 빈, 막지 않음).
 *
 * - 대상: 그 품목·공정에 오늘 유효한 종품(LAST_OFF) 검사계획이 있을 때만 — 계획이 없으면 종품을 요구하지 않는 공정이라 경고하지 않는다
 * - 이 공정 산출 LOT 들의 그 공정 종품 상태(OffInspService.lastOff): 없음·판정 전 → QMS_LAST_PENDING, 가장 최근 판정 NG → QMS_LAST_NG
 * - MES 트랜잭션 안: 읽기만·잠금 없음, MES 표는 계약(CloseContext)으로만
 */
@Component
@Order(100)
public class QmsLastOffNotice implements CloseNotice {

    static final String LAST_PENDING = "QMS_LAST_PENDING";
    static final String LAST_NG = "QMS_LAST_NG";

    private final OffInspService offs;
    private final QmsGateMapper gateMapper;
    private final InspPlanMapper planMapper;
    private final BizClock clock;

    public QmsLastOffNotice(OffInspService offs, QmsGateMapper gateMapper, InspPlanMapper planMapper, BizClock clock) {
        this.offs = offs;
        this.gateMapper = gateMapper;
        this.planMapper = planMapper;
        this.clock = clock;
    }

    @Override
    public List<Notice> notices(CloseContext ctx) {
        if (!hasPlan(ctx)) {
            return List.of();
        }
        OffSide side = offs.lastOff(ctx.procId(), ctx.outputLotIds());
        switch (side.state()) {
            case "OK":
                return List.of();
            case "NG": {
                OffRow r = side.rows().stream().filter(x -> "JUDGED".equals(x.getStatus()) && x.getJudgedAt() != null)
                        .max(Comparator.comparing(OffRow::getJudgedAt).thenComparing(OffRow::getInspId)).orElse(side.rows().get(0));
                return List.of(new Notice(LAST_NG, "qms.close.lastNg", List.of(r.getInspNo(), r.getLotNo()), "INSP", r.getInspId()));
            }
            case "PENDING": {
                OffRow r = side.rows().get(0);
                return List.of(new Notice(LAST_PENDING, "qms.close.lastPending", List.of(r.getInspNo()), "INSP", r.getInspId()));
            }
            default:
                return List.of(new Notice(LAST_PENDING, "qms.close.lastNone", List.of(), null, null));
        }
    }

    /** 오늘 유효한 종품 검사계획(품목 → 품목 분류 순, 공정 일치)이 있는가 — 착수 게이트 Q9-2 와 같은 고르기 */
    boolean hasPlan(CloseContext ctx) {
        Long classId = gateMapper.findItemClassId(ctx.itemId());
        List<InspPlanResolver.Candidate> cands = planMapper.findCandidates(OffInspService.LAST_OFF, ctx.itemId(), classId).stream()
                .map(c -> new InspPlanResolver.Candidate(c.getInspPlanId(), c.getInspType(), c.getItemId(), c.getItemClassId(), c.getProcId(),
                        c.getAltNo(), c.getRev(), c.getStatus(), c.getValidFrom(), c.getValidTo()))
                .toList();
        return new InspPlanResolver(cands).effective(new InspPlanResolver.ItemRef(ctx.itemId(), classId), OffInspService.LAST_OFF, ctx.procId(),
                clock.today()).isPresent();
    }
}
