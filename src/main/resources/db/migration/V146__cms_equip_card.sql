-- =====================================================================
-- CMS C17 — 설비 이력 카드 단독 화면 (docs/33 13.5, 5장 표). 표 변경 없음
-- - cms-equip-card 화면(조회·엑셀) — 설비 한 대 × 기간의 요약 + 이력 한 표(작업지시·점검 실행·사용 예비품·카운터 리셋)
-- - 이력 종류 코드 CMS_HIST_KIND(전역, 회사 추가 불가)
-- 임시 번호(docs/29 2.2) — 병합 때 이름만 바뀐다.
-- =====================================================================

INSERT INTO sys_prog (prog_cd, prog_nm_key, view_path, module_cd, actions, sort_no) VALUES
    ('cms-equip-card', 'prog.cmsEquipCard', 'cms/equip-card', 'CMS', ARRAY['SEARCH','EXCEL']::varchar(20)[], 515);

INSERT INTO sys_code_grp (grp_cd, grp_nm_key, comp_ext_yn) VALUES
    ('CMS_HIST_KIND', 'code.grp.CMS_HIST_KIND', 'N');
INSERT INTO sys_code (grp_cd, code, code_nm_key, sort_no) VALUES
    ('CMS_HIST_KIND', 'MWO',   'code.CMS_HIST_KIND.MWO',   1),
    ('CMS_HIST_KIND', 'CHK',   'code.CMS_HIST_KIND.CHK',   2),
    ('CMS_HIST_KIND', 'PART',  'code.CMS_HIST_KIND.PART',  3),
    ('CMS_HIST_KIND', 'RESET', 'code.CMS_HIST_KIND.RESET', 4);
