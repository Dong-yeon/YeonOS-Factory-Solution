-- =====================================================================
-- 장비 시리얼 구성(as-built) — docs/77. 임시 번호(이 세션 대역 V9970~V9979) — 병합 때 상위 세션이 다음 빈 번호로 이름만 바꾼다
--
-- - lot_master.origin 에 UNIT(장비 LOT — 시리얼마다 하나, 수량 1, 번호 = 시리얼 번호, wo_id 없음 → 생산 LOT 집계에 안 섞임)
-- - prd_serial: 장비 시리얼(지시 1건에 계획 수량만큼) — 번호·지시·품목·장비 LOT 불변, row_ver 로 동시 장착 409
-- - prd_serial_part: 장착 원장 — INSTALL 만 쌓고 탈거는 역분개 REMOVE(reversal_of_id). 장착 = 계보 CONSUME(부품 LOT → 장비 LOT) 동반
-- - 같은 품목 × 부품 시리얼은 장착 중 한 대에만(advisory 잠금으로 줄 세운 뒤 확인)
-- - 화면 prd-serial(구성), prd-serial-trace(역추적)
-- =====================================================================

-- ---------------------------------------------------------------------
-- 1. 장비 LOT 출처 UNIT
-- ---------------------------------------------------------------------
ALTER TABLE lot_master DROP CONSTRAINT ck_lot_master_origin;
ALTER TABLE lot_master ADD CONSTRAINT ck_lot_master_origin
    CHECK (origin IN ('RECEIVED', 'PRODUCED', 'SPLIT', 'MERGE', 'RELABEL', 'REWORK', 'REPAIR', 'UNIT'));
-- 장비 LOT 은 개체 하나 — 수량 1
ALTER TABLE lot_master ADD CONSTRAINT ck_lot_master_unit CHECK (origin <> 'UNIT' OR qty_created = 1);
INSERT INTO sys_code (grp_cd, code, code_nm_key, sort_no) VALUES
    ('LOT_ORIGIN', 'UNIT', 'code.LOT_ORIGIN.UNIT', 8);

INSERT INTO sys_code_grp (grp_cd, grp_nm_key, comp_ext_yn) VALUES
    ('SERIAL_PART_EV', 'code.grp.SERIAL_PART_EV', 'N');
INSERT INTO sys_code (grp_cd, code, code_nm_key, sort_no) VALUES
    ('SERIAL_PART_EV', 'INSTALL', 'code.SERIAL_PART_EV.INSTALL', 1),
    ('SERIAL_PART_EV', 'REMOVE',  'code.SERIAL_PART_EV.REMOVE',  2);

-- ---------------------------------------------------------------------
-- 2. 장비 시리얼
-- ---------------------------------------------------------------------
CREATE TABLE prd_serial (
    serial_id    bigint         GENERATED ALWAYS AS IDENTITY,
    comp_cd      varchar(20)    NOT NULL,
    serial_no    varchar(60)    NOT NULL,
    wo_id        bigint         NOT NULL,
    item_id      bigint         NOT NULL,
    unit_lot_id  bigint         NOT NULL,
    remark       varchar(1000),
    row_ver      integer        NOT NULL DEFAULT 0,
    created_at   timestamptz    NOT NULL DEFAULT now(),
    created_by   varchar(50)    NOT NULL,
    updated_at   timestamptz    NOT NULL DEFAULT now(),
    updated_by   varchar(50)    NOT NULL,
    CONSTRAINT pk_prd_serial PRIMARY KEY (serial_id),
    CONSTRAINT fk_prd_serial_comp FOREIGN KEY (comp_cd) REFERENCES sys_comp (comp_cd),
    CONSTRAINT fk_prd_serial_wo FOREIGN KEY (wo_id) REFERENCES prd_wo (wo_id),
    CONSTRAINT fk_prd_serial_item FOREIGN KEY (item_id) REFERENCES mst_item (item_id),
    CONSTRAINT fk_prd_serial_lot FOREIGN KEY (unit_lot_id) REFERENCES lot_master (lot_id),
    CONSTRAINT uq_prd_serial_no UNIQUE (comp_cd, serial_no),
    CONSTRAINT uq_prd_serial_lot UNIQUE (comp_cd, unit_lot_id),
    -- 명판·바코드로 오가는 번호: 출력 가능한 ASCII, 공백 없음(LOT 번호와 같은 규칙 — 장비 LOT 번호가 된다)
    CONSTRAINT ck_prd_serial_no CHECK (serial_no ~ '^[\x21-\x7E]{1,60}$')
);
COMMENT ON TABLE prd_serial IS '장비 시리얼(완성품 1대, docs/77) — 지시 1건에 계획 수량만큼. 번호·지시·품목·장비 LOT 은 고정, row_ver 는 장착·탈거마다 +1';
COMMENT ON COLUMN prd_serial.serial_no IS '시리얼 번호 — 채번 규칙 SERIAL(항상 자동, 사용자 결정 2026-11-16)';
COMMENT ON COLUMN prd_serial.unit_lot_id IS '장비 LOT(origin UNIT, 수량 1, 번호 = 시리얼 번호) — 부품 장착 계보(CONSUME)의 자식';
CREATE INDEX ix_prd_serial_wo ON prd_serial (comp_cd, wo_id);

CREATE FUNCTION hb_guard_prd_serial() RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    v_wo  prd_wo;
    v_cnt integer;
BEGIN
    IF TG_OP = 'UPDATE' THEN
        IF (NEW.serial_no, NEW.wo_id, NEW.item_id, NEW.unit_lot_id, NEW.comp_cd)
               IS DISTINCT FROM (OLD.serial_no, OLD.wo_id, OLD.item_id, OLD.unit_lot_id, OLD.comp_cd) THEN
            RAISE EXCEPTION 'serial % keeps its number, order, item and unit lot', OLD.serial_no
                USING ERRCODE = '55000', CONSTRAINT = 'ck_prd_serial_fixed';
        END IF;
        RETURN NEW;
    END IF;
    -- 지시를 잠근다 — 동시에 만든 두 요청이 같은 남은 수량을 쓰지 않게
    SELECT * INTO v_wo FROM prd_wo WHERE wo_id = NEW.wo_id AND comp_cd = NEW.comp_cd FOR UPDATE;
    IF v_wo.wo_id IS NULL OR v_wo.item_id <> NEW.item_id THEN
        RAISE EXCEPTION 'work order % is not an order of the item in company %', NEW.wo_id, NEW.comp_cd
            USING ERRCODE = '23503', CONSTRAINT = 'fk_prd_serial_wo_comp';
    END IF;
    IF v_wo.status NOT IN ('RELEASED', 'IN_PROGRESS') THEN
        RAISE EXCEPTION 'work order % is %', v_wo.wo_no, v_wo.status USING ERRCODE = '55000', CONSTRAINT = 'ck_prd_serial_wo_state';
    END IF;
    IF v_wo.plan_qty <> trunc(v_wo.plan_qty) THEN
        RAISE EXCEPTION 'work order % quantity is not whole', v_wo.wo_no USING ERRCODE = '23514', CONSTRAINT = 'ck_prd_serial_wo_whole';
    END IF;
    SELECT count(*) INTO v_cnt FROM prd_serial WHERE wo_id = NEW.wo_id AND comp_cd = NEW.comp_cd;
    IF v_cnt + 1 > v_wo.plan_qty THEN
        RAISE EXCEPTION 'work order % has % serials already', v_wo.wo_no, v_cnt USING ERRCODE = '23514', CONSTRAINT = 'ck_prd_serial_count';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM lot_master l WHERE l.lot_id = NEW.unit_lot_id AND l.comp_cd = NEW.comp_cd AND l.origin = 'UNIT'
                      AND l.item_id = NEW.item_id AND l.lot_no = NEW.serial_no) THEN
        RAISE EXCEPTION 'lot % is not the unit lot of serial %', NEW.unit_lot_id, NEW.serial_no
            USING ERRCODE = '23503', CONSTRAINT = 'fk_prd_serial_lot_comp';
    END IF;
    RETURN NEW;
END
$$;
CREATE TRIGGER tg_prd_serial_guard BEFORE INSERT OR UPDATE ON prd_serial FOR EACH ROW EXECUTE FUNCTION hb_guard_prd_serial();
CREATE TRIGGER tg_prd_serial_no_delete BEFORE DELETE ON prd_serial FOR EACH ROW EXECUTE FUNCTION hb_forbid_delete_master();

-- ---------------------------------------------------------------------
-- 3. 장착 원장
-- ---------------------------------------------------------------------
CREATE TABLE prd_serial_part (
    part_id         bigint         GENERATED ALWAYS AS IDENTITY,
    comp_cd         varchar(20)    NOT NULL,
    serial_id       bigint         NOT NULL,
    ev_type         varchar(10)    NOT NULL,
    part_item_id    bigint         NOT NULL,
    part_lot_id     bigint,
    part_serial_no  varchar(60),
    qty             numeric(18,6)  NOT NULL,
    unit_cd         varchar(30)    NOT NULL,
    wo_proc_id      bigint,
    gen_id          bigint,
    reason          varchar(500),
    occurred_at     timestamptz    NOT NULL,
    recorded_at     timestamptz    NOT NULL DEFAULT now(),
    entry_method    varchar(10)    NOT NULL,
    recorded_by     varchar(50)    NOT NULL,
    performed_by    bigint,
    reversal_of_id  bigint,
    CONSTRAINT pk_prd_serial_part PRIMARY KEY (part_id),
    CONSTRAINT fk_prd_serial_part_serial FOREIGN KEY (serial_id) REFERENCES prd_serial (serial_id),
    CONSTRAINT fk_prd_serial_part_item FOREIGN KEY (part_item_id) REFERENCES mst_item (item_id),
    CONSTRAINT fk_prd_serial_part_lot FOREIGN KEY (part_lot_id) REFERENCES lot_master (lot_id),
    CONSTRAINT fk_prd_serial_part_proc FOREIGN KEY (wo_proc_id) REFERENCES prd_wo_proc (wo_proc_id),
    CONSTRAINT fk_prd_serial_part_gen FOREIGN KEY (gen_id) REFERENCES lot_genealogy (gen_id),
    CONSTRAINT fk_prd_serial_part_worker FOREIGN KEY (performed_by) REFERENCES mst_worker (worker_id),
    CONSTRAINT fk_prd_serial_part_reversal FOREIGN KEY (reversal_of_id) REFERENCES prd_serial_part (part_id),
    CONSTRAINT ck_prd_serial_part_ev CHECK (ev_type IN ('INSTALL', 'REMOVE')),
    CONSTRAINT ck_prd_serial_part_method CHECK (entry_method IN ('KEY', 'SCAN', 'AUTO', 'IMPORT', 'CORRECT')),
    CONSTRAINT ck_prd_serial_part_sn CHECK (part_serial_no ~ '^[\x21-\x7E]([\x20-\x7E]*[\x21-\x7E])?$'),
    -- 장착 = 양수·되돌림 아님·정정 아님 / 탈거 = 장착 행의 반대(음수)·사유 필수
    CONSTRAINT ck_prd_serial_part_qty CHECK (
        (ev_type = 'INSTALL' AND reversal_of_id IS NULL AND qty > 0 AND entry_method <> 'CORRECT')
        OR (ev_type = 'REMOVE' AND reversal_of_id IS NOT NULL AND qty < 0 AND length(btrim(reason)) > 0)),
    -- 부품 시리얼 = 개체 하나
    CONSTRAINT ck_prd_serial_part_unit CHECK (part_serial_no IS NULL OR abs(qty) = 1)
);
COMMENT ON TABLE prd_serial_part IS '장비 장착 원장(as-built, docs/77) — 장착 행만 쌓고 탈거는 반대 행(reversal_of_id). 수정·삭제 없음';
COMMENT ON COLUMN prd_serial_part.gen_id IS '계보 행 — 장착 = 부품 LOT → 장비 LOT CONSUME, 탈거 = 그 역분개. LOT 비관리 부품은 비움';
COMMENT ON COLUMN prd_serial_part.part_serial_no IS '부품 시리얼(명판 번호 등, 선택) — 같은 품목 × 번호는 장착 중 한 대에만';
COMMENT ON COLUMN prd_serial_part.performed_by IS '실제 작업자(mst_worker) — recorded_by(입력한 사람)와 다를 수 있다';
CREATE UNIQUE INDEX uq_prd_serial_part_reversal ON prd_serial_part (reversal_of_id) WHERE reversal_of_id IS NOT NULL;
CREATE INDEX ix_prd_serial_part_serial ON prd_serial_part (comp_cd, serial_id);
CREATE INDEX ix_prd_serial_part_lot ON prd_serial_part (comp_cd, part_lot_id) WHERE part_lot_id IS NOT NULL;
CREATE INDEX ix_prd_serial_part_sn ON prd_serial_part (comp_cd, part_item_id, part_serial_no) WHERE part_serial_no IS NOT NULL;

CREATE FUNCTION hb_guard_prd_serial_part() RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    v_serial prd_serial;
    v_item   mst_item;
    v_lot    lot_master;
    v_gen    lot_genealogy;
    v_orig   prd_serial_part;
BEGIN
    -- 시리얼을 잠근다 — 같은 장비에 대한 장착·탈거를 줄 세운다
    SELECT * INTO v_serial FROM prd_serial WHERE serial_id = NEW.serial_id AND comp_cd = NEW.comp_cd FOR UPDATE;
    SELECT * INTO v_item FROM mst_item WHERE item_id = NEW.part_item_id AND comp_cd = NEW.comp_cd;
    IF v_serial.serial_id IS NULL OR v_item.item_id IS NULL THEN
        RAISE EXCEPTION 'serial % / item % are not of company %', NEW.serial_id, NEW.part_item_id, NEW.comp_cd
            USING ERRCODE = '23503', CONSTRAINT = 'fk_prd_serial_part_comp';
    END IF;
    IF NEW.unit_cd <> v_item.unit_cd THEN
        RAISE EXCEPTION 'part quantity is in the item unit %', v_item.unit_cd USING ERRCODE = '23514', CONSTRAINT = 'ck_prd_serial_part_uom';
    END IF;
    IF (NEW.wo_proc_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM prd_wo_proc p WHERE p.wo_proc_id = NEW.wo_proc_id
                                                      AND p.comp_cd = NEW.comp_cd AND p.wo_id = v_serial.wo_id))
       OR (NEW.performed_by IS NOT NULL AND NOT EXISTS (SELECT 1 FROM mst_worker k WHERE k.worker_id = NEW.performed_by
                                                           AND k.comp_cd = NEW.comp_cd)) THEN
        RAISE EXCEPTION 'part context is not of serial % in company %', v_serial.serial_no, NEW.comp_cd
            USING ERRCODE = '23503', CONSTRAINT = 'fk_prd_serial_part_ctx_comp';
    END IF;
    IF NEW.part_lot_id IS NOT NULL THEN
        SELECT * INTO v_lot FROM lot_master WHERE lot_id = NEW.part_lot_id AND comp_cd = NEW.comp_cd;
        IF v_lot.lot_id IS NULL OR v_lot.item_id <> NEW.part_item_id THEN
            RAISE EXCEPTION 'lot % is not a lot of the part item', NEW.part_lot_id
                USING ERRCODE = '23503', CONSTRAINT = 'fk_prd_serial_part_lot_item';
        END IF;
    END IF;
    -- 계보 행: 부품 LOT → 이 장비 LOT 의 투입(장착) / 그 역분개(탈거), 수량 같음
    IF NEW.gen_id IS NOT NULL THEN
        SELECT * INTO v_gen FROM lot_genealogy WHERE gen_id = NEW.gen_id AND comp_cd = NEW.comp_cd;
        IF v_gen.gen_id IS NULL OR v_gen.rel_type <> 'CONSUME' OR v_gen.parent_lot_id IS DISTINCT FROM NEW.part_lot_id
           OR v_gen.child_lot_id <> v_serial.unit_lot_id OR v_gen.qty <> NEW.qty
           OR (NEW.ev_type = 'INSTALL') <> (v_gen.reversal_of_id IS NULL) THEN
            RAISE EXCEPTION 'genealogy row % does not match the part row', NEW.gen_id
                USING ERRCODE = '23514', CONSTRAINT = 'ck_prd_serial_part_gen';
        END IF;
    END IF;
    IF NEW.ev_type = 'INSTALL' THEN
        IF v_item.use_yn <> 'Y' THEN
            RAISE EXCEPTION 'item % is not active', v_item.item_cd USING ERRCODE = '23503', CONSTRAINT = 'fk_prd_serial_part_item_active';
        END IF;
        -- LOT 관리 품목은 LOT 과 계보 투입이 있어야 한다(장착 = 계보 투입, 사용자 결정 2026-11-16)
        IF v_item.lot_ctrl_yn = 'Y' AND (NEW.part_lot_id IS NULL OR NEW.gen_id IS NULL) THEN
            RAISE EXCEPTION 'lot-controlled item % needs a lot', v_item.item_cd USING ERRCODE = '23514', CONSTRAINT = 'ck_prd_serial_part_lot_req';
        END IF;
        IF (NEW.part_lot_id IS NULL) <> (NEW.gen_id IS NULL) THEN
            RAISE EXCEPTION 'part lot and genealogy go together' USING ERRCODE = '23514', CONSTRAINT = 'ck_prd_serial_part_gen';
        END IF;
        -- 같은 품목 × 부품 시리얼은 장착 중 한 대에만 — 번호로 줄 세운 뒤 확인(동시에 두 장비)
        IF NEW.part_serial_no IS NOT NULL THEN
            PERFORM pg_advisory_xact_lock(hashtextextended('prd_serial_part:' || NEW.comp_cd || ':' || NEW.part_item_id || ':'
                                                           || NEW.part_serial_no, 0));
            IF EXISTS (SELECT 1 FROM prd_serial_part i
                        WHERE i.comp_cd = NEW.comp_cd AND i.part_item_id = NEW.part_item_id AND i.part_serial_no = NEW.part_serial_no
                          AND i.ev_type = 'INSTALL'
                          AND NOT EXISTS (SELECT 1 FROM prd_serial_part r WHERE r.reversal_of_id = i.part_id)) THEN
                RAISE EXCEPTION 'part serial % is installed already', NEW.part_serial_no
                    USING ERRCODE = '23505', CONSTRAINT = 'uq_prd_serial_part_sn_active';
            END IF;
        END IF;
        RETURN NEW;
    END IF;
    -- 탈거: 같은 회사의 장착 행과 같은 시리얼·품목·LOT·번호·단위, 수량 정확히 반대
    SELECT * INTO v_orig FROM prd_serial_part WHERE part_id = NEW.reversal_of_id AND comp_cd = NEW.comp_cd;
    IF v_orig.part_id IS NULL OR v_orig.ev_type <> 'INSTALL'
       OR (v_orig.serial_id, v_orig.part_item_id, v_orig.part_lot_id, v_orig.part_serial_no, v_orig.unit_cd, v_orig.qty)
          IS DISTINCT FROM (NEW.serial_id, NEW.part_item_id, NEW.part_lot_id, NEW.part_serial_no, NEW.unit_cd, -NEW.qty)
       OR (v_orig.gen_id IS NULL) <> (NEW.gen_id IS NULL)
       OR (NEW.gen_id IS NOT NULL AND v_gen.reversal_of_id IS DISTINCT FROM v_orig.gen_id) THEN
        RAISE EXCEPTION 'removal must mirror install row %', NEW.reversal_of_id
            USING ERRCODE = '23514', CONSTRAINT = 'ck_prd_serial_part_reversal';
    END IF;
    RETURN NEW;
END
$$;
CREATE TRIGGER tg_prd_serial_part_guard BEFORE INSERT ON prd_serial_part FOR EACH ROW EXECUTE FUNCTION hb_guard_prd_serial_part();
CREATE TRIGGER tg_prd_serial_part_ledger BEFORE UPDATE OR DELETE ON prd_serial_part FOR EACH ROW EXECUTE FUNCTION hb_forbid_update_delete();

-- ---------------------------------------------------------------------
-- 4. 감사 · RLS · 권한 · 화면 · 보존
-- ---------------------------------------------------------------------
CREATE TRIGGER tg_audit AFTER INSERT OR UPDATE OR DELETE ON prd_serial FOR EACH ROW EXECUTE FUNCTION hb_audit('serial_id', 'row_ver', '');

ALTER TABLE prd_serial ENABLE ROW LEVEL SECURITY;
ALTER TABLE prd_serial FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON prd_serial USING (comp_cd = hb_current_comp()) WITH CHECK (comp_cd = hb_current_comp());
ALTER TABLE prd_serial_part ENABLE ROW LEVEL SECURITY;
ALTER TABLE prd_serial_part FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON prd_serial_part USING (comp_cd = hb_current_comp()) WITH CHECK (comp_cd = hb_current_comp());
REVOKE DELETE, TRUNCATE ON prd_serial FROM ${app_role};
REVOKE UPDATE, DELETE, TRUNCATE ON prd_serial_part FROM ${app_role};

INSERT INTO sys_prog (prog_cd, prog_nm_key, view_path, module_cd, actions, sort_no) VALUES
    ('prd-serial', 'prog.prdSerial', 'production/serial-mgt', 'PRD', ARRAY['SEARCH','SAVE','EXCEL']::varchar(20)[], 470),
    ('prd-serial-trace', 'prog.prdSerialTrace', 'production/serial-trace', 'PRD', ARRAY['SEARCH','EXCEL']::varchar(20)[], 480);
INSERT INTO sys_retention_table (table_nm, class_cd) VALUES
    ('prd_serial', 'PRODUCTION_RECORD'), ('prd_serial_part', 'PRODUCTION_RECORD');
