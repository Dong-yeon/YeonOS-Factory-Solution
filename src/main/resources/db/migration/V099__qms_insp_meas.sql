-- =====================================================================
-- P3 QMS 2부 Q6 검사 실행·측정값 원장 (docs/29_P3 12.2) — 임시 번호, 병합 때 재번호
--
-- - qms_insp: 검사 1건(LOT × 유형 × 공정) — 적용 검사계획은 InspPlanResolver 로 만들 때 고정, 로트 수량은 샘플링 기준
-- - qms_insp_line: 계획 행 스냅샷(특성·데이터 유형·규격·계측기 클래스) + 샘플 수 n·Ac·Re, 행 결과는 판정 확정 때 한 번
-- - qms_insp_meas: 측정값 원장 — 추가만, 정정 = 무효 행(void_of_id), (행, 샘플 번호) 에 살아 있는 값 하나.
--   계측기 클래스가 VISUAL 이 아니면 게이지 자산 필수(같은 회사·같은 클래스). 교정 상태 차단은 서비스(GaugeStatus)
-- - LOT·품목·공정·물리 자산은 읽기·FK 만(남의 표 변경 없음)
-- =====================================================================

INSERT INTO sys_code_grp (grp_cd, grp_nm_key, comp_ext_yn) VALUES
    ('QMS_INSP_STATUS', 'code.grp.QMS_INSP_STATUS', 'N'),
    ('QMS_INSP_RESULT', 'code.grp.QMS_INSP_RESULT', 'N');
INSERT INTO sys_code (grp_cd, code, code_nm_key, sort_no) VALUES
    ('QMS_INSP_STATUS', 'OPEN',   'code.QMS_INSP_STATUS.OPEN',   1),
    ('QMS_INSP_STATUS', 'JUDGED', 'code.QMS_INSP_STATUS.JUDGED', 2),
    ('QMS_INSP_STATUS', 'VOID',   'code.QMS_INSP_STATUS.VOID',   3),
    ('QMS_INSP_RESULT', 'OK', 'code.QMS_INSP_RESULT.OK', 1),
    ('QMS_INSP_RESULT', 'NG', 'code.QMS_INSP_RESULT.NG', 2);

-- ---------------------------------------------------------------------
-- 1. 검사
-- ---------------------------------------------------------------------
CREATE TABLE qms_insp (
    insp_id        bigint         GENERATED ALWAYS AS IDENTITY,
    comp_cd        varchar(20)    NOT NULL,
    insp_no        varchar(30)    NOT NULL,
    insp_type      varchar(10)    NOT NULL,
    lot_id         bigint         NOT NULL,
    item_id        bigint         NOT NULL,
    proc_id        bigint,
    insp_plan_id   bigint         NOT NULL,
    lot_qty        bigint         NOT NULL,
    status         varchar(8)     NOT NULL DEFAULT 'OPEN',
    result         varchar(2),
    occurred_at    timestamptz    NOT NULL,
    judged_at      timestamptz,
    judged_by      varchar(50),
    void_reason    varchar(500),
    voided_at      timestamptz,
    voided_by      varchar(50),
    remark         varchar(1000),
    row_ver        integer        NOT NULL DEFAULT 0,
    created_at     timestamptz    NOT NULL DEFAULT now(),
    created_by     varchar(50)    NOT NULL,
    updated_at     timestamptz    NOT NULL DEFAULT now(),
    updated_by     varchar(50)    NOT NULL,
    CONSTRAINT pk_qms_insp PRIMARY KEY (insp_id),
    CONSTRAINT fk_qms_insp_comp FOREIGN KEY (comp_cd) REFERENCES sys_comp (comp_cd),
    CONSTRAINT fk_qms_insp_lot FOREIGN KEY (lot_id) REFERENCES lot_master (lot_id),
    CONSTRAINT fk_qms_insp_item FOREIGN KEY (item_id) REFERENCES mst_item (item_id),
    CONSTRAINT fk_qms_insp_proc FOREIGN KEY (proc_id) REFERENCES mst_proc (proc_id),
    CONSTRAINT fk_qms_insp_plan FOREIGN KEY (insp_plan_id) REFERENCES qms_insp_plan (insp_plan_id),
    CONSTRAINT uq_qms_insp_no UNIQUE (comp_cd, insp_no),
    CONSTRAINT ck_qms_insp_type CHECK (insp_type IN ('RECEIVE', 'PROCESS', 'FINAL', 'FIRST_OFF', 'LAST_OFF', 'PATROL')),
    CONSTRAINT ck_qms_insp_lot_qty CHECK (lot_qty BETWEEN 1 AND 1000000000),
    CONSTRAINT ck_qms_insp_status CHECK (status IN ('OPEN', 'JUDGED', 'VOID')),
    CONSTRAINT ck_qms_insp_result CHECK (result IS NULL OR result IN ('OK', 'NG')),
    -- 결과는 판정 확정 때만(무효가 되어도 확정 결과는 남긴다)
    CONSTRAINT ck_qms_insp_judged CHECK ((result IS NULL) = (judged_at IS NULL) AND (judged_at IS NULL) = (judged_by IS NULL)
        AND (status <> 'JUDGED' OR result IS NOT NULL) AND (status <> 'OPEN' OR result IS NULL)),
    CONSTRAINT ck_qms_insp_void CHECK ((status = 'VOID') = (voided_at IS NOT NULL)
        AND (status <> 'VOID' OR (coalesce(length(btrim(void_reason)), 0) > 0 AND voided_by IS NOT NULL)))
);
CREATE INDEX ix_qms_insp_lot ON qms_insp (comp_cd, lot_id);
CREATE INDEX ix_qms_insp_occurred ON qms_insp (comp_cd, occurred_at DESC);
COMMENT ON TABLE qms_insp IS '검사 1건 — LOT × 유형 × 공정, 적용 검사계획·로트 수량은 만들 때 고정, OPEN → JUDGED(OK/NG) / VOID (docs/29_P3 Q6)';

CREATE FUNCTION hb_guard_qms_insp() RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    v_lot_item bigint;
    v_plan     qms_insp_plan;
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'inspection cannot be deleted (void it)' USING ERRCODE = '55000', CONSTRAINT = 'ck_qms_insp_no_delete';
    END IF;
    IF TG_OP = 'INSERT' THEN
        IF NEW.status <> 'OPEN' THEN
            RAISE EXCEPTION 'new inspection must be OPEN' USING ERRCODE = '55000', CONSTRAINT = 'ck_qms_insp_open';
        END IF;
        SELECT item_id INTO v_lot_item FROM lot_master WHERE lot_id = NEW.lot_id AND comp_cd = NEW.comp_cd;
        IF NOT FOUND THEN
            RAISE EXCEPTION 'lot % does not belong to company %', NEW.lot_id, NEW.comp_cd USING ERRCODE = '23503', CONSTRAINT = 'fk_qms_insp_lot_comp';
        END IF;
        IF v_lot_item <> NEW.item_id THEN
            RAISE EXCEPTION 'inspection item must be the lot item' USING ERRCODE = '23514', CONSTRAINT = 'ck_qms_insp_lot_item';
        END IF;
        IF NEW.proc_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM mst_proc p WHERE p.proc_id = NEW.proc_id AND p.comp_cd = NEW.comp_cd) THEN
            RAISE EXCEPTION 'process % does not belong to company %', NEW.proc_id, NEW.comp_cd USING ERRCODE = '23503', CONSTRAINT = 'fk_qms_insp_proc_comp';
        END IF;
        SELECT * INTO v_plan FROM qms_insp_plan WHERE insp_plan_id = NEW.insp_plan_id AND comp_cd = NEW.comp_cd;
        IF NOT FOUND THEN
            RAISE EXCEPTION 'plan % does not belong to company %', NEW.insp_plan_id, NEW.comp_cd USING ERRCODE = '23503', CONSTRAINT = 'fk_qms_insp_plan_comp';
        END IF;
        IF v_plan.status <> 'RELEASED' OR v_plan.insp_type <> NEW.insp_type THEN
            RAISE EXCEPTION 'inspection must use a released plan of the same type' USING ERRCODE = '23514', CONSTRAINT = 'ck_qms_insp_plan_released';
        END IF;
        RETURN NEW;
    END IF;
    -- UPDATE
    IF (NEW.comp_cd, NEW.insp_no, NEW.insp_type, NEW.lot_id, NEW.item_id, NEW.insp_plan_id, NEW.lot_qty, NEW.occurred_at)
       IS DISTINCT FROM (OLD.comp_cd, OLD.insp_no, OLD.insp_type, OLD.lot_id, OLD.item_id, OLD.insp_plan_id, OLD.lot_qty, OLD.occurred_at)
       OR NEW.proc_id IS DISTINCT FROM OLD.proc_id THEN
        RAISE EXCEPTION 'inspection % key cannot be changed', OLD.insp_id USING ERRCODE = '55000', CONSTRAINT = 'ck_qms_insp_immutable';
    END IF;
    IF OLD.status = 'VOID' THEN
        RAISE EXCEPTION 'void inspection % cannot be changed', OLD.insp_id USING ERRCODE = '55000', CONSTRAINT = 'ck_qms_insp_immutable';
    END IF;
    IF OLD.status <> NEW.status AND NOT ((OLD.status = 'OPEN' AND NEW.status IN ('JUDGED', 'VOID')) OR (OLD.status = 'JUDGED' AND NEW.status = 'VOID')) THEN
        RAISE EXCEPTION 'inspection status % -> % is not allowed', OLD.status, NEW.status USING ERRCODE = '55000', CONSTRAINT = 'ck_qms_insp_transition';
    END IF;
    -- 확정 결과는 한 번만
    IF OLD.status = 'JUDGED' AND (NEW.result, NEW.judged_at, NEW.judged_by) IS DISTINCT FROM (OLD.result, OLD.judged_at, OLD.judged_by) THEN
        RAISE EXCEPTION 'judged result of % cannot be changed', OLD.insp_id USING ERRCODE = '55000', CONSTRAINT = 'ck_qms_insp_immutable';
    END IF;
    RETURN NEW;
END
$$;
CREATE TRIGGER tg_qms_insp_guard BEFORE INSERT OR UPDATE OR DELETE ON qms_insp FOR EACH ROW EXECUTE FUNCTION hb_guard_qms_insp();

-- ---------------------------------------------------------------------
-- 2. 검사 행 (계획 행 스냅샷)
-- ---------------------------------------------------------------------
CREATE TABLE qms_insp_line (
    insp_line_id   bigint         GENERATED ALWAYS AS IDENTITY,
    comp_cd        varchar(20)    NOT NULL,
    insp_id        bigint         NOT NULL,
    line_no        integer        NOT NULL,
    plan_line_id   bigint         NOT NULL,
    char_id        bigint         NOT NULL,
    data_type      varchar(10)    NOT NULL,
    nominal        numeric(18,6),
    lsl            numeric(18,6),
    usl            numeric(18,6),
    scale          smallint,
    unit_nm        varchar(20),
    gauge_class    varchar(30)    NOT NULL,
    sample_size    integer        NOT NULL,
    ac             integer        NOT NULL,
    re             integer        NOT NULL,
    result         varchar(2),
    created_at     timestamptz    NOT NULL DEFAULT now(),
    created_by     varchar(50)    NOT NULL,
    CONSTRAINT pk_qms_insp_line PRIMARY KEY (insp_line_id),
    CONSTRAINT fk_qms_insp_line_comp FOREIGN KEY (comp_cd) REFERENCES sys_comp (comp_cd),
    CONSTRAINT fk_qms_insp_line_insp FOREIGN KEY (insp_id) REFERENCES qms_insp (insp_id),
    CONSTRAINT fk_qms_insp_line_plan_line FOREIGN KEY (plan_line_id) REFERENCES qms_insp_plan_line (line_id),
    CONSTRAINT fk_qms_insp_line_char FOREIGN KEY (char_id) REFERENCES qms_char (char_id),
    CONSTRAINT uq_qms_insp_line_no UNIQUE (insp_id, line_no),
    CONSTRAINT uq_qms_insp_line_plan UNIQUE (insp_id, plan_line_id),
    CONSTRAINT ck_qms_insp_line_data_type CHECK (data_type IN ('VARIABLE', 'ATTRIBUTE')),
    CONSTRAINT ck_qms_insp_line_sample CHECK (sample_size BETWEEN 1 AND 1000000000 AND ac >= 0 AND re > ac),
    CONSTRAINT ck_qms_insp_line_result CHECK (result IS NULL OR result IN ('OK', 'NG'))
);
COMMENT ON TABLE qms_insp_line IS '검사 행 — 계획 행 스냅샷 + 샘플 수·Ac·Re, 행 결과는 판정 확정 때 한 번 (docs/29_P3 Q6)';

CREATE FUNCTION hb_guard_qms_insp_line() RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    v_status varchar;
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'inspection lines cannot be deleted' USING ERRCODE = '55000', CONSTRAINT = 'ck_qms_insp_line_no_delete';
    END IF;
    SELECT status INTO v_status FROM qms_insp WHERE insp_id = NEW.insp_id AND comp_cd = NEW.comp_cd;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'inspection % does not belong to company %', NEW.insp_id, NEW.comp_cd USING ERRCODE = '23503', CONSTRAINT = 'fk_qms_insp_line_insp_comp';
    END IF;
    IF TG_OP = 'INSERT' THEN
        IF v_status <> 'OPEN' OR NEW.result IS NOT NULL THEN
            RAISE EXCEPTION 'lines are added to an open inspection without result' USING ERRCODE = '55000', CONSTRAINT = 'ck_qms_insp_line_open';
        END IF;
        IF NOT EXISTS (SELECT 1 FROM qms_insp_plan_line l JOIN qms_insp h ON h.insp_id = NEW.insp_id
                        WHERE l.line_id = NEW.plan_line_id AND l.insp_plan_id = h.insp_plan_id AND l.char_id = NEW.char_id) THEN
            RAISE EXCEPTION 'line must come from the inspection plan' USING ERRCODE = '23514', CONSTRAINT = 'ck_qms_insp_line_plan';
        END IF;
        RETURN NEW;
    END IF;
    -- UPDATE: 결과 한 번만(검사가 열려 있을 때 — 확정 트랜잭션), 그 밖 칸 고정
    IF (NEW.comp_cd, NEW.insp_id, NEW.line_no, NEW.plan_line_id, NEW.char_id, NEW.data_type, NEW.gauge_class, NEW.sample_size, NEW.ac, NEW.re)
       IS DISTINCT FROM (OLD.comp_cd, OLD.insp_id, OLD.line_no, OLD.plan_line_id, OLD.char_id, OLD.data_type, OLD.gauge_class, OLD.sample_size, OLD.ac, OLD.re)
       OR (NEW.nominal, NEW.lsl, NEW.usl, NEW.scale, NEW.unit_nm) IS DISTINCT FROM (OLD.nominal, OLD.lsl, OLD.usl, OLD.scale, OLD.unit_nm)
       OR OLD.result IS NOT NULL OR v_status <> 'OPEN' THEN
        RAISE EXCEPTION 'inspection line % cannot be changed', OLD.insp_line_id USING ERRCODE = '55000', CONSTRAINT = 'ck_qms_insp_line_immutable';
    END IF;
    RETURN NEW;
END
$$;
CREATE TRIGGER tg_qms_insp_line_guard BEFORE INSERT OR UPDATE OR DELETE ON qms_insp_line FOR EACH ROW EXECUTE FUNCTION hb_guard_qms_insp_line();

-- ---------------------------------------------------------------------
-- 3. 측정값 원장
-- ---------------------------------------------------------------------
CREATE TABLE qms_insp_meas (
    meas_id         bigint         GENERATED ALWAYS AS IDENTITY,
    comp_cd         varchar(20)    NOT NULL,
    insp_line_id    bigint         NOT NULL,
    sample_no       integer        NOT NULL,
    value           numeric(18,6),
    ok_yn           char(1),
    gauge_asset_id  bigint,
    void_of_id      bigint,
    occurred_at     timestamptz    NOT NULL,
    recorded_at     timestamptz    NOT NULL DEFAULT now(),
    recorded_by     varchar(50)    NOT NULL,
    CONSTRAINT pk_qms_insp_meas PRIMARY KEY (meas_id),
    CONSTRAINT fk_qms_insp_meas_comp FOREIGN KEY (comp_cd) REFERENCES sys_comp (comp_cd),
    CONSTRAINT fk_qms_insp_meas_line FOREIGN KEY (insp_line_id) REFERENCES qms_insp_line (insp_line_id),
    CONSTRAINT fk_qms_insp_meas_gauge FOREIGN KEY (gauge_asset_id) REFERENCES mst_phys_asset (asset_id),
    CONSTRAINT fk_qms_insp_meas_void FOREIGN KEY (void_of_id) REFERENCES qms_insp_meas (meas_id),
    CONSTRAINT ck_qms_insp_meas_sample CHECK (sample_no >= 1),
    CONSTRAINT ck_qms_insp_meas_ok CHECK (ok_yn IS NULL OR ok_yn IN ('Y', 'N')),
    -- 값 행 = 숫자 또는 OK/NG 하나, 무효 행 = 빈 값(가리키는 행만)
    CONSTRAINT ck_qms_insp_meas_value CHECK (
        (void_of_id IS NULL AND ((value IS NULL) <> (ok_yn IS NULL)))
        OR (void_of_id IS NOT NULL AND value IS NULL AND ok_yn IS NULL AND gauge_asset_id IS NULL))
);
CREATE UNIQUE INDEX uq_qms_insp_meas_void ON qms_insp_meas (void_of_id) WHERE void_of_id IS NOT NULL;
CREATE INDEX ix_qms_insp_meas_line ON qms_insp_meas (insp_line_id, sample_no);
-- Q9 교정 NG 영향 LOT: 그 게이지로 언제 무엇을 쟀나
CREATE INDEX ix_qms_insp_meas_gauge ON qms_insp_meas (comp_cd, gauge_asset_id, occurred_at) WHERE gauge_asset_id IS NOT NULL;
COMMENT ON TABLE qms_insp_meas IS '측정값 원장 — 추가만, 정정 = 무효 행(void_of_id) 뒤 같은 샘플 번호로 다시 (docs/29_P3 Q6)';

CREATE FUNCTION hb_guard_qms_insp_meas() RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    v_line   qms_insp_line;
    v_status varchar;
    v_target qms_insp_meas;
    v_class  varchar;
BEGIN
    IF TG_OP <> 'INSERT' THEN
        RAISE EXCEPTION 'measurement ledger is append-only (void and re-measure)' USING ERRCODE = '55000', CONSTRAINT = 'ck_qms_insp_meas_append_only';
    END IF;
    -- 잠금 순서 = 판정 확정과 같게 검사 머리 → 검사 행: 머리 FOR SHARE(확정과 줄 세움) → 행 FOR UPDATE(같은 샘플 동시 입력 줄 세움)
    SELECT * INTO v_line FROM qms_insp_line WHERE insp_line_id = NEW.insp_line_id AND comp_cd = NEW.comp_cd;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'inspection line % does not belong to company %', NEW.insp_line_id, NEW.comp_cd
            USING ERRCODE = '23503', CONSTRAINT = 'fk_qms_insp_meas_line_comp';
    END IF;
    SELECT status INTO v_status FROM qms_insp WHERE insp_id = v_line.insp_id FOR SHARE;
    PERFORM 1 FROM qms_insp_line WHERE insp_line_id = NEW.insp_line_id FOR UPDATE;
    IF v_status <> 'OPEN' THEN
        RAISE EXCEPTION 'inspection is % — measurements are fixed', v_status USING ERRCODE = '55000', CONSTRAINT = 'ck_qms_insp_meas_open';
    END IF;
    IF NEW.sample_no > v_line.sample_size THEN
        RAISE EXCEPTION 'sample % is beyond sample size %', NEW.sample_no, v_line.sample_size USING ERRCODE = '23514', CONSTRAINT = 'ck_qms_insp_meas_sample_size';
    END IF;
    IF NEW.void_of_id IS NOT NULL THEN
        SELECT * INTO v_target FROM qms_insp_meas WHERE meas_id = NEW.void_of_id;
        IF NOT FOUND OR v_target.insp_line_id <> NEW.insp_line_id OR v_target.sample_no <> NEW.sample_no OR v_target.void_of_id IS NOT NULL THEN
            RAISE EXCEPTION 'void row must point to a value row of the same line and sample' USING ERRCODE = '23514', CONSTRAINT = 'ck_qms_insp_meas_void_target';
        END IF;
        RETURN NEW;
    END IF;
    IF (v_line.data_type = 'VARIABLE') <> (NEW.value IS NOT NULL) THEN
        RAISE EXCEPTION 'value kind must match data type %', v_line.data_type USING ERRCODE = '23514', CONSTRAINT = 'ck_qms_insp_meas_kind';
    END IF;
    IF EXISTS (SELECT 1 FROM qms_insp_meas m WHERE m.insp_line_id = NEW.insp_line_id AND m.sample_no = NEW.sample_no AND m.void_of_id IS NULL
                  AND NOT EXISTS (SELECT 1 FROM qms_insp_meas v WHERE v.void_of_id = m.meas_id)) THEN
        RAISE EXCEPTION 'sample % already has a live value (void it first)', NEW.sample_no USING ERRCODE = '23505', CONSTRAINT = 'uq_qms_insp_meas_live';
    END IF;
    IF v_line.gauge_class <> 'VISUAL' AND NEW.gauge_asset_id IS NULL THEN
        RAISE EXCEPTION 'gauge is required for class %', v_line.gauge_class USING ERRCODE = '23514', CONSTRAINT = 'ck_qms_insp_meas_gauge_required';
    END IF;
    IF NEW.gauge_asset_id IS NOT NULL THEN
        SELECT gauge_class INTO v_class FROM mst_phys_asset a
         WHERE a.asset_id = NEW.gauge_asset_id AND a.comp_cd = NEW.comp_cd AND a.asset_type = 'GAUGE';
        IF NOT FOUND THEN
            RAISE EXCEPTION 'gauge % does not belong to company %', NEW.gauge_asset_id, NEW.comp_cd USING ERRCODE = '23503', CONSTRAINT = 'fk_qms_insp_meas_gauge_comp';
        END IF;
        IF v_class IS DISTINCT FROM v_line.gauge_class THEN
            RAISE EXCEPTION 'gauge class % does not match line class %', v_class, v_line.gauge_class
                USING ERRCODE = '23514', CONSTRAINT = 'ck_qms_insp_meas_gauge_class';
        END IF;
    END IF;
    RETURN NEW;
END
$$;
CREATE TRIGGER tg_qms_insp_meas_guard BEFORE INSERT OR UPDATE OR DELETE ON qms_insp_meas FOR EACH ROW EXECUTE FUNCTION hb_guard_qms_insp_meas();

-- ---------------------------------------------------------------------
-- 4. 감사 · RLS · 권한 · 화면 · 보존
-- ---------------------------------------------------------------------
CREATE TRIGGER tg_audit AFTER INSERT OR UPDATE OR DELETE ON qms_insp FOR EACH ROW EXECUTE FUNCTION hb_audit('insp_id', '', '');
CREATE TRIGGER tg_audit AFTER INSERT OR UPDATE OR DELETE ON qms_insp_line FOR EACH ROW EXECUTE FUNCTION hb_audit('insp_line_id', '', '');
-- qms_insp_meas 는 원장(추가만, 행 자체가 이력) — 감사 트리거 없음

ALTER TABLE qms_insp ENABLE ROW LEVEL SECURITY;
ALTER TABLE qms_insp FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON qms_insp USING (comp_cd = hb_current_comp()) WITH CHECK (comp_cd = hb_current_comp());
ALTER TABLE qms_insp_line ENABLE ROW LEVEL SECURITY;
ALTER TABLE qms_insp_line FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON qms_insp_line USING (comp_cd = hb_current_comp()) WITH CHECK (comp_cd = hb_current_comp());
ALTER TABLE qms_insp_meas ENABLE ROW LEVEL SECURITY;
ALTER TABLE qms_insp_meas FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON qms_insp_meas USING (comp_cd = hb_current_comp()) WITH CHECK (comp_cd = hb_current_comp());

REVOKE DELETE, TRUNCATE ON qms_insp, qms_insp_line, qms_insp_meas FROM ${app_role};
REVOKE UPDATE ON qms_insp_meas FROM ${app_role};

INSERT INTO sys_prog (prog_cd, prog_nm_key, view_path, module_cd, actions, sort_no) VALUES
    ('qms-insp', 'prog.qmsInsp', 'qms/insp-exec', 'QMS', ARRAY['SEARCH','SAVE','DELETE']::varchar(20)[], 690);
INSERT INTO sys_retention_table (table_nm, class_cd) VALUES
    ('qms_insp', 'QUALITY_RECORD'), ('qms_insp_line', 'QUALITY_RECORD'), ('qms_insp_meas', 'QUALITY_RECORD');
