package io.smarthappy.module.wms.label;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import io.smarthappy.module.wms.label.PrintTrackDtos.ApplyResult;
import io.smarthappy.module.wms.label.PrintTrackDtos.EventRow;
import io.smarthappy.module.wms.label.PrintTrackDtos.JobState;
import io.smarthappy.module.wms.label.PrintTrackDtos.PendingJob;
import io.smarthappy.module.wms.label.PrintTrackDtos.PrinterStatusRow;
import io.smarthappy.module.wms.label.PrintTrackDtos.ResolveForm;
import io.smarthappy.module.wms.label.PrintTrackDtos.VerifyForm;
import io.smarthappy.module.wms.label.PrintTrackDtos.VerifyResult;
import io.smarthappy.module.wms.label.ZebraStatus.Probe;
import io.smarthappy.platform.common.web.BusinessException;
import io.smarthappy.platform.common.web.HbErrorCode;
import io.smarthappy.platform.tenant.TenantContext;
import io.smarthappy.platform.time.BizClock;
import tools.jackson.databind.json.JsonMapper;

/**
 * 인쇄 상태 3단 (docs/65 18.1) — 사건 기록·전이 판정·확인(odometer)·검증 스캔·사람 확정.
 * 상태 = 사건 원장의 마지막 상태 사건(없으면 아웃박스). 전이는 {@link PrintStates#canMove} 한 표.
 */
@Service
public class PrintTrackService {

    /** 결과 */
    public enum Move {
        MOVED, DUP, STALE
    }

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final PrintTrackMapper mapper;
    private final BizClock clock;

    public PrintTrackService(PrintTrackMapper mapper, BizClock clock) {
        this.mapper = mapper;
        this.clock = clock;
    }

    // ------------------------------------------------------------------ 상태 전이

    /**
     * 작업 상태를 옮긴다(작업 잠금 → 지금 상태 → 표 검사). 같은 상태 = DUP(사건 없음 — 재전송 멱등),
     * 허용 안 된 전이 = STALE 사건만(늦게 온 옛 결과 — 상태를 되돌리지 않는다, docs/65 5.2-3).
     *
     * @param terminalId 에이전트 장치(없으면 null)
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Move move(long jobId, String to, String reasonCd, String detail, String src, Long terminalId, String method,
            OffsetDateTime occurredAt) {
        if (!PrintStates.isState(to) || PrintStates.QUEUED.equals(to)) {
            throw new IllegalArgumentException("state " + to);
        }
        mapper.lockJob(jobId);
        JobState j = mapper.jobState(jobId);
        if (j == null) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        String from = j.getStateCd();
        if (to.equals(from)) {
            return Move.DUP;
        }
        if (!PrintStates.canMove(from, to)) {
            event(jobId, PrintStates.STALE, to, trim(from + "→" + to + (detail == null ? "" : " " + detail), 500), src, terminalId, method,
                    occurredAt);
            return Move.STALE;
        }
        event(jobId, to, reasonCd, detail, src, terminalId, method, occurredAt);
        return Move.MOVED;
    }

    /** 상태를 바꾸지 않는 사건(WARN·VERIFY_NG) */
    @Transactional(propagation = Propagation.MANDATORY)
    public void note(long jobId, String eventCd, String reasonCd, String detail, String src, Long terminalId, String method) {
        if (PrintStates.isState(eventCd)) {
            throw new IllegalArgumentException("state event " + eventCd);
        }
        event(jobId, eventCd, reasonCd, detail, src, terminalId, method, null);
    }

    private void event(long jobId, String eventCd, String reasonCd, String detail, String src, Long terminalId, String method,
            OffsetDateTime occurredAt) {
        mapper.insertEvent(jobId, eventCd, reasonCd == null ? null : trim(reasonCd, 40), detail == null ? null : trim(detail, 500), src,
                terminalId, occurredAt == null ? now() : occurredAt, method == null ? "AUTO" : method,
                trim(TenantContext.currentActor(), 50), null);
    }

    @Transactional(readOnly = true)
    public String state(long jobId) {
        JobState j = mapper.jobState(jobId);
        if (j == null) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        return j.getStateCd();
    }

    @Transactional(readOnly = true)
    public List<EventRow> events(long jobId) {
        if (mapper.jobState(jobId) == null) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        return mapper.events(jobId);
    }

    // ------------------------------------------------------------------ 검증 스캔 (VERIFIED)

    /** 스캔 글자 = 라벨 값 barcode(없으면 bizKey) → VERIFIED, 다르면 VERIFY_NG 사건(상태 그대로) */
    @Transactional
    public VerifyResult verify(long jobId, VerifyForm in, String src) {
        String scanned = in == null ? null : normalizeScan(in.scanned());
        if (scanned == null || scanned.isEmpty() || scanned.length() > 512) {
            throw LabelPrintService.fieldError("scanned", scanned == null || scanned.isEmpty() ? "REQUIRED" : "TOO_LONG", 512);
        }
        mapper.lockJob(jobId);
        JobState j = mapper.jobState(jobId);
        if (j == null) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        String expected = expectedScan(j);
        if (!expected.equals(scanned)) {
            note(jobId, PrintStates.VERIFY_NG, "MISMATCH", trim("scanned=" + scanned, 500), src, null, "SCAN");
            return new VerifyResult(false, j.getStateCd());
        }
        if (PrintStates.VERIFIED.equals(j.getStateCd())) {
            return new VerifyResult(true, j.getStateCd());
        }
        if (!PrintStates.canMove(j.getStateCd(), PrintStates.VERIFIED)) {
            // 아직 안 보냈거나 끝난 작업 — 라벨이 붙어 있을 수 없다
            throw new BusinessException(HbErrorCode.CONFLICT);
        }
        event(jobId, PrintStates.VERIFIED, null, null, src, null, "SCAN", null);
        return new VerifyResult(true, PrintStates.VERIFIED);
    }

    static String expectedScan(JobState j) {
        Map<String, String> v = values(j.getLabelValues());
        String b = v.get("barcode");
        return normalizeScan(b == null || b.isBlank() ? j.getBizKey() : b);
    }

    /** 웨지가 붙이는 줄바꿈·공백, 심볼 식별자(]C1 ]d2 ]Q3 ]E0 …)를 걷는다 */
    static String normalizeScan(String s) {
        if (s == null) {
            return null;
        }
        String t = s.strip();
        if (t.length() >= 3 && t.charAt(0) == ']' && Character.isLetter(t.charAt(1)) && Character.isLetterOrDigit(t.charAt(2))) {
            t = t.substring(3);
        }
        return t;
    }

    // ------------------------------------------------------------------ 사람 확정

    /** 결과 불명·프린터 오류 작업을 사람이 닫는다: PRINTED(찍힌 것 확인) / CANCELLED(안 찍힘). 사유 필수 */
    @Transactional
    public String resolve(long jobId, ResolveForm in) {
        return resolve(jobId, in, false);
    }

    /** @param queuedAgent 아직 안 가져간 에이전트 작업(큐 행을 잠그고 확인함) — 그때만 QUEUED 를 CANCELLED 로 닫을 수 있다 */
    @Transactional
    public String resolve(long jobId, ResolveForm in, boolean queuedAgent) {
        String reason = in == null || in.reason() == null ? "" : in.reason().strip();
        if (reason.length() < 2 || reason.length() > 200) {
            throw LabelPrintService.fieldError("reason", reason.length() < 2 ? "REQUIRED" : "TOO_LONG", 200);
        }
        String to = in.stateCd();
        if (!PrintStates.PRINTED.equals(to) && !PrintStates.CANCELLED.equals(to)) {
            throw LabelPrintService.fieldError("stateCd", "CODE");
        }
        mapper.lockJob(jobId);
        JobState j = mapper.jobState(jobId);
        if (j == null) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        // 사람이 닫는 것은 "모르는" 작업만 — 보내기 전(QUEUED)·보내는 중을 닫으면 설치형 아웃박스가 그래도 보낸다
        boolean queuedCancel = queuedAgent && PrintStates.QUEUED.equals(j.getStateCd()) && PrintStates.CANCELLED.equals(to);
        if (!queuedCancel && !PrintStates.UNKNOWN.equals(j.getStateCd()) && !PrintStates.PRINTER_ERROR.equals(j.getStateCd())) {
            throw new BusinessException(HbErrorCode.CONFLICT);
        }
        event(jobId, to, "USER", reason, "USER", null, "KEY", null);
        return to;
    }

    // ------------------------------------------------------------------ 프린터 확인 (PRINTED — odometer 전후 비교)

    /**
     * 프린터 상태 한 번을 반영한다 — 설치형 확인 작업과 에이전트 하트비트가 같은 함수(판정식 한 곳).
     * 상태 행 잠금 → 상태 갱신 → 증가량으로 확인 안 된 작업을 보낸 순서대로 PRINTED → 오류면 남은 작업에 PRINTER_ERROR 한 번.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public ApplyResult apply(long printerId, Probe p, String src, Long terminalId) {
        mapper.ensureStatus(printerId, src);
        PrinterStatusRow s = mapper.lockStatus(printerId);
        Long base = s.getOdometerBase();
        int printed = 0;
        int errors = 0;
        List<PendingJob> pending = mapper.pendingJobs(printerId);
        int consumed = 0;
        if (p.reachable() && p.odometer() != null) {
            long odo = p.odometer();
            if (base == null || odo < base) {
                base = odo;                                // 첫 확인·프린터 초기화(교체) — 기준만 잡는다
            } else {
                long delta = odo - base;
                for (PendingJob j : pending) {
                    long need = (long) j.getCopies() * ZplLayout.labelsPerCopy(j.getZpl());
                    if (delta < need) {
                        break;
                    }
                    delta -= need;
                    base += need;
                    consumed++;
                    if (move(j.getJobId(), PrintStates.PRINTED, "ODOMETER", "odometer " + (base - need) + "→" + base, src, terminalId,
                            "AUTO", null) == Move.MOVED) {
                        printed++;
                        mapper.setStage(j.getJobId(), "CLOSED");            // 에이전트 작업이면 큐도 닫는다(설치형은 행 없음)
                    }
                }
                // 남은 증가량 = 다른 곳에서 찍은 장 — 기다리는 작업이 없고 보낼 작업도 없을 때만 버린다
                if (consumed == pending.size() && !mapper.hasUnsent(printerId)) {
                    base = odo;
                }
            }
        }
        if (p.reachable() && !p.errors().isEmpty()) {
            String first = p.errors().get(0);
            for (int i = consumed; i < pending.size(); i++) {
                PendingJob j = pending.get(i);
                if (PrintStates.SENT.equals(j.getStateCd())
                        && move(j.getJobId(), PrintStates.PRINTER_ERROR, first, ZebraStatus.join(p.errors()), src, terminalId, "AUTO",
                                null) == Move.MOVED) {
                    errors++;
                }
            }
        }
        s.setStateCd(p.state());
        s.setErrors(ZebraStatus.join(p.errors()));
        s.setWarnings(ZebraStatus.join(p.warnings()));
        if (p.odometer() != null) {
            s.setOdometer(p.odometer());
        }
        s.setOdometerBase(base);
        s.setSrc(src);
        s.setDetail(p.detail() == null ? null : trim(p.detail(), 200));
        mapper.updateStatus(s);
        return new ApplyResult(p.state(), printed, errors);
    }

    /** 화면 "지금 확인" — 확인 결과를 새 트랜잭션에서 반영(TCP 는 부른 쪽이 트랜잭션 밖에서) */
    @Transactional
    public ApplyResult applyNow(long printerId, Probe p, String src) {
        return apply(printerId, p, src, null);
    }

    @Transactional(readOnly = true)
    public PrinterStatusRow status(long printerId) {
        return mapper.statusOf(printerId);
    }

    // ------------------------------------------------------------------ 공통

    @SuppressWarnings("unchecked")
    static Map<String, String> values(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        Map<String, Object> raw = JSON.readValue(json, Map.class);
        java.util.LinkedHashMap<String, String> out = new java.util.LinkedHashMap<>();
        raw.forEach((k, v) -> out.put(k, v == null ? null : String.valueOf(v)));
        return out;
    }

    private OffsetDateTime now() {
        return clock.instant().atOffset(ZoneOffset.UTC);
    }

    static String trim(String s, int max) {
        if (s == null) {
            return null;
        }
        String t = s.codePoints().filter(c -> !Character.isISOControl(c))
                .collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append).toString();
        return t.length() > max ? t.substring(0, max) : t;
    }
}
