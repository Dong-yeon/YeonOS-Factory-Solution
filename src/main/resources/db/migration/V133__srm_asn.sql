-- =====================================================================
-- SRM 2부 R3a 사전 납품 통지 ASN (docs/31 4.11) — 임시 번호, 병합 때 재번호
--
-- - srm_asn: 머리(번호 SRM_ASN·협력사·공장·출하일·도착 예정일·협력사 납품서 번호·출처), 상태
--     DRAFT → SENT → PARTIAL_RECEIVED → RECEIVED / CLOSED(사람 마감) / CANCELLED(받은 것 없을 때)
-- - srm_asn_line: 행(발주 행 × 공급사 LOT, 발주 단위 수량·제조일·유통기한·포장 수) — 작성 중만 편집
-- - 받은 양 = Σ 입고 이력(srm_po_receipt.asn_line_id) — 게이트가 연결(asnLineId, 되돌림은 reversesRef)
-- - ASN 한도(R3-4): 확정(SENT) 때만 Σ 남은 ASN ≤ (주문 × (1 + SRM_RECV_OVER_PCT/100)) − 입고. 입고 때 ASN 초과는 막지 않는다
-- - 화면 srm-asn(목록·입력·확정), srm-asn-print(납품서 인쇄, 메뉴 없음)
-- =====================================================================

INSERT INTO sys_code_grp (grp_cd, grp_nm_key, comp_ext_yn) VALUES
    ('ASN_STATUS', 'code.grp.ASN_STATUS', 'N');
INSERT INTO sys_code (grp_cd, code, code_nm_key, sort_no) VALUES
    ('ASN_STATUS', 'DRAFT',            'code.ASN_STATUS.DRAFT',            1),
    ('ASN_STATUS', 'SENT',             'code.ASN_STATUS.SENT',             2),
    ('ASN_STATUS', 'PARTIAL_RECEIVED', 'code.ASN_STATUS.PARTIAL_RECEIVED', 3),
    ('ASN_STATUS', 'RECEIVED',         'code.ASN_STATUS.RECEIVED',         4),
    ('ASN_STATUS', 'CLOSED',           'code.ASN_STATUS.CLOSED',           5),
    ('ASN_STATUS', 'CANCELLED',        'code.ASN_STATUS.CANCELLED',        6);

-- 구매 입고 허용 초과율(공장 값 → 회사 값 → 기본) — 서비스(PolicyService)와 같은 순서
CREATE FUNCTION hb_srm_recv_over_pct(p_comp varchar, p_plant varchar) RETURNS integer
    LANGUAGE sql STABLE
AS $$
    SELECT coalesce((SELECT p.policy_value::int FROM sys_policy p
                      WHERE p.comp_cd = p_comp AND p.policy_key = 'SRM_RECV_OVER_PCT' AND p.plant_cd IN (p_plant, '*')
                      ORDER BY CASE WHEN p.plant_cd = '*' THEN 1 ELSE 0 END LIMIT 1),
                    (SELECT d.default_value::int FROM sys_policy_def d WHERE d.policy_key = 'SRM_RECV_OVER_PCT'), 0)
$$;

-- ---------------------------------------------------------------------
-- 1. 머리
-- ---------------------------------------------------------------------
CREATE TABLE srm_asn (
    asn_id         bigint        GENERATED ALWAYS AS IDENTITY,
    comp_cd        varchar(20)   NOT NULL,
    asn_no         varchar(30)   NOT NULL,
    cust_id        bigint        NOT NULL,
    plant_cd       varchar(20)   NOT NULL,
    ship_date      date          NOT NULL,
    eta_date       date          NOT NULL,
    supplier_ref   varchar(60),
    carrier_memo   varchar(200),
    source         varchar(10)   NOT NULL DEFAULT 'MANUAL',
    import_job_id  bigint,
    status         varchar(20)   NOT NULL DEFAULT 'DRAFT',
    sent_at        timestamptz,
    sent_by        varchar(50),
    cancel_reason  varchar(500),
    remark         varchar(1000),
    row_ver        integer       NOT NULL DEFAULT 0,
    created_at     timestamptz   NOT NULL DEFAULT now(),
    created_by     varchar(50)   NOT NULL,
    updated_at     timestamptz   NOT NULL DEFAULT now(),
    updated_by     varchar(50)   NOT NULL,
    CONSTRAINT pk_srm_asn PRIMARY KEY (asn_id),
    CONSTRAINT fk_srm_asn_supplier FOREIGN KEY (cust_id) REFERENCES srm_supplier (cust_id),
    CONSTRAINT fk_srm_asn_plant FOREIGN KEY (comp_cd, plant_cd) REFERENCES sys_plant (comp_cd, plant_cd),
    CONSTRAINT fk_srm_asn_job FOREIGN KEY (import_job_id) REFERENCES sys_import_job (job_id),
    CONSTRAINT uq_srm_asn_no UNIQUE (comp_cd, asn_no),
    CONSTRAINT ck_srm_asn_status CHECK (status IN ('DRAFT', 'SENT', 'PARTIAL_RECEIVED', 'RECEIVED', 'CLOSED', 'CANCELLED')),
    CONSTRAINT ck_srm_asn_source CHECK (source IN ('MANUAL', 'IMPORT')),
    CONSTRAINT ck_srm_asn_job CHECK ((source = 'IMPORT') = (import_job_id IS NOT NULL)),
    CONSTRAINT ck_srm_asn_dates CHECK (ship_date BETWEEN DATE '2000-01-01' AND DATE '2999-12-31' AND eta_date >= ship_date
                                       AND eta_date <= DATE '2999-12-31'),
    -- CHECK 는 NULL 을 통과로 본다 — coalesce 로
    CONSTRAINT ck_srm_asn_cancel CHECK ((status = 'CANCELLED') = (coalesce(length(btrim(cancel_reason)), 0) > 0)),
    CONSTRAINT ck_srm_asn_sent CHECK (status = 'DRAFT' OR status = 'CANCELLED' OR sent_at IS NOT NULL),
    CONSTRAINT ck_srm_asn_ref CHECK (supplier_ref IS NULL OR length(btrim(supplier_ref)) > 0)
);
-- 같은 협력사 납품서 번호는 살아 있는 ASN 하나(취소하면 다시 쓸 수 있다)
CREATE UNIQUE INDEX uq_srm_asn_supplier_ref ON srm_asn (comp_cd, cust_id, supplier_ref) WHERE supplier_ref IS NOT NULL AND status <> 'CANCELLED';
CREATE INDEX ix_srm_asn_eta ON srm_asn (comp_cd, plant_cd, eta_date, asn_id);
COMMENT ON TABLE srm_asn IS '사전 납품 통지 머리 (docs/31 4.11) — 한 협력사 × 한 공장. 작성 중만 편집, 확정 = 협력사가 보냈음';
COMMENT ON COLUMN srm_asn.supplier_ref IS '협력사 납품서 번호 — 같은 협력사 안 살아 있는 ASN 하나. 입고 화면 검색 칸(AsnQuery.find)이 쓴다(스캔 키는 ASN:{asn_no} 만)';

CREATE FUNCTION hb_guard_srm_asn() RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    v_any boolean;
    v_all boolean;
    v_bad record;
BEGIN
    IF TG_OP = 'INSERT' THEN
        IF NEW.status <> 'DRAFT' THEN
            RAISE EXCEPTION 'new advance shipping notice must be DRAFT' USING ERRCODE = '55000', CONSTRAINT = 'ck_srm_asn_transition';
        END IF;
        IF NOT hb_srm_supplier_of_comp(NEW.comp_cd, NEW.cust_id) THEN
            RAISE EXCEPTION 'supplier % is not of company %', NEW.cust_id, NEW.comp_cd
                USING ERRCODE = '23503', CONSTRAINT = 'fk_srm_asn_supplier_comp';
        END IF;
        IF NEW.import_job_id IS NOT NULL
           AND NOT EXISTS (SELECT 1 FROM sys_import_job j WHERE j.job_id = NEW.import_job_id AND j.comp_cd = NEW.comp_cd) THEN
            RAISE EXCEPTION 'import job % is not of company %', NEW.import_job_id, NEW.comp_cd
                USING ERRCODE = '23503', CONSTRAINT = 'fk_srm_asn_job_comp';
        END IF;
        RETURN NEW;
    END IF;
    IF (NEW.asn_no, NEW.comp_cd, NEW.cust_id, NEW.source, NEW.created_at, NEW.created_by)
       IS DISTINCT FROM (OLD.asn_no, OLD.comp_cd, OLD.cust_id, OLD.source, OLD.created_at, OLD.created_by)
       OR NEW.import_job_id IS DISTINCT FROM OLD.import_job_id THEN
        RAISE EXCEPTION 'number, supplier and source are fixed' USING ERRCODE = '55000', CONSTRAINT = 'ck_srm_asn_fixed';
    END IF;
    IF OLD.status <> 'DRAFT' AND (NEW.plant_cd, NEW.ship_date, NEW.eta_date) IS DISTINCT FROM (OLD.plant_cd, OLD.ship_date, OLD.eta_date)
       OR OLD.status <> 'DRAFT' AND (NEW.supplier_ref IS DISTINCT FROM OLD.supplier_ref OR NEW.carrier_memo IS DISTINCT FROM OLD.carrier_memo
                                     OR NEW.remark IS DISTINCT FROM OLD.remark) THEN
        RAISE EXCEPTION '% advance shipping notice cannot be edited', OLD.status USING ERRCODE = '55000', CONSTRAINT = 'ck_srm_asn_draft';
    END IF;
    IF NEW.status <> OLD.status THEN
        IF NOT ((OLD.status = 'DRAFT' AND NEW.status IN ('SENT', 'CANCELLED'))
             OR (OLD.status = 'SENT' AND NEW.status IN ('PARTIAL_RECEIVED', 'RECEIVED', 'CLOSED', 'CANCELLED'))
             OR (OLD.status IN ('PARTIAL_RECEIVED', 'RECEIVED')
                 AND NEW.status IN ('SENT', 'PARTIAL_RECEIVED', 'RECEIVED', 'CLOSED'))) THEN
            RAISE EXCEPTION 'advance shipping notice cannot go from % to %', OLD.status, NEW.status
                USING ERRCODE = '55000', CONSTRAINT = 'ck_srm_asn_transition';
        END IF;
        SELECT * INTO v_any, v_all FROM hb_srm_asn_recv_state(NEW.asn_id);
        -- 받은 것이 있으면 취소 불가(D6 과 같은 규칙)
        IF NEW.status = 'CANCELLED' AND v_any THEN
            RAISE EXCEPTION 'a received advance shipping notice cannot be cancelled' USING ERRCODE = '55000', CONSTRAINT = 'ck_srm_asn_cancel_received';
        END IF;
        -- 입고 상태로 들고 나는 전이는 받은 양과 맞아야(마감·취소 제외 — 발주 ck_srm_po_recv_status 와 같은 모양)
        IF NEW.status IN ('SENT', 'PARTIAL_RECEIVED', 'RECEIVED') AND OLD.status IN ('SENT', 'PARTIAL_RECEIVED', 'RECEIVED') THEN
            -- IF 조건 안에 CASE … THEN 을 쓰지 않는다(PL/pgSQL 이 첫 THEN 에서 조건을 끊음 — R1 교훈)
            IF NOT ((NEW.status = 'RECEIVED' AND v_all)
                    OR (NEW.status = 'PARTIAL_RECEIVED' AND v_any AND NOT v_all)
                    OR (NEW.status = 'SENT' AND NOT v_any)) THEN
                RAISE EXCEPTION 'status % does not match the received quantities', NEW.status
                    USING ERRCODE = '55000', CONSTRAINT = 'ck_srm_asn_recv_status';
            END IF;
        END IF;
        -- 확정: 행이 하나 이상 + 한도(R3-4) — 발주 행마다 Σ 남은 ASN(살아 있는 것, 이 ASN 포함) ≤ 주문 × (1 + 초과율) − 입고
        IF OLD.status = 'DRAFT' AND NEW.status = 'SENT' THEN
            IF NOT EXISTS (SELECT 1 FROM srm_asn_line l WHERE l.asn_id = NEW.asn_id) THEN
                RAISE EXCEPTION 'advance shipping notice has no lines' USING ERRCODE = '55000', CONSTRAINT = 'ck_srm_asn_lines';
            END IF;
            SELECT pl.po_line_id INTO v_bad
              FROM srm_po_line pl
             WHERE pl.po_line_id IN (SELECT l.po_line_id FROM srm_asn_line l WHERE l.asn_id = NEW.asn_id)
               AND (SELECT coalesce(sum(greatest(al.qty - coalesce((SELECT sum(x.qty) FROM srm_po_receipt x WHERE x.asn_line_id = al.asn_line_id), 0), 0)), 0)
                      FROM srm_asn_line al JOIN srm_asn a ON a.asn_id = al.asn_id
                     WHERE al.po_line_id = pl.po_line_id
                       AND (a.asn_id = NEW.asn_id OR a.status IN ('SENT', 'PARTIAL_RECEIVED')))
                   > (pl.qty - pl.cancel_qty) * (100 + hb_srm_recv_over_pct(NEW.comp_cd, NEW.plant_cd)) / 100 - pl.recv_qty
             LIMIT 1;
            IF FOUND THEN
                RAISE EXCEPTION 'notified quantity exceeds what order line % can still receive', v_bad.po_line_id
                    USING ERRCODE = '23514', CONSTRAINT = 'ck_srm_asn_over';
            END IF;
        END IF;
    END IF;
    RETURN NEW;
END
$$;
CREATE TRIGGER tg_srm_asn_guard BEFORE INSERT OR UPDATE ON srm_asn FOR EACH ROW EXECUTE FUNCTION hb_guard_srm_asn();
CREATE TRIGGER tg_srm_asn_no_delete BEFORE DELETE ON srm_asn FOR EACH ROW EXECUTE FUNCTION hb_forbid_delete_master();

-- ---------------------------------------------------------------------
-- 2. 행
-- ---------------------------------------------------------------------
CREATE TABLE srm_asn_line (
    asn_line_id      bigint         GENERATED ALWAYS AS IDENTITY,
    comp_cd          varchar(20)    NOT NULL,
    asn_id           bigint         NOT NULL,
    line_no          integer        NOT NULL,
    po_line_id       bigint         NOT NULL,
    item_id          bigint         NOT NULL,
    unit_cd          varchar(30)    NOT NULL,
    qty              numeric(18,6)  NOT NULL,
    supplier_lot_no  varchar(60),
    mfg_date         date,
    expiry_date      date,
    pack_cnt         integer,
    remark           varchar(500),
    created_at       timestamptz    NOT NULL DEFAULT now(),
    created_by       varchar(50)    NOT NULL,
    updated_at       timestamptz    NOT NULL DEFAULT now(),
    updated_by       varchar(50)    NOT NULL,
    CONSTRAINT pk_srm_asn_line PRIMARY KEY (asn_line_id),
    CONSTRAINT fk_srm_asn_line_asn FOREIGN KEY (asn_id) REFERENCES srm_asn (asn_id),
    CONSTRAINT fk_srm_asn_line_po_line FOREIGN KEY (po_line_id) REFERENCES srm_po_line (po_line_id),
    CONSTRAINT fk_srm_asn_line_item FOREIGN KEY (item_id) REFERENCES mst_item (item_id),
    CONSTRAINT uq_srm_asn_line_no UNIQUE (asn_id, line_no),
    CONSTRAINT ck_srm_asn_line_no CHECK (line_no BETWEEN 1 AND 9999),
    CONSTRAINT ck_srm_asn_line_qty CHECK (qty > 0),
    CONSTRAINT ck_srm_asn_line_pack CHECK (pack_cnt IS NULL OR pack_cnt BETWEEN 1 AND 99999),
    CONSTRAINT ck_srm_asn_line_lot CHECK (supplier_lot_no IS NULL OR length(btrim(supplier_lot_no)) > 0),
    CONSTRAINT ck_srm_asn_line_dates CHECK (mfg_date IS NULL OR expiry_date IS NULL OR expiry_date >= mfg_date)
);
-- 같은 ASN 안 같은 발주 행·같은 공급사 LOT 은 한 줄
CREATE UNIQUE INDEX uq_srm_asn_line_lot ON srm_asn_line (asn_id, po_line_id, coalesce(supplier_lot_no, ''));
CREATE INDEX ix_srm_asn_line_po_line ON srm_asn_line (comp_cd, po_line_id);
COMMENT ON TABLE srm_asn_line IS 'ASN 행 — 발주 행 × 공급사 LOT, 수량은 발주 단위(스냅샷). 작성 중만 편집. 받은 양 = Σ 입고 이력(asn_line_id)';

CREATE FUNCTION hb_guard_srm_asn_line() RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    v_row  srm_asn_line;
    v_head record;
    v_line record;
BEGIN
    v_row := CASE WHEN TG_OP = 'DELETE' THEN OLD ELSE NEW END;
    SELECT a.status, a.comp_cd, a.cust_id, a.plant_cd INTO v_head FROM srm_asn a WHERE a.asn_id = v_row.asn_id;
    IF NOT FOUND OR v_head.comp_cd <> v_row.comp_cd THEN
        RAISE EXCEPTION 'advance shipping notice % does not belong to company %', v_row.asn_id, v_row.comp_cd
            USING ERRCODE = '23503', CONSTRAINT = 'fk_srm_asn_line_asn_comp';
    END IF;
    IF v_head.status <> 'DRAFT' THEN
        RAISE EXCEPTION 'lines of % advance shipping notice cannot be changed', v_head.status
            USING ERRCODE = '55000', CONSTRAINT = 'ck_srm_asn_line_draft';
    END IF;
    IF TG_OP = 'DELETE' THEN
        RETURN OLD;
    END IF;
    IF TG_OP = 'UPDATE' AND (NEW.asn_id <> OLD.asn_id OR NEW.line_no <> OLD.line_no) THEN
        RAISE EXCEPTION 'line cannot move' USING ERRCODE = '55000', CONSTRAINT = 'ck_srm_asn_line_move';
    END IF;
    -- 발주 행: 같은 회사·같은 협력사·같은 공장, 발행된(회신·입고 중) 원천 SRM 발주의 열린 행, 품목·단위 = 발주 행
    SELECT l.comp_cd, l.item_id, l.unit_cd, l.line_status, p.cust_id, p.plant_cd, p.status, p.source INTO v_line
      FROM srm_po_line l JOIN srm_po p ON p.po_id = l.po_id WHERE l.po_line_id = NEW.po_line_id;
    IF NOT FOUND OR v_line.comp_cd <> NEW.comp_cd OR v_line.cust_id <> v_head.cust_id OR v_line.plant_cd <> v_head.plant_cd THEN
        RAISE EXCEPTION 'order line % is not a line of the same company, supplier and plant', NEW.po_line_id
            USING ERRCODE = '23503', CONSTRAINT = 'fk_srm_asn_line_po_line_comp';
    END IF;
    IF v_line.source <> 'SRM' OR v_line.status NOT IN ('ISSUED', 'ACKED', 'PARTIAL_RECEIVED') OR v_line.line_status <> 'OPEN' THEN
        RAISE EXCEPTION 'order line % cannot be notified (% % %)', NEW.po_line_id, v_line.source, v_line.status, v_line.line_status
            USING ERRCODE = '55000', CONSTRAINT = 'ck_srm_asn_line_po_state';
    END IF;
    IF NEW.item_id <> v_line.item_id OR NEW.unit_cd <> v_line.unit_cd THEN
        RAISE EXCEPTION 'item and unit must be those of the order line' USING ERRCODE = '23514', CONSTRAINT = 'ck_srm_asn_line_item';
    END IF;
    RETURN NEW;
END
$$;
CREATE TRIGGER tg_srm_asn_line_guard BEFORE INSERT OR UPDATE OR DELETE ON srm_asn_line
    FOR EACH ROW EXECUTE FUNCTION hb_guard_srm_asn_line();

-- ---------------------------------------------------------------------
-- 3. 입고 이력 ↔ ASN 행 (게이트가 남긴다) — V128 함수 본문 그대로 + (R3) ASN 행 확인
-- ---------------------------------------------------------------------
ALTER TABLE srm_po_receipt ADD COLUMN asn_line_id bigint;
ALTER TABLE srm_po_receipt ADD CONSTRAINT fk_srm_po_receipt_asn_line FOREIGN KEY (asn_line_id) REFERENCES srm_asn_line (asn_line_id);
CREATE INDEX ix_srm_po_receipt_asn_line ON srm_po_receipt (comp_cd, asn_line_id) WHERE asn_line_id IS NOT NULL;
COMMENT ON COLUMN srm_po_receipt.asn_line_id IS 'ASN 행(docs/31 4.11) — 게이트 asnLineId, 되돌림은 원래 입고 이력의 값을 따른다';

CREATE OR REPLACE FUNCTION hb_guard_srm_po_receipt() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP <> 'INSERT' THEN
        RAISE EXCEPTION 'purchase receipts are append-only' USING ERRCODE = '55000', CONSTRAINT = 'ck_srm_po_receipt_append_only';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM srm_po_line l WHERE l.po_line_id = NEW.po_line_id AND l.po_id = NEW.po_id AND l.comp_cd = NEW.comp_cd) THEN
        RAISE EXCEPTION 'line % is not a line of purchase order % of company %', NEW.po_line_id, NEW.po_id, NEW.comp_cd
            USING ERRCODE = '23503', CONSTRAINT = 'fk_srm_po_receipt_line_comp';
    END IF;
    -- (R3) ASN 행은 같은 회사·같은 발주 행
    IF NEW.asn_line_id IS NOT NULL
       AND NOT EXISTS (SELECT 1 FROM srm_asn_line a WHERE a.asn_line_id = NEW.asn_line_id AND a.po_line_id = NEW.po_line_id
                          AND a.comp_cd = NEW.comp_cd) THEN
        RAISE EXCEPTION 'notice line % is not a line for order line %', NEW.asn_line_id, NEW.po_line_id
            USING ERRCODE = '23503', CONSTRAINT = 'fk_srm_po_receipt_asn_line_comp';
    END IF;
    RETURN NEW;
END
$$;

-- 입고 상태(받은 양) 판정 (SQL 함수는 만들 때 표·칸을 확인하므로 표·asn_line_id 뒤에) — 행마다 순입고(이력 합) ≥ ASN 수량이면 다 받음
CREATE FUNCTION hb_srm_asn_recv_state(p_asn bigint, OUT any_recv boolean, OUT all_recv boolean)
    LANGUAGE sql STABLE
AS $$
    SELECT coalesce(bool_or(r.net > 0), false), coalesce(bool_and(r.net >= r.qty), false)
      FROM (SELECT l.qty, coalesce((SELECT sum(x.qty) FROM srm_po_receipt x WHERE x.asn_line_id = l.asn_line_id), 0) AS net
              FROM srm_asn_line l WHERE l.asn_id = p_asn) r
$$;

-- ---------------------------------------------------------------------
-- 4. 감사 · RLS · 권한 · 화면 · 보존
-- ---------------------------------------------------------------------
CREATE TRIGGER tg_audit AFTER INSERT OR UPDATE OR DELETE ON srm_asn FOR EACH ROW EXECUTE FUNCTION hb_audit('asn_id', '', '');
CREATE TRIGGER tg_audit AFTER INSERT OR UPDATE OR DELETE ON srm_asn_line FOR EACH ROW EXECUTE FUNCTION hb_audit('asn_line_id', '', '');

ALTER TABLE srm_asn ENABLE ROW LEVEL SECURITY;
ALTER TABLE srm_asn FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON srm_asn USING (comp_cd = hb_current_comp()) WITH CHECK (comp_cd = hb_current_comp());
ALTER TABLE srm_asn_line ENABLE ROW LEVEL SECURITY;
ALTER TABLE srm_asn_line FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON srm_asn_line USING (comp_cd = hb_current_comp()) WITH CHECK (comp_cd = hb_current_comp());
REVOKE DELETE, TRUNCATE ON srm_asn FROM ${app_role};
REVOKE TRUNCATE ON srm_asn_line FROM ${app_role};

INSERT INTO sys_prog (prog_cd, prog_nm_key, view_path, module_cd, actions, sort_no) VALUES
    ('srm-asn',       'prog.srmAsn',      'srm/asn-mgt',   'SRM', ARRAY['SEARCH','SAVE']::varchar(20)[], 862),
    ('srm-asn-print', 'prog.srmAsnPrint', 'srm/asn-print', 'SRM', ARRAY['SEARCH']::varchar(20)[], 863);
INSERT INTO sys_retention_table (table_nm, class_cd) VALUES
    ('srm_asn', 'MASTER'), ('srm_asn_line', 'MASTER');
