-- PLM PCN 메일에 붙인 변경 통지서 사본 (docs/39 33장 F4, 상위 승인 2026-10-24) — 임시 번호 V9909(병합 때 상위가 정식 번호로)
-- 메일 한 통에 무엇을 붙였나 = 통지서 사본 파일(plm-eco × PCN-{pcn_id}) 하나 또는 없음.
-- 원장 표라 insert 때만 채운다 — 기존 UPDATE·DELETE 차단 트리거(tg_plm_pcn_mail_ledger)가 그대로 막는다.

ALTER TABLE plm_pcn_mail ADD COLUMN notice_file_id bigint;
ALTER TABLE plm_pcn_mail ADD CONSTRAINT fk_plm_pcn_mail_notice FOREIGN KEY (notice_file_id) REFERENCES sys_file (file_id);
COMMENT ON COLUMN plm_pcn_mail.notice_file_id IS '첨부한 변경 통지서 사본(plm-eco × PCN-{pcn_id}) — 없으면 글자 메일 (docs/39 33장)';

-- 같은 회사 + 담당자는 그 통지의 고객 담당자 + 통지서 사본은 같은 회사·그 통지 대상의 사용 중 파일
CREATE OR REPLACE FUNCTION hb_guard_plm_pcn_mail() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM plm_pcn n JOIN mst_cust_contact t ON t.cust_id = n.cust_id AND t.comp_cd = n.comp_cd
                    WHERE n.pcn_id = NEW.pcn_id AND n.comp_cd = NEW.comp_cd AND t.contact_id = NEW.contact_id) THEN
        RAISE EXCEPTION 'PCN mail recipient is not a contact of the PCN customer in company %', NEW.comp_cd
            USING ERRCODE = '23503', CONSTRAINT = 'fk_plm_pcn_mail_ref_comp';
    END IF;
    IF NEW.notice_file_id IS NOT NULL AND NOT EXISTS (
            SELECT 1 FROM sys_file f
             WHERE f.file_id = NEW.notice_file_id AND f.comp_cd = NEW.comp_cd AND f.use_yn = 'Y'
               AND f.target_type = 'plm-eco' AND f.target_id = 'PCN-' || NEW.pcn_id) THEN
        RAISE EXCEPTION 'PCN mail notice file is not the notice copy of PCN % in company %', NEW.pcn_id, NEW.comp_cd
            USING ERRCODE = '23503', CONSTRAINT = 'fk_plm_pcn_mail_notice_ref';
    END IF;
    RETURN NEW;
END
$$;
