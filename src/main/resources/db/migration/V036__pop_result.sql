-- =====================================================================
-- V036 POP 착수·실적·투입 (P2 5b — docs/25 4.7)
--
-- 원장 4종(01 3.1): 공정 이벤트 prd_wo_proc_event, 실적 prd_result(+ 불량 내역 prd_result_defect), 파라미터 실측 prd_param_actual
-- - 모두 recorded_by = 단말(t{id}:{코드}), performed_by = 작업자, client_req_id 멱등(unique)
-- - 공정 상태는 전이 표대로, 그리고 같은 트랜잭션의 이벤트 행이 있을 때만 바뀐다(직접 SQL 로 상태만 바꾸기 거부)
-- - 실적 정정 = 역분개(수량 부호 반대 + 사유, 한 번만), 불량 내역 합 = 실적 수량(지연 제약 트리거)
-- =====================================================================

INSERT INTO sys_policy_def (policy_key, value_type, default_value, allowed_values, min_value, max_value, plant_scope_yn, desc_key) VALUES
    ('POP_FUTURE_TOLERANCE_SEC', 'INT', '300', NULL, 0, 3600, 'N', 'policy.POP_FUTURE_TOLERANCE_SEC');

-- 비가동(멈춤) 사유 — 6단계 cms_state_reason 으로 옮길 때까지. 회사가 코드를 더할 수 있다
INSERT INTO sys_code_grp (grp_cd, grp_nm_key, comp_ext_yn) VALUES ('DOWNTIME_REASON', 'code.grp.DOWNTIME_REASON', 'Y');
INSERT INTO sys_code (grp_cd, code, code_nm_key, sort_no) VALUES
    ('DOWNTIME_REASON', 'MAT_WAIT',   'code.DOWNTIME_REASON.MAT_WAIT', 1),
    ('DOWNTIME_REASON', 'EQUIP_FAIL', 'code.DOWNTIME_REASON.EQUIP_FAIL', 2),
    ('DOWNTIME_REASON', 'QUALITY',    'code.DOWNTIME_REASON.QUALITY', 3),
    ('DOWNTIME_REASON', 'SETUP',      'code.DOWNTIME_REASON.SETUP', 4),
    ('DOWNTIME_REASON', 'BREAK',      'code.DOWNTIME_REASON.BREAK', 5),
    ('DOWNTIME_REASON', 'OTHER',      'code.DOWNTIME_REASON.OTHER', 9);

INSERT INTO sys_code_grp (grp_cd, grp_nm_key, comp_ext_yn) VALUES ('PROC_EVENT', 'code.grp.PROC_EVENT', 'N');
INSERT INTO sys_code (grp_cd, code, code_nm_key, sort_no) VALUES
    ('PROC_EVENT', 'START',  'code.PROC_EVENT.START', 1),
    ('PROC_EVENT', 'PAUSE',  'code.PROC_EVENT.PAUSE', 2),
    ('PROC_EVENT', 'RESUME', 'code.PROC_EVENT.RESUME', 3),
    ('PROC_EVENT', 'END',    'code.PROC_EVENT.END', 4);

-- ---------------------------------------------------------------------
-- 1. 공정 이벤트 원장
-- ---------------------------------------------------------------------
CREATE TABLE prd_wo_proc_event (
    event_id        bigint        GENERATED ALWAYS AS IDENTITY,
    comp_cd         varchar(20)   NOT NULL,
    wo_id           bigint        NOT NULL,
    wo_proc_id      bigint        NOT NULL,
    event_cd        varchar(10)   NOT NULL,
    reason_cd       varchar(30),
    gate_mode       varchar(10),
    equip_id        bigint,
    terminal_id     bigint,
    work_day        date          NOT NULL,
    shift_cd        varchar(20),
    client_req_id   varchar(64)   NOT NULL,
    occurred_at     timestamptz   NOT NULL,
    recorded_at     timestamptz   NOT NULL DEFAULT now(),
    entry_method    varchar(10)   NOT NULL,
    recorded_by     varchar(50)   NOT NULL,
    performed_by    bigint        NOT NULL,
    reversal_of_id  bigint,
    CONSTRAINT pk_prd_wo_proc_event PRIMARY KEY (event_id),
    CONSTRAINT fk_prd_wo_proc_event_wo FOREIGN KEY (wo_id) REFERENCES prd_wo (wo_id),
    CONSTRAINT fk_prd_wo_proc_event_proc FOREIGN KEY (wo_proc_id) REFERENCES prd_wo_proc (wo_proc_id),
    CONSTRAINT fk_prd_wo_proc_event_equip FOREIGN KEY (equip_id) REFERENCES mst_equip (equip_id),
    CONSTRAINT fk_prd_wo_proc_event_terminal FOREIGN KEY (terminal_id) REFERENCES sys_terminal (terminal_id),
    CONSTRAINT fk_prd_wo_proc_event_worker FOREIGN KEY (performed_by) REFERENCES mst_worker (worker_id),
    CONSTRAINT uq_prd_wo_proc_event_req UNIQUE (comp_cd, client_req_id),
    CONSTRAINT ck_prd_wo_proc_event_cd CHECK (event_cd IN ('START', 'PAUSE', 'RESUME', 'END')),
    CONSTRAINT ck_prd_wo_proc_event_reason CHECK ((event_cd = 'PAUSE') = (reason_cd IS NOT NULL)),
    CONSTRAINT ck_prd_wo_proc_event_gate CHECK ((event_cd = 'START') = (gate_mode IS NOT NULL) AND (gate_mode IS NULL OR gate_mode IN ('OK', 'WARN'))),
    CONSTRAINT ck_prd_wo_proc_event_req CHECK (client_req_id ~ '^[A-Za-z0-9_-]{8,64}$'),
    CONSTRAINT ck_prd_wo_proc_event_method CHECK (entry_method IN ('KEY', 'SCAN')),
    -- 이벤트는 정정하지 않는다(다음 이벤트로 바로잡는다) — 원장 칸 규약만 지킨다
    CONSTRAINT ck_prd_wo_proc_event_no_reversal CHECK (reversal_of_id IS NULL)
);
COMMENT ON TABLE prd_wo_proc_event IS '공정 착수·멈춤·재개·종료 원장 (docs/25 4.7). recorded_by = 단말, performed_by = 작업자';
CREATE INDEX ix_prd_wo_proc_event_proc ON prd_wo_proc_event (comp_cd, wo_proc_id, occurred_at);

-- ---------------------------------------------------------------------
-- 2. 실적 원장 + 불량 내역
-- ---------------------------------------------------------------------
CREATE TABLE prd_result (
    result_id       bigint         GENERATED ALWAYS AS IDENTITY,
    comp_cd         varchar(20)    NOT NULL,
    wo_id           bigint         NOT NULL,
    wo_proc_id      bigint         NOT NULL,
    lot_id          bigint,
    equip_id        bigint,
    terminal_id     bigint,
    good_qty        numeric(18,6)  NOT NULL,
    scrap_qty       numeric(18,6)  NOT NULL,
    rework_qty      numeric(18,6)  NOT NULL,
    repair_qty      numeric(18,6)  NOT NULL,
    unit_cd         varchar(30)    NOT NULL,
    work_day        date           NOT NULL,
    shift_cd        varchar(20),
    client_req_id   varchar(64),
    reason          varchar(500),
    occurred_at     timestamptz    NOT NULL,
    recorded_at     timestamptz    NOT NULL DEFAULT now(),
    entry_method    varchar(10)    NOT NULL,
    recorded_by     varchar(50)    NOT NULL,
    performed_by    bigint,
    reversal_of_id  bigint,
    CONSTRAINT pk_prd_result PRIMARY KEY (result_id),
    CONSTRAINT fk_prd_result_wo FOREIGN KEY (wo_id) REFERENCES prd_wo (wo_id),
    CONSTRAINT fk_prd_result_proc FOREIGN KEY (wo_proc_id) REFERENCES prd_wo_proc (wo_proc_id),
    CONSTRAINT fk_prd_result_lot FOREIGN KEY (lot_id) REFERENCES lot_master (lot_id),
    CONSTRAINT fk_prd_result_equip FOREIGN KEY (equip_id) REFERENCES mst_equip (equip_id),
    CONSTRAINT fk_prd_result_terminal FOREIGN KEY (terminal_id) REFERENCES sys_terminal (terminal_id),
    CONSTRAINT fk_prd_result_worker FOREIGN KEY (performed_by) REFERENCES mst_worker (worker_id),
    CONSTRAINT fk_prd_result_reversal FOREIGN KEY (reversal_of_id) REFERENCES prd_result (result_id),
    CONSTRAINT uq_prd_result_req UNIQUE (comp_cd, client_req_id),
    CONSTRAINT ck_prd_result_req CHECK (client_req_id IS NULL OR client_req_id ~ '^[A-Za-z0-9_-]{8,64}$'),
    CONSTRAINT ck_prd_result_method CHECK (entry_method IN ('KEY', 'SCAN', 'AUTO', 'IMPORT', 'CORRECT')),
    -- 일반 행: 모두 0 이상·합 > 0·작업자·요청 id 필수 / 역분개: 모두 0 이하·CORRECT·사유
    CONSTRAINT ck_prd_result_qty CHECK (
        (reversal_of_id IS NULL AND entry_method <> 'CORRECT' AND good_qty >= 0 AND scrap_qty >= 0 AND rework_qty >= 0
         AND repair_qty >= 0 AND good_qty + scrap_qty + rework_qty + repair_qty > 0 AND performed_by IS NOT NULL
         AND client_req_id IS NOT NULL)
        OR (reversal_of_id IS NOT NULL AND entry_method = 'CORRECT' AND good_qty <= 0 AND scrap_qty <= 0 AND rework_qty <= 0
            AND repair_qty <= 0 AND length(btrim(reason)) > 0))
);
COMMENT ON TABLE prd_result IS '생산 실적 원장 (01 3.1, docs/25 4.7) — 수량은 지시 단위. 정정은 역분개(부호 반대, 한 번만)';
COMMENT ON COLUMN prd_result.lot_id IS '산출 LOT(양품 > 0 일 때 만든 LOT). 역분개 행은 원래 행과 같은 LOT';
CREATE UNIQUE INDEX uq_prd_result_reversal ON prd_result (reversal_of_id) WHERE reversal_of_id IS NOT NULL;
CREATE INDEX ix_prd_result_proc ON prd_result (comp_cd, wo_proc_id, occurred_at);
CREATE INDEX ix_prd_result_day ON prd_result (comp_cd, work_day, result_id);

CREATE TABLE prd_result_defect (
    result_defect_id bigint         GENERATED ALWAYS AS IDENTITY,
    comp_cd          varchar(20)    NOT NULL,
    result_id        bigint         NOT NULL,
    kind             varchar(10)    NOT NULL,
    defect_id        bigint         NOT NULL,
    qty              numeric(18,6)  NOT NULL,
    CONSTRAINT pk_prd_result_defect PRIMARY KEY (result_defect_id),
    CONSTRAINT fk_prd_result_defect_result FOREIGN KEY (result_id) REFERENCES prd_result (result_id),
    CONSTRAINT fk_prd_result_defect_defect FOREIGN KEY (defect_id) REFERENCES qms_defect (defect_id),
    CONSTRAINT uq_prd_result_defect UNIQUE (comp_cd, result_id, kind, defect_id),
    CONSTRAINT ck_prd_result_defect_kind CHECK (kind IN ('SCRAP', 'REWORK', 'REPAIR')),
    CONSTRAINT ck_prd_result_defect_qty CHECK (qty <> 0)
);
COMMENT ON TABLE prd_result_defect IS '실적의 불량 유형별 수량 — 종류별 합 = 실적의 폐기·재작업·수리 수량(커밋 때 확인). 추가만';

-- ---------------------------------------------------------------------
-- 3. 파라미터 실측 원장
-- ---------------------------------------------------------------------
CREATE TABLE prd_param_actual (
    actual_id       bigint         GENERATED ALWAYS AS IDENTITY,
    comp_cd         varchar(20)    NOT NULL,
    wo_proc_id      bigint         NOT NULL,
    wo_param_id     bigint         NOT NULL,
    param_cd        varchar(30)    NOT NULL,
    value           numeric(18,6)  NOT NULL,
    judge           varchar(2)     NOT NULL,
    equip_id        bigint,
    lot_id          bigint,
    terminal_id     bigint,
    source          varchar(10)    NOT NULL DEFAULT 'MANUAL',
    work_day        date           NOT NULL,
    shift_cd        varchar(20),
    client_req_id   varchar(64),
    reason          varchar(500),
    occurred_at     timestamptz    NOT NULL,
    recorded_at     timestamptz    NOT NULL DEFAULT now(),
    entry_method    varchar(10)    NOT NULL,
    recorded_by     varchar(50)    NOT NULL,
    performed_by    bigint,
    reversal_of_id  bigint,
    CONSTRAINT pk_prd_param_actual PRIMARY KEY (actual_id),
    CONSTRAINT fk_prd_param_actual_proc FOREIGN KEY (wo_proc_id) REFERENCES prd_wo_proc (wo_proc_id),
    CONSTRAINT fk_prd_param_actual_param FOREIGN KEY (wo_param_id) REFERENCES prd_wo_param (wo_param_id),
    CONSTRAINT fk_prd_param_actual_equip FOREIGN KEY (equip_id) REFERENCES mst_equip (equip_id),
    CONSTRAINT fk_prd_param_actual_lot FOREIGN KEY (lot_id) REFERENCES lot_master (lot_id),
    CONSTRAINT fk_prd_param_actual_terminal FOREIGN KEY (terminal_id) REFERENCES sys_terminal (terminal_id),
    CONSTRAINT fk_prd_param_actual_worker FOREIGN KEY (performed_by) REFERENCES mst_worker (worker_id),
    CONSTRAINT fk_prd_param_actual_reversal FOREIGN KEY (reversal_of_id) REFERENCES prd_param_actual (actual_id),
    CONSTRAINT uq_prd_param_actual_req UNIQUE (comp_cd, client_req_id),
    CONSTRAINT ck_prd_param_actual_req CHECK (client_req_id IS NULL OR client_req_id ~ '^[A-Za-z0-9_-]{8,64}$'),
    CONSTRAINT ck_prd_param_actual_judge CHECK (judge IN ('OK', 'NG')),
    CONSTRAINT ck_prd_param_actual_source CHECK (source IN ('MANUAL', 'COLLECTOR')),
    CONSTRAINT ck_prd_param_actual_method CHECK (entry_method IN ('KEY', 'SCAN', 'AUTO', 'IMPORT', 'CORRECT')),
    -- 정정 행 = 원래 행을 가리키는 CORRECT 행(값 그대로) + 사유. 조회에서 둘 다 빠진다
    CONSTRAINT ck_prd_param_actual_reversal CHECK (
        (reversal_of_id IS NULL AND entry_method <> 'CORRECT' AND client_req_id IS NOT NULL)
        OR (reversal_of_id IS NOT NULL AND entry_method = 'CORRECT' AND length(btrim(reason)) > 0))
);
COMMENT ON TABLE prd_param_actual IS '공정 파라미터 실측 원장 (docs/25 4.7) — 지시 파라미터 스냅샷만, 판정은 저장 때 상하한으로';
CREATE UNIQUE INDEX uq_prd_param_actual_reversal ON prd_param_actual (reversal_of_id) WHERE reversal_of_id IS NOT NULL;
CREATE INDEX ix_prd_param_actual_proc ON prd_param_actual (comp_cd, wo_proc_id, occurred_at);

-- ---------------------------------------------------------------------
-- 4. 트리거 — 같은 회사·같은 지시, 전이 표, 역분개 거울, 불량 내역 합
-- ---------------------------------------------------------------------

-- 공정 이벤트: 같은 회사의 지시·공정(공정은 그 지시의 것)·설비·단말·작업자, 멈춤 사유는 쓸 수 있는 코드
CREATE FUNCTION hb_guard_proc_event() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM prd_wo_proc p WHERE p.wo_proc_id = NEW.wo_proc_id AND p.comp_cd = NEW.comp_cd AND p.wo_id = NEW.wo_id)
       OR (NEW.equip_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM mst_equip e WHERE e.equip_id = NEW.equip_id AND e.comp_cd = NEW.comp_cd))
       OR (NEW.terminal_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM sys_terminal t WHERE t.terminal_id = NEW.terminal_id
                                                          AND t.comp_cd = NEW.comp_cd))
       OR NOT EXISTS (SELECT 1 FROM mst_worker k WHERE k.worker_id = NEW.performed_by AND k.comp_cd = NEW.comp_cd) THEN
        RAISE EXCEPTION 'process event context is not of company %', NEW.comp_cd
            USING ERRCODE = '23503', CONSTRAINT = 'fk_prd_wo_proc_event_ctx_comp';
    END IF;
    IF NEW.reason_cd IS NOT NULL
       AND NOT EXISTS (SELECT 1 FROM sys_code c WHERE c.grp_cd = 'DOWNTIME_REASON' AND c.code = NEW.reason_cd AND c.use_yn = 'Y')
       AND NOT EXISTS (SELECT 1 FROM sys_comp_code c WHERE c.comp_cd = NEW.comp_cd AND c.grp_cd = 'DOWNTIME_REASON'
                          AND c.code = NEW.reason_cd AND c.use_yn = 'Y') THEN
        RAISE EXCEPTION 'downtime reason % is not usable', NEW.reason_cd
            USING ERRCODE = '23503', CONSTRAINT = 'fk_prd_wo_proc_event_reason';
    END IF;
    RETURN NEW;
END
$$;
CREATE TRIGGER tg_prd_wo_proc_event_guard BEFORE INSERT ON prd_wo_proc_event FOR EACH ROW EXECUTE FUNCTION hb_guard_proc_event();
CREATE TRIGGER tg_prd_wo_proc_event_ledger BEFORE UPDATE OR DELETE ON prd_wo_proc_event FOR EACH ROW EXECUTE FUNCTION hb_forbid_update_delete();

-- 공정 상태: 전이 표 + 같은 트랜잭션에 그 이벤트 행(recorded_at = now() 는 트랜잭션 시작 시각)
-- 완료(DONE)는 7단계 완료판정이 이 함수를 바꿔 연다
CREATE FUNCTION hb_guard_wo_proc_status() RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    v_event varchar(10);
BEGIN
    IF NEW.status = OLD.status THEN
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
CREATE TRIGGER tg_prd_wo_proc_status BEFORE UPDATE OF status ON prd_wo_proc FOR EACH ROW EXECUTE FUNCTION hb_guard_wo_proc_status();

-- 실적: 같은 회사 문맥, 일반 행은 공정이 진행 중(RUN·PAUSE)이고 단위 = 지시 단위, 산출 LOT 은 그 지시의 생산 LOT.
-- 역분개는 원래 행의 거울(같은 공정·LOT·단위, 수량 정확히 반대)
CREATE FUNCTION hb_guard_prd_result() RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    v_wo   prd_wo;
    v_proc prd_wo_proc;
    v_orig prd_result;
BEGIN
    SELECT * INTO v_wo FROM prd_wo WHERE wo_id = NEW.wo_id AND comp_cd = NEW.comp_cd;
    SELECT * INTO v_proc FROM prd_wo_proc WHERE wo_proc_id = NEW.wo_proc_id AND comp_cd = NEW.comp_cd AND wo_id = NEW.wo_id;
    IF v_wo.wo_id IS NULL OR v_proc.wo_proc_id IS NULL
       OR (NEW.equip_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM mst_equip e WHERE e.equip_id = NEW.equip_id AND e.comp_cd = NEW.comp_cd))
       OR (NEW.terminal_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM sys_terminal t WHERE t.terminal_id = NEW.terminal_id
                                                          AND t.comp_cd = NEW.comp_cd))
       OR (NEW.performed_by IS NOT NULL AND NOT EXISTS (SELECT 1 FROM mst_worker k WHERE k.worker_id = NEW.performed_by
                                                           AND k.comp_cd = NEW.comp_cd))
       OR (NEW.lot_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM lot_master l WHERE l.lot_id = NEW.lot_id AND l.comp_cd = NEW.comp_cd
                                                     AND l.wo_id = NEW.wo_id AND l.origin = 'PRODUCED')) THEN
        RAISE EXCEPTION 'result context is not of company % or work order %', NEW.comp_cd, NEW.wo_id
            USING ERRCODE = '23503', CONSTRAINT = 'fk_prd_result_ctx_comp';
    END IF;
    IF NEW.unit_cd <> v_wo.unit_cd THEN
        RAISE EXCEPTION 'result quantity is in the work order unit %', v_wo.unit_cd
            USING ERRCODE = '23514', CONSTRAINT = 'ck_prd_result_unit';
    END IF;
    IF NEW.reversal_of_id IS NULL THEN
        IF v_proc.status NOT IN ('RUN', 'PAUSE') THEN
            RAISE EXCEPTION 'results are recorded while the process runs (now %)', v_proc.status
                USING ERRCODE = '55000', CONSTRAINT = 'ck_prd_result_proc_running';
        END IF;
        IF (NEW.good_qty > 0) <> (NEW.lot_id IS NOT NULL) THEN
            RAISE EXCEPTION 'a result with good quantity has exactly its output lot'
                USING ERRCODE = '23514', CONSTRAINT = 'ck_prd_result_lot';
        END IF;
        RETURN NEW;
    END IF;
    SELECT * INTO v_orig FROM prd_result WHERE result_id = NEW.reversal_of_id AND comp_cd = NEW.comp_cd;
    IF v_orig.result_id IS NULL OR v_orig.reversal_of_id IS NOT NULL
       OR (v_orig.wo_proc_id, v_orig.lot_id, v_orig.unit_cd, v_orig.good_qty, v_orig.scrap_qty, v_orig.rework_qty, v_orig.repair_qty)
          IS DISTINCT FROM (NEW.wo_proc_id, NEW.lot_id, NEW.unit_cd, -NEW.good_qty, -NEW.scrap_qty, -NEW.rework_qty, -NEW.repair_qty) THEN
        RAISE EXCEPTION 'reversal must mirror result %', NEW.reversal_of_id
            USING ERRCODE = '23514', CONSTRAINT = 'ck_prd_result_reversal';
    END IF;
    RETURN NEW;
END
$$;
CREATE TRIGGER tg_prd_result_guard BEFORE INSERT ON prd_result FOR EACH ROW EXECUTE FUNCTION hb_guard_prd_result();
CREATE TRIGGER tg_prd_result_ledger BEFORE UPDATE OR DELETE ON prd_result FOR EACH ROW EXECUTE FUNCTION hb_forbid_update_delete();

-- 불량 내역: 같은 회사 실적·사용 중 불량 유형, 부호는 실적과 같다(역분개는 음수)
CREATE FUNCTION hb_guard_result_defect() RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    v_res prd_result;
BEGIN
    SELECT * INTO v_res FROM prd_result WHERE result_id = NEW.result_id AND comp_cd = NEW.comp_cd;
    IF v_res.result_id IS NULL
       OR NOT EXISTS (SELECT 1 FROM qms_defect d WHERE d.defect_id = NEW.defect_id AND d.comp_cd = NEW.comp_cd
                         AND (d.use_yn = 'Y' OR v_res.reversal_of_id IS NOT NULL)) THEN
        RAISE EXCEPTION 'defect line is not of company %', NEW.comp_cd
            USING ERRCODE = '23503', CONSTRAINT = 'fk_prd_result_defect_ctx_comp';
    END IF;
    IF (v_res.reversal_of_id IS NULL) <> (NEW.qty > 0) THEN
        RAISE EXCEPTION 'defect quantity sign follows the result' USING ERRCODE = '23514', CONSTRAINT = 'ck_prd_result_defect_sign';
    END IF;
    RETURN NEW;
END
$$;
CREATE TRIGGER tg_prd_result_defect_guard BEFORE INSERT ON prd_result_defect FOR EACH ROW EXECUTE FUNCTION hb_guard_result_defect();
CREATE TRIGGER tg_prd_result_defect_ledger BEFORE UPDATE OR DELETE ON prd_result_defect FOR EACH ROW EXECUTE FUNCTION hb_forbid_update_delete();

-- 커밋 때: 실적의 종류별 불량 합 = 그 수량 ("닫을 때 필수", 11 D13 — 확정이 곧 닫기)
CREATE FUNCTION hb_check_result_defect_sum() RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    v_id  bigint := NEW.result_id;   -- 두 테이블 모두 result_id 칸
    v_res prd_result;
BEGIN
    SELECT * INTO v_res FROM prd_result WHERE result_id = v_id;
    IF v_res.scrap_qty <> coalesce((SELECT sum(qty) FROM prd_result_defect WHERE result_id = v_id AND kind = 'SCRAP'), 0)
       OR v_res.rework_qty <> coalesce((SELECT sum(qty) FROM prd_result_defect WHERE result_id = v_id AND kind = 'REWORK'), 0)
       OR v_res.repair_qty <> coalesce((SELECT sum(qty) FROM prd_result_defect WHERE result_id = v_id AND kind = 'REPAIR'), 0) THEN
        RAISE EXCEPTION 'defect lines of result % do not add up to its scrap/rework/repair quantities', v_id
            USING ERRCODE = '23514', CONSTRAINT = 'ck_prd_result_defect_sum';
    END IF;
    RETURN NULL;
END
$$;
CREATE CONSTRAINT TRIGGER tg_prd_result_defect_sum AFTER INSERT ON prd_result
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION hb_check_result_defect_sum();
CREATE CONSTRAINT TRIGGER tg_prd_result_defect_sum AFTER INSERT ON prd_result_defect
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION hb_check_result_defect_sum();

-- 파라미터 실측: 같은 회사·그 공정의 스냅샷 파라미터(코드 일치), 정정은 원래 행의 거울(같은 파라미터·값)
CREATE FUNCTION hb_guard_param_actual() RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    v_orig prd_param_actual;
BEGIN
    IF NOT EXISTS (SELECT 1 FROM prd_wo_param p WHERE p.wo_param_id = NEW.wo_param_id AND p.comp_cd = NEW.comp_cd
                      AND p.wo_proc_id = NEW.wo_proc_id AND p.param_cd = NEW.param_cd)
       OR (NEW.equip_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM mst_equip e WHERE e.equip_id = NEW.equip_id AND e.comp_cd = NEW.comp_cd))
       OR (NEW.lot_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM lot_master l WHERE l.lot_id = NEW.lot_id AND l.comp_cd = NEW.comp_cd))
       OR (NEW.terminal_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM sys_terminal t WHERE t.terminal_id = NEW.terminal_id
                                                          AND t.comp_cd = NEW.comp_cd))
       OR (NEW.performed_by IS NOT NULL AND NOT EXISTS (SELECT 1 FROM mst_worker k WHERE k.worker_id = NEW.performed_by
                                                           AND k.comp_cd = NEW.comp_cd)) THEN
        RAISE EXCEPTION 'parameter actual context is not of company %', NEW.comp_cd
            USING ERRCODE = '23503', CONSTRAINT = 'fk_prd_param_actual_ctx_comp';
    END IF;
    IF NEW.reversal_of_id IS NOT NULL THEN
        SELECT * INTO v_orig FROM prd_param_actual WHERE actual_id = NEW.reversal_of_id AND comp_cd = NEW.comp_cd;
        IF v_orig.actual_id IS NULL OR v_orig.reversal_of_id IS NOT NULL
           OR (v_orig.wo_param_id, v_orig.value) IS DISTINCT FROM (NEW.wo_param_id, NEW.value) THEN
            RAISE EXCEPTION 'correction must mirror parameter actual %', NEW.reversal_of_id
                USING ERRCODE = '23514', CONSTRAINT = 'ck_prd_param_actual_mirror';
        END IF;
    END IF;
    RETURN NEW;
END
$$;
CREATE TRIGGER tg_prd_param_actual_guard BEFORE INSERT ON prd_param_actual FOR EACH ROW EXECUTE FUNCTION hb_guard_param_actual();
CREATE TRIGGER tg_prd_param_actual_ledger BEFORE UPDATE OR DELETE ON prd_param_actual FOR EACH ROW EXECUTE FUNCTION hb_forbid_update_delete();

-- ---------------------------------------------------------------------
-- 5. RLS · 권한 · 보존
-- ---------------------------------------------------------------------
ALTER TABLE prd_wo_proc_event ENABLE ROW LEVEL SECURITY;
ALTER TABLE prd_wo_proc_event FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON prd_wo_proc_event USING (comp_cd = hb_current_comp()) WITH CHECK (comp_cd = hb_current_comp());
ALTER TABLE prd_result ENABLE ROW LEVEL SECURITY;
ALTER TABLE prd_result FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON prd_result USING (comp_cd = hb_current_comp()) WITH CHECK (comp_cd = hb_current_comp());
ALTER TABLE prd_result_defect ENABLE ROW LEVEL SECURITY;
ALTER TABLE prd_result_defect FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON prd_result_defect USING (comp_cd = hb_current_comp()) WITH CHECK (comp_cd = hb_current_comp());
ALTER TABLE prd_param_actual ENABLE ROW LEVEL SECURITY;
ALTER TABLE prd_param_actual FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON prd_param_actual USING (comp_cd = hb_current_comp()) WITH CHECK (comp_cd = hb_current_comp());
REVOKE UPDATE, DELETE, TRUNCATE ON prd_wo_proc_event, prd_result, prd_result_defect, prd_param_actual FROM ${app_role};

INSERT INTO sys_retention_table (table_nm, class_cd) VALUES
    ('prd_wo_proc_event', 'PRODUCTION_RECORD'), ('prd_result', 'PRODUCTION_RECORD'),
    ('prd_result_defect', 'PRODUCTION_RECORD'), ('prd_param_actual', 'PRODUCTION_RECORD');
