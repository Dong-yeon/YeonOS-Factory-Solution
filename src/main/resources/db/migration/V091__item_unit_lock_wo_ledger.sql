-- =====================================================================
-- 사용자 결정 D-B1 (docs/30 11.10, 2026-10-21 채택) — 작업지시·재고 원장에 쓰인 품목은 기본 단위를 바꿀 수 없다
--
-- - V018 의 "쓰이면 변경 금지"(BOM 머리·행·사용 중 환산)에 prd_wo(지시)·inv_trx(재고 원장)를 더한다 — 제약 이름은 그대로
--   fk_mst_item_unit_in_use(품목 서비스·엑셀 올리기가 이미 IN_USE 칸 오류로 바꾼다)
-- - 커밋 전 원장 INSERT 와 단위 변경이 엇갈리지 않게: 원장 검사 트리거가 품목 행을 FOR SHARE 로 읽는다
--   (단위 변경 UPDATE 와 서로 기다림 — 원장이 먼저면 변경 트리거가 그 원장을 보고 막고, 변경이 먼저면 원장이 새 단위로 단위 검사에 걸린다)
-- ※ mst_item(P1 표) 트리거 함수 변경 = "남의 표 변경"(PR 본문). MES 지시 생성과의 경합은 B1 post() 의 UNIT 검사가 마지막 관문
-- =====================================================================

CREATE OR REPLACE FUNCTION hb_check_item_unit_in_use() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.unit_cd <> OLD.unit_cd AND (
           EXISTS (SELECT 1 FROM mst_bom b WHERE b.comp_cd = OLD.comp_cd AND b.item_id = OLD.item_id)
        OR EXISTS (SELECT 1 FROM mst_bom_line l WHERE l.comp_cd = OLD.comp_cd AND l.item_id = OLD.item_id)
        OR EXISTS (SELECT 1 FROM mst_unit_conv c
                    WHERE c.comp_cd = OLD.comp_cd AND c.item_id = OLD.item_id AND c.use_yn = 'Y')
        OR EXISTS (SELECT 1 FROM prd_wo w WHERE w.comp_cd = OLD.comp_cd AND w.item_id = OLD.item_id)
        OR EXISTS (SELECT 1 FROM inv_trx t WHERE t.comp_cd = OLD.comp_cd AND t.item_id = OLD.item_id)) THEN
        RAISE EXCEPTION 'unit of item % is in use (BOM, unit conversion, work order or stock ledger)', OLD.item_id
            USING ERRCODE = '23503', CONSTRAINT = 'fk_mst_item_unit_in_use';
    END IF;
    RETURN NEW;
END
$$;

CREATE INDEX IF NOT EXISTS ix_prd_wo_item ON prd_wo (comp_cd, item_id);

-- 원장 INSERT 검사 — V079 판에 품목 행 FOR SHARE 만 더한다
CREATE OR REPLACE FUNCTION hb_guard_inv_trx() RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    v_item   mst_item;
    v_node   mst_equip_hier;
    v_stock  char(1);
    v_orig   inv_trx;
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
        IF NOT EXISTS (SELECT 1 FROM lot_master l WHERE l.lot_id = NEW.lot_id AND l.comp_cd = NEW.comp_cd AND l.item_id = NEW.item_id) THEN
            RAISE EXCEPTION 'lot % is not a lot of item % in company %', NEW.lot_id, NEW.item_id, NEW.comp_cd
                USING ERRCODE = '23503', CONSTRAINT = 'fk_inv_trx_lot_comp';
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
