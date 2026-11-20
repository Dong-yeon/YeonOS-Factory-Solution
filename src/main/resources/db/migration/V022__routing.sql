-- =====================================================================
-- V022 공정·라우팅·파라미터 세트·표준 사이클타임 (P1 5단계 — docs/20)
--
-- - 공정 마스터 mst_proc: 재사용하는 작업 단위(코드·분류·외주 여부)
-- - 라우팅 머리 = 품목 × 공장 × 대체 번호 × 리비전. 상태·기간·불변 규칙은 BOM(V015·V017)과 같다
-- - 단계 = 공정 순번 × 공정 × 작업장(같은 공장 LINE·CELL) × 표준 사이클타임·사이클당 수량·준비 시간
-- - 파라미터 = 단계별 숫자 설정값의 목표·하한·상한(단위는 공학 단위 글자 — kA·ms·°C 는 재고 단위 코드에 없다)
-- - 단계·파라미터는 머리가 DRAFT 일 때만 바뀐다. 확정 라우팅이 쓰는 작업장은 사용 중지·유형 변경 거부
-- =====================================================================

INSERT INTO sys_code_grp (grp_cd, grp_nm_key, comp_ext_yn) VALUES ('PROC_CAT', 'code.grp.PROC_CAT', 'N');
INSERT INTO sys_code (grp_cd, code, code_nm_key, sort_no) VALUES
    ('PROC_CAT', 'PRESS',    'code.PROC_CAT.PRESS',    1),
    ('PROC_CAT', 'MACHINE',  'code.PROC_CAT.MACHINE',  2),
    ('PROC_CAT', 'WELD',     'code.PROC_CAT.WELD',     3),
    ('PROC_CAT', 'ASSEMBLY', 'code.PROC_CAT.ASSEMBLY', 4),
    ('PROC_CAT', 'PAINT',    'code.PROC_CAT.PAINT',    5),
    ('PROC_CAT', 'HEAT',     'code.PROC_CAT.HEAT',     6),
    ('PROC_CAT', 'INSPECT',  'code.PROC_CAT.INSPECT',  7),
    ('PROC_CAT', 'PACK',     'code.PROC_CAT.PACK',     8),
    ('PROC_CAT', 'OTHER',    'code.PROC_CAT.OTHER',    9);

-- ---------------------------------------------------------------------
-- 1. 공정 마스터
-- ---------------------------------------------------------------------
CREATE TABLE mst_proc (
    proc_id     bigint        GENERATED ALWAYS AS IDENTITY,
    comp_cd     varchar(20)   NOT NULL,
    proc_cd     varchar(20)   NOT NULL,
    proc_nm     varchar(100)  NOT NULL,
    proc_cat    varchar(10)   NOT NULL,
    outsrc_yn   char(1)       NOT NULL DEFAULT 'N',
    sort_no     integer       NOT NULL DEFAULT 0,
    use_yn      char(1)       NOT NULL DEFAULT 'Y',
    remark      varchar(500),
    row_ver     integer       NOT NULL DEFAULT 0,
    created_at  timestamptz   NOT NULL DEFAULT now(),
    created_by  varchar(50)   NOT NULL,
    updated_at  timestamptz   NOT NULL DEFAULT now(),
    updated_by  varchar(50)   NOT NULL,
    CONSTRAINT pk_mst_proc PRIMARY KEY (proc_id),
    CONSTRAINT fk_mst_proc_comp FOREIGN KEY (comp_cd) REFERENCES sys_comp (comp_cd),
    CONSTRAINT uq_mst_proc_cd UNIQUE (comp_cd, proc_cd),
    CONSTRAINT ck_mst_proc_cd CHECK (proc_cd ~ '^[A-Z0-9][A-Z0-9_-]{0,19}$'),
    CONSTRAINT ck_mst_proc_nm CHECK (length(btrim(proc_nm)) > 0),
    CONSTRAINT ck_mst_proc_cat CHECK (proc_cat IN ('PRESS', 'MACHINE', 'WELD', 'ASSEMBLY', 'PAINT', 'HEAT', 'INSPECT', 'PACK', 'OTHER')),
    CONSTRAINT ck_mst_proc_outsrc CHECK (outsrc_yn IN ('Y', 'N')),
    CONSTRAINT ck_mst_proc_use_yn CHECK (use_yn IN ('Y', 'N'))
);
COMMENT ON TABLE mst_proc IS '공정 마스터 (ISA-95 Process Segment 축소). 품목의 proc_type(조달 구분)과 다르다';

CREATE FUNCTION hb_guard_proc() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'process cannot be deleted (use use_yn = N)' USING ERRCODE = '55000', CONSTRAINT = 'ck_mst_proc_no_delete';
    END IF;
    -- 외주 여부는 단계의 작업장 규칙(자체 = 작업장 필수)을 바꾸므로, 확정 라우팅이 쓰는 공정은 바꾸지 않는다
    IF TG_OP = 'UPDATE' AND NEW.outsrc_yn <> OLD.outsrc_yn AND EXISTS (
           SELECT 1 FROM mst_routing_step s JOIN mst_routing r ON r.routing_id = s.routing_id
            WHERE s.proc_id = NEW.proc_id AND r.status = 'RELEASED') THEN
        RAISE EXCEPTION 'process % is used by a released routing', NEW.proc_id
            USING ERRCODE = '23514', CONSTRAINT = 'ck_mst_proc_outsrc_in_use';
    END IF;
    RETURN NEW;
END
$$;

-- ---------------------------------------------------------------------
-- 2. 라우팅 머리
-- ---------------------------------------------------------------------
CREATE TABLE mst_routing (
    routing_id     bigint         GENERATED ALWAYS AS IDENTITY,
    comp_cd        varchar(20)    NOT NULL,
    item_id        bigint         NOT NULL,
    plant_cd       varchar(20)    NOT NULL,
    alt_no         smallint       NOT NULL DEFAULT 1,
    rev            varchar(4)     NOT NULL,
    status         varchar(10)    NOT NULL DEFAULT 'DRAFT',
    valid_from     date           NOT NULL,
    valid_to       date,
    change_no      varchar(30),
    change_reason  varchar(500),
    remark         varchar(1000),
    released_at    timestamptz,
    released_by    varchar(50),
    row_ver        integer        NOT NULL DEFAULT 0,
    created_at     timestamptz    NOT NULL DEFAULT now(),
    created_by     varchar(50)    NOT NULL,
    updated_at     timestamptz    NOT NULL DEFAULT now(),
    updated_by     varchar(50)    NOT NULL,
    CONSTRAINT pk_mst_routing PRIMARY KEY (routing_id),
    CONSTRAINT fk_mst_routing_item FOREIGN KEY (item_id) REFERENCES mst_item (item_id),
    -- 복합 키라 다른 회사 공장은 참조할 수 없다
    CONSTRAINT fk_mst_routing_plant FOREIGN KEY (comp_cd, plant_cd) REFERENCES sys_plant (comp_cd, plant_cd),
    CONSTRAINT uq_mst_routing_rev UNIQUE (comp_cd, item_id, plant_cd, alt_no, rev),
    CONSTRAINT ck_mst_routing_alt CHECK (alt_no BETWEEN 1 AND 99),
    CONSTRAINT ck_mst_routing_rev CHECK (rev ~ '^[A-Z0-9]{1,4}$'),
    CONSTRAINT ck_mst_routing_status CHECK (status IN ('DRAFT', 'RELEASED', 'OBSOLETE')),
    CONSTRAINT ck_mst_routing_period CHECK (valid_to IS NULL OR valid_to > valid_from),
    CONSTRAINT ck_mst_routing_released CHECK (status = 'DRAFT' OR released_at IS NOT NULL OR status = 'OBSOLETE'),
    -- 기준일의 유효 라우팅이 품목·공장·대체마다 하나뿐 (BOM ex_mst_bom_period 와 같은 규칙)
    CONSTRAINT ex_mst_routing_period EXCLUDE USING gist (
        comp_cd WITH =, item_id WITH =, plant_cd WITH =, alt_no WITH =, daterange(valid_from, valid_to, '[)') WITH &&
    ) WHERE (status = 'RELEASED')
);
CREATE UNIQUE INDEX uq_mst_routing_draft ON mst_routing (comp_cd, item_id, plant_cd, alt_no) WHERE status = 'DRAFT';
CREATE INDEX ix_mst_routing_item ON mst_routing (comp_cd, item_id, status);
COMMENT ON TABLE mst_routing IS '라우팅 머리 (품목 × 공장 × 대체 × 리비전). RELEASED·OBSOLETE 는 수정 불가 — 새 리비전으로 (docs/20)';

CREATE FUNCTION hb_guard_routing() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'routing cannot be deleted (use OBSOLETE)' USING ERRCODE = '55000', CONSTRAINT = 'ck_mst_routing_no_delete';
    END IF;
    IF NOT hb_item_of_comp(NEW.comp_cd, NEW.item_id) THEN
        RAISE EXCEPTION 'item % does not belong to company %', NEW.item_id, NEW.comp_cd
            USING ERRCODE = '23503', CONSTRAINT = 'fk_mst_routing_item_comp';
    END IF;
    IF TG_OP = 'INSERT' THEN
        IF NEW.status <> 'DRAFT' THEN
            RAISE EXCEPTION 'new routing must be DRAFT' USING ERRCODE = '55000', CONSTRAINT = 'ck_mst_routing_draft';
        END IF;
        RETURN NEW;
    END IF;
    -- 품목·공장·대체 번호는 상태와 관계없이 불변 — 공장이 바뀌면 단계의 작업장(같은 공장 규칙)을 거치지 않는다 (QA 3a #1 교훈)
    IF NEW.comp_cd <> OLD.comp_cd OR NEW.item_id <> OLD.item_id OR NEW.plant_cd <> OLD.plant_cd OR NEW.alt_no <> OLD.alt_no THEN
        RAISE EXCEPTION 'routing % item / plant / alternative cannot be changed', OLD.routing_id
            USING ERRCODE = '55000', CONSTRAINT = 'ck_mst_routing_key_immutable';
    END IF;
    IF OLD.status <> NEW.status AND NOT (
           (OLD.status = 'DRAFT' AND NEW.status IN ('RELEASED', 'OBSOLETE'))
        OR (OLD.status = 'RELEASED' AND NEW.status = 'OBSOLETE')) THEN
        RAISE EXCEPTION 'routing status % -> % is not allowed', OLD.status, NEW.status
            USING ERRCODE = '55000', CONSTRAINT = 'ck_mst_routing_transition';
    END IF;
    IF OLD.status IN ('RELEASED', 'OBSOLETE') AND (
           NEW.rev <> OLD.rev OR NEW.valid_from <> OLD.valid_from
        OR NEW.change_no IS DISTINCT FROM OLD.change_no OR NEW.change_reason IS DISTINCT FROM OLD.change_reason
        OR NEW.remark IS DISTINCT FROM OLD.remark OR NEW.released_at IS DISTINCT FROM OLD.released_at
        OR (OLD.status = 'OBSOLETE' AND NEW.valid_to IS DISTINCT FROM OLD.valid_to)
        OR (OLD.status = 'RELEASED' AND NEW.valid_to IS DISTINCT FROM OLD.valid_to
            AND (NEW.valid_to IS NULL OR (OLD.valid_to IS NOT NULL AND NEW.valid_to > OLD.valid_to)))) THEN
        RAISE EXCEPTION 'released/obsolete routing % cannot be changed (create a new revision)', OLD.routing_id
            USING ERRCODE = '55000', CONSTRAINT = 'ck_mst_routing_immutable';
    END IF;
    RETURN NEW;
END
$$;
CREATE TRIGGER tg_mst_routing_guard BEFORE INSERT OR UPDATE OR DELETE ON mst_routing
    FOR EACH ROW EXECUTE FUNCTION hb_guard_routing();

-- ---------------------------------------------------------------------
-- 3. 공정 단계
-- ---------------------------------------------------------------------
CREATE TABLE mst_routing_step (
    step_id          bigint         GENERATED ALWAYS AS IDENTITY,
    comp_cd          varchar(20)    NOT NULL,
    routing_id       bigint         NOT NULL,
    op_seq           integer        NOT NULL,
    proc_id          bigint         NOT NULL,
    node_id          bigint,
    std_ct_sec       numeric(12,3),
    units_per_cycle  integer        NOT NULL DEFAULT 1,
    setup_min        numeric(8,2)   NOT NULL DEFAULT 0,
    remark           varchar(500),
    row_ver          integer        NOT NULL DEFAULT 0,
    created_at       timestamptz    NOT NULL DEFAULT now(),
    created_by       varchar(50)    NOT NULL,
    updated_at       timestamptz    NOT NULL DEFAULT now(),
    updated_by       varchar(50)    NOT NULL,
    CONSTRAINT pk_mst_routing_step PRIMARY KEY (step_id),
    CONSTRAINT fk_mst_routing_step_routing FOREIGN KEY (routing_id) REFERENCES mst_routing (routing_id),
    CONSTRAINT fk_mst_routing_step_proc FOREIGN KEY (proc_id) REFERENCES mst_proc (proc_id),
    CONSTRAINT fk_mst_routing_step_node FOREIGN KEY (node_id) REFERENCES mst_equip_hier (node_id),
    -- 커밋 시점 검사: 한 번 저장으로 순번 10↔20 을 바꿀 수 있게
    CONSTRAINT uq_mst_routing_step_seq UNIQUE (routing_id, op_seq) DEFERRABLE INITIALLY DEFERRED,
    CONSTRAINT ck_mst_routing_step_seq CHECK (op_seq BETWEEN 1 AND 9999),
    CONSTRAINT ck_mst_routing_step_ct CHECK (std_ct_sec IS NULL OR std_ct_sec > 0),
    CONSTRAINT ck_mst_routing_step_upc CHECK (units_per_cycle BETWEEN 1 AND 999),
    CONSTRAINT ck_mst_routing_step_setup CHECK (setup_min >= 0)
);
CREATE INDEX ix_mst_routing_step_routing ON mst_routing_step (comp_cd, routing_id, op_seq);
CREATE INDEX ix_mst_routing_step_node ON mst_routing_step (node_id) WHERE node_id IS NOT NULL;
CREATE INDEX ix_mst_routing_step_proc ON mst_routing_step (proc_id);
COMMENT ON COLUMN mst_routing_step.std_ct_sec IS '표준 사이클타임(초/사이클). OEE 성능 분모 = std_ct_sec / units_per_cycle';
COMMENT ON COLUMN mst_routing_step.units_per_cycle IS '사이클당 수량(캐비티·동시 가공 수)';

CREATE FUNCTION hb_guard_routing_step() RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    v_row  mst_routing_step;
    v_head mst_routing;
    v_node mst_equip_hier;
BEGIN
    v_row := CASE WHEN TG_OP = 'DELETE' THEN OLD ELSE NEW END;
    SELECT * INTO v_head FROM mst_routing WHERE routing_id = v_row.routing_id;
    IF NOT FOUND OR v_head.comp_cd <> v_row.comp_cd THEN
        RAISE EXCEPTION 'routing % does not belong to company %', v_row.routing_id, v_row.comp_cd
            USING ERRCODE = '23503', CONSTRAINT = 'fk_mst_routing_step_routing_comp';
    END IF;
    IF v_head.status <> 'DRAFT' THEN
        RAISE EXCEPTION 'steps of % routing % cannot be changed (create a new revision)', v_head.status, v_row.routing_id
            USING ERRCODE = '55000', CONSTRAINT = 'ck_mst_routing_step_draft';
    END IF;
    IF TG_OP = 'DELETE' THEN
        RETURN OLD;
    END IF;
    IF TG_OP = 'UPDATE' AND (NEW.routing_id <> OLD.routing_id OR NEW.comp_cd <> OLD.comp_cd) THEN
        RAISE EXCEPTION 'step cannot move to another routing' USING ERRCODE = '55000', CONSTRAINT = 'ck_mst_routing_step_move';
    END IF;
    IF (TG_OP = 'INSERT' OR NEW.proc_id <> OLD.proc_id) AND NOT EXISTS (
           SELECT 1 FROM mst_proc p WHERE p.proc_id = NEW.proc_id AND p.comp_cd = NEW.comp_cd AND p.use_yn = 'Y') THEN
        RAISE EXCEPTION 'process % is not an active process of company %', NEW.proc_id, NEW.comp_cd
            USING ERRCODE = '23503', CONSTRAINT = 'fk_mst_routing_step_proc_comp';
    END IF;
    IF NEW.node_id IS NOT NULL AND (TG_OP = 'INSERT' OR NEW.node_id IS DISTINCT FROM OLD.node_id) THEN
        SELECT * INTO v_node FROM mst_equip_hier WHERE node_id = NEW.node_id;
        IF NOT FOUND OR v_node.comp_cd <> NEW.comp_cd OR v_node.plant_cd <> v_head.plant_cd THEN
            RAISE EXCEPTION 'work center % is not in plant %/%', NEW.node_id, NEW.comp_cd, v_head.plant_cd
                USING ERRCODE = '23503', CONSTRAINT = 'fk_mst_routing_step_node_plant';
        END IF;
        IF v_node.node_type NOT IN ('LINE', 'CELL') OR v_node.use_yn <> 'Y' THEN
            RAISE EXCEPTION 'work center must be an active LINE or CELL' USING ERRCODE = '23514', CONSTRAINT = 'ck_mst_routing_step_node_type';
        END IF;
    END IF;
    RETURN NEW;
END
$$;
CREATE TRIGGER tg_mst_routing_step_guard BEFORE INSERT OR UPDATE OR DELETE ON mst_routing_step
    FOR EACH ROW EXECUTE FUNCTION hb_guard_routing_step();

-- ---------------------------------------------------------------------
-- 4. 파라미터 세트
-- ---------------------------------------------------------------------
CREATE TABLE mst_routing_param (
    param_id    bigint         GENERATED ALWAYS AS IDENTITY,
    comp_cd     varchar(20)    NOT NULL,
    step_id     bigint         NOT NULL,
    param_cd    varchar(30)    NOT NULL,
    param_nm    varchar(100)   NOT NULL,
    unit_nm     varchar(20),
    target_val  numeric(18,6),
    lsl         numeric(18,6),
    usl         numeric(18,6),
    sort_no     integer        NOT NULL DEFAULT 0,
    row_ver     integer        NOT NULL DEFAULT 0,
    created_at  timestamptz    NOT NULL DEFAULT now(),
    created_by  varchar(50)    NOT NULL,
    updated_at  timestamptz    NOT NULL DEFAULT now(),
    updated_by  varchar(50)    NOT NULL,
    CONSTRAINT pk_mst_routing_param PRIMARY KEY (param_id),
    CONSTRAINT fk_mst_routing_param_step FOREIGN KEY (step_id) REFERENCES mst_routing_step (step_id),
    CONSTRAINT uq_mst_routing_param_cd UNIQUE (step_id, param_cd) DEFERRABLE INITIALLY DEFERRED,
    CONSTRAINT ck_mst_routing_param_cd CHECK (param_cd ~ '^[A-Z0-9_]{1,30}$'),
    CONSTRAINT ck_mst_routing_param_nm CHECK (length(btrim(param_nm)) > 0),
    CONSTRAINT ck_mst_routing_param_any CHECK (target_val IS NOT NULL OR lsl IS NOT NULL OR usl IS NOT NULL),
    CONSTRAINT ck_mst_routing_param_range CHECK (
        (lsl IS NULL OR usl IS NULL OR lsl <= usl)
        AND (target_val IS NULL OR ((lsl IS NULL OR lsl <= target_val) AND (usl IS NULL OR target_val <= usl))))
);
CREATE INDEX ix_mst_routing_param_step ON mst_routing_param (comp_cd, step_id, sort_no);
COMMENT ON TABLE mst_routing_param IS '공정 파라미터 세트 (목표·하한·상한). 작업지시가 스냅샷한다(P2) — ISA-88 Master Recipe 축소';
COMMENT ON COLUMN mst_routing_param.unit_nm IS '공학 단위 표시(kA, ms, °C, MPa) — 재고 단위(UNIT 코드)와 달라 글자로 둔다';

CREATE FUNCTION hb_guard_routing_param() RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    v_row  mst_routing_param;
    v_comp varchar;
    v_st   varchar;
BEGIN
    v_row := CASE WHEN TG_OP = 'DELETE' THEN OLD ELSE NEW END;
    SELECT s.comp_cd, r.status INTO v_comp, v_st
      FROM mst_routing_step s JOIN mst_routing r ON r.routing_id = s.routing_id WHERE s.step_id = v_row.step_id;
    IF NOT FOUND OR v_comp <> v_row.comp_cd THEN
        RAISE EXCEPTION 'step % does not belong to company %', v_row.step_id, v_row.comp_cd
            USING ERRCODE = '23503', CONSTRAINT = 'fk_mst_routing_param_step_comp';
    END IF;
    IF v_st <> 'DRAFT' THEN
        RAISE EXCEPTION 'parameters of % routing cannot be changed', v_st
            USING ERRCODE = '55000', CONSTRAINT = 'ck_mst_routing_param_draft';
    END IF;
    IF TG_OP = 'DELETE' THEN
        RETURN OLD;
    END IF;
    IF TG_OP = 'UPDATE' AND (NEW.step_id <> OLD.step_id OR NEW.comp_cd <> OLD.comp_cd) THEN
        RAISE EXCEPTION 'parameter cannot move to another step' USING ERRCODE = '55000', CONSTRAINT = 'ck_mst_routing_param_move';
    END IF;
    RETURN NEW;
END
$$;
CREATE TRIGGER tg_mst_routing_param_guard BEFORE INSERT OR UPDATE OR DELETE ON mst_routing_param
    FOR EACH ROW EXECUTE FUNCTION hb_guard_routing_param();

-- 공정 트리거는 단계 테이블이 생긴 뒤에
CREATE TRIGGER tg_mst_proc_guard BEFORE INSERT OR UPDATE OR DELETE ON mst_proc
    FOR EACH ROW EXECUTE FUNCTION hb_guard_proc();

-- 장비 계층: 확정 라우팅이 쓰는 작업장은 사용 중지·LINE/CELL 밖 유형 변경 거부 (V021 설비 규칙과 같은 취지)
CREATE FUNCTION hb_guard_equip_hier_routing() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF ((NEW.use_yn = 'N' AND OLD.use_yn = 'Y') OR (NEW.node_type <> OLD.node_type AND NEW.node_type NOT IN ('LINE', 'CELL')))
       AND EXISTS (SELECT 1 FROM mst_routing_step s JOIN mst_routing r ON r.routing_id = s.routing_id
                    WHERE s.node_id = NEW.node_id AND r.status = 'RELEASED') THEN
        RAISE EXCEPTION 'node % is a work center of a released routing', NEW.node_id
            USING ERRCODE = '23514', CONSTRAINT = 'ck_mst_equip_hier_routing_use';
    END IF;
    RETURN NEW;
END
$$;
CREATE TRIGGER tg_mst_equip_hier_routing BEFORE UPDATE OF use_yn, node_type ON mst_equip_hier
    FOR EACH ROW EXECUTE FUNCTION hb_guard_equip_hier_routing();

-- ---------------------------------------------------------------------
-- 5. 감사 · RLS · 권한 · 화면 · 보존
-- ---------------------------------------------------------------------
CREATE TRIGGER tg_audit AFTER INSERT OR UPDATE OR DELETE ON mst_proc FOR EACH ROW EXECUTE FUNCTION hb_audit('proc_id', '', '');
CREATE TRIGGER tg_audit AFTER INSERT OR UPDATE OR DELETE ON mst_routing FOR EACH ROW EXECUTE FUNCTION hb_audit('routing_id', '', '');
CREATE TRIGGER tg_audit AFTER INSERT OR UPDATE OR DELETE ON mst_routing_step FOR EACH ROW EXECUTE FUNCTION hb_audit('step_id', '', '');
CREATE TRIGGER tg_audit AFTER INSERT OR UPDATE OR DELETE ON mst_routing_param FOR EACH ROW EXECUTE FUNCTION hb_audit('param_id', '', '');

ALTER TABLE mst_proc ENABLE ROW LEVEL SECURITY;
ALTER TABLE mst_proc FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON mst_proc USING (comp_cd = hb_current_comp()) WITH CHECK (comp_cd = hb_current_comp());
ALTER TABLE mst_routing ENABLE ROW LEVEL SECURITY;
ALTER TABLE mst_routing FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON mst_routing USING (comp_cd = hb_current_comp()) WITH CHECK (comp_cd = hb_current_comp());
ALTER TABLE mst_routing_step ENABLE ROW LEVEL SECURITY;
ALTER TABLE mst_routing_step FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON mst_routing_step USING (comp_cd = hb_current_comp()) WITH CHECK (comp_cd = hb_current_comp());
ALTER TABLE mst_routing_param ENABLE ROW LEVEL SECURITY;
ALTER TABLE mst_routing_param FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON mst_routing_param USING (comp_cd = hb_current_comp()) WITH CHECK (comp_cd = hb_current_comp());

-- 공정·머리는 삭제 대신 사용 중지·폐기. 작성 중 라우팅의 단계·파라미터만 지울 수 있다(트리거가 상태 확인)
REVOKE DELETE, TRUNCATE ON mst_proc, mst_routing FROM ${app_role};
REVOKE TRUNCATE ON mst_routing_step, mst_routing_param FROM ${app_role};

INSERT INTO sys_prog (prog_cd, prog_nm_key, view_path, module_cd, actions, sort_no) VALUES
    ('mst-proc',    'prog.mstProc',    'master/proc-mgt',    'MST', ARRAY['SEARCH','SAVE']::varchar(20)[], 140),
    ('mst-routing', 'prog.mstRouting', 'master/routing-mgt', 'MST', ARRAY['SEARCH','SAVE','APPROVE','DELETE','EXCEL']::varchar(20)[], 141);
INSERT INTO sys_retention_table (table_nm, class_cd) VALUES
    ('mst_proc', 'MASTER'), ('mst_routing', 'MASTER'), ('mst_routing_step', 'MASTER'), ('mst_routing_param', 'MASTER');
