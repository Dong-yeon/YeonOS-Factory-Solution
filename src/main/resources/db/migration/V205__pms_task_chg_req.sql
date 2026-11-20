-- =====================================================================
-- PMS 담당자 일정변경 요청 → 승인 (docs/48 6장 2·7장 R-3, docs/32 4.13) — 임시 번호(docs/29 2.2), 병합 때 이름만 바뀐다
--
-- - 정책 PMS_TASK_CHG_APPROVAL: Y(기본) = 플랫폼 결재(문서 유형 PMS_TASK_CHG, 결재선 기본 = 그 프로젝트 PM),
--   N = 결재 없이 PM(또는 APPROVE 권한)이 프로젝트 화면에서 바로 승인·반려. 요청 때의 방식이 그 요청 끝까지 간다
-- - pms_task_chg_req: 요청(잎 작업 하나, 새 계획일 + 사유 + 요청 때 작업 row_ver). 한 작업에 열린 요청은 하나.
--   승인 = 기존 "사유 있는 계획 변경" 경로 그대로(기준선 불변, 기준선 있으면 pms_task_plan_change 에 출처 REQ),
--   승인 때 작업 row_ver 가 요청 때와 다르면 409. 결정·회수 뒤 불변, 지우지 않는다
-- - pms_task_plan_change.source 에 REQ 추가
-- 표 소유 = PMS. 다른 모듈 표 변경 없음(apr_request 는 FK 로만 참조)
-- =====================================================================

INSERT INTO sys_policy_def (policy_key, value_type, default_value, allowed_values, min_value, max_value, desc_key) VALUES
    ('PMS_TASK_CHG_APPROVAL', 'BOOL', 'Y', NULL, NULL, NULL, 'policy.PMS_TASK_CHG_APPROVAL');

ALTER TABLE pms_task_plan_change DROP CONSTRAINT ck_pms_task_plan_change_source;
ALTER TABLE pms_task_plan_change ADD CONSTRAINT ck_pms_task_plan_change_source CHECK (source IN ('EDIT', 'PUSH', 'REQ'));
COMMENT ON TABLE pms_task_plan_change IS '기준선 뒤 계획일 변경 이력(폼 EDIT / 후행 밀기 PUSH / 일정변경 요청 승인 REQ) — INSERT 만';

CREATE TABLE pms_task_chg_req (
    req_id         bigint        GENERATED ALWAYS AS IDENTITY,
    comp_cd        varchar(20)   NOT NULL,
    proj_id        bigint        NOT NULL,
    task_id        bigint        NOT NULL,
    requester_id   bigint        NOT NULL,
    before_start   date          NOT NULL,
    before_end     date          NOT NULL,
    new_start      date          NOT NULL,
    new_end        date          NOT NULL,
    reason         varchar(500)  NOT NULL,
    task_row_ver   integer       NOT NULL,
    status         varchar(12)   NOT NULL,
    apr_id         bigint,
    decided_at     timestamptz,
    decided_by     varchar(50),
    decide_note    varchar(500),
    row_ver        integer       NOT NULL DEFAULT 0,
    created_at     timestamptz   NOT NULL DEFAULT now(),
    created_by     varchar(50)   NOT NULL,
    updated_at     timestamptz   NOT NULL DEFAULT now(),
    updated_by     varchar(50)   NOT NULL,
    CONSTRAINT pk_pms_task_chg_req PRIMARY KEY (req_id),
    CONSTRAINT fk_pms_task_chg_req_proj FOREIGN KEY (proj_id) REFERENCES pms_project (proj_id),
    CONSTRAINT fk_pms_task_chg_req_requester FOREIGN KEY (requester_id) REFERENCES sys_user (user_id),
    CONSTRAINT fk_pms_task_chg_req_apr FOREIGN KEY (apr_id) REFERENCES apr_request (apr_id),
    CONSTRAINT ck_pms_task_chg_req_status CHECK (status IN ('REQUESTED', 'IN_APPROVAL', 'APPROVED', 'REJECTED', 'WITHDRAWN')),
    CONSTRAINT ck_pms_task_chg_req_dates CHECK (new_start <= new_end
        AND new_start BETWEEN DATE '1950-01-01' AND DATE '2999-12-31' AND new_end BETWEEN DATE '1950-01-01' AND DATE '2999-12-31'),
    CONSTRAINT ck_pms_task_chg_req_change CHECK (new_start <> before_start OR new_end <> before_end),
    CONSTRAINT ck_pms_task_chg_req_reason CHECK (length(btrim(reason)) > 0),
    CONSTRAINT ck_pms_task_chg_req_decided CHECK (
           (status IN ('REQUESTED', 'IN_APPROVAL') AND decided_at IS NULL)
        OR (status IN ('APPROVED', 'REJECTED', 'WITHDRAWN') AND decided_at IS NOT NULL))
);
COMMENT ON TABLE pms_task_chg_req IS '담당자 일정변경 요청(잎 작업) — 결재(IN_APPROVAL) 또는 PM 바로 처리(REQUESTED) → 승인·반려·회수. 결정 뒤 불변 (docs/32 4.13)';
COMMENT ON COLUMN pms_task_chg_req.task_id IS '요청 대상 작업 — FK 를 두지 않는다(작업이 지워져도 요청 기록은 남는다, 기준선 이력과 같은 규칙). 같은 회사·프로젝트·잎 작업은 트리거가 확인';
COMMENT ON COLUMN pms_task_chg_req.task_row_ver IS '요청 때 작업 row_ver — 승인 때 다르면 409(그사이 작업이 바뀌었다)';
COMMENT ON COLUMN pms_task_chg_req.before_start IS '요청 때 계획 시작일(화면 비교용, 승인 때 이력의 변경 전 값은 그때 작업 값)';
-- 한 작업에 열린 요청은 하나
CREATE UNIQUE INDEX uq_pms_task_chg_req_open ON pms_task_chg_req (comp_cd, task_id) WHERE status IN ('REQUESTED', 'IN_APPROVAL');
CREATE INDEX ix_pms_task_chg_req_proj ON pms_task_chg_req (comp_cd, proj_id, created_at);

CREATE FUNCTION hb_guard_pms_task_chg_req() RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    v_status varchar(10);
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'change requests are not deleted' USING ERRCODE = '55000', CONSTRAINT = 'ck_pms_task_chg_req_no_delete';
    END IF;
    IF TG_OP = 'INSERT' THEN
        SELECT p.status INTO v_status FROM pms_project p WHERE p.proj_id = NEW.proj_id AND p.comp_cd = NEW.comp_cd;
        IF NOT FOUND THEN
            RAISE EXCEPTION 'project % is not of company %', NEW.proj_id, NEW.comp_cd USING ERRCODE = '23503', CONSTRAINT = 'fk_pms_task_chg_req_proj_comp';
        END IF;
        IF v_status IN ('CLOSED', 'CANCELLED') THEN
            RAISE EXCEPTION 'project % is closed', NEW.proj_id USING ERRCODE = '55000', CONSTRAINT = 'ck_pms_task_chg_req_project_closed';
        END IF;
        IF NOT EXISTS (SELECT 1 FROM pms_task t WHERE t.task_id = NEW.task_id AND t.proj_id = NEW.proj_id
                          AND t.comp_cd = NEW.comp_cd AND t.is_summary = 'N') THEN
            RAISE EXCEPTION 'task % is not a leaf task of project %', NEW.task_id, NEW.proj_id
                USING ERRCODE = '23503', CONSTRAINT = 'fk_pms_task_chg_req_task';
        END IF;
        IF NOT EXISTS (SELECT 1 FROM sys_user u WHERE u.user_id = NEW.requester_id AND u.comp_cd = NEW.comp_cd) THEN
            RAISE EXCEPTION 'requester % is not of company %', NEW.requester_id, NEW.comp_cd
                USING ERRCODE = '23503', CONSTRAINT = 'fk_pms_task_chg_req_requester_comp';
        END IF;
        IF NEW.status NOT IN ('REQUESTED', 'IN_APPROVAL') OR NEW.apr_id IS NOT NULL THEN
            RAISE EXCEPTION 'change request starts open without approval' USING ERRCODE = '55000', CONSTRAINT = 'ck_pms_task_chg_req_transition';
        END IF;
        RETURN NEW;
    END IF;
    IF NEW.proj_id <> OLD.proj_id OR NEW.task_id <> OLD.task_id OR NEW.requester_id <> OLD.requester_id
       OR NEW.before_start <> OLD.before_start OR NEW.before_end <> OLD.before_end
       OR NEW.new_start <> OLD.new_start OR NEW.new_end <> OLD.new_end OR NEW.reason <> OLD.reason
       OR NEW.task_row_ver <> OLD.task_row_ver THEN
        RAISE EXCEPTION 'change request content is fixed' USING ERRCODE = '55000', CONSTRAINT = 'ck_pms_task_chg_req_fixed';
    END IF;
    IF OLD.status IN ('APPROVED', 'REJECTED', 'WITHDRAWN') THEN
        RAISE EXCEPTION 'change request % is final', OLD.req_id USING ERRCODE = '55000', CONSTRAINT = 'ck_pms_task_chg_req_final';
    END IF;
    -- 결재 id 는 결재 중 요청에 한 번만, 결재 중 ↔ 바로 처리는 서로 바뀌지 않는다
    IF NEW.apr_id IS DISTINCT FROM OLD.apr_id AND (OLD.apr_id IS NOT NULL OR OLD.status <> 'IN_APPROVAL') THEN
        RAISE EXCEPTION 'approval of change request % is fixed', OLD.req_id USING ERRCODE = '55000', CONSTRAINT = 'ck_pms_task_chg_req_apr';
    END IF;
    IF NEW.status <> OLD.status AND NEW.status NOT IN ('APPROVED', 'REJECTED', 'WITHDRAWN') THEN
        RAISE EXCEPTION 'change request status % -> % is not allowed', OLD.status, NEW.status
            USING ERRCODE = '55000', CONSTRAINT = 'ck_pms_task_chg_req_transition';
    END IF;
    RETURN NEW;
END
$$;
CREATE TRIGGER tg_pms_task_chg_req_guard BEFORE INSERT OR UPDATE OR DELETE ON pms_task_chg_req
    FOR EACH ROW EXECUTE FUNCTION hb_guard_pms_task_chg_req();

CREATE TRIGGER tg_audit AFTER INSERT OR UPDATE OR DELETE ON pms_task_chg_req FOR EACH ROW EXECUTE FUNCTION hb_audit('req_id', '', '');

ALTER TABLE pms_task_chg_req ENABLE ROW LEVEL SECURITY;
ALTER TABLE pms_task_chg_req FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON pms_task_chg_req USING (comp_cd = hb_current_comp()) WITH CHECK (comp_cd = hb_current_comp());
REVOKE DELETE, TRUNCATE ON pms_task_chg_req FROM ${app_role};
INSERT INTO sys_retention_table (table_nm, class_cd) VALUES ('pms_task_chg_req', 'QUALITY_RECORD');
