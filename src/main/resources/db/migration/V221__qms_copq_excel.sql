-- =====================================================================
-- QMS COPQ 엑셀 내보내기 (docs/29_P3 34장, 사용자 결정 Q-2 3번). 표 변경 없음
-- - qms-copq 화면에 EXCEL 동작
-- 임시 번호(docs/29 2.2) — 병합 때 이름만 바뀐다.
-- =====================================================================

UPDATE sys_prog SET actions = ARRAY['SEARCH','SAVE','DELETE','EXCEL']::varchar(20)[] WHERE prog_cd = 'qms-copq';
