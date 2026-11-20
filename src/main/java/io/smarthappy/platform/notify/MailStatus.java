package io.smarthappy.platform.notify;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * 외부 메일 한 통의 발송 결과 (docs/36 18.5 R2). SENT = SMTP 서버가 받았다까지 — 반송·읽음은 모른다.
 *
 * @param reason SENT 면 null. PENDING 이면 직전 재시도 사유(없으면 null), FAILED 면 실패 사유
 */
public record MailStatus(UUID messageId, State state, int attempts, OffsetDateTime sentAt, MailFailReason reason) {

    /** 화면 문구 키 {@code mail.state.{이름}} */
    public enum State {
        /** 보내기 전·보내는 중·재시도 대기 */
        PENDING,
        /** 메일 서버가 받음 */
        SENT,
        /** 최종 실패 — 플랫폼은 다시 보내지 않는다(R6) */
        FAILED,
        /** 보내기 전 호출자가 취소 (18.7) — 한 번도 보내 보지 않았다. 장애(FAILED)와 따로 센다 */
        CANCELED,
        /**
         * 재시도 대기 중 취소 (QA M-1) — 한 번 이상 보내 봤으므로 직전 시도(시간 초과 등)가 실제로는 배달됐을 수 있다.
         * "더 보내지 않음(이미 배달됐을 수 있음)" — "보내기 전 취소" 라고 쓰면 틀린 말이 된다
         */
        STOPPED
    }
}
