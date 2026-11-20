-- PLM PPAP 제출 메일 발송 이력 (docs/39 37장 P1~P9, 상위 채택 2026-10-26) — V190(임시 V9911)
-- 고객 담당자에게 보낸 메일 한 통 = 한 행. 받는 주소·이름은 보낸 때의 스냅샷. 첨부 = 그 시점 PPAP 제출 요약서 사본(plm-eco × PPAPMAIL-{ppap_id}).
-- 송신 자체는 플랫폼 MailService(아웃박스 MAIL) — 여기는 업무 쪽 "누가 언제 누구에게 무엇을" 기록. 수정·삭제 없음(원장).

CREATE TABLE plm_ppap_mail (
    mail_id          bigint        GENERATED ALWAYS AS IDENTITY,
    comp_cd          varchar(20)   NOT NULL,
    ppap_id          bigint        NOT NULL,
    contact_id       bigint        NOT NULL,
    to_addr          varchar(200)  NOT NULL,
    to_nm            varchar(100)  NOT NULL,
    subject          varchar(200)  NOT NULL,
    outbox_id        uuid          NOT NULL,
    summary_file_id  bigint        NOT NULL,
    created_at       timestamptz   NOT NULL DEFAULT now(),
    created_by       varchar(50)   NOT NULL,
    CONSTRAINT pk_plm_ppap_mail PRIMARY KEY (mail_id),
    CONSTRAINT fk_plm_ppap_mail_comp FOREIGN KEY (comp_cd) REFERENCES sys_comp (comp_cd),
    CONSTRAINT fk_plm_ppap_mail_ppap FOREIGN KEY (ppap_id) REFERENCES plm_ppap (ppap_id),
    CONSTRAINT fk_plm_ppap_mail_contact FOREIGN KEY (contact_id) REFERENCES mst_cust_contact (contact_id),
    CONSTRAINT fk_plm_ppap_mail_summary FOREIGN KEY (summary_file_id) REFERENCES sys_file (file_id),
    CONSTRAINT ck_plm_ppap_mail_addr CHECK (length(btrim(to_addr)) > 0),
    CONSTRAINT ck_plm_ppap_mail_nm CHECK (length(btrim(to_nm)) > 0)
);
COMMENT ON TABLE plm_ppap_mail IS 'PPAP 제출 메일 발송 이력 (docs/39 37장) — 받는 주소·이름 스냅샷, 요약서 사본, 원장처럼 수정·삭제 없음';
CREATE INDEX ix_plm_ppap_mail_ppap ON plm_ppap_mail (comp_cd, ppap_id);

-- 같은 회사 + 담당자는 그 패키지 고객의 담당자 + 요약서 사본은 같은 회사·그 패키지 사본 대상의 사용 중 파일
CREATE FUNCTION hb_guard_plm_ppap_mail() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM plm_ppap p JOIN mst_cust_contact t ON t.cust_id = p.cust_id AND t.comp_cd = p.comp_cd
                    WHERE p.ppap_id = NEW.ppap_id AND p.comp_cd = NEW.comp_cd AND t.contact_id = NEW.contact_id) THEN
        RAISE EXCEPTION 'PPAP mail recipient is not a contact of the PPAP customer in company %', NEW.comp_cd
            USING ERRCODE = '23503', CONSTRAINT = 'fk_plm_ppap_mail_ref_comp';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM sys_file f
                    WHERE f.file_id = NEW.summary_file_id AND f.comp_cd = NEW.comp_cd AND f.use_yn = 'Y'
                      AND f.target_type = 'plm-eco' AND f.target_id = 'PPAPMAIL-' || NEW.ppap_id) THEN
        RAISE EXCEPTION 'PPAP mail summary file is not the summary copy of PPAP % in company %', NEW.ppap_id, NEW.comp_cd
            USING ERRCODE = '23503', CONSTRAINT = 'fk_plm_ppap_mail_summary_ref';
    END IF;
    RETURN NEW;
END
$$;
CREATE TRIGGER tg_plm_ppap_mail_guard BEFORE INSERT ON plm_ppap_mail FOR EACH ROW EXECUTE FUNCTION hb_guard_plm_ppap_mail();
CREATE TRIGGER tg_plm_ppap_mail_ledger BEFORE UPDATE OR DELETE ON plm_ppap_mail FOR EACH ROW EXECUTE FUNCTION hb_forbid_update_delete();

ALTER TABLE plm_ppap_mail ENABLE ROW LEVEL SECURITY;
ALTER TABLE plm_ppap_mail FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON plm_ppap_mail USING (comp_cd = hb_current_comp()) WITH CHECK (comp_cd = hb_current_comp());
REVOKE DELETE, TRUNCATE ON plm_ppap_mail FROM ${app_role};

-- 변경 감사(추가만 하는 표라 INSERT 만 남는다)·보존 클래스 = 패키지(plm_ppap)와 같은 MASTER
CREATE TRIGGER tg_audit AFTER INSERT OR UPDATE OR DELETE ON plm_ppap_mail FOR EACH ROW EXECUTE FUNCTION hb_audit('mail_id', '', '');
INSERT INTO sys_retention_table (table_nm, class_cd) VALUES ('plm_ppap_mail', 'MASTER');
