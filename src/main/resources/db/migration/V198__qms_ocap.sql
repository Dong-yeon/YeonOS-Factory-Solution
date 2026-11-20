-- QMS OCAP 표준 조치 목록 1단계 (docs/29_P3 26장, 사용자·상위 결정 2026-10-27 — Q-oa·Q-ob·Q-oc 추천대로, Q-od 표 2개 승인)
-- 기준정보 qms_ocap_item(규칙별·공통, 사용 중지만·삭제 없음) + SPC 위반 ACTION 에서 고른 항목의 사본 원장 qms_spc_event_act_ocap(추가만)

CREATE TABLE qms_ocap_item (
    ocap_id      bigint        GENERATED ALWAYS AS IDENTITY,
    comp_cd      varchar(20)   NOT NULL,
    rule_cd      varchar(4),
    seq          integer       NOT NULL DEFAULT 0,
    action_text  varchar(500)  NOT NULL,
    use_yn       char(1)       NOT NULL DEFAULT 'Y',
    row_ver      integer       NOT NULL DEFAULT 0,
    created_at   timestamptz   NOT NULL DEFAULT now(),
    created_by   varchar(50)   NOT NULL,
    updated_at   timestamptz   NOT NULL DEFAULT now(),
    updated_by   varchar(50)   NOT NULL,
    CONSTRAINT pk_qms_ocap_item PRIMARY KEY (ocap_id),
    CONSTRAINT fk_qms_ocap_item_comp FOREIGN KEY (comp_cd) REFERENCES sys_comp (comp_cd),
    -- null = 모든 규칙 공통(OC-1)
    CONSTRAINT ck_qms_ocap_item_rule CHECK (rule_cd IS NULL OR rule_cd IN ('WE1', 'WE2', 'WE3', 'WE4', 'R1')),
    CONSTRAINT ck_qms_ocap_item_seq CHECK (seq BETWEEN 0 AND 99999),
    CONSTRAINT ck_qms_ocap_item_text CHECK (length(btrim(action_text)) > 0),
    CONSTRAINT ck_qms_ocap_item_use CHECK (use_yn IN ('Y', 'N'))
);
-- 같은 규칙(공통 포함) 안 사용 중인 같은 문구는 하나
CREATE UNIQUE INDEX uq_qms_ocap_item_text ON qms_ocap_item (comp_cd, coalesce(rule_cd, '*'), action_text) WHERE use_yn = 'Y';
CREATE INDEX ix_qms_ocap_item_rule ON qms_ocap_item (comp_cd, rule_cd, seq);
COMMENT ON TABLE qms_ocap_item IS 'OCAP 표준 조치 목록 — 규칙별(null = 공통), 사용 중지만(삭제 없음), 고른 기록은 사본(qms_spc_event_act_ocap) (docs/29_P3 26장)';

CREATE TABLE qms_spc_event_act_ocap (
    act_ocap_id  bigint        GENERATED ALWAYS AS IDENTITY,
    comp_cd      varchar(20)   NOT NULL,
    act_id       bigint        NOT NULL,
    ocap_id      bigint        NOT NULL,
    rule_cd      varchar(4),
    action_text  varchar(500)  NOT NULL,
    created_at   timestamptz   NOT NULL DEFAULT now(),
    created_by   varchar(50)   NOT NULL,
    CONSTRAINT pk_qms_spc_event_act_ocap PRIMARY KEY (act_ocap_id),
    CONSTRAINT fk_qms_spc_event_act_ocap_comp FOREIGN KEY (comp_cd) REFERENCES sys_comp (comp_cd),
    CONSTRAINT fk_qms_spc_event_act_ocap_act FOREIGN KEY (act_id) REFERENCES qms_spc_event_act (act_id),
    CONSTRAINT fk_qms_spc_event_act_ocap_item FOREIGN KEY (ocap_id) REFERENCES qms_ocap_item (ocap_id),
    CONSTRAINT uq_qms_spc_event_act_ocap UNIQUE (act_id, ocap_id)
);
COMMENT ON TABLE qms_spc_event_act_ocap IS 'SPC 위반 조치(ACTION)에서 고른 OCAP 항목 — 고른 순간 문구·규칙 사본, 추가만 (docs/29_P3 26장 OC-2)';

-- 사본 원장: 추가만, 처리 행이 같은 회사의 ACTION, 항목이 같은 회사
CREATE FUNCTION hb_guard_qms_spc_event_act_ocap() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP <> 'INSERT' THEN
        RAISE EXCEPTION 'OCAP selection ledger is append-only' USING ERRCODE = '55000', CONSTRAINT = 'ck_qms_spc_event_act_ocap_append_only';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM qms_spc_event_act a WHERE a.act_id = NEW.act_id AND a.comp_cd = NEW.comp_cd AND a.act_type = 'ACTION') THEN
        RAISE EXCEPTION 'act % is not an ACTION of company %', NEW.act_id, NEW.comp_cd
            USING ERRCODE = '23514', CONSTRAINT = 'ck_qms_spc_event_act_ocap_action';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM qms_ocap_item o WHERE o.ocap_id = NEW.ocap_id AND o.comp_cd = NEW.comp_cd) THEN
        RAISE EXCEPTION 'OCAP item % does not belong to company %', NEW.ocap_id, NEW.comp_cd
            USING ERRCODE = '23503', CONSTRAINT = 'fk_qms_spc_event_act_ocap_item_comp';
    END IF;
    RETURN NEW;
END
$$;
CREATE TRIGGER tg_qms_spc_event_act_ocap_guard BEFORE INSERT OR UPDATE OR DELETE ON qms_spc_event_act_ocap FOR EACH ROW
    EXECUTE FUNCTION hb_guard_qms_spc_event_act_ocap();

-- 목록은 지우지 않는다(사본이 원 항목을 가리킴) — 사용 중지만(OC-6)
CREATE FUNCTION hb_guard_qms_ocap_item() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'OCAP items are never deleted (use_yn = N)' USING ERRCODE = '55000', CONSTRAINT = 'ck_qms_ocap_item_no_delete';
END
$$;
CREATE TRIGGER tg_qms_ocap_item_no_delete BEFORE DELETE ON qms_ocap_item FOR EACH ROW EXECUTE FUNCTION hb_guard_qms_ocap_item();

CREATE TRIGGER tg_audit AFTER INSERT OR UPDATE OR DELETE ON qms_ocap_item FOR EACH ROW EXECUTE FUNCTION hb_audit('ocap_id', '', '');

ALTER TABLE qms_ocap_item ENABLE ROW LEVEL SECURITY;
ALTER TABLE qms_ocap_item FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON qms_ocap_item USING (comp_cd = hb_current_comp()) WITH CHECK (comp_cd = hb_current_comp());
ALTER TABLE qms_spc_event_act_ocap ENABLE ROW LEVEL SECURITY;
ALTER TABLE qms_spc_event_act_ocap FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON qms_spc_event_act_ocap USING (comp_cd = hb_current_comp()) WITH CHECK (comp_cd = hb_current_comp());

REVOKE TRUNCATE ON qms_ocap_item FROM ${app_role};
REVOKE UPDATE, DELETE, TRUNCATE ON qms_spc_event_act_ocap FROM ${app_role};

INSERT INTO sys_prog (prog_cd, prog_nm_key, view_path, module_cd, actions, sort_no) VALUES
    ('qms-ocap', 'prog.qmsOcap', 'qms/ocap-mgt', 'QMS', ARRAY['SEARCH','SAVE']::varchar(20)[], 697);

INSERT INTO sys_retention_table (table_nm, class_cd) VALUES
    ('qms_ocap_item', 'MASTER'), ('qms_spc_event_act_ocap', 'QUALITY_RECORD');
