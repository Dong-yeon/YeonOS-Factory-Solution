-- =====================================================================
-- CMS 3차 G1·G2 — 설비·생산 대시보드 화면 등록 + 고장 분석 탭 (docs/33 16.4·16.5, docs/64 결정 Q1 ②). 표 변경 없음
-- - cms-dashboard 화면(조회만): 탭 개요(docs/51 C2 — V231 API) / 고장 분석(G1 파레토·반복 고장 + G2 수리 시간 분해)
--   대시보드 API 권한은 cms-kpi → cms-dashboard 로 옮긴다(코드). 메뉴는 데모·개발 시드(DevCompanySeed.CMS_MENUS)
-- - 정책 CMS_REPEAT_FAIL_DAYS: 같은 설비 × 같은 원인 고장이 이 일수 안에 다시 나면 반복(기본 30, 공장별)
-- 임시 번호(docs/29 2.2) — 병합 때 이름만 바뀐다.
-- =====================================================================

INSERT INTO sys_prog (prog_cd, prog_nm_key, view_path, module_cd, actions, sort_no) VALUES
    ('cms-dashboard', 'prog.cmsDashboard', 'cms/dashboard', 'CMS', ARRAY['SEARCH']::varchar(20)[], 645);

INSERT INTO sys_policy_def (policy_key, value_type, default_value, allowed_values, min_value, max_value, plant_scope_yn, desc_key) VALUES
    ('CMS_REPEAT_FAIL_DAYS', 'INT', '30', NULL, 1, 365, 'Y', 'policy.CMS_REPEAT_FAIL_DAYS');
