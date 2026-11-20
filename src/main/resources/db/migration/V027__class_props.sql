-- =====================================================================
-- V027 품목 클래스·클래스 속성·attrs jsonb (P1 7b — docs/22 5b)
--
-- EAV 대신 대상 테이블의 attrs jsonb + 속성 정의(mst_class_prop)로 검증 (docs/02 6.3 수정 채택).
-- - mst_item_class: 회사별 품목 분류. 설비 클래스는 V021 mst_equip_class
-- - mst_class_prop: (ITEM|EQUIP, 클래스, 속성 코드) 정의 — 형식 TEXT·NUM·DATE·BOOL·LIST, 필수, 자릿수, 선택지
-- - hb_check_attrs: 키는 정의된 것만, 사용 중 필수 속성은 값 있음, 형식·자릿수·선택지·길이 — 품목·설비 트리거가 부른다
-- =====================================================================

INSERT INTO sys_code_grp (grp_cd, grp_nm_key, comp_ext_yn) VALUES
    ('PROP_DATA_TYPE', 'code.grp.PROP_DATA_TYPE', 'N'),
    ('CLASS_TYPE',     'code.grp.CLASS_TYPE',     'N');
INSERT INTO sys_code (grp_cd, code, code_nm_key, sort_no) VALUES
    ('PROP_DATA_TYPE', 'TEXT', 'code.PROP_DATA_TYPE.TEXT', 1),
    ('PROP_DATA_TYPE', 'NUM',  'code.PROP_DATA_TYPE.NUM',  2),
    ('PROP_DATA_TYPE', 'DATE', 'code.PROP_DATA_TYPE.DATE', 3),
    ('PROP_DATA_TYPE', 'BOOL', 'code.PROP_DATA_TYPE.BOOL', 4),
    ('PROP_DATA_TYPE', 'LIST', 'code.PROP_DATA_TYPE.LIST', 5),
    ('CLASS_TYPE', 'ITEM',  'code.CLASS_TYPE.ITEM',  1),
    ('CLASS_TYPE', 'EQUIP', 'code.CLASS_TYPE.EQUIP', 2);

-- ---------------------------------------------------------------------
-- 1. 품목 클래스
-- ---------------------------------------------------------------------
CREATE TABLE mst_item_class (
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
    CONSTRAINT pk_mst_item_class PRIMARY KEY (class_id),
    CONSTRAINT fk_mst_item_class_comp FOREIGN KEY (comp_cd) REFERENCES sys_comp (comp_cd),
    CONSTRAINT uq_mst_item_class_cd UNIQUE (comp_cd, class_cd),
    CONSTRAINT ck_mst_item_class_cd CHECK (class_cd ~ '^[A-Z0-9][A-Z0-9._-]{0,29}$'),
    CONSTRAINT ck_mst_item_class_nm CHECK (length(btrim(class_nm)) > 0),
    CONSTRAINT ck_mst_item_class_use_yn CHECK (use_yn IN ('Y', 'N'))
);
COMMENT ON TABLE mst_item_class IS '품목 클래스 (회사별 분류 — 코일·볼트류·프레스품). 품목 유형(FG·RM)은 시스템 코드 (docs/22 5b)';
CREATE TRIGGER tg_mst_item_class_no_delete BEFORE DELETE ON mst_item_class FOR EACH ROW EXECUTE FUNCTION hb_forbid_delete_master();

ALTER TABLE mst_item ADD COLUMN item_class_id bigint;
ALTER TABLE mst_item ADD COLUMN attrs jsonb NOT NULL DEFAULT '{}'::jsonb;
ALTER TABLE mst_item ADD CONSTRAINT fk_mst_item_class FOREIGN KEY (item_class_id) REFERENCES mst_item_class (class_id);
ALTER TABLE mst_item ADD CONSTRAINT ck_mst_item_attrs CHECK (jsonb_typeof(attrs) = 'object');
CREATE INDEX ix_mst_item_class ON mst_item (comp_cd, item_class_id) WHERE item_class_id IS NOT NULL;
ALTER TABLE mst_equip ADD COLUMN attrs jsonb NOT NULL DEFAULT '{}'::jsonb;
ALTER TABLE mst_equip ADD CONSTRAINT ck_mst_equip_attrs CHECK (jsonb_typeof(attrs) = 'object');
COMMENT ON COLUMN mst_item.attrs IS '클래스 속성 값 {속성 코드: 값} — mst_class_prop 정의로 검증(hb_check_attrs)';
COMMENT ON COLUMN mst_equip.attrs IS '클래스 속성 값 {속성 코드: 값} — mst_class_prop 정의로 검증(hb_check_attrs)';

-- ---------------------------------------------------------------------
-- 2. 속성 정의
-- ---------------------------------------------------------------------
CREATE TABLE mst_class_prop (
    prop_id      bigint         GENERATED ALWAYS AS IDENTITY,
    comp_cd      varchar(20)    NOT NULL,
    class_type   varchar(10)    NOT NULL,
    class_id     bigint         NOT NULL,
    prop_cd      varchar(30)    NOT NULL,
    prop_nm      varchar(100)   NOT NULL,
    data_type    varchar(10)    NOT NULL,
    unit_nm      varchar(20),
    scale        smallint,
    options      jsonb,
    required_yn  char(1)        NOT NULL DEFAULT 'N',
    semantic_id  varchar(200),
    sort_no      integer        NOT NULL DEFAULT 0,
    use_yn       char(1)        NOT NULL DEFAULT 'Y',
    row_ver      integer        NOT NULL DEFAULT 0,
    created_at   timestamptz    NOT NULL DEFAULT now(),
    created_by   varchar(50)    NOT NULL,
    updated_at   timestamptz    NOT NULL DEFAULT now(),
    updated_by   varchar(50)    NOT NULL,
    CONSTRAINT pk_mst_class_prop PRIMARY KEY (prop_id),
    CONSTRAINT fk_mst_class_prop_comp FOREIGN KEY (comp_cd) REFERENCES sys_comp (comp_cd),
    CONSTRAINT uq_mst_class_prop UNIQUE (comp_cd, class_type, class_id, prop_cd),
    CONSTRAINT ck_mst_class_prop_type CHECK (class_type IN ('ITEM', 'EQUIP')),
    CONSTRAINT ck_mst_class_prop_cd CHECK (prop_cd ~ '^[A-Z][A-Z0-9_]{0,29}$'),
    CONSTRAINT ck_mst_class_prop_nm CHECK (length(btrim(prop_nm)) > 0),
    CONSTRAINT ck_mst_class_prop_data_type CHECK (data_type IN ('TEXT', 'NUM', 'DATE', 'BOOL', 'LIST')),
    -- 자릿수는 숫자에만(0~6), 선택지는 목록에만(문자열 1~50개)
    CONSTRAINT ck_mst_class_prop_scale CHECK ((data_type = 'NUM') = (scale IS NOT NULL) AND (scale IS NULL OR scale BETWEEN 0 AND 6)),
    CONSTRAINT ck_mst_class_prop_options CHECK ((data_type = 'LIST') = (options IS NOT NULL)
        AND (options IS NULL OR (jsonb_typeof(options) = 'array' AND jsonb_array_length(options) BETWEEN 1 AND 50))),
    CONSTRAINT ck_mst_class_prop_required CHECK (required_yn IN ('Y', 'N')),
    CONSTRAINT ck_mst_class_prop_use_yn CHECK (use_yn IN ('Y', 'N'))
);
CREATE INDEX ix_mst_class_prop_class ON mst_class_prop (comp_cd, class_type, class_id, sort_no);
COMMENT ON TABLE mst_class_prop IS '클래스 속성 정의 — 값은 대상 테이블 attrs jsonb (docs/02 6.3, docs/22 5b). semantic_id = AAS 연계용(선택)';

CREATE FUNCTION hb_guard_class_prop() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'UPDATE' AND (NEW.comp_cd <> OLD.comp_cd OR NEW.class_type <> OLD.class_type OR NEW.class_id <> OLD.class_id
                             OR NEW.prop_cd <> OLD.prop_cd OR NEW.data_type <> OLD.data_type) THEN
        -- 이미 저장된 값의 뜻이 바뀐다 — 새 속성 코드로
        RAISE EXCEPTION 'property % key / data type cannot be changed', OLD.prop_id USING ERRCODE = '55000', CONSTRAINT = 'ck_mst_class_prop_fixed';
    END IF;
    IF TG_OP = 'INSERT' AND NOT (
           (NEW.class_type = 'ITEM' AND EXISTS (SELECT 1 FROM mst_item_class c WHERE c.class_id = NEW.class_id AND c.comp_cd = NEW.comp_cd))
        OR (NEW.class_type = 'EQUIP' AND EXISTS (SELECT 1 FROM mst_equip_class c WHERE c.class_id = NEW.class_id AND c.comp_cd = NEW.comp_cd))) THEN
        RAISE EXCEPTION '% class % does not belong to company %', NEW.class_type, NEW.class_id, NEW.comp_cd
            USING ERRCODE = '23503', CONSTRAINT = 'fk_mst_class_prop_class';
    END IF;
    IF NEW.options IS NOT NULL AND EXISTS (
           SELECT 1 FROM jsonb_array_elements(NEW.options) o
            WHERE jsonb_typeof(o) <> 'string' OR length(btrim(o #>> '{}')) = 0 OR length(o #>> '{}') > 100) THEN
        RAISE EXCEPTION 'options must be non-empty strings' USING ERRCODE = '23514', CONSTRAINT = 'ck_mst_class_prop_options';
    END IF;
    RETURN NEW;
END
$$;
CREATE TRIGGER tg_mst_class_prop_guard BEFORE INSERT OR UPDATE ON mst_class_prop
    FOR EACH ROW EXECUTE FUNCTION hb_guard_class_prop();
CREATE TRIGGER tg_mst_class_prop_no_delete BEFORE DELETE ON mst_class_prop FOR EACH ROW EXECUTE FUNCTION hb_forbid_delete_master();

-- ---------------------------------------------------------------------
-- 3. 값 검증 (품목·설비 공용)
-- ---------------------------------------------------------------------
-- p_required = 사용 중 필수 속성까지 볼지. 설비는 먼저 만들고 속성을 나중에 넣으므로 값을 저장할 때만 (docs/22 5b)
CREATE FUNCTION hb_check_attrs(p_comp varchar, p_type varchar, p_class bigint, p_attrs jsonb, p_required boolean) RETURNS void
    LANGUAGE plpgsql STABLE
AS $$
DECLARE
    v_key  text;
    v_val  jsonb;
    v_prop mst_class_prop;
    v_txt  text;
BEGIN
    IF jsonb_typeof(p_attrs) <> 'object' THEN
        RAISE EXCEPTION 'attributes must be an object' USING ERRCODE = '23514', CONSTRAINT = 'ck_attrs_object';
    END IF;
    IF p_class IS NULL THEN
        IF p_attrs <> '{}'::jsonb THEN
            RAISE EXCEPTION 'attributes need a class' USING ERRCODE = '23514', CONSTRAINT = 'ck_attrs_no_class';
        END IF;
        RETURN;
    END IF;
    -- 1) 키는 그 클래스에 정의된 것만, 값은 형식대로 (null 은 "값 없음")
    FOR v_key, v_val IN SELECT key, value FROM jsonb_each(p_attrs) LOOP
        SELECT * INTO v_prop FROM mst_class_prop
         WHERE comp_cd = p_comp AND class_type = p_type AND class_id = p_class AND prop_cd = v_key;
        IF NOT FOUND THEN
            RAISE EXCEPTION 'attribute % is not defined for the class', v_key USING ERRCODE = '23514', CONSTRAINT = 'ck_attrs_unknown';
        END IF;
        CONTINUE WHEN jsonb_typeof(v_val) = 'null';
        v_txt := v_val #>> '{}';
        IF (v_prop.data_type IN ('TEXT', 'DATE', 'LIST') AND jsonb_typeof(v_val) <> 'string')
           OR (v_prop.data_type = 'NUM' AND (jsonb_typeof(v_val) <> 'number' OR scale(v_txt::numeric) > v_prop.scale
                                             OR abs(v_txt::numeric) >= 1e15))
           OR (v_prop.data_type = 'BOOL' AND jsonb_typeof(v_val) <> 'boolean')
           OR (v_prop.data_type = 'TEXT' AND (length(v_txt) > 500 OR length(btrim(v_txt)) = 0))
           OR (v_prop.data_type = 'DATE' AND v_txt !~ '^(19|20|21)[0-9]{2}-[0-9]{2}-[0-9]{2}$')
           OR (v_prop.data_type = 'LIST' AND NOT v_prop.options ? v_txt) THEN
            RAISE EXCEPTION 'attribute % has a wrong value', v_key USING ERRCODE = '23514', CONSTRAINT = 'ck_attrs_value';
        END IF;
        IF v_prop.data_type = 'DATE' THEN
            PERFORM v_txt::date;      -- 2026-02-30 같은 날짜는 여기서 22008
        END IF;
    END LOOP;
    -- 2) 사용 중 필수 속성은 값 있음
    IF NOT p_required THEN
        RETURN;
    END IF;
    FOR v_prop IN SELECT * FROM mst_class_prop
                   WHERE comp_cd = p_comp AND class_type = p_type AND class_id = p_class AND required_yn = 'Y' AND use_yn = 'Y' LOOP
        IF NOT p_attrs ? v_prop.prop_cd OR jsonb_typeof(p_attrs -> v_prop.prop_cd) = 'null' THEN
            RAISE EXCEPTION 'attribute % is required', v_prop.prop_cd USING ERRCODE = '23514', CONSTRAINT = 'ck_attrs_required';
        END IF;
    END LOOP;
END
$$;

CREATE FUNCTION hb_check_item_attrs() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.item_class_id IS NOT NULL AND (TG_OP = 'INSERT' OR NEW.item_class_id IS DISTINCT FROM OLD.item_class_id)
       AND NOT EXISTS (SELECT 1 FROM mst_item_class c WHERE c.class_id = NEW.item_class_id AND c.comp_cd = NEW.comp_cd AND c.use_yn = 'Y') THEN
        RAISE EXCEPTION 'item class % is not an active class of company %', NEW.item_class_id, NEW.comp_cd
            USING ERRCODE = '23503', CONSTRAINT = 'fk_mst_item_class_comp';
    END IF;
    IF TG_OP = 'INSERT' OR NEW.attrs IS DISTINCT FROM OLD.attrs OR NEW.item_class_id IS DISTINCT FROM OLD.item_class_id THEN
        -- 품목은 클래스·값을 함께 저장한다(속성 저장) — 바뀌면 필수까지
        PERFORM hb_check_attrs(NEW.comp_cd, 'ITEM', NEW.item_class_id, NEW.attrs, true);
    END IF;
    RETURN NEW;
END
$$;
CREATE TRIGGER tg_mst_item_attrs BEFORE INSERT OR UPDATE OF attrs, item_class_id ON mst_item
    FOR EACH ROW EXECUTE FUNCTION hb_check_item_attrs();

CREATE FUNCTION hb_check_equip_attrs() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    -- 설비는 폼에서 먼저 만들고(클래스 포함) 속성은 나중에 넣는다 — 필수 검사는 속성 값을 저장할 때만.
    -- 클래스만 바꿀 때는 지금 값이 새 클래스 정의에 맞는지(키·형식)만 본다
    IF TG_OP = 'INSERT' OR NEW.attrs IS DISTINCT FROM OLD.attrs OR NEW.class_id IS DISTINCT FROM OLD.class_id THEN
        PERFORM hb_check_attrs(NEW.comp_cd, 'EQUIP', NEW.class_id, NEW.attrs,
                               TG_OP = 'UPDATE' AND NEW.attrs IS DISTINCT FROM OLD.attrs);
    END IF;
    RETURN NEW;
END
$$;
CREATE TRIGGER tg_mst_equip_attrs BEFORE INSERT OR UPDATE OF attrs, class_id ON mst_equip
    FOR EACH ROW EXECUTE FUNCTION hb_check_equip_attrs();

-- ---------------------------------------------------------------------
-- 4. 감사 · RLS · 권한 · 화면 · 보존
-- ---------------------------------------------------------------------
CREATE TRIGGER tg_audit AFTER INSERT OR UPDATE OR DELETE ON mst_item_class FOR EACH ROW EXECUTE FUNCTION hb_audit('class_id', '', '');
CREATE TRIGGER tg_audit AFTER INSERT OR UPDATE OR DELETE ON mst_class_prop FOR EACH ROW EXECUTE FUNCTION hb_audit('prop_id', '', '');

ALTER TABLE mst_item_class ENABLE ROW LEVEL SECURITY;
ALTER TABLE mst_item_class FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON mst_item_class USING (comp_cd = hb_current_comp()) WITH CHECK (comp_cd = hb_current_comp());
ALTER TABLE mst_class_prop ENABLE ROW LEVEL SECURITY;
ALTER TABLE mst_class_prop FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON mst_class_prop USING (comp_cd = hb_current_comp()) WITH CHECK (comp_cd = hb_current_comp());
REVOKE DELETE, TRUNCATE ON mst_item_class, mst_class_prop FROM ${app_role};

INSERT INTO sys_prog (prog_cd, prog_nm_key, view_path, module_cd, actions, sort_no) VALUES
    ('mst-class-prop', 'prog.mstClassProp', 'master/class-prop-mgt', 'MST', ARRAY['SEARCH','SAVE']::varchar(20)[], 161);
INSERT INTO sys_retention_table (table_nm, class_cd) VALUES ('mst_item_class', 'MASTER'), ('mst_class_prop', 'MASTER');
