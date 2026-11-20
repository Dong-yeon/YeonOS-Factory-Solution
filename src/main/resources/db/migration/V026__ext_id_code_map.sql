-- =====================================================================
-- V026 외부 시스템·외부 ID 매핑·코드 매핑·ERP 품목 동기화 정책 (P1 7a — docs/22)
--
-- 업무 테이블에 ERP·고객 전용 컬럼을 넣지 않는다(docs/07 하지 말 것 12). 외부 코드는 여기에만.
-- - mst_ext_system: 회사별 외부 시스템(ERP·고객 포털·협력사)
-- - mst_ext_id_map: 시스템 × 대상 유형 × 내부 id ↔ 외부 코드 (시스템 안에서 1:1, 사용 중 행만)
-- - if_code_map:   시스템 × 코드 유형 × 외부 코드 → 내부 코드 (단위·품목 유형·창고)
-- - 정책 ERP_ITEM_SYNC_MODE: PULL 이면 품목 ERP 주인 필드 잠금(서비스)
-- =====================================================================

INSERT INTO sys_code_grp (grp_cd, grp_nm_key, comp_ext_yn) VALUES
    ('EXT_SYSTEM_TYPE', 'code.grp.EXT_SYSTEM_TYPE', 'N'),
    ('EXT_ENTITY',      'code.grp.EXT_ENTITY',      'N'),
    ('IF_CODE_TYPE',    'code.grp.IF_CODE_TYPE',    'N');
INSERT INTO sys_code (grp_cd, code, code_nm_key, sort_no) VALUES
    ('EXT_SYSTEM_TYPE', 'ERP',      'code.EXT_SYSTEM_TYPE.ERP',      1),
    ('EXT_SYSTEM_TYPE', 'CUSTOMER', 'code.EXT_SYSTEM_TYPE.CUSTOMER', 2),
    ('EXT_SYSTEM_TYPE', 'SUPPLIER', 'code.EXT_SYSTEM_TYPE.SUPPLIER', 3),
    ('EXT_SYSTEM_TYPE', 'OTHER',    'code.EXT_SYSTEM_TYPE.OTHER',    4),
    ('EXT_ENTITY', 'ITEM', 'code.EXT_ENTITY.ITEM', 1),
    ('EXT_ENTITY', 'CUST', 'code.EXT_ENTITY.CUST', 2),
    ('IF_CODE_TYPE', 'UOM',       'code.IF_CODE_TYPE.UOM',       1),
    ('IF_CODE_TYPE', 'ITEM_TYPE', 'code.IF_CODE_TYPE.ITEM_TYPE', 2),
    ('IF_CODE_TYPE', 'WH',        'code.IF_CODE_TYPE.WH',        3);

INSERT INTO sys_policy_def (policy_key, value_type, default_value, allowed_values, min_value, max_value, desc_key) VALUES
    -- docs/07 5.6·D8: PULL = ERP 가 품목 주인(MES 에서 ERP 주인 필드 잠금), EXCEL = 엑셀 이관·화면 입력, OFF = 연동 없음
    ('ERP_ITEM_SYNC_MODE', 'ENUM', 'EXCEL', ARRAY['PULL','EXCEL','OFF'], NULL, NULL, 'policy.ERP_ITEM_SYNC_MODE');

-- ---------------------------------------------------------------------
-- 1. 외부 시스템
-- ---------------------------------------------------------------------
CREATE TABLE mst_ext_system (
    system_id    bigint        GENERATED ALWAYS AS IDENTITY,
    comp_cd      varchar(20)   NOT NULL,
    system_cd    varchar(20)   NOT NULL,
    system_nm    varchar(100)  NOT NULL,
    system_type  varchar(10)   NOT NULL,
    cust_id      bigint,
    use_yn       char(1)       NOT NULL DEFAULT 'Y',
    remark       varchar(500),
    row_ver      integer       NOT NULL DEFAULT 0,
    created_at   timestamptz   NOT NULL DEFAULT now(),
    created_by   varchar(50)   NOT NULL,
    updated_at   timestamptz   NOT NULL DEFAULT now(),
    updated_by   varchar(50)   NOT NULL,
    CONSTRAINT pk_mst_ext_system PRIMARY KEY (system_id),
    CONSTRAINT fk_mst_ext_system_comp FOREIGN KEY (comp_cd) REFERENCES sys_comp (comp_cd),
    CONSTRAINT fk_mst_ext_system_cust FOREIGN KEY (cust_id) REFERENCES mst_cust (cust_id),
    CONSTRAINT uq_mst_ext_system_cd UNIQUE (comp_cd, system_cd),
    CONSTRAINT ck_mst_ext_system_cd CHECK (system_cd ~ '^[A-Z0-9][A-Z0-9_-]{0,19}$'),
    CONSTRAINT ck_mst_ext_system_nm CHECK (length(btrim(system_nm)) > 0),
    CONSTRAINT ck_mst_ext_system_type CHECK (system_type IN ('ERP', 'CUSTOMER', 'SUPPLIER', 'OTHER')),
    -- 거래처 연결은 고객·협력사 시스템에만
    CONSTRAINT ck_mst_ext_system_cust CHECK (cust_id IS NULL OR system_type IN ('CUSTOMER', 'SUPPLIER')),
    CONSTRAINT ck_mst_ext_system_use_yn CHECK (use_yn IN ('Y', 'N'))
);
COMMENT ON TABLE mst_ext_system IS '외부 시스템 (ERP·고객 포털·협력사) — 외부 코드의 "어디의 코드인가" (docs/22)';

CREATE FUNCTION hb_guard_ext_system() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.cust_id IS NOT NULL AND (TG_OP = 'INSERT' OR NEW.cust_id IS DISTINCT FROM OLD.cust_id)
       AND NOT EXISTS (SELECT 1 FROM mst_cust c WHERE c.cust_id = NEW.cust_id AND c.comp_cd = NEW.comp_cd) THEN
        RAISE EXCEPTION 'customer % does not belong to company %', NEW.cust_id, NEW.comp_cd
            USING ERRCODE = '23503', CONSTRAINT = 'fk_mst_ext_system_cust_comp';
    END IF;
    IF TG_OP = 'UPDATE' AND NEW.system_cd <> OLD.system_cd THEN
        RAISE EXCEPTION 'system code cannot be changed' USING ERRCODE = '55000', CONSTRAINT = 'ck_mst_ext_system_cd_fixed';
    END IF;
    RETURN NEW;
END
$$;
CREATE TRIGGER tg_mst_ext_system_guard BEFORE INSERT OR UPDATE ON mst_ext_system
    FOR EACH ROW EXECUTE FUNCTION hb_guard_ext_system();
CREATE TRIGGER tg_mst_ext_system_no_delete BEFORE DELETE ON mst_ext_system FOR EACH ROW EXECUTE FUNCTION hb_forbid_delete_master();

-- 시스템이 그 회사의 것인지 (쓰는 쪽 트리거 공용)
CREATE FUNCTION hb_ext_system_of_comp(p_comp varchar, p_system bigint) RETURNS boolean
    LANGUAGE sql STABLE
AS $$
    SELECT EXISTS (SELECT 1 FROM mst_ext_system WHERE system_id = p_system AND comp_cd = p_comp)
$$;

-- ---------------------------------------------------------------------
-- 2. 외부 ID 매핑
-- ---------------------------------------------------------------------
CREATE TABLE mst_ext_id_map (
    map_id       bigint        GENERATED ALWAYS AS IDENTITY,
    comp_cd      varchar(20)   NOT NULL,
    system_id    bigint        NOT NULL,
    entity_type  varchar(10)   NOT NULL,
    internal_id  bigint        NOT NULL,
    external_id  varchar(100)  NOT NULL,
    external_nm  varchar(200),
    use_yn       char(1)       NOT NULL DEFAULT 'Y',
    row_ver      integer       NOT NULL DEFAULT 0,
    created_at   timestamptz   NOT NULL DEFAULT now(),
    created_by   varchar(50)   NOT NULL,
    updated_at   timestamptz   NOT NULL DEFAULT now(),
    updated_by   varchar(50)   NOT NULL,
    CONSTRAINT pk_mst_ext_id_map PRIMARY KEY (map_id),
    CONSTRAINT fk_mst_ext_id_map_system FOREIGN KEY (system_id) REFERENCES mst_ext_system (system_id),
    CONSTRAINT ck_mst_ext_id_map_entity CHECK (entity_type IN ('ITEM', 'CUST')),
    -- 외부 코드는 앞뒤 공백 없이, 제어 문자 없이 (ERP 코드는 대소문자를 구분할 수 있어 대문자로 바꾸지 않는다)
    CONSTRAINT ck_mst_ext_id_map_ext CHECK (external_id = btrim(external_id) AND length(external_id) > 0
                                            AND external_id !~ '[[:cntrl:]]'),
    CONSTRAINT ck_mst_ext_id_map_use_yn CHECK (use_yn IN ('Y', 'N'))
);
-- 시스템 안에서 1:1 (사용 중 행만 — 잘못 넣은 매핑은 끄고 다시 넣는다)
CREATE UNIQUE INDEX uq_mst_ext_id_map_ext ON mst_ext_id_map (comp_cd, system_id, entity_type, external_id) WHERE use_yn = 'Y';
CREATE UNIQUE INDEX uq_mst_ext_id_map_int ON mst_ext_id_map (comp_cd, system_id, entity_type, internal_id) WHERE use_yn = 'Y';
CREATE INDEX ix_mst_ext_id_map_target ON mst_ext_id_map (comp_cd, entity_type, internal_id);
COMMENT ON TABLE mst_ext_id_map IS '외부 ID 매핑 (ISA-95 Part 7 성격) — 시스템 안에서 내부 ↔ 외부 1:1. 업무 테이블에 외부 코드 컬럼 대신 (docs/22)';

CREATE FUNCTION hb_guard_ext_id_map() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'UPDATE' AND (NEW.comp_cd <> OLD.comp_cd OR NEW.system_id <> OLD.system_id
                             OR NEW.entity_type <> OLD.entity_type OR NEW.internal_id <> OLD.internal_id) THEN
        -- 가리키는 대상이 바뀌면 송신 이력의 뜻이 바뀐다 — 끄고 새로 넣는다
        RAISE EXCEPTION 'mapping % target cannot be changed', OLD.map_id USING ERRCODE = '55000', CONSTRAINT = 'ck_mst_ext_id_map_fixed';
    END IF;
    IF TG_OP = 'INSERT' THEN
        IF NOT hb_ext_system_of_comp(NEW.comp_cd, NEW.system_id) THEN
            RAISE EXCEPTION 'system % does not belong to company %', NEW.system_id, NEW.comp_cd
                USING ERRCODE = '23503', CONSTRAINT = 'fk_mst_ext_id_map_system_comp';
        END IF;
        IF (NEW.entity_type = 'ITEM' AND NOT hb_item_of_comp(NEW.comp_cd, NEW.internal_id))
           OR (NEW.entity_type = 'CUST' AND NOT EXISTS (SELECT 1 FROM mst_cust c WHERE c.cust_id = NEW.internal_id AND c.comp_cd = NEW.comp_cd)) THEN
            RAISE EXCEPTION '% % does not belong to company %', NEW.entity_type, NEW.internal_id, NEW.comp_cd
                USING ERRCODE = '23503', CONSTRAINT = 'fk_mst_ext_id_map_target';
        END IF;
    END IF;
    RETURN NEW;
END
$$;
CREATE TRIGGER tg_mst_ext_id_map_guard BEFORE INSERT OR UPDATE ON mst_ext_id_map
    FOR EACH ROW EXECUTE FUNCTION hb_guard_ext_id_map();
CREATE TRIGGER tg_mst_ext_id_map_no_delete BEFORE DELETE ON mst_ext_id_map FOR EACH ROW EXECUTE FUNCTION hb_forbid_delete_master();

-- ---------------------------------------------------------------------
-- 3. 코드 매핑
-- ---------------------------------------------------------------------
CREATE TABLE if_code_map (
    code_map_id  bigint        GENERATED ALWAYS AS IDENTITY,
    comp_cd      varchar(20)   NOT NULL,
    system_id    bigint        NOT NULL,
    code_type    varchar(10)   NOT NULL,
    ext_code     varchar(50)   NOT NULL,
    int_code     varchar(30)   NOT NULL,
    use_yn       char(1)       NOT NULL DEFAULT 'Y',
    row_ver      integer       NOT NULL DEFAULT 0,
    created_at   timestamptz   NOT NULL DEFAULT now(),
    created_by   varchar(50)   NOT NULL,
    updated_at   timestamptz   NOT NULL DEFAULT now(),
    updated_by   varchar(50)   NOT NULL,
    CONSTRAINT pk_if_code_map PRIMARY KEY (code_map_id),
    CONSTRAINT fk_if_code_map_system FOREIGN KEY (system_id) REFERENCES mst_ext_system (system_id),
    CONSTRAINT ck_if_code_map_type CHECK (code_type IN ('UOM', 'ITEM_TYPE', 'WH')),
    CONSTRAINT ck_if_code_map_ext CHECK (ext_code = btrim(ext_code) AND length(ext_code) > 0 AND ext_code !~ '[[:cntrl:]]'),
    CONSTRAINT ck_if_code_map_use_yn CHECK (use_yn IN ('Y', 'N'))
);
-- 외부 표기 "ea"·"EA" 는 같은 말 — 대소문자 무시 유일 (사용 중 행만)
CREATE UNIQUE INDEX uq_if_code_map ON if_code_map (comp_cd, system_id, code_type, lower(ext_code)) WHERE use_yn = 'Y';
COMMENT ON TABLE if_code_map IS '코드 매핑 — 외부 표기 → 내부 코드 (단위 "개" → EA, ERP 창고 → WH 노드). docs/07 4.2';

CREATE FUNCTION hb_guard_if_code_map() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'UPDATE' AND (NEW.comp_cd <> OLD.comp_cd OR NEW.system_id <> OLD.system_id OR NEW.code_type <> OLD.code_type) THEN
        RAISE EXCEPTION 'code map % key cannot be changed', OLD.code_map_id USING ERRCODE = '55000', CONSTRAINT = 'ck_if_code_map_fixed';
    END IF;
    IF TG_OP = 'INSERT' AND NOT hb_ext_system_of_comp(NEW.comp_cd, NEW.system_id) THEN
        RAISE EXCEPTION 'system % does not belong to company %', NEW.system_id, NEW.comp_cd
            USING ERRCODE = '23503', CONSTRAINT = 'fk_if_code_map_system_comp';
    END IF;
    -- 내부 코드는 지금 쓸 수 있는 코드로만 새로 가리킨다
    IF TG_OP = 'INSERT' OR NEW.int_code <> OLD.int_code OR (NEW.use_yn = 'Y' AND OLD.use_yn = 'N') THEN
        IF (NEW.code_type = 'UOM' AND NOT hb_unit_usable(NEW.comp_cd, NEW.int_code))
           OR (NEW.code_type = 'ITEM_TYPE' AND NOT EXISTS (SELECT 1 FROM sys_code WHERE grp_cd = 'ITEM_TYPE' AND code = NEW.int_code AND use_yn = 'Y'))
           OR (NEW.code_type = 'WH' AND NOT EXISTS (SELECT 1 FROM mst_equip_hier h WHERE h.comp_cd = NEW.comp_cd
                                                      AND h.node_cd = NEW.int_code AND h.node_type = 'WH' AND h.use_yn = 'Y')) THEN
            RAISE EXCEPTION 'internal % code % is not available', NEW.code_type, NEW.int_code
                USING ERRCODE = '23503', CONSTRAINT = 'fk_if_code_map_int_code';
        END IF;
    END IF;
    RETURN NEW;
END
$$;
CREATE TRIGGER tg_if_code_map_guard BEFORE INSERT OR UPDATE ON if_code_map
    FOR EACH ROW EXECUTE FUNCTION hb_guard_if_code_map();
CREATE TRIGGER tg_if_code_map_no_delete BEFORE DELETE ON if_code_map FOR EACH ROW EXECUTE FUNCTION hb_forbid_delete_master();

-- ---------------------------------------------------------------------
-- 4. 감사 · RLS · 권한 · 화면 · 보존
-- ---------------------------------------------------------------------
CREATE TRIGGER tg_audit AFTER INSERT OR UPDATE OR DELETE ON mst_ext_system FOR EACH ROW EXECUTE FUNCTION hb_audit('system_id', '', '');
CREATE TRIGGER tg_audit AFTER INSERT OR UPDATE OR DELETE ON mst_ext_id_map FOR EACH ROW EXECUTE FUNCTION hb_audit('map_id', '', '');
CREATE TRIGGER tg_audit AFTER INSERT OR UPDATE OR DELETE ON if_code_map FOR EACH ROW EXECUTE FUNCTION hb_audit('code_map_id', '', '');

ALTER TABLE mst_ext_system ENABLE ROW LEVEL SECURITY;
ALTER TABLE mst_ext_system FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON mst_ext_system USING (comp_cd = hb_current_comp()) WITH CHECK (comp_cd = hb_current_comp());
ALTER TABLE mst_ext_id_map ENABLE ROW LEVEL SECURITY;
ALTER TABLE mst_ext_id_map FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON mst_ext_id_map USING (comp_cd = hb_current_comp()) WITH CHECK (comp_cd = hb_current_comp());
ALTER TABLE if_code_map ENABLE ROW LEVEL SECURITY;
ALTER TABLE if_code_map FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON if_code_map USING (comp_cd = hb_current_comp()) WITH CHECK (comp_cd = hb_current_comp());
REVOKE DELETE, TRUNCATE ON mst_ext_system, mst_ext_id_map, if_code_map FROM ${app_role};

INSERT INTO sys_prog (prog_cd, prog_nm_key, view_path, module_cd, actions, sort_no) VALUES
    ('mst-ext-system', 'prog.mstExtSystem', 'master/ext-system-mgt', 'MST', ARRAY['SEARCH','SAVE']::varchar(20)[], 160);
INSERT INTO sys_retention_table (table_nm, class_cd) VALUES
    ('mst_ext_system', 'MASTER'), ('mst_ext_id_map', 'MASTER'), ('if_code_map', 'MASTER');
