-- =====================================================================
-- V024 특성 마스터·불량 유형 2단·용어 사전 (P1 6단계 — docs/21)
--
-- - qms_char: 검사·SPC·FMEA·CP·PPAP 공통 키. 제품 특성(품목 + 풍선 번호) / 공정 특성(공정 + 번호)
-- - 불량 유형 = 표준 상위 DEFECT_CAT(시스템 코드) + 회사 하위 qms_defect
-- - 용어 사전 = 전역 sys_term(Flyway 로만) + 회사 동의어 mst_term_alias
-- =====================================================================

INSERT INTO sys_code_grp (grp_cd, grp_nm_key, comp_ext_yn) VALUES
    ('SPECIAL_CHAR',   'code.grp.SPECIAL_CHAR',   'Y'),     -- 고객 기호는 회사가 추가
    ('CHAR_SOURCE',    'code.grp.CHAR_SOURCE',    'N'),
    ('CHAR_TYPE',      'code.grp.CHAR_TYPE',      'N'),
    ('CHAR_DATA_TYPE', 'code.grp.CHAR_DATA_TYPE', 'N'),
    ('DEFECT_CAT',     'code.grp.DEFECT_CAT',     'N');     -- 표준 상위는 회사가 늘리지 않는다(회사 간·AI 라벨 공통)
INSERT INTO sys_code (grp_cd, code, code_nm_key, sort_no) VALUES
    ('SPECIAL_CHAR', 'NONE', 'code.SPECIAL_CHAR.NONE', 1),
    ('SPECIAL_CHAR', 'CC',   'code.SPECIAL_CHAR.CC',   2),
    ('SPECIAL_CHAR', 'SC',   'code.SPECIAL_CHAR.SC',   3),
    ('CHAR_SOURCE', 'DRAWING',  'code.CHAR_SOURCE.DRAWING',  1),
    ('CHAR_SOURCE', 'CUSTOMER', 'code.CHAR_SOURCE.CUSTOMER', 2),
    ('CHAR_SOURCE', 'FMEA',     'code.CHAR_SOURCE.FMEA',     3),
    ('CHAR_SOURCE', 'SPEC',     'code.CHAR_SOURCE.SPEC',     4),
    ('CHAR_TYPE', 'PRODUCT', 'code.CHAR_TYPE.PRODUCT', 1),
    ('CHAR_TYPE', 'PROCESS', 'code.CHAR_TYPE.PROCESS', 2),
    ('CHAR_DATA_TYPE', 'VARIABLE',  'code.CHAR_DATA_TYPE.VARIABLE',  1),
    ('CHAR_DATA_TYPE', 'ATTRIBUTE', 'code.CHAR_DATA_TYPE.ATTRIBUTE', 2),
    ('DEFECT_CAT', 'APPEARANCE', 'code.DEFECT_CAT.APPEARANCE', 1),
    ('DEFECT_CAT', 'DIMENSION',  'code.DEFECT_CAT.DIMENSION',  2),
    ('DEFECT_CAT', 'FUNCTION',   'code.DEFECT_CAT.FUNCTION',   3),
    ('DEFECT_CAT', 'MATERIAL',   'code.DEFECT_CAT.MATERIAL',   4),
    ('DEFECT_CAT', 'WELD',       'code.DEFECT_CAT.WELD',       5),
    ('DEFECT_CAT', 'ASSEMBLY',   'code.DEFECT_CAT.ASSEMBLY',   6),
    ('DEFECT_CAT', 'CONTAM',     'code.DEFECT_CAT.CONTAM',     7),
    ('DEFECT_CAT', 'LABEL',      'code.DEFECT_CAT.LABEL',      8),
    ('DEFECT_CAT', 'OTHER',      'code.DEFECT_CAT.OTHER',      9);

-- 코드가 시스템 코드 또는 그 회사가 추가한 사용 중 코드인지 (hb_unit_usable 의 일반형)
CREATE FUNCTION hb_code_usable(p_comp varchar, p_grp varchar, p_code varchar) RETURNS boolean
    LANGUAGE sql STABLE
AS $$
    SELECT EXISTS (SELECT 1 FROM sys_code WHERE grp_cd = p_grp AND code = p_code AND use_yn = 'Y')
        OR EXISTS (SELECT 1 FROM sys_comp_code WHERE comp_cd = p_comp AND grp_cd = p_grp AND code = p_code AND use_yn = 'Y')
$$;

-- ---------------------------------------------------------------------
-- 1. 특성 마스터
-- ---------------------------------------------------------------------
CREATE TABLE qms_char (
    char_id      bigint         GENERATED ALWAYS AS IDENTITY,
    comp_cd      varchar(20)    NOT NULL,
    char_type    varchar(10)    NOT NULL,
    item_id      bigint,
    proc_id      bigint,
    char_no      varchar(20)    NOT NULL,
    char_nm      varchar(200)   NOT NULL,
    data_type    varchar(10)    NOT NULL DEFAULT 'VARIABLE',
    unit_nm      varchar(20),
    scale        smallint       NOT NULL DEFAULT 2,
    nominal      numeric(18,6),
    lsl          numeric(18,6),
    usl          numeric(18,6),
    special_cls  varchar(30)    NOT NULL DEFAULT 'NONE',
    source       varchar(10)    NOT NULL DEFAULT 'DRAWING',
    drawing_no   varchar(50),
    drawing_rev  varchar(10),
    sort_no      integer        NOT NULL DEFAULT 0,
    use_yn       char(1)        NOT NULL DEFAULT 'Y',
    remark       varchar(1000),
    row_ver      integer        NOT NULL DEFAULT 0,
    created_at   timestamptz    NOT NULL DEFAULT now(),
    created_by   varchar(50)    NOT NULL,
    updated_at   timestamptz    NOT NULL DEFAULT now(),
    updated_by   varchar(50)    NOT NULL,
    CONSTRAINT pk_qms_char PRIMARY KEY (char_id),
    CONSTRAINT fk_qms_char_comp FOREIGN KEY (comp_cd) REFERENCES sys_comp (comp_cd),
    CONSTRAINT fk_qms_char_item FOREIGN KEY (item_id) REFERENCES mst_item (item_id),
    CONSTRAINT fk_qms_char_proc FOREIGN KEY (proc_id) REFERENCES mst_proc (proc_id),
    CONSTRAINT ck_qms_char_type CHECK (char_type IN ('PRODUCT', 'PROCESS')),
    -- 제품 특성은 품목 필수, 공정 특성은 공정 필수
    CONSTRAINT ck_qms_char_target CHECK ((char_type = 'PRODUCT' AND item_id IS NOT NULL)
                                         OR (char_type = 'PROCESS' AND proc_id IS NOT NULL)),
    CONSTRAINT ck_qms_char_no CHECK (char_no ~ '^[A-Z0-9][A-Z0-9._-]{0,19}$'),
    CONSTRAINT ck_qms_char_nm CHECK (length(btrim(char_nm)) > 0),
    CONSTRAINT ck_qms_char_data_type CHECK (data_type IN ('VARIABLE', 'ATTRIBUTE')),
    CONSTRAINT ck_qms_char_scale CHECK (scale BETWEEN 0 AND 6),
    -- 계량: 하한·상한 중 하나 이상, 하한 ≤ 공칭 ≤ 상한 / 계수: 수치 없음
    CONSTRAINT ck_qms_char_spec CHECK (
        (data_type = 'ATTRIBUTE' AND nominal IS NULL AND lsl IS NULL AND usl IS NULL)
        OR (data_type = 'VARIABLE' AND (lsl IS NOT NULL OR usl IS NOT NULL)
            AND (lsl IS NULL OR usl IS NULL OR lsl < usl)
            AND (nominal IS NULL OR ((lsl IS NULL OR lsl <= nominal) AND (usl IS NULL OR nominal <= usl))))),
    CONSTRAINT ck_qms_char_source CHECK (source IN ('DRAWING', 'CUSTOMER', 'FMEA', 'SPEC')),
    CONSTRAINT ck_qms_char_use_yn CHECK (use_yn IN ('Y', 'N'))
);
CREATE UNIQUE INDEX uq_qms_char_item_no ON qms_char (comp_cd, item_id, char_no) WHERE item_id IS NOT NULL;
CREATE UNIQUE INDEX uq_qms_char_proc_no ON qms_char (comp_cd, proc_id, char_no) WHERE item_id IS NULL;
CREATE INDEX ix_qms_char_proc ON qms_char (comp_cd, proc_id) WHERE proc_id IS NOT NULL;
COMMENT ON TABLE qms_char IS '특성 마스터 — 검사·SPC·FMEA·관리계획·PPAP 공통 키 (docs/21). 행 리비전 없음: 규격 변경은 감사 이력 + 도면 리비전, 측정값은 규격을 스냅샷';
COMMENT ON COLUMN qms_char.char_no IS '도면 풍선 번호 (제품 특성은 품목 안, 품목 없는 공정 특성은 공정 안 유일)';
COMMENT ON COLUMN qms_char.scale IS '측정 자릿수 — 측정 화면·그리드가 쓴다 (docs/09)';

CREATE FUNCTION hb_guard_qms_char() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'characteristic cannot be deleted (use use_yn = N)' USING ERRCODE = '55000', CONSTRAINT = 'ck_qms_char_no_delete';
    END IF;
    IF NEW.item_id IS NOT NULL AND (TG_OP = 'INSERT' OR NEW.item_id IS DISTINCT FROM OLD.item_id)
       AND NOT hb_item_of_comp(NEW.comp_cd, NEW.item_id) THEN
        RAISE EXCEPTION 'item % does not belong to company %', NEW.item_id, NEW.comp_cd
            USING ERRCODE = '23503', CONSTRAINT = 'fk_qms_char_item_comp';
    END IF;
    IF NEW.proc_id IS NOT NULL AND (TG_OP = 'INSERT' OR NEW.proc_id IS DISTINCT FROM OLD.proc_id)
       AND NOT EXISTS (SELECT 1 FROM mst_proc p WHERE p.proc_id = NEW.proc_id AND p.comp_cd = NEW.comp_cd) THEN
        RAISE EXCEPTION 'process % does not belong to company %', NEW.proc_id, NEW.comp_cd
            USING ERRCODE = '23503', CONSTRAINT = 'fk_qms_char_proc_comp';
    END IF;
    IF (TG_OP = 'INSERT' OR NEW.special_cls <> OLD.special_cls) AND NOT hb_code_usable(NEW.comp_cd, 'SPECIAL_CHAR', NEW.special_cls) THEN
        RAISE EXCEPTION 'special characteristic class % is not available for company %', NEW.special_cls, NEW.comp_cd
            USING ERRCODE = '23503', CONSTRAINT = 'fk_qms_char_special';
    END IF;
    RETURN NEW;
END
$$;
CREATE TRIGGER tg_qms_char_guard BEFORE INSERT OR UPDATE OR DELETE ON qms_char
    FOR EACH ROW EXECUTE FUNCTION hb_guard_qms_char();

-- ---------------------------------------------------------------------
-- 2. 불량 유형 (회사 하위 — 상위는 DEFECT_CAT)
-- ---------------------------------------------------------------------
CREATE TABLE qms_defect (
    defect_id   bigint        GENERATED ALWAYS AS IDENTITY,
    comp_cd     varchar(20)   NOT NULL,
    defect_cd   varchar(20)   NOT NULL,
    defect_nm   varchar(100)  NOT NULL,
    cat_cd      varchar(20)   NOT NULL,
    sort_no     integer       NOT NULL DEFAULT 0,
    use_yn      char(1)       NOT NULL DEFAULT 'Y',
    remark      varchar(500),
    row_ver     integer       NOT NULL DEFAULT 0,
    created_at  timestamptz   NOT NULL DEFAULT now(),
    created_by  varchar(50)   NOT NULL,
    updated_at  timestamptz   NOT NULL DEFAULT now(),
    updated_by  varchar(50)   NOT NULL,
    CONSTRAINT pk_qms_defect PRIMARY KEY (defect_id),
    CONSTRAINT fk_qms_defect_comp FOREIGN KEY (comp_cd) REFERENCES sys_comp (comp_cd),
    CONSTRAINT uq_qms_defect_cd UNIQUE (comp_cd, defect_cd),
    CONSTRAINT ck_qms_defect_cd CHECK (defect_cd ~ '^[A-Z0-9][A-Z0-9_-]{0,19}$'),
    CONSTRAINT ck_qms_defect_nm CHECK (length(btrim(defect_nm)) > 0),
    CONSTRAINT ck_qms_defect_cat CHECK (cat_cd IN ('APPEARANCE', 'DIMENSION', 'FUNCTION', 'MATERIAL', 'WELD', 'ASSEMBLY',
                                                  'CONTAM', 'LABEL', 'OTHER')),
    CONSTRAINT ck_qms_defect_use_yn CHECK (use_yn IN ('Y', 'N'))
);
COMMENT ON TABLE qms_defect IS '불량 유형 (회사 하위). 상위 = 시스템 코드 DEFECT_CAT — 실적·NCR·비전 재판정 공통 라벨 (docs/21, docs/03)';

CREATE FUNCTION hb_forbid_delete_master() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION '% rows cannot be deleted (use use_yn = N)', TG_TABLE_NAME
        USING ERRCODE = '55000', CONSTRAINT = 'ck_' || TG_TABLE_NAME || '_no_delete';
END
$$;
CREATE TRIGGER tg_qms_defect_no_delete BEFORE DELETE ON qms_defect FOR EACH ROW EXECUTE FUNCTION hb_forbid_delete_master();

-- ---------------------------------------------------------------------
-- 3. 용어 사전
-- ---------------------------------------------------------------------
CREATE TABLE sys_term (
    term_cd   varchar(30)    NOT NULL,
    category  varchar(10)    NOT NULL,
    term_ko   varchar(100)   NOT NULL,
    term_en   varchar(100)   NOT NULL,
    def_ko    varchar(1000)  NOT NULL,
    def_en    varchar(1000)  NOT NULL,
    sort_no   integer        NOT NULL DEFAULT 0,
    CONSTRAINT pk_sys_term PRIMARY KEY (term_cd),
    CONSTRAINT ck_sys_term_cd CHECK (term_cd ~ '^[A-Z0-9_]{1,30}$'),
    CONSTRAINT ck_sys_term_category CHECK (category IN ('KPI', 'QUALITY', 'PROD', 'EQUIP'))
);
COMMENT ON TABLE sys_term IS '용어 사전 (전역) — 지표·용어 정의. 내용은 Flyway 로만, P5 에서 RAG·번역 용어집으로 (docs/21, docs/03)';
REVOKE INSERT, UPDATE, DELETE, TRUNCATE ON sys_term FROM ${app_role};

INSERT INTO sys_term (term_cd, category, term_ko, term_en, def_ko, def_en, sort_no) VALUES
    ('INPUT_QTY',    'PROD',    '투입 수량', 'Input quantity',
     '공정에 투입한 수량. 양품 + 폐기 + 재작업 + 수리 대기의 합과 같아야 한다.',
     'Quantity started in an operation. It equals good + scrap + rework + repair pending.', 10),
    ('GOOD_QTY',     'PROD',    '양품 수량', 'Good quantity',
     '첫 번에 규격을 만족해 다음 공정·입고로 넘긴 수량(재작업 후 양품은 따로 센다).',
     'Quantity that met the specification the first time and moved on (good after rework is counted separately).', 20),
    ('SCRAP_QTY',    'PROD',    '폐기 수량', 'Scrap quantity',
     '고칠 수 없어 버린 수량. 불량 유형 코드가 붙는다.', 'Quantity discarded because it cannot be fixed. It carries a defect code.', 30),
    ('REWORK_QTY',   'PROD',    '재작업 수량', 'Rework quantity',
     '규격을 벗어나 같은 공정을 다시 거쳐 고친 수량.', 'Quantity out of specification that was fixed by repeating the operation.', 40),
    ('DEFECT_RATE',  'QUALITY', '불량률', 'Defect rate',
     '(폐기 + 재작업) ÷ 투입 수량. 백분율로 보인다.', '(scrap + rework) / input quantity, shown as a percentage.', 50),
    ('PPM',          'QUALITY', 'PPM', 'PPM',
     '불량 수 ÷ 수량 × 1,000,000. 고객 클레임 PPM 은 납품 수량 기준.', 'Defects / quantity x 1,000,000. Customer PPM uses delivered quantity.', 60),
    ('CPK',          'QUALITY', '공정능력지수 Cpk', 'Process capability Cpk',
     'min(상한 − 평균, 평균 − 하한) ÷ 3σ. 특성 마스터의 하한·상한을 쓴다.', 'min(USL - mean, mean - LSL) / 3 sigma, using the limits in the characteristic master.', 70),
    ('SPECIAL_CHAR', 'QUALITY', '특별특성', 'Special characteristic',
     '안전·법규(CC) 또는 기능·조립(SC)에 영향이 커 관리계획에서 따로 관리하는 특성.',
     'A characteristic with a large effect on safety/regulation (CC) or fit/function (SC), controlled separately in the control plan.', 80),
    ('CYCLE_TIME',   'EQUIP',   '사이클타임', 'Cycle time',
     '한 사이클(캐비티 수만큼 생산)에 걸리는 표준 초. 라우팅 단계에 있다.', 'Standard seconds for one cycle (producing as many parts as cavities). Kept on routing operations.', 90),
    ('AVAILABILITY', 'KPI',     '시간 가동률', 'Availability',
     '가동 시간 ÷ 계획 가동 시간.', 'Run time / planned production time.', 100),
    ('PERFORMANCE',  'KPI',     '성능 가동률', 'Performance',
     '(이상적 1개 시간 × 생산 수량) ÷ 가동 시간. 이상적 1개 시간 = 사이클타임 ÷ 사이클당 수량.',
     '(ideal time per part x quantity) / run time. Ideal time per part = cycle time / parts per cycle.', 110),
    ('QUALITY_RATE', 'KPI',     '양품률', 'Quality rate',
     '양품 수량 ÷ 생산 수량.', 'Good quantity / total quantity.', 120),
    ('OEE',          'KPI',     '설비 종합효율', 'OEE',
     '시간 가동률 × 성능 가동률 × 양품률.', 'Availability x performance x quality rate.', 130);

CREATE TABLE mst_term_alias (
    alias_id    bigint        GENERATED ALWAYS AS IDENTITY,
    comp_cd     varchar(20)   NOT NULL,
    term_cd     varchar(30)   NOT NULL,
    alias       varchar(100)  NOT NULL,
    lang        varchar(5)    NOT NULL DEFAULT 'ko',
    use_yn      char(1)       NOT NULL DEFAULT 'Y',
    row_ver     integer       NOT NULL DEFAULT 0,
    created_at  timestamptz   NOT NULL DEFAULT now(),
    created_by  varchar(50)   NOT NULL,
    updated_at  timestamptz   NOT NULL DEFAULT now(),
    updated_by  varchar(50)   NOT NULL,
    CONSTRAINT pk_mst_term_alias PRIMARY KEY (alias_id),
    CONSTRAINT fk_mst_term_alias_comp FOREIGN KEY (comp_cd) REFERENCES sys_comp (comp_cd),
    CONSTRAINT fk_mst_term_alias_term FOREIGN KEY (term_cd) REFERENCES sys_term (term_cd),
    CONSTRAINT ck_mst_term_alias_alias CHECK (length(btrim(alias)) > 0),
    CONSTRAINT ck_mst_term_alias_lang CHECK (lang IN ('ko', 'en', 'vi')),
    CONSTRAINT ck_mst_term_alias_use_yn CHECK (use_yn IN ('Y', 'N'))
);
-- 한 말이 두 용어를 가리키지 않게 (대소문자·앞뒤 공백 무시)
CREATE UNIQUE INDEX uq_mst_term_alias ON mst_term_alias (comp_cd, lower(btrim(alias)));
COMMENT ON TABLE mst_term_alias IS '용어 사전 회사 동의어 (현장 말 → 전역 용어). 자연어 조회·번역 용어집이 쓴다(P5)';
CREATE TRIGGER tg_mst_term_alias_no_delete BEFORE DELETE ON mst_term_alias FOR EACH ROW EXECUTE FUNCTION hb_forbid_delete_master();

-- ---------------------------------------------------------------------
-- 4. 감사 · RLS · 권한 · 화면 · 보존
-- ---------------------------------------------------------------------
CREATE TRIGGER tg_audit AFTER INSERT OR UPDATE OR DELETE ON qms_char FOR EACH ROW EXECUTE FUNCTION hb_audit('char_id', '', '');
CREATE TRIGGER tg_audit AFTER INSERT OR UPDATE OR DELETE ON qms_defect FOR EACH ROW EXECUTE FUNCTION hb_audit('defect_id', '', '');
CREATE TRIGGER tg_audit AFTER INSERT OR UPDATE OR DELETE ON mst_term_alias FOR EACH ROW EXECUTE FUNCTION hb_audit('alias_id', '', '');

ALTER TABLE qms_char ENABLE ROW LEVEL SECURITY;
ALTER TABLE qms_char FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON qms_char USING (comp_cd = hb_current_comp()) WITH CHECK (comp_cd = hb_current_comp());
ALTER TABLE qms_defect ENABLE ROW LEVEL SECURITY;
ALTER TABLE qms_defect FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON qms_defect USING (comp_cd = hb_current_comp()) WITH CHECK (comp_cd = hb_current_comp());
ALTER TABLE mst_term_alias ENABLE ROW LEVEL SECURITY;
ALTER TABLE mst_term_alias FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON mst_term_alias USING (comp_cd = hb_current_comp()) WITH CHECK (comp_cd = hb_current_comp());
REVOKE DELETE, TRUNCATE ON qms_char, qms_defect, mst_term_alias FROM ${app_role};

INSERT INTO sys_prog (prog_cd, prog_nm_key, view_path, module_cd, actions, sort_no) VALUES
    ('mst-char',   'prog.mstChar',   'master/char-mgt',   'MST', ARRAY['SEARCH','SAVE','EXCEL']::varchar(20)[], 150),
    ('mst-defect', 'prog.mstDefect', 'master/defect-mgt', 'MST', ARRAY['SEARCH','SAVE']::varchar(20)[], 151),
    ('mst-term',   'prog.mstTerm',   'master/term-mgt',   'MST', ARRAY['SEARCH','SAVE']::varchar(20)[], 152);
INSERT INTO sys_retention_table (table_nm, class_cd) VALUES
    ('qms_char', 'MASTER'), ('qms_defect', 'MASTER'), ('mst_term_alias', 'MASTER');
