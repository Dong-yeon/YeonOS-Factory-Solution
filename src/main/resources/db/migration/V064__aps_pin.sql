-- =====================================================================
-- V9852 APS 3단계 — 간트 수동 고정·수주 납기 (docs/38 12장, 병합 때 재번호)
--
-- - aps_pin: (지시, 공정 순번) 하나에 사용 중 고정 하나 = 자원 + 시작 시각. 다음 계산이 먼저 자리 잡는다(A22·A23)
-- - aps_plan_op.pin_id·warn_cd: 계산이 쓴 고정과 알림(PIN_MOVED·PIN_IGNORED·PIN_PREV)
-- - aps_plan_wo.due_date·due_src: 계산 때 납기 사본(수주 행 납기, 없으면 지시 완료일 — A21)
-- - 지시·수주 표는 읽기만 — 이 파일은 남의 표를 바꾸지 않는다
-- =====================================================================

-- ---------------------------------------------------------------------
-- 1. 수동 고정
-- ---------------------------------------------------------------------
CREATE TABLE aps_pin (
    pin_id       bigint        GENERATED ALWAYS AS IDENTITY,
    comp_cd      varchar(20)   NOT NULL,
    plant_cd     varchar(20)   NOT NULL,
    wo_id        bigint        NOT NULL,
    op_seq       integer       NOT NULL,
    res_kind     varchar(6)    NOT NULL,
    equip_id     bigint,
    node_id      bigint,
    start_at     timestamptz   NOT NULL,
    src_plan_id  bigint        NOT NULL,
    remark       varchar(500),
    use_yn       char(1)       NOT NULL DEFAULT 'Y',
    row_ver      integer       NOT NULL DEFAULT 0,
    created_at   timestamptz   NOT NULL DEFAULT now(),
    created_by   varchar(50)   NOT NULL,
    updated_at   timestamptz   NOT NULL DEFAULT now(),
    updated_by   varchar(50)   NOT NULL,
    CONSTRAINT pk_aps_pin PRIMARY KEY (pin_id),
    CONSTRAINT fk_aps_pin_plant FOREIGN KEY (comp_cd, plant_cd) REFERENCES sys_plant (comp_cd, plant_cd),
    CONSTRAINT fk_aps_pin_wo FOREIGN KEY (wo_id) REFERENCES prd_wo (wo_id),
    CONSTRAINT fk_aps_pin_equip FOREIGN KEY (equip_id) REFERENCES mst_equip (equip_id),
    CONSTRAINT fk_aps_pin_node FOREIGN KEY (node_id) REFERENCES mst_equip_hier (node_id),
    CONSTRAINT fk_aps_pin_plan FOREIGN KEY (src_plan_id) REFERENCES aps_plan (plan_id),
    CONSTRAINT ck_aps_pin_use CHECK (use_yn IN ('Y', 'N')),
    CONSTRAINT ck_aps_pin_res CHECK ((res_kind = 'EQUIP' AND equip_id IS NOT NULL)
                                     OR (res_kind = 'NODE' AND node_id IS NOT NULL AND equip_id IS NULL)
                                     OR (res_kind = 'OUTSRC' AND equip_id IS NULL AND node_id IS NULL)),
    CONSTRAINT ck_aps_pin_start CHECK (start_at >= TIMESTAMPTZ '2000-01-01 00:00:00+00' AND start_at < TIMESTAMPTZ '2999-12-01 00:00:00+00'),
    CONSTRAINT ck_aps_pin_op CHECK (op_seq > 0)
);
COMMENT ON TABLE aps_pin IS 'APS 수동 고정 (docs/38 12장) — 다음 계산이 먼저 자리 잡는다. 계획 결과·지시는 바꾸지 않는다';
-- 공정 하나에 사용 중 고정 하나 — 동시에 두 사람이 고정하면 두 번째는 409
CREATE UNIQUE INDEX uq_aps_pin_op ON aps_pin (comp_cd, wo_id, op_seq) WHERE use_yn = 'Y';
CREATE INDEX ix_aps_pin_plant ON aps_pin (comp_cd, plant_cd, use_yn);

-- FK 는 RLS 를 무시한다 — 지시·설비·작업장·계획이 같은 회사·같은 공장인지 확인. 지시·공정 순번·만든 계획은 고정
CREATE FUNCTION hb_guard_aps_pin() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'UPDATE' AND (NEW.wo_id, NEW.op_seq, NEW.plant_cd, NEW.src_plan_id) IS DISTINCT FROM
                            (OLD.wo_id, OLD.op_seq, OLD.plant_cd, OLD.src_plan_id) THEN
        RAISE EXCEPTION 'pin % keeps its operation', OLD.pin_id USING ERRCODE = '55000', CONSTRAINT = 'ck_aps_pin_fixed';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM prd_wo w WHERE w.wo_id = NEW.wo_id AND w.comp_cd = NEW.comp_cd AND w.plant_cd = NEW.plant_cd)
       OR NOT EXISTS (SELECT 1 FROM aps_plan p WHERE p.plan_id = NEW.src_plan_id AND p.comp_cd = NEW.comp_cd
                         AND p.plant_cd = NEW.plant_cd)
       OR (NEW.equip_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM mst_equip e WHERE e.equip_id = NEW.equip_id
                         AND e.comp_cd = NEW.comp_cd AND e.plant_cd = NEW.plant_cd))
       OR (NEW.node_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM mst_equip_hier h WHERE h.node_id = NEW.node_id
                         AND h.comp_cd = NEW.comp_cd AND h.plant_cd = NEW.plant_cd)) THEN
        RAISE EXCEPTION 'pin refers to another company or plant row' USING ERRCODE = '23503', CONSTRAINT = 'fk_aps_pin_ref_comp';
    END IF;
    RETURN NEW;
END
$$;
CREATE TRIGGER tg_aps_pin_guard BEFORE INSERT OR UPDATE ON aps_pin FOR EACH ROW EXECUTE FUNCTION hb_guard_aps_pin();
CREATE TRIGGER tg_aps_pin_no_delete BEFORE DELETE ON aps_pin FOR EACH ROW EXECUTE FUNCTION hb_forbid_delete_master();
CREATE TRIGGER tg_audit AFTER INSERT OR UPDATE OR DELETE ON aps_pin FOR EACH ROW EXECUTE FUNCTION hb_audit('pin_id', '', '');

ALTER TABLE aps_pin ENABLE ROW LEVEL SECURITY;
ALTER TABLE aps_pin FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON aps_pin USING (comp_cd = hb_current_comp()) WITH CHECK (comp_cd = hb_current_comp());
REVOKE DELETE, TRUNCATE ON aps_pin FROM ${app_role};
INSERT INTO sys_retention_table (table_nm, class_cd) VALUES ('aps_pin', 'PLANNING');

-- ---------------------------------------------------------------------
-- 2. 계산 결과: 고정·알림, 납기 사본
-- ---------------------------------------------------------------------
ALTER TABLE aps_plan_op ADD COLUMN pin_id bigint;
ALTER TABLE aps_plan_op ADD COLUMN warn_cd varchar(12);
ALTER TABLE aps_plan_op ADD CONSTRAINT fk_aps_plan_op_pin FOREIGN KEY (pin_id) REFERENCES aps_pin (pin_id);
ALTER TABLE aps_plan_op ADD CONSTRAINT ck_aps_plan_op_warn CHECK (warn_cd IS NULL OR warn_cd IN ('PIN_MOVED', 'PIN_IGNORED', 'PIN_PREV'));
COMMENT ON COLUMN aps_plan_op.pin_id IS '이 계산이 자리 잡는 데 쓴 고정(무시한 고정도 — warn_cd PIN_IGNORED)';

ALTER TABLE aps_plan_wo ADD COLUMN due_date date;
ALTER TABLE aps_plan_wo ADD COLUMN due_src varchar(5);
-- 새 행부터 필수(이전 계획 행은 비어 있다)
ALTER TABLE aps_plan_wo ADD CONSTRAINT ck_aps_plan_wo_due
    CHECK (due_date IS NOT NULL AND coalesce(due_src, '') IN ('ORDER', 'WO')) NOT VALID;
COMMENT ON COLUMN aps_plan_wo.due_date IS '계산 때 납기 — 수주 행 납기, 수주 미연결이면 지시 계획 완료일(docs/38 A21)';

-- 결과 행의 고정도 같은 회사여야 한다(V9850 함수에 고정 확인을 덧붙여 교체)
CREATE OR REPLACE FUNCTION hb_guard_aps_result() RETURNS trigger
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
                          AND e.comp_cd = NEW.comp_cd))
           OR (NEW.pin_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM aps_pin n WHERE n.pin_id = NEW.pin_id
                          AND n.comp_cd = NEW.comp_cd AND n.wo_id = NEW.wo_id AND n.op_seq = NEW.op_seq)) THEN
            RAISE EXCEPTION 'plan operation refers to another company row' USING ERRCODE = '23503', CONSTRAINT = 'fk_aps_result_ref_comp';
        END IF;
    END IF;
    RETURN NEW;
END
$$;
