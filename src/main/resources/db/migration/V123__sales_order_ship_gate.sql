-- =====================================================================
-- 영업 출하 게이트 (docs/25 4.22, docs/30 12.5 — WMS B6, MES 보완 확정 2026-10-22)
-- - sal_order_line.shipped_qty: 출하 수량 투영(원본 = WMS SHIP 원장). 계약 OrderShipGate 만 바꾼다
-- - done_by_ship_yn: 출하가 다 나가 자동으로 닫힌 행(Y) / 사람이 닫은 행(N). 출하 취소로 미결이 생기면 Y 행만 다시 연다
-- - 행 가드: 끝난 행은 그대로 — 단 (1) 닫힌 행의 출하 수량만 바꾸기(취소 되돌림), (2) 자동 마감 행을 다시 열기 만 허용.
--   열린 행 수량은 출하 수량 아래로 못 내린다, 출하가 있는 행은 취소 못 한다(출하 취소가 먼저)
-- - 머리 가드: 끝난 수주는 그대로 — 단 자동 마감 행이 다시 열려 CLOSED → OPEN 만 허용
-- - 정책 SAL_OVERSHIP_PCT(회사, 기본 0): 수량 × (1 + pct/100) 까지 출하 허용
-- - 품목 기본 단위 잠금(V018·V091)에 수주 행을 더한다(수주·출하 수량 = 기본 단위)
-- =====================================================================

ALTER TABLE sal_order_line
    ADD COLUMN shipped_qty      numeric(18,6) NOT NULL DEFAULT 0,
    ADD COLUMN done_by_ship_yn  char(1)       NOT NULL DEFAULT 'N';
ALTER TABLE sal_order_line ADD CONSTRAINT ck_sal_order_line_shipped CHECK (shipped_qty >= 0);
ALTER TABLE sal_order_line ADD CONSTRAINT ck_sal_order_line_done_by_ship
    CHECK (done_by_ship_yn IN ('Y', 'N') AND (done_by_ship_yn = 'N' OR status = 'DONE'));
COMMENT ON COLUMN sal_order_line.shipped_qty IS '출하 수량 투영(기본 단위) — 원본은 WMS SHIP 원장, 계약 OrderShipGate 만 바꾼다(docs/25 4.22)';
COMMENT ON COLUMN sal_order_line.done_by_ship_yn IS 'Y = 출하가 다 나가 자동 마감(출하 취소로 미결이 생기면 다시 열림), N = 열림 또는 사람이 마감';

CREATE OR REPLACE FUNCTION hb_guard_sal_order_line() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'UPDATE' THEN
        IF NEW.order_id <> OLD.order_id OR NEW.line_no <> OLD.line_no OR NEW.item_id <> OLD.item_id THEN
            RAISE EXCEPTION 'order line target is fixed (cancel and add a new line)' USING ERRCODE = '55000', CONSTRAINT = 'ck_sal_order_line_fixed';
        END IF;
        IF OLD.status <> 'OPEN' THEN
            -- 끝난 행: 출하 수량만 바꾸기 또는 자동 마감 행 다시 열기(출하 취소) — 그 밖은 그대로
            IF (NEW.qty, NEW.due_date, NEW.cancel_reason, NEW.remark) IS DISTINCT FROM (OLD.qty, OLD.due_date, OLD.cancel_reason, OLD.remark)
               OR NOT ((NEW.status = OLD.status AND NEW.done_by_ship_yn = OLD.done_by_ship_yn AND OLD.status = 'DONE')
                       OR (OLD.status = 'DONE' AND OLD.done_by_ship_yn = 'Y' AND NEW.status = 'OPEN' AND NEW.done_by_ship_yn = 'N'
                           AND NEW.shipped_qty < NEW.qty)) THEN
                RAISE EXCEPTION 'order line % is closed', OLD.line_no USING ERRCODE = '55000', CONSTRAINT = 'ck_sal_order_line_closed';
            END IF;
            RETURN NEW;
        END IF;
        IF NEW.qty <> OLD.qty AND NEW.qty < NEW.shipped_qty THEN
            RAISE EXCEPTION 'order line % quantity is below shipped %', OLD.line_no, NEW.shipped_qty
                USING ERRCODE = '23514', CONSTRAINT = 'ck_sal_order_line_qty_shipped';
        END IF;
        IF NEW.status = 'CANCELLED' AND NEW.shipped_qty > 0 THEN
            RAISE EXCEPTION 'order line % has shipments — cancel the shipments first', OLD.line_no
                USING ERRCODE = '55000', CONSTRAINT = 'ck_sal_order_line_cancel_shipped';
        END IF;
        RETURN NEW;
    END IF;
    -- 새 행: 같은 회사의 열린 수주, 같은 회사의 사용 중 품목
    IF NOT EXISTS (SELECT 1 FROM sal_order o WHERE o.order_id = NEW.order_id AND o.comp_cd = NEW.comp_cd) THEN
        RAISE EXCEPTION 'order % is not of company %', NEW.order_id, NEW.comp_cd USING ERRCODE = '23503', CONSTRAINT = 'fk_sal_order_line_order_comp';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM sal_order o WHERE o.order_id = NEW.order_id AND o.status = 'OPEN') THEN
        RAISE EXCEPTION 'order % is closed', NEW.order_id USING ERRCODE = '55000', CONSTRAINT = 'ck_sal_order_closed';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM mst_item i WHERE i.item_id = NEW.item_id AND i.comp_cd = NEW.comp_cd AND i.use_yn = 'Y') THEN
        RAISE EXCEPTION 'item % is not an active item of company %', NEW.item_id, NEW.comp_cd
            USING ERRCODE = '23503', CONSTRAINT = 'fk_sal_order_line_item_comp';
    END IF;
    IF NEW.status <> 'OPEN' THEN
        RAISE EXCEPTION 'a new order line starts open' USING ERRCODE = '23514', CONSTRAINT = 'ck_sal_order_line_status_new';
    END IF;
    IF NEW.shipped_qty <> 0 OR NEW.done_by_ship_yn <> 'N' THEN
        RAISE EXCEPTION 'a new order line starts unshipped' USING ERRCODE = '23514', CONSTRAINT = 'ck_sal_order_line_status_new';
    END IF;
    RETURN NEW;
END
$$;

CREATE OR REPLACE FUNCTION hb_guard_sal_order() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'UPDATE' THEN
        IF NEW.cust_id <> OLD.cust_id OR NEW.order_no <> OLD.order_no THEN
            RAISE EXCEPTION 'order customer and number are fixed' USING ERRCODE = '55000', CONSTRAINT = 'ck_sal_order_fixed';
        END IF;
        -- 끝난 수주는 그대로 — 단 출하 취소로 자동 마감 행이 다시 열려 CLOSED → OPEN(다른 칸은 그대로)
        IF OLD.status <> 'OPEN' THEN
            IF NOT (OLD.status = 'CLOSED' AND NEW.status = 'OPEN'
                    AND (NEW.cust_po_no, NEW.order_date, NEW.remark) IS NOT DISTINCT FROM (OLD.cust_po_no, OLD.order_date, OLD.remark)
                    AND EXISTS (SELECT 1 FROM sal_order_line l WHERE l.order_id = NEW.order_id AND l.status = 'OPEN')) THEN
                RAISE EXCEPTION 'order % is closed', OLD.order_no USING ERRCODE = '55000', CONSTRAINT = 'ck_sal_order_closed';
            END IF;
        END IF;
        RETURN NEW;
    END IF;
    -- 새 수주는 같은 회사의 사용 중 판매 거래처만(FK 는 회사를 보지 않는다)
    IF NOT EXISTS (SELECT 1 FROM mst_cust c WHERE c.cust_id = NEW.cust_id AND c.comp_cd = NEW.comp_cd
                      AND c.use_yn = 'Y' AND c.cust_type IN ('SALES', 'BOTH')) THEN
        RAISE EXCEPTION 'customer % is not an active sales customer of company %', NEW.cust_id, NEW.comp_cd
            USING ERRCODE = '23503', CONSTRAINT = 'fk_sal_order_cust_comp';
    END IF;
    IF NEW.status <> 'OPEN' THEN
        RAISE EXCEPTION 'a new order starts open' USING ERRCODE = '23514', CONSTRAINT = 'ck_sal_order_status_new';
    END IF;
    RETURN NEW;
END
$$;

-- 품목 기본 단위 잠금: V091 정의 + 수주 행(수주·출하 수량 = 기본 단위)
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
        OR EXISTS (SELECT 1 FROM sal_order_line s WHERE s.comp_cd = OLD.comp_cd AND s.item_id = OLD.item_id)) THEN
        RAISE EXCEPTION 'unit of item % is in use (BOM, unit conversion, work order, stock ledger or sales order)', OLD.item_id
            USING ERRCODE = '23503', CONSTRAINT = 'fk_mst_item_unit_in_use';
    END IF;
    RETURN NEW;
END
$$;
CREATE INDEX IF NOT EXISTS ix_sal_order_line_item ON sal_order_line (comp_cd, item_id);

INSERT INTO sys_policy_def (policy_key, value_type, default_value, allowed_values, min_value, max_value, plant_scope_yn, desc_key) VALUES
    ('SAL_OVERSHIP_PCT', 'INT', '0', NULL, 0, 100, 'N', 'policy.SAL_OVERSHIP_PCT');
