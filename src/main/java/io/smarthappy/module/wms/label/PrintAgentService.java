package io.smarthappy.module.wms.label;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import io.smarthappy.module.pop.TerminalPrincipal;
import io.smarthappy.module.wms.label.PrintTrackDtos.AgentHeartbeat;
import io.smarthappy.module.wms.label.PrintTrackDtos.AgentHeartbeatView;
import io.smarthappy.module.wms.label.PrintTrackDtos.AgentPrinter;
import io.smarthappy.module.wms.label.PrintTrackDtos.AgentPrinterState;
import io.smarthappy.module.wms.label.PrintTrackDtos.AgentResult;
import io.smarthappy.module.wms.label.PrintTrackDtos.AgentResultView;
import io.smarthappy.module.wms.label.PrintTrackDtos.ClaimedJob;
import io.smarthappy.module.wms.label.PrintTrackDtos.DispatchRow;
import io.smarthappy.module.wms.label.ZebraStatus.Probe;
import io.smarthappy.platform.common.web.BusinessException;
import io.smarthappy.platform.common.web.HbErrorCode;
import io.smarthappy.platform.policy.PolicyService;
import io.smarthappy.platform.time.BizClock;

/**
 * pull 인쇄 에이전트 (docs/65 19장) — SaaS 서버는 공장 안 프린터로 밀 수 없으니 에이전트가 당겨 간다.
 *
 * <ul>
 * <li>장치 = sys_terminal COLLECTOR(Bearer 키, 시스템관리에서 발급). 회사 범위는 키가, <b>장치 범위</b>(그 장치 프린터의 작업만)는 여기서</li>
 * <li>claim = {@code FOR UPDATE SKIP LOCKED} lease. 결과는 E1 과 같은 사건 원장·전이표({@link PrintTrackService#move})</li>
 * <li>lease 만료 = UNKNOWN — <b>자동 재인쇄 금지</b>(이미 찍혔을 수 있다), 사람이 확정하거나 넘긴다</li>
 * </ul>
 */
@Service
public class PrintAgentService {

    static final String POLICY_LEASE = "WMS_PRINT_LEASE_SEC";
    static final String POLICY_WAIT = "WMS_PRINT_AGENT_WAIT_SEC";
    static final int CLAIM_MAX = 20;
    static final int MAX_PRINTERS = 50;
    static final Set<String> RESULT_STATES = Set.of(PrintStates.SENT, PrintStates.PRINTED, PrintStates.PRINTER_ERROR, PrintStates.FAILED);

    private final PrintTrackMapper mapper;
    private final PrintTrackService track;
    private final PolicyService policy;
    private final BizClock clock;

    public PrintAgentService(PrintTrackMapper mapper, PrintTrackService track, PolicyService policy, BizClock clock) {
        this.mapper = mapper;
        this.track = track;
        this.policy = policy;
        this.clock = clock;
    }

    /** 요청 때(LabelPrintService) — AGENT 프린터 작업을 그 프린터 담당 장치의 큐에 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void enqueue(long jobId, long printerId, long terminalId) {
        mapper.insertDispatch(jobId, printerId, terminalId);
    }

    @Transactional(readOnly = true)
    public int waitCap() {
        return Math.max(0, Math.min(25, policy.intValue(POLICY_WAIT)));
    }

    /** 가져가기 — 그 장치 것만, 한 번에 max 건(1~20). 가져간 작업은 CLAIMED + lease */
    @Transactional
    public List<ClaimedJob> claim(TerminalPrincipal d, int max) {
        int n = Math.max(1, Math.min(CLAIM_MAX, max));
        int lease = policy.intValue(POLICY_LEASE);
        List<ClaimedJob> out = new ArrayList<>();
        for (long jobId : mapper.claimable(d.terminalId(), n)) {
            if (mapper.markClaimed(jobId, lease) == 0) {
                continue;
            }
            if (track.move(jobId, PrintStates.CLAIMED, null, "claim", "AGENT", d.terminalId(), "AUTO", null) != PrintTrackService.Move.MOVED) {
                continue;
            }
            ClaimedJob j = mapper.claimedJob(jobId);
            j.setLabels(j.getCopies() * ZplLayout.labelsPerCopy(j.getZpl()));
            out.add(j);
        }
        return out;
    }

    /**
     * 결과 보고 — 그 작업을 가져간 장치만(아니면 404: 다른 장치·회사 작업 있는지조차 알리지 않는다).
     * 같은 상태 재전송 = DUP, 표가 허용 안 하는 늦은 결과 = STALE(사건만) — 둘 다 200 이라 에이전트가 같은 보고를 되풀이하지 않는다.
     */
    @Transactional
    public AgentResultView result(TerminalPrincipal d, long jobId, AgentResult in) {
        DispatchRow row = mapper.lockDispatch(jobId);
        if (row == null || row.getTerminalId() != d.terminalId()) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        if (row.getClaimCnt() == 0) {
            throw new BusinessException(HbErrorCode.CONFLICT);                 // 가져가지 않은 작업의 결과
        }
        String state = in == null || in.state() == null ? null : in.state().strip();
        if (state == null || !RESULT_STATES.contains(state)) {
            throw LabelPrintService.fieldError("state", "CODE");
        }
        String reason = in.reasonCd() == null || in.reasonCd().isBlank() ? null : in.reasonCd().strip();
        if (reason != null && !reason.matches("^[A-Z][A-Z0-9_]{0,39}$")) {
            throw LabelPrintService.fieldError("reasonCd", "FORMAT");
        }
        String detail = in.detail() == null || in.detail().isBlank() ? null : PrintTrackService.trim(in.detail().strip(), 200);
        OffsetDateTime at = occurredAt(in.occurredAt());
        PrintTrackService.Move m = track.move(jobId, state, reason, detail, "AGENT", d.terminalId(), "AUTO", at);
        if (m != PrintTrackService.Move.STALE) {
            if (PrintStates.PRINTED.equals(state) || PrintStates.FAILED.equals(state)) {
                mapper.setStage(jobId, "CLOSED");
            } else if (!"SENT".equals(row.getStage())) {
                mapper.setStage(jobId, "SENT");                                  // SENT·PRINTER_ERROR — lease 는 더 보지 않는다
            }
        }
        return new AgentResultView(jobId, track.state(jobId), m.name());
    }

    /** 장치가 보낸 시각 — 형식 밖·미래(1분 넘게)면 서버 시각(장비 시계는 믿지 않는다 — 5.2-5) */
    OffsetDateTime occurredAt(String s) {
        Instant now = clock.instant();
        if (s == null || s.isBlank()) {
            return now.atOffset(ZoneOffset.UTC);
        }
        try {
            Instant t = OffsetDateTime.parse(s.strip()).toInstant();
            return (t.isAfter(now.plusSeconds(60)) ? now : t).atOffset(ZoneOffset.UTC);
        } catch (DateTimeParseException e) {
            return now.atOffset(ZoneOffset.UTC);
        }
    }

    /** 하트비트 — 그 장치 프린터 상태를 E1 과 같은 함수로(odometer 확인·오류). 남의 프린터 id 는 unknown 으로 */
    @Transactional
    public AgentHeartbeatView heartbeat(TerminalPrincipal d, AgentHeartbeat in) {
        List<AgentPrinter> mine = mapper.agentPrinters(d.terminalId());
        List<Long> unknown = new ArrayList<>();
        if (in != null && in.printers() != null) {
            if (in.printers().size() > MAX_PRINTERS) {
                throw new BusinessException(HbErrorCode.TOO_MANY_ROWS, in.printers().size(), MAX_PRINTERS);
            }
            for (AgentPrinterState s : in.printers()) {
                if (s == null || s.printerId() == null) {
                    continue;
                }
                if (mine.stream().noneMatch(p -> p.getPrinterId() == s.printerId())) {
                    unknown.add(s.printerId());
                    continue;
                }
                boolean online = s.online() == null || s.online();
                Long odo = s.odometer() != null && s.odometer() >= 0 ? s.odometer() : null;
                Probe p = online ? new Probe(true, ZebraStatus.cleanCodes(s.errors()), ZebraStatus.cleanCodes(s.warnings()), odo,
                        odo == null && (s.errors() == null || s.errors().isEmpty()) ? "AGENT_NO_ODOMETER" : null)
                        : Probe.offline("AGENT_PRINTER_OFFLINE");
                track.apply(s.printerId(), p, "AGENT", d.terminalId());
            }
        }
        return new AgentHeartbeatView(mapper.queuedCount(d.terminalId()), mine, unknown);
    }

    /** 정기 작업(회사 범위 트랜잭션 안) — lease 만료 CLAIMED → UNKNOWN. 자동으로 다시 나눠 주지 않는다 */
    @Transactional(propagation = Propagation.MANDATORY)
    public int expireLeases() {
        int n = 0;
        for (long jobId : mapper.leaseExpired()) {
            if (track.move(jobId, PrintStates.UNKNOWN, "LEASE_EXPIRED", null, "SYSTEM", null, "AUTO", null) == PrintTrackService.Move.MOVED) {
                n++;
            }
            mapper.setStage(jobId, "CLOSED");
        }
        return n;
    }

    /**
     * 사람이 닫기·다른 프린터로 넘기기 전 — 에이전트 작업이면 큐 행을 잠그고 아직 안 가져갔는지(QUEUED) 본다.
     * 가져간 중(CLAIMED, lease 안)은 에이전트가 찍는 중일 수 있어 막는다(409) — 보낸 뒤 오류(PRINTER_ERROR)는 상태 규칙이 거른다.
     * 큐 행이 없으면(설치형) null
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public DispatchRow lockForClose(long jobId) {
        DispatchRow row = mapper.lockDispatch(jobId);
        if (row != null && "CLAIMED".equals(row.getStage())) {
            throw new BusinessException(HbErrorCode.CONFLICT);
        }
        return row;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void close(long jobId) {
        mapper.setStage(jobId, "CLOSED");
    }
}
