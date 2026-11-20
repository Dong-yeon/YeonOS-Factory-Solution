-- =====================================================================
-- MES 사무 실적 조회 (docs/25 4.16) — 화면 등록 + 불량 합계 조인 인덱스. 표 변경 없음
-- - prd_result_defect 는 실적 FK 만 있고 인덱스가 없었다 → 기간 안 실적의 불량 합계가 표 전체를 훑지 않게
-- =====================================================================

CREATE INDEX ix_prd_result_defect_result ON prd_result_defect (comp_cd, result_id);

INSERT INTO sys_prog (prog_cd, prog_nm_key, view_path, module_cd, actions, sort_no) VALUES
    ('prd-result', 'prog.prdResult', 'production/result-view', 'PRD', ARRAY['SEARCH', 'EXCEL']::varchar(20)[], 455);
