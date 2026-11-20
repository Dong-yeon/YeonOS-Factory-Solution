-- PLM PCN 메일 발송 이력 (docs/39 23장, 사용자 결정 2026-10-23 E1~E9) — 임시 번호 V9907(병합 때 상위가 정식 번호로)
-- 고객 담당자에게 보낸 메일 한 통 = 한 행. 받는 주소·이름은 보낸 때의 스냅샷(담당자 정보가 바뀌어도 기록은 그대로).
-- 송신 자체는 플랫폼 MailService(아웃박스 MAIL) — 여기는 업무 쪽 "누가 언제 누구에게" 기록. 수정·삭제 없음.

CREATE TABLE plm_pcn_mail (
    mail_id      bigint        GENERATED ALWAYS AS IDENTITY,
    comp_cd      varchar(20)   NOT NULL,
    pcn_id       bigint        NOT NULL,
    contact_id   bigint        NOT NULL,
    to_addr      varchar(200)  NOT NULL,
    to_nm        varchar(100)  NOT NULL,
    subject      varchar(200)  NOT NULL,
    outbox_id    uuid          NOT NULL,
    created_at   timestamptz   NOT NULL DEFAULT now(),
    created_by   varchar(50)   NOT NULL,
    CONSTRAINT pk_plm_pcn_mail PRIMARY KEY (mail_id),
    CONSTRAINT fk_plm_pcn_mail_comp FOREIGN KEY (comp_cd) REFERENCES sys_comp (comp_cd),
    CONSTRAINT fk_plm_pcn_mail_pcn FOREIGN KEY (pcn_id) REFERENCES plm_pcn (pcn_id),
    CONSTRAINT fk_plm_pcn_mail_contact FOREIGN KEY (contact_id) REFERENCES mst_cust_contact (contact_id),
    CONSTRAINT ck_plm_pcn_mail_addr CHECK (length(btrim(to_addr)) > 0),
    CONSTRAINT ck_plm_pcn_mail_nm CHECK (length(btrim(to_nm)) > 0)
);
COMMENT ON TABLE plm_pcn_mail IS 'PCN 메일 발송 이력 (docs/39 23장) — 받는 주소·이름 스냅샷, 원장처럼 수정·삭제 없음(감사 컬럼은 created 만)';
CREATE INDEX ix_plm_pcn_mail_pcn ON plm_pcn_mail (comp_cd, pcn_id);

-- 같은 회사 + 담당자는 그 통지의 고객 담당자여야 한다(다른 고객 담당자에게 보낸 기록이 생기지 않게)
CREATE FUNCTION hb_guard_plm_pcn_mail() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM plm_pcn n JOIN mst_cust_contact t ON t.cust_id = n.cust_id AND t.comp_cd = n.comp_cd
                    WHERE n.pcn_id = NEW.pcn_id AND n.comp_cd = NEW.comp_cd AND t.contact_id = NEW.contact_id) THEN
        RAISE EXCEPTION 'PCN mail recipient is not a contact of the PCN customer in company %', NEW.comp_cd
            USING ERRCODE = '23503', CONSTRAINT = 'fk_plm_pcn_mail_ref_comp';
    END IF;
    RETURN NEW;
END
$$;
CREATE TRIGGER tg_plm_pcn_mail_guard BEFORE INSERT ON plm_pcn_mail FOR EACH ROW EXECUTE FUNCTION hb_guard_plm_pcn_mail();
CREATE TRIGGER tg_plm_pcn_mail_ledger BEFORE UPDATE OR DELETE ON plm_pcn_mail FOR EACH ROW EXECUTE FUNCTION hb_forbid_update_delete();

ALTER TABLE plm_pcn_mail ENABLE ROW LEVEL SECURITY;
ALTER TABLE plm_pcn_mail FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON plm_pcn_mail USING (comp_cd = hb_current_comp()) WITH CHECK (comp_cd = hb_current_comp());
REVOKE DELETE, TRUNCATE ON plm_pcn_mail FROM ${app_role};

-- 변경 감사(추가만 하는 표라 INSERT 만 남는다)·보존 클래스 = 통지(plm_pcn)와 같은 MASTER
CREATE TRIGGER tg_audit AFTER INSERT OR UPDATE OR DELETE ON plm_pcn_mail FOR EACH ROW EXECUTE FUNCTION hb_audit('mail_id', '', '');
INSERT INTO sys_retention_table (table_nm, class_cd) VALUES ('plm_pcn_mail', 'MASTER');
