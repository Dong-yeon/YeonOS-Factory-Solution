-- =====================================================================
-- PMS 5단계 — 홈·포트폴리오·프로젝트 보고서 (docs/32 PM5, 4.7)
-- 임시 번호(docs/29 2.2) — 상위 세션이 병합 때 파일 이름만 바꾼다
-- 새 표 없음: 포트폴리오·내 할 일·보고서는 PM1~PM4 표를 읽기만 한다. 홈 카드(pms-my-work·pms-portfolio)는 이 화면 SEARCH 권한으로 보인다
-- =====================================================================

INSERT INTO sys_prog (prog_cd, prog_nm_key, view_path, module_cd, actions, sort_no) VALUES
    ('pms-home', 'prog.pmsHome', 'pms/pms-home', 'PMS', ARRAY['SEARCH','EXCEL']::varchar(20)[], 800);
