-- =====================================================================
-- 기록 무결성 점검 (docs/52 Y1 — V200·임시 V9714) — ALCOA+ 대응표 docs/54
-- - sys_integrity_run: 점검 결과(추가만). 누가·언제·상태·문제 수 + 점검별 결과(jsonb). 고칠 수 없다 — 점검 기록 자체가 증빙
-- - 화면 sys-integrity: SEARCH(이력·결과) · FN1(점검 실행 — 접속 로그 ACTION) · EXCEL(결과 내보내기)
--   기본 권한은 관리자 그룹(admin_yn)만 — 다른 그룹은 회사 관리자가 권한그룹 화면에서 준다
-- =====================================================================

CREATE TABLE sys_integrity_run (
    run_id       bigint        GENERATED ALWAYS AS IDENTITY,
    comp_cd      varchar(20)   NOT NULL,
    occurred_at  timestamptz   NOT NULL DEFAULT now(),   -- 점검 시각(보존 클래스 AUDIT_LOG 의 정리 기준 칸)
    run_by       varchar(80)   NOT NULL,
    status       varchar(10)   NOT NULL,
    problem_cnt  integer       NOT NULL,
    result       jsonb         NOT NULL,
    CONSTRAINT pk_sys_integrity_run PRIMARY KEY (run_id),
    CONSTRAINT fk_sys_integrity_run_comp FOREIGN KEY (comp_cd) REFERENCES sys_comp (comp_cd),
    CONSTRAINT ck_sys_integrity_run_status CHECK (status IN ('OK', 'WARN', 'FAIL')),
    CONSTRAINT ck_sys_integrity_run_problems CHECK (problem_cnt >= 0),
    CONSTRAINT ck_sys_integrity_run_result CHECK (jsonb_typeof(result) = 'array')
);
CREATE INDEX ix_sys_integrity_run_at ON sys_integrity_run (comp_cd, occurred_at DESC);
COMMENT ON TABLE sys_integrity_run IS '기록 무결성 점검 결과 (추가만 — 다이제스트 체인·원장 보호·전자서명 해시·감사 트리거, docs/52 Y1)';

ALTER TABLE sys_integrity_run ENABLE ROW LEVEL SECURITY;
ALTER TABLE sys_integrity_run FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON sys_integrity_run USING (comp_cd = hb_current_comp()) WITH CHECK (comp_cd = hb_current_comp());
REVOKE UPDATE, DELETE, TRUNCATE ON sys_integrity_run FROM ${app_role};

INSERT INTO sys_retention_table (table_nm, class_cd) VALUES ('sys_integrity_run', 'AUDIT_LOG');

INSERT INTO sys_prog (prog_cd, prog_nm_key, view_path, module_cd, actions, sort_no, self_service_yn) VALUES
    ('sys-integrity', 'prog.sysIntegrity', 'admin/integrity-check', 'SYS', ARRAY['SEARCH', 'FN1', 'EXCEL']::varchar(20)[], 943, 'N');
