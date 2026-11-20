-- QMS 검사 실행 엑셀 올리기 (docs/29_P3 25장 XI-7, 사용자·상위 결정 2026-10-27) — 측정값 원장에 입력 방법·원본 올리기 작업
-- 화면 = KEY(기본), 엑셀 = IMPORT + 그 작업(sys_import_job). 무효(정정) 행은 언제나 KEY. 원장은 그대로 추가만(V099 가드)

ALTER TABLE qms_insp_meas
    ADD COLUMN entry_method  varchar(10) NOT NULL DEFAULT 'KEY',
    ADD COLUMN import_job_id bigint;

ALTER TABLE qms_insp_meas
    ADD CONSTRAINT fk_qms_insp_meas_import_job FOREIGN KEY (import_job_id) REFERENCES sys_import_job (job_id),
    ADD CONSTRAINT ck_qms_insp_meas_entry_method CHECK (entry_method IN ('KEY', 'IMPORT')),
    -- 엑셀이면 작업이 있고, 화면이면 없다. 무효 행은 화면(정정 경로)만
    ADD CONSTRAINT ck_qms_insp_meas_import_job CHECK ((entry_method = 'IMPORT') = (import_job_id IS NOT NULL)
        AND (void_of_id IS NULL OR entry_method = 'KEY'));

COMMENT ON COLUMN qms_insp_meas.entry_method IS '입력 방법 — KEY(화면)·IMPORT(엑셀 올리기, docs/29_P3 25장)';
COMMENT ON COLUMN qms_insp_meas.import_job_id IS '엑셀 올리기 작업(sys_import_job) — 원본 파일까지 따라감';

-- 작업은 같은 회사·검사 측정 템플릿이어야 한다(다른 회사 작업 id 를 가리키지 않게)
CREATE FUNCTION hb_guard_qms_insp_meas_job() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.import_job_id IS NOT NULL AND NOT EXISTS (
            SELECT 1 FROM sys_import_job j WHERE j.job_id = NEW.import_job_id AND j.comp_cd = NEW.comp_cd AND j.template_cd = 'QMS_INSP_MEAS') THEN
        RAISE EXCEPTION 'import job % is not a QMS_INSP_MEAS job of company %', NEW.import_job_id, NEW.comp_cd
            USING ERRCODE = '23503', CONSTRAINT = 'fk_qms_insp_meas_import_job_comp';
    END IF;
    RETURN NEW;
END
$$;
CREATE TRIGGER tg_qms_insp_meas_job BEFORE INSERT ON qms_insp_meas FOR EACH ROW EXECUTE FUNCTION hb_guard_qms_insp_meas_job();
