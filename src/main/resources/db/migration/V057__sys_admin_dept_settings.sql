-- =====================================================================
-- 시스템관리 2단계 (docs/36 9장) — 부서 마스터 + 사용자 소속, 정책·회사 코드·채번·회사 정보 화면.
-- 임시 번호(병합 때 재번호, docs/29 2.2)
--
-- 1. sys_dept (회사별 트리) + 트리거: 같은 회사 부모, 순환·10단 금지, 사용 중 하위가 있으면 사용 중지 거부
-- 2. sys_user.dept_id + 트리거: 다른 회사·사용 중지 부서로 새로 넣는 것 거부(이미 있는 소속은 그대로 — 새 참조만 막는다)
-- 3. 화면 5개 (sys-dept · sys-policy · sys-code · sys-numbering · sys-comp)
-- 정책·회사 코드·채번 규칙·회사 표는 이미 있다(V001·V006) — 화면·API 만 새로
-- =====================================================================

-- ---------------------------------------------------------------------
-- 1. 부서
-- ---------------------------------------------------------------------
CREATE TABLE sys_dept (
    dept_id     bigint       GENERATED ALWAYS AS IDENTITY,
    comp_cd     varchar(20)  NOT NULL,
    parent_id   bigint,
    dept_cd     varchar(30)  NOT NULL,
    dept_nm     varchar(100) NOT NULL,
    sort_no     integer      NOT NULL DEFAULT 0,
    use_yn      char(1)      NOT NULL DEFAULT 'Y',
    row_ver     integer      NOT NULL DEFAULT 0,
    created_at  timestamptz  NOT NULL DEFAULT now(),
    created_by  varchar(50)  NOT NULL,
    updated_at  timestamptz  NOT NULL DEFAULT now(),
    updated_by  varchar(50)  NOT NULL,
    CONSTRAINT pk_sys_dept PRIMARY KEY (dept_id),
    CONSTRAINT fk_sys_dept_comp FOREIGN KEY (comp_cd) REFERENCES sys_comp (comp_cd),
    CONSTRAINT fk_sys_dept_parent FOREIGN KEY (parent_id) REFERENCES sys_dept (dept_id),
    CONSTRAINT ck_sys_dept_cd CHECK (dept_cd ~ '^[A-Z0-9][A-Z0-9_-]{0,29}$'),
    CONSTRAINT ck_sys_dept_nm CHECK (length(btrim(dept_nm)) > 0),
    CONSTRAINT ck_sys_dept_not_self CHECK (parent_id IS NULL OR parent_id <> dept_id),
    CONSTRAINT ck_sys_dept_use_yn CHECK (use_yn IN ('Y', 'N'))
);
CREATE UNIQUE INDEX uq_sys_dept_cd ON sys_dept (comp_cd, dept_cd);
CREATE INDEX ix_sys_dept_parent ON sys_dept (comp_cd, parent_id, sort_no);
COMMENT ON TABLE sys_dept IS '부서 (회사별 트리). 삭제 없음 — 사용 중지. 사용자 소속은 sys_user.dept_id (docs/36 9.1)';

ALTER TABLE sys_dept ENABLE ROW LEVEL SECURITY;
ALTER TABLE sys_dept FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON sys_dept
    USING (comp_cd = hb_current_comp()) WITH CHECK (comp_cd = hb_current_comp());

CREATE FUNCTION hb_check_dept_tree() RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    v_parent sys_dept;
    v_depth  integer;
    v_height integer := 0;
    v_cycle  boolean;
    v_moved  boolean;
BEGIN
    IF TG_OP = 'UPDATE' AND NEW.comp_cd <> OLD.comp_cd THEN
        RAISE EXCEPTION 'dept % cannot move to another company', OLD.dept_id
            USING ERRCODE = '55000', CONSTRAINT = 'ck_sys_dept_comp_fixed';
    END IF;
    -- 같은 회사의 부서 이동·사용 여부 변경을 한 줄로 (두 이동이 함께 순환을 만들거나, 하위를 켜는 동안 부모를 끄지 않게)
    PERFORM pg_advisory_xact_lock(hashtextextended('sys_dept:' || NEW.comp_cd, 0));
    v_moved := NEW.parent_id IS NOT NULL AND (TG_OP = 'INSERT' OR NEW.parent_id IS DISTINCT FROM OLD.parent_id);
    IF NEW.parent_id IS NOT NULL THEN
        SELECT * INTO v_parent FROM sys_dept WHERE dept_id = NEW.parent_id;
        -- 회사별 표 사이 FK 는 RLS 를 무시하므로 같은 회사를 직접 확인한다
        IF NOT FOUND OR v_parent.comp_cd <> NEW.comp_cd THEN
            RAISE EXCEPTION 'parent dept % is not in company %', NEW.parent_id, NEW.comp_cd
                USING ERRCODE = '23503', CONSTRAINT = 'fk_sys_dept_parent_comp';
        END IF;
        -- 새 부모 지정·다시 사용(N→Y)은 사용 중 부모 아래로만
        IF NEW.use_yn = 'Y' AND v_parent.use_yn <> 'Y'
           AND (v_moved OR (TG_OP = 'UPDATE' AND OLD.use_yn = 'N')) THEN
            RAISE EXCEPTION 'parent dept % is not in use', NEW.parent_id
                USING ERRCODE = '23514', CONSTRAINT = 'ck_sys_dept_parent_use';
        END IF;
    END IF;
    IF v_moved THEN
        WITH RECURSIVE up(dept_id, parent_id, depth) AS (
            SELECT dept_id, parent_id, 1 FROM sys_dept WHERE dept_id = NEW.parent_id
            UNION ALL
            SELECT d.dept_id, d.parent_id, u.depth + 1 FROM up u JOIN sys_dept d ON d.dept_id = u.parent_id
             WHERE u.depth < 50 AND u.dept_id <> NEW.dept_id
        )
        SELECT bool_or(dept_id = NEW.dept_id), max(depth) INTO v_cycle, v_depth FROM up;
        IF v_cycle THEN
            RAISE EXCEPTION 'dept % cannot move under its own descendant', NEW.dept_id
                USING ERRCODE = '23514', CONSTRAINT = 'ck_sys_dept_cycle';
        END IF;
        IF TG_OP = 'UPDATE' THEN
            WITH RECURSIVE down(dept_id, h) AS (
                SELECT dept_id, 1 FROM sys_dept WHERE parent_id = NEW.dept_id
                UNION ALL
                SELECT d.dept_id, w.h + 1 FROM down w JOIN sys_dept d ON d.parent_id = w.dept_id WHERE w.h < 50
            )
            SELECT coalesce(max(h), 0) INTO v_height FROM down;
        END IF;
        IF v_depth + 1 + v_height > 10 THEN
            RAISE EXCEPTION 'dept % would be deeper than 10', NEW.dept_id
                USING ERRCODE = '23514', CONSTRAINT = 'ck_sys_dept_depth';
        END IF;
    END IF;
    -- 사용 중지: 사용 중 하위가 있으면 거부(하위가 루트에서 닿지 않는 채 "사용 중" 으로 남지 않게)
    IF TG_OP = 'UPDATE' AND NEW.use_yn = 'N' AND OLD.use_yn = 'Y'
       AND EXISTS (SELECT 1 FROM sys_dept c WHERE c.parent_id = NEW.dept_id AND c.use_yn = 'Y') THEN
        RAISE EXCEPTION 'dept % has active children', NEW.dept_id
            USING ERRCODE = '23514', CONSTRAINT = 'ck_sys_dept_children_active';
    END IF;
    RETURN NEW;
END
$$;
CREATE TRIGGER tg_sys_dept_tree BEFORE INSERT OR UPDATE ON sys_dept
    FOR EACH ROW EXECUTE FUNCTION hb_check_dept_tree();
CREATE TRIGGER tg_sys_dept_no_delete BEFORE DELETE ON sys_dept
    FOR EACH ROW EXECUTE FUNCTION hb_forbid_delete_master();
CREATE TRIGGER tg_audit AFTER INSERT OR UPDATE OR DELETE ON sys_dept
    FOR EACH ROW EXECUTE FUNCTION hb_audit('dept_id', '', '');
INSERT INTO sys_retention_table (table_nm, class_cd) VALUES ('sys_dept', 'MASTER');

-- ---------------------------------------------------------------------
-- 2. 사용자 소속
-- ---------------------------------------------------------------------
ALTER TABLE sys_user ADD COLUMN dept_id bigint;
ALTER TABLE sys_user ADD CONSTRAINT fk_sys_user_dept FOREIGN KEY (dept_id) REFERENCES sys_dept (dept_id);
CREATE INDEX ix_sys_user_dept ON sys_user (comp_cd, dept_id);
COMMENT ON COLUMN sys_user.dept_id IS '소속 부서 (한 사람 한 부서, 겸직 없음 — docs/36 Q11)';

-- 새로 넣거나 바꿀 때만 본다: 같은 회사 + 사용 중 부서. 이미 소속된 부서가 사용 중지돼도 사람은 그대로(Q10)
CREATE FUNCTION hb_check_user_dept() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.dept_id IS NOT NULL AND (TG_OP = 'INSERT' OR NEW.dept_id IS DISTINCT FROM OLD.dept_id) THEN
        IF NOT EXISTS (SELECT 1 FROM sys_dept d WHERE d.dept_id = NEW.dept_id AND d.comp_cd = NEW.comp_cd) THEN
            RAISE EXCEPTION 'dept % does not belong to company %', NEW.dept_id, NEW.comp_cd
                USING ERRCODE = '23503', CONSTRAINT = 'fk_sys_user_dept_comp';
        END IF;
        -- 부서 행을 FOR SHARE 로 잡아 동시에 그 부서를 사용 중지하는 것과 직렬화(확인 후 실행 사이에 꺼지지 않게)
        PERFORM 1 FROM sys_dept d WHERE d.dept_id = NEW.dept_id AND d.use_yn = 'Y' FOR SHARE;
        IF NOT FOUND THEN
            RAISE EXCEPTION 'dept % is not in use', NEW.dept_id
                USING ERRCODE = '23514', CONSTRAINT = 'ck_sys_user_dept_use';
        END IF;
    END IF;
    RETURN NEW;
END
$$;
CREATE TRIGGER tg_sys_user_dept BEFORE INSERT OR UPDATE OF dept_id ON sys_user
    FOR EACH ROW EXECUTE FUNCTION hb_check_user_dept();

-- ---------------------------------------------------------------------
-- 3. 화면
-- ---------------------------------------------------------------------
INSERT INTO sys_prog (prog_cd, prog_nm_key, view_path, module_cd, actions, sort_no) VALUES
    ('sys-dept',      'prog.sysDept',      'admin/dept-mgt',      'SYS', ARRAY['SEARCH','SAVE']::varchar(20)[], 933),
    ('sys-policy',    'prog.sysPolicy',    'admin/policy-mgt',    'SYS', ARRAY['SEARCH','SAVE']::varchar(20)[], 934),
    ('sys-code',      'prog.sysCode',      'admin/code-mgt',      'SYS', ARRAY['SEARCH','SAVE']::varchar(20)[], 935),
    ('sys-numbering', 'prog.sysNumbering', 'admin/numbering-mgt', 'SYS', ARRAY['SEARCH','SAVE']::varchar(20)[], 936),
    ('sys-comp',      'prog.sysComp',      'admin/comp-mgt',      'SYS', ARRAY['SEARCH','SAVE']::varchar(20)[], 937);
