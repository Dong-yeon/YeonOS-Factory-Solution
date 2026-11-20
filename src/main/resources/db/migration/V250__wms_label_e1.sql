-- =====================================================================
-- V9300 라벨 보강 E1 (docs/65 18장 — 6.1 인쇄 3단 상태·프린터 상태, 6.3 한글 글꼴, 6.5 템플릿 규칙)
--
-- - wms_print_job_event: 인쇄 사건 원장(추가만). 작업의 지금 상태 = 상태 사건 중 마지막(없으면 아웃박스에서 파생)
-- - wms_printer_status: 프린터 마지막 상태(~HQES 오류·경고, odometer, 확인 시각) — 확인 작업·에이전트가 갱신
-- - wms_printer 칸: 한글 글꼴 등록 여부·파일, 용지 폭, 상태 확인 여부
-- - wms_label_tpl.size_cd: 크기 ID(폭x높이 mm, 생성 칸) / wms_label_tpl_rule: 거래처 > 품목 > 유형 기본
-- 임시 번호(docs/29 2.2, WMS 대역) — 병합 때 이름만 바뀐다.
-- =====================================================================

INSERT INTO sys_policy_def (policy_key, value_type, default_value, allowed_values, min_value, max_value, plant_scope_yn, desc_key) VALUES
    -- 설치형(DIRECT) 프린터 상태 확인(~HQES + odometer) 주기. 확인 안 된 SENT 작업이 있으면 주기를 기다리지 않는다
    ('WMS_PRINTER_PROBE_SEC', 'INT', '30', NULL, 5, 3600, 'N', 'policy.WMS_PRINTER_PROBE_SEC');

INSERT INTO sys_code_grp (grp_cd, grp_nm_key, comp_ext_yn) VALUES
    ('WMS_PRINT_STATE', 'code.grp.WMS_PRINT_STATE', 'N'),
    ('WMS_PRINTER_STATE', 'code.grp.WMS_PRINTER_STATE', 'N'),
    ('WMS_PRINT_EVENT', 'code.grp.WMS_PRINT_EVENT', 'N');
INSERT INTO sys_code (grp_cd, code, code_nm_key, sort_no) VALUES
    ('WMS_PRINT_STATE', 'QUEUED',        'code.WMS_PRINT_STATE.QUEUED',        1),
    ('WMS_PRINT_STATE', 'CLAIMED',       'code.WMS_PRINT_STATE.CLAIMED',       2),
    ('WMS_PRINT_STATE', 'SENT',          'code.WMS_PRINT_STATE.SENT',          3),
    ('WMS_PRINT_STATE', 'PRINTED',       'code.WMS_PRINT_STATE.PRINTED',       4),
    ('WMS_PRINT_STATE', 'VERIFIED',      'code.WMS_PRINT_STATE.VERIFIED',      5),
    ('WMS_PRINT_STATE', 'PRINTER_ERROR', 'code.WMS_PRINT_STATE.PRINTER_ERROR', 6),
    ('WMS_PRINT_STATE', 'FAILED',        'code.WMS_PRINT_STATE.FAILED',        7),
    ('WMS_PRINT_STATE', 'UNKNOWN',       'code.WMS_PRINT_STATE.UNKNOWN',       8),
    ('WMS_PRINT_STATE', 'CANCELLED',     'code.WMS_PRINT_STATE.CANCELLED',     9),
    ('WMS_PRINTER_STATE', 'OK',      'code.WMS_PRINTER_STATE.OK',      1),
    ('WMS_PRINTER_STATE', 'WARN',    'code.WMS_PRINTER_STATE.WARN',    2),
    ('WMS_PRINTER_STATE', 'ERROR',   'code.WMS_PRINTER_STATE.ERROR',   3),
    ('WMS_PRINTER_STATE', 'OFFLINE', 'code.WMS_PRINTER_STATE.OFFLINE', 4),
    ('WMS_PRINTER_STATE', 'UNKNOWN', 'code.WMS_PRINTER_STATE.UNKNOWN', 5),
    ('WMS_PRINT_EVENT', 'CLAIMED',       'code.WMS_PRINT_STATE.CLAIMED',       1),
    ('WMS_PRINT_EVENT', 'SENT',          'code.WMS_PRINT_STATE.SENT',          2),
    ('WMS_PRINT_EVENT', 'PRINTED',       'code.WMS_PRINT_STATE.PRINTED',       3),
    ('WMS_PRINT_EVENT', 'VERIFIED',      'code.WMS_PRINT_STATE.VERIFIED',      4),
    ('WMS_PRINT_EVENT', 'PRINTER_ERROR', 'code.WMS_PRINT_STATE.PRINTER_ERROR', 5),
    ('WMS_PRINT_EVENT', 'FAILED',        'code.WMS_PRINT_STATE.FAILED',        6),
    ('WMS_PRINT_EVENT', 'UNKNOWN',       'code.WMS_PRINT_STATE.UNKNOWN',       7),
    ('WMS_PRINT_EVENT', 'CANCELLED',     'code.WMS_PRINT_STATE.CANCELLED',     8),
    ('WMS_PRINT_EVENT', 'VERIFY_NG',     'code.WMS_PRINT_EVENT.VERIFY_NG',     9),
    ('WMS_PRINT_EVENT', 'WARN',          'code.WMS_PRINT_EVENT.WARN',          10),
    ('WMS_PRINT_EVENT', 'STALE',         'code.WMS_PRINT_EVENT.STALE',         11);

-- ---------------------------------------------------------------------
-- 1. 프린터 칸 (6.3 한글 글꼴 · 6.5 용지 폭 · 6.1 상태 확인)
-- ---------------------------------------------------------------------
ALTER TABLE wms_printer ADD COLUMN cjk_font_yn char(1) NOT NULL DEFAULT 'N';
ALTER TABLE wms_printer ADD COLUMN cjk_font_file varchar(40);
ALTER TABLE wms_printer ADD COLUMN media_width_mm numeric(5,1);
ALTER TABLE wms_printer ADD COLUMN status_check_yn char(1) NOT NULL DEFAULT 'Y';
-- 연결 방식(docs/65 3장): DIRECT = 서버가 TCP 로 밀어 보냄(설치형, 지금 W2) / AGENT = 공장 PC 에이전트가 당겨 감(E2)
ALTER TABLE wms_printer ADD COLUMN conn_mode varchar(10) NOT NULL DEFAULT 'DIRECT';
ALTER TABLE wms_printer ADD CONSTRAINT ck_wms_printer_conn_mode CHECK (conn_mode IN ('DIRECT', 'AGENT'));
ALTER TABLE wms_printer ADD CONSTRAINT ck_wms_printer_cjk CHECK (cjk_font_yn IN ('Y', 'N'));
-- 프린터 저장 장치 글꼴 파일 이름(E: 플래시·R: 메모리·B: 카드·A: 기본 플래시) — 형식만, 실제 파일 유무는 프린터 몫
ALTER TABLE wms_printer ADD CONSTRAINT ck_wms_printer_cjk_file CHECK (cjk_font_file IS NULL OR cjk_font_file ~ '^[AERB]:[A-Z0-9_-]{1,30}\.(TTF|TTE|FNT)$');
ALTER TABLE wms_printer ADD CONSTRAINT ck_wms_printer_media CHECK (media_width_mm IS NULL OR media_width_mm BETWEEN 10 AND 300);
ALTER TABLE wms_printer ADD CONSTRAINT ck_wms_printer_status_check CHECK (status_check_yn IN ('Y', 'N'));
COMMENT ON COLUMN wms_printer.cjk_font_yn IS '한글 TTF 를 프린터에 올렸나(~DY) — N 이면 한글 값 인쇄 때 경고 사건 (docs/65 18.2)';
COMMENT ON COLUMN wms_printer.media_width_mm IS '장착 용지 폭(mm, 선택) — 템플릿 폭이 더 크면 요청 거부 (docs/65 18.4)';

-- ---------------------------------------------------------------------
-- 2. 템플릿 크기 ID · 선택 규칙 (6.5)
-- ---------------------------------------------------------------------
ALTER TABLE wms_label_tpl ADD COLUMN size_cd varchar(20)
    GENERATED ALWAYS AS (trim_scale(width_mm)::text || 'x' || trim_scale(height_mm)::text) STORED;
COMMENT ON COLUMN wms_label_tpl.size_cd IS '크기 ID(폭x높이 mm) — 물리 규격과 양식을 나눠 본다 (docs/65 18.4)';

CREATE TABLE wms_label_tpl_rule (
    rule_id     bigint        GENERATED ALWAYS AS IDENTITY,
    comp_cd     varchar(20)   NOT NULL,
    label_type  varchar(10)   NOT NULL,
    scope_cd    varchar(10)   NOT NULL,
    cust_id     bigint,
    item_id     bigint,
    tpl_id      bigint        NOT NULL,
    use_yn      char(1)       NOT NULL DEFAULT 'Y',
    remark      varchar(500),
    row_ver     integer       NOT NULL DEFAULT 0,
    created_at  timestamptz   NOT NULL DEFAULT now(),
    created_by  varchar(50)   NOT NULL,
    updated_at  timestamptz   NOT NULL DEFAULT now(),
    updated_by  varchar(50)   NOT NULL,
    CONSTRAINT pk_wms_label_tpl_rule PRIMARY KEY (rule_id),
    CONSTRAINT fk_wms_label_tpl_rule_comp FOREIGN KEY (comp_cd) REFERENCES sys_comp (comp_cd),
    CONSTRAINT fk_wms_label_tpl_rule_tpl FOREIGN KEY (tpl_id) REFERENCES wms_label_tpl (tpl_id),
    CONSTRAINT fk_wms_label_tpl_rule_cust FOREIGN KEY (cust_id) REFERENCES mst_cust (cust_id),
    CONSTRAINT fk_wms_label_tpl_rule_item FOREIGN KEY (item_id) REFERENCES mst_item (item_id),
    CONSTRAINT ck_wms_label_tpl_rule_type CHECK (label_type IN ('LOC', 'ITEM', 'LOT', 'PALLET', 'SHIP')),
    CONSTRAINT ck_wms_label_tpl_rule_scope CHECK (
        (scope_cd = 'CUST' AND cust_id IS NOT NULL AND item_id IS NULL)
        OR (scope_cd = 'ITEM' AND item_id IS NOT NULL AND cust_id IS NULL)),
    CONSTRAINT ck_wms_label_tpl_rule_use_yn CHECK (use_yn IN ('Y', 'N'))
);
-- 사용 중 규칙은 (유형, 거래처|품목) 마다 하나 — 둘이면 어느 템플릿인지 모른다
CREATE UNIQUE INDEX uq_wms_label_tpl_rule ON wms_label_tpl_rule (comp_cd, label_type, scope_cd, coalesce(cust_id, 0), coalesce(item_id, 0))
    WHERE use_yn = 'Y';
CREATE INDEX ix_wms_label_tpl_rule_tpl ON wms_label_tpl_rule (comp_cd, tpl_id);
COMMENT ON TABLE wms_label_tpl_rule IS '라벨 템플릿 선택 규칙 (docs/65 18.4) — 거래처 > 품목 > 유형 기본. 삭제 = 사용 중지';

-- 템플릿·거래처·품목은 같은 회사, 템플릿 유형 = 규칙 유형. 범위·대상·유형은 고정(바꾸려면 새 규칙)
CREATE FUNCTION hb_guard_wms_label_tpl_rule() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'UPDATE' AND (NEW.label_type <> OLD.label_type OR NEW.scope_cd <> OLD.scope_cd
                             OR NEW.cust_id IS DISTINCT FROM OLD.cust_id OR NEW.item_id IS DISTINCT FROM OLD.item_id) THEN
        RAISE EXCEPTION 'label template rule target is fixed' USING ERRCODE = '55000', CONSTRAINT = 'ck_wms_label_tpl_rule_fixed';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM wms_label_tpl t WHERE t.tpl_id = NEW.tpl_id AND t.comp_cd = NEW.comp_cd
                      AND t.label_type = NEW.label_type) THEN
        RAISE EXCEPTION 'template % is not a % template of company %', NEW.tpl_id, NEW.label_type, NEW.comp_cd
            USING ERRCODE = '23503', CONSTRAINT = 'fk_wms_label_tpl_rule_tpl_comp';
    END IF;
    IF NEW.cust_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM mst_cust c WHERE c.cust_id = NEW.cust_id AND c.comp_cd = NEW.comp_cd) THEN
        RAISE EXCEPTION 'customer % is not of company %', NEW.cust_id, NEW.comp_cd
            USING ERRCODE = '23503', CONSTRAINT = 'fk_wms_label_tpl_rule_cust_comp';
    END IF;
    IF NEW.item_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM mst_item i WHERE i.item_id = NEW.item_id AND i.comp_cd = NEW.comp_cd) THEN
        RAISE EXCEPTION 'item % is not of company %', NEW.item_id, NEW.comp_cd
            USING ERRCODE = '23503', CONSTRAINT = 'fk_wms_label_tpl_rule_item_comp';
    END IF;
    RETURN NEW;
END
$$;
CREATE TRIGGER tg_wms_label_tpl_rule_guard BEFORE INSERT OR UPDATE ON wms_label_tpl_rule FOR EACH ROW EXECUTE FUNCTION hb_guard_wms_label_tpl_rule();
CREATE TRIGGER tg_wms_label_tpl_rule_no_delete BEFORE DELETE ON wms_label_tpl_rule FOR EACH ROW EXECUTE FUNCTION hb_forbid_delete_master();

-- ---------------------------------------------------------------------
-- 3. 프린터 마지막 상태 (6.1) — 실행 칸(확인 작업·에이전트가 갱신)
-- ---------------------------------------------------------------------
CREATE TABLE wms_printer_status (
    comp_cd        varchar(20)   NOT NULL,
    printer_id     bigint        NOT NULL,
    state_cd       varchar(10)   NOT NULL DEFAULT 'UNKNOWN',
    errors         varchar(200),
    warnings       varchar(200),
    odometer       bigint,
    odometer_base  bigint,
    src            varchar(10)   NOT NULL,
    detail         varchar(200),
    checked_at     timestamptz,
    changed_at     timestamptz   NOT NULL DEFAULT now(),
    CONSTRAINT pk_wms_printer_status PRIMARY KEY (printer_id),
    CONSTRAINT fk_wms_printer_status_printer FOREIGN KEY (printer_id) REFERENCES wms_printer (printer_id),
    CONSTRAINT ck_wms_printer_status_state CHECK (state_cd IN ('OK', 'WARN', 'ERROR', 'OFFLINE', 'UNKNOWN')),
    CONSTRAINT ck_wms_printer_status_src CHECK (src IN ('DIRECT', 'AGENT')),
    CONSTRAINT ck_wms_printer_status_odo CHECK ((odometer IS NULL OR odometer >= 0) AND (odometer_base IS NULL OR odometer_base >= 0))
);
CREATE INDEX ix_wms_printer_status_comp ON wms_printer_status (comp_cd, printer_id);
COMMENT ON TABLE wms_printer_status IS '프린터 마지막 상태 (docs/65 18.1) — ~HQES 오류·경고, odometer(확인 기준 odometer_base), 출처 DIRECT 확인 작업·AGENT';

CREATE FUNCTION hb_guard_wms_printer_status() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM wms_printer p WHERE p.printer_id = NEW.printer_id AND p.comp_cd = NEW.comp_cd) THEN
        RAISE EXCEPTION 'printer % is not of company %', NEW.printer_id, NEW.comp_cd
            USING ERRCODE = '23503', CONSTRAINT = 'fk_wms_printer_status_comp';
    END IF;
    RETURN NEW;
END
$$;
CREATE TRIGGER tg_wms_printer_status_guard BEFORE INSERT OR UPDATE ON wms_printer_status FOR EACH ROW EXECUTE FUNCTION hb_guard_wms_printer_status();

-- ---------------------------------------------------------------------
-- 4. 인쇄 사건 원장 (추가만, 6.1)
-- ---------------------------------------------------------------------
CREATE TABLE wms_print_job_event (
    event_id        bigint        GENERATED ALWAYS AS IDENTITY,
    comp_cd         varchar(20)   NOT NULL,
    job_id          bigint        NOT NULL,
    event_cd        varchar(15)   NOT NULL,
    reason_cd       varchar(40),
    detail          varchar(500),
    src             varchar(10)   NOT NULL,
    terminal_id     bigint,
    occurred_at     timestamptz   NOT NULL,
    recorded_at     timestamptz   NOT NULL DEFAULT now(),
    entry_method    varchar(10)   NOT NULL DEFAULT 'AUTO',
    recorded_by     varchar(50)   NOT NULL,
    performed_by    bigint,
    reversal_of_id  bigint,
    CONSTRAINT pk_wms_print_job_event PRIMARY KEY (event_id),
    CONSTRAINT fk_wms_print_job_event_job FOREIGN KEY (job_id) REFERENCES wms_print_job (job_id),
    CONSTRAINT fk_wms_print_job_event_terminal FOREIGN KEY (terminal_id) REFERENCES sys_terminal (terminal_id),
    CONSTRAINT ck_wms_print_job_event_cd CHECK (event_cd IN ('CLAIMED', 'SENT', 'PRINTED', 'VERIFIED', 'PRINTER_ERROR', 'FAILED',
        'UNKNOWN', 'CANCELLED', 'VERIFY_NG', 'WARN', 'STALE')),
    CONSTRAINT ck_wms_print_job_event_reason CHECK (reason_cd IS NULL OR reason_cd ~ '^[A-Z][A-Z0-9_:.x-]{0,39}$'),
    CONSTRAINT ck_wms_print_job_event_src CHECK (src IN ('DIRECT', 'AGENT', 'USER', 'SCAN', 'SYSTEM')),
    CONSTRAINT ck_wms_print_job_event_method CHECK (entry_method IN ('AUTO', 'SCAN', 'KEY')),
    -- 사람이 닫은 것(USER)·사람 확정은 사유가 있어야 한다
    CONSTRAINT ck_wms_print_job_event_user CHECK (src <> 'USER' OR (detail IS NOT NULL AND length(btrim(detail)) >= 2)),
    CONSTRAINT ck_wms_print_job_event_no_reversal CHECK (reversal_of_id IS NULL)
);
CREATE INDEX ix_wms_print_job_event_job ON wms_print_job_event (comp_cd, job_id, event_id);
CREATE INDEX ix_wms_print_job_event_time ON wms_print_job_event (comp_cd, occurred_at);
COMMENT ON TABLE wms_print_job_event IS '인쇄 사건 원장 (docs/65 18.1) — 작업 상태 = 상태 사건 중 마지막. VERIFY_NG·WARN·STALE 은 상태를 바꾸지 않는다';

CREATE FUNCTION hb_guard_wms_print_job_event() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM wms_print_job j WHERE j.job_id = NEW.job_id AND j.comp_cd = NEW.comp_cd)
       OR (NEW.terminal_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM sys_terminal t WHERE t.terminal_id = NEW.terminal_id
                                                          AND t.comp_cd = NEW.comp_cd)) THEN
        RAISE EXCEPTION 'print event context is not of company %', NEW.comp_cd
            USING ERRCODE = '23503', CONSTRAINT = 'fk_wms_print_job_event_comp';
    END IF;
    RETURN NEW;
END
$$;
CREATE TRIGGER tg_wms_print_job_event_guard BEFORE INSERT ON wms_print_job_event FOR EACH ROW EXECUTE FUNCTION hb_guard_wms_print_job_event();
CREATE TRIGGER tg_wms_print_job_event_ledger BEFORE UPDATE OR DELETE ON wms_print_job_event FOR EACH ROW EXECUTE FUNCTION hb_forbid_update_delete();

-- ---------------------------------------------------------------------
-- 5. 감사 · RLS · 권한 · 보존
-- ---------------------------------------------------------------------
CREATE TRIGGER tg_audit AFTER INSERT OR UPDATE OR DELETE ON wms_label_tpl_rule FOR EACH ROW EXECUTE FUNCTION hb_audit('rule_id', '', '');

ALTER TABLE wms_label_tpl_rule ENABLE ROW LEVEL SECURITY;
ALTER TABLE wms_label_tpl_rule FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON wms_label_tpl_rule USING (comp_cd = hb_current_comp()) WITH CHECK (comp_cd = hb_current_comp());
ALTER TABLE wms_printer_status ENABLE ROW LEVEL SECURITY;
ALTER TABLE wms_printer_status FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON wms_printer_status USING (comp_cd = hb_current_comp()) WITH CHECK (comp_cd = hb_current_comp());
ALTER TABLE wms_print_job_event ENABLE ROW LEVEL SECURITY;
ALTER TABLE wms_print_job_event FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON wms_print_job_event USING (comp_cd = hb_current_comp()) WITH CHECK (comp_cd = hb_current_comp());

REVOKE DELETE, TRUNCATE ON wms_label_tpl_rule, wms_printer_status FROM ${app_role};
REVOKE UPDATE, DELETE, TRUNCATE ON wms_print_job_event FROM ${app_role};

INSERT INTO sys_retention_table (table_nm, class_cd) VALUES
    ('wms_label_tpl_rule', 'MASTER'), ('wms_printer_status', 'MASTER'),
    -- 라벨 인쇄·검증 사건은 추적 근거 — 인쇄 작업과 같게
    ('wms_print_job_event', 'QUALITY_RECORD');
