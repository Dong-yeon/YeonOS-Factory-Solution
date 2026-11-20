-- =====================================================================
-- V042 CMS 1부 C2 — 점검표·예방점검 계획(달력 주기)·점검 실행 (docs/33 4.3, 결정 D5·D6·D7·D8)
--
-- - cms_chk_tmpl / cms_chk_tmpl_item: 점검표(리비전 없음 — 실행 행이 스냅샷)
-- - cms_pm_plan: 대상 × 점검표 × 실행 방식(WO/CHECK) × 달력 주기(FIXED/FLOAT). 한 계획에 열린 예정은 하나
-- - cms_chk_run / cms_chk_result: 점검 실행과 결과(항목 스냅샷). 결과는 OPEN 에서만 바뀐다
-- - cms_mwo.pm_plan_id FK, cms_mwo.src_run_id(점검 NG 후속)
-- - 정책 CMS_PM_LEAD_DAYS(예정 생성 선행일)·CMS_PM_GRACE_DAYS(기한 준수 허용)
-- 병행 개발(docs/33 6장): 번호는 main 병합 직전에 다시 매길 수 있다.
-- =====================================================================

INSERT INTO sys_policy_def (policy_key, value_type, default_value, allowed_values, min_value, max_value, desc_key) VALUES
    ('CMS_PM_LEAD_DAYS',  'INT', '7', NULL, 0, 90, 'policy.CMS_PM_LEAD_DAYS'),
    ('CMS_PM_GRACE_DAYS', 'INT', '0', NULL, 0, 30, 'policy.CMS_PM_GRACE_DAYS');

INSERT INTO sys_code_grp (grp_cd, grp_nm_key, comp_ext_yn) VALUES
    ('CMS_JUDGE_TYPE',  'code.grp.CMS_JUDGE_TYPE',  'N'),
    ('CMS_EXEC_MODE',   'code.grp.CMS_EXEC_MODE',   'N'),
    ('CMS_CAL_UNIT',    'code.grp.CMS_CAL_UNIT',    'N'),
    ('CMS_SCHED_BASIS', 'code.grp.CMS_SCHED_BASIS', 'N'),
    ('CMS_RUN_STATUS',  'code.grp.CMS_RUN_STATUS',  'N');
INSERT INTO sys_code (grp_cd, code, code_nm_key, sort_no) VALUES
    ('CMS_JUDGE_TYPE', 'OKNG', 'code.CMS_JUDGE_TYPE.OKNG', 1),
    ('CMS_JUDGE_TYPE', 'NUM',  'code.CMS_JUDGE_TYPE.NUM',  2),
    ('CMS_JUDGE_TYPE', 'TEXT', 'code.CMS_JUDGE_TYPE.TEXT', 3),
    ('CMS_EXEC_MODE', 'WO',    'code.CMS_EXEC_MODE.WO',    1),
    ('CMS_EXEC_MODE', 'CHECK', 'code.CMS_EXEC_MODE.CHECK', 2),
    ('CMS_CAL_UNIT', 'DAY',   'code.CMS_CAL_UNIT.DAY',   1),
    ('CMS_CAL_UNIT', 'WEEK',  'code.CMS_CAL_UNIT.WEEK',  2),
    ('CMS_CAL_UNIT', 'MONTH', 'code.CMS_CAL_UNIT.MONTH', 3),
    ('CMS_CAL_UNIT', 'YEAR',  'code.CMS_CAL_UNIT.YEAR',  4),
    ('CMS_SCHED_BASIS', 'FIXED', 'code.CMS_SCHED_BASIS.FIXED', 1),
    ('CMS_SCHED_BASIS', 'FLOAT', 'code.CMS_SCHED_BASIS.FLOAT', 2),
    ('CMS_RUN_STATUS', 'OPEN',    'code.CMS_RUN_STATUS.OPEN',    1),
    ('CMS_RUN_STATUS', 'DONE',    'code.CMS_RUN_STATUS.DONE',    2),
    ('CMS_RUN_STATUS', 'SKIPPED', 'code.CMS_RUN_STATUS.SKIPPED', 3);

-- ---------------------------------------------------------------------
-- 1. 점검표
-- ---------------------------------------------------------------------
CREATE TABLE cms_chk_tmpl (
    tmpl_id         bigint        GENERATED ALWAYS AS IDENTITY,
    comp_cd         varchar(20)   NOT NULL,
    tmpl_cd         varchar(20)   NOT NULL,
    tmpl_nm         varchar(100)  NOT NULL,
    target_kind     varchar(5)    NOT NULL DEFAULT 'EQUIP',
    equip_class_id  bigint,
    use_yn          char(1)       NOT NULL DEFAULT 'Y',
    remark          varchar(500),
    row_ver         integer       NOT NULL DEFAULT 0,
    created_at      timestamptz   NOT NULL DEFAULT now(),
    created_by      varchar(50)   NOT NULL,
    updated_at      timestamptz   NOT NULL DEFAULT now(),
    updated_by      varchar(50)   NOT NULL,
    CONSTRAINT pk_cms_chk_tmpl PRIMARY KEY (tmpl_id),
    CONSTRAINT fk_cms_chk_tmpl_comp FOREIGN KEY (comp_cd) REFERENCES sys_comp (comp_cd),
    CONSTRAINT fk_cms_chk_tmpl_class FOREIGN KEY (equip_class_id) REFERENCES mst_equip_class (class_id),
    CONSTRAINT uq_cms_chk_tmpl_cd UNIQUE (comp_cd, tmpl_cd),
    CONSTRAINT ck_cms_chk_tmpl_cd CHECK (tmpl_cd ~ '^[A-Z0-9][A-Z0-9_-]{0,19}$'),
    CONSTRAINT ck_cms_chk_tmpl_nm CHECK (length(btrim(tmpl_nm)) > 0),
    CONSTRAINT ck_cms_chk_tmpl_kind CHECK (target_kind IN ('EQUIP', 'ASSET')),
    -- 설비 클래스 한정은 설비 점검표만
    CONSTRAINT ck_cms_chk_tmpl_class_kind CHECK (equip_class_id IS NULL OR target_kind = 'EQUIP'),
    CONSTRAINT ck_cms_chk_tmpl_use_yn CHECK (use_yn IN ('Y', 'N'))
);
COMMENT ON TABLE cms_chk_tmpl IS '점검표 (docs/33 C2). 리비전 없음 — 점검 실행이 항목·기준을 스냅샷한다(D8)';

CREATE TABLE cms_chk_tmpl_item (
    item_id      bigint         GENERATED ALWAYS AS IDENTITY,
    comp_cd      varchar(20)    NOT NULL,
    tmpl_id      bigint         NOT NULL,
    seq          integer        NOT NULL,
    item_nm      varchar(200)   NOT NULL,
    method_text  varchar(500),
    judge_type   varchar(5)     NOT NULL DEFAULT 'OKNG',
    lsl          numeric(18,6),
    usl          numeric(18,6),
    unit_nm      varchar(20),
    scale        smallint       NOT NULL DEFAULT 2,
    required_yn  char(1)        NOT NULL DEFAULT 'Y',
    row_ver      integer        NOT NULL DEFAULT 0,
    created_at   timestamptz    NOT NULL DEFAULT now(),
    created_by   varchar(50)    NOT NULL,
    updated_at   timestamptz    NOT NULL DEFAULT now(),
    updated_by   varchar(50)    NOT NULL,
    CONSTRAINT pk_cms_chk_tmpl_item PRIMARY KEY (item_id),
    CONSTRAINT fk_cms_chk_tmpl_item_tmpl FOREIGN KEY (tmpl_id) REFERENCES cms_chk_tmpl (tmpl_id),
    CONSTRAINT uq_cms_chk_tmpl_item_seq UNIQUE (comp_cd, tmpl_id, seq) DEFERRABLE INITIALLY DEFERRED,
    CONSTRAINT ck_cms_chk_tmpl_item_seq CHECK (seq BETWEEN 1 AND 9999),
    CONSTRAINT ck_cms_chk_tmpl_item_nm CHECK (length(btrim(item_nm)) > 0),
    CONSTRAINT ck_cms_chk_tmpl_item_judge CHECK (judge_type IN ('OKNG', 'NUM', 'TEXT')),
    CONSTRAINT ck_cms_chk_tmpl_item_scale CHECK (scale BETWEEN 0 AND 6),
    -- 수치 판정: 하한·상한 중 하나 이상, 하한 < 상한 / 그 밖: 수치 없음
    CONSTRAINT ck_cms_chk_tmpl_item_spec CHECK (
        (judge_type = 'NUM' AND (lsl IS NOT NULL OR usl IS NOT NULL) AND (lsl IS NULL OR usl IS NULL OR lsl < usl))
        OR (judge_type <> 'NUM' AND lsl IS NULL AND usl IS NULL)),
    CONSTRAINT ck_cms_chk_tmpl_item_req CHECK (required_yn IN ('Y', 'N'))
);
COMMENT ON TABLE cms_chk_tmpl_item IS '점검 항목 — 한 벌 저장(서버가 id 로 추가·수정·삭제). 지워도 과거 점검 실행은 스냅샷이라 그대로';

CREATE FUNCTION hb_guard_cms_chk_tmpl() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'UPDATE' AND (NEW.tmpl_cd <> OLD.tmpl_cd OR NEW.target_kind <> OLD.target_kind) THEN
        RAISE EXCEPTION 'template code and target kind are fixed' USING ERRCODE = '55000', CONSTRAINT = 'ck_cms_chk_tmpl_fixed';
    END IF;
    IF NEW.equip_class_id IS NOT NULL AND (TG_OP = 'INSERT' OR NEW.equip_class_id IS DISTINCT FROM OLD.equip_class_id)
       AND NOT EXISTS (SELECT 1 FROM mst_equip_class c WHERE c.class_id = NEW.equip_class_id AND c.comp_cd = NEW.comp_cd
                          AND c.use_yn = 'Y') THEN
        RAISE EXCEPTION 'equipment class % is not an active class of company %', NEW.equip_class_id, NEW.comp_cd
            USING ERRCODE = '23503', CONSTRAINT = 'fk_cms_chk_tmpl_class_comp';
    END IF;
    RETURN NEW;
END
$$;
CREATE TRIGGER tg_cms_chk_tmpl_guard BEFORE INSERT OR UPDATE ON cms_chk_tmpl FOR EACH ROW EXECUTE FUNCTION hb_guard_cms_chk_tmpl();
CREATE TRIGGER tg_cms_chk_tmpl_no_delete BEFORE DELETE ON cms_chk_tmpl FOR EACH ROW EXECUTE FUNCTION hb_forbid_delete_master();

CREATE FUNCTION hb_guard_cms_chk_tmpl_item() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'UPDATE' AND NEW.tmpl_id <> OLD.tmpl_id THEN
        RAISE EXCEPTION 'item template is fixed' USING ERRCODE = '55000', CONSTRAINT = 'ck_cms_chk_tmpl_item_fixed';
    END IF;
    IF TG_OP = 'INSERT' AND NOT EXISTS (SELECT 1 FROM cms_chk_tmpl t WHERE t.tmpl_id = NEW.tmpl_id AND t.comp_cd = NEW.comp_cd) THEN
        RAISE EXCEPTION 'template % is not of company %', NEW.tmpl_id, NEW.comp_cd
            USING ERRCODE = '23503', CONSTRAINT = 'fk_cms_chk_tmpl_item_tmpl_comp';
    END IF;
    RETURN NEW;
END
$$;
CREATE TRIGGER tg_cms_chk_tmpl_item_guard BEFORE INSERT OR UPDATE ON cms_chk_tmpl_item
    FOR EACH ROW EXECUTE FUNCTION hb_guard_cms_chk_tmpl_item();

-- ---------------------------------------------------------------------
-- 2. 예방점검 계획
-- ---------------------------------------------------------------------
CREATE TABLE cms_pm_plan (
    plan_id         bigint        GENERATED ALWAYS AS IDENTITY,
    comp_cd         varchar(20)   NOT NULL,
    plan_cd         varchar(60)   NOT NULL,
    plan_nm         varchar(100)  NOT NULL,
    target_type     varchar(5)    NOT NULL,
    equip_id        bigint,
    asset_id        bigint,
    tmpl_id         bigint        NOT NULL,
    exec_mode       varchar(5)    NOT NULL DEFAULT 'WO',
    cycle_type      varchar(5)    NOT NULL DEFAULT 'CAL',
    cal_unit        varchar(5)    NOT NULL,
    cal_interval    integer       NOT NULL,
    schedule_basis  varchar(5)    NOT NULL DEFAULT 'FIXED',
    anchor_date     date          NOT NULL,
    lead_days       integer,
    assignee_id     bigint,
    est_minutes     integer,
    next_due_date   date          NOT NULL,
    last_done_date  date,
    use_yn          char(1)       NOT NULL DEFAULT 'Y',
    remark          varchar(500),
    row_ver         integer       NOT NULL DEFAULT 0,
    created_at      timestamptz   NOT NULL DEFAULT now(),
    created_by      varchar(50)   NOT NULL,
    updated_at      timestamptz   NOT NULL DEFAULT now(),
    updated_by      varchar(50)   NOT NULL,
    CONSTRAINT pk_cms_pm_plan PRIMARY KEY (plan_id),
    CONSTRAINT fk_cms_pm_plan_comp FOREIGN KEY (comp_cd) REFERENCES sys_comp (comp_cd),
    CONSTRAINT fk_cms_pm_plan_equip FOREIGN KEY (equip_id) REFERENCES mst_equip (equip_id),
    CONSTRAINT fk_cms_pm_plan_asset FOREIGN KEY (asset_id) REFERENCES mst_phys_asset (asset_id),
    CONSTRAINT fk_cms_pm_plan_tmpl FOREIGN KEY (tmpl_id) REFERENCES cms_chk_tmpl (tmpl_id),
    CONSTRAINT fk_cms_pm_plan_assignee FOREIGN KEY (assignee_id) REFERENCES sys_user (user_id),
    CONSTRAINT uq_cms_pm_plan_cd UNIQUE (comp_cd, plan_cd),
    CONSTRAINT ck_cms_pm_plan_cd CHECK (plan_cd ~ '^[A-Z0-9][A-Z0-9._-]{0,59}$'),
    CONSTRAINT ck_cms_pm_plan_nm CHECK (length(btrim(plan_nm)) > 0),
    CONSTRAINT ck_cms_pm_plan_target CHECK ((target_type = 'EQUIP' AND equip_id IS NOT NULL AND asset_id IS NULL)
                                           OR (target_type = 'ASSET' AND asset_id IS NOT NULL AND equip_id IS NULL)),
    CONSTRAINT ck_cms_pm_plan_mode CHECK (exec_mode IN ('WO', 'CHECK')),
    -- 사용량 주기(USAGE)는 C3 에서 이 제약을 바꿔 연다
    CONSTRAINT ck_cms_pm_plan_cycle CHECK (cycle_type = 'CAL'),
    CONSTRAINT ck_cms_pm_plan_unit CHECK (cal_unit IN ('DAY', 'WEEK', 'MONTH', 'YEAR')),
    CONSTRAINT ck_cms_pm_plan_interval CHECK (cal_interval BETWEEN 1 AND 999),
    CONSTRAINT ck_cms_pm_plan_basis CHECK (schedule_basis IN ('FIXED', 'FLOAT')),
    CONSTRAINT ck_cms_pm_plan_lead CHECK (lead_days IS NULL OR lead_days BETWEEN 0 AND 90),
    CONSTRAINT ck_cms_pm_plan_est CHECK (est_minutes IS NULL OR est_minutes BETWEEN 1 AND 10080),
    CONSTRAINT ck_cms_pm_plan_dates CHECK (anchor_date BETWEEN DATE '2000-01-01' AND DATE '2999-12-31'
                                          AND next_due_date BETWEEN DATE '2000-01-01' AND DATE '2999-12-31'),
    CONSTRAINT ck_cms_pm_plan_use_yn CHECK (use_yn IN ('Y', 'N'))
);
CREATE INDEX ix_cms_pm_plan_due ON cms_pm_plan (comp_cd, use_yn, next_due_date);
CREATE INDEX ix_cms_pm_plan_equip ON cms_pm_plan (comp_cd, equip_id) WHERE equip_id IS NOT NULL;
COMMENT ON TABLE cms_pm_plan IS '예방점검 계획 (docs/33 C2·4.3). 한 계획에 열린 예정은 하나 — 끝나거나 건너뛰면 PmDue 가 다음 예정일을 계산';
COMMENT ON COLUMN cms_pm_plan.anchor_date IS '첫 예정일 — FIXED 의 k 번째 예정 = 첫 예정일 + k×간격(월·년은 첫 예정일에서 다시 더한다)';

CREATE FUNCTION hb_guard_cms_pm_plan() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'UPDATE' AND (NEW.plan_cd <> OLD.plan_cd OR NEW.target_type <> OLD.target_type
                             OR NEW.equip_id IS DISTINCT FROM OLD.equip_id OR NEW.asset_id IS DISTINCT FROM OLD.asset_id) THEN
        RAISE EXCEPTION 'plan code and target are fixed' USING ERRCODE = '55000', CONSTRAINT = 'ck_cms_pm_plan_fixed';
    END IF;
    IF TG_OP = 'INSERT' THEN
        IF NEW.equip_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM mst_equip e WHERE e.equip_id = NEW.equip_id
                AND e.comp_cd = NEW.comp_cd AND e.use_yn = 'Y') THEN
            RAISE EXCEPTION 'equipment % is not an active equipment of company %', NEW.equip_id, NEW.comp_cd
                USING ERRCODE = '23503', CONSTRAINT = 'fk_cms_pm_plan_equip_comp';
        END IF;
        IF NEW.asset_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM mst_phys_asset a WHERE a.asset_id = NEW.asset_id
                AND a.comp_cd = NEW.comp_cd AND a.use_yn = 'Y' AND a.asset_type IN ('MOLD', 'JIG')) THEN
            RAISE EXCEPTION 'asset % is not an active mold or jig of company %', NEW.asset_id, NEW.comp_cd
                USING ERRCODE = '23503', CONSTRAINT = 'fk_cms_pm_plan_asset_comp';
        END IF;
    END IF;
    -- 점검표: 같은 회사·같은 대상 종류, 클래스 한정이면 그 클래스 설비, 새로 걸 때는 사용 중만
    IF TG_OP = 'INSERT' OR NEW.tmpl_id <> OLD.tmpl_id THEN
        IF NOT EXISTS (SELECT 1 FROM cms_chk_tmpl t WHERE t.tmpl_id = NEW.tmpl_id AND t.comp_cd = NEW.comp_cd AND t.use_yn = 'Y'
                          AND t.target_kind = NEW.target_type
                          AND (t.equip_class_id IS NULL
                               OR t.equip_class_id = (SELECT e.class_id FROM mst_equip e WHERE e.equip_id = NEW.equip_id))) THEN
            RAISE EXCEPTION 'template % does not fit this plan target', NEW.tmpl_id
                USING ERRCODE = '23503', CONSTRAINT = 'fk_cms_pm_plan_tmpl_comp';
        END IF;
    END IF;
    IF NEW.assignee_id IS NOT NULL AND (TG_OP = 'INSERT' OR NEW.assignee_id IS DISTINCT FROM OLD.assignee_id)
       AND NOT EXISTS (SELECT 1 FROM sys_user u WHERE u.user_id = NEW.assignee_id AND u.comp_cd = NEW.comp_cd AND u.use_yn = 'Y') THEN
        RAISE EXCEPTION 'user % is not an active user of company %', NEW.assignee_id, NEW.comp_cd
            USING ERRCODE = '23503', CONSTRAINT = 'fk_cms_pm_plan_assignee_comp';
    END IF;
    RETURN NEW;
END
$$;
CREATE TRIGGER tg_cms_pm_plan_guard BEFORE INSERT OR UPDATE ON cms_pm_plan FOR EACH ROW EXECUTE FUNCTION hb_guard_cms_pm_plan();
CREATE TRIGGER tg_cms_pm_plan_no_delete BEFORE DELETE ON cms_pm_plan FOR EACH ROW EXECUTE FUNCTION hb_forbid_delete_master();

-- 작업지시 → 계획 FK(V032 는 계획 테이블 전이라 걸지 못했다), 점검 NG 후속의 출처
ALTER TABLE cms_mwo ADD CONSTRAINT fk_cms_mwo_pm_plan FOREIGN KEY (pm_plan_id) REFERENCES cms_pm_plan (plan_id);

-- ---------------------------------------------------------------------
-- 3. 점검 실행 · 결과
-- ---------------------------------------------------------------------
CREATE TABLE cms_chk_run (
    run_id        bigint        GENERATED ALWAYS AS IDENTITY,
    comp_cd       varchar(20)   NOT NULL,
    plan_id       bigint        NOT NULL,
    mwo_id        bigint,
    target_type   varchar(5)    NOT NULL,
    equip_id      bigint,
    asset_id      bigint,
    tmpl_id       bigint        NOT NULL,
    due_date      date          NOT NULL,
    status        varchar(8)    NOT NULL DEFAULT 'OPEN',
    performed_at  timestamptz,
    performed_by  bigint,
    result        varchar(2),
    skip_reason   varchar(500),
    row_ver       integer       NOT NULL DEFAULT 0,
    created_at    timestamptz   NOT NULL DEFAULT now(),
    created_by    varchar(50)   NOT NULL,
    updated_at    timestamptz   NOT NULL DEFAULT now(),
    updated_by    varchar(50)   NOT NULL,
    CONSTRAINT pk_cms_chk_run PRIMARY KEY (run_id),
    CONSTRAINT fk_cms_chk_run_comp FOREIGN KEY (comp_cd) REFERENCES sys_comp (comp_cd),
    CONSTRAINT fk_cms_chk_run_plan FOREIGN KEY (plan_id) REFERENCES cms_pm_plan (plan_id),
    CONSTRAINT fk_cms_chk_run_mwo FOREIGN KEY (mwo_id) REFERENCES cms_mwo (mwo_id),
    CONSTRAINT fk_cms_chk_run_equip FOREIGN KEY (equip_id) REFERENCES mst_equip (equip_id),
    CONSTRAINT fk_cms_chk_run_asset FOREIGN KEY (asset_id) REFERENCES mst_phys_asset (asset_id),
    CONSTRAINT fk_cms_chk_run_tmpl FOREIGN KEY (tmpl_id) REFERENCES cms_chk_tmpl (tmpl_id),
    CONSTRAINT fk_cms_chk_run_by FOREIGN KEY (performed_by) REFERENCES sys_user (user_id),
    CONSTRAINT uq_cms_chk_run_due UNIQUE (comp_cd, plan_id, due_date),
    CONSTRAINT uq_cms_chk_run_mwo UNIQUE (comp_cd, mwo_id),
    CONSTRAINT ck_cms_chk_run_target CHECK ((target_type = 'EQUIP' AND equip_id IS NOT NULL AND asset_id IS NULL)
                                          OR (target_type = 'ASSET' AND asset_id IS NOT NULL AND equip_id IS NULL)),
    CONSTRAINT ck_cms_chk_run_status CHECK (status IN ('OPEN', 'DONE', 'SKIPPED')),
    CONSTRAINT ck_cms_chk_run_done CHECK ((status = 'DONE') = (performed_at IS NOT NULL AND result IS NOT NULL)),
    CONSTRAINT ck_cms_chk_run_result CHECK (result IS NULL OR result IN ('OK', 'NG')),
    CONSTRAINT ck_cms_chk_run_skip CHECK ((status = 'SKIPPED') = (skip_reason IS NOT NULL AND length(btrim(skip_reason)) > 0))
);
CREATE INDEX ix_cms_chk_run_due ON cms_chk_run (comp_cd, due_date, status);
CREATE INDEX ix_cms_chk_run_equip ON cms_chk_run (comp_cd, equip_id, due_date) WHERE equip_id IS NOT NULL;
COMMENT ON TABLE cms_chk_run IS '점검 실행 (docs/33 C2) — 계획의 예정 하나. WO 방식은 작업지시와 1:1, CHECK 방식은 단독(일상점검, D7)';

CREATE TABLE cms_chk_result (
    result_id    bigint         GENERATED ALWAYS AS IDENTITY,
    comp_cd      varchar(20)    NOT NULL,
    run_id       bigint         NOT NULL,
    seq          integer        NOT NULL,
    item_nm      varchar(200)   NOT NULL,
    method_text  varchar(500),
    judge_type   varchar(5)     NOT NULL,
    lsl          numeric(18,6),
    usl          numeric(18,6),
    unit_nm      varchar(20),
    scale        smallint       NOT NULL,
    required_yn  char(1)        NOT NULL,
    value_num    numeric(18,6),
    value_text   varchar(500),
    judge        varchar(2),
    remark       varchar(500),
    row_ver      integer        NOT NULL DEFAULT 0,
    created_at   timestamptz    NOT NULL DEFAULT now(),
    created_by   varchar(50)    NOT NULL,
    updated_at   timestamptz    NOT NULL DEFAULT now(),
    updated_by   varchar(50)    NOT NULL,
    CONSTRAINT pk_cms_chk_result PRIMARY KEY (result_id),
    CONSTRAINT fk_cms_chk_result_run FOREIGN KEY (run_id) REFERENCES cms_chk_run (run_id),
    CONSTRAINT uq_cms_chk_result_seq UNIQUE (comp_cd, run_id, seq),
    CONSTRAINT ck_cms_chk_result_judge CHECK (judge IS NULL OR judge IN ('OK', 'NG')),
    -- 수치 항목은 값이 있으면 판정도(서버가 계산), 판정 없는 글 항목은 판정 없음
    CONSTRAINT ck_cms_chk_result_num CHECK (judge_type <> 'NUM' OR (value_num IS NULL) = (judge IS NULL)),
    CONSTRAINT ck_cms_chk_result_text CHECK (judge_type <> 'TEXT' OR judge IS NULL),
    CONSTRAINT ck_cms_chk_result_value CHECK (judge_type = 'NUM' OR value_num IS NULL)
);
COMMENT ON TABLE cms_chk_result IS '점검 결과 — 생성 때 점검표 항목을 스냅샷, 점검 실행이 OPEN 일 때만 값이 바뀐다';

-- 점검 실행: 키 칸 고정, 끝난(DONE·SKIPPED) 실행은 불변, 상태는 OPEN → DONE|SKIPPED 만
CREATE FUNCTION hb_guard_cms_chk_run() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'check run cannot be deleted (skip it)' USING ERRCODE = '55000', CONSTRAINT = 'ck_cms_chk_run_no_delete';
    END IF;
    IF TG_OP = 'INSERT' THEN
        IF NEW.status <> 'OPEN' THEN
            RAISE EXCEPTION 'new check run must be OPEN' USING ERRCODE = '55000', CONSTRAINT = 'ck_cms_chk_run_transition';
        END IF;
        IF NOT EXISTS (SELECT 1 FROM cms_pm_plan p WHERE p.plan_id = NEW.plan_id AND p.comp_cd = NEW.comp_cd
                          AND p.target_type = NEW.target_type AND p.equip_id IS NOT DISTINCT FROM NEW.equip_id
                          AND p.asset_id IS NOT DISTINCT FROM NEW.asset_id) THEN
            RAISE EXCEPTION 'plan % does not match this check run', NEW.plan_id USING ERRCODE = '23503', CONSTRAINT = 'fk_cms_chk_run_plan_comp';
        END IF;
        IF NEW.mwo_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM cms_mwo m WHERE m.mwo_id = NEW.mwo_id AND m.comp_cd = NEW.comp_cd
                AND m.pm_plan_id = NEW.plan_id AND m.due_ref_date = NEW.due_date) THEN
            RAISE EXCEPTION 'work order % does not match this check run', NEW.mwo_id USING ERRCODE = '23503', CONSTRAINT = 'fk_cms_chk_run_mwo_comp';
        END IF;
        IF NOT EXISTS (SELECT 1 FROM cms_chk_tmpl t WHERE t.tmpl_id = NEW.tmpl_id AND t.comp_cd = NEW.comp_cd) THEN
            RAISE EXCEPTION 'template % is not of company %', NEW.tmpl_id, NEW.comp_cd USING ERRCODE = '23503', CONSTRAINT = 'fk_cms_chk_run_tmpl_comp';
        END IF;
        RETURN NEW;
    END IF;
    IF NEW.plan_id <> OLD.plan_id OR NEW.mwo_id IS DISTINCT FROM OLD.mwo_id OR NEW.tmpl_id <> OLD.tmpl_id
       OR NEW.due_date <> OLD.due_date OR NEW.equip_id IS DISTINCT FROM OLD.equip_id OR NEW.asset_id IS DISTINCT FROM OLD.asset_id
       OR NEW.comp_cd <> OLD.comp_cd THEN
        RAISE EXCEPTION 'check run key columns are fixed' USING ERRCODE = '55000', CONSTRAINT = 'ck_cms_chk_run_fixed';
    END IF;
    IF OLD.status <> 'OPEN' THEN
        RAISE EXCEPTION 'finished check run is read-only' USING ERRCODE = '55000', CONSTRAINT = 'ck_cms_chk_run_done_fixed';
    END IF;
    IF NEW.performed_by IS NOT NULL AND NOT hb_user_of_comp(NEW.comp_cd, NEW.performed_by) THEN
        RAISE EXCEPTION 'user % is not of company %', NEW.performed_by, NEW.comp_cd USING ERRCODE = '23503', CONSTRAINT = 'fk_cms_chk_run_by_comp';
    END IF;
    RETURN NEW;
END
$$;
CREATE TRIGGER tg_cms_chk_run_guard BEFORE INSERT OR UPDATE OR DELETE ON cms_chk_run FOR EACH ROW EXECUTE FUNCTION hb_guard_cms_chk_run();

CREATE FUNCTION hb_guard_cms_chk_result() RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    r record;
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'check result cannot be deleted' USING ERRCODE = '55000', CONSTRAINT = 'ck_cms_chk_result_no_delete';
    END IF;
    IF TG_OP = 'UPDATE' AND (NEW.run_id <> OLD.run_id OR NEW.seq <> OLD.seq OR NEW.item_nm <> OLD.item_nm
                             OR NEW.judge_type <> OLD.judge_type OR NEW.lsl IS DISTINCT FROM OLD.lsl
                             OR NEW.usl IS DISTINCT FROM OLD.usl OR NEW.scale <> OLD.scale OR NEW.required_yn <> OLD.required_yn) THEN
        RAISE EXCEPTION 'check result snapshot is fixed' USING ERRCODE = '55000', CONSTRAINT = 'ck_cms_chk_result_fixed';
    END IF;
    -- 실행 행을 잠가 완료와 줄 세운다 — 완료와 동시에 들어온 값이 완료 뒤에 남지 않게
    PERFORM 1 FROM cms_chk_run x WHERE x.run_id = NEW.run_id AND x.comp_cd = NEW.comp_cd AND x.status = 'OPEN' FOR UPDATE;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'results change only while the check run is OPEN' USING ERRCODE = '55000', CONSTRAINT = 'ck_cms_chk_result_status';
    END IF;
    r := NEW;
    RETURN r;
END
$$;
CREATE TRIGGER tg_cms_chk_result_guard BEFORE INSERT OR UPDATE OR DELETE ON cms_chk_result
    FOR EACH ROW EXECUTE FUNCTION hb_guard_cms_chk_result();

ALTER TABLE cms_mwo ADD COLUMN src_run_id bigint;
ALTER TABLE cms_mwo ADD CONSTRAINT fk_cms_mwo_src_run FOREIGN KEY (src_run_id) REFERENCES cms_chk_run (run_id);
COMMENT ON COLUMN cms_mwo.src_run_id IS '점검 NG 에서 만든 후속 작업지시의 출처 점검 실행';

-- 작업지시 등록 때 계획·출처 실행도 같은 회사인지 (V032 트리거는 그대로 두고 보조 트리거로)
CREATE FUNCTION hb_guard_cms_mwo_pm() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'UPDATE' AND NEW.src_run_id IS DISTINCT FROM OLD.src_run_id THEN
        RAISE EXCEPTION 'work order key columns are fixed' USING ERRCODE = '55000', CONSTRAINT = 'ck_cms_mwo_fixed';
    END IF;
    IF TG_OP = 'INSERT' AND NEW.pm_plan_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM cms_pm_plan p WHERE p.plan_id = NEW.pm_plan_id
            AND p.comp_cd = NEW.comp_cd AND p.target_type = NEW.target_type AND p.equip_id IS NOT DISTINCT FROM NEW.equip_id
            AND p.asset_id IS NOT DISTINCT FROM NEW.asset_id) THEN
        RAISE EXCEPTION 'plan % does not match this work order', NEW.pm_plan_id USING ERRCODE = '23503', CONSTRAINT = 'fk_cms_mwo_pm_plan_comp';
    END IF;
    IF TG_OP = 'INSERT' AND NEW.src_run_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM cms_chk_run x WHERE x.run_id = NEW.src_run_id
            AND x.comp_cd = NEW.comp_cd) THEN
        RAISE EXCEPTION 'check run % is not of company %', NEW.src_run_id, NEW.comp_cd USING ERRCODE = '23503', CONSTRAINT = 'fk_cms_mwo_src_run_comp';
    END IF;
    RETURN NEW;
END
$$;
CREATE TRIGGER tg_cms_mwo_pm_guard BEFORE INSERT OR UPDATE ON cms_mwo FOR EACH ROW EXECUTE FUNCTION hb_guard_cms_mwo_pm();

-- ---------------------------------------------------------------------
-- 4. 감사 · RLS · 권한 · 화면 · 보존
-- ---------------------------------------------------------------------
CREATE TRIGGER tg_audit AFTER INSERT OR UPDATE OR DELETE ON cms_chk_tmpl FOR EACH ROW EXECUTE FUNCTION hb_audit('tmpl_id', '', '');
CREATE TRIGGER tg_audit AFTER INSERT OR UPDATE OR DELETE ON cms_chk_tmpl_item FOR EACH ROW EXECUTE FUNCTION hb_audit('item_id', '', '');
CREATE TRIGGER tg_audit AFTER INSERT OR UPDATE OR DELETE ON cms_pm_plan FOR EACH ROW EXECUTE FUNCTION hb_audit('plan_id', '', '');
CREATE TRIGGER tg_audit AFTER INSERT OR UPDATE OR DELETE ON cms_chk_run FOR EACH ROW EXECUTE FUNCTION hb_audit('run_id', '', '');
CREATE TRIGGER tg_audit AFTER INSERT OR UPDATE OR DELETE ON cms_chk_result FOR EACH ROW EXECUTE FUNCTION hb_audit('result_id', '', '');

ALTER TABLE cms_chk_tmpl ENABLE ROW LEVEL SECURITY;
ALTER TABLE cms_chk_tmpl FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON cms_chk_tmpl USING (comp_cd = hb_current_comp()) WITH CHECK (comp_cd = hb_current_comp());
ALTER TABLE cms_chk_tmpl_item ENABLE ROW LEVEL SECURITY;
ALTER TABLE cms_chk_tmpl_item FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON cms_chk_tmpl_item USING (comp_cd = hb_current_comp()) WITH CHECK (comp_cd = hb_current_comp());
ALTER TABLE cms_pm_plan ENABLE ROW LEVEL SECURITY;
ALTER TABLE cms_pm_plan FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON cms_pm_plan USING (comp_cd = hb_current_comp()) WITH CHECK (comp_cd = hb_current_comp());
ALTER TABLE cms_chk_run ENABLE ROW LEVEL SECURITY;
ALTER TABLE cms_chk_run FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON cms_chk_run USING (comp_cd = hb_current_comp()) WITH CHECK (comp_cd = hb_current_comp());
ALTER TABLE cms_chk_result ENABLE ROW LEVEL SECURITY;
ALTER TABLE cms_chk_result FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON cms_chk_result USING (comp_cd = hb_current_comp()) WITH CHECK (comp_cd = hb_current_comp());
REVOKE DELETE, TRUNCATE ON cms_chk_tmpl, cms_pm_plan, cms_chk_run, cms_chk_result FROM ${app_role};
REVOKE TRUNCATE ON cms_chk_tmpl_item FROM ${app_role};

INSERT INTO sys_prog (prog_cd, prog_nm_key, view_path, module_cd, actions, sort_no) VALUES
    ('cms-chk-tmpl', 'prog.cmsChkTmpl', 'cms/chk-tmpl-mgt', 'CMS', ARRAY['SEARCH','SAVE']::varchar(20)[], 540),
    ('cms-pm-plan',  'prog.cmsPmPlan',  'cms/pm-plan-mgt',  'CMS', ARRAY['SEARCH','SAVE']::varchar(20)[], 550),
    ('cms-chk-run',  'prog.cmsChkRun',  'cms/chk-run',      'CMS', ARRAY['SEARCH','SAVE','DELETE']::varchar(20)[], 560);
INSERT INTO sys_retention_table (table_nm, class_cd) VALUES
    ('cms_chk_tmpl', 'MASTER'), ('cms_chk_tmpl_item', 'MASTER'), ('cms_pm_plan', 'MASTER'),
    ('cms_chk_run', 'MAINT_RECORD'), ('cms_chk_result', 'MAINT_RECORD');
