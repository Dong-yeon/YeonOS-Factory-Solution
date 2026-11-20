-- =====================================================================
-- SRM 2부 R4-4·R4-3·R4-9·R4-10 반품 출고 (docs/31 4.12.7 · docs/30 12.14) — 임시 번호(docs/29 2.2)
--
-- - WMS 원장 유형 RTN_SUP(−, 협력사 반품 출고). 출처 = SRM_RETURN(src_id = srm_return.return_id). FK 없음(값은 게이트 계약으로)
--   보류 LOT 도 나간다(반품 대상은 보통 보류된 불량 LOT — 원장 트리거 ck_inv_trx_lot_hold 는 ADJ_OUT·MOVE_OUT·SHIP 만 막는다)
-- - SRM srm_return: 출고 근거 ship_ref(회사 안 유일, SHIPPED 일 때만), 전이 SHIPPED → CONFIRMED(출고 역분개 — R4-10)
--   상태를 바꾸는 것은 SRM 게이트 ReturnShipGate(WMS 가 원장과 같은 트랜잭션에서 부름)
-- - 화면 wms-rtn-issue(반품 출고)
-- - 남의 표 변경: srm_return(칸·색인·CHECK·트리거 함수) — 상위 승인(2026-10-23), SRM 세션 사전 리뷰 반영
-- =====================================================================

-- ---------------------------------------------------------------------
-- 1. 원장 유형
-- ---------------------------------------------------------------------
INSERT INTO sys_code (grp_cd, code, code_nm_key, sort_no) VALUES
    ('INV_TRX_TYPE', 'RTN_SUP', 'code.INV_TRX_TYPE.RTN_SUP', 29);

ALTER TABLE inv_trx DROP CONSTRAINT ck_inv_trx_type;
ALTER TABLE inv_trx ADD CONSTRAINT ck_inv_trx_type CHECK (trx_type IN ('OPENING', 'ADJ_IN', 'ADJ_OUT', 'MOVE_OUT', 'MOVE_IN', 'STATUS_OUT',
                                                                        'STATUS_IN', 'COUNT_ADJ', 'RCV_PRD', 'RCV_MAT', 'ISSUE_WO',
                                                                        'CONV_OUT', 'CONV_IN', 'RCV_PO', 'SHIP', 'ISSUE_MRO', 'MRO_RTN',
                                                                        'RTN_SUP'));
ALTER TABLE inv_trx DROP CONSTRAINT ck_inv_trx_sign;
ALTER TABLE inv_trx ADD CONSTRAINT ck_inv_trx_sign CHECK (
    trx_type = 'COUNT_ADJ'
    OR (trx_type IN ('OPENING', 'ADJ_IN', 'MOVE_IN', 'STATUS_IN', 'RCV_PRD', 'RCV_MAT', 'CONV_IN', 'RCV_PO', 'MRO_RTN')
        AND (qty > 0) = (reversal_of_id IS NULL))
    OR (trx_type IN ('ADJ_OUT', 'MOVE_OUT', 'STATUS_OUT', 'ISSUE_WO', 'CONV_OUT', 'SHIP', 'ISSUE_MRO', 'RTN_SUP')
        AND (qty < 0) = (reversal_of_id IS NULL)));
-- 반품 출고는 출처 = 반품 지시
ALTER TABLE inv_trx ADD CONSTRAINT ck_inv_trx_rtn_src CHECK (
    trx_type <> 'RTN_SUP' OR (src_type = 'SRM_RETURN' AND src_id IS NOT NULL));
CREATE INDEX ix_inv_trx_srm_return ON inv_trx (comp_cd, src_id) WHERE src_type = 'SRM_RETURN';

-- ---------------------------------------------------------------------
-- 2. SRM 반품 지시 — 출고 근거·전이
-- ---------------------------------------------------------------------
ALTER TABLE srm_return ADD COLUMN ship_ref varchar(100);
ALTER TABLE srm_return ADD CONSTRAINT ck_srm_return_ship_ref CHECK ((status = 'SHIPPED') = (ship_ref IS NOT NULL));
CREATE UNIQUE INDEX uq_srm_return_ship_ref ON srm_return (comp_cd, ship_ref) WHERE ship_ref IS NOT NULL;
COMMENT ON COLUMN srm_return.ship_ref IS '출고 근거(WMS RTN_ISS:{요청 id}) — 출고됨일 때만, 되돌리면 비움. 회사 안 유일';

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

-- ---------------------------------------------------------------------
-- 3. 화면
-- ---------------------------------------------------------------------
INSERT INTO sys_prog (prog_cd, prog_nm_key, view_path, module_cd, actions, sort_no) VALUES
    ('wms-rtn-issue', 'prog.wmsRtnIssue', 'wms/rtn-issue', 'WMS', ARRAY['SEARCH','SAVE']::varchar(20)[], 467);
