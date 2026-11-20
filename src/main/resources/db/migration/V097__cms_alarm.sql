-- =====================================================================
-- CMS 2부 C8 — 설비 알람 정의·알람 원장 (docs/33 11.2 C8·11.6, 결정 D22, MES 맞춤 Q2·docs/25 4.17)
--
-- - 수집 ALARM 이벤트는 MES EquipEventService → 계약 AlarmSink(raise/clear) → 이 원장. 이 파일은 MES 표를 고치지 않는다
-- - cms_alarm_def: 회사 × 알람 코드 × 설비 클래스(없으면 모든 클래스) → 이름·심각도·현상 코드·자동 고장 신고
--   찾기는 설비 클래스가 맞는 정의 먼저, 없으면 클래스 없는 정의. 정의가 없어도 원장에는 남는다(화면 "미정의")
-- - cms_alarm_log: 발생마다 한 행(발생 횟수 = 행 수 → 반복 알람), 해제는 같은 설비·코드의 열린 행을 "한 번만" 닫는다
--   해제 시각이 발생보다 앞(장치 시계)이면 발생 시각으로 당기고 표시(clear_adjusted_yn) — 수집을 막지 않는다
-- - 자동 고장 신고(D22): 정의 auto_bm_yn = Y(심각도 CRITICAL 만) 이고 같은 설비·코드의 알람이 연 신고(REQ·OPEN·WIP)가 없을 때만
-- - 반복 알람: 정책 CMS_ALARM_REPEAT_COUNT 회 이상 / CMS_ALARM_REPEAT_HOURS 시간 안(발생 시각 기준, 조회 때 계산)
-- 임시 번호(docs/29 2.2) — 병합 때 이름만 바뀐다.
-- =====================================================================

INSERT INTO sys_policy_def (policy_key, value_type, default_value, allowed_values, min_value, max_value, desc_key) VALUES
    ('CMS_ALARM_REPEAT_COUNT', 'INT', '3',  NULL, 2, 100, 'policy.CMS_ALARM_REPEAT_COUNT'),
    ('CMS_ALARM_REPEAT_HOURS', 'INT', '24', NULL, 1, 720, 'policy.CMS_ALARM_REPEAT_HOURS');

INSERT INTO sys_code_grp (grp_cd, grp_nm_key, comp_ext_yn) VALUES ('CMS_ALARM_SEV', 'code.grp.CMS_ALARM_SEV', 'N');
INSERT INTO sys_code (grp_cd, code, code_nm_key, sort_no) VALUES
    ('CMS_ALARM_SEV', 'INFO',     'code.CMS_ALARM_SEV.INFO',     1),
    ('CMS_ALARM_SEV', 'WARN',     'code.CMS_ALARM_SEV.WARN',     2),
    ('CMS_ALARM_SEV', 'CRITICAL', 'code.CMS_ALARM_SEV.CRITICAL', 3);

-- ---------------------------------------------------------------------
-- 1. 알람 정의
-- ---------------------------------------------------------------------
CREATE TABLE cms_alarm_def (
    def_id          bigint        GENERATED ALWAYS AS IDENTITY,
    comp_cd         varchar(20)   NOT NULL,
    alarm_cd        varchar(50)   NOT NULL,
    equip_class_id  bigint,
    alarm_nm        varchar(100)  NOT NULL,
    severity        varchar(10)   NOT NULL DEFAULT 'WARN',
    symptom_id      bigint,
    auto_bm_yn      char(1)       NOT NULL DEFAULT 'N',
    use_yn          char(1)       NOT NULL DEFAULT 'Y',
    remark          varchar(500),
    row_ver         integer       NOT NULL DEFAULT 0,
    created_at      timestamptz   NOT NULL DEFAULT now(),
    created_by      varchar(50)   NOT NULL,
    updated_at      timestamptz   NOT NULL DEFAULT now(),
    updated_by      varchar(50)   NOT NULL,
    CONSTRAINT pk_cms_alarm_def PRIMARY KEY (def_id),
    CONSTRAINT fk_cms_alarm_def_comp FOREIGN KEY (comp_cd) REFERENCES sys_comp (comp_cd),
    CONSTRAINT fk_cms_alarm_def_class FOREIGN KEY (equip_class_id) REFERENCES mst_equip_class (class_id),
    CONSTRAINT fk_cms_alarm_def_symptom FOREIGN KEY (symptom_id) REFERENCES cms_fail_code (fail_id),
    CONSTRAINT uq_cms_alarm_def_cd UNIQUE NULLS NOT DISTINCT (comp_cd, alarm_cd, equip_class_id),
    -- MES 수집 정규화와 같은 모양(docs/25 4.17)
    CONSTRAINT ck_cms_alarm_def_cd CHECK (alarm_cd ~ '^[A-Z0-9][A-Z0-9_.:-]{0,49}$'),
    CONSTRAINT ck_cms_alarm_def_nm CHECK (length(btrim(alarm_nm)) > 0),
    CONSTRAINT ck_cms_alarm_def_sev CHECK (severity IN ('INFO', 'WARN', 'CRITICAL')),
    -- 자동 고장 신고는 CRITICAL 만(D22 — 반복 경고가 신고를 쏟아내지 않게)
    CONSTRAINT ck_cms_alarm_def_auto CHECK (auto_bm_yn IN ('Y', 'N') AND (auto_bm_yn = 'N' OR severity = 'CRITICAL')),
    CONSTRAINT ck_cms_alarm_def_use_yn CHECK (use_yn IN ('Y', 'N'))
);
COMMENT ON TABLE cms_alarm_def IS '설비 알람 정의 (docs/33 C8) — 회사 × 코드 × 설비 클래스(없으면 전체). 클래스가 맞는 정의가 먼저';

CREATE FUNCTION hb_guard_cms_alarm_def() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'UPDATE' AND (NEW.alarm_cd <> OLD.alarm_cd OR NEW.equip_class_id IS DISTINCT FROM OLD.equip_class_id
                             OR NEW.comp_cd <> OLD.comp_cd) THEN
        RAISE EXCEPTION 'alarm definition code and class are fixed' USING ERRCODE = '55000', CONSTRAINT = 'ck_cms_alarm_def_fixed';
    END IF;
    IF NEW.equip_class_id IS NOT NULL AND TG_OP = 'INSERT'
       AND NOT EXISTS (SELECT 1 FROM mst_equip_class c WHERE c.class_id = NEW.equip_class_id AND c.comp_cd = NEW.comp_cd AND c.use_yn = 'Y') THEN
        RAISE EXCEPTION 'equipment class % is not an active class of company %', NEW.equip_class_id, NEW.comp_cd
            USING ERRCODE = '23503', CONSTRAINT = 'fk_cms_alarm_def_class_comp';
    END IF;
    IF NEW.symptom_id IS NOT NULL AND (TG_OP = 'INSERT' OR NEW.symptom_id IS DISTINCT FROM OLD.symptom_id)
       AND NOT EXISTS (SELECT 1 FROM cms_fail_code f WHERE f.fail_id = NEW.symptom_id AND f.comp_cd = NEW.comp_cd
                          AND f.code_type = 'SYMPTOM' AND f.use_yn = 'Y') THEN
        RAISE EXCEPTION 'symptom % is not an active symptom code of company %', NEW.symptom_id, NEW.comp_cd
            USING ERRCODE = '23503', CONSTRAINT = 'fk_cms_alarm_def_symptom_comp';
    END IF;
    RETURN NEW;
END
$$;
CREATE TRIGGER tg_cms_alarm_def_guard BEFORE INSERT OR UPDATE ON cms_alarm_def FOR EACH ROW EXECUTE FUNCTION hb_guard_cms_alarm_def();
CREATE TRIGGER tg_cms_alarm_def_no_delete BEFORE DELETE ON cms_alarm_def FOR EACH ROW EXECUTE FUNCTION hb_forbid_delete_master();

-- ---------------------------------------------------------------------
-- 2. 알람 원장 — 발생마다 한 행, 해제는 한 번만
-- ---------------------------------------------------------------------
CREATE TABLE cms_alarm_log (
    alarm_id            bigint        GENERATED ALWAYS AS IDENTITY,
    comp_cd             varchar(20)   NOT NULL,
    equip_id            bigint        NOT NULL,
    alarm_cd            varchar(50)   NOT NULL,
    alarm_text          varchar(200),
    raised_at           timestamptz   NOT NULL,
    cleared_at          timestamptz,
    clear_adjusted_yn   char(1)       NOT NULL DEFAULT 'N',
    terminal_id         bigint,
    raise_event_id      varchar(100),
    clear_event_id      varchar(100),
    mwo_id              bigint,
    recorded_at         timestamptz   NOT NULL DEFAULT now(),
    cleared_recorded_at timestamptz,
    recorded_by         varchar(50)   NOT NULL,
    CONSTRAINT pk_cms_alarm_log PRIMARY KEY (alarm_id),
    CONSTRAINT fk_cms_alarm_log_equip FOREIGN KEY (equip_id) REFERENCES mst_equip (equip_id),
    CONSTRAINT fk_cms_alarm_log_terminal FOREIGN KEY (terminal_id) REFERENCES sys_terminal (terminal_id),
    CONSTRAINT fk_cms_alarm_log_mwo FOREIGN KEY (mwo_id) REFERENCES cms_mwo (mwo_id),
    CONSTRAINT ck_cms_alarm_log_cd CHECK (alarm_cd ~ '^[A-Z0-9][A-Z0-9_.:-]{0,49}$'),
    CONSTRAINT ck_cms_alarm_log_range CHECK (cleared_at IS NULL OR cleared_at >= raised_at),
    CONSTRAINT ck_cms_alarm_log_clear CHECK (clear_adjusted_yn IN ('Y', 'N')
        AND (cleared_at IS NOT NULL OR (clear_adjusted_yn = 'N' AND clear_event_id IS NULL AND cleared_recorded_at IS NULL))
        AND (cleared_at IS NULL OR cleared_recorded_at IS NOT NULL))
);
CREATE INDEX ix_cms_alarm_log_equip ON cms_alarm_log (comp_cd, equip_id, alarm_cd, raised_at);
CREATE INDEX ix_cms_alarm_log_time ON cms_alarm_log (comp_cd, raised_at);
CREATE INDEX ix_cms_alarm_log_open ON cms_alarm_log (comp_cd, equip_id, alarm_cd) WHERE cleared_at IS NULL;
COMMENT ON TABLE cms_alarm_log IS '설비 알람 원장 (docs/33 C8) — 발생마다 한 행(반복 판정), 해제는 열린 행을 한 번만. 정의는 조회 때 붙인다';
COMMENT ON COLUMN cms_alarm_log.clear_adjusted_yn IS 'Y = 장치가 보낸 해제 시각이 발생보다 앞이라 발생 시각으로 당김';

CREATE FUNCTION hb_guard_cms_alarm_log() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'alarm log cannot be deleted' USING ERRCODE = '55000', CONSTRAINT = 'ck_cms_alarm_log_ledger';
    END IF;
    IF TG_OP = 'UPDATE' THEN
        -- 한 번만 해제: cleared_at null → 값, 해제 칸 넷만
        IF OLD.cleared_at IS NOT NULL OR NEW.cleared_at IS NULL
           OR (to_jsonb(NEW) - 'cleared_at' - 'clear_adjusted_yn' - 'clear_event_id' - 'cleared_recorded_at')
              IS DISTINCT FROM (to_jsonb(OLD) - 'cleared_at' - 'clear_adjusted_yn' - 'clear_event_id' - 'cleared_recorded_at') THEN
            RAISE EXCEPTION 'alarm % can only be cleared once', OLD.alarm_id USING ERRCODE = '55000', CONSTRAINT = 'ck_cms_alarm_log_clear_once';
        END IF;
        RETURN NEW;
    END IF;
    IF NEW.cleared_at IS NOT NULL THEN
        RAISE EXCEPTION 'new alarm must be open' USING ERRCODE = '55000', CONSTRAINT = 'ck_cms_alarm_log_ledger';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM mst_equip e WHERE e.equip_id = NEW.equip_id AND e.comp_cd = NEW.comp_cd)
       OR (NEW.terminal_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM sys_terminal t WHERE t.terminal_id = NEW.terminal_id
                                                          AND t.comp_cd = NEW.comp_cd))
       OR (NEW.mwo_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM cms_mwo m WHERE m.mwo_id = NEW.mwo_id AND m.comp_cd = NEW.comp_cd
                                                     AND m.mwo_type = 'BM' AND m.equip_id = NEW.equip_id)) THEN
        RAISE EXCEPTION 'alarm context is not of company %', NEW.comp_cd USING ERRCODE = '23503', CONSTRAINT = 'fk_cms_alarm_log_ctx_comp';
    END IF;
    RETURN NEW;
END
$$;
CREATE TRIGGER tg_cms_alarm_log_guard BEFORE INSERT OR UPDATE OR DELETE ON cms_alarm_log FOR EACH ROW EXECUTE FUNCTION hb_guard_cms_alarm_log();

CREATE TRIGGER tg_audit AFTER INSERT OR UPDATE OR DELETE ON cms_alarm_def FOR EACH ROW EXECUTE FUNCTION hb_audit('def_id', '', '');

ALTER TABLE cms_alarm_def ENABLE ROW LEVEL SECURITY;
ALTER TABLE cms_alarm_def FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON cms_alarm_def USING (comp_cd = hb_current_comp()) WITH CHECK (comp_cd = hb_current_comp());
ALTER TABLE cms_alarm_log ENABLE ROW LEVEL SECURITY;
ALTER TABLE cms_alarm_log FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON cms_alarm_log USING (comp_cd = hb_current_comp()) WITH CHECK (comp_cd = hb_current_comp());
REVOKE DELETE, TRUNCATE ON cms_alarm_def FROM ${app_role};
-- 원장: 표 UPDATE 권한 없음, 해제 칸만 열 단위(MES 상태 구간 원장과 같은 방식)
REVOKE UPDATE, DELETE, TRUNCATE ON cms_alarm_log FROM ${app_role};
GRANT UPDATE (cleared_at, clear_adjusted_yn, clear_event_id, cleared_recorded_at) ON cms_alarm_log TO ${app_role};

INSERT INTO sys_prog (prog_cd, prog_nm_key, view_path, module_cd, actions, sort_no) VALUES
    ('cms-alarm',     'prog.cmsAlarm',    'cms/alarm-log', 'CMS', ARRAY['SEARCH']::varchar(20)[], 620),
    ('cms-alarm-def', 'prog.cmsAlarmDef', 'cms/alarm-def', 'CMS', ARRAY['SEARCH','SAVE']::varchar(20)[], 630);
INSERT INTO sys_retention_table (table_nm, class_cd) VALUES
    ('cms_alarm_def', 'MASTER'), ('cms_alarm_log', 'PRODUCTION_RECORD');
