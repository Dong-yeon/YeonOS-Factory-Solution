-- =====================================================================
-- 현장 단말 APK 다운로드 화면 (docs/50 4장 D1 — V197·임시 V9713) — 화면 등록만, 표 변경 없음
-- - APK·release.json 은 DB 가 아니라 서버 디스크(happyboot.pda.dir)에 운영자가 놓는다(F1·F4) — 회사별 RLS 표에 전역 파일을 넣지 않는다
-- - 기본 권한은 관리자 그룹(admin_yn)만 — 다른 그룹은 회사 관리자가 권한그룹 화면에서 준다(Q6)
-- =====================================================================

INSERT INTO sys_prog (prog_cd, prog_nm_key, view_path, module_cd, actions, sort_no, self_service_yn) VALUES
    ('sys-pda-app', 'prog.sysPdaApp', 'admin/pda-app', 'SYS', ARRAY['SEARCH']::varchar(20)[], 950, 'N');
