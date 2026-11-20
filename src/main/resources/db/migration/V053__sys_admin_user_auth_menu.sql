-- =====================================================================
-- 시스템관리 1단계 (docs/36) — 사용자·권한그룹·메뉴 관리 화면. 임시 번호(병합 때 재번호, docs/29 2.2)
--
-- 1. 관리자 그룹 admin_yn: 'Y' 그룹 구성원 = 사용 중인 모든 프로그램의 모든 액션(행 없이).
--    회사 생성 뒤 Flyway 로 추가된 화면이 어느 회사 관리자에게도 부여되지 않던 문제(docs/36 D2)
-- 2. sys_user.session_not_before: 관리자 비밀번호 초기화 때 그 사용자의 기존 세션을 끊는다(D4)
--    + sys_user 감사 트리거가 잠금 칸을 무시하지 않게(D3)
-- 3. 메뉴 트리 트리거: 부모는 같은 회사 폴더, 순환·깊이(5) 금지, 자식 있는 폴더를 화면으로 바꾸기 금지(D5)
-- 4. 화면 3개 (sys-user · sys-auth-group · sys-menu)
-- =====================================================================

-- ---------------------------------------------------------------------
-- 1. 관리자 그룹
-- ---------------------------------------------------------------------
ALTER TABLE sys_auth_group ADD COLUMN admin_yn char(1) NOT NULL DEFAULT 'N';
ALTER TABLE sys_auth_group ADD CONSTRAINT ck_sys_auth_group_admin_yn CHECK (admin_yn IN ('Y', 'N'));
COMMENT ON COLUMN sys_auth_group.admin_yn IS '회사 관리자 그룹: Y 면 구성원이 사용 중인 모든 프로그램의 모든 액션을 가진다(sys_auth_prog 행 불필요)';

-- 기존 회사의 ADMIN 그룹(운영자 회사 생성·로컬 시더가 "모든 화면 모든 액션"으로 만든 그룹)을 관리자 그룹으로 (docs/36 Q1).
-- 마이그레이션 계정이 슈퍼유저가 아니면 FORCE RLS 를 받으므로 회사마다 범위를 열고 바꾼다.
-- 회사 목록은 먼저 배열로 받는다(루프 안에서 범위를 바꾸면 커서가 읽는 행의 RLS 판정이 달라질 수 있다 — V007 교훈)
DO $$
DECLARE
    v_comps varchar[];
    v_comp  varchar;
BEGIN
    PERFORM set_config('app.scope', 'SYSTEM', true);
    SELECT coalesce(array_agg(comp_cd), ARRAY[]::varchar[]) INTO v_comps FROM sys_comp;
    PERFORM set_config('app.scope', '', true);
    FOREACH v_comp IN ARRAY v_comps LOOP
        PERFORM set_config('app.comp_cd', v_comp, true);
        UPDATE sys_auth_group SET admin_yn = 'Y' WHERE comp_cd = v_comp AND auth_grp_cd = 'ADMIN';
    END LOOP;
    PERFORM set_config('app.comp_cd', '', true);
END
$$;

-- ---------------------------------------------------------------------
-- 2. 세션 무효 기준 시각
-- ---------------------------------------------------------------------
ALTER TABLE sys_user ADD COLUMN session_not_before timestamptz;
COMMENT ON COLUMN sys_user.session_not_before IS '이 시각 전에 로그인한 세션은 무효 (관리자 비밀번호 초기화 때 now())';

-- 잠금·해제가 감사에 남게 (docs/36 D3): V007 은 lock_yn·locked_until 을 무시 컬럼으로 둬서 관리자 잠금·해제의 행위자가
-- 남지 않았다. 로그인 횟수·마지막 로그인(매 로그인 잡음)만 계속 무시한다. 실패 5회 자동 잠금도 이제 남는다(행위자 SYSTEM)
DROP TRIGGER tg_audit ON sys_user;
CREATE TRIGGER tg_audit AFTER INSERT OR UPDATE OR DELETE ON sys_user
    FOR EACH ROW EXECUTE FUNCTION hb_audit('user_id', 'login_fail_cnt,last_login_at', 'pwd_hash');

-- ---------------------------------------------------------------------
-- 3. 메뉴 트리 규칙 (V005 의 같은 회사 확인 트리거 뒤에 돈다 — 이름순)
-- ---------------------------------------------------------------------
CREATE FUNCTION hb_check_menu_tree() RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    v_parent_prog varchar;
    v_depth       integer;
    v_height      integer;
    v_cycle       boolean;
BEGIN
    -- 폴더 → 화면 전환: 자식이 있으면 안 된다(자식이 루트에서 닿지 않게 되어 조용히 사라진다)
    IF TG_OP = 'UPDATE' AND NEW.prog_cd IS NOT NULL AND OLD.prog_cd IS NULL
       AND EXISTS (SELECT 1 FROM sys_menu c WHERE c.parent_id = NEW.menu_id) THEN
        RAISE EXCEPTION 'menu % has children', NEW.menu_id
            USING ERRCODE = '23514', CONSTRAINT = 'ck_sys_menu_has_children';
    END IF;
    IF NEW.parent_id IS NULL OR (TG_OP = 'UPDATE' AND NEW.parent_id IS NOT DISTINCT FROM OLD.parent_id) THEN
        RETURN NEW;
    END IF;
    -- 같은 회사의 메뉴 이동을 한 줄로: 두 이동이 서로의 커밋 전 상태를 보고 함께 순환을 만들지 않게
    PERFORM pg_advisory_xact_lock(hashtextextended('sys_menu:' || NEW.comp_cd, 0));
    SELECT m.prog_cd INTO v_parent_prog FROM sys_menu m WHERE m.menu_id = NEW.parent_id AND m.comp_cd = NEW.comp_cd;
    IF v_parent_prog IS NOT NULL THEN
        RAISE EXCEPTION 'parent menu % is not a folder', NEW.parent_id
            USING ERRCODE = '23514', CONSTRAINT = 'ck_sys_menu_parent_folder';
    END IF;
    -- 새 부모에서 위로 올라가며 자기 자신을 만나면 순환, 깊이도 함께
    WITH RECURSIVE up(menu_id, parent_id, depth) AS (
        SELECT menu_id, parent_id, 1 FROM sys_menu WHERE menu_id = NEW.parent_id
        UNION ALL
        SELECT m.menu_id, m.parent_id, u.depth + 1 FROM up u JOIN sys_menu m ON m.menu_id = u.parent_id
         WHERE u.depth < 50 AND u.menu_id <> NEW.menu_id
    )
    SELECT bool_or(menu_id = NEW.menu_id), max(depth) INTO v_cycle, v_depth FROM up;
    IF v_cycle THEN
        RAISE EXCEPTION 'menu % cannot move under its own descendant', NEW.menu_id
            USING ERRCODE = '23514', CONSTRAINT = 'ck_sys_menu_cycle';
    END IF;
    -- 옮기는 메뉴 아래 높이까지(새 메뉴는 0). 표시 깊이 상한 5(MenuService.MAX_DEPTH) — 넘으면 셸에 안 보인다
    v_height := 0;
    IF TG_OP = 'UPDATE' THEN
        WITH RECURSIVE down(menu_id, h) AS (
            SELECT menu_id, 1 FROM sys_menu WHERE parent_id = NEW.menu_id
            UNION ALL
            SELECT m.menu_id, d.h + 1 FROM down d JOIN sys_menu m ON m.parent_id = d.menu_id WHERE d.h < 50
        )
        SELECT coalesce(max(h), 0) INTO v_height FROM down;
    END IF;
    IF v_depth + 1 + v_height > 5 THEN
        RAISE EXCEPTION 'menu % would be deeper than 5', NEW.menu_id
            USING ERRCODE = '23514', CONSTRAINT = 'ck_sys_menu_depth';
    END IF;
    RETURN NEW;
END
$$;
CREATE TRIGGER tg_sys_menu_tree BEFORE INSERT OR UPDATE OF parent_id, prog_cd ON sys_menu
    FOR EACH ROW EXECUTE FUNCTION hb_check_menu_tree();

-- ---------------------------------------------------------------------
-- 4. 화면
--    사용자 관리: FN1 = 비밀번호 초기화, FN2 = 잠금·잠금 해제 (권한 관리와 헬프데스크를 나눌 수 있게, docs/36 Q3)
-- ---------------------------------------------------------------------
INSERT INTO sys_prog (prog_cd, prog_nm_key, view_path, module_cd, actions, sort_no) VALUES
    ('sys-user',       'prog.sysUser',      'admin/user-mgt',       'SYS', ARRAY['SEARCH','SAVE','FN1','FN2']::varchar(20)[], 930),
    ('sys-auth-group', 'prog.sysAuthGroup', 'admin/auth-group-mgt', 'SYS', ARRAY['SEARCH','SAVE']::varchar(20)[], 931),
    ('sys-menu',       'prog.sysMenu',      'admin/menu-mgt',       'SYS', ARRAY['SEARCH','SAVE']::varchar(20)[], 932);

-- ---------------------------------------------------------------------
-- 5. 사용자 잠금 상태 코드 (목록 표시·조회 조건 — 저장 칸 아님, 조회 때 lock_yn·locked_until 로 계산)
-- ---------------------------------------------------------------------
INSERT INTO sys_code_grp (grp_cd, grp_nm_key, comp_ext_yn) VALUES
    ('USER_LOCK_STATE', 'code.grp.USER_LOCK_STATE', 'N');
INSERT INTO sys_code (grp_cd, code, code_nm_key, sort_no) VALUES
    ('USER_LOCK_STATE', 'NONE',   'code.USER_LOCK_STATE.NONE',   1),
    ('USER_LOCK_STATE', 'FAIL',   'code.USER_LOCK_STATE.FAIL',   2),
    ('USER_LOCK_STATE', 'MANUAL', 'code.USER_LOCK_STATE.MANUAL', 3);
