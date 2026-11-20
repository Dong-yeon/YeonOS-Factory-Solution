-- =====================================================================
-- P3 QMS Q2b MSA — GRR 연구 (docs/29 2장 Q2b, 결정 D9) — 임시 번호, 병합 때 재번호
--
-- - 연구 머리 qms_msa_study = 계측기(물리 자산 GAUGE) × 특성(선택) × 방법(AVG_RANGE 평균·범위법 / ATTRIBUTE 계수형 일치율)
--   × 측정자 2~3 · 부품 2~10(계수형 50) · 반복 2~3. ANOVA 는 같은 원자료로 2차(D9)
-- - 원자료 qms_msa_data = 측정자 × 부품 × 반복 한 칸 한 행. 계수형 기준값은 측정자 0 · 반복 1 행(선택)
-- - 상태 DRAFT → DONE(확정: 계산 결과 스냅샷 result_json + %GRR·ndc·판정 칸) / CANCELLED. 확정·취소 뒤 머리·원자료 불변
-- - 확정 순간 원자료가 모양대로 다 찼는지 트리거가 다시 센다(서비스가 칸 오류로 먼저)
-- =====================================================================

INSERT INTO sys_code_grp (grp_cd, grp_nm_key, comp_ext_yn) VALUES
    ('MSA_METHOD',  'code.grp.MSA_METHOD',  'N'),
    ('MSA_BASIS',   'code.grp.MSA_BASIS',   'N'),
    ('MSA_STATUS',  'code.grp.MSA_STATUS',  'N'),
    ('MSA_VERDICT', 'code.grp.MSA_VERDICT', 'N');
INSERT INTO sys_code (grp_cd, code, code_nm_key, sort_no) VALUES
    ('MSA_METHOD', 'AVG_RANGE', 'code.MSA_METHOD.AVG_RANGE', 1),
    ('MSA_METHOD', 'ATTRIBUTE', 'code.MSA_METHOD.ATTRIBUTE', 2),
    ('MSA_BASIS', 'PROCESS',   'code.MSA_BASIS.PROCESS',   1),
    ('MSA_BASIS', 'TOLERANCE', 'code.MSA_BASIS.TOLERANCE', 2),
    ('MSA_STATUS', 'DRAFT',     'code.MSA_STATUS.DRAFT',     1),
    ('MSA_STATUS', 'DONE',      'code.MSA_STATUS.DONE',      2),
    ('MSA_STATUS', 'CANCELLED', 'code.MSA_STATUS.CANCELLED', 3),
    ('MSA_VERDICT', 'ACCEPT',      'code.MSA_VERDICT.ACCEPT',      1),
    ('MSA_VERDICT', 'CONDITIONAL', 'code.MSA_VERDICT.CONDITIONAL', 2),
    ('MSA_VERDICT', 'REJECT',      'code.MSA_VERDICT.REJECT',      3);

INSERT INTO sys_policy_def (policy_key, value_type, default_value, allowed_values, min_value, max_value, plant_scope_yn, desc_key) VALUES
    ('QMS_GRR_OK_PCT',    'INT', '10', NULL, 1, 100, 'N', 'policy.QMS_GRR_OK_PCT'),
    ('QMS_GRR_LIMIT_PCT', 'INT', '30', NULL, 1, 100, 'N', 'policy.QMS_GRR_LIMIT_PCT');

-- ---------------------------------------------------------------------
-- 1. 연구 머리
-- ---------------------------------------------------------------------
CREATE TABLE qms_msa_study (
    study_id       bigint         GENERATED ALWAYS AS IDENTITY,
    comp_cd        varchar(20)    NOT NULL,
    study_no       varchar(30)    NOT NULL,
    asset_id       bigint         NOT NULL,
    char_id        bigint,
    method         varchar(10)    NOT NULL,
    appraiser_cnt    smallint       NOT NULL,
    part_cnt         smallint       NOT NULL,
    trial_cnt        smallint       NOT NULL,
    basis          varchar(10),
    process_sigma  numeric(18,6),
    appraiser_nms  varchar(50)[]  NOT NULL DEFAULT '{}',
    study_date     date           NOT NULL,
    status         varchar(10)    NOT NULL DEFAULT 'DRAFT',
    tolerance      numeric(18,6),
    result_json    jsonb,
    grr_pct        numeric(9,2),
    ndc            integer,
    verdict        varchar(12),
    done_at        timestamptz,
    done_by        varchar(50),
    remark         varchar(500),
    row_ver        integer        NOT NULL DEFAULT 0,
    created_at     timestamptz    NOT NULL DEFAULT now(),
    created_by     varchar(50)    NOT NULL,
    updated_at     timestamptz    NOT NULL DEFAULT now(),
    updated_by     varchar(50)    NOT NULL,
    CONSTRAINT pk_qms_msa_study PRIMARY KEY (study_id),
    CONSTRAINT fk_qms_msa_study_comp FOREIGN KEY (comp_cd) REFERENCES sys_comp (comp_cd),
    CONSTRAINT fk_qms_msa_study_asset FOREIGN KEY (asset_id) REFERENCES mst_phys_asset (asset_id),
    CONSTRAINT fk_qms_msa_study_char FOREIGN KEY (char_id) REFERENCES qms_char (char_id),
    CONSTRAINT uq_qms_msa_study_no UNIQUE (comp_cd, study_no),
    CONSTRAINT ck_qms_msa_study_method CHECK (
        (method = 'AVG_RANGE' AND basis IN ('PROCESS', 'TOLERANCE') AND part_cnt BETWEEN 2 AND 10)
        OR (method = 'ATTRIBUTE' AND basis IS NULL AND process_sigma IS NULL AND part_cnt BETWEEN 2 AND 50)),
    CONSTRAINT ck_qms_msa_study_shape CHECK (appraiser_cnt BETWEEN 2 AND 3 AND trial_cnt BETWEEN 2 AND 3),
    -- 공정 σ 는 공정 기준에서만 (공차 기준이면 공차가 분모)
    CONSTRAINT ck_qms_msa_study_sigma CHECK (process_sigma IS NULL OR (process_sigma > 0 AND basis = 'PROCESS')),
    CONSTRAINT ck_qms_msa_study_names CHECK (cardinality(appraiser_nms) IN (0, appraiser_cnt)),
    CONSTRAINT ck_qms_msa_study_status CHECK (status IN ('DRAFT', 'DONE', 'CANCELLED')),
    -- 확정본은 결과가 있어야 한다 (CHECK 는 NULL 을 통과로 보므로 IS NOT NULL 로 묶는다)
    CONSTRAINT ck_qms_msa_study_done CHECK (status <> 'DONE' OR (result_json IS NOT NULL AND done_at IS NOT NULL AND done_by IS NOT NULL
        AND (method = 'ATTRIBUTE' OR (grr_pct IS NOT NULL AND ndc IS NOT NULL AND verdict IS NOT NULL)))),
    CONSTRAINT ck_qms_msa_study_verdict CHECK (verdict IS NULL OR verdict IN ('ACCEPT', 'CONDITIONAL', 'REJECT'))
);
CREATE INDEX ix_qms_msa_study_asset ON qms_msa_study (comp_cd, asset_id, study_date DESC);
CREATE INDEX ix_qms_msa_study_char ON qms_msa_study (char_id);
COMMENT ON TABLE qms_msa_study IS 'MSA 연구(GRR 평균·범위법 / 계수형 일치율). 확정 때 계산 결과 스냅샷, 확정·취소 뒤 불변 (docs/29 Q2b)';
COMMENT ON COLUMN qms_msa_study.tolerance IS '공차 기준이면 확정 때 특성 상한 − 하한 스냅샷 (특성 규격이 나중에 바뀌어도 결과 그대로)';
COMMENT ON COLUMN qms_msa_study.appraiser_nms IS '측정자 이름(측정자 번호 순) — 비우거나 측정자 수만큼';

CREATE FUNCTION hb_guard_qms_msa_study() RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    v_cells integer;
    v_bad   integer;
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'MSA study cannot be deleted (cancel it)' USING ERRCODE = '55000', CONSTRAINT = 'ck_qms_msa_study_no_delete';
    END IF;
    IF TG_OP = 'INSERT' AND NEW.status <> 'DRAFT' THEN
        RAISE EXCEPTION 'new MSA study must be DRAFT' USING ERRCODE = '55000', CONSTRAINT = 'ck_qms_msa_study_draft';
    END IF;
    IF TG_OP = 'UPDATE' THEN
        IF NEW.comp_cd <> OLD.comp_cd THEN
            RAISE EXCEPTION 'company cannot change' USING ERRCODE = '55000', CONSTRAINT = 'ck_qms_msa_study_immutable';
        END IF;
        IF OLD.status <> 'DRAFT' THEN
            RAISE EXCEPTION '% MSA study % cannot be changed', OLD.status, OLD.study_id
                USING ERRCODE = '55000', CONSTRAINT = 'ck_qms_msa_study_immutable';
        END IF;
        IF NEW.status <> OLD.status AND NEW.status NOT IN ('DONE', 'CANCELLED') THEN
            RAISE EXCEPTION 'MSA study status % -> % is not allowed', OLD.status, NEW.status
                USING ERRCODE = '55000', CONSTRAINT = 'ck_qms_msa_study_transition';
        END IF;
    END IF;
    -- 계측기·특성: 같은 회사, 새 참조는 사용 중 게이지·특성
    IF TG_OP = 'INSERT' OR NEW.asset_id <> OLD.asset_id THEN
        IF NOT EXISTS (SELECT 1 FROM mst_phys_asset a WHERE a.asset_id = NEW.asset_id AND a.comp_cd = NEW.comp_cd
                          AND a.asset_type = 'GAUGE' AND a.use_yn = 'Y') THEN
            RAISE EXCEPTION 'gauge % is not an active gauge of company %', NEW.asset_id, NEW.comp_cd
                USING ERRCODE = '23503', CONSTRAINT = 'fk_qms_msa_study_asset_comp';
        END IF;
    END IF;
    IF NEW.char_id IS NOT NULL AND (TG_OP = 'INSERT' OR NEW.char_id IS DISTINCT FROM OLD.char_id) THEN
        IF NOT EXISTS (SELECT 1 FROM qms_char c WHERE c.char_id = NEW.char_id AND c.comp_cd = NEW.comp_cd AND c.use_yn = 'Y') THEN
            RAISE EXCEPTION 'characteristic % is not active in company %', NEW.char_id, NEW.comp_cd
                USING ERRCODE = '23503', CONSTRAINT = 'fk_qms_msa_study_char_comp';
        END IF;
    END IF;
    -- 확정: 원자료가 모양(측정자 × 부품 × 반복)대로 빠짐없이, 모양 밖 행 없이, 방법에 맞는 칸으로
    IF TG_OP = 'UPDATE' AND OLD.status = 'DRAFT' AND NEW.status = 'DONE' THEN
        SELECT count(*) FILTER (WHERE d.appraiser_no BETWEEN 1 AND NEW.appraiser_cnt AND d.part_no <= NEW.part_cnt AND d.trial_no <= NEW.trial_cnt
                                  AND ((NEW.method = 'AVG_RANGE' AND d.value IS NOT NULL) OR (NEW.method = 'ATTRIBUTE' AND d.attr_value IS NOT NULL))),
               count(*) FILTER (WHERE d.appraiser_no > NEW.appraiser_cnt OR d.part_no > NEW.part_cnt OR d.trial_no > NEW.trial_cnt
                                  OR (d.appraiser_no = 0 AND NEW.method <> 'ATTRIBUTE'))
          INTO v_cells, v_bad
          FROM qms_msa_data d WHERE d.study_id = NEW.study_id;
        IF v_cells <> NEW.appraiser_cnt * NEW.part_cnt * NEW.trial_cnt OR v_bad > 0 THEN
            RAISE EXCEPTION 'MSA study % data is incomplete (% of % cells)', NEW.study_id, v_cells, NEW.appraiser_cnt * NEW.part_cnt * NEW.trial_cnt
                USING ERRCODE = '23514', CONSTRAINT = 'ck_qms_msa_study_data_complete';
        END IF;
    END IF;
    RETURN NEW;
END
$$;
CREATE TRIGGER tg_qms_msa_study_guard BEFORE INSERT OR UPDATE OR DELETE ON qms_msa_study
    FOR EACH ROW EXECUTE FUNCTION hb_guard_qms_msa_study();

-- ---------------------------------------------------------------------
-- 2. 원자료
-- ---------------------------------------------------------------------
CREATE TABLE qms_msa_data (
    data_id       bigint         GENERATED ALWAYS AS IDENTITY,
    comp_cd       varchar(20)    NOT NULL,
    study_id      bigint         NOT NULL,
    appraiser_no  smallint       NOT NULL,
    part_no       smallint       NOT NULL,
    trial_no      smallint       NOT NULL,
    value         numeric(18,6),
    attr_value    varchar(2),
    created_at    timestamptz    NOT NULL DEFAULT now(),
    created_by    varchar(50)    NOT NULL,
    CONSTRAINT pk_qms_msa_data PRIMARY KEY (data_id),
    CONSTRAINT fk_qms_msa_data_comp FOREIGN KEY (comp_cd) REFERENCES sys_comp (comp_cd),
    CONSTRAINT fk_qms_msa_data_study FOREIGN KEY (study_id) REFERENCES qms_msa_study (study_id),
    CONSTRAINT uq_qms_msa_data_cell UNIQUE (study_id, appraiser_no, part_no, trial_no),
    CONSTRAINT ck_qms_msa_data_pos CHECK (appraiser_no BETWEEN 0 AND 3 AND part_no BETWEEN 1 AND 50 AND trial_no BETWEEN 1 AND 3),
    -- 한 칸 = 수치 또는 OK/NG 중 하나. 측정자 0 = 계수형 기준값(반복 1, OK/NG)
    CONSTRAINT ck_qms_msa_data_value CHECK ((value IS NULL) <> (attr_value IS NULL)),
    CONSTRAINT ck_qms_msa_data_attr CHECK (attr_value IS NULL OR attr_value IN ('OK', 'NG')),
    CONSTRAINT ck_qms_msa_data_ref CHECK (appraiser_no <> 0 OR (trial_no = 1 AND attr_value IS NOT NULL))
);
COMMENT ON TABLE qms_msa_data IS 'MSA 원자료(측정자 × 부품 × 반복 한 칸 한 행). 연구가 작성 중일 때만 바뀐다. 측정자 0 = 계수형 기준값';

CREATE FUNCTION hb_guard_qms_msa_data() RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    v_row   qms_msa_data;
    v_study qms_msa_study;
BEGIN
    v_row := CASE WHEN TG_OP = 'DELETE' THEN OLD ELSE NEW END;
    SELECT * INTO v_study FROM qms_msa_study WHERE study_id = v_row.study_id;
    IF NOT FOUND OR v_study.comp_cd <> v_row.comp_cd THEN
        RAISE EXCEPTION 'MSA study % does not belong to company %', v_row.study_id, v_row.comp_cd
            USING ERRCODE = '23503', CONSTRAINT = 'fk_qms_msa_data_study_comp';
    END IF;
    IF v_study.status <> 'DRAFT' THEN
        RAISE EXCEPTION 'data of % MSA study % cannot be changed', v_study.status, v_row.study_id
            USING ERRCODE = '55000', CONSTRAINT = 'ck_qms_msa_data_draft';
    END IF;
    IF TG_OP = 'UPDATE' AND (NEW.study_id <> OLD.study_id OR NEW.comp_cd <> OLD.comp_cd) THEN
        RAISE EXCEPTION 'data cannot move to another study' USING ERRCODE = '55000', CONSTRAINT = 'ck_qms_msa_data_move';
    END IF;
    IF TG_OP <> 'DELETE' AND ((v_study.method = 'AVG_RANGE' AND NEW.value IS NULL)
                             OR (v_study.method = 'ATTRIBUTE' AND NEW.attr_value IS NULL)) THEN
        RAISE EXCEPTION 'data kind does not match method % of study %', v_study.method, NEW.study_id
            USING ERRCODE = '23514', CONSTRAINT = 'ck_qms_msa_data_kind';
    END IF;
    RETURN v_row;
END
$$;
CREATE TRIGGER tg_qms_msa_data_guard BEFORE INSERT OR UPDATE OR DELETE ON qms_msa_data
    FOR EACH ROW EXECUTE FUNCTION hb_guard_qms_msa_data();

-- ---------------------------------------------------------------------
-- 3. 감사 · RLS · 권한 · 화면 · 보존
-- ---------------------------------------------------------------------
CREATE TRIGGER tg_audit AFTER INSERT OR UPDATE OR DELETE ON qms_msa_study FOR EACH ROW EXECUTE FUNCTION hb_audit('study_id', '', '');
CREATE TRIGGER tg_audit AFTER INSERT OR UPDATE OR DELETE ON qms_msa_data FOR EACH ROW EXECUTE FUNCTION hb_audit('data_id', '', '');

ALTER TABLE qms_msa_study ENABLE ROW LEVEL SECURITY;
ALTER TABLE qms_msa_study FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON qms_msa_study USING (comp_cd = hb_current_comp()) WITH CHECK (comp_cd = hb_current_comp());
ALTER TABLE qms_msa_data ENABLE ROW LEVEL SECURITY;
ALTER TABLE qms_msa_data FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON qms_msa_data USING (comp_cd = hb_current_comp()) WITH CHECK (comp_cd = hb_current_comp());

-- 연구는 취소로만 끝낸다. 원자료는 작성 중일 때 지우고 다시 넣는다(트리거가 상태 확인)
REVOKE DELETE, TRUNCATE ON qms_msa_study FROM ${app_role};
REVOKE TRUNCATE ON qms_msa_data FROM ${app_role};

INSERT INTO sys_prog (prog_cd, prog_nm_key, view_path, module_cd, actions, sort_no) VALUES
    ('qms-msa', 'prog.qmsMsa', 'qms/msa-study', 'QMS', ARRAY['SEARCH','SAVE','APPROVE','EXCEL']::varchar(20)[], 630);
INSERT INTO sys_retention_table (table_nm, class_cd) VALUES ('qms_msa_study', 'QUALITY_RECORD'), ('qms_msa_data', 'QUALITY_RECORD');
