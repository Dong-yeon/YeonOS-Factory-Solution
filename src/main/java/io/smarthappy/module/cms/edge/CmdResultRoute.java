package io.smarthappy.module.cms.edge;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import io.smarthappy.module.cms.edge.EdgeDtos.CmdRow;
import io.smarthappy.platform.messaging.InboundKeys;
import io.smarthappy.platform.messaging.InboundMessage;
import io.smarthappy.platform.messaging.InboundOutcome;
import io.smarthappy.platform.messaging.InboundResult;
import io.smarthappy.platform.messaging.InboundRoute;
import io.smarthappy.platform.tenant.TenantContext;
import lombok.RequiredArgsConstructor;
import tools.jackson.databind.JsonNode;

/**
 * 수신 경로 CMD_RESULT (docs/65 16.4) — 엣지·어댑터의 명령 단계 보고(WRITE · READBACK · RESULT).
 *
 * 키 = cmdKey + phase, 내용 = ok·value·readback·status·errors.
 * - 단계는 순서가 뒤바뀌어 와도 모두 원장에 남는다. 끝난 명령의 상태는 바꾸지 않는다(UNKNOWN 만 늦은 결과로 확정)
 * - RESULT: "완료 + 오류 동반" = 실패({@link InboundOutcome}). DONE 이면 서버가 다시 읽은 값(readback)을 의도 값과 **다시 대조**
 * - 그 명령을 받은 장치만 보고할 수 있다(다른 장치·다른 회사 cmdKey = 거부)
 */
@Component
@RequiredArgsConstructor
public class CmdResultRoute implements InboundRoute {

    public static final String ROUTE = "CMD_RESULT";
    static final Set<String> PHASES = Set.of("WRITE", "READBACK", "RESULT");
    static final Set<String> OPEN = Set.of("REQUESTED", "SENT", "CLAIMED", "WRITTEN");

    private final EdgeMapper mapper;

    @Override
    public String routeCd() {
        return ROUTE;
    }

    @Override
    public List<String> keyFields() {
        return List.of("cmdKey", "phase");
    }

    @Override
    public List<String> contentFields() {
        return List.of("ok", "value", "readback", "status", "errors");
    }

    @Override
    public String partField() {
        return "cmdKey";
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public InboundResult handle(InboundMessage m) {
        UUID key;
        try {
            key = UUID.fromString(EdgeSupport.text(m.body(), "cmdKey", 40));
        } catch (RuntimeException e) {
            throw new Reject("CMD_KEY");
        }
        String phase = EdgeSupport.upper(EdgeSupport.text(m.body(), "phase", 20));
        if (phase == null || !PHASES.contains(phase)) {
            throw new Reject("PHASE");
        }
        CmdRow cmd = mapper.lockCmdByKey(key);
        if (cmd == null) {
            throw new Reject("CMD");                      // 없는 명령·다른 회사 명령(RLS)
        }
        if (!Objects.equals(cmd.getTerminalId(), m.sourceId())) {
            throw new Reject("NOT_MY_CMD");
        }
        Instant at = m.occurredAt() == null ? m.receivedAt() : m.occurredAt();
        String by = TenantContext.currentActor();
        boolean open = OPEN.contains(cmd.getStatus());
        switch (phase) {
            case "WRITE" -> {
                Boolean ok = EdgeSupport.bool(m.body(), "ok");
                String value = EdgeSupport.text(m.body(), "value", 50);
                step(cmd, "WRITE", ok == null ? null : ok ? "Y" : "N", value, EdgeSupport.text(m.body(), "error", 500), m, at, by);
                if (open && Boolean.FALSE.equals(ok)) {
                    mapper.setCmdStatus(cmd.getCmdId(), "FAILED", "WRITE_ERROR", null);
                } else if (open && !"WRITTEN".equals(cmd.getStatus())) {
                    mapper.setCmdStatus(cmd.getCmdId(), "WRITTEN", null, null);
                }
                return InboundResult.done(open ? null : "LATE_STEP");
            }
            case "READBACK" -> {
                String rb = EdgeSupport.text(m.body(), "readback", 50);
                if (rb == null) {
                    rb = EdgeSupport.text(m.body(), "value", 50);
                }
                String norm = EdgeSupport.normalize(cmd.getDataType(), rb);
                step(cmd, "READBACK", norm == null ? null : norm.equals(cmd.getCmdValue()) ? "Y" : "N", rb, null, m, at, by);
                return InboundResult.done(open ? null : "LATE_STEP");
            }
            default -> {
                JsonNode errors = m.body().get("errors");
                String outcome = InboundOutcome.of(EdgeSupport.text(m.body(), "status", 20), errors);
                String rb = EdgeSupport.text(m.body(), "readback", 50);
                String norm = EdgeSupport.normalize(cmd.getDataType(), rb);
                String resultCd = null;
                if ("DONE".equals(outcome) && (norm == null || !norm.equals(cmd.getCmdValue()))) {
                    outcome = "FAILED";
                    resultCd = "VERIFY_MISMATCH";                 // 엣지가 완료라 해도 다시 읽은 값이 의도와 다르면 실패
                } else if ("FAILED".equals(outcome)) {
                    resultCd = errors == null || errors.isNull() || errors.isEmpty() ? "FAILED" : "ERRORS";
                } else if ("REJECTED".equals(outcome)) {
                    resultCd = EdgeSupport.upper(EdgeSupport.text(m.body(), "reason", 50));
                    resultCd = resultCd == null ? "EDGE_REJECTED" : resultCd;
                } else if ("UNKNOWN".equals(outcome)) {
                    resultCd = "STATUS";
                }
                String detail = errors == null || errors.isNull() || errors.isEmpty() ? EdgeSupport.text(m.body(), "status", 20)
                        : InboundKeys.canonical(errors);
                step(cmd, "RESULT", "DONE".equals(outcome) ? "Y" : "N", rb, detail, m, at, by);
                boolean resolvable = open || "UNKNOWN".equals(cmd.getStatus());
                if (!resolvable) {
                    return InboundResult.done("LATE_RESULT");    // 끝난 명령 — 단계만 남김
                }
                mapper.setCmdStatus(cmd.getCmdId(), outcome, resultCd, norm == null ? cut(rb) : norm);
                return InboundResult.done(outcome);
            }
        }
    }

    private void step(CmdRow cmd, String phase, String okYn, String value, String detail, InboundMessage m, Instant at, String by) {
        mapper.insertStep(cmd.getCmdId(), phase, okYn, cut(value), detail, "EDGE", m.sourceId(), m.inboundId(), at, "AUTO", by);
    }

    private static String cut(String s) {
        return s == null ? null : s.length() > 50 ? s.substring(0, 50) : s;
    }
}
