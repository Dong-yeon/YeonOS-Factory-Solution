-- =====================================================================
-- V9851 APS 2단계 — 계획 반영·낡은 계획 판정 (docs/38 11장, 병합 때 재번호)
--
-- - aps_plan: 상태 APPLIED(끝 상태) + 반영 시각·사람·건수. 반영 칸은 APPLIED 로 바뀌는 그 UPDATE 에서만(applied_at = now())
-- - aps_plan_wo.wo_row_ver · aps_plan_op.proc_row_ver: 계산 때 읽은 지시·지시 공정 row_ver 사본 — 반영 때 지금 값과 다르면 거부(A12)
-- - 지시(prd_wo·prd_wo_proc)는 이 파일이 바꾸지 않는다 — 반영은 MES 서비스(WoService.update·assign) 경유
-- =====================================================================

-- ---------------------------------------------------------------------
-- 1. 계획 머리: 반영
-- ---------------------------------------------------------------------
ALTER TABLE aps_plan
    ADD COLUMN applied_at      timestamptz,
    ADD COLUMN applied_by      varchar(50),
    ADD COLUMN applied_wo_cnt  integer,
    ADD COLUMN applied_op_cnt  integer;

ALTER TABLE aps_plan DROP CONSTRAINT ck_aps_plan_status;
ALTER TABLE aps_plan ADD CONSTRAINT ck_aps_plan_status CHECK (status IN ('CALCULATED', 'DISCARDED', 'APPLIED'));
-- 반영 칸은 APPLIED 일 때만, 그때는 전부(CHECK 는 NULL 을 통과로 보므로 coalesce 로)
ALTER TABLE aps_plan ADD CONSTRAINT ck_aps_plan_applied CHECK (
    (status = 'APPLIED' AND applied_at IS NOT NULL AND applied_by IS NOT NULL
         AND coalesce(applied_wo_cnt, -1) >= 0 AND coalesce(applied_op_cnt, -1) >= 0)
    OR (status <> 'APPLIED' AND applied_at IS NULL AND applied_by IS NULL AND applied_wo_cnt IS NULL AND applied_op_cnt IS NULL));

-- V9850 함수 교체: 새 행 = CALCULATED(반영 칸 비움), 계산 칸 고정, CALCULATED → DISCARDED·APPLIED 만, DISCARDED·APPLIED 는 끝
CREATE OR REPLACE FUNCTION hb_guard_aps_plan() RETURNS trigger
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
    IF OLD.status = 'APPLIED' THEN
        RAISE EXCEPTION 'plan % is applied', OLD.plan_no USING ERRCODE = '55000', CONSTRAINT = 'ck_aps_plan_applied_fixed';
    END IF;
    IF NEW.status <> OLD.status AND NOT (OLD.status = 'CALCULATED' AND NEW.status IN ('DISCARDED', 'APPLIED')) THEN
        RAISE EXCEPTION 'plan cannot go from % to %', OLD.status, NEW.status
            USING ERRCODE = '55000', CONSTRAINT = 'ck_aps_plan_transition';
    END IF;
    -- 반영 표식은 반영하는 그 트랜잭션의 시각(직접 SQL 로 지난 시각을 꾸며 넣지 못한다)
    IF NEW.status = 'APPLIED' AND NEW.applied_at <> now() THEN
        RAISE EXCEPTION 'plan % is applied only now', OLD.plan_no USING ERRCODE = '55000', CONSTRAINT = 'ck_aps_plan_applied_now';
    END IF;
    RETURN NEW;
END
$$;

-- ---------------------------------------------------------------------
-- 2. 결과 행: 계산 때 row_ver 사본 (1단계 행은 비어 있다 → NOT VALID, 그 계획은 반영 불가)
-- ---------------------------------------------------------------------
ALTER TABLE aps_plan_wo ADD COLUMN wo_row_ver integer;
ALTER TABLE aps_plan_wo ADD CONSTRAINT ck_aps_plan_wo_row_ver CHECK (coalesce(wo_row_ver, -1) >= 0) NOT VALID;
COMMENT ON COLUMN aps_plan_wo.wo_row_ver IS '계산 때 지시 row_ver — 반영 때 다르면 낡은 계획(docs/38 A12)';

ALTER TABLE aps_plan_op ADD COLUMN proc_row_ver integer;
ALTER TABLE aps_plan_op ADD CONSTRAINT ck_aps_plan_op_row_ver
    CHECK ((wo_proc_id IS NULL) = (proc_row_ver IS NULL) AND coalesce(proc_row_ver, 0) >= 0) NOT VALID;
COMMENT ON COLUMN aps_plan_op.proc_row_ver IS '계산 때 지시 공정 row_ver(발행 지시만) — 반영 때 다르면 낡은 계획';

-- ---------------------------------------------------------------------
-- 3. 화면: 반영 = APPROVE (+ 서비스가 prd-wo SAVE 도 확인, A13)
-- ---------------------------------------------------------------------
UPDATE sys_prog SET actions = ARRAY['SEARCH','SAVE','APPROVE']::varchar(20)[] WHERE prog_cd = 'aps-plan';
