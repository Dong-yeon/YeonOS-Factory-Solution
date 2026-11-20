package io.smarthappy.module.pop;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.smarthappy.module.master.workforce.QualGate;
import io.smarthappy.module.master.workforce.WorkDayResolver;
import io.smarthappy.module.master.workforce.WorkDayResolver.WorkDay;
import io.smarthappy.module.master.workforce.WorkforceDtos.GateMissing;
import io.smarthappy.module.master.workforce.WorkforceDtos.GateResult;
import io.smarthappy.module.pop.PopDtos.PopApproval;
import io.smarthappy.module.pop.PopDtos.PopWorker;
import io.smarthappy.module.pop.PopWorkDtos.CorrectForm;
import io.smarthappy.module.pop.PopWorkDtos.DefectLine;
import io.smarthappy.module.pop.PopWorkDtos.DefectLineRow;
import io.smarthappy.module.pop.PopWorkDtos.EventForm;
import io.smarthappy.module.pop.PopWorkDtos.EventReply;
import io.smarthappy.module.pop.PopWorkDtos.EventRow;
import io.smarthappy.module.pop.PopWorkDtos.GateLine;
import io.smarthappy.module.pop.PopWorkDtos.InputLine;
import io.smarthappy.module.pop.PopWorkDtos.LotScan;
import io.smarthappy.module.pop.PopWorkDtos.NewEvent;
import io.smarthappy.module.pop.PopWorkDtos.NewParam;
import io.smarthappy.module.pop.PopWorkDtos.NewResult;
import io.smarthappy.module.pop.PopWorkDtos.ParamActualRow;
import io.smarthappy.module.pop.PopWorkDtos.ParamForm;
import io.smarthappy.module.pop.PopWorkDtos.ParamReply;
import io.smarthappy.module.pop.PopWorkDtos.ParamRow;
import io.smarthappy.module.pop.PopWorkDtos.ProcRow;
import io.smarthappy.module.pop.PopWorkDtos.CountSuggest;
import io.smarthappy.module.pop.PopWorkDtos.ProcView;
import io.smarthappy.module.pop.PopWorkDtos.ResultForm;
import io.smarthappy.module.pop.PopWorkDtos.ResultReply;
import io.smarthappy.module.pop.PopWorkDtos.ResultRow;
import io.smarthappy.module.pop.PopWorkDtos.StartGateView;
import io.smarthappy.module.production.LotDtos.LockedLot;
import io.smarthappy.module.production.LotDtos.LotRow;
import io.smarthappy.module.production.LotHoldService;
import io.smarthappy.module.production.link.StartGate;
import io.smarthappy.module.production.link.StartGate.StartContext;
import io.smarthappy.module.production.LotMapper;
import io.smarthappy.module.production.LotService;
import io.smarthappy.module.sales.OrderService;
import io.smarthappy.platform.code.CodeService;
import io.smarthappy.platform.common.db.PgErrors;
import io.smarthappy.platform.common.text.VisibleText;
import io.smarthappy.platform.common.web.BusinessException;
import io.smarthappy.platform.common.web.FieldErrors;
import io.smarthappy.platform.common.web.HbErrorCode;
import io.smarthappy.platform.policy.PolicyService;
import io.smarthappy.platform.time.BizClock;
import lombok.RequiredArgsConstructor;

/**
 * POP 착수·실적·투입 (P2 5b, docs/25 4.7) — 단말 작업장 안의 공정만, 지금 작업자 필수.
 *
 * 모든 저장은 단말이 만든 client_req_id 로 멱등: 같은 id 가 다시 오면 처음 저장한 행으로 같은 응답(replayed = true).
 * 동시에 두 번 오면 unique 위반으로 트랜잭션이 깨지므로, 호출한 쪽(컨트롤러)이 새 트랜잭션에서 replay* 로 다시 읽는다.
 * recorded_by = 단말(t{id}:{코드}), performed_by = 작업자 — 공용 단말 대리 입력이 원장에 드러난다.
 */
@Service
@RequiredArgsConstructor
public class PopWorkService {

    static final Pattern REQ_ID = Pattern.compile("^[A-Za-z0-9_-]{8,64}$");
    static final String FUTURE_POLICY = "POP_FUTURE_TOLERANCE_SEC";
    static final String RESULT_APPROVAL_POLICY = "POP_APPROVAL_RESULT_CORRECT";
    static final String PARAM_APPROVAL_POLICY = "POP_APPROVAL_PARAM_CORRECT";
    static final int MAX_LINES = 50;
    private static final Pattern PARAM_VALUE = Pattern.compile("^-?\\d{1,12}(\\.\\d{1,6})?$");
    private static final List<String> KINDS = List.of("SCRAP", "REWORK", "REPAIR");

    private final PopWorkMapper mapper;
    private final LotService lots;
    private final LotMapper lotMapper;
    private final LotHoldService holds;
    private final OrderService orders;
    private final WorkDayResolver workDays;
    private final QualGate gate;
    private final StartGates startGates;
    private final PopDispatch dispatch;
    private final PopInspService insp;
    private final MessageSource messages;
    private final PolicyService policies;
    private final CodeService codes;
    private final BizClock clock;

    // ------------------------------------------------------------------ 보기

    @Transactional(readOnly = true)
    public ProcView view(TerminalPrincipal t, PopWorker w, long woProcId) {
        PopWorker me = require(w);
        ProcRow p = proc(t, woProcId, false);
        Instant now = clock.instant();
        WorkDay wd = workDays.resolve(t.plantCd(), now);
        // 착수 게이트 미리보기 — 공정 화면에서만(내 할 일 목록에는 부르지 않음, docs/29_P3 12.9 G-f)
        StartGateView preview = null;
        if ("WAIT".equals(p.getStatus()) || "READY".equals(p.getStatus())) {
            StartGates.Outcome o = startGates.check(startContext(t, me, p, wd, now, true));
            preview = new StartGateView(o.mode(), o.hits().stream().map(h -> new GateLine(h.source(), h.code(), h.message())).toList());
        }
        return new ProcView(p, gate.check(t.plantCd(), p.getProcId(), me.workerId(), wd.workDay()), mapper.params(woProcId),
                mapper.bom(p.getWoId(), p.getOpSeq()), mapper.dayResults(woProcId, wd.workDay()),
                mapper.dayGood(woProcId, wd.workDay()), mapper.defectOptions(),
                codes.codes("DOWNTIME_REASON", LocaleContextHolder.getLocale()), preview, countSuggest(p, now),
                dispatch.slotOf(t, woProcId), insp.on());
    }

    /** 수집 COUNT 제안 — 진행(RUN·PAUSE) 공정, 설비 배정, 그 설비에 진행 중 공정이 이것 하나일 때만 */
    private CountSuggest countSuggest(ProcRow p, Instant now) {
        if (p.getEquipId() == null || !("RUN".equals(p.getStatus()) || "PAUSE".equals(p.getStatus()))
                || mapper.runningOnEquip(p.getEquipId()) != 1) {
            return null;
        }
        CountSuggest c = mapper.countSuggest(p.getWoProcId(), p.getEquipId(), now);
        return c == null || c.getSince() == null ? null : c;
    }

    /** 투입 스캔 확인 — LOT 번호 → 품목·잔량·보류 */
    @Transactional(readOnly = true)
    public LotScan lot(PopWorker w, String lotNo) {
        require(w);
        LotRow l = lotNo == null || lotNo.isBlank() ? null : lotMapper.findLotByNo(lotNo.strip());
        if (l == null) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        return new LotScan(l.getLotNo(), l.getItemCd(), l.getItemNm(), l.getBalance(), l.getUnitCd(), l.getHoldYn());
    }

    // ------------------------------------------------------------------ 착수·멈춤·재개·종료

    @Transactional
    public EventReply event(TerminalPrincipal t, PopWorker w, long woProcId, String eventCd, EventForm in) {
        return event(t, w, woProcId, eventCd, in, null);
    }

    /**
     * 지난 착수·멈춤·종료를 그 시각으로 — 데모 이력 시더(MesDemoFlow) 전용, API 없음. 전이·게이트·멱등은 {@link #event} 와 같고 발생 시각만 받는다
     * (기록 시각 recorded_at 은 지금). 지금보다 뒤면 거부
     */
    @Transactional
    public EventReply eventAt(TerminalPrincipal t, PopWorker w, long woProcId, String eventCd, EventForm in, Instant at) {
        if (at == null || at.isAfter(clock.instant())) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        return event(t, w, woProcId, eventCd, in, at);
    }

    private EventReply event(TerminalPrincipal t, PopWorker w, long woProcId, String eventCd, EventForm in, Instant at) {
        PopWorker me = require(w);
        String req = reqId(in == null ? null : in.clientReqId());
        EventRow done = mapper.findEventByReq(req);
        if (done != null) {
            return replayEvent(done, woProcId);
        }
        ProcRow p = proc(t, woProcId, true);
        String from = p.getStatus();
        String to;
        switch (eventCd) {
            case "START" -> to = from.equals("WAIT") || from.equals("READY") ? "RUN" : null;
            case "PAUSE" -> to = from.equals("RUN") ? "PAUSE" : null;
            case "RESUME" -> to = from.equals("PAUSE") ? "RUN" : null;
            case "END" -> to = from.equals("RUN") || from.equals("PAUSE") ? "READY" : null;
            default -> throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        if (to == null) {
            throw OrderService.fieldError("status", "STATE");
        }
        Instant now = at == null ? clock.instant() : at;
        WorkDay wd = workDays.resolve(t.plantCd(), now);
        String reason = null;
        String gateMode = null;
        String gateDetail = null;
        if (eventCd.equals("PAUSE")) {
            if (in == null || VisibleText.isBlank(in.reasonCd())) {   // 보이지 않는 글자만인 코드도 비었음(QA R4-a)
                throw OrderService.fieldError("reasonCd", "REQUIRED");
            }
            reason = in.reasonCd().strip().toUpperCase(Locale.ROOT);
        }
        if (eventCd.equals("START")) {
            GateResult g = gate.check(t.plantCd(), p.getProcId(), me.workerId(), wd.workDay());
            String names = String.join(", ", g.missing().stream().map(GateMissing::qualNm).toList());
            boolean ack = in != null && Boolean.TRUE.equals(in.ack());
            // 착수 게이트 빈들(QMS 등) — 자격 게이트 다음·이벤트 기록 전, 가장 나쁜 mode(docs/25 4.21)
            StartGates.Outcome o = startGates.check(startContext(t, me, p, wd, now, false));
            String mode = StartGates.worst(g.mode(), o.mode());
            if (o.hits().isEmpty() && StartGate.OK.equals(o.mode())) {
                // 자격만 걸림 — 지금 오류 그대로
                if ("BLOCK".equals(g.mode())) {
                    throw new BusinessException(HbErrorCode.QUAL_BLOCKED, names);
                }
                if ("WARN".equals(g.mode()) && !ack) {
                    throw new BusinessException(HbErrorCode.QUAL_WARN_ACK, names);
                }
            } else {
                String text = gateText(g, names, o);
                if (StartGate.BLOCK.equals(mode)) {
                    throw new BusinessException(HbErrorCode.START_GATE_BLOCKED, text);
                }
                if (StartGate.WARN.equals(mode) && !ack) {
                    throw new BusinessException(HbErrorCode.START_GATE_WARN_ACK, text);
                }
            }
            gateMode = StartGate.WARN.equals(mode) ? "WARN" : "OK";
            gateDetail = "WARN".equals(gateMode) ? gateDetail(g, o) : null;
        }
        long id;
        try {
            id = mapper.insertEvent(new NewEvent(p.getWoId(), woProcId, eventCd, reason, gateMode, p.getEquipId(), t.terminalId(),
                    wd.workDay(), wd.shiftCd(), req, now, "KEY", me.workerId(), gateDetail), t.actor());
        } catch (DataIntegrityViolationException e) {
            if ("fk_prd_wo_proc_event_reason".equals(PgErrors.constraint(e).orElse(""))) {
                throw OrderService.fieldError("reasonCd", "NO_SUCH_CODE");
            }
            throw e;
        }
        if (mapper.updateProcStatus(woProcId, from, to, t.actor()) == 0) {
            throw OrderService.fieldError("status", "STATE");
        }
        if (eventCd.equals("START")) {
            mapper.woInProgress(p.getWoId(), t.actor());
        }
        return new EventReply(id, eventCd, to, gateMode, false);
    }

    private StartContext startContext(TerminalPrincipal t, PopWorker me, ProcRow p, WorkDay wd, Instant now, boolean preview) {
        return new StartContext(t.plantCd(), p.getWoId(), p.getWoProcId(), p.getProcId(), p.getItemId(), p.getEquipId(), me.workerId(),
                t.terminalId(), wd.workDay(), now, preview);
    }

    /** 확인·거부 문구 — 자격 부족 + 게이트 이유(요청 로캘) */
    private String gateText(GateResult g, String names, StartGates.Outcome o) {
        List<String> parts = new ArrayList<>();
        if (!"OK".equals(g.mode()) && !names.isEmpty()) {
            parts.add(messages.getMessage("pop.gate.qual", new Object[] {names}, LocaleContextHolder.getLocale()));
        }
        o.hits().forEach(h -> parts.add(h.message()));
        return parts.isEmpty() ? o.mode() : String.join(" / ", parts);
    }

    /** 이벤트 기록용 — 출처:코드(참조) 나열, 500자. 문구가 아니라 코드라 로캘·문구가 바뀌어도 근거가 남는다 */
    private static String gateDetail(GateResult g, StartGates.Outcome o) {
        List<String> parts = new ArrayList<>();
        if (!"OK".equals(g.mode())) {
            g.missing().forEach(m -> parts.add("QUAL:" + m.qualCd()));
        }
        o.hits().forEach(h -> parts.add(h.source() + ":" + h.code() + (h.refId() == null ? "" : "(" + h.refType() + " " + h.refId() + ")")));
        String s = String.join(", ", parts);
        return s.isEmpty() ? null : s.length() > 500 ? s.substring(0, 500) : s;
    }

    /** 같은 요청 id 가 이미 저장됨(동시 도착 포함) — 처음 응답 그대로 */
    @Transactional(readOnly = true)
    public EventReply replayEvent(String clientReqId, long woProcId) {
        EventRow done = mapper.findEventByReq(clientReqId);
        if (done == null) {
            throw new BusinessException(HbErrorCode.CONFLICT);
        }
        return replayEvent(done, woProcId);
    }

    private EventReply replayEvent(EventRow done, long woProcId) {
        if (done.getWoProcId() != woProcId) {
            throw new BusinessException(HbErrorCode.DUPLICATE_REQUEST);
        }
        return new EventReply(done.getEventId(), done.getEventCd(), mapper.procStatus(woProcId), done.getGateMode(), true);
    }

    // ------------------------------------------------------------------ 실적 확정

    @Transactional
    public ResultReply result(TerminalPrincipal t, PopWorker w, long woProcId, ResultForm in) {
        return result(t, w, woProcId, in, false);
    }

    /**
     * 지난 실적의 이관(옮겨 적기) — 데모 이력 시더(MesDemoFlow) 전용, API 없음. 검증·LOT·계보·불량 합은 {@link #result} 와 같고 다른 점은 셋:
     * 발생 시각 필수(지금보다 뒤 거부), 지시 발행 전 시각도 받음(발행 시각은 발행 트랜잭션 표식이라 늘 지금 — V033 스냅샷 트리거),
     * entry_method = IMPORT(실적·투입 계보)
     */
    @Transactional
    public ResultReply resultImport(TerminalPrincipal t, PopWorker w, long woProcId, ResultForm in) {
        return result(t, w, woProcId, in, true);
    }

    private ResultReply result(TerminalPrincipal t, PopWorker w, long woProcId, ResultForm in, boolean imported) {
        PopWorker me = require(w);
        if (in == null) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        String req = reqId(in.clientReqId());
        ResultRow done = mapper.findResultByReq(req);
        if (done != null) {
            return replayResult(done, woProcId);
        }
        ProcRow p = proc(t, woProcId, true);
        if (!p.getStatus().equals("RUN") && !p.getStatus().equals("PAUSE")) {
            throw OrderService.fieldError("status", "STATE");
        }
        Map<String, Integer> scales = orders.unitScales();
        int scale = OrderService.scaleOf(scales, p.getUnitCd());
        FieldErrors e = FieldErrors.create();
        BigDecimal good = zero(OrderService.qty(in.goodQty(), "goodQty", scale, false, e));
        BigDecimal scrap = zero(OrderService.qty(in.scrapQty(), "scrapQty", scale, false, e));
        BigDecimal rework = zero(OrderService.qty(in.reworkQty(), "reworkQty", scale, false, e));
        BigDecimal repair = zero(OrderService.qty(in.repairQty(), "repairQty", scale, false, e));
        Instant at = occurredAt(in.occurredAt(), p, e, imported);
        e.throwIfAny();
        if (good.add(scrap).add(rework).add(repair).signum() == 0) {
            throw OrderService.fieldError("goodQty", "REQUIRED");
        }
        // 불량 내역: 종류별 합 = 그 수량 (닫을 때 필수)
        List<DefectLine> defects = in.defects() == null ? List.of() : in.defects();
        List<InputLine> inputs = in.inputs() == null ? List.of() : in.inputs();
        if (defects.size() > MAX_LINES || inputs.size() > MAX_LINES) {
            throw new BusinessException(HbErrorCode.TOO_MANY_ROWS, Math.max(defects.size(), inputs.size()), MAX_LINES);
        }
        Map<String, BigDecimal> sums = new HashMap<>();
        List<Object[]> defectRows = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < defects.size(); i++) {
            DefectLine d = defects.get(i);
            FieldErrors.RowScope r = e.row(String.valueOf(i));
            String kind = d == null || d.kind() == null ? "" : d.kind().strip().toUpperCase(Locale.ROOT);
            if (!KINDS.contains(kind)) {
                r.add("kind", "FORMAT");
                continue;
            }
            String cd = d.defectCd() == null ? "" : d.defectCd().strip().toUpperCase(Locale.ROOT);
            Long defectId = cd.isEmpty() ? null : mapper.defectIdByCd(cd);
            if (defectId == null) {
                r.add("defectCd", cd.isEmpty() ? "REQUIRED" : "NO_SUCH_CODE");
                continue;
            }
            if (!seen.add(kind + ":" + defectId)) {
                r.add("defectCd", "DUPLICATE");
                continue;
            }
            FieldErrors one = FieldErrors.create();
            BigDecimal q = OrderService.qty(d.qty(), "qty", scale, true, one);
            if (q == null) {
                r.add("qty", one.isEmpty() ? "REQUIRED" : one.list().get(0).code());
                continue;
            }
            sums.merge(kind, q, BigDecimal::add);
            defectRows.add(new Object[] {kind, defectId, q});
        }
        e.throwIfAny();
        Map<String, BigDecimal> need = Map.of("SCRAP", scrap, "REWORK", rework, "REPAIR", repair);
        for (String k : KINDS) {
            if (need.get(k).compareTo(sums.getOrDefault(k, BigDecimal.ZERO)) != 0) {
                e.add(k.toLowerCase(Locale.ROOT) + "Qty", "DEFECT_SUM");
            }
        }
        e.throwIfAny();
        // 투입: 자재 LOT 번호·수량(그 LOT 단위)
        List<Object[]> inputRows = new ArrayList<>();
        Set<Long> inputLots = new HashSet<>();
        for (int i = 0; i < inputs.size(); i++) {
            InputLine l = inputs.get(i);
            FieldErrors.RowScope r = e.row(String.valueOf(i));
            String no = l == null || l.lotNo() == null ? "" : l.lotNo().strip();
            LotRow lot = no.isEmpty() ? null : lotMapper.findLotByNo(no);
            if (lot == null) {
                r.add("lotNo", no.isEmpty() ? "REQUIRED" : "NO_SUCH_CODE");
                continue;
            }
            if (!inputLots.add(lot.getLotId())) {
                r.add("lotNo", "DUPLICATE");
                continue;
            }
            FieldErrors one = FieldErrors.create();
            BigDecimal q = OrderService.qty(l.qty(), "qty", OrderService.scaleOf(scales, lot.getUnitCd()), true, one);
            if (q == null) {
                r.add("qty", one.isEmpty() ? "REQUIRED" : one.list().get(0).code());
                continue;
            }
            inputRows.add(new Object[] {lot.getLotId(), q, i, lot.getItemId()});
        }
        e.throwIfAny();
        if (!inputRows.isEmpty() && good.signum() == 0) {
            throw OrderService.fieldError("goodQty", "INPUT_NEEDS_GOOD");
        }
        WorkDay wd = workDays.resolve(t.plantCd(), at);
        // 잠금 순서 MES → ECO(3c): 투입 자재 LOT 을 먼저 잠그고 → 산출 LOT → 설계 변경 표시(ECO 행) → 투입 계보(change_id = 표시)
        lots.lockForConsume(inputRows.stream().map(r -> (Long) r[0]).toList());
        // 재고 칸 순서(QB-1): 투입은 (품목, LOT) 순 — WMS 차감이 재고 칸을 잡는다. 비관리 품목은 LOT 이 달라도 같은 칸이라
        // 요청 행 순서대로면 다른 실적·정정과 칸을 엇갈려 잡아 교착(40P01)이 난다. 칸 오류는 r[2](요청 행 번호)로 그대로
        inputRows.sort(java.util.Comparator.comparingLong((Object[] r) -> (Long) r[3]).thenComparingLong(r -> (Long) r[0]));
        Long lotId = good.signum() > 0 ? lots.createProduced(p.getWoId(), good, at, t.actor()) : null;
        Long changeId = lotId == null ? null : lots.mark(lotId, p.getWoId(), p.getEquipId(), at);
        for (Object[] r : inputRows) {
            try {
                lots.consume((Long) r[0], lotId, (BigDecimal) r[1], p.getWoId(), woProcId, p.getEquipId(), me.workerId(),
                        imported ? "IMPORT" : "SCAN", at,
                        changeId, t.actor());
            } catch (BusinessException ex) {
                // 칸 오류 없는 거부(재고 부족·마감 409 등 — WMS B5 훅)는 원인 그대로(docs/25 4.20 ②)
                if (ex.fieldErrors().isEmpty()) {
                    throw ex;
                }
                // 잔량 초과(qty)·보류·빈 LOT(lotId) → 그 투입 행의 칸 오류로
                FieldErrors.FieldError first = ex.fieldErrors().get(0);
                FieldErrors one = FieldErrors.create();
                one.row(String.valueOf(r[2])).add("qty".equals(first.field()) ? "qty" : "lotNo", first.code());
                throw new BusinessException(HbErrorCode.INVALID_VALUE).withFieldErrors(one.list());
            }
        }
        long resultId = mapper.insertResult(new NewResult(p.getWoId(), woProcId, lotId, p.getEquipId(), t.terminalId(), good, scrap,
                rework, repair, p.getUnitCd(), wd.workDay(), wd.shiftCd(), req, null, at,
                imported ? "IMPORT" : Boolean.TRUE.equals(in.fromCount()) ? "AUTO" : "KEY", me.workerId(), null), t.actor());
        for (Object[] d : defectRows) {
            mapper.insertDefect(resultId, (String) d[0], (Long) d[1], (BigDecimal) d[2]);
        }
        return reply(mapper.findResult(resultId), p.getPlanQty(), false);
    }

    @Transactional(readOnly = true)
    public ResultReply replayResult(String clientReqId, long woProcId) {
        ResultRow done = mapper.findResultByReq(clientReqId);
        if (done == null) {
            throw new BusinessException(HbErrorCode.CONFLICT);
        }
        return replayResult(done, woProcId);
    }

    private ResultReply replayResult(ResultRow done, long woProcId) {
        if (done.getWoProcId() != woProcId) {
            throw new BusinessException(HbErrorCode.DUPLICATE_REQUEST);
        }
        return reply(done, mapper.planQty(done.getWoId()), true);
    }

    private ResultReply reply(ResultRow r, String planQty, boolean replayed) {
        return new ResultReply(r.getResultId(), r.getLotNo(), mapper.dayGood(r.getWoProcId(), r.getWorkDay()), planQty, r.getUnitCd(),
                mapper.lastDefect(r.getWoProcId()), replayed);
    }

    // ------------------------------------------------------------------ 실적 정정(역분개)

    /**
     * 역분개: 수량 반대 + 불량 내역 반대 + 이 실적의 투입 계보 역분개 + 산출 LOT 보류.
     * 산출 LOT 이 이미 다른 데로 나갔으면 거부(뒤 공정을 먼저 정정)
     */
    @Transactional
    public long correct(TerminalPrincipal t, PopWorker w, long resultId, CorrectForm in) {
        return correct(t, w, resultId, in, null);
    }

    /** 실적 정정 — 공장 정책 POP_APPROVAL_RESULT_CORRECT 가 켜져 있으면 감독자 승인(approval) 필수, 원장 pop_approval 에 같이 남긴다(4.37) */
    @Transactional
    public long correct(TerminalPrincipal t, PopWorker w, long resultId, CorrectForm in, PopApproval approval) {
        PopWorker me = require(w);
        ResultRow r = mapper.findResult(resultId);
        if (r == null || r.getReversalOfId() != null) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        ProcRow p = proc(t, r.getWoProcId(), true);
        FieldErrors e = FieldErrors.create();
        String reason = OrderService.text(in == null ? null : in.reason(), "reason", 500, e);
        if (reason == null && e.isEmpty()) {
            e.add("reason", "REQUIRED");
        }
        e.throwIfAny();
        if (r.isReversed()) {
            throw OrderService.fieldError("resultId", "STATE");
        }
        boolean needApproval = approvalRequired(RESULT_APPROVAL_POLICY, t, approval);
        if (r.getLotId() != null) {
            // 잠금 순서: 투입 자재 LOT 과 산출 LOT 을 LOT id 순으로 한꺼번에(실적 확정의 lockForConsume 과 같은 규칙) — 계보 id 순으로
            // 하나씩 잡으면 투입 순서가 엇갈린 두 실적을 동시에 정정할 때 서로 기다릴 수 있다(docs/25 4.20 ③)
            List<Long> lockIds = new ArrayList<>(mapper.inputParents(r.getLotId(), r.getWoProcId()));
            lockIds.add(r.getLotId());
            lots.lockForConsume(lockIds);
            LockedLot l = lotMapper.lockLot(r.getLotId());
            if (l.getBalance().compareTo(new BigDecimal(r.getGoodQty())) < 0) {
                throw OrderService.fieldError("resultId", "LOT_USED");
            }
            for (Long gen : mapper.inputEdges(r.getLotId(), r.getWoProcId())) {
                lots.reverse(gen, reason, t.actor());
            }
            // 산출 LOT 보류 = 이 실적 근거(MES_CORRECT, docs/25 4.19) — 다른 보류 근거와 따로 풀린다
            holds.holdForCorrection(r.getLotId(), resultId, reason, t.actor());
        }
        long id;
        try {
            id = mapper.insertResult(new NewResult(r.getWoId(), r.getWoProcId(), r.getLotId(), r.getEquipId(), t.terminalId(),
                    new BigDecimal(r.getGoodQty()).negate(), new BigDecimal(r.getScrapQty()).negate(),
                    new BigDecimal(r.getReworkQty()).negate(), new BigDecimal(r.getRepairQty()).negate(), r.getUnitCd(),
                    r.getWorkDay(), r.getShiftCd(), null, reason, clock.instant(), "CORRECT", me.workerId(), resultId), t.actor());
        } catch (DuplicateKeyException ex) {
            throw OrderService.fieldError("resultId", "STATE");
        }
        for (DefectLineRow d : mapper.defectsOf(resultId)) {
            mapper.insertDefect(id, d.getKind(), d.getDefectId(), new BigDecimal(d.getQty()).negate());
        }
        if (needApproval) {
            recordApproval(t, approval, resultId, id, reason);
        }
        return id;
    }

    // ------------------------------------------------------------------ 파라미터 실측

    @Transactional
    public ParamReply param(TerminalPrincipal t, PopWorker w, long woProcId, ParamForm in) {
        return param(t, w, woProcId, in, false);
    }

    /** 지난 파라미터 실측의 이관 — 데모 이력 시더 전용, API 없음. {@link #resultImport} 와 같은 셋만 다르다 */
    @Transactional
    public ParamReply paramImport(TerminalPrincipal t, PopWorker w, long woProcId, ParamForm in) {
        return param(t, w, woProcId, in, true);
    }

    private ParamReply param(TerminalPrincipal t, PopWorker w, long woProcId, ParamForm in, boolean imported) {
        PopWorker me = require(w);
        if (in == null) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        String req = reqId(in.clientReqId());
        ParamActualRow done = mapper.findParamByReq(req);
        if (done != null) {
            return replayParam(done, woProcId);
        }
        ProcRow p = proc(t, woProcId, true);
        if (!p.getStatus().equals("RUN") && !p.getStatus().equals("PAUSE")) {
            throw OrderService.fieldError("status", "STATE");
        }
        FieldErrors e = FieldErrors.create();
        String cd = in.paramCd() == null ? "" : in.paramCd().strip().toUpperCase(Locale.ROOT);
        ParamRow param = cd.isEmpty() ? null : mapper.findParam(woProcId, cd);
        if (param == null) {
            e.add("paramCd", cd.isEmpty() ? "REQUIRED" : "NO_SUCH_CODE");
        }
        String raw = in.value() == null ? "" : in.value().strip();
        BigDecimal v = null;
        if (raw.isEmpty()) {
            e.add("value", "REQUIRED");
        } else if (!PARAM_VALUE.matcher(raw).matches()) {
            e.add("value", "FORMAT");
        } else {
            v = new BigDecimal(raw);
        }
        Instant at = occurredAt(in.occurredAt(), p, e, imported);
        e.throwIfAny();
        boolean ok = (param.getLsl() == null || v.compareTo(new BigDecimal(param.getLsl())) >= 0)
                && (param.getUsl() == null || v.compareTo(new BigDecimal(param.getUsl())) <= 0);
        WorkDay wd = workDays.resolve(t.plantCd(), at);
        long id = mapper.insertParam(new NewParam(woProcId, param.getWoParamId(), param.getParamCd(), v, ok ? "OK" : "NG",
                p.getEquipId(), t.terminalId(), wd.workDay(), wd.shiftCd(), req, null, at, imported ? "IMPORT" : "KEY", me.workerId(), null),
                t.actor());
        return new ParamReply(id, ok ? "OK" : "NG", false);
    }

    @Transactional(readOnly = true)
    public ParamReply replayParam(String clientReqId, long woProcId) {
        ParamActualRow done = mapper.findParamByReq(clientReqId);
        if (done == null) {
            throw new BusinessException(HbErrorCode.CONFLICT);
        }
        return replayParam(done, woProcId);
    }

    private ParamReply replayParam(ParamActualRow done, long woProcId) {
        if (done.getWoProcId() != woProcId) {
            throw new BusinessException(HbErrorCode.DUPLICATE_REQUEST);
        }
        return new ParamReply(done.getActualId(), done.getJudge(), true);
    }

    /** 실측 정정 — 원래 행을 가리키는 CORRECT 행(값 그대로). 조회에서 둘 다 빠진다 */
    @Transactional
    public long correctParam(TerminalPrincipal t, PopWorker w, long actualId, CorrectForm in) {
        return correctParam(t, w, actualId, in, null);
    }

    /** 파라미터 정정 — 공장 정책 POP_APPROVAL_PARAM_CORRECT 면 감독자 승인 필수(4.37) */
    @Transactional
    public long correctParam(TerminalPrincipal t, PopWorker w, long actualId, CorrectForm in, PopApproval approval) {
        PopWorker me = require(w);
        ParamActualRow a = mapper.findParamActual(actualId);
        if (a == null || a.getReversalOfId() != null) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        ProcRow p = proc(t, a.getWoProcId(), true);
        FieldErrors e = FieldErrors.create();
        String reason = OrderService.text(in == null ? null : in.reason(), "reason", 500, e);
        if (reason == null && e.isEmpty()) {
            e.add("reason", "REQUIRED");
        }
        e.throwIfAny();
        if (a.isReversed()) {
            throw OrderService.fieldError("actualId", "STATE");
        }
        boolean needApproval = approvalRequired(PARAM_APPROVAL_POLICY, t, approval);
        WorkDay wd = workDays.resolve(t.plantCd(), clock.instant());
        long id;
        try {
            id = mapper.insertParam(new NewParam(a.getWoProcId(), a.getWoParamId(), a.getParamCd(), new BigDecimal(a.getValue()),
                    a.getJudge(), p.getEquipId(), t.terminalId(), wd.workDay(), wd.shiftCd(), null, reason, clock.instant(), "CORRECT",
                    me.workerId(), actualId), t.actor());
        } catch (DuplicateKeyException ex) {
            throw OrderService.fieldError("actualId", "STATE");
        }
        if (needApproval) {
            recordApproval(t, approval, actualId, id, reason);
        }
        return id;
    }

    /** 정책이 켜져 있으면 승인이 있어야 한다(없으면 403 — 화면이 승인 대화상자를 연다). 꺼져 있으면 받은 승인도 쓰지 않는다 */
    private boolean approvalRequired(String policy, TerminalPrincipal t, PopApproval approval) {
        if (!"Y".equals(policies.value(policy, t.plantCd()))) {
            return false;
        }
        if (approval == null) {
            throw new BusinessException(HbErrorCode.POP_APPROVAL_REQUIRED);
        }
        return true;
    }

    private void recordApproval(TerminalPrincipal t, PopApproval a, long targetId, long reversalId, String reason) {
        try {
            mapper.insertApproval(a, targetId, reversalId, reason, java.time.Instant.ofEpochMilli(a.issuedAt()), t.actor());
        } catch (DuplicateKeyException ex) {
            throw new BusinessException(HbErrorCode.POP_APPROVAL_REQUIRED);   // 같은 대상 승인 원장이 이미 — 정정도 이미 됐다(위에서 STATE)
        }
    }

    // ------------------------------------------------------------------ 도우미

    private static PopWorker require(PopWorker w) {
        if (w == null) {
            throw new BusinessException(HbErrorCode.POP_NO_WORKER);
        }
        return w;
    }

    private ProcRow proc(TerminalPrincipal t, long woProcId, boolean lock) {
        ProcRow p = mapper.findProc(woProcId, t.nodeId(), t.plantCd(), lock);
        if (p == null) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        return p;
    }

    static String reqId(String raw) {
        if (raw == null || !REQ_ID.matcher(raw).matches()) {
            throw OrderService.fieldError("clientReqId", raw == null || raw.isBlank() ? "REQUIRED" : "FORMAT");
        }
        return raw;
    }

    /** 발생 시각: 없으면 지금, 정책 허용보다 미래면 FUTURE, 지시 발행 전이면 RANGE. 이관은 필수·지금까지·발행 전 허용 */
    private Instant occurredAt(String raw, ProcRow p, FieldErrors e, boolean imported) {
        Instant now = clock.instant();
        if (raw == null || raw.isBlank()) {
            if (imported) {
                e.add("occurredAt", "REQUIRED");
            }
            return now;
        }
        Instant at;
        try {
            at = OffsetDateTime.parse(raw.strip()).toInstant();
        } catch (DateTimeParseException ex) {
            e.add("occurredAt", "FORMAT");
            return now;
        }
        if (at.isAfter(imported ? now : now.plus(Duration.ofSeconds(policies.intValue(FUTURE_POLICY))))) {
            e.add("occurredAt", "FUTURE");
        } else if (!imported && p.getReleasedAt() != null && at.isBefore(p.getReleasedAt().toInstant())) {
            e.add("occurredAt", "RANGE");
        }
        return at;
    }

    private static BigDecimal zero(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }
}
