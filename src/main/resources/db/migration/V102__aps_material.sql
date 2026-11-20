-- =====================================================================
-- V9855 APS 5단계 ② — 자재 가용 확인 (docs/38 14.1~14.3, A27~A34 사용자 채택 2026-10-21, 병합 때 재번호)
--
-- - 계산이 MES WoMaterialQuery(남은 소요)·WMS StockQuery(가용 재고)·SRM PoSupplyQuery(입고 예정)를 읽는다 — 그 표들은 바꾸지 않는다
-- - 계획 매개변수: mat_mode WARN(기본 — 시각 그대로 부족 표시)·DELAY(충족 시각 뒤로, 기간 안 안 되면 MAT 예외), mat_po(입고 예정 포함, 기본 N)
-- - 결과 사본(계산 트랜잭션에서만 추가, 이후 불변 — A1·A34): aps_plan_mat(지시 × 공정 × 품목 할당), aps_plan_supply(계산에 쓴 공급 줄)
-- - 보존 정리(V095)가 새 결과 표도 지우도록 프로시저 교체
-- =====================================================================

-- ---------------------------------------------------------------------
-- 1. 계획 머리·지시·공정 결과 칸
-- ---------------------------------------------------------------------
ALTER TABLE aps_plan
    ADD COLUMN mat_mode varchar(5) NOT NULL DEFAULT 'WARN',
    ADD COLUMN mat_po char(1) NOT NULL DEFAULT 'N',
    ADD COLUMN mat_short_wo_cnt integer NOT NULL DEFAULT 0;
ALTER TABLE aps_plan ADD CONSTRAINT ck_aps_plan_mat CHECK (mat_mode IN ('WARN', 'DELAY') AND mat_po IN ('Y', 'N')
                                                          AND mat_short_wo_cnt BETWEEN 0 AND wo_cnt);
COMMENT ON COLUMN aps_plan.mat_mode IS '자재 부족 처리 — WARN 표시만(기본) / DELAY 충족 시각 뒤로(docs/38 A27)';
COMMENT ON COLUMN aps_plan.mat_po IS '입고 예정(SRM 열린 발주) 포함 여부 — 기본 N, SRM 입고 차감 전 이중 계산 방지(A30)';

-- 지시 결과: 자재 판정 — NULL(문제 없음·소요 없음) / SHORT(부족) / NO_BOM(계획 지시 전개 불가 — 소요를 모른다)
ALTER TABLE aps_plan_wo ADD COLUMN mat_cd varchar(6);
ALTER TABLE aps_plan_wo ADD CONSTRAINT ck_aps_plan_wo_mat CHECK (mat_cd IS NULL OR mat_cd IN ('SHORT', 'NO_BOM'));

-- 예외 코드에 MAT(DELAY 에서 계획 기간 안에 자재가 덮이지 않음)
ALTER TABLE aps_plan_wo DROP CONSTRAINT ck_aps_plan_wo_exc;
ALTER TABLE aps_plan_wo ADD CONSTRAINT ck_aps_plan_wo_exc CHECK (exc_cd IS NULL OR exc_cd IN ('NO_CT', 'NO_NODE', 'HORIZON', 'PREV', 'MAT'));
ALTER TABLE aps_plan_op DROP CONSTRAINT ck_aps_plan_op_exc;
ALTER TABLE aps_plan_op ADD CONSTRAINT ck_aps_plan_op_exc CHECK (exc_cd IS NULL OR exc_cd IN ('NO_CT', 'NO_NODE', 'HORIZON', 'PREV', 'MAT'));

-- V095 함수 교체: 자재 매개변수·부족 수도 계산 칸(고정)
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
        NEW.unsched_op_cnt, NEW.makespan_end, NEW.calc_ms, NEW.off_days,
        NEW.mat_mode, NEW.mat_po, NEW.mat_short_wo_cnt)
       IS DISTINCT FROM
       (OLD.plan_no, OLD.plant_cd, OLD.rule_cd, OLD.horizon_start, OLD.horizon_days, OLD.outsrc_lead_hr, OLD.time_zone,
        OLD.sched_from, OLD.sched_to, OLD.work_windows, OLD.calculated_at, OLD.calculated_by, OLD.wo_cnt, OLD.op_cnt, OLD.late_wo_cnt,
        OLD.unsched_op_cnt, OLD.makespan_end, OLD.calc_ms, OLD.off_days,
        OLD.mat_mode, OLD.mat_po, OLD.mat_short_wo_cnt) THEN
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
-- 2. 자재 결과 사본 (추가만, 계산 트랜잭션에서만)
-- ---------------------------------------------------------------------
CREATE TABLE aps_plan_mat (
    plan_mat_id  bigint         GENERATED ALWAYS AS IDENTITY,
    comp_cd      varchar(20)    NOT NULL,
    plan_id      bigint         NOT NULL,
    plan_wo_id   bigint         NOT NULL,
    wo_id        bigint         NOT NULL,
    op_seq       integer        NOT NULL,
    src_op_seq   integer,
    item_id      bigint         NOT NULL,
    need_qty     numeric(18,6)  NOT NULL,
    unit_cd      varchar(30)    NOT NULL,
    need_at      timestamptz,
    alloc_qty    numeric(18,6)  NOT NULL,
    short_qty    numeric(18,6)  NOT NULL,
    covered_at   timestamptz,
    CONSTRAINT pk_aps_plan_mat PRIMARY KEY (plan_mat_id),
    CONSTRAINT fk_aps_plan_mat_plan FOREIGN KEY (plan_id) REFERENCES aps_plan (plan_id),
    CONSTRAINT fk_aps_plan_mat_wo_row FOREIGN KEY (plan_wo_id) REFERENCES aps_plan_wo (plan_wo_id),
    CONSTRAINT fk_aps_plan_mat_wo FOREIGN KEY (wo_id) REFERENCES prd_wo (wo_id),
    CONSTRAINT fk_aps_plan_mat_item FOREIGN KEY (item_id) REFERENCES mst_item (item_id),
    CONSTRAINT uq_aps_plan_mat UNIQUE (comp_cd, plan_id, wo_id, op_seq, item_id),
    -- 계획된 공정: 할당 + 부족 = 필요. 계획 못 한 공정(need_at 없음): 판정 없음(할당·부족 0)
    CONSTRAINT ck_aps_plan_mat_qty CHECK (need_qty > 0 AND alloc_qty >= 0 AND short_qty >= 0
        AND ((need_at IS NOT NULL AND alloc_qty + short_qty = need_qty) OR (need_at IS NULL AND alloc_qty = 0 AND short_qty = 0)))
);
COMMENT ON TABLE aps_plan_mat IS 'APS 계획의 자재 할당(지시 × 공정 × 품목: 필요·필요 시각·할당·부족·충족 시각) — 추가만(docs/38 A34)';
CREATE INDEX ix_aps_plan_mat_short ON aps_plan_mat (comp_cd, plan_id) WHERE short_qty > 0;

CREATE TABLE aps_plan_supply (
    plan_supply_id  bigint         GENERATED ALWAYS AS IDENTITY,
    comp_cd         varchar(20)    NOT NULL,
    plan_id         bigint         NOT NULL,
    item_id         bigint         NOT NULL,
    kind            varchar(5)     NOT NULL,
    supply_at       timestamptz    NOT NULL,
    qty             numeric(18,6)  NOT NULL,
    unit_cd         varchar(30)    NOT NULL,
    po_no           varchar(50),
    po_line_no      integer,
    promised_yn     char(1)        NOT NULL DEFAULT 'N',
    base_unit_yn    char(1)        NOT NULL DEFAULT 'Y',
    CONSTRAINT pk_aps_plan_supply PRIMARY KEY (plan_supply_id),
    CONSTRAINT fk_aps_plan_supply_plan FOREIGN KEY (plan_id) REFERENCES aps_plan (plan_id),
    CONSTRAINT fk_aps_plan_supply_item FOREIGN KEY (item_id) REFERENCES mst_item (item_id),
    CONSTRAINT ck_aps_plan_supply_kind CHECK ((kind = 'STOCK' AND po_no IS NULL) OR (kind = 'PO' AND po_no IS NOT NULL)),
    CONSTRAINT ck_aps_plan_supply_qty CHECK (qty >= 0 AND promised_yn IN ('Y', 'N') AND base_unit_yn IN ('Y', 'N'))
);
COMMENT ON TABLE aps_plan_supply IS 'APS 계획이 쓴 공급(계산 시점 가용 재고·입고 예정, 기본 단위 아닌 줄은 base_unit_yn=N 으로 남기고 계산에서 뺌) — 추가만';
CREATE INDEX ix_aps_plan_supply_plan ON aps_plan_supply (comp_cd, plan_id, item_id);

-- 계산 트랜잭션에서만, 같은 회사의 계획·지시 결과·품목만(FK 는 RLS 를 무시한다)
CREATE FUNCTION hb_guard_aps_mat_result() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM aps_plan p WHERE p.plan_id = NEW.plan_id AND p.comp_cd = NEW.comp_cd
                      AND p.status = 'CALCULATED' AND p.calculated_at = now()) THEN
        RAISE EXCEPTION 'plan material rows are written only when calculating (plan %)', NEW.plan_id
            USING ERRCODE = '55000', CONSTRAINT = 'ck_aps_result_calc';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM mst_item i WHERE i.item_id = NEW.item_id AND i.comp_cd = NEW.comp_cd) THEN
        RAISE EXCEPTION 'plan material row refers to another company row' USING ERRCODE = '23503', CONSTRAINT = 'fk_aps_result_ref_comp';
    END IF;
    -- plpgsql 은 OR 의 다른 쪽에 있어도 NEW.칸 을 해석한다 — 표마다 따로 확인(aps_plan_supply 에는 plan_wo_id 가 없다)
    IF TG_TABLE_NAME = 'aps_plan_mat' THEN
        IF NOT EXISTS (SELECT 1 FROM aps_plan_wo x WHERE x.plan_wo_id = NEW.plan_wo_id
                          AND x.comp_cd = NEW.comp_cd AND x.plan_id = NEW.plan_id AND x.wo_id = NEW.wo_id) THEN
            RAISE EXCEPTION 'plan material row refers to another company row' USING ERRCODE = '23503', CONSTRAINT = 'fk_aps_result_ref_comp';
        END IF;
    END IF;
    RETURN NEW;
END
$$;
CREATE TRIGGER tg_aps_plan_mat_calc BEFORE INSERT ON aps_plan_mat FOR EACH ROW EXECUTE FUNCTION hb_guard_aps_mat_result();
CREATE TRIGGER tg_aps_plan_supply_calc BEFORE INSERT ON aps_plan_supply FOR EACH ROW EXECUTE FUNCTION hb_guard_aps_mat_result();
CREATE TRIGGER tg_aps_plan_mat_append_only BEFORE UPDATE OR DELETE ON aps_plan_mat FOR EACH ROW EXECUTE FUNCTION hb_guard_aps_result_append_only();
CREATE TRIGGER tg_aps_plan_supply_append_only BEFORE UPDATE OR DELETE ON aps_plan_supply FOR EACH ROW EXECUTE FUNCTION hb_guard_aps_result_append_only();

ALTER TABLE aps_plan_mat ENABLE ROW LEVEL SECURITY;
ALTER TABLE aps_plan_mat FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON aps_plan_mat USING (comp_cd = hb_current_comp()) WITH CHECK (comp_cd = hb_current_comp());
ALTER TABLE aps_plan_supply ENABLE ROW LEVEL SECURITY;
ALTER TABLE aps_plan_supply FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON aps_plan_supply USING (comp_cd = hb_current_comp()) WITH CHECK (comp_cd = hb_current_comp());
REVOKE UPDATE, DELETE, TRUNCATE ON aps_plan_mat, aps_plan_supply FROM ${app_role};
INSERT INTO sys_retention_table (table_nm, class_cd) VALUES ('aps_plan_mat', 'PLAN_RESULT'), ('aps_plan_supply', 'PLAN_RESULT');

-- ---------------------------------------------------------------------
-- 3. 보존 정리(V095) 교체 — 새 결과 표도 함께
-- ---------------------------------------------------------------------
CREATE OR REPLACE FUNCTION hb_purge_aps_results(p_now timestamptz DEFAULT now())
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
    n_mat  bigint;
    n_sup  bigint;
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
            DELETE FROM aps_plan_mat m WHERE m.comp_cd = c_cd AND m.plan_id = ANY (ids);
            GET DIAGNOSTICS n_mat = ROW_COUNT;
            DELETE FROM aps_plan_supply s WHERE s.comp_cd = c_cd AND s.plan_id = ANY (ids);
            GET DIAGNOSTICS n_sup = ROW_COUNT;
            DELETE FROM aps_plan_op o WHERE o.comp_cd = c_cd AND o.plan_id = ANY (ids);
            GET DIAGNOSTICS n_op = ROW_COUNT;
            DELETE FROM aps_plan_wo x WHERE x.comp_cd = c_cd AND x.plan_id = ANY (ids);
            GET DIAGNOSTICS n_wo = ROW_COUNT;
            UPDATE aps_plan a SET purged_at = now(), row_ver = row_ver + 1, updated_at = now(), updated_by = 'SYSTEM:purge'
             WHERE a.comp_cd = c_cd AND a.plan_id = ANY (ids);
            out_comp_cd := c_cd; out_plans := cardinality(ids); out_rows := n_op + n_wo + n_mat + n_sup;
            RETURN NEXT;
        END IF;
    END LOOP;
    PERFORM set_config('hb.aps_purge', '', true);
    PERFORM set_config('app.comp_cd', '', true);
END
$$;
