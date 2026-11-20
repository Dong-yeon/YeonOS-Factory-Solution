-- =====================================================================
-- WMS 2부 B2 LOT 관리 품목 재고 — 사무 화면 (docs/30 12장) — 임시 번호(docs/29 2.2)
--
-- - 원장 검사: LOT 행을 FOR SHARE 로 읽고, 보류(hold_yn = Y) LOT 의 조정 출고·이동 출고를 거부(ck_inv_trx_lot_hold)
--   상태 바꿈(STATUS_*)·실사 조정·역분개는 허용 — 보류 LOT 을 HOLD 상태로 격리하거나 정정하는 길은 남긴다
-- - 실사 줄 lot_id → lot_master FK(같은 회사·품목은 확정 때 원장 검사가 본다)
-- - MES 표(lot_master)는 읽기만
-- =====================================================================

-- 원장 INSERT 검사 — V9303 판에 LOT 보류 검사를 더한다
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
           OR NEW.lot_id IS DISTINCT FROM v_orig.lot_id THEN
            RAISE EXCEPTION 'a reversal mirrors its original row (and cannot reverse a reversal)'
                USING ERRCODE = '23514', CONSTRAINT = 'ck_inv_trx_reversal_mirror';
        END IF;
    END IF;
    RETURN NEW;
END
$$;

ALTER TABLE wms_count_line ADD CONSTRAINT fk_wms_count_line_lot FOREIGN KEY (lot_id) REFERENCES lot_master (lot_id);
CREATE INDEX ix_inv_stock_lot ON inv_stock (lot_id) WHERE lot_id IS NOT NULL;
