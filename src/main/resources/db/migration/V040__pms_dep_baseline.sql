-- =====================================================================
-- V040 PMS 2단계 — 작업 의존·기준선·계획 변경 이력 (docs/32 PM2, 4.4, 결정 D3·D4)
--
-- - pms_task_dep: 선행 → 후행(FS + 지연일). 잎 작업끼리, 같은 프로젝트, 순환·조상↔자손 금지. 추가·삭제만
-- - pms_baseline / pms_baseline_task: 기준선(번호 1·2·3…) + 모든 작업 계획일 스냅샷 — INSERT 만
-- - pms_task_plan_change: 기준선 뒤 계획일 변경(전·후·사유) — INSERT 만
-- 스냅샷·이력의 task_id 는 FK 를 두지 않는다(실적 없는 작업은 지울 수 있고, 지워도 기준선·이력은 남아야 한다)
-- ※ 병행 개발(docs/29 6장): V 번호가 겹치면 main 에 늦게 들어가는 쪽이 번호를 다시 매긴다
-- =====================================================================

-- ---------------------------------------------------------------------
-- 1. 의존
-- ---------------------------------------------------------------------
CREATE TABLE pms_task_dep (
    dep_id        bigint        GENERATED ALWAYS AS IDENTITY,
    comp_cd       varchar(20)   NOT NULL,
    proj_id       bigint        NOT NULL,
    pred_task_id  bigint        NOT NULL,
    succ_task_id  bigint        NOT NULL,
    lag_days      integer       NOT NULL DEFAULT 0,
    created_at    timestamptz   NOT NULL DEFAULT now(),
    created_by    varchar(50)   NOT NULL,
    CONSTRAINT pk_pms_task_dep PRIMARY KEY (dep_id),
    CONSTRAINT fk_pms_task_dep_proj FOREIGN KEY (proj_id) REFERENCES pms_project (proj_id),
    CONSTRAINT fk_pms_task_dep_pred FOREIGN KEY (pred_task_id) REFERENCES pms_task (task_id),
    CONSTRAINT fk_pms_task_dep_succ FOREIGN KEY (succ_task_id) REFERENCES pms_task (task_id),
    CONSTRAINT uq_pms_task_dep UNIQUE (comp_cd, pred_task_id, succ_task_id),
    CONSTRAINT ck_pms_task_dep_self CHECK (pred_task_id <> succ_task_id),
    CONSTRAINT ck_pms_task_dep_lag CHECK (lag_days BETWEEN -365 AND 365)
);
COMMENT ON TABLE pms_task_dep IS '작업 의존(FS: 후행 시작 ≥ 선행 종료 + 1 + lag). 위반은 막지 않고 경고, 후행 밀기는 사람이 확정 (docs/32 D4)';
CREATE INDEX ix_pms_task_dep_proj ON pms_task_dep (comp_cd, proj_id);
CREATE INDEX ix_pms_task_dep_succ ON pms_task_dep (comp_cd, succ_task_id);

CREATE FUNCTION hb_guard_pms_task_dep() RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    v_row    pms_task_dep;
    v_status varchar(10);
    v_cycle  boolean;
BEGIN
    v_row := CASE WHEN TG_OP = 'DELETE' THEN OLD ELSE NEW END;
    SELECT p.status INTO v_status FROM pms_project p WHERE p.proj_id = v_row.proj_id AND p.comp_cd = v_row.comp_cd;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'project % is not of company %', v_row.proj_id, v_row.comp_cd USING ERRCODE = '23503', CONSTRAINT = 'fk_pms_task_dep_proj_comp';
    END IF;
    IF v_status IN ('CLOSED', 'CANCELLED') THEN
        RAISE EXCEPTION 'project % is closed', v_row.proj_id USING ERRCODE = '55000', CONSTRAINT = 'ck_pms_task_dep_project_closed';
    END IF;
    IF TG_OP = 'DELETE' THEN
        RETURN OLD;
    END IF;
    IF TG_OP = 'UPDATE' THEN
        RAISE EXCEPTION 'dependency rows are insert/delete only' USING ERRCODE = '55000', CONSTRAINT = 'ck_pms_task_dep_fixed';
    END IF;
    -- 두 작업 모두 같은 회사·같은 프로젝트의 잎(요약 아님)
    IF (SELECT count(*) FROM pms_task t WHERE t.task_id IN (NEW.pred_task_id, NEW.succ_task_id)
           AND t.proj_id = NEW.proj_id AND t.comp_cd = NEW.comp_cd AND t.is_summary = 'N') <> 2 THEN
        RAISE EXCEPTION 'dependency tasks must be leaf tasks of project %', NEW.proj_id
            USING ERRCODE = '23514', CONSTRAINT = 'ck_pms_task_dep_tasks';
    END IF;
    -- 순환: 후행에서 의존을 따라가 선행에 닿으면 순환. UNION(중복 제거)이라 마름모 의존에서 경로 수가 폭증하지 않고
    -- (BOM 재귀 교훈 — SKILL §5) 이미 있는 순환에서도 끝난다
    WITH RECURSIVE nxt(task_id) AS (
        SELECT NEW.succ_task_id
        UNION
        SELECT d.succ_task_id FROM nxt n JOIN pms_task_dep d ON d.pred_task_id = n.task_id
    )
    SELECT bool_or(task_id = NEW.pred_task_id) INTO v_cycle FROM nxt;
    IF v_cycle THEN
        RAISE EXCEPTION 'dependency % -> % makes a cycle', NEW.pred_task_id, NEW.succ_task_id
            USING ERRCODE = '23514', CONSTRAINT = 'ck_pms_task_dep_cycle';
    END IF;
    RETURN NEW;
END
$$;
CREATE TRIGGER tg_pms_task_dep_guard BEFORE INSERT OR UPDATE OR DELETE ON pms_task_dep
    FOR EACH ROW EXECUTE FUNCTION hb_guard_pms_task_dep();

-- 의존이 걸린 잎이 요약이 되면(하위 추가) 의존 뜻이 흐려진다 → 막는다(서비스가 먼저 칸 오류로)
CREATE FUNCTION hb_guard_pms_task_dep_parent() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.parent_id IS NOT NULL AND (TG_OP = 'INSERT' OR NEW.parent_id IS DISTINCT FROM OLD.parent_id)
       AND EXISTS (SELECT 1 FROM pms_task_dep d WHERE d.pred_task_id = NEW.parent_id OR d.succ_task_id = NEW.parent_id) THEN
        RAISE EXCEPTION 'task % has dependencies and cannot have sub-tasks', NEW.parent_id
            USING ERRCODE = '23514', CONSTRAINT = 'ck_pms_task_parent_has_deps';
    END IF;
    RETURN NEW;
END
$$;
CREATE TRIGGER tg_pms_task_dep_parent BEFORE INSERT OR UPDATE ON pms_task FOR EACH ROW EXECUTE FUNCTION hb_guard_pms_task_dep_parent();

-- ---------------------------------------------------------------------
-- 2. 기준선
-- ---------------------------------------------------------------------
CREATE TABLE pms_baseline (
    baseline_id  bigint        GENERATED ALWAYS AS IDENTITY,
    comp_cd      varchar(20)   NOT NULL,
    proj_id      bigint        NOT NULL,
    baseline_no  integer       NOT NULL,
    reason       varchar(500)  NOT NULL,
    task_cnt     integer       NOT NULL,
    plan_end     date,
    created_at   timestamptz   NOT NULL DEFAULT now(),
    created_by   varchar(50)   NOT NULL,
    CONSTRAINT pk_pms_baseline PRIMARY KEY (baseline_id),
    CONSTRAINT fk_pms_baseline_proj FOREIGN KEY (proj_id) REFERENCES pms_project (proj_id),
    CONSTRAINT uq_pms_baseline_no UNIQUE (comp_cd, proj_id, baseline_no),
    CONSTRAINT ck_pms_baseline_no CHECK (baseline_no > 0),
    CONSTRAINT ck_pms_baseline_reason CHECK (length(btrim(reason)) > 0)
);
COMMENT ON TABLE pms_baseline IS '기준선(계획 확정 시점). INSERT 만 — 계획을 다시 확정하면 새 번호 (docs/32 D3)';

CREATE TABLE pms_baseline_task (
    bt_id        bigint        GENERATED ALWAYS AS IDENTITY,
    comp_cd      varchar(20)   NOT NULL,
    baseline_id  bigint        NOT NULL,
    task_id      bigint        NOT NULL,
    wbs_no       varchar(60)   NOT NULL,
    task_nm      varchar(200)  NOT NULL,
    is_summary   char(1)       NOT NULL,
    plan_start   date          NOT NULL,
    plan_end     date          NOT NULL,
    CONSTRAINT pk_pms_baseline_task PRIMARY KEY (bt_id),
    CONSTRAINT fk_pms_baseline_task_bl FOREIGN KEY (baseline_id) REFERENCES pms_baseline (baseline_id),
    CONSTRAINT uq_pms_baseline_task UNIQUE (comp_cd, baseline_id, task_id)
);
COMMENT ON TABLE pms_baseline_task IS '기준선 작업 스냅샷. task_id 는 FK 없음(작업을 지워도 스냅샷은 남는다)';

CREATE TABLE pms_task_plan_change (
    pc_id         bigint        GENERATED ALWAYS AS IDENTITY,
    comp_cd       varchar(20)   NOT NULL,
    proj_id       bigint        NOT NULL,
    task_id       bigint        NOT NULL,
    baseline_no   integer       NOT NULL,
    before_start  date          NOT NULL,
    before_end    date          NOT NULL,
    after_start   date          NOT NULL,
    after_end     date          NOT NULL,
    source        varchar(10)   NOT NULL,
    reason        varchar(500)  NOT NULL,
    created_at    timestamptz   NOT NULL DEFAULT now(),
    created_by    varchar(50)   NOT NULL,
    CONSTRAINT pk_pms_task_plan_change PRIMARY KEY (pc_id),
    CONSTRAINT fk_pms_task_plan_change_proj FOREIGN KEY (proj_id) REFERENCES pms_project (proj_id),
    CONSTRAINT ck_pms_task_plan_change_source CHECK (source IN ('EDIT', 'PUSH')),
    CONSTRAINT ck_pms_task_plan_change_reason CHECK (length(btrim(reason)) > 0)
);
COMMENT ON TABLE pms_task_plan_change IS '기준선 뒤 계획일 변경 이력(폼 EDIT / 후행 밀기 PUSH) — INSERT 만';
CREATE INDEX ix_pms_task_plan_change ON pms_task_plan_change (comp_cd, task_id, created_at);

-- 기준선 머리·스냅샷·변경 이력은 같은 회사 프로젝트만, 고치지 않는다
CREATE FUNCTION hb_guard_pms_baseline() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP <> 'INSERT' THEN
        RAISE EXCEPTION '% is insert only', TG_TABLE_NAME USING ERRCODE = '55000', CONSTRAINT = 'ck_pms_baseline_insert_only';
    END IF;
    IF TG_TABLE_NAME = 'pms_baseline_task' THEN
        IF NOT EXISTS (SELECT 1 FROM pms_baseline b WHERE b.baseline_id = NEW.baseline_id AND b.comp_cd = NEW.comp_cd) THEN
            RAISE EXCEPTION 'baseline % is not of company %', NEW.baseline_id, NEW.comp_cd
                USING ERRCODE = '23503', CONSTRAINT = 'fk_pms_baseline_task_comp';
        END IF;
    ELSIF NOT EXISTS (SELECT 1 FROM pms_project p WHERE p.proj_id = NEW.proj_id AND p.comp_cd = NEW.comp_cd
                         AND p.status NOT IN ('CLOSED', 'CANCELLED')) THEN
        RAISE EXCEPTION 'project % is closed or not of company %', NEW.proj_id, NEW.comp_cd
            USING ERRCODE = '55000', CONSTRAINT = 'ck_pms_baseline_project';
    END IF;
    RETURN NEW;
END
$$;
CREATE TRIGGER tg_pms_baseline_guard BEFORE INSERT OR UPDATE OR DELETE ON pms_baseline FOR EACH ROW EXECUTE FUNCTION hb_guard_pms_baseline();
CREATE TRIGGER tg_pms_baseline_task_guard BEFORE INSERT OR UPDATE OR DELETE ON pms_baseline_task FOR EACH ROW EXECUTE FUNCTION hb_guard_pms_baseline();
CREATE TRIGGER tg_pms_task_plan_change_guard BEFORE INSERT OR UPDATE OR DELETE ON pms_task_plan_change FOR EACH ROW EXECUTE FUNCTION hb_guard_pms_baseline();

-- ---------------------------------------------------------------------
-- 3. 감사 · RLS · 권한 · 보존
-- ---------------------------------------------------------------------
CREATE TRIGGER tg_audit AFTER INSERT OR UPDATE OR DELETE ON pms_task_dep FOR EACH ROW EXECUTE FUNCTION hb_audit('dep_id', '', '');
CREATE TRIGGER tg_audit AFTER INSERT OR UPDATE OR DELETE ON pms_baseline FOR EACH ROW EXECUTE FUNCTION hb_audit('baseline_id', '', '');
CREATE TRIGGER tg_audit AFTER INSERT OR UPDATE OR DELETE ON pms_baseline_task FOR EACH ROW EXECUTE FUNCTION hb_audit('bt_id', '', '');
CREATE TRIGGER tg_audit AFTER INSERT OR UPDATE OR DELETE ON pms_task_plan_change FOR EACH ROW EXECUTE FUNCTION hb_audit('pc_id', '', '');

ALTER TABLE pms_task_dep ENABLE ROW LEVEL SECURITY;
ALTER TABLE pms_task_dep FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON pms_task_dep USING (comp_cd = hb_current_comp()) WITH CHECK (comp_cd = hb_current_comp());
ALTER TABLE pms_baseline ENABLE ROW LEVEL SECURITY;
ALTER TABLE pms_baseline FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON pms_baseline USING (comp_cd = hb_current_comp()) WITH CHECK (comp_cd = hb_current_comp());
ALTER TABLE pms_baseline_task ENABLE ROW LEVEL SECURITY;
ALTER TABLE pms_baseline_task FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON pms_baseline_task USING (comp_cd = hb_current_comp()) WITH CHECK (comp_cd = hb_current_comp());
ALTER TABLE pms_task_plan_change ENABLE ROW LEVEL SECURITY;
ALTER TABLE pms_task_plan_change FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON pms_task_plan_change USING (comp_cd = hb_current_comp()) WITH CHECK (comp_cd = hb_current_comp());
REVOKE TRUNCATE ON pms_task_dep FROM ${app_role};
REVOKE UPDATE, DELETE, TRUNCATE ON pms_baseline, pms_baseline_task, pms_task_plan_change FROM ${app_role};

INSERT INTO sys_retention_table (table_nm, class_cd) VALUES
    ('pms_task_dep', 'QUALITY_RECORD'), ('pms_baseline', 'QUALITY_RECORD'), ('pms_baseline_task', 'QUALITY_RECORD'),
    ('pms_task_plan_change', 'QUALITY_RECORD');
