-- =====================================================================
-- V9850 APS 1단계 — 계획 버전·유한 용량 순방향 스케줄 결과 (docs/38 4장, 병합 때 재번호)
--
-- - aps_plan: 한 번의 계산 = 한 버전. 다시 계산하면 새 버전, 버전은 폐기만(CALCULATED → DISCARDED)
-- - aps_plan_wo·aps_plan_op: 계산 결과 — **계산하는 그 트랜잭션에서만** 추가(calculated_at = now()), 그 뒤 수정·삭제 없음
-- - 지시(prd_wo·prd_wo_proc)는 읽기만 한다 — 이 파일은 남의 표를 바꾸지 않는다
-- =====================================================================

-- 계산 결과(시뮬레이션): 현장 사실이 아니므로 원장 규약 대상이 아니다. 1단계는 정리하지 않는다(docs/38 A9)
INSERT INTO sys_retention_class (class_cd, basis, default_days, min_days, purge_allowed_yn, desc_key) VALUES
    ('PLANNING', 'KEEP', NULL, NULL, 'N', 'retention.PLANNING');

-- ---------------------------------------------------------------------
-- 1. 계획 버전
-- ---------------------------------------------------------------------
CREATE TABLE aps_plan (
    plan_id         bigint        GENERATED ALWAYS AS IDENTITY,
    comp_cd         varchar(20)   NOT NULL,
    plan_no         varchar(50)   NOT NULL,
    plant_cd        varchar(20)   NOT NULL,
    plan_nm         varchar(100),
    rule_cd         varchar(20)   NOT NULL DEFAULT 'PRIO_DUE',
    horizon_start   date          NOT NULL,
    horizon_days    smallint      NOT NULL,
    outsrc_lead_hr  smallint      NOT NULL DEFAULT 24,
    time_zone       varchar(50)   NOT NULL,
    sched_from      timestamptz   NOT NULL,
    sched_to        timestamptz   NOT NULL,
    work_windows    jsonb         NOT NULL,
    status          varchar(12)   NOT NULL DEFAULT 'CALCULATED',
    calculated_at   timestamptz   NOT NULL DEFAULT now(),
    calculated_by   varchar(50)   NOT NULL,
    wo_cnt          integer       NOT NULL,
    op_cnt          integer       NOT NULL,
    late_wo_cnt     integer       NOT NULL,
    unsched_op_cnt  integer       NOT NULL,
    makespan_end    timestamptz,
    calc_ms         integer       NOT NULL,
    discard_reason  varchar(500),
    remark          varchar(1000),
    row_ver         integer       NOT NULL DEFAULT 0,
    created_at      timestamptz   NOT NULL DEFAULT now(),
    created_by      varchar(50)   NOT NULL,
    updated_at      timestamptz   NOT NULL DEFAULT now(),
    updated_by      varchar(50)   NOT NULL,
    CONSTRAINT pk_aps_plan PRIMARY KEY (plan_id),
    CONSTRAINT fk_aps_plan_plant FOREIGN KEY (comp_cd, plant_cd) REFERENCES sys_plant (comp_cd, plant_cd),
    CONSTRAINT uq_aps_plan_no UNIQUE (comp_cd, plan_no),
    CONSTRAINT ck_aps_plan_rule CHECK (rule_cd IN ('PRIO_DUE')),
    CONSTRAINT ck_aps_plan_horizon CHECK (horizon_days BETWEEN 1 AND 31
                                          AND horizon_start BETWEEN DATE '2000-01-01' AND DATE '2999-12-01'),
    CONSTRAINT ck_aps_plan_lead CHECK (outsrc_lead_hr BETWEEN 0 AND 720),
    CONSTRAINT ck_aps_plan_window CHECK (sched_from <= sched_to),
    -- 계산에 쓴 근무 시간 구간 [[시작 epoch 초, 끝 epoch 초], …] — 근무조가 나중에 바뀌어도 간트 음영은 계산 때 그대로
    CONSTRAINT ck_aps_plan_windows CHECK (jsonb_typeof(work_windows) = 'array'),
    CONSTRAINT ck_aps_plan_counts CHECK (wo_cnt >= 0 AND op_cnt >= 0 AND late_wo_cnt BETWEEN 0 AND wo_cnt
                                         AND unsched_op_cnt BETWEEN 0 AND op_cnt AND calc_ms >= 0),
    CONSTRAINT ck_aps_plan_status CHECK (status IN ('CALCULATED', 'DISCARDED')),
    CONSTRAINT ck_aps_plan_discard CHECK ((status = 'DISCARDED') = (discard_reason IS NOT NULL AND length(btrim(discard_reason)) > 0))
);
COMMENT ON TABLE aps_plan IS 'APS 계획 버전 (docs/38). 한 번의 계산 = 한 행, 다시 계산하면 새 행. 지시는 바꾸지 않는다(시뮬레이션)';
CREATE INDEX ix_aps_plan_calc ON aps_plan (comp_cd, calculated_at, plan_id);

-- 새 행은 CALCULATED, 계산 칸(입력·결과 요약)은 고정 — 상태(폐기)·폐기 사유·이름·비고만
CREATE FUNCTION hb_guard_aps_plan() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'INSERT' THEN
        IF NEW.status <> 'CALCULATED' THEN
            RAISE EXCEPTION 'a new plan starts calculated' USING ERRCODE = '23514', CONSTRAINT = 'ck_aps_plan_status_new';
        END IF;
        RETURN NEW;
    END IF;
    IF (NEW.plan_no, NEW.plant_cd, NEW.rule_cd, NEW.horizon_start, NEW.horizon_days, NEW.outsrc_lead_hr, NEW.time_zone,
        NEW.sched_from, NEW.sched_to, NEW.work_windows, NEW.calculated_at, NEW.calculated_by, NEW.wo_cnt, NEW.op_cnt, NEW.late_wo_cnt,
        NEW.unsched_op_cnt, NEW.makespan_end, NEW.calc_ms)
       IS DISTINCT FROM
       (OLD.plan_no, OLD.plant_cd, OLD.rule_cd, OLD.horizon_start, OLD.horizon_days, OLD.outsrc_lead_hr, OLD.time_zone,
        OLD.sched_from, OLD.sched_to, OLD.work_windows, OLD.calculated_at, OLD.calculated_by, OLD.wo_cnt, OLD.op_cnt, OLD.late_wo_cnt,
        OLD.unsched_op_cnt, OLD.makespan_end, OLD.calc_ms) THEN
        RAISE EXCEPTION 'plan % keeps its calculation', OLD.plan_no USING ERRCODE = '55000', CONSTRAINT = 'ck_aps_plan_fixed';
    END IF;
    IF OLD.status = 'DISCARDED' THEN
        RAISE EXCEPTION 'plan % is discarded', OLD.plan_no USING ERRCODE = '55000', CONSTRAINT = 'ck_aps_plan_discarded';
    END IF;
    IF NEW.status <> OLD.status AND NOT (OLD.status = 'CALCULATED' AND NEW.status = 'DISCARDED') THEN
        RAISE EXCEPTION 'plan cannot go from % to %', OLD.status, NEW.status
            USING ERRCODE = '55000', CONSTRAINT = 'ck_aps_plan_transition';
    END IF;
    RETURN NEW;
END
$$;
CREATE TRIGGER tg_aps_plan_guard BEFORE INSERT OR UPDATE ON aps_plan FOR EACH ROW EXECUTE FUNCTION hb_guard_aps_plan();
CREATE TRIGGER tg_aps_plan_no_delete BEFORE DELETE ON aps_plan FOR EACH ROW EXECUTE FUNCTION hb_forbid_delete_master();

-- ---------------------------------------------------------------------
-- 2. 계산 결과 (추가만)
-- ---------------------------------------------------------------------
CREATE TABLE aps_plan_wo (
    plan_wo_id  bigint         GENERATED ALWAYS AS IDENTITY,
    comp_cd     varchar(20)    NOT NULL,
    plan_id     bigint         NOT NULL,
    seq         integer        NOT NULL,
    wo_id       bigint         NOT NULL,
    wo_no       varchar(50)    NOT NULL,
    wo_status   varchar(12)    NOT NULL,
    item_id     bigint         NOT NULL,
    plan_qty    numeric(18,6)  NOT NULL,
    unit_cd     varchar(30)    NOT NULL,
    priority    smallint       NOT NULL,
    plan_start  date           NOT NULL,
    plan_end    date           NOT NULL,
    start_at    timestamptz,
    end_at      timestamptz,
    late_yn     char(1)        NOT NULL DEFAULT 'N',
    exc_cd      varchar(10),
    CONSTRAINT pk_aps_plan_wo PRIMARY KEY (plan_wo_id),
    CONSTRAINT fk_aps_plan_wo_plan FOREIGN KEY (plan_id) REFERENCES aps_plan (plan_id),
    CONSTRAINT fk_aps_plan_wo_wo FOREIGN KEY (wo_id) REFERENCES prd_wo (wo_id),
    CONSTRAINT fk_aps_plan_wo_item FOREIGN KEY (item_id) REFERENCES mst_item (item_id),
    CONSTRAINT uq_aps_plan_wo UNIQUE (comp_cd, plan_id, wo_id),
    CONSTRAINT uq_aps_plan_wo_seq UNIQUE (comp_cd, plan_id, seq),
    CONSTRAINT ck_aps_plan_wo_time CHECK ((start_at IS NULL) = (end_at IS NULL) AND (start_at IS NULL OR start_at <= end_at)),
    CONSTRAINT ck_aps_plan_wo_late CHECK (late_yn IN ('Y', 'N')),
    CONSTRAINT ck_aps_plan_wo_exc CHECK (exc_cd IS NULL OR exc_cd IN ('NO_CT', 'NO_NODE', 'HORIZON', 'PREV'))
);
COMMENT ON TABLE aps_plan_wo IS 'APS 계획의 지시별 결과(계산 때 지시 값 사본 + 규칙 순번·시작·끝·늦음) — 추가만';

CREATE TABLE aps_plan_op (
    plan_op_id  bigint         GENERATED ALWAYS AS IDENTITY,
    comp_cd     varchar(20)    NOT NULL,
    plan_id     bigint         NOT NULL,
    plan_wo_id  bigint         NOT NULL,
    wo_id       bigint         NOT NULL,
    wo_proc_id  bigint,
    op_seq      integer        NOT NULL,
    proc_id     bigint         NOT NULL,
    proc_cd     varchar(20)    NOT NULL,
    proc_nm     varchar(100)   NOT NULL,
    node_id     bigint,
    equip_id    bigint,
    res_kind    varchar(6),
    qty         numeric(18,6)  NOT NULL,
    setup_sec   integer        NOT NULL,
    run_sec     integer        NOT NULL,
    start_at    timestamptz,
    end_at      timestamptz,
    exc_cd      varchar(10),
    CONSTRAINT pk_aps_plan_op PRIMARY KEY (plan_op_id),
    CONSTRAINT fk_aps_plan_op_plan FOREIGN KEY (plan_id) REFERENCES aps_plan (plan_id),
    CONSTRAINT fk_aps_plan_op_wo_row FOREIGN KEY (plan_wo_id) REFERENCES aps_plan_wo (plan_wo_id),
    CONSTRAINT fk_aps_plan_op_wo FOREIGN KEY (wo_id) REFERENCES prd_wo (wo_id),
    CONSTRAINT fk_aps_plan_op_wo_proc FOREIGN KEY (wo_proc_id) REFERENCES prd_wo_proc (wo_proc_id),
    CONSTRAINT fk_aps_plan_op_proc FOREIGN KEY (proc_id) REFERENCES mst_proc (proc_id),
    CONSTRAINT fk_aps_plan_op_node FOREIGN KEY (node_id) REFERENCES mst_equip_hier (node_id),
    CONSTRAINT fk_aps_plan_op_equip FOREIGN KEY (equip_id) REFERENCES mst_equip (equip_id),
    CONSTRAINT uq_aps_plan_op UNIQUE (comp_cd, plan_id, wo_id, op_seq),
    CONSTRAINT ck_aps_plan_op_qty CHECK (qty > 0 AND setup_sec >= 0 AND run_sec >= 0),
    CONSTRAINT ck_aps_plan_op_time CHECK ((start_at IS NULL) = (end_at IS NULL) AND (start_at IS NULL OR start_at < end_at
                                          OR (start_at = end_at AND setup_sec + run_sec = 0))),
    -- 계획된 공정은 자원이 있고 예외가 없다, 계획 못 한 공정은 시각 없이 예외 코드
    CONSTRAINT ck_aps_plan_op_result CHECK ((start_at IS NOT NULL AND exc_cd IS NULL AND res_kind IS NOT NULL)
                                            OR (start_at IS NULL AND exc_cd IS NOT NULL)),
    CONSTRAINT ck_aps_plan_op_res CHECK (res_kind IS NULL
                                         OR (res_kind = 'EQUIP' AND equip_id IS NOT NULL)
                                         OR (res_kind = 'NODE' AND node_id IS NOT NULL AND equip_id IS NULL)
                                         OR (res_kind = 'OUTSRC' AND equip_id IS NULL)),
    CONSTRAINT ck_aps_plan_op_exc CHECK (exc_cd IS NULL OR exc_cd IN ('NO_CT', 'NO_NODE', 'HORIZON', 'PREV'))
);
COMMENT ON TABLE aps_plan_op IS 'APS 계획의 공정별 결과(자원·시작·끝 또는 예외) — 추가만';
CREATE INDEX ix_aps_plan_op_equip ON aps_plan_op (comp_cd, plan_id, equip_id, start_at);

-- 결과는 같은 회사의 계획을 **계산하는 트랜잭션에서만** 넣는다(calculated_at = now() = 트랜잭션 시작 시각).
-- FK 는 RLS 를 무시하므로 지시·설비·작업장·공정이 같은 회사인지도 여기서 확인한다
CREATE FUNCTION hb_guard_aps_result() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM aps_plan p WHERE p.plan_id = NEW.plan_id AND p.comp_cd = NEW.comp_cd
                      AND p.status = 'CALCULATED' AND p.calculated_at = now()) THEN
        RAISE EXCEPTION 'plan result rows are written only when calculating (plan %)', NEW.plan_id
            USING ERRCODE = '55000', CONSTRAINT = 'ck_aps_result_calc';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM prd_wo w WHERE w.wo_id = NEW.wo_id AND w.comp_cd = NEW.comp_cd) THEN
        RAISE EXCEPTION 'work order % is not of company %', NEW.wo_id, NEW.comp_cd
            USING ERRCODE = '23503', CONSTRAINT = 'fk_aps_result_wo_comp';
    END IF;
    IF TG_TABLE_NAME = 'aps_plan_op' THEN
        IF NOT EXISTS (SELECT 1 FROM aps_plan_wo x WHERE x.plan_wo_id = NEW.plan_wo_id AND x.comp_cd = NEW.comp_cd
                          AND x.plan_id = NEW.plan_id AND x.wo_id = NEW.wo_id)
           OR NOT EXISTS (SELECT 1 FROM mst_proc r WHERE r.proc_id = NEW.proc_id AND r.comp_cd = NEW.comp_cd)
           OR (NEW.wo_proc_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM prd_wo_proc q WHERE q.wo_proc_id = NEW.wo_proc_id
                          AND q.comp_cd = NEW.comp_cd AND q.wo_id = NEW.wo_id))
           OR (NEW.node_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM mst_equip_hier h WHERE h.node_id = NEW.node_id
                          AND h.comp_cd = NEW.comp_cd))
           OR (NEW.equip_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM mst_equip e WHERE e.equip_id = NEW.equip_id
                          AND e.comp_cd = NEW.comp_cd)) THEN
            RAISE EXCEPTION 'plan operation refers to another company row' USING ERRCODE = '23503', CONSTRAINT = 'fk_aps_result_ref_comp';
        END IF;
    END IF;
    RETURN NEW;
END
$$;
CREATE TRIGGER tg_aps_plan_wo_calc BEFORE INSERT ON aps_plan_wo FOR EACH ROW EXECUTE FUNCTION hb_guard_aps_result();
CREATE TRIGGER tg_aps_plan_op_calc BEFORE INSERT ON aps_plan_op FOR EACH ROW EXECUTE FUNCTION hb_guard_aps_result();
CREATE TRIGGER tg_aps_plan_wo_append_only BEFORE UPDATE OR DELETE ON aps_plan_wo FOR EACH ROW EXECUTE FUNCTION hb_forbid_update_delete();
CREATE TRIGGER tg_aps_plan_op_append_only BEFORE UPDATE OR DELETE ON aps_plan_op FOR EACH ROW EXECUTE FUNCTION hb_forbid_update_delete();

-- ---------------------------------------------------------------------
-- 3. 감사 · RLS · 권한 · 화면 · 보존
-- ---------------------------------------------------------------------
CREATE TRIGGER tg_audit AFTER INSERT OR UPDATE OR DELETE ON aps_plan FOR EACH ROW EXECUTE FUNCTION hb_audit('plan_id', '', '');

ALTER TABLE aps_plan ENABLE ROW LEVEL SECURITY;
ALTER TABLE aps_plan FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON aps_plan USING (comp_cd = hb_current_comp()) WITH CHECK (comp_cd = hb_current_comp());
ALTER TABLE aps_plan_wo ENABLE ROW LEVEL SECURITY;
ALTER TABLE aps_plan_wo FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON aps_plan_wo USING (comp_cd = hb_current_comp()) WITH CHECK (comp_cd = hb_current_comp());
ALTER TABLE aps_plan_op ENABLE ROW LEVEL SECURITY;
ALTER TABLE aps_plan_op FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON aps_plan_op USING (comp_cd = hb_current_comp()) WITH CHECK (comp_cd = hb_current_comp());
REVOKE DELETE, TRUNCATE ON aps_plan FROM ${app_role};
REVOKE UPDATE, DELETE, TRUNCATE ON aps_plan_wo, aps_plan_op FROM ${app_role};

INSERT INTO sys_prog (prog_cd, prog_nm_key, view_path, module_cd, actions, sort_no) VALUES
    ('aps-plan', 'prog.apsPlan', 'aps/plan-gantt', 'APS', ARRAY['SEARCH','SAVE']::varchar(20)[], 970);
INSERT INTO sys_retention_table (table_nm, class_cd) VALUES
    ('aps_plan', 'PLANNING'), ('aps_plan_wo', 'PLANNING'), ('aps_plan_op', 'PLANNING');
