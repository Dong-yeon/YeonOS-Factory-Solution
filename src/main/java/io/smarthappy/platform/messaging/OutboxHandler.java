package io.smarthappy.platform.messaging;

/**
 * 아웃박스 토픽 하나의 송신기 (ERP 송신·메일·알림·라벨 인쇄 등). 빈으로 등록하면 토픽으로 찾는다.
 *
 * 규칙
 * - {@link #send} 는 **DB 트랜잭션 밖**에서 불린다(릴레이 2단계). 여기서 DB 를 쓰지 않는다 — 회사 범위도 열려 있지 않다
 * - 같은 메시지가 두 번 갈 수 있다(최소 1회 전달). 수신측은 {@link OutboxMessage#messageId()} 로 중복을 거른다
 * - 예외를 던지면 재시도. 단 {@link #retryOnReadTimeout()} 가 false 면 읽기 타임아웃은 실패로 끝낸다
 *   (메일·알림: 상대가 이미 받았을 수 있어 중복이 누락보다 나쁨, 01 5.8)
 */
public interface OutboxHandler {

    /** 토픽 이름 (sys_outbox.topic, 대문자) */
    String topic();

    SendResult send(OutboxMessage message) throws Exception;

    default boolean retryOnReadTimeout() {
        return true;
    }

    /** 송신 결과 */
    record SendResult(Outcome outcome, String error) {
        public static SendResult sent() {
            return new SendResult(Outcome.SENT, null);
        }

        public static SendResult retry(String error) {
            return new SendResult(Outcome.RETRY, error);
        }

        public static SendResult fail(String error) {
            return new SendResult(Outcome.FAIL, error);
        }
    }

    enum Outcome {
        SENT, RETRY, FAIL
    }
}
