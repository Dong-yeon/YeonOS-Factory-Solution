-- P6 APQP A5-2b (docs/41 13.8) — DFMEA 재검토·BOM 다시 잇기, PFMEA 고장 영향의 DFMEA 출처(D6·D7)
-- - apqp_fmea_fail.src_fail_id: PFMEA 고장 영향(FE)을 DFMEA 고장 형태(FM)에서 제안받아 만들었을 때 그 출처(글자는 복사)
-- - BOM 바꾸기 규칙: "자품목 노드가 있으면 금지" → "자품목을 가리키는 하위 시스템은 모두 새 BOM 의 줄"(다시 잇기용)
-- 재검토 표시(BOM·도면 새 리비전)는 조회 계산이라 스키마 변경 없음

ALTER TABLE apqp_fmea_fail ADD COLUMN src_fail_id bigint;
ALTER TABLE apqp_fmea_fail ADD CONSTRAINT fk_apqp_fmea_fail_src FOREIGN KEY (src_fail_id) REFERENCES apqp_fmea_fail (fail_id);
ALTER TABLE apqp_fmea_fail ADD CONSTRAINT ck_apqp_fmea_fail_src CHECK (src_fail_id IS NULL OR fail_kind = 'FE');
CREATE INDEX ix_apqp_fmea_fail_src ON apqp_fmea_fail (src_fail_id) WHERE src_fail_id IS NOT NULL;
COMMENT ON COLUMN apqp_fmea_fail.src_fail_id IS 'PFMEA 고장 영향의 출처 — 같은 품목 확정 DFMEA 의 고장 형태(제안만, 글자는 복사)';

-- 머리 — V211 과 같고 BOM 바꾸기 규칙만 다르다
CREATE OR REPLACE FUNCTION hb_guard_apqp_fmea() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        IF OLD.status <> 'DRAFT' THEN
            RAISE EXCEPTION 'released fmea % cannot be deleted', OLD.fmea_id USING ERRCODE = '55000', CONSTRAINT = 'ck_apqp_fmea_fixed';
        END IF;
        RETURN OLD;
    END IF;
    IF TG_OP = 'UPDATE' THEN
        -- 확정본: 상태 RELEASED → OBSOLETE 만, 또는 상태 그대로 row_ver 만(확정본 조치 진행 때 머리 낡음 표시) — 나머지 칸 그대로
        IF OLD.status IN ('RELEASED', 'OBSOLETE') THEN
            IF NOT (NEW.status = OLD.status OR (OLD.status = 'RELEASED' AND NEW.status = 'OBSOLETE'))
               OR (to_jsonb(NEW) - ARRAY['status', 'row_ver', 'updated_at', 'updated_by'])
                  IS DISTINCT FROM (to_jsonb(OLD) - ARRAY['status', 'row_ver', 'updated_at', 'updated_by']) THEN
                RAISE EXCEPTION 'fmea % is %', OLD.fmea_id, OLD.status USING ERRCODE = '55000', CONSTRAINT = 'ck_apqp_fmea_fixed';
            END IF;
            RETURN NEW;
        END IF;
        -- 결재 중: 내용 그대로, 승인 = RELEASED(확정 칸), 반려·회수 = DRAFT
        IF OLD.status = 'IN_APPROVAL' THEN
            IF NEW.status NOT IN ('IN_APPROVAL', 'DRAFT', 'RELEASED')
               OR (to_jsonb(NEW) - ARRAY['status', 'row_ver', 'updated_at', 'updated_by', 'released_at', 'released_by'])
                  IS DISTINCT FROM (to_jsonb(OLD) - ARRAY['status', 'row_ver', 'updated_at', 'updated_by', 'released_at', 'released_by']) THEN
                RAISE EXCEPTION 'fmea % is IN_APPROVAL', OLD.fmea_id USING ERRCODE = '55000', CONSTRAINT = 'ck_apqp_fmea_fixed';
            END IF;
        ELSIF NEW.status NOT IN ('DRAFT', 'IN_APPROVAL', 'RELEASED') OR NEW.fmea_no <> OLD.fmea_no OR NEW.rev <> OLD.rev
           OR NEW.comp_cd <> OLD.comp_cd THEN
            RAISE EXCEPTION 'fmea % transition', OLD.fmea_id USING ERRCODE = '55000', CONSTRAINT = 'ck_apqp_fmea_transition';
        END IF;
        IF NEW.status IN ('IN_APPROVAL', 'RELEASED') AND NEW.status <> OLD.status
           AND EXISTS (SELECT 1 FROM apqp_fmea_risk r WHERE r.fmea_id = NEW.fmea_id AND r.ap IS NULL) THEN
            RAISE EXCEPTION 'fmea % has risks without ap', NEW.fmea_id USING ERRCODE = '23514', CONSTRAINT = 'ck_apqp_fmea_risk_ap';
        END IF;
        IF OLD.status = 'IN_APPROVAL' THEN
            RETURN NEW;
        END IF;
    ELSIF NEW.status <> 'DRAFT' THEN
        RAISE EXCEPTION 'fmea starts as DRAFT' USING ERRCODE = '55000', CONSTRAINT = 'ck_apqp_fmea_transition';
    END IF;
    -- 같은 회사(FK 는 RLS 를 무시) + 라우팅은 머리 품목의 것
    IF NOT EXISTS (SELECT 1 FROM mst_item x WHERE x.item_id = NEW.item_id AND x.comp_cd = NEW.comp_cd) THEN
        RAISE EXCEPTION 'item % is not of company %', NEW.item_id, NEW.comp_cd USING ERRCODE = '23503', CONSTRAINT = 'fk_apqp_fmea_item_comp';
    END IF;
    IF NEW.routing_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM mst_routing x WHERE x.routing_id = NEW.routing_id
                                                     AND x.item_id = NEW.item_id AND x.comp_cd = NEW.comp_cd) THEN
        RAISE EXCEPTION 'routing % is not of item %', NEW.routing_id, NEW.item_id USING ERRCODE = '23503', CONSTRAINT = 'fk_apqp_fmea_routing_item';
    END IF;
    IF TG_OP = 'UPDATE' AND NEW.routing_id IS DISTINCT FROM OLD.routing_id
       AND EXISTS (SELECT 1 FROM apqp_fmea_node n WHERE n.fmea_id = NEW.fmea_id AND n.step_id IS NOT NULL) THEN
        RAISE EXCEPTION 'fmea % has steps of the routing', NEW.fmea_id USING ERRCODE = '23514', CONSTRAINT = 'ck_apqp_fmea_routing_used';
    END IF;
    IF NEW.proj_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM pms_project x WHERE x.proj_id = NEW.proj_id AND x.comp_cd = NEW.comp_cd) THEN
        RAISE EXCEPTION 'project % is not of company %', NEW.proj_id, NEW.comp_cd USING ERRCODE = '23503', CONSTRAINT = 'fk_apqp_fmea_proj_comp';
    END IF;
    IF NEW.ap_set_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM apqp_ap_set x WHERE x.set_id = NEW.ap_set_id AND x.comp_cd = NEW.comp_cd) THEN
        RAISE EXCEPTION 'ap set % is not of company %', NEW.ap_set_id, NEW.comp_cd USING ERRCODE = '23503', CONSTRAINT = 'fk_apqp_fmea_ap_set_comp';
    END IF;
    -- A5-2a DFMEA: 유형은 바뀌지 않는다, BOM 리비전은 머리 품목의 것, 도면 리비전은 같은 회사 DRAWING 문서
    IF TG_OP = 'UPDATE' AND NEW.fmea_type <> OLD.fmea_type THEN
        RAISE EXCEPTION 'fmea % type is fixed', OLD.fmea_id USING ERRCODE = '55000', CONSTRAINT = 'ck_apqp_fmea_type_fixed';
    END IF;
    IF NEW.bom_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM mst_bom x WHERE x.bom_id = NEW.bom_id
                                                 AND x.item_id = NEW.item_id AND x.comp_cd = NEW.comp_cd) THEN
        RAISE EXCEPTION 'bom % is not of item %', NEW.bom_id, NEW.item_id USING ERRCODE = '23503', CONSTRAINT = 'fk_apqp_fmea_bom_item';
    END IF;
    -- A5-2b: BOM 을 바꿀 때 자품목을 가리키는 하위 시스템은 모두 새 BOM 의 줄이어야 한다(다시 잇기가 짝 없는 것을 먼저 글자로 바꾼다).
    -- 구성품의 자품목은 하위 시스템 품목의 BOM 에서 온 것이라 머리 BOM 과 무관
    IF TG_OP = 'UPDATE' AND NEW.bom_id IS DISTINCT FROM OLD.bom_id
       AND EXISTS (SELECT 1 FROM apqp_fmea_node n
                    WHERE n.fmea_id = NEW.fmea_id AND n.node_kind = 'SUBSYSTEM' AND n.ref_item_id IS NOT NULL
                      AND NOT EXISTS (SELECT 1 FROM mst_bom_line l WHERE l.bom_id = NEW.bom_id AND l.item_id = n.ref_item_id)) THEN
        RAISE EXCEPTION 'fmea % has subsystems not in bom %', NEW.fmea_id, NEW.bom_id USING ERRCODE = '23514', CONSTRAINT = 'ck_apqp_fmea_bom_used';
    END IF;
    IF NEW.doc_rev_id IS NOT NULL AND (TG_OP = 'INSERT' OR NEW.doc_rev_id IS DISTINCT FROM OLD.doc_rev_id)
       AND NOT EXISTS (SELECT 1 FROM mst_doc_rev r JOIN mst_doc d ON d.doc_id = r.doc_id
                        WHERE r.doc_rev_id = NEW.doc_rev_id AND r.comp_cd = NEW.comp_cd AND d.doc_type = 'DRAWING') THEN
        RAISE EXCEPTION 'doc rev % is not a drawing of company %', NEW.doc_rev_id, NEW.comp_cd
            USING ERRCODE = '23503', CONSTRAINT = 'fk_apqp_fmea_doc_rev_drawing';
    END IF;
    IF NEW.prev_fmea_id IS NOT NULL AND TG_OP = 'INSERT'
       AND NOT EXISTS (SELECT 1 FROM apqp_fmea x WHERE x.fmea_id = NEW.prev_fmea_id AND x.comp_cd = NEW.comp_cd AND x.fmea_no = NEW.fmea_no) THEN
        RAISE EXCEPTION 'previous fmea % is not of %', NEW.prev_fmea_id, NEW.fmea_no USING ERRCODE = '23503', CONSTRAINT = 'fk_apqp_fmea_prev_no';
    END IF;
    RETURN NEW;
END
$$;

-- 고장 — V211 과 같고 출처 검사를 더한다
CREATE OR REPLACE FUNCTION hb_guard_apqp_fmea_fail() RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    v_kind varchar(10);
BEGIN
    IF TG_OP = 'DELETE' THEN
        PERFORM hb_apqp_fmea_editable(OLD.fmea_id, OLD.comp_cd, false);
        RETURN OLD;
    END IF;
    IF TG_OP = 'UPDATE' AND (NEW.fmea_id <> OLD.fmea_id OR NEW.func_id <> OLD.func_id OR NEW.fail_kind <> OLD.fail_kind
                              OR NEW.comp_cd <> OLD.comp_cd) THEN
        RAISE EXCEPTION 'failure % kind/function is fixed', OLD.fail_id USING ERRCODE = '55000', CONSTRAINT = 'ck_apqp_fmea_fail_fixed';
    END IF;
    PERFORM hb_apqp_fmea_editable(NEW.fmea_id, NEW.comp_cd, false);
    SELECT n.node_kind INTO v_kind FROM apqp_fmea_func f JOIN apqp_fmea_node n ON n.node_id = f.node_id
     WHERE f.func_id = NEW.func_id AND f.fmea_id = NEW.fmea_id;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'function % is not in fmea %', NEW.func_id, NEW.fmea_id USING ERRCODE = '23503', CONSTRAINT = 'fk_apqp_fmea_fail_func_fmea';
    END IF;
    IF (NEW.fail_kind = 'FE' AND v_kind NOT IN ('ITEM', 'SYSTEM')) OR (NEW.fail_kind = 'FM' AND v_kind NOT IN ('STEP', 'SUBSYSTEM'))
       OR (NEW.fail_kind = 'FC' AND v_kind NOT IN ('ELEMENT', 'COMPONENT')) THEN
        RAISE EXCEPTION '% does not belong to a % node', NEW.fail_kind, v_kind USING ERRCODE = '23514', CONSTRAINT = 'ck_apqp_fmea_fail_level';
    END IF;
    IF NEW.defect_id IS NOT NULL AND (TG_OP = 'INSERT' OR NEW.defect_id IS DISTINCT FROM OLD.defect_id)
       AND NOT EXISTS (SELECT 1 FROM qms_defect d WHERE d.defect_id = NEW.defect_id AND d.comp_cd = NEW.comp_cd) THEN
        RAISE EXCEPTION 'defect % is not of company %', NEW.defect_id, NEW.comp_cd USING ERRCODE = '23503', CONSTRAINT = 'fk_apqp_fmea_fail_defect_comp';
    END IF;
    -- A5-2b D7: PFMEA 고장 영향의 출처 = 같은 품목의 확정(또는 폐기된) DFMEA 고장 형태(글자는 복사, 출처는 표시만)
    IF NEW.src_fail_id IS NOT NULL AND (TG_OP = 'INSERT' OR NEW.src_fail_id IS DISTINCT FROM OLD.src_fail_id)
       AND NOT EXISTS (SELECT 1 FROM apqp_fmea_fail s JOIN apqp_fmea sf ON sf.fmea_id = s.fmea_id
                         JOIN apqp_fmea me ON me.fmea_id = NEW.fmea_id
                        WHERE s.fail_id = NEW.src_fail_id AND s.comp_cd = NEW.comp_cd AND s.fail_kind = 'FM'
                          AND sf.fmea_type = 'DFMEA' AND sf.status IN ('RELEASED', 'OBSOLETE')
                          AND me.fmea_type = 'PFMEA' AND sf.item_id = me.item_id) THEN
        RAISE EXCEPTION 'source % is not a released DFMEA failure mode of the item', NEW.src_fail_id
            USING ERRCODE = '23503', CONSTRAINT = 'fk_apqp_fmea_fail_src_dfmea';
    END IF;
    RETURN NEW;
END
$$;
