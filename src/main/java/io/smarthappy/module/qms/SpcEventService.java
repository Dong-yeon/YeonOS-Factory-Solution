package io.smarthappy.module.qms;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import io.smarthappy.module.master.bom.BomService;
import io.smarthappy.module.production.link.LotHoldPort;
import io.smarthappy.module.qms.SpcDtos.LimitRow;
import io.smarthappy.module.qms.SpcDtos.MeasVal;
import io.smarthappy.module.qms.SpcDtos.SubgroupHead;
import io.smarthappy.module.qms.SpcEventDtos.ActForm;
import io.smarthappy.module.qms.SpcEventDtos.ActRow;
import io.smarthappy.module.qms.SpcEventDtos.EventDetail;
import io.smarthappy.module.qms.SpcEventDtos.EventPage;
import io.smarthappy.module.qms.SpcEventDtos.EventRow;
import io.smarthappy.module.qms.SpcEventDtos.EventSort;
import io.smarthappy.module.qms.SpcEventDtos.JudgedLine;
import io.smarthappy.module.qms.SpcEventMapper.EventSearch;
import io.smarthappy.platform.common.db.PgErrors;
import io.smarthappy.platform.common.query.PageQuery;
import io.smarthappy.platform.common.web.BusinessException;
import io.smarthappy.platform.common.web.FieldErrors;
import io.smarthappy.platform.common.web.HbErrorCode;
import io.smarthappy.platform.grid.ColumnSpec;
import io.smarthappy.platform.notify.NotificationService;
import io.smarthappy.platform.notify.NotifyMessage;
import io.smarthappy.platform.policy.Policies;
import io.smarthappy.platform.policy.PolicyService;
import io.smarthappy.platform.common.text.VisibleText;
import io.smarthappy.platform.security.LoginUser;

/**
 * SPC 위반 이벤트 원장 (qms-spc "위반") — docs/29_P3 19장, 사용자 결정 Q-n~Q-q.
 *
 * - 기록: 검사 판정 확정 트랜잭션 안(InspService.judge 끝) — 계량형 행마다 그 단위에 고정 관리한계(ACTIVE)가 있을 때만,
 *   그 행까지의 최근 부분군으로 관리도와 같은 {@link SpcCalc} 를 돌려 **새 점에 걸린 규칙만** 규칙마다 한 행(SV-1·2). 같은 단위 판정은
 *   advisory xact lock 으로 줄 세운다(SV-10). 새 이벤트는 qms-spc 를 볼 수 있고 알림을 켠 사람에게(기본 꺼짐, SV-8)
 * - 정정: 검사 무효 때 그 검사의 열린 이벤트에 VOID(SV-5). 고정 한계 무효는 이벤트를 건드리지 않는다(스냅샷)
 * - 처리: ACK·ACTION·CLOSE·VOID — 원장은 고치지 않고 처리 기록만 쌓는다(SV-4). 종결·무효 뒤엔 없음
 * - 자동 보류(SV-6, docs/29_P3 23장): 정책 QMS_SPC_AUTO_HOLD = LIMIT(WE1·R1)·ALL 이면 새 이벤트마다 그 검사 LOT 을 MES 계약으로 보류
 *   (출처 QMS_SPC, 참조 = 이벤트 id). 해제는 그 이벤트의 CLOSE·VOID(검사 무효의 자동 VOID 포함) 때만 — ACK·ACTION 은 안 풂.
 *   잠금 순서 = 검사 머리 → 이벤트(처리 트리거 advisory) → LOT(포트) — NCR 등록(검사 FOR SHARE → LOT)과 같은 방향(23.3)
 */
@Service
public class SpcEventService {

    /** WE4 = 연속 8점 — 새 점과 직전 7점 */
    static final int WINDOW = 8;
    static final Set<String> ACTS = Set.of("ACK", "ACTION", "CLOSE", "VOID");
    static final Set<String> LINKS = Set.of("NCR", "CONTAIN", "CAPA");
    static final Set<String> STATUSES = Set.of("OPEN", "ACKED", "CLOSED", "VOID");
    static final List<String> GRID_DEFAULT_SORT = List.of("occurredAt,desc");
    /** 자동 보류 LIMIT 범위 — 한계 밖 규칙만(런 규칙은 "추세를 보라" 신호라 LOT 을 세울 근거로 약하다, 23.1 AH-1) */
    static final Set<String> LIMIT_RULES = Set.of("WE1", "R1");

    static final List<ColumnSpec> GRID_COLUMNS = List.of(
            ColumnSpec.of("occurredAt", "qms.spcEvent.col.occurredAt", ColumnSpec.ColumnType.DATETIME, 140).withSort().withPriority(1),
            ColumnSpec.code("status", "qms.spcEvent.col.status", "QMS_SPC_EVENT_STATUS", 80).withSort().withPriority(1),
            ColumnSpec.code("ruleCd", "qms.spcEvent.col.rule", "QMS_SPC_RULE", 90).withSort().withPriority(1),
            ColumnSpec.text("itemCd", "qms.spc.col.item", 120).withSort().withPriority(1),
            ColumnSpec.text("charNo", "qms.spc.col.char", 70).withPriority(2),
            ColumnSpec.text("charNm", "qms.spc.col.charNm", 130).withFlex().withMinWidth(90).withPriority(3),
            ColumnSpec.code("inspType", "qms.spc.col.type", "INSP_TYPE", 90).withPriority(3),
            ColumnSpec.text("inspNo", "qms.spcEvent.col.inspNo", 120).withPriority(2),
            ColumnSpec.text("lotNo", "qms.spcEvent.col.lotNo", 120).withPriority(3),
            ColumnSpec.text("lastBy", "qms.spcEvent.col.lastBy", 90).withPriority(3));

    private final SpcEventMapper mapper;
    private final SpcMapper spcMapper;
    private final NotificationService notify;
    private final PolicyService policies;
    private final ObjectProvider<LotHoldPort> holdPort;
    private final OcapService ocaps;

    public SpcEventService(SpcEventMapper mapper, SpcMapper spcMapper, NotificationService notify, PolicyService policies,
            ObjectProvider<LotHoldPort> holdPort, OcapService ocaps) {
        this.mapper = mapper;
        this.spcMapper = spcMapper;
        this.notify = notify;
        this.policies = policies;
        this.holdPort = holdPort;
        this.ocaps = ocaps;
    }

    // ------------------------------------------------------------------ 기록 (검사 서비스가 부른다)

    /** 판정 확정 직후 같은 트랜잭션 — 만든 이벤트 수 */
    @Transactional(propagation = Propagation.MANDATORY)
    public int onJudged(long inspId, String by) {
        int made = 0;
        String autoHold = policies.value(Policies.QMS_SPC_AUTO_HOLD);
        for (JudgedLine l : mapper.findJudgedLines(inspId)) {
            LimitRow limit = spcMapper.findLiveLimit(l.getItemId(), l.getCharId(), l.getInspType(), l.getProcId());
            if (limit == null) {
                continue;   // Q-n: 고정 관리한계가 있는 단위만
            }
            mapper.lockUnit(l.getItemId() + ":" + l.getCharId() + ":" + l.getInspType() + ":" + (l.getProcId() == null ? 0 : l.getProcId()));
            if ("ATTRIBUTE".equals(l.getDataType())) {
                made += onJudgedP(l, limit, inspId, autoHold, by);
                continue;
            }
            List<SubgroupHead> heads = new ArrayList<>(mapper.findSubgroupsUpTo(l.getItemId(), l.getCharId(), l.getInspType(), l.getProcId(),
                    l.getInspLineId(), WINDOW));
            if (heads.isEmpty() || heads.get(0).getInspLineId() != l.getInspLineId()) {
                continue;   // 살아 있는 값이 없는 행
            }
            java.util.Collections.reverse(heads);
            Map<Long, List<BigDecimal>> vals = new LinkedHashMap<>();
            heads.forEach(h -> vals.put(h.getInspLineId(), new ArrayList<>()));
            for (MeasVal m : spcMapper.findValues(vals.keySet())) {
                vals.get(m.getInspLineId()).add(m.getValue());
            }
            SpcCalc.Limits fixed = new SpcCalc.Limits(limit.getCenter().doubleValue(), limit.getSigma().doubleValue());
            SpcCalc.Result r = SpcCalc.chart(new ArrayList<>(vals.values()), fixed, new TreeSet<>(List.of(limit.getRules().split(","))), null, null,
                    false);
            int last = heads.size() - 1;
            SpcCalc.Point p = r.points().stream().filter(x -> x.index() == last).findFirst().orElse(null);
            if (p == null) {
                continue;   // 부분군 크기 2~25 밖 — 관리도에도 없는 점
            }
            for (String rule : new TreeSet<>(p.rules())) {
                Long id = mapper.insertEvent(limit.getLimitId(), l, inspId, rule, p.n(), dec(p.mean()), dec(p.range()),
                        p.z() == null ? null : dec(p.z()), limit.getCenter(), limit.getSigma(), "XBAR_R", by);
                if (id != null) {
                    made++;
                    alert(id, l, rule);
                    if ("ALL".equals(autoHold) || ("LIMIT".equals(autoHold) && LIMIT_RULES.contains(rule))) {
                        hold(l.getLotId(), id, "SPC 위반 " + rule + " — 검사 " + l.getInspNo(), by);
                    }
                }
            }
        }
        return made;
    }

    /** p 관리도 단위(docs/29_P3 28장) — 고정 p̄ 로 그 검사 행까지 그려 마지막 점의 규칙(WE1·WE4)마다 이벤트. 자동 보류·알림은 X̄ 와 같다 */
    private int onJudgedP(JudgedLine l, LimitRow limit, long inspId, String autoHold, String by) {
        if (!"P".equals(limit.getChartType())) {
            return 0;
        }
        List<SubgroupHead> heads = new ArrayList<>(mapper.findPSubgroupsUpTo(l.getItemId(), l.getCharId(), l.getInspType(), l.getProcId(),
                l.getInspLineId(), WINDOW));
        if (heads.isEmpty() || heads.get(0).getInspLineId() != l.getInspLineId()) {
            return 0;   // 살아 있는 OK/NG 가 없는 행
        }
        java.util.Collections.reverse(heads);
        SpcCalc.PResult r = SpcCalc.pChart(heads.stream().map(h -> new int[] {h.getN(), h.getD()}).toList(), limit.getCenter().doubleValue(),
                new TreeSet<>(List.of(limit.getRules().split(","))));
        int last = heads.size() - 1;
        SpcCalc.PPoint p = r.points().stream().filter(x -> x.index() == last).findFirst().orElse(null);
        if (p == null) {
            return 0;
        }
        int made = 0;
        for (String rule : new TreeSet<>(p.rules())) {
            Long id = mapper.insertEvent(limit.getLimitId(), l, inspId, rule, p.n(), dec(p.p()), BigDecimal.valueOf(p.d()),
                    p.z() == null ? null : dec(p.z()), limit.getCenter(), limit.getSigma(), "P", by);
            if (id != null) {
                made++;
                alert(id, l, rule);
                if ("ALL".equals(autoHold) || ("LIMIT".equals(autoHold) && LIMIT_RULES.contains(rule))) {
                    hold(l.getLotId(), id, "SPC 위반 " + rule + " — 검사 " + l.getInspNo(), by);
                }
            }
        }
        return made;
    }

    /** 검사 무효 직후 같은 트랜잭션 — 그 검사의 열린 이벤트에 VOID(SV-5) */
    @Transactional(propagation = Propagation.MANDATORY)
    public int onVoided(long inspId, String inspNo, String by) {
        List<Long> closed = mapper.voidOpenOfInsp(inspId, "근거 검사 무효 " + inspNo, by);
        if (!closed.isEmpty()) {
            long lotId = mapper.findInspLot(inspId);
            for (Long id : closed) {
                release(lotId, id, "SPC 위반 무효 — 근거 검사 무효 " + inspNo, by);
            }
        }
        return closed.size();
    }

    /** 자동 보류(SV-6) — MES 가 없는 설치본이면 판정을 거부(조용히 넘기면 "보류됐다" 고 믿는다, AH-9) */
    private void hold(long lotId, long eventId, String reason, String by) {
        try {
            LotHoldPort.of(holdPort).hold(lotId, LotHoldPort.QMS_SPC, eventId, NcrService.cut(reason), by);
        } catch (IllegalStateException ex) {
            throw new BusinessException(HbErrorCode.QMS_SPC_HOLD_UNAVAILABLE);
        }
    }

    /** 이 이벤트 근거만 해제 — 다른 근거가 남으면 LOT 은 계속 보류. MES 가 없으면 잡은 보류도 없으므로 할 일 없음 */
    private void release(long lotId, long eventId, String reason, String by) {
        LotHoldPort port = LotHoldPort.of(holdPort);
        if (port != LotHoldPort.NONE) {
            port.release(lotId, LotHoldPort.QMS_SPC, eventId, NcrService.cut(reason), by);
        }
    }

    /** qms-spc 를 볼 수 있고 이 알림을 켠 사람에게(같은 이벤트는 한 번) */
    private void alert(long eventId, JudgedLine l, String rule) {
        for (Long uid : notify.searchers(SpcService.PROG)) {
            notify.send(NotifyMessage.of(QmsNotifyEvents.QMS_SPC_EVENT, uid, String.valueOf(eventId), "notify.qms.spcEvent", l.getInspNo(), rule)
                    .withLink(SpcService.PROG, Map.of()));
        }
    }

    // ------------------------------------------------------------------ 조회

    @Transactional(readOnly = true)
    public EventPage grid(String keyword, String status, Long itemId, Long charId, String inspType, Long procId, Integer page, Integer size,
            List<String> sort, Boolean total) {
        PageQuery pq = PageQuery.parse(page, size, sort, total, EventSort.values(), policies.intValue(Policies.GRID_PAGE_SIZE_MAX),
                GRID_DEFAULT_SORT);
        EventSearch q = search(keyword, status, itemId, charId, inspType, procId,
                pq.getSorts().stream().anyMatch(o -> EventSort.ITEM_CD.name().equals(o.getKey())));
        return new EventPage(mapper.findGrid(q, pq), pq.isWithTotal() ? mapper.countGrid(q) : null);
    }

    public List<ColumnSpec> gridColumns() {
        return GRID_COLUMNS;
    }

    @Transactional(readOnly = true)
    public EventDetail detail(long id) {
        EventRow e = mapper.findById(id);
        if (e == null) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        boolean held = LotHoldPort.of(holdPort).holds(e.getLotId()).stream()
                .anyMatch(h -> LotHoldPort.QMS_SPC.equals(h.source()) && h.refId() != null && h.refId() == id);
        List<ActRow> acts = mapper.findActs(id);
        Map<Long, List<OcapDtos.PickRow>> picks = ocaps.picksOfEvent(id);
        acts.forEach(a -> a.setOcaps(picks.getOrDefault(a.getActId(), List.of())));
        return new EventDetail(e, acts, held);
    }

    /** 조치 폼의 OCAP 고르기 목록 — 그 위반 규칙 + 공통(사용 중만) */
    @Transactional(readOnly = true)
    public List<OcapDtos.OcapRow> ocapOptions(long id) {
        EventRow e = mapper.findById(id);
        if (e == null) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        return ocaps.options(e.getRuleCd());
    }

    // ------------------------------------------------------------------ 처리

    @Transactional
    public void act(long id, ActForm in) {
        LoginUser me = SamplingService.me();
        if (in == null) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        FieldErrors e = FieldErrors.create();
        String type = VisibleText.isBlank(in.actType()) ? null : in.actType().strip().toUpperCase(Locale.ROOT);
        if (type == null) {
            e.add("actType", "REQUIRED");
        } else if (!ACTS.contains(type)) {
            e.add("actType", "CODE");
        }
        String note = type != null && !"ACK".equals(type) ? VisibleText.reason(in.note(), "note", 1, 1000, e)
                : VisibleText.optional(in.note(), "note", 1000, e);
        String linkType = VisibleText.isBlank(in.linkType()) ? null : in.linkType().strip().toUpperCase(Locale.ROOT);
        String linkNo = VisibleText.optional(in.linkNo(), "linkNo", 30, e);
        if ((linkType == null) != (linkNo == null)) {
            e.add(linkType == null ? "linkType" : "linkNo", "REQUIRED");
        } else if (linkType != null && (!LINKS.contains(linkType) || !("ACTION".equals(type) || "CLOSE".equals(type)))) {
            e.add("linkType", "CODE");
        }
        boolean picking = in.ocapIds() != null && !in.ocapIds().isEmpty();
        if (picking && type != null && !"ACTION".equals(type)) {
            e.add("ocapIds", "CODE");   // OCAP 항목은 조치(ACTION)에서만(26장 OC-4)
        }
        e.throwIfAny();
        EventRow ev = mapper.findById(id);
        if (ev == null) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        if ("CLOSED".equals(ev.getStatus()) || "VOID".equals(ev.getStatus())) {
            throw new BusinessException(HbErrorCode.QMS_SPC_EVENT_STATE);
        }
        // 고를 항목 먼저 확인·잠금(FOR SHARE) → 처리 행 → 사본. 하나라도 어긋나면 처리 행도 안 남음(같은 트랜잭션)
        List<OcapDtos.OcapRow> picked = picking ? ocaps.lockAndCheck(in.ocapIds(), ev.getRuleCd()) : List.of();
        long actId;
        try {
            actId = mapper.insertAct(id, type, note, linkType, linkNo == null ? null : linkNo.toUpperCase(Locale.ROOT), me.loginId());
        } catch (DataAccessException x) {
            // 위 확인 뒤(OCAP 항목·이벤트 잠금을 기다리는 사이) 다른 사람이 종결·무효 — 가드 거부도 같은 상태 오류로(QA O-a)
            if (PgErrors.constraint(x).filter("ck_qms_spc_event_act_done"::equals).isPresent()) {
                throw new BusinessException(HbErrorCode.QMS_SPC_EVENT_STATE);
            }
            throw x;
        }
        ocaps.pick(actId, picked, me.loginId());
        // 해제는 종결·무효 때만(Q-z) — 처리 행 INSERT(이벤트 advisory 잠금) 뒤 LOT 잠금(포트)
        if ("CLOSE".equals(type) || "VOID".equals(type)) {
            release(ev.getLotId(), id, ("CLOSE".equals(type) ? "SPC 위반 종결 " + ev.getRuleCd() : "SPC 위반 무효") + " — " + note, me.loginId());
        }
    }

    private EventSearch search(String keyword, String status, Long itemId, Long charId, String inspType, Long procId, boolean joinItem) {
        String kw = keyword == null || keyword.isBlank() ? null : "%" + BomService.likeEscape(keyword.strip()) + "%";
        List<String> statuses = null;
        if (status != null && !status.isBlank()) {
            String s = status.strip();
            statuses = "OPEN_ALL".equals(s) ? List.of("OPEN", "ACKED") : STATUSES.contains(s) ? List.of(s) : List.of("-");
        }
        boolean unit = itemId != null && charId != null && inspType != null;
        return new EventSearch(kw, statuses, itemId, charId, inspType, procId, unit, joinItem);
    }

    private static BigDecimal dec(double v) {
        return BigDecimal.valueOf(v).setScale(10, RoundingMode.HALF_UP);
    }
}
