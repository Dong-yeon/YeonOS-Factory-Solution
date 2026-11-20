-- =====================================================================
-- SRM 2부 R4c·R4d 골격 — 반품 지시 srm_return · 클레임 srm_claim (docs/31 4.12.4, 결정 R4-1·3·4·6) — 임시 번호, 병합 때 재번호
--
-- - 표와 DB 규칙만. 서비스·화면은 QMS 계약 ReturnPage(#348) 뒤(반품 대상 목록에서 만들기)
-- - 근거 NCR 은 QMS 공개 계약(SupplierQualityQuery)으로만 읽는다 → QMS 표에 FK 를 걸지 않고 id + 번호 스냅샷.
--   (회사, NCR, 처리 줄) 은 살아 있는 반품 하나 / (회사, NCR) 은 살아 있는 클레임 하나 — 두 번 만들기 방지(R4-1 멱등)
-- - 반품 수량 ≤ 그 처리 줄 반품 수량 − 이미 반품한 양: 계약 값이 필요해 서비스가 본다(DB 는 수량 > 0 만)
-- - 반품 출고는 WMS(R4-4) — SHIPPED 는 나중 WMS 게이트가 넣는다. 교체 요구 반품은 발주 행 필수(R4-3, 입고 되돌림 대상)
-- - 클레임 금액은 표기용(청구·전표는 ERP), 협력사 회신은 최소 칸(원인·대책·회신일 — R4-6)
-- =====================================================================

INSERT INTO sys_code_grp (grp_cd, grp_nm_key, comp_ext_yn) VALUES
    ('RETURN_STATUS', 'code.grp.RETURN_STATUS', 'N'),
    ('CLAIM_STATUS',  'code.grp.CLAIM_STATUS',  'N'),
    ('CLAIM_ACTION',  'code.grp.CLAIM_ACTION',  'N');
INSERT INTO sys_code (grp_cd, code, code_nm_key, sort_no) VALUES
    ('RETURN_STATUS', 'DRAFT',     'code.RETURN_STATUS.DRAFT',     1),
    ('RETURN_STATUS', 'CONFIRMED', 'code.RETURN_STATUS.CONFIRMED', 2),
    ('RETURN_STATUS', 'SHIPPED',   'code.RETURN_STATUS.SHIPPED',   3),
    ('RETURN_STATUS', 'CANCELLED', 'code.RETURN_STATUS.CANCELLED', 4),
    ('CLAIM_STATUS',  'DRAFT',     'code.CLAIM_STATUS.DRAFT',      1),
    ('CLAIM_STATUS',  'SENT',      'code.CLAIM_STATUS.SENT',       2),
    ('CLAIM_STATUS',  'ANSWERED',  'code.CLAIM_STATUS.ANSWERED',   3),
    ('CLAIM_STATUS',  'CLOSED',    'code.CLAIM_STATUS.CLOSED',     4),
    ('CLAIM_STATUS',  'CANCELLED', 'code.CLAIM_STATUS.CANCELLED',  5),
    ('CLAIM_ACTION',  'CAPA',      'code.CLAIM_ACTION.CAPA',       1),
    ('CLAIM_ACTION',  'CHARGE',    'code.CLAIM_ACTION.CHARGE',     2),
    ('CLAIM_ACTION',  'BOTH',      'code.CLAIM_ACTION.BOTH',       3);

-- ---------------------------------------------------------------------
-- 1. 반품 지시
-- ---------------------------------------------------------------------
CREATE TABLE srm_return (
    return_id      bigint         GENERATED ALWAYS AS IDENTITY,
    comp_cd        varchar(20)    NOT NULL,
    return_no      varchar(30)    NOT NULL,
    cust_id        bigint         NOT NULL,
    plant_cd       varchar(20)    NOT NULL,
    ncr_id         bigint         NOT NULL,
    ncr_no         varchar(30)    NOT NULL,
    ncr_line_no    integer        NOT NULL,
    lot_id         bigint         NOT NULL,
    lot_no         varchar(60)    NOT NULL,
    po_line_id     bigint,
    item_id        bigint         NOT NULL,
    unit_cd        varchar(30)    NOT NULL,
    qty            numeric(18,6)  NOT NULL,
    replace_yn     char(1)        NOT NULL DEFAULT 'N',
    status         varchar(20)    NOT NULL DEFAULT 'DRAFT',
    confirmed_at   timestamptz,
    confirmed_by   varchar(50),
    shipped_at     timestamptz,
    cancel_reason  varchar(500),
    remark         varchar(1000),
    row_ver        integer        NOT NULL DEFAULT 0,
    created_at     timestamptz    NOT NULL DEFAULT now(),
    created_by     varchar(50)    NOT NULL,
    updated_at     timestamptz    NOT NULL DEFAULT now(),
    updated_by     varchar(50)    NOT NULL,
    CONSTRAINT pk_srm_return PRIMARY KEY (return_id),
    CONSTRAINT fk_srm_return_supplier FOREIGN KEY (cust_id) REFERENCES srm_supplier (cust_id),
    CONSTRAINT fk_srm_return_plant FOREIGN KEY (comp_cd, plant_cd) REFERENCES sys_plant (comp_cd, plant_cd),
    CONSTRAINT fk_srm_return_po_line FOREIGN KEY (po_line_id) REFERENCES srm_po_line (po_line_id),
    CONSTRAINT fk_srm_return_item FOREIGN KEY (item_id) REFERENCES mst_item (item_id),
    CONSTRAINT uq_srm_return_no UNIQUE (comp_cd, return_no),
    CONSTRAINT ck_srm_return_status CHECK (status IN ('DRAFT', 'CONFIRMED', 'SHIPPED', 'CANCELLED')),
    CONSTRAINT ck_srm_return_qty CHECK (qty > 0),
    CONSTRAINT ck_srm_return_line_no CHECK (ncr_line_no BETWEEN 1 AND 9999),
    CONSTRAINT ck_srm_return_replace CHECK (replace_yn IN ('Y', 'N')),
    -- 교체 요구면 어느 발주 행 입고를 되돌릴지 알아야 한다(R4-3)
    CONSTRAINT ck_srm_return_replace_po CHECK (replace_yn = 'N' OR po_line_id IS NOT NULL),
    -- CHECK 는 NULL 을 통과로 본다 — coalesce 로
    CONSTRAINT ck_srm_return_cancel CHECK ((status = 'CANCELLED') = (coalesce(length(btrim(cancel_reason)), 0) > 0)),
    CONSTRAINT ck_srm_return_confirmed CHECK (status IN ('DRAFT', 'CANCELLED') OR confirmed_at IS NOT NULL),
    CONSTRAINT ck_srm_return_shipped CHECK ((status = 'SHIPPED') = (shipped_at IS NOT NULL))
);
-- 같은 NCR 처리 줄에 살아 있는 반품 하나(취소하면 다시 만들 수 있다)
CREATE UNIQUE INDEX uq_srm_return_ncr_line ON srm_return (comp_cd, ncr_id, ncr_line_no) WHERE status <> 'CANCELLED';
CREATE INDEX ix_srm_return_cust ON srm_return (comp_cd, cust_id, created_at);
COMMENT ON TABLE srm_return IS '반품 지시 (docs/31 4.12.4) — 근거 = 닫힌 NCR 의 RETURN 처리 줄(QMS 계약). 출고는 WMS, SHIPPED 는 WMS 게이트';
COMMENT ON COLUMN srm_return.ncr_id IS 'QMS NCR id — QMS 표에 FK 없음(공개 계약으로만 읽음), 회사·협력사 확인은 서비스가 계약 값으로';

CREATE FUNCTION hb_guard_srm_return() RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    v_line record;
BEGIN
    IF TG_OP = 'INSERT' THEN
        IF NEW.status <> 'DRAFT' THEN
            RAISE EXCEPTION 'new return must be DRAFT' USING ERRCODE = '55000', CONSTRAINT = 'ck_srm_return_transition';
        END IF;
        IF NOT hb_srm_supplier_of_comp(NEW.comp_cd, NEW.cust_id) THEN
            RAISE EXCEPTION 'supplier % is not of company %', NEW.cust_id, NEW.comp_cd
                USING ERRCODE = '23503', CONSTRAINT = 'fk_srm_return_supplier_comp';
        END IF;
    ELSE
        IF (NEW.return_no, NEW.comp_cd, NEW.cust_id, NEW.ncr_id, NEW.ncr_no, NEW.ncr_line_no, NEW.lot_id, NEW.lot_no, NEW.item_id, NEW.unit_cd,
            NEW.created_at, NEW.created_by)
           IS DISTINCT FROM (OLD.return_no, OLD.comp_cd, OLD.cust_id, OLD.ncr_id, OLD.ncr_no, OLD.ncr_line_no, OLD.lot_id, OLD.lot_no, OLD.item_id,
            OLD.unit_cd, OLD.created_at, OLD.created_by) THEN
            RAISE EXCEPTION 'number, supplier and basis are fixed' USING ERRCODE = '55000', CONSTRAINT = 'ck_srm_return_fixed';
        END IF;
        IF OLD.status <> 'DRAFT' AND (NEW.plant_cd, NEW.po_line_id, NEW.qty, NEW.replace_yn, NEW.remark)
                                     IS DISTINCT FROM (OLD.plant_cd, OLD.po_line_id, OLD.qty, OLD.replace_yn, OLD.remark) THEN
            RAISE EXCEPTION '% return cannot be edited', OLD.status USING ERRCODE = '55000', CONSTRAINT = 'ck_srm_return_draft';
        END IF;
        IF NEW.status <> OLD.status
           AND NOT ((OLD.status = 'DRAFT' AND NEW.status IN ('CONFIRMED', 'CANCELLED'))
                 OR (OLD.status = 'CONFIRMED' AND NEW.status IN ('SHIPPED', 'CANCELLED'))) THEN
            RAISE EXCEPTION 'return cannot go from % to %', OLD.status, NEW.status USING ERRCODE = '55000', CONSTRAINT = 'ck_srm_return_transition';
        END IF;
    END IF;
    -- 발주 행: 같은 회사·같은 협력사·같은 품목·단위의 SRM 발주 행
    IF NEW.po_line_id IS NOT NULL AND (TG_OP = 'INSERT' OR NEW.po_line_id IS DISTINCT FROM OLD.po_line_id) THEN
        SELECT l.comp_cd, l.item_id, l.unit_cd, p.cust_id INTO v_line
          FROM srm_po_line l JOIN srm_po p ON p.po_id = l.po_id WHERE l.po_line_id = NEW.po_line_id;
        IF NOT FOUND OR v_line.comp_cd <> NEW.comp_cd OR v_line.cust_id <> NEW.cust_id THEN
            RAISE EXCEPTION 'order line % is not a line of the same company and supplier', NEW.po_line_id
                USING ERRCODE = '23503', CONSTRAINT = 'fk_srm_return_po_line_comp';
        END IF;
        IF v_line.item_id <> NEW.item_id OR v_line.unit_cd <> NEW.unit_cd THEN
            RAISE EXCEPTION 'item and unit must be those of the order line' USING ERRCODE = '23514', CONSTRAINT = 'ck_srm_return_po_item';
        END IF;
    END IF;
    RETURN NEW;
END
$$;
CREATE TRIGGER tg_srm_return_guard BEFORE INSERT OR UPDATE ON srm_return FOR EACH ROW EXECUTE FUNCTION hb_guard_srm_return();
CREATE TRIGGER tg_srm_return_no_delete BEFORE DELETE ON srm_return FOR EACH ROW EXECUTE FUNCTION hb_forbid_delete_master();

-- ---------------------------------------------------------------------
-- 2. 클레임 머리
-- ---------------------------------------------------------------------
CREATE TABLE srm_claim (
    claim_id        bigint         GENERATED ALWAYS AS IDENTITY,
    comp_cd         varchar(20)    NOT NULL,
    claim_no        varchar(30)    NOT NULL,
    cust_id         bigint         NOT NULL,
    action_cd       varchar(10)    NOT NULL,
    title           varchar(200)   NOT NULL,
    request_text    varchar(2000)  NOT NULL,
    reply_due       date,
    charge_amt      numeric(20,4),
    currency_cd     varchar(30),
    status          varchar(20)    NOT NULL DEFAULT 'DRAFT',
    sent_at         timestamptz,
    sent_by         varchar(50),
    answer_cause    varchar(2000),
    answer_action   varchar(2000),
    answered_on     date,
    closed_at       timestamptz,
    closed_by       varchar(50),
    cancel_reason   varchar(500),
    row_ver         integer        NOT NULL DEFAULT 0,
    created_at      timestamptz    NOT NULL DEFAULT now(),
    created_by      varchar(50)    NOT NULL,
    updated_at      timestamptz    NOT NULL DEFAULT now(),
    updated_by      varchar(50)    NOT NULL,
    CONSTRAINT pk_srm_claim PRIMARY KEY (claim_id),
    CONSTRAINT fk_srm_claim_supplier FOREIGN KEY (cust_id) REFERENCES srm_supplier (cust_id),
    CONSTRAINT uq_srm_claim_no UNIQUE (comp_cd, claim_no),
    CONSTRAINT ck_srm_claim_status CHECK (status IN ('DRAFT', 'SENT', 'ANSWERED', 'CLOSED', 'CANCELLED')),
    CONSTRAINT ck_srm_claim_action CHECK (action_cd IN ('CAPA', 'CHARGE', 'BOTH')),
    CONSTRAINT ck_srm_claim_title CHECK (length(btrim(title)) > 0 AND length(btrim(request_text)) > 0),
    -- 비용 청구가 들면 금액(> 0)·통화, 시정조치만이면 금액 없음
    CONSTRAINT ck_srm_claim_charge CHECK (CASE WHEN action_cd = 'CAPA' THEN charge_amt IS NULL AND currency_cd IS NULL
                                               ELSE charge_amt > 0 AND currency_cd IS NOT NULL END),
    CONSTRAINT ck_srm_claim_due CHECK (reply_due IS NULL OR reply_due BETWEEN DATE '2000-01-01' AND DATE '2999-12-31'),
    CONSTRAINT ck_srm_claim_cancel CHECK ((status = 'CANCELLED') = (coalesce(length(btrim(cancel_reason)), 0) > 0)),
    CONSTRAINT ck_srm_claim_sent CHECK (status IN ('DRAFT', 'CANCELLED') OR sent_at IS NOT NULL),
    -- 회신(원인·대책·회신일)은 ANSWERED·CLOSED 에 반드시, 작성 중엔 없음
    CONSTRAINT ck_srm_claim_answer CHECK (CASE WHEN status IN ('ANSWERED', 'CLOSED')
                                                   THEN coalesce(length(btrim(answer_cause)), 0) > 0
                                                        AND coalesce(length(btrim(answer_action)), 0) > 0 AND answered_on IS NOT NULL
                                               WHEN status = 'DRAFT' THEN answer_cause IS NULL AND answer_action IS NULL AND answered_on IS NULL
                                               ELSE true END),
    CONSTRAINT ck_srm_claim_closed CHECK ((status = 'CLOSED') = (closed_at IS NOT NULL))
);
CREATE INDEX ix_srm_claim_cust ON srm_claim (comp_cd, cust_id, created_at);
COMMENT ON TABLE srm_claim IS '협력사 클레임 (docs/31 4.12.4, R4-6) — 시정조치 요구·비용 청구 표기(청구·전표는 ERP), 협력사 회신 최소 칸';

CREATE FUNCTION hb_guard_srm_claim() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'INSERT' THEN
        IF NEW.status <> 'DRAFT' THEN
            RAISE EXCEPTION 'new claim must be DRAFT' USING ERRCODE = '55000', CONSTRAINT = 'ck_srm_claim_transition';
        END IF;
        IF NOT hb_srm_supplier_of_comp(NEW.comp_cd, NEW.cust_id) THEN
            RAISE EXCEPTION 'supplier % is not of company %', NEW.cust_id, NEW.comp_cd
                USING ERRCODE = '23503', CONSTRAINT = 'fk_srm_claim_supplier_comp';
        END IF;
    ELSE
        IF (NEW.claim_no, NEW.comp_cd, NEW.cust_id, NEW.created_at, NEW.created_by)
           IS DISTINCT FROM (OLD.claim_no, OLD.comp_cd, OLD.cust_id, OLD.created_at, OLD.created_by) THEN
            RAISE EXCEPTION 'number and supplier are fixed' USING ERRCODE = '55000', CONSTRAINT = 'ck_srm_claim_fixed';
        END IF;
        -- 요구 내용은 작성 중만, 회신 칸은 보낸 뒤(SENT) 또는 회신으로 넘어갈 때만
        IF OLD.status <> 'DRAFT' AND (NEW.action_cd, NEW.title, NEW.request_text, NEW.reply_due, NEW.charge_amt, NEW.currency_cd)
                                     IS DISTINCT FROM (OLD.action_cd, OLD.title, OLD.request_text, OLD.reply_due, OLD.charge_amt, OLD.currency_cd) THEN
            RAISE EXCEPTION '% claim request cannot be edited', OLD.status USING ERRCODE = '55000', CONSTRAINT = 'ck_srm_claim_draft';
        END IF;
        IF OLD.status NOT IN ('SENT', 'ANSWERED') AND (NEW.answer_cause, NEW.answer_action, NEW.answered_on)
                                                      IS DISTINCT FROM (OLD.answer_cause, OLD.answer_action, OLD.answered_on) THEN
            RAISE EXCEPTION 'answer of % claim cannot be changed', OLD.status USING ERRCODE = '55000', CONSTRAINT = 'ck_srm_claim_answer_state';
        END IF;
        IF NEW.status <> OLD.status THEN
            -- ANSWERED → SENT = 회신 반려(다시 요구)
            IF NOT ((OLD.status = 'DRAFT' AND NEW.status IN ('SENT', 'CANCELLED'))
                 OR (OLD.status = 'SENT' AND NEW.status IN ('ANSWERED', 'CANCELLED'))
                 OR (OLD.status = 'ANSWERED' AND NEW.status IN ('CLOSED', 'SENT'))) THEN
                RAISE EXCEPTION 'claim cannot go from % to %', OLD.status, NEW.status USING ERRCODE = '55000', CONSTRAINT = 'ck_srm_claim_transition';
            END IF;
            IF OLD.status = 'DRAFT' AND NEW.status = 'SENT'
               AND NOT EXISTS (SELECT 1 FROM srm_claim_ncr n WHERE n.claim_id = NEW.claim_id) THEN
                RAISE EXCEPTION 'claim has no nonconformance' USING ERRCODE = '55000', CONSTRAINT = 'ck_srm_claim_ncrs';
            END IF;
        END IF;
    END IF;
    IF NEW.currency_cd IS NOT NULL AND (TG_OP = 'INSERT' OR NEW.currency_cd IS DISTINCT FROM OLD.currency_cd)
       AND NOT hb_code_usable(NEW.comp_cd, 'CURRENCY', NEW.currency_cd) THEN
        RAISE EXCEPTION 'currency % is not usable', NEW.currency_cd USING ERRCODE = '23503', CONSTRAINT = 'fk_srm_claim_currency';
    END IF;
    RETURN NEW;
END
$$;
CREATE TRIGGER tg_srm_claim_guard BEFORE INSERT OR UPDATE ON srm_claim FOR EACH ROW EXECUTE FUNCTION hb_guard_srm_claim();
CREATE TRIGGER tg_srm_claim_no_delete BEFORE DELETE ON srm_claim FOR EACH ROW EXECUTE FUNCTION hb_forbid_delete_master();

-- ---------------------------------------------------------------------
-- 3. 클레임 근거 NCR (줄)
-- ---------------------------------------------------------------------
CREATE TABLE srm_claim_ncr (
    claim_ncr_id  bigint         GENERATED ALWAYS AS IDENTITY,
    comp_cd       varchar(20)    NOT NULL,
    claim_id      bigint         NOT NULL,
    ncr_id        bigint         NOT NULL,
    ncr_no        varchar(30)    NOT NULL,
    item_id       bigint         NOT NULL,
    nc_qty        numeric(18,6)  NOT NULL,
    unit_cd       varchar(30)    NOT NULL,
    created_at    timestamptz    NOT NULL DEFAULT now(),
    created_by    varchar(50)    NOT NULL,
    updated_at    timestamptz    NOT NULL DEFAULT now(),
    updated_by    varchar(50)    NOT NULL,
    CONSTRAINT pk_srm_claim_ncr PRIMARY KEY (claim_ncr_id),
    CONSTRAINT fk_srm_claim_ncr_claim FOREIGN KEY (claim_id) REFERENCES srm_claim (claim_id),
    CONSTRAINT fk_srm_claim_ncr_item FOREIGN KEY (item_id) REFERENCES mst_item (item_id),
    CONSTRAINT uq_srm_claim_ncr UNIQUE (claim_id, ncr_id),
    CONSTRAINT ck_srm_claim_ncr_qty CHECK (nc_qty > 0)
);
CREATE INDEX ix_srm_claim_ncr_ncr ON srm_claim_ncr (comp_cd, ncr_id);
COMMENT ON TABLE srm_claim_ncr IS '클레임 근거 NCR — id·번호·수량 스냅샷(QMS 계약 값), 작성 중만 편집. 한 NCR 은 살아 있는 클레임 하나';

CREATE FUNCTION hb_guard_srm_claim_ncr() RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    v_row  srm_claim_ncr;
    v_head record;
BEGIN
    v_row := CASE WHEN TG_OP = 'DELETE' THEN OLD ELSE NEW END;
    SELECT c.status, c.comp_cd INTO v_head FROM srm_claim c WHERE c.claim_id = v_row.claim_id;
    IF NOT FOUND OR v_head.comp_cd <> v_row.comp_cd THEN
        RAISE EXCEPTION 'claim % does not belong to company %', v_row.claim_id, v_row.comp_cd
            USING ERRCODE = '23503', CONSTRAINT = 'fk_srm_claim_ncr_claim_comp';
    END IF;
    IF v_head.status <> 'DRAFT' THEN
        RAISE EXCEPTION 'nonconformances of % claim cannot be changed', v_head.status USING ERRCODE = '55000', CONSTRAINT = 'ck_srm_claim_ncr_draft';
    END IF;
    IF TG_OP = 'DELETE' THEN
        RETURN OLD;
    END IF;
    IF TG_OP = 'UPDATE' AND (NEW.claim_id, NEW.ncr_id) IS DISTINCT FROM (OLD.claim_id, OLD.ncr_id) THEN
        RAISE EXCEPTION 'line cannot move' USING ERRCODE = '55000', CONSTRAINT = 'ck_srm_claim_ncr_move';
    END IF;
    -- 한 NCR 은 살아 있는(취소 아닌) 클레임 하나 — 부모 상태를 봐야 해서 부분 유일 색인 대신
    IF EXISTS (SELECT 1 FROM srm_claim_ncr n JOIN srm_claim c ON c.claim_id = n.claim_id
                WHERE n.comp_cd = NEW.comp_cd AND n.ncr_id = NEW.ncr_id AND n.claim_id <> NEW.claim_id AND c.status <> 'CANCELLED') THEN
        RAISE EXCEPTION 'nonconformance % is already in another claim', NEW.ncr_id USING ERRCODE = '23505', CONSTRAINT = 'uq_srm_claim_ncr_live';
    END IF;
    RETURN NEW;
END
$$;
CREATE TRIGGER tg_srm_claim_ncr_guard BEFORE INSERT OR UPDATE OR DELETE ON srm_claim_ncr
    FOR EACH ROW EXECUTE FUNCTION hb_guard_srm_claim_ncr();

-- ---------------------------------------------------------------------
-- 4. 감사 · RLS · 권한 · 보존 (화면 sys_prog 는 서비스 PR 에서)
-- ---------------------------------------------------------------------
CREATE TRIGGER tg_audit AFTER INSERT OR UPDATE OR DELETE ON srm_return FOR EACH ROW EXECUTE FUNCTION hb_audit('return_id', '', '');
CREATE TRIGGER tg_audit AFTER INSERT OR UPDATE OR DELETE ON srm_claim FOR EACH ROW EXECUTE FUNCTION hb_audit('claim_id', '', '');
CREATE TRIGGER tg_audit AFTER INSERT OR UPDATE OR DELETE ON srm_claim_ncr FOR EACH ROW EXECUTE FUNCTION hb_audit('claim_ncr_id', '', '');

ALTER TABLE srm_return ENABLE ROW LEVEL SECURITY;
ALTER TABLE srm_return FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON srm_return USING (comp_cd = hb_current_comp()) WITH CHECK (comp_cd = hb_current_comp());
ALTER TABLE srm_claim ENABLE ROW LEVEL SECURITY;
ALTER TABLE srm_claim FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON srm_claim USING (comp_cd = hb_current_comp()) WITH CHECK (comp_cd = hb_current_comp());
ALTER TABLE srm_claim_ncr ENABLE ROW LEVEL SECURITY;
ALTER TABLE srm_claim_ncr FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON srm_claim_ncr USING (comp_cd = hb_current_comp()) WITH CHECK (comp_cd = hb_current_comp());
REVOKE DELETE, TRUNCATE ON srm_return FROM ${app_role};
REVOKE DELETE, TRUNCATE ON srm_claim FROM ${app_role};
REVOKE TRUNCATE ON srm_claim_ncr FROM ${app_role};

INSERT INTO sys_retention_table (table_nm, class_cd) VALUES
    ('srm_return', 'MASTER'), ('srm_claim', 'MASTER'), ('srm_claim_ncr', 'MASTER');
