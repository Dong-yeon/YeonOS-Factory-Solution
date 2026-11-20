-- =====================================================================
-- V009 거래처관리(표준 참조 화면) · 그리드 상태 · 화면 피드백 (P0 8단계)
--
-- 거래처관리는 이후 모든 업무 화면의 표준 참조본이다: 마스터(조회 그리드 + 폼) + 디테일(담당자 폼 목록),
-- 첨부(sys_file), 서버 엑셀, 변경 이력(sys_audit_log), 피드백·도움말.
-- =====================================================================

-- ---------------------------------------------------------------------
-- 1. 코드 그룹
-- ---------------------------------------------------------------------
INSERT INTO sys_code_grp (grp_cd, grp_nm_key, comp_ext_yn) VALUES
    ('CUST_TYPE',       'code.grp.CUST_TYPE',       'N'),
    ('FEEDBACK_STATUS', 'code.grp.FEEDBACK_STATUS', 'N');

INSERT INTO sys_code (grp_cd, code, code_nm_key, sort_no) VALUES
    ('CUST_TYPE', 'SALES',    'code.CUST_TYPE.SALES', 1),
    ('CUST_TYPE', 'PURCHASE', 'code.CUST_TYPE.PURCHASE', 2),
    ('CUST_TYPE', 'BOTH',     'code.CUST_TYPE.BOTH', 3),
    ('FEEDBACK_STATUS', 'NEW',  'code.FEEDBACK_STATUS.NEW', 1),
    ('FEEDBACK_STATUS', 'SEEN', 'code.FEEDBACK_STATUS.SEEN', 2),
    ('FEEDBACK_STATUS', 'DONE', 'code.FEEDBACK_STATUS.DONE', 3);

-- ---------------------------------------------------------------------
-- 2. 거래처 · 담당자
-- ---------------------------------------------------------------------
CREATE TABLE mst_cust (
    cust_id     bigint        GENERATED ALWAYS AS IDENTITY,
    comp_cd     varchar(20)   NOT NULL,
    cust_cd     varchar(30)   NOT NULL,
    cust_nm     varchar(200)  NOT NULL,
    cust_type   varchar(10)   NOT NULL,
    biz_no      varchar(10),
    ceo_nm      varchar(100),
    tel         varchar(30),
    email       varchar(200),
    addr        varchar(500),
    remark      varchar(1000),
    use_yn      char(1)       NOT NULL DEFAULT 'Y',
    row_ver     integer       NOT NULL DEFAULT 0,
    created_at  timestamptz   NOT NULL DEFAULT now(),
    created_by  varchar(50)   NOT NULL,
    updated_at  timestamptz   NOT NULL DEFAULT now(),
    updated_by  varchar(50)   NOT NULL,
    CONSTRAINT pk_mst_cust PRIMARY KEY (cust_id),
    CONSTRAINT fk_mst_cust_comp FOREIGN KEY (comp_cd) REFERENCES sys_comp (comp_cd),
    CONSTRAINT uq_mst_cust_cd UNIQUE (comp_cd, cust_cd),
    CONSTRAINT ck_mst_cust_cd CHECK (cust_cd ~ '^[A-Z0-9][A-Z0-9_-]{0,29}$'),
    CONSTRAINT ck_mst_cust_nm CHECK (length(btrim(cust_nm)) > 0),
    CONSTRAINT ck_mst_cust_type CHECK (cust_type IN ('SALES', 'PURCHASE', 'BOTH')),
    CONSTRAINT ck_mst_cust_biz_no CHECK (biz_no ~ '^[0-9]{10}$'),
    CONSTRAINT ck_mst_cust_use_yn CHECK (use_yn IN ('Y', 'N'))
);
CREATE INDEX ix_mst_cust_nm ON mst_cust (comp_cd, cust_nm);
COMMENT ON TABLE mst_cust IS '거래처 (P0 8단계 표준 참조 화면). 사업자번호는 숫자 10자리로 저장, 표시는 화면 서식';

CREATE TABLE mst_cust_contact (
    contact_id  bigint        GENERATED ALWAYS AS IDENTITY,
    comp_cd     varchar(20)   NOT NULL,
    cust_id     bigint        NOT NULL,
    contact_nm  varchar(100)  NOT NULL,
    dept_nm     varchar(100),
    tel         varchar(30),
    email       varchar(200),
    main_yn     char(1)       NOT NULL DEFAULT 'N',
    use_yn      char(1)       NOT NULL DEFAULT 'Y',
    row_ver     integer       NOT NULL DEFAULT 0,
    created_at  timestamptz   NOT NULL DEFAULT now(),
    created_by  varchar(50)   NOT NULL,
    updated_at  timestamptz   NOT NULL DEFAULT now(),
    updated_by  varchar(50)   NOT NULL,
    CONSTRAINT pk_mst_cust_contact PRIMARY KEY (contact_id),
    CONSTRAINT fk_mst_cust_contact_comp FOREIGN KEY (comp_cd) REFERENCES sys_comp (comp_cd),
    CONSTRAINT fk_mst_cust_contact_cust FOREIGN KEY (cust_id) REFERENCES mst_cust (cust_id),
    CONSTRAINT ck_mst_cust_contact_nm CHECK (length(btrim(contact_nm)) > 0),
    CONSTRAINT ck_mst_cust_contact_main CHECK (main_yn IN ('Y', 'N')),
    CONSTRAINT ck_mst_cust_contact_use_yn CHECK (use_yn IN ('Y', 'N'))
);
CREATE INDEX ix_mst_cust_contact_cust ON mst_cust_contact (comp_cd, cust_id);
-- 주 담당자는 거래처당 1명 (사용 중인 것만)
CREATE UNIQUE INDEX uq_mst_cust_contact_main ON mst_cust_contact (comp_cd, cust_id) WHERE main_yn = 'Y' AND use_yn = 'Y';
COMMENT ON TABLE mst_cust_contact IS '거래처 담당자 (개인정보: 연락처·메일 — 엑셀 내보내기 대상 아님)';

CREATE FUNCTION hb_check_cust_contact_comp() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM mst_cust c WHERE c.cust_id = NEW.cust_id AND c.comp_cd = NEW.comp_cd) THEN
        RAISE EXCEPTION 'customer % does not belong to company %', NEW.cust_id, NEW.comp_cd
            USING ERRCODE = '23503', CONSTRAINT = 'fk_mst_cust_contact_cust_comp';
    END IF;
    RETURN NEW;
END
$$;
CREATE TRIGGER tg_mst_cust_contact_comp BEFORE INSERT OR UPDATE ON mst_cust_contact
    FOR EACH ROW EXECUTE FUNCTION hb_check_cust_contact_comp();

CREATE TRIGGER tg_audit AFTER INSERT OR UPDATE OR DELETE ON mst_cust
    FOR EACH ROW EXECUTE FUNCTION hb_audit('cust_id', '', '');
CREATE TRIGGER tg_audit AFTER INSERT OR UPDATE OR DELETE ON mst_cust_contact
    FOR EACH ROW EXECUTE FUNCTION hb_audit('contact_id', '', '');

-- ---------------------------------------------------------------------
-- 3. 그리드 상태 (사용자별 열 폭·순서·숨김·정렬 — docs/09 4.6)
-- ---------------------------------------------------------------------
CREATE TABLE sys_grid_state (
    comp_cd     varchar(20)  NOT NULL,
    user_id     bigint       NOT NULL,
    prog_cd     varchar(50)  NOT NULL,
    grid_id     varchar(30)  NOT NULL,
    def_hash    varchar(64)  NOT NULL,
    state       jsonb        NOT NULL,
    row_ver     integer      NOT NULL DEFAULT 0,
    created_at  timestamptz  NOT NULL DEFAULT now(),
    created_by  varchar(50)  NOT NULL,
    updated_at  timestamptz  NOT NULL DEFAULT now(),
    updated_by  varchar(50)  NOT NULL,
    CONSTRAINT pk_sys_grid_state PRIMARY KEY (comp_cd, user_id, prog_cd, grid_id),
    CONSTRAINT fk_sys_grid_state_comp FOREIGN KEY (comp_cd) REFERENCES sys_comp (comp_cd),
    CONSTRAINT fk_sys_grid_state_user FOREIGN KEY (user_id) REFERENCES sys_user (user_id),
    CONSTRAINT fk_sys_grid_state_prog FOREIGN KEY (prog_cd) REFERENCES sys_prog (prog_cd),
    CONSTRAINT ck_sys_grid_state_grid CHECK (grid_id ~ '^[a-z][a-zA-Z0-9]{0,29}$'),
    CONSTRAINT ck_sys_grid_state_hash CHECK (def_hash ~ '^[0-9a-f]{8,64}$'),
    CONSTRAINT ck_sys_grid_state_size CHECK (pg_column_size(state) <= 16384)
);
COMMENT ON TABLE sys_grid_state IS '그리드 화면 설정 (사용자별). 코드 정의에 병합해서 쓴다 — 업무 데이터 아님';

CREATE FUNCTION hb_check_user_comp() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM sys_user u WHERE u.user_id = NEW.user_id AND u.comp_cd = NEW.comp_cd) THEN
        RAISE EXCEPTION 'user % does not belong to company %', NEW.user_id, NEW.comp_cd
            USING ERRCODE = '23503', CONSTRAINT = 'fk_user_comp';
    END IF;
    RETURN NEW;
END
$$;
COMMENT ON FUNCTION hb_check_user_comp() IS '행의 user_id 가 같은 회사 사용자인지 (회사별 테이블 간 FK 는 RLS 를 무시한다)';
CREATE TRIGGER tg_sys_grid_state_comp BEFORE INSERT OR UPDATE ON sys_grid_state
    FOR EACH ROW EXECUTE FUNCTION hb_check_user_comp();

-- ---------------------------------------------------------------------
-- 4. 화면 피드백 ("이 화면 불편해요" — docs/11 Top #10)
-- ---------------------------------------------------------------------
CREATE TABLE sys_feedback (
    feedback_id  bigint        GENERATED ALWAYS AS IDENTITY,
    comp_cd      varchar(20)   NOT NULL,
    user_id      bigint        NOT NULL,
    prog_cd      varchar(50)   NOT NULL,
    content      varchar(2000) NOT NULL,
    status       varchar(10)   NOT NULL DEFAULT 'NEW',
    client_type  varchar(10)   NOT NULL DEFAULT 'WEB',
    row_ver      integer       NOT NULL DEFAULT 0,
    created_at   timestamptz   NOT NULL DEFAULT now(),
    created_by   varchar(50)   NOT NULL,
    updated_at   timestamptz   NOT NULL DEFAULT now(),
    updated_by   varchar(50)   NOT NULL,
    CONSTRAINT pk_sys_feedback PRIMARY KEY (feedback_id),
    CONSTRAINT fk_sys_feedback_comp FOREIGN KEY (comp_cd) REFERENCES sys_comp (comp_cd),
    CONSTRAINT fk_sys_feedback_user FOREIGN KEY (user_id) REFERENCES sys_user (user_id),
    CONSTRAINT fk_sys_feedback_prog FOREIGN KEY (prog_cd) REFERENCES sys_prog (prog_cd),
    CONSTRAINT ck_sys_feedback_content CHECK (length(btrim(content)) > 0),
    CONSTRAINT ck_sys_feedback_status CHECK (status IN ('NEW', 'SEEN', 'DONE')),
    CONSTRAINT ck_sys_feedback_client CHECK (client_type IN ('WEB', 'POP', 'PDA', 'API'))
);
CREATE INDEX ix_sys_feedback_status ON sys_feedback (comp_cd, status, created_at);
COMMENT ON TABLE sys_feedback IS '화면 피드백 — 회사 관리자가 본다. 운영자(SmartHappy) 열람은 지원 세션 원칙(05)을 따른다';
CREATE TRIGGER tg_sys_feedback_comp BEFORE INSERT OR UPDATE ON sys_feedback
    FOR EACH ROW EXECUTE FUNCTION hb_check_user_comp();
CREATE TRIGGER tg_audit AFTER INSERT OR UPDATE OR DELETE ON sys_feedback
    FOR EACH ROW EXECUTE FUNCTION hb_audit('feedback_id', '', '');

-- ---------------------------------------------------------------------
-- 5. RLS · 권한
-- ---------------------------------------------------------------------
ALTER TABLE mst_cust ENABLE ROW LEVEL SECURITY;
ALTER TABLE mst_cust FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON mst_cust USING (comp_cd = hb_current_comp()) WITH CHECK (comp_cd = hb_current_comp());

ALTER TABLE mst_cust_contact ENABLE ROW LEVEL SECURITY;
ALTER TABLE mst_cust_contact FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON mst_cust_contact USING (comp_cd = hb_current_comp()) WITH CHECK (comp_cd = hb_current_comp());

ALTER TABLE sys_grid_state ENABLE ROW LEVEL SECURITY;
ALTER TABLE sys_grid_state FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON sys_grid_state USING (comp_cd = hb_current_comp()) WITH CHECK (comp_cd = hb_current_comp());

ALTER TABLE sys_feedback ENABLE ROW LEVEL SECURITY;
ALTER TABLE sys_feedback FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON sys_feedback USING (comp_cd = hb_current_comp()) WITH CHECK (comp_cd = hb_current_comp());

-- 기준정보는 삭제 대신 사용 안 함(use_yn) — 참조 무결성·감사 추적
REVOKE DELETE, TRUNCATE ON mst_cust, mst_cust_contact, sys_feedback FROM ${app_role};

-- ---------------------------------------------------------------------
-- 6. 화면 · 보존 · 정책
-- ---------------------------------------------------------------------
INSERT INTO sys_prog (prog_cd, prog_nm_key, view_path, module_cd, actions, sort_no)
VALUES ('mst-cust', 'prog.mstCust', 'master/cust-mgt', 'MST', ARRAY['SEARCH','SAVE','DELETE','EXCEL']::varchar(20)[], 100);

INSERT INTO sys_retention_class (class_cd, basis, default_days, min_days, purge_allowed_yn, desc_key) VALUES
    ('USER_SETTING', 'KEEP', NULL, NULL, 'N', 'retention.USER_SETTING');

INSERT INTO sys_retention_table (table_nm, class_cd) VALUES
    ('mst_cust', 'MASTER'), ('mst_cust_contact', 'MASTER'),
    ('sys_grid_state', 'USER_SETTING'), ('sys_feedback', 'SYSTEM');

INSERT INTO sys_policy_def (policy_key, value_type, default_value, allowed_values, min_value, max_value, desc_key) VALUES
    ('GRID_PAGE_SIZE_MAX',               'INT', '500',    NULL, 10, 2000,    'policy.GRID_PAGE_SIZE_MAX'),
    ('GRID_BATCH_MAX_ROWS',              'INT', '1000',   NULL, 1, 10000,    'policy.GRID_BATCH_MAX_ROWS'),
    ('EXCEL_EXPORT_MAX_ROWS',            'INT', '100000', NULL, 100, 1000000, 'policy.EXCEL_EXPORT_MAX_ROWS'),
    ('EXCEL_EXPORT_CONCURRENT_PER_COMP', 'INT', '2',      NULL, 1, 20,       'policy.EXCEL_EXPORT_CONCURRENT_PER_COMP'),
    ('EXCEL_EXPORT_TIMEOUT_SEC',         'INT', '180',    NULL, 10, 1800,    'policy.EXCEL_EXPORT_TIMEOUT_SEC');
