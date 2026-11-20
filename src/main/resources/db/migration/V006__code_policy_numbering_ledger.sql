-- =====================================================================
-- V006 공통코드 · 정책 · 채번 · 원장 지원 (P0 5단계)
--
-- 전역(제품 정의, Flyway 로만 변경, 앱 계정 SELECT 만): sys_code_grp, sys_code, sys_policy_def
-- 회사별(RLS): sys_comp_code, sys_policy, sys_numbering_rule, sys_numbering_seq
-- 원장 지원: hb_forbid_update_delete() — 원장 테이블에 붙이는 수정·삭제 차단 트리거 함수 (01 3.1)
-- =====================================================================

-- ---------------------------------------------------------------------
-- 1. 공통코드
--    시스템 코드(전역) + 회사 코드(회사별). comp_ext_yn = 'Y' 인 그룹만 회사가 코드를 추가할 수 있다.
--    이름: 시스템 코드는 i18n 키, 회사 코드는 회사가 입력한 이름.
-- ---------------------------------------------------------------------
CREATE TABLE sys_code_grp (
    grp_cd       varchar(30)  NOT NULL,
    grp_nm_key   varchar(100) NOT NULL,
    comp_ext_yn  char(1)      NOT NULL DEFAULT 'N',
    use_yn       char(1)      NOT NULL DEFAULT 'Y',
    created_at   timestamptz  NOT NULL DEFAULT now(),
    created_by   varchar(50)  NOT NULL DEFAULT 'FLYWAY',
    CONSTRAINT pk_sys_code_grp PRIMARY KEY (grp_cd),
    CONSTRAINT ck_sys_code_grp_cd CHECK (grp_cd ~ '^[A-Z][A-Z0-9_]{1,29}$'),
    CONSTRAINT ck_sys_code_grp_ext CHECK (comp_ext_yn IN ('Y', 'N')),
    CONSTRAINT ck_sys_code_grp_use_yn CHECK (use_yn IN ('Y', 'N'))
);
COMMENT ON TABLE sys_code_grp IS '공통코드 그룹 (전역). comp_ext_yn=Y 면 회사가 코드를 추가할 수 있다';

CREATE TABLE sys_code (
    grp_cd       varchar(30)  NOT NULL,
    code         varchar(30)  NOT NULL,
    code_nm_key  varchar(100) NOT NULL,
    sort_no      integer      NOT NULL DEFAULT 0,
    use_yn       char(1)      NOT NULL DEFAULT 'Y',
    created_at   timestamptz  NOT NULL DEFAULT now(),
    created_by   varchar(50)  NOT NULL DEFAULT 'FLYWAY',
    CONSTRAINT pk_sys_code PRIMARY KEY (grp_cd, code),
    CONSTRAINT fk_sys_code_grp FOREIGN KEY (grp_cd) REFERENCES sys_code_grp (grp_cd),
    CONSTRAINT ck_sys_code_code CHECK (code ~ '^[A-Z0-9][A-Z0-9_-]{0,29}$'),
    CONSTRAINT ck_sys_code_use_yn CHECK (use_yn IN ('Y', 'N'))
);
COMMENT ON TABLE sys_code IS '시스템 공통코드 (전역, 이름은 i18n 키)';

CREATE TABLE sys_comp_code (
    comp_cd      varchar(20)  NOT NULL,
    grp_cd       varchar(30)  NOT NULL,
    code         varchar(30)  NOT NULL,
    code_nm      varchar(100) NOT NULL,
    sort_no      integer      NOT NULL DEFAULT 0,
    attrs        jsonb        NOT NULL DEFAULT '{}'::jsonb,
    use_yn       char(1)      NOT NULL DEFAULT 'Y',
    row_ver      integer      NOT NULL DEFAULT 0,
    created_at   timestamptz  NOT NULL DEFAULT now(),
    created_by   varchar(50)  NOT NULL,
    updated_at   timestamptz  NOT NULL DEFAULT now(),
    updated_by   varchar(50)  NOT NULL,
    CONSTRAINT pk_sys_comp_code PRIMARY KEY (comp_cd, grp_cd, code),
    CONSTRAINT fk_sys_comp_code_comp FOREIGN KEY (comp_cd) REFERENCES sys_comp (comp_cd),
    CONSTRAINT fk_sys_comp_code_grp FOREIGN KEY (grp_cd) REFERENCES sys_code_grp (grp_cd),
    CONSTRAINT ck_sys_comp_code_code CHECK (code ~ '^[A-Z0-9][A-Z0-9_-]{0,29}$'),
    CONSTRAINT ck_sys_comp_code_nm CHECK (length(btrim(code_nm)) > 0),
    CONSTRAINT ck_sys_comp_code_attrs CHECK (jsonb_typeof(attrs) = 'object'),
    CONSTRAINT ck_sys_comp_code_use_yn CHECK (use_yn IN ('Y', 'N'))
);
COMMENT ON TABLE sys_comp_code IS '회사 공통코드 (comp_ext_yn=Y 그룹만, 시스템 코드와 같은 code 는 쓸 수 없음 — 트리거)';

-- 회사 코드는 확장 허용 그룹에만, 시스템 코드와 겹치지 않게
CREATE FUNCTION hb_check_comp_code() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM sys_code_grp g WHERE g.grp_cd = NEW.grp_cd AND g.comp_ext_yn = 'Y') THEN
        RAISE EXCEPTION 'code group % does not allow company codes', NEW.grp_cd
            USING ERRCODE = '23514', CONSTRAINT = 'ck_sys_comp_code_grp_ext';
    END IF;
    IF EXISTS (SELECT 1 FROM sys_code c WHERE c.grp_cd = NEW.grp_cd AND c.code = NEW.code) THEN
        RAISE EXCEPTION 'code % already exists as a system code in group %', NEW.code, NEW.grp_cd
            USING ERRCODE = '23505', CONSTRAINT = 'uq_sys_comp_code_system';
    END IF;
    RETURN NEW;
END
$$;
CREATE TRIGGER tg_sys_comp_code_check BEFORE INSERT OR UPDATE ON sys_comp_code
    FOR EACH ROW EXECUTE FUNCTION hb_check_comp_code();

-- ---------------------------------------------------------------------
-- 2. 정책 (01 5.3) — 정의는 전역, 값은 회사(+공장)별. 조회 순서: 공장 값 → 회사 값 → 기본값
--    plant_cd = '*' 는 회사 전체 값 (NULL 대신 — 기본키·unique 를 단순하게)
-- ---------------------------------------------------------------------
CREATE TABLE sys_policy_def (
    policy_key      varchar(50)  NOT NULL,
    value_type      varchar(10)  NOT NULL,
    default_value   varchar(2000) NOT NULL,
    allowed_values  varchar(30)[],
    min_value       integer,
    max_value       integer,
    plant_scope_yn  char(1)      NOT NULL DEFAULT 'N',
    desc_key        varchar(100) NOT NULL,
    created_at      timestamptz  NOT NULL DEFAULT now(),
    created_by      varchar(50)  NOT NULL DEFAULT 'FLYWAY',
    CONSTRAINT pk_sys_policy_def PRIMARY KEY (policy_key),
    CONSTRAINT ck_sys_policy_def_key CHECK (policy_key ~ '^[A-Z][A-Z0-9_]{1,49}$'),
    CONSTRAINT ck_sys_policy_def_type CHECK (value_type IN ('BOOL', 'INT', 'ENUM', 'STRING', 'IP_LIST')),
    CONSTRAINT ck_sys_policy_def_plant CHECK (plant_scope_yn IN ('Y', 'N'))
);
COMMENT ON TABLE sys_policy_def IS '정책 정의 (전역): 형식·기본값·허용값·범위';

CREATE TABLE sys_policy (
    comp_cd      varchar(20)   NOT NULL,
    plant_cd     varchar(20)   NOT NULL DEFAULT '*',
    policy_key   varchar(50)   NOT NULL,
    policy_value varchar(2000) NOT NULL,
    row_ver      integer       NOT NULL DEFAULT 0,
    created_at   timestamptz   NOT NULL DEFAULT now(),
    created_by   varchar(50)   NOT NULL,
    updated_at   timestamptz   NOT NULL DEFAULT now(),
    updated_by   varchar(50)   NOT NULL,
    CONSTRAINT pk_sys_policy PRIMARY KEY (comp_cd, plant_cd, policy_key),
    CONSTRAINT fk_sys_policy_comp FOREIGN KEY (comp_cd) REFERENCES sys_comp (comp_cd),
    CONSTRAINT fk_sys_policy_def FOREIGN KEY (policy_key) REFERENCES sys_policy_def (policy_key)
);
COMMENT ON TABLE sys_policy IS '회사·공장별 정책 값 (plant_cd = * 는 회사 전체). 값 검증은 PolicyService';

-- ---------------------------------------------------------------------
-- 3. 채번 (01 5.2) — 규칙은 회사별, 시퀀스는 규칙 × 기간
--    pattern 토큰: {yyyy} {yy} {MM} {dd} {plant} {seq:N}. 그 밖의 글자는 영문 대문자·숫자·- _ / . 만
-- ---------------------------------------------------------------------
CREATE TABLE sys_numbering_rule (
    comp_cd       varchar(20)  NOT NULL,
    rule_cd       varchar(30)  NOT NULL,
    rule_nm       varchar(100) NOT NULL,
    pattern       varchar(100) NOT NULL,
    reset_cycle   varchar(10)  NOT NULL DEFAULT 'NONE',
    use_yn        char(1)      NOT NULL DEFAULT 'Y',
    row_ver       integer      NOT NULL DEFAULT 0,
    created_at    timestamptz  NOT NULL DEFAULT now(),
    created_by    varchar(50)  NOT NULL,
    updated_at    timestamptz  NOT NULL DEFAULT now(),
    updated_by    varchar(50)  NOT NULL,
    CONSTRAINT pk_sys_numbering_rule PRIMARY KEY (comp_cd, rule_cd),
    CONSTRAINT fk_sys_numbering_rule_comp FOREIGN KEY (comp_cd) REFERENCES sys_comp (comp_cd),
    CONSTRAINT ck_sys_numbering_rule_cd CHECK (rule_cd ~ '^[A-Z][A-Z0-9_]{1,29}$'),
    CONSTRAINT ck_sys_numbering_rule_reset CHECK (reset_cycle IN ('NONE', 'YEAR', 'MONTH', 'DAY')),
    CONSTRAINT ck_sys_numbering_rule_pattern CHECK (pattern ~ '\{seq:[1-9][0-9]?\}'),
    CONSTRAINT ck_sys_numbering_rule_use_yn CHECK (use_yn IN ('Y', 'N'))
);
COMMENT ON TABLE sys_numbering_rule IS '채번 규칙 (회사별). 예: WO-{yyMM}-{seq:4}, reset MONTH';

CREATE TABLE sys_numbering_seq (
    comp_cd     varchar(20) NOT NULL,
    rule_cd     varchar(30) NOT NULL,
    period_key  varchar(20) NOT NULL,
    last_no     bigint      NOT NULL,
    updated_at  timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT pk_sys_numbering_seq PRIMARY KEY (comp_cd, rule_cd, period_key),
    CONSTRAINT fk_sys_numbering_seq_rule FOREIGN KEY (comp_cd, rule_cd) REFERENCES sys_numbering_rule (comp_cd, rule_cd),
    CONSTRAINT ck_sys_numbering_seq_no CHECK (last_no > 0)
);
COMMENT ON TABLE sys_numbering_seq IS '채번 시퀀스 (규칙 × 기간). 업무 트랜잭션 안에서 증가 → 롤백되면 번호도 되돌아감(결번 없음)';

-- ---------------------------------------------------------------------
-- 4. RLS
-- ---------------------------------------------------------------------
ALTER TABLE sys_comp_code ENABLE ROW LEVEL SECURITY;
ALTER TABLE sys_comp_code FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON sys_comp_code
    USING (comp_cd = hb_current_comp()) WITH CHECK (comp_cd = hb_current_comp());

ALTER TABLE sys_policy ENABLE ROW LEVEL SECURITY;
ALTER TABLE sys_policy FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON sys_policy
    USING (comp_cd = hb_current_comp()) WITH CHECK (comp_cd = hb_current_comp());

ALTER TABLE sys_numbering_rule ENABLE ROW LEVEL SECURITY;
ALTER TABLE sys_numbering_rule FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON sys_numbering_rule
    USING (comp_cd = hb_current_comp()) WITH CHECK (comp_cd = hb_current_comp());

ALTER TABLE sys_numbering_seq ENABLE ROW LEVEL SECURITY;
ALTER TABLE sys_numbering_seq FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON sys_numbering_seq
    USING (comp_cd = hb_current_comp()) WITH CHECK (comp_cd = hb_current_comp());

-- 전역 테이블: 앱 계정 쓰기 권한 회수
REVOKE INSERT, UPDATE, DELETE, TRUNCATE ON sys_code_grp, sys_code, sys_policy_def FROM ${app_role};

-- ---------------------------------------------------------------------
-- 5. 원장 지원 (01 3.1) — 원장 테이블에 BEFORE UPDATE OR DELETE 트리거로 붙인다.
--    정정은 역분개 행(reversal_of_id)만. 카탈로그 검사: reversal_of_id 컬럼이 있는 테이블은 이 트리거 필수.
-- ---------------------------------------------------------------------
CREATE FUNCTION hb_forbid_update_delete() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'ledger table % is append-only (use a reversal row)', TG_TABLE_NAME
        USING ERRCODE = '23514', CONSTRAINT = 'ck_ledger_append_only';
END
$$;
COMMENT ON FUNCTION hb_forbid_update_delete() IS '원장 테이블 수정·삭제 차단 — 정정은 역분개 행으로';

-- ---------------------------------------------------------------------
-- 6. 기본 데이터: 코드 그룹·코드, 정책 정의
-- ---------------------------------------------------------------------
INSERT INTO sys_code_grp (grp_cd, grp_nm_key, comp_ext_yn) VALUES
    ('LOCALE',          'code.grp.LOCALE',          'N'),
    ('COMP_STATUS',     'code.grp.COMP_STATUS',     'N'),
    ('NUMBERING_RESET', 'code.grp.NUMBERING_RESET', 'N'),
    ('UNIT',            'code.grp.UNIT',            'Y');

INSERT INTO sys_code (grp_cd, code, code_nm_key, sort_no) VALUES
    ('LOCALE', 'KO', 'code.LOCALE.KO', 1),
    ('LOCALE', 'EN', 'code.LOCALE.EN', 2),
    ('COMP_STATUS', 'ACTIVE',    'code.COMP_STATUS.ACTIVE', 1),
    ('COMP_STATUS', 'SUSPENDED', 'code.COMP_STATUS.SUSPENDED', 2),
    ('COMP_STATUS', 'CLOSED',    'code.COMP_STATUS.CLOSED', 3),
    ('NUMBERING_RESET', 'NONE',  'code.NUMBERING_RESET.NONE', 1),
    ('NUMBERING_RESET', 'YEAR',  'code.NUMBERING_RESET.YEAR', 2),
    ('NUMBERING_RESET', 'MONTH', 'code.NUMBERING_RESET.MONTH', 3),
    ('NUMBERING_RESET', 'DAY',   'code.NUMBERING_RESET.DAY', 4),
    ('UNIT', 'EA', 'code.UNIT.EA', 1),
    ('UNIT', 'KG', 'code.UNIT.KG', 2),
    ('UNIT', 'M',  'code.UNIT.M', 3);

INSERT INTO sys_policy_def (policy_key, value_type, default_value, allowed_values, min_value, max_value, desc_key) VALUES
    -- 비밀번호 (05 D9 — NIST SP 800-63B-4 방향: 길이·차단 목록, 조합·주기 변경 기본 끔)
    ('PWD_MIN_LENGTH',          'INT',     '12',   NULL, 8, 64, 'policy.PWD_MIN_LENGTH'),
    ('PWD_COMPLEXITY_REQUIRED', 'BOOL',    'N',    NULL, NULL, NULL, 'policy.PWD_COMPLEXITY_REQUIRED'),
    ('PWD_EXPIRE_DAYS',         'INT',     '0',    NULL, 0, 365, 'policy.PWD_EXPIRE_DAYS'),
    ('PWD_BLOCKLIST_ENABLED',   'BOOL',    'Y',    NULL, NULL, NULL, 'policy.PWD_BLOCKLIST_ENABLED'),
    -- 로그인 (05 G14 — 공장 NAT): 등록 IP 는 IP 단독 시도 제한 완화
    ('LOGIN_TRUSTED_IPS',       'IP_LIST', '',     NULL, NULL, NULL, 'policy.LOGIN_TRUSTED_IPS'),
    ('MFA_REQUIRED_FOR_ADMIN',  'BOOL',    'N',    NULL, NULL, NULL, 'policy.MFA_REQUIRED_FOR_ADMIN'),
    -- AI (03 6.1-5): 정의만, 기능은 P5. 학습 동의 기본 없음
    ('AI_ENABLED',              'BOOL',    'N',    NULL, NULL, NULL, 'policy.AI_ENABLED'),
    ('AI_EXTERNAL_API_ALLOWED', 'BOOL',    'N',    NULL, NULL, NULL, 'policy.AI_EXTERNAL_API_ALLOWED'),
    ('AI_PROVIDER',             'ENUM',    'NONE', ARRAY['NONE','EXTERNAL','LOCAL'], NULL, NULL, 'policy.AI_PROVIDER'),
    ('AI_TRAINING_CONSENT',     'STRING',  '',     NULL, NULL, NULL, 'policy.AI_TRAINING_CONSENT'),
    ('AI_PII_MASKING',          'BOOL',    'Y',    NULL, NULL, NULL, 'policy.AI_PII_MASKING');
