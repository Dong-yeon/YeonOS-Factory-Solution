-- =====================================================================
-- PMS 7단계 ② — 산출물 ↔ 프로젝트 ECO 의 PPAP 패키지 (docs/32 PM7·4.9, D5·D18·D19)
-- - pms_deliverable.ref_type 에 PLM_PPAP(ref_id = ppap_id), 이력 LINK 행에 ppap_id
-- - PPAP 에 연결된 산출물이 있으면 프로젝트 ECO 를 바꾸거나 풀 수 없다
-- 앱은 PLM 계약 EcoLookup.ppap 으로만 읽는다. PLM 표(plm_ppap)는 FK·같은 회사 확인으로 읽기만 한다(쓰기 없음)
-- =====================================================================

ALTER TABLE pms_deliverable DROP CONSTRAINT ck_pms_deliverable_ref_type;
ALTER TABLE pms_deliverable ADD CONSTRAINT ck_pms_deliverable_ref_type
    CHECK (ref_type IS NULL OR ref_type IN ('FILE', 'QMS_DOC_REV', 'PLM_PPAP'));
COMMENT ON COLUMN pms_deliverable.ref_type IS '제출물 종류: FILE(첨부) / QMS_DOC_REV(QMS 문서 리비전, 배포 = 승인, D6) / PLM_PPAP(프로젝트 ECO 의 PPAP, 고객 승인 = 승인, D18·D19)';

-- 연결 대상 = 같은 회사. PPAP 는 이 프로젝트 ECO 의 패키지만
CREATE OR REPLACE FUNCTION hb_check_pms_deliverable_doc() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.ref_type IS NULL OR NOT (TG_OP = 'INSERT' OR NEW.ref_id IS DISTINCT FROM OLD.ref_id OR NEW.ref_type IS DISTINCT FROM OLD.ref_type) THEN
        RETURN NEW;
    END IF;
    IF NEW.ref_type = 'QMS_DOC_REV'
       AND NOT EXISTS (SELECT 1 FROM mst_doc_rev r WHERE r.doc_rev_id = NEW.ref_id AND r.comp_cd = NEW.comp_cd) THEN
        RAISE EXCEPTION 'document revision % is not of company %', NEW.ref_id, NEW.comp_cd
            USING ERRCODE = '23503', CONSTRAINT = 'fk_pms_deliverable_doc_rev';
    END IF;
    IF NEW.ref_type = 'PLM_PPAP'
       AND NOT EXISTS (SELECT 1 FROM plm_ppap x JOIN pms_project p ON p.eco_id = x.eco_id
                        WHERE x.ppap_id = NEW.ref_id AND x.comp_cd = NEW.comp_cd AND p.proj_id = NEW.proj_id) THEN
        RAISE EXCEPTION 'PPAP % is not the package of the ECO of project %', NEW.ref_id, NEW.proj_id
            USING ERRCODE = '23503', CONSTRAINT = 'fk_pms_deliverable_ppap';
    END IF;
    RETURN NEW;
END
$$;

ALTER TABLE pms_deliverable_hist ADD COLUMN ppap_id bigint;
COMMENT ON COLUMN pms_deliverable_hist.ppap_id IS 'LINK 행: 연결한 PPAP 패키지(plm_ppap)';
ALTER TABLE pms_deliverable_hist ADD CONSTRAINT fk_pms_deliverable_hist_ppap FOREIGN KEY (ppap_id) REFERENCES plm_ppap (ppap_id);
ALTER TABLE pms_deliverable_hist DROP CONSTRAINT ck_pms_deliverable_hist_doc;
ALTER TABLE pms_deliverable_hist ADD CONSTRAINT ck_pms_deliverable_hist_doc
    CHECK (CASE WHEN action = 'LINK' THEN num_nonnulls(doc_rev_id, ppap_id) = 1 ELSE doc_rev_id IS NULL AND ppap_id IS NULL END);

-- 프로젝트 ECO: 같은 회사 + PPAP 연결 산출물이 있으면 바꾸거나 풀 수 없다
CREATE OR REPLACE FUNCTION hb_check_pms_project_eco() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.eco_id IS NOT DISTINCT FROM OLD.eco_id THEN
        RETURN NEW;
    END IF;
    IF NEW.eco_id IS NOT NULL
       AND NOT EXISTS (SELECT 1 FROM plm_eco e WHERE e.eco_id = NEW.eco_id AND e.comp_cd = NEW.comp_cd) THEN
        RAISE EXCEPTION 'ECO % is not of company %', NEW.eco_id, NEW.comp_cd USING ERRCODE = '23503', CONSTRAINT = 'fk_pms_project_eco_comp';
    END IF;
    IF OLD.eco_id IS NOT NULL
       AND EXISTS (SELECT 1 FROM pms_deliverable d WHERE d.proj_id = OLD.proj_id AND d.ref_type = 'PLM_PPAP') THEN
        RAISE EXCEPTION 'project % has deliverables linked to the PPAP of its ECO', OLD.proj_id
            USING ERRCODE = '55000', CONSTRAINT = 'ck_pms_project_eco_fixed';
    END IF;
    RETURN NEW;
END
$$;
