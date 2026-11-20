-- =====================================================================
-- MES 키오스크 빠져나오기 (docs/25 4.52, docs/50 K4·K5) — 표 변경 없음
-- - 접속 로그 이벤트 POP_KIOSK_EXIT(감독자가 단말 고정을 풂)·POP_KIOSK_EXIT_FAIL(감독자 아님·PIN 틀림·잠김) 코드 — 접속 로그 화면 조건·표시
-- =====================================================================

INSERT INTO sys_code (grp_cd, code, code_nm_key, sort_no) VALUES
    ('ACCESS_EVENT', 'POP_KIOSK_EXIT',      'code.ACCESS_EVENT.POP_KIOSK_EXIT',      360),
    ('ACCESS_EVENT', 'POP_KIOSK_EXIT_FAIL', 'code.ACCESS_EVENT.POP_KIOSK_EXIT_FAIL', 370);
