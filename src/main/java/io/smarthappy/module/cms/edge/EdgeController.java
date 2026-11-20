package io.smarthappy.module.cms.edge;

import java.util.ArrayList;
import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import io.smarthappy.module.cms.edge.EdgeDtos.ClaimedCmd;
import io.smarthappy.module.cms.edge.EdgeDtos.HeartbeatResult;
import io.smarthappy.module.cms.edge.EdgeDtos.MessagesResult;
import io.smarthappy.module.pop.TerminalPrincipal;
import io.smarthappy.platform.common.web.ApiResponse;
import io.smarthappy.platform.common.web.BusinessException;
import io.smarthappy.platform.common.web.HbErrorCode;
import io.smarthappy.platform.common.web.ErrorMessages;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 엣지·장비 어댑터 API (docs/65 16장) — `/ext/**` 체인(ExtSecurityConfig, 수집 장치 Bearer API 키 → 그 회사 범위).
 * 설치형 DIRECT 어댑터와 SaaS 엣지가 **같은 API** 를 쓴다 — 시험도 한 벌.
 *
 * - POST /ext/v1/edge/messages  {sentAt?, messages:[{route, …}]}(1~500) → 200 건별 결과(처리는 비동기). 과부하만 429 + Retry-After, 봉투 형식 오류 400
 * - POST /ext/v1/edge/heartbeat {bootId, sentAt?, birth?, equips:[…]} → 200
 * - POST /ext/v1/edge/commands/claim {max?} → 200 그 장치의 명령(EDGE) — lease 를 잡는다
 */
@RestController
@RequiredArgsConstructor
public class EdgeController {

    static final int MAX_MESSAGES = 500;
    private static final JsonMapper JSON = JsonMapper.builder().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).build();

    private final EdgeIngestService ingest;
    private final EquipCommandService commands;
    private final ErrorMessages errors;

    @PostMapping(value = "/ext/v1/edge/messages", consumes = "application/json")
    public ResponseEntity<ApiResponse<MessagesResult>> messages(@AuthenticationPrincipal TerminalPrincipal d, @RequestBody String body,
            HttpServletRequest request, HttpServletResponse response) throws java.io.IOException {
        JsonNode root = parse(body);
        JsonNode list = root.get("messages");
        if (list == null || !list.isArray()) {
            throw new BusinessException(HbErrorCode.BAD_REQUEST);
        }
        if (list.isEmpty() || list.size() > MAX_MESSAGES) {
            throw new BusinessException(HbErrorCode.TOO_MANY_ROWS, list.size(), MAX_MESSAGES);
        }
        List<JsonNode> items = new ArrayList<>(list.size());
        list.forEach(items::add);
        try {
            return ResponseEntity.ok(ApiResponse.ok(ingest.messages(d, items, EdgeIngestService.sentAt(root))));
        } catch (EdgeIngestService.Overloaded o) {
            // 유일한 "다시 보내라" — 상대는 Retry-After 뒤에 같은 묶음을 다시 보낸다(업무 키 멱등이라 중복은 걸러진다)
            response.setHeader("Retry-After", String.valueOf(o.retryAfterSec()));
            errors.write(request, response, HbErrorCode.TOO_MANY_REQUESTS);
            return null;
        }
    }

    @PostMapping(value = "/ext/v1/edge/heartbeat", consumes = "application/json")
    public ApiResponse<HeartbeatResult> heartbeat(@AuthenticationPrincipal TerminalPrincipal d, @RequestBody String body) {
        return ApiResponse.ok(ingest.heartbeat(d, parse(body)));
    }

    @PostMapping(value = "/ext/v1/edge/commands/claim")
    public ResponseEntity<ApiResponse<List<ClaimedCmd>>> claim(@AuthenticationPrincipal TerminalPrincipal d,
            @RequestBody(required = false) String body) {
        int max = EquipCommandService.CLAIM_MAX;
        if (body != null && !body.isBlank()) {
            JsonNode n = parse(body).get("max");
            if (n != null && n.isIntegralNumber()) {
                max = n.asInt();
            }
        }
        return ResponseEntity.status(HttpStatus.OK).body(ApiResponse.ok(commands.claim(d, max)));
    }

    private static JsonNode parse(String body) {
        try {
            JsonNode n = JSON.readTree(body);
            if (n == null || !n.isObject()) {
                throw new BusinessException(HbErrorCode.BAD_REQUEST);
            }
            return n;
        } catch (JacksonException e) {
            throw new BusinessException(HbErrorCode.BAD_REQUEST);
        }
    }
}
