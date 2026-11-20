-- =====================================================================
-- POP 감독자 승인 (docs/25 4.37, 사용자 결정 P1~P6 추천안 2026-10-24 — 임시 V9119)
-- - 정책: 실적 정정·파라미터 정정에 감독자 승인 요구(공장별 BOOL, 기본 N = 지금 그대로), 감독자 자격 코드(회사)
-- - pop_approval: 승인 원장 — 추가만(수정·삭제 차단 + REVOKE), RLS, 단말·작업자는 같은 회사(FK 는 RLS 를 안 본다),
--   요청 작업자 ≠ 승인자(자기 승인 금지 P3), 한 원 행에 승인 하나(정정도 한 번뿐)
-- - 접속 로그 이벤트 POP_APPROVAL_FAIL(감독자 아님·PIN 틀림·잠김)
-- =====================================================================

INSERT INTO sys_policy_def (policy_key, value_type, default_value, allowed_values, min_value, max_value, plant_scope_yn, desc_key) VALUES
    ('POP_APPROVAL_RESULT_CORRECT', 'BOOL',   'N', NULL, NULL, NULL, 'Y', 'policy.POP_APPROVAL_RESULT_CORRECT'),
    ('POP_APPROVAL_PARAM_CORRECT',  'BOOL',   'N', NULL, NULL, NULL, 'Y', 'policy.POP_APPROVAL_PARAM_CORRECT'),
    ('POP_APPROVER_QUAL',           'STRING', '',  NULL, NULL, NULL, 'N', 'policy.POP_APPROVER_QUAL');

CREATE TABLE pop_approval (
    approval_id          bigint        GENERATED ALWAYS AS IDENTITY,
    comp_cd              varchar(20)   NOT NULL,
    terminal_id          bigint        NOT NULL,
    requester_worker_id  bigint        NOT NULL,
    approver_worker_id   bigint        NOT NULL,
    action_cd            varchar(20)   NOT NULL,
    target_id            bigint        NOT NULL,
    reversal_id          bigint        NOT NULL,
    reason               varchar(500)  NOT NULL,
    occurred_at          timestamptz   NOT NULL,
    recorded_at          timestamptz   NOT NULL DEFAULT now(),
    recorded_by          varchar(50)   NOT NULL,
    CONSTRAINT pk_pop_approval PRIMARY KEY (approval_id),
    CONSTRAINT fk_pop_approval_comp FOREIGN KEY (comp_cd) REFERENCES sys_comp (comp_cd),
    CONSTRAINT fk_pop_approval_terminal FOREIGN KEY (terminal_id) REFERENCES sys_terminal (terminal_id),
    CONSTRAINT fk_pop_approval_requester FOREIGN KEY (requester_worker_id) REFERENCES mst_worker (worker_id),
    CONSTRAINT fk_pop_approval_approver FOREIGN KEY (approver_worker_id) REFERENCES mst_worker (worker_id),
    CONSTRAINT ck_pop_approval_action CHECK (action_cd IN ('RESULT_CORRECT', 'PARAM_CORRECT')),
    CONSTRAINT ck_pop_approval_self CHECK (requester_worker_id <> approver_worker_id),
    CONSTRAINT ck_pop_approval_reason CHECK (length(btrim(reason)) > 0),
    CONSTRAINT uq_pop_approval_target UNIQUE (action_cd, target_id)
);
COMMENT ON TABLE pop_approval IS 'POP 감독자 승인 원장 (docs/25 4.37) — 승인자(배지+PIN)·요청 작업자·동작·원 행·역분개 행. occurred_at = 승인(PIN 확인) 시각';
CREATE INDEX ix_pop_approval_terminal ON pop_approval (comp_cd, terminal_id, occurred_at);

CREATE FUNCTION hb_guard_pop_approval() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM sys_terminal t WHERE t.terminal_id = NEW.terminal_id AND t.comp_cd = NEW.comp_cd)
       OR NOT EXISTS (SELECT 1 FROM mst_worker w WHERE w.worker_id = NEW.requester_worker_id AND w.comp_cd = NEW.comp_cd)
       OR NOT EXISTS (SELECT 1 FROM mst_worker w WHERE w.worker_id = NEW.approver_worker_id AND w.comp_cd = NEW.comp_cd) THEN
        RAISE EXCEPTION 'approval context is not of company %', NEW.comp_cd
            USING ERRCODE = '23503', CONSTRAINT = 'fk_pop_approval_ctx_comp';
    END IF;
    RETURN NEW;
END
$$;
CREATE TRIGGER tg_pop_approval_guard BEFORE INSERT ON pop_approval FOR EACH ROW EXECUTE FUNCTION hb_guard_pop_approval();
CREATE TRIGGER tg_pop_approval_ledger BEFORE UPDATE OR DELETE ON pop_approval FOR EACH ROW EXECUTE FUNCTION hb_forbid_update_delete();

ALTER TABLE pop_approval ENABLE ROW LEVEL SECURITY;
ALTER TABLE pop_approval FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON pop_approval USING (comp_cd = hb_current_comp()) WITH CHECK (comp_cd = hb_current_comp());
REVOKE UPDATE, DELETE, TRUNCATE ON pop_approval FROM ${app_role};
INSERT INTO sys_retention_table (table_nm, class_cd) VALUES ('pop_approval', 'PRODUCTION_RECORD');

INSERT INTO sys_code (grp_cd, code, code_nm_key, sort_no) VALUES
    ('ACCESS_EVENT', 'POP_APPROVAL_FAIL', 'code.ACCESS_EVENT.POP_APPROVAL_FAIL', 350);
