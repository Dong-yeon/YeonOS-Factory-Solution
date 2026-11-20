package io.smarthappy.module.cms.edge;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

import io.smarthappy.platform.messaging.InboundKeys;
import io.smarthappy.platform.messaging.InboundOutcome;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 장비 연동 E0 순수 규칙 (docs/65 16.2·16.5·16.6·16.7) — PackML 전이 허용표·대응표, 업무 키 정규화, "완료 ≠ 성공", 끊김 판정식, 값 정규화.
 * DB 없이 돈다.
 */
class EdgeRulesTest {

    static final JsonMapper JSON = JsonMapper.builder().build();

    private static JsonNode json(String s) {
        return JSON.readTree(s);
    }

    @Test
    void PackML_전이_허용표() {
        // 정상 흐름: Stopped → Idle(Reset) → Starting → Execute → Complete → Idle
        assertThat(PackMl.allowed("STOPPED", "IDLE")).isTrue();
        assertThat(PackMl.allowed("IDLE", "STARTING")).isTrue();
        assertThat(PackMl.allowed("STARTING", "EXECUTE")).isTrue();
        assertThat(PackMl.allowed("EXECUTE", "COMPLETE")).isTrue();
        assertThat(PackMl.allowed("COMPLETE", "IDLE")).isTrue();
        // 보류(내부)·일시 정지(외부)는 Execute 에서 갔다 돌아온다
        assertThat(PackMl.allowed("EXECUTE", "HELD")).isTrue();
        assertThat(PackMl.allowed("HELD", "EXECUTE")).isTrue();
        assertThat(PackMl.allowed("SUSPENDED", "EXECUTE")).isTrue();
        // 비상 정지는 어디서나, 빠져나오는 길은 Clear → Stopped 뿐
        for (String s : PackMl.STATES) {
            assertThat(PackMl.allowed(s, "ABORTED")).as(s + " → ABORTED").isTrue();
        }
        assertThat(PackMl.allowed("ABORTED", "EXECUTE")).isFalse();
        assertThat(PackMl.allowed("ABORTED", "IDLE")).isFalse();
        assertThat(PackMl.allowed("ABORTED", "STOPPED")).isTrue();
        // 건너뛰기 금지
        assertThat(PackMl.allowed("IDLE", "EXECUTE")).isFalse();
        assertThat(PackMl.allowed("STOPPED", "EXECUTE")).isFalse();
        assertThat(PackMl.allowed("SUSPENDED", "HELD")).isFalse();
        assertThat(PackMl.allowed("COMPLETE", "EXECUTE")).isFalse();
        // 같은 상태·첫 상태
        assertThat(PackMl.allowed("EXECUTE", "EXECUTE")).isTrue();
        assertThat(PackMl.allowed(null, "EXECUTE")).isTrue();
        // 정규화
        assertThat(PackMl.state(" execute ")).isEqualTo("EXECUTE");
        assertThat(PackMl.state("RUNNING")).isNull();
        assertThat(PackMl.mode(null)).isEqualTo("PRODUCTION");
        assertThat(PackMl.mode("turbo")).isNull();
    }

    @Test
    void PackML_에서_E10_분류와_MachineryItemState_대응() {
        assertThat(PackMl.stateCat("EXECUTE", "PRODUCTION")).isEqualTo("PRD");
        assertThat(PackMl.stateCat("IDLE", "PRODUCTION")).isEqualTo("SBY");
        assertThat(PackMl.stateCat("SUSPENDED", "PRODUCTION")).isEqualTo("SBY");   // 외부 원인(자재 없음·후공정 막힘)
        assertThat(PackMl.stateCat("HELD", "PRODUCTION")).isEqualTo("UDT");        // 내부 원인
        assertThat(PackMl.stateCat("ABORTED", "MAINTENANCE")).isEqualTo("UDT");    // 비상 정지는 모드 무관
        assertThat(PackMl.stateCat("EXECUTE", "MAINTENANCE")).isEqualTo("SDT");
        assertThat(PackMl.stateCat("EXECUTE", "MANUAL")).isEqualTo("ENG");
        assertThat(PackMl.machineryItemState("EXECUTE", "PRODUCTION")).isEqualTo("Executing");
        assertThat(PackMl.machineryItemState("EXECUTE", "MANUAL")).isEqualTo("NotExecuting");
        assertThat(PackMl.machineryItemState("IDLE", "PRODUCTION")).isEqualTo("NotExecuting");
        assertThat(PackMl.machineryItemState("ABORTED", "PRODUCTION")).isEqualTo("OutOfService");
        assertThat(PackMl.machineryItemState("IDLE", "MAINTENANCE")).isEqualTo("OutOfService");
        assertThat(PackMl.machineryItemState(null, "PRODUCTION")).isEqualTo("NotAvailable");
    }

    @Test
    void 업무_키_정규화는_공백_숫자_꼴_객체_칸_순서에_무관() {
        List<String> f = List.of("equipCd", "seq");
        assertThat(InboundKeys.join(json("{\"equipCd\":\" EQ1 \",\"seq\":5,\"msgId\":\"a\"}"), f))
                .isEqualTo(InboundKeys.join(json("{\"seq\":5,\"equipCd\":\"EQ1\",\"msgId\":\"b\"}"), f));
        assertThat(InboundKeys.canonical(json("1.50"))).isEqualTo("1.5");
        assertThat(InboundKeys.canonical(json("-0.0"))).isEqualTo("0");
        assertThat(InboundKeys.canonical(json("{\"b\":1,\"a\":[2,\"x \"]}"))).isEqualTo(InboundKeys.canonical(json("{\"a\":[2,\"x\"],\"b\":1}")));
        // 배열은 순서가 뜻이다
        assertThat(InboundKeys.canonical(json("[1,2]"))).isNotEqualTo(InboundKeys.canonical(json("[2,1]")));
        assertThat(InboundKeys.firstMissing(json("{\"equipCd\":\"EQ1\",\"seq\":null}"), f)).isEqualTo("seq");
        assertThat(InboundKeys.firstMissing(json("{\"equipCd\":\"  \",\"seq\":1}"), f)).isEqualTo("equipCd");
        assertThat(InboundKeys.sha256Hex("a")).hasSize(64);
    }

    @Test
    void 완료라도_오류가_있으면_실패_상태는_아는_글자만() {
        assertThat(InboundOutcome.of("FINISHED", null)).isEqualTo("DONE");
        assertThat(InboundOutcome.of("done", json("[]"))).isEqualTo("DONE");
        assertThat(InboundOutcome.of("FINISHED", json("[{\"errorType\":\"x\"}]"))).isEqualTo("FAILED");
        assertThat(InboundOutcome.of("OK", json("\"jam\""))).isEqualTo("FAILED");
        assertThat(InboundOutcome.of("ERROR", null)).isEqualTo("FAILED");
        assertThat(InboundOutcome.of("REJECTED", null)).isEqualTo("REJECTED");
        // 상태 코드를 범위로 묶지 않는다 — "5" 나 모르는 글자는 성공이 아니다
        assertThat(InboundOutcome.of("5", null)).isEqualTo("UNKNOWN");
        assertThat(InboundOutcome.of(null, null)).isEqualTo("UNKNOWN");
    }

    @Test
    void 끊김_판정은_마지막_수신_더하기_주기_곱하기_놓친_횟수() {
        Instant seen = Instant.parse("2026-11-03T00:00:00Z");
        assertThat(EquipLinkJudge.judge("Y", seen, 5, 3, seen.plusSeconds(15))).isEqualTo(EquipLinkJudge.ONLINE);
        assertThat(EquipLinkJudge.judge("Y", seen, 5, 3, seen.plusSeconds(16))).isEqualTo(EquipLinkJudge.LOST);
        assertThat(EquipLinkJudge.judge("Y", null, 5, 3, seen)).isEqualTo(EquipLinkJudge.NEVER);
        assertThat(EquipLinkJudge.judge("N", seen, 5, 3, seen)).isEqualTo(EquipLinkJudge.OFF);
    }

    @Test
    void 태그_값_정규화는_의도와_다시_읽은_값을_같은_꼴로() {
        assertThat(EdgeSupport.normalize("NUM", "055.000")).isEqualTo("55");
        assertThat(EdgeSupport.normalize("NUM", "-0")).isEqualTo("0");
        assertThat(EdgeSupport.normalize("NUM", "1e3")).isNull();
        assertThat(EdgeSupport.normalize("NUM", "12.1234567")).isNull();
        assertThat(EdgeSupport.normalize("BOOL", "1")).isEqualTo("true");
        assertThat(EdgeSupport.normalize("BOOL", "FALSE")).isEqualTo("false");
        assertThat(EdgeSupport.normalize("BOOL", "yes")).isNull();
    }
}
