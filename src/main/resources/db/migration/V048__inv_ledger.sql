-- =====================================================================
-- V048 재고 원장 엔진 (P4 WMS 1부 W3 — docs/30 4장 W3, 결정 D1·D3·D4·D8)
--
-- - inv_trx: 재고 원장(01 3.1) — 행마다 부호 있는 수량, 이동·상태 바꿈은 같은 move_grp_id 의 두 행(합 0), 정정은 역분개 행만
-- - inv_stock: 현재고 = 원장의 투영. inv_trx INSERT 트리거만 고친다(직접 DML 은 트리거 깊이로 거부). qty >= 0 이
--   마이너스 재고를 동시성까지 막는다(D3 — 두 출고가 동시에 와도 행 잠금 + CHECK 로 하나만)
-- - inv_reconcile_run: 대사 결과(sum(inv_trx) = inv_stock, 불일치는 알림만 — 자동 보정 없음)
-- - 1부는 LOT 비관리 품목만(D1): lot_id 칸은 두고 LOT 관리 품목은 트리거가 거부. lot_master FK 는 main 동기화 뒤
-- ※ 병행 개발(docs/29 6장): main 에 늦게 들어가는 쪽이 이 번호를 다시 매긴다
-- =====================================================================

INSERT INTO sys_code_grp (grp_cd, grp_nm_key, comp_ext_yn) VALUES
    ('INV_TRX_TYPE', 'code.grp.INV_TRX_TYPE', 'N'),
    ('WMS_ADJ_REASON', 'code.grp.WMS_ADJ_REASON', 'Y');
INSERT INTO sys_code (grp_cd, code, code_nm_key, sort_no) VALUES
    ('INV_TRX_TYPE', 'OPENING',    'code.INV_TRX_TYPE.OPENING',    1),
    ('INV_TRX_TYPE', 'ADJ_IN',     'code.INV_TRX_TYPE.ADJ_IN',     2),
    ('INV_TRX_TYPE', 'ADJ_OUT',    'code.INV_TRX_TYPE.ADJ_OUT',    3),
    ('INV_TRX_TYPE', 'MOVE_OUT',   'code.INV_TRX_TYPE.MOVE_OUT',   4),
    ('INV_TRX_TYPE', 'MOVE_IN',    'code.INV_TRX_TYPE.MOVE_IN',    5),
    ('INV_TRX_TYPE', 'STATUS_OUT', 'code.INV_TRX_TYPE.STATUS_OUT', 6),
    ('INV_TRX_TYPE', 'STATUS_IN',  'code.INV_TRX_TYPE.STATUS_IN',  7),
    ('INV_TRX_TYPE', 'COUNT_ADJ',  'code.INV_TRX_TYPE.COUNT_ADJ',  8),
    ('WMS_ADJ_REASON', 'DAMAGE', 'code.WMS_ADJ_REASON.DAMAGE', 1),
    ('WMS_ADJ_REASON', 'LOSS',   'code.WMS_ADJ_REASON.LOSS',   2),
    ('WMS_ADJ_REASON', 'FOUND',  'code.WMS_ADJ_REASON.FOUND',  3),
    ('WMS_ADJ_REASON', 'SAMPLE', 'code.WMS_ADJ_REASON.SAMPLE', 4),
    ('WMS_ADJ_REASON', 'OTHER',  'code.WMS_ADJ_REASON.OTHER',  9);

-- ---------------------------------------------------------------------
-- 1. 재고 원장
-- ---------------------------------------------------------------------
CREATE TABLE inv_trx (
    trx_id          bigint         GENERATED ALWAYS AS IDENTITY,
    comp_cd         varchar(20)    NOT NULL,
    plant_cd        varchar(20)    NOT NULL,
    trx_type        varchar(10)    NOT NULL,
    item_id         bigint         NOT NULL,
    node_id         bigint         NOT NULL,
    lot_id          bigint,
    stock_status    varchar(10)    NOT NULL,
    qty             numeric(18,6)  NOT NULL,
    unit_cd         varchar(30)    NOT NULL,
    move_grp_id     uuid,
    src_type        varchar(20)    NOT NULL,
    src_id          bigint,
    reason_cd       varchar(30),
    remark          varchar(500),
    client_req_id   varchar(64),
    req_seq         smallint,
    occurred_at     timestamptz    NOT NULL,
    recorded_at     timestamptz    NOT NULL DEFAULT now(),
    entry_method    varchar(10)    NOT NULL,
    recorded_by     varchar(50)    NOT NULL,
    performed_by    bigint,
    reversal_of_id  bigint,
    CONSTRAINT pk_inv_trx PRIMARY KEY (trx_id),
    CONSTRAINT fk_inv_trx_plant FOREIGN KEY (comp_cd, plant_cd) REFERENCES sys_plant (comp_cd, plant_cd),
    CONSTRAINT fk_inv_trx_item FOREIGN KEY (item_id) REFERENCES mst_item (item_id),
    CONSTRAINT fk_inv_trx_node FOREIGN KEY (node_id) REFERENCES mst_equip_hier (node_id),
    CONSTRAINT fk_inv_trx_worker FOREIGN KEY (performed_by) REFERENCES mst_worker (worker_id),
    CONSTRAINT fk_inv_trx_reversal FOREIGN KEY (reversal_of_id) REFERENCES inv_trx (trx_id),
    CONSTRAINT ck_inv_trx_type CHECK (trx_type IN ('OPENING', 'ADJ_IN', 'ADJ_OUT', 'MOVE_OUT', 'MOVE_IN', 'STATUS_OUT', 'STATUS_IN',
                                                   'COUNT_ADJ')),
    CONSTRAINT ck_inv_trx_status CHECK (stock_status IN ('AVAILABLE', 'QC', 'HOLD', 'BLOCKED')),
    CONSTRAINT ck_inv_trx_method CHECK (entry_method IN ('KEY', 'SCAN', 'AUTO', 'IMPORT', 'CORRECT')),
    CONSTRAINT ck_inv_trx_src CHECK (src_type ~ '^[A-Z][A-Z0-9_]{1,19}$'),
    CONSTRAINT ck_inv_trx_qty_scale CHECK (qty <> 0 AND abs(qty) < 1000000000000),
    -- 방향: 들어오는 유형은 +, 나가는 유형은 −, 역분개는 반대. 실사 조정은 차이만큼(부호 자유)
    CONSTRAINT ck_inv_trx_sign CHECK (
        trx_type = 'COUNT_ADJ'
        OR (trx_type IN ('OPENING', 'ADJ_IN', 'MOVE_IN', 'STATUS_IN') AND (qty > 0) = (reversal_of_id IS NULL))
        OR (trx_type IN ('ADJ_OUT', 'MOVE_OUT', 'STATUS_OUT') AND (qty < 0) = (reversal_of_id IS NULL))),
    -- 이동·상태 바꿈은 짝(move_grp_id) 필수, 그 밖은 짝 없음
    CONSTRAINT ck_inv_trx_move CHECK ((trx_type IN ('MOVE_OUT', 'MOVE_IN', 'STATUS_OUT', 'STATUS_IN')) = (move_grp_id IS NOT NULL)),
    -- 정정 = 역분개(입력 방식 CORRECT + 사유), 그 밖에는 CORRECT 를 쓰지 않는다
    CONSTRAINT ck_inv_trx_correct CHECK ((reversal_of_id IS NULL AND entry_method <> 'CORRECT')
        OR (reversal_of_id IS NOT NULL AND entry_method = 'CORRECT' AND length(btrim(remark)) > 0)),
    CONSTRAINT ck_inv_trx_req CHECK ((client_req_id IS NULL) = (req_seq IS NULL))
);
COMMENT ON TABLE inv_trx IS '재고 원장 (docs/30 W3, 01 3.1). 수정·삭제 없음, 정정은 역분개 행. 현재고 inv_stock 은 이 테이블 INSERT 트리거의 투영';
COMMENT ON COLUMN inv_trx.lot_id IS 'LOT (1부는 항상 null — LOT 관리 품목은 2부, lot_master FK 는 main 동기화 뒤)';
COMMENT ON COLUMN inv_trx.src_type IS '출처: MANUAL·COUNT·OPENING (2부: PRD_RECEIPT·MAT_RECEIPT·RCV_PO·WO_ISSUE·SHIP …)';
COMMENT ON COLUMN inv_trx.client_req_id IS '멱등 키 — 같은 요청을 다시 보내면 처음 결과를 돌려준다(req_seq = 요청 안 행 순서)';
CREATE UNIQUE INDEX uq_inv_trx_req ON inv_trx (comp_cd, client_req_id, req_seq) WHERE client_req_id IS NOT NULL;
CREATE UNIQUE INDEX uq_inv_trx_reversal ON inv_trx (reversal_of_id) WHERE reversal_of_id IS NOT NULL;
CREATE INDEX ix_inv_trx_item ON inv_trx (comp_cd, item_id, occurred_at, trx_id);
CREATE INDEX ix_inv_trx_plant ON inv_trx (comp_cd, plant_cd, occurred_at, trx_id);
CREATE INDEX ix_inv_trx_move ON inv_trx (move_grp_id) WHERE move_grp_id IS NOT NULL;

-- ---------------------------------------------------------------------
-- 2. 현재고 (투영)
-- ---------------------------------------------------------------------
CREATE TABLE inv_stock (
    stock_id      bigint         GENERATED ALWAYS AS IDENTITY,
    comp_cd       varchar(20)    NOT NULL,
    plant_cd      varchar(20)    NOT NULL,
    item_id       bigint         NOT NULL,
    node_id       bigint         NOT NULL,
    lot_id        bigint,
    stock_status  varchar(10)    NOT NULL,
    qty           numeric(18,6)  NOT NULL DEFAULT 0,
    last_trx_id   bigint         NOT NULL,
    updated_at    timestamptz    NOT NULL DEFAULT now(),
    CONSTRAINT pk_inv_stock PRIMARY KEY (stock_id),
    CONSTRAINT fk_inv_stock_item FOREIGN KEY (item_id) REFERENCES mst_item (item_id),
    CONSTRAINT fk_inv_stock_node FOREIGN KEY (node_id) REFERENCES mst_equip_hier (node_id),
    CONSTRAINT uq_inv_stock_key UNIQUE NULLS NOT DISTINCT (comp_cd, item_id, node_id, lot_id, stock_status),
    -- D3: 마이너스 재고 없음 — 동시 출고도 이 제약이 마지막에 막는다(23514 → 409 INSUFFICIENT_STOCK)
    CONSTRAINT ck_inv_stock_qty CHECK (qty >= 0)
);
COMMENT ON TABLE inv_stock IS '현재고 = inv_trx 의 투영(키: 품목 × 위치 × LOT × 재고 상태). 수량 0 행은 남긴다. 직접 고치지 않는다';
CREATE INDEX ix_inv_stock_node ON inv_stock (comp_cd, plant_cd, node_id);

-- 원장 INSERT 전 검사 — 서비스가 먼저 보지만 직접 SQL 까지 여기서 막는다
CREATE FUNCTION hb_guard_inv_trx() RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    v_item  mst_item;
    v_node  mst_equip_hier;
    v_stock char(1);
    v_orig  inv_trx;
BEGIN
    SELECT * INTO v_item FROM mst_item i WHERE i.item_id = NEW.item_id AND i.comp_cd = NEW.comp_cd;
    IF v_item.item_id IS NULL THEN
        RAISE EXCEPTION 'item % is not of company %', NEW.item_id, NEW.comp_cd USING ERRCODE = '23503', CONSTRAINT = 'fk_inv_trx_item_comp';
    END IF;
    IF NEW.unit_cd <> v_item.unit_cd THEN
        RAISE EXCEPTION 'ledger quantity is in the item base unit %', v_item.unit_cd
            USING ERRCODE = '23514', CONSTRAINT = 'ck_inv_trx_unit';
    END IF;
    -- 1부(D1): LOT 비관리 품목만, LOT 칸은 비운다
    IF NEW.lot_id IS NOT NULL OR v_item.lot_ctrl_yn = 'Y' THEN
        RAISE EXCEPTION 'lot-controlled items come with lots (P4 part 2)' USING ERRCODE = '23514', CONSTRAINT = 'ck_inv_trx_lot_item';
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
        -- 새 사실은 사용 중 품목·사용 중이고 재고를 둘 수 있는 위치에만(역분개는 원래 위치로 되돌리는 것이라 허용)
        IF v_item.use_yn <> 'Y' THEN
            RAISE EXCEPTION 'item % is not in use', NEW.item_id USING ERRCODE = '23514', CONSTRAINT = 'ck_inv_trx_item_use';
        END IF;
        SELECT a.stock_yn INTO v_stock FROM wms_loc_attr a WHERE a.node_id = NEW.node_id;
        IF v_node.use_yn <> 'Y' OR coalesce(v_stock, 'Y') <> 'Y' THEN
            RAISE EXCEPTION 'node % does not hold stock', NEW.node_id USING ERRCODE = '23514', CONSTRAINT = 'ck_inv_trx_node_stock';
        END IF;
    ELSE
        SELECT * INTO v_orig FROM inv_trx t WHERE t.trx_id = NEW.reversal_of_id AND t.comp_cd = NEW.comp_cd;
        IF v_orig.trx_id IS NULL THEN
            RAISE EXCEPTION 'trx % is not of company %', NEW.reversal_of_id, NEW.comp_cd
                USING ERRCODE = '23503', CONSTRAINT = 'fk_inv_trx_reversal_comp';
        END IF;
        -- 역분개 = 원래 행의 정확한 반대(역분개의 역분개는 없다 — 다시 넣으려면 새 사실로)
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
CREATE TRIGGER tg_inv_trx_guard BEFORE INSERT ON inv_trx FOR EACH ROW EXECUTE FUNCTION hb_guard_inv_trx();
CREATE TRIGGER tg_inv_trx_ledger BEFORE UPDATE OR DELETE ON inv_trx FOR EACH ROW EXECUTE FUNCTION hb_forbid_update_delete();

-- 투영: 원장 한 행 = 현재고 한 키에 더하기. 행 잠금(UPDATE) + CHECK(qty >= 0) 이 동시 출고를 줄 세우고 막는다.
-- UPDATE 먼저 — INSERT … ON CONFLICT DO UPDATE 는 충돌 확인 전에 "넣으려던 행"(출고면 음수)에 CHECK 를 걸어
-- 재고가 있어도 출고가 거부된다(2026-10-20 발견). 키가 없을 때만 INSERT(동시 첫 입고는 ON CONFLICT 로 합친다)
CREATE FUNCTION hb_inv_apply() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    UPDATE inv_stock s SET qty = s.qty + NEW.qty, last_trx_id = NEW.trx_id, updated_at = now()
     WHERE s.comp_cd = NEW.comp_cd AND s.item_id = NEW.item_id AND s.node_id = NEW.node_id
       AND s.lot_id IS NOT DISTINCT FROM NEW.lot_id AND s.stock_status = NEW.stock_status;
    IF NOT FOUND THEN
        INSERT INTO inv_stock AS s (comp_cd, plant_cd, item_id, node_id, lot_id, stock_status, qty, last_trx_id)
        VALUES (NEW.comp_cd, NEW.plant_cd, NEW.item_id, NEW.node_id, NEW.lot_id, NEW.stock_status, NEW.qty, NEW.trx_id)
        ON CONFLICT (comp_cd, item_id, node_id, lot_id, stock_status)
        DO UPDATE SET qty = s.qty + EXCLUDED.qty, last_trx_id = EXCLUDED.last_trx_id, updated_at = now();
    END IF;
    RETURN NULL;
END
$$;
CREATE TRIGGER tg_inv_trx_apply AFTER INSERT ON inv_trx FOR EACH ROW EXECUTE FUNCTION hb_inv_apply();

-- 현재고는 투영 트리거 안에서만 바뀐다 — 앱의 직접 INSERT·UPDATE·DELETE 는 트리거 깊이 1 이라 거부
-- (SECURITY DEFINER 를 쓰지 않으려고 앱에 쓰기 권한은 주되, 이 트리거가 막는다 — RLS 우회 금지 규칙)
CREATE FUNCTION hb_guard_inv_stock() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF pg_trigger_depth() < 2 THEN
        RAISE EXCEPTION 'inv_stock is a projection of inv_trx (post a ledger row instead)'
            USING ERRCODE = '55000', CONSTRAINT = 'ck_inv_stock_projection';
    END IF;
    RETURN CASE WHEN TG_OP = 'DELETE' THEN OLD ELSE NEW END;
END
$$;
CREATE TRIGGER tg_inv_stock_guard BEFORE INSERT OR UPDATE OR DELETE ON inv_stock FOR EACH ROW EXECUTE FUNCTION hb_guard_inv_stock();

-- 이동·상태 바꿈 짝: 같은 move_grp_id 는 정확히 2행, 합 0, 같은 품목·LOT, (위치, 상태) 가 다름, 유형 짝이 맞음.
-- 트랜잭션 끝에 검사(지연 제약 트리거) — 두 행을 차례로 넣는 동안은 짝이 맞지 않으므로
CREATE FUNCTION hb_check_inv_move() RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    r record;
BEGIN
    SELECT count(*) AS n, sum(qty) AS total, count(DISTINCT item_id) AS items, count(DISTINCT coalesce(lot_id, -1)) AS lots,
           count(DISTINCT (node_id, stock_status)) AS places, count(DISTINCT regexp_replace(trx_type, '_(IN|OUT)$', '')) AS kinds,
           bool_or(trx_type LIKE '%\_OUT' ESCAPE '\' ) AND bool_or(trx_type LIKE '%\_IN' ESCAPE '\') AS paired,
           bool_and(comp_cd = NEW.comp_cd AND plant_cd = NEW.plant_cd) AS same_plant
      INTO r
      FROM inv_trx WHERE move_grp_id = NEW.move_grp_id;
    IF r.n <> 2 OR r.total <> 0 OR r.items <> 1 OR r.lots <> 1 OR r.places <> 2 OR r.kinds <> 1 OR NOT r.paired OR NOT r.same_plant THEN
        RAISE EXCEPTION 'move group % must be one OUT row and one IN row of the same item that sum to zero', NEW.move_grp_id
            USING ERRCODE = '23514', CONSTRAINT = 'ck_inv_trx_move_pair';
    END IF;
    RETURN NULL;
END
$$;
CREATE CONSTRAINT TRIGGER tg_inv_trx_move_pair AFTER INSERT ON inv_trx DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW WHEN (NEW.move_grp_id IS NOT NULL) EXECUTE FUNCTION hb_check_inv_move();

-- 노드 경로(공장/창고/랙/칸) — 조회용. RLS 그대로(SECURITY INVOKER), 깊이 상한 20
CREATE FUNCTION wms_node_path(p_node bigint) RETURNS text
    LANGUAGE sql STABLE
AS $$
    WITH RECURSIVE up AS (
        SELECT h.node_id, h.parent_id, h.plant_cd, h.node_cd::text AS path, 1 AS d FROM mst_equip_hier h WHERE h.node_id = p_node
        UNION ALL
        SELECT p.node_id, p.parent_id, p.plant_cd, p.node_cd || '/' || up.path, up.d + 1
          FROM up JOIN mst_equip_hier p ON p.node_id = up.parent_id WHERE up.d < 20)
    SELECT plant_cd || '/' || path FROM up WHERE parent_id IS NULL
$$;

-- ---------------------------------------------------------------------
-- 3. 대사 결과
-- ---------------------------------------------------------------------
CREATE TABLE inv_reconcile_run (
    run_id          bigint        GENERATED ALWAYS AS IDENTITY,
    comp_cd         varchar(20)   NOT NULL,
    run_at          timestamptz   NOT NULL DEFAULT now(),
    key_count       integer       NOT NULL,
    mismatch_count  integer       NOT NULL,
    detail          jsonb         NOT NULL DEFAULT '[]'::jsonb,
    run_by          varchar(80)   NOT NULL,
    CONSTRAINT pk_inv_reconcile_run PRIMARY KEY (run_id),
    CONSTRAINT fk_inv_reconcile_run_comp FOREIGN KEY (comp_cd) REFERENCES sys_comp (comp_cd),
    CONSTRAINT ck_inv_reconcile_run_cnt CHECK (key_count >= 0 AND mismatch_count BETWEEN 0 AND key_count + mismatch_count)
);
COMMENT ON TABLE inv_reconcile_run IS '재고 대사 결과 — sum(inv_trx) 와 inv_stock 비교. 불일치는 알림만, 자동 보정 없음(01 3.1). detail 은 최대 100건';
CREATE INDEX ix_inv_reconcile_run ON inv_reconcile_run (comp_cd, run_at);

-- ---------------------------------------------------------------------
-- 4. RLS · 권한 · 화면 · 보존
-- ---------------------------------------------------------------------
CREATE TRIGGER tg_audit AFTER INSERT OR UPDATE OR DELETE ON inv_reconcile_run FOR EACH ROW EXECUTE FUNCTION hb_audit('run_id', '', 'detail');

ALTER TABLE inv_trx ENABLE ROW LEVEL SECURITY;
ALTER TABLE inv_trx FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON inv_trx USING (comp_cd = hb_current_comp()) WITH CHECK (comp_cd = hb_current_comp());
ALTER TABLE inv_stock ENABLE ROW LEVEL SECURITY;
ALTER TABLE inv_stock FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON inv_stock USING (comp_cd = hb_current_comp()) WITH CHECK (comp_cd = hb_current_comp());
ALTER TABLE inv_reconcile_run ENABLE ROW LEVEL SECURITY;
ALTER TABLE inv_reconcile_run FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON inv_reconcile_run USING (comp_cd = hb_current_comp()) WITH CHECK (comp_cd = hb_current_comp());
REVOKE UPDATE, DELETE, TRUNCATE ON inv_trx FROM ${app_role};
REVOKE DELETE, TRUNCATE ON inv_stock FROM ${app_role};
REVOKE UPDATE, DELETE, TRUNCATE ON inv_reconcile_run FROM ${app_role};

INSERT INTO sys_prog (prog_cd, prog_nm_key, view_path, module_cd, actions, sort_no) VALUES
    ('wms-stock', 'prog.wmsStock', 'wms/stock',     'WMS', ARRAY['SEARCH','EXCEL','FN1']::varchar(20)[], 430),
    ('wms-trx',   'prog.wmsTrx',   'wms/trx',       'WMS', ARRAY['SEARCH','SAVE','EXCEL']::varchar(20)[], 440),
    ('wms-move',  'prog.wmsMove',  'wms/stock-move', 'WMS', ARRAY['SEARCH','SAVE']::varchar(20)[], 450);
INSERT INTO sys_retention_table (table_nm, class_cd) VALUES
    -- D8: 재고 원장은 LOT 추적 근거 — 품질기록과 같게(부품 EOL + 1년)
    ('inv_trx', 'QUALITY_RECORD'), ('inv_stock', 'QUALITY_RECORD'), ('inv_reconcile_run', 'SYSTEM');
