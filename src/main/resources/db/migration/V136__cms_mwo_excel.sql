-- =====================================================================
-- CMS C14 — 보전 작업지시 엑셀 (docs/33 13장 2번). 표 변경 없음
-- - cms-mwo 화면에 EXCEL 동작
-- 임시 번호(docs/29 2.2) — 병합 때 이름만 바뀐다.
-- =====================================================================

UPDATE sys_prog SET actions = ARRAY['SEARCH','SAVE','APPROVE','DELETE','EXCEL']::varchar(20)[] WHERE prog_cd = 'cms-mwo';
