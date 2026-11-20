-- =====================================================================
-- V033 작업지시 (P2 3단계 — docs/25 4.4, 결정 D1)
--
-- - prd_wo: 지시(채번 PRD_WO, 품목·공장·수량·계획일·라우팅/BOM 리비전·수주 행). 상태 전이는 트리거 표 하나
-- - 스냅샷 3종(발행 때 한 번): prd_wo_proc(라우팅 단계 = 디스패치 단위, D1) · prd_wo_param(파라미터) · prd_wo_bom(1단 소요량)
--   스냅샷 행은 **발행하는 그 트랜잭션에서만** 넣을 수 있다(released_at = now() — now() 는 트랜잭션 시작 시각)
-- =====================================================================

INSERT INTO sys_code_grp (grp_cd, grp_nm_key, comp_ext_yn) VALUES
    ('WO_STATUS', 'code.grp.WO_STATUS', 'N'),
    ('WO_PROC_STATUS', 'code.grp.WO_PROC_STATUS', 'N');
INSERT INTO sys_code (grp_cd, code, code_nm_key, sort_no) VALUES
    ('WO_STATUS', 'PLANNED',     'code.WO_STATUS.PLANNED',     1),
    ('WO_STATUS', 'RELEASED',    'code.WO_STATUS.RELEASED',    2),
    ('WO_STATUS', 'IN_PROGRESS', 'code.WO_STATUS.IN_PROGRESS', 3),
    ('WO_STATUS', 'DONE',        'code.WO_STATUS.DONE',        4),
    ('WO_STATUS', 'CLOSED',      'code.WO_STATUS.CLOSED',      5),
    ('WO_STATUS', 'CANCELLED',   'code.WO_STATUS.CANCELLED',   6),
    ('WO_PROC_STATUS', 'WAIT',  'code.WO_PROC_STATUS.WAIT',  1),
    ('WO_PROC_STATUS', 'READY', 'code.WO_PROC_STATUS.READY', 2),
    ('WO_PROC_STATUS', 'RUN',   'code.WO_PROC_STATUS.RUN',   3),
    ('WO_PROC_STATUS', 'PAUSE', 'code.WO_PROC_STATUS.PAUSE', 4),
    ('WO_PROC_STATUS', 'DONE',  'code.WO_PROC_STATUS.DONE',  5);

-- 생산 기록(지시·실적·계보): 품질 추적의 근거 — 지우지 않는다(정리 정책은 P6, 품질 기록 보존 기간 이상)
INSERT INTO sys_retention_class (class_cd, basis, default_days, min_days, purge_allowed_yn, desc_key) VALUES
    ('PRODUCTION_RECORD', 'KEEP', NULL, NULL, 'N', 'retention.PRODUCTION_RECORD');

-- ---------------------------------------------------------------------
-- 1. 지시
-- ---------------------------------------------------------------------
CREATE TABLE prd_wo (
    wo_id          bigint         GENERATED ALWAYS AS IDENTITY,
    comp_cd        varchar(20)    NOT NULL,
    wo_no          varchar(50)    NOT NULL,
    plant_cd       varchar(20)    NOT NULL,
    item_id        bigint         NOT NULL,
    plan_qty       numeric(18,6)  NOT NULL,
    unit_cd        varchar(30)    NOT NULL,
    plan_start     date           NOT NULL,
    plan_end       date           NOT NULL,
    routing_id     bigint         NOT NULL,
    bom_id         bigint,
    order_line_id  bigint,
    priority       smallint       NOT NULL DEFAULT 5,
    status         varchar(12)    NOT NULL DEFAULT 'PLANNED',
    released_at    timestamptz,
    released_by    varchar(50),
    cancel_reason  varchar(500),
    remark         varchar(1000),
    row_ver        integer        NOT NULL DEFAULT 0,
    created_at     timestamptz    NOT NULL DEFAULT now(),
    created_by     varchar(50)    NOT NULL,
    updated_at     timestamptz    NOT NULL DEFAULT now(),
    updated_by     varchar(50)    NOT NULL,
    CONSTRAINT pk_prd_wo PRIMARY KEY (wo_id),
    CONSTRAINT fk_prd_wo_plant FOREIGN KEY (comp_cd, plant_cd) REFERENCES sys_plant (comp_cd, plant_cd),
    CONSTRAINT fk_prd_wo_item FOREIGN KEY (item_id) REFERENCES mst_item (item_id),
    CONSTRAINT fk_prd_wo_routing FOREIGN KEY (routing_id) REFERENCES mst_routing (routing_id),
    CONSTRAINT fk_prd_wo_bom FOREIGN KEY (bom_id) REFERENCES mst_bom (bom_id),
    CONSTRAINT fk_prd_wo_order_line FOREIGN KEY (order_line_id) REFERENCES sal_order_line (line_id),
    CONSTRAINT uq_prd_wo_no UNIQUE (comp_cd, wo_no),
    CONSTRAINT ck_prd_wo_qty CHECK (plan_qty > 0),
    CONSTRAINT ck_prd_wo_plan CHECK (plan_start <= plan_end AND plan_start >= DATE '2000-01-01' AND plan_end <= DATE '2999-12-31'),
    CONSTRAINT ck_prd_wo_priority CHECK (priority BETWEEN 1 AND 9),
    CONSTRAINT ck_prd_wo_status CHECK (status IN ('PLANNED', 'RELEASED', 'IN_PROGRESS', 'DONE', 'CLOSED', 'CANCELLED')),
    CONSTRAINT ck_prd_wo_cancel CHECK ((status = 'CANCELLED') = (cancel_reason IS NOT NULL AND length(btrim(cancel_reason)) > 0)),
    CONSTRAINT ck_prd_wo_released CHECK ((status = 'PLANNED' OR (status = 'CANCELLED' AND released_at IS NULL)) = (released_at IS NULL))
);
COMMENT ON TABLE prd_wo IS '작업지시 (docs/25 4.4). 발행 때 라우팅·파라미터·BOM 스냅샷 — 그 뒤 기준정보 변경과 무관';
CREATE INDEX ix_prd_wo_plan ON prd_wo (comp_cd, plan_start, wo_id);
CREATE INDEX ix_prd_wo_order_line ON prd_wo (comp_cd, order_line_id) WHERE order_line_id IS NOT NULL;

-- 지시가 가리키는 기준정보가 같은 회사·같은 품목·같은 공장인지(만들 때·발행 전 변경 때)
CREATE FUNCTION hb_check_wo_refs(p prd_wo) RETURNS void
    LANGUAGE plpgsql STABLE
AS $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM mst_routing r WHERE r.routing_id = p.routing_id AND r.comp_cd = p.comp_cd
                      AND r.item_id = p.item_id AND r.plant_cd = p.plant_cd AND r.status = 'RELEASED') THEN
        RAISE EXCEPTION 'routing % is not a released routing of the item and plant', p.routing_id
            USING ERRCODE = '23503', CONSTRAINT = 'fk_prd_wo_routing_item';
    END IF;
    IF p.bom_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM mst_bom b WHERE b.bom_id = p.bom_id AND b.comp_cd = p.comp_cd
                      AND b.item_id = p.item_id AND b.status = 'RELEASED') THEN
        RAISE EXCEPTION 'bom % is not a released bom of the item', p.bom_id
            USING ERRCODE = '23503', CONSTRAINT = 'fk_prd_wo_bom_item';
    END IF;
    IF p.order_line_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM sal_order_line l WHERE l.line_id = p.order_line_id
                      AND l.comp_cd = p.comp_cd AND l.item_id = p.item_id AND l.status = 'OPEN') THEN
        RAISE EXCEPTION 'order line % is not an open line of the item', p.order_line_id
            USING ERRCODE = '23503', CONSTRAINT = 'fk_prd_wo_order_line_item';
    END IF;
END
$$;

CREATE FUNCTION hb_guard_prd_wo() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'INSERT' THEN
        IF NEW.status <> 'PLANNED' THEN
            RAISE EXCEPTION 'a new work order starts planned' USING ERRCODE = '23514', CONSTRAINT = 'ck_prd_wo_status_new';
        END IF;
        IF NOT EXISTS (SELECT 1 FROM mst_item i WHERE i.item_id = NEW.item_id AND i.comp_cd = NEW.comp_cd AND i.use_yn = 'Y') THEN
            RAISE EXCEPTION 'item % is not an active item of company %', NEW.item_id, NEW.comp_cd
                USING ERRCODE = '23503', CONSTRAINT = 'fk_prd_wo_item_comp';
        END IF;
        PERFORM hb_check_wo_refs(NEW);
        RETURN NEW;
    END IF;
    IF NEW.wo_no <> OLD.wo_no OR NEW.plant_cd <> OLD.plant_cd OR NEW.item_id <> OLD.item_id OR NEW.unit_cd <> OLD.unit_cd THEN
        RAISE EXCEPTION 'work order number, plant and item are fixed' USING ERRCODE = '55000', CONSTRAINT = 'ck_prd_wo_fixed';
    END IF;
    IF OLD.status IN ('CANCELLED', 'CLOSED') THEN
        RAISE EXCEPTION 'work order % is closed', OLD.wo_no USING ERRCODE = '55000', CONSTRAINT = 'ck_prd_wo_closed';
    END IF;
    IF NEW.status <> OLD.status AND NOT (
           (OLD.status = 'PLANNED' AND NEW.status IN ('RELEASED', 'CANCELLED'))
        OR (OLD.status = 'RELEASED' AND NEW.status IN ('IN_PROGRESS', 'CANCELLED'))
        OR (OLD.status = 'IN_PROGRESS' AND NEW.status = 'DONE')
        OR (OLD.status = 'DONE' AND NEW.status = 'CLOSED')) THEN
        RAISE EXCEPTION 'work order cannot go from % to %', OLD.status, NEW.status
            USING ERRCODE = '55000', CONSTRAINT = 'ck_prd_wo_transition';
    END IF;
    -- 발행 뒤에는 수량·기준정보·발행 기록이 고정(스냅샷이 그 값에서 나왔다). 일정·우선순위·비고만
    IF OLD.status <> 'PLANNED' AND (NEW.plan_qty <> OLD.plan_qty OR NEW.routing_id <> OLD.routing_id
           OR NEW.bom_id IS DISTINCT FROM OLD.bom_id OR NEW.order_line_id IS DISTINCT FROM OLD.order_line_id
           OR NEW.released_at IS DISTINCT FROM OLD.released_at OR NEW.released_by IS DISTINCT FROM OLD.released_by) THEN
        RAISE EXCEPTION 'released work order % keeps its quantity and references', OLD.wo_no
            USING ERRCODE = '55000', CONSTRAINT = 'ck_prd_wo_released_fixed';
    END IF;
    IF OLD.status = 'PLANNED' AND (NEW.routing_id <> OLD.routing_id OR NEW.bom_id IS DISTINCT FROM OLD.bom_id
           OR NEW.order_line_id IS DISTINCT FROM OLD.order_line_id) THEN
        PERFORM hb_check_wo_refs(NEW);
    END IF;
    RETURN NEW;
END
$$;
CREATE TRIGGER tg_prd_wo_guard BEFORE INSERT OR UPDATE ON prd_wo FOR EACH ROW EXECUTE FUNCTION hb_guard_prd_wo();
CREATE TRIGGER tg_prd_wo_no_delete BEFORE DELETE ON prd_wo FOR EACH ROW EXECUTE FUNCTION hb_forbid_delete_master();

-- ---------------------------------------------------------------------
-- 2. 스냅샷
-- ---------------------------------------------------------------------
CREATE TABLE prd_wo_proc (
    wo_proc_id       bigint         GENERATED ALWAYS AS IDENTITY,
    comp_cd          varchar(20)    NOT NULL,
    wo_id            bigint         NOT NULL,
    op_seq           integer        NOT NULL,
    proc_id          bigint         NOT NULL,
    proc_cd          varchar(20)    NOT NULL,
    proc_nm          varchar(100)   NOT NULL,
    node_id          bigint,
    std_ct_sec       numeric(12,3),
    units_per_cycle  integer        NOT NULL,
    setup_min        numeric(8,2)   NOT NULL,
    src_step_id      bigint         NOT NULL,
    status           varchar(10)    NOT NULL DEFAULT 'WAIT',
    equip_id         bigint,
    row_ver          integer        NOT NULL DEFAULT 0,
    created_at       timestamptz    NOT NULL DEFAULT now(),
    created_by       varchar(50)    NOT NULL,
    updated_at       timestamptz    NOT NULL DEFAULT now(),
    updated_by       varchar(50)    NOT NULL,
    CONSTRAINT pk_prd_wo_proc PRIMARY KEY (wo_proc_id),
    CONSTRAINT fk_prd_wo_proc_wo FOREIGN KEY (wo_id) REFERENCES prd_wo (wo_id),
    CONSTRAINT fk_prd_wo_proc_proc FOREIGN KEY (proc_id) REFERENCES mst_proc (proc_id),
    CONSTRAINT fk_prd_wo_proc_node FOREIGN KEY (node_id) REFERENCES mst_equip_hier (node_id),
    CONSTRAINT fk_prd_wo_proc_equip FOREIGN KEY (equip_id) REFERENCES mst_equip (equip_id),
    CONSTRAINT uq_prd_wo_proc UNIQUE (comp_cd, wo_id, op_seq),
    CONSTRAINT ck_prd_wo_proc_status CHECK (status IN ('WAIT', 'READY', 'RUN', 'PAUSE', 'DONE'))
);
COMMENT ON TABLE prd_wo_proc IS '지시 공정(라우팅 단계 스냅샷) = 디스패치 단위(D1). 상태·배정 설비만 바뀐다';

CREATE TABLE prd_wo_param (
    wo_param_id  bigint         GENERATED ALWAYS AS IDENTITY,
    comp_cd      varchar(20)    NOT NULL,
    wo_proc_id   bigint         NOT NULL,
    param_cd     varchar(30)    NOT NULL,
    param_nm     varchar(100)   NOT NULL,
    unit_nm      varchar(20),
    target_val   numeric(18,6),
    lsl          numeric(18,6),
    usl          numeric(18,6),
    sort_no      integer        NOT NULL,
    CONSTRAINT pk_prd_wo_param PRIMARY KEY (wo_param_id),
    CONSTRAINT fk_prd_wo_param_proc FOREIGN KEY (wo_proc_id) REFERENCES prd_wo_proc (wo_proc_id),
    CONSTRAINT uq_prd_wo_param UNIQUE (comp_cd, wo_proc_id, param_cd)
);
COMMENT ON TABLE prd_wo_param IS '지시 공정 파라미터(스냅샷) — 추가만';

CREATE TABLE prd_wo_bom (
    wo_bom_id     bigint         GENERATED ALWAYS AS IDENTITY,
    comp_cd       varchar(20)    NOT NULL,
    wo_id         bigint         NOT NULL,
    seq           integer        NOT NULL,
    item_id       bigint         NOT NULL,
    line_type     varchar(10)    NOT NULL,
    req_qty       numeric(18,6)  NOT NULL,
    unit_cd       varchar(30)    NOT NULL,
    op_seq        integer,
    issue_method  varchar(10),
    alt_grp       varchar(10),
    alt_priority  smallint,
    alt_pct       numeric(5,2),
    src_bom_id    bigint         NOT NULL,
    src_line_id   bigint         NOT NULL,
    src_qty       numeric(18,6)  NOT NULL,
    src_unit_cd   varchar(30)    NOT NULL,
    scrap_pct     numeric(5,2)   NOT NULL,
    phantom_path  varchar(500),
    CONSTRAINT pk_prd_wo_bom PRIMARY KEY (wo_bom_id),
    CONSTRAINT fk_prd_wo_bom_wo FOREIGN KEY (wo_id) REFERENCES prd_wo (wo_id),
    CONSTRAINT fk_prd_wo_bom_item FOREIGN KEY (item_id) REFERENCES mst_item (item_id),
    CONSTRAINT uq_prd_wo_bom UNIQUE (comp_cd, wo_id, seq),
    CONSTRAINT ck_prd_wo_bom_qty CHECK (req_qty > 0),
    CONSTRAINT ck_prd_wo_bom_type CHECK (line_type IN ('NORMAL', 'BYPRODUCT'))
);
COMMENT ON TABLE prd_wo_bom IS '지시 자재 소요(BOM 1단 스냅샷, 가상 조립은 펼침) — 자품목 기본 단위, 추가만';

-- 스냅샷은 같은 회사의 지시를 **발행하는 트랜잭션에서만** 넣는다(released_at = now() = 트랜잭션 시작 시각)
CREATE FUNCTION hb_guard_wo_snapshot() RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    v_wo bigint;
BEGIN
    IF TG_TABLE_NAME = 'prd_wo_param' THEN
        SELECT p.wo_id INTO v_wo FROM prd_wo_proc p WHERE p.wo_proc_id = NEW.wo_proc_id AND p.comp_cd = NEW.comp_cd;
    ELSE
        v_wo := NEW.wo_id;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM prd_wo w WHERE w.wo_id = v_wo AND w.comp_cd = NEW.comp_cd
                      AND w.status = 'RELEASED' AND w.released_at = now()) THEN
        RAISE EXCEPTION 'work order snapshot rows are written only when releasing (wo %)', v_wo
            USING ERRCODE = '55000', CONSTRAINT = 'ck_prd_wo_snapshot_release';
    END IF;
    RETURN NEW;
END
$$;
CREATE TRIGGER tg_prd_wo_proc_snapshot BEFORE INSERT ON prd_wo_proc FOR EACH ROW EXECUTE FUNCTION hb_guard_wo_snapshot();
CREATE TRIGGER tg_prd_wo_param_snapshot BEFORE INSERT ON prd_wo_param FOR EACH ROW EXECUTE FUNCTION hb_guard_wo_snapshot();
CREATE TRIGGER tg_prd_wo_bom_snapshot BEFORE INSERT ON prd_wo_bom FOR EACH ROW EXECUTE FUNCTION hb_guard_wo_snapshot();
CREATE TRIGGER tg_prd_wo_param_append_only BEFORE UPDATE OR DELETE ON prd_wo_param FOR EACH ROW EXECUTE FUNCTION hb_forbid_update_delete();
CREATE TRIGGER tg_prd_wo_bom_append_only BEFORE UPDATE OR DELETE ON prd_wo_bom FOR EACH ROW EXECUTE FUNCTION hb_forbid_update_delete();

-- 지시 공정: 스냅샷 칸은 고정, 상태·배정 설비만. 설비는 같은 회사·지시 공장의 사용 중 설비
CREATE FUNCTION hb_guard_prd_wo_proc() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF (NEW.wo_id, NEW.op_seq, NEW.proc_id, NEW.proc_cd, NEW.proc_nm, NEW.src_step_id, NEW.units_per_cycle, NEW.setup_min)
           IS DISTINCT FROM (OLD.wo_id, OLD.op_seq, OLD.proc_id, OLD.proc_cd, OLD.proc_nm, OLD.src_step_id, OLD.units_per_cycle, OLD.setup_min)
       OR NEW.node_id IS DISTINCT FROM OLD.node_id OR NEW.std_ct_sec IS DISTINCT FROM OLD.std_ct_sec THEN
        RAISE EXCEPTION 'work order process snapshot is fixed' USING ERRCODE = '55000', CONSTRAINT = 'ck_prd_wo_proc_fixed';
    END IF;
    IF NEW.equip_id IS NOT NULL AND NEW.equip_id IS DISTINCT FROM OLD.equip_id
       AND NOT EXISTS (SELECT 1 FROM mst_equip e JOIN prd_wo w ON w.wo_id = NEW.wo_id
                        WHERE e.equip_id = NEW.equip_id AND e.comp_cd = NEW.comp_cd AND e.plant_cd = w.plant_cd AND e.use_yn = 'Y') THEN
        RAISE EXCEPTION 'equipment % is not an active equipment of the work order plant', NEW.equip_id
            USING ERRCODE = '23503', CONSTRAINT = 'fk_prd_wo_proc_equip_plant';
    END IF;
    RETURN NEW;
END
$$;
CREATE TRIGGER tg_prd_wo_proc_guard BEFORE UPDATE ON prd_wo_proc FOR EACH ROW EXECUTE FUNCTION hb_guard_prd_wo_proc();
CREATE TRIGGER tg_prd_wo_proc_no_delete BEFORE DELETE ON prd_wo_proc FOR EACH ROW EXECUTE FUNCTION hb_forbid_delete_master();

-- ---------------------------------------------------------------------
-- 3. 감사 · RLS · 권한 · 화면 · 보존
-- ---------------------------------------------------------------------
CREATE TRIGGER tg_audit AFTER INSERT OR UPDATE OR DELETE ON prd_wo FOR EACH ROW EXECUTE FUNCTION hb_audit('wo_id', '', '');
CREATE TRIGGER tg_audit AFTER INSERT OR UPDATE OR DELETE ON prd_wo_proc FOR EACH ROW EXECUTE FUNCTION hb_audit('wo_proc_id', '', '');

ALTER TABLE prd_wo ENABLE ROW LEVEL SECURITY;
ALTER TABLE prd_wo FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON prd_wo USING (comp_cd = hb_current_comp()) WITH CHECK (comp_cd = hb_current_comp());
ALTER TABLE prd_wo_proc ENABLE ROW LEVEL SECURITY;
ALTER TABLE prd_wo_proc FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON prd_wo_proc USING (comp_cd = hb_current_comp()) WITH CHECK (comp_cd = hb_current_comp());
ALTER TABLE prd_wo_param ENABLE ROW LEVEL SECURITY;
ALTER TABLE prd_wo_param FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON prd_wo_param USING (comp_cd = hb_current_comp()) WITH CHECK (comp_cd = hb_current_comp());
ALTER TABLE prd_wo_bom ENABLE ROW LEVEL SECURITY;
ALTER TABLE prd_wo_bom FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON prd_wo_bom USING (comp_cd = hb_current_comp()) WITH CHECK (comp_cd = hb_current_comp());
REVOKE DELETE, TRUNCATE ON prd_wo, prd_wo_proc FROM ${app_role};
REVOKE UPDATE, DELETE, TRUNCATE ON prd_wo_param, prd_wo_bom FROM ${app_role};

INSERT INTO sys_prog (prog_cd, prog_nm_key, view_path, module_cd, actions, sort_no) VALUES
    ('prd-wo', 'prog.prdWo', 'production/wo-mgt', 'PRD', ARRAY['SEARCH','SAVE']::varchar(20)[], 400);
INSERT INTO sys_retention_table (table_nm, class_cd) VALUES
    ('prd_wo', 'PRODUCTION_RECORD'), ('prd_wo_proc', 'PRODUCTION_RECORD'),
    ('prd_wo_param', 'PRODUCTION_RECORD'), ('prd_wo_bom', 'PRODUCTION_RECORD');
