-- =====================================================================
-- WMS 2부 B6a 출하 (docs/30 12.5·12.7, D-B15~D-B20) — 임시 번호(docs/29 2.2)
--
-- - wms_ship / wms_ship_line / wms_ship_pick: 출하 지시 머리·줄(수주 행)·피킹(LOT·출발 칸·수량)
--   머리 DRAFT → PICKING(피킹이 하나라도) → SHIPPED(출하 확정), 출하 취소 = SHIPPED → PICKING, 지시 취소 = CANCELLED(남은 피킹 없을 때)
--   피킹 PICKED → SHIPPED(출하 확정) / UNPICKED(피킹 취소 — 이동 역분개), 출하 취소는 SHIPPED → PICKED
-- - 원장: 피킹 = MOVE 짝(출발 칸 → 출하 대기 STAGING, 출처 SHIP_PICK), 출하 = SHIP −(STAGING 칸, 출처 SHIP_PICK) — 수주 행 출하 수량 원본
--   영업 sal_order_line.shipped_qty 는 영업 계약 OrderShipGate 가 바꾸는 투영(D-B16, WMS 는 영업 표에 쓰지 않는다)
-- - 원장 검사(V122 판 교체): 보류 LOT 은 SHIP 도 거부
-- - 정책 SHIP_BLOCK_UNAPPROVED_CHANGE(회사, 기본 Y): PLM 출하 확인(PPAP 미승인 변경 LOT) block 이면 출하 409
-- - 화면 wms-ship, 채번 WMS_SHIP(SHP-yyMMdd-seq)
-- - 남의 표 변경 없음(sal_order_line 은 FK·읽기만)
-- =====================================================================

INSERT INTO sys_policy_def (policy_key, value_type, default_value, allowed_values, min_value, max_value, plant_scope_yn, desc_key) VALUES
    ('SHIP_BLOCK_UNAPPROVED_CHANGE', 'BOOL', 'Y', NULL, NULL, NULL, 'N', 'policy.SHIP_BLOCK_UNAPPROVED_CHANGE');

INSERT INTO sys_code_grp (grp_cd, grp_nm_key, comp_ext_yn) VALUES
    ('WMS_SHIP_STATUS', 'code.grp.WMS_SHIP_STATUS', 'N'),
    ('WMS_PICK_STATUS', 'code.grp.WMS_PICK_STATUS', 'N');
INSERT INTO sys_code (grp_cd, code, code_nm_key, sort_no) VALUES
    ('WMS_SHIP_STATUS', 'DRAFT',     'code.WMS_SHIP_STATUS.DRAFT',     1),
    ('WMS_SHIP_STATUS', 'PICKING',   'code.WMS_SHIP_STATUS.PICKING',   2),
    ('WMS_SHIP_STATUS', 'SHIPPED',   'code.WMS_SHIP_STATUS.SHIPPED',   3),
    ('WMS_SHIP_STATUS', 'CANCELLED', 'code.WMS_SHIP_STATUS.CANCELLED', 4),
    ('WMS_PICK_STATUS', 'PICKED',    'code.WMS_PICK_STATUS.PICKED',    1),
    ('WMS_PICK_STATUS', 'SHIPPED',   'code.WMS_PICK_STATUS.SHIPPED',   2),
    ('WMS_PICK_STATUS', 'UNPICKED',  'code.WMS_PICK_STATUS.UNPICKED',  3),
    ('INV_TRX_TYPE', 'SHIP', 'code.INV_TRX_TYPE.SHIP', 26);

-- ---------------------------------------------------------------------
-- 1. 출하 지시 머리
-- ---------------------------------------------------------------------
CREATE TABLE wms_ship (
    ship_id        bigint         GENERATED ALWAYS AS IDENTITY,
    comp_cd        varchar(20)    NOT NULL,
    plant_cd       varchar(20)    NOT NULL,
    ship_no        varchar(40)    NOT NULL,
    cust_id        bigint         NOT NULL,
    stage_node_id  bigint         NOT NULL,
    plan_date      date           NOT NULL,
    status         varchar(10)    NOT NULL DEFAULT 'DRAFT',
    remark         varchar(500),
    shipped_at     timestamptz,
    shipped_by     varchar(50),
    cancel_reason  varchar(500),
    row_ver        integer        NOT NULL DEFAULT 0,
    created_at     timestamptz    NOT NULL DEFAULT now(),
    created_by     varchar(50)    NOT NULL,
    updated_at     timestamptz    NOT NULL DEFAULT now(),
    updated_by     varchar(50)    NOT NULL,
    CONSTRAINT pk_wms_ship PRIMARY KEY (ship_id),
    CONSTRAINT fk_wms_ship_plant FOREIGN KEY (comp_cd, plant_cd) REFERENCES sys_plant (comp_cd, plant_cd),
    CONSTRAINT fk_wms_ship_cust FOREIGN KEY (cust_id) REFERENCES mst_cust (cust_id),
    CONSTRAINT fk_wms_ship_stage FOREIGN KEY (stage_node_id) REFERENCES mst_equip_hier (node_id),
    CONSTRAINT uq_wms_ship_no UNIQUE (comp_cd, ship_no),
    CONSTRAINT ck_wms_ship_status CHECK (status IN ('DRAFT', 'PICKING', 'SHIPPED', 'CANCELLED')),
    CONSTRAINT ck_wms_ship_date CHECK (plan_date BETWEEN DATE '2000-01-01' AND DATE '2999-12-31'),
    CONSTRAINT ck_wms_ship_shipped CHECK ((status = 'SHIPPED') = (shipped_at IS NOT NULL AND shipped_by IS NOT NULL)),
    CONSTRAINT ck_wms_ship_cancel CHECK ((status = 'CANCELLED') = (coalesce(length(btrim(cancel_reason)), 0) > 0))
);
COMMENT ON TABLE wms_ship IS '출하 지시 머리 (docs/30 12.7, B6a). 한 고객·한 공장, 출하 대기 위치(STAGING) 하나';
CREATE INDEX ix_wms_ship_plant ON wms_ship (comp_cd, plant_cd, status, plan_date);

-- ---------------------------------------------------------------------
-- 2. 출하 줄 = 수주 행
-- ---------------------------------------------------------------------
CREATE TABLE wms_ship_line (
    ship_line_id   bigint         GENERATED ALWAYS AS IDENTITY,
    comp_cd        varchar(20)    NOT NULL,
    ship_id        bigint         NOT NULL,
    line_no        integer        NOT NULL,
    order_line_id  bigint         NOT NULL,
    item_id        bigint         NOT NULL,
    qty            numeric(18,6)  NOT NULL,
    created_at     timestamptz    NOT NULL DEFAULT now(),
    created_by     varchar(50)    NOT NULL,
    updated_at     timestamptz    NOT NULL DEFAULT now(),
    updated_by     varchar(50)    NOT NULL,
    CONSTRAINT pk_wms_ship_line PRIMARY KEY (ship_line_id),
    CONSTRAINT fk_wms_ship_line_ship FOREIGN KEY (ship_id) REFERENCES wms_ship (ship_id),
    CONSTRAINT fk_wms_ship_line_order FOREIGN KEY (order_line_id) REFERENCES sal_order_line (line_id),
    CONSTRAINT fk_wms_ship_line_item FOREIGN KEY (item_id) REFERENCES mst_item (item_id),
    CONSTRAINT uq_wms_ship_line_no UNIQUE (ship_id, line_no),
    CONSTRAINT uq_wms_ship_line_order UNIQUE (ship_id, order_line_id),
    CONSTRAINT ck_wms_ship_line_no CHECK (line_no BETWEEN 1 AND 9999),
    CONSTRAINT ck_wms_ship_line_qty CHECK (qty > 0)
);
COMMENT ON TABLE wms_ship_line IS '출하 줄 — 수주 행 하나, 지시 수량(품목 기본 단위). 출하 수량은 피킹·원장에서';
CREATE INDEX ix_wms_ship_line_order ON wms_ship_line (comp_cd, order_line_id);

-- ---------------------------------------------------------------------
-- 3. 피킹 — LOT·출발 칸·수량, 원장 이동 짝(출발 → STAGING)과 출하 SHIP 행이 이 id 를 출처로 가진다
-- ---------------------------------------------------------------------
CREATE TABLE wms_ship_pick (
    pick_id        bigint         GENERATED ALWAYS AS IDENTITY,
    comp_cd        varchar(20)    NOT NULL,
    ship_line_id   bigint         NOT NULL,
    from_node_id   bigint         NOT NULL,
    lot_id         bigint,
    qty            numeric(18,6)  NOT NULL,
    status         varchar(10)    NOT NULL DEFAULT 'PICKED',
    created_at     timestamptz    NOT NULL DEFAULT now(),
    created_by     varchar(50)    NOT NULL,
    updated_at     timestamptz    NOT NULL DEFAULT now(),
    updated_by     varchar(50)    NOT NULL,
    CONSTRAINT pk_wms_ship_pick PRIMARY KEY (pick_id),
    CONSTRAINT fk_wms_ship_pick_line FOREIGN KEY (ship_line_id) REFERENCES wms_ship_line (ship_line_id),
    CONSTRAINT fk_wms_ship_pick_node FOREIGN KEY (from_node_id) REFERENCES mst_equip_hier (node_id),
    CONSTRAINT fk_wms_ship_pick_lot FOREIGN KEY (lot_id) REFERENCES lot_master (lot_id),
    CONSTRAINT ck_wms_ship_pick_qty CHECK (qty > 0),
    CONSTRAINT ck_wms_ship_pick_status CHECK (status IN ('PICKED', 'SHIPPED', 'UNPICKED'))
);
COMMENT ON TABLE wms_ship_pick IS '출하 피킹 — PICKED(STAGING 에 있음) → SHIPPED(출하 원장) / UNPICKED(이동 역분개). 출하 취소는 SHIPPED → PICKED';
CREATE INDEX ix_wms_ship_pick_line ON wms_ship_pick (comp_cd, ship_line_id);

-- 머리: 전이 표, 번호·공장·고객·대기 위치는 고정(DRAFT 동안 대기 위치·날짜·비고만), 대기 위치 = 같은 회사·공장의 STAGING 위치
CREATE FUNCTION hb_guard_wms_ship() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'INSERT' THEN
        IF NEW.status <> 'DRAFT' THEN
            RAISE EXCEPTION 'new shipment must be DRAFT' USING ERRCODE = '55000', CONSTRAINT = 'ck_wms_ship_transition';
        END IF;
    ELSE
        IF (NEW.ship_no, NEW.plant_cd, NEW.cust_id, NEW.comp_cd) IS DISTINCT FROM (OLD.ship_no, OLD.plant_cd, OLD.cust_id, OLD.comp_cd) THEN
            RAISE EXCEPTION 'shipment key is fixed' USING ERRCODE = '55000', CONSTRAINT = 'ck_wms_ship_fixed';
        END IF;
        IF OLD.status = 'CANCELLED' OR NOT (
               NEW.status = OLD.status
            OR (OLD.status, NEW.status) IN (('DRAFT', 'PICKING'), ('PICKING', 'DRAFT'), ('PICKING', 'SHIPPED'), ('SHIPPED', 'PICKING'),
                                            ('DRAFT', 'CANCELLED'))) THEN
            RAISE EXCEPTION 'shipment % cannot go % -> %', OLD.ship_no, OLD.status, NEW.status
                USING ERRCODE = '55000', CONSTRAINT = 'ck_wms_ship_transition';
        END IF;
        IF OLD.status <> 'DRAFT' AND NEW.stage_node_id <> OLD.stage_node_id THEN
            RAISE EXCEPTION 'staging location is fixed after picking' USING ERRCODE = '55000', CONSTRAINT = 'ck_wms_ship_fixed';
        END IF;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM mst_equip_hier n JOIN wms_loc_attr a ON a.node_id = n.node_id
                    WHERE n.node_id = NEW.stage_node_id AND n.comp_cd = NEW.comp_cd AND n.plant_cd = NEW.plant_cd
                      AND n.node_type = 'LOC' AND a.loc_usage = 'STAGING') THEN
        RAISE EXCEPTION 'node % is not a staging location of plant %', NEW.stage_node_id, NEW.plant_cd
            USING ERRCODE = '23514', CONSTRAINT = 'ck_wms_ship_stage';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM mst_cust c WHERE c.cust_id = NEW.cust_id AND c.comp_cd = NEW.comp_cd) THEN
        RAISE EXCEPTION 'customer % is not of company %', NEW.cust_id, NEW.comp_cd USING ERRCODE = '23503', CONSTRAINT = 'fk_wms_ship_cust_comp';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER tg_guard BEFORE INSERT OR UPDATE ON wms_ship FOR EACH ROW EXECUTE FUNCTION hb_guard_wms_ship();

-- 줄: 머리 DRAFT 일 때만 추가, 수주 행 = 같은 회사·같은 고객·같은 품목. 줄은 바뀌지 않는다(지시 수량은 DRAFT 일 때만)
CREATE FUNCTION hb_guard_wms_ship_line() RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    v_ship wms_ship%ROWTYPE;
BEGIN
    SELECT * INTO v_ship FROM wms_ship WHERE ship_id = NEW.ship_id;
    IF NOT FOUND OR v_ship.comp_cd <> NEW.comp_cd THEN
        RAISE EXCEPTION 'shipment % is not of company %', NEW.ship_id, NEW.comp_cd USING ERRCODE = '23503', CONSTRAINT = 'fk_wms_ship_line_comp';
    END IF;
    IF TG_OP = 'UPDATE' THEN
        IF (NEW.ship_id, NEW.line_no, NEW.order_line_id, NEW.item_id, NEW.comp_cd)
           IS DISTINCT FROM (OLD.ship_id, OLD.line_no, OLD.order_line_id, OLD.item_id, OLD.comp_cd) THEN
            RAISE EXCEPTION 'shipment line key is fixed' USING ERRCODE = '55000', CONSTRAINT = 'ck_wms_ship_line_fixed';
        END IF;
    END IF;
    IF v_ship.status <> 'DRAFT' AND (TG_OP = 'INSERT' OR NEW.qty <> OLD.qty) THEN
        RAISE EXCEPTION 'shipment % is not a draft', v_ship.ship_no USING ERRCODE = '55000', CONSTRAINT = 'ck_wms_ship_line_draft';
    END IF;
    IF TG_OP = 'INSERT' AND NOT EXISTS (
            SELECT 1 FROM sal_order_line l JOIN sal_order o ON o.order_id = l.order_id
             WHERE l.line_id = NEW.order_line_id AND l.comp_cd = NEW.comp_cd AND o.cust_id = v_ship.cust_id AND l.item_id = NEW.item_id) THEN
        RAISE EXCEPTION 'order line % is not a line of customer % item %', NEW.order_line_id, v_ship.cust_id, NEW.item_id
            USING ERRCODE = '23503', CONSTRAINT = 'fk_wms_ship_line_order_comp';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER tg_guard BEFORE INSERT OR UPDATE ON wms_ship_line FOR EACH ROW EXECUTE FUNCTION hb_guard_wms_ship_line();

-- 피킹: 줄·출발 칸·LOT 은 고정, 상태 전이 PICKED ↔ SHIPPED, PICKED → UNPICKED(끝). 출발 칸 = 같은 회사·같은 공장
CREATE FUNCTION hb_guard_wms_ship_pick() RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    v_plant varchar(20);
BEGIN
    SELECT s.plant_cd INTO v_plant FROM wms_ship_line l JOIN wms_ship s ON s.ship_id = l.ship_id
     WHERE l.ship_line_id = NEW.ship_line_id AND l.comp_cd = NEW.comp_cd;
    IF v_plant IS NULL THEN
        RAISE EXCEPTION 'shipment line % is not of company %', NEW.ship_line_id, NEW.comp_cd USING ERRCODE = '23503', CONSTRAINT = 'fk_wms_ship_pick_comp';
    END IF;
    IF TG_OP = 'INSERT' THEN
        IF NEW.status <> 'PICKED' THEN
            RAISE EXCEPTION 'new pick must be PICKED' USING ERRCODE = '55000', CONSTRAINT = 'ck_wms_ship_pick_transition';
        END IF;
        IF NOT EXISTS (SELECT 1 FROM mst_equip_hier n WHERE n.node_id = NEW.from_node_id AND n.comp_cd = NEW.comp_cd AND n.plant_cd = v_plant) THEN
            RAISE EXCEPTION 'node % is not of plant %', NEW.from_node_id, v_plant USING ERRCODE = '23503', CONSTRAINT = 'fk_wms_ship_pick_node_comp';
        END IF;
    ELSE
        IF (NEW.ship_line_id, NEW.from_node_id, NEW.lot_id, NEW.qty, NEW.comp_cd)
           IS DISTINCT FROM (OLD.ship_line_id, OLD.from_node_id, OLD.lot_id, OLD.qty, OLD.comp_cd)
           OR NOT (NEW.status = OLD.status OR (OLD.status, NEW.status) IN (('PICKED', 'SHIPPED'), ('SHIPPED', 'PICKED'), ('PICKED', 'UNPICKED'))) THEN
            RAISE EXCEPTION 'pick % cannot go % -> %', OLD.pick_id, OLD.status, NEW.status
                USING ERRCODE = '55000', CONSTRAINT = 'ck_wms_ship_pick_transition';
        END IF;
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER tg_guard BEFORE INSERT OR UPDATE ON wms_ship_pick FOR EACH ROW EXECUTE FUNCTION hb_guard_wms_ship_pick();

CREATE TRIGGER tg_audit AFTER INSERT OR UPDATE OR DELETE ON wms_ship FOR EACH ROW EXECUTE FUNCTION hb_audit('ship_id', '', '');
CREATE TRIGGER tg_audit AFTER INSERT OR UPDATE OR DELETE ON wms_ship_line FOR EACH ROW EXECUTE FUNCTION hb_audit('ship_line_id', '', '');
CREATE TRIGGER tg_audit AFTER INSERT OR UPDATE OR DELETE ON wms_ship_pick FOR EACH ROW EXECUTE FUNCTION hb_audit('pick_id', '', '');

ALTER TABLE wms_ship ENABLE ROW LEVEL SECURITY;
ALTER TABLE wms_ship FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON wms_ship USING (comp_cd = hb_current_comp()) WITH CHECK (comp_cd = hb_current_comp());
ALTER TABLE wms_ship_line ENABLE ROW LEVEL SECURITY;
ALTER TABLE wms_ship_line FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON wms_ship_line USING (comp_cd = hb_current_comp()) WITH CHECK (comp_cd = hb_current_comp());
ALTER TABLE wms_ship_pick ENABLE ROW LEVEL SECURITY;
ALTER TABLE wms_ship_pick FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON wms_ship_pick USING (comp_cd = hb_current_comp()) WITH CHECK (comp_cd = hb_current_comp());
REVOKE DELETE, TRUNCATE ON wms_ship, wms_ship_line, wms_ship_pick FROM ${app_role};

-- ---------------------------------------------------------------------
-- 4. 원장 — SHIP −, 피킹 이동·출하는 출처 SHIP_PICK
-- ---------------------------------------------------------------------
ALTER TABLE inv_trx DROP CONSTRAINT ck_inv_trx_type;
ALTER TABLE inv_trx ADD CONSTRAINT ck_inv_trx_type CHECK (trx_type IN ('OPENING', 'ADJ_IN', 'ADJ_OUT', 'MOVE_OUT', 'MOVE_IN', 'STATUS_OUT',
                                                                        'STATUS_IN', 'COUNT_ADJ', 'RCV_PRD', 'RCV_MAT', 'ISSUE_WO',
                                                                        'CONV_OUT', 'CONV_IN', 'RCV_PO', 'SHIP'));
ALTER TABLE inv_trx DROP CONSTRAINT ck_inv_trx_sign;
ALTER TABLE inv_trx ADD CONSTRAINT ck_inv_trx_sign CHECK (
    trx_type = 'COUNT_ADJ'
    OR (trx_type IN ('OPENING', 'ADJ_IN', 'MOVE_IN', 'STATUS_IN', 'RCV_PRD', 'RCV_MAT', 'CONV_IN', 'RCV_PO') AND (qty > 0) = (reversal_of_id IS NULL))
    OR (trx_type IN ('ADJ_OUT', 'MOVE_OUT', 'STATUS_OUT', 'ISSUE_WO', 'CONV_OUT', 'SHIP') AND (qty < 0) = (reversal_of_id IS NULL)));
ALTER TABLE inv_trx ADD CONSTRAINT ck_inv_trx_ship_src CHECK (trx_type <> 'SHIP' OR (src_type = 'SHIP_PICK' AND src_id IS NOT NULL));
CREATE INDEX ix_inv_trx_ship_pick ON inv_trx (comp_cd, src_id) WHERE src_type = 'SHIP_PICK';

INSERT INTO sys_prog (prog_cd, prog_nm_key, view_path, module_cd, actions, sort_no) VALUES
    ('wms-ship', 'prog.wmsShip', 'wms/ship', 'WMS', ARRAY['SEARCH','SAVE','APPROVE']::varchar(20)[], 465);
INSERT INTO sys_retention_table (table_nm, class_cd) VALUES
    ('wms_ship', 'QUALITY_RECORD'), ('wms_ship_line', 'QUALITY_RECORD'), ('wms_ship_pick', 'QUALITY_RECORD');

-- 원장 INSERT 전 검사 — V122 판에서 보류 LOT 의 SHIP 도 거부
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
        IF v_lot.hold_yn = 'Y' AND NEW.reversal_of_id IS NULL AND NEW.trx_type IN ('ADJ_OUT', 'MOVE_OUT', 'SHIP') THEN
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
