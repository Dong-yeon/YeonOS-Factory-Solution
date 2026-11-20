-- =====================================================================
-- 메뉴 개편 (docs/75) — 임시 V9950(병합 때 재번호). 화면 등록만, 표 변경 없음
-- - 모바일 웹앱 안내(sys-mobile-app): 사무 메뉴에서 /m(docs/74)으로 들어가는 곳 — 주소·열기·복사·설치 방법.
--   /m 자체는 sys_prog 가 없다(docs/74 M1) — 메뉴는 화면(prog)만 걸 수 있어서 안내 화면 하나를 둔다.
-- - 개인 화면(self_service_yn = Y): /m 은 회사 사용자면 누구나 열고, 그 안 조각은 각 사무 화면 권한으로 서버가 가른다.
-- - 메뉴 모양은 DefaultMenuTree(Java) 한 벌 — 데모·개발 시드와 운영 콘솔 새 회사가 같이 쓴다. 설치된 회사는 메뉴 관리에서.
-- =====================================================================

INSERT INTO sys_prog (prog_cd, prog_nm_key, view_path, module_cd, actions, sort_no, self_service_yn) VALUES
    ('sys-mobile-app', 'prog.sysMobileApp', 'admin/mobile-app', 'SYS', ARRAY['SEARCH']::varchar(20)[], 951, 'Y');
