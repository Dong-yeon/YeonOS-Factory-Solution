-- =====================================================================
-- V003 사용자 (로그인 계정)
-- - 로그인 ID 는 회사 안에서만 유일, 대소문자 무시 (Admin = admin)
-- - 비밀번호는 Spring Security DelegatingPasswordEncoder 형식 ({bcrypt}…)
-- - 연속 실패 시 잠금(lock_yn), 퇴사·사용중지는 use_yn = 'N'
-- - 회사별 테이블 → RLS (tenant_isolation)
-- =====================================================================

CREATE TABLE sys_user (
    user_id            bigint       GENERATED ALWAYS AS IDENTITY,
    comp_cd            varchar(20)  NOT NULL,
    login_id           varchar(50)  NOT NULL,
    user_nm            varchar(50)  NOT NULL,
    pwd_hash           varchar(200) NOT NULL,
    email              varchar(200),
    locale_cd          varchar(10)  NOT NULL DEFAULT 'ko',
    time_zone          varchar(50),
    lock_yn            char(1)      NOT NULL DEFAULT 'N',
    login_fail_cnt     integer      NOT NULL DEFAULT 0,
    pwd_change_req_yn  char(1)      NOT NULL DEFAULT 'N',
    pwd_changed_at     timestamptz,
    last_login_at      timestamptz,
    use_yn             char(1)      NOT NULL DEFAULT 'Y',
    row_ver            integer      NOT NULL DEFAULT 0,
    created_at         timestamptz  NOT NULL DEFAULT now(),
    created_by         varchar(50)  NOT NULL,
    updated_at         timestamptz  NOT NULL DEFAULT now(),
    updated_by         varchar(50)  NOT NULL,
    CONSTRAINT pk_sys_user PRIMARY KEY (user_id),
    CONSTRAINT fk_sys_user_comp FOREIGN KEY (comp_cd) REFERENCES sys_comp (comp_cd),
    -- 영문·숫자로 시작, 영문·숫자·. _ - @ 만 (공백·한글·따옴표 차단), 2~50자
    CONSTRAINT ck_sys_user_login_id CHECK (login_id ~ '^[A-Za-z0-9][A-Za-z0-9._@-]{1,49}$'),
    CONSTRAINT ck_sys_user_nm CHECK (length(btrim(user_nm)) > 0),
    CONSTRAINT ck_sys_user_pwd_hash CHECK (pwd_hash ~ '^\{[a-z0-9-]+\}'),
    CONSTRAINT ck_sys_user_fail_cnt CHECK (login_fail_cnt >= 0),
    CONSTRAINT ck_sys_user_lock_yn CHECK (lock_yn IN ('Y', 'N')),
    CONSTRAINT ck_sys_user_pwd_change_req_yn CHECK (pwd_change_req_yn IN ('Y', 'N')),
    CONSTRAINT ck_sys_user_use_yn CHECK (use_yn IN ('Y', 'N'))
);

CREATE UNIQUE INDEX uq_sys_user_login_id ON sys_user (comp_cd, lower(login_id));

COMMENT ON TABLE  sys_user IS '사용자 (로그인 계정)';
COMMENT ON COLUMN sys_user.login_id IS '로그인 ID (회사 안에서 대소문자 무시 유일)';
COMMENT ON COLUMN sys_user.pwd_hash IS '비밀번호 해시 ({bcrypt}… DelegatingPasswordEncoder 형식). 원문 저장 금지';
COMMENT ON COLUMN sys_user.lock_yn IS '로그인 연속 실패 잠금';
COMMENT ON COLUMN sys_user.login_fail_cnt IS '연속 로그인 실패 횟수 (성공 시 0)';
COMMENT ON COLUMN sys_user.pwd_change_req_yn IS '다음 로그인 때 비밀번호 변경 필요 (초기 계정 등)';

ALTER TABLE sys_user ENABLE ROW LEVEL SECURITY;
ALTER TABLE sys_user FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON sys_user
    USING (comp_cd = hb_current_comp())
    WITH CHECK (comp_cd = hb_current_comp());
