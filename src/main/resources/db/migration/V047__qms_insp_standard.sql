-- =====================================================================
-- V047 P3 QMS Q1 검사 기준 — 샘플링 계획 · AQL 표 · 검사계획(리비전) (docs/29 2장 Q1, 결정 D1~D3·D6)
--
-- - AQL 표는 시드가 없다(D3, 표준 원문 저작권): 회사가 엑셀로 올린다. 로트 크기 → 시료 문자(qms_aql_table),
--   시료 문자 × AQL × 엄격도 → n·Ac·Re(qms_aql_plan_row). 화살표는 올리는 쪽이 풀어서 n·Ac·Re 로 넣는다
-- - 샘플링 계획 = FIXED(고정 n) / ALL(전수) / PERCENT(비율) / AQL(표 참조). 확정·폐기 검사계획이 쓰는 계획은 방식·수치 변경 금지
-- - 검사계획 머리 = 검사 유형 × (품목 또는 품목 클래스) × 공정(선택) × 대체 × 리비전 — BOM·라우팅 리비전 규칙 그대로
--   (작성 중만 편집, 확정본 불변, 확정본 기간 겹침 배제, 대상 키 불변, 작성 중 하나)
-- - 행 = 특성 × 방법 × 계측기 클래스(또는 VISUAL) × 샘플링 × 주기 + 확정 때 특성 규격 스냅샷.
--   스냅샷은 머리 트리거가 DRAFT → RELEASED 순간에 qms_char 에서 복사한다 — 어떤 경로로 확정해도 빠지지 않게
-- - 특성은 계획 대상과 맞아야 한다: 제품 특성 = 같은 품목(품목 계획만), 공정 특성 = 계획 공정과 같거나 계획에 공정이 없음
--   (qms_char 에 품목 클래스 단위 제품 특성이 없다 — 클래스 계획은 검사 공정의 공정 특성을 쓴다, docs/29 Q1 상세)
-- 병렬 브랜치도 V047 를 쓴다 — main 에 늦게 들어가는 쪽이 번호를 다시 매긴다(docs/29 D10).
-- =====================================================================

INSERT INTO sys_code_grp (grp_cd, grp_nm_key, comp_ext_yn) VALUES
    ('INSP_TYPE',       'code.grp.INSP_TYPE',       'N'),
    ('SAMPLING_METHOD', 'code.grp.SAMPLING_METHOD', 'N'),
    ('AQL_SEVERITY',    'code.grp.AQL_SEVERITY',    'N'),
    ('INSP_LEVEL',      'code.grp.INSP_LEVEL',      'N'),
    ('INSP_FREQ',       'code.grp.INSP_FREQ',       'N'),
    ('GAUGE_CLASS',     'code.grp.GAUGE_CLASS',     'Y');     -- 회사가 계측기 종류를 추가
INSERT INTO sys_code (grp_cd, code, code_nm_key, sort_no) VALUES
    ('INSP_TYPE', 'RECEIVE',   'code.INSP_TYPE.RECEIVE',   1),
    ('INSP_TYPE', 'PROCESS',   'code.INSP_TYPE.PROCESS',   2),
    ('INSP_TYPE', 'FINAL',     'code.INSP_TYPE.FINAL',     3),
    ('INSP_TYPE', 'FIRST_OFF', 'code.INSP_TYPE.FIRST_OFF', 4),
    ('INSP_TYPE', 'LAST_OFF',  'code.INSP_TYPE.LAST_OFF',  5),
    ('INSP_TYPE', 'PATROL',    'code.INSP_TYPE.PATROL',    6),
    ('SAMPLING_METHOD', 'FIXED',   'code.SAMPLING_METHOD.FIXED',   1),
    ('SAMPLING_METHOD', 'ALL',     'code.SAMPLING_METHOD.ALL',     2),
    ('SAMPLING_METHOD', 'PERCENT', 'code.SAMPLING_METHOD.PERCENT', 3),
    ('SAMPLING_METHOD', 'AQL',     'code.SAMPLING_METHOD.AQL',     4),
    ('AQL_SEVERITY', 'NORMAL',    'code.AQL_SEVERITY.NORMAL',    1),
    ('AQL_SEVERITY', 'TIGHTENED', 'code.AQL_SEVERITY.TIGHTENED', 2),
    ('AQL_SEVERITY', 'REDUCED',   'code.AQL_SEVERITY.REDUCED',   3),
    ('INSP_LEVEL', 'I',   'code.INSP_LEVEL.I',   1),
    ('INSP_LEVEL', 'II',  'code.INSP_LEVEL.II',  2),
    ('INSP_LEVEL', 'III', 'code.INSP_LEVEL.III', 3),
    ('INSP_LEVEL', 'S1',  'code.INSP_LEVEL.S1',  4),
    ('INSP_LEVEL', 'S2',  'code.INSP_LEVEL.S2',  5),
    ('INSP_LEVEL', 'S3',  'code.INSP_LEVEL.S3',  6),
    ('INSP_LEVEL', 'S4',  'code.INSP_LEVEL.S4',  7),
    ('INSP_FREQ', 'EVERY_LOT',   'code.INSP_FREQ.EVERY_LOT',   1),
    ('INSP_FREQ', 'START',       'code.INSP_FREQ.START',       2),
    ('INSP_FREQ', 'END',         'code.INSP_FREQ.END',         3),
    ('INSP_FREQ', 'EVERY_N',     'code.INSP_FREQ.EVERY_N',     4),
    ('INSP_FREQ', 'EVERY_HOURS', 'code.INSP_FREQ.EVERY_HOURS', 5),
    ('GAUGE_CLASS', 'VISUAL',     'code.GAUGE_CLASS.VISUAL',     1),
    ('GAUGE_CLASS', 'CALIPER',    'code.GAUGE_CLASS.CALIPER',    2),
    ('GAUGE_CLASS', 'MICROMETER', 'code.GAUGE_CLASS.MICROMETER', 3),
    ('GAUGE_CLASS', 'HEIGHT',     'code.GAUGE_CLASS.HEIGHT',     4),
    ('GAUGE_CLASS', 'DIAL',       'code.GAUGE_CLASS.DIAL',       5),
    ('GAUGE_CLASS', 'GO_NOGO',    'code.GAUGE_CLASS.GO_NOGO',    6),
    ('GAUGE_CLASS', 'CMM',        'code.GAUGE_CLASS.CMM',        7),
    ('GAUGE_CLASS', 'TORQUE',     'code.GAUGE_CLASS.TORQUE',     8),
    ('GAUGE_CLASS', 'TENSILE',    'code.GAUGE_CLASS.TENSILE',    9),
    ('GAUGE_CLASS', 'OTHER',      'code.GAUGE_CLASS.OTHER',      10);

-- ---------------------------------------------------------------------
-- 1. AQL 표 — 로트 크기 → 시료 문자
-- ---------------------------------------------------------------------
CREATE TABLE qms_aql_table (
    aql_table_id  bigint        GENERATED ALWAYS AS IDENTITY,
    comp_cd       varchar(20)   NOT NULL,
    std_cd        varchar(20)   NOT NULL,
    insp_level    varchar(3)    NOT NULL,
    lot_from      bigint        NOT NULL,
    lot_to        bigint,
    code_letter   varchar(1)    NOT NULL,
    use_yn        char(1)       NOT NULL DEFAULT 'Y',
    row_ver       integer       NOT NULL DEFAULT 0,
    created_at    timestamptz   NOT NULL DEFAULT now(),
    created_by    varchar(50)   NOT NULL,
    updated_at    timestamptz   NOT NULL DEFAULT now(),
    updated_by    varchar(50)   NOT NULL,
    CONSTRAINT pk_qms_aql_table PRIMARY KEY (aql_table_id),
    CONSTRAINT fk_qms_aql_table_comp FOREIGN KEY (comp_cd) REFERENCES sys_comp (comp_cd),
    CONSTRAINT uq_qms_aql_table UNIQUE (comp_cd, std_cd, insp_level, lot_from),
    CONSTRAINT ck_qms_aql_table_std CHECK (std_cd ~ '^[A-Z0-9][A-Z0-9._-]{0,19}$'),
    CONSTRAINT ck_qms_aql_table_level CHECK (insp_level IN ('I', 'II', 'III', 'S1', 'S2', 'S3', 'S4')),
    CONSTRAINT ck_qms_aql_table_lot CHECK (lot_from >= 1 AND (lot_to IS NULL OR lot_to >= lot_from)),
    CONSTRAINT ck_qms_aql_table_letter CHECK (code_letter ~ '^[A-Z]$'),
    CONSTRAINT ck_qms_aql_table_use_yn CHECK (use_yn IN ('Y', 'N')),
    -- 같은 표준·검사 수준에서 로트 구간이 겹치면 시료 문자가 둘이 된다. 올리기 한 번에 구간을 옮길 수 있게 커밋 시점 검사
    CONSTRAINT ex_qms_aql_table_lot EXCLUDE USING gist (
        comp_cd WITH =, std_cd WITH =, insp_level WITH =, int8range(lot_from, lot_to, '[]') WITH &&
    ) WHERE (use_yn = 'Y') DEFERRABLE INITIALLY DEFERRED
);
COMMENT ON TABLE qms_aql_table IS 'AQL 표 1 — 로트 크기 구간 → 시료 문자 (회사가 올림, 시드 없음 — docs/29 D3)';
COMMENT ON COLUMN qms_aql_table.lot_to IS '구간 끝(포함). 비우면 끝 없음';
CREATE TRIGGER tg_qms_aql_table_no_delete BEFORE DELETE ON qms_aql_table FOR EACH ROW EXECUTE FUNCTION hb_forbid_delete_master();

-- ---------------------------------------------------------------------
-- 2. AQL 표 — 시료 문자 × AQL × 엄격도 → n · Ac · Re
-- ---------------------------------------------------------------------
CREATE TABLE qms_aql_plan_row (
    aql_row_id   bigint        GENERATED ALWAYS AS IDENTITY,
    comp_cd      varchar(20)   NOT NULL,
    std_cd       varchar(20)   NOT NULL,
    severity     varchar(10)   NOT NULL,
    code_letter  varchar(1)    NOT NULL,
    aql          numeric(8,3)  NOT NULL,
    sample_size  integer       NOT NULL,
    ac           integer       NOT NULL,
    re           integer       NOT NULL,
    use_yn       char(1)       NOT NULL DEFAULT 'Y',
    row_ver      integer       NOT NULL DEFAULT 0,
    created_at   timestamptz   NOT NULL DEFAULT now(),
    created_by   varchar(50)   NOT NULL,
    updated_at   timestamptz   NOT NULL DEFAULT now(),
    updated_by   varchar(50)   NOT NULL,
    CONSTRAINT pk_qms_aql_plan_row PRIMARY KEY (aql_row_id),
    CONSTRAINT fk_qms_aql_plan_row_comp FOREIGN KEY (comp_cd) REFERENCES sys_comp (comp_cd),
    CONSTRAINT uq_qms_aql_plan_row UNIQUE (comp_cd, std_cd, severity, code_letter, aql),
    CONSTRAINT ck_qms_aql_plan_row_std CHECK (std_cd ~ '^[A-Z0-9][A-Z0-9._-]{0,19}$'),
    CONSTRAINT ck_qms_aql_plan_row_severity CHECK (severity IN ('NORMAL', 'TIGHTENED', 'REDUCED')),
    CONSTRAINT ck_qms_aql_plan_row_letter CHECK (code_letter ~ '^[A-Z]$'),
    CONSTRAINT ck_qms_aql_plan_row_aql CHECK (aql > 0 AND aql <= 1000),
    CONSTRAINT ck_qms_aql_plan_row_n CHECK (sample_size BETWEEN 1 AND 1000000),
    -- 1회 샘플링: Re > Ac, Re ≤ n. Ac 와 Re 사이 간격(합격 + 보통 검사 복귀)은 수월한 검사에만
    CONSTRAINT ck_qms_aql_plan_row_acre CHECK (ac >= 0 AND re > ac AND re <= sample_size
                                               AND (re = ac + 1 OR severity = 'REDUCED')),
    CONSTRAINT ck_qms_aql_plan_row_use_yn CHECK (use_yn IN ('Y', 'N'))
);
COMMENT ON TABLE qms_aql_plan_row IS 'AQL 표 2 — 시료 문자 × AQL × 엄격도 → 시료 크기·합격 판정 개수(Ac)·불합격 판정 개수(Re). 화살표는 풀어서 넣는다';
CREATE TRIGGER tg_qms_aql_plan_row_no_delete BEFORE DELETE ON qms_aql_plan_row FOR EACH ROW EXECUTE FUNCTION hb_forbid_delete_master();

-- ---------------------------------------------------------------------
-- 3. 샘플링 계획
-- ---------------------------------------------------------------------
CREATE TABLE qms_sampling_plan (
    sampling_plan_id  bigint        GENERATED ALWAYS AS IDENTITY,
    comp_cd           varchar(20)   NOT NULL,
    plan_cd           varchar(20)   NOT NULL,
    plan_nm           varchar(100)  NOT NULL,
    method            varchar(10)   NOT NULL,
    fixed_n           integer,
    pct               numeric(5,2),
    std_cd            varchar(20),
    insp_level        varchar(3),
    severity          varchar(10),
    aql               numeric(8,3),
    ac                integer,
    re                integer,
    sort_no           integer       NOT NULL DEFAULT 0,
    use_yn            char(1)       NOT NULL DEFAULT 'Y',
    remark            varchar(500),
    row_ver           integer       NOT NULL DEFAULT 0,
    created_at        timestamptz   NOT NULL DEFAULT now(),
    created_by        varchar(50)   NOT NULL,
    updated_at        timestamptz   NOT NULL DEFAULT now(),
    updated_by        varchar(50)   NOT NULL,
    CONSTRAINT pk_qms_sampling_plan PRIMARY KEY (sampling_plan_id),
    CONSTRAINT fk_qms_sampling_plan_comp FOREIGN KEY (comp_cd) REFERENCES sys_comp (comp_cd),
    CONSTRAINT uq_qms_sampling_plan_cd UNIQUE (comp_cd, plan_cd),
    CONSTRAINT ck_qms_sampling_plan_cd CHECK (plan_cd ~ '^[A-Z0-9][A-Z0-9_-]{0,19}$'),
    CONSTRAINT ck_qms_sampling_plan_nm CHECK (length(btrim(plan_nm)) > 0),
    -- 방식마다 쓰는 칸만. AQL 은 n·Ac·Re 를 표에서 찾고, 나머지는 Ac·Re 를 직접(1회 샘플링: Re = Ac + 1)
    CONSTRAINT ck_qms_sampling_plan_method CHECK (
        (method = 'FIXED' AND fixed_n IS NOT NULL AND pct IS NULL
            AND std_cd IS NULL AND insp_level IS NULL AND severity IS NULL AND aql IS NULL AND ac IS NOT NULL)
        OR (method = 'ALL' AND fixed_n IS NULL AND pct IS NULL
            AND std_cd IS NULL AND insp_level IS NULL AND severity IS NULL AND aql IS NULL AND ac IS NOT NULL)
        OR (method = 'PERCENT' AND fixed_n IS NULL AND pct IS NOT NULL
            AND std_cd IS NULL AND insp_level IS NULL AND severity IS NULL AND aql IS NULL AND ac IS NOT NULL)
        OR (method = 'AQL' AND fixed_n IS NULL AND pct IS NULL
            AND std_cd IS NOT NULL AND insp_level IS NOT NULL AND severity IS NOT NULL AND aql IS NOT NULL
            AND ac IS NULL AND re IS NULL)),
    CONSTRAINT ck_qms_sampling_plan_n CHECK (fixed_n IS NULL OR fixed_n BETWEEN 1 AND 1000000),
    CONSTRAINT ck_qms_sampling_plan_pct CHECK (pct IS NULL OR (pct > 0 AND pct <= 100)),
    CONSTRAINT ck_qms_sampling_plan_acre CHECK ((ac IS NULL AND re IS NULL)
        OR (ac >= 0 AND re IS NOT NULL AND re = ac + 1 AND (fixed_n IS NULL OR re <= fixed_n))),
    CONSTRAINT ck_qms_sampling_plan_std CHECK (std_cd IS NULL OR std_cd ~ '^[A-Z0-9][A-Z0-9._-]{0,19}$'),
    CONSTRAINT ck_qms_sampling_plan_level CHECK (insp_level IS NULL OR insp_level IN ('I', 'II', 'III', 'S1', 'S2', 'S3', 'S4')),
    CONSTRAINT ck_qms_sampling_plan_severity CHECK (severity IS NULL OR severity IN ('NORMAL', 'TIGHTENED', 'REDUCED')),
    CONSTRAINT ck_qms_sampling_plan_aql CHECK (aql IS NULL OR (aql > 0 AND aql <= 1000)),
    CONSTRAINT ck_qms_sampling_plan_use_yn CHECK (use_yn IN ('Y', 'N'))
);
COMMENT ON TABLE qms_sampling_plan IS '샘플링 계획 — 고정 n·전수·비율·AQL 표 참조. 판정은 SamplingPlan(순수 함수). 확정 검사계획이 쓰면 방식·수치 불변';
COMMENT ON COLUMN qms_sampling_plan.pct IS '비율(%) — 시료 = 올림(로트 × 비율 / 100), 최소 1, 최대 로트';

-- ---------------------------------------------------------------------
-- 4. 검사계획 머리
-- ---------------------------------------------------------------------
CREATE TABLE qms_insp_plan (
    insp_plan_id   bigint        GENERATED ALWAYS AS IDENTITY,
    comp_cd        varchar(20)   NOT NULL,
    insp_type      varchar(10)   NOT NULL,
    item_id        bigint,
    item_class_id  bigint,
    proc_id        bigint,
    alt_no         smallint      NOT NULL DEFAULT 1,
    rev            varchar(4)    NOT NULL,
    status         varchar(10)   NOT NULL DEFAULT 'DRAFT',
    valid_from     date          NOT NULL,
    valid_to       date,
    doc_rev_id     bigint,
    change_no      varchar(30),
    change_reason  varchar(500),
    remark         varchar(1000),
    released_at    timestamptz,
    released_by    varchar(50),
    row_ver        integer       NOT NULL DEFAULT 0,
    created_at     timestamptz   NOT NULL DEFAULT now(),
    created_by     varchar(50)   NOT NULL,
    updated_at     timestamptz   NOT NULL DEFAULT now(),
    updated_by     varchar(50)   NOT NULL,
    CONSTRAINT pk_qms_insp_plan PRIMARY KEY (insp_plan_id),
    CONSTRAINT fk_qms_insp_plan_comp FOREIGN KEY (comp_cd) REFERENCES sys_comp (comp_cd),
    CONSTRAINT fk_qms_insp_plan_item FOREIGN KEY (item_id) REFERENCES mst_item (item_id),
    CONSTRAINT fk_qms_insp_plan_class FOREIGN KEY (item_class_id) REFERENCES mst_item_class (class_id),
    CONSTRAINT fk_qms_insp_plan_proc FOREIGN KEY (proc_id) REFERENCES mst_proc (proc_id),
    CONSTRAINT ck_qms_insp_plan_type CHECK (insp_type IN ('RECEIVE', 'PROCESS', 'FINAL', 'FIRST_OFF', 'LAST_OFF', 'PATROL')),
    -- 대상은 품목 또는 품목 클래스 중 하나 (docs/29 D2)
    CONSTRAINT ck_qms_insp_plan_target CHECK ((item_id IS NULL) <> (item_class_id IS NULL)),
    CONSTRAINT ck_qms_insp_plan_alt CHECK (alt_no BETWEEN 1 AND 99),
    CONSTRAINT ck_qms_insp_plan_rev CHECK (rev ~ '^[A-Z0-9]{1,4}$'),
    CONSTRAINT ck_qms_insp_plan_status CHECK (status IN ('DRAFT', 'RELEASED', 'OBSOLETE')),
    CONSTRAINT ck_qms_insp_plan_period CHECK (valid_to IS NULL OR valid_to > valid_from),
    CONSTRAINT ck_qms_insp_plan_released CHECK (status = 'DRAFT' OR released_at IS NOT NULL OR status = 'OBSOLETE'),
    -- 기준일의 유효 계획이 대상 키마다 하나뿐 (BOM ex_mst_bom_period 와 같은 규칙). NULL 은 = 로 같지 않으므로 0 으로 바꿔 비교
    CONSTRAINT ex_qms_insp_plan_period EXCLUDE USING gist (
        comp_cd WITH =, insp_type WITH =, (coalesce(item_id, 0)) WITH =, (coalesce(item_class_id, 0)) WITH =,
        (coalesce(proc_id, 0)) WITH =, alt_no WITH =, daterange(valid_from, valid_to, '[)') WITH &&
    ) WHERE (status = 'RELEASED')
);
CREATE UNIQUE INDEX uq_qms_insp_plan_rev ON qms_insp_plan
    (comp_cd, insp_type, coalesce(item_id, 0), coalesce(item_class_id, 0), coalesce(proc_id, 0), alt_no, rev);
-- 작성 중 리비전은 대상 키마다 하나
CREATE UNIQUE INDEX uq_qms_insp_plan_draft ON qms_insp_plan
    (comp_cd, insp_type, coalesce(item_id, 0), coalesce(item_class_id, 0), coalesce(proc_id, 0), alt_no) WHERE status = 'DRAFT';
CREATE INDEX ix_qms_insp_plan_item ON qms_insp_plan (comp_cd, item_id, insp_type, status) WHERE item_id IS NOT NULL;
CREATE INDEX ix_qms_insp_plan_class ON qms_insp_plan (comp_cd, item_class_id, insp_type, status) WHERE item_class_id IS NOT NULL;
COMMENT ON TABLE qms_insp_plan IS '검사계획 머리 (검사 유형 × 품목|품목 클래스 × 공정 × 대체 × 리비전). RELEASED·OBSOLETE 는 수정 불가 — 새 리비전으로 (docs/29 Q1)';
COMMENT ON COLUMN qms_insp_plan.proc_id IS '공정(선택) — 공정검사·초종품은 공정별, 비우면 그 대상의 공정 무관 계획';
COMMENT ON COLUMN qms_insp_plan.doc_rev_id IS '검사기준서 리비전 — Q3 관리문서(mst_doc_rev)가 생기면 FK·같은 회사 검사를 붙인다. Q1 은 쓰지 않음';

CREATE FUNCTION hb_guard_qms_insp_plan() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'inspection plan cannot be deleted (use OBSOLETE)' USING ERRCODE = '55000', CONSTRAINT = 'ck_qms_insp_plan_no_delete';
    END IF;
    IF TG_OP = 'INSERT' THEN
        IF NEW.item_id IS NOT NULL AND NOT hb_item_of_comp(NEW.comp_cd, NEW.item_id) THEN
            RAISE EXCEPTION 'item % does not belong to company %', NEW.item_id, NEW.comp_cd
                USING ERRCODE = '23503', CONSTRAINT = 'fk_qms_insp_plan_item_comp';
        END IF;
        IF NEW.item_class_id IS NOT NULL AND NOT EXISTS (
               SELECT 1 FROM mst_item_class c WHERE c.class_id = NEW.item_class_id AND c.comp_cd = NEW.comp_cd) THEN
            RAISE EXCEPTION 'item class % does not belong to company %', NEW.item_class_id, NEW.comp_cd
                USING ERRCODE = '23503', CONSTRAINT = 'fk_qms_insp_plan_class_comp';
        END IF;
        IF NEW.proc_id IS NOT NULL AND NOT EXISTS (
               SELECT 1 FROM mst_proc p WHERE p.proc_id = NEW.proc_id AND p.comp_cd = NEW.comp_cd) THEN
            RAISE EXCEPTION 'process % does not belong to company %', NEW.proc_id, NEW.comp_cd
                USING ERRCODE = '23503', CONSTRAINT = 'fk_qms_insp_plan_proc_comp';
        END IF;
        IF NEW.status <> 'DRAFT' THEN
            RAISE EXCEPTION 'new inspection plan must be DRAFT' USING ERRCODE = '55000', CONSTRAINT = 'ck_qms_insp_plan_draft';
        END IF;
        RETURN NEW;
    END IF;
    -- 대상 키는 상태와 관계없이 불변 — 바꾸면 행 트리거의 "특성이 계획 대상과 맞는지" 검사를 거치지 않는다 (BOM QA 3a #1 교훈)
    IF NEW.comp_cd <> OLD.comp_cd OR NEW.insp_type <> OLD.insp_type OR NEW.item_id IS DISTINCT FROM OLD.item_id
       OR NEW.item_class_id IS DISTINCT FROM OLD.item_class_id OR NEW.proc_id IS DISTINCT FROM OLD.proc_id
       OR NEW.alt_no <> OLD.alt_no THEN
        RAISE EXCEPTION 'inspection plan % target cannot be changed', OLD.insp_plan_id
            USING ERRCODE = '55000', CONSTRAINT = 'ck_qms_insp_plan_key_immutable';
    END IF;
    IF OLD.status <> NEW.status AND NOT (
           (OLD.status = 'DRAFT' AND NEW.status IN ('RELEASED', 'OBSOLETE'))
        OR (OLD.status = 'RELEASED' AND NEW.status = 'OBSOLETE')) THEN
        RAISE EXCEPTION 'inspection plan status % -> % is not allowed', OLD.status, NEW.status
            USING ERRCODE = '55000', CONSTRAINT = 'ck_qms_insp_plan_transition';
    END IF;
    IF OLD.status IN ('RELEASED', 'OBSOLETE') AND (
           NEW.rev <> OLD.rev OR NEW.valid_from <> OLD.valid_from OR NEW.doc_rev_id IS DISTINCT FROM OLD.doc_rev_id
        OR NEW.change_no IS DISTINCT FROM OLD.change_no OR NEW.change_reason IS DISTINCT FROM OLD.change_reason
        OR NEW.remark IS DISTINCT FROM OLD.remark OR NEW.released_at IS DISTINCT FROM OLD.released_at
        OR (OLD.status = 'OBSOLETE' AND NEW.valid_to IS DISTINCT FROM OLD.valid_to)
        OR (OLD.status = 'RELEASED' AND NEW.valid_to IS DISTINCT FROM OLD.valid_to
            AND (NEW.valid_to IS NULL OR (OLD.valid_to IS NOT NULL AND NEW.valid_to > OLD.valid_to)))) THEN
        RAISE EXCEPTION 'released/obsolete inspection plan % cannot be changed (create a new revision)', OLD.insp_plan_id
            USING ERRCODE = '55000', CONSTRAINT = 'ck_qms_insp_plan_immutable';
    END IF;
    -- 확정: 행 1건 이상 + 그 순간의 특성 규격을 행에 스냅샷 (docs/29 D1 — 확정 뒤 특성이 바뀌어도 "그때 기준" 이 남는다).
    -- 행 트리거는 머리가 아직 DRAFT 로 보이므로(BEFORE) 이 UPDATE 를 받는다
    IF OLD.status = 'DRAFT' AND NEW.status = 'RELEASED' THEN
        IF NOT EXISTS (SELECT 1 FROM qms_insp_plan_line l WHERE l.insp_plan_id = NEW.insp_plan_id) THEN
            RAISE EXCEPTION 'inspection plan % has no lines', NEW.insp_plan_id
                USING ERRCODE = '23514', CONSTRAINT = 'ck_qms_insp_plan_no_lines';
        END IF;
        UPDATE qms_insp_plan_line l
           SET nominal = c.nominal, lsl = c.lsl, usl = c.usl, unit_nm = c.unit_nm, scale = c.scale,
               special_cls = c.special_cls, data_type = c.data_type, updated_at = now(), updated_by = NEW.updated_by
          FROM qms_char c
         WHERE l.insp_plan_id = NEW.insp_plan_id AND c.char_id = l.char_id;
    END IF;
    RETURN NEW;
END
$$;
CREATE TRIGGER tg_qms_insp_plan_guard BEFORE INSERT OR UPDATE OR DELETE ON qms_insp_plan
    FOR EACH ROW EXECUTE FUNCTION hb_guard_qms_insp_plan();

-- ---------------------------------------------------------------------
-- 5. 검사계획 행
-- ---------------------------------------------------------------------
CREATE TABLE qms_insp_plan_line (
    line_id           bigint         GENERATED ALWAYS AS IDENTITY,
    comp_cd           varchar(20)    NOT NULL,
    insp_plan_id      bigint         NOT NULL,
    line_no           integer        NOT NULL,
    char_id           bigint         NOT NULL,
    method_nm         varchar(200),
    gauge_class       varchar(30)    NOT NULL,
    sampling_plan_id  bigint         NOT NULL,
    freq_type         varchar(12)    NOT NULL DEFAULT 'EVERY_LOT',
    freq_n            integer,
    reaction_plan     varchar(500),
    remark            varchar(500),
    -- 확정 때 스냅샷 (작성 중엔 비어 있고 화면은 특성을 따라간다)
    nominal           numeric(18,6),
    lsl               numeric(18,6),
    usl               numeric(18,6),
    unit_nm           varchar(20),
    scale             smallint,
    special_cls       varchar(30),
    data_type         varchar(10),
    row_ver           integer        NOT NULL DEFAULT 0,
    created_at        timestamptz    NOT NULL DEFAULT now(),
    created_by        varchar(50)    NOT NULL,
    updated_at        timestamptz    NOT NULL DEFAULT now(),
    updated_by        varchar(50)    NOT NULL,
    CONSTRAINT pk_qms_insp_plan_line PRIMARY KEY (line_id),
    CONSTRAINT fk_qms_insp_plan_line_comp FOREIGN KEY (comp_cd) REFERENCES sys_comp (comp_cd),
    CONSTRAINT fk_qms_insp_plan_line_plan FOREIGN KEY (insp_plan_id) REFERENCES qms_insp_plan (insp_plan_id),
    CONSTRAINT fk_qms_insp_plan_line_char FOREIGN KEY (char_id) REFERENCES qms_char (char_id),
    CONSTRAINT fk_qms_insp_plan_line_sampling FOREIGN KEY (sampling_plan_id) REFERENCES qms_sampling_plan (sampling_plan_id),
    -- 같은 특성 두 번 금지(24 H). 커밋 시점 검사 — 한 번 저장으로 두 행의 특성·순번을 맞바꿀 수 있게
    CONSTRAINT uq_qms_insp_plan_line_char UNIQUE (insp_plan_id, char_id) DEFERRABLE INITIALLY DEFERRED,
    CONSTRAINT uq_qms_insp_plan_line_no UNIQUE (insp_plan_id, line_no) DEFERRABLE INITIALLY DEFERRED,
    CONSTRAINT ck_qms_insp_plan_line_no CHECK (line_no BETWEEN 1 AND 9999),
    CONSTRAINT ck_qms_insp_plan_line_freq CHECK (
        (freq_type IN ('EVERY_LOT', 'START', 'END') AND freq_n IS NULL)
        OR (freq_type = 'EVERY_N' AND freq_n IS NOT NULL AND freq_n BETWEEN 1 AND 1000000)
        OR (freq_type = 'EVERY_HOURS' AND freq_n IS NOT NULL AND freq_n BETWEEN 1 AND 168)),
    CONSTRAINT ck_qms_insp_plan_line_data_type CHECK (data_type IS NULL OR data_type IN ('VARIABLE', 'ATTRIBUTE'))
);
CREATE INDEX ix_qms_insp_plan_line_plan ON qms_insp_plan_line (comp_cd, insp_plan_id, line_no);
CREATE INDEX ix_qms_insp_plan_line_char ON qms_insp_plan_line (char_id);
CREATE INDEX ix_qms_insp_plan_line_sampling ON qms_insp_plan_line (sampling_plan_id);
COMMENT ON TABLE qms_insp_plan_line IS '검사계획 행 — 특성·방법·계측기 클래스·샘플링·주기 + 확정 때 특성 규격 스냅샷. 머리가 DRAFT 일 때만 바뀐다';
COMMENT ON COLUMN qms_insp_plan_line.gauge_class IS '계측기 클래스(공통코드 GAUGE_CLASS, 회사 추가 가능) — VISUAL = 육안. Q2 교정 판정이 이 클래스의 게이지를 본다';
COMMENT ON COLUMN qms_insp_plan_line.freq_n IS 'EVERY_N = n 개마다, EVERY_HOURS = n 시간마다';
COMMENT ON COLUMN qms_insp_plan_line.data_type IS '스냅샷 표시 겸용 — 비어 있으면 아직 확정 전(특성을 따라감)';

CREATE FUNCTION hb_guard_qms_insp_plan_line() RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    v_row  qms_insp_plan_line;
    v_head qms_insp_plan;
    v_char qms_char;
BEGIN
    v_row := CASE WHEN TG_OP = 'DELETE' THEN OLD ELSE NEW END;
    SELECT * INTO v_head FROM qms_insp_plan WHERE insp_plan_id = v_row.insp_plan_id;
    IF NOT FOUND OR v_head.comp_cd <> v_row.comp_cd THEN
        RAISE EXCEPTION 'inspection plan % does not belong to company %', v_row.insp_plan_id, v_row.comp_cd
            USING ERRCODE = '23503', CONSTRAINT = 'fk_qms_insp_plan_line_plan_comp';
    END IF;
    IF v_head.status <> 'DRAFT' THEN
        RAISE EXCEPTION 'lines of % inspection plan % cannot be changed (create a new revision)', v_head.status, v_row.insp_plan_id
            USING ERRCODE = '55000', CONSTRAINT = 'ck_qms_insp_plan_line_draft';
    END IF;
    IF TG_OP = 'DELETE' THEN
        RETURN OLD;
    END IF;
    IF TG_OP = 'UPDATE' AND (NEW.insp_plan_id <> OLD.insp_plan_id OR NEW.comp_cd <> OLD.comp_cd) THEN
        RAISE EXCEPTION 'line cannot move to another plan' USING ERRCODE = '55000', CONSTRAINT = 'ck_qms_insp_plan_line_move';
    END IF;
    -- 특성: 같은 회사 · 계획 대상과 맞음 · 새 참조는 사용 중(같은 대상 키의 다른 리비전이 쓰던 것은 허용 — 새 리비전 복사, V028 과 같은 규칙)
    IF TG_OP = 'INSERT' OR NEW.char_id <> OLD.char_id THEN
        SELECT * INTO v_char FROM qms_char WHERE char_id = NEW.char_id;
        IF NOT FOUND OR v_char.comp_cd <> NEW.comp_cd THEN
            RAISE EXCEPTION 'characteristic % does not belong to company %', NEW.char_id, NEW.comp_cd
                USING ERRCODE = '23503', CONSTRAINT = 'fk_qms_insp_plan_line_char_comp';
        END IF;
        IF (v_char.item_id IS NOT NULL AND v_char.item_id IS DISTINCT FROM v_head.item_id)
           OR (v_char.item_id IS NULL AND v_head.proc_id IS NOT NULL AND v_char.proc_id <> v_head.proc_id) THEN
            RAISE EXCEPTION 'characteristic % does not fit inspection plan %', NEW.char_id, NEW.insp_plan_id
                USING ERRCODE = '23514', CONSTRAINT = 'ck_qms_insp_plan_line_char_target';
        END IF;
        IF v_char.use_yn <> 'Y' AND NOT EXISTS (
               SELECT 1 FROM qms_insp_plan_line l JOIN qms_insp_plan p ON p.insp_plan_id = l.insp_plan_id
                WHERE l.char_id = NEW.char_id AND p.insp_plan_id <> NEW.insp_plan_id AND p.insp_type = v_head.insp_type
                  AND p.item_id IS NOT DISTINCT FROM v_head.item_id AND p.item_class_id IS NOT DISTINCT FROM v_head.item_class_id
                  AND p.proc_id IS NOT DISTINCT FROM v_head.proc_id AND p.alt_no = v_head.alt_no) THEN
            RAISE EXCEPTION 'characteristic % is not in use', NEW.char_id
                USING ERRCODE = '23514', CONSTRAINT = 'ck_qms_insp_plan_line_char_active';
        END IF;
    END IF;
    IF TG_OP = 'INSERT' OR NEW.sampling_plan_id <> OLD.sampling_plan_id THEN
        IF NOT EXISTS (SELECT 1 FROM qms_sampling_plan s WHERE s.sampling_plan_id = NEW.sampling_plan_id AND s.comp_cd = NEW.comp_cd) THEN
            RAISE EXCEPTION 'sampling plan % does not belong to company %', NEW.sampling_plan_id, NEW.comp_cd
                USING ERRCODE = '23503', CONSTRAINT = 'fk_qms_insp_plan_line_sampling_comp';
        END IF;
        IF NOT EXISTS (SELECT 1 FROM qms_sampling_plan s WHERE s.sampling_plan_id = NEW.sampling_plan_id AND s.use_yn = 'Y')
           AND NOT EXISTS (
               SELECT 1 FROM qms_insp_plan_line l JOIN qms_insp_plan p ON p.insp_plan_id = l.insp_plan_id
                WHERE l.sampling_plan_id = NEW.sampling_plan_id AND p.insp_plan_id <> NEW.insp_plan_id
                  AND p.insp_type = v_head.insp_type AND p.item_id IS NOT DISTINCT FROM v_head.item_id
                  AND p.item_class_id IS NOT DISTINCT FROM v_head.item_class_id AND p.proc_id IS NOT DISTINCT FROM v_head.proc_id
                  AND p.alt_no = v_head.alt_no) THEN
            RAISE EXCEPTION 'sampling plan % is not in use', NEW.sampling_plan_id
                USING ERRCODE = '23514', CONSTRAINT = 'ck_qms_insp_plan_line_sampling_active';
        END IF;
    END IF;
    IF (TG_OP = 'INSERT' OR NEW.gauge_class <> OLD.gauge_class) AND NOT hb_code_usable(NEW.comp_cd, 'GAUGE_CLASS', NEW.gauge_class)
       AND NOT EXISTS (
           SELECT 1 FROM qms_insp_plan_line l JOIN qms_insp_plan p ON p.insp_plan_id = l.insp_plan_id
            WHERE l.gauge_class = NEW.gauge_class AND p.insp_plan_id <> NEW.insp_plan_id
              AND p.insp_type = v_head.insp_type AND p.item_id IS NOT DISTINCT FROM v_head.item_id
              AND p.item_class_id IS NOT DISTINCT FROM v_head.item_class_id AND p.proc_id IS NOT DISTINCT FROM v_head.proc_id
              AND p.alt_no = v_head.alt_no) THEN
        RAISE EXCEPTION 'gauge class % is not available for company %', NEW.gauge_class, NEW.comp_cd
            USING ERRCODE = '23503', CONSTRAINT = 'fk_qms_insp_plan_line_gauge_class';
    END IF;
    RETURN NEW;
END
$$;
CREATE TRIGGER tg_qms_insp_plan_line_guard BEFORE INSERT OR UPDATE OR DELETE ON qms_insp_plan_line
    FOR EACH ROW EXECUTE FUNCTION hb_guard_qms_insp_plan_line();

-- 샘플링 계획: 삭제 금지, 확정·폐기 검사계획이 쓰면 방식·수치 변경 금지(그 검사계획의 "그때 기준" 이 바뀐다 — 새 계획 코드로)
CREATE FUNCTION hb_guard_qms_sampling_plan() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'sampling plan cannot be deleted (use use_yn = N)' USING ERRCODE = '55000', CONSTRAINT = 'ck_qms_sampling_plan_no_delete';
    END IF;
    IF (NEW.method, NEW.fixed_n, NEW.pct, NEW.std_cd, NEW.insp_level, NEW.severity, NEW.aql, NEW.ac, NEW.re)
           IS DISTINCT FROM (OLD.method, OLD.fixed_n, OLD.pct, OLD.std_cd, OLD.insp_level, OLD.severity, OLD.aql, OLD.ac, OLD.re)
       AND EXISTS (SELECT 1 FROM qms_insp_plan_line l JOIN qms_insp_plan p ON p.insp_plan_id = l.insp_plan_id
                    WHERE l.sampling_plan_id = NEW.sampling_plan_id AND p.status <> 'DRAFT') THEN
        RAISE EXCEPTION 'sampling plan % is used by a released inspection plan', NEW.sampling_plan_id
            USING ERRCODE = '23514', CONSTRAINT = 'ck_qms_sampling_plan_in_use';
    END IF;
    RETURN NEW;
END
$$;
CREATE TRIGGER tg_qms_sampling_plan_guard BEFORE UPDATE OR DELETE ON qms_sampling_plan
    FOR EACH ROW EXECUTE FUNCTION hb_guard_qms_sampling_plan();

-- ---------------------------------------------------------------------
-- 6. 감사 · RLS · 권한 · 화면 · 보존
-- ---------------------------------------------------------------------
CREATE TRIGGER tg_audit AFTER INSERT OR UPDATE OR DELETE ON qms_aql_table FOR EACH ROW EXECUTE FUNCTION hb_audit('aql_table_id', '', '');
CREATE TRIGGER tg_audit AFTER INSERT OR UPDATE OR DELETE ON qms_aql_plan_row FOR EACH ROW EXECUTE FUNCTION hb_audit('aql_row_id', '', '');
CREATE TRIGGER tg_audit AFTER INSERT OR UPDATE OR DELETE ON qms_sampling_plan FOR EACH ROW EXECUTE FUNCTION hb_audit('sampling_plan_id', '', '');
CREATE TRIGGER tg_audit AFTER INSERT OR UPDATE OR DELETE ON qms_insp_plan FOR EACH ROW EXECUTE FUNCTION hb_audit('insp_plan_id', '', '');
CREATE TRIGGER tg_audit AFTER INSERT OR UPDATE OR DELETE ON qms_insp_plan_line FOR EACH ROW EXECUTE FUNCTION hb_audit('line_id', '', '');

ALTER TABLE qms_aql_table ENABLE ROW LEVEL SECURITY;
ALTER TABLE qms_aql_table FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON qms_aql_table USING (comp_cd = hb_current_comp()) WITH CHECK (comp_cd = hb_current_comp());
ALTER TABLE qms_aql_plan_row ENABLE ROW LEVEL SECURITY;
ALTER TABLE qms_aql_plan_row FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON qms_aql_plan_row USING (comp_cd = hb_current_comp()) WITH CHECK (comp_cd = hb_current_comp());
ALTER TABLE qms_sampling_plan ENABLE ROW LEVEL SECURITY;
ALTER TABLE qms_sampling_plan FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON qms_sampling_plan USING (comp_cd = hb_current_comp()) WITH CHECK (comp_cd = hb_current_comp());
ALTER TABLE qms_insp_plan ENABLE ROW LEVEL SECURITY;
ALTER TABLE qms_insp_plan FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON qms_insp_plan USING (comp_cd = hb_current_comp()) WITH CHECK (comp_cd = hb_current_comp());
ALTER TABLE qms_insp_plan_line ENABLE ROW LEVEL SECURITY;
ALTER TABLE qms_insp_plan_line FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON qms_insp_plan_line USING (comp_cd = hb_current_comp()) WITH CHECK (comp_cd = hb_current_comp());

-- 기준·머리는 삭제 대신 사용 안 함·폐기. 작성 중 검사계획의 행만 지울 수 있다(트리거가 상태 확인)
REVOKE DELETE, TRUNCATE ON qms_aql_table, qms_aql_plan_row, qms_sampling_plan, qms_insp_plan FROM ${app_role};
REVOKE TRUNCATE ON qms_insp_plan_line FROM ${app_role};

INSERT INTO sys_prog (prog_cd, prog_nm_key, view_path, module_cd, actions, sort_no) VALUES
    ('qms-sampling',  'prog.qmsSampling',  'qms/sampling-mgt',  'QMS', ARRAY['SEARCH','SAVE','EXCEL']::varchar(20)[], 300),
    ('qms-insp-plan', 'prog.qmsInspPlan',  'qms/insp-plan-mgt', 'QMS', ARRAY['SEARCH','SAVE','APPROVE','DELETE','EXCEL']::varchar(20)[], 310);
-- 샘플링·AQL 표는 기준정보, 검사계획은 품질 문서(IATF 7.5.3.2.1 — 부품 생산·서비스 기간 + 1년)
INSERT INTO sys_retention_table (table_nm, class_cd) VALUES
    ('qms_aql_table', 'MASTER'), ('qms_aql_plan_row', 'MASTER'), ('qms_sampling_plan', 'MASTER'),
    ('qms_insp_plan', 'QUALITY_RECORD'), ('qms_insp_plan_line', 'QUALITY_RECORD');
