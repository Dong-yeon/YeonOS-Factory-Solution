-- =====================================================================
-- CMS 2부 C9 — 설비 KPI 화면 (docs/33 11.8, 결정 D19·D20·D21). 표 변경 없음
-- - KPI 정의는 순수 함수 EquipKpi + 문서(D20 — sys_kpi_def 표를 두지 않는다). 원장(MES 상태 구간·실적, CMS 사후 사유·예방점검)은 읽기만
-- - 표준 사이클 = 작업지시 공정 스냅샷 prd_wo_proc.std_ct_sec / units_per_cycle (D21, 라우팅 공정 표준 시간을 지시 때 복사한 값)
-- 임시 번호(docs/29 2.2) — 병합 때 이름만 바뀐다.
-- =====================================================================

-- 기간 안 설비 실적 합(설비·발생 시각) — prd_result 에는 공정·시각 인덱스만 있다
CREATE INDEX ix_prd_result_equip_time ON prd_result (comp_cd, equip_id, occurred_at) WHERE equip_id IS NOT NULL;

INSERT INTO sys_prog (prog_cd, prog_nm_key, view_path, module_cd, actions, sort_no) VALUES
    ('cms-kpi', 'prog.cmsKpi', 'cms/kpi', 'CMS', ARRAY['SEARCH']::varchar(20)[], 640);
