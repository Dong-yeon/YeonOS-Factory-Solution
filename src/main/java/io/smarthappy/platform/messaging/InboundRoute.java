package io.smarthappy.platform.messaging;

import java.util.List;

/**
 * 수신 경로 하나 (docs/65 5.2·16.2) — 모듈이 빈으로 등록하면 {@link InboundJournal} 이 원문을 저장하고 {@link InboundProcessor} 가 부른다.
 *
 * 키 규칙(경로마다 여기서 정한다 — 플랫폼 수신 계층의 "경로별 키 규칙")
 * - {@link #keyFields()} = 업무 키(상관키 + 상태[+ seq]). 상대 메시지 ID 를 넣지 않는다 — 같은 요청을 다시 보내도 ID 는 매번 바뀐다
 * - {@link #contentFields()} = 같은 키일 때 "같은 내용인가" 를 가르는 칸. 같으면 중복(DUP), 다르면 충돌(CONFLICT — 처리 안 함)
 * - 칸 값은 정규화해서 비교한다(글자 앞뒤 공백, 숫자 1.50 = 1.5, 객체 칸 순서 무관)
 *
 * {@link #handle} 규칙
 * - 처리 트랜잭션 안·회사 범위 안에서 불린다(행위자 = 보낸 장치). 같은 업무 키는 한 번만 불린다(sys_inbox `IN:{경로}`)
 * - 결과를 돌려주면 그 트랜잭션이 커밋된다(STALE·REJECTED 도 그때 남긴 사건은 남는다)
 * - {@link Reject} 를 던지면 처리 트랜잭션을 되돌리고 REJECTED 로만 남긴다(다시 시도 안 함)
 * - 그 밖의 예외는 일시 오류로 보고 뒤로 미뤄 다시 — 상한이면 격리(QUARANTINE). 상대에게는 가지 않는다
 */
public interface InboundRoute {

    /** 경로 코드 — {@code ^[A-Z][A-Z0-9_]{2,29}$} */
    String routeCd();

    /** 업무 키 칸(모두 값이 있어야 저장한다) */
    List<String> keyFields();

    /** 내용 칸 — 같은 키·다른 내용 = 충돌 */
    List<String> contentFields();

    /** 처리 줄 세움 단위 칸(예: equipCd) — 없으면 null */
    default String partField() {
        return null;
    }

    /** 순번 칸(예: seq, 0 이상 정수) — 같은 줄 안에서 이 순서로 처리. 없으면 null */
    default String orderField() {
        return null;
    }

    /** 현장 발생 시각 칸(ISO-8601 오프셋) — 없으면 null */
    default String occurredField() {
        return "occurredAt";
    }

    InboundResult handle(InboundMessage message);

    /** 처리 거부 — 트랜잭션을 되돌리고 REJECTED(code) 로 남긴다 */
    final class Reject extends RuntimeException {
        private final String code;

        public Reject(String code) {
            super(code, null, false, false);
            this.code = code;
        }

        public String code() {
            return code;
        }
    }
}
