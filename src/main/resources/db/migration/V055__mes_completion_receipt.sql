-- =====================================================================
-- 공정 완료판정·지시 완료·마감·생산 입고·재작업/수리 처리 (P2 7a — docs/25 4.9)
-- 임시 번호(MES 대역, docs/29 2.2) — 병합 때 상위 세션이 다음 빈 번호로 이름만 바꾼다
--
-- - hb_proc_qty: 공정 하나의 실적 순합(G·S·RW·RP) + 재작업·수리 처리 합 — 판정·처리·입고·정정 검사가 같은 계산을 쓴다
-- - prd_wo_proc_close: 완료판정 원장(보존식 결과). 공정 READY → DONE 은 같은 트랜잭션의 판정 행이 있을 때만
-- - prd_rework: 재작업·수리 대기 수량의 처리(양품 = 새 산출 LOT, 폐기). 공정 행 잠금 뒤 대기 확인
-- - prd_receipt: 생산 입고 원장(마지막 공정 양품 LOT → WH·LOC). 재고는 세지 않는다 — WMS 2부가 첫 소스로 이관(docs/30 D2)
-- - 지시 완료 = 모든 공정 DONE, 완료된 공정의 실적 정정 거부, 처리된 재작업을 대기 아래로 되돌리는 정정 거부
-- =====================================================================

INSERT INTO sys_policy_def (policy_key, value_type, default_value, allowed_values, min_value, max_value, plant_scope_yn, desc_key) VALUES
    ('PROC_BALANCE_TOL_PCT', 'INT', '0', NULL, 0, 20, 'Y', 'policy.PROC_BALANCE_TOL_PCT');

-- ---------------------------------------------------------------------
-- 1. 공정 수량 — 실적 순합 + 처리 합 (처리 표를 먼저 만들어야 함수가 컴파일된다)
-- ---------------------------------------------------------------------
CREATE TABLE prd_rework (
    rework_id       bigint         GENERATED ALWAYS AS IDENTITY,
    comp_cd         varchar(20)    NOT NULL,
    wo_id           bigint         NOT NULL,
    wo_proc_id      bigint         NOT NULL,
    kind            varchar(10)    NOT NULL,
    qty             numeric(18,6)  NOT NULL,
    good_qty        numeric(18,6)  NOT NULL,
    scrap_qty       numeric(18,6)  NOT NULL,
    unit_cd         varchar(30)    NOT NULL,
    lot_id          bigint,
    reason          varchar(500),
    client_req_id   varchar(64)    NOT NULL,
    occurred_at     timestamptz    NOT NULL,
    recorded_at     timestamptz    NOT NULL DEFAULT now(),
    entry_method    varchar(10)    NOT NULL,
    recorded_by     varchar(50)    NOT NULL,
    performed_by    bigint,
    reversal_of_id  bigint,
    CONSTRAINT pk_prd_rework PRIMARY KEY (rework_id),
    CONSTRAINT fk_prd_rework_wo FOREIGN KEY (wo_id) REFERENCES prd_wo (wo_id),
    CONSTRAINT fk_prd_rework_proc FOREIGN KEY (wo_proc_id) REFERENCES prd_wo_proc (wo_proc_id),
    CONSTRAINT fk_prd_rework_lot FOREIGN KEY (lot_id) REFERENCES lot_master (lot_id),
    CONSTRAINT fk_prd_rework_worker FOREIGN KEY (performed_by) REFERENCES mst_worker (worker_id),
    CONSTRAINT uq_prd_rework_req UNIQUE (comp_cd, client_req_id),
    CONSTRAINT ck_prd_rework_req CHECK (client_req_id ~ '^[A-Za-z0-9_-]{8,64}$'),
    CONSTRAINT ck_prd_rework_kind CHECK (kind IN ('REWORK', 'REPAIR')),
    CONSTRAINT ck_prd_rework_qty CHECK (qty > 0 AND good_qty >= 0 AND scrap_qty >= 0 AND good_qty + scrap_qty = qty),
    CONSTRAINT ck_prd_rework_lot CHECK ((good_qty > 0) = (lot_id IS NOT NULL)),
    CONSTRAINT ck_prd_rework_method CHECK (entry_method IN ('KEY', 'SCAN')),
    -- 처리는 정정하지 않는다(D14 — 잘못 처리한 산출 LOT 은 보류) — 원장 칸 규약만
    CONSTRAINT ck_prd_rework_no_reversal CHECK (reversal_of_id IS NULL)
);
COMMENT ON TABLE prd_rework IS '재작업·수리 대기 처리 원장 (docs/25 4.9) — 대기 수량을 양품(새 산출 LOT)·폐기로 나눈다';
CREATE INDEX ix_prd_rework_proc ON prd_rework (comp_cd, wo_proc_id);

-- 공정 하나의 수량. 실적 = 역분개 포함 순합, 처리 = 종류별 합. RLS 그대로(호출자 회사 범위)
CREATE FUNCTION hb_proc_qty(p_proc bigint)
    RETURNS TABLE (good numeric, scrap numeric, rework numeric, repair numeric,
                   rework_done numeric, repair_done numeric, disp_good numeric, disp_scrap numeric)
    LANGUAGE sql STABLE
AS $$
    SELECT coalesce((SELECT sum(good_qty) FROM prd_result WHERE wo_proc_id = p_proc), 0),
           coalesce((SELECT sum(scrap_qty) FROM prd_result WHERE wo_proc_id = p_proc), 0),
           coalesce((SELECT sum(rework_qty) FROM prd_result WHERE wo_proc_id = p_proc), 0),
           coalesce((SELECT sum(repair_qty) FROM prd_result WHERE wo_proc_id = p_proc), 0),
           coalesce((SELECT sum(qty) FROM prd_rework WHERE wo_proc_id = p_proc AND kind = 'REWORK'), 0),
           coalesce((SELECT sum(qty) FROM prd_rework WHERE wo_proc_id = p_proc AND kind = 'REPAIR'), 0),
           coalesce((SELECT sum(good_qty) FROM prd_rework WHERE wo_proc_id = p_proc), 0),
           coalesce((SELECT sum(scrap_qty) FROM prd_rework WHERE wo_proc_id = p_proc), 0)
$$;

-- 처리: 같은 회사·같은 지시 공정, 공정 행을 잠근 뒤 대기 ≥ 수량, 완료(DONE)된 공정 아님, 단위 = 지시 단위,
-- 산출 LOT 은 그 지시의 생산 LOT
CREATE FUNCTION hb_guard_prd_rework() RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    v_proc prd_wo_proc;
    v_wo   prd_wo;
    v_q    record;
    v_wait numeric;
BEGIN
    SELECT * INTO v_proc FROM prd_wo_proc WHERE wo_proc_id = NEW.wo_proc_id AND comp_cd = NEW.comp_cd AND wo_id = NEW.wo_id FOR UPDATE;
    SELECT * INTO v_wo FROM prd_wo WHERE wo_id = NEW.wo_id AND comp_cd = NEW.comp_cd;
    IF v_proc.wo_proc_id IS NULL OR v_wo.wo_id IS NULL
       OR (NEW.performed_by IS NOT NULL AND NOT EXISTS (SELECT 1 FROM mst_worker k WHERE k.worker_id = NEW.performed_by
                                                           AND k.comp_cd = NEW.comp_cd))
       OR (NEW.lot_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM lot_master l WHERE l.lot_id = NEW.lot_id AND l.comp_cd = NEW.comp_cd
                                                     AND l.wo_id = NEW.wo_id AND l.origin = 'PRODUCED')) THEN
        RAISE EXCEPTION 'rework context is not of company % or work order %', NEW.comp_cd, NEW.wo_id
            USING ERRCODE = '23503', CONSTRAINT = 'fk_prd_rework_ctx_comp';
    END IF;
    IF NEW.unit_cd <> v_wo.unit_cd THEN
        RAISE EXCEPTION 'rework quantity is in the work order unit %', v_wo.unit_cd
            USING ERRCODE = '23514', CONSTRAINT = 'ck_prd_rework_unit';
    END IF;
    IF v_proc.status = 'DONE' OR v_wo.status <> 'IN_PROGRESS' THEN
        RAISE EXCEPTION 'process % is closed', NEW.wo_proc_id USING ERRCODE = '55000', CONSTRAINT = 'ck_prd_rework_proc_open';
    END IF;
    SELECT * INTO v_q FROM hb_proc_qty(NEW.wo_proc_id);
    v_wait := CASE NEW.kind WHEN 'REWORK' THEN v_q.rework - v_q.rework_done ELSE v_q.repair - v_q.repair_done END;
    IF NEW.qty > v_wait THEN
        RAISE EXCEPTION 'only % % units are waiting on process %', v_wait, NEW.kind, NEW.wo_proc_id
            USING ERRCODE = '23514', CONSTRAINT = 'ck_prd_rework_pending';
    END IF;
    RETURN NEW;
END
$$;
CREATE TRIGGER tg_prd_rework_guard BEFORE INSERT ON prd_rework FOR EACH ROW EXECUTE FUNCTION hb_guard_prd_rework();
CREATE TRIGGER tg_prd_rework_ledger BEFORE UPDATE OR DELETE ON prd_rework FOR EACH ROW EXECUTE FUNCTION hb_forbid_update_delete();

-- ---------------------------------------------------------------------
-- 2. 완료판정 원장 + 공정 상태 READY → DONE
-- ---------------------------------------------------------------------
CREATE TABLE prd_wo_proc_close (
    close_id        bigint         GENERATED ALWAYS AS IDENTITY,
    comp_cd         varchar(20)    NOT NULL,
    wo_id           bigint         NOT NULL,
    wo_proc_id      bigint         NOT NULL,
    input_qty       numeric(18,6)  NOT NULL,
    good_qty        numeric(18,6)  NOT NULL,
    scrap_qty       numeric(18,6)  NOT NULL,
    rework_qty      numeric(18,6)  NOT NULL,
    repair_qty      numeric(18,6)  NOT NULL,
    eff_good_qty    numeric(18,6)  NOT NULL,
    diff_qty        numeric(18,6)  NOT NULL,
    tol_pct         integer        NOT NULL,
    first_op_yn     char(1)        NOT NULL,
    remark          varchar(500),
    occurred_at     timestamptz    NOT NULL,
    recorded_at     timestamptz    NOT NULL DEFAULT now(),
    entry_method    varchar(10)    NOT NULL,
    recorded_by     varchar(50)    NOT NULL,
    performed_by    bigint,
    reversal_of_id  bigint,
    CONSTRAINT pk_prd_wo_proc_close PRIMARY KEY (close_id),
    CONSTRAINT fk_prd_wo_proc_close_wo FOREIGN KEY (wo_id) REFERENCES prd_wo (wo_id),
    CONSTRAINT fk_prd_wo_proc_close_proc FOREIGN KEY (wo_proc_id) REFERENCES prd_wo_proc (wo_proc_id),
    CONSTRAINT uq_prd_wo_proc_close UNIQUE (comp_cd, wo_proc_id),
    CONSTRAINT ck_prd_wo_proc_close_first CHECK (first_op_yn IN ('Y', 'N')),
    CONSTRAINT ck_prd_wo_proc_close_tol CHECK (tol_pct BETWEEN 0 AND 20),
    CONSTRAINT ck_prd_wo_proc_close_method CHECK (entry_method = 'KEY'),
    -- 완료는 되돌리지 않는다(발행과 같음) — 원장 칸 규약만
    CONSTRAINT ck_prd_wo_proc_close_no_reversal CHECK (reversal_of_id IS NULL AND performed_by IS NULL)
);
COMMENT ON TABLE prd_wo_proc_close IS '공정 완료판정 원장 (docs/25 4.9) — 보존식 투입 = 양품 + 폐기 + 재작업 + 수리(허용 오차 %), 공정당 한 번';

-- 판정 행: 같은 회사·지시, 공정 READY, 앞 공정 DONE, 대기 0, 처리 합 > 0, 칸 값 = 실제 계산, 보존식(첫 공정 제외)
CREATE FUNCTION hb_guard_proc_close() RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    v_proc prd_wo_proc;
    v_prev prd_wo_proc;
    v_q    record;
    v_p    record;
    v_in   numeric;
    v_out  numeric;
BEGIN
    SELECT * INTO v_proc FROM prd_wo_proc WHERE wo_proc_id = NEW.wo_proc_id AND comp_cd = NEW.comp_cd AND wo_id = NEW.wo_id FOR UPDATE;
    IF v_proc.wo_proc_id IS NULL THEN
        RAISE EXCEPTION 'close context is not of company % or work order %', NEW.comp_cd, NEW.wo_id
            USING ERRCODE = '23503', CONSTRAINT = 'fk_prd_wo_proc_close_ctx_comp';
    END IF;
    IF v_proc.status <> 'READY' OR NOT EXISTS (SELECT 1 FROM prd_wo w WHERE w.wo_id = NEW.wo_id AND w.status = 'IN_PROGRESS') THEN
        RAISE EXCEPTION 'process % is not ready to close (%)', NEW.wo_proc_id, v_proc.status
            USING ERRCODE = '55000', CONSTRAINT = 'ck_prd_wo_proc_close_state';
    END IF;
    SELECT * INTO v_prev FROM prd_wo_proc WHERE wo_id = NEW.wo_id AND comp_cd = NEW.comp_cd AND op_seq < v_proc.op_seq
     ORDER BY op_seq DESC LIMIT 1;
    IF v_prev.wo_proc_id IS NOT NULL AND v_prev.status <> 'DONE' THEN
        RAISE EXCEPTION 'previous process % is not closed', v_prev.wo_proc_id
            USING ERRCODE = '55000', CONSTRAINT = 'ck_prd_wo_proc_close_prev';
    END IF;
    SELECT * INTO v_q FROM hb_proc_qty(NEW.wo_proc_id);
    IF v_q.rework <> v_q.rework_done OR v_q.repair <> v_q.repair_done THEN
        RAISE EXCEPTION 'process % has rework or repair waiting', NEW.wo_proc_id
            USING ERRCODE = '23514', CONSTRAINT = 'ck_prd_wo_proc_close_pending';
    END IF;
    v_out := v_q.good + v_q.scrap + v_q.rework + v_q.repair;
    IF v_out <= 0 THEN
        RAISE EXCEPTION 'process % has no results', NEW.wo_proc_id USING ERRCODE = '23514', CONSTRAINT = 'ck_prd_wo_proc_close_empty';
    END IF;
    IF v_prev.wo_proc_id IS NULL THEN
        v_in := v_out;
    ELSE
        SELECT * INTO v_p FROM hb_proc_qty(v_prev.wo_proc_id);
        v_in := v_p.good + v_p.disp_good;
    END IF;
    IF (NEW.input_qty, NEW.good_qty, NEW.scrap_qty, NEW.rework_qty, NEW.repair_qty, NEW.eff_good_qty, NEW.diff_qty, NEW.first_op_yn)
       IS DISTINCT FROM (v_in, v_q.good, v_q.scrap, v_q.rework, v_q.repair, v_q.good + v_q.disp_good, v_out - v_in,
                         CASE WHEN v_prev.wo_proc_id IS NULL THEN 'Y' ELSE 'N' END) THEN
        RAISE EXCEPTION 'close record of process % does not match its results', NEW.wo_proc_id
            USING ERRCODE = '23514', CONSTRAINT = 'ck_prd_wo_proc_close_values';
    END IF;
    IF abs(v_out - v_in) * 100 > v_in * NEW.tol_pct THEN
        RAISE EXCEPTION 'process % does not balance: input %, output %', NEW.wo_proc_id, v_in, v_out
            USING ERRCODE = '23514', CONSTRAINT = 'ck_prd_wo_proc_close_balance';
    END IF;
    RETURN NEW;
END
$$;
CREATE TRIGGER tg_prd_wo_proc_close_guard BEFORE INSERT ON prd_wo_proc_close FOR EACH ROW EXECUTE FUNCTION hb_guard_proc_close();
CREATE TRIGGER tg_prd_wo_proc_close_ledger BEFORE UPDATE OR DELETE ON prd_wo_proc_close FOR EACH ROW EXECUTE FUNCTION hb_forbid_update_delete();

-- 공정 상태 — 5b 의 전이 표에 READY → DONE 을 연다(같은 트랜잭션의 판정 행 필요). DONE 은 끝
CREATE OR REPLACE FUNCTION hb_guard_wo_proc_status() RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    v_event varchar(10);
BEGIN
    IF NEW.status = OLD.status THEN
        RETURN NEW;
    END IF;
    IF OLD.status = 'READY' AND NEW.status = 'DONE' THEN
        IF NOT EXISTS (SELECT 1 FROM prd_wo_proc_close c WHERE c.wo_proc_id = NEW.wo_proc_id AND c.comp_cd = NEW.comp_cd
                          AND c.recorded_at = now()) THEN
            RAISE EXCEPTION 'process closes only with a close record in the same transaction'
                USING ERRCODE = '55000', CONSTRAINT = 'ck_prd_wo_proc_close_required';
        END IF;
        RETURN NEW;
    END IF;
    v_event := CASE
        WHEN OLD.status IN ('WAIT', 'READY') AND NEW.status = 'RUN' THEN 'START'
        WHEN OLD.status = 'RUN' AND NEW.status = 'PAUSE' THEN 'PAUSE'
        WHEN OLD.status = 'PAUSE' AND NEW.status = 'RUN' THEN 'RESUME'
        WHEN OLD.status IN ('RUN', 'PAUSE') AND NEW.status = 'READY' THEN 'END'
    END;
    IF v_event IS NULL THEN
        RAISE EXCEPTION 'process cannot go from % to %', OLD.status, NEW.status
            USING ERRCODE = '55000', CONSTRAINT = 'ck_prd_wo_proc_transition';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM prd_wo_proc_event e WHERE e.wo_proc_id = NEW.wo_proc_id AND e.comp_cd = NEW.comp_cd
                      AND e.event_cd = v_event AND e.recorded_at = now()) THEN
        RAISE EXCEPTION 'process status changes only with a % event in the same transaction', v_event
            USING ERRCODE = '55000', CONSTRAINT = 'ck_prd_wo_proc_event_required';
    END IF;
    RETURN NEW;
END
$$;

-- 지시 완료 = 모든 공정 DONE. 트리거 이름은 tg_prd_wo_guard(전이 표) 뒤에 돌도록(BEFORE 트리거는 이름순)
CREATE FUNCTION hb_guard_wo_done() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.status = 'DONE' AND OLD.status <> 'DONE'
       AND EXISTS (SELECT 1 FROM prd_wo_proc p WHERE p.wo_id = NEW.wo_id AND p.comp_cd = NEW.comp_cd AND p.status <> 'DONE') THEN
        RAISE EXCEPTION 'work order % has open processes', NEW.wo_id USING ERRCODE = '55000', CONSTRAINT = 'ck_prd_wo_procs_done';
    END IF;
    RETURN NEW;
END
$$;
CREATE TRIGGER tg_prd_wo_status_done BEFORE UPDATE OF status ON prd_wo FOR EACH ROW EXECUTE FUNCTION hb_guard_wo_done();

-- 실적 정정(역분개): 완료된 공정은 거부, 처리된 재작업·수리를 대기 아래로 만들면 거부(공정 행 잠금 — 처리와 줄 세움)
CREATE FUNCTION hb_guard_result_after_close() RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    v_proc prd_wo_proc;
    v_q    record;
BEGIN
    IF NEW.reversal_of_id IS NULL THEN
        RETURN NEW;
    END IF;
    SELECT * INTO v_proc FROM prd_wo_proc WHERE wo_proc_id = NEW.wo_proc_id AND comp_cd = NEW.comp_cd FOR UPDATE;
    IF v_proc.status = 'DONE' THEN
        RAISE EXCEPTION 'process % is closed — results cannot be corrected', NEW.wo_proc_id
            USING ERRCODE = '55000', CONSTRAINT = 'ck_prd_result_proc_done';
    END IF;
    SELECT * INTO v_q FROM hb_proc_qty(NEW.wo_proc_id);
    IF v_q.rework + NEW.rework_qty < v_q.rework_done OR v_q.repair + NEW.repair_qty < v_q.repair_done THEN
        RAISE EXCEPTION 'correction would undo rework or repair already dispositioned on process %', NEW.wo_proc_id
            USING ERRCODE = '23514', CONSTRAINT = 'ck_prd_result_rework_disposed';
    END IF;
    RETURN NEW;
END
$$;
CREATE TRIGGER tg_prd_result_reversal_guard BEFORE INSERT ON prd_result FOR EACH ROW EXECUTE FUNCTION hb_guard_result_after_close();

-- ---------------------------------------------------------------------
-- 3. 생산 입고 원장 (WMS 2부가 inv_trx 로 이관할 칸: 품목·LOT·공장·노드·수량·단위·발생 시각)
-- ---------------------------------------------------------------------
CREATE TABLE prd_receipt (
    receipt_id      bigint         GENERATED ALWAYS AS IDENTITY,
    comp_cd         varchar(20)    NOT NULL,
    plant_cd        varchar(20)    NOT NULL,
    wo_id           bigint         NOT NULL,
    item_id         bigint         NOT NULL,
    lot_id          bigint         NOT NULL,
    node_id         bigint         NOT NULL,
    qty             numeric(18,6)  NOT NULL,
    unit_cd         varchar(30)    NOT NULL,
    client_req_id   varchar(64),
    reason          varchar(500),
    occurred_at     timestamptz    NOT NULL,
    recorded_at     timestamptz    NOT NULL DEFAULT now(),
    entry_method    varchar(10)    NOT NULL,
    recorded_by     varchar(50)    NOT NULL,
    performed_by    bigint,
    reversal_of_id  bigint,
    CONSTRAINT pk_prd_receipt PRIMARY KEY (receipt_id),
    CONSTRAINT fk_prd_receipt_plant FOREIGN KEY (comp_cd, plant_cd) REFERENCES sys_plant (comp_cd, plant_cd),
    CONSTRAINT fk_prd_receipt_wo FOREIGN KEY (wo_id) REFERENCES prd_wo (wo_id),
    CONSTRAINT fk_prd_receipt_item FOREIGN KEY (item_id) REFERENCES mst_item (item_id),
    CONSTRAINT fk_prd_receipt_lot FOREIGN KEY (lot_id) REFERENCES lot_master (lot_id),
    CONSTRAINT fk_prd_receipt_node FOREIGN KEY (node_id) REFERENCES mst_equip_hier (node_id),
    CONSTRAINT fk_prd_receipt_worker FOREIGN KEY (performed_by) REFERENCES mst_worker (worker_id),
    CONSTRAINT fk_prd_receipt_reversal FOREIGN KEY (reversal_of_id) REFERENCES prd_receipt (receipt_id),
    CONSTRAINT uq_prd_receipt_req UNIQUE (comp_cd, client_req_id),
    CONSTRAINT ck_prd_receipt_req CHECK (client_req_id IS NULL OR client_req_id ~ '^[A-Za-z0-9_-]{8,64}$'),
    CONSTRAINT ck_prd_receipt_method CHECK (entry_method IN ('KEY', 'SCAN', 'CORRECT')),
    CONSTRAINT ck_prd_receipt_qty CHECK (
        (reversal_of_id IS NULL AND entry_method <> 'CORRECT' AND qty > 0 AND client_req_id IS NOT NULL)
        OR (reversal_of_id IS NOT NULL AND entry_method = 'CORRECT' AND qty < 0 AND coalesce(length(btrim(reason)), 0) > 0))
);
COMMENT ON TABLE prd_receipt IS '생산 입고 원장 (docs/25 4.9, D2) — 마지막 공정 양품 LOT → 창고·로케이션. 재고 수량은 P4 WMS 가 이관해 센다';
CREATE UNIQUE INDEX uq_prd_receipt_reversal ON prd_receipt (reversal_of_id) WHERE reversal_of_id IS NOT NULL;
CREATE INDEX ix_prd_receipt_wo ON prd_receipt (comp_cd, wo_id, lot_id);
CREATE INDEX ix_prd_receipt_time ON prd_receipt (comp_cd, occurred_at);

-- 입고: 같은 회사 문맥, 지시 진행·완료(마감 전), LOT = 그 지시의 마지막 공정 산출(실적·재작업 처리), 보류 아님,
-- 위치 = 같은 공장 사용 중 WH·LOC, 단위·품목 = 지시, LOT 행 잠금 뒤 순입고 ≤ 만든 양. 역분개 = 거울
CREATE FUNCTION hb_guard_prd_receipt() RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    v_wo   prd_wo;
    v_lot  lot_master;
    v_last bigint;
    v_orig prd_receipt;
    v_sum  numeric;
BEGIN
    SELECT * INTO v_wo FROM prd_wo WHERE wo_id = NEW.wo_id AND comp_cd = NEW.comp_cd;
    SELECT * INTO v_lot FROM lot_master WHERE lot_id = NEW.lot_id AND comp_cd = NEW.comp_cd FOR UPDATE;
    IF v_wo.wo_id IS NULL OR v_lot.lot_id IS NULL OR v_lot.wo_id IS DISTINCT FROM NEW.wo_id OR v_lot.origin <> 'PRODUCED'
       OR NOT EXISTS (SELECT 1 FROM mst_equip_hier h WHERE h.node_id = NEW.node_id AND h.comp_cd = NEW.comp_cd)
       OR (NEW.performed_by IS NOT NULL AND NOT EXISTS (SELECT 1 FROM mst_worker k WHERE k.worker_id = NEW.performed_by
                                                           AND k.comp_cd = NEW.comp_cd)) THEN
        RAISE EXCEPTION 'receipt context is not of company % or work order %', NEW.comp_cd, NEW.wo_id
            USING ERRCODE = '23503', CONSTRAINT = 'fk_prd_receipt_ctx_comp';
    END IF;
    IF (NEW.plant_cd, NEW.item_id, NEW.unit_cd) IS DISTINCT FROM (v_wo.plant_cd, v_wo.item_id, v_wo.unit_cd) THEN
        RAISE EXCEPTION 'receipt plant, item and unit follow the work order'
            USING ERRCODE = '23514', CONSTRAINT = 'ck_prd_receipt_wo_match';
    END IF;
    IF v_wo.status NOT IN ('IN_PROGRESS', 'DONE') THEN
        RAISE EXCEPTION 'work order % is %', NEW.wo_id, v_wo.status USING ERRCODE = '55000', CONSTRAINT = 'ck_prd_receipt_wo_state';
    END IF;
    IF NEW.reversal_of_id IS NULL THEN
        SELECT p.wo_proc_id INTO v_last FROM prd_wo_proc p WHERE p.wo_id = NEW.wo_id AND p.comp_cd = NEW.comp_cd
         ORDER BY p.op_seq DESC LIMIT 1;
        IF NOT EXISTS (SELECT 1 FROM prd_result r WHERE r.wo_proc_id = v_last AND r.lot_id = NEW.lot_id)
           AND NOT EXISTS (SELECT 1 FROM prd_rework w WHERE w.wo_proc_id = v_last AND w.lot_id = NEW.lot_id) THEN
            RAISE EXCEPTION 'lot % is not an output of the last process', NEW.lot_id
                USING ERRCODE = '23514', CONSTRAINT = 'ck_prd_receipt_last_op';
        END IF;
        IF v_lot.hold_yn = 'Y' THEN
            RAISE EXCEPTION 'lot % is on hold', NEW.lot_id USING ERRCODE = '23514', CONSTRAINT = 'ck_prd_receipt_hold';
        END IF;
        IF NOT EXISTS (SELECT 1 FROM mst_equip_hier h WHERE h.node_id = NEW.node_id AND h.plant_cd = NEW.plant_cd
                          AND h.node_type IN ('WH', 'LOC') AND h.use_yn = 'Y') THEN
            RAISE EXCEPTION 'node % is not an active warehouse location of plant %', NEW.node_id, NEW.plant_cd
                USING ERRCODE = '23514', CONSTRAINT = 'ck_prd_receipt_node';
        END IF;
        SELECT coalesce(sum(qty), 0) INTO v_sum FROM prd_receipt WHERE lot_id = NEW.lot_id AND comp_cd = NEW.comp_cd;
        IF v_sum + NEW.qty > v_lot.qty_created THEN
            RAISE EXCEPTION 'lot % receipts % exceed its quantity %', NEW.lot_id, v_sum + NEW.qty, v_lot.qty_created
                USING ERRCODE = '23514', CONSTRAINT = 'ck_prd_receipt_over';
        END IF;
        RETURN NEW;
    END IF;
    SELECT * INTO v_orig FROM prd_receipt WHERE receipt_id = NEW.reversal_of_id AND comp_cd = NEW.comp_cd;
    IF v_orig.receipt_id IS NULL OR v_orig.reversal_of_id IS NOT NULL
       OR (v_orig.wo_id, v_orig.lot_id, v_orig.node_id, v_orig.qty) IS DISTINCT FROM (NEW.wo_id, NEW.lot_id, NEW.node_id, -NEW.qty) THEN
        RAISE EXCEPTION 'reversal must mirror receipt %', NEW.reversal_of_id
            USING ERRCODE = '23514', CONSTRAINT = 'ck_prd_receipt_reversal';
    END IF;
    RETURN NEW;
END
$$;
CREATE TRIGGER tg_prd_receipt_guard BEFORE INSERT ON prd_receipt FOR EACH ROW EXECUTE FUNCTION hb_guard_prd_receipt();
CREATE TRIGGER tg_prd_receipt_ledger BEFORE UPDATE OR DELETE ON prd_receipt FOR EACH ROW EXECUTE FUNCTION hb_forbid_update_delete();

-- ---------------------------------------------------------------------
-- 4. RLS · 권한 · 화면 · 보존
-- ---------------------------------------------------------------------
ALTER TABLE prd_rework ENABLE ROW LEVEL SECURITY;
ALTER TABLE prd_rework FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON prd_rework USING (comp_cd = hb_current_comp()) WITH CHECK (comp_cd = hb_current_comp());
ALTER TABLE prd_wo_proc_close ENABLE ROW LEVEL SECURITY;
ALTER TABLE prd_wo_proc_close FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON prd_wo_proc_close USING (comp_cd = hb_current_comp()) WITH CHECK (comp_cd = hb_current_comp());
ALTER TABLE prd_receipt ENABLE ROW LEVEL SECURITY;
ALTER TABLE prd_receipt FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON prd_receipt USING (comp_cd = hb_current_comp()) WITH CHECK (comp_cd = hb_current_comp());

REVOKE UPDATE, DELETE, TRUNCATE ON prd_rework, prd_wo_proc_close, prd_receipt FROM ${app_role};

INSERT INTO sys_prog (prog_cd, prog_nm_key, view_path, module_cd, actions, sort_no) VALUES
    ('prd-receipt', 'prog.prdReceipt', 'production/receipt-mgt', 'PRD', ARRAY['SEARCH', 'SAVE']::varchar(20)[], 450);
INSERT INTO sys_retention_table (table_nm, class_cd) VALUES
    ('prd_rework', 'PRODUCTION_RECORD'), ('prd_wo_proc_close', 'PRODUCTION_RECORD'), ('prd_receipt', 'PRODUCTION_RECORD');
