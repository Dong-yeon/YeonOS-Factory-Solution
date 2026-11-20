-- =====================================================================
-- V9850(임시 — 병합 때 재번호) APS 10단계 — MRP 자재소요계획 (docs/38 19장, docs/62 A2·AP-4, docs/63 G3·G7·AP-8)
--
-- - aps_mrp: MRP 실행 머리 — 계산마다 새로(CALC), 폐기(DISCARDED)만. 계산 칸 불변
-- - aps_mrp_item: 계획 품목(계산 때 저차 코드·재고·계획 기준정보·공급원 사본)
-- - aps_mrp_elem: 재고/소요 목록 요소(공급 STOCK·WO·PO·MPS·PLAN / 수요 SO·FC·DEP·WODEP·SAFE) — 계획 주문 = MPS·PLAN
-- - aps_mrp_peg: 페깅(수요 요소 ↔ 공급 요소, 수량)
-- - aps_mrp_exc: 예외 메시지(당김·밀기·취소·줄임·늦음 + 데이터 예외)
-- - 결과 4표는 계산 트랜잭션에서만 추가(V052 수법), 앱 계정 UPDATE·DELETE 없음
-- - 지시·발주·수주·내시·재고·BOM 표는 읽기만(계약) — 이 파일은 남의 표를 바꾸지 않는다
-- =====================================================================

-- ---------------------------------------------------------------------
-- 1. 실행 머리
-- ---------------------------------------------------------------------
CREATE TABLE aps_mrp (
    mrp_id          bigint        GENERATED ALWAYS AS IDENTITY,
    comp_cd         varchar(20)   NOT NULL,
    mrp_no          varchar(50)   NOT NULL,
    plant_cd        varchar(20)   NOT NULL,
    mrp_nm          varchar(100),
    horizon_start   date          NOT NULL,
    horizon_days    smallint      NOT NULL,
    mps_id          bigint,
    out_days        smallint      NOT NULL,
    status          varchar(10)   NOT NULL DEFAULT 'CALC',
    item_cnt        integer       NOT NULL,
    order_cnt       integer       NOT NULL,
    exc_cnt         integer       NOT NULL,
    calculated_at   timestamptz   NOT NULL DEFAULT now(),
    calculated_by   varchar(50)   NOT NULL,
    discard_reason  varchar(500),
    row_ver         integer       NOT NULL DEFAULT 0,
    created_at      timestamptz   NOT NULL DEFAULT now(),
    created_by      varchar(50)   NOT NULL,
    updated_at      timestamptz   NOT NULL DEFAULT now(),
    updated_by      varchar(50)   NOT NULL,
    CONSTRAINT pk_aps_mrp PRIMARY KEY (mrp_id),
    CONSTRAINT fk_aps_mrp_plant FOREIGN KEY (comp_cd, plant_cd) REFERENCES sys_plant (comp_cd, plant_cd),
    CONSTRAINT fk_aps_mrp_mps FOREIGN KEY (mps_id) REFERENCES aps_mps (mps_id),
    CONSTRAINT uq_aps_mrp_no UNIQUE (comp_cd, mrp_no),
    CONSTRAINT ck_aps_mrp_status CHECK (status IN ('CALC', 'DISCARDED')),
    CONSTRAINT ck_aps_mrp_days CHECK (horizon_days BETWEEN 7 AND 92 AND out_days BETWEEN 0 AND 60
                                      AND item_cnt >= 0 AND order_cnt >= 0 AND exc_cnt >= 0),
    CONSTRAINT ck_aps_mrp_start CHECK (horizon_start BETWEEN DATE '2000-01-01' AND DATE '2999-12-01'),
    CONSTRAINT ck_aps_mrp_discard CHECK ((status = 'DISCARDED') = (discard_reason IS NOT NULL AND length(btrim(discard_reason)) > 0))
);
COMMENT ON TABLE aps_mrp IS 'MRP 실행 (docs/38 19장) — 계산마다 새로, 계획 주문은 제안만(지시·구매 요청은 A3 에서 사람이 골라)';
COMMENT ON COLUMN aps_mrp.mps_id IS '계산 때 현행 확정 MPS — 없으면 비움(독립 수요 없음, 안전재고·열린 지시 소요만)';
COMMENT ON COLUMN aps_mrp.out_days IS '밀기 예외 허용 일수(정책 APS_MRP_OUT_DAYS 사본)';
CREATE INDEX ix_aps_mrp_calc ON aps_mrp (comp_cd, calculated_at, mrp_id);

-- 계산 칸 고정·상태 전이·같은 회사 MPS
CREATE FUNCTION hb_guard_aps_mrp() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'INSERT' THEN
        IF NEW.status <> 'CALC' THEN
            RAISE EXCEPTION 'new mrp starts as CALC' USING ERRCODE = '55000', CONSTRAINT = 'ck_aps_mrp_transition';
        END IF;
    ELSE
        IF (NEW.mrp_no, NEW.plant_cd, NEW.mrp_nm, NEW.horizon_start, NEW.horizon_days, NEW.mps_id, NEW.out_days, NEW.item_cnt,
            NEW.order_cnt, NEW.exc_cnt, NEW.calculated_at, NEW.calculated_by)
           IS DISTINCT FROM (OLD.mrp_no, OLD.plant_cd, OLD.mrp_nm, OLD.horizon_start, OLD.horizon_days, OLD.mps_id, OLD.out_days,
            OLD.item_cnt, OLD.order_cnt, OLD.exc_cnt, OLD.calculated_at, OLD.calculated_by) THEN
            RAISE EXCEPTION 'mrp % keeps its calculated values', OLD.mrp_id USING ERRCODE = '55000', CONSTRAINT = 'ck_aps_mrp_fixed';
        END IF;
        IF NEW.status IS DISTINCT FROM OLD.status AND NOT (OLD.status = 'CALC' AND NEW.status = 'DISCARDED') THEN
            RAISE EXCEPTION 'mrp % cannot go % -> %', OLD.mrp_id, OLD.status, NEW.status
                USING ERRCODE = '55000', CONSTRAINT = 'ck_aps_mrp_transition';
        END IF;
    END IF;
    IF NEW.mps_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM aps_mps m WHERE m.mps_id = NEW.mps_id
                      AND m.comp_cd = NEW.comp_cd AND m.plant_cd = NEW.plant_cd) THEN
        RAISE EXCEPTION 'mrp mps refers to another company or plant' USING ERRCODE = '23503', CONSTRAINT = 'fk_aps_mrp_mps_comp';
    END IF;
    RETURN NEW;
END
$$;
CREATE TRIGGER tg_aps_mrp_guard BEFORE INSERT OR UPDATE ON aps_mrp FOR EACH ROW EXECUTE FUNCTION hb_guard_aps_mrp();
CREATE TRIGGER tg_aps_mrp_no_delete BEFORE DELETE ON aps_mrp FOR EACH ROW EXECUTE FUNCTION hb_forbid_delete_master();
CREATE TRIGGER tg_audit AFTER INSERT OR UPDATE OR DELETE ON aps_mrp FOR EACH ROW EXECUTE FUNCTION hb_audit('mrp_id', '', '');

-- ---------------------------------------------------------------------
-- 2. 계획 품목
-- ---------------------------------------------------------------------
CREATE TABLE aps_mrp_item (
    mrp_item_id  bigint         GENERATED ALWAYS AS IDENTITY,
    comp_cd      varchar(20)    NOT NULL,
    mrp_id       bigint         NOT NULL,
    item_id      bigint         NOT NULL,
    llc          smallint       NOT NULL,
    proc_type    varchar(10)    NOT NULL,
    unit_cd      varchar(30)    NOT NULL,
    mps_yn       char(1)        NOT NULL,
    stock_qty    numeric(18,6)  NOT NULL,
    safety_qty   numeric(18,6)  NOT NULL,
    lot_rule     varchar(3)     NOT NULL,
    lot_qty      numeric(18,6),
    lot_mult     numeric(18,6),
    moq          numeric(18,6),
    order_mult   numeric(18,6),
    lead_days    smallint       NOT NULL,
    lead_src     varchar(10)    NOT NULL,
    cust_id      bigint,
    CONSTRAINT pk_aps_mrp_item PRIMARY KEY (mrp_item_id),
    CONSTRAINT fk_aps_mrp_item_mrp FOREIGN KEY (mrp_id) REFERENCES aps_mrp (mrp_id),
    CONSTRAINT fk_aps_mrp_item_item FOREIGN KEY (item_id) REFERENCES mst_item (item_id),
    CONSTRAINT uq_aps_mrp_item UNIQUE (comp_cd, mrp_id, item_id),
    CONSTRAINT ck_aps_mrp_item_llc CHECK (llc BETWEEN 0 AND 30 AND lead_days BETWEEN 0 AND 365),
    CONSTRAINT ck_aps_mrp_item_proc CHECK (proc_type IN ('MAKE', 'BUY', 'OUTSRC')),
    CONSTRAINT ck_aps_mrp_item_mps CHECK (mps_yn IN ('Y', 'N')),
    CONSTRAINT ck_aps_mrp_item_lot CHECK (lot_rule IN ('EX', 'FX', 'MIN')),
    CONSTRAINT ck_aps_mrp_item_src CHECK (lead_src IN ('PARAM', 'SOURCE', 'NONE'))
);
COMMENT ON TABLE aps_mrp_item IS 'MRP 계획 품목 — 계산 때 저차 코드·가용 재고·계획 기준정보·주 공급원 사본 (계산 트랜잭션에서만 추가)';

-- ---------------------------------------------------------------------
-- 3. 요소 · 페깅 · 예외
-- ---------------------------------------------------------------------
CREATE TABLE aps_mrp_elem (
    comp_cd      varchar(20)    NOT NULL,
    mrp_id       bigint         NOT NULL,
    elem_seq     integer        NOT NULL,
    item_id      bigint         NOT NULL,
    dir          char(1)        NOT NULL,
    kind         varchar(10)    NOT NULL,
    elem_date    date           NOT NULL,
    qty          numeric(18,6)  NOT NULL,
    past_due_yn  char(1)        NOT NULL DEFAULT 'N',
    start_date   date,
    late_yn      char(1)        NOT NULL DEFAULT 'N',
    parent_seq   integer,
    ref_id       bigint,
    ref_no       varchar(100),
    CONSTRAINT pk_aps_mrp_elem PRIMARY KEY (mrp_id, elem_seq),
    CONSTRAINT fk_aps_mrp_elem_mrp FOREIGN KEY (mrp_id) REFERENCES aps_mrp (mrp_id),
    CONSTRAINT fk_aps_mrp_elem_item FOREIGN KEY (item_id) REFERENCES mst_item (item_id),
    CONSTRAINT fk_aps_mrp_elem_parent FOREIGN KEY (mrp_id, parent_seq) REFERENCES aps_mrp_elem (mrp_id, elem_seq),
    CONSTRAINT ck_aps_mrp_elem_seq CHECK (elem_seq > 0 AND qty > 0),
    -- 공급·수요 종류는 시스템 고정(엔진이 값마다 분기)
    CONSTRAINT ck_aps_mrp_elem_kind CHECK ((dir = 'S' AND kind IN ('STOCK', 'WO', 'PO', 'MPS', 'PLAN'))
                                           OR (dir = 'D' AND kind IN ('SO', 'FC', 'DEP', 'WODEP', 'SAFE'))),
    CONSTRAINT ck_aps_mrp_elem_yn CHECK (past_due_yn IN ('Y', 'N') AND late_yn IN ('Y', 'N')),
    -- 시작일은 계획 주문(MPS·PLAN)에만(필수), 부모는 종속 수요(DEP)에만(필수)
    CONSTRAINT ck_aps_mrp_elem_start CHECK ((kind IN ('MPS', 'PLAN')) = (start_date IS NOT NULL)),
    CONSTRAINT ck_aps_mrp_elem_parent CHECK ((kind = 'DEP') = (parent_seq IS NOT NULL))
);
COMMENT ON TABLE aps_mrp_elem IS 'MRP 재고/소요 목록 요소 — 계획 주문 = 종류 MPS·PLAN (계산 트랜잭션에서만 추가)';
CREATE INDEX ix_aps_mrp_elem_item ON aps_mrp_elem (comp_cd, mrp_id, item_id, elem_date, elem_seq);

CREATE TABLE aps_mrp_peg (
    comp_cd     varchar(20)    NOT NULL,
    mrp_id      bigint         NOT NULL,
    demand_seq  integer        NOT NULL,
    supply_seq  integer        NOT NULL,
    qty         numeric(18,6)  NOT NULL,
    CONSTRAINT pk_aps_mrp_peg PRIMARY KEY (mrp_id, demand_seq, supply_seq),
    CONSTRAINT fk_aps_mrp_peg_demand FOREIGN KEY (mrp_id, demand_seq) REFERENCES aps_mrp_elem (mrp_id, elem_seq),
    CONSTRAINT fk_aps_mrp_peg_supply FOREIGN KEY (mrp_id, supply_seq) REFERENCES aps_mrp_elem (mrp_id, elem_seq),
    CONSTRAINT ck_aps_mrp_peg_qty CHECK (qty > 0)
);
COMMENT ON TABLE aps_mrp_peg IS 'MRP 페깅 — 수요 요소 ↔ 공급 요소 (위로 따라가면 수주·내시 줄·상위 지시)';
CREATE INDEX ix_aps_mrp_peg_supply ON aps_mrp_peg (mrp_id, supply_seq);

CREATE TABLE aps_mrp_exc (
    exc_id    bigint         GENERATED ALWAYS AS IDENTITY,
    comp_cd   varchar(20)    NOT NULL,
    mrp_id    bigint         NOT NULL,
    item_id   bigint         NOT NULL,
    exc_cd    varchar(20)    NOT NULL,
    elem_seq  integer,
    cur_date  date,
    sug_date  date,
    qty       numeric(18,6),
    ref_no    varchar(100),
    CONSTRAINT pk_aps_mrp_exc PRIMARY KEY (exc_id),
    CONSTRAINT fk_aps_mrp_exc_mrp FOREIGN KEY (mrp_id) REFERENCES aps_mrp (mrp_id),
    CONSTRAINT fk_aps_mrp_exc_item FOREIGN KEY (item_id) REFERENCES mst_item (item_id),
    CONSTRAINT fk_aps_mrp_exc_elem FOREIGN KEY (mrp_id, elem_seq) REFERENCES aps_mrp_elem (mrp_id, elem_seq),
    CONSTRAINT ck_aps_mrp_exc_cd CHECK (exc_cd IN ('RESCHED_IN', 'RESCHED_OUT', 'CANCEL', 'REDUCE', 'START_PAST',
                                                   'NO_LEAD', 'NO_SOURCE', 'NO_BOM', 'UNIT', 'MPS_PAST', 'MPS_DEP', 'MPS_SHORT'))
);
COMMENT ON TABLE aps_mrp_exc IS 'MRP 예외 메시지 (docs/38 19.6, G7) — 알림만, 지시·발주를 바꾸지 않는다';
CREATE INDEX ix_aps_mrp_exc_mrp ON aps_mrp_exc (comp_cd, mrp_id, exc_cd);

-- 결과는 같은 회사 MRP 를 계산하는 트랜잭션에서만 넣는다(calculated_at = now() — V052 수법). 품목은 같은 회사
CREATE FUNCTION hb_guard_aps_mrp_result() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM aps_mrp m WHERE m.mrp_id = NEW.mrp_id AND m.comp_cd = NEW.comp_cd
                      AND m.status = 'CALC' AND m.calculated_at = now()) THEN
        RAISE EXCEPTION 'mrp results are written only when calculating (mrp %)', NEW.mrp_id
            USING ERRCODE = '55000', CONSTRAINT = 'ck_aps_mrp_result_calc';
    END IF;
    IF TG_TABLE_NAME IN ('aps_mrp_item', 'aps_mrp_elem', 'aps_mrp_exc') THEN
        IF NOT EXISTS (SELECT 1 FROM mst_item i WHERE i.item_id = NEW.item_id AND i.comp_cd = NEW.comp_cd) THEN
            RAISE EXCEPTION 'mrp result refers to another company item' USING ERRCODE = '23503', CONSTRAINT = 'fk_aps_mrp_result_comp';
        END IF;
    END IF;
    RETURN NEW;
END
$$;
CREATE TRIGGER tg_aps_mrp_item_calc BEFORE INSERT ON aps_mrp_item FOR EACH ROW EXECUTE FUNCTION hb_guard_aps_mrp_result();
CREATE TRIGGER tg_aps_mrp_elem_calc BEFORE INSERT ON aps_mrp_elem FOR EACH ROW EXECUTE FUNCTION hb_guard_aps_mrp_result();
CREATE TRIGGER tg_aps_mrp_peg_calc BEFORE INSERT ON aps_mrp_peg FOR EACH ROW EXECUTE FUNCTION hb_guard_aps_mrp_result();
CREATE TRIGGER tg_aps_mrp_exc_calc BEFORE INSERT ON aps_mrp_exc FOR EACH ROW EXECUTE FUNCTION hb_guard_aps_mrp_result();
CREATE TRIGGER tg_aps_mrp_item_append_only BEFORE UPDATE OR DELETE ON aps_mrp_item FOR EACH ROW EXECUTE FUNCTION hb_forbid_update_delete();
CREATE TRIGGER tg_aps_mrp_elem_append_only BEFORE UPDATE OR DELETE ON aps_mrp_elem FOR EACH ROW EXECUTE FUNCTION hb_forbid_update_delete();
CREATE TRIGGER tg_aps_mrp_peg_append_only BEFORE UPDATE OR DELETE ON aps_mrp_peg FOR EACH ROW EXECUTE FUNCTION hb_forbid_update_delete();
CREATE TRIGGER tg_aps_mrp_exc_append_only BEFORE UPDATE OR DELETE ON aps_mrp_exc FOR EACH ROW EXECUTE FUNCTION hb_forbid_update_delete();

-- ---------------------------------------------------------------------
-- 4. RLS · 권한 · 정책 · 화면 · 보존
-- ---------------------------------------------------------------------
ALTER TABLE aps_mrp ENABLE ROW LEVEL SECURITY;
ALTER TABLE aps_mrp FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON aps_mrp USING (comp_cd = hb_current_comp()) WITH CHECK (comp_cd = hb_current_comp());
ALTER TABLE aps_mrp_item ENABLE ROW LEVEL SECURITY;
ALTER TABLE aps_mrp_item FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON aps_mrp_item USING (comp_cd = hb_current_comp()) WITH CHECK (comp_cd = hb_current_comp());
ALTER TABLE aps_mrp_elem ENABLE ROW LEVEL SECURITY;
ALTER TABLE aps_mrp_elem FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON aps_mrp_elem USING (comp_cd = hb_current_comp()) WITH CHECK (comp_cd = hb_current_comp());
ALTER TABLE aps_mrp_peg ENABLE ROW LEVEL SECURITY;
ALTER TABLE aps_mrp_peg FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON aps_mrp_peg USING (comp_cd = hb_current_comp()) WITH CHECK (comp_cd = hb_current_comp());
ALTER TABLE aps_mrp_exc ENABLE ROW LEVEL SECURITY;
ALTER TABLE aps_mrp_exc FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON aps_mrp_exc USING (comp_cd = hb_current_comp()) WITH CHECK (comp_cd = hb_current_comp());
REVOKE DELETE, TRUNCATE ON aps_mrp FROM ${app_role};
REVOKE UPDATE, DELETE, TRUNCATE ON aps_mrp_item, aps_mrp_elem, aps_mrp_peg, aps_mrp_exc FROM ${app_role};

INSERT INTO sys_policy_def (policy_key, value_type, default_value, allowed_values, min_value, max_value, desc_key) VALUES
    ('APS_MRP_OUT_DAYS', 'INT', '3', NULL, 0, 60, 'policy.APS_MRP_OUT_DAYS');

INSERT INTO sys_prog (prog_cd, prog_nm_key, view_path, module_cd, actions, sort_no) VALUES
    ('aps-mrp', 'prog.apsMrp', 'aps/mrp', 'APS', ARRAY['SEARCH','SAVE']::varchar(20)[], 973);
INSERT INTO sys_retention_table (table_nm, class_cd) VALUES
    ('aps_mrp', 'PLANNING'), ('aps_mrp_item', 'PLANNING'), ('aps_mrp_elem', 'PLANNING'), ('aps_mrp_peg', 'PLANNING'), ('aps_mrp_exc', 'PLANNING');
