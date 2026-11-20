-- 홈화면 1단계 (docs/37) — "내 할 일" 카드 홈. (임시 V9800 → 병합 때 V049)
-- 1단계는 새 표가 없다: 카드는 각 모듈의 HomeCardProvider 빈이 계산하고, 사용자별 숨김·순서(sys_home_user_card)는 2단계(docs/37 7장).
-- 역할별 홈으로 쓰려면 권한그룹 home_prog_cd = 'home-main'(회사 데이터 — 관리 화면·시더).

INSERT INTO sys_prog (prog_cd, prog_nm_key, view_path, module_cd, actions, sort_no) VALUES
    ('home-main', 'prog.homeMain', 'home/home-main', 'SYS', ARRAY['SEARCH']::varchar(20)[], 950);
