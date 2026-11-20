-- =====================================================================
-- SRM 2부 R2 입고 이력 (docs/31 4.10) — 임시 번호, 병합 때 재번호
--
-- - srm_po_receipt: 구매 입고 게이트(PoReceiptGate) 호출 1번 = 1행(추가만). 발생일·수량(부호 = 입고 +/되돌림 −)·근거(trx_ref, 회사 안 유일)
-- - 합계 = 이력: srm_po_line.recv_qty = Σ 그 행 이력 — 커밋 때 검사(지연 제약 트리거 ck_srm_po_receipt_sum)
-- - 평가 AUTO 지표 OTD_RATE(납기 준수율)의 근거. 이관 대상 없음: R1 투영을 부르는 쪽(WMS 구매 입고)이 아직 없다
-- =====================================================================

CREATE TABLE srm_po_receipt (
    receipt_id    bigint         GENERATED ALWAYS AS IDENTITY,
    comp_cd       varchar(20)    NOT NULL,
    po_id         bigint         NOT NULL,
    po_line_id    bigint         NOT NULL,
    occurred_on   date           NOT NULL,
    qty           numeric(18,6)  NOT NULL,
    trx_ref       varchar(100)   NOT NULL,
    created_at    timestamptz    NOT NULL DEFAULT now(),
    created_by    varchar(50)    NOT NULL,
    CONSTRAINT pk_srm_po_receipt PRIMARY KEY (receipt_id),
    CONSTRAINT fk_srm_po_receipt_po FOREIGN KEY (po_id) REFERENCES srm_po (po_id),
    CONSTRAINT fk_srm_po_receipt_line FOREIGN KEY (po_line_id) REFERENCES srm_po_line (po_line_id),
    CONSTRAINT uq_srm_po_receipt_ref UNIQUE (comp_cd, trx_ref),
    CONSTRAINT ck_srm_po_receipt_qty CHECK (qty <> 0),
    CONSTRAINT ck_srm_po_receipt_ref CHECK (length(btrim(trx_ref)) > 0),
    CONSTRAINT ck_srm_po_receipt_date CHECK (occurred_on BETWEEN DATE '2000-01-01' AND DATE '2999-12-31')
);
CREATE INDEX ix_srm_po_receipt_line ON srm_po_receipt (comp_cd, po_line_id, occurred_on);
COMMENT ON TABLE srm_po_receipt IS '구매 입고 이력(docs/31 4.10) — 게이트 호출마다 1행, 추가만. 발주 행 recv_qty = Σ qty(커밋 때 검사)';
COMMENT ON COLUMN srm_po_receipt.occurred_on IS '입고 발생일(공장 날짜). 되돌림은 되돌리는 입고의 발생일 — 납기 준수율이 되돌림을 반영하도록';
COMMENT ON COLUMN srm_po_receipt.trx_ref IS '부르는 쪽 근거(원장 출처 키 등) — 회사 안 유일, 같은 근거로 두 번 부르면 거부';

CREATE FUNCTION hb_guard_srm_po_receipt() RETURNS trigger
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
    RETURN NEW;
END
$$;
CREATE TRIGGER tg_srm_po_receipt_guard BEFORE INSERT OR UPDATE OR DELETE ON srm_po_receipt
    FOR EACH ROW EXECUTE FUNCTION hb_guard_srm_po_receipt();

-- 합계 = 이력: 커밋 때 그 행의 recv_qty 와 이력 합이 같아야 한다(게이트는 이력 → 행 순서로 쓴다)
CREATE FUNCTION hb_check_srm_po_receipt_sum() RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    v_recv numeric;
    v_sum  numeric;
BEGIN
    SELECT l.recv_qty INTO v_recv FROM srm_po_line l WHERE l.po_line_id = NEW.po_line_id;
    SELECT coalesce(sum(r.qty), 0) INTO v_sum FROM srm_po_receipt r WHERE r.po_line_id = NEW.po_line_id;
    IF v_recv IS DISTINCT FROM v_sum THEN
        RAISE EXCEPTION 'received % of line % differs from its receipts %', v_recv, NEW.po_line_id, v_sum
            USING ERRCODE = '23514', CONSTRAINT = 'ck_srm_po_receipt_sum';
    END IF;
    RETURN NULL;
END
$$;
CREATE CONSTRAINT TRIGGER tg_srm_po_receipt_sum AFTER INSERT ON srm_po_receipt
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION hb_check_srm_po_receipt_sum();
CREATE CONSTRAINT TRIGGER tg_srm_po_line_receipt_sum AFTER UPDATE OF recv_qty ON srm_po_line
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW WHEN (OLD.recv_qty IS DISTINCT FROM NEW.recv_qty)
    EXECUTE FUNCTION hb_check_srm_po_receipt_sum();

CREATE TRIGGER tg_audit AFTER INSERT OR UPDATE OR DELETE ON srm_po_receipt FOR EACH ROW EXECUTE FUNCTION hb_audit('receipt_id', '', '');

ALTER TABLE srm_po_receipt ENABLE ROW LEVEL SECURITY;
ALTER TABLE srm_po_receipt FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON srm_po_receipt USING (comp_cd = hb_current_comp()) WITH CHECK (comp_cd = hb_current_comp());
REVOKE UPDATE, DELETE, TRUNCATE ON srm_po_receipt FROM ${app_role};

-- 보존: 발주·회신과 같은 클래스(docs/31 3장 보존 — 1부 거래 기록 = MASTER)
INSERT INTO sys_retention_table (table_nm, class_cd) VALUES
    ('srm_po_receipt', 'MASTER');
