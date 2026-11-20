-- =====================================================================
-- V9301 pull 인쇄 에이전트 E2 (docs/65 19장 — 6.2)
--
-- - wms_printer.agent_terminal_id: 담당 에이전트 = sys_terminal 유형 COLLECTOR(Bearer API 키, 시스템관리 sys-terminal 화면에서 발급)
-- - wms_print_job.conn_mode + outbox_message_id NULL 허용: AGENT 작업은 아웃박스를 거치지 않는다(서버가 공장 안으로 밀 수 없다)
-- - wms_print_dispatch: AGENT 작업 큐(장치·단계·lease) — 인쇄 상태 이력은 사건 원장(V9300 wms_print_job_event)
-- 임시 번호(docs/29 2.2, WMS 대역) — 병합 때 이름만 바뀐다.
-- =====================================================================

INSERT INTO sys_policy_def (policy_key, value_type, default_value, allowed_values, min_value, max_value, plant_scope_yn, desc_key) VALUES
    -- 가져간 뒤 결과(SENT 이상)까지 기한 — 넘으면 결과 불명(UNKNOWN). 자동 재인쇄는 하지 않는다
    ('WMS_PRINT_LEASE_SEC',         'INT', '60',  NULL, 10, 3600,  'N', 'policy.WMS_PRINT_LEASE_SEC'),
    -- claim 롱폴링 최대(초) — 요청 waitSec 의 상한
    ('WMS_PRINT_AGENT_WAIT_SEC',    'INT', '20',  NULL, 0,  25,    'N', 'policy.WMS_PRINT_AGENT_WAIT_SEC'),
    -- 에이전트 마지막 접속이 이보다 오래면 오프라인 표시
    ('WMS_PRINT_AGENT_OFFLINE_SEC', 'INT', '120', NULL, 15, 86400, 'N', 'policy.WMS_PRINT_AGENT_OFFLINE_SEC');

INSERT INTO sys_code_grp (grp_cd, grp_nm_key, comp_ext_yn) VALUES ('WMS_PRINT_STAGE', 'code.grp.WMS_PRINT_STAGE', 'N');
INSERT INTO sys_code (grp_cd, code, code_nm_key, sort_no) VALUES
    ('WMS_PRINT_STAGE', 'QUEUED',  'code.WMS_PRINT_STAGE.QUEUED',  1),
    ('WMS_PRINT_STAGE', 'CLAIMED', 'code.WMS_PRINT_STAGE.CLAIMED', 2),
    ('WMS_PRINT_STAGE', 'SENT',    'code.WMS_PRINT_STAGE.SENT',    3),
    ('WMS_PRINT_STAGE', 'CLOSED',  'code.WMS_PRINT_STAGE.CLOSED',  4);

-- ---------------------------------------------------------------------
-- 1. 프린터 — 담당 에이전트
-- ---------------------------------------------------------------------
ALTER TABLE wms_printer ADD COLUMN agent_terminal_id bigint;
ALTER TABLE wms_printer ADD CONSTRAINT fk_wms_printer_agent FOREIGN KEY (agent_terminal_id) REFERENCES sys_terminal (terminal_id);
-- AGENT 면 에이전트 필수, DIRECT 면 없음(남은 배정이 엉뚱한 장치에 작업을 주지 않게)
ALTER TABLE wms_printer ADD CONSTRAINT ck_wms_printer_agent CHECK (
    (conn_mode = 'AGENT' AND agent_terminal_id IS NOT NULL) OR (conn_mode = 'DIRECT' AND agent_terminal_id IS NULL));
COMMENT ON COLUMN wms_printer.agent_terminal_id IS '담당 인쇄 에이전트(sys_terminal COLLECTOR, docs/65 19.1) — AGENT 만';

CREATE FUNCTION hb_guard_wms_printer_agent() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.agent_terminal_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM sys_terminal t WHERE t.terminal_id = NEW.agent_terminal_id
                          AND t.comp_cd = NEW.comp_cd AND t.terminal_type = 'COLLECTOR') THEN
        RAISE EXCEPTION 'terminal % is not a device of company %', NEW.agent_terminal_id, NEW.comp_cd
            USING ERRCODE = '23503', CONSTRAINT = 'fk_wms_printer_agent_comp';
    END IF;
    RETURN NEW;
END
$$;
CREATE TRIGGER tg_wms_printer_agent_guard BEFORE INSERT OR UPDATE OF agent_terminal_id, conn_mode ON wms_printer
    FOR EACH ROW EXECUTE FUNCTION hb_guard_wms_printer_agent();

-- ---------------------------------------------------------------------
-- 2. 인쇄 작업 — 연결 방식, AGENT 는 아웃박스 없음
-- ---------------------------------------------------------------------
ALTER TABLE wms_print_job ADD COLUMN conn_mode varchar(10) NOT NULL DEFAULT 'DIRECT';
ALTER TABLE wms_print_job ALTER COLUMN outbox_message_id DROP NOT NULL;
ALTER TABLE wms_print_job ADD CONSTRAINT ck_wms_print_job_conn CHECK (
    (conn_mode = 'DIRECT' AND outbox_message_id IS NOT NULL) OR (conn_mode = 'AGENT' AND outbox_message_id IS NULL));
COMMENT ON COLUMN wms_print_job.conn_mode IS '요청 때 프린터 연결 방식(docs/65 19.2) — DIRECT = 아웃박스·서버 TCP, AGENT = 에이전트 큐(wms_print_dispatch)';

-- ---------------------------------------------------------------------
-- 3. 에이전트 큐 (작업 1:1)
-- ---------------------------------------------------------------------
CREATE TABLE wms_print_dispatch (
    job_id       bigint        NOT NULL,
    comp_cd      varchar(20)   NOT NULL,
    printer_id   bigint        NOT NULL,
    terminal_id  bigint        NOT NULL,
    stage        varchar(10)   NOT NULL DEFAULT 'QUEUED',
    claim_cnt    integer       NOT NULL DEFAULT 0,
    claimed_at   timestamptz,
    lease_until  timestamptz,
    closed_at    timestamptz,
    created_at   timestamptz   NOT NULL DEFAULT now(),
    CONSTRAINT pk_wms_print_dispatch PRIMARY KEY (job_id),
    CONSTRAINT fk_wms_print_dispatch_job FOREIGN KEY (job_id) REFERENCES wms_print_job (job_id),
    CONSTRAINT fk_wms_print_dispatch_printer FOREIGN KEY (printer_id) REFERENCES wms_printer (printer_id),
    CONSTRAINT fk_wms_print_dispatch_terminal FOREIGN KEY (terminal_id) REFERENCES sys_terminal (terminal_id),
    CONSTRAINT ck_wms_print_dispatch_stage CHECK (stage IN ('QUEUED', 'CLAIMED', 'SENT', 'CLOSED')),
    CONSTRAINT ck_wms_print_dispatch_claim CHECK (claim_cnt >= 0),
    CONSTRAINT ck_wms_print_dispatch_lease CHECK (stage <> 'CLAIMED' OR lease_until IS NOT NULL),
    CONSTRAINT ck_wms_print_dispatch_closed CHECK ((stage = 'CLOSED') = (closed_at IS NOT NULL))
);
-- 가져가기: 장치별 기다리는 작업 순서대로 / 만료 판정: 가져간 것 중 lease 지난 것
CREATE INDEX ix_wms_print_dispatch_queue ON wms_print_dispatch (comp_cd, terminal_id, stage, job_id);
CREATE INDEX ix_wms_print_dispatch_lease ON wms_print_dispatch (comp_cd, lease_until) WHERE stage = 'CLAIMED';
COMMENT ON TABLE wms_print_dispatch IS '인쇄 에이전트 큐 (docs/65 19.2) — QUEUED → CLAIMED(lease) → SENT → CLOSED. 닫힌 행은 다시 열지 않는다';

-- 같은 회사 작업·프린터·장치, 작업은 AGENT. 단계는 앞으로만, 대상(작업·프린터·장치)은 고정
CREATE FUNCTION hb_guard_wms_print_dispatch() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'INSERT' THEN
        IF NOT EXISTS (SELECT 1 FROM wms_print_job j WHERE j.job_id = NEW.job_id AND j.comp_cd = NEW.comp_cd
                          AND j.printer_id = NEW.printer_id AND j.conn_mode = 'AGENT')
           OR NOT EXISTS (SELECT 1 FROM sys_terminal t WHERE t.terminal_id = NEW.terminal_id AND t.comp_cd = NEW.comp_cd
                          AND t.terminal_type = 'COLLECTOR') THEN
            RAISE EXCEPTION 'dispatch context is not an agent job of company %', NEW.comp_cd
                USING ERRCODE = '23503', CONSTRAINT = 'fk_wms_print_dispatch_comp';
        END IF;
        RETURN NEW;
    END IF;
    IF NEW.job_id <> OLD.job_id OR NEW.printer_id <> OLD.printer_id OR NEW.terminal_id <> OLD.terminal_id OR NEW.comp_cd <> OLD.comp_cd THEN
        RAISE EXCEPTION 'dispatch target is fixed' USING ERRCODE = '55000', CONSTRAINT = 'ck_wms_print_dispatch_fixed';
    END IF;
    IF OLD.stage = 'CLOSED' AND NEW.stage <> 'CLOSED'
       OR (OLD.stage = 'SENT' AND NEW.stage IN ('QUEUED', 'CLAIMED'))
       OR (OLD.stage = 'CLAIMED' AND NEW.stage = 'QUEUED') THEN
        RAISE EXCEPTION 'dispatch stage % → % not allowed', OLD.stage, NEW.stage
            USING ERRCODE = '55000', CONSTRAINT = 'ck_wms_print_dispatch_transition';
    END IF;
    RETURN NEW;
END
$$;
CREATE TRIGGER tg_wms_print_dispatch_guard BEFORE INSERT OR UPDATE ON wms_print_dispatch FOR EACH ROW EXECUTE FUNCTION hb_guard_wms_print_dispatch();

ALTER TABLE wms_print_dispatch ENABLE ROW LEVEL SECURITY;
ALTER TABLE wms_print_dispatch FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON wms_print_dispatch USING (comp_cd = hb_current_comp()) WITH CHECK (comp_cd = hb_current_comp());
REVOKE DELETE, TRUNCATE ON wms_print_dispatch FROM ${app_role};

INSERT INTO sys_retention_table (table_nm, class_cd) VALUES ('wms_print_dispatch', 'QUALITY_RECORD');
