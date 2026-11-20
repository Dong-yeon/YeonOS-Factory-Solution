-- =====================================================================
-- V004 로그인 잠금 자동 해제 (docs/04 6.2-7)
-- 연속 실패 잠금은 locked_until 까지만 유효하다(자동 해제). 아이디만 알면 남의 계정을
-- 영구히 잠글 수 있던 문제를 막는다.
-- locked_until 이 NULL 인 잠금(lock_yn = 'Y')은 관리자 수동 잠금 → 시간이 지나도 풀리지 않고,
-- 이미 로그인된 세션도 다음 요청에서 끊긴다. 실패 잠금은 기존 세션을 끊지 않는다.
-- =====================================================================

ALTER TABLE sys_user ADD COLUMN locked_until timestamptz;

COMMENT ON COLUMN sys_user.lock_yn IS '잠금 여부. locked_until 이 있으면 실패 잠금(그 시각에 자동 해제), 없으면 관리자 수동 잠금';
COMMENT ON COLUMN sys_user.locked_until IS '실패 잠금 해제 시각 (NULL + lock_yn=Y 는 관리자 수동 잠금)';
