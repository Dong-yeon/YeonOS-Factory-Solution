-- =====================================================================
-- V037 로케이션 속성·스캔 키 (P4 WMS 1부 W1 — docs/30 4장 W1, 결정 D7)
--
-- - wms_loc_attr: 창고·로케이션 노드(mst_equip_hier WH·LOC)의 WMS 속성 1:1 곁 테이블 — P1 테이블은 고치지 않는다
--   (docs/19 "로케이션 용량·혼적·피킹 순서는 P4"). 행이 없으면 기본값(STORAGE · 재고 둠 · 혼적 허용 · 피킹 순서 0)
-- - 코드 WMS_LOC_USAGE(로케이션 용도), STOCK_STATUS(재고 상태 — W3 원장도 쓴다)
-- - 정책 WMS_SCAN_BARE_AS: 접두어 없는 바코드를 무엇으로 읽나(LOC/ITEM/NONE)
-- ※ 병행 개발(docs/29 6장): main 에 늦게 들어가는 쪽이 이 번호를 다시 매긴다
-- =====================================================================

INSERT INTO sys_policy_def (policy_key, value_type, default_value, allowed_values, min_value, max_value, plant_scope_yn, desc_key) VALUES
    -- docs/30 D7: 기존 로케이션 라벨(코드만 찍힌 것)과 호환 — 기본은 로케이션으로 읽는다
    ('WMS_SCAN_BARE_AS', 'ENUM', 'LOC', ARRAY['LOC','ITEM','NONE'], NULL, NULL, 'N', 'policy.WMS_SCAN_BARE_AS');

INSERT INTO sys_code_grp (grp_cd, grp_nm_key, comp_ext_yn) VALUES
    ('WMS_LOC_USAGE', 'code.grp.WMS_LOC_USAGE', 'N'),
    ('STOCK_STATUS',  'code.grp.STOCK_STATUS',  'N');
INSERT INTO sys_code (grp_cd, code, code_nm_key, sort_no) VALUES
    ('WMS_LOC_USAGE', 'STORAGE',   'code.WMS_LOC_USAGE.STORAGE',   1),
    ('WMS_LOC_USAGE', 'RECEIVING', 'code.WMS_LOC_USAGE.RECEIVING', 2),
    ('WMS_LOC_USAGE', 'QC',        'code.WMS_LOC_USAGE.QC',        3),
    ('WMS_LOC_USAGE', 'STAGING',   'code.WMS_LOC_USAGE.STAGING',   4),
    ('WMS_LOC_USAGE', 'LINESIDE',  'code.WMS_LOC_USAGE.LINESIDE',  5),
    ('WMS_LOC_USAGE', 'SCRAP',     'code.WMS_LOC_USAGE.SCRAP',     6),
    ('STOCK_STATUS',  'AVAILABLE', 'code.STOCK_STATUS.AVAILABLE',  1),
    ('STOCK_STATUS',  'QC',        'code.STOCK_STATUS.QC',         2),
    ('STOCK_STATUS',  'HOLD',      'code.STOCK_STATUS.HOLD',       3),
    ('STOCK_STATUS',  'BLOCKED',   'code.STOCK_STATUS.BLOCKED',    4);

CREATE TABLE wms_loc_attr (
    node_id         bigint        NOT NULL,
    comp_cd         varchar(20)   NOT NULL,
    loc_usage       varchar(10)   NOT NULL DEFAULT 'STORAGE',
    stock_yn        char(1)       NOT NULL DEFAULT 'Y',
    pick_seq        integer       NOT NULL DEFAULT 0,
    mix_item_yn     char(1)       NOT NULL DEFAULT 'Y',
    mix_lot_yn      char(1)       NOT NULL DEFAULT 'Y',
    default_status  varchar(10),
    remark          varchar(500),
    row_ver         integer       NOT NULL DEFAULT 0,
    created_at      timestamptz   NOT NULL DEFAULT now(),
    created_by      varchar(50)   NOT NULL,
    updated_at      timestamptz   NOT NULL DEFAULT now(),
    updated_by      varchar(50)   NOT NULL,
    CONSTRAINT pk_wms_loc_attr PRIMARY KEY (node_id),
    CONSTRAINT fk_wms_loc_attr_node FOREIGN KEY (node_id) REFERENCES mst_equip_hier (node_id),
    CONSTRAINT fk_wms_loc_attr_comp FOREIGN KEY (comp_cd) REFERENCES sys_comp (comp_cd),
    CONSTRAINT ck_wms_loc_attr_usage CHECK (loc_usage IN ('STORAGE', 'RECEIVING', 'QC', 'STAGING', 'LINESIDE', 'SCRAP')),
    CONSTRAINT ck_wms_loc_attr_stock_yn CHECK (stock_yn IN ('Y', 'N')),
    CONSTRAINT ck_wms_loc_attr_pick_seq CHECK (pick_seq BETWEEN 0 AND 999999),
    CONSTRAINT ck_wms_loc_attr_mix_item CHECK (mix_item_yn IN ('Y', 'N')),
    CONSTRAINT ck_wms_loc_attr_mix_lot CHECK (mix_lot_yn IN ('Y', 'N')),
    -- 품목 혼적을 허용하면서 LOT 혼적만 막는 건 말이 되지만, 그 반대(품목 혼적 금지 + LOT 혼적 허용)도 된다 — 둘은 독립
    CONSTRAINT ck_wms_loc_attr_status CHECK (default_status IN ('AVAILABLE', 'QC', 'HOLD', 'BLOCKED'))
);
COMMENT ON TABLE wms_loc_attr IS '창고·로케이션 WMS 속성 (mst_equip_hier WH·LOC 1:1, docs/30 W1). 행이 없으면 기본값. 용량은 하지 않는다(docs/30 9장)';
COMMENT ON COLUMN wms_loc_attr.stock_yn IS '재고를 둘 수 있는 노드인가 — 랙 같은 묶음 노드는 N. W3 원장 위치 검사가 본다';
COMMENT ON COLUMN wms_loc_attr.pick_seq IS '피킹 순서(작을수록 먼저) — W4 차감 전략의 동률 깨기·LOC_SEQ 전략';
COMMENT ON COLUMN wms_loc_attr.default_status IS '이 위치로 들어오는 재고의 기본 상태 제안(QC 구역 → QC). 강제 아님';

-- 노드는 같은 회사의 창고·로케이션만, 새로 붙일 때는 사용 중인 노드만, 대상 노드는 바꾸지 않는다.
-- 장비 계층 노드의 유형은 만든 뒤 고정(V020)이라 여기서 한 번만 보면 된다.
CREATE FUNCTION hb_guard_wms_loc_attr() RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    v_type varchar(10);
    v_use  char(1);
BEGIN
    IF TG_OP = 'UPDATE' AND NEW.node_id <> OLD.node_id THEN
        RAISE EXCEPTION 'location attribute node is fixed' USING ERRCODE = '55000', CONSTRAINT = 'ck_wms_loc_attr_node_fixed';
    END IF;
    SELECT h.node_type, h.use_yn INTO v_type, v_use
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
    RETURN NEW;
END
$$;
CREATE TRIGGER tg_wms_loc_attr_guard BEFORE INSERT OR UPDATE ON wms_loc_attr FOR EACH ROW EXECUTE FUNCTION hb_guard_wms_loc_attr();
CREATE TRIGGER tg_wms_loc_attr_no_delete BEFORE DELETE ON wms_loc_attr FOR EACH ROW EXECUTE FUNCTION hb_forbid_delete_master();
CREATE TRIGGER tg_audit AFTER INSERT OR UPDATE OR DELETE ON wms_loc_attr FOR EACH ROW EXECUTE FUNCTION hb_audit('node_id', '', '');

ALTER TABLE wms_loc_attr ENABLE ROW LEVEL SECURITY;
ALTER TABLE wms_loc_attr FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON wms_loc_attr USING (comp_cd = hb_current_comp()) WITH CHECK (comp_cd = hb_current_comp());
REVOKE DELETE, TRUNCATE ON wms_loc_attr FROM ${app_role};

INSERT INTO sys_prog (prog_cd, prog_nm_key, view_path, module_cd, actions, sort_no) VALUES
    ('wms-loc', 'prog.wmsLoc', 'wms/loc-attr', 'WMS', ARRAY['SEARCH','SAVE','EXCEL']::varchar(20)[], 400);
INSERT INTO sys_retention_table (table_nm, class_cd) VALUES ('wms_loc_attr', 'MASTER');
