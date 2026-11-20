-- =====================================================================
-- WMS W-12 적치 검사 — 위치 ↔ 허용 품목군 (2026-11-02 사용자 결정, docs/30 12.19) — 임시 번호(docs/29 2.2)
--
-- - wms_loc_class_allow: 창고·로케이션 노드에 "이 품목군만 둔다" 목록. 행 = (노드, 품목군), 빼기는 use_yn = 'N'(마스터 삭제 금지 규칙)
-- - 실효 목록 = 자기 노드에 사용 중 행이 있으면 그것, 없으면 가장 가까운 상위 노드의 목록(상속), 어디에도 없으면 전부 허용
-- - 판정은 서비스 하나(PutawayCheck) — 입고·이동·PDA·고객 반품 받는 칸이 같은 함수를 부른다
-- - 정책 WMS_PUTAWAY_CHECK(공장) = OFF / WARN(기본 — 저장 + 경고) / BLOCK(409)
-- =====================================================================

CREATE TABLE wms_loc_class_allow (
    allow_id       bigint        GENERATED ALWAYS AS IDENTITY,
    comp_cd        varchar(20)   NOT NULL,
    node_id        bigint        NOT NULL,
    item_class_id  bigint        NOT NULL,
    use_yn         char(1)       NOT NULL DEFAULT 'Y',
    row_ver        integer       NOT NULL DEFAULT 0,
    created_at     timestamptz   NOT NULL DEFAULT now(),
    created_by     varchar(50)   NOT NULL,
    updated_at     timestamptz   NOT NULL DEFAULT now(),
    updated_by     varchar(50)   NOT NULL,
    CONSTRAINT pk_wms_loc_class_allow PRIMARY KEY (allow_id),
    CONSTRAINT uq_wms_loc_class_allow UNIQUE (comp_cd, node_id, item_class_id),
    CONSTRAINT fk_wms_loc_class_allow_node FOREIGN KEY (node_id) REFERENCES mst_equip_hier (node_id),
    CONSTRAINT fk_wms_loc_class_allow_class FOREIGN KEY (item_class_id) REFERENCES mst_item_class (class_id),
    CONSTRAINT ck_wms_loc_class_allow_use_yn CHECK (use_yn IN ('Y', 'N'))
);
COMMENT ON TABLE wms_loc_class_allow IS '적치 검사 허용 품목군 (W-12, docs/30 12.19) — 노드에 사용 중 행이 있으면 그 품목군만 둔다, 없으면 가장 가까운 상위 노드 목록 상속, 어디에도 없으면 전부 허용';
COMMENT ON COLUMN wms_loc_class_allow.node_id IS '창고·로케이션 노드(mst_equip_hier, WH·LOC)';
COMMENT ON COLUMN wms_loc_class_allow.use_yn IS 'N = 목록에서 뺌(행은 남김 — 마스터 삭제 금지)';
CREATE INDEX ix_wms_loc_class_allow_node ON wms_loc_class_allow (comp_cd, node_id) WHERE use_yn = 'Y';

-- 노드·품목군은 같은 회사 것만(FK 는 RLS 를 보지 않는다), 노드는 창고·로케이션만, 회사·노드·품목군은 고정(바꾸려면 빼고 새로)
CREATE FUNCTION hb_guard_wms_loc_class_allow() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'UPDATE' AND (NEW.comp_cd IS DISTINCT FROM OLD.comp_cd OR NEW.node_id IS DISTINCT FROM OLD.node_id
                             OR NEW.item_class_id IS DISTINCT FROM OLD.item_class_id) THEN
        RAISE EXCEPTION 'putaway allow row key is fixed' USING ERRCODE = '55000', CONSTRAINT = 'ck_wms_loc_class_allow_fixed';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM mst_equip_hier h WHERE h.node_id = NEW.node_id AND h.comp_cd = NEW.comp_cd AND h.node_type IN ('WH', 'LOC'))
       OR NOT EXISTS (SELECT 1 FROM mst_item_class k WHERE k.class_id = NEW.item_class_id AND k.comp_cd = NEW.comp_cd) THEN
        RAISE EXCEPTION 'putaway allow target belongs to another company or is not a storage node'
            USING ERRCODE = '23514', CONSTRAINT = 'ck_wms_loc_class_allow_comp';
    END IF;
    RETURN NEW;
END
$$;
CREATE TRIGGER tg_guard BEFORE INSERT OR UPDATE ON wms_loc_class_allow FOR EACH ROW EXECUTE FUNCTION hb_guard_wms_loc_class_allow();
CREATE TRIGGER tg_no_delete BEFORE DELETE ON wms_loc_class_allow FOR EACH ROW EXECUTE FUNCTION hb_forbid_delete_master();
CREATE TRIGGER tg_audit AFTER INSERT OR UPDATE OR DELETE ON wms_loc_class_allow FOR EACH ROW EXECUTE FUNCTION hb_audit('allow_id', '', '');

ALTER TABLE wms_loc_class_allow ENABLE ROW LEVEL SECURITY;
ALTER TABLE wms_loc_class_allow FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON wms_loc_class_allow USING (comp_cd = hb_current_comp()) WITH CHECK (comp_cd = hb_current_comp());

INSERT INTO sys_retention_table (table_nm, class_cd) VALUES ('wms_loc_class_allow', 'MASTER');

-- 공장 정책 — 기본 WARN(사용자 결정 2026-11-02)
INSERT INTO sys_policy_def (policy_key, value_type, default_value, allowed_values, min_value, max_value, plant_scope_yn, desc_key) VALUES
    ('WMS_PUTAWAY_CHECK', 'ENUM', 'WARN', ARRAY['OFF','WARN','BLOCK'], NULL, NULL, 'Y', 'policy.WMS_PUTAWAY_CHECK');
