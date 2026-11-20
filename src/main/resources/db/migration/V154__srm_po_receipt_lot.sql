-- SRM 2부 R4-8 (docs/31 4.12.7·docs/30 12.2): 구매 입고 이력에 받은 LOT — 반품 출고 게이트(R4-4)가 반품 LOT 로 그 LOT 를 받은 입고를 찾는다.
-- WMS 구매 입고는 게이트(이력 INSERT)를 원장보다 먼저 부르고 LOT 는 그 뒤에 생긴다 → 같은 트랜잭션에서 attachLot 이 lot_id 를 한 번만 채운다.
-- 되돌림 행은 되돌리는 입고 이력의 lot_id 를 INSERT 때 그대로 받는다. 옛 이력(이 판 전)은 비어 있다.
ALTER TABLE srm_po_receipt ADD COLUMN lot_id bigint;
CREATE INDEX ix_srm_po_receipt_lot ON srm_po_receipt (comp_cd, lot_id) WHERE lot_id IS NOT NULL;
COMMENT ON COLUMN srm_po_receipt.lot_id IS '받은 LOT(lot_master, FK 없음 — 회사 일치는 트리거) — 입고는 attachLot 이 null → 값 한 번만, 되돌림은 원래 입고의 값';

CREATE OR REPLACE FUNCTION hb_guard_srm_po_receipt() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'purchase receipts are append-only' USING ERRCODE = '55000', CONSTRAINT = 'ck_srm_po_receipt_append_only';
    END IF;
    IF TG_OP = 'UPDATE' THEN
        -- (R4-8) 바꿀 수 있는 것은 lot_id 한 번(null → 값)뿐, 다른 칸은 그대로
        IF OLD.lot_id IS NOT NULL OR NEW.lot_id IS NULL
           OR (to_jsonb(NEW) - 'lot_id') IS DISTINCT FROM (to_jsonb(OLD) - 'lot_id') THEN
            RAISE EXCEPTION 'purchase receipts are append-only' USING ERRCODE = '55000', CONSTRAINT = 'ck_srm_po_receipt_append_only';
        END IF;
    ELSE
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
    END IF;
    -- (R4-8) LOT 는 같은 회사
    IF NEW.lot_id IS NOT NULL
       AND NOT EXISTS (SELECT 1 FROM lot_master m WHERE m.lot_id = NEW.lot_id AND m.comp_cd = NEW.comp_cd) THEN
        RAISE EXCEPTION 'lot % is not a lot of company %', NEW.lot_id, NEW.comp_cd
            USING ERRCODE = '23503', CONSTRAINT = 'fk_srm_po_receipt_lot_comp';
    END IF;
    RETURN NEW;
END
$$;

GRANT UPDATE (lot_id) ON srm_po_receipt TO ${app_role};
