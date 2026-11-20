package io.smarthappy.module.cms;

import java.util.ArrayList;
import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import io.smarthappy.module.cms.CollectorDtos.IngestResult;
import io.smarthappy.module.pop.TerminalPrincipal;
import io.smarthappy.platform.common.web.ApiResponse;
import io.smarthappy.platform.common.web.BusinessException;
import io.smarthappy.platform.common.web.HbErrorCode;
import lombok.RequiredArgsConstructor;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 수집기 표준 이벤트 수신 (02 2.2(a), docs/25 4.1·4.8) — ExtSecurityConfig(ROLE_DEVICE) 가 지킨다.
 * 본문 = 이벤트 하나 또는 배열(최대 500). 묶음 하나 = 트랜잭션 하나, 건마다 저장점(한 건이 틀려도 나머지는 저장, docs/70 W1) → 202 + 건별 결과.
 * 숫자는 BigDecimal 로 읽는다(Double 을 거치면 소수가 조용히 바뀐다 — 7단계 QA 교훈).
 */
@RestController
@RequiredArgsConstructor
public class EquipEventController {

    static final int MAX_EVENTS = 500;
    private static final JsonMapper JSON = JsonMapper.builder().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).build();

    private final EquipEventService events;

    @PostMapping(value = "/ext/v1/equip-events", consumes = "application/json")
    public ResponseEntity<ApiResponse<IngestResult>> ingest(@AuthenticationPrincipal TerminalPrincipal d, @RequestBody String body) {
        JsonNode root;
        try {
            root = JSON.readTree(body);
        } catch (JacksonException e) {
            throw new BusinessException(HbErrorCode.BAD_REQUEST);
        }
        List<JsonNode> list = new ArrayList<>();
        if (root != null && root.isArray()) {
            root.forEach(list::add);
        } else if (root != null && root.isObject()) {
            list.add(root);
        } else {
            throw new BusinessException(HbErrorCode.BAD_REQUEST);
        }
        if (list.isEmpty() || list.size() > MAX_EVENTS) {
            throw new BusinessException(HbErrorCode.TOO_MANY_ROWS, list.size(), MAX_EVENTS);
        }
        IngestResult result = events.ingest(d, list);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(ApiResponse.ok(result));
    }
}
