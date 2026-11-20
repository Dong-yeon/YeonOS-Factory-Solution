-- =====================================================================
-- V013 품목 마스터 · 단위 코드 (P1 1단계, docs/17 3장)
--
-- 품목은 이후 BOM·라우팅·재고·검사가 모두 참조하는 기준정보다.
-- 단위의 소수 자릿수는 공통코드 속성(attrs.scale) — 회사 코드(sys_comp_code.attrs)와 같은 모양.
-- =====================================================================

-- ---------------------------------------------------------------------
-- 1. 시스템 공통코드 속성 (회사 코드 sys_comp_code.attrs 와 같은 모양)
-- ---------------------------------------------------------------------
ALTER TABLE sys_code ADD COLUMN attrs jsonb NOT NULL DEFAULT '{}'::jsonb;
ALTER TABLE sys_code ADD CONSTRAINT ck_sys_code_attrs CHECK (jsonb_typeof(attrs) = 'object');
COMMENT ON COLUMN sys_code.attrs IS '코드 속성 (예: UNIT 의 {"scale": 소수 자릿수})';

-- ---------------------------------------------------------------------
-- 2. 코드: 단위(V006 의 EA·KG·M 에 소수 자릿수 + 추가) · 품목 유형 · 조달 구분
--    품목 유형·조달 구분은 BOM·재고·지시 로직이 값으로 분기하므로 회사 추가 불가(docs/17 P1-2)
-- ---------------------------------------------------------------------
UPDATE sys_code SET attrs = '{"scale": 0}', sort_no = 1  WHERE grp_cd = 'UNIT' AND code = 'EA';
UPDATE sys_code SET attrs = '{"scale": 3}', sort_no = 10 WHERE grp_cd = 'UNIT' AND code = 'KG';
UPDATE sys_code SET attrs = '{"scale": 3}', sort_no = 20 WHERE grp_cd = 'UNIT' AND code = 'M';

INSERT INTO sys_code (grp_cd, code, code_nm_key, sort_no, attrs) VALUES
    ('UNIT', 'SET',   'code.UNIT.SET',   2,  '{"scale": 0}'),
    ('UNIT', 'BOX',   'code.UNIT.BOX',   3,  '{"scale": 0}'),
    ('UNIT', 'ROLL',  'code.UNIT.ROLL',  4,  '{"scale": 0}'),
    ('UNIT', 'SHEET', 'code.UNIT.SHEET', 5,  '{"scale": 0}'),
    ('UNIT', 'G',     'code.UNIT.G',     11, '{"scale": 3}'),
    ('UNIT', 'TON',   'code.UNIT.TON',   12, '{"scale": 3}'),
    ('UNIT', 'MM',    'code.UNIT.MM',    21, '{"scale": 3}'),
    ('UNIT', 'L',     'code.UNIT.L',     30, '{"scale": 3}'),
    ('UNIT', 'ML',    'code.UNIT.ML',    31, '{"scale": 3}');

INSERT INTO sys_code_grp (grp_cd, grp_nm_key, comp_ext_yn) VALUES
    ('ITEM_TYPE', 'code.grp.ITEM_TYPE', 'N'),
    ('PROC_TYPE', 'code.grp.PROC_TYPE', 'N');

INSERT INTO sys_code (grp_cd, code, code_nm_key, sort_no) VALUES
    ('ITEM_TYPE', 'FG',     'code.ITEM_TYPE.FG',     1),
    ('ITEM_TYPE', 'SFG',    'code.ITEM_TYPE.SFG',    2),
    ('ITEM_TYPE', 'RM',     'code.ITEM_TYPE.RM',     3),
    ('ITEM_TYPE', 'SUB',    'code.ITEM_TYPE.SUB',    4),
    ('ITEM_TYPE', 'CON',    'code.ITEM_TYPE.CON',    5),
    ('PROC_TYPE', 'MAKE',   'code.PROC_TYPE.MAKE',   1),
    ('PROC_TYPE', 'BUY',    'code.PROC_TYPE.BUY',    2),
    ('PROC_TYPE', 'OUTSRC', 'code.PROC_TYPE.OUTSRC', 3);

-- ---------------------------------------------------------------------
-- 3. 품목
-- ---------------------------------------------------------------------
CREATE TABLE mst_item (
    item_id      bigint         GENERATED ALWAYS AS IDENTITY,
    comp_cd      varchar(20)    NOT NULL,
    item_cd      varchar(40)    NOT NULL,
    item_nm      varchar(200)   NOT NULL,
    spec         varchar(200),
    item_type    varchar(10)    NOT NULL,
    proc_type    varchar(10)    NOT NULL,
    unit_cd      varchar(30)    NOT NULL,
    lot_ctrl_yn  char(1)        NOT NULL DEFAULT 'Y',
    net_mass_kg  numeric(15,6),
    hs_cd        varchar(10),
    remark       varchar(1000),
    use_yn       char(1)        NOT NULL DEFAULT 'Y',
    row_ver      integer        NOT NULL DEFAULT 0,
    created_at   timestamptz    NOT NULL DEFAULT now(),
    created_by   varchar(50)    NOT NULL,
    updated_at   timestamptz    NOT NULL DEFAULT now(),
    updated_by   varchar(50)    NOT NULL,
    CONSTRAINT pk_mst_item PRIMARY KEY (item_id),
    CONSTRAINT fk_mst_item_comp FOREIGN KEY (comp_cd) REFERENCES sys_comp (comp_cd),
    CONSTRAINT uq_mst_item_cd UNIQUE (comp_cd, item_cd),
    -- 자동차 부품 품번은 84910-2S000, AB.123/4 처럼 - . / 를 쓴다
    CONSTRAINT ck_mst_item_cd CHECK (item_cd ~ '^[A-Z0-9][A-Z0-9._/-]{0,39}$'),
    CONSTRAINT ck_mst_item_nm CHECK (length(btrim(item_nm)) > 0),
    CONSTRAINT ck_mst_item_type CHECK (item_type IN ('FG', 'SFG', 'RM', 'SUB', 'CON')),
    CONSTRAINT ck_mst_item_proc CHECK (proc_type IN ('MAKE', 'BUY', 'OUTSRC')),
    CONSTRAINT ck_mst_item_lot CHECK (lot_ctrl_yn IN ('Y', 'N')),
    CONSTRAINT ck_mst_item_mass CHECK (net_mass_kg > 0),
    CONSTRAINT ck_mst_item_hs CHECK (hs_cd ~ '^[0-9]{6,10}$'),
    CONSTRAINT ck_mst_item_use_yn CHECK (use_yn IN ('Y', 'N'))
);
CREATE INDEX ix_mst_item_nm ON mst_item (comp_cd, item_nm);
CREATE INDEX ix_mst_item_type ON mst_item (comp_cd, item_type);
COMMENT ON TABLE mst_item IS '품목 (P1 1단계). 리비전은 BOM·라우팅에만(docs/17 P1-3). ERP·고객 품번은 mst_ext_id_map(P1 7단계)';
COMMENT ON COLUMN mst_item.net_mass_kg IS '기본 단위 1개당 순질량 kg (docs/08 D3 — 탄소 배분·CBAM)';
COMMENT ON COLUMN mst_item.hs_cd IS 'HS/CN 코드 숫자 6~10자리 (docs/08 D3)';

-- 단위는 시스템 단위 또는 **같은 회사**가 추가한 단위(사용 중) — 회사 코드는 RLS 테이블이라 FK 를 걸 수 없어 트리거로.
-- 사용 중지는 새로 고를 때만 막는다(이미 쓰던 품목의 단위가 나중에 사용 중지돼도 그 품목 수정은 가능).
CREATE FUNCTION hb_check_item_unit() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'UPDATE' AND NEW.unit_cd = OLD.unit_cd THEN
        RETURN NEW;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM sys_code WHERE grp_cd = 'UNIT' AND code = NEW.unit_cd AND use_yn = 'Y')
       AND NOT EXISTS (SELECT 1 FROM sys_comp_code
                        WHERE comp_cd = NEW.comp_cd AND grp_cd = 'UNIT' AND code = NEW.unit_cd AND use_yn = 'Y') THEN
        RAISE EXCEPTION 'unit % is not available for company %', NEW.unit_cd, NEW.comp_cd
            USING ERRCODE = '23503', CONSTRAINT = 'fk_mst_item_unit';
    END IF;
    RETURN NEW;
END
$$;
CREATE TRIGGER tg_mst_item_unit BEFORE INSERT OR UPDATE ON mst_item
    FOR EACH ROW EXECUTE FUNCTION hb_check_item_unit();

CREATE TRIGGER tg_audit AFTER INSERT OR UPDATE OR DELETE ON mst_item
    FOR EACH ROW EXECUTE FUNCTION hb_audit('item_id', '', '');

ALTER TABLE mst_item ENABLE ROW LEVEL SECURITY;
ALTER TABLE mst_item FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON mst_item USING (comp_cd = hb_current_comp()) WITH CHECK (comp_cd = hb_current_comp());
-- 기준정보는 삭제 대신 사용 안 함(use_yn)
REVOKE DELETE, TRUNCATE ON mst_item FROM ${app_role};

-- ---------------------------------------------------------------------
-- 4. 화면 · 보존
-- ---------------------------------------------------------------------
INSERT INTO sys_prog (prog_cd, prog_nm_key, view_path, module_cd, actions, sort_no)
VALUES ('mst-item', 'prog.mstItem', 'master/item-mgt', 'MST', ARRAY['SEARCH','SAVE','DELETE','EXCEL']::varchar(20)[], 110);
INSERT INTO sys_retention_table (table_nm, class_cd) VALUES ('mst_item', 'MASTER');
