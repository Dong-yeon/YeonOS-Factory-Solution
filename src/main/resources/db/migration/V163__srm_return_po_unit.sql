-- =====================================================================
-- SRM 2부 R4c-단위 교체 요구 반품 — 다른 발주 단위 허용 (docs/31 4.12.4·4.12.7) — 임시 번호(docs/29 2.2)
--
-- - 지금까지: 반품의 발주 행은 같은 품목·같은 단위여야 했다(ck_srm_return_po_item) → BOX 로 발주하고 EA(LOT 기본 단위)로
--   불량이 난 품목은 교체 요구를 받을 수 없었다
-- - 이제: 같은 품목만 요구. 단위 환산(품목 단위 환산표 — 입고 게이트와 같은 표)과 발주 단위 자릿수는 서비스(만들기·수정·확정)와
--   출고 게이트(되돌릴 때)가 본다 — 환산표는 나중에 바뀔 수 있어 행 저장 시점에 굳히지 않는다
-- - hb_guard_srm_return 본문은 V159 와 같고 발주 행 검사의 단위 비교만 뺀다
-- =====================================================================

CREATE OR REPLACE FUNCTION hb_guard_srm_return() RETURNS trigger
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
        -- (R4-10) 출고 역분개: SHIPPED → CONFIRMED
        IF NEW.status <> OLD.status
           AND NOT ((OLD.status = 'DRAFT' AND NEW.status IN ('CONFIRMED', 'CANCELLED'))
                 OR (OLD.status = 'CONFIRMED' AND NEW.status IN ('SHIPPED', 'CANCELLED'))
                 OR (OLD.status = 'SHIPPED' AND NEW.status = 'CONFIRMED')) THEN
            RAISE EXCEPTION 'return cannot go from % to %', OLD.status, NEW.status USING ERRCODE = '55000', CONSTRAINT = 'ck_srm_return_transition';
        END IF;
    END IF;
    -- 발주 행: 같은 회사·같은 협력사·같은 품목의 SRM 발주 행(단위는 달라도 된다 — 환산은 서비스·게이트, R4c-단위)
    IF NEW.po_line_id IS NOT NULL AND (TG_OP = 'INSERT' OR NEW.po_line_id IS DISTINCT FROM OLD.po_line_id) THEN
        SELECT l.comp_cd, l.item_id, p.cust_id INTO v_line
          FROM srm_po_line l JOIN srm_po p ON p.po_id = l.po_id WHERE l.po_line_id = NEW.po_line_id;
        IF NOT FOUND OR v_line.comp_cd <> NEW.comp_cd OR v_line.cust_id <> NEW.cust_id THEN
            RAISE EXCEPTION 'order line % is not a line of the same company and supplier', NEW.po_line_id
                USING ERRCODE = '23503', CONSTRAINT = 'fk_srm_return_po_line_comp';
        END IF;
        IF v_line.item_id <> NEW.item_id THEN
            RAISE EXCEPTION 'item must be that of the order line' USING ERRCODE = '23514', CONSTRAINT = 'ck_srm_return_po_item';
        END IF;
    END IF;
    RETURN NEW;
END
$$;
