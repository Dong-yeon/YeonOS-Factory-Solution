-- =====================================================================
-- P3 QMS SPC 위반 이벤트 원장 (docs/29_P3 19장, 사용자 결정 Q-n~Q-q) — 임시 번호, 병합 때 재번호
--
-- - qms_spc_event: 위반 원장 — 판정 확정 때 고정 관리한계(ACTIVE) 단위의 새 점이 규칙(WE1~4·R1)에 걸리면 규칙마다 한 행.
--   한계 스냅샷(limit id·중심·σ̂)·점 값·occurred_at(판정 시각)·recorded_at. 추가만(UPDATE/DELETE 차단)
-- - qms_spc_event_act: 처리 기록 — ACK(알았음)·ACTION(조치)·CLOSE(종결, 메모 필수)·VOID(무효, 사유 필수). 추가만,
--   종결·무효 뒤에는 더 없음. 이벤트 상태 = 처리 기록에서 파생(VOID > CLOSED > ACKED > OPEN)
-- - 같은 이벤트 처리 동시 입력은 advisory xact lock 으로 줄 세움(원장은 UPDATE 권한이 없어 FOR UPDATE 를 못 씀)
-- - 정책 QMS_GATE_SPC: 착수 게이트 Q9-3(이 품목·공정의 열린 위반) — 다른 게이트 규칙과 같은 OFF·WARN·BLOCK, 기본 WARN
-- =====================================================================

INSERT INTO sys_policy_def (policy_key, value_type, default_value, allowed_values, min_value, max_value, plant_scope_yn, desc_key) VALUES
    ('QMS_GATE_SPC', 'ENUM', 'WARN', ARRAY['OFF','WARN','BLOCK'], NULL, NULL, 'N', 'policy.QMS_GATE_SPC');

INSERT INTO sys_code_grp (grp_cd, grp_nm_key, comp_ext_yn) VALUES
    ('QMS_SPC_EVENT_STATUS', 'code.grp.QMS_SPC_EVENT_STATUS', 'N'),
    ('QMS_SPC_ACT',          'code.grp.QMS_SPC_ACT',          'N'),
    ('QMS_SPC_RULE',         'code.grp.QMS_SPC_RULE',         'N');
INSERT INTO sys_code (grp_cd, code, code_nm_key, sort_no) VALUES
    ('QMS_SPC_EVENT_STATUS', 'OPEN',   'code.QMS_SPC_EVENT_STATUS.OPEN',   1),
    ('QMS_SPC_EVENT_STATUS', 'ACKED',  'code.QMS_SPC_EVENT_STATUS.ACKED',  2),
    ('QMS_SPC_EVENT_STATUS', 'CLOSED', 'code.QMS_SPC_EVENT_STATUS.CLOSED', 3),
    ('QMS_SPC_EVENT_STATUS', 'VOID',   'code.QMS_SPC_EVENT_STATUS.VOID',   4),
    ('QMS_SPC_ACT', 'ACK',    'code.QMS_SPC_ACT.ACK',    1),
    ('QMS_SPC_ACT', 'ACTION', 'code.QMS_SPC_ACT.ACTION', 2),
    ('QMS_SPC_ACT', 'CLOSE',  'code.QMS_SPC_ACT.CLOSE',  3),
    ('QMS_SPC_ACT', 'VOID',   'code.QMS_SPC_ACT.VOID',   4),
    ('QMS_SPC_RULE', 'WE1', 'code.QMS_SPC_RULE.WE1', 1),
    ('QMS_SPC_RULE', 'WE2', 'code.QMS_SPC_RULE.WE2', 2),
    ('QMS_SPC_RULE', 'WE3', 'code.QMS_SPC_RULE.WE3', 3),
    ('QMS_SPC_RULE', 'WE4', 'code.QMS_SPC_RULE.WE4', 4),
    ('QMS_SPC_RULE', 'R1',  'code.QMS_SPC_RULE.R1',  5);

-- ---------------------------------------------------------------------
-- 1. 위반 원장
-- ---------------------------------------------------------------------
CREATE TABLE qms_spc_event (
    event_id      bigint         GENERATED ALWAYS AS IDENTITY,
    comp_cd       varchar(20)    NOT NULL,
    limit_id      bigint         NOT NULL,
    item_id       bigint         NOT NULL,
    char_id       bigint         NOT NULL,
    insp_type     varchar(10)    NOT NULL,
    proc_id       bigint,
    insp_id       bigint         NOT NULL,
    insp_line_id  bigint         NOT NULL,
    rule_cd       varchar(3)     NOT NULL,
    n             integer        NOT NULL,
    point_mean    numeric(24,10) NOT NULL,
    point_range   numeric(24,10) NOT NULL,
    z             numeric(24,10),
    center        numeric(24,10) NOT NULL,
    sigma         numeric(24,10) NOT NULL,
    occurred_at   timestamptz    NOT NULL,
    recorded_at   timestamptz    NOT NULL DEFAULT now(),
    created_by    varchar(50)    NOT NULL,
    CONSTRAINT pk_qms_spc_event PRIMARY KEY (event_id),
    CONSTRAINT fk_qms_spc_event_comp FOREIGN KEY (comp_cd) REFERENCES sys_comp (comp_cd),
    CONSTRAINT fk_qms_spc_event_limit FOREIGN KEY (limit_id) REFERENCES qms_spc_limit (limit_id),
    CONSTRAINT fk_qms_spc_event_insp FOREIGN KEY (insp_id) REFERENCES qms_insp (insp_id),
    CONSTRAINT fk_qms_spc_event_line FOREIGN KEY (insp_line_id) REFERENCES qms_insp_line (insp_line_id),
    CONSTRAINT ck_qms_spc_event_rule CHECK (rule_cd IN ('WE1', 'WE2', 'WE3', 'WE4', 'R1')),
    CONSTRAINT ck_qms_spc_event_n CHECK (n BETWEEN 2 AND 25 AND sigma > 0),
    -- 같은 검사 행·규칙은 한 번(판정 확정은 한 번이지만 재시도·이중 호출에도)
    CONSTRAINT uq_qms_spc_event_line_rule UNIQUE (insp_line_id, rule_cd)
);
CREATE INDEX ix_qms_spc_event_unit ON qms_spc_event (comp_cd, item_id, char_id, insp_type, proc_id, occurred_at DESC);
CREATE INDEX ix_qms_spc_event_insp ON qms_spc_event (comp_cd, insp_id);
CREATE INDEX ix_qms_spc_event_occurred ON qms_spc_event (comp_cd, occurred_at DESC);
COMMENT ON TABLE qms_spc_event IS 'SPC 위반 원장 — 판정 확정 때 고정 관리한계 단위의 새 점이 걸린 규칙마다 한 행, 추가만 (docs/29_P3 19장)';
COMMENT ON COLUMN qms_spc_event.center IS '그때 고정 관리한계 스냅샷(한계가 나중에 무효·재산정돼도 남는다) — sigma 와 함께';
COMMENT ON COLUMN qms_spc_event.occurred_at IS '판정 확정 시각(부분군이 확정된 때), recorded_at = 행이 들어간 시각';

CREATE FUNCTION hb_guard_qms_spc_event() RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    v_line  qms_insp_line;
    v_insp  qms_insp;
    v_limit qms_spc_limit;
BEGIN
    IF TG_OP <> 'INSERT' THEN
        RAISE EXCEPTION 'SPC event ledger is append-only (void it with an action)' USING ERRCODE = '55000', CONSTRAINT = 'ck_qms_spc_event_append_only';
    END IF;
    SELECT * INTO v_line FROM qms_insp_line WHERE insp_line_id = NEW.insp_line_id AND comp_cd = NEW.comp_cd;
    IF NOT FOUND OR v_line.insp_id <> NEW.insp_id OR v_line.char_id <> NEW.char_id THEN
        RAISE EXCEPTION 'inspection line % does not match event of company %', NEW.insp_line_id, NEW.comp_cd
            USING ERRCODE = '23503', CONSTRAINT = 'fk_qms_spc_event_line_comp';
    END IF;
    SELECT * INTO v_insp FROM qms_insp WHERE insp_id = NEW.insp_id;
    IF v_insp.status <> 'JUDGED' OR v_insp.item_id <> NEW.item_id OR v_insp.insp_type <> NEW.insp_type
       OR v_insp.proc_id IS DISTINCT FROM NEW.proc_id THEN
        RAISE EXCEPTION 'event must point to a judged inspection of the same unit' USING ERRCODE = '23514', CONSTRAINT = 'ck_qms_spc_event_insp';
    END IF;
    SELECT * INTO v_limit FROM qms_spc_limit WHERE limit_id = NEW.limit_id AND comp_cd = NEW.comp_cd;
    IF NOT FOUND OR v_limit.item_id <> NEW.item_id OR v_limit.char_id <> NEW.char_id OR v_limit.insp_type <> NEW.insp_type
       OR v_limit.proc_id IS DISTINCT FROM NEW.proc_id THEN
        RAISE EXCEPTION 'limit % is not the limit of this unit in company %', NEW.limit_id, NEW.comp_cd
            USING ERRCODE = '23503', CONSTRAINT = 'fk_qms_spc_event_limit_comp';
    END IF;
    RETURN NEW;
END
$$;
CREATE TRIGGER tg_qms_spc_event_guard BEFORE INSERT OR UPDATE OR DELETE ON qms_spc_event FOR EACH ROW EXECUTE FUNCTION hb_guard_qms_spc_event();

-- ---------------------------------------------------------------------
-- 2. 처리 기록
-- ---------------------------------------------------------------------
CREATE TABLE qms_spc_event_act (
    act_id      bigint         GENERATED ALWAYS AS IDENTITY,
    comp_cd     varchar(20)    NOT NULL,
    event_id    bigint         NOT NULL,
    act_type    varchar(6)     NOT NULL,
    note        varchar(1000),
    link_type   varchar(6),
    link_no     varchar(30),
    acted_at    timestamptz    NOT NULL DEFAULT now(),
    acted_by    varchar(50)    NOT NULL,
    CONSTRAINT pk_qms_spc_event_act PRIMARY KEY (act_id),
    CONSTRAINT fk_qms_spc_event_act_comp FOREIGN KEY (comp_cd) REFERENCES sys_comp (comp_cd),
    CONSTRAINT fk_qms_spc_event_act_event FOREIGN KEY (event_id) REFERENCES qms_spc_event (event_id),
    CONSTRAINT ck_qms_spc_event_act_type CHECK (act_type IN ('ACK', 'ACTION', 'CLOSE', 'VOID')),
    -- 조치·종결·무효는 메모 필수(Q-o: 종결은 메모만 필수, 연결은 선택)
    CONSTRAINT ck_qms_spc_event_act_note CHECK (act_type = 'ACK' OR coalesce(length(btrim(note)), 0) > 0),
    CONSTRAINT ck_qms_spc_event_act_link CHECK ((link_type IS NULL) = (link_no IS NULL)
        AND (link_type IS NULL OR (link_type IN ('NCR', 'CONTAIN', 'CAPA') AND act_type IN ('ACTION', 'CLOSE'))))
);
CREATE INDEX ix_qms_spc_event_act_event ON qms_spc_event_act (comp_cd, event_id, act_id);
COMMENT ON TABLE qms_spc_event_act IS 'SPC 위반 처리 기록 — ACK·ACTION·CLOSE·VOID, 추가만, 종결·무효 뒤엔 없음 (docs/29_P3 19장 SV-4)';
COMMENT ON COLUMN qms_spc_event_act.link_no IS '조치 연결 번호(NCR·봉쇄·8D) — 번호 사본, FK 없음(조치 기록은 그때 사람이 적은 것)';

CREATE FUNCTION hb_guard_qms_spc_event_act() RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    v_done boolean;
BEGIN
    IF TG_OP <> 'INSERT' THEN
        RAISE EXCEPTION 'SPC event actions are append-only' USING ERRCODE = '55000', CONSTRAINT = 'ck_qms_spc_event_act_append_only';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM qms_spc_event e WHERE e.event_id = NEW.event_id AND e.comp_cd = NEW.comp_cd) THEN
        RAISE EXCEPTION 'SPC event % does not belong to company %', NEW.event_id, NEW.comp_cd
            USING ERRCODE = '23503', CONSTRAINT = 'fk_qms_spc_event_act_event_comp';
    END IF;
    -- 같은 이벤트의 처리 입력을 줄 세운 뒤(둘 다 "열림" 을 보고 종결 두 번 같은 것 방지) 종결·무효 여부를 본다
    PERFORM pg_advisory_xact_lock(hashtext('qms_spc_event_act'), (NEW.event_id % 2147483647)::int);
    SELECT EXISTS (SELECT 1 FROM qms_spc_event_act a WHERE a.event_id = NEW.event_id AND a.act_type IN ('CLOSE', 'VOID')) INTO v_done;
    IF v_done THEN
        RAISE EXCEPTION 'SPC event % is already closed or voided', NEW.event_id USING ERRCODE = '55000', CONSTRAINT = 'ck_qms_spc_event_act_done';
    END IF;
    RETURN NEW;
END
$$;
CREATE TRIGGER tg_qms_spc_event_act_guard BEFORE INSERT OR UPDATE OR DELETE ON qms_spc_event_act FOR EACH ROW
    EXECUTE FUNCTION hb_guard_qms_spc_event_act();

-- ---------------------------------------------------------------------
-- 3. RLS · 권한 · 보존 (두 표 모두 원장 — 행 자체가 이력이라 감사 트리거 없음)
-- ---------------------------------------------------------------------
ALTER TABLE qms_spc_event ENABLE ROW LEVEL SECURITY;
ALTER TABLE qms_spc_event FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON qms_spc_event USING (comp_cd = hb_current_comp()) WITH CHECK (comp_cd = hb_current_comp());
ALTER TABLE qms_spc_event_act ENABLE ROW LEVEL SECURITY;
ALTER TABLE qms_spc_event_act FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON qms_spc_event_act USING (comp_cd = hb_current_comp()) WITH CHECK (comp_cd = hb_current_comp());

REVOKE UPDATE, DELETE, TRUNCATE ON qms_spc_event, qms_spc_event_act FROM ${app_role};

INSERT INTO sys_retention_table (table_nm, class_cd) VALUES
    ('qms_spc_event', 'QUALITY_RECORD'), ('qms_spc_event_act', 'QUALITY_RECORD');
