-- =====================================================================
-- V030 근무조·작업자·자격 (P2 1단계 — docs/25 4.2, 결정 D3·D5)
--
-- - mst_shift: 공장별 근무조(현지 시각, 자정 넘는 조, 작업일 오프셋). 사용 중 조끼리 시간 겹침 금지(트리거)
-- - mst_worker: 작업자(로그인 계정 아님). 배지는 해시만, 사무 계정 연결은 선택(1:1)
-- - mst_qual / mst_worker_qual / mst_proc_qual_req: 자격 · 보유(갱신 = 새 행, 삭제 없음) · 공정별 필요 자격(WARN/BLOCK)
-- - 정책 QUAL_GATE_MODE(공장별 상한 OFF/WARN/BLOCK)
-- =====================================================================

INSERT INTO sys_policy_def (policy_key, value_type, default_value, allowed_values, min_value, max_value, plant_scope_yn, desc_key) VALUES
    -- docs/25 4.2: 실제 게이트 = min(이 정책, 공정 요구 행의 gate_mode). 공장마다 — 도입 초기엔 WARN 으로 시작해 BLOCK 으로 올린다
    ('QUAL_GATE_MODE', 'ENUM', 'WARN', ARRAY['OFF','WARN','BLOCK'], NULL, NULL, 'Y', 'policy.QUAL_GATE_MODE');

INSERT INTO sys_code_grp (grp_cd, grp_nm_key, comp_ext_yn) VALUES
    ('GATE_MODE', 'code.grp.GATE_MODE', 'N');
INSERT INTO sys_code (grp_cd, code, code_nm_key, sort_no) VALUES
    ('GATE_MODE', 'WARN',  'code.GATE_MODE.WARN',  1),
    ('GATE_MODE', 'BLOCK', 'code.GATE_MODE.BLOCK', 2);

-- ---------------------------------------------------------------------
-- 1. 근무조
-- ---------------------------------------------------------------------
CREATE TABLE mst_shift (
    shift_id    bigint        GENERATED ALWAYS AS IDENTITY,
    comp_cd     varchar(20)   NOT NULL,
    plant_cd    varchar(20)   NOT NULL,
    shift_cd    varchar(10)   NOT NULL,
    shift_nm    varchar(50)   NOT NULL,
    start_time  time(0)       NOT NULL,
    end_time    time(0)       NOT NULL,
    day_offset  smallint      NOT NULL DEFAULT 0,
    break_min   smallint      NOT NULL DEFAULT 0,
    sort_no     integer       NOT NULL DEFAULT 0,
    use_yn      char(1)       NOT NULL DEFAULT 'Y',
    remark      varchar(500),
    row_ver     integer       NOT NULL DEFAULT 0,
    created_at  timestamptz   NOT NULL DEFAULT now(),
    created_by  varchar(50)   NOT NULL,
    updated_at  timestamptz   NOT NULL DEFAULT now(),
    updated_by  varchar(50)   NOT NULL,
    CONSTRAINT pk_mst_shift PRIMARY KEY (shift_id),
    CONSTRAINT fk_mst_shift_plant FOREIGN KEY (comp_cd, plant_cd) REFERENCES sys_plant (comp_cd, plant_cd),
    CONSTRAINT uq_mst_shift_cd UNIQUE (comp_cd, plant_cd, shift_cd),
    CONSTRAINT ck_mst_shift_cd CHECK (shift_cd ~ '^[A-Z0-9][A-Z0-9_-]{0,9}$'),
    CONSTRAINT ck_mst_shift_nm CHECK (length(btrim(shift_nm)) > 0),
    -- 분 단위(초 0), 시작 = 끝(24시간 조)은 만들지 않는다
    CONSTRAINT ck_mst_shift_time CHECK (extract(second FROM start_time) = 0 AND extract(second FROM end_time) = 0
                                        AND start_time <> end_time),
    CONSTRAINT ck_mst_shift_offset CHECK (day_offset IN (-1, 0)),
    CONSTRAINT ck_mst_shift_break CHECK (break_min BETWEEN 0 AND 600),
    CONSTRAINT ck_mst_shift_use_yn CHECK (use_yn IN ('Y', 'N'))
);
COMMENT ON TABLE mst_shift IS '근무조 (공장 현지 시각). end <= start 면 자정을 넘는 조. 작업일 = 시작 날짜 + day_offset (docs/25 4.2)';

-- 근무 시간(분). 자정을 넘으면 + 1440
CREATE FUNCTION hb_shift_minutes(p_start time, p_end time) RETURNS integer
    LANGUAGE sql IMMUTABLE
AS $$
    SELECT CASE WHEN p_end > p_start
                THEN (extract(epoch FROM p_end - p_start) / 60)::integer
                ELSE (extract(epoch FROM p_end - p_start) / 60)::integer + 1440 END
$$;

CREATE FUNCTION hb_guard_shift() RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    v_s integer;
    v_e integer;
    v_os integer;
    v_oe integer;
    r record;
BEGIN
    v_s := (extract(epoch FROM NEW.start_time) / 60)::integer;
    v_e := v_s + hb_shift_minutes(NEW.start_time, NEW.end_time);
    IF NEW.break_min >= v_e - v_s THEN
        RAISE EXCEPTION 'break % min must be shorter than the shift', NEW.break_min
            USING ERRCODE = '23514', CONSTRAINT = 'ck_mst_shift_break_len';
    END IF;
    IF NEW.use_yn <> 'Y' THEN
        RETURN NEW;
    END IF;
    -- 같은 공장 근무조 저장을 줄 세운다(동시 저장 두 건이 서로의 겹침을 못 보는 것 방지)
    PERFORM pg_advisory_xact_lock(hashtext('mst_shift'), hashtext(NEW.comp_cd || '/' || NEW.plant_cd));
    FOR r IN SELECT start_time, end_time, shift_cd FROM mst_shift
              WHERE comp_cd = NEW.comp_cd AND plant_cd = NEW.plant_cd AND use_yn = 'Y'
                AND shift_id IS DISTINCT FROM NEW.shift_id LOOP
        v_os := (extract(epoch FROM r.start_time) / 60)::integer;
        v_oe := v_os + hb_shift_minutes(r.start_time, r.end_time);
        -- 하루 원 위의 구간: 상대 구간을 -1440·0·+1440 으로 옮겨 하나라도 겹치면 겹침
        IF (v_s < v_oe - 1440 AND v_os - 1440 < v_e) OR (v_s < v_oe AND v_os < v_e) OR (v_s < v_oe + 1440 AND v_os + 1440 < v_e) THEN
            RAISE EXCEPTION 'shift % overlaps shift % in plant %', NEW.shift_cd, r.shift_cd, NEW.plant_cd
                USING ERRCODE = '23P01', CONSTRAINT = 'ck_mst_shift_overlap';
        END IF;
    END LOOP;
    RETURN NEW;
END
$$;
CREATE TRIGGER tg_mst_shift_guard BEFORE INSERT OR UPDATE ON mst_shift FOR EACH ROW EXECUTE FUNCTION hb_guard_shift();
CREATE TRIGGER tg_mst_shift_no_delete BEFORE DELETE ON mst_shift FOR EACH ROW EXECUTE FUNCTION hb_forbid_delete_master();

-- ---------------------------------------------------------------------
-- 2. 작업자
-- ---------------------------------------------------------------------
CREATE TABLE mst_worker (
    worker_id   bigint        GENERATED ALWAYS AS IDENTITY,
    comp_cd     varchar(20)   NOT NULL,
    worker_cd   varchar(20)   NOT NULL,
    worker_nm   varchar(50)   NOT NULL,
    plant_cd    varchar(20),
    dept_nm     varchar(50),
    badge_hash  varchar(64),
    pin_hash    varchar(200),
    user_id     bigint,
    use_yn      char(1)       NOT NULL DEFAULT 'Y',
    remark      varchar(500),
    row_ver     integer       NOT NULL DEFAULT 0,
    created_at  timestamptz   NOT NULL DEFAULT now(),
    created_by  varchar(50)   NOT NULL,
    updated_at  timestamptz   NOT NULL DEFAULT now(),
    updated_by  varchar(50)   NOT NULL,
    CONSTRAINT pk_mst_worker PRIMARY KEY (worker_id),
    CONSTRAINT fk_mst_worker_comp FOREIGN KEY (comp_cd) REFERENCES sys_comp (comp_cd),
    CONSTRAINT fk_mst_worker_plant FOREIGN KEY (comp_cd, plant_cd) REFERENCES sys_plant (comp_cd, plant_cd),
    CONSTRAINT fk_mst_worker_user FOREIGN KEY (user_id) REFERENCES sys_user (user_id),
    CONSTRAINT uq_mst_worker_cd UNIQUE (comp_cd, worker_cd),
    CONSTRAINT uq_mst_worker_badge UNIQUE (comp_cd, badge_hash),
    CONSTRAINT uq_mst_worker_user UNIQUE (comp_cd, user_id),
    CONSTRAINT ck_mst_worker_cd CHECK (worker_cd ~ '^[A-Z0-9][A-Z0-9._-]{0,19}$'),
    CONSTRAINT ck_mst_worker_nm CHECK (length(btrim(worker_nm)) > 0),
    CONSTRAINT ck_mst_worker_badge CHECK (badge_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_mst_worker_use_yn CHECK (use_yn IN ('Y', 'N'))
);
COMMENT ON TABLE mst_worker IS '작업자 (로그인 계정 아님 — POP 은 단말 세션 + 배지 전환, docs/25 D3). 사무 계정 연결은 선택';
COMMENT ON COLUMN mst_worker.badge_hash IS 'sha256(회사코드 || '':'' || 배지 값) hex — 배지 값은 저장하지 않는다';
COMMENT ON COLUMN mst_worker.pin_hash IS 'POP PIN(BCrypt) — 지정·확인은 P2 5a';

CREATE FUNCTION hb_guard_worker() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    -- 사무 계정은 같은 회사 계정만(FK 는 회사를 보지 않는다)
    IF NEW.user_id IS NOT NULL AND (TG_OP = 'INSERT' OR NEW.user_id IS DISTINCT FROM OLD.user_id)
       AND NOT EXISTS (SELECT 1 FROM sys_user u WHERE u.user_id = NEW.user_id AND u.comp_cd = NEW.comp_cd) THEN
        RAISE EXCEPTION 'user % is not a user of company %', NEW.user_id, NEW.comp_cd
            USING ERRCODE = '23503', CONSTRAINT = 'fk_mst_worker_user_comp';
    END IF;
    IF TG_OP = 'UPDATE' AND NEW.worker_cd <> OLD.worker_cd THEN
        RAISE EXCEPTION 'worker code is fixed' USING ERRCODE = '55000', CONSTRAINT = 'ck_mst_worker_cd_fixed';
    END IF;
    RETURN NEW;
END
$$;
CREATE TRIGGER tg_mst_worker_guard BEFORE INSERT OR UPDATE ON mst_worker FOR EACH ROW EXECUTE FUNCTION hb_guard_worker();
CREATE TRIGGER tg_mst_worker_no_delete BEFORE DELETE ON mst_worker FOR EACH ROW EXECUTE FUNCTION hb_forbid_delete_master();

-- ---------------------------------------------------------------------
-- 3. 자격 · 보유 · 공정별 필요 자격
-- ---------------------------------------------------------------------
CREATE TABLE mst_qual (
    qual_id      bigint        GENERATED ALWAYS AS IDENTITY,
    comp_cd      varchar(20)   NOT NULL,
    qual_cd      varchar(20)   NOT NULL,
    qual_nm      varchar(100)  NOT NULL,
    valid_months smallint,
    sort_no      integer       NOT NULL DEFAULT 0,
    use_yn       char(1)       NOT NULL DEFAULT 'Y',
    remark       varchar(500),
    row_ver      integer       NOT NULL DEFAULT 0,
    created_at   timestamptz   NOT NULL DEFAULT now(),
    created_by   varchar(50)   NOT NULL,
    updated_at   timestamptz   NOT NULL DEFAULT now(),
    updated_by   varchar(50)   NOT NULL,
    CONSTRAINT pk_mst_qual PRIMARY KEY (qual_id),
    CONSTRAINT fk_mst_qual_comp FOREIGN KEY (comp_cd) REFERENCES sys_comp (comp_cd),
    CONSTRAINT uq_mst_qual_cd UNIQUE (comp_cd, qual_cd),
    CONSTRAINT ck_mst_qual_cd CHECK (qual_cd ~ '^[A-Z0-9][A-Z0-9._-]{0,19}$'),
    CONSTRAINT ck_mst_qual_nm CHECK (length(btrim(qual_nm)) > 0),
    CONSTRAINT ck_mst_qual_months CHECK (valid_months BETWEEN 1 AND 600),
    CONSTRAINT ck_mst_qual_use_yn CHECK (use_yn IN ('Y', 'N'))
);
COMMENT ON TABLE mst_qual IS '자격 (용접·크레인·검사원 등). valid_months 가 있으면 보유 행의 기본 만료일 = 취득일 + 개월';
CREATE TRIGGER tg_mst_qual_no_delete BEFORE DELETE ON mst_qual FOR EACH ROW EXECUTE FUNCTION hb_forbid_delete_master();

CREATE TABLE mst_worker_qual (
    wq_id        bigint        GENERATED ALWAYS AS IDENTITY,
    comp_cd      varchar(20)   NOT NULL,
    worker_id    bigint        NOT NULL,
    qual_id      bigint        NOT NULL,
    acquired_on  date          NOT NULL,
    expires_on   date,
    revoked_on   date,
    cert_no      varchar(50),
    remark       varchar(500),
    row_ver      integer       NOT NULL DEFAULT 0,
    created_at   timestamptz   NOT NULL DEFAULT now(),
    created_by   varchar(50)   NOT NULL,
    updated_at   timestamptz   NOT NULL DEFAULT now(),
    updated_by   varchar(50)   NOT NULL,
    CONSTRAINT pk_mst_worker_qual PRIMARY KEY (wq_id),
    CONSTRAINT fk_mst_worker_qual_worker FOREIGN KEY (worker_id) REFERENCES mst_worker (worker_id),
    CONSTRAINT fk_mst_worker_qual_qual FOREIGN KEY (qual_id) REFERENCES mst_qual (qual_id),
    CONSTRAINT ck_mst_worker_qual_period CHECK (expires_on IS NULL OR expires_on >= acquired_on),
    CONSTRAINT ck_mst_worker_qual_revoke CHECK (revoked_on IS NULL OR revoked_on >= acquired_on),
    CONSTRAINT ck_mst_worker_qual_years CHECK (acquired_on >= DATE '1950-01-01' AND acquired_on <= DATE '2999-12-31'
                                               AND (expires_on IS NULL OR expires_on <= DATE '2999-12-31'))
);
COMMENT ON TABLE mst_worker_qual IS '작업자 자격 보유 — 갱신은 새 행(이력), 삭제 없음. 취소는 revoked_on (docs/25 4.2)';
CREATE INDEX ix_mst_worker_qual ON mst_worker_qual (comp_cd, worker_id, qual_id, acquired_on);

CREATE TABLE mst_proc_qual_req (
    req_id      bigint        GENERATED ALWAYS AS IDENTITY,
    comp_cd     varchar(20)   NOT NULL,
    proc_id     bigint        NOT NULL,
    qual_id     bigint        NOT NULL,
    gate_mode   varchar(10)   NOT NULL DEFAULT 'WARN',
    use_yn      char(1)       NOT NULL DEFAULT 'Y',
    row_ver     integer       NOT NULL DEFAULT 0,
    created_at  timestamptz   NOT NULL DEFAULT now(),
    created_by  varchar(50)   NOT NULL,
    updated_at  timestamptz   NOT NULL DEFAULT now(),
    updated_by  varchar(50)   NOT NULL,
    CONSTRAINT pk_mst_proc_qual_req PRIMARY KEY (req_id),
    CONSTRAINT fk_mst_proc_qual_req_proc FOREIGN KEY (proc_id) REFERENCES mst_proc (proc_id),
    CONSTRAINT fk_mst_proc_qual_req_qual FOREIGN KEY (qual_id) REFERENCES mst_qual (qual_id),
    CONSTRAINT uq_mst_proc_qual_req UNIQUE (comp_cd, proc_id, qual_id),
    CONSTRAINT ck_mst_proc_qual_req_mode CHECK (gate_mode IN ('WARN', 'BLOCK')),
    CONSTRAINT ck_mst_proc_qual_req_use_yn CHECK (use_yn IN ('Y', 'N'))
);
COMMENT ON TABLE mst_proc_qual_req IS '공정별 필요 자격 — 착수 게이트(P2 5b). 실제 모드 = min(정책 QUAL_GATE_MODE, gate_mode)';

-- 보유·요구 행은 같은 회사의 작업자·자격·공정만, 대상(작업자·자격·공정)은 바꾸지 않는다
CREATE FUNCTION hb_guard_worker_qual() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'UPDATE' AND (NEW.worker_id <> OLD.worker_id OR NEW.qual_id <> OLD.qual_id OR NEW.acquired_on <> OLD.acquired_on) THEN
        RAISE EXCEPTION 'worker qualification target is fixed (add a new row)' USING ERRCODE = '55000', CONSTRAINT = 'ck_mst_worker_qual_fixed';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM mst_worker w WHERE w.worker_id = NEW.worker_id AND w.comp_cd = NEW.comp_cd) THEN
        RAISE EXCEPTION 'worker % is not of company %', NEW.worker_id, NEW.comp_cd USING ERRCODE = '23503', CONSTRAINT = 'fk_mst_worker_qual_worker_comp';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM mst_qual q WHERE q.qual_id = NEW.qual_id AND q.comp_cd = NEW.comp_cd
                      AND (TG_OP = 'UPDATE' OR q.use_yn = 'Y')) THEN
        RAISE EXCEPTION 'qualification % is not an active qualification of company %', NEW.qual_id, NEW.comp_cd
            USING ERRCODE = '23503', CONSTRAINT = 'fk_mst_worker_qual_qual_comp';
    END IF;
    RETURN NEW;
END
$$;
CREATE TRIGGER tg_mst_worker_qual_guard BEFORE INSERT OR UPDATE ON mst_worker_qual FOR EACH ROW EXECUTE FUNCTION hb_guard_worker_qual();
CREATE TRIGGER tg_mst_worker_qual_no_delete BEFORE DELETE ON mst_worker_qual FOR EACH ROW EXECUTE FUNCTION hb_forbid_delete_master();

CREATE FUNCTION hb_guard_proc_qual_req() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'UPDATE' AND (NEW.proc_id <> OLD.proc_id OR NEW.qual_id <> OLD.qual_id) THEN
        RAISE EXCEPTION 'requirement target is fixed' USING ERRCODE = '55000', CONSTRAINT = 'ck_mst_proc_qual_req_fixed';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM mst_proc p WHERE p.proc_id = NEW.proc_id AND p.comp_cd = NEW.comp_cd) THEN
        RAISE EXCEPTION 'process % is not of company %', NEW.proc_id, NEW.comp_cd USING ERRCODE = '23503', CONSTRAINT = 'fk_mst_proc_qual_req_proc_comp';
    END IF;
    -- 새로 요구하거나 다시 사용(N→Y)할 때는 사용 중인 자격만
    IF NEW.use_yn = 'Y' AND (TG_OP = 'INSERT' OR OLD.use_yn <> 'Y')
       AND NOT EXISTS (SELECT 1 FROM mst_qual q WHERE q.qual_id = NEW.qual_id AND q.comp_cd = NEW.comp_cd AND q.use_yn = 'Y') THEN
        RAISE EXCEPTION 'qualification % is not an active qualification of company %', NEW.qual_id, NEW.comp_cd
            USING ERRCODE = '23503', CONSTRAINT = 'fk_mst_proc_qual_req_qual_comp';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM mst_qual q WHERE q.qual_id = NEW.qual_id AND q.comp_cd = NEW.comp_cd) THEN
        RAISE EXCEPTION 'qualification % is not of company %', NEW.qual_id, NEW.comp_cd
            USING ERRCODE = '23503', CONSTRAINT = 'fk_mst_proc_qual_req_qual_comp';
    END IF;
    RETURN NEW;
END
$$;
CREATE TRIGGER tg_mst_proc_qual_req_guard BEFORE INSERT OR UPDATE ON mst_proc_qual_req FOR EACH ROW EXECUTE FUNCTION hb_guard_proc_qual_req();
CREATE TRIGGER tg_mst_proc_qual_req_no_delete BEFORE DELETE ON mst_proc_qual_req FOR EACH ROW EXECUTE FUNCTION hb_forbid_delete_master();

-- ---------------------------------------------------------------------
-- 4. 감사 · RLS · 권한 · 화면 · 보존
-- ---------------------------------------------------------------------
CREATE TRIGGER tg_audit AFTER INSERT OR UPDATE OR DELETE ON mst_shift FOR EACH ROW EXECUTE FUNCTION hb_audit('shift_id', '', '');
CREATE TRIGGER tg_audit AFTER INSERT OR UPDATE OR DELETE ON mst_worker FOR EACH ROW EXECUTE FUNCTION hb_audit('worker_id', '', 'badge_hash,pin_hash');
CREATE TRIGGER tg_audit AFTER INSERT OR UPDATE OR DELETE ON mst_qual FOR EACH ROW EXECUTE FUNCTION hb_audit('qual_id', '', '');
CREATE TRIGGER tg_audit AFTER INSERT OR UPDATE OR DELETE ON mst_worker_qual FOR EACH ROW EXECUTE FUNCTION hb_audit('wq_id', '', '');
CREATE TRIGGER tg_audit AFTER INSERT OR UPDATE OR DELETE ON mst_proc_qual_req FOR EACH ROW EXECUTE FUNCTION hb_audit('req_id', '', '');

ALTER TABLE mst_shift ENABLE ROW LEVEL SECURITY;
ALTER TABLE mst_shift FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON mst_shift USING (comp_cd = hb_current_comp()) WITH CHECK (comp_cd = hb_current_comp());
ALTER TABLE mst_worker ENABLE ROW LEVEL SECURITY;
ALTER TABLE mst_worker FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON mst_worker USING (comp_cd = hb_current_comp()) WITH CHECK (comp_cd = hb_current_comp());
ALTER TABLE mst_qual ENABLE ROW LEVEL SECURITY;
ALTER TABLE mst_qual FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON mst_qual USING (comp_cd = hb_current_comp()) WITH CHECK (comp_cd = hb_current_comp());
ALTER TABLE mst_worker_qual ENABLE ROW LEVEL SECURITY;
ALTER TABLE mst_worker_qual FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON mst_worker_qual USING (comp_cd = hb_current_comp()) WITH CHECK (comp_cd = hb_current_comp());
ALTER TABLE mst_proc_qual_req ENABLE ROW LEVEL SECURITY;
ALTER TABLE mst_proc_qual_req FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON mst_proc_qual_req USING (comp_cd = hb_current_comp()) WITH CHECK (comp_cd = hb_current_comp());
REVOKE DELETE, TRUNCATE ON mst_shift, mst_worker, mst_qual, mst_worker_qual, mst_proc_qual_req FROM ${app_role};

INSERT INTO sys_prog (prog_cd, prog_nm_key, view_path, module_cd, actions, sort_no) VALUES
    ('mst-shift',  'prog.mstShift',  'master/shift-mgt',  'MST', ARRAY['SEARCH','SAVE']::varchar(20)[], 180),
    ('mst-qual',   'prog.mstQual',   'master/qual-mgt',   'MST', ARRAY['SEARCH','SAVE']::varchar(20)[], 181),
    ('mst-worker', 'prog.mstWorker', 'master/worker-mgt', 'MST', ARRAY['SEARCH','SAVE']::varchar(20)[], 182);
INSERT INTO sys_retention_table (table_nm, class_cd) VALUES
    ('mst_shift', 'MASTER'), ('mst_worker', 'MASTER'), ('mst_qual', 'MASTER'), ('mst_worker_qual', 'MASTER'),
    ('mst_proc_qual_req', 'MASTER');
