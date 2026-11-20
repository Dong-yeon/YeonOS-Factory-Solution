-- =====================================================================
-- V039 PMS 1단계 — 프로젝트·WBS·템플릿 (docs/32 PM1, 결정 D1·D2·D7·D8)
--
-- - pms_template / pms_template_task: 프로젝트 템플릿(기준일 START/SOP 대비 상대 일정, 트리)
-- - pms_project / pms_project_item: 프로젝트(채번 PMS_PROJECT, 유형, 고객·공장·PM·SOP 일, 상태 전이), 대상 품목
-- - pms_task: WBS 작업(트리, 계획·실적일, 진척률). 요약 작업(자식 있음)의 날짜·진척·상태는 서비스가 파생해 저장
-- - 트리: 같은 템플릿/프로젝트 안 부모만, 순환 금지, 깊이 10(정책 PMS_MAX_DEPTH 는 서비스가 더 좁게)
-- - 닫힌(CLOSED·CANCELLED) 프로젝트의 작업은 바꿀 수 없다(트리거)
-- ※ 병행 개발(docs/29 6장): QMS·WMS 등과 V 번호가 겹치면 main 에 늦게 들어가는 쪽이 번호를 다시 매긴다
-- =====================================================================

INSERT INTO sys_policy_def (policy_key, value_type, default_value, allowed_values, min_value, max_value, desc_key) VALUES
    ('PMS_MAX_DEPTH', 'INT', '6', NULL, 2, 10, 'policy.PMS_MAX_DEPTH'),
    ('PMS_MAX_TASKS', 'INT', '2000', NULL, 50, 10000, 'policy.PMS_MAX_TASKS');

INSERT INTO sys_code_grp (grp_cd, grp_nm_key, comp_ext_yn) VALUES
    ('PMS_PROJ_TYPE', 'code.grp.PMS_PROJ_TYPE', 'N');
INSERT INTO sys_code (grp_cd, code, code_nm_key, sort_no) VALUES
    ('PMS_PROJ_TYPE', 'APQP',    'code.PMS_PROJ_TYPE.APQP',    1),
    ('PMS_PROJ_TYPE', 'CHANGE',  'code.PMS_PROJ_TYPE.CHANGE',  2),
    ('PMS_PROJ_TYPE', 'EQUIP',   'code.PMS_PROJ_TYPE.EQUIP',   3),
    ('PMS_PROJ_TYPE', 'IMPROVE', 'code.PMS_PROJ_TYPE.IMPROVE', 4),
    ('PMS_PROJ_TYPE', 'GENERAL', 'code.PMS_PROJ_TYPE.GENERAL', 5);

-- ---------------------------------------------------------------------
-- 1. 템플릿
-- ---------------------------------------------------------------------
CREATE TABLE pms_template (
    tmpl_id     bigint        GENERATED ALWAYS AS IDENTITY,
    comp_cd     varchar(20)   NOT NULL,
    tmpl_cd     varchar(20)   NOT NULL,
    tmpl_nm     varchar(100)  NOT NULL,
    proj_type   varchar(10)   NOT NULL,
    anchor      varchar(5)    NOT NULL DEFAULT 'START',
    use_yn      char(1)       NOT NULL DEFAULT 'Y',
    remark      varchar(500),
    row_ver     integer       NOT NULL DEFAULT 0,
    created_at  timestamptz   NOT NULL DEFAULT now(),
    created_by  varchar(50)   NOT NULL,
    updated_at  timestamptz   NOT NULL DEFAULT now(),
    updated_by  varchar(50)   NOT NULL,
    CONSTRAINT pk_pms_template PRIMARY KEY (tmpl_id),
    CONSTRAINT fk_pms_template_comp FOREIGN KEY (comp_cd) REFERENCES sys_comp (comp_cd),
    CONSTRAINT uq_pms_template_cd UNIQUE (comp_cd, tmpl_cd),
    CONSTRAINT ck_pms_template_cd CHECK (tmpl_cd ~ '^[A-Z0-9][A-Z0-9_-]{0,19}$'),
    CONSTRAINT ck_pms_template_nm CHECK (length(btrim(tmpl_nm)) > 0),
    CONSTRAINT ck_pms_template_type CHECK (proj_type IN ('APQP', 'CHANGE', 'EQUIP', 'IMPROVE', 'GENERAL')),
    CONSTRAINT ck_pms_template_anchor CHECK (anchor IN ('START', 'SOP')),
    CONSTRAINT ck_pms_template_use_yn CHECK (use_yn IN ('Y', 'N'))
);
COMMENT ON TABLE pms_template IS '프로젝트 템플릿. anchor = 상대 일정의 기준일(START 시작일 / SOP 양산 시작일, docs/32 4.1)';
CREATE TRIGGER tg_pms_template_no_delete BEFORE DELETE ON pms_template FOR EACH ROW EXECUTE FUNCTION hb_forbid_delete_master();

CREATE TABLE pms_template_task (
    tt_id          bigint        GENERATED ALWAYS AS IDENTITY,
    comp_cd        varchar(20)   NOT NULL,
    tmpl_id        bigint        NOT NULL,
    parent_id      bigint,
    seq            integer       NOT NULL,
    task_type      varchar(10)   NOT NULL DEFAULT 'TASK',
    task_nm        varchar(200)  NOT NULL,
    offset_days    integer       NOT NULL DEFAULT 0,
    duration_days  integer       NOT NULL DEFAULT 1,
    is_summary     char(1)       NOT NULL DEFAULT 'N',
    remark         varchar(500),
    row_ver        integer       NOT NULL DEFAULT 0,
    created_at     timestamptz   NOT NULL DEFAULT now(),
    created_by     varchar(50)   NOT NULL,
    updated_at     timestamptz   NOT NULL DEFAULT now(),
    updated_by     varchar(50)   NOT NULL,
    CONSTRAINT pk_pms_template_task PRIMARY KEY (tt_id),
    CONSTRAINT fk_pms_template_task_tmpl FOREIGN KEY (tmpl_id) REFERENCES pms_template (tmpl_id),
    CONSTRAINT fk_pms_template_task_parent FOREIGN KEY (parent_id) REFERENCES pms_template_task (tt_id),
    CONSTRAINT ck_pms_template_task_type CHECK (task_type IN ('TASK', 'MILESTONE', 'GATE')),
    CONSTRAINT ck_pms_template_task_nm CHECK (length(btrim(task_nm)) > 0),
    CONSTRAINT ck_pms_template_task_offset CHECK (offset_days BETWEEN -3650 AND 3650),
    -- 마일스톤·게이트는 하루(기간 0), 작업은 1일 이상
    CONSTRAINT ck_pms_template_task_duration CHECK (
        (task_type = 'TASK' AND duration_days BETWEEN 1 AND 3650) OR (task_type <> 'TASK' AND duration_days = 0)),
    CONSTRAINT ck_pms_template_task_summary CHECK (is_summary IN ('Y', 'N'))
);
COMMENT ON TABLE pms_template_task IS '템플릿 작업(트리). offset_days = 기준일 대비 시작(음수 = 기준일 전), 요약(자식 있음)의 일정은 복사 때 자식에서 계산';
CREATE INDEX ix_pms_template_task ON pms_template_task (comp_cd, tmpl_id, parent_id, seq);

-- ---------------------------------------------------------------------
-- 2. 프로젝트
-- ---------------------------------------------------------------------
CREATE TABLE pms_project (
    proj_id        bigint        GENERATED ALWAYS AS IDENTITY,
    comp_cd        varchar(20)   NOT NULL,
    proj_no        varchar(30)   NOT NULL,
    proj_nm        varchar(200)  NOT NULL,
    proj_type      varchar(10)   NOT NULL,
    cust_id        bigint,
    plant_cd       varchar(20),
    pm_user_id     bigint        NOT NULL,
    tmpl_id        bigint,
    sop_date       date,
    plan_start     date,
    plan_end       date,
    progress_pct   smallint      NOT NULL DEFAULT 0,
    status         varchar(10)   NOT NULL DEFAULT 'DRAFT',
    status_reason  varchar(500),
    closed_on      date,
    remark         varchar(1000),
    row_ver        integer       NOT NULL DEFAULT 0,
    created_at     timestamptz   NOT NULL DEFAULT now(),
    created_by     varchar(50)   NOT NULL,
    updated_at     timestamptz   NOT NULL DEFAULT now(),
    updated_by     varchar(50)   NOT NULL,
    CONSTRAINT pk_pms_project PRIMARY KEY (proj_id),
    CONSTRAINT fk_pms_project_comp FOREIGN KEY (comp_cd) REFERENCES sys_comp (comp_cd),
    CONSTRAINT fk_pms_project_cust FOREIGN KEY (cust_id) REFERENCES mst_cust (cust_id),
    CONSTRAINT fk_pms_project_plant FOREIGN KEY (comp_cd, plant_cd) REFERENCES sys_plant (comp_cd, plant_cd),
    CONSTRAINT fk_pms_project_pm FOREIGN KEY (pm_user_id) REFERENCES sys_user (user_id),
    CONSTRAINT fk_pms_project_tmpl FOREIGN KEY (tmpl_id) REFERENCES pms_template (tmpl_id),
    CONSTRAINT uq_pms_project_no UNIQUE (comp_cd, proj_no),
    CONSTRAINT ck_pms_project_nm CHECK (length(btrim(proj_nm)) > 0),
    CONSTRAINT ck_pms_project_type CHECK (proj_type IN ('APQP', 'CHANGE', 'EQUIP', 'IMPROVE', 'GENERAL')),
    CONSTRAINT ck_pms_project_status CHECK (status IN ('DRAFT', 'ACTIVE', 'ON_HOLD', 'CLOSED', 'CANCELLED')),
    CONSTRAINT ck_pms_project_progress CHECK (progress_pct BETWEEN 0 AND 100),
    CONSTRAINT ck_pms_project_plan CHECK (plan_start IS NULL OR plan_end IS NULL OR plan_start <= plan_end),
    CONSTRAINT ck_pms_project_dates CHECK (sop_date IS NULL OR sop_date BETWEEN DATE '1950-01-01' AND DATE '2999-12-31'),
    CONSTRAINT ck_pms_project_closed CHECK ((status IN ('CLOSED', 'CANCELLED')) = (closed_on IS NOT NULL))
);
COMMENT ON TABLE pms_project IS '프로젝트(PMS 프로젝트관리). plan_start/end·progress_pct 는 작업에서 파생(서비스가 저장), 삭제 없음 — 취소는 CANCELLED (docs/32)';
CREATE TRIGGER tg_pms_project_no_delete BEFORE DELETE ON pms_project FOR EACH ROW EXECUTE FUNCTION hb_forbid_delete_master();

CREATE FUNCTION hb_guard_pms_project() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    -- 회사별 테이블 간 FK 는 RLS 를 무시한다 → 같은 회사 행인지 직접 확인
    IF NEW.cust_id IS NOT NULL AND (TG_OP = 'INSERT' OR NEW.cust_id IS DISTINCT FROM OLD.cust_id)
       AND NOT EXISTS (SELECT 1 FROM mst_cust c WHERE c.cust_id = NEW.cust_id AND c.comp_cd = NEW.comp_cd) THEN
        RAISE EXCEPTION 'customer % is not of company %', NEW.cust_id, NEW.comp_cd USING ERRCODE = '23503', CONSTRAINT = 'fk_pms_project_cust_comp';
    END IF;
    IF (TG_OP = 'INSERT' OR NEW.pm_user_id IS DISTINCT FROM OLD.pm_user_id)
       AND NOT EXISTS (SELECT 1 FROM sys_user u WHERE u.user_id = NEW.pm_user_id AND u.comp_cd = NEW.comp_cd) THEN
        RAISE EXCEPTION 'user % is not of company %', NEW.pm_user_id, NEW.comp_cd USING ERRCODE = '23503', CONSTRAINT = 'fk_pms_project_pm_comp';
    END IF;
    IF NEW.tmpl_id IS NOT NULL AND (TG_OP = 'INSERT' OR NEW.tmpl_id IS DISTINCT FROM OLD.tmpl_id)
       AND NOT EXISTS (SELECT 1 FROM pms_template t WHERE t.tmpl_id = NEW.tmpl_id AND t.comp_cd = NEW.comp_cd) THEN
        RAISE EXCEPTION 'template % is not of company %', NEW.tmpl_id, NEW.comp_cd USING ERRCODE = '23503', CONSTRAINT = 'fk_pms_project_tmpl_comp';
    END IF;
    IF TG_OP = 'INSERT' THEN
        IF NEW.status <> 'DRAFT' THEN
            RAISE EXCEPTION 'project starts as DRAFT' USING ERRCODE = '55000', CONSTRAINT = 'ck_pms_project_transition';
        END IF;
        RETURN NEW;
    END IF;
    IF NEW.proj_no <> OLD.proj_no THEN
        RAISE EXCEPTION 'project number is fixed' USING ERRCODE = '55000', CONSTRAINT = 'ck_pms_project_no_fixed';
    END IF;
    -- 닫힌 프로젝트는 바뀌지 않는다(재개 없음 — 새 프로젝트로)
    IF OLD.status IN ('CLOSED', 'CANCELLED') THEN
        RAISE EXCEPTION 'project % is closed', OLD.proj_no USING ERRCODE = '55000', CONSTRAINT = 'ck_pms_project_closed_fixed';
    END IF;
    IF NEW.status <> OLD.status AND NOT (
           (OLD.status = 'DRAFT'   AND NEW.status IN ('ACTIVE', 'CANCELLED'))
        OR (OLD.status = 'ACTIVE'  AND NEW.status IN ('ON_HOLD', 'CLOSED', 'CANCELLED'))
        OR (OLD.status = 'ON_HOLD' AND NEW.status IN ('ACTIVE', 'CANCELLED'))) THEN
        RAISE EXCEPTION 'project status % -> % is not allowed', OLD.status, NEW.status
            USING ERRCODE = '55000', CONSTRAINT = 'ck_pms_project_transition';
    END IF;
    RETURN NEW;
END
$$;
CREATE TRIGGER tg_pms_project_guard BEFORE INSERT OR UPDATE ON pms_project FOR EACH ROW EXECUTE FUNCTION hb_guard_pms_project();

CREATE TABLE pms_project_item (
    pi_id       bigint        GENERATED ALWAYS AS IDENTITY,
    comp_cd     varchar(20)   NOT NULL,
    proj_id     bigint        NOT NULL,
    item_id     bigint        NOT NULL,
    created_at  timestamptz   NOT NULL DEFAULT now(),
    created_by  varchar(50)   NOT NULL,
    CONSTRAINT pk_pms_project_item PRIMARY KEY (pi_id),
    CONSTRAINT fk_pms_project_item_proj FOREIGN KEY (proj_id) REFERENCES pms_project (proj_id),
    CONSTRAINT fk_pms_project_item_item FOREIGN KEY (item_id) REFERENCES mst_item (item_id),
    CONSTRAINT uq_pms_project_item UNIQUE (comp_cd, proj_id, item_id)
);
COMMENT ON TABLE pms_project_item IS '프로젝트 대상 품목 (신규 부품 런칭·변경 대상). 품목 화면에서 "이 품목의 프로젝트" 를 찾는 키';
CREATE INDEX ix_pms_project_item_item ON pms_project_item (comp_cd, item_id);

-- ---------------------------------------------------------------------
-- 3. WBS 작업
-- ---------------------------------------------------------------------
CREATE TABLE pms_task (
    task_id        bigint        GENERATED ALWAYS AS IDENTITY,
    comp_cd        varchar(20)   NOT NULL,
    proj_id        bigint        NOT NULL,
    parent_id      bigint,
    seq            integer       NOT NULL,
    task_type      varchar(10)   NOT NULL DEFAULT 'TASK',
    task_nm        varchar(200)  NOT NULL,
    owner_user_id  bigint,
    plan_start     date          NOT NULL,
    plan_end       date          NOT NULL,
    act_start      date,
    act_end        date,
    progress_pct   smallint      NOT NULL DEFAULT 0,
    status         varchar(12)   NOT NULL DEFAULT 'NOT_STARTED',
    is_summary     char(1)       NOT NULL DEFAULT 'N',
    remark         varchar(500),
    row_ver        integer       NOT NULL DEFAULT 0,
    created_at     timestamptz   NOT NULL DEFAULT now(),
    created_by     varchar(50)   NOT NULL,
    updated_at     timestamptz   NOT NULL DEFAULT now(),
    updated_by     varchar(50)   NOT NULL,
    CONSTRAINT pk_pms_task PRIMARY KEY (task_id),
    CONSTRAINT fk_pms_task_proj FOREIGN KEY (proj_id) REFERENCES pms_project (proj_id),
    CONSTRAINT fk_pms_task_parent FOREIGN KEY (parent_id) REFERENCES pms_task (task_id),
    CONSTRAINT fk_pms_task_owner FOREIGN KEY (owner_user_id) REFERENCES sys_user (user_id),
    CONSTRAINT ck_pms_task_type CHECK (task_type IN ('TASK', 'MILESTONE', 'GATE')),
    CONSTRAINT ck_pms_task_nm CHECK (length(btrim(task_nm)) > 0),
    CONSTRAINT ck_pms_task_status CHECK (status IN ('NOT_STARTED', 'IN_PROGRESS', 'DONE', 'CANCELLED')),
    CONSTRAINT ck_pms_task_progress CHECK (progress_pct BETWEEN 0 AND 100),
    CONSTRAINT ck_pms_task_summary CHECK (is_summary IN ('Y', 'N')),
    CONSTRAINT ck_pms_task_dates CHECK (plan_start >= DATE '1950-01-01' AND plan_end <= DATE '2999-12-31'
                                        AND (act_start IS NULL OR act_start >= DATE '1950-01-01')
                                        AND (act_end IS NULL OR act_end <= DATE '2999-12-31')),
    CONSTRAINT ck_pms_task_plan CHECK (plan_start <= plan_end),
    CONSTRAINT ck_pms_task_act CHECK (act_end IS NULL OR (act_start IS NOT NULL AND act_start <= act_end)),
    -- 마일스톤·게이트는 하루짜리(요약이 될 수 없다는 것은 서비스가 막는다)
    CONSTRAINT ck_pms_task_point CHECK (task_type = 'TASK' OR plan_start = plan_end),
    -- 상태와 실적·진척이 서로 맞아야 한다. 취소는 그때 값을 그대로 둔다
    --   NOT_STARTED = 실적 없음·0% / IN_PROGRESS = 시작일 있음·종료일 없음·100% 미만 / DONE = 시작·종료일·100%
    CONSTRAINT ck_pms_task_state CHECK (
           (status = 'NOT_STARTED' AND act_start IS NULL AND act_end IS NULL AND progress_pct = 0)
        OR (status = 'IN_PROGRESS' AND act_start IS NOT NULL AND act_end IS NULL AND progress_pct < 100)
        OR (status = 'DONE' AND act_end IS NOT NULL AND progress_pct = 100)
        OR status = 'CANCELLED')
);
COMMENT ON TABLE pms_task IS 'WBS 작업(트리). 요약(is_summary=Y, 자식 있음)의 계획·실적·진척·상태는 자식에서 파생해 서비스가 저장. WBS 번호는 저장하지 않는다(조회 때 계산)';
CREATE INDEX ix_pms_task_proj ON pms_task (comp_cd, proj_id, parent_id, seq);
CREATE INDEX ix_pms_task_owner ON pms_task (comp_cd, owner_user_id, status);

-- 템플릿·프로젝트 트리 공통 검사: 같은 머리 안 부모, 순환, 깊이 10
CREATE FUNCTION hb_guard_pms_template_task() RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    v_cycle boolean;
    v_depth integer;
BEGIN
    IF TG_OP = 'UPDATE' AND NEW.tmpl_id <> OLD.tmpl_id THEN
        RAISE EXCEPTION 'template task cannot move to another template' USING ERRCODE = '55000', CONSTRAINT = 'ck_pms_template_task_tmpl_fixed';
    END IF;
    IF TG_OP = 'INSERT' AND NOT EXISTS (SELECT 1 FROM pms_template t WHERE t.tmpl_id = NEW.tmpl_id AND t.comp_cd = NEW.comp_cd) THEN
        RAISE EXCEPTION 'template % is not of company %', NEW.tmpl_id, NEW.comp_cd USING ERRCODE = '23503', CONSTRAINT = 'fk_pms_template_task_tmpl_comp';
    END IF;
    IF NEW.parent_id IS NOT NULL AND (TG_OP = 'INSERT' OR NEW.parent_id IS DISTINCT FROM OLD.parent_id) THEN
        IF NOT EXISTS (SELECT 1 FROM pms_template_task p WHERE p.tt_id = NEW.parent_id AND p.tmpl_id = NEW.tmpl_id AND p.comp_cd = NEW.comp_cd) THEN
            RAISE EXCEPTION 'parent % is not in template %', NEW.parent_id, NEW.tmpl_id USING ERRCODE = '23503', CONSTRAINT = 'fk_pms_template_task_parent_tmpl';
        END IF;
        WITH RECURSIVE up(tt_id, parent_id, depth) AS (
            SELECT tt_id, parent_id, 1 FROM pms_template_task WHERE tt_id = NEW.parent_id
            UNION ALL
            SELECT t.tt_id, t.parent_id, u.depth + 1 FROM up u JOIN pms_template_task t ON t.tt_id = u.parent_id
             WHERE u.depth < 20 AND u.tt_id <> NEW.tt_id
        )
        SELECT bool_or(tt_id = NEW.tt_id), max(depth) INTO v_cycle, v_depth FROM up;
        IF v_cycle THEN
            RAISE EXCEPTION 'task % cannot move under its own descendant', NEW.tt_id USING ERRCODE = '23514', CONSTRAINT = 'ck_pms_template_task_cycle';
        END IF;
        IF v_depth >= 10 THEN
            RAISE EXCEPTION 'template tree deeper than 10 levels' USING ERRCODE = '23514', CONSTRAINT = 'ck_pms_template_task_depth';
        END IF;
    END IF;
    RETURN NEW;
END
$$;
CREATE TRIGGER tg_pms_template_task_guard BEFORE INSERT OR UPDATE ON pms_template_task
    FOR EACH ROW EXECUTE FUNCTION hb_guard_pms_template_task();

CREATE FUNCTION hb_guard_pms_task() RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    v_status varchar(10);
    v_cycle  boolean;
    v_depth  integer;
    v_row    pms_task;
BEGIN
    v_row := CASE WHEN TG_OP = 'DELETE' THEN OLD ELSE NEW END;
    SELECT p.status INTO v_status FROM pms_project p WHERE p.proj_id = v_row.proj_id AND p.comp_cd = v_row.comp_cd;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'project % is not of company %', v_row.proj_id, v_row.comp_cd USING ERRCODE = '23503', CONSTRAINT = 'fk_pms_task_proj_comp';
    END IF;
    IF v_status IN ('CLOSED', 'CANCELLED') THEN
        RAISE EXCEPTION 'project % is closed', v_row.proj_id USING ERRCODE = '55000', CONSTRAINT = 'ck_pms_task_project_closed';
    END IF;
    IF TG_OP = 'DELETE' THEN
        -- 실적이 있는 작업은 지우지 않는다(취소로) — 자식이 있으면 FK 가 막는다
        IF OLD.act_start IS NOT NULL THEN
            RAISE EXCEPTION 'task % has actuals (cancel instead)', OLD.task_id USING ERRCODE = '55000', CONSTRAINT = 'ck_pms_task_delete_actual';
        END IF;
        RETURN OLD;
    END IF;
    IF TG_OP = 'UPDATE' AND NEW.proj_id <> OLD.proj_id THEN
        RAISE EXCEPTION 'task cannot move to another project' USING ERRCODE = '55000', CONSTRAINT = 'ck_pms_task_proj_fixed';
    END IF;
    IF NEW.owner_user_id IS NOT NULL AND (TG_OP = 'INSERT' OR NEW.owner_user_id IS DISTINCT FROM OLD.owner_user_id)
       AND NOT EXISTS (SELECT 1 FROM sys_user u WHERE u.user_id = NEW.owner_user_id AND u.comp_cd = NEW.comp_cd) THEN
        RAISE EXCEPTION 'user % is not of company %', NEW.owner_user_id, NEW.comp_cd USING ERRCODE = '23503', CONSTRAINT = 'fk_pms_task_owner_comp';
    END IF;
    IF NEW.parent_id IS NOT NULL AND (TG_OP = 'INSERT' OR NEW.parent_id IS DISTINCT FROM OLD.parent_id) THEN
        IF NOT EXISTS (SELECT 1 FROM pms_task p WHERE p.task_id = NEW.parent_id AND p.proj_id = NEW.proj_id AND p.comp_cd = NEW.comp_cd) THEN
            RAISE EXCEPTION 'parent % is not in project %', NEW.parent_id, NEW.proj_id USING ERRCODE = '23503', CONSTRAINT = 'fk_pms_task_parent_proj';
        END IF;
        WITH RECURSIVE up(task_id, parent_id, depth) AS (
            SELECT task_id, parent_id, 1 FROM pms_task WHERE task_id = NEW.parent_id
            UNION ALL
            SELECT t.task_id, t.parent_id, u.depth + 1 FROM up u JOIN pms_task t ON t.task_id = u.parent_id
             WHERE u.depth < 20 AND u.task_id <> NEW.task_id
        )
        SELECT bool_or(task_id = NEW.task_id), max(depth) INTO v_cycle, v_depth FROM up;
        IF v_cycle THEN
            RAISE EXCEPTION 'task % cannot move under its own descendant', NEW.task_id USING ERRCODE = '23514', CONSTRAINT = 'ck_pms_task_cycle';
        END IF;
        IF v_depth >= 10 THEN
            RAISE EXCEPTION 'WBS deeper than 10 levels' USING ERRCODE = '23514', CONSTRAINT = 'ck_pms_task_depth';
        END IF;
    END IF;
    RETURN NEW;
END
$$;
CREATE TRIGGER tg_pms_task_guard BEFORE INSERT OR UPDATE OR DELETE ON pms_task FOR EACH ROW EXECUTE FUNCTION hb_guard_pms_task();

CREATE FUNCTION hb_guard_pms_project_item() RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    v_row pms_project_item;
BEGIN
    v_row := CASE WHEN TG_OP = 'DELETE' THEN OLD ELSE NEW END;
    IF NOT EXISTS (SELECT 1 FROM pms_project p WHERE p.proj_id = v_row.proj_id AND p.comp_cd = v_row.comp_cd
                      AND p.status NOT IN ('CLOSED', 'CANCELLED')) THEN
        RAISE EXCEPTION 'project % is closed or not of company %', v_row.proj_id, v_row.comp_cd
            USING ERRCODE = '55000', CONSTRAINT = 'ck_pms_project_item_proj';
    END IF;
    IF TG_OP = 'INSERT' AND NOT EXISTS (SELECT 1 FROM mst_item i WHERE i.item_id = NEW.item_id AND i.comp_cd = NEW.comp_cd) THEN
        RAISE EXCEPTION 'item % is not of company %', NEW.item_id, NEW.comp_cd USING ERRCODE = '23503', CONSTRAINT = 'fk_pms_project_item_item_comp';
    END IF;
    IF TG_OP = 'UPDATE' THEN
        RAISE EXCEPTION 'project item rows are insert/delete only' USING ERRCODE = '55000', CONSTRAINT = 'ck_pms_project_item_fixed';
    END IF;
    RETURN v_row;
END
$$;
CREATE TRIGGER tg_pms_project_item_guard BEFORE INSERT OR UPDATE OR DELETE ON pms_project_item
    FOR EACH ROW EXECUTE FUNCTION hb_guard_pms_project_item();

-- ---------------------------------------------------------------------
-- 4. 감사 · RLS · 권한 · 화면 · 보존
-- ---------------------------------------------------------------------
CREATE TRIGGER tg_audit AFTER INSERT OR UPDATE OR DELETE ON pms_template FOR EACH ROW EXECUTE FUNCTION hb_audit('tmpl_id', '', '');
CREATE TRIGGER tg_audit AFTER INSERT OR UPDATE OR DELETE ON pms_template_task FOR EACH ROW EXECUTE FUNCTION hb_audit('tt_id', '', '');
CREATE TRIGGER tg_audit AFTER INSERT OR UPDATE OR DELETE ON pms_project FOR EACH ROW EXECUTE FUNCTION hb_audit('proj_id', '', '');
CREATE TRIGGER tg_audit AFTER INSERT OR UPDATE OR DELETE ON pms_project_item FOR EACH ROW EXECUTE FUNCTION hb_audit('pi_id', '', '');
CREATE TRIGGER tg_audit AFTER INSERT OR UPDATE OR DELETE ON pms_task FOR EACH ROW EXECUTE FUNCTION hb_audit('task_id', '', '');

ALTER TABLE pms_template ENABLE ROW LEVEL SECURITY;
ALTER TABLE pms_template FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON pms_template USING (comp_cd = hb_current_comp()) WITH CHECK (comp_cd = hb_current_comp());
ALTER TABLE pms_template_task ENABLE ROW LEVEL SECURITY;
ALTER TABLE pms_template_task FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON pms_template_task USING (comp_cd = hb_current_comp()) WITH CHECK (comp_cd = hb_current_comp());
ALTER TABLE pms_project ENABLE ROW LEVEL SECURITY;
ALTER TABLE pms_project FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON pms_project USING (comp_cd = hb_current_comp()) WITH CHECK (comp_cd = hb_current_comp());
ALTER TABLE pms_project_item ENABLE ROW LEVEL SECURITY;
ALTER TABLE pms_project_item FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON pms_project_item USING (comp_cd = hb_current_comp()) WITH CHECK (comp_cd = hb_current_comp());
ALTER TABLE pms_task ENABLE ROW LEVEL SECURITY;
ALTER TABLE pms_task FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON pms_task USING (comp_cd = hb_current_comp()) WITH CHECK (comp_cd = hb_current_comp());
-- 템플릿 행·대상 품목·실적 없는 작업은 지울 수 있다(트리거가 조건 확인). 머리(템플릿·프로젝트)는 지우지 않는다
REVOKE DELETE, TRUNCATE ON pms_template, pms_project FROM ${app_role};
REVOKE TRUNCATE ON pms_template_task, pms_project_item, pms_task FROM ${app_role};

INSERT INTO sys_prog (prog_cd, prog_nm_key, view_path, module_cd, actions, sort_no) VALUES
    -- APPROVE = 회사 전체 프로젝트 관리(PMO): PM 이 아닌 프로젝트도 머리·WBS 를 고칠 수 있다(docs/32 D8)
    ('pms-template', 'prog.pmsTemplate', 'pms/template-mgt', 'PMS', ARRAY['SEARCH','SAVE']::varchar(20)[], 500),
    ('pms-project',  'prog.pmsProject',  'pms/project-mgt',  'PMS', ARRAY['SEARCH','SAVE','APPROVE']::varchar(20)[], 510);
INSERT INTO sys_retention_table (table_nm, class_cd) VALUES
    ('pms_template', 'MASTER'), ('pms_template_task', 'MASTER'),
    -- APQP 승인 근거 — 부품 EOL + 1년(02 2.3, IATF 7.5.3.2.1)
    ('pms_project', 'QUALITY_RECORD'), ('pms_project_item', 'QUALITY_RECORD'), ('pms_task', 'QUALITY_RECORD');
