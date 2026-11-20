-- =====================================================================
-- CMS C21 — grid 엑셀 통일 1묶음 (docs/33 13.7). 표 변경 없음
-- - cms-chk-run 화면에 EXCEL 동작(grid 메뉴 엑셀, POST /api/cms/chk-runs/export)
-- - cms-mwo 는 이미 EXCEL(V136) — grid 메뉴 엑셀은 같은 /export 에 열 목록을 보내는 방식이라 동작 추가 없음
-- 임시 번호(docs/29 2.2) — 병합 때 이름만 바뀐다.
-- =====================================================================

UPDATE sys_prog SET actions = ARRAY['SEARCH','SAVE','DELETE','EXCEL']::varchar(20)[] WHERE prog_cd = 'cms-chk-run';
