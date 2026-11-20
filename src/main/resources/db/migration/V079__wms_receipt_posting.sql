-- =====================================================================
-- WMS 2부 B1 생산 입고 → 재고 원장 (docs/30 11장, D2 — MES 합의 2026-10-21) — 임시 번호(docs/29 2.2)
--
-- - inv_trx 유형 RCV_PRD(생산 입고, +). 출처 PRD_RECEIPT·src_id = prd_receipt.receipt_id, 멱등 키 PRD_RECEIPT:{id}
-- - inv_trx.lot_id: lot_master FK + 같은 회사. 규칙을 1부 "LOT 품목 거부" 에서 바꾼다:
--     LOT 관리 품목(lot_ctrl_yn = Y) = lot_id 필수 / 비관리 품목 = lot_id 비움(재고 키는 품목 × 위치, LOT 은 src_id 로만 연결)
-- - 이관(entry_method IMPORT + src_type PRD_RECEIPT)은 과거 사실의 옮겨 적기 → 사용 중지 품목·위치·재고 안 둠 검사를 건너뛴다
--   (회사·공장·단위·마감 검사는 그대로 — 마감은 다른 트리거 hb_check_inv_period)
-- - inv_backfill_run: 이관 실행 결과(행마다 실패 이유), inv_reconcile_run.missing_receipts: 원장에 없는 생산 입고 수
-- - MES 표(prd_*·lot_*)는 바꾸지 않는다(읽기만)
-- =====================================================================

INSERT INTO sys_code (grp_cd, code, code_nm_key, sort_no) VALUES
    ('INV_TRX_TYPE', 'RCV_PRD', 'code.INV_TRX_TYPE.RCV_PRD', 20);

ALTER TABLE inv_trx DROP CONSTRAINT ck_inv_trx_type;
ALTER TABLE inv_trx ADD CONSTRAINT ck_inv_trx_type CHECK (trx_type IN ('OPENING', 'ADJ_IN', 'ADJ_OUT', 'MOVE_OUT', 'MOVE_IN', 'STATUS_OUT',
                                                                        'STATUS_IN', 'COUNT_ADJ', 'RCV_PRD'));
ALTER TABLE inv_trx DROP CONSTRAINT ck_inv_trx_sign;
ALTER TABLE inv_trx ADD CONSTRAINT ck_inv_trx_sign CHECK (
    trx_type = 'COUNT_ADJ'
    OR (trx_type IN ('OPENING', 'ADJ_IN', 'MOVE_IN', 'STATUS_IN', 'RCV_PRD') AND (qty > 0) = (reversal_of_id IS NULL))
    OR (trx_type IN ('ADJ_OUT', 'MOVE_OUT', 'STATUS_OUT') AND (qty < 0) = (reversal_of_id IS NULL)));
ALTER TABLE inv_trx ADD CONSTRAINT fk_inv_trx_lot FOREIGN KEY (lot_id) REFERENCES lot_master (lot_id);
ALTER TABLE inv_stock ADD CONSTRAINT fk_inv_stock_lot FOREIGN KEY (lot_id) REFERENCES lot_master (lot_id);
COMMENT ON COLUMN inv_trx.lot_id IS 'LOT — LOT 관리 품목은 필수, 비관리 품목은 비움(출처 행으로 연결). 2부 B1';
CREATE INDEX ix_inv_trx_src ON inv_trx (comp_cd, src_type, src_id) WHERE src_id IS NOT NULL;

-- 원장 INSERT 전 검사 — V048 판을 바꾼다(LOT 규칙·이관 예외). 서비스가 먼저 보지만 직접 SQL 까지 여기서 막는다
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
    SELECT * INTO v_item FROM mst_item i WHERE i.item_id = NEW.item_id AND i.comp_cd = NEW.comp_cd;
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

-- ---------------------------------------------------------------------
-- 이관 실행 결과 (회사별)
-- ---------------------------------------------------------------------
CREATE TABLE inv_backfill_run (
    run_id      bigint        GENERATED ALWAYS AS IDENTITY,
    comp_cd     varchar(20)   NOT NULL,
    kind        varchar(20)   NOT NULL,
    run_at      timestamptz   NOT NULL DEFAULT now(),
    run_by      varchar(80)   NOT NULL,
    total       integer       NOT NULL,
    posted      integer       NOT NULL,
    existed     integer       NOT NULL,
    failed      integer       NOT NULL,
    detail      jsonb         NOT NULL DEFAULT '[]'::jsonb,
    CONSTRAINT pk_inv_backfill_run PRIMARY KEY (run_id),
    CONSTRAINT fk_inv_backfill_run_comp FOREIGN KEY (comp_cd) REFERENCES sys_comp (comp_cd),
    CONSTRAINT ck_inv_backfill_run_kind CHECK (kind IN ('PRD_RECEIPT')),
    CONSTRAINT ck_inv_backfill_run_cnt CHECK (total >= 0 AND posted >= 0 AND existed >= 0 AND failed >= 0
                                              AND posted + existed + failed = total)
);
COMMENT ON TABLE inv_backfill_run IS '재고 원장 이관 결과 (docs/30 11.4) — detail = 실패 행(최대 200건: 출처 id·오류 코드). 다시 돌리면 실패 행만 다시 시도된다(멱등 키)';
CREATE INDEX ix_inv_backfill_run ON inv_backfill_run (comp_cd, kind, run_at);
CREATE TRIGGER tg_audit AFTER INSERT OR UPDATE OR DELETE ON inv_backfill_run FOR EACH ROW EXECUTE FUNCTION hb_audit('run_id', '', 'detail');
ALTER TABLE inv_backfill_run ENABLE ROW LEVEL SECURITY;
ALTER TABLE inv_backfill_run FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON inv_backfill_run USING (comp_cd = hb_current_comp()) WITH CHECK (comp_cd = hb_current_comp());
REVOKE UPDATE, DELETE, TRUNCATE ON inv_backfill_run FROM ${app_role};
INSERT INTO sys_retention_table (table_nm, class_cd) VALUES ('inv_backfill_run', 'SYSTEM');

-- 대사: 원장에 없는 생산 입고 수(이관을 한 번 끝낸 회사만 셈 — 그 전에는 null)
ALTER TABLE inv_reconcile_run ADD COLUMN missing_receipts integer;
ALTER TABLE inv_reconcile_run ADD CONSTRAINT ck_inv_reconcile_run_missing CHECK (missing_receipts IS NULL OR missing_receipts >= 0);

-- 현재고 화면 FN2 = 생산 입고 이관
UPDATE sys_prog SET actions = ARRAY['SEARCH','EXCEL','FN1','FN2']::varchar(20)[] WHERE prog_cd = 'wms-stock';
