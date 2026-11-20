-- =====================================================================
-- V9853 APS 4단계 — 공장 휴일 달력 반영 (docs/38 13장, 병합 때 재번호)
--
-- - 계산은 MES WorkCalendar(V086 mst_work_cal)를 읽어 날마다 일하는 근무조만 펼친다 — 이 파일은 그 표를 바꾸지 않는다
-- - aps_plan.off_days: 계산 때 계획 기간 안 쉬는 날 사본(날짜·근거·사유) — 달력이 바뀌어도 간트 휴일 표시는 계산 때 그대로(A26)
-- =====================================================================

ALTER TABLE aps_plan ADD COLUMN off_days jsonb NOT NULL DEFAULT '[]';
ALTER TABLE aps_plan ADD CONSTRAINT ck_aps_plan_off_days CHECK (jsonb_typeof(off_days) = 'array');
COMMENT ON COLUMN aps_plan.off_days IS '계산 때 계획 기간 안 쉬는 날 [{day, source, reason}] — 공장 휴일 달력(docs/38 A26)';

-- V059 함수 교체: off_days 도 계산 칸(고정)
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
        NEW.unsched_op_cnt, NEW.makespan_end, NEW.calc_ms, NEW.off_days)
       IS DISTINCT FROM
       (OLD.plan_no, OLD.plant_cd, OLD.rule_cd, OLD.horizon_start, OLD.horizon_days, OLD.outsrc_lead_hr, OLD.time_zone,
        OLD.sched_from, OLD.sched_to, OLD.work_windows, OLD.calculated_at, OLD.calculated_by, OLD.wo_cnt, OLD.op_cnt, OLD.late_wo_cnt,
        OLD.unsched_op_cnt, OLD.makespan_end, OLD.calc_ms, OLD.off_days) THEN
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
