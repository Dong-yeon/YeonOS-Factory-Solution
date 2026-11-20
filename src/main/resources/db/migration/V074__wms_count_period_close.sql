-- =====================================================================
-- WMS 1부 W5 실사·기초 재고·기간 마감 (docs/30 4.5, 결정 D9·D10) — 임시 번호(docs/29 2.2), 병합 때 이름만 바뀐다
--
-- - wms_count / wms_count_line: 실사 머리·줄. 장부 수량 = **센 시각 기준 원장 합**(서버 계산) → 실사 중 이동이 있어도 이중 조정 없음(D9)
--   상태 DRAFT → COUNTING → REVIEW → POSTED, 취소 CANCELLED(사유). REVIEW → COUNTING(재실사). 확정 = 줄마다 COUNT_ADJ/OPENING 원장
-- - sys_period_close: 공장 × 모듈 × 월 마감(00 5.3 이름 — 모듈 칸으로 다른 모듈도 같은 표). 재개 = 사유, 마감·재개는 감사 트리거로 남는다
-- - inv_trx 에 마감 검사 트리거: 마감된 공장·월(공장 시간대)의 occurred_at 은 원장에 못 들어간다(55000 ck_inv_trx_period_closed, D10)
-- - 정책 WMS_COUNT_TOL_PCT: 차이율(|차이| / 장부 × 100)이 넘으면 확정에 APPROVE 권한(장부 0 에 차이가 있으면 항상 넘음)
-- =====================================================================

INSERT INTO sys_policy_def (policy_key, value_type, default_value, allowed_values, min_value, max_value, plant_scope_yn, desc_key) VALUES
    ('WMS_COUNT_TOL_PCT', 'INT', '5', NULL, 0, 100, 'Y', 'policy.WMS_COUNT_TOL_PCT');

INSERT INTO sys_code_grp (grp_cd, grp_nm_key, comp_ext_yn) VALUES
    ('WMS_COUNT_TYPE',   'code.grp.WMS_COUNT_TYPE',   'N'),
    ('WMS_COUNT_STATUS', 'code.grp.WMS_COUNT_STATUS', 'N'),
    ('PERIOD_STATUS',    'code.grp.PERIOD_STATUS',    'N');
INSERT INTO sys_code (grp_cd, code, code_nm_key, sort_no) VALUES
    ('WMS_COUNT_TYPE', 'OPENING', 'code.WMS_COUNT_TYPE.OPENING', 1),
    ('WMS_COUNT_TYPE', 'CYCLE',   'code.WMS_COUNT_TYPE.CYCLE',   2),
    ('WMS_COUNT_TYPE', 'FULL',    'code.WMS_COUNT_TYPE.FULL',    3),
    ('WMS_COUNT_STATUS', 'DRAFT',     'code.WMS_COUNT_STATUS.DRAFT',     1),
    ('WMS_COUNT_STATUS', 'COUNTING',  'code.WMS_COUNT_STATUS.COUNTING',  2),
    ('WMS_COUNT_STATUS', 'REVIEW',    'code.WMS_COUNT_STATUS.REVIEW',    3),
    ('WMS_COUNT_STATUS', 'POSTED',    'code.WMS_COUNT_STATUS.POSTED',    4),
    ('WMS_COUNT_STATUS', 'CANCELLED', 'code.WMS_COUNT_STATUS.CANCELLED', 5),
    ('PERIOD_STATUS', 'CLOSED',   'code.PERIOD_STATUS.CLOSED',   1),
    ('PERIOD_STATUS', 'REOPENED', 'code.PERIOD_STATUS.REOPENED', 2);

-- ---------------------------------------------------------------------
-- 1. 실사 머리
-- ---------------------------------------------------------------------
CREATE TABLE wms_count (
    count_id       bigint         GENERATED ALWAYS AS IDENTITY,
    comp_cd        varchar(20)    NOT NULL,
    plant_cd       varchar(20)    NOT NULL,
    count_no       varchar(40)    NOT NULL,
    count_type     varchar(10)    NOT NULL,
    scope_node_id  bigint         NOT NULL,
    item_id        bigint,
    blind_yn       char(1)        NOT NULL DEFAULT 'Y',
    tol_pct        numeric(5,2)   NOT NULL,
    status         varchar(10)    NOT NULL DEFAULT 'DRAFT',
    remark         varchar(500),
    started_at     timestamptz,
    posted_at      timestamptz,
    posted_by      varchar(50),
    cancel_reason  varchar(500),
    row_ver        integer        NOT NULL DEFAULT 0,
    created_at     timestamptz    NOT NULL DEFAULT now(),
    created_by     varchar(50)    NOT NULL,
    updated_at     timestamptz    NOT NULL DEFAULT now(),
    updated_by     varchar(50)    NOT NULL,
    CONSTRAINT pk_wms_count PRIMARY KEY (count_id),
    CONSTRAINT fk_wms_count_plant FOREIGN KEY (comp_cd, plant_cd) REFERENCES sys_plant (comp_cd, plant_cd),
    CONSTRAINT fk_wms_count_node FOREIGN KEY (scope_node_id) REFERENCES mst_equip_hier (node_id),
    CONSTRAINT fk_wms_count_item FOREIGN KEY (item_id) REFERENCES mst_item (item_id),
    CONSTRAINT uq_wms_count_no UNIQUE (comp_cd, count_no),
    CONSTRAINT ck_wms_count_type CHECK (count_type IN ('OPENING', 'CYCLE', 'FULL')),
    CONSTRAINT ck_wms_count_status CHECK (status IN ('DRAFT', 'COUNTING', 'REVIEW', 'POSTED', 'CANCELLED')),
    CONSTRAINT ck_wms_count_blind CHECK (blind_yn IN ('Y', 'N')),
    CONSTRAINT ck_wms_count_tol CHECK (tol_pct BETWEEN 0 AND 100),
    CONSTRAINT ck_wms_count_posted CHECK ((status = 'POSTED') = (posted_at IS NOT NULL AND posted_by IS NOT NULL)),
    CONSTRAINT ck_wms_count_cancel CHECK ((status = 'CANCELLED') = (coalesce(length(btrim(cancel_reason)), 0) > 0)),
    CONSTRAINT ck_wms_count_started CHECK (status IN ('DRAFT', 'CANCELLED') OR started_at IS NOT NULL)
);
COMMENT ON TABLE wms_count IS '재고 실사 머리 (docs/30 W5). 범위 = 창고·로케이션(하위 포함) + 품목(선택). 확정 = 줄마다 COUNT_ADJ/OPENING 원장';
CREATE INDEX ix_wms_count_plant ON wms_count (comp_cd, plant_cd, status);

-- 머리: 상태 전이 표 + 확정·취소 뒤 불변 + 범위 노드는 같은 회사·공장의 WH·LOC, 품목은 같은 회사
CREATE FUNCTION hb_guard_wms_count() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'INSERT' THEN
        IF NEW.status <> 'DRAFT' THEN
            RAISE EXCEPTION 'new count must be DRAFT' USING ERRCODE = '55000', CONSTRAINT = 'ck_wms_count_transition';
        END IF;
    ELSE
        IF (NEW.count_no, NEW.plant_cd, NEW.count_type, NEW.comp_cd) IS DISTINCT FROM (OLD.count_no, OLD.plant_cd, OLD.count_type, OLD.comp_cd)
           OR (OLD.status <> 'DRAFT' AND (NEW.scope_node_id, NEW.item_id, NEW.blind_yn, NEW.tol_pct)
                                         IS DISTINCT FROM (OLD.scope_node_id, OLD.item_id, OLD.blind_yn, OLD.tol_pct)) THEN
            RAISE EXCEPTION 'count number, plant, type are fixed; scope is fixed after start'
                USING ERRCODE = '55000', CONSTRAINT = 'ck_wms_count_fixed';
        END IF;
        IF OLD.status IN ('POSTED', 'CANCELLED') THEN
            RAISE EXCEPTION '% count cannot change', OLD.status USING ERRCODE = '55000', CONSTRAINT = 'ck_wms_count_done';
        END IF;
        IF NEW.status <> OLD.status AND NOT (
               (OLD.status = 'DRAFT'    AND NEW.status IN ('COUNTING', 'CANCELLED'))
            OR (OLD.status = 'COUNTING' AND NEW.status IN ('REVIEW', 'CANCELLED'))
            OR (OLD.status = 'REVIEW'   AND NEW.status IN ('COUNTING', 'POSTED', 'CANCELLED'))) THEN
            RAISE EXCEPTION 'count cannot go from % to %', OLD.status, NEW.status
                USING ERRCODE = '55000', CONSTRAINT = 'ck_wms_count_transition';
        END IF;
    END IF;
    IF TG_OP = 'INSERT' OR NEW.scope_node_id <> OLD.scope_node_id THEN
        IF NOT EXISTS (SELECT 1 FROM mst_equip_hier h WHERE h.node_id = NEW.scope_node_id AND h.comp_cd = NEW.comp_cd
                          AND h.plant_cd = NEW.plant_cd AND h.node_type IN ('WH', 'LOC')) THEN
            RAISE EXCEPTION 'scope node % is not a warehouse/location of plant %', NEW.scope_node_id, NEW.plant_cd
                USING ERRCODE = '23503', CONSTRAINT = 'fk_wms_count_node_comp';
        END IF;
    END IF;
    IF NEW.item_id IS NOT NULL AND (TG_OP = 'INSERT' OR NEW.item_id IS DISTINCT FROM OLD.item_id)
       AND NOT EXISTS (SELECT 1 FROM mst_item i WHERE i.item_id = NEW.item_id AND i.comp_cd = NEW.comp_cd) THEN
        RAISE EXCEPTION 'item % is not of company %', NEW.item_id, NEW.comp_cd USING ERRCODE = '23503', CONSTRAINT = 'fk_wms_count_item_comp';
    END IF;
    RETURN NEW;
END
$$;
CREATE TRIGGER tg_wms_count_guard BEFORE INSERT OR UPDATE ON wms_count FOR EACH ROW EXECUTE FUNCTION hb_guard_wms_count();
CREATE TRIGGER tg_wms_count_no_delete BEFORE DELETE ON wms_count FOR EACH ROW EXECUTE FUNCTION hb_forbid_delete_master();

-- ---------------------------------------------------------------------
-- 2. 실사 줄 — 같은 칸(위치 × 품목 × LOT × 상태)을 다시 세면 재실사 번호 +1, 확정은 칸마다 마지막 번호만
-- ---------------------------------------------------------------------
CREATE TABLE wms_count_line (
    line_id       bigint         GENERATED ALWAYS AS IDENTITY,
    comp_cd       varchar(20)    NOT NULL,
    count_id      bigint         NOT NULL,
    node_id       bigint         NOT NULL,
    item_id       bigint         NOT NULL,
    lot_id        bigint,
    stock_status  varchar(10)    NOT NULL DEFAULT 'AVAILABLE',
    recount_no    smallint       NOT NULL DEFAULT 0,
    book_qty      numeric(18,6),
    counted_qty   numeric(18,6),
    counted_at    timestamptz,
    counted_by    varchar(50),
    diff_qty      numeric(19,6)  GENERATED ALWAYS AS (counted_qty - book_qty) STORED,
    adj_trx_id    bigint,
    remark        varchar(500),
    row_ver       integer        NOT NULL DEFAULT 0,
    created_at    timestamptz    NOT NULL DEFAULT now(),
    created_by    varchar(50)    NOT NULL,
    updated_at    timestamptz    NOT NULL DEFAULT now(),
    updated_by    varchar(50)    NOT NULL,
    CONSTRAINT pk_wms_count_line PRIMARY KEY (line_id),
    CONSTRAINT fk_wms_count_line_count FOREIGN KEY (count_id) REFERENCES wms_count (count_id),
    CONSTRAINT fk_wms_count_line_node FOREIGN KEY (node_id) REFERENCES mst_equip_hier (node_id),
    CONSTRAINT fk_wms_count_line_item FOREIGN KEY (item_id) REFERENCES mst_item (item_id),
    CONSTRAINT fk_wms_count_line_trx FOREIGN KEY (adj_trx_id) REFERENCES inv_trx (trx_id),
    CONSTRAINT uq_wms_count_line_key UNIQUE NULLS NOT DISTINCT (count_id, node_id, item_id, lot_id, stock_status, recount_no),
    CONSTRAINT ck_wms_count_line_status CHECK (stock_status IN ('AVAILABLE', 'QC', 'HOLD', 'BLOCKED')),
    CONSTRAINT ck_wms_count_line_recount CHECK (recount_no BETWEEN 0 AND 99),
    -- 센 수량·센 시각·센 사람·장부는 함께 (센 시각 기준 장부를 서버가 계산)
    CONSTRAINT ck_wms_count_line_counted CHECK ((counted_qty IS NULL) = (counted_at IS NULL)
        AND (counted_qty IS NULL) = (counted_by IS NULL) AND (counted_qty IS NULL) = (book_qty IS NULL)),
    CONSTRAINT ck_wms_count_line_qty CHECK (counted_qty IS NULL OR (counted_qty >= 0 AND counted_qty < 1000000000000))
);
COMMENT ON TABLE wms_count_line IS '재고 실사 줄 (docs/30 W5). book_qty = counted_at 시각까지 원장 합(서버 계산), diff = 센 수량 − 장부';
CREATE INDEX ix_wms_count_line_count ON wms_count_line (count_id, line_id);

-- 줄: 머리가 DRAFT·COUNTING 일 때만 추가·수정(확정이 adj_trx_id 를 채우는 것만 REVIEW→POSTED 트랜잭션에서), 키는 고정,
-- 같은 회사의 머리·노드(머리 공장의 WH·LOC)·품목
CREATE FUNCTION hb_guard_wms_count_line() RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    v_cnt wms_count;
BEGIN
    SELECT * INTO v_cnt FROM wms_count c WHERE c.count_id = NEW.count_id AND c.comp_cd = NEW.comp_cd FOR SHARE;
    IF v_cnt.count_id IS NULL THEN
        RAISE EXCEPTION 'count % is not of company %', NEW.count_id, NEW.comp_cd USING ERRCODE = '23503', CONSTRAINT = 'fk_wms_count_line_count_comp';
    END IF;
    IF TG_OP = 'UPDATE' THEN
        IF (NEW.count_id, NEW.node_id, NEW.item_id, NEW.lot_id, NEW.stock_status, NEW.recount_no, NEW.comp_cd)
           IS DISTINCT FROM (OLD.count_id, OLD.node_id, OLD.item_id, OLD.lot_id, OLD.stock_status, OLD.recount_no, OLD.comp_cd) THEN
            RAISE EXCEPTION 'count line key is fixed' USING ERRCODE = '55000', CONSTRAINT = 'ck_wms_count_line_fixed';
        END IF;
        IF OLD.adj_trx_id IS NOT NULL THEN
            RAISE EXCEPTION 'posted count line cannot change' USING ERRCODE = '55000', CONSTRAINT = 'ck_wms_count_line_posted';
        END IF;
        -- 확정: 상태가 REVIEW 이고 원장 행만 붙인다(수량·장부는 그대로)
        IF NEW.adj_trx_id IS NOT NULL THEN
            IF v_cnt.status <> 'REVIEW' OR (NEW.counted_qty, NEW.book_qty, NEW.counted_at) IS DISTINCT FROM (OLD.counted_qty, OLD.book_qty, OLD.counted_at)
               OR NOT EXISTS (SELECT 1 FROM inv_trx t WHERE t.trx_id = NEW.adj_trx_id AND t.comp_cd = NEW.comp_cd AND t.src_type = 'COUNT'
                                 AND t.src_id = NEW.line_id) THEN
                RAISE EXCEPTION 'only posting links a ledger row' USING ERRCODE = '55000', CONSTRAINT = 'ck_wms_count_line_post';
            END IF;
            RETURN NEW;
        END IF;
    END IF;
    IF v_cnt.status NOT IN ('DRAFT', 'COUNTING') THEN
        RAISE EXCEPTION 'count lines change only while DRAFT/COUNTING (now %)', v_cnt.status
            USING ERRCODE = '55000', CONSTRAINT = 'ck_wms_count_line_state';
    END IF;
    IF TG_OP = 'INSERT' THEN
        IF NEW.adj_trx_id IS NOT NULL THEN
            RAISE EXCEPTION 'only posting links a ledger row' USING ERRCODE = '55000', CONSTRAINT = 'ck_wms_count_line_post';
        END IF;
        IF NOT EXISTS (SELECT 1 FROM mst_equip_hier h WHERE h.node_id = NEW.node_id AND h.comp_cd = NEW.comp_cd
                          AND h.plant_cd = v_cnt.plant_cd AND h.node_type IN ('WH', 'LOC')) THEN
            RAISE EXCEPTION 'node % is not a warehouse/location of plant %', NEW.node_id, v_cnt.plant_cd
                USING ERRCODE = '23503', CONSTRAINT = 'fk_wms_count_line_node_comp';
        END IF;
        IF NOT EXISTS (SELECT 1 FROM mst_item i WHERE i.item_id = NEW.item_id AND i.comp_cd = NEW.comp_cd) THEN
            RAISE EXCEPTION 'item % is not of company %', NEW.item_id, NEW.comp_cd USING ERRCODE = '23503', CONSTRAINT = 'fk_wms_count_line_item_comp';
        END IF;
    END IF;
    RETURN NEW;
END
$$;
CREATE TRIGGER tg_wms_count_line_guard BEFORE INSERT OR UPDATE ON wms_count_line FOR EACH ROW EXECUTE FUNCTION hb_guard_wms_count_line();
CREATE TRIGGER tg_wms_count_line_no_delete BEFORE DELETE ON wms_count_line FOR EACH ROW EXECUTE FUNCTION hb_forbid_delete_master();

-- ---------------------------------------------------------------------
-- 3. 기간 마감 (공장 × 모듈 × 월)
-- ---------------------------------------------------------------------
CREATE TABLE sys_period_close (
    close_id     bigint         GENERATED ALWAYS AS IDENTITY,
    comp_cd      varchar(20)    NOT NULL,
    plant_cd     varchar(20)    NOT NULL,
    module_cd    varchar(10)    NOT NULL,
    period       char(6)        NOT NULL,
    status       varchar(10)    NOT NULL,
    closed_at    timestamptz    NOT NULL,
    closed_by    varchar(50)    NOT NULL,
    reopened_at  timestamptz,
    reopened_by  varchar(50),
    reason       varchar(500),
    row_ver      integer        NOT NULL DEFAULT 0,
    created_at   timestamptz    NOT NULL DEFAULT now(),
    created_by   varchar(50)    NOT NULL,
    updated_at   timestamptz    NOT NULL DEFAULT now(),
    updated_by   varchar(50)    NOT NULL,
    CONSTRAINT pk_sys_period_close PRIMARY KEY (close_id),
    CONSTRAINT fk_sys_period_close_plant FOREIGN KEY (comp_cd, plant_cd) REFERENCES sys_plant (comp_cd, plant_cd),
    CONSTRAINT uq_sys_period_close UNIQUE (comp_cd, plant_cd, module_cd, period),
    CONSTRAINT ck_sys_period_close_module CHECK (module_cd ~ '^[A-Z]{2,10}$'),
    CONSTRAINT ck_sys_period_close_period CHECK (period ~ '^[0-9]{4}(0[1-9]|1[0-2])$'),
    CONSTRAINT ck_sys_period_close_status CHECK (status IN ('CLOSED', 'REOPENED')),
    -- 재개 상태면 재개 시각·사람·사유가 있다(다시 마감해도 마지막 재개 사유는 남긴다 — 이력은 감사 로그)
    CONSTRAINT ck_sys_period_close_reopen CHECK (status = 'CLOSED'
        OR (reopened_at IS NOT NULL AND reopened_by IS NOT NULL AND coalesce(length(btrim(reason)), 0) >= 2))
);
COMMENT ON TABLE sys_period_close IS '기간 마감 (공장 × 모듈 × 월, docs/30 W5·00 5.3). 마감된 월의 원장 입력은 각 모듈 원장 트리거가 막는다(INV = inv_trx)';
CREATE TRIGGER tg_sys_period_close_no_delete BEFORE DELETE ON sys_period_close FOR EACH ROW EXECUTE FUNCTION hb_forbid_delete_master();

-- 마감 잠금 키(회사·공장·모듈·월) — 원장 트리거(공유)와 마감·재개 서비스(배타)가 같은 키를 쓴다
CREATE FUNCTION hb_period_lock_key(p_comp varchar, p_plant varchar, p_module varchar, p_period text) RETURNS bigint
    LANGUAGE sql IMMUTABLE
AS $$ SELECT hashtextextended('PERIOD:' || p_comp || ':' || p_plant || ':' || p_module || ':' || p_period, 0) $$;

-- 원장 INSERT: 마감된 공장·월(공장 시간대 — 없으면 회사 시간대)이면 거부. 역분개도 원래 발생 시각이라 같이 막힌다
CREATE FUNCTION hb_check_inv_period() RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    v_tz     text;
    v_period text;
BEGIN
    SELECT coalesce(p.time_zone, c.time_zone) INTO v_tz
      FROM sys_plant p JOIN sys_comp c ON c.comp_cd = p.comp_cd
     WHERE p.comp_cd = NEW.comp_cd AND p.plant_cd = NEW.plant_cd;
    v_period := to_char(NEW.occurred_at AT TIME ZONE coalesce(v_tz, 'Asia/Seoul'), 'YYYYMM');
    -- 마감과 줄 세우기: 원장은 그 공장·월 잠금을 공유로, 마감·재개는 배타로 잡는다(hb_period_lock_key) — 마감 중에 들어온 원장은
    -- 마감이 커밋된 뒤 아래 확인(새 스냅샷)에서 막히고, 먼저 들어온 원장이 커밋될 때까지 마감이 기다린다
    PERFORM pg_advisory_xact_lock_shared(hb_period_lock_key(NEW.comp_cd, NEW.plant_cd, 'INV', v_period));
    IF EXISTS (SELECT 1 FROM sys_period_close pc WHERE pc.comp_cd = NEW.comp_cd AND pc.plant_cd = NEW.plant_cd AND pc.module_cd = 'INV'
                  AND pc.period = v_period AND pc.status = 'CLOSED') THEN
        RAISE EXCEPTION 'inventory period % of plant % is closed', v_period, NEW.plant_cd
            USING ERRCODE = '55000', CONSTRAINT = 'ck_inv_trx_period_closed';
    END IF;
    RETURN NEW;
END
$$;
-- 이름순으로 tg_inv_trx_guard 뒤에 돈다(같은 BEFORE 시점은 이름순 — 회사·품목 검사가 먼저)
CREATE TRIGGER tg_inv_trx_period BEFORE INSERT ON inv_trx FOR EACH ROW EXECUTE FUNCTION hb_check_inv_period();

-- ---------------------------------------------------------------------
-- 4. RLS · 감사 · 권한 · 화면 · 보존
-- ---------------------------------------------------------------------
CREATE TRIGGER tg_audit AFTER INSERT OR UPDATE OR DELETE ON wms_count FOR EACH ROW EXECUTE FUNCTION hb_audit('count_id', '', '');
CREATE TRIGGER tg_audit AFTER INSERT OR UPDATE OR DELETE ON wms_count_line FOR EACH ROW EXECUTE FUNCTION hb_audit('line_id', '', '');
CREATE TRIGGER tg_audit AFTER INSERT OR UPDATE OR DELETE ON sys_period_close FOR EACH ROW EXECUTE FUNCTION hb_audit('close_id', '', '');

ALTER TABLE wms_count ENABLE ROW LEVEL SECURITY;
ALTER TABLE wms_count FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON wms_count USING (comp_cd = hb_current_comp()) WITH CHECK (comp_cd = hb_current_comp());
ALTER TABLE wms_count_line ENABLE ROW LEVEL SECURITY;
ALTER TABLE wms_count_line FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON wms_count_line USING (comp_cd = hb_current_comp()) WITH CHECK (comp_cd = hb_current_comp());
ALTER TABLE sys_period_close ENABLE ROW LEVEL SECURITY;
ALTER TABLE sys_period_close FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON sys_period_close USING (comp_cd = hb_current_comp()) WITH CHECK (comp_cd = hb_current_comp());
REVOKE DELETE, TRUNCATE ON wms_count, wms_count_line, sys_period_close FROM ${app_role};

INSERT INTO sys_prog (prog_cd, prog_nm_key, view_path, module_cd, actions, sort_no) VALUES
    ('wms-count',  'prog.wmsCount',  'wms/count',  'WMS', ARRAY['SEARCH','SAVE','APPROVE']::varchar(20)[], 760),
    ('wms-period', 'prog.wmsPeriod', 'wms/period', 'WMS', ARRAY['SEARCH','APPROVE']::varchar(20)[], 770);
INSERT INTO sys_retention_table (table_nm, class_cd) VALUES
    ('wms_count', 'QUALITY_RECORD'), ('wms_count_line', 'QUALITY_RECORD'), ('sys_period_close', 'SYSTEM');
