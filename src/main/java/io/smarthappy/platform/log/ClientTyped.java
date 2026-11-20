package io.smarthappy.platform.log;

/**
 * 서버가 아는 단말 종류를 가진 인증 주체 (docs/50 Q9, 2026-10-27). 예: POP 단말 체인의 단말 주체 — 등록된 단말 유형(POP/PDA).
 * 접속 로그 {@code client_type} 은 이 값을 헤더 {@code X-HB-Client}(화면이 보내는 참고값)보다 먼저 쓴다.
 * 플랫폼이 업무 모듈 타입을 모르게 하려고 인터페이스로 둔다.
 */
public interface ClientTyped {

    /** WEB·POP·PDA·API 중 하나. 그 밖이면 무시하고 헤더 규칙으로 */
    String clientType();
}
