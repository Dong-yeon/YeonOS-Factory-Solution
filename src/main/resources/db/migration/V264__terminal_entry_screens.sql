-- =====================================================================
-- 현장 단말 진입 화면 (docs/36 21장) — V9713(병합 때 재번호). 화면 등록만, 표 변경 없음
-- - 사무 메뉴에서 현장 단말(POP /pop, 창고 PDA /pop/wh)로 들어가는 안내 화면 2개. 단말 화면은 새 탭으로 연다.
-- - POP 보안 분리(등록 단말 쿠키 + 작업자 배지, docs/25 4.6)는 그대로 — 이 화면은 사무 로그인으로 단말에 로그인시키지 않는다.
--   등록 여부는 화면 JS 가 POP 체인의 /api/pop/session 을 그대로 불러 본다(사무 체인은 단말 쿠키를 읽지 않음).
-- - 메뉴는 데모·개발 시드(DevCompanySeed). 설치된 회사는 메뉴 관리(sys-menu)에서 넣는다.
-- =====================================================================

INSERT INTO sys_prog (prog_cd, prog_nm_key, view_path, module_cd, actions, sort_no) VALUES
    ('prd-pop', 'prog.prdPop', 'production/pop-entry', 'PRD', ARRAY['SEARCH']::varchar(20)[], 480),
    ('wms-pda', 'prog.wmsPda', 'wms/pda-entry', 'WMS', ARRAY['SEARCH']::varchar(20)[], 790);
