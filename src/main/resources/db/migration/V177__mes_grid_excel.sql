-- =====================================================================
-- MES grid 엑셀 묶음 (docs/25 4.40, 상위 배정 3번 — 임시 V9120). 표 변경 없음
-- - 작업지시·생산 입고 대상·LOT·POP 단말·수집 카운트 화면에 EXCEL 동작(grid 메뉴 엑셀, POST …/export)
-- - 기준정보·CMS 목록과 같은 길(ExcelExportService — 건수 상한·출력 정보 시트·수식 주입 방지)
-- =====================================================================

UPDATE sys_prog SET actions = actions || ARRAY['EXCEL']::varchar(20)[]
 WHERE prog_cd IN ('prd-wo', 'prd-receipt', 'prd-lot', 'sys-terminal', 'cms-equip-count')
   AND NOT ('EXCEL' = ANY (actions));
