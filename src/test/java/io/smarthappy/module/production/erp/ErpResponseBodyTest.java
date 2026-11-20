package io.smarthappy.module.production.erp;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.smarthappy.module.production.erp.ErpPort.ErpDoc;
import io.smarthappy.module.production.erp.ErpPort.ErpResult;
import io.smarthappy.module.production.erp.ErpPort.Kind;
import io.smarthappy.platform.messaging.OutboxHandler.Outcome;
import io.smarthappy.platform.messaging.OutboxHandler.SendResult;
import io.smarthappy.platform.messaging.OutboxMessage;

/**
 * ERP 송신 응답 본문은 last_error 에 남기지 않는다(사용자 결정 Q-O1, docs/36 19장 O13·docs/25 4.50 — 시스템관리 OutboxResponseBodyTest 의 MES 몫).
 * 어댑터가 계약을 어기고 detail 에 응답 본문(JSON·HTML·여러 줄·긴 글)을 실어도 송신기가 짧은 사유(HTTP 상태)만 남기는지, 계약을 지킨 짧은 사유는 그대로인지 본다.
 */
class ErpResponseBodyTest {

    static final String BODY = "RESPONSE-BODY-" + UUID.randomUUID();

    /** 정해 둔 결과만 돌려주는 어댑터 */
    static ErpOutboundHandler handler(ErpResult result) {
        ErpPort port = new ErpPort() {
            @Override
            public String system() {
                return "ZT";
            }

            @Override
            public ErpResult post(ErpDoc doc) {
                return result;
            }

            @Override
            public int lookup(String compCd, String bizKey) {
                return 0;
            }
        };
        return new ErpOutboundHandler("ERP_WO_RESULT", "WO_RESULT", List.of(port));
    }

    static SendResult send(Kind kind, String detail) {
        return handler(ErpResult.of(kind, detail)).send(new OutboxMessage(1L, "ANY", UUID.randomUUID(), "ERP_WO_RESULT", "WO:1",
                Map.of("system", "ZT"), 0));
    }

    @Test
    void 응답_본문은_상태만_남기고_버린다() {
        String json = "400 {\"error\":\"" + BODY + "\",\"customer\":\"홍길동 010-1234-5678\"}";
        SendResult r = send(Kind.REJECTED, json);
        assertThat(r.outcome()).isEqualTo(Outcome.FAIL);
        assertThat(r.error()).isEqualTo("REJECTED: HTTP 400 (detail omitted)").doesNotContain(BODY).doesNotContain("010-1234");

        assertThat(send(Kind.RETRY, "HTTP 503 <html><body>" + BODY + "</body></html>").error())
                .isEqualTo("RETRY: HTTP 503 (detail omitted)");
        assertThat(send(Kind.RATE_LIMITED, "429 too many\n" + BODY).error()).isEqualTo("RATE_LIMITED: HTTP 429 (detail omitted)");
        // 상태 숫자도 없는 본문(사람 이름·전화가 섞인 글) → 사유 없음
        assertThat(send(Kind.UNKNOWN, "거래처 홍길동 " + BODY).error()).startsWith("UNKNOWN: detail omitted").doesNotContain(BODY)
                .doesNotContain("홍길동");
        // 허용 글자만이어도 80자를 넘으면 본문으로 본다
        assertThat(send(Kind.REJECTED, "400 " + "A".repeat(100)).error()).isEqualTo("REJECTED: HTTP 400 (detail omitted)");
    }

    @Test
    void 계약을_지킨_짧은_사유는_그대로() {
        assertThat(send(Kind.REJECTED, " 400 ITEM_UNMAPPED ").error()).isEqualTo("REJECTED: 400 ITEM_UNMAPPED");
        assertThat(send(Kind.RATE_LIMITED, "429 too many requests").error()).isEqualTo("RATE_LIMITED: 429 too many requests");
        assertThat(send(Kind.UNKNOWN, "read timeout after store").error()).startsWith("UNKNOWN: read timeout after store");
        assertThat(send(Kind.RETRY, null).error()).isEqualTo("RETRY: -");
        assertThat(send(Kind.RETRY, "  ").error()).isEqualTo("RETRY: -");
        assertThat(handler(ErpResult.ok("FERP-1")).send(new OutboxMessage(1L, "ANY", UUID.randomUUID(), "ERP_WO_RESULT", "WO:1",
                Map.of("system", "ZT"), 0)).outcome()).isEqualTo(Outcome.SENT);
    }
}
