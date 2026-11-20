package io.smarthappy.module.pop;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import io.smarthappy.module.production.link.StartGate;
import io.smarthappy.module.production.link.StartGate.GateReason;
import io.smarthappy.module.production.link.StartGate.GateVerdict;
import io.smarthappy.module.production.link.StartGate.StartContext;
import io.smarthappy.platform.policy.PolicyService;

/**
 * 착수 게이트 빈들을 부른다 (docs/25 4.21, docs/29_P3 12.9 G-a·G-e). 가장 나쁜 mode, 빈마다 세이브포인트(NESTED) —
 * 빈이 SQL 오류를 내도 착수 트랜잭션이 aborted 가 되지 않게 되돌리고, 이유 GATE_ERROR + 정책 START_GATE_ON_ERROR(기본 BLOCK).
 */
@Component
class StartGates {

    private static final Logger log = LoggerFactory.getLogger(StartGates.class);
    private static final Set<String> MODES = Set.of(StartGate.OK, StartGate.WARN, StartGate.BLOCK);
    static final String ERROR_CODE = "GATE_ERROR";

    /** 이유 한 줄 — source = 빈 key, message = 요청 로캘로 푼 문구 */
    record Hit(String source, String code, String message, String refType, Long refId) {
    }

    /** 합친 결과 — 빈이 없거나 모두 OK 면 (OK, 빈 목록) */
    record Outcome(String mode, List<Hit> hits) {
    }

    private final ObjectProvider<StartGate> gates;
    private final TransactionTemplate nested;
    private final PolicyService policies;
    private final MessageSource messages;

    StartGates(ObjectProvider<StartGate> gates, PlatformTransactionManager txm, PolicyService policies, MessageSource messages) {
        this.gates = gates;
        this.nested = new TransactionTemplate(txm);
        this.nested.setPropagationBehavior(TransactionDefinition.PROPAGATION_NESTED);
        this.policies = policies;
        this.messages = messages;
    }

    /** 부르는 쪽 트랜잭션 안에서(착수 = 쓰기, 미리보기 = 읽기 전용) */
    Outcome check(StartContext ctx) {
        Locale locale = LocaleContextHolder.getLocale();
        String mode = StartGate.OK;
        List<Hit> hits = new ArrayList<>();
        for (StartGate g : gates.orderedStream().toList()) {
            String key = g.key();
            GateVerdict v;
            try {
                v = nested.execute(s -> g.check(ctx));
            } catch (RuntimeException e) {
                log.warn("start gate {} failed on wo_proc {} — treated as {}", key, ctx.woProcId(), onError(), e);
                v = null;
            }
            if (v == null || v.mode() == null || !MODES.contains(v.mode())) {
                v = new GateVerdict(onError(), List.of(new GateReason(ERROR_CODE, "pop.gate.error", List.of(key), null, null)));
            }
            mode = worst(mode, v.mode());
            if (!StartGate.OK.equals(v.mode())) {
                for (GateReason r : v.reasons() == null ? List.<GateReason>of() : v.reasons()) {
                    Object[] args = r.args() == null ? new Object[0] : r.args().toArray();
                    String text = r.messageKey() == null ? r.code() : messages.getMessage(r.messageKey(), args, r.code(), locale);
                    hits.add(new Hit(key, r.code(), text, r.refType(), r.refId()));
                }
            }
        }
        return new Outcome(mode, hits);
    }

    private String onError() {
        return "WARN".equals(policies.value("START_GATE_ON_ERROR")) ? StartGate.WARN : StartGate.BLOCK;
    }

    static String worst(String a, String b) {
        if (StartGate.BLOCK.equals(a) || StartGate.BLOCK.equals(b)) {
            return StartGate.BLOCK;
        }
        return StartGate.WARN.equals(a) || StartGate.WARN.equals(b) ? StartGate.WARN : StartGate.OK;
    }
}
