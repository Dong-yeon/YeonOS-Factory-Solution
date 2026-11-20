-- =====================================================================
-- CMS 2부 C7 — 상태 사유 관리·비가동 사유 검토 (docs/33 11.2 C7, 결정 D18·D24, MES 맞춤 Q1)
--
-- - MES 원장(cms_equip_state_log·cms_state_reason)은 고치지 않는다(D18). 이 파일은 그 표에 칸·트리거·권한을 더하지 않는다
-- - cms_state_note: 상태 구간 × 사후 사유(원장 사유가 비었거나 틀린 구간에 사람이 단 사유). 추가 + 무효만, 같은 구간 유효 하나
--   닫힌 구간만, UNKNOWN(통신 끊김) 구간 제외. 분류는 같은 분류로만 — 예외: 비계획 정지(UDT) ↔ 계획 정지(SDT) 재분류
-- - cms_mwo_down: 고장 작업지시(BM) × 비계획 정지 구간. 추가 + 무효만, 같은 구간 유효 하나(작업지시 하나에 구간 여럿)
--   실효 분류(사후 사유가 있으면 그 분류) = UDT 인 구간만, 같은 설비만, 취소된 작업지시 제외
-- - 같은 구간에 대한 지정·연결은 구간 id 기준 advisory 잠금으로 줄 세운다(MES 원장 행은 잠그지 않는다 — 구간 닫기를 막지 않게)
-- 임시 번호(docs/29 2.2) — 병합 때 이름만 바뀐다.
-- =====================================================================

-- ---------------------------------------------------------------------
-- 1. 사후 사유
-- ---------------------------------------------------------------------
CREATE TABLE cms_state_note (
    note_id       bigint        GENERATED ALWAYS AS IDENTITY,
    comp_cd       varchar(20)   NOT NULL,
    state_log_id  bigint        NOT NULL,
    reason_id     bigint        NOT NULL,
    note_text     varchar(500),
    recorded_at   timestamptz   NOT NULL DEFAULT now(),
    recorded_by   varchar(50)   NOT NULL,
    void_yn       char(1)       NOT NULL DEFAULT 'N',
    void_reason   varchar(500),
    voided_by     varchar(50),
    voided_at     timestamptz,
    CONSTRAINT pk_cms_state_note PRIMARY KEY (note_id),
    CONSTRAINT fk_cms_state_note_log FOREIGN KEY (state_log_id) REFERENCES cms_equip_state_log (state_log_id),
    CONSTRAINT fk_cms_state_note_reason FOREIGN KEY (reason_id) REFERENCES cms_state_reason (reason_id),
    CONSTRAINT ck_cms_state_note_text CHECK (note_text IS NULL OR length(btrim(note_text)) > 0),
    CONSTRAINT ck_cms_state_note_void CHECK (void_yn IN ('Y', 'N')
        AND (void_yn = 'N') = (void_reason IS NULL AND voided_by IS NULL AND voided_at IS NULL)
        AND (void_reason IS NULL OR length(btrim(void_reason)) > 0))
);
CREATE UNIQUE INDEX uq_cms_state_note_live ON cms_state_note (comp_cd, state_log_id) WHERE void_yn = 'N';
CREATE INDEX ix_cms_state_note_log ON cms_state_note (comp_cd, state_log_id, note_id);
COMMENT ON TABLE cms_state_note IS '상태 구간 사후 사유 (docs/33 C7, D18) — MES 원장을 고치지 않는 덧붙임. 추가 + 무효만, 구간당 유효 하나. KPI 는 원장 사유보다 이것을 먼저';

CREATE FUNCTION hb_guard_cms_state_note() RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    log_cat   varchar(10);
    log_end   timestamptz;
    new_cat   varchar(10);
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'state note cannot be deleted (void it)' USING ERRCODE = '55000', CONSTRAINT = 'ck_cms_state_note_ledger';
    END IF;
    IF TG_OP = 'UPDATE' THEN
        -- 무효 한 가지뿐
        IF NEW.comp_cd <> OLD.comp_cd OR NEW.state_log_id <> OLD.state_log_id OR NEW.reason_id <> OLD.reason_id
           OR NEW.note_text IS DISTINCT FROM OLD.note_text OR NEW.recorded_at <> OLD.recorded_at OR NEW.recorded_by <> OLD.recorded_by
           OR OLD.void_yn = 'Y' OR NEW.void_yn <> 'Y' THEN
            RAISE EXCEPTION 'state note % is a ledger row (only void)', OLD.note_id
                USING ERRCODE = '55000', CONSTRAINT = 'ck_cms_state_note_ledger';
        END IF;
        PERFORM pg_advisory_xact_lock(hashtextextended('cms_state:' || OLD.comp_cd || ':' || OLD.state_log_id, 0));
        -- 재분류(SDT → UDT 반대 포함)를 무효로 하면 원장 분류로 돌아간다 — 그때 연결된 작업지시가 남으면 실효 분류가 어긋난다
        SELECT s.state_cat INTO log_cat FROM cms_equip_state_log s WHERE s.state_log_id = OLD.state_log_id;
        IF log_cat <> 'UDT' AND EXISTS (SELECT 1 FROM cms_mwo_down d WHERE d.state_log_id = OLD.state_log_id AND d.void_yn = 'N') THEN
            RAISE EXCEPTION 'unlink the work order first' USING ERRCODE = '23514', CONSTRAINT = 'ck_cms_state_note_linked';
        END IF;
        RETURN NEW;
    END IF;
    IF NEW.void_yn <> 'N' THEN
        RAISE EXCEPTION 'new state note must not be void' USING ERRCODE = '55000', CONSTRAINT = 'ck_cms_state_note_ledger';
    END IF;
    PERFORM pg_advisory_xact_lock(hashtextextended('cms_state:' || NEW.comp_cd || ':' || NEW.state_log_id, 0));
    SELECT s.state_cat, s.end_at INTO log_cat, log_end FROM cms_equip_state_log s
     WHERE s.state_log_id = NEW.state_log_id AND s.comp_cd = NEW.comp_cd;
    SELECT r.state_cat INTO new_cat FROM cms_state_reason r WHERE r.reason_id = NEW.reason_id AND r.comp_cd = NEW.comp_cd AND r.use_yn = 'Y';
    IF log_cat IS NULL OR new_cat IS NULL THEN
        RAISE EXCEPTION 'state note context is not of company %', NEW.comp_cd
            USING ERRCODE = '23503', CONSTRAINT = 'fk_cms_state_note_ctx_comp';
    END IF;
    IF log_end IS NULL THEN
        RAISE EXCEPTION 'interval % is still open', NEW.state_log_id USING ERRCODE = '23514', CONSTRAINT = 'ck_cms_state_note_closed';
    END IF;
    IF log_cat = 'UNKNOWN'
       OR NOT (new_cat = log_cat OR (new_cat IN ('UDT', 'SDT') AND log_cat IN ('UDT', 'SDT'))) THEN
        RAISE EXCEPTION 'reason category % does not fit interval category %', new_cat, log_cat
            USING ERRCODE = '23514', CONSTRAINT = 'ck_cms_state_note_cat';
    END IF;
    -- 작업지시가 연결된 구간(실효 UDT)을 다른 분류로 바꾸지 않는다
    IF new_cat <> 'UDT' AND EXISTS (SELECT 1 FROM cms_mwo_down d WHERE d.state_log_id = NEW.state_log_id AND d.void_yn = 'N') THEN
        RAISE EXCEPTION 'unlink the work order first' USING ERRCODE = '23514', CONSTRAINT = 'ck_cms_state_note_linked';
    END IF;
    RETURN NEW;
END
$$;

-- ---------------------------------------------------------------------
-- 2. 고장 작업지시 ↔ 비계획 정지 구간
-- ---------------------------------------------------------------------
CREATE TABLE cms_mwo_down (
    link_id       bigint        GENERATED ALWAYS AS IDENTITY,
    comp_cd       varchar(20)   NOT NULL,
    mwo_id        bigint        NOT NULL,
    state_log_id  bigint        NOT NULL,
    recorded_at   timestamptz   NOT NULL DEFAULT now(),
    recorded_by   varchar(50)   NOT NULL,
    void_yn       char(1)       NOT NULL DEFAULT 'N',
    void_reason   varchar(500),
    voided_by     varchar(50),
    voided_at     timestamptz,
    CONSTRAINT pk_cms_mwo_down PRIMARY KEY (link_id),
    CONSTRAINT fk_cms_mwo_down_mwo FOREIGN KEY (mwo_id) REFERENCES cms_mwo (mwo_id),
    CONSTRAINT fk_cms_mwo_down_log FOREIGN KEY (state_log_id) REFERENCES cms_equip_state_log (state_log_id),
    CONSTRAINT ck_cms_mwo_down_void CHECK (void_yn IN ('Y', 'N')
        AND (void_yn = 'N') = (void_reason IS NULL AND voided_by IS NULL AND voided_at IS NULL)
        AND (void_reason IS NULL OR length(btrim(void_reason)) > 0))
);
CREATE UNIQUE INDEX uq_cms_mwo_down_live ON cms_mwo_down (comp_cd, state_log_id) WHERE void_yn = 'N';
CREATE INDEX ix_cms_mwo_down_mwo ON cms_mwo_down (comp_cd, mwo_id, link_id);
CREATE INDEX ix_cms_mwo_down_log ON cms_mwo_down (comp_cd, state_log_id, link_id);
COMMENT ON TABLE cms_mwo_down IS '고장 작업지시 ↔ 비계획 정지(UDT) 구간 (docs/33 C7, D18) — 작업지시 수기 정지 구간(down_start/end)과 대조. 추가 + 무효만';

CREATE FUNCTION hb_guard_cms_mwo_down() RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    log_cat    varchar(10);
    log_equip  bigint;
    note_cat   varchar(10);
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'work order link cannot be deleted (void it)' USING ERRCODE = '55000', CONSTRAINT = 'ck_cms_mwo_down_ledger';
    END IF;
    IF TG_OP = 'UPDATE' THEN
        IF NEW.comp_cd <> OLD.comp_cd OR NEW.mwo_id <> OLD.mwo_id OR NEW.state_log_id <> OLD.state_log_id
           OR NEW.recorded_at <> OLD.recorded_at OR NEW.recorded_by <> OLD.recorded_by OR OLD.void_yn = 'Y' OR NEW.void_yn <> 'Y' THEN
            RAISE EXCEPTION 'work order link % is a ledger row (only void)', OLD.link_id
                USING ERRCODE = '55000', CONSTRAINT = 'ck_cms_mwo_down_ledger';
        END IF;
        RETURN NEW;
    END IF;
    IF NEW.void_yn <> 'N' THEN
        RAISE EXCEPTION 'new work order link must not be void' USING ERRCODE = '55000', CONSTRAINT = 'ck_cms_mwo_down_ledger';
    END IF;
    PERFORM pg_advisory_xact_lock(hashtextextended('cms_state:' || NEW.comp_cd || ':' || NEW.state_log_id, 0));
    SELECT s.state_cat, s.equip_id INTO log_cat, log_equip FROM cms_equip_state_log s
     WHERE s.state_log_id = NEW.state_log_id AND s.comp_cd = NEW.comp_cd;
    IF log_cat IS NULL OR NOT EXISTS (SELECT 1 FROM cms_mwo m WHERE m.mwo_id = NEW.mwo_id AND m.comp_cd = NEW.comp_cd) THEN
        RAISE EXCEPTION 'work order link context is not of company %', NEW.comp_cd
            USING ERRCODE = '23503', CONSTRAINT = 'fk_cms_mwo_down_ctx_comp';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM cms_mwo m WHERE m.mwo_id = NEW.mwo_id AND m.mwo_type = 'BM' AND m.status <> 'CANCELLED'
                      AND m.equip_id = log_equip) THEN
        RAISE EXCEPTION 'work order % is not a live breakdown order of equipment %', NEW.mwo_id, log_equip
            USING ERRCODE = '23514', CONSTRAINT = 'ck_cms_mwo_down_mwo';
    END IF;
    SELECT r.state_cat INTO note_cat FROM cms_state_note n JOIN cms_state_reason r ON r.reason_id = n.reason_id
     WHERE n.state_log_id = NEW.state_log_id AND n.void_yn = 'N';
    IF coalesce(note_cat, log_cat) <> 'UDT' THEN
        RAISE EXCEPTION 'interval % is not an unplanned downtime', NEW.state_log_id
            USING ERRCODE = '23514', CONSTRAINT = 'ck_cms_mwo_down_cat';
    END IF;
    RETURN NEW;
END
$$;

-- 두 함수가 서로의 표를 읽으므로 표를 모두 만든 뒤 트리거를 건다
CREATE TRIGGER tg_cms_state_note_guard BEFORE INSERT OR UPDATE OR DELETE ON cms_state_note
    FOR EACH ROW EXECUTE FUNCTION hb_guard_cms_state_note();
CREATE TRIGGER tg_cms_mwo_down_guard BEFORE INSERT OR UPDATE OR DELETE ON cms_mwo_down
    FOR EACH ROW EXECUTE FUNCTION hb_guard_cms_mwo_down();
CREATE TRIGGER tg_audit AFTER INSERT OR UPDATE OR DELETE ON cms_state_note FOR EACH ROW EXECUTE FUNCTION hb_audit('note_id', '', '');
CREATE TRIGGER tg_audit AFTER INSERT OR UPDATE OR DELETE ON cms_mwo_down FOR EACH ROW EXECUTE FUNCTION hb_audit('link_id', '', '');

ALTER TABLE cms_state_note ENABLE ROW LEVEL SECURITY;
ALTER TABLE cms_state_note FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON cms_state_note USING (comp_cd = hb_current_comp()) WITH CHECK (comp_cd = hb_current_comp());
ALTER TABLE cms_mwo_down ENABLE ROW LEVEL SECURITY;
ALTER TABLE cms_mwo_down FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON cms_mwo_down USING (comp_cd = hb_current_comp()) WITH CHECK (comp_cd = hb_current_comp());
REVOKE DELETE, TRUNCATE ON cms_state_note, cms_mwo_down FROM ${app_role};

-- ---------------------------------------------------------------------
-- 3. 화면 — 사유 관리(Q1: 표는 MES, 화면·API 는 CMS)·비가동 사유 검토. MES cms-state 는 그대로(D24)
-- ---------------------------------------------------------------------
INSERT INTO sys_prog (prog_cd, prog_nm_key, view_path, module_cd, actions, sort_no) VALUES
    ('cms-state-reason', 'prog.cmsStateReason', 'cms/state-reason-mgt', 'CMS', ARRAY['SEARCH','SAVE']::varchar(20)[], 600),
    ('cms-state-review', 'prog.cmsStateReview', 'cms/state-review',     'CMS', ARRAY['SEARCH','SAVE','DELETE']::varchar(20)[], 610);
INSERT INTO sys_retention_table (table_nm, class_cd) VALUES
    ('cms_state_note', 'PRODUCTION_RECORD'), ('cms_mwo_down', 'MAINT_RECORD');
