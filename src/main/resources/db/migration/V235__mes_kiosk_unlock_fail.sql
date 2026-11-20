-- =====================================================================
-- MES 키오스크 풀기 실패 알림 (docs/50 KS9, docs/25 4.54) — 표 변경 없음
-- - 접속 로그 이벤트 POP_KIOSK_UNLOCK_FAIL(서버는 빠져나옴으로 적었는데 화면이 풀지 못함 — 늦은 응답·기기 거절) 코드 — 접속 로그 화면 조건·표시
-- - 같은 exitRef 두 번째 알림 멱등은 sys_inbox(소비자 POP_KIOSK_UNLOCK_FAIL) — 표 그대로
-- =====================================================================

INSERT INTO sys_code (grp_cd, code, code_nm_key, sort_no) VALUES
    ('ACCESS_EVENT', 'POP_KIOSK_UNLOCK_FAIL', 'code.ACCESS_EVENT.POP_KIOSK_UNLOCK_FAIL', 380);
