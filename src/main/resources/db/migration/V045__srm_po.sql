-- =====================================================================
-- V045 SRM 발주 PO (P5 SRM 1부 S3 — docs/31 2장·4장, 결정 D1·D3·D6)
--
-- - srm_po: 머리(번호 SRM_PO·개정·협력사·공장·납품 창고·통화·결제 조건 스냅샷·합계), 상태
--     DRAFT → IN_APPROVAL(결재 SRM_PO) → APPROVED → ISSUED → ACKED(S5) → CLOSED, CANCELLED
--     결재가 필요 없으면(정책 SRM_PO_APPROVAL=N 또는 SRM_PO_APPROVAL_MIN_AMT 미만) DRAFT → APPROVED(확정, APPROVE 권한)
--     발행 뒤 변경 = 개정: ISSUED·ACKED → DRAFT 로 돌아가며 rev + 1 (발행본은 srm_po_rev 스냅샷에 남는다)
-- - srm_po_line: 행(품목·품명/협력사 품번 스냅샷·수량·단위·단가·금액·요구 납기·PR 행 연결). 작성 중만 편집
-- - srm_po_rev: 발행본 스냅샷(추가만) — "협력사에게 무엇을 보냈나"
-- - 정책 SRM_PO_SOURCE(SRM/ERP, D1)·SRM_PR_REQUIRED·SRM_PO_APPROVAL·SRM_PO_APPROVAL_MIN_AMT
-- ※ 병행 개발(docs/31 6장): main 에 늦게 들어가는 쪽이 번호를 다시 매긴다
-- =====================================================================

INSERT INTO sys_policy_def (policy_key, value_type, default_value, allowed_values, min_value, max_value, desc_key) VALUES
    ('SRM_PO_SOURCE',           'ENUM', 'SRM', ARRAY['SRM','ERP'], NULL, NULL, 'policy.SRM_PO_SOURCE'),
    ('SRM_PR_REQUIRED',         'BOOL', 'N',   NULL, NULL, NULL, 'policy.SRM_PR_REQUIRED'),
    ('SRM_PO_APPROVAL',         'BOOL', 'Y',   NULL, NULL, NULL, 'policy.SRM_PO_APPROVAL'),
    ('SRM_PO_APPROVAL_MIN_AMT', 'INT',  '0',   NULL, 0, 2000000000, 'policy.SRM_PO_APPROVAL_MIN_AMT');

INSERT INTO sys_code_grp (grp_cd, grp_nm_key, comp_ext_yn) VALUES
    ('PO_STATUS', 'code.grp.PO_STATUS', 'N');
INSERT INTO sys_code (grp_cd, code, code_nm_key, sort_no) VALUES
    ('PO_STATUS', 'DRAFT',       'code.PO_STATUS.DRAFT',       1),
    ('PO_STATUS', 'IN_APPROVAL', 'code.PO_STATUS.IN_APPROVAL', 2),
    ('PO_STATUS', 'APPROVED',    'code.PO_STATUS.APPROVED',    3),
    ('PO_STATUS', 'ISSUED',      'code.PO_STATUS.ISSUED',      4),
    ('PO_STATUS', 'ACKED',       'code.PO_STATUS.ACKED',       5),
    ('PO_STATUS', 'CLOSED',      'code.PO_STATUS.CLOSED',      6),
    ('PO_STATUS', 'CANCELLED',   'code.PO_STATUS.CANCELLED',   7);

-- 통화 금액 소수 자릿수(CURRENCY attrs.scale, 없으면 2)
CREATE FUNCTION hb_currency_scale(p_currency varchar) RETURNS integer
    LANGUAGE sql STABLE
AS $$
    SELECT coalesce((SELECT (attrs ->> 'scale')::int FROM sys_code WHERE grp_cd = 'CURRENCY' AND code = p_currency), 2)
$$;

-- ---------------------------------------------------------------------
-- 1. 머리
-- ---------------------------------------------------------------------
CREATE TABLE srm_po (
    po_id           bigint         GENERATED ALWAYS AS IDENTITY,
    comp_cd         varchar(20)    NOT NULL,
    po_no           varchar(30)    NOT NULL,
    rev             integer        NOT NULL DEFAULT 0,
    po_date         date           NOT NULL,
    cust_id         bigint         NOT NULL,
    plant_cd        varchar(20)    NOT NULL,
    wh_id           bigint,
    currency_cd     varchar(30)    NOT NULL,
    pay_terms_cd    varchar(30),
    buyer_user_id   bigint         NOT NULL,
    status          varchar(12)    NOT NULL DEFAULT 'DRAFT',
    apr_request_id  bigint,
    issued_at       timestamptz,
    issued_by       varchar(50),
    cancel_reason   varchar(500),
    total_amt       numeric(20,4)  NOT NULL DEFAULT 0,
    source          varchar(10)    NOT NULL DEFAULT 'SRM',
    remark          varchar(1000),
    row_ver         integer        NOT NULL DEFAULT 0,
    created_at      timestamptz    NOT NULL DEFAULT now(),
    created_by      varchar(50)    NOT NULL,
    updated_at      timestamptz    NOT NULL DEFAULT now(),
    updated_by      varchar(50)    NOT NULL,
    CONSTRAINT pk_srm_po PRIMARY KEY (po_id),
    CONSTRAINT fk_srm_po_comp FOREIGN KEY (comp_cd) REFERENCES sys_comp (comp_cd),
    CONSTRAINT fk_srm_po_supplier FOREIGN KEY (cust_id) REFERENCES srm_supplier (cust_id),
    CONSTRAINT fk_srm_po_plant FOREIGN KEY (comp_cd, plant_cd) REFERENCES sys_plant (comp_cd, plant_cd),
    CONSTRAINT fk_srm_po_wh FOREIGN KEY (wh_id) REFERENCES mst_equip_hier (node_id),
    CONSTRAINT fk_srm_po_buyer FOREIGN KEY (buyer_user_id) REFERENCES sys_user (user_id),
    CONSTRAINT fk_srm_po_apr FOREIGN KEY (apr_request_id) REFERENCES apr_request (apr_id),
    CONSTRAINT uq_srm_po_no UNIQUE (comp_cd, po_no),
    CONSTRAINT ck_srm_po_status CHECK (status IN ('DRAFT', 'IN_APPROVAL', 'APPROVED', 'ISSUED', 'ACKED', 'CLOSED', 'CANCELLED')),
    CONSTRAINT ck_srm_po_rev CHECK (rev BETWEEN 0 AND 999),
    CONSTRAINT ck_srm_po_source CHECK (source IN ('SRM', 'ERP')),
    CONSTRAINT ck_srm_po_cancel CHECK (status <> 'CANCELLED' OR (cancel_reason IS NOT NULL AND length(btrim(cancel_reason)) > 0)),
    CONSTRAINT ck_srm_po_total CHECK (total_amt >= 0)
);
CREATE INDEX ix_srm_po_date ON srm_po (comp_cd, po_date, po_id);
CREATE INDEX ix_srm_po_supplier ON srm_po (comp_cd, cust_id, status);
COMMENT ON TABLE srm_po IS '발주 머리 (docs/31 S3). 작성 중만 편집, 결재 SRM_PO, 발행 뒤 변경 = 개정(rev + 1, 발행본은 srm_po_rev)';
COMMENT ON COLUMN srm_po.total_amt IS '행 금액 합(행마다 통화 자릿수로 반올림한 뒤 합) — 서비스가 저장 때 계산, 발주서 표기용(대금은 ERP)';
COMMENT ON COLUMN srm_po.source IS 'SRM = 여기서 작성, ERP = ERP 발주를 받아 온 것(정책 SRM_PO_SOURCE=ERP, D1)';

CREATE FUNCTION hb_guard_srm_po() RETURNS trigger
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
        -- 거래 정지 협력사에는 새 발주 금지(기존 발주는 그대로) — docs/31 4.1
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
            OR (OLD.status = 'ACKED'       AND NEW.status IN ('DRAFT', 'CLOSED', 'CANCELLED'))) THEN
            RAISE EXCEPTION 'purchase order cannot go from % to %', OLD.status, NEW.status
                USING ERRCODE = '55000', CONSTRAINT = 'ck_srm_po_transition';
        END IF;
        -- 개정 번호는 발행본(ISSUED·ACKED)에서 작성 중으로 돌아갈 때만 1 오른다
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
CREATE TRIGGER tg_srm_po_guard BEFORE INSERT OR UPDATE ON srm_po FOR EACH ROW EXECUTE FUNCTION hb_guard_srm_po();
CREATE TRIGGER tg_srm_po_no_delete BEFORE DELETE ON srm_po FOR EACH ROW EXECUTE FUNCTION hb_forbid_delete_master();

-- ---------------------------------------------------------------------
-- 2. 행
-- ---------------------------------------------------------------------
CREATE TABLE srm_po_line (
    po_line_id        bigint         GENERATED ALWAYS AS IDENTITY,
    comp_cd           varchar(20)    NOT NULL,
    po_id             bigint         NOT NULL,
    line_no           integer        NOT NULL,
    item_id           bigint         NOT NULL,
    item_nm           varchar(200)   NOT NULL,
    supplier_item_cd  varchar(60),
    unit_cd           varchar(30)    NOT NULL,
    qty               numeric(18,6)  NOT NULL,
    unit_price        numeric(18,4)  NOT NULL,
    amt               numeric(20,4)  NOT NULL,
    due_date          date           NOT NULL,
    pr_line_id        bigint,
    cancel_qty        numeric(18,6)  NOT NULL DEFAULT 0,
    line_status       varchar(10)    NOT NULL DEFAULT 'OPEN',
    remark            varchar(500),
    created_at        timestamptz    NOT NULL DEFAULT now(),
    created_by        varchar(50)    NOT NULL,
    updated_at        timestamptz    NOT NULL DEFAULT now(),
    updated_by        varchar(50)    NOT NULL,
    CONSTRAINT pk_srm_po_line PRIMARY KEY (po_line_id),
    CONSTRAINT fk_srm_po_line_po FOREIGN KEY (po_id) REFERENCES srm_po (po_id),
    CONSTRAINT fk_srm_po_line_item FOREIGN KEY (item_id) REFERENCES mst_item (item_id),
    CONSTRAINT fk_srm_po_line_pr_line FOREIGN KEY (pr_line_id) REFERENCES srm_pr_line (pr_line_id),
    CONSTRAINT uq_srm_po_line_no UNIQUE (po_id, line_no),
    CONSTRAINT ck_srm_po_line_no CHECK (line_no BETWEEN 1 AND 9999),
    CONSTRAINT ck_srm_po_line_qty CHECK (qty > 0),
    CONSTRAINT ck_srm_po_line_price CHECK (unit_price > 0),
    CONSTRAINT ck_srm_po_line_cancel CHECK (cancel_qty >= 0 AND cancel_qty <= qty),
    CONSTRAINT ck_srm_po_line_status CHECK (line_status IN ('OPEN', 'CANCELLED', 'CLOSED')),
    CONSTRAINT ck_srm_po_line_due CHECK (due_date BETWEEN DATE '2000-01-01' AND DATE '2999-12-31')
);
CREATE INDEX ix_srm_po_line_po ON srm_po_line (comp_cd, po_id);
CREATE INDEX ix_srm_po_line_pr ON srm_po_line (comp_cd, pr_line_id) WHERE pr_line_id IS NOT NULL;
CREATE INDEX ix_srm_po_line_item ON srm_po_line (comp_cd, item_id);
COMMENT ON TABLE srm_po_line IS '발주 행 — 품명·협력사 품번·단위·단가는 저장 때 스냅샷. 금액 = round(수량 × 단가, 통화 자릿수). 열린 수량 = 수량 − 입고(2부) − 취소';
COMMENT ON COLUMN srm_po_line.cancel_qty IS '취소 수량(2부 부분 마감에서 사용, 1부는 0)';

CREATE FUNCTION hb_guard_srm_po_line() RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    v_row  srm_po_line;
    v_head record;
BEGIN
    v_row := CASE WHEN TG_OP = 'DELETE' THEN OLD ELSE NEW END;
    SELECT p.status, p.comp_cd, p.currency_cd INTO v_head FROM srm_po p WHERE p.po_id = v_row.po_id;
    IF NOT FOUND OR v_head.comp_cd <> v_row.comp_cd THEN
        RAISE EXCEPTION 'purchase order % does not belong to company %', v_row.po_id, v_row.comp_cd
            USING ERRCODE = '23503', CONSTRAINT = 'fk_srm_po_line_po_comp';
    END IF;
    -- 작성 중이 아니면 행 상태·취소 수량(2부)만
    IF v_head.status <> 'DRAFT' THEN
        IF TG_OP <> 'UPDATE' OR (NEW.item_id, NEW.item_nm, NEW.unit_cd, NEW.qty, NEW.unit_price, NEW.amt, NEW.due_date, NEW.line_no, NEW.po_id)
                IS DISTINCT FROM (OLD.item_id, OLD.item_nm, OLD.unit_cd, OLD.qty, OLD.unit_price, OLD.amt, OLD.due_date, OLD.line_no, OLD.po_id)
                OR NEW.pr_line_id IS DISTINCT FROM OLD.pr_line_id OR NEW.supplier_item_cd IS DISTINCT FROM OLD.supplier_item_cd
                OR NEW.remark IS DISTINCT FROM OLD.remark THEN
            RAISE EXCEPTION 'lines of % purchase order % cannot be changed', v_head.status, v_row.po_id
                USING ERRCODE = '55000', CONSTRAINT = 'ck_srm_po_line_draft';
        END IF;
    END IF;
    IF TG_OP = 'DELETE' THEN
        RETURN OLD;
    END IF;
    IF TG_OP = 'UPDATE' AND (NEW.po_id <> OLD.po_id OR NEW.line_no <> OLD.line_no) THEN
        RAISE EXCEPTION 'line cannot move' USING ERRCODE = '55000', CONSTRAINT = 'ck_srm_po_line_move';
    END IF;
    IF NOT hb_item_of_comp(NEW.comp_cd, NEW.item_id) THEN
        RAISE EXCEPTION 'item % does not belong to company %', NEW.item_id, NEW.comp_cd
            USING ERRCODE = '23503', CONSTRAINT = 'fk_srm_po_line_item_comp';
    END IF;
    IF (TG_OP = 'INSERT' OR NEW.unit_cd <> OLD.unit_cd) AND NOT hb_unit_usable(NEW.comp_cd, NEW.unit_cd) THEN
        RAISE EXCEPTION 'unit % is not available for company %', NEW.unit_cd, NEW.comp_cd
            USING ERRCODE = '23503', CONSTRAINT = 'fk_srm_po_line_unit';
    END IF;
    IF NEW.pr_line_id IS NOT NULL AND (TG_OP = 'INSERT' OR NEW.pr_line_id IS DISTINCT FROM OLD.pr_line_id)
       AND NOT EXISTS (SELECT 1 FROM srm_pr_line l WHERE l.pr_line_id = NEW.pr_line_id AND l.comp_cd = NEW.comp_cd
                          AND l.item_id = NEW.item_id) THEN
        RAISE EXCEPTION 'purchase request line % is not a line of company % for the same item', NEW.pr_line_id, NEW.comp_cd
            USING ERRCODE = '23503', CONSTRAINT = 'fk_srm_po_line_pr_comp';
    END IF;
    -- 금액 = 행마다 통화 자릿수로 반올림(발주서 표기와 같게) — 화면·직접 SQL 모두
    IF NEW.amt <> round(NEW.qty * NEW.unit_price, hb_currency_scale(v_head.currency_cd)) THEN
        RAISE EXCEPTION 'line amount % is not round(qty x price)', NEW.amt USING ERRCODE = '23514', CONSTRAINT = 'ck_srm_po_line_amt';
    END IF;
    RETURN NEW;
END
$$;
CREATE TRIGGER tg_srm_po_line_guard BEFORE INSERT OR UPDATE OR DELETE ON srm_po_line
    FOR EACH ROW EXECUTE FUNCTION hb_guard_srm_po_line();

-- ---------------------------------------------------------------------
-- 3. 발행본 스냅샷 (추가만)
-- ---------------------------------------------------------------------
CREATE TABLE srm_po_rev (
    po_rev_id   bigint        GENERATED ALWAYS AS IDENTITY,
    comp_cd     varchar(20)   NOT NULL,
    po_id       bigint        NOT NULL,
    rev         integer       NOT NULL,
    issued_at   timestamptz   NOT NULL DEFAULT now(),
    issued_by   varchar(50)   NOT NULL,
    snapshot    jsonb         NOT NULL,
    created_at  timestamptz   NOT NULL DEFAULT now(),
    created_by  varchar(50)   NOT NULL,
    CONSTRAINT pk_srm_po_rev PRIMARY KEY (po_rev_id),
    CONSTRAINT fk_srm_po_rev_po FOREIGN KEY (po_id) REFERENCES srm_po (po_id),
    CONSTRAINT uq_srm_po_rev UNIQUE (comp_cd, po_id, rev),
    CONSTRAINT ck_srm_po_rev_snapshot CHECK (jsonb_typeof(snapshot) = 'object')
);
COMMENT ON TABLE srm_po_rev IS '발주 발행본 스냅샷(머리 + 행) — 개정마다 한 행, 수정·삭제 금지. 회신(S5)은 이 개정 번호를 들고 있다';

CREATE FUNCTION hb_guard_srm_po_rev() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP <> 'INSERT' THEN
        RAISE EXCEPTION 'issued purchase order snapshots are append-only' USING ERRCODE = '55000', CONSTRAINT = 'ck_srm_po_rev_append_only';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM srm_po p WHERE p.po_id = NEW.po_id AND p.comp_cd = NEW.comp_cd) THEN
        RAISE EXCEPTION 'purchase order % does not belong to company %', NEW.po_id, NEW.comp_cd
            USING ERRCODE = '23503', CONSTRAINT = 'fk_srm_po_rev_po_comp';
    END IF;
    RETURN NEW;
END
$$;
CREATE TRIGGER tg_srm_po_rev_guard BEFORE INSERT OR UPDATE OR DELETE ON srm_po_rev
    FOR EACH ROW EXECUTE FUNCTION hb_guard_srm_po_rev();

-- ---------------------------------------------------------------------
-- 4. 감사 · RLS · 권한 · 화면 · 보존
-- ---------------------------------------------------------------------
CREATE TRIGGER tg_audit AFTER INSERT OR UPDATE OR DELETE ON srm_po FOR EACH ROW EXECUTE FUNCTION hb_audit('po_id', '', '');
CREATE TRIGGER tg_audit AFTER INSERT OR UPDATE OR DELETE ON srm_po_line FOR EACH ROW EXECUTE FUNCTION hb_audit('po_line_id', '', '');
CREATE TRIGGER tg_audit AFTER INSERT OR UPDATE OR DELETE ON srm_po_rev FOR EACH ROW EXECUTE FUNCTION hb_audit('po_rev_id', 'snapshot', '');

ALTER TABLE srm_po ENABLE ROW LEVEL SECURITY;
ALTER TABLE srm_po FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON srm_po USING (comp_cd = hb_current_comp()) WITH CHECK (comp_cd = hb_current_comp());
ALTER TABLE srm_po_line ENABLE ROW LEVEL SECURITY;
ALTER TABLE srm_po_line FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON srm_po_line USING (comp_cd = hb_current_comp()) WITH CHECK (comp_cd = hb_current_comp());
ALTER TABLE srm_po_rev ENABLE ROW LEVEL SECURITY;
ALTER TABLE srm_po_rev FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON srm_po_rev USING (comp_cd = hb_current_comp()) WITH CHECK (comp_cd = hb_current_comp());
REVOKE DELETE, TRUNCATE ON srm_po, srm_po_rev FROM ${app_role};
REVOKE UPDATE ON srm_po_rev FROM ${app_role};
REVOKE TRUNCATE ON srm_po_line FROM ${app_role};

-- 승인된 구매요청 취소는 그 요청 행을 가리키는 살아 있는 발주 행이 없을 때만 (docs/31 4.2 남은 것)
CREATE OR REPLACE FUNCTION hb_guard_srm_pr_cancel() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.status = 'CANCELLED' AND OLD.status <> 'CANCELLED'
       AND EXISTS (SELECT 1 FROM srm_po_line l JOIN srm_pr_line r ON r.pr_line_id = l.pr_line_id
                     JOIN srm_po p ON p.po_id = l.po_id
                    WHERE r.pr_id = NEW.pr_id AND p.status <> 'CANCELLED' AND l.line_status <> 'CANCELLED') THEN
        RAISE EXCEPTION 'purchase request % has purchase order lines', NEW.pr_id USING ERRCODE = '55000', CONSTRAINT = 'ck_srm_pr_has_po';
    END IF;
    RETURN NEW;
END
$$;
CREATE TRIGGER tg_srm_pr_cancel_guard BEFORE UPDATE ON srm_pr FOR EACH ROW EXECUTE FUNCTION hb_guard_srm_pr_cancel();

INSERT INTO sys_prog (prog_cd, prog_nm_key, view_path, module_cd, actions, sort_no) VALUES
    ('srm-po', 'prog.srmPo', 'srm/po-mgt', 'SRM', ARRAY['SEARCH','SAVE','APPROVE']::varchar(20)[], 520);
INSERT INTO sys_retention_table (table_nm, class_cd) VALUES
    ('srm_po', 'MASTER'), ('srm_po_line', 'MASTER'), ('srm_po_rev', 'MASTER');
