-- =====================================================================
-- PMS 3단계 — 산출물·게이트 심사(launch readiness) (docs/32 PM3, 4.5, 결정 D5·D6·D11)
-- 임시 번호(docs/29 2.2) — 상위 세션이 병합 때 파일 이름만 바꾼다
--
-- - pms_template_item: 템플릿 작업의 산출물 칸(DELIV)·게이트 점검 문구(CHECK) — 프로젝트를 만들 때 복사
-- - pms_task_check: 프로젝트 게이트 작업의 점검 문구(템플릿에서 복사) — 심사를 만들 때 점검표로 복사
-- - pms_deliverable: 작업 × 산출물 칸(필수, 상태 NONE→SUBMITTED→APPROVED/REJECTED, 면제 WAIVED). 연결 칸 ref_type(지금 FILE 만) + ref_id
-- - pms_deliverable_hist: 제출·승인·반려·면제 이력(INSERT 만). 제출마다 새 파일 — 제출한 파일은 지울 수 없다(sys_file 트리거)
-- - pms_gate_review / pms_gate_check / pms_gate_cond: 게이트 심사 + 점검표 + 조건부 통과 조건. 결정 뒤 불변
-- - pms_issue: 이슈(PM4 화면). PM3 에서는 조건부 통과 조건이 이슈로 자동 생성되는 곳
-- 남의 표: sys_file 에 BEFORE UPDATE 트리거 1개(대상 pms-deliverable 이고 제출 이력에 있는 파일의 use_yn → N 거부)
-- =====================================================================

INSERT INTO sys_policy_def (policy_key, value_type, default_value, allowed_values, min_value, max_value, desc_key) VALUES
    -- 게이트 통과 전 뒤 단계 작업 착수: OFF 검사 없음 / WARN 경고 + 화면 표시(기록은 착수일·통과일 자체) / BLOCK 거부 (D11)
    ('PMS_GATE_ENFORCE', 'ENUM', 'WARN', ARRAY['OFF','WARN','BLOCK'], NULL, NULL, 'policy.PMS_GATE_ENFORCE'),
    -- 게이트 결정 결재(Y) / 결재 없이 APPROVE 권한으로 바로 결정(N)
    ('PMS_GATE_APPROVAL', 'BOOL', 'Y', NULL, NULL, NULL, 'policy.PMS_GATE_APPROVAL'),
    -- 기한 임박(산출물·이슈·홈 카드) 일수
    ('PMS_DUE_SOON_DAYS', 'INT', '7', NULL, 0, 60, 'policy.PMS_DUE_SOON_DAYS');

INSERT INTO sys_code_grp (grp_cd, grp_nm_key, comp_ext_yn) VALUES
    ('PMS_DELIV_STATUS', 'code.grp.PMS_DELIV_STATUS', 'N');
INSERT INTO sys_code (grp_cd, code, code_nm_key, sort_no) VALUES
    ('PMS_DELIV_STATUS', 'NONE',      'code.PMS_DELIV_STATUS.NONE',      1),
    ('PMS_DELIV_STATUS', 'SUBMITTED', 'code.PMS_DELIV_STATUS.SUBMITTED', 2),
    ('PMS_DELIV_STATUS', 'APPROVED',  'code.PMS_DELIV_STATUS.APPROVED',  3),
    ('PMS_DELIV_STATUS', 'REJECTED',  'code.PMS_DELIV_STATUS.REJECTED',  4),
    ('PMS_DELIV_STATUS', 'WAIVED',    'code.PMS_DELIV_STATUS.WAIVED',    5);

-- ---------------------------------------------------------------------
-- 1. 템플릿 산출물 칸 · 점검 문구
-- ---------------------------------------------------------------------
CREATE TABLE pms_template_item (
    ti_id        bigint        GENERATED ALWAYS AS IDENTITY,
    comp_cd      varchar(20)   NOT NULL,
    tmpl_id      bigint        NOT NULL,
    tt_id        bigint        NOT NULL,
    kind         varchar(5)    NOT NULL,
    seq          integer       NOT NULL,
    item_nm      varchar(200)  NOT NULL,
    required_yn  char(1)       NOT NULL DEFAULT 'Y',
    created_at   timestamptz   NOT NULL DEFAULT now(),
    created_by   varchar(50)   NOT NULL,
    CONSTRAINT pk_pms_template_item PRIMARY KEY (ti_id),
    CONSTRAINT fk_pms_template_item_tmpl FOREIGN KEY (tmpl_id) REFERENCES pms_template (tmpl_id),
    CONSTRAINT fk_pms_template_item_tt FOREIGN KEY (tt_id) REFERENCES pms_template_task (tt_id),
    CONSTRAINT uq_pms_template_item UNIQUE (comp_cd, tt_id, kind, seq),
    CONSTRAINT ck_pms_template_item_kind CHECK (kind IN ('DELIV', 'CHECK')),
    CONSTRAINT ck_pms_template_item_nm CHECK (length(btrim(item_nm)) > 0),
    CONSTRAINT ck_pms_template_item_req CHECK (required_yn IN ('Y', 'N'))
);
COMMENT ON TABLE pms_template_item IS '템플릿 작업의 산출물 칸(DELIV)·게이트 점검 문구(CHECK, 게이트 작업만). 추가·삭제만 — 이미 만든 프로젝트에는 반영하지 않는다';
CREATE INDEX ix_pms_template_item ON pms_template_item (comp_cd, tmpl_id);

CREATE FUNCTION hb_guard_pms_template_item() RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    v_type varchar(10);
BEGIN
    IF TG_OP = 'UPDATE' THEN
        RAISE EXCEPTION 'template items are insert/delete only' USING ERRCODE = '55000', CONSTRAINT = 'ck_pms_template_item_fixed';
    END IF;
    IF TG_OP = 'DELETE' THEN
        RETURN OLD;
    END IF;
    SELECT k.task_type INTO v_type FROM pms_template_task k
     WHERE k.tt_id = NEW.tt_id AND k.tmpl_id = NEW.tmpl_id AND k.comp_cd = NEW.comp_cd;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'template task % is not in template %', NEW.tt_id, NEW.tmpl_id
            USING ERRCODE = '23503', CONSTRAINT = 'fk_pms_template_item_tt_comp';
    END IF;
    IF NEW.kind = 'CHECK' AND v_type <> 'GATE' THEN
        RAISE EXCEPTION 'check items belong to gate tasks' USING ERRCODE = '23514', CONSTRAINT = 'ck_pms_template_item_gate';
    END IF;
    RETURN NEW;
END
$$;
CREATE TRIGGER tg_pms_template_item_guard BEFORE INSERT OR UPDATE OR DELETE ON pms_template_item
    FOR EACH ROW EXECUTE FUNCTION hb_guard_pms_template_item();

-- 점검 문구가 달린 템플릿 작업은 게이트가 아닌 종류로 바꿀 수 없다(서비스가 먼저 칸 오류로)
CREATE FUNCTION hb_guard_pms_template_task_items() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.task_type <> 'GATE' AND OLD.task_type = 'GATE'
       AND EXISTS (SELECT 1 FROM pms_template_item i WHERE i.tt_id = NEW.tt_id AND i.kind = 'CHECK') THEN
        RAISE EXCEPTION 'template task % has gate check items', NEW.tt_id USING ERRCODE = '23514', CONSTRAINT = 'ck_pms_template_task_has_checks';
    END IF;
    RETURN NEW;
END
$$;
CREATE TRIGGER tg_pms_template_task_items BEFORE UPDATE OF task_type ON pms_template_task
    FOR EACH ROW EXECUTE FUNCTION hb_guard_pms_template_task_items();

-- ---------------------------------------------------------------------
-- 2. 프로젝트 게이트 점검 문구 (템플릿 복사본)
-- ---------------------------------------------------------------------
CREATE TABLE pms_task_check (
    tc_id       bigint        GENERATED ALWAYS AS IDENTITY,
    comp_cd     varchar(20)   NOT NULL,
    proj_id     bigint        NOT NULL,
    task_id     bigint        NOT NULL,
    seq         integer       NOT NULL,
    check_nm    varchar(200)  NOT NULL,
    created_at  timestamptz   NOT NULL DEFAULT now(),
    created_by  varchar(50)   NOT NULL,
    CONSTRAINT pk_pms_task_check PRIMARY KEY (tc_id),
    CONSTRAINT fk_pms_task_check_proj FOREIGN KEY (proj_id) REFERENCES pms_project (proj_id),
    CONSTRAINT fk_pms_task_check_task FOREIGN KEY (task_id) REFERENCES pms_task (task_id),
    CONSTRAINT uq_pms_task_check UNIQUE (comp_cd, task_id, seq),
    CONSTRAINT ck_pms_task_check_nm CHECK (length(btrim(check_nm)) > 0)
);
COMMENT ON TABLE pms_task_check IS '게이트 작업의 점검 문구(템플릿에서 복사). 심사를 만들 때 pms_gate_check 로 다시 복사되고, 심사 안에서 고쳐 쓴다';

-- ---------------------------------------------------------------------
-- 3. 산출물
-- ---------------------------------------------------------------------
CREATE TABLE pms_deliverable (
    deliv_id       bigint        GENERATED ALWAYS AS IDENTITY,
    comp_cd        varchar(20)   NOT NULL,
    proj_id        bigint        NOT NULL,
    task_id        bigint        NOT NULL,
    seq            integer       NOT NULL,
    deliv_nm       varchar(200)  NOT NULL,
    required_yn    char(1)       NOT NULL DEFAULT 'Y',
    owner_user_id  bigint,
    due_date       date,
    status         varchar(10)   NOT NULL DEFAULT 'NONE',
    ref_type       varchar(12),
    ref_id         bigint,
    waive_reason   varchar(500),
    decision_note  varchar(500),
    decided_by     varchar(50),
    decided_at     timestamptz,
    submitted_by   varchar(50),
    submitted_at   timestamptz,
    sub_cnt        integer       NOT NULL DEFAULT 0,
    row_ver        integer       NOT NULL DEFAULT 0,
    created_at     timestamptz   NOT NULL DEFAULT now(),
    created_by     varchar(50)   NOT NULL,
    updated_at     timestamptz   NOT NULL DEFAULT now(),
    updated_by     varchar(50)   NOT NULL,
    CONSTRAINT pk_pms_deliverable PRIMARY KEY (deliv_id),
    CONSTRAINT fk_pms_deliverable_proj FOREIGN KEY (proj_id) REFERENCES pms_project (proj_id),
    CONSTRAINT fk_pms_deliverable_task FOREIGN KEY (task_id) REFERENCES pms_task (task_id),
    CONSTRAINT fk_pms_deliverable_owner FOREIGN KEY (owner_user_id) REFERENCES sys_user (user_id),
    CONSTRAINT uq_pms_deliverable UNIQUE (comp_cd, task_id, seq),
    CONSTRAINT ck_pms_deliverable_nm CHECK (length(btrim(deliv_nm)) > 0),
    CONSTRAINT ck_pms_deliverable_req CHECK (required_yn IN ('Y', 'N')),
    CONSTRAINT ck_pms_deliverable_status CHECK (status IN ('NONE', 'SUBMITTED', 'APPROVED', 'REJECTED', 'WAIVED')),
    -- 연결 칸: 지금은 첨부 파일만(D6). QMS_DOC_REV·PPAP_PKG 는 그 모듈이 main 에 들어온 뒤 새 V 파일로 추가
    CONSTRAINT ck_pms_deliverable_ref_type CHECK (ref_type IS NULL OR ref_type IN ('FILE')),
    CONSTRAINT ck_pms_deliverable_ref CHECK ((ref_type IS NULL) = (ref_id IS NULL)),
    CONSTRAINT ck_pms_deliverable_due CHECK (due_date IS NULL OR due_date BETWEEN DATE '1950-01-01' AND DATE '2999-12-31'),
    -- 상태와 칸이 맞아야: 제출·승인·반려는 제출물이 있다, 시작 전은 없다, 면제는 사유(NULL 이면 CHECK 가 통과하므로 coalesce)
    CONSTRAINT ck_pms_deliverable_state CHECK (
           (status = 'NONE' AND ref_id IS NULL)
        OR (status IN ('SUBMITTED', 'APPROVED', 'REJECTED') AND ref_id IS NOT NULL AND submitted_at IS NOT NULL)
        OR (status = 'WAIVED' AND coalesce(length(btrim(waive_reason)), 0) > 0)),
    CONSTRAINT ck_pms_deliverable_decided CHECK (status NOT IN ('APPROVED', 'REJECTED', 'WAIVED') OR decided_at IS NOT NULL)
);
COMMENT ON TABLE pms_deliverable IS '산출물 칸(작업 × 산출물). due_date NULL = 작업 계획 종료일. 제출마다 새 파일, 승인본 파일은 바꾸지 않는다(새 제출) (docs/32 PM3)';
CREATE INDEX ix_pms_deliverable_proj ON pms_deliverable (comp_cd, proj_id);
CREATE INDEX ix_pms_deliverable_owner ON pms_deliverable (comp_cd, owner_user_id, status);

CREATE FUNCTION hb_guard_pms_deliverable() RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    v_row    pms_deliverable;
    v_status varchar(10);
BEGIN
    v_row := CASE WHEN TG_OP = 'DELETE' THEN OLD ELSE NEW END;
    SELECT p.status INTO v_status FROM pms_project p WHERE p.proj_id = v_row.proj_id AND p.comp_cd = v_row.comp_cd;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'project % is not of company %', v_row.proj_id, v_row.comp_cd USING ERRCODE = '23503', CONSTRAINT = 'fk_pms_deliverable_proj_comp';
    END IF;
    IF v_status IN ('CLOSED', 'CANCELLED') THEN
        RAISE EXCEPTION 'project % is closed', v_row.proj_id USING ERRCODE = '55000', CONSTRAINT = 'ck_pms_deliverable_project_closed';
    END IF;
    IF TG_OP = 'DELETE' THEN
        -- 한 번이라도 제출·면제된 칸은 지우지 않는다(이력이 가리킨다) — 필요 없으면 필수 아님으로
        IF OLD.status <> 'NONE' OR OLD.sub_cnt > 0 OR EXISTS (SELECT 1 FROM pms_deliverable_hist h WHERE h.deliv_id = OLD.deliv_id) THEN
            RAISE EXCEPTION 'deliverable % has history', OLD.deliv_id USING ERRCODE = '55000', CONSTRAINT = 'ck_pms_deliverable_delete';
        END IF;
        RETURN OLD;
    END IF;
    IF TG_OP = 'INSERT' THEN
        IF NOT EXISTS (SELECT 1 FROM pms_task t WHERE t.task_id = NEW.task_id AND t.proj_id = NEW.proj_id AND t.comp_cd = NEW.comp_cd) THEN
            RAISE EXCEPTION 'task % is not in project %', NEW.task_id, NEW.proj_id USING ERRCODE = '23503', CONSTRAINT = 'fk_pms_deliverable_task_proj';
        END IF;
        IF NEW.status <> 'NONE' OR NEW.ref_id IS NOT NULL THEN
            RAISE EXCEPTION 'deliverable starts as NONE' USING ERRCODE = '55000', CONSTRAINT = 'ck_pms_deliverable_transition';
        END IF;
    ELSE
        IF NEW.task_id <> OLD.task_id OR NEW.proj_id <> OLD.proj_id THEN
            RAISE EXCEPTION 'deliverable cannot move to another task' USING ERRCODE = '55000', CONSTRAINT = 'ck_pms_deliverable_task_fixed';
        END IF;
        IF NEW.status <> OLD.status AND NOT (
               (OLD.status = 'NONE'      AND NEW.status IN ('SUBMITTED', 'WAIVED'))
            OR (OLD.status = 'SUBMITTED' AND NEW.status IN ('APPROVED', 'REJECTED', 'WAIVED'))
            OR (OLD.status = 'REJECTED'  AND NEW.status IN ('SUBMITTED', 'WAIVED'))
            OR (OLD.status = 'APPROVED'  AND NEW.status = 'SUBMITTED')
            OR (OLD.status = 'WAIVED'    AND NEW.status IN ('NONE', 'SUBMITTED'))) THEN
            RAISE EXCEPTION 'deliverable status % -> % is not allowed', OLD.status, NEW.status
                USING ERRCODE = '55000', CONSTRAINT = 'ck_pms_deliverable_transition';
        END IF;
        -- 제출물(파일)은 "새 제출" 로만 바뀐다 — 승인본 파일을 그 자리에서 바꾸지 못한다. 제출은 늘 새 파일
        IF NEW.ref_id IS DISTINCT FROM OLD.ref_id AND NEW.status <> 'SUBMITTED' THEN
            RAISE EXCEPTION 'deliverable file changes only by a new submission' USING ERRCODE = '55000', CONSTRAINT = 'ck_pms_deliverable_ref_fixed';
        END IF;
        IF NEW.status = 'SUBMITTED' AND (OLD.status <> 'SUBMITTED' OR NEW.sub_cnt <> OLD.sub_cnt)
           AND NEW.ref_id IS NOT DISTINCT FROM OLD.ref_id THEN
            RAISE EXCEPTION 'a submission needs a new file' USING ERRCODE = '55000', CONSTRAINT = 'ck_pms_deliverable_new_file';
        END IF;
    END IF;
    IF NEW.owner_user_id IS NOT NULL AND (TG_OP = 'INSERT' OR NEW.owner_user_id IS DISTINCT FROM OLD.owner_user_id)
       AND NOT EXISTS (SELECT 1 FROM sys_user u WHERE u.user_id = NEW.owner_user_id AND u.comp_cd = NEW.comp_cd) THEN
        RAISE EXCEPTION 'user % is not of company %', NEW.owner_user_id, NEW.comp_cd USING ERRCODE = '23503', CONSTRAINT = 'fk_pms_deliverable_owner_comp';
    END IF;
    -- 연결 파일 = 같은 회사, 이 산출물에 올린 사용 중 첨부(다른 산출물·다른 화면 파일 금지)
    IF NEW.ref_type = 'FILE' AND (TG_OP = 'INSERT' OR NEW.ref_id IS DISTINCT FROM OLD.ref_id)
       AND NOT EXISTS (SELECT 1 FROM sys_file f WHERE f.file_id = NEW.ref_id AND f.comp_cd = NEW.comp_cd
                          AND f.target_type = 'pms-deliverable' AND f.target_id = 'D' || NEW.deliv_id AND f.use_yn = 'Y') THEN
        RAISE EXCEPTION 'file % is not an attachment of deliverable %', NEW.ref_id, NEW.deliv_id
            USING ERRCODE = '23503', CONSTRAINT = 'fk_pms_deliverable_file';
    END IF;
    RETURN NEW;
END
$$;
CREATE TRIGGER tg_pms_deliverable_guard BEFORE INSERT OR UPDATE OR DELETE ON pms_deliverable
    FOR EACH ROW EXECUTE FUNCTION hb_guard_pms_deliverable();

CREATE TABLE pms_deliverable_hist (
    dh_id       bigint        GENERATED ALWAYS AS IDENTITY,
    comp_cd     varchar(20)   NOT NULL,
    proj_id     bigint        NOT NULL,
    deliv_id    bigint        NOT NULL,
    action      varchar(10)   NOT NULL,
    file_id     bigint,
    note        varchar(500),
    created_at  timestamptz   NOT NULL DEFAULT now(),
    created_by  varchar(50)   NOT NULL,
    CONSTRAINT pk_pms_deliverable_hist PRIMARY KEY (dh_id),
    CONSTRAINT fk_pms_deliverable_hist_deliv FOREIGN KEY (deliv_id) REFERENCES pms_deliverable (deliv_id),
    CONSTRAINT ck_pms_deliverable_hist_action CHECK (action IN ('SUBMIT', 'APPROVE', 'REJECT', 'WAIVE', 'UNWAIVE')),
    CONSTRAINT ck_pms_deliverable_hist_file CHECK ((action = 'SUBMIT') = (file_id IS NOT NULL))
);
COMMENT ON TABLE pms_deliverable_hist IS '산출물 제출·결정 이력 — INSERT 만. SUBMIT 행의 파일은 지울 수 없다(tg_sys_file_pms_deliverable)';
CREATE INDEX ix_pms_deliverable_hist ON pms_deliverable_hist (comp_cd, deliv_id);
-- 같은 파일로 두 번 제출 금지("제출마다 새 파일")
CREATE UNIQUE INDEX uq_pms_deliverable_hist_file ON pms_deliverable_hist (comp_cd, file_id) WHERE file_id IS NOT NULL;

CREATE FUNCTION hb_guard_pms_deliverable_hist() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP <> 'INSERT' THEN
        RAISE EXCEPTION 'deliverable history is insert only' USING ERRCODE = '55000', CONSTRAINT = 'ck_pms_deliverable_hist_insert_only';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pms_deliverable d WHERE d.deliv_id = NEW.deliv_id AND d.proj_id = NEW.proj_id AND d.comp_cd = NEW.comp_cd) THEN
        RAISE EXCEPTION 'deliverable % is not of company %', NEW.deliv_id, NEW.comp_cd USING ERRCODE = '23503', CONSTRAINT = 'fk_pms_deliverable_hist_comp';
    END IF;
    RETURN NEW;
END
$$;
CREATE TRIGGER tg_pms_deliverable_hist_guard BEFORE INSERT OR UPDATE OR DELETE ON pms_deliverable_hist
    FOR EACH ROW EXECUTE FUNCTION hb_guard_pms_deliverable_hist();

-- 남의 표(sys_file): 제출한 산출물 파일은 첨부 API 로 지울 수 없다 — 승인 근거 파일이 사라지지 않게
CREATE FUNCTION hb_guard_sys_file_pms_deliverable() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.use_yn = 'N' AND OLD.use_yn = 'Y'
       AND EXISTS (SELECT 1 FROM pms_deliverable_hist h WHERE h.file_id = OLD.file_id AND h.comp_cd = OLD.comp_cd) THEN
        RAISE EXCEPTION 'file % is a submitted deliverable', OLD.file_id USING ERRCODE = '55000', CONSTRAINT = 'ck_pms_deliverable_file_fixed';
    END IF;
    RETURN NEW;
END
$$;
CREATE TRIGGER tg_sys_file_pms_deliverable BEFORE UPDATE ON sys_file
    FOR EACH ROW WHEN (OLD.target_type = 'pms-deliverable') EXECUTE FUNCTION hb_guard_sys_file_pms_deliverable();

-- ---------------------------------------------------------------------
-- 4. 게이트 심사
-- ---------------------------------------------------------------------
CREATE TABLE pms_gate_review (
    review_id     bigint        GENERATED ALWAYS AS IDENTITY,
    comp_cd       varchar(20)   NOT NULL,
    proj_id       bigint        NOT NULL,
    gate_task_id  bigint        NOT NULL,
    review_no     integer       NOT NULL,
    review_date   date          NOT NULL,
    decision      varchar(12),
    status        varchar(12)   NOT NULL DEFAULT 'DRAFT',
    summary       varchar(2000),
    req_cnt       integer,
    ready_cnt     integer,
    apr_id        bigint,
    decided_at    timestamptz,
    decided_by    varchar(50),
    row_ver       integer       NOT NULL DEFAULT 0,
    created_at    timestamptz   NOT NULL DEFAULT now(),
    created_by    varchar(50)   NOT NULL,
    updated_at    timestamptz   NOT NULL DEFAULT now(),
    updated_by    varchar(50)   NOT NULL,
    CONSTRAINT pk_pms_gate_review PRIMARY KEY (review_id),
    CONSTRAINT fk_pms_gate_review_proj FOREIGN KEY (proj_id) REFERENCES pms_project (proj_id),
    CONSTRAINT fk_pms_gate_review_task FOREIGN KEY (gate_task_id) REFERENCES pms_task (task_id),
    CONSTRAINT fk_pms_gate_review_apr FOREIGN KEY (apr_id) REFERENCES apr_request (apr_id),
    CONSTRAINT uq_pms_gate_review_no UNIQUE (comp_cd, gate_task_id, review_no),
    CONSTRAINT ck_pms_gate_review_no CHECK (review_no > 0),
    CONSTRAINT ck_pms_gate_review_status CHECK (status IN ('DRAFT', 'IN_APPROVAL', 'DECIDED', 'CANCELLED')),
    CONSTRAINT ck_pms_gate_review_decision CHECK (decision IS NULL OR decision IN ('GO', 'CONDITIONAL', 'NO_GO')),
    CONSTRAINT ck_pms_gate_review_date CHECK (review_date BETWEEN DATE '1950-01-01' AND DATE '2999-12-31'),
    -- 결재 중·결정은 결정값과 상신 때 준비율이 있어야. 결정은 결정 시각
    CONSTRAINT ck_pms_gate_review_state CHECK (
           status IN ('DRAFT', 'CANCELLED')
        OR (status = 'IN_APPROVAL' AND decision IS NOT NULL AND req_cnt IS NOT NULL)
        OR (status = 'DECIDED' AND decision IS NOT NULL AND req_cnt IS NOT NULL AND decided_at IS NOT NULL))
);
COMMENT ON TABLE pms_gate_review IS '게이트 심사(게이트 작업마다 번호 1·2·3…). 결정(DECIDED)·취소 뒤 불변. GO·CONDITIONAL = 게이트 작업 완료 (docs/32 PM3)';
-- 한 게이트에 열린(작성 중·결재 중) 심사는 하나
CREATE UNIQUE INDEX uq_pms_gate_review_open ON pms_gate_review (comp_cd, gate_task_id) WHERE status IN ('DRAFT', 'IN_APPROVAL');
CREATE INDEX ix_pms_gate_review_proj ON pms_gate_review (comp_cd, proj_id);

CREATE FUNCTION hb_guard_pms_gate_review() RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    v_status varchar(10);
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'gate reviews are not deleted (cancel instead)' USING ERRCODE = '55000', CONSTRAINT = 'ck_pms_gate_review_no_delete';
    END IF;
    IF TG_OP = 'INSERT' THEN
        SELECT p.status INTO v_status FROM pms_project p WHERE p.proj_id = NEW.proj_id AND p.comp_cd = NEW.comp_cd;
        IF NOT FOUND THEN
            RAISE EXCEPTION 'project % is not of company %', NEW.proj_id, NEW.comp_cd USING ERRCODE = '23503', CONSTRAINT = 'fk_pms_gate_review_proj_comp';
        END IF;
        IF v_status IN ('CLOSED', 'CANCELLED') THEN
            RAISE EXCEPTION 'project % is closed', NEW.proj_id USING ERRCODE = '55000', CONSTRAINT = 'ck_pms_gate_review_project_closed';
        END IF;
        IF NOT EXISTS (SELECT 1 FROM pms_task t WHERE t.task_id = NEW.gate_task_id AND t.proj_id = NEW.proj_id
                          AND t.comp_cd = NEW.comp_cd AND t.task_type = 'GATE') THEN
            RAISE EXCEPTION 'task % is not a gate of project %', NEW.gate_task_id, NEW.proj_id
                USING ERRCODE = '23503', CONSTRAINT = 'fk_pms_gate_review_gate';
        END IF;
        IF NEW.status <> 'DRAFT' THEN
            RAISE EXCEPTION 'gate review starts as DRAFT' USING ERRCODE = '55000', CONSTRAINT = 'ck_pms_gate_review_transition';
        END IF;
        RETURN NEW;
    END IF;
    IF NEW.proj_id <> OLD.proj_id OR NEW.gate_task_id <> OLD.gate_task_id OR NEW.review_no <> OLD.review_no THEN
        RAISE EXCEPTION 'gate review keys are fixed' USING ERRCODE = '55000', CONSTRAINT = 'ck_pms_gate_review_key_fixed';
    END IF;
    IF OLD.status IN ('DECIDED', 'CANCELLED') THEN
        RAISE EXCEPTION 'gate review % is final', OLD.review_id USING ERRCODE = '55000', CONSTRAINT = 'ck_pms_gate_review_final';
    END IF;
    IF NEW.status <> OLD.status AND NOT (
           (OLD.status = 'DRAFT'       AND NEW.status IN ('IN_APPROVAL', 'DECIDED', 'CANCELLED'))
        OR (OLD.status = 'IN_APPROVAL' AND NEW.status IN ('DRAFT', 'DECIDED', 'CANCELLED'))) THEN
        RAISE EXCEPTION 'gate review status % -> % is not allowed', OLD.status, NEW.status
            USING ERRCODE = '55000', CONSTRAINT = 'ck_pms_gate_review_transition';
    END IF;
    -- 결재 중에는 내용(결정·요약·날짜)을 바꾸지 않는다 — 결재자가 본 내용 그대로 결정된다
    IF OLD.status = 'IN_APPROVAL' AND NEW.status IN ('IN_APPROVAL', 'DECIDED')
       AND (NEW.decision IS DISTINCT FROM OLD.decision OR NEW.summary IS DISTINCT FROM OLD.summary
            OR NEW.review_date <> OLD.review_date) THEN
        RAISE EXCEPTION 'gate review % is in approval', OLD.review_id USING ERRCODE = '55000', CONSTRAINT = 'ck_pms_gate_review_in_approval';
    END IF;
    RETURN NEW;
END
$$;
CREATE TRIGGER tg_pms_gate_review_guard BEFORE INSERT OR UPDATE OR DELETE ON pms_gate_review
    FOR EACH ROW EXECUTE FUNCTION hb_guard_pms_gate_review();

CREATE TABLE pms_gate_check (
    gc_id      bigint        GENERATED ALWAYS AS IDENTITY,
    comp_cd    varchar(20)   NOT NULL,
    review_id  bigint        NOT NULL,
    seq        integer       NOT NULL,
    check_nm   varchar(200)  NOT NULL,
    result     varchar(2),
    note       varchar(500),
    CONSTRAINT pk_pms_gate_check PRIMARY KEY (gc_id),
    CONSTRAINT fk_pms_gate_check_review FOREIGN KEY (review_id) REFERENCES pms_gate_review (review_id),
    CONSTRAINT uq_pms_gate_check UNIQUE (comp_cd, review_id, seq),
    CONSTRAINT ck_pms_gate_check_nm CHECK (length(btrim(check_nm)) > 0),
    CONSTRAINT ck_pms_gate_check_result CHECK (result IS NULL OR result IN ('OK', 'NG', 'NA'))
);
COMMENT ON TABLE pms_gate_check IS '게이트 심사 점검표 — 작성 중 심사에서만 바뀐다';

CREATE TABLE pms_gate_cond (
    cond_id        bigint        GENERATED ALWAYS AS IDENTITY,
    comp_cd        varchar(20)   NOT NULL,
    review_id      bigint        NOT NULL,
    seq            integer       NOT NULL,
    title          varchar(200)  NOT NULL,
    owner_user_id  bigint,
    due_date       date,
    issue_id       bigint,
    CONSTRAINT pk_pms_gate_cond PRIMARY KEY (cond_id),
    CONSTRAINT fk_pms_gate_cond_review FOREIGN KEY (review_id) REFERENCES pms_gate_review (review_id),
    CONSTRAINT fk_pms_gate_cond_owner FOREIGN KEY (owner_user_id) REFERENCES sys_user (user_id),
    CONSTRAINT uq_pms_gate_cond UNIQUE (comp_cd, review_id, seq),
    CONSTRAINT ck_pms_gate_cond_title CHECK (length(btrim(title)) > 0),
    CONSTRAINT ck_pms_gate_cond_due CHECK (due_date IS NULL OR due_date BETWEEN DATE '1950-01-01' AND DATE '2999-12-31')
);
COMMENT ON TABLE pms_gate_cond IS '조건부 통과(CONDITIONAL)의 조건 — 결정될 때 이슈(pms_issue)로 만들어지고 issue_id 로 이어진다';

-- 점검표·조건은 작성 중 심사에서만. 조건의 issue_id 는 결정 처리(작성 중·결재 중 → 결정 직전)에서 한 번만 채운다
CREATE FUNCTION hb_guard_pms_gate_child() RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    v_review bigint;
    v_comp   varchar(20);
    v_status varchar(12);
BEGIN
    IF TG_OP = 'DELETE' THEN
        v_review := OLD.review_id; v_comp := OLD.comp_cd;
    ELSE
        v_review := NEW.review_id; v_comp := NEW.comp_cd;
        IF TG_OP = 'UPDATE' AND NEW.review_id <> OLD.review_id THEN
            RAISE EXCEPTION 'gate review rows cannot move' USING ERRCODE = '55000', CONSTRAINT = 'ck_pms_gate_child_fixed';
        END IF;
    END IF;
    SELECT r.status INTO v_status FROM pms_gate_review r WHERE r.review_id = v_review AND r.comp_cd = v_comp;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'gate review % is not of company %', v_review, v_comp USING ERRCODE = '23503', CONSTRAINT = 'fk_pms_gate_child_comp';
    END IF;
    -- 두 표가 같은 함수를 쓰므로 표마다 다른 칸은 jsonb 로 읽는다(점검표 행에는 issue_id 가 없다)
    IF TG_TABLE_NAME = 'pms_gate_cond' AND TG_OP = 'UPDATE' THEN
        IF v_status IN ('DRAFT', 'IN_APPROVAL') AND to_jsonb(OLD) ->> 'issue_id' IS NULL AND to_jsonb(NEW) ->> 'issue_id' IS NOT NULL
           AND (to_jsonb(NEW) - 'issue_id') = (to_jsonb(OLD) - 'issue_id') THEN
            RETURN NEW;
        END IF;
    END IF;
    IF v_status <> 'DRAFT' THEN
        RAISE EXCEPTION 'gate review % is not a draft', v_review USING ERRCODE = '55000', CONSTRAINT = 'ck_pms_gate_child_draft';
    END IF;
    IF TG_TABLE_NAME = 'pms_gate_cond' AND TG_OP <> 'DELETE' THEN
        IF to_jsonb(NEW) ->> 'issue_id' IS NOT NULL THEN
            RAISE EXCEPTION 'condition issue is set by the decision' USING ERRCODE = '55000', CONSTRAINT = 'ck_pms_gate_cond_issue';
        END IF;
        IF to_jsonb(NEW) ->> 'owner_user_id' IS NOT NULL AND NOT EXISTS (SELECT 1 FROM sys_user u
                WHERE u.user_id = (to_jsonb(NEW) ->> 'owner_user_id')::bigint AND u.comp_cd = NEW.comp_cd) THEN
            RAISE EXCEPTION 'user % is not of company %', to_jsonb(NEW) ->> 'owner_user_id', NEW.comp_cd USING ERRCODE = '23503', CONSTRAINT = 'fk_pms_gate_cond_owner_comp';
        END IF;
    END IF;
    RETURN CASE WHEN TG_OP = 'DELETE' THEN OLD ELSE NEW END;
END
$$;
CREATE TRIGGER tg_pms_gate_check_guard BEFORE INSERT OR UPDATE OR DELETE ON pms_gate_check
    FOR EACH ROW EXECUTE FUNCTION hb_guard_pms_gate_child();
CREATE TRIGGER tg_pms_gate_cond_guard BEFORE INSERT OR UPDATE OR DELETE ON pms_gate_cond
    FOR EACH ROW EXECUTE FUNCTION hb_guard_pms_gate_child();

-- 심사가 있는 게이트 작업은 게이트가 아닌 종류로 바꿀 수 없다(심사가 가리키는 대상이 흐려진다)
CREATE FUNCTION hb_guard_pms_task_gate_review() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF OLD.task_type = 'GATE' AND NEW.task_type <> 'GATE'
       AND EXISTS (SELECT 1 FROM pms_gate_review r WHERE r.gate_task_id = NEW.task_id) THEN
        RAISE EXCEPTION 'gate % has reviews', NEW.task_id USING ERRCODE = '23514', CONSTRAINT = 'ck_pms_task_gate_has_reviews';
    END IF;
    RETURN NEW;
END
$$;
CREATE TRIGGER tg_pms_task_gate_review BEFORE UPDATE OF task_type ON pms_task
    FOR EACH ROW EXECUTE FUNCTION hb_guard_pms_task_gate_review();

-- ---------------------------------------------------------------------
-- 5. 이슈 (화면·객체 연결은 PM4 — PM3 은 조건부 통과 조건에서 자동 생성)
-- ---------------------------------------------------------------------
CREATE TABLE pms_issue (
    issue_id        bigint         GENERATED ALWAYS AS IDENTITY,
    comp_cd         varchar(20)    NOT NULL,
    issue_no        varchar(30)    NOT NULL,
    proj_id         bigint         NOT NULL,
    task_id         bigint,
    title           varchar(200)   NOT NULL,
    detail          varchar(2000),
    severity        varchar(4)     NOT NULL DEFAULT 'MID',
    status          varchar(12)    NOT NULL DEFAULT 'OPEN',
    owner_user_id   bigint,
    due_date        date,
    resolution      varchar(1000),
    resolved_on     date,
    source          varchar(10)    NOT NULL DEFAULT 'MANUAL',
    gate_review_id  bigint,
    obj_type        varchar(10),
    obj_id          bigint,
    row_ver         integer        NOT NULL DEFAULT 0,
    created_at      timestamptz    NOT NULL DEFAULT now(),
    created_by      varchar(50)    NOT NULL,
    updated_at      timestamptz    NOT NULL DEFAULT now(),
    updated_by      varchar(50)    NOT NULL,
    CONSTRAINT pk_pms_issue PRIMARY KEY (issue_id),
    CONSTRAINT fk_pms_issue_proj FOREIGN KEY (proj_id) REFERENCES pms_project (proj_id),
    CONSTRAINT fk_pms_issue_task FOREIGN KEY (task_id) REFERENCES pms_task (task_id),
    CONSTRAINT fk_pms_issue_owner FOREIGN KEY (owner_user_id) REFERENCES sys_user (user_id),
    CONSTRAINT fk_pms_issue_review FOREIGN KEY (gate_review_id) REFERENCES pms_gate_review (review_id),
    CONSTRAINT uq_pms_issue_no UNIQUE (comp_cd, issue_no),
    CONSTRAINT ck_pms_issue_title CHECK (length(btrim(title)) > 0),
    CONSTRAINT ck_pms_issue_severity CHECK (severity IN ('LOW', 'MID', 'HIGH')),
    CONSTRAINT ck_pms_issue_status CHECK (status IN ('OPEN', 'IN_PROGRESS', 'RESOLVED', 'CLOSED')),
    CONSTRAINT ck_pms_issue_source CHECK (source IN ('MANUAL', 'GATE')),
    CONSTRAINT ck_pms_issue_gate CHECK ((source = 'GATE') = (gate_review_id IS NOT NULL)),
    -- 연결 객체: 지금 있는 기준정보만. LOT·CAPA 는 그 모듈 병합 뒤 새 V 파일로 (docs/32 6장)
    CONSTRAINT ck_pms_issue_obj_type CHECK (obj_type IS NULL OR obj_type IN ('ITEM', 'CUST', 'EQUIP', 'ASSET')),
    CONSTRAINT ck_pms_issue_obj CHECK ((obj_type IS NULL) = (obj_id IS NULL)),
    CONSTRAINT ck_pms_issue_due CHECK (due_date IS NULL OR due_date BETWEEN DATE '1950-01-01' AND DATE '2999-12-31'),
    -- 해결·종료는 해결 내용과 해결일이 있어야(닫을 때 필수, 11 D13)
    CONSTRAINT ck_pms_issue_resolved CHECK (status IN ('OPEN', 'IN_PROGRESS')
        OR (coalesce(length(btrim(resolution)), 0) > 0 AND resolved_on IS NOT NULL))
);
COMMENT ON TABLE pms_issue IS '프로젝트 이슈(가벼운 할 일·위험, D10). 채번 PMS_ISSUE. 조건부 통과 조건 → source GATE 로 자동 생성';
CREATE INDEX ix_pms_issue_proj ON pms_issue (comp_cd, proj_id, status);
CREATE INDEX ix_pms_issue_owner ON pms_issue (comp_cd, owner_user_id, status);
CREATE INDEX ix_pms_issue_obj ON pms_issue (comp_cd, obj_type, obj_id) WHERE obj_type IS NOT NULL;
CREATE TRIGGER tg_pms_issue_no_delete BEFORE DELETE ON pms_issue FOR EACH ROW EXECUTE FUNCTION hb_forbid_delete_master();

ALTER TABLE pms_gate_cond ADD CONSTRAINT fk_pms_gate_cond_issue FOREIGN KEY (issue_id) REFERENCES pms_issue (issue_id);

CREATE FUNCTION hb_guard_pms_issue() RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    v_status varchar(10);
    v_obj_ok boolean;
BEGIN
    SELECT p.status INTO v_status FROM pms_project p WHERE p.proj_id = NEW.proj_id AND p.comp_cd = NEW.comp_cd;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'project % is not of company %', NEW.proj_id, NEW.comp_cd USING ERRCODE = '23503', CONSTRAINT = 'fk_pms_issue_proj_comp';
    END IF;
    IF v_status IN ('CLOSED', 'CANCELLED') THEN
        RAISE EXCEPTION 'project % is closed', NEW.proj_id USING ERRCODE = '55000', CONSTRAINT = 'ck_pms_issue_project_closed';
    END IF;
    IF TG_OP = 'UPDATE' THEN
        IF NEW.proj_id <> OLD.proj_id OR NEW.issue_no <> OLD.issue_no OR NEW.source <> OLD.source
           OR NEW.gate_review_id IS DISTINCT FROM OLD.gate_review_id THEN
            RAISE EXCEPTION 'issue keys are fixed' USING ERRCODE = '55000', CONSTRAINT = 'ck_pms_issue_key_fixed';
        END IF;
        IF OLD.status = 'CLOSED' THEN
            RAISE EXCEPTION 'issue % is closed', OLD.issue_no USING ERRCODE = '55000', CONSTRAINT = 'ck_pms_issue_closed';
        END IF;
        IF NEW.status <> OLD.status AND NOT (
               (OLD.status = 'OPEN'        AND NEW.status IN ('IN_PROGRESS', 'RESOLVED'))
            OR (OLD.status = 'IN_PROGRESS' AND NEW.status IN ('OPEN', 'RESOLVED'))
            OR (OLD.status = 'RESOLVED'    AND NEW.status IN ('CLOSED', 'IN_PROGRESS'))) THEN
            RAISE EXCEPTION 'issue status % -> % is not allowed', OLD.status, NEW.status
                USING ERRCODE = '55000', CONSTRAINT = 'ck_pms_issue_transition';
        END IF;
    ELSIF NEW.status <> 'OPEN' THEN
        RAISE EXCEPTION 'issue starts as OPEN' USING ERRCODE = '55000', CONSTRAINT = 'ck_pms_issue_transition';
    END IF;
    IF NEW.task_id IS NOT NULL AND (TG_OP = 'INSERT' OR NEW.task_id IS DISTINCT FROM OLD.task_id)
       AND NOT EXISTS (SELECT 1 FROM pms_task t WHERE t.task_id = NEW.task_id AND t.proj_id = NEW.proj_id AND t.comp_cd = NEW.comp_cd) THEN
        RAISE EXCEPTION 'task % is not in project %', NEW.task_id, NEW.proj_id USING ERRCODE = '23503', CONSTRAINT = 'fk_pms_issue_task_proj';
    END IF;
    IF NEW.owner_user_id IS NOT NULL AND (TG_OP = 'INSERT' OR NEW.owner_user_id IS DISTINCT FROM OLD.owner_user_id)
       AND NOT EXISTS (SELECT 1 FROM sys_user u WHERE u.user_id = NEW.owner_user_id AND u.comp_cd = NEW.comp_cd) THEN
        RAISE EXCEPTION 'user % is not of company %', NEW.owner_user_id, NEW.comp_cd USING ERRCODE = '23503', CONSTRAINT = 'fk_pms_issue_owner_comp';
    END IF;
    IF NEW.gate_review_id IS NOT NULL AND TG_OP = 'INSERT'
       AND NOT EXISTS (SELECT 1 FROM pms_gate_review r WHERE r.review_id = NEW.gate_review_id AND r.proj_id = NEW.proj_id
                          AND r.comp_cd = NEW.comp_cd) THEN
        RAISE EXCEPTION 'gate review % is not in project %', NEW.gate_review_id, NEW.proj_id
            USING ERRCODE = '23503', CONSTRAINT = 'fk_pms_issue_review_proj';
    END IF;
    -- 연결 객체 = 같은 회사의 행(FK 는 RLS 를 무시하므로 유형별로 직접 확인 — "A사 품목에 B사 이슈" 금지).
    -- plpgsql 의 IF 조건은 첫 THEN 에서 끝나므로 CASE 는 따로 계산한다
    IF NEW.obj_type IS NOT NULL AND (TG_OP = 'INSERT' OR NEW.obj_type IS DISTINCT FROM OLD.obj_type OR NEW.obj_id IS DISTINCT FROM OLD.obj_id) THEN
        v_obj_ok := CASE NEW.obj_type
            WHEN 'ITEM'  THEN EXISTS (SELECT 1 FROM mst_item x  WHERE x.item_id  = NEW.obj_id AND x.comp_cd = NEW.comp_cd)
            WHEN 'CUST'  THEN EXISTS (SELECT 1 FROM mst_cust x  WHERE x.cust_id  = NEW.obj_id AND x.comp_cd = NEW.comp_cd)
            WHEN 'EQUIP' THEN EXISTS (SELECT 1 FROM mst_equip x WHERE x.equip_id = NEW.obj_id AND x.comp_cd = NEW.comp_cd)
            WHEN 'ASSET' THEN EXISTS (SELECT 1 FROM mst_phys_asset x WHERE x.asset_id = NEW.obj_id AND x.comp_cd = NEW.comp_cd)
            ELSE false END;
        IF NOT v_obj_ok THEN
            RAISE EXCEPTION 'object %:% is not of company %', NEW.obj_type, NEW.obj_id, NEW.comp_cd
                USING ERRCODE = '23503', CONSTRAINT = 'fk_pms_issue_obj_comp';
        END IF;
    END IF;
    RETURN NEW;
END
$$;
CREATE TRIGGER tg_pms_issue_guard BEFORE INSERT OR UPDATE ON pms_issue FOR EACH ROW EXECUTE FUNCTION hb_guard_pms_issue();

-- ---------------------------------------------------------------------
-- 6. 감사 · RLS · 권한 · 화면 · 보존
-- ---------------------------------------------------------------------
CREATE TRIGGER tg_audit AFTER INSERT OR UPDATE OR DELETE ON pms_template_item FOR EACH ROW EXECUTE FUNCTION hb_audit('ti_id', '', '');
CREATE TRIGGER tg_audit AFTER INSERT OR UPDATE OR DELETE ON pms_task_check FOR EACH ROW EXECUTE FUNCTION hb_audit('tc_id', '', '');
CREATE TRIGGER tg_audit AFTER INSERT OR UPDATE OR DELETE ON pms_deliverable FOR EACH ROW EXECUTE FUNCTION hb_audit('deliv_id', '', '');
CREATE TRIGGER tg_audit AFTER INSERT OR UPDATE OR DELETE ON pms_deliverable_hist FOR EACH ROW EXECUTE FUNCTION hb_audit('dh_id', '', '');
CREATE TRIGGER tg_audit AFTER INSERT OR UPDATE OR DELETE ON pms_gate_review FOR EACH ROW EXECUTE FUNCTION hb_audit('review_id', '', '');
CREATE TRIGGER tg_audit AFTER INSERT OR UPDATE OR DELETE ON pms_gate_check FOR EACH ROW EXECUTE FUNCTION hb_audit('gc_id', '', '');
CREATE TRIGGER tg_audit AFTER INSERT OR UPDATE OR DELETE ON pms_gate_cond FOR EACH ROW EXECUTE FUNCTION hb_audit('cond_id', '', '');
CREATE TRIGGER tg_audit AFTER INSERT OR UPDATE OR DELETE ON pms_issue FOR EACH ROW EXECUTE FUNCTION hb_audit('issue_id', '', '');

ALTER TABLE pms_template_item ENABLE ROW LEVEL SECURITY;
ALTER TABLE pms_template_item FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON pms_template_item USING (comp_cd = hb_current_comp()) WITH CHECK (comp_cd = hb_current_comp());
ALTER TABLE pms_task_check ENABLE ROW LEVEL SECURITY;
ALTER TABLE pms_task_check FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON pms_task_check USING (comp_cd = hb_current_comp()) WITH CHECK (comp_cd = hb_current_comp());
ALTER TABLE pms_deliverable ENABLE ROW LEVEL SECURITY;
ALTER TABLE pms_deliverable FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON pms_deliverable USING (comp_cd = hb_current_comp()) WITH CHECK (comp_cd = hb_current_comp());
ALTER TABLE pms_deliverable_hist ENABLE ROW LEVEL SECURITY;
ALTER TABLE pms_deliverable_hist FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON pms_deliverable_hist USING (comp_cd = hb_current_comp()) WITH CHECK (comp_cd = hb_current_comp());
ALTER TABLE pms_gate_review ENABLE ROW LEVEL SECURITY;
ALTER TABLE pms_gate_review FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON pms_gate_review USING (comp_cd = hb_current_comp()) WITH CHECK (comp_cd = hb_current_comp());
ALTER TABLE pms_gate_check ENABLE ROW LEVEL SECURITY;
ALTER TABLE pms_gate_check FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON pms_gate_check USING (comp_cd = hb_current_comp()) WITH CHECK (comp_cd = hb_current_comp());
ALTER TABLE pms_gate_cond ENABLE ROW LEVEL SECURITY;
ALTER TABLE pms_gate_cond FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON pms_gate_cond USING (comp_cd = hb_current_comp()) WITH CHECK (comp_cd = hb_current_comp());
ALTER TABLE pms_issue ENABLE ROW LEVEL SECURITY;
ALTER TABLE pms_issue FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON pms_issue USING (comp_cd = hb_current_comp()) WITH CHECK (comp_cd = hb_current_comp());

-- 이력은 추가만, 심사·이슈는 지우지 않는다. 산출물 칸·점검 문구·점검표·조건은 트리거 조건 안에서 지울 수 있다
REVOKE UPDATE, DELETE, TRUNCATE ON pms_deliverable_hist FROM ${app_role};
REVOKE DELETE, TRUNCATE ON pms_gate_review, pms_issue FROM ${app_role};
REVOKE UPDATE, TRUNCATE ON pms_template_item, pms_task_check FROM ${app_role};
REVOKE TRUNCATE ON pms_deliverable, pms_gate_check, pms_gate_cond FROM ${app_role};

INSERT INTO sys_prog (prog_cd, prog_nm_key, view_path, module_cd, actions, sort_no) VALUES
    -- APPROVE = 결재 없이 결정(정책 PMS_GATE_APPROVAL=N) / 산출물 승인·반려·면제
    ('pms-gate',        'prog.pmsGate',        'pms/gate-review',     'PMS', ARRAY['SEARCH','SAVE','APPROVE']::varchar(20)[], 820),
    ('pms-deliverable', 'prog.pmsDeliverable', 'pms/deliverable-mgt', 'PMS', ARRAY['SEARCH','SAVE','APPROVE','EXCEL']::varchar(20)[], 830);
INSERT INTO sys_retention_table (table_nm, class_cd) VALUES
    ('pms_template_item', 'MASTER'),
    ('pms_task_check', 'QUALITY_RECORD'), ('pms_deliverable', 'QUALITY_RECORD'), ('pms_deliverable_hist', 'QUALITY_RECORD'),
    ('pms_gate_review', 'QUALITY_RECORD'), ('pms_gate_check', 'QUALITY_RECORD'), ('pms_gate_cond', 'QUALITY_RECORD'),
    ('pms_issue', 'QUALITY_RECORD');
