-- =====================================================================
-- V019 BOM 변경 도구 (P1 3c — docs/18 Q8·Q10·C4·C5)
--
-- 비교·복사·저레벨 코드는 테이블 없이 기존 구조로 계산한다. 여기서는 일괄 대체 화면 등록만.
-- 일괄 대체 = 자품목 X 를 쓰는 BOM 들에 Y 를 넣은 작성 중 리비전을 만든다(확정은 BOM 관리에서 따로 — 확정 검사를 지나게).
-- =====================================================================

INSERT INTO sys_prog (prog_cd, prog_nm_key, view_path, module_cd, actions, sort_no) VALUES
    ('mst-bom-replace', 'prog.mstBomReplace', 'master/bom-replace', 'MST', ARRAY['SEARCH','SAVE']::varchar(20)[], 123);
