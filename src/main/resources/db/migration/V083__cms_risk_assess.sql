-- =====================================================================
-- CMS 1부 C6 — 위험성평가 대장 (docs/33 4.7, 결정 D12·D13 · docs/27 M10 산업안전보건법)
--
-- - cms_risk_assess: 평가 머리(채번 CMS_RA, 유형·방법·행렬 스냅샷, 상태 DRAFT → IN_APPROVAL → RELEASED → OBSOLETE, 결재)
-- - cms_risk_item: 유해위험요인(작업·분류·현재 조치·가능성×중대성 → 위험성 수준·개선 대책·담당·기한·완료·개선 후 위험성)
-- - cms_risk_member: 참여·주지 기록(근로자 참여 없이는 확정 불가)
-- - 확정본 불변: RELEASED 뒤에는 평가 내용이 바뀌지 않고 **개선 진행 칸(완료일·개선 후 위험성)과 주지일만** 채운다(D17)
-- - 정책 CMS_RA_MATRIX(3X3/5X5, 평가를 만들 때 스냅샷)·CMS_RA_APPROVAL(Y = 결재, N = APPROVE 권한으로 바로 확정)
-- - 보존 클래스 SAFETY_RECORD(발생 기준, 최소 3년·기본 5년, 자동 삭제 안 함)
-- 임시 번호(docs/29 2.2) — 병합 때 이름만 바뀐다.
-- =====================================================================

INSERT INTO sys_retention_class (class_cd, basis, default_days, min_days, purge_allowed_yn, desc_key) VALUES
    ('SAFETY_RECORD', 'OCCURRED_AT', 1825, 1095, 'N', 'retention.SAFETY_RECORD');   -- 산안법 보존 3년, 중처법 대응 5년 [확인 필요]

INSERT INTO sys_policy_def (policy_key, value_type, default_value, allowed_values, min_value, max_value, desc_key) VALUES
    ('CMS_RA_MATRIX',   'ENUM', '3X3', ARRAY['3X3','5X5'], NULL, NULL, 'policy.CMS_RA_MATRIX'),
    ('CMS_RA_APPROVAL', 'BOOL', 'Y',   NULL,               NULL, NULL, 'policy.CMS_RA_APPROVAL');

INSERT INTO sys_code_grp (grp_cd, grp_nm_key, comp_ext_yn) VALUES
    ('CMS_RA_TYPE',    'code.grp.CMS_RA_TYPE',    'N'),
    ('CMS_RA_METHOD',  'code.grp.CMS_RA_METHOD',  'N'),
    ('CMS_RA_STATUS',  'code.grp.CMS_RA_STATUS',  'N'),
    ('CMS_RISK_LEVEL', 'code.grp.CMS_RISK_LEVEL', 'N'),
    ('CMS_HAZARD_CAT', 'code.grp.CMS_HAZARD_CAT', 'N'),
    ('CMS_RA_ROLE',    'code.grp.CMS_RA_ROLE',    'N');
INSERT INTO sys_code (grp_cd, code, code_nm_key, sort_no) VALUES
    ('CMS_RA_TYPE', 'INITIAL',    'code.CMS_RA_TYPE.INITIAL',    1),
    ('CMS_RA_TYPE', 'REGULAR',    'code.CMS_RA_TYPE.REGULAR',    2),
    ('CMS_RA_TYPE', 'ADHOC',      'code.CMS_RA_TYPE.ADHOC',      3),
    ('CMS_RA_TYPE', 'CONTINUOUS', 'code.CMS_RA_TYPE.CONTINUOUS', 4),
    ('CMS_RA_METHOD', 'FREQ_SEV',   'code.CMS_RA_METHOD.FREQ_SEV',   1),
    ('CMS_RA_METHOD', 'THREE_STEP', 'code.CMS_RA_METHOD.THREE_STEP', 2),
    ('CMS_RA_STATUS', 'DRAFT',       'code.CMS_RA_STATUS.DRAFT',       1),
    ('CMS_RA_STATUS', 'IN_APPROVAL', 'code.CMS_RA_STATUS.IN_APPROVAL', 2),
    ('CMS_RA_STATUS', 'RELEASED',    'code.CMS_RA_STATUS.RELEASED',    3),
    ('CMS_RA_STATUS', 'OBSOLETE',    'code.CMS_RA_STATUS.OBSOLETE',    4),
    ('CMS_RISK_LEVEL', 'LOW',    'code.CMS_RISK_LEVEL.LOW',    1),
    ('CMS_RISK_LEVEL', 'MEDIUM', 'code.CMS_RISK_LEVEL.MEDIUM', 2),
    ('CMS_RISK_LEVEL', 'HIGH',   'code.CMS_RISK_LEVEL.HIGH',   3),
    ('CMS_HAZARD_CAT', 'MECH',  'code.CMS_HAZARD_CAT.MECH',  1),
    ('CMS_HAZARD_CAT', 'ELEC',  'code.CMS_HAZARD_CAT.ELEC',  2),
    ('CMS_HAZARD_CAT', 'CHEM',  'code.CMS_HAZARD_CAT.CHEM',  3),
    ('CMS_HAZARD_CAT', 'BIO',   'code.CMS_HAZARD_CAT.BIO',   4),
    ('CMS_HAZARD_CAT', 'ERGO',  'code.CMS_HAZARD_CAT.ERGO',  5),
    ('CMS_HAZARD_CAT', 'ENV',   'code.CMS_HAZARD_CAT.ENV',   6),
    ('CMS_HAZARD_CAT', 'OTHER', 'code.CMS_HAZARD_CAT.OTHER', 7),
    ('CMS_RA_ROLE', 'MANAGER',    'code.CMS_RA_ROLE.MANAGER',    1),
    ('CMS_RA_ROLE', 'SUPERVISOR', 'code.CMS_RA_ROLE.SUPERVISOR', 2),
    ('CMS_RA_ROLE', 'SAFETY',     'code.CMS_RA_ROLE.SAFETY',     3),
    ('CMS_RA_ROLE', 'WORKER_REP', 'code.CMS_RA_ROLE.WORKER_REP', 4),
    ('CMS_RA_ROLE', 'WORKER',     'code.CMS_RA_ROLE.WORKER',     5),
    ('CMS_RA_ROLE', 'OTHER',      'code.CMS_RA_ROLE.OTHER',      6);

-- ---------------------------------------------------------------------
-- 1. 평가 머리
-- ---------------------------------------------------------------------
CREATE TABLE cms_risk_assess (
    ra_id         bigint        GENERATED ALWAYS AS IDENTITY,
    comp_cd       varchar(20)   NOT NULL,
    ra_no         varchar(30)   NOT NULL,
    plant_cd      varchar(20),
    ra_type       varchar(10)   NOT NULL,
    method        varchar(10)   NOT NULL,
    matrix_cd     varchar(3)    NOT NULL,
    title         varchar(200)  NOT NULL,
    scope_text    varchar(500),
    assess_date   date          NOT NULL,
    status        varchar(11)   NOT NULL DEFAULT 'DRAFT',
    prev_ra_id    bigint,
    apr_id        bigint,
    released_at   timestamptz,
    released_by   varchar(50),
    remark        varchar(500),
    row_ver       integer       NOT NULL DEFAULT 0,
    created_at    timestamptz   NOT NULL DEFAULT now(),
    created_by    varchar(50)   NOT NULL,
    updated_at    timestamptz   NOT NULL DEFAULT now(),
    updated_by    varchar(50)   NOT NULL,
    CONSTRAINT pk_cms_risk_assess PRIMARY KEY (ra_id),
    CONSTRAINT fk_cms_risk_assess_comp FOREIGN KEY (comp_cd) REFERENCES sys_comp (comp_cd),
    CONSTRAINT fk_cms_risk_assess_prev FOREIGN KEY (prev_ra_id) REFERENCES cms_risk_assess (ra_id),
    CONSTRAINT uq_cms_risk_assess_no UNIQUE (comp_cd, ra_no),
    CONSTRAINT ck_cms_risk_assess_type CHECK (ra_type IN ('INITIAL', 'REGULAR', 'ADHOC', 'CONTINUOUS')),
    CONSTRAINT ck_cms_risk_assess_method CHECK (method IN ('FREQ_SEV', 'THREE_STEP')),
    CONSTRAINT ck_cms_risk_assess_matrix CHECK (matrix_cd IN ('3X3', '5X5')),
    CONSTRAINT ck_cms_risk_assess_title CHECK (length(btrim(title)) > 0),
    CONSTRAINT ck_cms_risk_assess_status CHECK (status IN ('DRAFT', 'IN_APPROVAL', 'RELEASED', 'OBSOLETE')),
    -- 확정본은 확정 시각이 있다(폐기는 확정 전후 모두 가능)
    CONSTRAINT ck_cms_risk_assess_released CHECK (status <> 'RELEASED' OR (released_at IS NOT NULL AND released_by IS NOT NULL)),
    CONSTRAINT ck_cms_risk_assess_date CHECK (assess_date BETWEEN DATE '2000-01-01' AND DATE '2999-12-31')
);
CREATE INDEX ix_cms_risk_assess_date ON cms_risk_assess (comp_cd, assess_date);
COMMENT ON TABLE cms_risk_assess IS '위험성평가 (docs/33 C6, D12) — 확정(RELEASED) 뒤 내용 불변, 개선 진행·주지만 채운다(D17)';
COMMENT ON COLUMN cms_risk_assess.matrix_cd IS '만들 때의 정책 CMS_RA_MATRIX 스냅샷 — 정책이 바뀌어도 이 평가의 수준 계산은 그대로';
COMMENT ON COLUMN cms_risk_assess.released_at IS '확정 시각 — 보존 기산(SAFETY_RECORD). 작성 중에 폐기한 평가(OBSOLETE)는 비어 있다';

-- 전이: DRAFT → IN_APPROVAL|RELEASED|OBSOLETE(폐기), IN_APPROVAL → DRAFT|RELEASED, RELEASED → OBSOLETE
-- 확정 뒤(RELEASED·OBSOLETE) 머리 내용 불변, OBSOLETE 는 끝
CREATE FUNCTION hb_guard_cms_risk_assess() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'INSERT' THEN
        IF NEW.status <> 'DRAFT' THEN
            RAISE EXCEPTION 'new risk assessment must be DRAFT' USING ERRCODE = '55000', CONSTRAINT = 'ck_cms_risk_assess_transition';
        END IF;
        IF NEW.plant_cd IS NOT NULL AND NOT EXISTS (SELECT 1 FROM sys_plant p WHERE p.comp_cd = NEW.comp_cd AND p.plant_cd = NEW.plant_cd) THEN
            RAISE EXCEPTION 'plant % is not of company %', NEW.plant_cd, NEW.comp_cd USING ERRCODE = '23503', CONSTRAINT = 'fk_cms_risk_assess_plant_comp';
        END IF;
        IF NEW.prev_ra_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM cms_risk_assess r WHERE r.ra_id = NEW.prev_ra_id
                AND r.comp_cd = NEW.comp_cd AND r.status = 'RELEASED') THEN
            RAISE EXCEPTION 'previous assessment % is not a released assessment of company %', NEW.prev_ra_id, NEW.comp_cd
                USING ERRCODE = '23503', CONSTRAINT = 'fk_cms_risk_assess_prev_comp';
        END IF;
        RETURN NEW;
    END IF;
    IF NEW.ra_no <> OLD.ra_no OR NEW.comp_cd <> OLD.comp_cd OR NEW.matrix_cd <> OLD.matrix_cd OR NEW.method <> OLD.method
       OR NEW.prev_ra_id IS DISTINCT FROM OLD.prev_ra_id THEN
        RAISE EXCEPTION 'risk assessment key columns are fixed' USING ERRCODE = '55000', CONSTRAINT = 'ck_cms_risk_assess_fixed';
    END IF;
    IF NEW.status <> OLD.status AND NOT (
           (OLD.status = 'DRAFT' AND NEW.status IN ('IN_APPROVAL', 'RELEASED', 'OBSOLETE'))
        OR (OLD.status = 'IN_APPROVAL' AND NEW.status IN ('DRAFT', 'RELEASED'))
        OR (OLD.status = 'RELEASED' AND NEW.status = 'OBSOLETE')) THEN
        RAISE EXCEPTION 'risk assessment cannot go % -> %', OLD.status, NEW.status USING ERRCODE = '55000', CONSTRAINT = 'ck_cms_risk_assess_transition';
    END IF;
    -- 결재 중·확정 뒤에는 내용 불변(상태·결재 번호·확정 칸만)
    IF OLD.status <> 'DRAFT' AND (NEW.title <> OLD.title OR NEW.scope_text IS DISTINCT FROM OLD.scope_text OR NEW.ra_type <> OLD.ra_type
           OR NEW.plant_cd IS DISTINCT FROM OLD.plant_cd OR NEW.assess_date <> OLD.assess_date OR NEW.remark IS DISTINCT FROM OLD.remark) THEN
        RAISE EXCEPTION 'risk assessment % is not editable in %', OLD.ra_no, OLD.status USING ERRCODE = '55000', CONSTRAINT = 'ck_cms_risk_assess_released_fixed';
    END IF;
    IF OLD.status = 'OBSOLETE' THEN
        RAISE EXCEPTION 'obsolete risk assessment is read-only' USING ERRCODE = '55000', CONSTRAINT = 'ck_cms_risk_assess_released_fixed';
    END IF;
    IF NEW.plant_cd IS DISTINCT FROM OLD.plant_cd AND NEW.plant_cd IS NOT NULL
       AND NOT EXISTS (SELECT 1 FROM sys_plant p WHERE p.comp_cd = NEW.comp_cd AND p.plant_cd = NEW.plant_cd) THEN
        RAISE EXCEPTION 'plant % is not of company %', NEW.plant_cd, NEW.comp_cd USING ERRCODE = '23503', CONSTRAINT = 'fk_cms_risk_assess_plant_comp';
    END IF;
    RETURN NEW;
END
$$;
CREATE TRIGGER tg_cms_risk_assess_guard BEFORE INSERT OR UPDATE ON cms_risk_assess FOR EACH ROW EXECUTE FUNCTION hb_guard_cms_risk_assess();
CREATE TRIGGER tg_cms_risk_assess_no_delete BEFORE DELETE ON cms_risk_assess FOR EACH ROW EXECUTE FUNCTION hb_forbid_delete_master();

-- ---------------------------------------------------------------------
-- 2. 유해위험요인
-- ---------------------------------------------------------------------
CREATE TABLE cms_risk_item (
    item_id          bigint        GENERATED ALWAYS AS IDENTITY,
    comp_cd          varchar(20)   NOT NULL,
    ra_id            bigint        NOT NULL,
    seq              integer       NOT NULL,
    task_text        varchar(200)  NOT NULL,
    equip_id         bigint,
    hazard_cat       varchar(5)    NOT NULL,
    hazard_text      varchar(500)  NOT NULL,
    current_measure  varchar(500),
    likelihood       smallint,
    severity         smallint,
    risk_level       varchar(6)    NOT NULL,
    plan_measure     varchar(500),
    owner_user_id    bigint,
    due_date         date,
    done_date        date,
    residual_l       smallint,
    residual_s       smallint,
    residual_level   varchar(6),
    row_ver          integer       NOT NULL DEFAULT 0,
    created_at       timestamptz   NOT NULL DEFAULT now(),
    created_by       varchar(50)   NOT NULL,
    updated_at       timestamptz   NOT NULL DEFAULT now(),
    updated_by       varchar(50)   NOT NULL,
    CONSTRAINT pk_cms_risk_item PRIMARY KEY (item_id),
    CONSTRAINT fk_cms_risk_item_ra FOREIGN KEY (ra_id) REFERENCES cms_risk_assess (ra_id),
    CONSTRAINT fk_cms_risk_item_equip FOREIGN KEY (equip_id) REFERENCES mst_equip (equip_id),
    CONSTRAINT fk_cms_risk_item_owner FOREIGN KEY (owner_user_id) REFERENCES sys_user (user_id),
    CONSTRAINT uq_cms_risk_item_seq UNIQUE (comp_cd, ra_id, seq),
    CONSTRAINT ck_cms_risk_item_seq CHECK (seq BETWEEN 1 AND 9999),
    CONSTRAINT ck_cms_risk_item_text CHECK (length(btrim(task_text)) > 0 AND length(btrim(hazard_text)) > 0),
    CONSTRAINT ck_cms_risk_item_cat CHECK (hazard_cat IN ('MECH', 'ELEC', 'CHEM', 'BIO', 'ERGO', 'ENV', 'OTHER')),
    -- 빈도·강도법은 가능성·중대성 1~5(행렬 크기는 서비스가 평가의 행렬로), 3단계 판단법은 둘 다 비우고 수준만
    CONSTRAINT ck_cms_risk_item_ls CHECK ((likelihood IS NULL) = (severity IS NULL)
                                         AND (likelihood IS NULL OR (likelihood BETWEEN 1 AND 5 AND severity BETWEEN 1 AND 5))),
    CONSTRAINT ck_cms_risk_item_level CHECK (risk_level IN ('LOW', 'MEDIUM', 'HIGH')
                                            AND (residual_level IS NULL OR residual_level IN ('LOW', 'MEDIUM', 'HIGH'))),
    CONSTRAINT ck_cms_risk_item_residual CHECK ((residual_l IS NULL) = (residual_s IS NULL)
                                               AND (residual_l IS NULL OR (residual_l BETWEEN 1 AND 5 AND residual_s BETWEEN 1 AND 5))),
    -- 개선 후 위험성은 개선을 끝냈을 때만
    CONSTRAINT ck_cms_risk_item_done CHECK (done_date IS NOT NULL OR (residual_level IS NULL AND residual_l IS NULL))
);
CREATE INDEX ix_cms_risk_item_ra ON cms_risk_item (comp_cd, ra_id);
CREATE INDEX ix_cms_risk_item_due ON cms_risk_item (comp_cd, due_date) WHERE done_date IS NULL AND due_date IS NOT NULL;
COMMENT ON TABLE cms_risk_item IS '유해위험요인 — 평가가 작성 중일 때만 추가·수정·삭제, 확정 뒤에는 개선 진행 칸(완료일·개선 후 위험성)만';

CREATE FUNCTION hb_guard_cms_risk_item() RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    r       record;
    st      varchar;
BEGIN
    r := CASE WHEN TG_OP = 'DELETE' THEN OLD ELSE NEW END;
    IF TG_OP = 'UPDATE' AND (NEW.ra_id <> OLD.ra_id OR NEW.comp_cd <> OLD.comp_cd) THEN
        RAISE EXCEPTION 'risk item assessment is fixed' USING ERRCODE = '55000', CONSTRAINT = 'ck_cms_risk_item_fixed';
    END IF;
    -- 평가 행을 잠가 상신·확정과 줄 세운다
    SELECT a.status INTO st FROM cms_risk_assess a WHERE a.ra_id = r.ra_id AND a.comp_cd = r.comp_cd FOR UPDATE;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'assessment % is not of company %', r.ra_id, r.comp_cd USING ERRCODE = '23503', CONSTRAINT = 'fk_cms_risk_item_ra_comp';
    END IF;
    IF st <> 'DRAFT' THEN
        -- 확정본: 개선 진행 칸만, 그것도 RELEASED 일 때만
        IF TG_OP <> 'UPDATE' OR st <> 'RELEASED'
           OR NEW.seq <> OLD.seq OR NEW.task_text <> OLD.task_text OR NEW.equip_id IS DISTINCT FROM OLD.equip_id
           OR NEW.hazard_cat <> OLD.hazard_cat OR NEW.hazard_text <> OLD.hazard_text
           OR NEW.current_measure IS DISTINCT FROM OLD.current_measure OR NEW.likelihood IS DISTINCT FROM OLD.likelihood
           OR NEW.severity IS DISTINCT FROM OLD.severity OR NEW.risk_level <> OLD.risk_level
           OR NEW.plan_measure IS DISTINCT FROM OLD.plan_measure OR NEW.owner_user_id IS DISTINCT FROM OLD.owner_user_id
           OR NEW.due_date IS DISTINCT FROM OLD.due_date THEN
            RAISE EXCEPTION 'risk item of % assessment is fixed (only improvement progress)', st
                USING ERRCODE = '55000', CONSTRAINT = 'ck_cms_risk_item_released_fixed';
        END IF;
    END IF;
    IF TG_OP <> 'DELETE' THEN
        IF NEW.equip_id IS NOT NULL AND (TG_OP = 'INSERT' OR NEW.equip_id IS DISTINCT FROM OLD.equip_id)
           AND NOT EXISTS (SELECT 1 FROM mst_equip e WHERE e.equip_id = NEW.equip_id AND e.comp_cd = NEW.comp_cd AND e.use_yn = 'Y') THEN
            RAISE EXCEPTION 'equipment % is not an active equipment of company %', NEW.equip_id, NEW.comp_cd
                USING ERRCODE = '23503', CONSTRAINT = 'fk_cms_risk_item_equip_comp';
        END IF;
        IF NEW.owner_user_id IS NOT NULL AND (TG_OP = 'INSERT' OR NEW.owner_user_id IS DISTINCT FROM OLD.owner_user_id)
           AND NOT EXISTS (SELECT 1 FROM sys_user u WHERE u.user_id = NEW.owner_user_id AND u.comp_cd = NEW.comp_cd AND u.use_yn = 'Y') THEN
            RAISE EXCEPTION 'user % is not an active user of company %', NEW.owner_user_id, NEW.comp_cd
                USING ERRCODE = '23503', CONSTRAINT = 'fk_cms_risk_item_owner_comp';
        END IF;
    END IF;
    RETURN r;
END
$$;
CREATE TRIGGER tg_cms_risk_item_guard BEFORE INSERT OR UPDATE OR DELETE ON cms_risk_item FOR EACH ROW EXECUTE FUNCTION hb_guard_cms_risk_item();

-- ---------------------------------------------------------------------
-- 3. 참여·주지 기록
-- ---------------------------------------------------------------------
CREATE TABLE cms_risk_member (
    member_id        bigint        GENERATED ALWAYS AS IDENTITY,
    comp_cd          varchar(20)   NOT NULL,
    ra_id            bigint        NOT NULL,
    user_id          bigint,
    member_nm        varchar(100)  NOT NULL,
    role_cd          varchar(10)   NOT NULL,
    participated_on  date,
    notified_on      date,
    remark           varchar(500),
    row_ver          integer       NOT NULL DEFAULT 0,
    created_at       timestamptz   NOT NULL DEFAULT now(),
    created_by       varchar(50)   NOT NULL,
    updated_at       timestamptz   NOT NULL DEFAULT now(),
    updated_by       varchar(50)   NOT NULL,
    CONSTRAINT pk_cms_risk_member PRIMARY KEY (member_id),
    CONSTRAINT fk_cms_risk_member_ra FOREIGN KEY (ra_id) REFERENCES cms_risk_assess (ra_id),
    CONSTRAINT fk_cms_risk_member_user FOREIGN KEY (user_id) REFERENCES sys_user (user_id),
    CONSTRAINT ck_cms_risk_member_nm CHECK (length(btrim(member_nm)) > 0),
    CONSTRAINT ck_cms_risk_member_role CHECK (role_cd IN ('MANAGER', 'SUPERVISOR', 'SAFETY', 'WORKER_REP', 'WORKER', 'OTHER'))
);
CREATE INDEX ix_cms_risk_member_ra ON cms_risk_member (comp_cd, ra_id);
COMMENT ON TABLE cms_risk_member IS '위험성평가 참여·주지 — 사용자 또는 이름(협력업체 근로자 등). 근로자(대표) 참여 없이는 확정 불가';
COMMENT ON COLUMN cms_risk_member.member_nm IS '표시 이름 — 사용자면 그때 이름을 스냅샷';

-- 작성 중: 추가·수정·삭제. 확정 뒤(RELEASED): 주지일만 채운다. 그 밖: 불변
CREATE FUNCTION hb_guard_cms_risk_member() RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    r   record;
    st  varchar;
BEGIN
    r := CASE WHEN TG_OP = 'DELETE' THEN OLD ELSE NEW END;
    IF TG_OP = 'UPDATE' AND (NEW.ra_id <> OLD.ra_id OR NEW.comp_cd <> OLD.comp_cd) THEN
        RAISE EXCEPTION 'member assessment is fixed' USING ERRCODE = '55000', CONSTRAINT = 'ck_cms_risk_member_fixed';
    END IF;
    SELECT a.status INTO st FROM cms_risk_assess a WHERE a.ra_id = r.ra_id AND a.comp_cd = r.comp_cd FOR UPDATE;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'assessment % is not of company %', r.ra_id, r.comp_cd USING ERRCODE = '23503', CONSTRAINT = 'fk_cms_risk_member_ra_comp';
    END IF;
    IF st <> 'DRAFT' AND (TG_OP <> 'UPDATE' OR st <> 'RELEASED' OR NEW.user_id IS DISTINCT FROM OLD.user_id
           OR NEW.member_nm <> OLD.member_nm OR NEW.role_cd <> OLD.role_cd OR NEW.participated_on IS DISTINCT FROM OLD.participated_on) THEN
        RAISE EXCEPTION 'member of % assessment is fixed (only notification date)', st
            USING ERRCODE = '55000', CONSTRAINT = 'ck_cms_risk_member_released_fixed';
    END IF;
    IF TG_OP <> 'DELETE' AND NEW.user_id IS NOT NULL AND (TG_OP = 'INSERT' OR NEW.user_id IS DISTINCT FROM OLD.user_id)
       AND NOT hb_user_of_comp(NEW.comp_cd, NEW.user_id) THEN
        RAISE EXCEPTION 'user % is not of company %', NEW.user_id, NEW.comp_cd USING ERRCODE = '23503', CONSTRAINT = 'fk_cms_risk_member_user_comp';
    END IF;
    RETURN r;
END
$$;
CREATE TRIGGER tg_cms_risk_member_guard BEFORE INSERT OR UPDATE OR DELETE ON cms_risk_member
    FOR EACH ROW EXECUTE FUNCTION hb_guard_cms_risk_member();

-- ---------------------------------------------------------------------
-- 4. 감사 · RLS · 권한 · 화면 · 보존
-- ---------------------------------------------------------------------
CREATE TRIGGER tg_audit AFTER INSERT OR UPDATE OR DELETE ON cms_risk_assess FOR EACH ROW EXECUTE FUNCTION hb_audit('ra_id', '', '');
CREATE TRIGGER tg_audit AFTER INSERT OR UPDATE OR DELETE ON cms_risk_item FOR EACH ROW EXECUTE FUNCTION hb_audit('item_id', '', '');
CREATE TRIGGER tg_audit AFTER INSERT OR UPDATE OR DELETE ON cms_risk_member FOR EACH ROW EXECUTE FUNCTION hb_audit('member_id', '', '');

ALTER TABLE cms_risk_assess ENABLE ROW LEVEL SECURITY;
ALTER TABLE cms_risk_assess FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON cms_risk_assess USING (comp_cd = hb_current_comp()) WITH CHECK (comp_cd = hb_current_comp());
ALTER TABLE cms_risk_item ENABLE ROW LEVEL SECURITY;
ALTER TABLE cms_risk_item FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON cms_risk_item USING (comp_cd = hb_current_comp()) WITH CHECK (comp_cd = hb_current_comp());
ALTER TABLE cms_risk_member ENABLE ROW LEVEL SECURITY;
ALTER TABLE cms_risk_member FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON cms_risk_member USING (comp_cd = hb_current_comp()) WITH CHECK (comp_cd = hb_current_comp());
REVOKE DELETE, TRUNCATE ON cms_risk_assess FROM ${app_role};
REVOKE TRUNCATE ON cms_risk_item, cms_risk_member FROM ${app_role};

INSERT INTO sys_prog (prog_cd, prog_nm_key, view_path, module_cd, actions, sort_no) VALUES
    ('cms-risk', 'prog.cmsRisk', 'cms/risk-mgt', 'CMS', ARRAY['SEARCH','SAVE','APPROVE','DELETE','EXCEL']::varchar(20)[], 590);
INSERT INTO sys_retention_table (table_nm, class_cd) VALUES
    ('cms_risk_assess', 'SAFETY_RECORD'), ('cms_risk_item', 'SAFETY_RECORD'), ('cms_risk_member', 'SAFETY_RECORD');
