-- =====================================================================
-- 시스템관리 5단계 (docs/36 16장) — 전자서명 의미·재인증(docs/35 A14) 플랫폼 부분.
-- 임시 번호(병합 때 재번호, docs/29 2.2)
--
-- 1. sys_esign: 서명 원장 (추가만 — 수정·삭제 차단 + REVOKE). 서명 취소 없음, 내용이 바뀌면 새 서명
-- 2. 코드 ESIGN_MEANING(AUTHOR·REVIEW·APPROVE), 접속 로그 이벤트 ESIGN_FAIL·ESIGN_LOCKED
-- =====================================================================

CREATE TABLE sys_esign (
    esign_id      bigint        GENERATED ALWAYS AS IDENTITY,
    comp_cd       varchar(20)   NOT NULL,
    user_id       bigint        NOT NULL,
    signer_nm     varchar(50)   NOT NULL,
    meaning_cd    varchar(20)   NOT NULL,
    target_type   varchar(50)   NOT NULL,
    target_id     bigint        NOT NULL,
    content_hash  char(64)      NOT NULL,
    auth_method   varchar(10)   NOT NULL,
    signed_at     timestamptz   NOT NULL DEFAULT now(),
    CONSTRAINT pk_sys_esign PRIMARY KEY (esign_id),
    CONSTRAINT fk_sys_esign_comp FOREIGN KEY (comp_cd) REFERENCES sys_comp (comp_cd),
    CONSTRAINT fk_sys_esign_user FOREIGN KEY (user_id) REFERENCES sys_user (user_id),
    CONSTRAINT ck_sys_esign_meaning CHECK (meaning_cd ~ '^[A-Z][A-Z0-9_]{1,19}$'),
    CONSTRAINT ck_sys_esign_target CHECK (target_type ~ '^[a-z][a-z0-9_]{1,49}$' AND target_id > 0),
    CONSTRAINT ck_sys_esign_hash CHECK (content_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_sys_esign_method CHECK (auth_method IN ('PWD', 'PWD_TOTP')),
    CONSTRAINT ck_sys_esign_signer CHECK (length(btrim(signer_nm)) > 0)
);
CREATE INDEX ix_sys_esign_target ON sys_esign (comp_cd, target_type, target_id, signed_at);
COMMENT ON TABLE sys_esign IS '전자서명 원장 — 재인증한 사람·의미·대상·내용 해시. 추가만(취소 없음, 내용이 바뀌면 새 서명) (docs/36 16장)';
COMMENT ON COLUMN sys_esign.signer_nm IS '서명 때 이름(출력용 스냅샷 — 이름이 바뀌어도 서명은 그대로)';
COMMENT ON COLUMN sys_esign.content_hash IS 'SHA-256(모듈이 정한 정규 글자) — 서명 뒤 내용이 바뀌면 해시가 달라진다';

ALTER TABLE sys_esign ENABLE ROW LEVEL SECURITY;
ALTER TABLE sys_esign FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON sys_esign
    USING (comp_cd = hb_current_comp()) WITH CHECK (comp_cd = hb_current_comp());
CREATE TRIGGER tg_sys_esign_comp BEFORE INSERT ON sys_esign
    FOR EACH ROW EXECUTE FUNCTION hb_check_user_comp();
-- 의미 코드는 시스템 코드에 있는 것만(모듈이 의미를 늘리려면 코드 행 추가)
CREATE FUNCTION hb_check_esign_meaning() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM sys_code WHERE grp_cd = 'ESIGN_MEANING' AND code = NEW.meaning_cd AND use_yn = 'Y') THEN
        RAISE EXCEPTION 'unknown esign meaning %', NEW.meaning_cd USING ERRCODE = '23514', CONSTRAINT = 'ck_sys_esign_meaning_code';
    END IF;
    RETURN NEW;
END
$$;
CREATE TRIGGER tg_sys_esign_meaning BEFORE INSERT ON sys_esign
    FOR EACH ROW EXECUTE FUNCTION hb_check_esign_meaning();
CREATE TRIGGER tg_sys_esign_append_only BEFORE UPDATE OR DELETE ON sys_esign
    FOR EACH ROW EXECUTE FUNCTION hb_forbid_update_delete();
REVOKE UPDATE, DELETE, TRUNCATE ON sys_esign FROM ${app_role};
INSERT INTO sys_retention_table (table_nm, class_cd) VALUES ('sys_esign', 'QUALITY_RECORD');

-- ---------------------------------------------------------------------
-- 2. 코드
-- ---------------------------------------------------------------------
INSERT INTO sys_code_grp (grp_cd, grp_nm_key, comp_ext_yn) VALUES
    ('ESIGN_MEANING', 'code.grp.ESIGN_MEANING', 'N');
INSERT INTO sys_code (grp_cd, code, code_nm_key, sort_no) VALUES
    ('ESIGN_MEANING', 'AUTHOR',  'code.ESIGN_MEANING.AUTHOR',  10),
    ('ESIGN_MEANING', 'REVIEW',  'code.ESIGN_MEANING.REVIEW',  20),
    ('ESIGN_MEANING', 'APPROVE', 'code.ESIGN_MEANING.APPROVE', 30),
    ('ACCESS_EVENT', 'ESIGN_FAIL',   'code.ACCESS_EVENT.ESIGN_FAIL',   330),
    ('ACCESS_EVENT', 'ESIGN_LOCKED', 'code.ACCESS_EVENT.ESIGN_LOCKED', 340);
