-- =====================================================================
-- V9500 SRM 납기 회신 (P5 SRM 1부 S5 — docs/31 2장·4.5). 임시 번호 — 병합 때 상위 세션이 이름만 바꾼다(docs/29 2.2)
--
-- - srm_po_ack: 회신 묶음(발주 × 개정 × 회신 순번, 출처 MANUAL / IMPORT, 상태 ACCEPTED / PARTIAL / REJECTED). 추가만 —
--     재회신 = 새 순번, 유효 회신 = 발주 지금 개정의 가장 큰 순번. 만든 트랜잭션 안에서만 상태 칸 하나를 고칠 수 있다
--     (행을 다 넣은 뒤 상태를 계산해 적는다 — 엑셀 묶음이 한 발주를 나눠 넣어도 같은 묶음)
-- - srm_po_ack_line: 회신 행(발주 행 × 약속 납기 × 약속 수량 — 분할 납기). 묶음과 같은 트랜잭션에서만, 그 뒤 불변
-- - 발주 전이 표에 ACKED → ISSUED(재회신이 거절) 추가, ISSUED ↔ ACKED 는 같은 트랜잭션의 새 회신 묶음이 있어야
-- - 정책 SRM_ACK_DUE_DAYS(회신 기한, 발행일 + 일수), 화면 srm-ack·srm-po-status
-- =====================================================================

INSERT INTO sys_policy_def (policy_key, value_type, default_value, allowed_values, min_value, max_value, desc_key) VALUES
    ('SRM_ACK_DUE_DAYS', 'INT', '2', NULL, 0, 60, 'policy.SRM_ACK_DUE_DAYS');

INSERT INTO sys_code_grp (grp_cd, grp_nm_key, comp_ext_yn) VALUES
    ('PO_ACK_STATUS', 'code.grp.PO_ACK_STATUS', 'N');
INSERT INTO sys_code (grp_cd, code, code_nm_key, sort_no) VALUES
    ('PO_ACK_STATUS', 'ACCEPTED', 'code.PO_ACK_STATUS.ACCEPTED', 1),
    ('PO_ACK_STATUS', 'PARTIAL',  'code.PO_ACK_STATUS.PARTIAL',  2),
    ('PO_ACK_STATUS', 'REJECTED', 'code.PO_ACK_STATUS.REJECTED', 3);

-- ---------------------------------------------------------------------
-- 1. 회신 묶음
-- ---------------------------------------------------------------------
CREATE TABLE srm_po_ack (
    ack_id         bigint        GENERATED ALWAYS AS IDENTITY,
    comp_cd        varchar(20)   NOT NULL,
    po_id          bigint        NOT NULL,
    rev            integer       NOT NULL,
    ack_seq        integer       NOT NULL,
    source         varchar(10)   NOT NULL,
    ack_status     varchar(10)   NOT NULL,
    reject_reason  varchar(500),
    ack_by_nm      varchar(100),
    ack_on         date          NOT NULL,
    remark         varchar(500),
    import_job_id  bigint,
    created_at     timestamptz   NOT NULL DEFAULT now(),
    created_by     varchar(50)   NOT NULL,
    CONSTRAINT pk_srm_po_ack PRIMARY KEY (ack_id),
    CONSTRAINT fk_srm_po_ack_po FOREIGN KEY (po_id) REFERENCES srm_po (po_id),
    CONSTRAINT fk_srm_po_ack_job FOREIGN KEY (import_job_id) REFERENCES sys_import_job (job_id),
    CONSTRAINT uq_srm_po_ack_seq UNIQUE (comp_cd, po_id, ack_seq),
    CONSTRAINT ck_srm_po_ack_seq CHECK (ack_seq BETWEEN 1 AND 9999),
    CONSTRAINT ck_srm_po_ack_source CHECK (source IN ('MANUAL', 'IMPORT')),
    CONSTRAINT ck_srm_po_ack_status CHECK (ack_status IN ('ACCEPTED', 'PARTIAL', 'REJECTED')),
    -- CHECK 는 NULL 을 통과로 본다 — 거절 사유는 coalesce 로 (SKILL 교훈)
    CONSTRAINT ck_srm_po_ack_reject CHECK ((ack_status = 'REJECTED') = (coalesce(length(btrim(reject_reason)), 0) > 0)),
    CONSTRAINT ck_srm_po_ack_on CHECK (ack_on BETWEEN DATE '2000-01-01' AND DATE '2999-12-31'),
    CONSTRAINT ck_srm_po_ack_job CHECK ((source = 'IMPORT') = (import_job_id IS NOT NULL))
);
CREATE INDEX ix_srm_po_ack_po ON srm_po_ack (comp_cd, po_id, rev, ack_seq DESC);
COMMENT ON TABLE srm_po_ack IS '납기 회신 묶음 (docs/31 S5) — 추가만, 재회신 = 새 순번, 유효 = 발주 지금 개정의 가장 큰 순번';
COMMENT ON COLUMN srm_po_ack.ack_by_nm IS '협력사 쪽 회신자 이름(협력사 포털이 없어 내부 대리 입력 — D4)';
COMMENT ON COLUMN srm_po_ack.ack_on IS '협력사가 회신한 날(회사 날짜). 기록 시각은 created_at';

CREATE FUNCTION hb_guard_srm_po_ack() RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    v_po record;
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'purchase order acknowledgements are append-only' USING ERRCODE = '55000', CONSTRAINT = 'ck_srm_po_ack_append_only';
    END IF;
    IF TG_OP = 'UPDATE' THEN
        -- 만든 트랜잭션 안에서 수락 ↔ 부분 상태만(행을 다 넣은 뒤 계산) — 그 밖은 모두 불변
        IF OLD.created_at <> now()
           OR (NEW.ack_id, NEW.comp_cd, NEW.po_id, NEW.rev, NEW.ack_seq, NEW.source, NEW.ack_on, NEW.created_at, NEW.created_by)
              IS DISTINCT FROM (OLD.ack_id, OLD.comp_cd, OLD.po_id, OLD.rev, OLD.ack_seq, OLD.source, OLD.ack_on, OLD.created_at, OLD.created_by)
           OR NEW.reject_reason IS DISTINCT FROM OLD.reject_reason OR NEW.ack_by_nm IS DISTINCT FROM OLD.ack_by_nm
           OR NEW.remark IS DISTINCT FROM OLD.remark OR NEW.import_job_id IS DISTINCT FROM OLD.import_job_id
           OR OLD.ack_status = 'REJECTED' OR NEW.ack_status = 'REJECTED' THEN
            RAISE EXCEPTION 'purchase order acknowledgements are append-only'
                USING ERRCODE = '55000', CONSTRAINT = 'ck_srm_po_ack_append_only';
        END IF;
        RETURN NEW;
    END IF;
    SELECT p.comp_cd, p.status, p.rev, p.po_date INTO v_po FROM srm_po p WHERE p.po_id = NEW.po_id;
    IF NOT FOUND OR v_po.comp_cd <> NEW.comp_cd THEN
        RAISE EXCEPTION 'purchase order % does not belong to company %', NEW.po_id, NEW.comp_cd
            USING ERRCODE = '23503', CONSTRAINT = 'fk_srm_po_ack_po_comp';
    END IF;
    IF v_po.status NOT IN ('ISSUED', 'ACKED') THEN
        RAISE EXCEPTION '% purchase order cannot be acknowledged', v_po.status USING ERRCODE = '55000', CONSTRAINT = 'ck_srm_po_ack_po_state';
    END IF;
    IF NEW.rev <> v_po.rev THEN
        RAISE EXCEPTION 'acknowledgement revision % is not the current revision %', NEW.rev, v_po.rev
            USING ERRCODE = '55000', CONSTRAINT = 'ck_srm_po_ack_rev';
    END IF;
    IF NEW.ack_on < v_po.po_date THEN
        RAISE EXCEPTION 'acknowledged before the order date' USING ERRCODE = '23514', CONSTRAINT = 'ck_srm_po_ack_before_po';
    END IF;
    IF NEW.import_job_id IS NOT NULL
       AND NOT EXISTS (SELECT 1 FROM sys_import_job j WHERE j.job_id = NEW.import_job_id AND j.comp_cd = NEW.comp_cd) THEN
        RAISE EXCEPTION 'import job % is not of company %', NEW.import_job_id, NEW.comp_cd
            USING ERRCODE = '23503', CONSTRAINT = 'fk_srm_po_ack_job_comp';
    END IF;
    RETURN NEW;
END
$$;
CREATE TRIGGER tg_srm_po_ack_guard BEFORE INSERT OR UPDATE OR DELETE ON srm_po_ack FOR EACH ROW EXECUTE FUNCTION hb_guard_srm_po_ack();

-- ---------------------------------------------------------------------
-- 2. 회신 행 (분할 납기)
-- ---------------------------------------------------------------------
CREATE TABLE srm_po_ack_line (
    ack_line_id    bigint         GENERATED ALWAYS AS IDENTITY,
    comp_cd        varchar(20)    NOT NULL,
    ack_id         bigint         NOT NULL,
    po_line_id     bigint         NOT NULL,
    promised_date  date           NOT NULL,
    promised_qty   numeric(18,6)  NOT NULL,
    remark         varchar(500),
    created_at     timestamptz    NOT NULL DEFAULT now(),
    created_by     varchar(50)    NOT NULL,
    CONSTRAINT pk_srm_po_ack_line PRIMARY KEY (ack_line_id),
    CONSTRAINT fk_srm_po_ack_line_ack FOREIGN KEY (ack_id) REFERENCES srm_po_ack (ack_id),
    CONSTRAINT fk_srm_po_ack_line_po_line FOREIGN KEY (po_line_id) REFERENCES srm_po_line (po_line_id),
    CONSTRAINT uq_srm_po_ack_line UNIQUE (comp_cd, ack_id, po_line_id, promised_date),
    CONSTRAINT ck_srm_po_ack_line_qty CHECK (promised_qty > 0),
    CONSTRAINT ck_srm_po_ack_line_date CHECK (promised_date BETWEEN DATE '2000-01-01' AND DATE '2999-12-31')
);
CREATE INDEX ix_srm_po_ack_line_po_line ON srm_po_ack_line (comp_cd, po_line_id);
COMMENT ON TABLE srm_po_ack_line IS '납기 회신 행 — 한 발주 행에 분할 납기 여러 줄. 묶음과 같은 트랜잭션에서만 추가, 불변. Σ 약속 ≤ 열린 수량';

CREATE FUNCTION hb_guard_srm_po_ack_line() RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    v_ack  record;
    v_line record;
    v_sum  numeric;
BEGIN
    IF TG_OP <> 'INSERT' THEN
        RAISE EXCEPTION 'purchase order acknowledgement lines are append-only'
            USING ERRCODE = '55000', CONSTRAINT = 'ck_srm_po_ack_line_append_only';
    END IF;
    SELECT a.comp_cd, a.po_id, a.ack_status, a.created_at INTO v_ack FROM srm_po_ack a WHERE a.ack_id = NEW.ack_id;
    IF NOT FOUND OR v_ack.comp_cd <> NEW.comp_cd THEN
        RAISE EXCEPTION 'acknowledgement % does not belong to company %', NEW.ack_id, NEW.comp_cd
            USING ERRCODE = '23503', CONSTRAINT = 'fk_srm_po_ack_line_ack_comp';
    END IF;
    -- 묶음을 만든 트랜잭션에서만(now() = 트랜잭션 시작 시각) — 나중에 직접 SQL 로 약속을 덧붙이지 못한다
    IF v_ack.created_at <> now() THEN
        RAISE EXCEPTION 'lines can be added only with their acknowledgement' USING ERRCODE = '55000', CONSTRAINT = 'ck_srm_po_ack_line_same_tx';
    END IF;
    IF v_ack.ack_status = 'REJECTED' THEN
        RAISE EXCEPTION 'a rejection has no lines' USING ERRCODE = '55000', CONSTRAINT = 'ck_srm_po_ack_line_rejected';
    END IF;
    SELECT l.po_id, l.qty, l.cancel_qty, l.line_status, p.po_date INTO v_line
      FROM srm_po_line l JOIN srm_po p ON p.po_id = l.po_id WHERE l.po_line_id = NEW.po_line_id;
    IF NOT FOUND OR v_line.po_id <> v_ack.po_id THEN
        RAISE EXCEPTION 'line % is not a line of the acknowledged order', NEW.po_line_id
            USING ERRCODE = '23503', CONSTRAINT = 'fk_srm_po_ack_line_po_line_comp';
    END IF;
    IF v_line.line_status <> 'OPEN' THEN
        RAISE EXCEPTION 'line % is %', NEW.po_line_id, v_line.line_status USING ERRCODE = '55000', CONSTRAINT = 'ck_srm_po_ack_line_open';
    END IF;
    IF NEW.promised_date < v_line.po_date THEN
        RAISE EXCEPTION 'promised before the order date' USING ERRCODE = '23514', CONSTRAINT = 'ck_srm_po_ack_line_before_po';
    END IF;
    SELECT coalesce(sum(promised_qty), 0) INTO v_sum FROM srm_po_ack_line WHERE ack_id = NEW.ack_id AND po_line_id = NEW.po_line_id;
    IF v_sum + NEW.promised_qty > v_line.qty - v_line.cancel_qty THEN
        RAISE EXCEPTION 'promised % exceeds the open quantity', v_sum + NEW.promised_qty
            USING ERRCODE = '23514', CONSTRAINT = 'ck_srm_po_ack_line_over';
    END IF;
    RETURN NEW;
END
$$;
CREATE TRIGGER tg_srm_po_ack_line_guard BEFORE INSERT OR UPDATE OR DELETE ON srm_po_ack_line
    FOR EACH ROW EXECUTE FUNCTION hb_guard_srm_po_ack_line();

-- ---------------------------------------------------------------------
-- 3. 발주 전이 표: ACKED → ISSUED 추가 + ISSUED ↔ ACKED 는 같은 트랜잭션의 새 회신 묶음 필요
--    (V045 함수 본문 그대로 + 표시한 두 곳만 다르다)
-- ---------------------------------------------------------------------
CREATE OR REPLACE FUNCTION hb_guard_srm_po() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'INSERT' THEN
        IF NEW.status <> 'DRAFT' OR NEW.rev <> 0 THEN
            RAISE EXCEPTION 'new purchase order must be DRAFT rev 0' USING ERRCODE = '55000', CONSTRAINT = 'ck_srm_po_transition';
        END IF;
        IF NOT hb_srm_supplier_of_comp(NEW.comp_cd, NEW.cust_id) THEN
            RAISE EXCEPTION 'supplier % is not of company %', NEW.cust_id, NEW.comp_cd
                USING ERRCODE = '23503', CONSTRAINT = 'fk_srm_po_supplier_comp';
        END IF;
        IF EXISTS (SELECT 1 FROM srm_supplier s WHERE s.cust_id = NEW.cust_id AND s.trade_status = 'BLOCKED') THEN
            RAISE EXCEPTION 'supplier % is blocked', NEW.cust_id USING ERRCODE = '55000', CONSTRAINT = 'ck_srm_po_supplier_blocked';
        END IF;
        IF NOT hb_code_usable(NEW.comp_cd, 'CURRENCY', NEW.currency_cd) THEN
            RAISE EXCEPTION 'currency % is not usable', NEW.currency_cd USING ERRCODE = '23503', CONSTRAINT = 'fk_srm_po_currency';
        END IF;
    ELSE
        IF NEW.po_no <> OLD.po_no OR NEW.po_date <> OLD.po_date OR NEW.cust_id <> OLD.cust_id OR NEW.currency_cd <> OLD.currency_cd
           OR NEW.comp_cd <> OLD.comp_cd OR NEW.source <> OLD.source THEN
            RAISE EXCEPTION 'purchase order number, date, supplier, currency and source are fixed'
                USING ERRCODE = '55000', CONSTRAINT = 'ck_srm_po_fixed';
        END IF;
        IF OLD.status <> 'DRAFT' AND (NEW.plant_cd IS DISTINCT FROM OLD.plant_cd OR NEW.wh_id IS DISTINCT FROM OLD.wh_id
                OR NEW.pay_terms_cd IS DISTINCT FROM OLD.pay_terms_cd OR NEW.remark IS DISTINCT FROM OLD.remark
                OR NEW.total_amt IS DISTINCT FROM OLD.total_amt OR NEW.buyer_user_id IS DISTINCT FROM OLD.buyer_user_id) THEN
            RAISE EXCEPTION '% purchase order cannot be edited', OLD.status USING ERRCODE = '55000', CONSTRAINT = 'ck_srm_po_draft';
        END IF;
        IF NEW.status <> OLD.status AND NOT (
               (OLD.status = 'DRAFT'       AND NEW.status IN ('IN_APPROVAL', 'APPROVED', 'CANCELLED'))
            OR (OLD.status = 'IN_APPROVAL' AND NEW.status IN ('APPROVED', 'DRAFT'))
            OR (OLD.status = 'APPROVED'    AND NEW.status IN ('ISSUED', 'DRAFT', 'CANCELLED'))
            OR (OLD.status = 'ISSUED'      AND NEW.status IN ('ACKED', 'DRAFT', 'CLOSED', 'CANCELLED'))
            OR (OLD.status = 'ACKED'       AND NEW.status IN ('ISSUED', 'DRAFT', 'CLOSED', 'CANCELLED'))) THEN    -- (S5) ACKED → ISSUED
            RAISE EXCEPTION 'purchase order cannot go from % to %', OLD.status, NEW.status
                USING ERRCODE = '55000', CONSTRAINT = 'ck_srm_po_transition';
        END IF;
        -- (S5) 회신 상태는 회신으로만 — 같은 트랜잭션에서 지금 개정의 회신 묶음을 넣어야 한다
        IF NEW.status <> OLD.status AND OLD.status IN ('ISSUED', 'ACKED') AND NEW.status IN ('ISSUED', 'ACKED')
           AND NOT EXISTS (SELECT 1 FROM srm_po_ack a WHERE a.po_id = NEW.po_id AND a.rev = NEW.rev AND a.created_at = now()) THEN
            RAISE EXCEPTION 'acknowledgement status changes only with a new acknowledgement'
                USING ERRCODE = '55000', CONSTRAINT = 'ck_srm_po_ack_required';
        END IF;
        IF NEW.rev <> OLD.rev AND NOT (NEW.rev = OLD.rev + 1 AND OLD.status IN ('ISSUED', 'ACKED') AND NEW.status = 'DRAFT') THEN
            RAISE EXCEPTION 'revision changes only when an issued order goes back to draft'
                USING ERRCODE = '55000', CONSTRAINT = 'ck_srm_po_rev_step';
        END IF;
        IF OLD.status IN ('ISSUED', 'ACKED') AND NEW.status = 'DRAFT' AND NEW.rev <> OLD.rev + 1 THEN
            RAISE EXCEPTION 'revising an issued order must raise the revision'
                USING ERRCODE = '55000', CONSTRAINT = 'ck_srm_po_rev_step';
        END IF;
    END IF;
    IF NEW.buyer_user_id IS NOT NULL AND (TG_OP = 'INSERT' OR NEW.buyer_user_id IS DISTINCT FROM OLD.buyer_user_id)
       AND NOT EXISTS (SELECT 1 FROM sys_user u WHERE u.user_id = NEW.buyer_user_id AND u.comp_cd = NEW.comp_cd) THEN
        RAISE EXCEPTION 'user % is not a user of company %', NEW.buyer_user_id, NEW.comp_cd
            USING ERRCODE = '23503', CONSTRAINT = 'fk_srm_po_buyer_comp';
    END IF;
    IF NEW.wh_id IS NOT NULL AND (TG_OP = 'INSERT' OR NEW.wh_id IS DISTINCT FROM OLD.wh_id OR NEW.plant_cd IS DISTINCT FROM OLD.plant_cd)
       AND NOT EXISTS (SELECT 1 FROM mst_equip_hier h WHERE h.node_id = NEW.wh_id AND h.comp_cd = NEW.comp_cd
                          AND h.plant_cd = NEW.plant_cd AND h.node_type = 'WH') THEN
        RAISE EXCEPTION 'warehouse % is not a warehouse of plant %', NEW.wh_id, NEW.plant_cd
            USING ERRCODE = '23503', CONSTRAINT = 'fk_srm_po_wh_plant';
    END IF;
    IF NEW.pay_terms_cd IS NOT NULL AND (TG_OP = 'INSERT' OR NEW.pay_terms_cd IS DISTINCT FROM OLD.pay_terms_cd)
       AND NOT hb_code_usable(NEW.comp_cd, 'PAY_TERMS', NEW.pay_terms_cd) THEN
        RAISE EXCEPTION 'pay terms % is not usable', NEW.pay_terms_cd USING ERRCODE = '23503', CONSTRAINT = 'fk_srm_po_pay_terms';
    END IF;
    RETURN NEW;
END
$$;

-- ---------------------------------------------------------------------
-- 4. 감사 · RLS · 권한 · 화면 · 보존
-- ---------------------------------------------------------------------
CREATE TRIGGER tg_audit AFTER INSERT OR UPDATE OR DELETE ON srm_po_ack FOR EACH ROW EXECUTE FUNCTION hb_audit('ack_id', '', '');
CREATE TRIGGER tg_audit AFTER INSERT OR UPDATE OR DELETE ON srm_po_ack_line FOR EACH ROW EXECUTE FUNCTION hb_audit('ack_line_id', '', '');

ALTER TABLE srm_po_ack ENABLE ROW LEVEL SECURITY;
ALTER TABLE srm_po_ack FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON srm_po_ack USING (comp_cd = hb_current_comp()) WITH CHECK (comp_cd = hb_current_comp());
ALTER TABLE srm_po_ack_line ENABLE ROW LEVEL SECURITY;
ALTER TABLE srm_po_ack_line FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON srm_po_ack_line USING (comp_cd = hb_current_comp()) WITH CHECK (comp_cd = hb_current_comp());
REVOKE DELETE, TRUNCATE ON srm_po_ack, srm_po_ack_line FROM ${app_role};
REVOKE UPDATE ON srm_po_ack_line FROM ${app_role};

INSERT INTO sys_prog (prog_cd, prog_nm_key, view_path, module_cd, actions, sort_no) VALUES
    ('srm-ack',       'prog.srmAck',      'srm/ack-mgt',   'SRM', ARRAY['SEARCH','SAVE']::varchar(20)[], 860),
    ('srm-po-status', 'prog.srmPoStatus', 'srm/po-status', 'SRM', ARRAY['SEARCH']::varchar(20)[], 861);
INSERT INTO sys_retention_table (table_nm, class_cd) VALUES
    ('srm_po_ack', 'MASTER'), ('srm_po_ack_line', 'MASTER');
