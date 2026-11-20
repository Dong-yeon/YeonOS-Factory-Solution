-- =====================================================================
-- V015 BOM 구조 · 리비전 · 품목별 단위 환산 (P1 3a — docs/18 결정 B1~B8, docs/17 3단계)
--
-- - 생산 BOM(MBOM) 하나. 머리 = 모품목 × 대체 번호 × 리비전, 상태 DRAFT → RELEASED → OBSOLETE
-- - RELEASED 끼리 같은 모품목·대체 번호의 유효기간이 겹치면 안 된다(배제 제약) → 기준일의 유효 BOM 은 0 또는 1건
-- - RELEASED·OBSOLETE 는 고치지 않는다(바꾸려면 새 리비전) — 트리거. 단 RELEASED 의 유효 종료일 마감과 폐기는 허용
-- - 순환 금지: 행을 넣거나 자품목을 바꿀 때 "자품목 아래(미폐기 모든 리비전)에 모품목이 있으면" 거부.
--   회사 단위 잠금으로 두 사람이 A→B, B→A 를 동시에 저장하는 경쟁도 막는다.
-- =====================================================================

CREATE EXTENSION IF NOT EXISTS btree_gist;   -- 배제 제약에서 = 와 && 를 함께 (PG13+ trusted 확장)

-- ---------------------------------------------------------------------
-- 1. 코드
-- ---------------------------------------------------------------------
INSERT INTO sys_code_grp (grp_cd, grp_nm_key, comp_ext_yn) VALUES
    ('BOM_STATUS',    'code.grp.BOM_STATUS',    'N'),
    ('BOM_LINE_TYPE', 'code.grp.BOM_LINE_TYPE', 'N'),
    ('ISSUE_METHOD',  'code.grp.ISSUE_METHOD',  'N');
INSERT INTO sys_code (grp_cd, code, code_nm_key, sort_no) VALUES
    ('BOM_STATUS', 'DRAFT',    'code.BOM_STATUS.DRAFT',    1),
    ('BOM_STATUS', 'RELEASED', 'code.BOM_STATUS.RELEASED', 2),
    ('BOM_STATUS', 'OBSOLETE', 'code.BOM_STATUS.OBSOLETE', 3),
    ('BOM_LINE_TYPE', 'NORMAL',    'code.BOM_LINE_TYPE.NORMAL',    1),
    ('BOM_LINE_TYPE', 'PHANTOM',   'code.BOM_LINE_TYPE.PHANTOM',   2),
    ('BOM_LINE_TYPE', 'BYPRODUCT', 'code.BOM_LINE_TYPE.BYPRODUCT', 3),
    ('ISSUE_METHOD', 'BACKFLUSH', 'code.ISSUE_METHOD.BACKFLUSH', 1),
    ('ISSUE_METHOD', 'MANUAL',    'code.ISSUE_METHOD.MANUAL',    2);

-- 단위 코드가 시스템 단위 또는 그 회사가 추가한 사용 중 단위인지 (V013 hb_check_item_unit 과 같은 규칙)
CREATE FUNCTION hb_unit_usable(p_comp varchar, p_unit varchar) RETURNS boolean
    LANGUAGE sql STABLE
AS $$
    SELECT EXISTS (SELECT 1 FROM sys_code WHERE grp_cd = 'UNIT' AND code = p_unit AND use_yn = 'Y')
        OR EXISTS (SELECT 1 FROM sys_comp_code WHERE comp_cd = p_comp AND grp_cd = 'UNIT' AND code = p_unit AND use_yn = 'Y')
$$;

-- 품목이 그 회사 것인지 (회사별 테이블 간 FK 는 RLS 를 무시한다)
CREATE FUNCTION hb_item_of_comp(p_comp varchar, p_item bigint) RETURNS boolean
    LANGUAGE sql STABLE
AS $$
    SELECT EXISTS (SELECT 1 FROM mst_item WHERE item_id = p_item AND comp_cd = p_comp)
$$;

-- ---------------------------------------------------------------------
-- 2. 품목별 단위 환산: 1 from_unit = factor × to_unit(그 품목의 기본 단위)
-- ---------------------------------------------------------------------
CREATE TABLE mst_unit_conv (
    conv_id     bigint         GENERATED ALWAYS AS IDENTITY,
    comp_cd     varchar(20)    NOT NULL,
    item_id     bigint         NOT NULL,
    from_unit   varchar(30)    NOT NULL,
    to_unit     varchar(30)    NOT NULL,
    factor      numeric(18,9)  NOT NULL,
    use_yn      char(1)        NOT NULL DEFAULT 'Y',
    row_ver     integer        NOT NULL DEFAULT 0,
    created_at  timestamptz    NOT NULL DEFAULT now(),
    created_by  varchar(50)    NOT NULL,
    updated_at  timestamptz    NOT NULL DEFAULT now(),
    updated_by  varchar(50)    NOT NULL,
    CONSTRAINT pk_mst_unit_conv PRIMARY KEY (conv_id),
    CONSTRAINT fk_mst_unit_conv_comp FOREIGN KEY (comp_cd) REFERENCES sys_comp (comp_cd),
    CONSTRAINT fk_mst_unit_conv_item FOREIGN KEY (item_id) REFERENCES mst_item (item_id),
    CONSTRAINT uq_mst_unit_conv UNIQUE (comp_cd, item_id, from_unit),
    CONSTRAINT ck_mst_unit_conv_units CHECK (from_unit <> to_unit),
    CONSTRAINT ck_mst_unit_conv_factor CHECK (factor > 0),
    CONSTRAINT ck_mst_unit_conv_use_yn CHECK (use_yn IN ('Y', 'N'))
);
COMMENT ON TABLE mst_unit_conv IS '품목별 단위 환산 (예: 코일 1 ROLL = 850 KG). to_unit 이 품목 기본 단위와 같을 때만 쓴다';

CREATE FUNCTION hb_check_unit_conv() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF NOT hb_item_of_comp(NEW.comp_cd, NEW.item_id) THEN
        RAISE EXCEPTION 'item % does not belong to company %', NEW.item_id, NEW.comp_cd
            USING ERRCODE = '23503', CONSTRAINT = 'fk_mst_unit_conv_item_comp';
    END IF;
    IF (TG_OP = 'INSERT' OR NEW.from_unit <> OLD.from_unit) AND NOT hb_unit_usable(NEW.comp_cd, NEW.from_unit) THEN
        RAISE EXCEPTION 'unit % is not available for company %', NEW.from_unit, NEW.comp_cd
            USING ERRCODE = '23503', CONSTRAINT = 'fk_mst_unit_conv_unit';
    END IF;
    RETURN NEW;
END
$$;
CREATE TRIGGER tg_mst_unit_conv_check BEFORE INSERT OR UPDATE ON mst_unit_conv
    FOR EACH ROW EXECUTE FUNCTION hb_check_unit_conv();

-- ---------------------------------------------------------------------
-- 3. BOM 머리
-- ---------------------------------------------------------------------
CREATE TABLE mst_bom (
    bom_id         bigint         GENERATED ALWAYS AS IDENTITY,
    comp_cd        varchar(20)    NOT NULL,
    item_id        bigint         NOT NULL,
    alt_no         smallint       NOT NULL DEFAULT 1,
    rev            varchar(4)     NOT NULL,
    status         varchar(10)    NOT NULL DEFAULT 'DRAFT',
    valid_from     date           NOT NULL,
    valid_to       date,
    base_qty       numeric(18,6)  NOT NULL DEFAULT 1,
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
    CONSTRAINT pk_mst_bom PRIMARY KEY (bom_id),
    CONSTRAINT fk_mst_bom_comp FOREIGN KEY (comp_cd) REFERENCES sys_comp (comp_cd),
    CONSTRAINT fk_mst_bom_item FOREIGN KEY (item_id) REFERENCES mst_item (item_id),
    CONSTRAINT uq_mst_bom_rev UNIQUE (comp_cd, item_id, alt_no, rev),
    CONSTRAINT ck_mst_bom_alt CHECK (alt_no BETWEEN 1 AND 99),
    CONSTRAINT ck_mst_bom_rev CHECK (rev ~ '^[A-Z0-9]{1,4}$'),
    CONSTRAINT ck_mst_bom_status CHECK (status IN ('DRAFT', 'RELEASED', 'OBSOLETE')),
    CONSTRAINT ck_mst_bom_period CHECK (valid_to IS NULL OR valid_to > valid_from),
    CONSTRAINT ck_mst_bom_base CHECK (base_qty > 0),
    CONSTRAINT ck_mst_bom_released CHECK (status = 'DRAFT' OR released_at IS NOT NULL OR status = 'OBSOLETE'),
    -- 기준일의 유효 BOM 이 하나뿐이도록: 확정본끼리 기간 겹침 금지 (docs/18 B2, D365 활성 버전 규칙)
    CONSTRAINT ex_mst_bom_period EXCLUDE USING gist (
        comp_cd WITH =, item_id WITH =, alt_no WITH =, daterange(valid_from, valid_to, '[)') WITH &&
    ) WHERE (status = 'RELEASED')
);
-- 작성 중 리비전은 모품목·대체 번호마다 하나
CREATE UNIQUE INDEX uq_mst_bom_draft ON mst_bom (comp_cd, item_id, alt_no) WHERE status = 'DRAFT';
CREATE INDEX ix_mst_bom_item ON mst_bom (comp_cd, item_id, status);
COMMENT ON TABLE mst_bom IS 'BOM 머리 (생산 BOM). RELEASED·OBSOLETE 는 수정 불가 — 새 리비전으로 (docs/18)';
COMMENT ON COLUMN mst_bom.base_qty IS '기준 수량 — 행 소요량은 모품목 base_qty 개(모품목 단위)를 만들 때의 양';
COMMENT ON COLUMN mst_bom.alt_no IS '대체 BOM 번호(같은 품목의 다른 구성). 3a 는 1 만 사용, 선택 규칙은 라우팅 때 (B6)';

CREATE FUNCTION hb_guard_bom() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'BOM header cannot be deleted (use OBSOLETE)'
            USING ERRCODE = '55000', CONSTRAINT = 'ck_mst_bom_no_delete';
    END IF;
    IF NOT hb_item_of_comp(NEW.comp_cd, NEW.item_id) THEN
        RAISE EXCEPTION 'item % does not belong to company %', NEW.item_id, NEW.comp_cd
            USING ERRCODE = '23503', CONSTRAINT = 'fk_mst_bom_item_comp';
    END IF;
    IF TG_OP = 'INSERT' THEN
        IF NEW.status <> 'DRAFT' THEN
            RAISE EXCEPTION 'new BOM must be DRAFT' USING ERRCODE = '55000', CONSTRAINT = 'ck_mst_bom_draft';
        END IF;
        RETURN NEW;
    END IF;
    -- 상태 이동: DRAFT→RELEASED·OBSOLETE, RELEASED→OBSOLETE 만
    IF OLD.status <> NEW.status AND NOT (
           (OLD.status = 'DRAFT' AND NEW.status IN ('RELEASED', 'OBSOLETE'))
        OR (OLD.status = 'RELEASED' AND NEW.status = 'OBSOLETE')) THEN
        RAISE EXCEPTION 'BOM status % -> % is not allowed', OLD.status, NEW.status
            USING ERRCODE = '55000', CONSTRAINT = 'ck_mst_bom_transition';
    END IF;
    -- 확정본·폐기본은 내용 수정 금지. 확정본은 유효 종료일 마감(앞당기기)과 폐기만
    IF OLD.status IN ('RELEASED', 'OBSOLETE') AND (
           NEW.item_id <> OLD.item_id OR NEW.alt_no <> OLD.alt_no OR NEW.rev <> OLD.rev
        OR NEW.valid_from <> OLD.valid_from OR NEW.base_qty <> OLD.base_qty
        OR NEW.change_no IS DISTINCT FROM OLD.change_no OR NEW.change_reason IS DISTINCT FROM OLD.change_reason
        OR NEW.remark IS DISTINCT FROM OLD.remark OR NEW.released_at IS DISTINCT FROM OLD.released_at
        OR (OLD.status = 'OBSOLETE' AND NEW.valid_to IS DISTINCT FROM OLD.valid_to)
        OR (OLD.status = 'RELEASED' AND NEW.valid_to IS DISTINCT FROM OLD.valid_to
            AND (NEW.valid_to IS NULL OR (OLD.valid_to IS NOT NULL AND NEW.valid_to > OLD.valid_to)))) THEN
        RAISE EXCEPTION 'released/obsolete BOM % cannot be changed (create a new revision)', OLD.bom_id
            USING ERRCODE = '55000', CONSTRAINT = 'ck_mst_bom_immutable';
    END IF;
    RETURN NEW;
END
$$;
CREATE TRIGGER tg_mst_bom_guard BEFORE INSERT OR UPDATE OR DELETE ON mst_bom
    FOR EACH ROW EXECUTE FUNCTION hb_guard_bom();

-- ---------------------------------------------------------------------
-- 4. BOM 행
-- ---------------------------------------------------------------------
CREATE TABLE mst_bom_line (
    line_id       bigint         GENERATED ALWAYS AS IDENTITY,
    comp_cd       varchar(20)    NOT NULL,
    bom_id        bigint         NOT NULL,
    line_no       integer        NOT NULL,
    item_id       bigint         NOT NULL,
    line_type     varchar(10)    NOT NULL DEFAULT 'NORMAL',
    qty           numeric(18,6)  NOT NULL,
    unit_cd       varchar(30)    NOT NULL,
    scrap_pct     numeric(5,2)   NOT NULL DEFAULT 0,
    fixed_yn      char(1)        NOT NULL DEFAULT 'N',
    issue_method  varchar(10),
    op_seq        integer,
    alt_grp       varchar(10),
    alt_priority  smallint,
    alt_pct       numeric(5,2),
    position      varchar(100),
    remark        varchar(500),
    row_ver       integer        NOT NULL DEFAULT 0,
    created_at    timestamptz    NOT NULL DEFAULT now(),
    created_by    varchar(50)    NOT NULL,
    updated_at    timestamptz    NOT NULL DEFAULT now(),
    updated_by    varchar(50)    NOT NULL,
    CONSTRAINT pk_mst_bom_line PRIMARY KEY (line_id),
    CONSTRAINT fk_mst_bom_line_comp FOREIGN KEY (comp_cd) REFERENCES sys_comp (comp_cd),
    CONSTRAINT fk_mst_bom_line_bom FOREIGN KEY (bom_id) REFERENCES mst_bom (bom_id),
    CONSTRAINT fk_mst_bom_line_item FOREIGN KEY (item_id) REFERENCES mst_item (item_id),
    -- 커밋 시점 검사: 한 번 저장으로 순번을 서로 바꿀 수 있게(1↔2)
    CONSTRAINT uq_mst_bom_line_no UNIQUE (bom_id, line_no) DEFERRABLE INITIALLY DEFERRED,
    CONSTRAINT ck_mst_bom_line_no CHECK (line_no BETWEEN 1 AND 9999),
    CONSTRAINT ck_mst_bom_line_type CHECK (line_type IN ('NORMAL', 'PHANTOM', 'BYPRODUCT')),
    -- 수량은 항상 양수 — 부산물은 음수가 아니라 유형으로 (docs/18 B5)
    CONSTRAINT ck_mst_bom_line_qty CHECK (qty > 0),
    CONSTRAINT ck_mst_bom_line_scrap CHECK (scrap_pct >= 0 AND scrap_pct < 100),
    CONSTRAINT ck_mst_bom_line_fixed CHECK (fixed_yn IN ('Y', 'N')),
    -- 투입 방식은 일반 행에만 (가상 조립은 통과, 부산물은 산출)
    CONSTRAINT ck_mst_bom_line_issue CHECK ((line_type = 'NORMAL') = (issue_method IS NOT NULL)
                                            AND (issue_method IS NULL OR issue_method IN ('BACKFLUSH', 'MANUAL'))),
    -- 대체 그룹은 일반 행에만, 그룹이 있으면 우선순위·사용 비율 필수
    CONSTRAINT ck_mst_bom_line_alt CHECK (
        (alt_grp IS NULL AND alt_priority IS NULL AND alt_pct IS NULL)
        OR (alt_grp ~ '^[A-Z0-9]{1,10}$' AND line_type = 'NORMAL' AND alt_priority BETWEEN 1 AND 99
            AND alt_pct >= 0 AND alt_pct <= 100)),
    CONSTRAINT ck_mst_bom_line_op CHECK (op_seq IS NULL OR op_seq BETWEEN 1 AND 9999)
);
CREATE INDEX ix_mst_bom_line_bom ON mst_bom_line (comp_cd, bom_id, line_no);
-- 역전개(이 품목을 쓰는 BOM) 용
CREATE INDEX ix_mst_bom_line_item ON mst_bom_line (comp_cd, item_id);
COMMENT ON TABLE mst_bom_line IS 'BOM 행. 머리가 DRAFT 일 때만 추가·수정·삭제 (트리거). 순환 금지 (트리거)';

CREATE FUNCTION hb_guard_bom_line() RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    v_row   mst_bom_line;
    v_head  record;
    v_cycle boolean;
BEGIN
    v_row := CASE WHEN TG_OP = 'DELETE' THEN OLD ELSE NEW END;
    SELECT b.item_id, b.status, b.comp_cd INTO v_head FROM mst_bom b WHERE b.bom_id = v_row.bom_id;
    IF NOT FOUND OR v_head.comp_cd <> v_row.comp_cd THEN
        RAISE EXCEPTION 'BOM % does not belong to company %', v_row.bom_id, v_row.comp_cd
            USING ERRCODE = '23503', CONSTRAINT = 'fk_mst_bom_line_bom_comp';
    END IF;
    IF v_head.status <> 'DRAFT' THEN
        RAISE EXCEPTION 'lines of % BOM % cannot be changed (create a new revision)', v_head.status, v_row.bom_id
            USING ERRCODE = '55000', CONSTRAINT = 'ck_mst_bom_line_draft';
    END IF;
    IF TG_OP = 'DELETE' THEN
        RETURN OLD;
    END IF;
    IF TG_OP = 'UPDATE' AND NEW.bom_id <> OLD.bom_id THEN
        RAISE EXCEPTION 'line cannot move to another BOM' USING ERRCODE = '55000', CONSTRAINT = 'ck_mst_bom_line_move';
    END IF;
    IF NOT hb_item_of_comp(NEW.comp_cd, NEW.item_id) THEN
        RAISE EXCEPTION 'item % does not belong to company %', NEW.item_id, NEW.comp_cd
            USING ERRCODE = '23503', CONSTRAINT = 'fk_mst_bom_line_item_comp';
    END IF;
    IF (TG_OP = 'INSERT' OR NEW.unit_cd <> OLD.unit_cd) AND NOT hb_unit_usable(NEW.comp_cd, NEW.unit_cd) THEN
        RAISE EXCEPTION 'unit % is not available for company %', NEW.unit_cd, NEW.comp_cd
            USING ERRCODE = '23503', CONSTRAINT = 'fk_mst_bom_line_unit';
    END IF;
    IF TG_OP = 'INSERT' OR NEW.item_id <> OLD.item_id THEN
        IF NEW.item_id = v_head.item_id THEN
            RAISE EXCEPTION 'BOM cycle: item % contains itself', NEW.item_id
                USING ERRCODE = '23514', CONSTRAINT = 'ck_mst_bom_cycle';
        END IF;
        -- 회사 단위 잠금: 동시에 A→B, B→A 를 넣는 두 트랜잭션 중 뒤쪽이 앞쪽 커밋을 보고 검사한다
        PERFORM pg_advisory_xact_lock(hashtextextended('mst_bom_structure:' || NEW.comp_cd, 0));
        -- 자품목 아래(미폐기 모든 리비전·대체)에 모품목이 있으면 순환 (docs/18 B7). UNION 은 이미 있는 순환에서도 끝난다
        WITH RECURSIVE down(item_id) AS (
            SELECT NEW.item_id
            UNION
            SELECT l.item_id
              FROM down d
              JOIN mst_bom b ON b.comp_cd = NEW.comp_cd AND b.item_id = d.item_id AND b.status <> 'OBSOLETE'
              JOIN mst_bom_line l ON l.bom_id = b.bom_id
        )
        SELECT EXISTS (SELECT 1 FROM down WHERE item_id = v_head.item_id) INTO v_cycle;
        IF v_cycle THEN
            RAISE EXCEPTION 'BOM cycle: item % is already above item %', v_head.item_id, NEW.item_id
                USING ERRCODE = '23514', CONSTRAINT = 'ck_mst_bom_cycle';
        END IF;
    END IF;
    RETURN NEW;
END
$$;
CREATE TRIGGER tg_mst_bom_line_guard BEFORE INSERT OR UPDATE OR DELETE ON mst_bom_line
    FOR EACH ROW EXECUTE FUNCTION hb_guard_bom_line();

-- ---------------------------------------------------------------------
-- 5. 감사 · RLS · 권한 · 화면 · 보존
-- ---------------------------------------------------------------------
CREATE TRIGGER tg_audit AFTER INSERT OR UPDATE OR DELETE ON mst_unit_conv
    FOR EACH ROW EXECUTE FUNCTION hb_audit('conv_id', '', '');
CREATE TRIGGER tg_audit AFTER INSERT OR UPDATE OR DELETE ON mst_bom
    FOR EACH ROW EXECUTE FUNCTION hb_audit('bom_id', '', '');
CREATE TRIGGER tg_audit AFTER INSERT OR UPDATE OR DELETE ON mst_bom_line
    FOR EACH ROW EXECUTE FUNCTION hb_audit('line_id', '', '');

ALTER TABLE mst_unit_conv ENABLE ROW LEVEL SECURITY;
ALTER TABLE mst_unit_conv FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON mst_unit_conv USING (comp_cd = hb_current_comp()) WITH CHECK (comp_cd = hb_current_comp());
ALTER TABLE mst_bom ENABLE ROW LEVEL SECURITY;
ALTER TABLE mst_bom FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON mst_bom USING (comp_cd = hb_current_comp()) WITH CHECK (comp_cd = hb_current_comp());
ALTER TABLE mst_bom_line ENABLE ROW LEVEL SECURITY;
ALTER TABLE mst_bom_line FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON mst_bom_line USING (comp_cd = hb_current_comp()) WITH CHECK (comp_cd = hb_current_comp());

-- 머리·환산은 삭제 대신 폐기·사용 안 함. 작성 중(DRAFT) BOM 의 행만 지울 수 있다(트리거가 상태 확인)
REVOKE DELETE, TRUNCATE ON mst_unit_conv, mst_bom FROM ${app_role};
REVOKE TRUNCATE ON mst_bom_line FROM ${app_role};

INSERT INTO sys_prog (prog_cd, prog_nm_key, view_path, module_cd, actions, sort_no)
VALUES ('mst-bom', 'prog.mstBom', 'master/bom-mgt', 'MST',
        ARRAY['SEARCH','SAVE','APPROVE','DELETE','EXCEL']::varchar(20)[], 120);
INSERT INTO sys_retention_table (table_nm, class_cd) VALUES
    ('mst_unit_conv', 'MASTER'), ('mst_bom', 'MASTER'), ('mst_bom_line', 'MASTER');
