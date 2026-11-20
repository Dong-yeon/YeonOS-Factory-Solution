-- =====================================================================
-- 품목 기본 단위 변경 금지에 LOT 사용을 더한다 (docs/25 4.32, WMS 합의 Q1 · 11.4 남은 것)
--
-- - hb_check_item_unit_in_use: V123 판(BOM·환산·지시·재고 원장·수주) 조건을 모두 그대로 두고 lot_master 만 더한다.
--   지시 없이 자재 LOT(공급사 LOT)만 등록된 품목도 단위를 바꾸면 LOT 수량·잔량·계보 수량의 뜻이 조용히 바뀐다.
--   제약 이름은 그대로 fk_mst_item_unit_in_use(품목 서비스·엑셀 올리기가 IN_USE 칸 오류로 바꾼다)
-- - hb_guard_lot_master: V109 판 그대로 + INSERT 때 품목 행을 FOR SHARE 로 읽는다(V091 inv_trx 와 같은 줄 세움).
--   그렇지 않으면 커밋 전 단위 변경과 LOT 등록이 엇갈려(변경은 LOT 을 못 보고, LOT 은 옛 단위를 봄) 둘 다 통과한다
-- - 인덱스: ix_lot_master_item (comp_cd, item_id, created_at) 이 이미 있다(V034)
-- ※ mst_item(P1 표) 트리거 함수 변경 = "남의 표 변경"(PR 본문). 같은 함수를 바꾸는 다른 V 와 병합 순서 확인
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
        OR EXISTS (SELECT 1 FROM inv_trx t WHERE t.comp_cd = OLD.comp_cd AND t.item_id = OLD.item_id)
        OR EXISTS (SELECT 1 FROM sal_order_line s WHERE s.comp_cd = OLD.comp_cd AND s.item_id = OLD.item_id)
        OR EXISTS (SELECT 1 FROM lot_master m WHERE m.comp_cd = OLD.comp_cd AND m.item_id = OLD.item_id)) THEN
        RAISE EXCEPTION 'unit of item % is in use (BOM, unit conversion, work order, stock ledger, sales order or lot)', OLD.item_id
            USING ERRCODE = '23503', CONSTRAINT = 'fk_mst_item_unit_in_use';
    END IF;
    RETURN NEW;
END
$$;

CREATE OR REPLACE FUNCTION hb_guard_lot_master() RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    v_reason varchar;
BEGIN
    IF TG_OP = 'UPDATE' THEN
        IF (NEW.lot_no, NEW.item_id, NEW.origin, NEW.qty_created, NEW.unit_cd, NEW.occurred_at)
               IS DISTINCT FROM (OLD.lot_no, OLD.item_id, OLD.origin, OLD.qty_created, OLD.unit_cd, OLD.occurred_at)
           OR NEW.wo_id IS DISTINCT FROM OLD.wo_id OR NEW.supplier_cust_id IS DISTINCT FROM OLD.supplier_cust_id
           OR NEW.supplier_lot_no IS DISTINCT FROM OLD.supplier_lot_no THEN
            RAISE EXCEPTION 'lot % keeps its number, item, quantity and origin', OLD.lot_no
                USING ERRCODE = '55000', CONSTRAINT = 'ck_lot_master_fixed';
        END IF;
        IF (NEW.hold_yn, NEW.hold_reason) IS DISTINCT FROM (OLD.hold_yn, OLD.hold_reason) THEN
            v_reason := hb_lot_hold_summary(NEW.lot_id);
            IF (NEW.hold_yn, NEW.hold_reason) IS DISTINCT FROM (CASE WHEN v_reason IS NULL THEN 'N' ELSE 'Y' END, v_reason) THEN
                RAISE EXCEPTION 'lot % hold follows its hold records (lot_hold)', OLD.lot_no
                    USING ERRCODE = '55000', CONSTRAINT = 'ck_lot_master_hold_basis';
            END IF;
        END IF;
        RETURN NEW;
    END IF;
    IF NEW.hold_yn <> 'N' THEN
        RAISE EXCEPTION 'a new lot starts without hold — add a hold record' USING ERRCODE = '55000', CONSTRAINT = 'ck_lot_master_hold_basis';
    END IF;
    -- FOR SHARE: 같은 품목 단위 변경(UPDATE)과 줄 세움 — 변경이 먼저면 새 단위를 보고 아래에서 막히고, LOT 이 먼저면 변경이 LOT 을 보고 막힌다
    IF NOT EXISTS (SELECT 1 FROM mst_item i WHERE i.item_id = NEW.item_id AND i.comp_cd = NEW.comp_cd AND i.use_yn = 'Y'
                      AND i.unit_cd = NEW.unit_cd FOR SHARE) THEN
        RAISE EXCEPTION 'item % is not an active item of company % (unit %)', NEW.item_id, NEW.comp_cd, NEW.unit_cd
            USING ERRCODE = '23503', CONSTRAINT = 'fk_lot_master_item_comp';
    END IF;
    IF NEW.wo_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM prd_wo w WHERE w.wo_id = NEW.wo_id AND w.comp_cd = NEW.comp_cd
                      AND w.item_id = NEW.item_id AND w.status IN ('RELEASED', 'IN_PROGRESS', 'DONE')) THEN
        RAISE EXCEPTION 'work order % is not a released order of the item', NEW.wo_id
            USING ERRCODE = '23503', CONSTRAINT = 'fk_lot_master_wo_item';
    END IF;
    IF NEW.supplier_cust_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM mst_cust c WHERE c.cust_id = NEW.supplier_cust_id
                      AND c.comp_cd = NEW.comp_cd AND c.use_yn = 'Y' AND c.cust_type IN ('PURCHASE', 'BOTH')) THEN
        RAISE EXCEPTION 'customer % is not an active supplier of company %', NEW.supplier_cust_id, NEW.comp_cd
            USING ERRCODE = '23503', CONSTRAINT = 'fk_lot_master_supplier_comp';
    END IF;
    RETURN NEW;
END
$$;
