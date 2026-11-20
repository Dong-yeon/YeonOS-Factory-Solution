package io.smarthappy.platform.user;

/**
 * 아이디·id 로 찾은 회사 사용자 — 사용 상태와 함께. active = 사용 중이고 관리자 잠금이 아님(결재선·위임과 같은 규칙).
 * 이미 담당자로 걸린 사람은 사용 중지돼도 그대로 두는 업무가 있어(바꾸지 않으면 통과) 상태를 따로 돌려준다
 */
public record UserRef(long userId, String loginId, String userNm, boolean active) {
}
