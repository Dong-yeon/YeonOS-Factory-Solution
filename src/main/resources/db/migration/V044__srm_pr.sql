-- =====================================================================
-- V044 SRM 구매요청 PR (P5 SRM 1부 S2 — docs/31 2장·4장, 결정 D5)
--
-- - srm_pr: 머리(번호 채번 SRM_PR·요청자·공장·필요일·사유, 상태 DRAFT → REQUESTED → APPROVED / REJECTED → CLOSED, CANCELLED)
-- - srm_pr_line: 행(품목·수량·품목 기본 단위·필요일·희망 협력사). 작성 중(DRAFT)만 추가·수정·삭제
-- - 승인은 APPROVE 권한 단순 승인(결재 아님). 요청자 본인 승인은 정책 SRM_PR_SELF_APPROVE(기본 N)
-- - 상태 전이 표·작성 중만 편집은 트리거가 최종(55000) — 서비스가 먼저 칸 오류·409 로 알린다
-- ※ 병행 개발(docs/31 6장): main 에 늦게 들어가는 쪽이 번호를 다시 매긴다
-- =====================================================================

INSERT INTO sys_policy_def (policy_key, value_type, default_value, allowed_values, min_value, max_value, desc_key) VALUES
    ('SRM_PR_SELF_APPROVE', 'BOOL', 'N', NULL, NULL, NULL, 'policy.SRM_PR_SELF_APPROVE');

INSERT INTO sys_code_grp (grp_cd, grp_nm_key, comp_ext_yn) VALUES
    ('PR_STATUS', 'code.grp.PR_STATUS', 'N');
INSERT INTO sys_code (grp_cd, code, code_nm_key, sort_no) VALUES
    ('PR_STATUS', 'DRAFT',     'code.PR_STATUS.DRAFT',     1),
    ('PR_STATUS', 'REQUESTED', 'code.PR_STATUS.REQUESTED', 2),
    ('PR_STATUS', 'APPROVED',  'code.PR_STATUS.APPROVED',  3),
    ('PR_STATUS', 'REJECTED',  'code.PR_STATUS.REJECTED',  4),
    ('PR_STATUS', 'CLOSED',    'code.PR_STATUS.CLOSED',    5),
    ('PR_STATUS', 'CANCELLED', 'code.PR_STATUS.CANCELLED', 6);

-- ---------------------------------------------------------------------
-- 1. 머리
-- ---------------------------------------------------------------------
CREATE TABLE srm_pr (
    pr_id          bigint        GENERATED ALWAYS AS IDENTITY,
    comp_cd        varchar(20)   NOT NULL,
    pr_no          varchar(30)   NOT NULL,
    pr_date        date          NOT NULL,
    plant_cd       varchar(20)   NOT NULL,
    req_user_id    bigint        NOT NULL,
    dept_nm        varchar(50),
    need_date      date,
    reason         varchar(500),
    status         varchar(10)   NOT NULL DEFAULT 'DRAFT',
    decided_by     bigint,
    decided_at     timestamptz,
    reject_reason  varchar(500),
    cancel_reason  varchar(500),
    row_ver        integer       NOT NULL DEFAULT 0,
    created_at     timestamptz   NOT NULL DEFAULT now(),
    created_by     varchar(50)   NOT NULL,
    updated_at     timestamptz   NOT NULL DEFAULT now(),
    updated_by     varchar(50)   NOT NULL,
    CONSTRAINT pk_srm_pr PRIMARY KEY (pr_id),
    CONSTRAINT fk_srm_pr_comp FOREIGN KEY (comp_cd) REFERENCES sys_comp (comp_cd),
    CONSTRAINT fk_srm_pr_plant FOREIGN KEY (comp_cd, plant_cd) REFERENCES sys_plant (comp_cd, plant_cd),
    CONSTRAINT fk_srm_pr_req_user FOREIGN KEY (req_user_id) REFERENCES sys_user (user_id),
    CONSTRAINT fk_srm_pr_decided_by FOREIGN KEY (decided_by) REFERENCES sys_user (user_id),
    CONSTRAINT uq_srm_pr_no UNIQUE (comp_cd, pr_no),
    CONSTRAINT ck_srm_pr_status CHECK (status IN ('DRAFT', 'REQUESTED', 'APPROVED', 'REJECTED', 'CLOSED', 'CANCELLED')),
    CONSTRAINT ck_srm_pr_reject CHECK (status <> 'REJECTED' OR (reject_reason IS NOT NULL AND length(btrim(reject_reason)) > 0)),
    CONSTRAINT ck_srm_pr_cancel CHECK (status <> 'CANCELLED' OR (cancel_reason IS NOT NULL AND length(btrim(cancel_reason)) > 0)),
    CONSTRAINT ck_srm_pr_need CHECK (need_date BETWEEN DATE '2000-01-01' AND DATE '2999-12-31')
);
CREATE INDEX ix_srm_pr_date ON srm_pr (comp_cd, pr_date, pr_id);
CREATE INDEX ix_srm_pr_status ON srm_pr (comp_cd, status);
COMMENT ON TABLE srm_pr IS '구매요청 머리 (docs/31 S2). 작성 중(DRAFT)만 편집, 상태 전이는 트리거 표가 최종. 승인 = APPROVE 권한(결재 아님, D5)';
COMMENT ON COLUMN srm_pr.decided_by IS '승인·반려한 사용자 (요청자 본인 승인은 정책 SRM_PR_SELF_APPROVE)';

-- 상태 전이 표 + 작성 중이 아닐 때 내용 칸 고정 + 같은 회사 요청자
CREATE FUNCTION hb_guard_srm_pr() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'INSERT' THEN
        IF NEW.status <> 'DRAFT' THEN
            RAISE EXCEPTION 'new purchase request must be DRAFT' USING ERRCODE = '55000', CONSTRAINT = 'ck_srm_pr_transition';
        END IF;
    ELSE
        IF NEW.pr_no <> OLD.pr_no OR NEW.pr_date <> OLD.pr_date OR NEW.req_user_id <> OLD.req_user_id OR NEW.comp_cd <> OLD.comp_cd THEN
            RAISE EXCEPTION 'purchase request number, date and requester are fixed'
                USING ERRCODE = '55000', CONSTRAINT = 'ck_srm_pr_fixed';
        END IF;
        IF OLD.status <> 'DRAFT' AND (NEW.plant_cd IS DISTINCT FROM OLD.plant_cd OR NEW.dept_nm IS DISTINCT FROM OLD.dept_nm
                OR NEW.need_date IS DISTINCT FROM OLD.need_date OR NEW.reason IS DISTINCT FROM OLD.reason) THEN
            RAISE EXCEPTION '% purchase request cannot be edited', OLD.status USING ERRCODE = '55000', CONSTRAINT = 'ck_srm_pr_draft';
        END IF;
        IF NEW.status <> OLD.status AND NOT (
               (OLD.status = 'DRAFT'     AND NEW.status IN ('REQUESTED', 'CANCELLED'))
            OR (OLD.status = 'REQUESTED' AND NEW.status IN ('DRAFT', 'APPROVED', 'REJECTED', 'CANCELLED'))
            OR (OLD.status = 'REJECTED'  AND NEW.status = 'DRAFT')
            OR (OLD.status = 'APPROVED'  AND NEW.status IN ('CLOSED', 'CANCELLED'))) THEN
            RAISE EXCEPTION 'purchase request cannot go from % to %', OLD.status, NEW.status
                USING ERRCODE = '55000', CONSTRAINT = 'ck_srm_pr_transition';
        END IF;
    END IF;
    IF (TG_OP = 'INSERT' OR NEW.req_user_id <> OLD.req_user_id)
       AND NOT EXISTS (SELECT 1 FROM sys_user u WHERE u.user_id = NEW.req_user_id AND u.comp_cd = NEW.comp_cd) THEN
        RAISE EXCEPTION 'user % is not a user of company %', NEW.req_user_id, NEW.comp_cd
            USING ERRCODE = '23503', CONSTRAINT = 'fk_srm_pr_req_user_comp';
    END IF;
    IF NEW.decided_by IS NOT NULL AND (TG_OP = 'INSERT' OR NEW.decided_by IS DISTINCT FROM OLD.decided_by)
       AND NOT EXISTS (SELECT 1 FROM sys_user u WHERE u.user_id = NEW.decided_by AND u.comp_cd = NEW.comp_cd) THEN
        RAISE EXCEPTION 'user % is not a user of company %', NEW.decided_by, NEW.comp_cd
            USING ERRCODE = '23503', CONSTRAINT = 'fk_srm_pr_decided_by_comp';
    END IF;
    RETURN NEW;
END
$$;
CREATE TRIGGER tg_srm_pr_guard BEFORE INSERT OR UPDATE ON srm_pr FOR EACH ROW EXECUTE FUNCTION hb_guard_srm_pr();
CREATE TRIGGER tg_srm_pr_no_delete BEFORE DELETE ON srm_pr FOR EACH ROW EXECUTE FUNCTION hb_forbid_delete_master();

-- ---------------------------------------------------------------------
-- 2. 행
-- ---------------------------------------------------------------------
CREATE TABLE srm_pr_line (
    pr_line_id    bigint         GENERATED ALWAYS AS IDENTITY,
    comp_cd       varchar(20)    NOT NULL,
    pr_id         bigint         NOT NULL,
    line_no       integer        NOT NULL,
    item_id       bigint         NOT NULL,
    qty           numeric(18,6)  NOT NULL,
    unit_cd       varchar(30)    NOT NULL,
    need_date     date,
    pref_cust_id  bigint,
    remark        varchar(500),
    line_status   varchar(10)    NOT NULL DEFAULT 'OPEN',
    created_at    timestamptz    NOT NULL DEFAULT now(),
    created_by    varchar(50)    NOT NULL,
    updated_at    timestamptz    NOT NULL DEFAULT now(),
    updated_by    varchar(50)    NOT NULL,
    CONSTRAINT pk_srm_pr_line PRIMARY KEY (pr_line_id),
    CONSTRAINT fk_srm_pr_line_pr FOREIGN KEY (pr_id) REFERENCES srm_pr (pr_id),
    CONSTRAINT fk_srm_pr_line_item FOREIGN KEY (item_id) REFERENCES mst_item (item_id),
    CONSTRAINT fk_srm_pr_line_pref FOREIGN KEY (pref_cust_id) REFERENCES srm_supplier (cust_id),
    CONSTRAINT uq_srm_pr_line_no UNIQUE (pr_id, line_no),
    CONSTRAINT ck_srm_pr_line_no CHECK (line_no BETWEEN 1 AND 9999),
    CONSTRAINT ck_srm_pr_line_qty CHECK (qty > 0),
    CONSTRAINT ck_srm_pr_line_status CHECK (line_status IN ('OPEN', 'CANCELLED', 'CLOSED')),
    CONSTRAINT ck_srm_pr_line_need CHECK (need_date BETWEEN DATE '2000-01-01' AND DATE '2999-12-31')
);
CREATE INDEX ix_srm_pr_line_pr ON srm_pr_line (comp_cd, pr_id);
CREATE INDEX ix_srm_pr_line_item ON srm_pr_line (comp_cd, item_id);
COMMENT ON TABLE srm_pr_line IS '구매요청 행 — 수량은 품목 기본 단위(unit_cd 는 저장 때 스냅샷). 남은 수량은 저장하지 않고 발주 행에서 계산(S3)';

CREATE FUNCTION hb_guard_srm_pr_line() RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    v_row  srm_pr_line;
    v_head record;
BEGIN
    v_row := CASE WHEN TG_OP = 'DELETE' THEN OLD ELSE NEW END;
    SELECT p.status, p.comp_cd INTO v_head FROM srm_pr p WHERE p.pr_id = v_row.pr_id;
    IF NOT FOUND OR v_head.comp_cd <> v_row.comp_cd THEN
        RAISE EXCEPTION 'purchase request % does not belong to company %', v_row.pr_id, v_row.comp_cd
            USING ERRCODE = '23503', CONSTRAINT = 'fk_srm_pr_line_pr_comp';
    END IF;
    -- 작성 중이 아니면 행 상태(OPEN → CLOSED·CANCELLED, 발주 연결 S3)만 바꿀 수 있다
    IF v_head.status <> 'DRAFT' THEN
        IF TG_OP <> 'UPDATE' OR NOT (OLD.line_status = 'OPEN' AND NEW.line_status IN ('CLOSED', 'CANCELLED')
                AND (NEW.item_id, NEW.qty, NEW.unit_cd, NEW.line_no, NEW.pr_id) IS NOT DISTINCT FROM (OLD.item_id, OLD.qty, OLD.unit_cd, OLD.line_no, OLD.pr_id)
                AND NEW.need_date IS NOT DISTINCT FROM OLD.need_date AND NEW.pref_cust_id IS NOT DISTINCT FROM OLD.pref_cust_id
                AND NEW.remark IS NOT DISTINCT FROM OLD.remark) THEN
            RAISE EXCEPTION 'lines of % purchase request % cannot be changed', v_head.status, v_row.pr_id
                USING ERRCODE = '55000', CONSTRAINT = 'ck_srm_pr_line_draft';
        END IF;
    END IF;
    IF TG_OP = 'DELETE' THEN
        RETURN OLD;
    END IF;
    IF TG_OP = 'UPDATE' AND (NEW.pr_id <> OLD.pr_id OR NEW.line_no <> OLD.line_no) THEN
        RAISE EXCEPTION 'line cannot move' USING ERRCODE = '55000', CONSTRAINT = 'ck_srm_pr_line_move';
    END IF;
    IF NOT hb_item_of_comp(NEW.comp_cd, NEW.item_id) THEN
        RAISE EXCEPTION 'item % does not belong to company %', NEW.item_id, NEW.comp_cd
            USING ERRCODE = '23503', CONSTRAINT = 'fk_srm_pr_line_item_comp';
    END IF;
    IF (TG_OP = 'INSERT' OR NEW.unit_cd <> OLD.unit_cd) AND NOT hb_unit_usable(NEW.comp_cd, NEW.unit_cd) THEN
        RAISE EXCEPTION 'unit % is not available for company %', NEW.unit_cd, NEW.comp_cd
            USING ERRCODE = '23503', CONSTRAINT = 'fk_srm_pr_line_unit';
    END IF;
    IF NEW.pref_cust_id IS NOT NULL AND (TG_OP = 'INSERT' OR NEW.pref_cust_id IS DISTINCT FROM OLD.pref_cust_id)
       AND NOT hb_srm_supplier_of_comp(NEW.comp_cd, NEW.pref_cust_id) THEN
        RAISE EXCEPTION 'supplier % is not of company %', NEW.pref_cust_id, NEW.comp_cd
            USING ERRCODE = '23503', CONSTRAINT = 'fk_srm_pr_line_pref_comp';
    END IF;
    RETURN NEW;
END
$$;
CREATE TRIGGER tg_srm_pr_line_guard BEFORE INSERT OR UPDATE OR DELETE ON srm_pr_line
    FOR EACH ROW EXECUTE FUNCTION hb_guard_srm_pr_line();

-- ---------------------------------------------------------------------
-- 3. 감사 · RLS · 권한 · 화면 · 보존
-- ---------------------------------------------------------------------
CREATE TRIGGER tg_audit AFTER INSERT OR UPDATE OR DELETE ON srm_pr FOR EACH ROW EXECUTE FUNCTION hb_audit('pr_id', '', '');
CREATE TRIGGER tg_audit AFTER INSERT OR UPDATE OR DELETE ON srm_pr_line FOR EACH ROW EXECUTE FUNCTION hb_audit('pr_line_id', '', '');

ALTER TABLE srm_pr ENABLE ROW LEVEL SECURITY;
ALTER TABLE srm_pr FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON srm_pr USING (comp_cd = hb_current_comp()) WITH CHECK (comp_cd = hb_current_comp());
ALTER TABLE srm_pr_line ENABLE ROW LEVEL SECURITY;
ALTER TABLE srm_pr_line FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON srm_pr_line USING (comp_cd = hb_current_comp()) WITH CHECK (comp_cd = hb_current_comp());
REVOKE DELETE, TRUNCATE ON srm_pr FROM ${app_role};
REVOKE TRUNCATE ON srm_pr_line FROM ${app_role};

INSERT INTO sys_prog (prog_cd, prog_nm_key, view_path, module_cd, actions, sort_no) VALUES
    ('srm-pr', 'prog.srmPr', 'srm/pr-mgt', 'SRM', ARRAY['SEARCH','SAVE','APPROVE']::varchar(20)[], 510);
INSERT INTO sys_retention_table (table_nm, class_cd) VALUES
    ('srm_pr', 'MASTER'), ('srm_pr_line', 'MASTER');
