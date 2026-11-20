-- =====================================================================
-- CMS C16 — 기준정보 엑셀 3종 (docs/33 13장 "뒤" 첫 줄). 표 변경 없음
-- - cms-fail-code·cms-equip-profile·cms-pm-plan 화면에 EXCEL 동작 (grid 메뉴 엑셀, POST …/export)
-- 임시 번호(docs/29 2.2) — 병합 때 이름만 바뀐다.
-- =====================================================================

UPDATE sys_prog SET actions = ARRAY['SEARCH','SAVE','EXCEL']::varchar(20)[]
 WHERE prog_cd IN ('cms-fail-code', 'cms-equip-profile', 'cms-pm-plan');
