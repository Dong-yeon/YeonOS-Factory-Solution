-- =====================================================================
-- V041 CMS 1부 C1 — 고장 코드·설비 프로필·보전 작업지시 (docs/33 2장 C1·4장·4.1, 결정 D1·D2·D3·D13·D14·D15)
--
-- - cms_fail_code: 현상·원인·조치 회사 코드 + 전역 상위 분류 CMS_FAIL_CAT(회사 추가 불가) + 설비 클래스 한정(선택)
-- - cms_equip_profile: 설비 1:1 — 중요도 A/B/C·핵심 설비(IATF 8.5.1.5)·기본 보전 담당. P1 mst_equip 은 고치지 않는다
-- - cms_mwo: 보전 작업지시(BM 고장 수리 / CM 개선 / PM 예방 — PM 은 C2 계획이 만든다).
--            상태 REQ → OPEN → WIP → DONE → CLOSED (+ CANCELLED), 완료 뒤 내용 불변, 닫을 때만 필수(BM = 현상·원인·조치·작업 내용)
-- - cms_mwo_labor: 공수(작업자·날짜·분) — 작업지시가 끝나기 전까지만 추가·삭제
-- - 보존 클래스 MAINT_RECORD(KEEP) 신설
--
-- P2 6단계가 만드는 cms_state_cat·cms_state_reason·cms_equip_state_log·cms_equip_conn_log·cms_energy_reading 은 여기서 만들지 않는다.
-- 병행 개발(docs/33 6장): 이 번호는 main 병합 직전에 다시 매길 수 있다.
-- =====================================================================

INSERT INTO sys_retention_class (class_cd, basis, default_days, min_days, purge_allowed_yn, desc_key) VALUES
    ('MAINT_RECORD', 'KEEP', NULL, NULL, 'N', 'retention.MAINT_RECORD');   -- 보전 기록: 설비 폐기 뒤에도 이력 카드(IATF 8.5.1.5)

INSERT INTO sys_code_grp (grp_cd, grp_nm_key, comp_ext_yn) VALUES
    ('CMS_FAIL_CAT',   'code.grp.CMS_FAIL_CAT',   'N'),   -- 표준 상위는 회사가 늘리지 않는다(회사 간 비교·예지보전 라벨)
    ('CMS_FAIL_TYPE',  'code.grp.CMS_FAIL_TYPE',  'N'),
    ('CMS_MWO_TYPE',   'code.grp.CMS_MWO_TYPE',   'N'),
    ('CMS_MWO_STATUS', 'code.grp.CMS_MWO_STATUS', 'N'),
    ('CMS_CRIT',       'code.grp.CMS_CRIT',       'N');
INSERT INTO sys_code (grp_cd, code, code_nm_key, sort_no) VALUES
    ('CMS_FAIL_CAT', 'MECH',    'code.CMS_FAIL_CAT.MECH',    1),
    ('CMS_FAIL_CAT', 'ELEC',    'code.CMS_FAIL_CAT.ELEC',    2),
    ('CMS_FAIL_CAT', 'CTRL',    'code.CMS_FAIL_CAT.CTRL',    3),
    ('CMS_FAIL_CAT', 'FLUID',   'code.CMS_FAIL_CAT.FLUID',   4),
    ('CMS_FAIL_CAT', 'UTIL',    'code.CMS_FAIL_CAT.UTIL',    5),
    ('CMS_FAIL_CAT', 'TOOL',    'code.CMS_FAIL_CAT.TOOL',    6),
    ('CMS_FAIL_CAT', 'OPER',    'code.CMS_FAIL_CAT.OPER',    7),
    ('CMS_FAIL_CAT', 'EXT',     'code.CMS_FAIL_CAT.EXT',     8),
    ('CMS_FAIL_CAT', 'UNKNOWN', 'code.CMS_FAIL_CAT.UNKNOWN', 9),
    ('CMS_FAIL_TYPE', 'SYMPTOM', 'code.CMS_FAIL_TYPE.SYMPTOM', 1),
    ('CMS_FAIL_TYPE', 'CAUSE',   'code.CMS_FAIL_TYPE.CAUSE',   2),
    ('CMS_FAIL_TYPE', 'ACTION',  'code.CMS_FAIL_TYPE.ACTION',  3),
    ('CMS_MWO_TYPE', 'BM', 'code.CMS_MWO_TYPE.BM', 1),
    ('CMS_MWO_TYPE', 'CM', 'code.CMS_MWO_TYPE.CM', 2),
    ('CMS_MWO_TYPE', 'PM', 'code.CMS_MWO_TYPE.PM', 3),
    ('CMS_MWO_STATUS', 'REQ',       'code.CMS_MWO_STATUS.REQ',       1),
    ('CMS_MWO_STATUS', 'OPEN',      'code.CMS_MWO_STATUS.OPEN',      2),
    ('CMS_MWO_STATUS', 'WIP',       'code.CMS_MWO_STATUS.WIP',       3),
    ('CMS_MWO_STATUS', 'DONE',      'code.CMS_MWO_STATUS.DONE',      4),
    ('CMS_MWO_STATUS', 'CLOSED',    'code.CMS_MWO_STATUS.CLOSED',    5),
    ('CMS_MWO_STATUS', 'CANCELLED', 'code.CMS_MWO_STATUS.CANCELLED', 6),
    ('CMS_CRIT', 'A', 'code.CMS_CRIT.A', 1),
    ('CMS_CRIT', 'B', 'code.CMS_CRIT.B', 2),
    ('CMS_CRIT', 'C', 'code.CMS_CRIT.C', 3);

-- 사용자가 그 회사 계정인지 (FK 는 회사를 보지 않는다)
CREATE FUNCTION hb_user_of_comp(p_comp varchar, p_user bigint) RETURNS boolean
    LANGUAGE sql STABLE
AS $$
    SELECT EXISTS (SELECT 1 FROM sys_user u WHERE u.user_id = p_user AND u.comp_cd = p_comp)
$$;

-- ---------------------------------------------------------------------
-- 1. 고장 코드
-- ---------------------------------------------------------------------
CREATE TABLE cms_fail_code (
    fail_id         bigint        GENERATED ALWAYS AS IDENTITY,
    comp_cd         varchar(20)   NOT NULL,
    code_type       varchar(10)   NOT NULL,
    fail_cd         varchar(20)   NOT NULL,
    fail_nm         varchar(100)  NOT NULL,
    fail_cat        varchar(10),
    equip_class_id  bigint,
    sort_no         integer       NOT NULL DEFAULT 0,
    use_yn          char(1)       NOT NULL DEFAULT 'Y',
    remark          varchar(500),
    row_ver         integer       NOT NULL DEFAULT 0,
    created_at      timestamptz   NOT NULL DEFAULT now(),
    created_by      varchar(50)   NOT NULL,
    updated_at      timestamptz   NOT NULL DEFAULT now(),
    updated_by      varchar(50)   NOT NULL,
    CONSTRAINT pk_cms_fail_code PRIMARY KEY (fail_id),
    CONSTRAINT fk_cms_fail_code_comp FOREIGN KEY (comp_cd) REFERENCES sys_comp (comp_cd),
    CONSTRAINT fk_cms_fail_code_class FOREIGN KEY (equip_class_id) REFERENCES mst_equip_class (class_id),
    CONSTRAINT uq_cms_fail_code_cd UNIQUE (comp_cd, code_type, fail_cd),
    CONSTRAINT ck_cms_fail_code_type CHECK (code_type IN ('SYMPTOM', 'CAUSE', 'ACTION')),
    CONSTRAINT ck_cms_fail_code_cd CHECK (fail_cd ~ '^[A-Z0-9][A-Z0-9_-]{0,19}$'),
    CONSTRAINT ck_cms_fail_code_nm CHECK (length(btrim(fail_nm)) > 0),
    CONSTRAINT ck_cms_fail_code_cat CHECK (fail_cat IN ('MECH', 'ELEC', 'CTRL', 'FLUID', 'UTIL', 'TOOL', 'OPER', 'EXT', 'UNKNOWN')),
    -- 원인은 상위 분류 필수(회사 간 비교·예지보전 라벨의 키)
    CONSTRAINT ck_cms_fail_code_cause_cat CHECK (code_type <> 'CAUSE' OR fail_cat IS NOT NULL),
    CONSTRAINT ck_cms_fail_code_use_yn CHECK (use_yn IN ('Y', 'N'))
);
COMMENT ON TABLE cms_fail_code IS '고장 코드 (회사) — 현상·원인·조치. 상위 분류 = 시스템 코드 CMS_FAIL_CAT (docs/33 D1). 설비 클래스가 있으면 그 클래스 작업지시에서만';

CREATE FUNCTION hb_guard_cms_fail_code() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'UPDATE' AND (NEW.code_type <> OLD.code_type OR NEW.fail_cd <> OLD.fail_cd) THEN
        RAISE EXCEPTION 'fail code type and code are fixed' USING ERRCODE = '55000', CONSTRAINT = 'ck_cms_fail_code_fixed';
    END IF;
    IF NEW.equip_class_id IS NOT NULL AND (TG_OP = 'INSERT' OR NEW.equip_class_id IS DISTINCT FROM OLD.equip_class_id)
       AND NOT EXISTS (SELECT 1 FROM mst_equip_class c WHERE c.class_id = NEW.equip_class_id AND c.comp_cd = NEW.comp_cd
                          AND c.use_yn = 'Y') THEN
        RAISE EXCEPTION 'equipment class % is not an active class of company %', NEW.equip_class_id, NEW.comp_cd
            USING ERRCODE = '23503', CONSTRAINT = 'fk_cms_fail_code_class_comp';
    END IF;
    RETURN NEW;
END
$$;
CREATE TRIGGER tg_cms_fail_code_guard BEFORE INSERT OR UPDATE ON cms_fail_code FOR EACH ROW EXECUTE FUNCTION hb_guard_cms_fail_code();
CREATE TRIGGER tg_cms_fail_code_no_delete BEFORE DELETE ON cms_fail_code FOR EACH ROW EXECUTE FUNCTION hb_forbid_delete_master();

-- ---------------------------------------------------------------------
-- 2. 설비 프로필 (설비 1:1)
-- ---------------------------------------------------------------------
CREATE TABLE cms_equip_profile (
    equip_id       bigint        NOT NULL,
    comp_cd        varchar(20)   NOT NULL,
    criticality    char(1)       NOT NULL DEFAULT 'C',
    key_equip_yn   char(1)       NOT NULL DEFAULT 'N',
    owner_user_id  bigint,
    remark         varchar(500),
    row_ver        integer       NOT NULL DEFAULT 0,
    created_at     timestamptz   NOT NULL DEFAULT now(),
    created_by     varchar(50)   NOT NULL,
    updated_at     timestamptz   NOT NULL DEFAULT now(),
    updated_by     varchar(50)   NOT NULL,
    CONSTRAINT pk_cms_equip_profile PRIMARY KEY (equip_id),
    CONSTRAINT fk_cms_equip_profile_equip FOREIGN KEY (equip_id) REFERENCES mst_equip (equip_id),
    CONSTRAINT fk_cms_equip_profile_owner FOREIGN KEY (owner_user_id) REFERENCES sys_user (user_id),
    CONSTRAINT ck_cms_equip_profile_crit CHECK (criticality IN ('A', 'B', 'C')),
    CONSTRAINT ck_cms_equip_profile_key CHECK (key_equip_yn IN ('Y', 'N'))
);
CREATE INDEX ix_cms_equip_profile_comp ON cms_equip_profile (comp_cd, criticality);
COMMENT ON TABLE cms_equip_profile IS '설비 보전 프로필 (설비 1:1, docs/33 D15). 행이 없으면 중요도 C·핵심 아님·담당 없음';
COMMENT ON COLUMN cms_equip_profile.key_equip_yn IS 'IATF 16949 8.5.1.5 핵심 공정 설비 — 예비품·보전 목표 관리 대상';

CREATE FUNCTION hb_guard_cms_equip_profile() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'UPDATE' AND NEW.equip_id <> OLD.equip_id THEN
        RAISE EXCEPTION 'profile equipment is fixed' USING ERRCODE = '55000', CONSTRAINT = 'ck_cms_equip_profile_fixed';
    END IF;
    IF TG_OP = 'INSERT' AND NOT EXISTS (SELECT 1 FROM mst_equip e WHERE e.equip_id = NEW.equip_id AND e.comp_cd = NEW.comp_cd) THEN
        RAISE EXCEPTION 'equipment % is not of company %', NEW.equip_id, NEW.comp_cd
            USING ERRCODE = '23503', CONSTRAINT = 'fk_cms_equip_profile_equip_comp';
    END IF;
    IF NEW.owner_user_id IS NOT NULL AND (TG_OP = 'INSERT' OR NEW.owner_user_id IS DISTINCT FROM OLD.owner_user_id)
       AND NOT EXISTS (SELECT 1 FROM sys_user u WHERE u.user_id = NEW.owner_user_id AND u.comp_cd = NEW.comp_cd AND u.use_yn = 'Y') THEN
        RAISE EXCEPTION 'user % is not an active user of company %', NEW.owner_user_id, NEW.comp_cd
            USING ERRCODE = '23503', CONSTRAINT = 'fk_cms_equip_profile_owner_comp';
    END IF;
    RETURN NEW;
END
$$;
CREATE TRIGGER tg_cms_equip_profile_guard BEFORE INSERT OR UPDATE ON cms_equip_profile
    FOR EACH ROW EXECUTE FUNCTION hb_guard_cms_equip_profile();
CREATE TRIGGER tg_cms_equip_profile_no_delete BEFORE DELETE ON cms_equip_profile
    FOR EACH ROW EXECUTE FUNCTION hb_forbid_delete_master();

-- ---------------------------------------------------------------------
-- 3. 보전 작업지시
-- ---------------------------------------------------------------------
CREATE TABLE cms_mwo (
    mwo_id         bigint         GENERATED ALWAYS AS IDENTITY,
    comp_cd        varchar(20)    NOT NULL,
    mwo_no         varchar(30)    NOT NULL,
    mwo_type       varchar(2)     NOT NULL,
    plant_cd       varchar(20),
    target_type    varchar(5)     NOT NULL,
    equip_id       bigint,
    asset_id       bigint,
    title          varchar(200)   NOT NULL,
    priority       smallint       NOT NULL DEFAULT 2,
    status         varchar(10)    NOT NULL,
    reported_at    timestamptz    NOT NULL,
    requested_by   bigint,
    symptom_id     bigint,
    symptom_text   varchar(1000),
    assignee_id    bigint,
    due_date       date,
    started_at     timestamptz,
    finished_at    timestamptz,
    down_yn        char(1)        NOT NULL DEFAULT 'N',
    down_start_at  timestamptz,
    down_end_at    timestamptz,
    cause_id       bigint,
    action_id      bigint,
    work_text      varchar(2000),
    cancel_reason  varchar(500),
    closed_at      timestamptz,
    closed_by      bigint,
    pm_plan_id     bigint,
    due_ref_date   date,
    parent_mwo_id  bigint,
    row_ver        integer        NOT NULL DEFAULT 0,
    created_at     timestamptz    NOT NULL DEFAULT now(),
    created_by     varchar(50)    NOT NULL,
    updated_at     timestamptz    NOT NULL DEFAULT now(),
    updated_by     varchar(50)    NOT NULL,
    CONSTRAINT pk_cms_mwo PRIMARY KEY (mwo_id),
    CONSTRAINT fk_cms_mwo_comp FOREIGN KEY (comp_cd) REFERENCES sys_comp (comp_cd),
    CONSTRAINT fk_cms_mwo_plant FOREIGN KEY (comp_cd, plant_cd) REFERENCES sys_plant (comp_cd, plant_cd),
    CONSTRAINT fk_cms_mwo_equip FOREIGN KEY (equip_id) REFERENCES mst_equip (equip_id),
    CONSTRAINT fk_cms_mwo_asset FOREIGN KEY (asset_id) REFERENCES mst_phys_asset (asset_id),
    CONSTRAINT fk_cms_mwo_requested_by FOREIGN KEY (requested_by) REFERENCES sys_user (user_id),
    CONSTRAINT fk_cms_mwo_assignee FOREIGN KEY (assignee_id) REFERENCES sys_user (user_id),
    CONSTRAINT fk_cms_mwo_closed_by FOREIGN KEY (closed_by) REFERENCES sys_user (user_id),
    CONSTRAINT fk_cms_mwo_symptom FOREIGN KEY (symptom_id) REFERENCES cms_fail_code (fail_id),
    CONSTRAINT fk_cms_mwo_cause FOREIGN KEY (cause_id) REFERENCES cms_fail_code (fail_id),
    CONSTRAINT fk_cms_mwo_action FOREIGN KEY (action_id) REFERENCES cms_fail_code (fail_id),
    CONSTRAINT fk_cms_mwo_parent FOREIGN KEY (parent_mwo_id) REFERENCES cms_mwo (mwo_id),
    CONSTRAINT uq_cms_mwo_no UNIQUE (comp_cd, mwo_no),
    CONSTRAINT ck_cms_mwo_type CHECK (mwo_type IN ('BM', 'CM', 'PM')),
    CONSTRAINT ck_cms_mwo_status CHECK (status IN ('REQ', 'OPEN', 'WIP', 'DONE', 'CLOSED', 'CANCELLED')),
    CONSTRAINT ck_cms_mwo_target CHECK ((target_type = 'EQUIP' AND equip_id IS NOT NULL AND asset_id IS NULL)
                                        OR (target_type = 'ASSET' AND asset_id IS NOT NULL AND equip_id IS NULL)),
    CONSTRAINT ck_cms_mwo_title CHECK (length(btrim(title)) > 0),
    CONSTRAINT ck_cms_mwo_priority CHECK (priority BETWEEN 1 AND 3),
    -- 신고(REQ)는 고장(BM)만 — 개선·예방은 바로 OPEN
    CONSTRAINT ck_cms_mwo_req CHECK (status <> 'REQ' OR mwo_type = 'BM'),
    -- PM 은 계획이 만든다(C2): 계획·예정일과 함께만
    CONSTRAINT ck_cms_mwo_pm CHECK ((mwo_type = 'PM') = (pm_plan_id IS NOT NULL) AND (pm_plan_id IS NULL) = (due_ref_date IS NULL)),
    -- 고장 신고는 현상(코드 또는 글) 하나는 있어야 한다
    CONSTRAINT ck_cms_mwo_symptom_any CHECK (mwo_type <> 'BM' OR symptom_id IS NOT NULL OR length(btrim(symptom_text)) > 0),
    CONSTRAINT ck_cms_mwo_wip CHECK (status NOT IN ('WIP', 'DONE', 'CLOSED') OR started_at IS NOT NULL),
    CONSTRAINT ck_cms_mwo_order CHECK ((started_at IS NULL OR started_at >= reported_at)
                                       AND (finished_at IS NULL OR (started_at IS NOT NULL AND finished_at >= started_at))),
    CONSTRAINT ck_cms_mwo_down CHECK ((down_yn = 'N' AND down_start_at IS NULL AND down_end_at IS NULL)
                                      OR (down_yn = 'Y' AND down_start_at IS NOT NULL
                                          AND (down_end_at IS NULL OR down_end_at >= down_start_at))),
    -- 닫을 때 필수(docs/11 Top #13): 완료 시각, 정지 구간 끝, BM = 현상·원인·조치 코드 + 작업 내용, CM = 작업 내용
    CONSTRAINT ck_cms_mwo_done CHECK (status NOT IN ('DONE', 'CLOSED') OR (
        finished_at IS NOT NULL
        AND (down_yn = 'N' OR down_end_at IS NOT NULL)
        AND (mwo_type = 'PM' OR length(btrim(work_text)) > 0)
        AND (mwo_type <> 'BM' OR (symptom_id IS NOT NULL AND cause_id IS NOT NULL AND action_id IS NOT NULL)))),
    CONSTRAINT ck_cms_mwo_cancel CHECK ((status = 'CANCELLED') = (cancel_reason IS NOT NULL AND length(btrim(cancel_reason)) > 0)),
    CONSTRAINT ck_cms_mwo_closed CHECK ((status = 'CLOSED') = (closed_at IS NOT NULL)),
    CONSTRAINT ck_cms_mwo_down_yn CHECK (down_yn IN ('Y', 'N'))
);
CREATE INDEX ix_cms_mwo_status ON cms_mwo (comp_cd, status, reported_at);
CREATE INDEX ix_cms_mwo_equip ON cms_mwo (comp_cd, equip_id, reported_at) WHERE equip_id IS NOT NULL;
CREATE INDEX ix_cms_mwo_asset ON cms_mwo (comp_cd, asset_id, reported_at) WHERE asset_id IS NOT NULL;
CREATE INDEX ix_cms_mwo_reported ON cms_mwo (comp_cd, reported_at, mwo_id);
CREATE UNIQUE INDEX uq_cms_mwo_pm_due ON cms_mwo (comp_cd, pm_plan_id, due_ref_date) WHERE pm_plan_id IS NOT NULL;
COMMENT ON TABLE cms_mwo IS '보전 작업지시 (docs/33 C1·4.1) — BM 고장 수리 / CM 개선 / PM 예방. 원장 아님: 상태 전이 트리거 + 완료 뒤 불변';
COMMENT ON COLUMN cms_mwo.down_start_at IS '설비 정지 구간(수기). 2부에서 상태 원장 UDT 구간과 대조만 — 이 칸으로 MTBF/MTTR 을 내지 않는다(D10)';
COMMENT ON COLUMN cms_mwo.plant_cd IS '등록 때 대상의 공장(설비 = 설비의 공장). 시각 입력·표시의 시간대 기준';

CREATE FUNCTION hb_guard_cms_mwo() RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    v_ok boolean;
    v_fixed text[] := ARRAY['status', 'closed_at', 'closed_by', 'row_ver', 'updated_at', 'updated_by'];
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'work order cannot be deleted (cancel it)' USING ERRCODE = '55000', CONSTRAINT = 'ck_cms_mwo_no_delete';
    END IF;

    IF TG_OP = 'INSERT' THEN
        IF NEW.status NOT IN ('REQ', 'OPEN') THEN
            RAISE EXCEPTION 'new work order must start as REQ or OPEN' USING ERRCODE = '55000', CONSTRAINT = 'ck_cms_mwo_transition';
        END IF;
        IF NEW.equip_id IS NOT NULL THEN
            IF NOT EXISTS (SELECT 1 FROM mst_equip e WHERE e.equip_id = NEW.equip_id AND e.comp_cd = NEW.comp_cd AND e.use_yn = 'Y') THEN
                RAISE EXCEPTION 'equipment % is not an active equipment of company %', NEW.equip_id, NEW.comp_cd
                    USING ERRCODE = '23503', CONSTRAINT = 'fk_cms_mwo_equip_comp';
            END IF;
            IF NEW.plant_cd IS DISTINCT FROM (SELECT e.plant_cd FROM mst_equip e WHERE e.equip_id = NEW.equip_id) THEN
                RAISE EXCEPTION 'plant must be the plant of the equipment' USING ERRCODE = '23514', CONSTRAINT = 'ck_cms_mwo_plant';
            END IF;
        END IF;
        IF NEW.asset_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM mst_phys_asset a WHERE a.asset_id = NEW.asset_id
                AND a.comp_cd = NEW.comp_cd AND a.use_yn = 'Y' AND a.asset_type IN ('MOLD', 'JIG')) THEN
            -- 게이지는 대상이 아니다 — 교정은 QMS(docs/29 Q2a)
            RAISE EXCEPTION 'asset % is not an active mold or jig of company %', NEW.asset_id, NEW.comp_cd
                USING ERRCODE = '23503', CONSTRAINT = 'fk_cms_mwo_asset_comp';
        END IF;
        IF NEW.parent_mwo_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM cms_mwo m WHERE m.mwo_id = NEW.parent_mwo_id
                AND m.comp_cd = NEW.comp_cd) THEN
            RAISE EXCEPTION 'parent work order % is not of company %', NEW.parent_mwo_id, NEW.comp_cd
                USING ERRCODE = '23503', CONSTRAINT = 'fk_cms_mwo_parent_comp';
        END IF;
    ELSE
        -- 번호·유형·대상·공장·계획·상위는 만든 뒤 바꾸지 않는다(잘못 만들었으면 취소하고 새로)
        IF NEW.mwo_no <> OLD.mwo_no OR NEW.mwo_type <> OLD.mwo_type OR NEW.target_type <> OLD.target_type
           OR NEW.equip_id IS DISTINCT FROM OLD.equip_id OR NEW.asset_id IS DISTINCT FROM OLD.asset_id
           OR NEW.plant_cd IS DISTINCT FROM OLD.plant_cd OR NEW.pm_plan_id IS DISTINCT FROM OLD.pm_plan_id
           OR NEW.due_ref_date IS DISTINCT FROM OLD.due_ref_date OR NEW.parent_mwo_id IS DISTINCT FROM OLD.parent_mwo_id
           OR NEW.comp_cd <> OLD.comp_cd THEN
            RAISE EXCEPTION 'work order key columns are fixed' USING ERRCODE = '55000', CONSTRAINT = 'ck_cms_mwo_fixed';
        END IF;
        -- 상태 전이 표 (docs/33 4.1)
        v_ok := NEW.status = OLD.status AND OLD.status IN ('REQ', 'OPEN', 'WIP')
             OR (OLD.status, NEW.status) IN (('REQ', 'OPEN'), ('REQ', 'CANCELLED'), ('OPEN', 'WIP'), ('OPEN', 'CANCELLED'),
                                             ('WIP', 'DONE'), ('DONE', 'CLOSED'));
        IF NOT v_ok THEN
            RAISE EXCEPTION 'work order status % -> % is not allowed', OLD.status, NEW.status
                USING ERRCODE = '55000', CONSTRAINT = 'ck_cms_mwo_transition';
        END IF;
        -- 완료 뒤 불변: 확인(DONE → CLOSED)은 상태·확인 칸만 바꾼다
        IF OLD.status IN ('DONE', 'CLOSED', 'CANCELLED')
           AND (to_jsonb(NEW) - v_fixed) IS DISTINCT FROM (to_jsonb(OLD) - v_fixed) THEN
            RAISE EXCEPTION 'finished work order is read-only' USING ERRCODE = '55000', CONSTRAINT = 'ck_cms_mwo_done_fixed';
        END IF;
    END IF;

    -- 고장 코드: 같은 회사·칸에 맞는 유형, 새로 넣거나 바꿀 때는 사용 중만
    IF NEW.symptom_id IS NOT NULL AND (TG_OP = 'INSERT' OR NEW.symptom_id IS DISTINCT FROM OLD.symptom_id)
       AND NOT EXISTS (SELECT 1 FROM cms_fail_code f WHERE f.fail_id = NEW.symptom_id AND f.comp_cd = NEW.comp_cd
                          AND f.code_type = 'SYMPTOM' AND f.use_yn = 'Y') THEN
        RAISE EXCEPTION 'symptom code % is not an active symptom of company %', NEW.symptom_id, NEW.comp_cd
            USING ERRCODE = '23503', CONSTRAINT = 'fk_cms_mwo_symptom_comp';
    END IF;
    IF NEW.cause_id IS NOT NULL AND (TG_OP = 'INSERT' OR NEW.cause_id IS DISTINCT FROM OLD.cause_id)
       AND NOT EXISTS (SELECT 1 FROM cms_fail_code f WHERE f.fail_id = NEW.cause_id AND f.comp_cd = NEW.comp_cd
                          AND f.code_type = 'CAUSE' AND f.use_yn = 'Y') THEN
        RAISE EXCEPTION 'cause code % is not an active cause of company %', NEW.cause_id, NEW.comp_cd
            USING ERRCODE = '23503', CONSTRAINT = 'fk_cms_mwo_cause_comp';
    END IF;
    IF NEW.action_id IS NOT NULL AND (TG_OP = 'INSERT' OR NEW.action_id IS DISTINCT FROM OLD.action_id)
       AND NOT EXISTS (SELECT 1 FROM cms_fail_code f WHERE f.fail_id = NEW.action_id AND f.comp_cd = NEW.comp_cd
                          AND f.code_type = 'ACTION' AND f.use_yn = 'Y') THEN
        RAISE EXCEPTION 'action code % is not an active action of company %', NEW.action_id, NEW.comp_cd
            USING ERRCODE = '23503', CONSTRAINT = 'fk_cms_mwo_action_comp';
    END IF;
    -- 사용자: 같은 회사, 담당은 새로 지정할 때 사용 중만
    IF NEW.requested_by IS NOT NULL AND (TG_OP = 'INSERT' OR NEW.requested_by IS DISTINCT FROM OLD.requested_by)
       AND NOT hb_user_of_comp(NEW.comp_cd, NEW.requested_by) THEN
        RAISE EXCEPTION 'user % is not of company %', NEW.requested_by, NEW.comp_cd
            USING ERRCODE = '23503', CONSTRAINT = 'fk_cms_mwo_user_comp';
    END IF;
    IF NEW.closed_by IS NOT NULL AND (TG_OP = 'INSERT' OR NEW.closed_by IS DISTINCT FROM OLD.closed_by)
       AND NOT hb_user_of_comp(NEW.comp_cd, NEW.closed_by) THEN
        RAISE EXCEPTION 'user % is not of company %', NEW.closed_by, NEW.comp_cd
            USING ERRCODE = '23503', CONSTRAINT = 'fk_cms_mwo_user_comp';
    END IF;
    IF NEW.assignee_id IS NOT NULL AND (TG_OP = 'INSERT' OR NEW.assignee_id IS DISTINCT FROM OLD.assignee_id)
       AND NOT EXISTS (SELECT 1 FROM sys_user u WHERE u.user_id = NEW.assignee_id AND u.comp_cd = NEW.comp_cd AND u.use_yn = 'Y') THEN
        RAISE EXCEPTION 'user % is not an active user of company %', NEW.assignee_id, NEW.comp_cd
            USING ERRCODE = '23503', CONSTRAINT = 'fk_cms_mwo_assignee_comp';
    END IF;
    RETURN NEW;
END
$$;
CREATE TRIGGER tg_cms_mwo_guard BEFORE INSERT OR UPDATE OR DELETE ON cms_mwo FOR EACH ROW EXECUTE FUNCTION hb_guard_cms_mwo();

-- 공수 (작업지시 상세)
CREATE TABLE cms_mwo_labor (
    labor_id    bigint        GENERATED ALWAYS AS IDENTITY,
    comp_cd     varchar(20)   NOT NULL,
    mwo_id      bigint        NOT NULL,
    user_id     bigint        NOT NULL,
    work_date   date          NOT NULL,
    minutes     integer       NOT NULL,
    remark      varchar(500),
    row_ver     integer       NOT NULL DEFAULT 0,
    created_at  timestamptz   NOT NULL DEFAULT now(),
    created_by  varchar(50)   NOT NULL,
    updated_at  timestamptz   NOT NULL DEFAULT now(),
    updated_by  varchar(50)   NOT NULL,
    CONSTRAINT pk_cms_mwo_labor PRIMARY KEY (labor_id),
    CONSTRAINT fk_cms_mwo_labor_mwo FOREIGN KEY (mwo_id) REFERENCES cms_mwo (mwo_id),
    CONSTRAINT fk_cms_mwo_labor_user FOREIGN KEY (user_id) REFERENCES sys_user (user_id),
    CONSTRAINT ck_cms_mwo_labor_minutes CHECK (minutes BETWEEN 1 AND 1440),
    CONSTRAINT ck_cms_mwo_labor_date CHECK (work_date >= DATE '2000-01-01' AND work_date <= DATE '2999-12-31')
);
CREATE INDEX ix_cms_mwo_labor ON cms_mwo_labor (comp_cd, mwo_id);
COMMENT ON TABLE cms_mwo_labor IS '보전 공수 — 작업지시가 OPEN·WIP 일 때만 추가·삭제(수정 없음: 지우고 다시)';

CREATE FUNCTION hb_guard_cms_mwo_labor() RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    r record;
BEGIN
    IF TG_OP = 'UPDATE' THEN
        RAISE EXCEPTION 'labor rows are not updated (delete and add)' USING ERRCODE = '55000', CONSTRAINT = 'ck_cms_mwo_labor_no_update';
    END IF;
    r := CASE WHEN TG_OP = 'DELETE' THEN OLD ELSE NEW END;
    -- 작업지시 행을 잠가 상태 전이(완료)와 줄 세운다 — 완료와 동시에 들어온 공수가 완료 뒤에 남지 않게
    PERFORM 1 FROM cms_mwo m WHERE m.mwo_id = r.mwo_id AND m.comp_cd = r.comp_cd AND m.status IN ('OPEN', 'WIP') FOR UPDATE;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'labor can change only while the work order is OPEN or WIP' USING ERRCODE = '55000',
            CONSTRAINT = 'ck_cms_mwo_labor_status';
    END IF;
    IF TG_OP = 'INSERT' AND NOT EXISTS (SELECT 1 FROM sys_user u WHERE u.user_id = NEW.user_id AND u.comp_cd = NEW.comp_cd
                                           AND u.use_yn = 'Y') THEN
        RAISE EXCEPTION 'user % is not an active user of company %', NEW.user_id, NEW.comp_cd
            USING ERRCODE = '23503', CONSTRAINT = 'fk_cms_mwo_labor_user_comp';
    END IF;
    RETURN r;
END
$$;
CREATE TRIGGER tg_cms_mwo_labor_guard BEFORE INSERT OR UPDATE OR DELETE ON cms_mwo_labor
    FOR EACH ROW EXECUTE FUNCTION hb_guard_cms_mwo_labor();

-- ---------------------------------------------------------------------
-- 4. 감사 · RLS · 권한 · 화면 · 보존
-- ---------------------------------------------------------------------
CREATE TRIGGER tg_audit AFTER INSERT OR UPDATE OR DELETE ON cms_fail_code FOR EACH ROW EXECUTE FUNCTION hb_audit('fail_id', '', '');
CREATE TRIGGER tg_audit AFTER INSERT OR UPDATE OR DELETE ON cms_equip_profile FOR EACH ROW EXECUTE FUNCTION hb_audit('equip_id', '', '');
CREATE TRIGGER tg_audit AFTER INSERT OR UPDATE OR DELETE ON cms_mwo FOR EACH ROW EXECUTE FUNCTION hb_audit('mwo_id', '', '');
CREATE TRIGGER tg_audit AFTER INSERT OR UPDATE OR DELETE ON cms_mwo_labor FOR EACH ROW EXECUTE FUNCTION hb_audit('labor_id', '', '');

ALTER TABLE cms_fail_code ENABLE ROW LEVEL SECURITY;
ALTER TABLE cms_fail_code FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON cms_fail_code USING (comp_cd = hb_current_comp()) WITH CHECK (comp_cd = hb_current_comp());
ALTER TABLE cms_equip_profile ENABLE ROW LEVEL SECURITY;
ALTER TABLE cms_equip_profile FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON cms_equip_profile USING (comp_cd = hb_current_comp()) WITH CHECK (comp_cd = hb_current_comp());
ALTER TABLE cms_mwo ENABLE ROW LEVEL SECURITY;
ALTER TABLE cms_mwo FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON cms_mwo USING (comp_cd = hb_current_comp()) WITH CHECK (comp_cd = hb_current_comp());
ALTER TABLE cms_mwo_labor ENABLE ROW LEVEL SECURITY;
ALTER TABLE cms_mwo_labor FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON cms_mwo_labor USING (comp_cd = hb_current_comp()) WITH CHECK (comp_cd = hb_current_comp());
REVOKE DELETE, TRUNCATE ON cms_fail_code, cms_equip_profile, cms_mwo FROM ${app_role};
REVOKE UPDATE, TRUNCATE ON cms_mwo_labor FROM ${app_role};

INSERT INTO sys_prog (prog_cd, prog_nm_key, view_path, module_cd, actions, sort_no) VALUES
    ('cms-fail-code',     'prog.cmsFailCode',     'cms/fail-code-mgt',     'CMS', ARRAY['SEARCH','SAVE']::varchar(20)[], 500),
    ('cms-equip-profile', 'prog.cmsEquipProfile', 'cms/equip-profile-mgt', 'CMS', ARRAY['SEARCH','SAVE']::varchar(20)[], 510),
    ('cms-breakdown',     'prog.cmsBreakdown',    'cms/breakdown',         'CMS', ARRAY['SEARCH','SAVE']::varchar(20)[], 520),
    ('cms-mwo',           'prog.cmsMwo',          'cms/mwo-mgt',           'CMS', ARRAY['SEARCH','SAVE','APPROVE','DELETE']::varchar(20)[], 530);
INSERT INTO sys_retention_table (table_nm, class_cd) VALUES
    ('cms_fail_code', 'MASTER'), ('cms_equip_profile', 'MASTER'), ('cms_mwo', 'MAINT_RECORD'), ('cms_mwo_labor', 'MAINT_RECORD');
