package io.smarthappy.module.production.erp;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import io.smarthappy.module.production.erp.ErpPort.ErpDoc;
import io.smarthappy.module.production.erp.ErpPort.ErpResult;
import io.smarthappy.platform.messaging.OutboxHandler;
import io.smarthappy.platform.messaging.OutboxMessage;

/**
 * 아웃박스 ERP 송신기 (docs/25 4.11 표) — 토픽마다 하나(ErpConfig). 트랜잭션·회사 범위 밖, DB 금지.
 *
 * ErpPort 결과 → 아웃박스: OK=SENT, RETRY·RATE_LIMITED=RETRY, REJECTED=FAIL, UNKNOWN=FAIL("UNKNOWN") — 자동 재전송 없음(D19).
 * 본문의 system 이 가리키는 어댑터로 보낸다(발행 때의 정책 값 — 그 뒤 정책이 바뀌어도 이미 낸 메시지는 그대로 간다).
 * 어댑터의 detail 은 짧은 사유만 남긴다({@link #reason}) — 응답 본문을 last_error 에 싣지 않는다(Q-O1, docs/25 4.50).
 */
public class ErpOutboundHandler implements OutboxHandler {

    /** 짧은 사유로 받아들이는 모양 — 글자·숫자·공백·_ . : / ( ) - 만, 80자까지(JSON·HTML·여러 줄이면 본문으로 본다) */
    private static final Pattern SHORT = Pattern.compile("[A-Za-z0-9 _.:/()-]{1,80}");
    /** 본문으로 보이면 앞의 HTTP 상태 숫자만 살린다 */
    private static final Pattern STATUS = Pattern.compile("^\\s*(?:HTTP\\s*)?([1-5]\\d\\d)\\b");
    static final String OMITTED = "detail omitted";

    private final String topic;
    private final String kind;
    private final List<ErpPort> ports;

    public ErpOutboundHandler(String topic, String kind, List<ErpPort> ports) {
        this.topic = topic;
        this.kind = kind;
        this.ports = ports;
    }

    @Override
    public String topic() {
        return topic;
    }

    /** 상대가 멱등이 아니다 — 소켓 타임아웃이 올라와도 다시 보내지 않는다 */
    @Override
    public boolean retryOnReadTimeout() {
        return false;
    }

    @Override
    public SendResult send(OutboxMessage m) {
        Object system = m.payload().get("system");
        ErpPort port = ports.stream().filter(p -> p.system().equals(system)).findFirst().orElse(null);
        if (port == null) {
            return SendResult.fail("ERP adapter not found: " + system);
        }
        ErpResult r = port.post(new ErpDoc(m.compCd(), kind, m.bizKey(), m.payload()));
        if (r == null) {
            return SendResult.retry("no result");
        }
        return switch (r.kind()) {
            case OK -> SendResult.sent();
            case RETRY, RATE_LIMITED -> SendResult.retry(r.kind() + ": " + reason(r.detail()));
            case REJECTED -> SendResult.fail("REJECTED: " + reason(r.detail()));
            case UNKNOWN -> SendResult.fail("UNKNOWN: " + reason(r.detail()) + " — 자동 재전송 안 함, ERP 에서 전표 확인");
        };
    }

    /**
     * 어댑터 detail → last_error 에 남길 짧은 사유. 계약(짧은 사유만)을 지킨 값은 그대로(앞뒤 공백만 정리),
     * 아니면(응답 본문으로 보임 — 허용 글자 밖·80자 초과·여러 줄) 앞의 HTTP 상태 숫자만 "HTTP 400", 그것도 없으면 "detail omitted"
     */
    static String reason(String detail) {
        if (detail == null || detail.isBlank()) {
            return "-";
        }
        String d = detail.strip();
        if (SHORT.matcher(d).matches()) {
            return d;
        }
        Matcher m = STATUS.matcher(d);
        return m.find() ? "HTTP " + m.group(1) + " (" + OMITTED + ")" : OMITTED;
    }
}
