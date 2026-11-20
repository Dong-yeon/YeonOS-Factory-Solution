-- =====================================================================
-- 설비 상태·연결·Collector (P2 6단계 — docs/25 4.8, 02 2.2)
-- 임시 번호(MES 대역, docs/29 2.2) — 병합 때 상위 세션이 다음 빈 번호로 이름만 바꾼다
--
-- - 수집 장치 = sys_terminal 의 유형 COLLECTOR(API 키, 5a 재사용)
-- - 상태 분류: 전역 공통코드 STATE_CAT(SEMI E10 6 + UNKNOWN) — 표 대신 코드 + CHECK
-- - cms_state_reason(회사 사유) / cms_equip_state_log(구간 원장, 겹침 배제, "한 번만 닫기")
-- - cms_equip_conn_log(연결·누락 원장) / cms_collector_seq(장치별 마지막 seq) / cms_energy_reading(구간 사용량 원장)
-- =====================================================================

-- ---------------------------------------------------------------------
-- 1. 수집 장치 = 단말 유형 COLLECTOR
-- ---------------------------------------------------------------------
ALTER TABLE sys_terminal DROP CONSTRAINT ck_sys_terminal_type;
ALTER TABLE sys_terminal ADD CONSTRAINT ck_sys_terminal_type CHECK (terminal_type IN ('POP', 'PDA', 'COLLECTOR'));
-- 수집 장치는 등록 코드를 쓰지 않는다(키를 바로 준다)
ALTER TABLE sys_terminal ADD CONSTRAINT ck_sys_terminal_collector CHECK (terminal_type <> 'COLLECTOR' OR activation_hash IS NULL);
INSERT INTO sys_code (grp_cd, code, code_nm_key, sort_no) VALUES ('TERMINAL_TYPE', 'COLLECTOR', 'code.TERMINAL_TYPE.COLLECTOR', 3);
-- 수집 장치(Bearer API 키)와 POP·PDA(쿠키 키)는 서로 바꾸지 않는다 — 바꾸면 쿠키 키가 Bearer 로, Bearer 키가 쿠키로 쓰인다
CREATE FUNCTION hb_guard_terminal_type() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF (OLD.terminal_type = 'COLLECTOR') IS DISTINCT FROM (NEW.terminal_type = 'COLLECTOR') THEN
        RAISE EXCEPTION 'terminal % cannot change between collector and POP/PDA', OLD.terminal_id
            USING ERRCODE = '55000', CONSTRAINT = 'ck_sys_terminal_collector_fixed';
    END IF;
    RETURN NEW;
END
$$;
CREATE TRIGGER tg_sys_terminal_type_guard BEFORE UPDATE OF terminal_type ON sys_terminal FOR EACH ROW EXECUTE FUNCTION hb_guard_terminal_type();

-- ---------------------------------------------------------------------
-- 2. 상태 분류·사유
-- ---------------------------------------------------------------------
INSERT INTO sys_code_grp (grp_cd, grp_nm_key, comp_ext_yn) VALUES ('STATE_CAT', 'code.grp.STATE_CAT', 'N');
INSERT INTO sys_code (grp_cd, code, code_nm_key, sort_no) VALUES
    ('STATE_CAT', 'PRD',     'code.STATE_CAT.PRD', 1),
    ('STATE_CAT', 'SBY',     'code.STATE_CAT.SBY', 2),
    ('STATE_CAT', 'ENG',     'code.STATE_CAT.ENG', 3),
    ('STATE_CAT', 'SDT',     'code.STATE_CAT.SDT', 4),
    ('STATE_CAT', 'UDT',     'code.STATE_CAT.UDT', 5),
    ('STATE_CAT', 'NST',     'code.STATE_CAT.NST', 6),
    ('STATE_CAT', 'UNKNOWN', 'code.STATE_CAT.UNKNOWN', 9);

INSERT INTO sys_code_grp (grp_cd, grp_nm_key, comp_ext_yn) VALUES ('CONN_EVENT', 'code.grp.CONN_EVENT', 'N');
INSERT INTO sys_code (grp_cd, code, code_nm_key, sort_no) VALUES
    ('CONN_EVENT', 'ONLINE',  'code.CONN_EVENT.ONLINE', 1),
    ('CONN_EVENT', 'OFFLINE', 'code.CONN_EVENT.OFFLINE', 2),
    ('CONN_EVENT', 'GAP',     'code.CONN_EVENT.GAP', 3);

CREATE TABLE cms_state_reason (
    reason_id   bigint        GENERATED ALWAYS AS IDENTITY,
    comp_cd     varchar(20)   NOT NULL,
    reason_cd   varchar(30)   NOT NULL,
    reason_nm   varchar(100)  NOT NULL,
    state_cat   varchar(10)   NOT NULL,
    planned_yn  char(1)       NOT NULL DEFAULT 'N',
    sort_no     integer       NOT NULL DEFAULT 0,
    use_yn      char(1)       NOT NULL DEFAULT 'Y',
    row_ver     integer       NOT NULL DEFAULT 0,
    created_at  timestamptz   NOT NULL DEFAULT now(),
    created_by  varchar(50)   NOT NULL,
    updated_at  timestamptz   NOT NULL DEFAULT now(),
    updated_by  varchar(50)   NOT NULL,
    CONSTRAINT pk_cms_state_reason PRIMARY KEY (reason_id),
    CONSTRAINT fk_cms_state_reason_comp FOREIGN KEY (comp_cd) REFERENCES sys_comp (comp_cd),
    CONSTRAINT uq_cms_state_reason_cd UNIQUE (comp_cd, reason_cd),
    CONSTRAINT ck_cms_state_reason_cd CHECK (reason_cd ~ '^[A-Z0-9][A-Z0-9_-]{0,29}$'),
    CONSTRAINT ck_cms_state_reason_nm CHECK (length(btrim(reason_nm)) > 0),
    CONSTRAINT ck_cms_state_reason_cat CHECK (state_cat IN ('PRD', 'SBY', 'ENG', 'SDT', 'UDT', 'NST')),
    CONSTRAINT ck_cms_state_reason_planned CHECK (planned_yn IN ('Y', 'N')),
    CONSTRAINT ck_cms_state_reason_use_yn CHECK (use_yn IN ('Y', 'N'))
);
COMMENT ON TABLE cms_state_reason IS '설비 상태 사유 (회사, docs/25 4.8) — 사유가 상태 분류(SEMI E10)와 계획 여부를 정한다';
CREATE TRIGGER tg_cms_state_reason_no_delete BEFORE DELETE ON cms_state_reason FOR EACH ROW EXECUTE FUNCTION hb_forbid_delete_master();
CREATE TRIGGER tg_audit AFTER INSERT OR UPDATE OR DELETE ON cms_state_reason FOR EACH ROW EXECUTE FUNCTION hb_audit('reason_id', '', '');

-- ---------------------------------------------------------------------
-- 3. 상태 구간 원장 — 겹침 배제, 설비당 열린 구간 하나, 한 번만 닫기
-- ---------------------------------------------------------------------
CREATE TABLE cms_equip_state_log (
    state_log_id    bigint        GENERATED ALWAYS AS IDENTITY,
    comp_cd         varchar(20)   NOT NULL,
    equip_id        bigint        NOT NULL,
    state_cat       varchar(10)   NOT NULL,
    reason_id       bigint,
    start_at        timestamptz   NOT NULL,
    end_at          timestamptz,
    wo_id           bigint,
    source          varchar(10)   NOT NULL,
    terminal_id     bigint,
    event_id        varchar(100),
    occurred_at     timestamptz   NOT NULL,
    recorded_at     timestamptz   NOT NULL DEFAULT now(),
    entry_method    varchar(10)   NOT NULL,
    recorded_by     varchar(50)   NOT NULL,
    performed_by    bigint,
    reversal_of_id  bigint,
    CONSTRAINT pk_cms_equip_state_log PRIMARY KEY (state_log_id),
    CONSTRAINT fk_cms_equip_state_log_equip FOREIGN KEY (equip_id) REFERENCES mst_equip (equip_id),
    CONSTRAINT fk_cms_equip_state_log_reason FOREIGN KEY (reason_id) REFERENCES cms_state_reason (reason_id),
    CONSTRAINT fk_cms_equip_state_log_wo FOREIGN KEY (wo_id) REFERENCES prd_wo (wo_id),
    CONSTRAINT fk_cms_equip_state_log_terminal FOREIGN KEY (terminal_id) REFERENCES sys_terminal (terminal_id),
    CONSTRAINT fk_cms_equip_state_log_worker FOREIGN KEY (performed_by) REFERENCES mst_worker (worker_id),
    CONSTRAINT ck_cms_equip_state_log_cat CHECK (state_cat IN ('PRD', 'SBY', 'ENG', 'SDT', 'UDT', 'NST', 'UNKNOWN')),
    CONSTRAINT ck_cms_equip_state_log_range CHECK (end_at IS NULL OR end_at > start_at),
    CONSTRAINT ck_cms_equip_state_log_source CHECK (source IN ('AUTO', 'MANUAL')),
    CONSTRAINT ck_cms_equip_state_log_method CHECK (entry_method IN ('AUTO', 'KEY')),
    CONSTRAINT ck_cms_equip_state_log_unknown CHECK (state_cat <> 'UNKNOWN' OR reason_id IS NULL),
    -- 구간은 정정하지 않는다(다음 구간으로) — 원장 칸 규약만
    CONSTRAINT ck_cms_equip_state_log_no_reversal CHECK (reversal_of_id IS NULL),
    CONSTRAINT ex_cms_equip_state_log_overlap EXCLUDE USING gist
        (comp_cd WITH =, equip_id WITH =, tstzrange(start_at, coalesce(end_at, 'infinity'::timestamptz), '[)') WITH &&)
);
COMMENT ON TABLE cms_equip_state_log IS '설비 상태 구간 원장 (docs/25 4.8, D8) — 겹침 없음, 열린 구간(end_at null)은 한 번만 닫는다. UNKNOWN = 통신 끊김';
CREATE INDEX ix_cms_equip_state_log_equip ON cms_equip_state_log (comp_cd, equip_id, start_at);

CREATE FUNCTION hb_guard_equip_state() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'UPDATE' THEN
        -- 한 번만 닫기: end_at null → 값, 다른 칸은 그대로
        IF OLD.end_at IS NOT NULL OR NEW.end_at IS NULL
           OR (to_jsonb(NEW) - 'end_at') IS DISTINCT FROM (to_jsonb(OLD) - 'end_at') THEN
            RAISE EXCEPTION 'state interval % can only be closed once', OLD.state_log_id
                USING ERRCODE = '55000', CONSTRAINT = 'ck_cms_equip_state_log_close_once';
        END IF;
        RETURN NEW;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM mst_equip e WHERE e.equip_id = NEW.equip_id AND e.comp_cd = NEW.comp_cd)
       OR (NEW.reason_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM cms_state_reason r WHERE r.reason_id = NEW.reason_id
                                                        AND r.comp_cd = NEW.comp_cd AND r.state_cat = NEW.state_cat))
       OR (NEW.wo_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM prd_wo w WHERE w.wo_id = NEW.wo_id AND w.comp_cd = NEW.comp_cd))
       OR (NEW.terminal_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM sys_terminal t WHERE t.terminal_id = NEW.terminal_id
                                                          AND t.comp_cd = NEW.comp_cd))
       OR (NEW.performed_by IS NOT NULL AND NOT EXISTS (SELECT 1 FROM mst_worker k WHERE k.worker_id = NEW.performed_by
                                                           AND k.comp_cd = NEW.comp_cd)) THEN
        RAISE EXCEPTION 'state interval context is not of company %', NEW.comp_cd
            USING ERRCODE = '23503', CONSTRAINT = 'fk_cms_equip_state_log_ctx_comp';
    END IF;
    RETURN NEW;
END
$$;
CREATE TRIGGER tg_cms_equip_state_log_guard BEFORE INSERT OR UPDATE ON cms_equip_state_log FOR EACH ROW EXECUTE FUNCTION hb_guard_equip_state();
CREATE TRIGGER tg_cms_equip_state_log_ledger BEFORE DELETE ON cms_equip_state_log FOR EACH ROW EXECUTE FUNCTION hb_forbid_update_delete();

-- ---------------------------------------------------------------------
-- 4. 연결·누락 원장 + 장치별 마지막 seq
-- ---------------------------------------------------------------------
CREATE TABLE cms_equip_conn_log (
    conn_log_id     bigint        GENERATED ALWAYS AS IDENTITY,
    comp_cd         varchar(20)   NOT NULL,
    terminal_id     bigint        NOT NULL,
    equip_id        bigint,
    conn            varchar(10)   NOT NULL,
    seq_from        bigint,
    seq_to          bigint,
    event_id        varchar(100),
    occurred_at     timestamptz   NOT NULL,
    recorded_at     timestamptz   NOT NULL DEFAULT now(),
    entry_method    varchar(10)   NOT NULL DEFAULT 'AUTO',
    recorded_by     varchar(50)   NOT NULL,
    performed_by    bigint,
    reversal_of_id  bigint,
    CONSTRAINT pk_cms_equip_conn_log PRIMARY KEY (conn_log_id),
    CONSTRAINT fk_cms_equip_conn_log_terminal FOREIGN KEY (terminal_id) REFERENCES sys_terminal (terminal_id),
    CONSTRAINT fk_cms_equip_conn_log_equip FOREIGN KEY (equip_id) REFERENCES mst_equip (equip_id),
    CONSTRAINT ck_cms_equip_conn_log_conn CHECK (conn IN ('ONLINE', 'OFFLINE', 'GAP')),
    -- GAP 은 장치 단위(설비 없음)·누락 범위 필수, 나머지는 설비 필수
    CONSTRAINT ck_cms_equip_conn_log_gap CHECK (
        (conn = 'GAP' AND seq_from IS NOT NULL AND seq_to >= seq_from AND equip_id IS NULL)
        OR (conn <> 'GAP' AND seq_from IS NULL AND seq_to IS NULL AND equip_id IS NOT NULL)),
    CONSTRAINT ck_cms_equip_conn_log_method CHECK (entry_method = 'AUTO'),
    CONSTRAINT ck_cms_equip_conn_log_no_reversal CHECK (reversal_of_id IS NULL AND performed_by IS NULL)
);
COMMENT ON TABLE cms_equip_conn_log IS '설비 연결·수집 누락 원장 (docs/25 4.8) — 끊김은 비가동이 아니다(상태 구간 UNKNOWN)';
CREATE INDEX ix_cms_equip_conn_log_time ON cms_equip_conn_log (comp_cd, occurred_at);

CREATE FUNCTION hb_guard_conn_log() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM sys_terminal t WHERE t.terminal_id = NEW.terminal_id AND t.comp_cd = NEW.comp_cd)
       OR (NEW.equip_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM mst_equip e WHERE e.equip_id = NEW.equip_id AND e.comp_cd = NEW.comp_cd)) THEN
        RAISE EXCEPTION 'connection log context is not of company %', NEW.comp_cd
            USING ERRCODE = '23503', CONSTRAINT = 'fk_cms_equip_conn_log_ctx_comp';
    END IF;
    RETURN NEW;
END
$$;
CREATE TRIGGER tg_cms_equip_conn_log_guard BEFORE INSERT ON cms_equip_conn_log FOR EACH ROW EXECUTE FUNCTION hb_guard_conn_log();
CREATE TRIGGER tg_cms_equip_conn_log_ledger BEFORE UPDATE OR DELETE ON cms_equip_conn_log FOR EACH ROW EXECUTE FUNCTION hb_forbid_update_delete();

CREATE TABLE cms_collector_seq (
    comp_cd      varchar(20)   NOT NULL,
    terminal_id  bigint        NOT NULL,
    last_seq     bigint        NOT NULL,
    updated_at   timestamptz   NOT NULL DEFAULT now(),
    CONSTRAINT pk_cms_collector_seq PRIMARY KEY (comp_cd, terminal_id),
    CONSTRAINT fk_cms_collector_seq_terminal FOREIGN KEY (terminal_id) REFERENCES sys_terminal (terminal_id),
    CONSTRAINT ck_cms_collector_seq CHECK (last_seq >= 0)
);
COMMENT ON TABLE cms_collector_seq IS '수집 장치별 마지막 seq (누락 감지용 상태 — 원장 아님, 장치 행 잠금으로 순서 보장)';

-- ---------------------------------------------------------------------
-- 5. 에너지 계측 구간 사용량 원장 (08 D2)
-- ---------------------------------------------------------------------
CREATE TABLE cms_energy_reading (
    reading_id      bigint         GENERATED ALWAYS AS IDENTITY,
    comp_cd         varchar(20)    NOT NULL,
    plant_cd        varchar(20)    NOT NULL,
    meter_cd        varchar(30)    NOT NULL,
    period_start    timestamptz    NOT NULL,
    period_end      timestamptz    NOT NULL,
    qty             numeric(18,6)  NOT NULL,
    unit_cd         varchar(30)    NOT NULL,
    reading_src     varchar(10)    NOT NULL,
    terminal_id     bigint,
    event_id        varchar(100),
    reason          varchar(500),
    occurred_at     timestamptz    NOT NULL,
    recorded_at     timestamptz    NOT NULL DEFAULT now(),
    entry_method    varchar(10)    NOT NULL,
    recorded_by     varchar(50)    NOT NULL,
    performed_by    bigint,
    reversal_of_id  bigint,
    CONSTRAINT pk_cms_energy_reading PRIMARY KEY (reading_id),
    CONSTRAINT fk_cms_energy_reading_plant FOREIGN KEY (comp_cd, plant_cd) REFERENCES sys_plant (comp_cd, plant_cd),
    CONSTRAINT fk_cms_energy_reading_terminal FOREIGN KEY (terminal_id) REFERENCES sys_terminal (terminal_id),
    CONSTRAINT fk_cms_energy_reading_reversal FOREIGN KEY (reversal_of_id) REFERENCES cms_energy_reading (reading_id),
    CONSTRAINT ck_cms_energy_reading_meter CHECK (meter_cd ~ '^[A-Z0-9][A-Z0-9_.-]{0,29}$'),
    CONSTRAINT ck_cms_energy_reading_period CHECK (period_end > period_start),
    CONSTRAINT ck_cms_energy_reading_src CHECK (reading_src IN ('COLLECTOR', 'MANUAL', 'BILL')),
    CONSTRAINT ck_cms_energy_reading_method CHECK (entry_method IN ('AUTO', 'KEY', 'IMPORT', 'CORRECT')),
    CONSTRAINT ck_cms_energy_reading_qty CHECK (
        (reversal_of_id IS NULL AND entry_method <> 'CORRECT' AND qty > 0)
        OR (reversal_of_id IS NOT NULL AND entry_method = 'CORRECT' AND qty < 0 AND coalesce(length(btrim(reason)), 0) > 0))
);
COMMENT ON TABLE cms_energy_reading IS '에너지 계측 구간 사용량 원장 (08 D2, docs/25 4.8) — 같은 계측기 구간 겹침 없음(역분개 행 제외)';
-- 겹침은 일반 행끼리만(역분개 행은 원래 구간과 같은 구간). 역분개된 구간에 다시 넣는 것은 7단계 이후 필요하면 정정 흐름으로
CREATE UNIQUE INDEX uq_cms_energy_reading_reversal ON cms_energy_reading (reversal_of_id) WHERE reversal_of_id IS NOT NULL;
ALTER TABLE cms_energy_reading ADD CONSTRAINT ex_cms_energy_reading_overlap EXCLUDE USING gist
    (comp_cd WITH =, meter_cd WITH =, tstzrange(period_start, period_end, '[)') WITH &&) WHERE (reversal_of_id IS NULL);
CREATE TRIGGER tg_cms_energy_reading_ledger BEFORE UPDATE OR DELETE ON cms_energy_reading FOR EACH ROW EXECUTE FUNCTION hb_forbid_update_delete();

-- ---------------------------------------------------------------------
-- 6. (발견·수정) 역분개 사유 필수가 NULL 사유를 통과시키던 것 — 4·5b 단계 원장 3종
--    `... AND length(btrim(reason)) > 0` 은 reason 이 NULL 이면 NULL → CHECK 는 NULL 을 통과로 본다.
--    적용된 V 파일은 고치지 않으므로 같은 뜻의 제약을 하나 더 건다(서비스는 늘 사유를 넣어 기존 행은 맞다)
-- ---------------------------------------------------------------------
ALTER TABLE lot_genealogy ADD CONSTRAINT ck_lot_genealogy_reversal_reason
    CHECK (reversal_of_id IS NULL OR coalesce(length(btrim(reason)), 0) > 0);
ALTER TABLE prd_result ADD CONSTRAINT ck_prd_result_reversal_reason
    CHECK (reversal_of_id IS NULL OR coalesce(length(btrim(reason)), 0) > 0);
ALTER TABLE prd_param_actual ADD CONSTRAINT ck_prd_param_actual_reversal_reason
    CHECK (reversal_of_id IS NULL OR coalesce(length(btrim(reason)), 0) > 0);

-- ---------------------------------------------------------------------
-- 7. RLS · 권한 · 화면 · 보존
-- ---------------------------------------------------------------------
ALTER TABLE cms_state_reason ENABLE ROW LEVEL SECURITY;
ALTER TABLE cms_state_reason FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON cms_state_reason USING (comp_cd = hb_current_comp()) WITH CHECK (comp_cd = hb_current_comp());
ALTER TABLE cms_equip_state_log ENABLE ROW LEVEL SECURITY;
ALTER TABLE cms_equip_state_log FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON cms_equip_state_log USING (comp_cd = hb_current_comp()) WITH CHECK (comp_cd = hb_current_comp());
ALTER TABLE cms_equip_conn_log ENABLE ROW LEVEL SECURITY;
ALTER TABLE cms_equip_conn_log FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON cms_equip_conn_log USING (comp_cd = hb_current_comp()) WITH CHECK (comp_cd = hb_current_comp());
ALTER TABLE cms_collector_seq ENABLE ROW LEVEL SECURITY;
ALTER TABLE cms_collector_seq FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON cms_collector_seq USING (comp_cd = hb_current_comp()) WITH CHECK (comp_cd = hb_current_comp());
ALTER TABLE cms_energy_reading ENABLE ROW LEVEL SECURITY;
ALTER TABLE cms_energy_reading FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON cms_energy_reading USING (comp_cd = hb_current_comp()) WITH CHECK (comp_cd = hb_current_comp());

REVOKE DELETE, TRUNCATE ON cms_state_reason, cms_collector_seq FROM ${app_role};
REVOKE UPDATE, DELETE, TRUNCATE ON cms_equip_state_log, cms_equip_conn_log, cms_energy_reading FROM ${app_role};
-- 구간 닫기만: 열 단위 UPDATE 권한(표 권한은 없음 — 카탈로그 원장 검사 통과), 나머지는 트리거가 막는다
GRANT UPDATE (end_at) ON cms_equip_state_log TO ${app_role};

INSERT INTO sys_prog (prog_cd, prog_nm_key, view_path, module_cd, actions, sort_no) VALUES
    ('cms-state', 'prog.cmsState', 'cms/state-timeline', 'PRD', ARRAY['SEARCH']::varchar(20)[], 440);
INSERT INTO sys_retention_table (table_nm, class_cd) VALUES
    ('cms_state_reason', 'MASTER'), ('cms_collector_seq', 'MASTER'),
    ('cms_equip_state_log', 'PRODUCTION_RECORD'), ('cms_equip_conn_log', 'PRODUCTION_RECORD'),
    ('cms_energy_reading', 'PRODUCTION_RECORD');
