-- =====================================================================
-- V014 엑셀 올리기(기준정보 이관 엔진, P1 2단계 — docs/17 3b, docs/07 D1)
--
-- 올리기 = 읽기 → 검증 → 스테이징(이 두 테이블) → 미리보기 → 실행(본 테이블 반영).
-- 미리보기 단계는 이 두 테이블과 원본 파일(sys_file)에만 쓴다 — 본 테이블·아웃박스는 실행 때만.
-- 템플릿 정의(열·검증)는 Java 빈 ImportTemplate (docs/17 P1-8) — 여기에는 작업 기록만.
-- =====================================================================

CREATE TABLE sys_import_job (
    job_id        bigint        GENERATED ALWAYS AS IDENTITY,
    comp_cd       varchar(20)   NOT NULL,
    template_cd   varchar(30)   NOT NULL,
    template_ver  integer       NOT NULL,
    prog_cd       varchar(50)   NOT NULL,
    mode          varchar(12)   NOT NULL,
    status        varchar(10)   NOT NULL DEFAULT 'VALIDATED',
    file_id       bigint,
    file_nm       varchar(255)  NOT NULL,
    cnt_total     integer       NOT NULL DEFAULT 0,
    cnt_new       integer       NOT NULL DEFAULT 0,
    cnt_change    integer       NOT NULL DEFAULT 0,
    cnt_same      integer       NOT NULL DEFAULT 0,
    cnt_error     integer       NOT NULL DEFAULT 0,
    col_keys      varchar(1000) NOT NULL,
    ignored_cols  varchar(1000),
    result        jsonb,
    executed_at   timestamptz,
    executed_by   varchar(50),
    row_ver       integer       NOT NULL DEFAULT 0,
    created_at    timestamptz   NOT NULL DEFAULT now(),
    created_by    varchar(50)   NOT NULL,
    updated_at    timestamptz   NOT NULL DEFAULT now(),
    updated_by    varchar(50)   NOT NULL,
    CONSTRAINT pk_sys_import_job PRIMARY KEY (job_id),
    CONSTRAINT fk_sys_import_job_comp FOREIGN KEY (comp_cd) REFERENCES sys_comp (comp_cd),
    CONSTRAINT fk_sys_import_job_prog FOREIGN KEY (prog_cd) REFERENCES sys_prog (prog_cd),
    CONSTRAINT fk_sys_import_job_file FOREIGN KEY (file_id) REFERENCES sys_file (file_id),
    CONSTRAINT ck_sys_import_job_tpl CHECK (template_cd ~ '^[A-Z][A-Z0-9_]{1,29}$'),
    CONSTRAINT ck_sys_import_job_mode CHECK (mode IN ('INSERT_ONLY', 'UPSERT')),
    CONSTRAINT ck_sys_import_job_status CHECK (status IN ('VALIDATED', 'DONE', 'CANCELED')),
    CONSTRAINT ck_sys_import_job_cnt CHECK (cnt_total = cnt_new + cnt_change + cnt_same + cnt_error),
    CONSTRAINT ck_sys_import_job_result CHECK (result IS NULL OR jsonb_typeof(result) = 'object')
);
CREATE INDEX ix_sys_import_job_owner ON sys_import_job (comp_cd, created_by, created_at);
COMMENT ON TABLE sys_import_job IS '엑셀 올리기 작업 (P1 2단계). 상태 VALIDATED → DONE(실행) | CANCELED. 실행은 조건부 UPDATE 1건만 성공';
COMMENT ON COLUMN sys_import_job.result IS '대사 결과: 파일 행 수·반영 수·본 테이블 건수 전후';

CREATE TABLE sys_import_row (
    comp_cd       varchar(20)   NOT NULL,
    job_id        bigint        NOT NULL,
    row_no        integer       NOT NULL,
    row_status    varchar(6)    NOT NULL,
    biz_key       varchar(100),
    data          jsonb         NOT NULL,
    errors        jsonb,
    target_id     bigint,
    target_ver    integer,
    CONSTRAINT pk_sys_import_row PRIMARY KEY (job_id, row_no),
    CONSTRAINT fk_sys_import_row_comp FOREIGN KEY (comp_cd) REFERENCES sys_comp (comp_cd),
    CONSTRAINT fk_sys_import_row_job FOREIGN KEY (job_id) REFERENCES sys_import_job (job_id),
    CONSTRAINT ck_sys_import_row_status CHECK (row_status IN ('NEW', 'CHANGE', 'SAME', 'ERROR')),
    CONSTRAINT ck_sys_import_row_data CHECK (jsonb_typeof(data) = 'object'),
    CONSTRAINT ck_sys_import_row_errors CHECK (errors IS NULL OR jsonb_typeof(errors) = 'array')
);
CREATE INDEX ix_sys_import_row_status ON sys_import_row (comp_cd, job_id, row_status, row_no);
COMMENT ON TABLE sys_import_row IS '엑셀 올리기 스테이징 행 (정규화 값·오류). 감사 예외 — 본 테이블 반영이 감사된다';

-- 행의 작업이 같은 회사 것인지 (회사별 테이블 간 FK 는 RLS 를 무시한다)
CREATE FUNCTION hb_check_import_row_comp() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM sys_import_job j WHERE j.job_id = NEW.job_id AND j.comp_cd = NEW.comp_cd) THEN
        RAISE EXCEPTION 'import job % does not belong to company %', NEW.job_id, NEW.comp_cd
            USING ERRCODE = '23503', CONSTRAINT = 'fk_sys_import_row_job_comp';
    END IF;
    RETURN NEW;
END
$$;
CREATE TRIGGER tg_sys_import_row_comp BEFORE INSERT OR UPDATE ON sys_import_row
    FOR EACH ROW EXECUTE FUNCTION hb_check_import_row_comp();

-- 원본 파일도 같은 회사 것만
CREATE FUNCTION hb_check_import_job_file() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.file_id IS NOT NULL
       AND NOT EXISTS (SELECT 1 FROM sys_file f WHERE f.file_id = NEW.file_id AND f.comp_cd = NEW.comp_cd) THEN
        RAISE EXCEPTION 'file % does not belong to company %', NEW.file_id, NEW.comp_cd
            USING ERRCODE = '23503', CONSTRAINT = 'fk_sys_import_job_file_comp';
    END IF;
    RETURN NEW;
END
$$;
CREATE TRIGGER tg_sys_import_job_file BEFORE INSERT OR UPDATE ON sys_import_job
    FOR EACH ROW EXECUTE FUNCTION hb_check_import_job_file();

CREATE TRIGGER tg_audit AFTER INSERT OR UPDATE OR DELETE ON sys_import_job
    FOR EACH ROW EXECUTE FUNCTION hb_audit('job_id', '', '');

ALTER TABLE sys_import_job ENABLE ROW LEVEL SECURITY;
ALTER TABLE sys_import_job FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON sys_import_job USING (comp_cd = hb_current_comp()) WITH CHECK (comp_cd = hb_current_comp());
ALTER TABLE sys_import_row ENABLE ROW LEVEL SECURITY;
ALTER TABLE sys_import_row FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON sys_import_row USING (comp_cd = hb_current_comp()) WITH CHECK (comp_cd = hb_current_comp());
-- 작업 기록은 지우지 않는다(취소 상태로). 스테이징 행 정리는 보존 정책(소유자 도구)으로
REVOKE DELETE, TRUNCATE ON sys_import_job, sys_import_row FROM ${app_role};

INSERT INTO sys_retention_table (table_nm, class_cd) VALUES
    ('sys_import_job', 'SYSTEM'), ('sys_import_row', 'SYSTEM');

INSERT INTO sys_policy_def (policy_key, value_type, default_value, allowed_values, min_value, max_value, desc_key) VALUES
    ('IMPORT_MAX_ROWS', 'INT', '50000', NULL, 100, 200000, 'policy.IMPORT_MAX_ROWS');
