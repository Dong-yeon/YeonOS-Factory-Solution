-- =====================================================================
-- P3 QMS 2부 Q5 4M 변경 (docs/29_P3 11장·12.1) — 임시 번호, 병합 때 재번호
--
-- - qms_change: PLM ECO 의 1:1 확장(PK = FK eco_id). 4M 유형(복수)·변경 전/후·사유·영향 평가·임시 변경 복귀 조건.
--   ECO 머리·결재·고객 승인은 PLM 것만 쓴다(39 12.6 Q4). eco_no 는 PLM 에서 바뀌지 않는 번호라 목록·검색용으로 트리거가 복사
-- - qms_change_char: 영향 특성 또는 검사계획(리비전) 행 — 한 벌 저장(지우고 넣기)
-- - 저장은 ECO 가 DRAFT 일 때만. 트리거가 plm_eco 를 FOR SHARE 로 읽는다 — PLM 상신(plm_eco FOR UPDATE → 상신 규칙)과 직렬화,
--   잠금 순서는 양쪽 plm_eco → qms_* (docs/39 16장, docs/29_P3 11.4). PLM 표는 바꾸지 않는다(FK·읽기·행 잠금만)
-- =====================================================================

INSERT INTO sys_code_grp (grp_cd, grp_nm_key, comp_ext_yn) VALUES
    ('QMS_4M_TYPE', 'code.grp.QMS_4M_TYPE', 'N');
INSERT INTO sys_code (grp_cd, code, code_nm_key, sort_no) VALUES
    ('QMS_4M_TYPE', 'MAN',      'code.QMS_4M_TYPE.MAN',      1),
    ('QMS_4M_TYPE', 'MACHINE',  'code.QMS_4M_TYPE.MACHINE',  2),
    ('QMS_4M_TYPE', 'MATERIAL', 'code.QMS_4M_TYPE.MATERIAL', 3),
    ('QMS_4M_TYPE', 'METHOD',   'code.QMS_4M_TYPE.METHOD',   4),
    ('QMS_4M_TYPE', 'MEASURE',  'code.QMS_4M_TYPE.MEASURE',  5),
    ('QMS_4M_TYPE', 'ENV',      'code.QMS_4M_TYPE.ENV',      6),
    ('QMS_4M_TYPE', 'SUPPLIER', 'code.QMS_4M_TYPE.SUPPLIER', 7),
    ('QMS_4M_TYPE', 'SITE',     'code.QMS_4M_TYPE.SITE',     8);

INSERT INTO sys_policy_def (policy_key, value_type, default_value, allowed_values, min_value, max_value, plant_scope_yn, desc_key) VALUES
    ('QMS_4M_REQUIRED', 'BOOL', 'Y', NULL, NULL, NULL, 'N', 'policy.QMS_4M_REQUIRED');

-- ---------------------------------------------------------------------
-- 1. 4M 세부
-- ---------------------------------------------------------------------
CREATE TABLE qms_change (
    eco_id        bigint          NOT NULL,
    comp_cd       varchar(20)     NOT NULL,
    eco_no        varchar(50)     NOT NULL,
    m4_types      varchar(10)[]   NOT NULL,
    before_desc   varchar(2000)   NOT NULL,
    after_desc    varchar(2000)   NOT NULL,
    reason_desc   varchar(2000),
    impact_desc   varchar(4000),
    revert_cond   varchar(1000),
    row_ver       integer         NOT NULL DEFAULT 0,
    created_at    timestamptz     NOT NULL DEFAULT now(),
    created_by    varchar(50)     NOT NULL,
    updated_at    timestamptz     NOT NULL DEFAULT now(),
    updated_by    varchar(50)     NOT NULL,
    CONSTRAINT pk_qms_change PRIMARY KEY (eco_id),
    CONSTRAINT fk_qms_change_comp FOREIGN KEY (comp_cd) REFERENCES sys_comp (comp_cd),
    CONSTRAINT fk_qms_change_eco FOREIGN KEY (eco_id) REFERENCES plm_eco (eco_id),
    -- 4M 유형: 하나 이상, 정해진 값만(중복은 트리거)
    CONSTRAINT ck_qms_change_m4 CHECK (cardinality(m4_types) BETWEEN 1 AND 8
        AND m4_types <@ ARRAY['MAN', 'MACHINE', 'MATERIAL', 'METHOD', 'MEASURE', 'ENV', 'SUPPLIER', 'SITE']::varchar(10)[]),
    CONSTRAINT ck_qms_change_desc CHECK (length(btrim(before_desc)) > 0 AND length(btrim(after_desc)) > 0)
);
CREATE INDEX ix_qms_change_no ON qms_change (comp_cd, eco_no);
COMMENT ON TABLE qms_change IS '4M 변경 세부 — PLM ECO 1:1 확장, ECO DRAFT 일 때만 저장 (docs/29_P3 11장·Q5)';
COMMENT ON COLUMN qms_change.eco_no IS 'ECO 번호 사본(PLM 에서 바뀌지 않음) — 목록·검색용, 트리거가 채움';

CREATE TABLE qms_change_char (
    line_id       bigint          GENERATED ALWAYS AS IDENTITY,
    comp_cd       varchar(20)     NOT NULL,
    eco_id        bigint          NOT NULL,
    line_no       integer         NOT NULL,
    char_id       bigint,
    insp_plan_id  bigint,
    impact_desc   varchar(1000),
    created_at    timestamptz     NOT NULL DEFAULT now(),
    created_by    varchar(50)     NOT NULL,
    CONSTRAINT pk_qms_change_char PRIMARY KEY (line_id),
    CONSTRAINT fk_qms_change_char_comp FOREIGN KEY (comp_cd) REFERENCES sys_comp (comp_cd),
    CONSTRAINT fk_qms_change_char_change FOREIGN KEY (eco_id) REFERENCES qms_change (eco_id),
    CONSTRAINT fk_qms_change_char_char FOREIGN KEY (char_id) REFERENCES qms_char (char_id),
    CONSTRAINT fk_qms_change_char_plan FOREIGN KEY (insp_plan_id) REFERENCES qms_insp_plan (insp_plan_id),
    CONSTRAINT uq_qms_change_char_no UNIQUE (eco_id, line_no),
    CONSTRAINT ck_qms_change_char_one CHECK ((char_id IS NULL) <> (insp_plan_id IS NULL))
);
CREATE UNIQUE INDEX uq_qms_change_char_ref ON qms_change_char (eco_id, coalesce(char_id, 0), coalesce(insp_plan_id, 0));
COMMENT ON TABLE qms_change_char IS '4M 영향 특성·검사계획(리비전) — 한 벌 저장, ECO DRAFT 일 때만';

-- ECO 상태를 FOR SHARE 로 읽어 DRAFT 확인 — 같은 회사(RLS 로 다른 회사 ECO 는 안 보임)
CREATE FUNCTION hb_qms_change_eco_draft(p_comp varchar, p_eco bigint) RETURNS varchar
    LANGUAGE plpgsql
AS $$
DECLARE
    v_eco plm_eco;
BEGIN
    SELECT * INTO v_eco FROM plm_eco WHERE eco_id = p_eco FOR SHARE;
    IF NOT FOUND OR v_eco.comp_cd <> p_comp THEN
        RAISE EXCEPTION 'ECO % does not belong to company %', p_eco, p_comp USING ERRCODE = '23503', CONSTRAINT = 'fk_qms_change_eco_comp';
    END IF;
    IF v_eco.status <> 'DRAFT' THEN
        RAISE EXCEPTION '4M detail of % ECO % cannot be changed', v_eco.status, p_eco
            USING ERRCODE = '55000', CONSTRAINT = 'ck_qms_change_eco_draft';
    END IF;
    RETURN v_eco.eco_no;
END
$$;

CREATE FUNCTION hb_guard_qms_change() RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    v_no varchar;
BEGIN
    IF TG_OP = 'DELETE' THEN
        PERFORM hb_qms_change_eco_draft(OLD.comp_cd, OLD.eco_id);
        RETURN OLD;
    END IF;
    IF TG_OP = 'UPDATE' AND (NEW.eco_id <> OLD.eco_id OR NEW.comp_cd <> OLD.comp_cd OR NEW.eco_no <> OLD.eco_no) THEN
        RAISE EXCEPTION '4M detail % key cannot be changed', OLD.eco_id USING ERRCODE = '55000', CONSTRAINT = 'ck_qms_change_immutable';
    END IF;
    v_no := hb_qms_change_eco_draft(NEW.comp_cd, NEW.eco_id);
    IF TG_OP = 'INSERT' THEN
        NEW.eco_no := v_no;
    END IF;
    IF cardinality(NEW.m4_types) <> (SELECT count(DISTINCT t) FROM unnest(NEW.m4_types) t) THEN
        RAISE EXCEPTION '4M types must not repeat' USING ERRCODE = '23514', CONSTRAINT = 'ck_qms_change_m4_distinct';
    END IF;
    RETURN NEW;
END
$$;
CREATE TRIGGER tg_qms_change_guard BEFORE INSERT OR UPDATE OR DELETE ON qms_change FOR EACH ROW EXECUTE FUNCTION hb_guard_qms_change();

CREATE FUNCTION hb_guard_qms_change_char() RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    v_row qms_change_char;
BEGIN
    v_row := CASE WHEN TG_OP = 'DELETE' THEN OLD ELSE NEW END;
    IF TG_OP = 'UPDATE' THEN
        RAISE EXCEPTION '4M impact rows are replaced as a set' USING ERRCODE = '55000', CONSTRAINT = 'ck_qms_change_char_no_update';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM qms_change c WHERE c.eco_id = v_row.eco_id AND c.comp_cd = v_row.comp_cd) THEN
        RAISE EXCEPTION '4M detail % does not belong to company %', v_row.eco_id, v_row.comp_cd
            USING ERRCODE = '23503', CONSTRAINT = 'fk_qms_change_char_change_comp';
    END IF;
    PERFORM hb_qms_change_eco_draft(v_row.comp_cd, v_row.eco_id);
    IF TG_OP = 'DELETE' THEN
        RETURN OLD;
    END IF;
    IF (NEW.char_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM qms_char c WHERE c.char_id = NEW.char_id AND c.comp_cd = NEW.comp_cd))
       OR (NEW.insp_plan_id IS NOT NULL
           AND NOT EXISTS (SELECT 1 FROM qms_insp_plan p WHERE p.insp_plan_id = NEW.insp_plan_id AND p.comp_cd = NEW.comp_cd)) THEN
        RAISE EXCEPTION '4M impact target does not belong to company %', NEW.comp_cd
            USING ERRCODE = '23503', CONSTRAINT = 'fk_qms_change_char_comp_ref';
    END IF;
    RETURN NEW;
END
$$;
CREATE TRIGGER tg_qms_change_char_guard BEFORE INSERT OR UPDATE OR DELETE ON qms_change_char
    FOR EACH ROW EXECUTE FUNCTION hb_guard_qms_change_char();

-- ---------------------------------------------------------------------
-- 2. 감사 · RLS · 권한 · 화면 · 보존
-- ---------------------------------------------------------------------
CREATE TRIGGER tg_audit AFTER INSERT OR UPDATE OR DELETE ON qms_change FOR EACH ROW EXECUTE FUNCTION hb_audit('eco_id', '', '');
CREATE TRIGGER tg_audit AFTER INSERT OR UPDATE OR DELETE ON qms_change_char FOR EACH ROW EXECUTE FUNCTION hb_audit('line_id', '', '');

ALTER TABLE qms_change ENABLE ROW LEVEL SECURITY;
ALTER TABLE qms_change FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON qms_change USING (comp_cd = hb_current_comp()) WITH CHECK (comp_cd = hb_current_comp());
ALTER TABLE qms_change_char ENABLE ROW LEVEL SECURITY;
ALTER TABLE qms_change_char FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON qms_change_char USING (comp_cd = hb_current_comp()) WITH CHECK (comp_cd = hb_current_comp());

REVOKE TRUNCATE ON qms_change, qms_change_char FROM ${app_role};

INSERT INTO sys_prog (prog_cd, prog_nm_key, view_path, module_cd, actions, sort_no) VALUES
    ('qms-change', 'prog.qmsChange', 'qms/change-mgt', 'QMS', ARRAY['SEARCH','SAVE','DELETE']::varchar(20)[], 680);
INSERT INTO sys_retention_table (table_nm, class_cd) VALUES
    ('qms_change', 'QUALITY_RECORD'), ('qms_change_char', 'QUALITY_RECORD');
