-- =====================================================================
-- V9854 APS 5단계 ① — 계획 결과 보존 정리 (docs/38 14.4, A35·A36 사용자 채택 2026-10-21, 병합 때 재번호)
--
-- - 새 보존 클래스 PLAN_RESULT(기본 90일·최소 30일, 회사 sys_retention_policy 로 연장, 법적 보류 존중)
--   기준 = 'KEEP' — hb_purge_logs(OCCURRED_AT 칸 기준)가 손대지 않는다. 정리는 APS 전용 프로시저만
-- - 대상: 폐기·미반영(CALCULATED) 계획 중 계산 뒤 보존 기간이 지난 것의 결과 행(aps_plan_wo·aps_plan_op).
--   계획 머리는 남기고 purged_at 표시, 반영된 계획·사용 중 고정이 출처로 쓰는 계획은 결과도 보존
-- - 정리는 소유자 전용 hb_purge_aps_results()(앱 EXECUTE 없음, hb_purge_logs 와 같은 운영 도구가 실행).
--   추가만 트리거는 트랜잭션 로컬 표식 hb.aps_purge = 'Y' 일 때만 DELETE 통과 — 앱 계정은 DELETE 권한 자체가 없다
-- =====================================================================

-- ---------------------------------------------------------------------
-- 1. 보존 클래스
-- ---------------------------------------------------------------------
-- 기준 KEEP = 공통 정리(hb_purge_logs) 대상 아님, hb_purge_aps_results() 가 기간 판정(상위 세션 판단 2026-10-21)
INSERT INTO sys_retention_class (class_cd, basis, default_days, min_days, purge_allowed_yn, desc_key) VALUES
    ('PLAN_RESULT', 'KEEP', 90, 30, 'Y', 'retention.PLAN_RESULT');
UPDATE sys_retention_table SET class_cd = 'PLAN_RESULT' WHERE table_nm IN ('aps_plan_wo', 'aps_plan_op');

-- ---------------------------------------------------------------------
-- 2. 계획 머리: 정리 표시
-- ---------------------------------------------------------------------
ALTER TABLE aps_plan ADD COLUMN purged_at timestamptz;
COMMENT ON COLUMN aps_plan.purged_at IS '결과 행을 보존 기간이 지나 정리한 시각(머리·요약은 남는다, docs/38 A35)';

-- V9853(V087) 함수 교체: purged_at 은 정리 프로시저만(표식 + 결과 행이 이미 없음), 비어 있을 때 한 번, 반영된 계획 제외
CREATE OR REPLACE FUNCTION hb_guard_aps_plan() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'INSERT' THEN
        IF NEW.status <> 'CALCULATED' OR NEW.purged_at IS NOT NULL THEN
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
    IF NEW.purged_at IS DISTINCT FROM OLD.purged_at THEN
        IF OLD.purged_at IS NOT NULL OR NEW.status <> OLD.status OR OLD.status = 'APPLIED'
           OR coalesce(current_setting('hb.aps_purge', true), '') <> 'Y'
           OR EXISTS (SELECT 1 FROM aps_plan_wo x WHERE x.plan_id = OLD.plan_id) THEN
            RAISE EXCEPTION 'plan % is purged only by the retention procedure', OLD.plan_no
                USING ERRCODE = '55000', CONSTRAINT = 'ck_aps_plan_purge';
        END IF;
        RETURN NEW;
    END IF;
    IF OLD.status = 'DISCARDED' THEN
        RAISE EXCEPTION 'plan % is discarded', OLD.plan_no USING ERRCODE = '55000', CONSTRAINT = 'ck_aps_plan_discarded';
    END IF;
    IF OLD.status = 'APPLIED' THEN
        RAISE EXCEPTION 'plan % is applied', OLD.plan_no USING ERRCODE = '55000', CONSTRAINT = 'ck_aps_plan_applied_fixed';
    END IF;
    IF OLD.purged_at IS NOT NULL AND NEW.status = 'APPLIED' THEN
        RAISE EXCEPTION 'plan % has no results to apply', OLD.plan_no USING ERRCODE = '55000', CONSTRAINT = 'ck_aps_plan_purged';
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
-- 3. 결과 행: 추가만 — 정리 표식이 있는 트랜잭션의 DELETE 만 통과(앱 계정은 DELETE 권한이 없어 표식을 세워도 못 지운다)
-- ---------------------------------------------------------------------
CREATE FUNCTION hb_guard_aps_result_append_only() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'DELETE' AND coalesce(current_setting('hb.aps_purge', true), '') = 'Y' THEN
        RETURN OLD;
    END IF;
    RAISE EXCEPTION 'plan result table % is append-only', TG_TABLE_NAME
        USING ERRCODE = '23514', CONSTRAINT = 'ck_aps_result_append_only';
END
$$;
DROP TRIGGER tg_aps_plan_wo_append_only ON aps_plan_wo;
DROP TRIGGER tg_aps_plan_op_append_only ON aps_plan_op;
CREATE TRIGGER tg_aps_plan_wo_append_only BEFORE UPDATE OR DELETE ON aps_plan_wo FOR EACH ROW EXECUTE FUNCTION hb_guard_aps_result_append_only();
CREATE TRIGGER tg_aps_plan_op_append_only BEFORE UPDATE OR DELETE ON aps_plan_op FOR EACH ROW EXECUTE FUNCTION hb_guard_aps_result_append_only();

-- ---------------------------------------------------------------------
-- 4. 정리 프로시저 — 소유자 계정으로만(앱 EXECUTE 회수). 회사마다 범위를 열고 정리(hb_purge_logs 와 같은 수법)
-- ---------------------------------------------------------------------
CREATE FUNCTION hb_purge_aps_results(p_now timestamptz DEFAULT now())
    RETURNS TABLE (out_comp_cd varchar, out_plans bigint, out_rows bigint)
    LANGUAGE plpgsql
AS $$
DECLARE
    comps  varchar[];
    c_cd   varchar;
    keep   integer;
    ids    bigint[];
    n_op   bigint;
    n_wo   bigint;
BEGIN
    -- 회사 목록을 먼저 배열로(루프 중 범위를 바꾸므로 커서로 읽으면 RLS 판정이 달라질 수 있다)
    PERFORM set_config('app.comp_cd', '', true);
    PERFORM set_config('app.scope', 'SYSTEM', true);
    comps := ARRAY(SELECT c.comp_cd FROM sys_comp c ORDER BY c.comp_cd);
    PERFORM set_config('app.scope', '', true);
    PERFORM set_config('hb.aps_purge', 'Y', true);

    FOREACH c_cd IN ARRAY comps LOOP
        PERFORM set_config('app.comp_cd', c_cd, true);
        SELECT greatest(coalesce(p.keep_days, k.default_days), k.min_days) INTO keep
          FROM sys_retention_class k
          LEFT JOIN sys_retention_policy p ON p.comp_cd = c_cd AND p.class_cd = k.class_cd
         WHERE k.class_cd = 'PLAN_RESULT';
        -- 대상 계획을 먼저 배열로 — 반영된 계획·이미 정리됨·사용 중 고정의 출처·법적 보류 기간 안은 제외
        ids := ARRAY(
            SELECT a.plan_id FROM aps_plan a
             WHERE a.comp_cd = c_cd AND a.status IN ('CALCULATED', 'DISCARDED') AND a.purged_at IS NULL
               AND a.calculated_at < p_now - make_interval(days => keep)
               AND NOT EXISTS (SELECT 1 FROM aps_pin n WHERE n.src_plan_id = a.plan_id AND n.use_yn = 'Y')
               AND NOT EXISTS (SELECT 1 FROM sys_legal_hold h
                                WHERE h.comp_cd = a.comp_cd AND h.class_cd = 'PLAN_RESULT' AND h.released_at IS NULL
                                  AND a.calculated_at >= h.from_at AND a.calculated_at < h.to_at)
             ORDER BY a.plan_id);
        IF cardinality(ids) > 0 THEN
            DELETE FROM aps_plan_op o WHERE o.comp_cd = c_cd AND o.plan_id = ANY (ids);
            GET DIAGNOSTICS n_op = ROW_COUNT;
            DELETE FROM aps_plan_wo x WHERE x.comp_cd = c_cd AND x.plan_id = ANY (ids);
            GET DIAGNOSTICS n_wo = ROW_COUNT;
            UPDATE aps_plan a SET purged_at = now(), row_ver = row_ver + 1, updated_at = now(), updated_by = 'SYSTEM:purge'
             WHERE a.comp_cd = c_cd AND a.plan_id = ANY (ids);
            out_comp_cd := c_cd; out_plans := cardinality(ids); out_rows := n_op + n_wo;
            RETURN NEXT;
        END IF;
    END LOOP;
    PERFORM set_config('hb.aps_purge', '', true);
    PERFORM set_config('app.comp_cd', '', true);
END
$$;
COMMENT ON FUNCTION hb_purge_aps_results(timestamptz) IS 'APS 계획 결과 보존 정리 — 소유자 전용(앱 EXECUTE 없음), 반영 계획·고정 출처·법적 보류 제외, 머리는 남김(docs/38 A35·A36)';
REVOKE EXECUTE ON FUNCTION hb_purge_aps_results(timestamptz) FROM PUBLIC, ${app_role};
