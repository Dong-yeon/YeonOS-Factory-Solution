package io.smarthappy.module.qms;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import io.smarthappy.module.qms.FloorInspMapper.FloorHead;
import io.smarthappy.module.qms.FloorInspMapper.Terminal;
import io.smarthappy.module.qms.FloorInspMapper.WoProc;
import io.smarthappy.module.qms.InspDtos.CreateForm;
import io.smarthappy.module.qms.InspDtos.LineRow;
import io.smarthappy.module.qms.InspDtos.MeasEntry;
import io.smarthappy.module.qms.InspDtos.MeasRow;
import io.smarthappy.module.qms.InspDtos.MeasSource;
import io.smarthappy.module.qms.InspJudge.Verdict;
import io.smarthappy.module.qms.link.FloorInspPort;
import io.smarthappy.platform.common.text.VisibleText;
import io.smarthappy.platform.common.web.BusinessException;
import io.smarthappy.platform.common.web.FieldErrors;
import io.smarthappy.platform.common.web.HbErrorCode;
import io.smarthappy.platform.messaging.InboxService;
import io.smarthappy.platform.time.BizClock;

/**
 * 현장 단말(POP) 검사 입력 계약 구현 (docs/29_P3 53장) — 쓰기는 사무 검사(InspService)와 같은 경로(createAs·recordAs·judgeAs),
 * 행위자 = 단말(t{id}:{코드}) + 작업자 칸.
 *
 * - 검사 시작 자동: open = 같은 유형·LOT·공정의 열린 검사가 있으면 그것(FI-3)
 * - 판정 자동: measure 뒤 모든 행 판정·전부 OK 면 같은 트랜잭션에서 확정, NG 면 confirmNg 를 기다린다(FI-4)
 * - 멱등: sys_inbox(QMS_FLOOR_OPEN·QMS_FLOOR_MEAS·QMS_FLOOR_NG) — 재전송 = 처음 결과(FI-6)
 */
@Service
public class FloorInspService implements FloorInspPort {

    static final String C_OPEN = "QMS_FLOOR_OPEN";
    static final String C_MEAS = "QMS_FLOOR_MEAS";
    static final String C_NG = "QMS_FLOOR_NG";
    static final Pattern CLIENT_REQ = Pattern.compile("^[A-Za-z0-9_.:-]{8,64}$");
    /** due 순서 — 초물 → 순회(중물) → 종물 → 공정 */
    static final List<String> ORDER = List.of("FIRST_OFF", "PATROL", "LAST_OFF", "PROCESS");

    private final FloorInspMapper mapper;
    private final InspMapper insps;
    private final InspService inspService;
    private final InboxService inbox;
    private final BizClock clock;

    public FloorInspService(FloorInspMapper mapper, InspMapper insps, InspService inspService, InboxService inbox, BizClock clock) {
        this.mapper = mapper;
        this.insps = insps;
        this.inspService = inspService;
        this.inbox = inbox;
        this.clock = clock;
    }

    // ------------------------------------------------------------------ 읽기

    @Override
    @Transactional(readOnly = true)
    public List<DueInsp> due(DueQuery q) {
        if (q == null) {
            return List.of();
        }
        Terminal t = mapper.findTerminal(q.terminalId());
        WoProc wp = mapper.findWoProc(q.woProcId());
        if (t == null || wp == null || !Objects.equals(t.getPlantCd(), wp.getPlantCd())) {
            return List.of();
        }
        List<DueInsp> out = new ArrayList<>();
        for (String type : ORDER) {
            if (!inspService.hasPlan(type, wp.getItemId(), wp.getItemClassId(), wp.getProcId())) {
                continue;
            }
            String lot = mapper.suggestedLot(wp.getWoProcId(), "FIRST_OFF".equals(type));
            FloorInspMapper.InspRef open = lot == null ? null : mapper.findOpen(type, lot, wp.getProcId());
            FloorInspMapper.InspRef last = mapper.findLastJudged(type, wp.getWoId(), wp.getProcId());
            out.add(new DueInsp(type, lot, open == null ? null : open.getInspId(), open == null ? null : open.getInspNo(),
                    last == null ? null : last.getResult(), last == null ? null : last.getJudgedAt()));
        }
        return out;
    }

    @Override
    @Transactional(readOnly = true)
    public FloorInsp detail(long inspId, long terminalId) {
        Terminal t = mapper.findTerminal(terminalId);
        if (t == null) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        head(inspId, t);
        return view(inspId);
    }

    @Override
    @Transactional(readOnly = true)
    public List<Gauge> gauges(String gaugeClass) {
        return inspService.gaugeOptions(gaugeClass).stream()
                .map(g -> new Gauge(g.getAssetId(), g.getAssetCd(), g.getAssetNm(), gaugeClass.strip(), g.isBlocked())).toList();
    }

    // ------------------------------------------------------------------ 쓰기

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public InspRef open(OpenForm f) {
        if (f == null) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        FieldErrors e = FieldErrors.create();
        String type = VisibleText.isBlank(f.inspType()) ? null : f.inspType().strip();
        if (type == null) {
            e.add("inspType", "REQUIRED");
        } else if (!TYPES.contains(type)) {
            e.add("inspType", "CODE");
        }
        Terminal t = actorCheck(f.workerId(), f.terminalId(), f.clientReqId(), e);
        WoProc wp = mapper.findWoProc(f.woProcId());
        if (wp == null) {
            e.add("woProcId", "NO_SUCH_CODE");
        } else if (t != null && !Objects.equals(t.getPlantCd(), wp.getPlantCd())) {
            e.add("woProcId", "FLOOR_PLANT");   // 단말 공장의 지시만
        }
        String lotNo = VisibleText.isBlank(f.lotNo()) ? null : f.lotNo().strip();
        if (lotNo == null) {
            e.add("lotNo", "REQUIRED");
        } else if (wp != null && (lotNo.length() > 60 || mapper.lotOfWo(lotNo, wp.getWoId()) == null)) {
            e.add("lotNo", "NOT_WO_LOT");   // 이 지시의 산출 LOT 만
        }
        e.throwIfAny();
        FloorInspMapper.InspRef open = mapper.findOpen(type, lotNo, wp.getProcId());
        if (!inbox.firstTime(C_OPEN, f.clientReqId())) {
            FloorInspMapper.InspRef r = mapper.findByOpenReq(f.clientReqId());
            FloorInspMapper.InspRef x = r != null ? r : open;
            if (x == null) {
                throw new BusinessException(HbErrorCode.DUPLICATE_REQUEST);
            }
            return new InspRef(x.getInspId(), x.getInspNo(), r == null, true);
        }
        if (open != null) {
            return new InspRef(open.getInspId(), open.getInspNo(), true, false);
        }
        long id = inspService.createAs(new CreateForm(lotNo, type, wp.getProcCd(), null, null), null, actor(t),
                MeasSource.floor("KEY", f.workerId(), f.terminalId(), f.clientReqId()));
        return new InspRef(id, mapper.findHead(id).getInspNo(), false, false);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public MeasureResult measure(MeasureForm f) {
        if (f == null || f.entries() == null || f.entries().isEmpty() || f.entries().stream().anyMatch(Objects::isNull)) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        FieldErrors e = FieldErrors.create();
        String method = VisibleText.isBlank(f.entryMethod()) ? "KEY" : f.entryMethod().strip();
        if (!ENTRY_METHODS.contains(method)) {
            e.add("entryMethod", "CODE");
        }
        Terminal t = actorCheck(f.workerId(), f.terminalId(), f.clientReqId(), e);
        e.throwIfAny();
        head(f.inspId(), t);
        if (!inbox.firstTime(C_MEAS, f.clientReqId())) {
            return new MeasureResult(true, false, view(f.inspId()));
        }
        OffsetDateTime now = clock.now().toOffsetDateTime();
        List<MeasEntry> entries = f.entries().stream().map(x -> new MeasEntry(x.sampleNo(), x.value(), x.okYn(), x.gaugeAssetId())).toList();
        inspService.recordAs(f.inspId(), f.inspLineId(), entries, entries.stream().map(x -> now).toList(),
                MeasSource.floor(method, f.workerId(), f.terminalId(), f.clientReqId()), actor(t));
        // 모든 행 판정·전부 OK → 같은 경로로 확정(후속 SPC·재고 그대로). NG 가 섞이면 작업자의 [불합격 확정]을 기다린다
        List<LineRow> lines = insps.findLines(f.inspId());
        List<Verdict> verdicts = InspService.preview(lines, insps.findLiveMeas(f.inspId()));
        boolean judged = false;
        if (!verdicts.isEmpty() && verdicts.stream().allMatch(v -> v == Verdict.OK)) {
            inspService.judgeAs(f.inspId(), null, actor(t), f.workerId(), "OK");
            judged = true;
        }
        return new MeasureResult(false, judged, view(f.inspId()));
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public FloorInsp confirmNg(ConfirmForm f) {
        if (f == null) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        FieldErrors e = FieldErrors.create();
        Terminal t = actorCheck(f.workerId(), f.terminalId(), f.clientReqId(), e);
        e.throwIfAny();
        head(f.inspId(), t);
        if (!inbox.firstTime(C_NG, f.clientReqId())) {
            return view(f.inspId());
        }
        inspService.judgeAs(f.inspId(), null, actor(t), f.workerId(), "NG");
        return view(f.inspId());
    }

    // ------------------------------------------------------------------ 안쪽

    /** 작업자·단말·요청 id 확인 — 칸 오류를 모으고 단말을 돌려준다(없으면 null) */
    private Terminal actorCheck(long workerId, long terminalId, String clientReqId, FieldErrors e) {
        if (clientReqId == null || !CLIENT_REQ.matcher(clientReqId).matches()) {
            e.add("clientReqId", "FORMAT");
        }
        Terminal t = mapper.findTerminal(terminalId);   // RLS — 다른 회사·폐기 단말은 없음
        if (t == null) {
            e.add("terminalId", "NO_SUCH_CODE");
        }
        if (!mapper.workerActive(workerId)) {
            e.add("workerId", "NO_SUCH_CODE");   // 없음·다른 회사·사용 중지
        }
        return t;
    }

    /** 단말 범위 — 단말 유형·단말 공장의 생산 LOT 검사만, 아니면 404 */
    private FloorHead head(long inspId, Terminal t) {
        FloorHead h = mapper.findHead(inspId);
        if (h == null || !TYPES.contains(h.getInspType()) || h.getPlantCd() == null || !h.getPlantCd().equals(t.getPlantCd())) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        return h;
    }

    private static String actor(Terminal t) {
        return "t" + t.getTerminalId() + ":" + t.getTerminalCd();   // POP 행위자 관례(TerminalPrincipal.actor)
    }

    private FloorInsp view(long inspId) {
        FloorHead h = mapper.findHead(inspId);
        List<LineRow> lines = insps.findLines(inspId);
        List<MeasRow> meas = insps.findLiveMeas(inspId);
        List<Verdict> verdicts = InspService.preview(lines, meas);
        String preview = verdicts.isEmpty() ? Verdict.OPEN.name() : InspJudge.head(verdicts).name();
        boolean decided = !verdicts.isEmpty() && verdicts.stream().noneMatch(v -> v == Verdict.OPEN);
        String next = !"OPEN".equals(h.getStatus()) ? "DONE" : decided && "NG".equals(preview) ? "CONFIRM_NG" : "MEASURE";
        Map<Long, List<MeasRow>> byLine = meas.stream().collect(Collectors.groupingBy(MeasRow::getInspLineId));
        List<Line> out = new ArrayList<>();
        for (LineRow l : lines) {
            List<Meas> ms = byLine.getOrDefault(l.getInspLineId(), List.of()).stream()
                    .map(m -> new Meas(m.getMeasId(), m.getSampleNo(), m.getValue(), m.getOkYn(), m.getGaugeAssetId(), outOfSpec(l, m))).toList();
            out.add(new Line(l.getInspLineId(), l.getLineNo(), l.getCharLabel(), l.getCharNm(), l.getDataType(), l.getNominal(), l.getLsl(),
                    l.getUsl(), l.getScale(), l.getUnitNm(), l.getGaugeClass(), l.getSampleSize(), l.getAc(), l.getRe(), l.getVerdict(), ms));
        }
        return new FloorInsp(h.getInspId(), h.getInspNo(), h.getInspType(), h.getLotNo(), h.getItemCd(), h.getItemNm(), h.getProcCd(),
                h.getStatus(), h.getResult(), preview, next, out);
    }

    /** 계량 = 규격 밖(경계 포함 합격), 계수 = NG */
    static boolean outOfSpec(LineRow l, MeasRow m) {
        if (m.getValue() == null) {
            return "N".equals(m.getOkYn());
        }
        return (l.getLsl() != null && m.getValue().compareTo(l.getLsl()) < 0) || (l.getUsl() != null && m.getValue().compareTo(l.getUsl()) > 0);
    }
}
