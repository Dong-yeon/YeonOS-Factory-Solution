package io.smarthappy.platform.log;

/**
 * 접속·보안 로그 이벤트 (sys_access_log.event_cd). 기본 심각도는 탐지 규칙({@link AccessLogService})이 올릴 수 있다.
 * 정부지원사업 "활용 로그"(사용자·메뉴·기능별 사용)는 PAGE_VIEW·ACTION 으로 집계한다.
 */
public enum AccessEvent {
    LOGIN_OK(Severity.INFO),
    LOGIN_FAIL(Severity.INFO),
    /** 이번 실패로 계정이 잠김 */
    LOGIN_LOCKED(Severity.WARN),
    /** 비밀번호는 맞았으나 잠금·사용중지로 거부 */
    LOGIN_DENIED(Severity.INFO),
    LOGIN_THROTTLED(Severity.WARN),
    LOGOUT(Severity.INFO),
    SESSION_EXPIRED(Severity.INFO),
    SESSION_REVOKED(Severity.INFO),
    PAGE_VIEW(Severity.INFO),
    /** 조회 외 기능 실행(저장·삭제·엑셀·출력·승인 등) */
    ACTION(Severity.INFO),
    PERM_DENIED(Severity.WARN),
    /** RLS 가 다른 회사 데이터 쓰기를 막음 — 정상 화면 흐름에서는 나올 수 없다 */
    TENANT_FORBIDDEN(Severity.ALERT),
    PASSWORD_CHANGED(Severity.INFO),
    FILE_UPLOAD(Severity.INFO),
    FILE_DOWNLOAD(Severity.INFO),
    FILE_DELETE(Severity.INFO),
    FILE_REJECTED(Severity.WARN),
    /** POP 단말 활성화(등록 코드 → 단말 키) */
    TERMINAL_ACTIVATED(Severity.INFO),
    /** 등록 코드 틀림·잘못된 단말 쿠키 */
    TERMINAL_DENIED(Severity.WARN),
    /** POP 작업자 배지 전환 성공 */
    BADGE_OK(Severity.INFO),
    BADGE_FAIL(Severity.INFO),
    /** 틀린 배지가 단말 한도를 넘어 잠시 막힘 */
    BADGE_THROTTLED(Severity.WARN),
    /** 이번 PIN 실패로 작업자 PIN 이 잠김 */
    PIN_LOCKED(Severity.WARN),
    /** 관리자가 사용자 비밀번호를 초기화 (detail = 대상 사용자, docs/36) */
    PASSWORD_RESET(Severity.WARN),
    /** 관리자 수동 잠금·잠금 해제 (detail = 대상 사용자) — 감사 트리거가 잠금 칸을 무시하므로 여기 남긴다 */
    USER_LOCKED(Severity.INFO),
    USER_UNLOCKED(Severity.INFO),
    /** 부여 상한·자기 권한 변경 거부 — 정상 화면은 버튼을 숨기므로 조작 시도 */
    GRANT_DENIED(Severity.WARN),
    // 시스템관리 4a 관리자 MFA (docs/36 14장) — 실패 5회 잠금은 ALERT(보안 경고 알림)
    MFA_OK(Severity.INFO),
    MFA_FAIL(Severity.WARN),
    MFA_LOCKED(Severity.ALERT),
    MFA_ENROLLED(Severity.INFO),
    /** 관리자·운영자가 다른 사용자의 MFA 를 초기화 (detail = 대상 사용자) */
    MFA_RESET(Severity.WARN),
    // 시스템관리 5단계 전자서명 재인증 (docs/36 16장) — 세션당 5회 틀리면 세션을 끊는다(ALERT)
    ESIGN_FAIL(Severity.WARN),
    ESIGN_LOCKED(Severity.ALERT),
    // POP 감독자 승인 (docs/25 4.37) — 감독자 아님·자격 없음·PIN 틀림·잠김
    POP_APPROVAL_FAIL(Severity.WARN),
    // POP 키오스크 빠져나오기 (docs/25 4.52, docs/50 K4) — 성공(누가)·실패(사유는 승인과 같다)
    POP_KIOSK_EXIT(Severity.INFO),
    POP_KIOSK_EXIT_FAIL(Severity.WARN),
    /** 서버는 빠져나옴(POP_KIOSK_EXIT OK)으로 적었는데 화면이 풀지 못함 — 늦은 응답·기기 거절(docs/50 KS9). detail = 감독자·exitRef·사유 */
    POP_KIOSK_UNLOCK_FAIL(Severity.WARN);

    public enum Severity {
        INFO, WARN, ALERT
    }

    private final Severity severity;

    AccessEvent(Severity severity) {
        this.severity = severity;
    }

    public Severity severity() {
        return severity;
    }
}
