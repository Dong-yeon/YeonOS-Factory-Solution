-- =====================================================================
-- V016 BOM 전개·역전개 조회 (P1 3b — docs/18 Q1~Q7, 5.2·5.3)
--
-- 전개는 조회 전용(테이블 없음): 재귀 CTE 를 매퍼에서 돌린다. 여기서는 화면 등록과 레벨 상한 정책만.
-- 레벨 상한: 순환은 저장 때 막지만(V015 트리거), 비정상적으로 깊은 구조·전개 폭주를 조회에서도 끊는다.
-- =====================================================================

INSERT INTO sys_policy_def (policy_key, value_type, default_value, allowed_values, min_value, max_value, desc_key) VALUES
    ('BOM_MAX_LEVEL', 'INT', '30', NULL, 5, 99, 'policy.BOM_MAX_LEVEL'),
    ('BOM_EXPLODE_MAX_ROWS', 'INT', '20000', NULL, 1000, 200000, 'policy.BOM_EXPLODE_MAX_ROWS');

INSERT INTO sys_prog (prog_cd, prog_nm_key, view_path, module_cd, actions, sort_no) VALUES
    ('mst-bom-tree',  'prog.mstBomTree',  'master/bom-tree',  'MST', ARRAY['SEARCH','EXCEL']::varchar(20)[], 121),
    ('mst-bom-where', 'prog.mstBomWhere', 'master/bom-where', 'MST', ARRAY['SEARCH','EXCEL']::varchar(20)[], 122);
