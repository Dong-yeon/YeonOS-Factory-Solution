-- =====================================================================
-- P3 QMS MSA GRR ANOVA 방법 (docs/29_P3 45장, 결정 D9 "ANOVA 는 같은 원자료로 2차") — 임시 번호, 병합 때 재번호
--
-- - 방법 코드 ANOVA 추가. 모양·기준 규칙은 AVG_RANGE 와 같다(같은 연구를 두 방법으로 비교 — 측정자 2~3·부품 2~10·반복 2~3)
-- - CHECK ck_qms_msa_study_method 교체(기존 의미 + ANOVA), 계량형 기준 NULL 빈틈은 별도 제약 NOT VALID(기존 행 검사 안 함), 가드 함수 2개(확정 칸 수·원자료 종류)를 ANOVA 도 계량형으로 보게 교체
-- - 표·열 추가 없음. 결과 스냅샷(result_json)에 ANOVA 표가 함께 들어간다(서비스)
-- =====================================================================

INSERT INTO sys_code (grp_cd, code, code_nm_key, sort_no) VALUES
    ('MSA_METHOD', 'ANOVA', 'code.MSA_METHOD.ANOVA', 3);

ALTER TABLE qms_msa_study DROP CONSTRAINT ck_qms_msa_study_method;
-- 기존 의미(V071) + ANOVA 만 — 기존 행은 언제나 통과(ADD CONSTRAINT 가 기존 행을 검사해도 기동이 멈추지 않게)
ALTER TABLE qms_msa_study ADD CONSTRAINT ck_qms_msa_study_method CHECK (
    (method IN ('AVG_RANGE', 'ANOVA') AND basis IN ('PROCESS', 'TOLERANCE') AND part_cnt BETWEEN 2 AND 10)
    OR (method = 'ATTRIBUTE' AND basis IS NULL AND process_sigma IS NULL AND part_cnt BETWEEN 2 AND 50));

-- V071 빈틈: 계량형인데 기준이 NULL 이면 위 IN 이 NULL 이라 CHECK 를 통과한다(서비스는 늘 PROCESS 기본값 — 직접 SQL 만 해당).
-- 별도 제약 NOT VALID — 새로 넣거나 바뀐 행만 검사하고, 이미 있는 그런 행(확정본은 가드 때문에 고칠 수도 없음)은 그대로 둔다
ALTER TABLE qms_msa_study ADD CONSTRAINT ck_qms_msa_study_basis_required CHECK (method = 'ATTRIBUTE' OR basis IS NOT NULL) NOT VALID;

CREATE OR REPLACE FUNCTION hb_guard_qms_msa_study() RETURNS trigger
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
                                  AND ((NEW.method IN ('AVG_RANGE', 'ANOVA') AND d.value IS NOT NULL) OR (NEW.method = 'ATTRIBUTE' AND d.attr_value IS NOT NULL))),
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

CREATE OR REPLACE FUNCTION hb_guard_qms_msa_data() RETURNS trigger
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
    IF TG_OP <> 'DELETE' AND ((v_study.method IN ('AVG_RANGE', 'ANOVA') AND NEW.value IS NULL)
                             OR (v_study.method = 'ATTRIBUTE' AND NEW.attr_value IS NULL)) THEN
        RAISE EXCEPTION 'data kind does not match method % of study %', v_study.method, NEW.study_id
            USING ERRCODE = '23514', CONSTRAINT = 'ck_qms_msa_data_kind';
    END IF;
    RETURN v_row;
END
$$;
