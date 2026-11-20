package io.smarthappy.module.pop;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.smarthappy.module.pop.PopDtos.PopWorker;
import io.smarthappy.module.pop.PopWorkDtos.ProcRow;
import io.smarthappy.module.qms.link.FloorInspPort;
import io.smarthappy.module.qms.link.FloorInspPort.ConfirmForm;
import io.smarthappy.module.qms.link.FloorInspPort.DueInsp;
import io.smarthappy.module.qms.link.FloorInspPort.DueQuery;
import io.smarthappy.module.qms.link.FloorInspPort.Entry;
import io.smarthappy.module.qms.link.FloorInspPort.FloorInsp;
import io.smarthappy.module.qms.link.FloorInspPort.Gauge;
import io.smarthappy.module.qms.link.FloorInspPort.InspRef;
import io.smarthappy.module.qms.link.FloorInspPort.MeasureForm;
import io.smarthappy.module.qms.link.FloorInspPort.MeasureResult;
import io.smarthappy.module.qms.link.FloorInspPort.OpenForm;
import io.smarthappy.platform.code.CodeService;
import io.smarthappy.platform.common.web.BusinessException;
import io.smarthappy.platform.common.web.HbErrorCode;
import lombok.RequiredArgsConstructor;

/**
 * POP 검사 입력 (docs/25 4.57, 계약 docs/29_P3 53장) — 초물·순회(중물)·종물·공정 검사의 측정값을 단말에서. 화면·범위 확인은 MES,
 * 검사 만들기·측정 원장·판정은 QMS {@link FloorInspPort}(쓰기는 이 트랜잭션 안에서 부른다 — 계약 MANDATORY).
 *
 * - 지금 작업자 필수(428), 공정은 단말 작업장 안만(밖은 404 — 착수·실적과 같은 조회). 공장은 QMS 가 다시 본다
 * - 검사는 늘 공정을 거쳐 연다(/procs/{id}/insp/…): 다른 공정·다른 품목의 검사 id 를 넣으면 404
 * - 검사 시작 = 자동(열린 검사가 있으면 그것), 판정 = 자동(전부 OK 면 확정, NG 는 [불합격 확정] 한 번) — 사용자 결정 2026-11-10
 * - 멱등(clientReqId)·칸 오류·상태 오류는 QMS 것을 그대로 돌려준다. 측정값·검사 무효는 여기 없다(사무 화면만). 온라인만
 */
@Service
@RequiredArgsConstructor
public class PopInspService {

    static final String TYPE_GRP = "INSP_TYPE";

    /** 열 수 있는 유형 + 유형 이름(공통코드 INSP_TYPE, 사용자 언어) */
    public record DueView(List<DueInsp> due, Map<String, String> typeNames) {
    }

    public record OpenBody(String inspType, String lotNo, String clientReqId) {
    }

    /** 검사 열기 결과 — 참조(이미 열린 것·재전송 여부) + 화면 한 장 */
    public record Opened(InspRef ref, FloorInsp insp) {
    }

    public record MeasureBody(Long inspLineId, List<Entry> entries, String entryMethod, String clientReqId) {
    }

    public record ConfirmBody(String clientReqId) {
    }

    private final PopWorkMapper mapper;
    private final ObjectProvider<FloorInspPort> ports;
    private final CodeService codes;
    private final io.smarthappy.platform.module.CompModuleService modules;

    /** 검사 버튼 — QMS 구현이 없거나(NONE) 회사가 품질 모듈을 껐으면 숨김. 공정 화면은 생산 단말(POP)만 열 수 있다 */
    boolean on() {
        return FloorInspPort.of(ports) != FloorInspPort.NONE && modules.isOn("QMS");   // 회사가 품질을 끄면 숨김(docs/80)
    }

    @Transactional(readOnly = true)
    public DueView due(TerminalPrincipal t, PopWorker w, long woProcId) {
        require(w);
        proc(t, woProcId);
        List<DueInsp> due = FloorInspPort.of(ports).due(new DueQuery(woProcId, t.terminalId()));
        Map<String, String> names = new LinkedHashMap<>();
        if (!due.isEmpty()) {
            codes.codes(TYPE_GRP, LocaleContextHolder.getLocale()).forEach(c -> names.put(c.code(), c.name()));
        }
        return new DueView(due, names);
    }

    @Transactional
    public Opened open(TerminalPrincipal t, PopWorker w, long woProcId, OpenBody in) {
        PopWorker me = require(w);
        proc(t, woProcId);
        if (in == null) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        FloorInspPort qms = FloorInspPort.of(ports);
        InspRef ref = qms.open(new OpenForm(in.inspType(), in.lotNo(), woProcId, me.workerId(), t.terminalId(), in.clientReqId()));
        return new Opened(ref, qms.detail(ref.inspId(), t.terminalId()));
    }

    @Transactional(readOnly = true)
    public FloorInsp view(TerminalPrincipal t, PopWorker w, long woProcId, long inspId) {
        require(w);
        return ofProc(proc(t, woProcId), FloorInspPort.of(ports).detail(inspId, t.terminalId()));
    }

    @Transactional(readOnly = true)
    public List<Gauge> gauges(TerminalPrincipal t, PopWorker w, long woProcId, String gaugeClass) {
        require(w);
        proc(t, woProcId);
        return FloorInspPort.of(ports).gauges(gaugeClass);
    }

    @Transactional
    public MeasureResult measure(TerminalPrincipal t, PopWorker w, long woProcId, long inspId, MeasureBody in) {
        PopWorker me = require(w);
        ProcRow p = proc(t, woProcId);
        if (in == null || in.inspLineId() == null) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        FloorInspPort qms = FloorInspPort.of(ports);
        ofProc(p, qms.detail(inspId, t.terminalId()));
        return qms.measure(new MeasureForm(inspId, in.inspLineId(), in.entries() == null ? List.of() : in.entries(), in.entryMethod(),
                me.workerId(), t.terminalId(), in.clientReqId()));
    }

    @Transactional
    public FloorInsp confirmNg(TerminalPrincipal t, PopWorker w, long woProcId, long inspId, ConfirmBody in) {
        PopWorker me = require(w);
        ProcRow p = proc(t, woProcId);
        FloorInspPort qms = FloorInspPort.of(ports);
        ofProc(p, qms.detail(inspId, t.terminalId()));
        return qms.confirmNg(new ConfirmForm(inspId, me.workerId(), t.terminalId(), in == null ? null : in.clientReqId()));
    }

    /** 이 공정의 검사인가 — 공정 코드·품목이 같아야(다른 작업장 공정의 검사 id 를 넣어 보는 것을 막는다) */
    private static FloorInsp ofProc(ProcRow p, FloorInsp f) {
        if (f == null || !Objects.equals(p.getProcCd(), f.procCd()) || !Objects.equals(p.getItemCd(), f.itemCd())) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        return f;
    }

    private ProcRow proc(TerminalPrincipal t, long woProcId) {
        ProcRow p = mapper.findProc(woProcId, t.nodeId(), t.plantCd(), false);
        if (p == null) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        return p;
    }

    private static PopWorker require(PopWorker w) {
        if (w == null) {
            throw new BusinessException(HbErrorCode.POP_NO_WORKER);
        }
        return w;
    }
}
