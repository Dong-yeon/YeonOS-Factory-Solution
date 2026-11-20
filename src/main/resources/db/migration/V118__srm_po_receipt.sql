-- =====================================================================
-- SRM 2부 R1 구매 입고 게이트 (docs/31 4.9, WMS docs/30 12.2 D-B3) — 임시 번호, 병합 때 재번호
--
-- - srm_po_line.recv_qty: 입고 수량 투영(발주 단위). 원본은 WMS 원장(po_line_id 합), 게이트(PoReceiptGate)만 바꾼다
-- - 발주 상태 PARTIAL_RECEIVED·RECEIVED: ISSUED·ACKED → 입고 상태 → (되돌림이 0 이면) ISSUED·ACKED, 입고 상태 → CLOSED.
--   입고가 있는 발주는 개정·취소 불가(D6 "취소는 입고 0 일 때만")
-- - 입고 상태로 들어가고 나오는 전이는 행 입고 수량과 맞아야 한다(ck_srm_po_recv_status) — 직접 SQL 로 상태만 못 바꾼다
-- - 열린 수량 = 수량 − 취소 − 입고 (회신 행 초과 검사 ck_srm_po_ack_line_over 포함)
-- - 정책 SRM_RECV_OVER_PCT: 열린 수량을 넘겨 받을 수 있는 비율(%, 기본 0)
-- =====================================================================

INSERT INTO sys_policy_def (policy_key, value_type, default_value, allowed_values, min_value, max_value, desc_key) VALUES
    ('SRM_RECV_OVER_PCT', 'INT', '0', NULL, 0, 100, 'policy.SRM_RECV_OVER_PCT');

INSERT INTO sys_code (grp_cd, code, code_nm_key, sort_no) VALUES
    ('PO_STATUS', 'PARTIAL_RECEIVED', 'code.PO_STATUS.PARTIAL_RECEIVED', 8),
    ('PO_STATUS', 'RECEIVED',         'code.PO_STATUS.RECEIVED',         9);

ALTER TABLE srm_po DROP CONSTRAINT ck_srm_po_status;
ALTER TABLE srm_po ALTER COLUMN status TYPE varchar(20);    -- PARTIAL_RECEIVED(16자)
ALTER TABLE srm_po ADD CONSTRAINT ck_srm_po_status
    CHECK (status IN ('DRAFT', 'IN_APPROVAL', 'APPROVED', 'ISSUED', 'ACKED', 'PARTIAL_RECEIVED', 'RECEIVED', 'CLOSED', 'CANCELLED'));

ALTER TABLE srm_po_line ADD COLUMN recv_qty numeric(18,6) NOT NULL DEFAULT 0;
ALTER TABLE srm_po_line ADD CONSTRAINT ck_srm_po_line_recv CHECK (recv_qty >= 0);
COMMENT ON COLUMN srm_po_line.recv_qty IS '입고 수량(발주 단위) — WMS 원장 RCV_PO 합의 투영, PoReceiptGate 만 바꾼다(되돌림은 음수 호출)';
COMMENT ON TABLE srm_po_line IS '발주 행 — 품명·협력사 품번·단위·단가는 저장 때 스냅샷. 금액 = round(수량 × 단가, 통화 자릿수). 열린 수량 = 수량 − 취소 − 입고';

-- ---------------------------------------------------------------------
-- 1. 발주 전이 표 — V062 본문 그대로 + (R1) 표시한 곳만 다르다
-- ---------------------------------------------------------------------
CREATE OR REPLACE FUNCTION hb_guard_srm_po() RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    v_any  boolean;
    v_full boolean;
    v_ok   boolean;
BEGIN
    IF TG_OP = 'INSERT' THEN
        IF NEW.status <> 'DRAFT' OR NEW.rev <> 0 THEN
            RAISE EXCEPTION 'new purchase order must be DRAFT rev 0' USING ERRCODE = '55000', CONSTRAINT = 'ck_srm_po_transition';
        END IF;
        IF NOT hb_srm_supplier_of_comp(NEW.comp_cd, NEW.cust_id) THEN
            RAISE EXCEPTION 'supplier % is not of company %', NEW.cust_id, NEW.comp_cd
                USING ERRCODE = '23503', CONSTRAINT = 'fk_srm_po_supplier_comp';
        END IF;
        IF EXISTS (SELECT 1 FROM srm_supplier s WHERE s.cust_id = NEW.cust_id AND s.trade_status = 'BLOCKED') THEN
            RAISE EXCEPTION 'supplier % is blocked', NEW.cust_id USING ERRCODE = '55000', CONSTRAINT = 'ck_srm_po_supplier_blocked';
        END IF;
        IF NOT hb_code_usable(NEW.comp_cd, 'CURRENCY', NEW.currency_cd) THEN
            RAISE EXCEPTION 'currency % is not usable', NEW.currency_cd USING ERRCODE = '23503', CONSTRAINT = 'fk_srm_po_currency';
        END IF;
    ELSE
        IF NEW.po_no <> OLD.po_no OR NEW.po_date <> OLD.po_date OR NEW.cust_id <> OLD.cust_id OR NEW.currency_cd <> OLD.currency_cd
           OR NEW.comp_cd <> OLD.comp_cd OR NEW.source <> OLD.source THEN
            RAISE EXCEPTION 'purchase order number, date, supplier, currency and source are fixed'
                USING ERRCODE = '55000', CONSTRAINT = 'ck_srm_po_fixed';
        END IF;
        IF OLD.status <> 'DRAFT' AND (NEW.plant_cd IS DISTINCT FROM OLD.plant_cd OR NEW.wh_id IS DISTINCT FROM OLD.wh_id
                OR NEW.pay_terms_cd IS DISTINCT FROM OLD.pay_terms_cd OR NEW.remark IS DISTINCT FROM OLD.remark
                OR NEW.total_amt IS DISTINCT FROM OLD.total_amt OR NEW.buyer_user_id IS DISTINCT FROM OLD.buyer_user_id) THEN
            RAISE EXCEPTION '% purchase order cannot be edited', OLD.status USING ERRCODE = '55000', CONSTRAINT = 'ck_srm_po_draft';
        END IF;
        IF NEW.status <> OLD.status AND NOT (
               (OLD.status = 'DRAFT'       AND NEW.status IN ('IN_APPROVAL', 'APPROVED', 'CANCELLED'))
            OR (OLD.status = 'IN_APPROVAL' AND NEW.status IN ('APPROVED', 'DRAFT'))
            OR (OLD.status = 'APPROVED'    AND NEW.status IN ('ISSUED', 'DRAFT', 'CANCELLED'))
            OR (OLD.status = 'ISSUED'      AND NEW.status IN ('ACKED', 'DRAFT', 'CLOSED', 'CANCELLED', 'PARTIAL_RECEIVED', 'RECEIVED'))
            OR (OLD.status = 'ACKED'       AND NEW.status IN ('ISSUED', 'DRAFT', 'CLOSED', 'CANCELLED', 'PARTIAL_RECEIVED', 'RECEIVED'))
            -- (R1) 입고 상태끼리, 되돌림이 0 이면 회신 상태로, 그리고 마감
            OR (OLD.status IN ('PARTIAL_RECEIVED', 'RECEIVED')
                AND NEW.status IN ('PARTIAL_RECEIVED', 'RECEIVED', 'ISSUED', 'ACKED', 'CLOSED'))) THEN
            RAISE EXCEPTION 'purchase order cannot go from % to %', OLD.status, NEW.status
                USING ERRCODE = '55000', CONSTRAINT = 'ck_srm_po_transition';
        END IF;
        -- (S5) 회신 상태는 회신으로만 — 같은 트랜잭션에서 지금 개정의 회신 묶음을 넣어야 한다
        IF NEW.status <> OLD.status AND OLD.status IN ('ISSUED', 'ACKED') AND NEW.status IN ('ISSUED', 'ACKED')
           AND NOT EXISTS (SELECT 1 FROM srm_po_ack a WHERE a.po_id = NEW.po_id AND a.rev = NEW.rev AND a.created_at = now()) THEN
            RAISE EXCEPTION 'acknowledgement status changes only with a new acknowledgement'
                USING ERRCODE = '55000', CONSTRAINT = 'ck_srm_po_ack_required';
        END IF;
        -- (R1) 입고 상태로 들고 나는 전이는 행 입고 수량과 맞아야 한다(마감 제외):
        --      열린 행 전부 다 받음 → RECEIVED, 하나라도 받음 → PARTIAL_RECEIVED, 하나도 없음 → ISSUED·ACKED
        IF NEW.status <> OLD.status AND NEW.status <> 'CLOSED'
           AND (OLD.status IN ('PARTIAL_RECEIVED', 'RECEIVED') OR NEW.status IN ('PARTIAL_RECEIVED', 'RECEIVED')) THEN
            SELECT coalesce(bool_or(l.recv_qty > 0), false),
                   coalesce(bool_and(l.recv_qty >= l.qty - l.cancel_qty) FILTER (WHERE l.line_status = 'OPEN' AND l.qty - l.cancel_qty > 0), false)
              INTO v_any, v_full
              FROM srm_po_line l WHERE l.po_id = NEW.po_id;
            -- IF 조건 안에 CASE … THEN 을 쓰면 PL/pgSQL 이 첫 THEN 에서 조건을 끊는다 — 변수로
            v_ok := CASE NEW.status WHEN 'RECEIVED' THEN v_any AND v_full
                                    WHEN 'PARTIAL_RECEIVED' THEN v_any AND NOT v_full
                                    ELSE NOT v_any END;
            IF NOT v_ok THEN
                RAISE EXCEPTION 'status % does not match the received quantities', NEW.status
                    USING ERRCODE = '55000', CONSTRAINT = 'ck_srm_po_recv_status';
            END IF;
        END IF;
        IF NEW.rev <> OLD.rev AND NOT (NEW.rev = OLD.rev + 1 AND OLD.status IN ('ISSUED', 'ACKED') AND NEW.status = 'DRAFT') THEN
            RAISE EXCEPTION 'revision changes only when an issued order goes back to draft'
                USING ERRCODE = '55000', CONSTRAINT = 'ck_srm_po_rev_step';
        END IF;
        IF OLD.status IN ('ISSUED', 'ACKED') AND NEW.status = 'DRAFT' AND NEW.rev <> OLD.rev + 1 THEN
            RAISE EXCEPTION 'revising an issued order must raise the revision'
                USING ERRCODE = '55000', CONSTRAINT = 'ck_srm_po_rev_step';
        END IF;
    END IF;
    IF NEW.buyer_user_id IS NOT NULL AND (TG_OP = 'INSERT' OR NEW.buyer_user_id IS DISTINCT FROM OLD.buyer_user_id)
       AND NOT EXISTS (SELECT 1 FROM sys_user u WHERE u.user_id = NEW.buyer_user_id AND u.comp_cd = NEW.comp_cd) THEN
        RAISE EXCEPTION 'user % is not a user of company %', NEW.buyer_user_id, NEW.comp_cd
            USING ERRCODE = '23503', CONSTRAINT = 'fk_srm_po_buyer_comp';
    END IF;
    IF NEW.wh_id IS NOT NULL AND (TG_OP = 'INSERT' OR NEW.wh_id IS DISTINCT FROM OLD.wh_id OR NEW.plant_cd IS DISTINCT FROM OLD.plant_cd)
       AND NOT EXISTS (SELECT 1 FROM mst_equip_hier h WHERE h.node_id = NEW.wh_id AND h.comp_cd = NEW.comp_cd
                          AND h.plant_cd = NEW.plant_cd AND h.node_type = 'WH') THEN
        RAISE EXCEPTION 'warehouse % is not a warehouse of plant %', NEW.wh_id, NEW.plant_cd
            USING ERRCODE = '23503', CONSTRAINT = 'fk_srm_po_wh_plant';
    END IF;
    IF NEW.pay_terms_cd IS NOT NULL AND (TG_OP = 'INSERT' OR NEW.pay_terms_cd IS DISTINCT FROM OLD.pay_terms_cd)
       AND NOT hb_code_usable(NEW.comp_cd, 'PAY_TERMS', NEW.pay_terms_cd) THEN
        RAISE EXCEPTION 'pay terms % is not usable', NEW.pay_terms_cd USING ERRCODE = '23503', CONSTRAINT = 'fk_srm_po_pay_terms';
    END IF;
    RETURN NEW;
END
$$;

-- ---------------------------------------------------------------------
-- 2. 발주 행 — V045 본문 그대로 + (R1) 입고 수량은 발행된 발주에서만, 마감된 발주는 되돌림(감소)만
-- ---------------------------------------------------------------------
CREATE OR REPLACE FUNCTION hb_guard_srm_po_line() RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    v_row  srm_po_line;
    v_head record;
BEGIN
    v_row := CASE WHEN TG_OP = 'DELETE' THEN OLD ELSE NEW END;
    SELECT p.status, p.comp_cd, p.currency_cd INTO v_head FROM srm_po p WHERE p.po_id = v_row.po_id;
    IF NOT FOUND OR v_head.comp_cd <> v_row.comp_cd THEN
        RAISE EXCEPTION 'purchase order % does not belong to company %', v_row.po_id, v_row.comp_cd
            USING ERRCODE = '23503', CONSTRAINT = 'fk_srm_po_line_po_comp';
    END IF;
    -- 작성 중이 아니면 행 상태·취소 수량(2부)·입고 수량(R1)만
    IF v_head.status <> 'DRAFT' THEN
        IF TG_OP <> 'UPDATE' OR (NEW.item_id, NEW.item_nm, NEW.unit_cd, NEW.qty, NEW.unit_price, NEW.amt, NEW.due_date, NEW.line_no, NEW.po_id)
                IS DISTINCT FROM (OLD.item_id, OLD.item_nm, OLD.unit_cd, OLD.qty, OLD.unit_price, OLD.amt, OLD.due_date, OLD.line_no, OLD.po_id)
                OR NEW.pr_line_id IS DISTINCT FROM OLD.pr_line_id OR NEW.supplier_item_cd IS DISTINCT FROM OLD.supplier_item_cd
                OR NEW.remark IS DISTINCT FROM OLD.remark THEN
            RAISE EXCEPTION 'lines of % purchase order % cannot be changed', v_head.status, v_row.po_id
                USING ERRCODE = '55000', CONSTRAINT = 'ck_srm_po_line_draft';
        END IF;
    END IF;
    -- (R1) 입고 수량: 처음부터 0, 발행된 발주에서만 바뀌고 마감된 발주는 줄기만(원장 역분개)
    IF TG_OP = 'INSERT' AND NEW.recv_qty <> 0 THEN
        RAISE EXCEPTION 'a new line has no receipt' USING ERRCODE = '55000', CONSTRAINT = 'ck_srm_po_line_recv_state';
    END IF;
    IF TG_OP = 'UPDATE' AND NEW.recv_qty <> OLD.recv_qty
       AND NOT (v_head.status IN ('ISSUED', 'ACKED', 'PARTIAL_RECEIVED', 'RECEIVED')
                OR v_head.status = 'CLOSED' AND NEW.recv_qty < OLD.recv_qty) THEN
        RAISE EXCEPTION 'receipt of % purchase order % cannot change', v_head.status, v_row.po_id
            USING ERRCODE = '55000', CONSTRAINT = 'ck_srm_po_line_recv_state';
    END IF;
    IF TG_OP = 'DELETE' THEN
        IF OLD.recv_qty <> 0 THEN
            RAISE EXCEPTION 'a received line cannot be deleted' USING ERRCODE = '55000', CONSTRAINT = 'ck_srm_po_line_recv_state';
        END IF;
        RETURN OLD;
    END IF;
    IF TG_OP = 'UPDATE' AND (NEW.po_id <> OLD.po_id OR NEW.line_no <> OLD.line_no) THEN
        RAISE EXCEPTION 'line cannot move' USING ERRCODE = '55000', CONSTRAINT = 'ck_srm_po_line_move';
    END IF;
    IF NOT hb_item_of_comp(NEW.comp_cd, NEW.item_id) THEN
        RAISE EXCEPTION 'item % does not belong to company %', NEW.item_id, NEW.comp_cd
            USING ERRCODE = '23503', CONSTRAINT = 'fk_srm_po_line_item_comp';
    END IF;
    IF (TG_OP = 'INSERT' OR NEW.unit_cd <> OLD.unit_cd) AND NOT hb_unit_usable(NEW.comp_cd, NEW.unit_cd) THEN
        RAISE EXCEPTION 'unit % is not available for company %', NEW.unit_cd, NEW.comp_cd
            USING ERRCODE = '23503', CONSTRAINT = 'fk_srm_po_line_unit';
    END IF;
    IF NEW.pr_line_id IS NOT NULL AND (TG_OP = 'INSERT' OR NEW.pr_line_id IS DISTINCT FROM OLD.pr_line_id)
       AND NOT EXISTS (SELECT 1 FROM srm_pr_line l WHERE l.pr_line_id = NEW.pr_line_id AND l.comp_cd = NEW.comp_cd
                          AND l.item_id = NEW.item_id) THEN
        RAISE EXCEPTION 'purchase request line % is not a line of company % for the same item', NEW.pr_line_id, NEW.comp_cd
            USING ERRCODE = '23503', CONSTRAINT = 'fk_srm_po_line_pr_comp';
    END IF;
    IF NEW.amt <> round(NEW.qty * NEW.unit_price, hb_currency_scale(v_head.currency_cd)) THEN
        RAISE EXCEPTION 'line amount % is not round(qty x price)', NEW.amt USING ERRCODE = '23514', CONSTRAINT = 'ck_srm_po_line_amt';
    END IF;
    RETURN NEW;
END
$$;

-- ---------------------------------------------------------------------
-- 3. 회신 행 — V062 본문 그대로 + (R1) 열린 수량에서 입고를 뺀다
-- ---------------------------------------------------------------------
CREATE OR REPLACE FUNCTION hb_guard_srm_po_ack_line() RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    v_ack  record;
    v_line record;
    v_sum  numeric;
BEGIN
    IF TG_OP <> 'INSERT' THEN
        RAISE EXCEPTION 'purchase order acknowledgement lines are append-only'
            USING ERRCODE = '55000', CONSTRAINT = 'ck_srm_po_ack_line_append_only';
    END IF;
    SELECT a.comp_cd, a.po_id, a.ack_status, a.created_at INTO v_ack FROM srm_po_ack a WHERE a.ack_id = NEW.ack_id;
    IF NOT FOUND OR v_ack.comp_cd <> NEW.comp_cd THEN
        RAISE EXCEPTION 'acknowledgement % does not belong to company %', NEW.ack_id, NEW.comp_cd
            USING ERRCODE = '23503', CONSTRAINT = 'fk_srm_po_ack_line_ack_comp';
    END IF;
    -- 묶음을 만든 트랜잭션에서만(now() = 트랜잭션 시작 시각) — 나중에 직접 SQL 로 약속을 덧붙이지 못한다
    IF v_ack.created_at <> now() THEN
        RAISE EXCEPTION 'lines can be added only with their acknowledgement' USING ERRCODE = '55000', CONSTRAINT = 'ck_srm_po_ack_line_same_tx';
    END IF;
    IF v_ack.ack_status = 'REJECTED' THEN
        RAISE EXCEPTION 'a rejection has no lines' USING ERRCODE = '55000', CONSTRAINT = 'ck_srm_po_ack_line_rejected';
    END IF;
    SELECT l.po_id, l.qty, l.cancel_qty, l.recv_qty, l.line_status, p.po_date INTO v_line
      FROM srm_po_line l JOIN srm_po p ON p.po_id = l.po_id WHERE l.po_line_id = NEW.po_line_id;
    IF NOT FOUND OR v_line.po_id <> v_ack.po_id THEN
        RAISE EXCEPTION 'line % is not a line of the acknowledged order', NEW.po_line_id
            USING ERRCODE = '23503', CONSTRAINT = 'fk_srm_po_ack_line_po_line_comp';
    END IF;
    IF v_line.line_status <> 'OPEN' THEN
        RAISE EXCEPTION 'line % is %', NEW.po_line_id, v_line.line_status USING ERRCODE = '55000', CONSTRAINT = 'ck_srm_po_ack_line_open';
    END IF;
    IF NEW.promised_date < v_line.po_date THEN
        RAISE EXCEPTION 'promised before the order date' USING ERRCODE = '23514', CONSTRAINT = 'ck_srm_po_ack_line_before_po';
    END IF;
    SELECT coalesce(sum(promised_qty), 0) INTO v_sum FROM srm_po_ack_line WHERE ack_id = NEW.ack_id AND po_line_id = NEW.po_line_id;
    IF v_sum + NEW.promised_qty > v_line.qty - v_line.cancel_qty - v_line.recv_qty THEN    -- (R1)
        RAISE EXCEPTION 'promised % exceeds the open quantity', v_sum + NEW.promised_qty
            USING ERRCODE = '23514', CONSTRAINT = 'ck_srm_po_ack_line_over';
    END IF;
    RETURN NEW;
END
$$;
