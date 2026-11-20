-- =====================================================================
-- V005 프로그램(전역) · 권한그룹 · 사용자-권한그룹 · 메뉴 · 권한그룹×프로그램×액션
--
-- 전역 테이블(comp_cd 없음): sys_prog
--   제품이 정의하는 화면 목록. Flyway 로만 바뀐다 → 앱 계정은 SELECT 만(쓰기 권한 회수).
--   TenantCatalogInspector 의 전역 테이블 허용 목록에 등록돼 있고, 앱 계정 쓰기 권한이 있으면 기동 중단.
-- 회사별 테이블: sys_auth_group, sys_user_auth, sys_menu, sys_auth_prog → RLS
-- =====================================================================

-- ---------------------------------------------------------------------
-- 1. 프로그램 (전역)
-- ---------------------------------------------------------------------
CREATE TABLE sys_prog (
    prog_cd      varchar(50)  NOT NULL,
    prog_nm_key  varchar(100) NOT NULL,
    view_path    varchar(100) NOT NULL,
    module_cd    varchar(20)  NOT NULL,
    actions      varchar(20)[] NOT NULL DEFAULT ARRAY['SEARCH']::varchar(20)[],
    sort_no      integer      NOT NULL DEFAULT 0,
    use_yn       char(1)      NOT NULL DEFAULT 'Y',
    created_at   timestamptz  NOT NULL DEFAULT now(),
    created_by   varchar(50)  NOT NULL DEFAULT 'FLYWAY',
    updated_at   timestamptz  NOT NULL DEFAULT now(),
    updated_by   varchar(50)  NOT NULL DEFAULT 'FLYWAY',
    CONSTRAINT pk_sys_prog PRIMARY KEY (prog_cd),
    -- URL 경로 조각이므로 소문자·숫자·하이픈만 (대소문자 혼동·경로 조작 차단)
    CONSTRAINT ck_sys_prog_cd CHECK (prog_cd ~ '^[a-z0-9][a-z0-9-]{1,49}$'),
    -- 템플릿 경로: 소문자·숫자·하이픈·슬래시만, '..' 불가, 앞뒤 슬래시 불가
    CONSTRAINT ck_sys_prog_view_path CHECK (view_path ~ '^[a-z0-9-]+(/[a-z0-9-]+)*$'),
    CONSTRAINT ck_sys_prog_nm_key CHECK (prog_nm_key ~ '^[a-z0-9]+(\.[a-zA-Z0-9]+)+$'),
    CONSTRAINT ck_sys_prog_actions CHECK (
        cardinality(actions) > 0 AND
        actions <@ ARRAY['SEARCH','SAVE','DELETE','EXCEL','PRINT','APPROVE','FN1','FN2','FN3','FN4','FN5']::varchar(20)[]),
    CONSTRAINT ck_sys_prog_use_yn CHECK (use_yn IN ('Y', 'N'))
);

COMMENT ON TABLE  sys_prog IS '프로그램(화면) — 제품 전역 정의, Flyway 로만 변경';
COMMENT ON COLUMN sys_prog.prog_nm_key IS '화면 이름 i18n 메시지 키 (messages*.properties)';
COMMENT ON COLUMN sys_prog.view_path IS 'Thymeleaf 템플릿 경로 (/page/{prog_cd} 가 렌더링)';
COMMENT ON COLUMN sys_prog.actions IS '이 화면에서 쓰는 액션 (권한 부여 가능 범위)';

REVOKE INSERT, UPDATE, DELETE, TRUNCATE ON sys_prog FROM ${app_role};

-- ---------------------------------------------------------------------
-- 2. 권한그룹 (회사별)
-- ---------------------------------------------------------------------
CREATE TABLE sys_auth_group (
    comp_cd       varchar(20)  NOT NULL,
    auth_grp_cd   varchar(30)  NOT NULL,
    auth_grp_nm   varchar(100) NOT NULL,
    sort_no       integer      NOT NULL DEFAULT 0,
    use_yn        char(1)      NOT NULL DEFAULT 'Y',
    row_ver       integer      NOT NULL DEFAULT 0,
    created_at    timestamptz  NOT NULL DEFAULT now(),
    created_by    varchar(50)  NOT NULL,
    updated_at    timestamptz  NOT NULL DEFAULT now(),
    updated_by    varchar(50)  NOT NULL,
    CONSTRAINT pk_sys_auth_group PRIMARY KEY (comp_cd, auth_grp_cd),
    CONSTRAINT fk_sys_auth_group_comp FOREIGN KEY (comp_cd) REFERENCES sys_comp (comp_cd),
    CONSTRAINT ck_sys_auth_group_cd CHECK (auth_grp_cd ~ '^[A-Z0-9][A-Z0-9_-]{1,29}$'),
    CONSTRAINT ck_sys_auth_group_nm CHECK (length(btrim(auth_grp_nm)) > 0),
    CONSTRAINT ck_sys_auth_group_use_yn CHECK (use_yn IN ('Y', 'N'))
);
COMMENT ON TABLE sys_auth_group IS '권한그룹 (회사별)';

-- ---------------------------------------------------------------------
-- 3. 사용자 ↔ 권한그룹
-- ---------------------------------------------------------------------
CREATE TABLE sys_user_auth (
    comp_cd      varchar(20) NOT NULL,
    user_id      bigint      NOT NULL,
    auth_grp_cd  varchar(30) NOT NULL,
    created_at   timestamptz NOT NULL DEFAULT now(),
    created_by   varchar(50) NOT NULL,
    CONSTRAINT pk_sys_user_auth PRIMARY KEY (comp_cd, user_id, auth_grp_cd),
    CONSTRAINT fk_sys_user_auth_user FOREIGN KEY (user_id) REFERENCES sys_user (user_id),
    CONSTRAINT fk_sys_user_auth_group FOREIGN KEY (comp_cd, auth_grp_cd) REFERENCES sys_auth_group (comp_cd, auth_grp_cd)
);
CREATE INDEX ix_sys_user_auth_user ON sys_user_auth (comp_cd, user_id);
COMMENT ON TABLE sys_user_auth IS '사용자-권한그룹 (사용자는 여러 그룹 가능, 권한은 합집합)';

-- 다른 회사 사용자를 내 회사 그룹에 넣는 것 방지: user_id 의 회사와 comp_cd 가 같아야 한다
-- (FK 는 user_id 만 보므로 별도 확인. RLS 는 sys_user 의 다른 회사 행을 보여주지 않지만 FK 검사는 RLS 를 무시한다)
CREATE FUNCTION hb_check_user_auth_comp() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM sys_user u WHERE u.user_id = NEW.user_id AND u.comp_cd = NEW.comp_cd) THEN
        RAISE EXCEPTION 'user % does not belong to company %', NEW.user_id, NEW.comp_cd
            USING ERRCODE = '23503', CONSTRAINT = 'fk_sys_user_auth_user_comp';
    END IF;
    RETURN NEW;
END
$$;
CREATE TRIGGER tg_sys_user_auth_comp BEFORE INSERT OR UPDATE ON sys_user_auth
    FOR EACH ROW EXECUTE FUNCTION hb_check_user_auth_comp();

-- ---------------------------------------------------------------------
-- 4. 메뉴 (회사별 트리). prog_cd 가 없으면 폴더
-- ---------------------------------------------------------------------
CREATE TABLE sys_menu (
    menu_id      bigint       GENERATED ALWAYS AS IDENTITY,
    comp_cd      varchar(20)  NOT NULL,
    parent_id    bigint,
    menu_nm      varchar(100),
    menu_nm_key  varchar(100),
    prog_cd      varchar(50),
    sort_no      integer      NOT NULL DEFAULT 0,
    use_yn       char(1)      NOT NULL DEFAULT 'Y',
    row_ver      integer      NOT NULL DEFAULT 0,
    created_at   timestamptz  NOT NULL DEFAULT now(),
    created_by   varchar(50)  NOT NULL,
    updated_at   timestamptz  NOT NULL DEFAULT now(),
    updated_by   varchar(50)  NOT NULL,
    CONSTRAINT pk_sys_menu PRIMARY KEY (menu_id),
    CONSTRAINT fk_sys_menu_comp FOREIGN KEY (comp_cd) REFERENCES sys_comp (comp_cd),
    CONSTRAINT fk_sys_menu_prog FOREIGN KEY (prog_cd) REFERENCES sys_prog (prog_cd),
    CONSTRAINT fk_sys_menu_parent FOREIGN KEY (parent_id) REFERENCES sys_menu (menu_id),
    -- 이름: 회사가 직접 입력(menu_nm) 또는 i18n 키(menu_nm_key) 또는 프로그램 이름 키를 따름 → 폴더는 둘 중 하나 필수
    CONSTRAINT ck_sys_menu_name CHECK (prog_cd IS NOT NULL OR menu_nm IS NOT NULL OR menu_nm_key IS NOT NULL),
    CONSTRAINT ck_sys_menu_nm CHECK (menu_nm IS NULL OR length(btrim(menu_nm)) > 0),
    CONSTRAINT ck_sys_menu_not_self CHECK (parent_id IS NULL OR parent_id <> menu_id),
    CONSTRAINT ck_sys_menu_use_yn CHECK (use_yn IN ('Y', 'N'))
);
CREATE INDEX ix_sys_menu_comp ON sys_menu (comp_cd, parent_id, sort_no);
COMMENT ON TABLE sys_menu IS '메뉴 트리 (회사별). prog_cd 없으면 폴더';

-- 부모 메뉴는 같은 회사여야 한다 (FK 는 RLS 를 무시하므로 별도 확인)
CREATE FUNCTION hb_check_menu_parent_comp() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.parent_id IS NOT NULL AND NOT EXISTS
        (SELECT 1 FROM sys_menu m WHERE m.menu_id = NEW.parent_id AND m.comp_cd = NEW.comp_cd) THEN
        RAISE EXCEPTION 'parent menu % does not belong to company %', NEW.parent_id, NEW.comp_cd
            USING ERRCODE = '23503', CONSTRAINT = 'fk_sys_menu_parent_comp';
    END IF;
    RETURN NEW;
END
$$;
CREATE TRIGGER tg_sys_menu_parent_comp BEFORE INSERT OR UPDATE ON sys_menu
    FOR EACH ROW EXECUTE FUNCTION hb_check_menu_parent_comp();

-- ---------------------------------------------------------------------
-- 5. 권한그룹 × 프로그램 × 액션
-- ---------------------------------------------------------------------
CREATE TABLE sys_auth_prog (
    comp_cd      varchar(20) NOT NULL,
    auth_grp_cd  varchar(30) NOT NULL,
    prog_cd      varchar(50) NOT NULL,
    action_cd    varchar(20) NOT NULL,
    created_at   timestamptz NOT NULL DEFAULT now(),
    created_by   varchar(50) NOT NULL,
    CONSTRAINT pk_sys_auth_prog PRIMARY KEY (comp_cd, auth_grp_cd, prog_cd, action_cd),
    CONSTRAINT fk_sys_auth_prog_group FOREIGN KEY (comp_cd, auth_grp_cd) REFERENCES sys_auth_group (comp_cd, auth_grp_cd),
    CONSTRAINT fk_sys_auth_prog_prog FOREIGN KEY (prog_cd) REFERENCES sys_prog (prog_cd),
    CONSTRAINT ck_sys_auth_prog_action CHECK (action_cd IN
        ('SEARCH','SAVE','DELETE','EXCEL','PRINT','APPROVE','FN1','FN2','FN3','FN4','FN5'))
);
COMMENT ON TABLE sys_auth_prog IS '권한그룹 × 프로그램 × 액션 (행이 있으면 허용)';

-- ---------------------------------------------------------------------
-- 6. RLS
-- ---------------------------------------------------------------------
ALTER TABLE sys_auth_group ENABLE ROW LEVEL SECURITY;
ALTER TABLE sys_auth_group FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON sys_auth_group
    USING (comp_cd = hb_current_comp()) WITH CHECK (comp_cd = hb_current_comp());

ALTER TABLE sys_user_auth ENABLE ROW LEVEL SECURITY;
ALTER TABLE sys_user_auth FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON sys_user_auth
    USING (comp_cd = hb_current_comp()) WITH CHECK (comp_cd = hb_current_comp());

ALTER TABLE sys_menu ENABLE ROW LEVEL SECURITY;
ALTER TABLE sys_menu FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON sys_menu
    USING (comp_cd = hb_current_comp()) WITH CHECK (comp_cd = hb_current_comp());

ALTER TABLE sys_auth_prog ENABLE ROW LEVEL SECURITY;
ALTER TABLE sys_auth_prog FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON sys_auth_prog
    USING (comp_cd = hb_current_comp()) WITH CHECK (comp_cd = hb_current_comp());

-- ---------------------------------------------------------------------
-- 7. 플랫폼 기본 프로그램 (P0 4단계: 내 정보 화면)
-- ---------------------------------------------------------------------
INSERT INTO sys_prog (prog_cd, prog_nm_key, view_path, module_cd, actions, sort_no)
VALUES ('sys-my-info', 'prog.sysMyInfo', 'platform/my-info', 'SYS', ARRAY['SEARCH','SAVE']::varchar(20)[], 900);
