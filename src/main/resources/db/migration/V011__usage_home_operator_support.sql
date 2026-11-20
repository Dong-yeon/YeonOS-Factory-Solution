-- =====================================================================
-- V011 운영 (P0 9단계): 역할별 홈 · 활용 일 집계 · 운영자 · 운영 기록 · 고객 승인 지원 세션
--
-- 결정 (2026-10-15 사용자): D3 운영자는 회사별 "집계 숫자만" 본다 / D4 POP = 단말 등록 + 배지(P2) / D11 협력사 = 1회용 링크(P5)
-- 운영자(SmartHappy 운영자)는 회사 사용자와 다른 계정 체계다. "모든 회사 조회" 경로는 만들지 않는다(05 D8).
-- =====================================================================

-- ---------------------------------------------------------------------
-- 1. 역할별 홈 (docs/11 D6) — 권한그룹마다 로그인 직후 열 화면
-- ---------------------------------------------------------------------
ALTER TABLE sys_auth_group ADD COLUMN home_prog_cd varchar(50);
ALTER TABLE sys_auth_group ADD CONSTRAINT fk_sys_auth_group_home FOREIGN KEY (home_prog_cd) REFERENCES sys_prog (prog_cd);
COMMENT ON COLUMN sys_auth_group.home_prog_cd IS '로그인 직후 열 화면 (현장 역할은 메뉴 트리 대신 홈 하나, docs/11 D6)';

-- ---------------------------------------------------------------------
-- 2. 운영자 범위 (app.scope = 'OPS') — 운영자 콘솔 요청에서만 설정된다(TenantContext)
-- ---------------------------------------------------------------------
CREATE FUNCTION hb_is_ops_scope() RETURNS boolean
    LANGUAGE sql STABLE PARALLEL SAFE
AS $$
    SELECT coalesce(current_setting('app.scope', true), '') = 'OPS'
$$;
COMMENT ON FUNCTION hb_is_ops_scope() IS '운영자 콘솔 범위 여부 — 회사별 활용 집계(sys_usage_daily) 읽기와 회사 목록에만 쓴다';

-- 운영자는 회사 목록(코드·이름·상태)을 본다 (회사 생성·관리). 회사의 업무 데이터는 아니다
CREATE POLICY ops_directory_read ON sys_comp FOR SELECT USING (hb_is_ops_scope());

-- ---------------------------------------------------------------------
-- 3. 활용 일 집계 (docs/11 D2·D3) — 접속 로그는 보존 기간 뒤 정리되므로 추이는 집계로 남긴다. 사용자 ID 없음
-- ---------------------------------------------------------------------
CREATE TABLE sys_usage_daily (
    comp_cd      varchar(20) NOT NULL,
    usage_date   date        NOT NULL,
    event_cd     varchar(30) NOT NULL,
    prog_cd      varchar(50) NOT NULL DEFAULT '',
    action_cd    varchar(20) NOT NULL DEFAULT '',
    client_type  varchar(10) NOT NULL,
    user_cnt     integer     NOT NULL,
    event_cnt    integer     NOT NULL,
    created_at   timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT pk_sys_usage_daily PRIMARY KEY (comp_cd, usage_date, event_cd, prog_cd, action_cd, client_type),
    CONSTRAINT fk_sys_usage_daily_comp FOREIGN KEY (comp_cd) REFERENCES sys_comp (comp_cd),
    CONSTRAINT ck_sys_usage_daily_cnt CHECK (user_cnt >= 0 AND event_cnt >= 0)
);
COMMENT ON TABLE sys_usage_daily IS '활용 일 집계 (회사 시간대 날짜). 회사 관리자 + 운영자(OPS 범위, 숫자만) 열람';

ALTER TABLE sys_usage_daily ENABLE ROW LEVEL SECURITY;
ALTER TABLE sys_usage_daily FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON sys_usage_daily USING (comp_cd = hb_current_comp()) WITH CHECK (comp_cd = hb_current_comp());
CREATE POLICY ops_usage_read ON sys_usage_daily FOR SELECT USING (hb_is_ops_scope());
REVOKE UPDATE, DELETE, TRUNCATE ON sys_usage_daily FROM ${app_role};

-- ---------------------------------------------------------------------
-- 4. 운영자 계정 (전역) — 앱은 만들 수 없다. 첫 운영자는 마이그레이션 전용 실행에서만(OPS_BOOTSTRAP_*)
--    앱은 로그인 기록·MFA 등록·비밀번호 변경에 필요한 열만 고칠 수 있다(열 단위 권한)
-- ---------------------------------------------------------------------
CREATE TABLE sys_operator (
    op_id            bigint       GENERATED ALWAYS AS IDENTITY,
    login_id         varchar(50)  NOT NULL,
    op_nm            varchar(100) NOT NULL,
    pwd_hash         varchar(100) NOT NULL,
    totp_secret_enc  varchar(200),
    totp_enabled_yn  char(1)      NOT NULL DEFAULT 'N',
    fail_cnt         integer      NOT NULL DEFAULT 0,
    locked_until     timestamptz,
    last_login_at    timestamptz,
    last_login_ip    varchar(45),
    use_yn           char(1)      NOT NULL DEFAULT 'Y',
    created_at       timestamptz  NOT NULL DEFAULT now(),
    created_by       varchar(50)  NOT NULL,
    updated_at       timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT pk_sys_operator PRIMARY KEY (op_id),
    CONSTRAINT uq_sys_operator_login UNIQUE (login_id),
    CONSTRAINT ck_sys_operator_login CHECK (login_id ~ '^[a-z0-9][a-z0-9._-]{2,49}$'),
    CONSTRAINT ck_sys_operator_totp CHECK (totp_enabled_yn IN ('Y', 'N') AND (totp_enabled_yn = 'N' OR totp_secret_enc IS NOT NULL)),
    CONSTRAINT ck_sys_operator_use_yn CHECK (use_yn IN ('Y', 'N'))
);
COMMENT ON TABLE sys_operator IS 'SmartHappy 운영자 (회사 사용자와 별개). MFA(TOTP) 필수 — 비밀은 앱 키로 암호화해 저장';
REVOKE ALL ON sys_operator FROM ${app_role};
GRANT SELECT ON sys_operator TO ${app_role};
GRANT UPDATE (pwd_hash, totp_secret_enc, totp_enabled_yn, fail_cnt, locked_until, last_login_at, last_login_ip, updated_at)
    ON sys_operator TO ${app_role};

-- ---------------------------------------------------------------------
-- 5. 운영 기록 (전역, 추가만) — 운영자의 모든 동작: 로그인·회사 생성·잠금 해제·지원 세션 열람
-- ---------------------------------------------------------------------
CREATE TABLE sys_ops_log (
    log_id       bigint       GENERATED ALWAYS AS IDENTITY,
    occurred_at  timestamptz  NOT NULL DEFAULT now(),
    op_login_id  varchar(50),
    action       varchar(30)  NOT NULL,
    target_comp  varchar(20),
    result_cd    varchar(10)  NOT NULL,
    ip           varchar(45),
    detail       varchar(500),
    CONSTRAINT pk_sys_ops_log PRIMARY KEY (log_id),
    CONSTRAINT ck_sys_ops_log_result CHECK (result_cd IN ('OK', 'DENIED', 'FAIL')),
    CONSTRAINT ck_sys_ops_log_action CHECK (action ~ '^[A-Z][A-Z0-9_]{1,29}$')
);
CREATE INDEX ix_sys_ops_log_time ON sys_ops_log (occurred_at);
CREATE INDEX ix_sys_ops_log_comp ON sys_ops_log (target_comp, occurred_at);
COMMENT ON TABLE sys_ops_log IS '운영자 동작 기록 (추가만). 회사 관리자는 자기 회사 대상 기록을 볼 수 있어야 한다(지원 세션 투명성)';
REVOKE ALL ON sys_ops_log FROM ${app_role};
GRANT SELECT, INSERT ON sys_ops_log TO ${app_role};

-- ---------------------------------------------------------------------
-- 6. 고객 승인 지원 세션 (05 D8) — 회사 관리자가 기간·범위·사유를 정해 운영자에게 열람을 허락한다
-- ---------------------------------------------------------------------
CREATE TABLE sys_support_grant (
    grant_id     bigint        GENERATED ALWAYS AS IDENTITY,
    comp_cd      varchar(20)   NOT NULL,
    op_login_id  varchar(50)   NOT NULL,
    scope_cd     varchar(20)   NOT NULL,
    reason       varchar(500)  NOT NULL,
    from_at      timestamptz   NOT NULL,
    to_at        timestamptz   NOT NULL,
    revoked_at   timestamptz,
    approved_by  bigint        NOT NULL,
    row_ver      integer       NOT NULL DEFAULT 0,
    created_at   timestamptz   NOT NULL DEFAULT now(),
    created_by   varchar(50)   NOT NULL,
    updated_at   timestamptz   NOT NULL DEFAULT now(),
    updated_by   varchar(50)   NOT NULL,
    CONSTRAINT pk_sys_support_grant PRIMARY KEY (grant_id),
    CONSTRAINT fk_sys_support_grant_comp FOREIGN KEY (comp_cd) REFERENCES sys_comp (comp_cd),
    CONSTRAINT fk_sys_support_grant_user FOREIGN KEY (approved_by) REFERENCES sys_user (user_id),
    CONSTRAINT ck_sys_support_grant_scope CHECK (scope_cd IN ('LOGS', 'FEEDBACK', 'SETTINGS')),
    CONSTRAINT ck_sys_support_grant_range CHECK (from_at < to_at AND to_at <= from_at + interval '7 days'),
    CONSTRAINT ck_sys_support_grant_reason CHECK (length(btrim(reason)) > 0)
);
CREATE INDEX ix_sys_support_grant_active ON sys_support_grant (comp_cd, op_login_id, to_at) WHERE revoked_at IS NULL;
COMMENT ON TABLE sys_support_grant IS '지원 세션 허락 (최대 7일, 범위 지정, 회사 관리자가 언제든 회수). 운영자 열람은 sys_ops_log 에 남는다';

ALTER TABLE sys_support_grant ENABLE ROW LEVEL SECURITY;
ALTER TABLE sys_support_grant FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON sys_support_grant USING (comp_cd = hb_current_comp()) WITH CHECK (comp_cd = hb_current_comp());
REVOKE DELETE, TRUNCATE ON sys_support_grant FROM ${app_role};
CREATE FUNCTION hb_check_grant_approver_comp() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM sys_user u WHERE u.user_id = NEW.approved_by AND u.comp_cd = NEW.comp_cd) THEN
        RAISE EXCEPTION 'approver % does not belong to company %', NEW.approved_by, NEW.comp_cd
            USING ERRCODE = '23503', CONSTRAINT = 'fk_sys_support_grant_user_comp';
    END IF;
    RETURN NEW;
END
$$;
CREATE TRIGGER tg_sys_support_grant_user BEFORE INSERT OR UPDATE ON sys_support_grant
    FOR EACH ROW EXECUTE FUNCTION hb_check_grant_approver_comp();
CREATE TRIGGER tg_audit AFTER INSERT OR UPDATE OR DELETE ON sys_support_grant
    FOR EACH ROW EXECUTE FUNCTION hb_audit('grant_id', '', '');

-- ---------------------------------------------------------------------
-- 7. 화면 · 보존
-- ---------------------------------------------------------------------
INSERT INTO sys_prog (prog_cd, prog_nm_key, view_path, module_cd, actions, sort_no)
VALUES ('sys-support', 'prog.sysSupport', 'platform/support', 'SYS', ARRAY['SEARCH','SAVE']::varchar(20)[], 910),
       ('sys-usage',   'prog.sysUsage',   'platform/usage',   'SYS', ARRAY['SEARCH']::varchar(20)[], 920);

INSERT INTO sys_retention_class (class_cd, basis, default_days, min_days, purge_allowed_yn, desc_key) VALUES
    ('USAGE', 'KEEP', NULL, NULL, 'N', 'retention.USAGE');
INSERT INTO sys_retention_table (table_nm, class_cd) VALUES
    ('sys_usage_daily', 'USAGE'), ('sys_support_grant', 'SYSTEM');
