-- =====================================================================
-- PMS 7단계 — 프로젝트 ↔ PLM ECO (docs/32 PM7·4.9, D17·D20)
-- pms_project.eco_id(비울 수 있음, 회사 안에서 ECO 하나에 프로젝트 하나). 앱은 PLM 계약 EcoLookup 으로만 읽는다.
-- PLM 표(plm_eco)는 FK·같은 회사 확인으로 읽기만 한다(쓰기 없음).
-- PPAP 연결(D18·D19)·이슈 LOT 연결은 제공자 계약(EcoLookup.ppap·LotLookup.byNo)이 생긴 뒤 새 V 파일로
-- =====================================================================

ALTER TABLE pms_project ADD COLUMN eco_id bigint;
ALTER TABLE pms_project ADD CONSTRAINT fk_pms_project_eco FOREIGN KEY (eco_id) REFERENCES plm_eco (eco_id);
CREATE UNIQUE INDEX uq_pms_project_eco ON pms_project (comp_cd, eco_id) WHERE eco_id IS NOT NULL;
COMMENT ON COLUMN pms_project.eco_id IS 'PLM 변경지시(ECO) — 4M 변경·신규 품목 도입의 실행 프로젝트 (D17)';

-- ECO = 같은 회사(FK 는 RLS 를 무시한다)
CREATE FUNCTION hb_check_pms_project_eco() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.eco_id IS NOT NULL AND NEW.eco_id IS DISTINCT FROM OLD.eco_id
       AND NOT EXISTS (SELECT 1 FROM plm_eco e WHERE e.eco_id = NEW.eco_id AND e.comp_cd = NEW.comp_cd) THEN
        RAISE EXCEPTION 'ECO % is not of company %', NEW.eco_id, NEW.comp_cd USING ERRCODE = '23503', CONSTRAINT = 'fk_pms_project_eco_comp';
    END IF;
    RETURN NEW;
END
$$;
CREATE TRIGGER tg_pms_project_zeco BEFORE UPDATE OF eco_id ON pms_project
    FOR EACH ROW EXECUTE FUNCTION hb_check_pms_project_eco();
