-- =====================================================================
-- 시스템관리 4a (docs/36 14장) — 회사 관리자 MFA(TOTP) + 복구 코드.
-- 임시 번호(병합 때 재번호, docs/29 2.2)
--
-- 1. sys_user_mfa (사용자 1:1, 봉인된 비밀) + sys_user_mfa_recovery (복구 코드 해시)
-- 2. 정책: MFA_REQUIRED_FOR_ADMIN 기본값 N → Y (Q23·M2), MFA_REQUIRED_PROGS 추가
-- 3. 지원 세션 범위 USERS (운영자가 마지막 관리자의 MFA 를 초기화 — Q25)
-- 4. 접속 로그 이벤트 코드 MFA_* (AccessEvent 와 같은 집합 — 가드 시험)
-- 비밀·코드 원문은 DB 에 없다: 비밀은 앱 키로 봉인(AES-GCM), 복구 코드는 해시만
-- =====================================================================

-- ---------------------------------------------------------------------
-- 1. MFA
-- ---------------------------------------------------------------------
CREATE TABLE sys_user_mfa (
    mfa_id        bigint        GENERATED ALWAYS AS IDENTITY,
    comp_cd       varchar(20)   NOT NULL,
    user_id       bigint        NOT NULL,
    secret_enc    varchar(200)  NOT NULL,
    enabled_yn    char(1)       NOT NULL DEFAULT 'N',
    last_step     bigint        NOT NULL DEFAULT 0,
    fail_cnt      integer       NOT NULL DEFAULT 0,
    locked_until  timestamptz,
    enrolled_at   timestamptz,
    row_ver       integer       NOT NULL DEFAULT 0,
    created_at    timestamptz   NOT NULL DEFAULT now(),
    created_by    varchar(80)   NOT NULL,
    updated_at    timestamptz   NOT NULL DEFAULT now(),
    updated_by    varchar(80)   NOT NULL,
    CONSTRAINT pk_sys_user_mfa PRIMARY KEY (mfa_id),
    CONSTRAINT fk_sys_user_mfa_comp FOREIGN KEY (comp_cd) REFERENCES sys_comp (comp_cd),
    CONSTRAINT fk_sys_user_mfa_user FOREIGN KEY (user_id) REFERENCES sys_user (user_id),
    CONSTRAINT ck_sys_user_mfa_enabled CHECK (enabled_yn IN ('Y', 'N')),
    CONSTRAINT ck_sys_user_mfa_enrolled CHECK ((enabled_yn = 'Y') = (enrolled_at IS NOT NULL)),
    CONSTRAINT ck_sys_user_mfa_fail CHECK (fail_cnt >= 0)
);
CREATE UNIQUE INDEX uq_sys_user_mfa_user ON sys_user_mfa (comp_cd, user_id);
COMMENT ON TABLE sys_user_mfa IS '사용자 MFA(TOTP) — 비밀은 앱 키로 봉인(AES-GCM). enabled_yn = N 은 등록 중(확인 코드 전) (docs/36 14장)';

ALTER TABLE sys_user_mfa ENABLE ROW LEVEL SECURITY;
ALTER TABLE sys_user_mfa FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON sys_user_mfa
    USING (comp_cd = hb_current_comp()) WITH CHECK (comp_cd = hb_current_comp());
CREATE TRIGGER tg_sys_user_mfa_comp BEFORE INSERT OR UPDATE ON sys_user_mfa
    FOR EACH ROW EXECUTE FUNCTION hb_check_user_comp();
-- 실패 수·마지막 구간만 바뀐 UPDATE 는 감사에 남기지 않는다(로그인마다 잡음), 비밀은 값 대신 (changed)
CREATE TRIGGER tg_audit AFTER INSERT OR UPDATE OR DELETE ON sys_user_mfa
    FOR EACH ROW EXECUTE FUNCTION hb_audit('user_id', 'last_step,fail_cnt,locked_until', 'secret_enc');
REVOKE TRUNCATE ON sys_user_mfa FROM ${app_role};
INSERT INTO sys_retention_table (table_nm, class_cd) VALUES ('sys_user_mfa', 'MASTER');

CREATE TABLE sys_user_mfa_recovery (
    recovery_id  bigint       GENERATED ALWAYS AS IDENTITY,
    comp_cd      varchar(20)  NOT NULL,
    user_id      bigint       NOT NULL,
    code_hash    char(64)     NOT NULL,
    created_at   timestamptz  NOT NULL DEFAULT now(),
    used_at      timestamptz,
    CONSTRAINT pk_sys_user_mfa_recovery PRIMARY KEY (recovery_id),
    CONSTRAINT fk_sys_user_mfa_recovery_comp FOREIGN KEY (comp_cd) REFERENCES sys_comp (comp_cd),
    CONSTRAINT fk_sys_user_mfa_recovery_user FOREIGN KEY (user_id) REFERENCES sys_user (user_id),
    CONSTRAINT ck_sys_user_mfa_recovery_hash CHECK (code_hash ~ '^[0-9a-f]{64}$')
);
CREATE UNIQUE INDEX uq_sys_user_mfa_recovery ON sys_user_mfa_recovery (comp_cd, user_id, code_hash);
COMMENT ON TABLE sys_user_mfa_recovery IS 'MFA 복구 코드 — SHA-256(사용자 id:코드) 해시만. 한 번 쓰면 끝 (docs/36 M1)';

ALTER TABLE sys_user_mfa_recovery ENABLE ROW LEVEL SECURITY;
ALTER TABLE sys_user_mfa_recovery FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON sys_user_mfa_recovery
    USING (comp_cd = hb_current_comp()) WITH CHECK (comp_cd = hb_current_comp());
CREATE TRIGGER tg_sys_user_mfa_recovery_comp BEFORE INSERT ON sys_user_mfa_recovery
    FOR EACH ROW EXECUTE FUNCTION hb_check_user_comp();

-- 사용한 코드는 다시 쓸 수 없다(사용 시각을 비우거나 바꾸지 못함)
CREATE FUNCTION hb_guard_mfa_recovery() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF OLD.used_at IS NOT NULL THEN
        RAISE EXCEPTION 'recovery code % already used', OLD.recovery_id
            USING ERRCODE = '55000', CONSTRAINT = 'ck_sys_user_mfa_recovery_once';
    END IF;
    RETURN NEW;
END
$$;
CREATE TRIGGER tg_sys_user_mfa_recovery_once BEFORE UPDATE ON sys_user_mfa_recovery
    FOR EACH ROW EXECUTE FUNCTION hb_guard_mfa_recovery();
REVOKE UPDATE, TRUNCATE ON sys_user_mfa_recovery FROM ${app_role};
GRANT UPDATE (used_at) ON sys_user_mfa_recovery TO ${app_role};
INSERT INTO sys_retention_table (table_nm, class_cd) VALUES ('sys_user_mfa_recovery', 'MASTER');

-- ---------------------------------------------------------------------
-- 2. 정책
-- ---------------------------------------------------------------------
UPDATE sys_policy_def SET default_value = 'Y' WHERE policy_key = 'MFA_REQUIRED_FOR_ADMIN';
INSERT INTO sys_policy_def (policy_key, value_type, default_value, allowed_values, min_value, max_value, desc_key) VALUES
    ('MFA_REQUIRED_PROGS', 'STRING', 'sys-user,sys-auth-group', NULL, NULL, NULL, 'policy.MFA_REQUIRED_PROGS');

-- ---------------------------------------------------------------------
-- 3. 지원 세션 범위 USERS
-- ---------------------------------------------------------------------
ALTER TABLE sys_support_grant DROP CONSTRAINT ck_sys_support_grant_scope;
ALTER TABLE sys_support_grant ADD CONSTRAINT ck_sys_support_grant_scope
    CHECK (scope_cd IN ('LOGS', 'FEEDBACK', 'SETTINGS', 'USERS'));

-- ---------------------------------------------------------------------
-- 4. 접속 로그 이벤트 코드
-- ---------------------------------------------------------------------
INSERT INTO sys_code (grp_cd, code, code_nm_key, sort_no) VALUES
    ('ACCESS_EVENT', 'MFA_OK',       'code.ACCESS_EVENT.MFA_OK',       280),
    ('ACCESS_EVENT', 'MFA_FAIL',     'code.ACCESS_EVENT.MFA_FAIL',     290),
    ('ACCESS_EVENT', 'MFA_LOCKED',   'code.ACCESS_EVENT.MFA_LOCKED',   300),
    ('ACCESS_EVENT', 'MFA_ENROLLED', 'code.ACCESS_EVENT.MFA_ENROLLED', 310),
    ('ACCESS_EVENT', 'MFA_RESET',    'code.ACCESS_EVENT.MFA_RESET',    320);
