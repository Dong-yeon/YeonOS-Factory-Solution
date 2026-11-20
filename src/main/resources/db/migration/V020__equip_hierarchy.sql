-- =====================================================================
-- V020 장비 계층·창고/로케이션 (P1 4a — docs/19 2·3장, ISA-95 장비 계층)
--
-- 공장(sys_plant) 아래 한 그루 트리: 구역 AREA › 라인 LINE › 셀 CELL, 창고 WH › 로케이션 LOC(중첩).
-- 부모 규칙·순환·깊이·같은 회사·같은 공장은 트리거가 최종 판정(직접 SQL 포함). 삭제 없음 = 사용 중지.
-- =====================================================================

INSERT INTO sys_code_grp (grp_cd, grp_nm_key, comp_ext_yn) VALUES ('EQUIP_NODE_TYPE', 'code.grp.EQUIP_NODE_TYPE', 'N');
INSERT INTO sys_code (grp_cd, code, code_nm_key, sort_no) VALUES
    ('EQUIP_NODE_TYPE', 'AREA', 'code.EQUIP_NODE_TYPE.AREA', 1),
    ('EQUIP_NODE_TYPE', 'LINE', 'code.EQUIP_NODE_TYPE.LINE', 2),
    ('EQUIP_NODE_TYPE', 'CELL', 'code.EQUIP_NODE_TYPE.CELL', 3),
    ('EQUIP_NODE_TYPE', 'WH',   'code.EQUIP_NODE_TYPE.WH',   4),
    ('EQUIP_NODE_TYPE', 'LOC',  'code.EQUIP_NODE_TYPE.LOC',  5);

CREATE TABLE mst_equip_hier (
    node_id     bigint        GENERATED ALWAYS AS IDENTITY,
    comp_cd     varchar(20)   NOT NULL,
    plant_cd    varchar(20)   NOT NULL,
    parent_id   bigint,
    node_type   varchar(10)   NOT NULL,
    node_cd     varchar(30)   NOT NULL,
    node_nm     varchar(100)  NOT NULL,
    sort_no     integer       NOT NULL DEFAULT 0,
    use_yn      char(1)       NOT NULL DEFAULT 'Y',
    remark      varchar(500),
    row_ver     integer       NOT NULL DEFAULT 0,
    created_at  timestamptz   NOT NULL DEFAULT now(),
    created_by  varchar(50)   NOT NULL,
    updated_at  timestamptz   NOT NULL DEFAULT now(),
    updated_by  varchar(50)   NOT NULL,
    CONSTRAINT pk_mst_equip_hier PRIMARY KEY (node_id),
    -- 복합 FK: 공장은 회사별 테이블이라 회사 코드까지 맞아야 한다(다른 회사 공장 불가)
    CONSTRAINT fk_mst_equip_hier_plant FOREIGN KEY (comp_cd, plant_cd) REFERENCES sys_plant (comp_cd, plant_cd),
    CONSTRAINT fk_mst_equip_hier_parent FOREIGN KEY (parent_id) REFERENCES mst_equip_hier (node_id),
    CONSTRAINT uq_mst_equip_hier_cd UNIQUE (comp_cd, plant_cd, node_cd),
    CONSTRAINT ck_mst_equip_hier_type CHECK (node_type IN ('AREA', 'LINE', 'CELL', 'WH', 'LOC')),
    CONSTRAINT ck_mst_equip_hier_cd CHECK (node_cd ~ '^[A-Z0-9][A-Z0-9._-]{0,29}$'),
    CONSTRAINT ck_mst_equip_hier_nm CHECK (length(btrim(node_nm)) > 0),
    CONSTRAINT ck_mst_equip_hier_self CHECK (parent_id IS NULL OR parent_id <> node_id),
    CONSTRAINT ck_mst_equip_hier_use_yn CHECK (use_yn IN ('Y', 'N'))
);
CREATE INDEX ix_mst_equip_hier_parent ON mst_equip_hier (comp_cd, parent_id);
COMMENT ON TABLE mst_equip_hier IS '장비 계층·창고 트리 (ISA-95: AREA·LINE·CELL / 저장 구역 WH·저장 단위 LOC). Enterprise = 회사, Site = 공장';

-- 부모 규칙: 공장 바로 아래(AREA·WH), AREA → LINE·WH, LINE → CELL, WH → LOC, LOC → LOC
CREATE FUNCTION hb_equip_parent_ok(p_parent_type varchar, p_type varchar) RETURNS boolean
    LANGUAGE sql IMMUTABLE
AS $$
    SELECT CASE
        WHEN p_parent_type IS NULL THEN p_type IN ('AREA', 'WH')
        WHEN p_parent_type = 'AREA' THEN p_type IN ('LINE', 'WH')
        WHEN p_parent_type = 'LINE' THEN p_type = 'CELL'
        WHEN p_parent_type = 'WH'   THEN p_type = 'LOC'
        WHEN p_parent_type = 'LOC'  THEN p_type = 'LOC'
        ELSE false
    END
$$;

CREATE FUNCTION hb_guard_equip_hier() RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    v_parent mst_equip_hier;
    v_depth  integer;
    v_cycle  boolean;
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'equipment node cannot be deleted (use use_yn = N)'
            USING ERRCODE = '55000', CONSTRAINT = 'ck_mst_equip_hier_no_delete';
    END IF;
    IF TG_OP = 'UPDATE' AND (NEW.comp_cd <> OLD.comp_cd OR NEW.plant_cd <> OLD.plant_cd) THEN
        RAISE EXCEPTION 'node % cannot move to another plant', OLD.node_id
            USING ERRCODE = '55000', CONSTRAINT = 'ck_mst_equip_hier_plant_fixed';
    END IF;
    IF NEW.parent_id IS NOT NULL THEN
        SELECT * INTO v_parent FROM mst_equip_hier WHERE node_id = NEW.parent_id;
        -- 회사별 테이블 간 참조는 RLS 를 무시하므로 같은 회사·같은 공장을 직접 확인한다
        IF NOT FOUND OR v_parent.comp_cd <> NEW.comp_cd OR v_parent.plant_cd <> NEW.plant_cd THEN
            RAISE EXCEPTION 'parent % is not in plant %/%', NEW.parent_id, NEW.comp_cd, NEW.plant_cd
                USING ERRCODE = '23503', CONSTRAINT = 'fk_mst_equip_hier_parent_plant';
        END IF;
    END IF;
    IF NOT hb_equip_parent_ok(v_parent.node_type, NEW.node_type) THEN
        RAISE EXCEPTION 'node type % cannot be under %', NEW.node_type, coalesce(v_parent.node_type, 'PLANT')
            USING ERRCODE = '23514', CONSTRAINT = 'ck_mst_equip_hier_parent_type';
    END IF;
    -- 새 부모 지정(추가·이동)일 때: 사용 중지 부모 금지, 순환, 깊이
    IF NEW.parent_id IS NOT NULL AND (TG_OP = 'INSERT' OR NEW.parent_id IS DISTINCT FROM OLD.parent_id) THEN
        IF v_parent.use_yn <> 'Y' THEN
            RAISE EXCEPTION 'parent % is not in use', NEW.parent_id
                USING ERRCODE = '23514', CONSTRAINT = 'ck_mst_equip_hier_parent_use';
        END IF;
        -- 새 부모에서 위로 올라가며 자기 자신을 만나면 순환. 깊이도 함께 센다
        WITH RECURSIVE up(node_id, parent_id, depth) AS (
            SELECT node_id, parent_id, 1 FROM mst_equip_hier WHERE node_id = NEW.parent_id
            UNION ALL
            SELECT h.node_id, h.parent_id, u.depth + 1 FROM up u JOIN mst_equip_hier h ON h.node_id = u.parent_id
             WHERE u.depth < 50 AND u.node_id <> NEW.node_id
        )
        SELECT bool_or(node_id = NEW.node_id), max(depth) INTO v_cycle, v_depth FROM up;
        IF v_cycle THEN
            RAISE EXCEPTION 'node % cannot move under its own descendant', NEW.node_id
                USING ERRCODE = '23514', CONSTRAINT = 'ck_mst_equip_hier_cycle';
        END IF;
        IF v_depth >= 10 THEN
            RAISE EXCEPTION 'equipment tree deeper than 10 levels' USING ERRCODE = '23514', CONSTRAINT = 'ck_mst_equip_hier_depth';
        END IF;
    END IF;
    -- 사용 중지: 사용 중인 하위가 있으면 거부(고아 방지)
    IF TG_OP = 'UPDATE' AND NEW.use_yn = 'N' AND OLD.use_yn = 'Y'
       AND EXISTS (SELECT 1 FROM mst_equip_hier c WHERE c.parent_id = NEW.node_id AND c.use_yn = 'Y') THEN
        RAISE EXCEPTION 'node % has active children', NEW.node_id
            USING ERRCODE = '23514', CONSTRAINT = 'ck_mst_equip_hier_children_use';
    END IF;
    RETURN NEW;
END
$$;
CREATE TRIGGER tg_mst_equip_hier_guard BEFORE INSERT OR UPDATE OR DELETE ON mst_equip_hier
    FOR EACH ROW EXECUTE FUNCTION hb_guard_equip_hier();

CREATE TRIGGER tg_audit AFTER INSERT OR UPDATE OR DELETE ON mst_equip_hier
    FOR EACH ROW EXECUTE FUNCTION hb_audit('node_id', '', '');
ALTER TABLE mst_equip_hier ENABLE ROW LEVEL SECURITY;
ALTER TABLE mst_equip_hier FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON mst_equip_hier USING (comp_cd = hb_current_comp()) WITH CHECK (comp_cd = hb_current_comp());
REVOKE DELETE, TRUNCATE ON mst_equip_hier FROM ${app_role};

INSERT INTO sys_prog (prog_cd, prog_nm_key, view_path, module_cd, actions, sort_no) VALUES
    ('mst-plant',      'prog.mstPlant',     'master/plant-mgt',      'MST', ARRAY['SEARCH','SAVE','DELETE']::varchar(20)[], 130),
    ('mst-equip-hier', 'prog.mstEquipHier', 'master/equip-hier-mgt', 'MST', ARRAY['SEARCH','SAVE','DELETE','EXCEL']::varchar(20)[], 131);
INSERT INTO sys_retention_table (table_nm, class_cd) VALUES ('mst_equip_hier', 'MASTER');
