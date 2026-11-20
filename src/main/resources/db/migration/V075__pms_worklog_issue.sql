-- =====================================================================
-- PMS 4단계 — 공수(M/D)·이슈 화면 (docs/32 PM4, 4.6, 결정 D9·D10)
-- 임시 번호(docs/29 2.2) — 상위 세션이 병합 때 파일 이름만 바꾼다
--
-- - pms_work_log: 사용자 × 작업일 × 프로젝트·작업 × 분류 × 시간. 미리 채움(PREFILL) → 일 확정(CONFIRMED),
--   확정 행은 "다시 열기"(사유) 로만 작성 중이 된다. 하루 합계 ≤ 24h 는 트리거가 사용자·날짜 잠금 후 확인(정책은 서비스가 더 좁게)
-- - 분류 PMS_WORK_CAT = 시스템 기본 5개 + 회사 추가(comp_ext_yn Y)
-- - 이슈 표는 3단계에서 만들었다 — 여기는 화면 권한(pms-issue)만
-- =====================================================================

INSERT INTO sys_policy_def (policy_key, value_type, default_value, allowed_values, min_value, max_value, desc_key) VALUES
    ('PMS_DAY_MAX_HOURS', 'INT', '24', NULL, 1, 24, 'policy.PMS_DAY_MAX_HOURS');

INSERT INTO sys_code_grp (grp_cd, grp_nm_key, comp_ext_yn) VALUES
    ('PMS_WORK_CAT', 'code.grp.PMS_WORK_CAT', 'Y');
INSERT INTO sys_code (grp_cd, code, code_nm_key, sort_no) VALUES
    ('PMS_WORK_CAT', 'DESIGN',  'code.PMS_WORK_CAT.DESIGN',  1),
    ('PMS_WORK_CAT', 'MEETING', 'code.PMS_WORK_CAT.MEETING', 2),
    ('PMS_WORK_CAT', 'DOC',     'code.PMS_WORK_CAT.DOC',     3),
    ('PMS_WORK_CAT', 'TEST',    'code.PMS_WORK_CAT.TEST',    4),
    ('PMS_WORK_CAT', 'OTHER',   'code.PMS_WORK_CAT.OTHER',   5);

-- 이슈 심각도·상태(화면 선택지·엑셀 표기)
INSERT INTO sys_code_grp (grp_cd, grp_nm_key, comp_ext_yn) VALUES
    ('PMS_ISSUE_SEVERITY', 'code.grp.PMS_ISSUE_SEVERITY', 'N'),
    ('PMS_ISSUE_STATUS',   'code.grp.PMS_ISSUE_STATUS',   'N');
INSERT INTO sys_code (grp_cd, code, code_nm_key, sort_no) VALUES
    ('PMS_ISSUE_SEVERITY', 'HIGH', 'code.PMS_ISSUE_SEVERITY.HIGH', 1),
    ('PMS_ISSUE_SEVERITY', 'MID',  'code.PMS_ISSUE_SEVERITY.MID',  2),
    ('PMS_ISSUE_SEVERITY', 'LOW',  'code.PMS_ISSUE_SEVERITY.LOW',  3),
    ('PMS_ISSUE_STATUS', 'OPEN',        'code.PMS_ISSUE_STATUS.OPEN',        1),
    ('PMS_ISSUE_STATUS', 'IN_PROGRESS', 'code.PMS_ISSUE_STATUS.IN_PROGRESS', 2),
    ('PMS_ISSUE_STATUS', 'RESOLVED',    'code.PMS_ISSUE_STATUS.RESOLVED',    3),
    ('PMS_ISSUE_STATUS', 'CLOSED',      'code.PMS_ISSUE_STATUS.CLOSED',      4);

CREATE TABLE pms_work_log (
    wl_id          bigint         GENERATED ALWAYS AS IDENTITY,
    comp_cd        varchar(20)    NOT NULL,
    user_id        bigint         NOT NULL,
    work_day       date           NOT NULL,
    proj_id        bigint         NOT NULL,
    task_id        bigint,
    category_cd    varchar(30),
    hours          numeric(4,1)   NOT NULL,
    entry_method   varchar(8)     NOT NULL DEFAULT 'KEY',
    status         varchar(10)    NOT NULL DEFAULT 'DRAFT',
    confirmed_at   timestamptz,
    reopen_reason  varchar(500),
    note           varchar(500),
    row_ver        integer        NOT NULL DEFAULT 0,
    created_at     timestamptz    NOT NULL DEFAULT now(),
    created_by     varchar(50)    NOT NULL,
    updated_at     timestamptz    NOT NULL DEFAULT now(),
    updated_by     varchar(50)    NOT NULL,
    CONSTRAINT pk_pms_work_log PRIMARY KEY (wl_id),
    CONSTRAINT fk_pms_work_log_user FOREIGN KEY (user_id) REFERENCES sys_user (user_id),
    CONSTRAINT fk_pms_work_log_proj FOREIGN KEY (proj_id) REFERENCES pms_project (proj_id),
    CONSTRAINT fk_pms_work_log_task FOREIGN KEY (task_id) REFERENCES pms_task (task_id),
    CONSTRAINT ck_pms_work_log_hours CHECK (hours >= 0 AND hours <= 24),
    CONSTRAINT ck_pms_work_log_day CHECK (work_day BETWEEN DATE '1950-01-01' AND DATE '2999-12-31'),
    CONSTRAINT ck_pms_work_log_method CHECK (entry_method IN ('PREFILL', 'KEY')),
    CONSTRAINT ck_pms_work_log_status CHECK (status IN ('DRAFT', 'CONFIRMED')),
    -- 닫을 때 필수(11 D13): 확정 = 분류·확정 시각·0 보다 큰 시간 (NULL 이면 CHECK 가 통과하므로 IS NOT NULL)
    CONSTRAINT ck_pms_work_log_confirmed CHECK (status = 'DRAFT'
        OR (category_cd IS NOT NULL AND confirmed_at IS NOT NULL AND hours > 0))
);
COMMENT ON TABLE pms_work_log IS '공수(시간) 기록 — 본인만 입력, 일 단위 확정, 확정 뒤는 다시 열기(사유)로만 (docs/32 D9)';
-- 같은 날 같은 프로젝트·작업·분류는 한 행(미리 채움을 두 번 눌러도 늘지 않게)
CREATE UNIQUE INDEX uq_pms_work_log ON pms_work_log (comp_cd, user_id, work_day, proj_id, coalesce(task_id, 0), coalesce(category_cd, ''));
CREATE INDEX ix_pms_work_log_proj ON pms_work_log (comp_cd, proj_id, work_day);

CREATE FUNCTION hb_guard_pms_work_log() RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    v_row    pms_work_log;
    v_status varchar(10);
    v_sum    numeric;
BEGIN
    v_row := CASE WHEN TG_OP = 'DELETE' THEN OLD ELSE NEW END;
    IF TG_OP = 'DELETE' THEN
        IF OLD.status = 'CONFIRMED' THEN
            RAISE EXCEPTION 'confirmed work log % cannot be deleted', OLD.wl_id USING ERRCODE = '55000', CONSTRAINT = 'ck_pms_work_log_fixed';
        END IF;
        RETURN OLD;
    END IF;
    IF TG_OP = 'UPDATE' THEN
        IF NEW.user_id <> OLD.user_id OR NEW.work_day <> OLD.work_day OR NEW.proj_id <> OLD.proj_id THEN
            RAISE EXCEPTION 'work log keys are fixed' USING ERRCODE = '55000', CONSTRAINT = 'ck_pms_work_log_key_fixed';
        END IF;
        -- 확정 행: "다시 열기"(→ DRAFT + 사유) 만 — 다른 칸은 그대로
        IF OLD.status = 'CONFIRMED' THEN
            IF NOT (NEW.status = 'DRAFT' AND coalesce(length(btrim(NEW.reopen_reason)), 0) > 0
                    AND NEW.hours = OLD.hours AND NEW.task_id IS NOT DISTINCT FROM OLD.task_id
                    AND NEW.category_cd IS NOT DISTINCT FROM OLD.category_cd) THEN
                RAISE EXCEPTION 'confirmed work log % is fixed', OLD.wl_id USING ERRCODE = '55000', CONSTRAINT = 'ck_pms_work_log_fixed';
            END IF;
            RETURN NEW;
        END IF;
    ELSIF NEW.status <> 'DRAFT' THEN
        RAISE EXCEPTION 'work log starts as DRAFT' USING ERRCODE = '55000', CONSTRAINT = 'ck_pms_work_log_fixed';
    END IF;
    SELECT p.status INTO v_status FROM pms_project p WHERE p.proj_id = NEW.proj_id AND p.comp_cd = NEW.comp_cd;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'project % is not of company %', NEW.proj_id, NEW.comp_cd USING ERRCODE = '23503', CONSTRAINT = 'fk_pms_work_log_proj_comp';
    END IF;
    IF TG_OP = 'INSERT' AND v_status IN ('CLOSED', 'CANCELLED') THEN
        RAISE EXCEPTION 'project % is closed', NEW.proj_id USING ERRCODE = '55000', CONSTRAINT = 'ck_pms_work_log_project_closed';
    END IF;
    IF (TG_OP = 'INSERT' OR NEW.user_id IS DISTINCT FROM OLD.user_id)
       AND NOT EXISTS (SELECT 1 FROM sys_user u WHERE u.user_id = NEW.user_id AND u.comp_cd = NEW.comp_cd) THEN
        RAISE EXCEPTION 'user % is not of company %', NEW.user_id, NEW.comp_cd USING ERRCODE = '23503', CONSTRAINT = 'fk_pms_work_log_user_comp';
    END IF;
    IF NEW.task_id IS NOT NULL AND (TG_OP = 'INSERT' OR NEW.task_id IS DISTINCT FROM OLD.task_id)
       AND NOT EXISTS (SELECT 1 FROM pms_task t WHERE t.task_id = NEW.task_id AND t.proj_id = NEW.proj_id AND t.comp_cd = NEW.comp_cd) THEN
        RAISE EXCEPTION 'task % is not in project %', NEW.task_id, NEW.proj_id USING ERRCODE = '23503', CONSTRAINT = 'fk_pms_work_log_task_proj';
    END IF;
    IF NEW.category_cd IS NOT NULL AND (TG_OP = 'INSERT' OR NEW.category_cd IS DISTINCT FROM OLD.category_cd)
       AND NOT hb_code_usable(NEW.comp_cd, 'PMS_WORK_CAT', NEW.category_cd) THEN
        RAISE EXCEPTION 'work category % is not usable', NEW.category_cd USING ERRCODE = '23503', CONSTRAINT = 'fk_pms_work_log_category';
    END IF;
    -- 하루 합계 ≤ 24h — 사용자·날짜 잠금 뒤에 세야 동시 저장 두 건이 같은 합계를 보지 않는다
    IF TG_OP = 'INSERT' OR NEW.hours <> OLD.hours THEN
        PERFORM pg_advisory_xact_lock(hashtextextended('pms_work_log:' || NEW.comp_cd || ':' || NEW.user_id || ':' || NEW.work_day, 0));
        SELECT coalesce(sum(w.hours), 0) INTO v_sum FROM pms_work_log w
         WHERE w.comp_cd = NEW.comp_cd AND w.user_id = NEW.user_id AND w.work_day = NEW.work_day AND w.wl_id <> NEW.wl_id;
        IF v_sum + NEW.hours > 24 THEN
            RAISE EXCEPTION 'more than 24 hours on %', NEW.work_day USING ERRCODE = '23514', CONSTRAINT = 'ck_pms_work_log_day_sum';
        END IF;
    END IF;
    RETURN NEW;
END
$$;
CREATE TRIGGER tg_pms_work_log_guard BEFORE INSERT OR UPDATE OR DELETE ON pms_work_log FOR EACH ROW EXECUTE FUNCTION hb_guard_pms_work_log();

CREATE TRIGGER tg_audit AFTER INSERT OR UPDATE OR DELETE ON pms_work_log FOR EACH ROW EXECUTE FUNCTION hb_audit('wl_id', '', '');
ALTER TABLE pms_work_log ENABLE ROW LEVEL SECURITY;
ALTER TABLE pms_work_log FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON pms_work_log USING (comp_cd = hb_current_comp()) WITH CHECK (comp_cd = hb_current_comp());
REVOKE TRUNCATE ON pms_work_log FROM ${app_role};

INSERT INTO sys_prog (prog_cd, prog_nm_key, view_path, module_cd, actions, sort_no) VALUES
    ('pms-issue',   'prog.pmsIssue',   'pms/issue-mgt',   'PMS', ARRAY['SEARCH','SAVE','EXCEL']::varchar(20)[], 840),
    ('pms-worklog', 'prog.pmsWorklog', 'pms/worklog',     'PMS', ARRAY['SEARCH','SAVE']::varchar(20)[], 845);
INSERT INTO sys_retention_table (table_nm, class_cd) VALUES ('pms_work_log', 'QUALITY_RECORD');
