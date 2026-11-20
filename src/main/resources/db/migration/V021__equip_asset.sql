-- =====================================================================
-- V021 설비·설비 클래스·물리 자산·설치 이력 (P1 4b — docs/19 "4b 상세")
--
-- 설비는 장비 계층의 LINE·CELL 노드에 설치된다(비우면 보관). 물리 자산(금형·지그·게이지)의 설비 설치 구간은 원장 변형:
-- 열린 구간을 해체로 한 번 닫기와 무효 처리만 UPDATE, 삭제 금지, 같은 자산 두 곳 동시 설치 금지(배제 제약).
-- =====================================================================

INSERT INTO sys_code_grp (grp_cd, grp_nm_key, comp_ext_yn) VALUES ('PHYS_ASSET_TYPE', 'code.grp.PHYS_ASSET_TYPE', 'N');
INSERT INTO sys_code (grp_cd, code, code_nm_key, sort_no) VALUES
    ('PHYS_ASSET_TYPE', 'MOLD',  'code.PHYS_ASSET_TYPE.MOLD',  1),
    ('PHYS_ASSET_TYPE', 'JIG',   'code.PHYS_ASSET_TYPE.JIG',   2),
    ('PHYS_ASSET_TYPE', 'GAUGE', 'code.PHYS_ASSET_TYPE.GAUGE', 3);

-- ---------------------------------------------------------------------
-- 1. 설비 클래스
-- ---------------------------------------------------------------------
CREATE TABLE mst_equip_class (
    class_id    bigint        GENERATED ALWAYS AS IDENTITY,
    comp_cd     varchar(20)   NOT NULL,
    class_cd    varchar(30)   NOT NULL,
    class_nm    varchar(100)  NOT NULL,
    sort_no     integer       NOT NULL DEFAULT 0,
    use_yn      char(1)       NOT NULL DEFAULT 'Y',
    remark      varchar(500),
    row_ver     integer       NOT NULL DEFAULT 0,
    created_at  timestamptz   NOT NULL DEFAULT now(),
    created_by  varchar(50)   NOT NULL,
    updated_at  timestamptz   NOT NULL DEFAULT now(),
    updated_by  varchar(50)   NOT NULL,
    CONSTRAINT pk_mst_equip_class PRIMARY KEY (class_id),
    CONSTRAINT fk_mst_equip_class_comp FOREIGN KEY (comp_cd) REFERENCES sys_comp (comp_cd),
    CONSTRAINT uq_mst_equip_class_cd UNIQUE (comp_cd, class_cd),
    CONSTRAINT ck_mst_equip_class_cd CHECK (class_cd ~ '^[A-Z0-9][A-Z0-9._-]{0,29}$'),
    CONSTRAINT ck_mst_equip_class_nm CHECK (length(btrim(class_nm)) > 0),
    CONSTRAINT ck_mst_equip_class_use_yn CHECK (use_yn IN ('Y', 'N'))
);

-- ---------------------------------------------------------------------
-- 2. 설비
-- ---------------------------------------------------------------------
CREATE TABLE mst_equip (
    equip_id      bigint        GENERATED ALWAYS AS IDENTITY,
    comp_cd       varchar(20)   NOT NULL,
    plant_cd      varchar(20)   NOT NULL,
    equip_cd      varchar(30)   NOT NULL,
    equip_nm      varchar(100)  NOT NULL,
    class_id      bigint        NOT NULL,
    node_id       bigint,
    maker         varchar(100),
    model         varchar(100),
    serial_no     varchar(100),
    asset_no      varchar(50),
    install_date  date,
    use_yn        char(1)       NOT NULL DEFAULT 'Y',
    remark        varchar(1000),
    row_ver       integer       NOT NULL DEFAULT 0,
    created_at    timestamptz   NOT NULL DEFAULT now(),
    created_by    varchar(50)   NOT NULL,
    updated_at    timestamptz   NOT NULL DEFAULT now(),
    updated_by    varchar(50)   NOT NULL,
    CONSTRAINT pk_mst_equip PRIMARY KEY (equip_id),
    CONSTRAINT fk_mst_equip_plant FOREIGN KEY (comp_cd, plant_cd) REFERENCES sys_plant (comp_cd, plant_cd),
    CONSTRAINT fk_mst_equip_class FOREIGN KEY (class_id) REFERENCES mst_equip_class (class_id),
    CONSTRAINT fk_mst_equip_node FOREIGN KEY (node_id) REFERENCES mst_equip_hier (node_id),
    CONSTRAINT uq_mst_equip_cd UNIQUE (comp_cd, equip_cd),
    CONSTRAINT ck_mst_equip_cd CHECK (equip_cd ~ '^[A-Z0-9][A-Z0-9._-]{0,29}$'),
    CONSTRAINT ck_mst_equip_nm CHECK (length(btrim(equip_nm)) > 0),
    CONSTRAINT ck_mst_equip_use_yn CHECK (use_yn IN ('Y', 'N'))
);
CREATE INDEX ix_mst_equip_node ON mst_equip (comp_cd, node_id);
COMMENT ON TABLE mst_equip IS '설비 (ISA-95 Work Unit 수준 기계 한 대). node_id = 설치된 LINE·CELL, NULL = 보관·미설치';

CREATE FUNCTION hb_guard_equip() RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    v_node mst_equip_hier;
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'equipment cannot be deleted (use use_yn = N)' USING ERRCODE = '55000', CONSTRAINT = 'ck_mst_equip_no_delete';
    END IF;
    IF TG_OP = 'INSERT' OR NEW.class_id <> OLD.class_id THEN
        IF NOT EXISTS (SELECT 1 FROM mst_equip_class c WHERE c.class_id = NEW.class_id AND c.comp_cd = NEW.comp_cd AND c.use_yn = 'Y') THEN
            RAISE EXCEPTION 'class % is not an active class of company %', NEW.class_id, NEW.comp_cd
                USING ERRCODE = '23503', CONSTRAINT = 'fk_mst_equip_class_comp';
        END IF;
    END IF;
    IF NEW.node_id IS NOT NULL AND (TG_OP = 'INSERT' OR NEW.node_id IS DISTINCT FROM OLD.node_id OR NEW.plant_cd <> OLD.plant_cd) THEN
        SELECT * INTO v_node FROM mst_equip_hier WHERE node_id = NEW.node_id;
        IF NOT FOUND OR v_node.comp_cd <> NEW.comp_cd OR v_node.plant_cd <> NEW.plant_cd THEN
            RAISE EXCEPTION 'node % is not in plant %/%', NEW.node_id, NEW.comp_cd, NEW.plant_cd
                USING ERRCODE = '23503', CONSTRAINT = 'fk_mst_equip_node_plant';
        END IF;
        IF v_node.node_type NOT IN ('LINE', 'CELL') OR v_node.use_yn <> 'Y' THEN
            RAISE EXCEPTION 'equipment can be placed only on an active LINE or CELL' USING ERRCODE = '23514', CONSTRAINT = 'ck_mst_equip_node_type';
        END IF;
    END IF;
    RETURN NEW;
END
$$;
CREATE TRIGGER tg_mst_equip_guard BEFORE INSERT OR UPDATE OR DELETE ON mst_equip
    FOR EACH ROW EXECUTE FUNCTION hb_guard_equip();

-- 장비 계층 노드 사용 중지: 사용 중인 설비가 걸려 있으면 거부 (V020 하위 규칙과 같은 취지)
CREATE FUNCTION hb_guard_equip_hier_equip() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.use_yn = 'N' AND OLD.use_yn = 'Y'
       AND EXISTS (SELECT 1 FROM mst_equip e WHERE e.node_id = NEW.node_id AND e.use_yn = 'Y') THEN
        RAISE EXCEPTION 'node % has active equipment', NEW.node_id USING ERRCODE = '23514', CONSTRAINT = 'ck_mst_equip_hier_equip_use';
    END IF;
    RETURN NEW;
END
$$;
CREATE TRIGGER tg_mst_equip_hier_equip BEFORE UPDATE OF use_yn ON mst_equip_hier
    FOR EACH ROW EXECUTE FUNCTION hb_guard_equip_hier_equip();

-- ---------------------------------------------------------------------
-- 3. 물리 자산 (금형·지그·게이지)
-- ---------------------------------------------------------------------
CREATE TABLE mst_phys_asset (
    asset_id          bigint        GENERATED ALWAYS AS IDENTITY,
    comp_cd           varchar(20)   NOT NULL,
    asset_cd          varchar(30)   NOT NULL,
    asset_type        varchar(10)   NOT NULL,
    asset_nm          varchar(100)  NOT NULL,
    serial_no         varchar(100),
    item_id           bigint,
    life_shots        bigint,
    calib_cycle_days  integer,
    last_calib_date   date,
    use_yn            char(1)       NOT NULL DEFAULT 'Y',
    remark            varchar(1000),
    row_ver           integer       NOT NULL DEFAULT 0,
    created_at        timestamptz   NOT NULL DEFAULT now(),
    created_by        varchar(50)   NOT NULL,
    updated_at        timestamptz   NOT NULL DEFAULT now(),
    updated_by        varchar(50)   NOT NULL,
    CONSTRAINT pk_mst_phys_asset PRIMARY KEY (asset_id),
    CONSTRAINT fk_mst_phys_asset_comp FOREIGN KEY (comp_cd) REFERENCES sys_comp (comp_cd),
    CONSTRAINT fk_mst_phys_asset_item FOREIGN KEY (item_id) REFERENCES mst_item (item_id),
    CONSTRAINT uq_mst_phys_asset_cd UNIQUE (comp_cd, asset_cd),
    CONSTRAINT ck_mst_phys_asset_cd CHECK (asset_cd ~ '^[A-Z0-9][A-Z0-9._-]{0,29}$'),
    CONSTRAINT ck_mst_phys_asset_type CHECK (asset_type IN ('MOLD', 'JIG', 'GAUGE')),
    CONSTRAINT ck_mst_phys_asset_nm CHECK (length(btrim(asset_nm)) > 0),
    CONSTRAINT ck_mst_phys_asset_life CHECK (life_shots IS NULL OR life_shots > 0),
    CONSTRAINT ck_mst_phys_asset_calib CHECK (calib_cycle_days IS NULL OR calib_cycle_days BETWEEN 1 AND 3650),
    CONSTRAINT ck_mst_phys_asset_use_yn CHECK (use_yn IN ('Y', 'N'))
);
COMMENT ON TABLE mst_phys_asset IS '물리 자산 실물 (금형·지그·게이지, ISA-95 Physical Asset). 수명 타수·교정 주기는 P2·P3 이 쓴다';

CREATE FUNCTION hb_check_phys_asset() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'asset cannot be deleted (use use_yn = N)' USING ERRCODE = '55000', CONSTRAINT = 'ck_mst_phys_asset_no_delete';
    END IF;
    IF NEW.item_id IS NOT NULL AND (TG_OP = 'INSERT' OR NEW.item_id IS DISTINCT FROM OLD.item_id)
       AND NOT hb_item_of_comp(NEW.comp_cd, NEW.item_id) THEN
        RAISE EXCEPTION 'item % does not belong to company %', NEW.item_id, NEW.comp_cd
            USING ERRCODE = '23503', CONSTRAINT = 'fk_mst_phys_asset_item_comp';
    END IF;
    RETURN NEW;
END
$$;
CREATE TRIGGER tg_mst_phys_asset_check BEFORE INSERT OR UPDATE OR DELETE ON mst_phys_asset
    FOR EACH ROW EXECUTE FUNCTION hb_check_phys_asset();

-- ---------------------------------------------------------------------
-- 4. 설치 이력 (원장 변형)
-- ---------------------------------------------------------------------
CREATE TABLE mst_asset_install (
    install_id    bigint        GENERATED ALWAYS AS IDENTITY,
    comp_cd       varchar(20)   NOT NULL,
    asset_id      bigint        NOT NULL,
    equip_id      bigint        NOT NULL,
    installed_at  timestamptz   NOT NULL,
    removed_at    timestamptz,
    installed_by  varchar(50)   NOT NULL,
    removed_by    varchar(50),
    recorded_at   timestamptz   NOT NULL DEFAULT now(),
    void_yn       char(1)       NOT NULL DEFAULT 'N',
    void_reason   varchar(500),
    voided_by     varchar(50),
    voided_at     timestamptz,
    remark        varchar(500),
    CONSTRAINT pk_mst_asset_install PRIMARY KEY (install_id),
    CONSTRAINT fk_mst_asset_install_asset FOREIGN KEY (asset_id) REFERENCES mst_phys_asset (asset_id),
    CONSTRAINT fk_mst_asset_install_equip FOREIGN KEY (equip_id) REFERENCES mst_equip (equip_id),
    CONSTRAINT ck_mst_asset_install_period CHECK (removed_at IS NULL OR removed_at > installed_at),
    CONSTRAINT ck_mst_asset_install_void CHECK ((void_yn = 'N') = (void_reason IS NULL)),
    -- 같은 자산은 한 시점에 한 곳에만 (무효 행 제외)
    CONSTRAINT ex_mst_asset_install EXCLUDE USING gist (
        comp_cd WITH =, asset_id WITH =, tstzrange(installed_at, removed_at, '[)') WITH &&
    ) WHERE (void_yn = 'N')
);
CREATE INDEX ix_mst_asset_install_equip ON mst_asset_install (comp_cd, equip_id, installed_at);
COMMENT ON TABLE mst_asset_install IS '물리 자산 설치 이력 (원장 변형: 해체로 한 번 닫기·무효만 UPDATE, 삭제 금지)';

CREATE FUNCTION hb_guard_asset_install() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'install history cannot be deleted (void it)' USING ERRCODE = '55000', CONSTRAINT = 'ck_mst_asset_install_no_delete';
    END IF;
    IF TG_OP = 'INSERT' THEN
        IF NOT EXISTS (SELECT 1 FROM mst_phys_asset a WHERE a.asset_id = NEW.asset_id AND a.comp_cd = NEW.comp_cd AND a.use_yn = 'Y')
           OR NOT EXISTS (SELECT 1 FROM mst_equip e WHERE e.equip_id = NEW.equip_id AND e.comp_cd = NEW.comp_cd AND e.use_yn = 'Y') THEN
            RAISE EXCEPTION 'asset/equipment not active in company %', NEW.comp_cd
                USING ERRCODE = '23503', CONSTRAINT = 'fk_mst_asset_install_comp';
        END IF;
        IF NEW.void_yn <> 'N' THEN
            RAISE EXCEPTION 'new install row must not be void' USING ERRCODE = '55000', CONSTRAINT = 'ck_mst_asset_install_ledger';
        END IF;
        RETURN NEW;
    END IF;
    -- 허용되는 UPDATE 두 가지뿐: (1) 열린 구간 닫기 (2) 무효
    IF NEW.comp_cd <> OLD.comp_cd OR NEW.asset_id <> OLD.asset_id OR NEW.equip_id <> OLD.equip_id
       OR NEW.installed_at <> OLD.installed_at OR NEW.installed_by <> OLD.installed_by OR NEW.recorded_at <> OLD.recorded_at
       OR NEW.remark IS DISTINCT FROM OLD.remark
       OR (OLD.removed_at IS NOT NULL AND NEW.removed_at IS DISTINCT FROM OLD.removed_at)
       OR OLD.void_yn = 'Y' THEN
        RAISE EXCEPTION 'install history % is a ledger row (only close once or void)', OLD.install_id
            USING ERRCODE = '55000', CONSTRAINT = 'ck_mst_asset_install_ledger';
    END IF;
    RETURN NEW;
END
$$;
CREATE TRIGGER tg_mst_asset_install_guard BEFORE INSERT OR UPDATE OR DELETE ON mst_asset_install
    FOR EACH ROW EXECUTE FUNCTION hb_guard_asset_install();

-- 노드 경로 표시용 (P1/BODY/WD-L1/WD-L1-C1). 호출자 권한·RLS 그대로(security invoker)
CREATE FUNCTION hb_equip_node_path(p_node bigint) RETURNS text
    LANGUAGE sql STABLE
AS $$
    WITH RECURSIVE up AS (
        SELECT node_id, parent_id, node_cd, plant_cd, 1 AS d FROM mst_equip_hier WHERE node_id = p_node
        UNION ALL
        SELECT h.node_id, h.parent_id, h.node_cd, h.plant_cd, u.d + 1 FROM up u JOIN mst_equip_hier h ON h.node_id = u.parent_id
         WHERE u.d < 20
    )
    SELECT max(plant_cd) || '/' || string_agg(node_cd, '/' ORDER BY d DESC) FROM up
$$;

-- ---------------------------------------------------------------------
-- 5. 감사 · RLS · 권한 · 화면 · 보존
-- ---------------------------------------------------------------------
CREATE TRIGGER tg_audit AFTER INSERT OR UPDATE OR DELETE ON mst_equip_class FOR EACH ROW EXECUTE FUNCTION hb_audit('class_id', '', '');
CREATE TRIGGER tg_audit AFTER INSERT OR UPDATE OR DELETE ON mst_equip FOR EACH ROW EXECUTE FUNCTION hb_audit('equip_id', '', '');
CREATE TRIGGER tg_audit AFTER INSERT OR UPDATE OR DELETE ON mst_phys_asset FOR EACH ROW EXECUTE FUNCTION hb_audit('asset_id', '', '');
CREATE TRIGGER tg_audit AFTER INSERT OR UPDATE OR DELETE ON mst_asset_install FOR EACH ROW EXECUTE FUNCTION hb_audit('install_id', '', '');

ALTER TABLE mst_equip_class ENABLE ROW LEVEL SECURITY;
ALTER TABLE mst_equip_class FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON mst_equip_class USING (comp_cd = hb_current_comp()) WITH CHECK (comp_cd = hb_current_comp());
ALTER TABLE mst_equip ENABLE ROW LEVEL SECURITY;
ALTER TABLE mst_equip FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON mst_equip USING (comp_cd = hb_current_comp()) WITH CHECK (comp_cd = hb_current_comp());
ALTER TABLE mst_phys_asset ENABLE ROW LEVEL SECURITY;
ALTER TABLE mst_phys_asset FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON mst_phys_asset USING (comp_cd = hb_current_comp()) WITH CHECK (comp_cd = hb_current_comp());
ALTER TABLE mst_asset_install ENABLE ROW LEVEL SECURITY;
ALTER TABLE mst_asset_install FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON mst_asset_install USING (comp_cd = hb_current_comp()) WITH CHECK (comp_cd = hb_current_comp());
REVOKE DELETE, TRUNCATE ON mst_equip_class, mst_equip, mst_phys_asset, mst_asset_install FROM ${app_role};

INSERT INTO sys_prog (prog_cd, prog_nm_key, view_path, module_cd, actions, sort_no) VALUES
    ('mst-equip', 'prog.mstEquip', 'master/equip-mgt', 'MST', ARRAY['SEARCH','SAVE','DELETE','EXCEL']::varchar(20)[], 132),
    ('mst-asset', 'prog.mstAsset', 'master/asset-mgt', 'MST', ARRAY['SEARCH','SAVE','DELETE','EXCEL']::varchar(20)[], 133);
INSERT INTO sys_retention_table (table_nm, class_cd) VALUES
    ('mst_equip_class', 'MASTER'), ('mst_equip', 'MASTER'), ('mst_phys_asset', 'MASTER'), ('mst_asset_install', 'MASTER');
