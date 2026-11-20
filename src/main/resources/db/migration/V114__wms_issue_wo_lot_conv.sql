-- =====================================================================
-- WMS 2부 B5 투입 차감 ISSUE_WO + B2+ LOT 변환 CONV_OUT/IN (docs/30 12.4, D-B11~D-B13 사용자 확정 2026-10-22) — 임시 번호(docs/29 2.2)
--
-- - inv_trx 유형 ISSUE_WO(−, 출처 LOT_GEN = 계보 CONSUME 행) · CONV_OUT(−)/CONV_IN(+)(짝, 출처 LOT_GEN = 계보 변환 행)
-- - 변환 짝 = 같은 품목·같은 위치·같은 상태, LOT 이 다름(이동 짝과 반대) — 지연 짝 검사 함수를 바꾼다
-- - wms_loc_attr.feed_node_id: LINESIDE 위치가 보급하는 LINE·CELL(D-B12) — V020 계층 규칙은 그대로
-- - 공장 정책 WMS_STOCK_LINK(N 기본): MES POP 대기열의 409 보존이 들어간 뒤에만 켠다(docs/30 12.4.2)
-- - 남의 표(mst_*·prd_*·lot_*) 변경 없음 — lot_genealogy·prd_wo_proc 은 읽기만
-- =====================================================================

INSERT INTO sys_code (grp_cd, code, code_nm_key, sort_no) VALUES
    ('INV_TRX_TYPE', 'ISSUE_WO', 'code.INV_TRX_TYPE.ISSUE_WO', 22),
    ('INV_TRX_TYPE', 'CONV_OUT', 'code.INV_TRX_TYPE.CONV_OUT', 23),
    ('INV_TRX_TYPE', 'CONV_IN',  'code.INV_TRX_TYPE.CONV_IN',  24);

ALTER TABLE inv_trx DROP CONSTRAINT ck_inv_trx_type;
ALTER TABLE inv_trx ADD CONSTRAINT ck_inv_trx_type CHECK (trx_type IN ('OPENING', 'ADJ_IN', 'ADJ_OUT', 'MOVE_OUT', 'MOVE_IN', 'STATUS_OUT',
                                                                        'STATUS_IN', 'COUNT_ADJ', 'RCV_PRD', 'RCV_MAT', 'ISSUE_WO',
                                                                        'CONV_OUT', 'CONV_IN'));
ALTER TABLE inv_trx DROP CONSTRAINT ck_inv_trx_sign;
ALTER TABLE inv_trx ADD CONSTRAINT ck_inv_trx_sign CHECK (
    trx_type = 'COUNT_ADJ'
    OR (trx_type IN ('OPENING', 'ADJ_IN', 'MOVE_IN', 'STATUS_IN', 'RCV_PRD', 'RCV_MAT', 'CONV_IN') AND (qty > 0) = (reversal_of_id IS NULL))
    OR (trx_type IN ('ADJ_OUT', 'MOVE_OUT', 'STATUS_OUT', 'ISSUE_WO', 'CONV_OUT') AND (qty < 0) = (reversal_of_id IS NULL)));
ALTER TABLE inv_trx DROP CONSTRAINT ck_inv_trx_move;
ALTER TABLE inv_trx ADD CONSTRAINT ck_inv_trx_move CHECK (
    (trx_type IN ('MOVE_OUT', 'MOVE_IN', 'STATUS_OUT', 'STATUS_IN', 'CONV_OUT', 'CONV_IN')) = (move_grp_id IS NOT NULL));
-- 투입·변환은 계보 행이 출처다(직접 SQL 로 출처 없는 행 금지)
ALTER TABLE inv_trx ADD CONSTRAINT ck_inv_trx_lot_gen_src CHECK (
    trx_type NOT IN ('ISSUE_WO', 'CONV_OUT', 'CONV_IN') OR (src_type = 'LOT_GEN' AND src_id IS NOT NULL));

-- 짝 검사: 이동·상태 = 같은 LOT, 다른 (위치·상태) / 변환 = 같은 (위치·상태), 다른 LOT. 둘 다 2행·합 0·같은 품목·OUT/IN 짝·같은 공장
CREATE OR REPLACE FUNCTION hb_check_inv_move() RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    r record;
BEGIN
    SELECT count(*) AS n, sum(qty) AS total, count(DISTINCT item_id) AS items, count(DISTINCT coalesce(lot_id, -1)) AS lots,
           count(DISTINCT (node_id, stock_status)) AS places, count(DISTINCT regexp_replace(trx_type, '_(IN|OUT)$', '')) AS kinds,
           min(regexp_replace(trx_type, '_(IN|OUT)$', '')) AS kind,
           bool_or(trx_type LIKE '%\_OUT' ESCAPE '\' ) AND bool_or(trx_type LIKE '%\_IN' ESCAPE '\') AS paired,
           bool_and(comp_cd = NEW.comp_cd AND plant_cd = NEW.plant_cd) AS same_plant,
           bool_and(lot_id IS NOT NULL) AS all_lots
      INTO r
      FROM inv_trx WHERE move_grp_id = NEW.move_grp_id;
    IF r.n <> 2 OR r.total <> 0 OR r.items <> 1 OR r.kinds <> 1 OR NOT r.paired OR NOT r.same_plant
       OR (r.kind = 'CONV' AND (r.lots <> 2 OR r.places <> 1 OR NOT r.all_lots))
       OR (r.kind <> 'CONV' AND (r.lots <> 1 OR r.places <> 2)) THEN
        RAISE EXCEPTION 'move group % must be one OUT row and one IN row of the same item that sum to zero', NEW.move_grp_id
            USING ERRCODE = '23514', CONSTRAINT = 'ck_inv_trx_move_pair';
    END IF;
    RETURN NULL;
END
$$;

-- ---------------------------------------------------------------------
-- LINESIDE 보급 라인 (D-B12)
-- ---------------------------------------------------------------------
ALTER TABLE wms_loc_attr ADD COLUMN feed_node_id bigint;
ALTER TABLE wms_loc_attr ADD CONSTRAINT fk_wms_loc_attr_feed FOREIGN KEY (feed_node_id) REFERENCES mst_equip_hier (node_id);
ALTER TABLE wms_loc_attr ADD CONSTRAINT ck_wms_loc_attr_feed CHECK (feed_node_id IS NULL OR loc_usage = 'LINESIDE');
COMMENT ON COLUMN wms_loc_attr.feed_node_id IS '이 LINESIDE 위치가 보급하는 LINE·CELL(같은 회사·같은 공장) — 투입 차감이 먼저 보는 위치(docs/30 12.4.3, D-B12)';
CREATE INDEX ix_wms_loc_attr_feed ON wms_loc_attr (comp_cd, feed_node_id) WHERE feed_node_id IS NOT NULL;

-- V037 판에 보급 라인 검사를 더한다(같은 회사·같은 공장의 LINE·CELL)
CREATE OR REPLACE FUNCTION hb_guard_wms_loc_attr() RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    v_type  varchar(10);
    v_use   char(1);
    v_plant varchar(20);
BEGIN
    IF TG_OP = 'UPDATE' AND NEW.node_id <> OLD.node_id THEN
        RAISE EXCEPTION 'location attribute node is fixed' USING ERRCODE = '55000', CONSTRAINT = 'ck_wms_loc_attr_node_fixed';
    END IF;
    SELECT h.node_type, h.use_yn, h.plant_cd INTO v_type, v_use, v_plant
      FROM mst_equip_hier h WHERE h.node_id = NEW.node_id AND h.comp_cd = NEW.comp_cd;
    IF v_type IS NULL THEN
        RAISE EXCEPTION 'node % is not a node of company %', NEW.node_id, NEW.comp_cd
            USING ERRCODE = '23503', CONSTRAINT = 'fk_wms_loc_attr_node_comp';
    END IF;
    IF v_type NOT IN ('WH', 'LOC') THEN
        RAISE EXCEPTION 'node % is % (only WH and LOC have location attributes)', NEW.node_id, v_type
            USING ERRCODE = '23514', CONSTRAINT = 'ck_wms_loc_attr_node_type';
    END IF;
    IF TG_OP = 'INSERT' AND v_use <> 'Y' THEN
        RAISE EXCEPTION 'node % is not in use', NEW.node_id USING ERRCODE = '23514', CONSTRAINT = 'ck_wms_loc_attr_node_use';
    END IF;
    IF NEW.feed_node_id IS NOT NULL AND (TG_OP = 'INSERT' OR NEW.feed_node_id IS DISTINCT FROM OLD.feed_node_id)
       AND NOT EXISTS (SELECT 1 FROM mst_equip_hier f WHERE f.node_id = NEW.feed_node_id AND f.comp_cd = NEW.comp_cd
                          AND f.plant_cd = v_plant AND f.node_type IN ('LINE', 'CELL') AND f.use_yn = 'Y') THEN
        RAISE EXCEPTION 'feed node % must be an active LINE or CELL of the same plant', NEW.feed_node_id
            USING ERRCODE = '23503', CONSTRAINT = 'fk_wms_loc_attr_feed_comp';
    END IF;
    RETURN NEW;
END
$$;

INSERT INTO sys_policy_def (policy_key, value_type, default_value, allowed_values, min_value, max_value, plant_scope_yn, desc_key) VALUES
    ('WMS_STOCK_LINK', 'BOOL', 'N', NULL, NULL, NULL, 'Y', 'policy.WMS_STOCK_LINK');
