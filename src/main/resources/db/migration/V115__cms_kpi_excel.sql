-- =====================================================================
-- CMS C11 — 설비 KPI 엑셀·일별 추이 (docs/33 11.12). 표 변경 없음
-- - cms-kpi 화면에 EXCEL 동작, 엑셀의 "구분" 칸 코드(공장 합계·라인·설비)
-- 임시 번호(docs/29 2.2) — 병합 때 이름만 바뀐다.
-- =====================================================================

UPDATE sys_prog SET actions = ARRAY['SEARCH','EXCEL']::varchar(20)[] WHERE prog_cd = 'cms-kpi';

INSERT INTO sys_code_grp (grp_cd, grp_nm_key, comp_ext_yn) VALUES ('CMS_KPI_KIND', 'code.grp.CMS_KPI_KIND', 'N');
INSERT INTO sys_code (grp_cd, code, code_nm_key, sort_no) VALUES
    ('CMS_KPI_KIND', 'PLANT', 'code.CMS_KPI_KIND.PLANT', 1),
    ('CMS_KPI_KIND', 'LINE',  'code.CMS_KPI_KIND.LINE',  2),
    ('CMS_KPI_KIND', 'EQUIP', 'code.CMS_KPI_KIND.EQUIP', 3);
