-- =====================================================================
-- WMS 2부 B4b 구매 입고 RCV_PO (docs/30 12.2·12.3, D-B3 — SRM 게이트 PoReceiptGate, docs/31 4.9) — 임시 번호(docs/29 2.2)
--
-- - inv_trx.po_line_id(발주 행) + po_qty(발주 단위 수량, 부호 = qty) — 원본은 원장, SRM srm_po_line.recv_qty 는 게이트가 바꾸는 투영
-- - 유형 RCV_PO(+), 출처는 자재 입고와 같이 MAT_RECEIPT·src_id = 입고 LOT
-- - 원장 검사(V092 판 교체): 발주 행 같은 회사·같은 품목, 역분개 거울에 po_line_id·po_qty 포함
-- - 대사: inv_reconcile_run.rcv_po_mismatch = srm_po_line.recv_qty ≠ Σ po_qty(RCV_PO, 역분개 포함)인 발주 행 수
-- - 남의 표 변경 없음(srm_po_line 은 FK·읽기만)
-- =====================================================================

INSERT INTO sys_code (grp_cd, code, code_nm_key, sort_no) VALUES
    ('INV_TRX_TYPE', 'RCV_PO', 'code.INV_TRX_TYPE.RCV_PO', 25);

ALTER TABLE inv_trx ADD COLUMN po_line_id bigint;
ALTER TABLE inv_trx ADD COLUMN po_qty numeric(18,6);
ALTER TABLE inv_trx ADD CONSTRAINT fk_inv_trx_po_line FOREIGN KEY (po_line_id) REFERENCES srm_po_line (po_line_id);
COMMENT ON COLUMN inv_trx.po_line_id IS '구매 입고의 발주 행(RCV_PO 만, B4b) — SRM recv_qty 투영의 원본';
COMMENT ON COLUMN inv_trx.po_qty IS '발주 단위 수량(부호 = qty) — 대사(srm_po_line.recv_qty = Σ po_qty)를 환산 없이';
CREATE INDEX ix_inv_trx_po_line ON inv_trx (comp_cd, po_line_id) WHERE po_line_id IS NOT NULL;

ALTER TABLE inv_trx DROP CONSTRAINT ck_inv_trx_type;
ALTER TABLE inv_trx ADD CONSTRAINT ck_inv_trx_type CHECK (trx_type IN ('OPENING', 'ADJ_IN', 'ADJ_OUT', 'MOVE_OUT', 'MOVE_IN', 'STATUS_OUT',
                                                                        'STATUS_IN', 'COUNT_ADJ', 'RCV_PRD', 'RCV_MAT', 'ISSUE_WO',
                                                                        'CONV_OUT', 'CONV_IN', 'RCV_PO'));
ALTER TABLE inv_trx DROP CONSTRAINT ck_inv_trx_sign;
ALTER TABLE inv_trx ADD CONSTRAINT ck_inv_trx_sign CHECK (
    trx_type = 'COUNT_ADJ'
    OR (trx_type IN ('OPENING', 'ADJ_IN', 'MOVE_IN', 'STATUS_IN', 'RCV_PRD', 'RCV_MAT', 'CONV_IN', 'RCV_PO') AND (qty > 0) = (reversal_of_id IS NULL))
    OR (trx_type IN ('ADJ_OUT', 'MOVE_OUT', 'STATUS_OUT', 'ISSUE_WO', 'CONV_OUT') AND (qty < 0) = (reversal_of_id IS NULL)));
-- 구매 입고는 발주 행·발주 단위 수량이 반드시 있고(부호 = qty), 다른 유형에는 없다
ALTER TABLE inv_trx ADD CONSTRAINT ck_inv_trx_rcv_po CHECK (
    (trx_type = 'RCV_PO') = (po_line_id IS NOT NULL)
    AND (po_line_id IS NULL) = (po_qty IS NULL)
    AND (po_qty IS NULL OR (po_qty <> 0 AND sign(po_qty) = sign(qty))));
-- 출처: 자재 입고·구매 입고 모두 입고 LOT
ALTER TABLE inv_trx DROP CONSTRAINT ck_inv_trx_rcv_mat_src;
ALTER TABLE inv_trx ADD CONSTRAINT ck_inv_trx_rcv_mat_src CHECK (
    trx_type NOT IN ('RCV_MAT', 'RCV_PO') OR (src_type = 'MAT_RECEIPT' AND src_id IS NOT NULL));

ALTER TABLE inv_reconcile_run ADD COLUMN rcv_po_mismatch integer;
ALTER TABLE inv_reconcile_run ADD CONSTRAINT ck_inv_reconcile_run_po CHECK (rcv_po_mismatch IS NULL OR rcv_po_mismatch >= 0);
COMMENT ON COLUMN inv_reconcile_run.rcv_po_mismatch IS 'SRM 발주 행 입고 수량(투영) ≠ 원장 RCV_PO po_qty 합인 발주 행 수 — 0 이 정상(B4b)';

-- 원장 INSERT 전 검사 — V092 판에 구매 입고 검사를 더한다
CREATE OR REPLACE FUNCTION hb_guard_inv_trx() RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    v_item   mst_item;
    v_node   mst_equip_hier;
    v_stock  char(1);
    v_orig   inv_trx;
    v_lot    lot_master;
    v_import boolean := NEW.entry_method = 'IMPORT' AND NEW.src_type = 'PRD_RECEIPT';
BEGIN
    -- FOR SHARE: 같은 품목 단위 변경(UPDATE)과 줄 세움(D-B1)
    SELECT * INTO v_item FROM mst_item i WHERE i.item_id = NEW.item_id AND i.comp_cd = NEW.comp_cd FOR SHARE;
    IF v_item.item_id IS NULL THEN
        RAISE EXCEPTION 'item % is not of company %', NEW.item_id, NEW.comp_cd USING ERRCODE = '23503', CONSTRAINT = 'fk_inv_trx_item_comp';
    END IF;
    IF NEW.unit_cd <> v_item.unit_cd THEN
        RAISE EXCEPTION 'ledger quantity is in the item base unit %', v_item.unit_cd
            USING ERRCODE = '23514', CONSTRAINT = 'ck_inv_trx_unit';
    END IF;
    -- LOT: 관리 품목 = 필수(같은 회사·같은 품목), 비관리 품목 = 비움
    IF v_item.lot_ctrl_yn = 'Y' THEN
        IF NEW.lot_id IS NULL THEN
            RAISE EXCEPTION 'lot-controlled item % needs a lot', NEW.item_id USING ERRCODE = '23514', CONSTRAINT = 'ck_inv_trx_lot_item';
        END IF;
        -- LOT 행 FOR SHARE: 보류 지정(UPDATE)과 줄 세움. 잠금 순서 lot_master → 기간 → inv_stock(docs/30 11.6)
        SELECT * INTO v_lot FROM lot_master l WHERE l.lot_id = NEW.lot_id AND l.comp_cd = NEW.comp_cd AND l.item_id = NEW.item_id FOR SHARE;
        IF v_lot.lot_id IS NULL THEN
            RAISE EXCEPTION 'lot % is not a lot of item % in company %', NEW.lot_id, NEW.item_id, NEW.comp_cd
                USING ERRCODE = '23503', CONSTRAINT = 'fk_inv_trx_lot_comp';
        END IF;
        -- 보류 LOT 은 꺼내거나 다른 위치로 옮기지 못한다(상태 바꿈·실사 조정·역분개는 된다 — 격리·정정 경로)
        IF v_lot.hold_yn = 'Y' AND NEW.reversal_of_id IS NULL AND NEW.trx_type IN ('ADJ_OUT', 'MOVE_OUT') THEN
            RAISE EXCEPTION 'lot % is on hold', NEW.lot_id USING ERRCODE = '23514', CONSTRAINT = 'ck_inv_trx_lot_hold';
        END IF;
    ELSIF NEW.lot_id IS NOT NULL THEN
        RAISE EXCEPTION 'item % is not lot-controlled — leave lot empty', NEW.item_id
            USING ERRCODE = '23514', CONSTRAINT = 'ck_inv_trx_lot_item';
    END IF;
    SELECT * INTO v_node FROM mst_equip_hier h WHERE h.node_id = NEW.node_id AND h.comp_cd = NEW.comp_cd;
    IF v_node.node_id IS NULL THEN
        RAISE EXCEPTION 'node % is not of company %', NEW.node_id, NEW.comp_cd USING ERRCODE = '23503', CONSTRAINT = 'fk_inv_trx_node_comp';
    END IF;
    IF v_node.node_type NOT IN ('WH', 'LOC') OR v_node.plant_cd <> NEW.plant_cd THEN
        RAISE EXCEPTION 'node % is not a warehouse/location of plant %', NEW.node_id, NEW.plant_cd
            USING ERRCODE = '23514', CONSTRAINT = 'ck_inv_trx_node_type';
    END IF;
    IF NEW.performed_by IS NOT NULL AND NOT EXISTS (SELECT 1 FROM mst_worker w WHERE w.worker_id = NEW.performed_by
                                                       AND w.comp_cd = NEW.comp_cd) THEN
        RAISE EXCEPTION 'worker % is not of company %', NEW.performed_by, NEW.comp_cd
            USING ERRCODE = '23503', CONSTRAINT = 'fk_inv_trx_worker_comp';
    END IF;
    -- 구매 입고: 발주 행은 같은 회사·같은 품목(B4b — FK 는 RLS 를 보지 않는다)
    IF NEW.po_line_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM srm_po_line p WHERE p.po_line_id = NEW.po_line_id
                                                     AND p.comp_cd = NEW.comp_cd AND p.item_id = NEW.item_id) THEN
        RAISE EXCEPTION 'po line % is not a line of item % in company %', NEW.po_line_id, NEW.item_id, NEW.comp_cd
            USING ERRCODE = '23503', CONSTRAINT = 'fk_inv_trx_po_line_comp';
    END IF;
    IF NEW.reversal_of_id IS NULL THEN
        -- 새 사실은 사용 중 품목·사용 중이고 재고를 둘 수 있는 위치에만. 이관은 그때 사실이라 건너뛴다
        IF NOT v_import THEN
            IF v_item.use_yn <> 'Y' THEN
                RAISE EXCEPTION 'item % is not in use', NEW.item_id USING ERRCODE = '23514', CONSTRAINT = 'ck_inv_trx_item_use';
            END IF;
            SELECT a.stock_yn INTO v_stock FROM wms_loc_attr a WHERE a.node_id = NEW.node_id;
            IF v_node.use_yn <> 'Y' OR coalesce(v_stock, 'Y') <> 'Y' THEN
                RAISE EXCEPTION 'node % does not hold stock', NEW.node_id USING ERRCODE = '23514', CONSTRAINT = 'ck_inv_trx_node_stock';
            END IF;
        END IF;
    ELSE
        SELECT * INTO v_orig FROM inv_trx t WHERE t.trx_id = NEW.reversal_of_id AND t.comp_cd = NEW.comp_cd;
        IF v_orig.trx_id IS NULL THEN
            RAISE EXCEPTION 'trx % is not of company %', NEW.reversal_of_id, NEW.comp_cd
                USING ERRCODE = '23503', CONSTRAINT = 'fk_inv_trx_reversal_comp';
        END IF;
        IF v_orig.reversal_of_id IS NOT NULL
           OR (NEW.trx_type, NEW.item_id, NEW.node_id, NEW.stock_status, NEW.qty, NEW.plant_cd)
              IS DISTINCT FROM (v_orig.trx_type, v_orig.item_id, v_orig.node_id, v_orig.stock_status, -v_orig.qty, v_orig.plant_cd)
           OR NEW.lot_id IS DISTINCT FROM v_orig.lot_id
           OR NEW.po_line_id IS DISTINCT FROM v_orig.po_line_id OR NEW.po_qty IS DISTINCT FROM -v_orig.po_qty THEN
            RAISE EXCEPTION 'a reversal mirrors its original row (and cannot reverse a reversal)'
                USING ERRCODE = '23514', CONSTRAINT = 'ck_inv_trx_reversal_mirror';
        END IF;
    END IF;
    RETURN NEW;
END
$$;
