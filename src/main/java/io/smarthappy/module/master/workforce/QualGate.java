package io.smarthappy.module.master.workforce;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import io.smarthappy.module.master.workforce.WorkforceDtos.GateMissing;
import io.smarthappy.module.master.workforce.WorkforceDtos.GateReqRow;
import io.smarthappy.module.master.workforce.WorkforceDtos.GateResult;
import io.smarthappy.module.master.workforce.WorkforceDtos.WorkerQualRow;
import io.smarthappy.platform.policy.PolicyService;
import lombok.RequiredArgsConstructor;

/**
 * 자격 게이트 (docs/25 4.2) — 작업자가 이 공정을 그 작업일에 해도 되는가.
 * 실제 모드 = min(공장 정책 QUAL_GATE_MODE, 요구 행의 gate_mode). 빠진 자격이 없으면 OK.
 * 1단계는 판정만(화면 미리보기), 착수 차단은 5b 가 이 결과로.
 */
@Component
@RequiredArgsConstructor
public class QualGate {

    static final String POLICY = "QUAL_GATE_MODE";
    private static final List<String> ORDER = List.of("OFF", "WARN", "BLOCK");

    private final WorkforceMapper mapper;
    private final PolicyService policies;

    @Transactional(readOnly = true)
    public GateResult check(String plantCd, long procId, long workerId, LocalDate workDay) {
        String policy = policies.value(POLICY, plantCd);
        if ("OFF".equals(policy)) {
            return new GateResult("OK", policy, List.of());
        }
        List<GateReqRow> reqs = mapper.findGateReqs(procId);
        if (reqs.isEmpty()) {
            return new GateResult("OK", policy, List.of());
        }
        Map<Long, List<WorkerQualRow>> held = mapper.findHeld(workerId, reqs.stream().map(GateReqRow::getQualId).toList())
                .stream().collect(Collectors.groupingBy(WorkerQualRow::getQualId));
        List<GateMissing> missing = new ArrayList<>();
        String mode = "OK";
        for (GateReqRow r : reqs) {
            String reason = reason(held.getOrDefault(r.getQualId(), List.of()), workDay);
            if (reason == null) {
                continue;
            }
            String m = min(policy, r.getGateMode());
            missing.add(new GateMissing(r.getQualCd(), r.getQualNm(), m, reason));
            if ("BLOCK".equals(m) || "WARN".equals(m) && "OK".equals(mode)) {
                mode = m;
            }
        }
        return new GateResult(mode, policy, missing);
    }

    /** 유효 행이 하나라도 있으면 null(통과). 보유 행이 없으면 NONE, 있으면 가장 최근 취득 행의 상태(EXPIRED·REVOKED·NOT_YET) */
    static String reason(List<WorkerQualRow> rows, LocalDate day) {
        if (rows.isEmpty()) {
            return "NONE";
        }
        String best = null;
        LocalDate bestAcq = null;
        for (WorkerQualRow r : rows) {
            String s = WorkerService.state(r, day);
            if ("VALID".equals(s)) {
                return null;
            }
            if (bestAcq == null || r.getAcquiredOn().isAfter(bestAcq)) {
                best = s;
                bestAcq = r.getAcquiredOn();
            }
        }
        return best;
    }

    static String min(String a, String b) {
        return ORDER.indexOf(a) <= ORDER.indexOf(b) ? a : b;
    }
}
