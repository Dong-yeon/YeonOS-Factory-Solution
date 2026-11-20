-- =====================================================================
-- CMS 1부 C3 — 사용량 카운터·계기 읽기·사용량 주기 예방점검 (docs/33 4.4, 결정 D4)
--
-- - cms_counter: 설비·금형·지그의 사용량 카운터(타수·사이클·가동 시간…)
-- - cms_counter_reading: 읽기 기록(원장 변형 — 추가 + 무효만). 구간 증가량을 저장하고 누적 = Σ 증가량(무효 제외)
--   계기 값이 줄면 리셋 표시가 있어야 하고, 무효는 마지막 행부터(계기 값 사슬이 끊기지 않게)
-- - cms_pm_plan: 사용량 주기(USAGE) — 달력 칸 대신 카운터·간격·첫 예정 사용량·다음 예정 사용량
-- - cms_chk_run.due_usage / cms_mwo.due_ref_usage: 사용량 예정의 멱등 키(같은 날 두 번 도달해도 각각 한 번)
-- 임시 번호(docs/29 2.2) — 병합 때 이름만 바뀐다.
-- =====================================================================

INSERT INTO sys_code_grp (grp_cd, grp_nm_key, comp_ext_yn) VALUES
    ('CMS_COUNTER_KIND', 'code.grp.CMS_COUNTER_KIND', 'N'),
    ('CMS_CYCLE_TYPE',   'code.grp.CMS_CYCLE_TYPE',   'N');
INSERT INTO sys_code (grp_cd, code, code_nm_key, sort_no) VALUES
    ('CMS_COUNTER_KIND', 'SHOTS',     'code.CMS_COUNTER_KIND.SHOTS',     1),
    ('CMS_COUNTER_KIND', 'CYCLES',    'code.CMS_COUNTER_KIND.CYCLES',    2),
    ('CMS_COUNTER_KIND', 'RUN_HOURS', 'code.CMS_COUNTER_KIND.RUN_HOURS', 3),
    ('CMS_COUNTER_KIND', 'OTHER',     'code.CMS_COUNTER_KIND.OTHER',     4),
    ('CMS_CYCLE_TYPE', 'CAL',   'code.CMS_CYCLE_TYPE.CAL',   1),
    ('CMS_CYCLE_TYPE', 'USAGE', 'code.CMS_CYCLE_TYPE.USAGE', 2);

-- ---------------------------------------------------------------------
-- 1. 카운터
-- ---------------------------------------------------------------------
CREATE TABLE cms_counter (
    counter_id    bigint        GENERATED ALWAYS AS IDENTITY,
    comp_cd       varchar(20)   NOT NULL,
    counter_cd    varchar(30)   NOT NULL,
    counter_nm    varchar(100)  NOT NULL,
    target_type   varchar(5)    NOT NULL,
    equip_id      bigint,
    asset_id      bigint,
    counter_kind  varchar(10)   NOT NULL,
    unit_nm       varchar(20),
    scale         smallint      NOT NULL DEFAULT 0,
    use_yn        char(1)       NOT NULL DEFAULT 'Y',
    remark        varchar(500),
    row_ver       integer       NOT NULL DEFAULT 0,
    created_at    timestamptz   NOT NULL DEFAULT now(),
    created_by    varchar(50)   NOT NULL,
    updated_at    timestamptz   NOT NULL DEFAULT now(),
    updated_by    varchar(50)   NOT NULL,
    CONSTRAINT pk_cms_counter PRIMARY KEY (counter_id),
    CONSTRAINT fk_cms_counter_comp FOREIGN KEY (comp_cd) REFERENCES sys_comp (comp_cd),
    CONSTRAINT fk_cms_counter_equip FOREIGN KEY (equip_id) REFERENCES mst_equip (equip_id),
    CONSTRAINT fk_cms_counter_asset FOREIGN KEY (asset_id) REFERENCES mst_phys_asset (asset_id),
    CONSTRAINT uq_cms_counter_cd UNIQUE (comp_cd, counter_cd),
    CONSTRAINT ck_cms_counter_cd CHECK (counter_cd ~ '^[A-Z0-9][A-Z0-9._-]{0,29}$'),
    CONSTRAINT ck_cms_counter_nm CHECK (length(btrim(counter_nm)) > 0),
    CONSTRAINT ck_cms_counter_target CHECK ((target_type = 'EQUIP' AND equip_id IS NOT NULL AND asset_id IS NULL)
                                          OR (target_type = 'ASSET' AND asset_id IS NOT NULL AND equip_id IS NULL)),
    CONSTRAINT ck_cms_counter_kind CHECK (counter_kind IN ('SHOTS', 'CYCLES', 'RUN_HOURS', 'OTHER')),
    CONSTRAINT ck_cms_counter_scale CHECK (scale BETWEEN 0 AND 6),
    CONSTRAINT ck_cms_counter_use_yn CHECK (use_yn IN ('Y', 'N'))
);
CREATE INDEX ix_cms_counter_equip ON cms_counter (comp_cd, equip_id) WHERE equip_id IS NOT NULL;
CREATE INDEX ix_cms_counter_asset ON cms_counter (comp_cd, asset_id) WHERE asset_id IS NOT NULL;
COMMENT ON TABLE cms_counter IS '사용량 카운터 (docs/33 C3). 누적은 읽기 기록의 Σ 증가량 — 카운터 행에 누적을 두지 않는다';
COMMENT ON COLUMN cms_counter.scale IS '값 소수 자릿수(가동 시간 0.5h 등) — 만든 뒤 고정';

CREATE FUNCTION hb_guard_cms_counter() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'UPDATE' AND (NEW.counter_cd <> OLD.counter_cd OR NEW.target_type <> OLD.target_type
                             OR NEW.equip_id IS DISTINCT FROM OLD.equip_id OR NEW.asset_id IS DISTINCT FROM OLD.asset_id
                             OR NEW.counter_kind <> OLD.counter_kind OR NEW.scale <> OLD.scale OR NEW.comp_cd <> OLD.comp_cd) THEN
        RAISE EXCEPTION 'counter code, target, kind and scale are fixed' USING ERRCODE = '55000', CONSTRAINT = 'ck_cms_counter_fixed';
    END IF;
    IF TG_OP = 'INSERT' THEN
        IF NEW.equip_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM mst_equip e WHERE e.equip_id = NEW.equip_id
                AND e.comp_cd = NEW.comp_cd AND e.use_yn = 'Y') THEN
            RAISE EXCEPTION 'equipment % is not an active equipment of company %', NEW.equip_id, NEW.comp_cd
                USING ERRCODE = '23503', CONSTRAINT = 'fk_cms_counter_equip_comp';
        END IF;
        IF NEW.asset_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM mst_phys_asset a WHERE a.asset_id = NEW.asset_id
                AND a.comp_cd = NEW.comp_cd AND a.use_yn = 'Y' AND a.asset_type IN ('MOLD', 'JIG')) THEN
            RAISE EXCEPTION 'asset % is not an active mold or jig of company %', NEW.asset_id, NEW.comp_cd
                USING ERRCODE = '23503', CONSTRAINT = 'fk_cms_counter_asset_comp';
        END IF;
    END IF;
    RETURN NEW;
END
$$;
CREATE TRIGGER tg_cms_counter_guard BEFORE INSERT OR UPDATE ON cms_counter FOR EACH ROW EXECUTE FUNCTION hb_guard_cms_counter();
CREATE TRIGGER tg_cms_counter_no_delete BEFORE DELETE ON cms_counter FOR EACH ROW EXECUTE FUNCTION hb_forbid_delete_master();

-- ---------------------------------------------------------------------
-- 2. 읽기 기록 (원장 변형: 추가 + 무효만)
-- ---------------------------------------------------------------------
CREATE TABLE cms_counter_reading (
    reading_id    bigint         GENERATED ALWAYS AS IDENTITY,
    comp_cd       varchar(20)    NOT NULL,
    counter_id    bigint         NOT NULL,
    read_at       timestamptz    NOT NULL,
    gauge_value   numeric(18,6),
    delta_qty     numeric(18,6)  NOT NULL,
    reset_yn      char(1)        NOT NULL DEFAULT 'N',
    source        varchar(6)     NOT NULL DEFAULT 'MANUAL',
    remark        varchar(500),
    recorded_at   timestamptz    NOT NULL DEFAULT now(),
    recorded_by   varchar(50)    NOT NULL,
    void_yn       char(1)        NOT NULL DEFAULT 'N',
    void_reason   varchar(500),
    voided_by     varchar(50),
    voided_at     timestamptz,
    CONSTRAINT pk_cms_counter_reading PRIMARY KEY (reading_id),
    CONSTRAINT fk_cms_counter_reading_counter FOREIGN KEY (counter_id) REFERENCES cms_counter (counter_id),
    CONSTRAINT ck_cms_counter_reading_delta CHECK (delta_qty >= 0),
    CONSTRAINT ck_cms_counter_reading_gauge CHECK (gauge_value IS NULL OR gauge_value >= 0),
    -- 리셋은 계기 값 입력에만
    CONSTRAINT ck_cms_counter_reading_reset CHECK (reset_yn IN ('Y', 'N') AND (reset_yn = 'N' OR gauge_value IS NOT NULL)),
    -- AUTO 는 2부(C10) 자동 입력 자리 — 1부는 MANUAL 만 쓴다
    CONSTRAINT ck_cms_counter_reading_source CHECK (source IN ('MANUAL', 'AUTO')),
    CONSTRAINT ck_cms_counter_reading_void CHECK (void_yn IN ('Y', 'N')
        AND (void_yn = 'N') = (void_reason IS NULL AND voided_by IS NULL AND voided_at IS NULL)
        AND (void_reason IS NULL OR length(btrim(void_reason)) > 0))
);
CREATE INDEX ix_cms_counter_reading_counter ON cms_counter_reading (comp_cd, counter_id, read_at, reading_id);
COMMENT ON TABLE cms_counter_reading IS '카운터 읽기 (원장 변형: 추가 + 무효만, 삭제 금지). 누적 = Σ delta_qty (무효 제외)';
COMMENT ON COLUMN cms_counter_reading.gauge_value IS '읽은 계기 값 — 없으면 증가량 직접 입력(초기 누적·계기 고장 구간 등)';
COMMENT ON COLUMN cms_counter_reading.delta_qty IS '구간 증가량 = 이번 계기 값 − 직전 계기 값(리셋이면 이번 계기 값, 첫 계기 값이면 0 = 기준)';

-- 순서: 카운터 행을 잠가 같은 카운터 입력을 줄 세운다. 증가량은 직전 계기 값과 맞아야 하고, 읽은 시각은 마지막 행보다 앞설 수 없다
CREATE FUNCTION hb_guard_cms_counter_reading() RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    last_at   timestamptz;
    prev      numeric;
    expected  numeric;
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'counter reading cannot be deleted (void it)' USING ERRCODE = '55000', CONSTRAINT = 'ck_cms_counter_reading_no_delete';
    END IF;
    IF TG_OP = 'INSERT' THEN
        PERFORM 1 FROM cms_counter c WHERE c.counter_id = NEW.counter_id AND c.comp_cd = NEW.comp_cd AND c.use_yn = 'Y' FOR UPDATE;
        IF NOT FOUND THEN
            RAISE EXCEPTION 'counter % is not an active counter of company %', NEW.counter_id, NEW.comp_cd
                USING ERRCODE = '23503', CONSTRAINT = 'fk_cms_counter_reading_counter_comp';
        END IF;
        IF NEW.void_yn <> 'N' THEN
            RAISE EXCEPTION 'new counter reading must not be void' USING ERRCODE = '55000', CONSTRAINT = 'ck_cms_counter_reading_ledger';
        END IF;
        SELECT max(r.read_at) INTO last_at FROM cms_counter_reading r WHERE r.counter_id = NEW.counter_id AND r.void_yn = 'N';
        IF last_at IS NOT NULL AND NEW.read_at < last_at THEN
            RAISE EXCEPTION 'reading time is before the last reading' USING ERRCODE = '55000', CONSTRAINT = 'ck_cms_counter_reading_order';
        END IF;
        IF NEW.gauge_value IS NOT NULL THEN
            SELECT r.gauge_value INTO prev FROM cms_counter_reading r
             WHERE r.counter_id = NEW.counter_id AND r.void_yn = 'N' AND r.gauge_value IS NOT NULL
             ORDER BY r.read_at DESC, r.reading_id DESC LIMIT 1;
            IF NEW.reset_yn = 'N' AND prev IS NOT NULL AND NEW.gauge_value < prev THEN
                RAISE EXCEPTION 'gauge value went down without reset' USING ERRCODE = '23514', CONSTRAINT = 'ck_cms_counter_reading_decrease';
            END IF;
            -- IF 조건 안에 CASE … THEN 을 쓰면 PL/pgSQL 이 첫 THEN 에서 조건을 자른다 — 기대값을 먼저 계산
            expected := CASE WHEN NEW.reset_yn = 'Y' THEN NEW.gauge_value WHEN prev IS NULL THEN 0 ELSE NEW.gauge_value - prev END;
            IF NEW.delta_qty <> expected THEN
                RAISE EXCEPTION 'delta does not match the gauge chain' USING ERRCODE = '23514', CONSTRAINT = 'ck_cms_counter_reading_chain';
            END IF;
        END IF;
        RETURN NEW;
    END IF;
    -- UPDATE 는 무효 한 가지뿐, 그리고 마지막 행부터
    IF NEW.comp_cd <> OLD.comp_cd OR NEW.counter_id <> OLD.counter_id OR NEW.read_at <> OLD.read_at
       OR NEW.gauge_value IS DISTINCT FROM OLD.gauge_value OR NEW.delta_qty <> OLD.delta_qty OR NEW.reset_yn <> OLD.reset_yn
       OR NEW.source <> OLD.source OR NEW.remark IS DISTINCT FROM OLD.remark OR NEW.recorded_at <> OLD.recorded_at
       OR NEW.recorded_by <> OLD.recorded_by OR OLD.void_yn = 'Y' OR NEW.void_yn <> 'Y' THEN
        RAISE EXCEPTION 'counter reading % is a ledger row (only void)', OLD.reading_id
            USING ERRCODE = '55000', CONSTRAINT = 'ck_cms_counter_reading_ledger';
    END IF;
    PERFORM 1 FROM cms_counter c WHERE c.counter_id = OLD.counter_id FOR UPDATE;
    IF EXISTS (SELECT 1 FROM cms_counter_reading r WHERE r.counter_id = OLD.counter_id AND r.void_yn = 'N'
                  AND r.reading_id <> OLD.reading_id
                  AND (r.read_at > OLD.read_at OR (r.read_at = OLD.read_at AND r.reading_id > OLD.reading_id))) THEN
        RAISE EXCEPTION 'void the latest reading first' USING ERRCODE = '55000', CONSTRAINT = 'ck_cms_counter_reading_void_order';
    END IF;
    RETURN NEW;
END
$$;
CREATE TRIGGER tg_cms_counter_reading_guard BEFORE INSERT OR UPDATE OR DELETE ON cms_counter_reading
    FOR EACH ROW EXECUTE FUNCTION hb_guard_cms_counter_reading();

-- ---------------------------------------------------------------------
-- 3. 예방점검 계획 — 사용량 주기
-- ---------------------------------------------------------------------
ALTER TABLE cms_pm_plan
    ADD COLUMN counter_id      bigint,
    ADD COLUMN usage_interval  numeric(18,6),
    ADD COLUMN usage_lead      numeric(18,6),
    ADD COLUMN anchor_usage    numeric(18,6),
    ADD COLUMN next_due_usage  numeric(18,6),
    ADD COLUMN last_done_usage numeric(18,6),
    ADD CONSTRAINT fk_cms_pm_plan_counter FOREIGN KEY (counter_id) REFERENCES cms_counter (counter_id);
ALTER TABLE cms_pm_plan ALTER COLUMN cal_unit DROP NOT NULL, ALTER COLUMN cal_interval DROP NOT NULL,
    ALTER COLUMN anchor_date DROP NOT NULL, ALTER COLUMN next_due_date DROP NOT NULL;
ALTER TABLE cms_pm_plan DROP CONSTRAINT ck_cms_pm_plan_cycle;
-- 달력 주기는 달력 칸만, 사용량 주기는 사용량 칸만. 선행 사용량 < 간격(선행이 간격 이상이면 끝나자마자 다음이 생긴다)
ALTER TABLE cms_pm_plan ADD CONSTRAINT ck_cms_pm_plan_cycle CHECK (
    (cycle_type = 'CAL' AND cal_unit IS NOT NULL AND cal_interval IS NOT NULL AND anchor_date IS NOT NULL AND next_due_date IS NOT NULL
        AND counter_id IS NULL AND usage_interval IS NULL AND usage_lead IS NULL AND anchor_usage IS NULL AND next_due_usage IS NULL
        AND last_done_usage IS NULL)
    OR (cycle_type = 'USAGE' AND counter_id IS NOT NULL AND usage_interval > 0 AND anchor_usage >= 0 AND next_due_usage IS NOT NULL
        AND (usage_lead IS NULL OR (usage_lead >= 0 AND usage_lead < usage_interval))
        AND cal_unit IS NULL AND cal_interval IS NULL AND anchor_date IS NULL AND next_due_date IS NULL));
CREATE INDEX ix_cms_pm_plan_counter ON cms_pm_plan (comp_cd, counter_id) WHERE counter_id IS NOT NULL;
COMMENT ON COLUMN cms_pm_plan.anchor_usage IS '사용량 주기의 첫 예정 사용량(누적) — FIXED 의 k 번째 = 첫 예정 + k×간격';

CREATE OR REPLACE FUNCTION hb_guard_cms_pm_plan() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'UPDATE' AND (NEW.plan_cd <> OLD.plan_cd OR NEW.target_type <> OLD.target_type
                             OR NEW.equip_id IS DISTINCT FROM OLD.equip_id OR NEW.asset_id IS DISTINCT FROM OLD.asset_id
                             OR NEW.cycle_type <> OLD.cycle_type OR NEW.counter_id IS DISTINCT FROM OLD.counter_id) THEN
        RAISE EXCEPTION 'plan code, target, cycle type and counter are fixed' USING ERRCODE = '55000', CONSTRAINT = 'ck_cms_pm_plan_fixed';
    END IF;
    IF TG_OP = 'INSERT' THEN
        IF NEW.equip_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM mst_equip e WHERE e.equip_id = NEW.equip_id
                AND e.comp_cd = NEW.comp_cd AND e.use_yn = 'Y') THEN
            RAISE EXCEPTION 'equipment % is not an active equipment of company %', NEW.equip_id, NEW.comp_cd
                USING ERRCODE = '23503', CONSTRAINT = 'fk_cms_pm_plan_equip_comp';
        END IF;
        IF NEW.asset_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM mst_phys_asset a WHERE a.asset_id = NEW.asset_id
                AND a.comp_cd = NEW.comp_cd AND a.use_yn = 'Y' AND a.asset_type IN ('MOLD', 'JIG')) THEN
            RAISE EXCEPTION 'asset % is not an active mold or jig of company %', NEW.asset_id, NEW.comp_cd
                USING ERRCODE = '23503', CONSTRAINT = 'fk_cms_pm_plan_asset_comp';
        END IF;
        -- 사용량 카운터: 같은 회사·사용 중·계획과 같은 대상
        IF NEW.counter_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM cms_counter c WHERE c.counter_id = NEW.counter_id
                AND c.comp_cd = NEW.comp_cd AND c.use_yn = 'Y' AND c.target_type = NEW.target_type
                AND c.equip_id IS NOT DISTINCT FROM NEW.equip_id AND c.asset_id IS NOT DISTINCT FROM NEW.asset_id) THEN
            RAISE EXCEPTION 'counter % does not fit this plan target', NEW.counter_id
                USING ERRCODE = '23503', CONSTRAINT = 'fk_cms_pm_plan_counter_comp';
        END IF;
    END IF;
    -- 점검표: 같은 회사·같은 대상 종류, 클래스 한정이면 그 클래스 설비, 새로 걸 때는 사용 중만
    IF TG_OP = 'INSERT' OR NEW.tmpl_id <> OLD.tmpl_id THEN
        IF NOT EXISTS (SELECT 1 FROM cms_chk_tmpl t WHERE t.tmpl_id = NEW.tmpl_id AND t.comp_cd = NEW.comp_cd AND t.use_yn = 'Y'
                          AND t.target_kind = NEW.target_type
                          AND (t.equip_class_id IS NULL
                               OR t.equip_class_id = (SELECT e.class_id FROM mst_equip e WHERE e.equip_id = NEW.equip_id))) THEN
            RAISE EXCEPTION 'template % does not fit this plan target', NEW.tmpl_id
                USING ERRCODE = '23503', CONSTRAINT = 'fk_cms_pm_plan_tmpl_comp';
        END IF;
    END IF;
    IF NEW.assignee_id IS NOT NULL AND (TG_OP = 'INSERT' OR NEW.assignee_id IS DISTINCT FROM OLD.assignee_id)
       AND NOT EXISTS (SELECT 1 FROM sys_user u WHERE u.user_id = NEW.assignee_id AND u.comp_cd = NEW.comp_cd AND u.use_yn = 'Y') THEN
        RAISE EXCEPTION 'user % is not an active user of company %', NEW.assignee_id, NEW.comp_cd
            USING ERRCODE = '23503', CONSTRAINT = 'fk_cms_pm_plan_assignee_comp';
    END IF;
    RETURN NEW;
END
$$;

-- ---------------------------------------------------------------------
-- 4. 예정의 사용량 키 — 점검 실행·작업지시
-- ---------------------------------------------------------------------
ALTER TABLE cms_chk_run ADD COLUMN due_usage numeric(18,6), ADD COLUMN done_usage numeric(18,6);
COMMENT ON COLUMN cms_chk_run.due_usage IS '사용량 주기의 예정 사용량(누적). 달력 주기는 비어 있다';
COMMENT ON COLUMN cms_chk_run.done_usage IS '사용량 주기 완료 때의 누적 — 기한 준수 = 완료 누적 ≤ 예정 사용량';
ALTER TABLE cms_chk_run DROP CONSTRAINT uq_cms_chk_run_due;
CREATE UNIQUE INDEX uq_cms_chk_run_due ON cms_chk_run (comp_cd, plan_id, due_date, due_usage) NULLS NOT DISTINCT;

ALTER TABLE cms_mwo ADD COLUMN due_ref_usage numeric(18,6);
COMMENT ON COLUMN cms_mwo.due_ref_usage IS '사용량 주기 PM 의 예정 사용량 — 예정 생성 멱등 키(due_ref_date 와 함께)';
DROP INDEX uq_cms_mwo_pm_due;
CREATE UNIQUE INDEX uq_cms_mwo_pm_due ON cms_mwo (comp_cd, pm_plan_id, due_ref_date, due_ref_usage) NULLS NOT DISTINCT
    WHERE pm_plan_id IS NOT NULL;

CREATE OR REPLACE FUNCTION hb_guard_cms_mwo_pm() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'UPDATE' AND (NEW.src_run_id IS DISTINCT FROM OLD.src_run_id OR NEW.due_ref_usage IS DISTINCT FROM OLD.due_ref_usage) THEN
        RAISE EXCEPTION 'work order key columns are fixed' USING ERRCODE = '55000', CONSTRAINT = 'ck_cms_mwo_fixed';
    END IF;
    IF TG_OP = 'INSERT' AND NEW.pm_plan_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM cms_pm_plan p WHERE p.plan_id = NEW.pm_plan_id
            AND p.comp_cd = NEW.comp_cd AND p.target_type = NEW.target_type AND p.equip_id IS NOT DISTINCT FROM NEW.equip_id
            AND p.asset_id IS NOT DISTINCT FROM NEW.asset_id AND (p.cycle_type = 'USAGE') = (NEW.due_ref_usage IS NOT NULL)) THEN
        RAISE EXCEPTION 'plan % does not match this work order', NEW.pm_plan_id USING ERRCODE = '23503', CONSTRAINT = 'fk_cms_mwo_pm_plan_comp';
    END IF;
    IF TG_OP = 'INSERT' AND NEW.pm_plan_id IS NULL AND NEW.due_ref_usage IS NOT NULL THEN
        RAISE EXCEPTION 'due usage is only for PM work orders' USING ERRCODE = '23514', CONSTRAINT = 'ck_cms_mwo_pm';
    END IF;
    IF TG_OP = 'INSERT' AND NEW.src_run_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM cms_chk_run x WHERE x.run_id = NEW.src_run_id
            AND x.comp_cd = NEW.comp_cd) THEN
        RAISE EXCEPTION 'check run % is not of company %', NEW.src_run_id, NEW.comp_cd USING ERRCODE = '23503', CONSTRAINT = 'fk_cms_mwo_src_run_comp';
    END IF;
    RETURN NEW;
END
$$;

-- 점검 실행: C2 규칙 + 사용량 키(예정 사용량은 사용량 계획에만, 작업지시와 같아야, 고정)
CREATE OR REPLACE FUNCTION hb_guard_cms_chk_run() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'check run cannot be deleted (skip it)' USING ERRCODE = '55000', CONSTRAINT = 'ck_cms_chk_run_no_delete';
    END IF;
    IF TG_OP = 'INSERT' THEN
        IF NEW.status <> 'OPEN' OR NEW.done_usage IS NOT NULL THEN
            RAISE EXCEPTION 'new check run must be OPEN' USING ERRCODE = '55000', CONSTRAINT = 'ck_cms_chk_run_transition';
        END IF;
        IF NOT EXISTS (SELECT 1 FROM cms_pm_plan p WHERE p.plan_id = NEW.plan_id AND p.comp_cd = NEW.comp_cd
                          AND p.target_type = NEW.target_type AND p.equip_id IS NOT DISTINCT FROM NEW.equip_id
                          AND p.asset_id IS NOT DISTINCT FROM NEW.asset_id
                          AND (p.cycle_type = 'USAGE') = (NEW.due_usage IS NOT NULL)) THEN
            RAISE EXCEPTION 'plan % does not match this check run', NEW.plan_id USING ERRCODE = '23503', CONSTRAINT = 'fk_cms_chk_run_plan_comp';
        END IF;
        IF NEW.mwo_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM cms_mwo m WHERE m.mwo_id = NEW.mwo_id AND m.comp_cd = NEW.comp_cd
                AND m.pm_plan_id = NEW.plan_id AND m.due_ref_date = NEW.due_date
                AND m.due_ref_usage IS NOT DISTINCT FROM NEW.due_usage) THEN
            RAISE EXCEPTION 'work order % does not match this check run', NEW.mwo_id USING ERRCODE = '23503', CONSTRAINT = 'fk_cms_chk_run_mwo_comp';
        END IF;
        IF NOT EXISTS (SELECT 1 FROM cms_chk_tmpl t WHERE t.tmpl_id = NEW.tmpl_id AND t.comp_cd = NEW.comp_cd) THEN
            RAISE EXCEPTION 'template % is not of company %', NEW.tmpl_id, NEW.comp_cd USING ERRCODE = '23503', CONSTRAINT = 'fk_cms_chk_run_tmpl_comp';
        END IF;
        RETURN NEW;
    END IF;
    IF NEW.plan_id <> OLD.plan_id OR NEW.mwo_id IS DISTINCT FROM OLD.mwo_id OR NEW.tmpl_id <> OLD.tmpl_id
       OR NEW.due_date <> OLD.due_date OR NEW.equip_id IS DISTINCT FROM OLD.equip_id OR NEW.asset_id IS DISTINCT FROM OLD.asset_id
       OR NEW.comp_cd <> OLD.comp_cd OR NEW.due_usage IS DISTINCT FROM OLD.due_usage THEN
        RAISE EXCEPTION 'check run key columns are fixed' USING ERRCODE = '55000', CONSTRAINT = 'ck_cms_chk_run_fixed';
    END IF;
    IF OLD.status <> 'OPEN' THEN
        RAISE EXCEPTION 'finished check run is read-only' USING ERRCODE = '55000', CONSTRAINT = 'ck_cms_chk_run_done_fixed';
    END IF;
    IF NEW.performed_by IS NOT NULL AND NOT hb_user_of_comp(NEW.comp_cd, NEW.performed_by) THEN
        RAISE EXCEPTION 'user % is not of company %', NEW.performed_by, NEW.comp_cd USING ERRCODE = '23503', CONSTRAINT = 'fk_cms_chk_run_by_comp';
    END IF;
    RETURN NEW;
END
$$;

-- ---------------------------------------------------------------------
-- 5. 감사 · RLS · 권한 · 화면 · 보존
-- ---------------------------------------------------------------------
CREATE TRIGGER tg_audit AFTER INSERT OR UPDATE OR DELETE ON cms_counter FOR EACH ROW EXECUTE FUNCTION hb_audit('counter_id', '', '');
CREATE TRIGGER tg_audit AFTER INSERT OR UPDATE OR DELETE ON cms_counter_reading FOR EACH ROW EXECUTE FUNCTION hb_audit('reading_id', '', '');

ALTER TABLE cms_counter ENABLE ROW LEVEL SECURITY;
ALTER TABLE cms_counter FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON cms_counter USING (comp_cd = hb_current_comp()) WITH CHECK (comp_cd = hb_current_comp());
ALTER TABLE cms_counter_reading ENABLE ROW LEVEL SECURITY;
ALTER TABLE cms_counter_reading FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON cms_counter_reading USING (comp_cd = hb_current_comp()) WITH CHECK (comp_cd = hb_current_comp());
REVOKE DELETE, TRUNCATE ON cms_counter, cms_counter_reading FROM ${app_role};

INSERT INTO sys_prog (prog_cd, prog_nm_key, view_path, module_cd, actions, sort_no) VALUES
    ('cms-counter', 'prog.cmsCounter', 'cms/counter-mgt', 'CMS', ARRAY['SEARCH','SAVE','DELETE']::varchar(20)[], 565);
INSERT INTO sys_retention_table (table_nm, class_cd) VALUES
    ('cms_counter', 'MASTER'), ('cms_counter_reading', 'MAINT_RECORD');
