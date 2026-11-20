package io.smarthappy.platform.notify;

/**
 * 외부 메일 실패·대기 사유 (docs/36 18.5 R3). 송신기가 {@code last_error} 앞에 {@code 코드: 고정 설명} 으로 남기고,
 * 발송 결과 읽기가 이 코드로 돌려준다 — 예외 메시지·주소·SMTP 응답 글은 싣지 않는다(R4). 화면 문구 키 {@code mail.reason.{코드}}
 */
public enum MailFailReason {
    /** 메일 설정 꺼짐 */
    DISABLED,
    /** 본문·주소·표시 이름 형식 */
    FORMAT,
    /** 서버가 받는 주소를 영구 거절(5xx) */
    REJECTED,
    /** SMTP 인증 */
    AUTH,
    /** 연결 뒤 시간 초과 — 서버가 받았을 수 있어 재시도 안 함 */
    TIMEOUT_NO_RETRY,
    /** 일시 오류(연결 실패 등) — 대기 중이면 재시도 예정 */
    TEMPORARY,
    /** 재시도 상한 */
    MAX_ATTEMPTS,
    /** 첨부 파일이 저장소에 없거나 바뀜 (18.6) */
    ATTACHMENT_MISSING,
    /** 코드 이전 행·알 수 없는 오류 */
    UNKNOWN;

    /** 송신 오류 기록 글 — {@code 코드: 설명}. 설명은 고정 문구나 예외 클래스 이름만 */
    public String record(String detail) {
        return detail == null || detail.isBlank() ? name() : name() + ": " + detail;
    }

    /** 오류 기록 글의 앞 코드 — 없거나 모르는 것은 UNKNOWN */
    static MailFailReason parse(String lastError) {
        if (lastError == null || lastError.isBlank()) {
            return UNKNOWN;
        }
        if ("max attempts".equals(lastError)) {   // 릴레이가 집을 때 상한으로 끝낸 행
            return MAX_ATTEMPTS;
        }
        int colon = lastError.indexOf(':');
        String code = colon < 0 ? lastError : lastError.substring(0, colon);
        for (MailFailReason r : values()) {
            if (r.name().equals(code)) {
                return r;
            }
        }
        return UNKNOWN;
    }
}
