-- =====================================================================
-- V034 LOT·계보 원장 (P2 4단계 — docs/25 4.5, 결정 D2·D4)
--
-- - lot_master: LOT(출처 RECEIVED·PRODUCED·SPLIT·MERGE·RELABEL, 만든 양은 고정). 잔량은 계보에서 계산(칸 없음 — D2 재고는 P4)
-- - lot_genealogy: 이 프로젝트 첫 원장(01 3.1) — 부모 LOT → 자식 LOT 으로 나간 양. 수정·삭제 없음, 정정은 역분개 행
--   부모 잔량을 넘겨 내보내지 못한다(트리거가 부모 LOT 행을 잠그고 확인 — 동시 분할·직접 SQL 까지)
-- - 정책 LOT_NO_MODE(SEQ·ITEM_SEQ·WO), LOT_TRACE_DEPTH
-- =====================================================================

INSERT INTO sys_policy_def (policy_key, value_type, default_value, allowed_values, min_value, max_value, desc_key) VALUES
    ('LOT_NO_MODE',     'ENUM', 'SEQ', ARRAY['SEQ','ITEM_SEQ','WO'], NULL, NULL, 'policy.LOT_NO_MODE'),
    ('LOT_TRACE_DEPTH', 'INT',  '30',  NULL, 1, 100, 'policy.LOT_TRACE_DEPTH');

INSERT INTO sys_code_grp (grp_cd, grp_nm_key, comp_ext_yn) VALUES
    ('LOT_ORIGIN', 'code.grp.LOT_ORIGIN', 'N'),
    ('LOT_REL_TYPE', 'code.grp.LOT_REL_TYPE', 'N'),
    ('ENTRY_METHOD', 'code.grp.ENTRY_METHOD', 'N');
INSERT INTO sys_code (grp_cd, code, code_nm_key, sort_no) VALUES
    ('LOT_ORIGIN', 'RECEIVED', 'code.LOT_ORIGIN.RECEIVED', 1),
    ('LOT_ORIGIN', 'PRODUCED', 'code.LOT_ORIGIN.PRODUCED', 2),
    ('LOT_ORIGIN', 'SPLIT',    'code.LOT_ORIGIN.SPLIT',    3),
    ('LOT_ORIGIN', 'MERGE',    'code.LOT_ORIGIN.MERGE',    4),
    ('LOT_ORIGIN', 'RELABEL',  'code.LOT_ORIGIN.RELABEL',  5),
    ('LOT_REL_TYPE', 'CONSUME', 'code.LOT_REL_TYPE.CONSUME', 1),
    ('LOT_REL_TYPE', 'SPLIT',   'code.LOT_REL_TYPE.SPLIT',   2),
    ('LOT_REL_TYPE', 'MERGE',   'code.LOT_REL_TYPE.MERGE',   3),
    ('LOT_REL_TYPE', 'RELABEL', 'code.LOT_REL_TYPE.RELABEL', 4),
    ('LOT_REL_TYPE', 'REWORK',  'code.LOT_REL_TYPE.REWORK',  5),
    ('LOT_REL_TYPE', 'REPAIR',  'code.LOT_REL_TYPE.REPAIR',  6),
    ('LOT_REL_TYPE', 'SORT',    'code.LOT_REL_TYPE.SORT',    7),
    ('ENTRY_METHOD', 'KEY',     'code.ENTRY_METHOD.KEY',     1),
    ('ENTRY_METHOD', 'SCAN',    'code.ENTRY_METHOD.SCAN',    2),
    ('ENTRY_METHOD', 'AUTO',    'code.ENTRY_METHOD.AUTO',    3),
    ('ENTRY_METHOD', 'IMPORT',  'code.ENTRY_METHOD.IMPORT',  4),
    ('ENTRY_METHOD', 'CORRECT', 'code.ENTRY_METHOD.CORRECT', 5);

-- ---------------------------------------------------------------------
-- 1. LOT
-- ---------------------------------------------------------------------
CREATE TABLE lot_master (
    lot_id            bigint         GENERATED ALWAYS AS IDENTITY,
    comp_cd           varchar(20)    NOT NULL,
    lot_no            varchar(60)    NOT NULL,
    item_id           bigint         NOT NULL,
    origin            varchar(10)    NOT NULL,
    qty_created       numeric(18,6)  NOT NULL,
    unit_cd           varchar(30)    NOT NULL,
    wo_id             bigint,
    supplier_cust_id  bigint,
    supplier_lot_no   varchar(60),
    occurred_at       timestamptz    NOT NULL,
    hold_yn           char(1)        NOT NULL DEFAULT 'N',
    hold_reason       varchar(500),
    remark            varchar(1000),
    row_ver           integer        NOT NULL DEFAULT 0,
    created_at        timestamptz    NOT NULL DEFAULT now(),
    created_by        varchar(50)    NOT NULL,
    updated_at        timestamptz    NOT NULL DEFAULT now(),
    updated_by        varchar(50)    NOT NULL,
    CONSTRAINT pk_lot_master PRIMARY KEY (lot_id),
    CONSTRAINT fk_lot_master_comp FOREIGN KEY (comp_cd) REFERENCES sys_comp (comp_cd),
    CONSTRAINT fk_lot_master_item FOREIGN KEY (item_id) REFERENCES mst_item (item_id),
    CONSTRAINT fk_lot_master_wo FOREIGN KEY (wo_id) REFERENCES prd_wo (wo_id),
    CONSTRAINT fk_lot_master_supplier FOREIGN KEY (supplier_cust_id) REFERENCES mst_cust (cust_id),
    CONSTRAINT uq_lot_master_no UNIQUE (comp_cd, lot_no),
    -- 라벨·바코드로 오가는 번호: 출력 가능한 ASCII, 공백 없음
    CONSTRAINT ck_lot_master_no CHECK (lot_no ~ '^[\x21-\x7E]{1,60}$'),
    CONSTRAINT ck_lot_master_supplier_no CHECK (supplier_lot_no ~ '^[\x21-\x7E]([\x20-\x7E]*[\x21-\x7E])?$'),
    CONSTRAINT ck_lot_master_origin CHECK (origin IN ('RECEIVED', 'PRODUCED', 'SPLIT', 'MERGE', 'RELABEL')),
    CONSTRAINT ck_lot_master_qty CHECK (qty_created > 0),
    CONSTRAINT ck_lot_master_produced CHECK ((origin = 'PRODUCED') = (wo_id IS NOT NULL)),
    CONSTRAINT ck_lot_master_received CHECK (origin = 'RECEIVED' OR (supplier_cust_id IS NULL AND supplier_lot_no IS NULL)),
    CONSTRAINT ck_lot_master_hold CHECK (hold_yn IN ('Y', 'N') AND (hold_yn = 'N' OR length(btrim(hold_reason)) > 0))
);
COMMENT ON TABLE lot_master IS 'LOT (docs/25 4.5). 만든 양·품목·출처는 고정, 잔량은 계보 원장에서 계산(hb_lot_balance). 재고 위치는 P4';
-- 같은 공급사 라벨(같은 품목)을 두 번 등록하지 않게
CREATE UNIQUE INDEX uq_lot_master_supplier_lot ON lot_master (comp_cd, supplier_cust_id, item_id, supplier_lot_no)
    WHERE supplier_lot_no IS NOT NULL;
CREATE INDEX ix_lot_master_item ON lot_master (comp_cd, item_id, created_at);
CREATE INDEX ix_lot_master_wo ON lot_master (comp_cd, wo_id) WHERE wo_id IS NOT NULL;

CREATE FUNCTION hb_guard_lot_master() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'UPDATE' THEN
        IF (NEW.lot_no, NEW.item_id, NEW.origin, NEW.qty_created, NEW.unit_cd, NEW.occurred_at)
               IS DISTINCT FROM (OLD.lot_no, OLD.item_id, OLD.origin, OLD.qty_created, OLD.unit_cd, OLD.occurred_at)
           OR NEW.wo_id IS DISTINCT FROM OLD.wo_id OR NEW.supplier_cust_id IS DISTINCT FROM OLD.supplier_cust_id
           OR NEW.supplier_lot_no IS DISTINCT FROM OLD.supplier_lot_no THEN
            RAISE EXCEPTION 'lot % keeps its number, item, quantity and origin', OLD.lot_no
                USING ERRCODE = '55000', CONSTRAINT = 'ck_lot_master_fixed';
        END IF;
        RETURN NEW;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM mst_item i WHERE i.item_id = NEW.item_id AND i.comp_cd = NEW.comp_cd AND i.use_yn = 'Y'
                      AND i.unit_cd = NEW.unit_cd) THEN
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
CREATE TRIGGER tg_lot_master_guard BEFORE INSERT OR UPDATE ON lot_master FOR EACH ROW EXECUTE FUNCTION hb_guard_lot_master();
CREATE TRIGGER tg_lot_master_no_delete BEFORE DELETE ON lot_master FOR EACH ROW EXECUTE FUNCTION hb_forbid_delete_master();

-- ---------------------------------------------------------------------
-- 2. 계보 원장
-- ---------------------------------------------------------------------
CREATE TABLE lot_genealogy (
    gen_id          bigint         GENERATED ALWAYS AS IDENTITY,
    comp_cd         varchar(20)    NOT NULL,
    parent_lot_id   bigint         NOT NULL,
    child_lot_id    bigint         NOT NULL,
    rel_type        varchar(10)    NOT NULL,
    qty             numeric(18,6)  NOT NULL,
    unit_cd         varchar(30)    NOT NULL,
    wo_id           bigint,
    wo_proc_id      bigint,
    equip_id        bigint,
    change_id       bigint,
    reason          varchar(500),
    occurred_at     timestamptz    NOT NULL,
    recorded_at     timestamptz    NOT NULL DEFAULT now(),
    entry_method    varchar(10)    NOT NULL,
    recorded_by     varchar(50)    NOT NULL,
    performed_by    bigint,
    reversal_of_id  bigint,
    CONSTRAINT pk_lot_genealogy PRIMARY KEY (gen_id),
    CONSTRAINT fk_lot_genealogy_parent FOREIGN KEY (parent_lot_id) REFERENCES lot_master (lot_id),
    CONSTRAINT fk_lot_genealogy_child FOREIGN KEY (child_lot_id) REFERENCES lot_master (lot_id),
    CONSTRAINT fk_lot_genealogy_wo FOREIGN KEY (wo_id) REFERENCES prd_wo (wo_id),
    CONSTRAINT fk_lot_genealogy_wo_proc FOREIGN KEY (wo_proc_id) REFERENCES prd_wo_proc (wo_proc_id),
    CONSTRAINT fk_lot_genealogy_equip FOREIGN KEY (equip_id) REFERENCES mst_equip (equip_id),
    CONSTRAINT fk_lot_genealogy_worker FOREIGN KEY (performed_by) REFERENCES mst_worker (worker_id),
    CONSTRAINT fk_lot_genealogy_reversal FOREIGN KEY (reversal_of_id) REFERENCES lot_genealogy (gen_id),
    CONSTRAINT ck_lot_genealogy_self CHECK (parent_lot_id <> child_lot_id),
    CONSTRAINT ck_lot_genealogy_type CHECK (rel_type IN ('CONSUME', 'SPLIT', 'MERGE', 'RELABEL', 'REWORK', 'REPAIR', 'SORT')),
    CONSTRAINT ck_lot_genealogy_method CHECK (entry_method IN ('KEY', 'SCAN', 'AUTO', 'IMPORT', 'CORRECT')),
    -- 일반 행은 양수, 역분개는 CORRECT·음수·사유 필수
    CONSTRAINT ck_lot_genealogy_qty CHECK (
        (reversal_of_id IS NULL AND qty > 0 AND entry_method <> 'CORRECT')
        OR (reversal_of_id IS NOT NULL AND qty < 0 AND entry_method = 'CORRECT' AND length(btrim(reason)) > 0))
);
COMMENT ON TABLE lot_genealogy IS 'LOT 계보 원장 (01 3.1, docs/25 4.5) — 부모 LOT 에서 자식 LOT 으로 나간 양(부모 단위). 수정·삭제 없음, 정정은 역분개';
COMMENT ON COLUMN lot_genealogy.performed_by IS '실제 작업자(mst_worker) — recorded_by(입력한 사람·단말)와 다를 수 있다(대리 입력)';
-- 한 행은 한 번만 되돌린다
CREATE UNIQUE INDEX uq_lot_genealogy_reversal ON lot_genealogy (reversal_of_id) WHERE reversal_of_id IS NOT NULL;
CREATE INDEX ix_lot_genealogy_parent ON lot_genealogy (comp_cd, parent_lot_id);
CREATE INDEX ix_lot_genealogy_child ON lot_genealogy (comp_cd, child_lot_id);

-- LOT 잔량 = 만든 양 − 이 LOT 이 부모인 계보의 순수량(역분개 포함)
CREATE FUNCTION hb_lot_balance(p_lot bigint) RETURNS numeric
    LANGUAGE sql STABLE
AS $$
    SELECT m.qty_created - coalesce((SELECT sum(g.qty) FROM lot_genealogy g WHERE g.parent_lot_id = m.lot_id), 0)
      FROM lot_master m WHERE m.lot_id = p_lot
$$;

CREATE FUNCTION hb_guard_lot_genealogy() RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    v_parent lot_master;
    v_child  lot_master;
    v_orig   lot_genealogy;
BEGIN
    -- 부모 LOT 을 잠근다 — 같은 LOT 에서 동시에 나가는 두 건이 같은 잔량을 쓰지 않게
    SELECT * INTO v_parent FROM lot_master WHERE lot_id = NEW.parent_lot_id AND comp_cd = NEW.comp_cd FOR UPDATE;
    SELECT * INTO v_child FROM lot_master WHERE lot_id = NEW.child_lot_id AND comp_cd = NEW.comp_cd;
    IF v_parent.lot_id IS NULL OR v_child.lot_id IS NULL THEN
        RAISE EXCEPTION 'lots % / % are not of company %', NEW.parent_lot_id, NEW.child_lot_id, NEW.comp_cd
            USING ERRCODE = '23503', CONSTRAINT = 'fk_lot_genealogy_lot_comp';
    END IF;
    IF NEW.unit_cd <> v_parent.unit_cd THEN
        RAISE EXCEPTION 'genealogy quantity is in the parent lot unit %', v_parent.unit_cd
            USING ERRCODE = '23514', CONSTRAINT = 'ck_lot_genealogy_unit';
    END IF;
    -- 분할·병합·라벨 바꿈·재작업·수리·선별은 같은 품목끼리. 투입만 다른 품목(자재 → 제품)
    IF NEW.rel_type <> 'CONSUME' AND v_parent.item_id <> v_child.item_id THEN
        RAISE EXCEPTION '% keeps the item', NEW.rel_type USING ERRCODE = '23514', CONSTRAINT = 'ck_lot_genealogy_item';
    END IF;
    IF (NEW.wo_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM prd_wo w WHERE w.wo_id = NEW.wo_id AND w.comp_cd = NEW.comp_cd))
       OR (NEW.wo_proc_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM prd_wo_proc p WHERE p.wo_proc_id = NEW.wo_proc_id
                                                         AND p.comp_cd = NEW.comp_cd AND p.wo_id = NEW.wo_id))
       OR (NEW.equip_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM mst_equip e WHERE e.equip_id = NEW.equip_id AND e.comp_cd = NEW.comp_cd))
       OR (NEW.performed_by IS NOT NULL AND NOT EXISTS (SELECT 1 FROM mst_worker k WHERE k.worker_id = NEW.performed_by
                                                           AND k.comp_cd = NEW.comp_cd)) THEN
        RAISE EXCEPTION 'genealogy context is not of company %', NEW.comp_cd
            USING ERRCODE = '23503', CONSTRAINT = 'fk_lot_genealogy_ctx_comp';
    END IF;
    IF NEW.reversal_of_id IS NULL THEN
        IF v_parent.hold_yn = 'Y' THEN
            RAISE EXCEPTION 'lot % is on hold', v_parent.lot_no USING ERRCODE = '55000', CONSTRAINT = 'ck_lot_genealogy_hold';
        END IF;
        IF hb_lot_balance(v_parent.lot_id) < NEW.qty THEN
            RAISE EXCEPTION 'lot % has only % left', v_parent.lot_no, hb_lot_balance(v_parent.lot_id)
                USING ERRCODE = '23514', CONSTRAINT = 'ck_lot_genealogy_balance';
        END IF;
        RETURN NEW;
    END IF;
    -- 역분개: 같은 회사의 원래 행(역분개 아님)과 같은 연결·유형·단위, 수량은 정확히 반대
    SELECT * INTO v_orig FROM lot_genealogy WHERE gen_id = NEW.reversal_of_id AND comp_cd = NEW.comp_cd;
    IF v_orig.gen_id IS NULL OR v_orig.reversal_of_id IS NOT NULL
       OR (v_orig.parent_lot_id, v_orig.child_lot_id, v_orig.rel_type, v_orig.unit_cd, v_orig.qty)
          IS DISTINCT FROM (NEW.parent_lot_id, NEW.child_lot_id, NEW.rel_type, NEW.unit_cd, -NEW.qty) THEN
        RAISE EXCEPTION 'reversal must mirror genealogy row %', NEW.reversal_of_id
            USING ERRCODE = '23514', CONSTRAINT = 'ck_lot_genealogy_reversal';
    END IF;
    RETURN NEW;
END
$$;
CREATE TRIGGER tg_lot_genealogy_guard BEFORE INSERT ON lot_genealogy FOR EACH ROW EXECUTE FUNCTION hb_guard_lot_genealogy();
CREATE TRIGGER tg_lot_genealogy_ledger BEFORE UPDATE OR DELETE ON lot_genealogy FOR EACH ROW EXECUTE FUNCTION hb_forbid_update_delete();

-- ---------------------------------------------------------------------
-- 3. 감사 · RLS · 권한 · 화면 · 보존
-- ---------------------------------------------------------------------
CREATE TRIGGER tg_audit AFTER INSERT OR UPDATE OR DELETE ON lot_master FOR EACH ROW EXECUTE FUNCTION hb_audit('lot_id', '', '');

ALTER TABLE lot_master ENABLE ROW LEVEL SECURITY;
ALTER TABLE lot_master FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON lot_master USING (comp_cd = hb_current_comp()) WITH CHECK (comp_cd = hb_current_comp());
ALTER TABLE lot_genealogy ENABLE ROW LEVEL SECURITY;
ALTER TABLE lot_genealogy FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON lot_genealogy USING (comp_cd = hb_current_comp()) WITH CHECK (comp_cd = hb_current_comp());
REVOKE DELETE, TRUNCATE ON lot_master FROM ${app_role};
REVOKE UPDATE, DELETE, TRUNCATE ON lot_genealogy FROM ${app_role};

INSERT INTO sys_prog (prog_cd, prog_nm_key, view_path, module_cd, actions, sort_no) VALUES
    ('prd-lot', 'prog.prdLot', 'production/lot-mgt', 'PRD', ARRAY['SEARCH','SAVE']::varchar(20)[], 410);
INSERT INTO sys_retention_table (table_nm, class_cd) VALUES
    ('lot_master', 'PRODUCTION_RECORD'), ('lot_genealogy', 'PRODUCTION_RECORD');
