-- =====================================================================
-- 회사별 사용 모듈 (docs/80) — 임시 V9990(병합 때 재번호)
-- 1. sys_comp_module: 회사가 고른 업무 모듈 켜기·끄기. 행이 없으면 켜짐(기존 회사 동작 변화 없음).
--    끄기 = 숨김 — 메뉴·화면·API·단말에서 막을 뿐 업무 데이터는 지우지 않는다(다시 켜면 그대로).
--    판정은 AuthMapper.xml 의 moduleOn 조각(권한 SQL 3곳)과 ModuleGateInterceptor.
-- 2. sys_prog.module_cd 바로잡기 7개 — 메뉴 자리(docs/75)와 같은 모듈로(docs/80 1.1, 사용자 결정 2026-11-16).
--    module_cd 는 지금까지 권한그룹·메뉴 관리 화면의 묶음 표시에만 쓰였다.
-- 3. 사용 모듈 화면 sys-module (시스템 > 운영 설정).
-- =====================================================================

CREATE TABLE sys_comp_module (
    comp_cd     varchar(20)  NOT NULL,
    module_cd   varchar(10)  NOT NULL,
    use_yn      char(1)      NOT NULL DEFAULT 'Y',
    row_ver     integer      NOT NULL DEFAULT 0,
    created_at  timestamptz  NOT NULL DEFAULT now(),
    created_by  varchar(50)  NOT NULL,
    updated_at  timestamptz  NOT NULL DEFAULT now(),
    updated_by  varchar(50)  NOT NULL,
    CONSTRAINT pk_sys_comp_module PRIMARY KEY (comp_cd, module_cd),
    CONSTRAINT fk_sys_comp_module_comp FOREIGN KEY (comp_cd) REFERENCES sys_comp (comp_cd),
    CONSTRAINT ck_sys_comp_module_cd CHECK (module_cd ~ '^[A-Z]{2,10}$'),
    -- 시스템·홈·플랫폼 공통은 항상 켜짐 — 행 자체를 두지 않는다
    CONSTRAINT ck_sys_comp_module_sys CHECK (module_cd <> 'SYS'),
    CONSTRAINT ck_sys_comp_module_use CHECK (use_yn IN ('Y', 'N'))
);
COMMENT ON TABLE sys_comp_module IS '회사별 사용 모듈 (docs/80) — 행이 없으면 켜짐. 끄기는 숨김(메뉴·화면·API·단말), 데이터는 그대로';
COMMENT ON COLUMN sys_comp_module.module_cd IS '업무 모듈 코드(sys_prog.module_cd, 목록은 ModuleCatalog) — SYS 는 둘 수 없음';
COMMENT ON COLUMN sys_comp_module.use_yn IS 'Y 켜짐 / N 꺼짐';

CREATE TRIGGER tg_audit AFTER INSERT OR UPDATE OR DELETE ON sys_comp_module
    FOR EACH ROW EXECUTE FUNCTION hb_audit('module_cd', '', '');

ALTER TABLE sys_comp_module ENABLE ROW LEVEL SECURITY;
ALTER TABLE sys_comp_module FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON sys_comp_module USING (comp_cd = hb_current_comp()) WITH CHECK (comp_cd = hb_current_comp());

REVOKE DELETE, TRUNCATE ON sys_comp_module FROM ${app_role};

INSERT INTO sys_retention_table (table_nm, class_cd) VALUES ('sys_comp_module', 'SYSTEM');

-- 2. module_cd 바로잡기 (docs/80 1.1)
UPDATE sys_prog SET module_cd = 'QMS', updated_at = now(), updated_by = 'FLYWAY' WHERE prog_cd IN ('apqp-fmea', 'apqp-dfmea', 'apqp-cp', 'apqp-ap');
UPDATE sys_prog SET module_cd = 'CMS', updated_at = now(), updated_by = 'FLYWAY' WHERE prog_cd IN ('cms-state', 'cms-equip-count');
UPDATE sys_prog SET module_cd = 'SYS', updated_at = now(), updated_by = 'FLYWAY' WHERE prog_cd = 'sys-terminal';

-- 3. 사용 모듈 화면
INSERT INTO sys_prog (prog_cd, prog_nm_key, view_path, module_cd, actions, sort_no) VALUES
    ('sys-module', 'prog.sysModule', 'admin/module-mgt', 'SYS', ARRAY['SEARCH','SAVE']::varchar(20)[], 915);
