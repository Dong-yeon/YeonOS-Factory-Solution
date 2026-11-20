-- =====================================================================
-- ERP 연계 경계·ERP 업로드용 엑셀 내보내기 (P2 7c — docs/25 4.11, D19·D20, 07 D4)
-- 임시 번호(MES 대역, docs/29 2.2) — 병합 때 상위 세션이 다음 빈 번호로 이름만 바꾼다
--
-- - 정책 ERP_SYSTEM: NONE(기본) / EXCEL(엑셀만) / FAKE(가짜 ERP 로 아웃박스 자동 송신 — 시험·데모)
-- - prd_wo.done_at: 지시 완료 시각(내보내기 "완료일" 기준). 이 버전 전에 완료된 지시는 비어 있다(개발 데이터만)
-- - prd_erp_export: 엑셀 내보내기 기록(종류·기간·건수·사람·시각) — 같은 기간 두 번은 막지 않고 화면이 알린다(D20)
-- - 화면 prd-erp-export (조회·엑셀)
-- =====================================================================

INSERT INTO sys_policy_def (policy_key, value_type, default_value, allowed_values, min_value, max_value, desc_key) VALUES
    ('ERP_SYSTEM', 'ENUM', 'NONE', ARRAY['NONE','EXCEL','FAKE'], NULL, NULL, 'policy.ERP_SYSTEM');

ALTER TABLE prd_wo ADD COLUMN done_at timestamptz;
ALTER TABLE prd_wo ADD CONSTRAINT ck_prd_wo_done_at CHECK (done_at IS NULL OR status IN ('DONE', 'CLOSED'));
COMMENT ON COLUMN prd_wo.done_at IS '지시 완료 시각 (7c — 완료 때 한 번, ERP 실적 송신·내보내기 기준)';
CREATE INDEX ix_prd_wo_done ON prd_wo (comp_cd, done_at) WHERE done_at IS NOT NULL;
CREATE INDEX ix_prd_receipt_occurred ON prd_receipt (comp_cd, occurred_at);

CREATE TABLE prd_erp_export (
    export_id    bigint        GENERATED ALWAYS AS IDENTITY,
    comp_cd      varchar(20)   NOT NULL,
    kind         varchar(10)   NOT NULL,
    from_day     date          NOT NULL,
    to_day       date          NOT NULL,
    row_cnt      integer       NOT NULL,
    exported_at  timestamptz   NOT NULL DEFAULT now(),
    exported_by  varchar(50)   NOT NULL,
    CONSTRAINT pk_prd_erp_export PRIMARY KEY (export_id),
    CONSTRAINT fk_prd_erp_export_comp FOREIGN KEY (comp_cd) REFERENCES sys_comp (comp_cd),
    CONSTRAINT ck_prd_erp_export_kind CHECK (kind IN ('RESULT', 'RECEIPT')),
    CONSTRAINT ck_prd_erp_export_range CHECK (from_day <= to_day),
    CONSTRAINT ck_prd_erp_export_cnt CHECK (row_cnt >= 0)
);
CREATE INDEX ix_prd_erp_export_kind ON prd_erp_export (comp_cd, kind, to_day);
COMMENT ON TABLE prd_erp_export IS 'ERP 업로드용 엑셀 내보내기 기록 (추가만 — 같은 기간 재내보내기 알림용, docs/25 D20)';

ALTER TABLE prd_erp_export ENABLE ROW LEVEL SECURITY;
ALTER TABLE prd_erp_export FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON prd_erp_export USING (comp_cd = hb_current_comp()) WITH CHECK (comp_cd = hb_current_comp());
REVOKE UPDATE, DELETE, TRUNCATE ON prd_erp_export FROM ${app_role};

INSERT INTO sys_prog (prog_cd, prog_nm_key, view_path, module_cd, actions, sort_no) VALUES
    ('prd-erp-export', 'prog.prdErpExport', 'production/erp-export', 'PRD', ARRAY['SEARCH', 'EXCEL']::varchar(20)[], 470);
INSERT INTO sys_retention_table (table_nm, class_cd) VALUES ('prd_erp_export', 'PRODUCTION_RECORD');
