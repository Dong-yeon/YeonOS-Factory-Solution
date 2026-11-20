package io.smarthappy.platform.messaging;

import java.time.Instant;

import tools.jackson.databind.JsonNode;

/**
 * 처리기가 경로에 넘기는 원문 한 건 (docs/65 16.2).
 *
 * @param sourceId   보낸 장치 id(모듈이 정한 뜻 — 장비 연동은 sys_terminal), 없으면 null
 * @param sourceCd   보낸 쪽 표기(행위자와 같은 값)
 * @param orderNo    순번(경로 orderField), 없으면 null
 * @param occurredAt 현장 발생 시각(경로 occurredField), 없으면 null
 * @param skewMs     받은 시각 − 보낸 쪽 봉투 시각, 모르면 null
 */
public record InboundMessage(long inboundId, String routeCd, Long sourceId, String sourceCd, String bizKey, String keyHash,
        JsonNode body, Long orderNo, Instant occurredAt, Instant receivedAt, Long skewMs) {
}
