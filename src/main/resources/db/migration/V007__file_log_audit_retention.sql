-- =====================================================================
-- V007 첨부 · 접속/보안 로그 · 변경 감사 · 보존 · 로그 다이제스트 (P0 6단계)
--
-- 로그 3종(sys_access_log, sys_audit_log, sys_log_digest)은 **추가만**: 앱 계정 UPDATE/DELETE 회수.
-- 정리는 소유자 권한 프로시저 hb_purge_logs() 로만 (앱 계정 실행 권한 없음, docs/05 D6·01 7.1).
-- 변경 감사는 DB 트리거 hb_audit() — 앱 코드가 깜빡해도, 직접 SQL 로 바꿔도 남는다.
-- =====================================================================

-- ---------------------------------------------------------------------
-- 1. 첨부 메타 (실제 파일은 FileStorage: 로컬 디스크 / S3)
-- ---------------------------------------------------------------------
CREATE TABLE sys_file (
    file_id       bigint       GENERATED ALWAYS AS IDENTITY,
    comp_cd       varchar(20)  NOT NULL,
    target_type   varchar(50)  NOT NULL,
    target_id     varchar(64)  NOT NULL,
    orig_name     varchar(255) NOT NULL,
    ext           varchar(20)  NOT NULL,
    content_type  varchar(150) NOT NULL,
    size_bytes    bigint       NOT NULL,
    sha256        char(64)     NOT NULL,
    storage_key   varchar(300) NOT NULL,
    use_yn        char(1)      NOT NULL DEFAULT 'Y',
    row_ver       integer      NOT NULL DEFAULT 0,
    created_at    timestamptz  NOT NULL DEFAULT now(),
    created_by    varchar(50)  NOT NULL,
    updated_at    timestamptz  NOT NULL DEFAULT now(),
    updated_by    varchar(50)  NOT NULL,
    CONSTRAINT pk_sys_file PRIMARY KEY (file_id),
    CONSTRAINT fk_sys_file_comp FOREIGN KEY (comp_cd) REFERENCES sys_comp (comp_cd),
    CONSTRAINT fk_sys_file_prog FOREIGN KEY (target_type) REFERENCES sys_prog (prog_cd),
    CONSTRAINT uq_sys_file_storage UNIQUE (storage_key),
    CONSTRAINT ck_sys_file_target_type CHECK (target_type ~ '^[a-z0-9][a-z0-9-]{1,49}$'),
    CONSTRAINT ck_sys_file_target_id CHECK (target_id ~ '^[A-Za-z0-9_.:-]{1,64}$'),
    CONSTRAINT ck_sys_file_size CHECK (size_bytes > 0),
    CONSTRAINT ck_sys_file_sha CHECK (sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_sys_file_key CHECK (storage_key ~ '^[A-Z0-9_-]+/[0-9]{4}/[0-9]{2}/[0-9a-f-]{36}$'),
    CONSTRAINT ck_sys_file_use_yn CHECK (use_yn IN ('Y', 'N'))
);
CREATE INDEX ix_sys_file_target ON sys_file (comp_cd, target_type, target_id);
COMMENT ON TABLE sys_file IS '첨부 메타 (target_type = 화면 prog_cd, target_id = 업무 행 키). 삭제는 use_yn=N';
COMMENT ON COLUMN sys_file.storage_key IS '{comp_cd}/{yyyy}/{MM}/{uuid} — 원본 파일명을 경로에 쓰지 않는다';

-- ---------------------------------------------------------------------
-- 2. 접속·보안 로그 (정부지원사업 활용 로그 원천 + 탐지, 01 7.3)
-- ---------------------------------------------------------------------
CREATE TABLE sys_access_log (
    log_id       bigint       GENERATED ALWAYS AS IDENTITY,
    comp_cd      varchar(20)  NOT NULL,
    occurred_at  timestamptz  NOT NULL DEFAULT now(),
    user_id      bigint,
    login_id     varchar(50),
    event_cd     varchar(30)  NOT NULL,
    prog_cd      varchar(50),
    action_cd    varchar(20),
    result_cd    varchar(10)  NOT NULL,
    severity     varchar(10)  NOT NULL DEFAULT 'INFO',
    client_type  varchar(10)  NOT NULL DEFAULT 'WEB',
    ip           varchar(45),
    detail       varchar(500),
    CONSTRAINT pk_sys_access_log PRIMARY KEY (log_id),
    CONSTRAINT ck_sys_access_log_result CHECK (result_cd IN ('OK', 'DENIED', 'FAIL')),
    CONSTRAINT ck_sys_access_log_severity CHECK (severity IN ('INFO', 'WARN', 'ALERT')),
    CONSTRAINT ck_sys_access_log_client CHECK (client_type IN ('WEB', 'POP', 'PDA', 'API', 'SYSTEM')),
    CONSTRAINT ck_sys_access_log_event CHECK (event_cd ~ '^[A-Z][A-Z0-9_]{1,29}$')
);
CREATE INDEX ix_sys_access_log_time ON sys_access_log (comp_cd, occurred_at);
CREATE INDEX ix_sys_access_log_alert ON sys_access_log (comp_cd, severity, occurred_at) WHERE severity <> 'INFO';
COMMENT ON TABLE sys_access_log IS '접속·보안 로그 (추가만). 비밀번호·토큰·세션 ID 는 남기지 않는다';

-- ---------------------------------------------------------------------
-- 3. 변경 감사 (트리거가 기록)
-- ---------------------------------------------------------------------
CREATE TABLE sys_audit_log (
    audit_id     bigint       GENERATED ALWAYS AS IDENTITY,
    comp_cd      varchar(20)  NOT NULL,
    occurred_at  timestamptz  NOT NULL DEFAULT now(),
    table_nm     varchar(63)  NOT NULL,
    row_key      varchar(200) NOT NULL,
    action       varchar(10)  NOT NULL,
    before_data  jsonb,
    after_data   jsonb,
    actor        varchar(80)  NOT NULL,
    CONSTRAINT pk_sys_audit_log PRIMARY KEY (audit_id),
    CONSTRAINT ck_sys_audit_log_action CHECK (action IN ('INSERT', 'UPDATE', 'DELETE'))
);
CREATE INDEX ix_sys_audit_log_row ON sys_audit_log (comp_cd, table_nm, row_key, occurred_at);
CREATE INDEX ix_sys_audit_log_time ON sys_audit_log (comp_cd, occurred_at);
COMMENT ON TABLE sys_audit_log IS '변경 감사 (추가만, 트리거 hb_audit 가 기록). 비밀번호 해시는 값 대신 (changed)';

-- 행 변경을 감사로그로. 인자: (키 컬럼들, 무시할 컬럼들, 가릴 컬럼들) — 쉼표 구분
-- 무시 컬럼만 바뀐 UPDATE 는 기록하지 않는다(로그인 기록·row_ver 등 잡음). 가릴 컬럼은 값 대신 "(changed)".
-- actor = 트랜잭션의 app.actor (TenantAwareTransactionManager 가 설정, 없으면 UNKNOWN — 직접 SQL 도 드러남)
CREATE FUNCTION hb_audit() RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    key_cols    text[] := string_to_array(coalesce(TG_ARGV[0], ''), ',');
    ignore_cols text[] := string_to_array(coalesce(TG_ARGV[1], ''), ',') || ARRAY['updated_at', 'updated_by', 'row_ver'];
    mask_cols   text[] := string_to_array(coalesce(TG_ARGV[2], ''), ',');
    old_full jsonb;
    new_full jsonb;
    old_j    jsonb;
    new_j    jsonb;
    c        text;
    row_key  text;
    src      jsonb;
BEGIN
    IF TG_OP <> 'INSERT' THEN old_full := to_jsonb(OLD); END IF;
    IF TG_OP <> 'DELETE' THEN new_full := to_jsonb(NEW); END IF;
    old_j := old_full;
    new_j := new_full;
    FOREACH c IN ARRAY ignore_cols LOOP
        old_j := old_j - c;
        new_j := new_j - c;
    END LOOP;
    FOREACH c IN ARRAY mask_cols LOOP
        IF (old_full -> c) IS DISTINCT FROM (new_full -> c) THEN
            IF new_j IS NOT NULL THEN new_j := (new_j - c) || jsonb_build_object(c, '(changed)'); END IF;
            IF old_j IS NOT NULL THEN old_j := (old_j - c) || jsonb_build_object(c, '(masked)'); END IF;
        ELSE
            old_j := old_j - c;
            new_j := new_j - c;
        END IF;
    END LOOP;
    IF TG_OP = 'UPDATE' AND old_j = new_j THEN
        RETURN NULL;
    END IF;
    src := coalesce(new_full, old_full);
    SELECT string_agg(coalesce(src ->> k, ''), '|') INTO row_key FROM unnest(key_cols) AS k;
    INSERT INTO sys_audit_log (comp_cd, table_nm, row_key, action, before_data, after_data, actor)
    VALUES (src ->> 'comp_cd', TG_TABLE_NAME, left(coalesce(row_key, ''), 200), TG_OP, old_j, new_j,
            left(coalesce(nullif(current_setting('app.actor', true), ''), 'UNKNOWN'), 80));
    RETURN NULL;
END
$$;
COMMENT ON FUNCTION hb_audit() IS '변경 감사 트리거: AFTER INSERT/UPDATE/DELETE FOR EACH ROW EXECUTE FUNCTION hb_audit(키, 무시, 가림)';

CREATE TRIGGER tg_audit AFTER INSERT OR UPDATE OR DELETE ON sys_comp
    FOR EACH ROW EXECUTE FUNCTION hb_audit('comp_cd', '', '');
CREATE TRIGGER tg_audit AFTER INSERT OR UPDATE OR DELETE ON sys_plant
    FOR EACH ROW EXECUTE FUNCTION hb_audit('comp_cd,plant_cd', '', '');
CREATE TRIGGER tg_audit AFTER INSERT OR UPDATE OR DELETE ON sys_user
    FOR EACH ROW EXECUTE FUNCTION hb_audit('user_id', 'login_fail_cnt,last_login_at,lock_yn,locked_until', 'pwd_hash');
CREATE TRIGGER tg_audit AFTER INSERT OR UPDATE OR DELETE ON sys_auth_group
    FOR EACH ROW EXECUTE FUNCTION hb_audit('comp_cd,auth_grp_cd', '', '');
CREATE TRIGGER tg_audit AFTER INSERT OR UPDATE OR DELETE ON sys_user_auth
    FOR EACH ROW EXECUTE FUNCTION hb_audit('comp_cd,user_id,auth_grp_cd', '', '');
CREATE TRIGGER tg_audit AFTER INSERT OR UPDATE OR DELETE ON sys_auth_prog
    FOR EACH ROW EXECUTE FUNCTION hb_audit('comp_cd,auth_grp_cd,prog_cd,action_cd', '', '');
CREATE TRIGGER tg_audit AFTER INSERT OR UPDATE OR DELETE ON sys_menu
    FOR EACH ROW EXECUTE FUNCTION hb_audit('menu_id', '', '');
CREATE TRIGGER tg_audit AFTER INSERT OR UPDATE OR DELETE ON sys_comp_code
    FOR EACH ROW EXECUTE FUNCTION hb_audit('comp_cd,grp_cd,code', '', '');
CREATE TRIGGER tg_audit AFTER INSERT OR UPDATE OR DELETE ON sys_policy
    FOR EACH ROW EXECUTE FUNCTION hb_audit('comp_cd,plant_cd,policy_key', '', '');
CREATE TRIGGER tg_audit AFTER INSERT OR UPDATE OR DELETE ON sys_numbering_rule
    FOR EACH ROW EXECUTE FUNCTION hb_audit('comp_cd,rule_cd', '', '');
CREATE TRIGGER tg_audit AFTER INSERT OR UPDATE OR DELETE ON sys_file
    FOR EACH ROW EXECUTE FUNCTION hb_audit('file_id', '', '');

-- ---------------------------------------------------------------------
-- 4. 보존 (01 4장·7.3, docs/05 4.6) — 클래스·테이블 매핑은 전역, 회사별 기간·법적 보류는 회사별
-- ---------------------------------------------------------------------
CREATE TABLE sys_retention_class (
    class_cd          varchar(30)  NOT NULL,
    basis             varchar(20)  NOT NULL,
    default_days      integer,
    min_days          integer,
    purge_allowed_yn  char(1)      NOT NULL,
    desc_key          varchar(100) NOT NULL,
    CONSTRAINT pk_sys_retention_class PRIMARY KEY (class_cd),
    CONSTRAINT ck_sys_retention_class_basis CHECK (basis IN ('OCCURRED_AT', 'ITEM_EOL', 'KEEP')),
    CONSTRAINT ck_sys_retention_class_purge CHECK (purge_allowed_yn IN ('Y', 'N')),
    CONSTRAINT ck_sys_retention_class_days CHECK (purge_allowed_yn = 'N' OR (default_days >= min_days AND min_days > 0))
);
COMMENT ON TABLE sys_retention_class IS '보존 클래스 (전역). purge_allowed_yn=Y 인 클래스만 정리 대상';

CREATE TABLE sys_retention_table (
    table_nm  varchar(63) NOT NULL,
    class_cd  varchar(30) NOT NULL,
    CONSTRAINT pk_sys_retention_table PRIMARY KEY (table_nm),
    CONSTRAINT fk_sys_retention_table_class FOREIGN KEY (class_cd) REFERENCES sys_retention_class (class_cd)
);
COMMENT ON TABLE sys_retention_table IS '회사별 테이블 → 보존 클래스 (RetentionCoverageGuardTest: comp_cd 테이블은 모두 등록돼야 함)';

CREATE TABLE sys_retention_policy (
    comp_cd     varchar(20) NOT NULL,
    class_cd    varchar(30) NOT NULL,
    keep_days   integer     NOT NULL,
    reason      varchar(200),
    row_ver     integer     NOT NULL DEFAULT 0,
    created_at  timestamptz NOT NULL DEFAULT now(),
    created_by  varchar(50) NOT NULL,
    updated_at  timestamptz NOT NULL DEFAULT now(),
    updated_by  varchar(50) NOT NULL,
    CONSTRAINT pk_sys_retention_policy PRIMARY KEY (comp_cd, class_cd),
    CONSTRAINT fk_sys_retention_policy_comp FOREIGN KEY (comp_cd) REFERENCES sys_comp (comp_cd),
    CONSTRAINT fk_sys_retention_policy_class FOREIGN KEY (class_cd) REFERENCES sys_retention_class (class_cd),
    CONSTRAINT ck_sys_retention_policy_days CHECK (keep_days > 0)
);
COMMENT ON TABLE sys_retention_policy IS '회사별 보존 기간 (클래스 최소 기간보다 짧게 둘 수 없음 — 정리 프로시저가 큰 값을 씀)';

CREATE TABLE sys_legal_hold (
    hold_id      bigint       GENERATED ALWAYS AS IDENTITY,
    comp_cd      varchar(20)  NOT NULL,
    class_cd     varchar(30)  NOT NULL,
    from_at      timestamptz  NOT NULL,
    to_at        timestamptz  NOT NULL,
    reason       varchar(500) NOT NULL,
    released_at  timestamptz,
    created_at   timestamptz  NOT NULL DEFAULT now(),
    created_by   varchar(50)  NOT NULL,
    updated_at   timestamptz  NOT NULL DEFAULT now(),
    updated_by   varchar(50)  NOT NULL,
    CONSTRAINT pk_sys_legal_hold PRIMARY KEY (hold_id),
    CONSTRAINT fk_sys_legal_hold_comp FOREIGN KEY (comp_cd) REFERENCES sys_comp (comp_cd),
    CONSTRAINT fk_sys_legal_hold_class FOREIGN KEY (class_cd) REFERENCES sys_retention_class (class_cd),
    CONSTRAINT ck_sys_legal_hold_range CHECK (from_at < to_at),
    CONSTRAINT ck_sys_legal_hold_reason CHECK (length(btrim(reason)) > 0)
);
COMMENT ON TABLE sys_legal_hold IS '법적 보류: 기간 안의 기록은 보존 기간이 지나도 정리하지 않는다 (해제 전까지)';
CREATE TRIGGER tg_audit AFTER INSERT OR UPDATE OR DELETE ON sys_retention_policy
    FOR EACH ROW EXECUTE FUNCTION hb_audit('comp_cd,class_cd', '', '');
CREATE TRIGGER tg_audit AFTER INSERT OR UPDATE OR DELETE ON sys_legal_hold
    FOR EACH ROW EXECUTE FUNCTION hb_audit('hold_id', '', '');

-- ---------------------------------------------------------------------
-- 5. 로그 일 다이제스트 (docs/05 D6) — 회사·테이블·날짜별 SHA-256 체인. 불변 백업 저장소로 반출(운영)
-- ---------------------------------------------------------------------
CREATE TABLE sys_log_digest (
    comp_cd      varchar(20) NOT NULL,
    table_nm     varchar(63) NOT NULL,
    log_date     date        NOT NULL,
    row_cnt      bigint      NOT NULL,
    digest       char(64)    NOT NULL,
    prev_digest  char(64),
    created_at   timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT pk_sys_log_digest PRIMARY KEY (comp_cd, table_nm, log_date),
    CONSTRAINT ck_sys_log_digest CHECK (digest ~ '^[0-9a-f]{64}$')
);
COMMENT ON TABLE sys_log_digest IS '로그 일 다이제스트 체인 (추가만). 과거 로그가 바뀌면 다시 계산한 값과 어긋난다';

-- ---------------------------------------------------------------------
-- 6. RLS + 추가 전용 권한
-- ---------------------------------------------------------------------
ALTER TABLE sys_file ENABLE ROW LEVEL SECURITY;
ALTER TABLE sys_file FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON sys_file USING (comp_cd = hb_current_comp()) WITH CHECK (comp_cd = hb_current_comp());

ALTER TABLE sys_access_log ENABLE ROW LEVEL SECURITY;
ALTER TABLE sys_access_log FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON sys_access_log USING (comp_cd = hb_current_comp()) WITH CHECK (comp_cd = hb_current_comp());

ALTER TABLE sys_audit_log ENABLE ROW LEVEL SECURITY;
ALTER TABLE sys_audit_log FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON sys_audit_log USING (comp_cd = hb_current_comp()) WITH CHECK (comp_cd = hb_current_comp());

ALTER TABLE sys_retention_policy ENABLE ROW LEVEL SECURITY;
ALTER TABLE sys_retention_policy FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON sys_retention_policy USING (comp_cd = hb_current_comp()) WITH CHECK (comp_cd = hb_current_comp());

ALTER TABLE sys_legal_hold ENABLE ROW LEVEL SECURITY;
ALTER TABLE sys_legal_hold FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON sys_legal_hold USING (comp_cd = hb_current_comp()) WITH CHECK (comp_cd = hb_current_comp());

ALTER TABLE sys_log_digest ENABLE ROW LEVEL SECURITY;
ALTER TABLE sys_log_digest FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON sys_log_digest USING (comp_cd = hb_current_comp()) WITH CHECK (comp_cd = hb_current_comp());

REVOKE UPDATE, DELETE, TRUNCATE ON sys_access_log, sys_audit_log, sys_log_digest FROM ${app_role};
REVOKE INSERT, UPDATE, DELETE, TRUNCATE ON sys_retention_class, sys_retention_table FROM ${app_role};

-- ---------------------------------------------------------------------
-- 7. 기본 데이터: 보존 클래스·테이블 매핑, 첨부 정책
-- ---------------------------------------------------------------------
INSERT INTO sys_retention_class (class_cd, basis, default_days, min_days, purge_allowed_yn, desc_key) VALUES
    ('ACCESS_LOG',     'OCCURRED_AT', 730,  365,  'Y', 'retention.ACCESS_LOG'),   -- 접속기록 1~2년(안전성 확보조치 기준, 원문 확인 필요)
    ('AUDIT_LOG',      'OCCURRED_AT', 1825, 1095, 'Y', 'retention.AUDIT_LOG'),
    ('LOG_DIGEST',     'KEEP',        NULL, NULL, 'N', 'retention.LOG_DIGEST'),
    ('MASTER',         'KEEP',        NULL, NULL, 'N', 'retention.MASTER'),
    ('FILE',           'KEEP',        NULL, NULL, 'N', 'retention.FILE'),
    ('SYSTEM',         'KEEP',        NULL, NULL, 'N', 'retention.SYSTEM'),
    ('QUALITY_RECORD', 'ITEM_EOL',    NULL, NULL, 'N', 'retention.QUALITY_RECORD'); -- IATF: 부품 생산·서비스 기간 + 1년 (P3 적용)

INSERT INTO sys_retention_table (table_nm, class_cd) VALUES
    ('sys_comp', 'MASTER'), ('sys_plant', 'MASTER'), ('sys_user', 'MASTER'),
    ('sys_auth_group', 'MASTER'), ('sys_user_auth', 'MASTER'), ('sys_menu', 'MASTER'), ('sys_auth_prog', 'MASTER'),
    ('sys_comp_code', 'MASTER'), ('sys_policy', 'SYSTEM'), ('sys_numbering_rule', 'SYSTEM'),
    ('sys_numbering_seq', 'SYSTEM'), ('sys_file', 'FILE'), ('sys_access_log', 'ACCESS_LOG'),
    ('sys_audit_log', 'AUDIT_LOG'), ('sys_retention_policy', 'SYSTEM'), ('sys_legal_hold', 'SYSTEM'),
    ('sys_log_digest', 'LOG_DIGEST');

INSERT INTO sys_policy_def (policy_key, value_type, default_value, allowed_values, min_value, max_value, desc_key) VALUES
    ('FILE_MAX_MB',      'INT',    '20', NULL, 1, 100, 'policy.FILE_MAX_MB'),
    ('FILE_ALLOWED_EXT', 'STRING', 'pdf,png,jpg,jpeg,gif,webp,bmp,tif,tiff,xlsx,xls,csv,docx,doc,pptx,ppt,hwp,hwpx,txt,zip,dwg,dxf,step,stp,igs,iges',
                         NULL, NULL, NULL, 'policy.FILE_ALLOWED_EXT');

-- ---------------------------------------------------------------------
-- 8. 로그 정리 프로시저 — 소유자 계정으로만 실행 (앱 계정 EXECUTE 회수). 회사마다 범위를 열고 정리.
--    purge_allowed_yn = 'Y' 인 클래스만, 회사 보존 기간(클래스 최소 기간 이상) 지난 행만, 법적 보류 기간은 제외.
--    운영: 배포 도구·cron 이 소유자 자격증명으로 호출 (런타임 앱은 소유자 자격증명이 없다, 01 7.1)
-- ---------------------------------------------------------------------
CREATE FUNCTION hb_purge_logs(p_now timestamptz DEFAULT now())
    RETURNS TABLE (out_comp_cd varchar, out_table_nm varchar, out_deleted bigint)
    LANGUAGE plpgsql
AS $$
DECLARE
    comps  varchar[];
    c_cd   varchar;
    t      record;
    keep   integer;
    n      bigint;
BEGIN
    -- 회사 목록을 먼저 배열로 받는다 (루프 중 범위를 바꾸므로 커서로 읽으면 RLS 판정이 달라질 수 있다)
    PERFORM set_config('app.comp_cd', '', true);
    PERFORM set_config('app.scope', 'SYSTEM', true);
    comps := ARRAY(SELECT c.comp_cd FROM sys_comp c ORDER BY c.comp_cd);
    PERFORM set_config('app.scope', '', true);

    FOREACH c_cd IN ARRAY comps LOOP
        PERFORM set_config('app.comp_cd', c_cd, true);
        FOR t IN SELECT rt.table_nm, rk.class_cd, rk.default_days, rk.min_days
                   FROM sys_retention_table rt
                   JOIN sys_retention_class rk ON rk.class_cd = rt.class_cd
                  WHERE rk.purge_allowed_yn = 'Y' AND rk.basis = 'OCCURRED_AT'
                  ORDER BY rt.table_nm LOOP
            SELECT greatest(coalesce(p.keep_days, t.default_days), t.min_days) INTO keep
              FROM (SELECT 1) one
              LEFT JOIN sys_retention_policy p ON p.comp_cd = c_cd AND p.class_cd = t.class_cd;
            EXECUTE format(
                'DELETE FROM %I l WHERE l.comp_cd = $1 AND l.occurred_at < $2
                    AND NOT EXISTS (SELECT 1 FROM sys_legal_hold h
                                     WHERE h.comp_cd = l.comp_cd AND h.class_cd = $3 AND h.released_at IS NULL
                                       AND l.occurred_at >= h.from_at AND l.occurred_at < h.to_at)', t.table_nm)
                USING c_cd, p_now - make_interval(days => keep), t.class_cd;
            GET DIAGNOSTICS n = ROW_COUNT;
            out_comp_cd := c_cd; out_table_nm := t.table_nm; out_deleted := n;
            RETURN NEXT;
        END LOOP;
    END LOOP;
    PERFORM set_config('app.comp_cd', '', true);
END
$$;
COMMENT ON FUNCTION hb_purge_logs(timestamptz) IS '보존 기간 지난 로그 정리 — 소유자 전용(앱 EXECUTE 없음), 법적 보류 제외, OCCURRED_AT 기준 정리 허용 클래스만';
REVOKE EXECUTE ON FUNCTION hb_purge_logs(timestamptz) FROM PUBLIC, ${app_role};
