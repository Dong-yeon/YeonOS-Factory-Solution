-- =====================================================================
-- V001 플랫폼 기반: 앱 계정 권한, 테넌트 함수, 회사·공장
-- 규칙 (docs/01_HappyBoot_P0_플랫폼_설계.md 3장)
--   - 소문자 snake_case, 업무 행은 comp_cd 필수, unique/index 첫 컬럼 comp_cd
--   - 감사 컬럼 created_at/by, updated_at/by (앱이 자동 주입), 낙관적 락 row_ver
--   - use_yn 은 NOT NULL + CHECK, 상태값은 영문 코드
--   - 회사별 테이블은 RLS 로 격리: comp_cd = hb_current_comp()
-- 실행 계정: 소유자(happyboot_owner). ${app_role} 은 Flyway placeholder (앱 접속 계정)
-- =====================================================================

-- ---------------------------------------------------------------------
-- 1. 앱 계정 기본 권한: 이후 소유자가 만드는 테이블·시퀀스·함수에 자동 적용
--    (DDL 권한은 주지 않는다 → 앱은 테이블을 만들거나 지우거나 RLS 를 끌 수 없다)
-- ---------------------------------------------------------------------
GRANT USAGE ON SCHEMA public TO ${app_role};

ALTER DEFAULT PRIVILEGES IN SCHEMA public
    GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO ${app_role};
ALTER DEFAULT PRIVILEGES IN SCHEMA public
    GRANT USAGE, SELECT ON SEQUENCES TO ${app_role};
ALTER DEFAULT PRIVILEGES IN SCHEMA public
    GRANT EXECUTE ON FUNCTIONS TO ${app_role};

-- ---------------------------------------------------------------------
-- 2. 현재 회사 함수
--    앱은 트랜잭션 시작 시 select set_config('app.comp_cd', :compCd, true) 를 실행한다 (트랜잭션 로컬).
--    설정 전이거나 트랜잭션이 끝나 '' 로 돌아온 커넥션에서는 NULL → 어떤 행도 보이지 않는다 (fail-closed).
-- ---------------------------------------------------------------------
CREATE FUNCTION hb_current_comp() RETURNS varchar
    LANGUAGE sql STABLE PARALLEL SAFE
AS $$
    SELECT nullif(current_setting('app.comp_cd', true), '')
$$;

COMMENT ON FUNCTION hb_current_comp() IS '현재 트랜잭션의 회사코드 (RLS 기준). 미설정이면 NULL';

-- ---------------------------------------------------------------------
-- 3. 회사 (테넌트)
-- ---------------------------------------------------------------------
CREATE TABLE sys_comp (
    comp_cd      varchar(20)  NOT NULL,
    comp_nm      varchar(100) NOT NULL,
    comp_en_nm   varchar(100),
    biz_no       varchar(20),
    ceo_nm       varchar(50),
    tel_no       varchar(30),
    addr         varchar(300),
    time_zone    varchar(50)  NOT NULL DEFAULT 'Asia/Seoul',
    locale_cd    varchar(10)  NOT NULL DEFAULT 'ko',
    status_cd    varchar(20)  NOT NULL DEFAULT 'ACTIVE',
    use_yn       char(1)      NOT NULL DEFAULT 'Y',
    row_ver      integer      NOT NULL DEFAULT 0,
    created_at   timestamptz  NOT NULL DEFAULT now(),
    created_by   varchar(50)  NOT NULL,
    updated_at   timestamptz  NOT NULL DEFAULT now(),
    updated_by   varchar(50)  NOT NULL,
    CONSTRAINT pk_sys_comp PRIMARY KEY (comp_cd),
    -- 회사코드는 URL·서브도메인·파일 경로에 쓰이므로 대문자 영숫자/하이픈/언더스코어만 (앞뒤 공백·한글 차단)
    CONSTRAINT ck_sys_comp_cd CHECK (comp_cd ~ '^[A-Z0-9][A-Z0-9_-]{1,19}$'),
    CONSTRAINT ck_sys_comp_nm CHECK (length(btrim(comp_nm)) > 0),
    CONSTRAINT ck_sys_comp_status CHECK (status_cd IN ('ACTIVE', 'SUSPENDED', 'CLOSED')),
    CONSTRAINT ck_sys_comp_use_yn CHECK (use_yn IN ('Y', 'N'))
);

COMMENT ON TABLE  sys_comp IS '회사(테넌트)';
COMMENT ON COLUMN sys_comp.comp_cd   IS '회사코드 (대문자 영숫자, 2~20자)';
COMMENT ON COLUMN sys_comp.status_cd IS 'ACTIVE 사용 / SUSPENDED 일시정지(구독만료 등) / CLOSED 해지';
COMMENT ON COLUMN sys_comp.row_ver   IS '낙관적 락 버전 (수정 시 +1, 불일치면 409)';

-- ---------------------------------------------------------------------
-- 4. 공장
-- ---------------------------------------------------------------------
CREATE TABLE sys_plant (
    comp_cd      varchar(20)  NOT NULL,
    plant_cd     varchar(20)  NOT NULL,
    plant_nm     varchar(100) NOT NULL,
    addr         varchar(300),
    time_zone    varchar(50),
    sort_no      integer      NOT NULL DEFAULT 0,
    use_yn       char(1)      NOT NULL DEFAULT 'Y',
    row_ver      integer      NOT NULL DEFAULT 0,
    created_at   timestamptz  NOT NULL DEFAULT now(),
    created_by   varchar(50)  NOT NULL,
    updated_at   timestamptz  NOT NULL DEFAULT now(),
    updated_by   varchar(50)  NOT NULL,
    CONSTRAINT pk_sys_plant PRIMARY KEY (comp_cd, plant_cd),
    CONSTRAINT fk_sys_plant_comp FOREIGN KEY (comp_cd) REFERENCES sys_comp (comp_cd),
    CONSTRAINT ck_sys_plant_cd CHECK (plant_cd ~ '^[A-Z0-9][A-Z0-9_-]{0,19}$'),
    CONSTRAINT ck_sys_plant_nm CHECK (length(btrim(plant_nm)) > 0),
    CONSTRAINT ck_sys_plant_use_yn CHECK (use_yn IN ('Y', 'N'))
);

COMMENT ON TABLE  sys_plant IS '공장 (회사 하위)';
COMMENT ON COLUMN sys_plant.time_zone IS '공장 시간대 (NULL 이면 회사 시간대)';

-- ---------------------------------------------------------------------
-- 5. RLS (회사별 행 격리)
--    FORCE: 소유자 계정으로 접속해도 정책 적용 (실수 방지). 마이그레이션의 데이터 작업은 set_config 후 수행.
--    sys_comp 는 "자기 회사 행"만 보인다. 회사 생성(운영자 콘솔)도 대상 comp_cd 로 set_config 후 insert 한다.
-- ---------------------------------------------------------------------
ALTER TABLE sys_comp  ENABLE ROW LEVEL SECURITY;
ALTER TABLE sys_comp  FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON sys_comp
    USING (comp_cd = hb_current_comp())
    WITH CHECK (comp_cd = hb_current_comp());

ALTER TABLE sys_plant ENABLE ROW LEVEL SECURITY;
ALTER TABLE sys_plant FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON sys_plant
    USING (comp_cd = hb_current_comp())
    WITH CHECK (comp_cd = hb_current_comp());
