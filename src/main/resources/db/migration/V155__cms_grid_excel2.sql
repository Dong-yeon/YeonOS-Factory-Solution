-- =====================================================================
-- CMS C21 — grid 엑셀 통일 2묶음 (docs/33 13.8). 표 변경 없음
-- - cms-counter·cms-alarm·cms-state-review 화면에 EXCEL 동작(grid 메뉴 엑셀, POST …/export)
-- 임시 번호(docs/29 2.2) — 병합 때 이름만 바뀐다.
-- =====================================================================

UPDATE sys_prog SET actions = ARRAY['SEARCH','SAVE','DELETE','EXCEL']::varchar(20)[] WHERE prog_cd = 'cms-counter';
UPDATE sys_prog SET actions = ARRAY['SEARCH','EXCEL']::varchar(20)[] WHERE prog_cd = 'cms-alarm';
UPDATE sys_prog SET actions = ARRAY['SEARCH','SAVE','DELETE','EXCEL']::varchar(20)[] WHERE prog_cd = 'cms-state-review';
