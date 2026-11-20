-- =====================================================================
-- CMS C23 — grid 엑셀 통일 3묶음 (docs/33 13.10). 표 변경 없음
-- - cms-alarm-def·cms-state-reason·cms-chk-tmpl 화면에 EXCEL 동작(grid 메뉴 엑셀, POST …/export)
-- - 이것으로 CMS 목록(grid) 화면은 모두 grid 메뉴 엑셀
-- 임시 번호(docs/29 2.2) — 병합 때 이름만 바뀐다.
-- =====================================================================

UPDATE sys_prog SET actions = ARRAY['SEARCH','SAVE','EXCEL']::varchar(20)[]
 WHERE prog_cd IN ('cms-alarm-def', 'cms-state-reason', 'cms-chk-tmpl');
