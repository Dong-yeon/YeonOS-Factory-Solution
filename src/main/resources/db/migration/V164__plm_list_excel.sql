-- =====================================================================
-- PLM 주 목록 엑셀 (docs/39 25장). 표 변경 없음
-- - plm-ecr·plm-eco 화면에 EXCEL 동작(grid 메뉴 엑셀, POST …/export) — 다른 모듈 grid 엑셀과 같은 방식
-- 임시 번호(docs/29 2.2) — 병합 때 이름만 바뀐다.
-- =====================================================================

UPDATE sys_prog SET actions = ARRAY['SEARCH','SAVE','APPROVE','EXCEL']::varchar(20)[] WHERE prog_cd IN ('plm-ecr', 'plm-eco');
