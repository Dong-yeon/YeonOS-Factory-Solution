-- =====================================================================
-- V232(임시 V9857) APS 7단계 — MPS 기준생산계획 (docs/38 16장, docs/62 A1·AP-1~4)
--
-- - aps_mps_item: MPS 품목(공장 × 품목) + 계획 기준정보(안전재고·로트 규칙). 품목은 회사에서 한 공장 MPS 에만(M1)
-- - aps_mps: MPS 버전 머리 — DRAFT → FIRMED → SUPERSEDED / DRAFT → DISCARDED, 공장당 FIRMED 하나(현행)
-- - aps_mps_row: 품목 행(계산 때 가용 재고·계획 기준정보 사본) — 계산 트랜잭션에서만 추가
-- - aps_mps_cell: 품목 × 날짜 칸(수요·공급·제안·계산 값·계획 수량) — 계산 트랜잭션에서만 추가, 초안일 때 계획 수량·사유만 고침
-- - 수주·내시·지시·재고 표는 읽기만 — 이 파일은 남의 표를 바꾸지 않는다
-- =====================================================================

-- ---------------------------------------------------------------------
-- 1. MPS 품목 (계획 기준정보)
-- ---------------------------------------------------------------------
CREATE TABLE aps_mps_item (
    mps_item_id  bigint         GENERATED ALWAYS AS IDENTITY,
    comp_cd      varchar(20)    NOT NULL,
    plant_cd     varchar(20)    NOT NULL,
    item_id      bigint         NOT NULL,
    safety_qty   numeric(18,6)  NOT NULL DEFAULT 0,
    lot_rule     varchar(3)     NOT NULL DEFAULT 'EX',
    lot_qty      numeric(18,6),
    remark       varchar(500),
    use_yn       char(1)        NOT NULL DEFAULT 'Y',
    row_ver      integer        NOT NULL DEFAULT 0,
    created_at   timestamptz    NOT NULL DEFAULT now(),
    created_by   varchar(50)    NOT NULL,
    updated_at   timestamptz    NOT NULL DEFAULT now(),
    updated_by   varchar(50)    NOT NULL,
    CONSTRAINT pk_aps_mps_item PRIMARY KEY (mps_item_id),
    CONSTRAINT fk_aps_mps_item_plant FOREIGN KEY (comp_cd, plant_cd) REFERENCES sys_plant (comp_cd, plant_cd),
    CONSTRAINT fk_aps_mps_item_item FOREIGN KEY (item_id) REFERENCES mst_item (item_id),
    CONSTRAINT ck_aps_mps_item_use CHECK (use_yn IN ('Y', 'N')),
    CONSTRAINT ck_aps_mps_item_safety CHECK (safety_qty >= 0),
    -- EX(그대로)는 로트 수량 없음, FX(고정 배수)·MIN(최소)은 로트 수량 > 0 필수
    CONSTRAINT ck_aps_mps_item_lot CHECK ((lot_rule = 'EX' AND lot_qty IS NULL)
                                          OR (lot_rule IN ('FX', 'MIN') AND lot_qty IS NOT NULL AND lot_qty > 0))
);
COMMENT ON TABLE aps_mps_item IS 'MPS 품목·계획 기준정보 (docs/38 16장) — 품목은 회사에서 한 공장 MPS 에만(사용 중 행 기준)';
CREATE UNIQUE INDEX uq_aps_mps_item ON aps_mps_item (comp_cd, item_id) WHERE use_yn = 'Y';
CREATE INDEX ix_aps_mps_item_plant ON aps_mps_item (comp_cd, plant_cd, use_yn);

-- FK 는 RLS 를 무시한다 — 품목이 같은 회사인지. 공장·품목은 고정(다른 공장으로 옮기려면 사용 중지 뒤 새로)
CREATE FUNCTION hb_guard_aps_mps_item() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'UPDATE' AND (NEW.plant_cd, NEW.item_id) IS DISTINCT FROM (OLD.plant_cd, OLD.item_id) THEN
        RAISE EXCEPTION 'mps item % keeps its plant and item', OLD.mps_item_id USING ERRCODE = '55000', CONSTRAINT = 'ck_aps_mps_item_fixed';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM mst_item i WHERE i.item_id = NEW.item_id AND i.comp_cd = NEW.comp_cd) THEN
        RAISE EXCEPTION 'mps item refers to another company item' USING ERRCODE = '23503', CONSTRAINT = 'fk_aps_mps_item_comp';
    END IF;
    RETURN NEW;
END
$$;
CREATE TRIGGER tg_aps_mps_item_guard BEFORE INSERT OR UPDATE ON aps_mps_item FOR EACH ROW EXECUTE FUNCTION hb_guard_aps_mps_item();
CREATE TRIGGER tg_aps_mps_item_no_delete BEFORE DELETE ON aps_mps_item FOR EACH ROW EXECUTE FUNCTION hb_forbid_delete_master();
CREATE TRIGGER tg_audit AFTER INSERT OR UPDATE OR DELETE ON aps_mps_item FOR EACH ROW EXECUTE FUNCTION hb_audit('mps_item_id', '', '');

-- ---------------------------------------------------------------------
-- 2. MPS 버전 머리
-- ---------------------------------------------------------------------
CREATE TABLE aps_mps (
    mps_id          bigint        GENERATED ALWAYS AS IDENTITY,
    comp_cd         varchar(20)   NOT NULL,
    mps_no          varchar(50)   NOT NULL,
    plant_cd        varchar(20)   NOT NULL,
    mps_nm          varchar(100),
    horizon_start   date          NOT NULL,
    horizon_days    smallint      NOT NULL,
    fence_days      smallint      NOT NULL,
    fence_end       date          NOT NULL,
    base_mps_id     bigint,
    status          varchar(10)   NOT NULL DEFAULT 'DRAFT',
    item_cnt        integer       NOT NULL,
    calculated_at   timestamptz   NOT NULL DEFAULT now(),
    calculated_by   varchar(50)   NOT NULL,
    firmed_at       timestamptz,
    firmed_by       varchar(50),
    superseded_at   timestamptz,
    discard_reason  varchar(500),
    remark          varchar(500),
    row_ver         integer       NOT NULL DEFAULT 0,
    created_at      timestamptz   NOT NULL DEFAULT now(),
    created_by      varchar(50)   NOT NULL,
    updated_at      timestamptz   NOT NULL DEFAULT now(),
    updated_by      varchar(50)   NOT NULL,
    CONSTRAINT pk_aps_mps PRIMARY KEY (mps_id),
    CONSTRAINT fk_aps_mps_plant FOREIGN KEY (comp_cd, plant_cd) REFERENCES sys_plant (comp_cd, plant_cd),
    CONSTRAINT fk_aps_mps_base FOREIGN KEY (base_mps_id) REFERENCES aps_mps (mps_id),
    CONSTRAINT uq_aps_mps_no UNIQUE (comp_cd, mps_no),
    CONSTRAINT ck_aps_mps_status CHECK (status IN ('DRAFT', 'FIRMED', 'SUPERSEDED', 'DISCARDED')),
    CONSTRAINT ck_aps_mps_days CHECK (horizon_days BETWEEN 7 AND 92 AND fence_days BETWEEN 0 AND 60 AND item_cnt >= 0),
    CONSTRAINT ck_aps_mps_start CHECK (horizon_start BETWEEN DATE '2000-01-01' AND DATE '2999-12-01'),
    -- 확정 칸은 FIRMED·SUPERSEDED 에만, 대체 시각은 SUPERSEDED 에만, 폐기 사유는 DISCARDED 에만(필수)
    CONSTRAINT ck_aps_mps_firmed CHECK ((status IN ('FIRMED', 'SUPERSEDED')) = (firmed_at IS NOT NULL AND firmed_by IS NOT NULL)),
    CONSTRAINT ck_aps_mps_superseded CHECK ((status = 'SUPERSEDED') = (superseded_at IS NOT NULL)),
    CONSTRAINT ck_aps_mps_discard CHECK ((status = 'DISCARDED') = (discard_reason IS NOT NULL AND length(btrim(discard_reason)) > 0))
);
COMMENT ON TABLE aps_mps IS 'MPS 버전 (docs/38 16장) — 계산마다 새 초안, 공장당 확정 하나(현행), 새 확정이 이전 확정을 대체';
-- 공장당 현행 확정 하나 — 동시에 두 초안을 확정하면 두 번째는 409
CREATE UNIQUE INDEX uq_aps_mps_firmed ON aps_mps (comp_cd, plant_cd) WHERE status = 'FIRMED';
CREATE INDEX ix_aps_mps_calc ON aps_mps (comp_cd, calculated_at, mps_id);

-- 상태 전이·계산 칸 고정·같은 회사(공장 FK 는 복합 키, 기준 MPS 는 트리거)
CREATE FUNCTION hb_guard_aps_mps() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'INSERT' THEN
        IF NEW.status <> 'DRAFT' THEN
            RAISE EXCEPTION 'new mps starts as DRAFT' USING ERRCODE = '55000', CONSTRAINT = 'ck_aps_mps_transition';
        END IF;
    ELSE
        IF (NEW.mps_no, NEW.plant_cd, NEW.horizon_start, NEW.horizon_days, NEW.fence_days, NEW.fence_end, NEW.base_mps_id,
            NEW.item_cnt, NEW.calculated_at, NEW.calculated_by)
           IS DISTINCT FROM (OLD.mps_no, OLD.plant_cd, OLD.horizon_start, OLD.horizon_days, OLD.fence_days, OLD.fence_end,
            OLD.base_mps_id, OLD.item_cnt, OLD.calculated_at, OLD.calculated_by) THEN
            RAISE EXCEPTION 'mps % keeps its calculated values', OLD.mps_id USING ERRCODE = '55000', CONSTRAINT = 'ck_aps_mps_fixed';
        END IF;
        IF NEW.status IS DISTINCT FROM OLD.status AND NOT (
               (OLD.status = 'DRAFT' AND NEW.status IN ('FIRMED', 'DISCARDED'))
            OR (OLD.status = 'FIRMED' AND NEW.status = 'SUPERSEDED')) THEN
            RAISE EXCEPTION 'mps % cannot go % -> %', OLD.mps_id, OLD.status, NEW.status
                USING ERRCODE = '55000', CONSTRAINT = 'ck_aps_mps_transition';
        END IF;
        -- 확정 칸은 확정하는 그 UPDATE 에서만(그 뒤 고정)
        IF (NEW.firmed_at, NEW.firmed_by) IS DISTINCT FROM (OLD.firmed_at, OLD.firmed_by)
           AND NOT (OLD.status = 'DRAFT' AND NEW.status = 'FIRMED' AND NEW.firmed_at = now()) THEN
            RAISE EXCEPTION 'mps % firm columns are set only when firming', OLD.mps_id USING ERRCODE = '55000', CONSTRAINT = 'ck_aps_mps_fixed';
        END IF;
        IF NEW.superseded_at IS DISTINCT FROM OLD.superseded_at AND NOT (OLD.status = 'FIRMED' AND NEW.status = 'SUPERSEDED') THEN
            RAISE EXCEPTION 'mps % superseded_at is set only when superseding', OLD.mps_id USING ERRCODE = '55000', CONSTRAINT = 'ck_aps_mps_fixed';
        END IF;
    END IF;
    IF NEW.base_mps_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM aps_mps b WHERE b.mps_id = NEW.base_mps_id
                      AND b.comp_cd = NEW.comp_cd AND b.plant_cd = NEW.plant_cd) THEN
        RAISE EXCEPTION 'base mps refers to another company or plant' USING ERRCODE = '23503', CONSTRAINT = 'fk_aps_mps_base_comp';
    END IF;
    RETURN NEW;
END
$$;
CREATE TRIGGER tg_aps_mps_guard BEFORE INSERT OR UPDATE ON aps_mps FOR EACH ROW EXECUTE FUNCTION hb_guard_aps_mps();
CREATE TRIGGER tg_aps_mps_no_delete BEFORE DELETE ON aps_mps FOR EACH ROW EXECUTE FUNCTION hb_forbid_delete_master();
CREATE TRIGGER tg_audit AFTER INSERT OR UPDATE OR DELETE ON aps_mps FOR EACH ROW EXECUTE FUNCTION hb_audit('mps_id', '', '');

-- ---------------------------------------------------------------------
-- 3. 품목 행 · 칸 (계산 결과)
-- ---------------------------------------------------------------------
CREATE TABLE aps_mps_row (
    mps_row_id  bigint         GENERATED ALWAYS AS IDENTITY,
    comp_cd     varchar(20)    NOT NULL,
    mps_id      bigint         NOT NULL,
    item_id     bigint         NOT NULL,
    unit_cd     varchar(30)    NOT NULL,
    stock_qty   numeric(18,6)  NOT NULL,
    safety_qty  numeric(18,6)  NOT NULL,
    lot_rule    varchar(3)     NOT NULL,
    lot_qty     numeric(18,6),
    CONSTRAINT pk_aps_mps_row PRIMARY KEY (mps_row_id),
    CONSTRAINT fk_aps_mps_row_mps FOREIGN KEY (mps_id) REFERENCES aps_mps (mps_id),
    CONSTRAINT fk_aps_mps_row_item FOREIGN KEY (item_id) REFERENCES mst_item (item_id),
    CONSTRAINT uq_aps_mps_row UNIQUE (comp_cd, mps_id, item_id),
    CONSTRAINT ck_aps_mps_row_qty CHECK (safety_qty >= 0),
    CONSTRAINT ck_aps_mps_row_lot CHECK ((lot_rule = 'EX' AND lot_qty IS NULL)
                                         OR (lot_rule IN ('FX', 'MIN') AND lot_qty IS NOT NULL AND lot_qty > 0))
);
COMMENT ON TABLE aps_mps_row IS 'MPS 품목 행 — 계산 때 가용 재고·계획 기준정보 사본 (계산 트랜잭션에서만 추가)';

CREATE TABLE aps_mps_cell (
    mps_cell_id   bigint         GENERATED ALWAYS AS IDENTITY,
    comp_cd       varchar(20)    NOT NULL,
    mps_id        bigint         NOT NULL,
    mps_row_id    bigint         NOT NULL,
    bucket_date   date           NOT NULL,
    demand_order  numeric(18,6)  NOT NULL DEFAULT 0,
    demand_fcst   numeric(18,6)  NOT NULL DEFAULT 0,
    past_due_qty  numeric(18,6)  NOT NULL DEFAULT 0,
    supply_wo     numeric(18,6)  NOT NULL DEFAULT 0,
    prop_qty      numeric(18,6)  NOT NULL DEFAULT 0,
    base_qty      numeric(18,6)  NOT NULL DEFAULT 0,
    fenced_yn     char(1)        NOT NULL DEFAULT 'N',
    plan_qty      numeric(18,6)  NOT NULL DEFAULT 0,
    chg_reason    varchar(500),
    CONSTRAINT pk_aps_mps_cell PRIMARY KEY (mps_cell_id),
    CONSTRAINT fk_aps_mps_cell_mps FOREIGN KEY (mps_id) REFERENCES aps_mps (mps_id),
    CONSTRAINT fk_aps_mps_cell_row FOREIGN KEY (mps_row_id) REFERENCES aps_mps_row (mps_row_id),
    CONSTRAINT uq_aps_mps_cell UNIQUE (comp_cd, mps_row_id, bucket_date),
    CONSTRAINT ck_aps_mps_cell_qty CHECK (demand_order >= 0 AND demand_fcst >= 0 AND past_due_qty >= 0 AND past_due_qty <= demand_order
                                          AND supply_wo >= 0 AND prop_qty >= 0 AND base_qty >= 0 AND plan_qty >= 0),
    CONSTRAINT ck_aps_mps_cell_fenced CHECK (fenced_yn IN ('Y', 'N')),
    -- 확정 구간 칸을 계산 값과 다르게 바꾸면 사유 필수(AP-3)
    CONSTRAINT ck_aps_mps_cell_reason CHECK (fenced_yn = 'N' OR plan_qty = base_qty
                                             OR (chg_reason IS NOT NULL AND length(btrim(chg_reason)) > 0))
);
COMMENT ON TABLE aps_mps_cell IS 'MPS 칸(품목 × 날짜) — 계산 트랜잭션에서만 추가, 초안일 때 계획 수량·사유만 고친다';
CREATE INDEX ix_aps_mps_cell_mps ON aps_mps_cell (comp_cd, mps_id, mps_row_id, bucket_date);

-- 행·칸은 같은 회사 MPS 를 계산하는 트랜잭션에서만 넣는다(calculated_at = now() — V052 수법)
CREATE FUNCTION hb_guard_aps_mps_result() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM aps_mps m WHERE m.mps_id = NEW.mps_id AND m.comp_cd = NEW.comp_cd
                      AND m.status = 'DRAFT' AND m.calculated_at = now()) THEN
        RAISE EXCEPTION 'mps rows are written only when calculating (mps %)', NEW.mps_id
            USING ERRCODE = '55000', CONSTRAINT = 'ck_aps_mps_result_calc';
    END IF;
    IF TG_TABLE_NAME = 'aps_mps_row' THEN
        IF NOT EXISTS (SELECT 1 FROM mst_item i WHERE i.item_id = NEW.item_id AND i.comp_cd = NEW.comp_cd) THEN
            RAISE EXCEPTION 'mps row refers to another company item' USING ERRCODE = '23503', CONSTRAINT = 'fk_aps_mps_result_comp';
        END IF;
    ELSE
        IF NOT EXISTS (SELECT 1 FROM aps_mps_row r WHERE r.mps_row_id = NEW.mps_row_id AND r.comp_cd = NEW.comp_cd
                          AND r.mps_id = NEW.mps_id) THEN
            RAISE EXCEPTION 'mps cell refers to another mps row' USING ERRCODE = '23503', CONSTRAINT = 'fk_aps_mps_result_comp';
        END IF;
    END IF;
    RETURN NEW;
END
$$;
CREATE TRIGGER tg_aps_mps_row_calc BEFORE INSERT ON aps_mps_row FOR EACH ROW EXECUTE FUNCTION hb_guard_aps_mps_result();
CREATE TRIGGER tg_aps_mps_cell_calc BEFORE INSERT ON aps_mps_cell FOR EACH ROW EXECUTE FUNCTION hb_guard_aps_mps_result();
CREATE TRIGGER tg_aps_mps_row_append_only BEFORE UPDATE OR DELETE ON aps_mps_row FOR EACH ROW EXECUTE FUNCTION hb_forbid_update_delete();

-- 칸: 계산 칸 불변, 계획 수량·사유는 머리가 DRAFT 일 때만
CREATE FUNCTION hb_guard_aps_mps_cell_edit() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'mps cells are not deleted' USING ERRCODE = '55000', CONSTRAINT = 'ck_aps_mps_cell_fixed';
    END IF;
    IF (NEW.mps_id, NEW.mps_row_id, NEW.bucket_date, NEW.demand_order, NEW.demand_fcst, NEW.past_due_qty, NEW.supply_wo,
        NEW.prop_qty, NEW.base_qty, NEW.fenced_yn)
       IS DISTINCT FROM (OLD.mps_id, OLD.mps_row_id, OLD.bucket_date, OLD.demand_order, OLD.demand_fcst, OLD.past_due_qty,
        OLD.supply_wo, OLD.prop_qty, OLD.base_qty, OLD.fenced_yn) THEN
        RAISE EXCEPTION 'mps cell % keeps its calculated values', OLD.mps_cell_id USING ERRCODE = '55000', CONSTRAINT = 'ck_aps_mps_cell_fixed';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM aps_mps m WHERE m.mps_id = NEW.mps_id AND m.status = 'DRAFT') THEN
        RAISE EXCEPTION 'mps % is not a draft', NEW.mps_id USING ERRCODE = '55000', CONSTRAINT = 'ck_aps_mps_cell_draft';
    END IF;
    RETURN NEW;
END
$$;
CREATE TRIGGER tg_aps_mps_cell_edit BEFORE UPDATE OR DELETE ON aps_mps_cell FOR EACH ROW EXECUTE FUNCTION hb_guard_aps_mps_cell_edit();
-- 칸 감사는 사람이 고친 것(UPDATE)만 — 계산 추가는 머리(계산한 사람·시각)가 기록이고, 칸 수가 많다(품목 × 날짜)
CREATE TRIGGER tg_audit AFTER UPDATE ON aps_mps_cell FOR EACH ROW EXECUTE FUNCTION hb_audit('mps_cell_id', '', '');

-- ---------------------------------------------------------------------
-- 4. RLS · 권한 · 정책 · 화면 · 보존
-- ---------------------------------------------------------------------
ALTER TABLE aps_mps_item ENABLE ROW LEVEL SECURITY;
ALTER TABLE aps_mps_item FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON aps_mps_item USING (comp_cd = hb_current_comp()) WITH CHECK (comp_cd = hb_current_comp());
ALTER TABLE aps_mps ENABLE ROW LEVEL SECURITY;
ALTER TABLE aps_mps FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON aps_mps USING (comp_cd = hb_current_comp()) WITH CHECK (comp_cd = hb_current_comp());
ALTER TABLE aps_mps_row ENABLE ROW LEVEL SECURITY;
ALTER TABLE aps_mps_row FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON aps_mps_row USING (comp_cd = hb_current_comp()) WITH CHECK (comp_cd = hb_current_comp());
ALTER TABLE aps_mps_cell ENABLE ROW LEVEL SECURITY;
ALTER TABLE aps_mps_cell FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON aps_mps_cell USING (comp_cd = hb_current_comp()) WITH CHECK (comp_cd = hb_current_comp());
REVOKE DELETE, TRUNCATE ON aps_mps_item, aps_mps, aps_mps_cell FROM ${app_role};
REVOKE UPDATE, DELETE, TRUNCATE ON aps_mps_row FROM ${app_role};

INSERT INTO sys_policy_def (policy_key, value_type, default_value, allowed_values, min_value, max_value, desc_key) VALUES
    ('APS_MPS_FENCE_DAYS', 'INT', '7', NULL, 0, 60, 'policy.APS_MPS_FENCE_DAYS');

INSERT INTO sys_prog (prog_cd, prog_nm_key, view_path, module_cd, actions, sort_no) VALUES
    ('aps-mps', 'prog.apsMps', 'aps/mps', 'APS', ARRAY['SEARCH','SAVE','APPROVE']::varchar(20)[], 971);
INSERT INTO sys_retention_table (table_nm, class_cd) VALUES
    ('aps_mps_item', 'PLANNING'), ('aps_mps', 'PLANNING'), ('aps_mps_row', 'PLANNING'), ('aps_mps_cell', 'PLANNING');
