-- =====================================================================
-- P6 APQP A5-2a — DFMEA (docs/41 13장 D4~D8, 2026-10-29 사용자: D1~D9 추천대로) — 임시 번호(docs/29 2.2), 병합 때 이름만 바뀐다
--
-- - 같은 표 apqp_fmea: fmea_type DFMEA 추가(D4). 대상 = 품목 + BOM 리비전 bom_id(구조 원본) + 도면 리비전 doc_rev_id(QMS DRAWING, 선택)(D5).
--   PFMEA 는 bom·도면 없음, DFMEA 는 라우팅 없음(CHECK). 유형은 바뀌지 않는다(트리거)
-- - 구조 노드: SYSTEM(뿌리 = 품목) › SUBSYSTEM › COMPONENT(DFMEA 전용, D4). ref_item_id = BOM 에서 끌어온 자품목(D6).
--   수준은 PFMEA 와 같은 3단 — FE = 뿌리, FM = 2단, FC = 3단(위험행·AP·조치 규칙 그대로)
-- - 기능 특성: DFMEA 는 제품 특성만, 머리 품목 또는 그 노드의 자품목 것(공정 특성 없음)
-- - 관리계획서는 PFMEA 만 가리킨다(트리거 추가)
-- - 새 화면 apqp-dfmea(설계 담당 권한을 따로, D8)
-- 남의 표: 없음(mst_bom·mst_doc_rev(QMS)·mst_item 을 FK·트리거로 가리키기만)
-- =====================================================================

ALTER TABLE apqp_fmea DROP CONSTRAINT ck_apqp_fmea_type;
ALTER TABLE apqp_fmea ADD CONSTRAINT ck_apqp_fmea_type CHECK (fmea_type IN ('PFMEA', 'DFMEA'));
ALTER TABLE apqp_fmea ADD COLUMN bom_id bigint;
ALTER TABLE apqp_fmea ADD COLUMN doc_rev_id bigint;
ALTER TABLE apqp_fmea ADD CONSTRAINT fk_apqp_fmea_bom FOREIGN KEY (bom_id) REFERENCES mst_bom (bom_id);
ALTER TABLE apqp_fmea ADD CONSTRAINT fk_apqp_fmea_doc_rev FOREIGN KEY (doc_rev_id) REFERENCES mst_doc_rev (doc_rev_id);
ALTER TABLE apqp_fmea ADD CONSTRAINT ck_apqp_fmea_target CHECK (
       (fmea_type = 'PFMEA' AND bom_id IS NULL AND doc_rev_id IS NULL)
    OR (fmea_type = 'DFMEA' AND routing_id IS NULL));
COMMENT ON COLUMN apqp_fmea.fmea_type IS 'PFMEA(공정 — 라우팅) / DFMEA(설계 — BOM·도면 리비전, docs/41 13장 A5-2)';
COMMENT ON COLUMN apqp_fmea.bom_id IS 'DFMEA 근거 BOM 리비전(머리 품목의 것) — 구조 끌어오기 원본';
COMMENT ON COLUMN apqp_fmea.doc_rev_id IS 'DFMEA 근거 도면 리비전(QMS 관리문서 DRAWING, 선택) — PFMEA 의 drawing_rev 글자 대신';
CREATE INDEX ix_apqp_fmea_type ON apqp_fmea (comp_cd, fmea_type);

ALTER TABLE apqp_fmea_node ADD COLUMN ref_item_id bigint;
ALTER TABLE apqp_fmea_node ADD CONSTRAINT fk_apqp_fmea_node_ref_item FOREIGN KEY (ref_item_id) REFERENCES mst_item (item_id);
-- SUBSYSTEM·COMPONENT 는 9자 — 칸을 넓힌다(varchar 넓히기는 표 다시 쓰기 없음)
ALTER TABLE apqp_fmea_node ALTER COLUMN node_kind TYPE varchar(10);
ALTER TABLE apqp_fmea_node DROP CONSTRAINT ck_apqp_fmea_node_kind;
ALTER TABLE apqp_fmea_node ADD CONSTRAINT ck_apqp_fmea_node_kind
    CHECK (node_kind IN ('ITEM', 'STEP', 'ELEMENT', 'SYSTEM', 'SUBSYSTEM', 'COMPONENT'));
ALTER TABLE apqp_fmea_node DROP CONSTRAINT ck_apqp_fmea_node_shape;
ALTER TABLE apqp_fmea_node ADD CONSTRAINT ck_apqp_fmea_node_shape CHECK (
       (node_kind IN ('ITEM', 'SYSTEM') AND parent_id IS NULL AND step_id IS NULL AND m4 IS NULL AND ref_item_id IS NULL)
    OR (node_kind = 'STEP' AND parent_id IS NOT NULL AND m4 IS NULL AND ref_item_id IS NULL)
    OR (node_kind = 'ELEMENT' AND parent_id IS NOT NULL AND step_id IS NULL AND ref_item_id IS NULL
        AND m4 IN ('MAN', 'MACHINE', 'MATERIAL', 'METHOD', 'MEASURE', 'ENV'))
    OR (node_kind IN ('SUBSYSTEM', 'COMPONENT') AND parent_id IS NOT NULL AND step_id IS NULL AND m4 IS NULL));
COMMENT ON COLUMN apqp_fmea_node.ref_item_id IS 'DFMEA 하위 시스템·구성품의 자품목(BOM 에서 끌어옴, 손으로 만든 노드는 비움)';
DROP INDEX uq_apqp_fmea_node_item;
CREATE UNIQUE INDEX uq_apqp_fmea_node_item ON apqp_fmea_node (fmea_id) WHERE node_kind IN ('ITEM', 'SYSTEM');
COMMENT ON TABLE apqp_fmea_node IS 'FMEA 구조 트리 — PFMEA ITEM › STEP(라우팅 단계) › ELEMENT(4M) / DFMEA SYSTEM › SUBSYSTEM › COMPONENT(BOM 자품목)';

-- 머리 — V158 과 같고 DFMEA(유형 고정·BOM·도면 리비전) 검사를 더한다
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
    IF TG_OP = 'UPDATE' AND NEW.bom_id IS DISTINCT FROM OLD.bom_id
       AND EXISTS (SELECT 1 FROM apqp_fmea_node n WHERE n.fmea_id = NEW.fmea_id AND n.ref_item_id IS NOT NULL) THEN
        RAISE EXCEPTION 'fmea % has nodes of the bom', NEW.fmea_id USING ERRCODE = '23514', CONSTRAINT = 'ck_apqp_fmea_bom_used';
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

-- 노드 — 유형별 종류·수준, 자품목은 같은 회사
CREATE OR REPLACE FUNCTION hb_guard_apqp_fmea_node() RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    v_parent_kind varchar(10);
    v_routing     bigint;
    v_type        varchar(6);
BEGIN
    IF TG_OP = 'DELETE' THEN
        PERFORM hb_apqp_fmea_editable(OLD.fmea_id, OLD.comp_cd, false);
        RETURN OLD;
    END IF;
    IF TG_OP = 'UPDATE' AND (NEW.fmea_id <> OLD.fmea_id OR NEW.node_kind <> OLD.node_kind OR NEW.comp_cd <> OLD.comp_cd) THEN
        RAISE EXCEPTION 'node % kind/fmea is fixed', OLD.node_id USING ERRCODE = '55000', CONSTRAINT = 'ck_apqp_fmea_node_fixed';
    END IF;
    PERFORM hb_apqp_fmea_editable(NEW.fmea_id, NEW.comp_cd, false);
    SELECT f.fmea_type, f.routing_id INTO v_type, v_routing FROM apqp_fmea f WHERE f.fmea_id = NEW.fmea_id;
    IF (v_type = 'PFMEA' AND NEW.node_kind NOT IN ('ITEM', 'STEP', 'ELEMENT'))
       OR (v_type = 'DFMEA' AND NEW.node_kind NOT IN ('SYSTEM', 'SUBSYSTEM', 'COMPONENT')) THEN
        RAISE EXCEPTION 'node kind % is not for %', NEW.node_kind, v_type USING ERRCODE = '23514', CONSTRAINT = 'ck_apqp_fmea_node_type';
    END IF;
    IF NEW.parent_id IS NOT NULL THEN
        SELECT p.node_kind INTO v_parent_kind FROM apqp_fmea_node p WHERE p.node_id = NEW.parent_id AND p.fmea_id = NEW.fmea_id;
        IF NOT FOUND OR (NEW.node_kind = 'STEP' AND v_parent_kind <> 'ITEM') OR (NEW.node_kind = 'ELEMENT' AND v_parent_kind <> 'STEP')
           OR (NEW.node_kind = 'SUBSYSTEM' AND v_parent_kind <> 'SYSTEM') OR (NEW.node_kind = 'COMPONENT' AND v_parent_kind <> 'SUBSYSTEM') THEN
            RAISE EXCEPTION 'node % parent % breaks the 3 levels', NEW.node_id, NEW.parent_id
                USING ERRCODE = '23514', CONSTRAINT = 'ck_apqp_fmea_node_level';
        END IF;
    END IF;
    IF NEW.step_id IS NOT NULL AND (TG_OP = 'INSERT' OR NEW.step_id IS DISTINCT FROM OLD.step_id) THEN
        IF v_routing IS NULL OR NOT EXISTS (SELECT 1 FROM mst_routing_step s WHERE s.step_id = NEW.step_id
                                               AND s.routing_id = v_routing AND s.comp_cd = NEW.comp_cd) THEN
            RAISE EXCEPTION 'step % is not of the fmea routing', NEW.step_id USING ERRCODE = '23503', CONSTRAINT = 'fk_apqp_fmea_node_step_routing';
        END IF;
    END IF;
    IF NEW.ref_item_id IS NOT NULL AND (TG_OP = 'INSERT' OR NEW.ref_item_id IS DISTINCT FROM OLD.ref_item_id)
       AND NOT EXISTS (SELECT 1 FROM mst_item i WHERE i.item_id = NEW.ref_item_id AND i.comp_cd = NEW.comp_cd) THEN
        RAISE EXCEPTION 'item % is not of company %', NEW.ref_item_id, NEW.comp_cd USING ERRCODE = '23503', CONSTRAINT = 'fk_apqp_fmea_node_ref_item_comp';
    END IF;
    RETURN NEW;
END
$$;

-- 기능 — PFMEA 그대로, DFMEA 는 제품 특성만(머리 품목 또는 그 노드의 자품목)
CREATE OR REPLACE FUNCTION hb_guard_apqp_fmea_func() RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    v_kind   varchar(10);
    v_step   bigint;
    v_ref    bigint;
    v_type   varchar(6);
    v_ctype  varchar(10);
    v_citem  bigint;
    v_cproc  bigint;
BEGIN
    IF TG_OP = 'DELETE' THEN
        PERFORM hb_apqp_fmea_editable(OLD.fmea_id, OLD.comp_cd, false);
        RETURN OLD;
    END IF;
    IF TG_OP = 'UPDATE' AND (NEW.fmea_id <> OLD.fmea_id OR NEW.node_id <> OLD.node_id OR NEW.comp_cd <> OLD.comp_cd) THEN
        RAISE EXCEPTION 'function % node is fixed', OLD.func_id USING ERRCODE = '55000', CONSTRAINT = 'ck_apqp_fmea_func_fixed';
    END IF;
    PERFORM hb_apqp_fmea_editable(NEW.fmea_id, NEW.comp_cd, false);
    SELECT n.node_kind, coalesce(n.step_id, p.step_id), n.ref_item_id INTO v_kind, v_step, v_ref
      FROM apqp_fmea_node n LEFT JOIN apqp_fmea_node p ON p.node_id = n.parent_id
     WHERE n.node_id = NEW.node_id AND n.fmea_id = NEW.fmea_id;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'node % is not in fmea %', NEW.node_id, NEW.fmea_id USING ERRCODE = '23503', CONSTRAINT = 'fk_apqp_fmea_func_node_fmea';
    END IF;
    IF NEW.char_id IS NOT NULL AND (TG_OP = 'INSERT' OR NEW.char_id IS DISTINCT FROM OLD.char_id) THEN
        SELECT c.char_type, c.item_id, c.proc_id INTO v_ctype, v_citem, v_cproc
          FROM qms_char c WHERE c.char_id = NEW.char_id AND c.comp_cd = NEW.comp_cd;
        IF NOT FOUND THEN
            RAISE EXCEPTION 'char % is not of company %', NEW.char_id, NEW.comp_cd USING ERRCODE = '23503', CONSTRAINT = 'fk_apqp_fmea_func_char_comp';
        END IF;
        SELECT f.fmea_type INTO v_type FROM apqp_fmea f WHERE f.fmea_id = NEW.fmea_id;
        IF v_type = 'DFMEA' THEN
            IF v_ctype <> 'PRODUCT' OR NOT (v_citem = v_ref OR EXISTS (SELECT 1 FROM apqp_fmea f WHERE f.fmea_id = NEW.fmea_id AND f.item_id = v_citem)) THEN
                RAISE EXCEPTION 'char % does not belong to this item/component', NEW.char_id USING ERRCODE = '23514', CONSTRAINT = 'ck_apqp_fmea_func_char_target';
            END IF;
        -- PFMEA: 제품 특성 = 머리 품목, 공정 특성 = 이 요소가 속한 라우팅 단계의 공정(단계를 가리킬 때)
        ELSIF (v_ctype = 'PRODUCT' AND NOT EXISTS (SELECT 1 FROM apqp_fmea f WHERE f.fmea_id = NEW.fmea_id AND f.item_id = v_citem))
           OR (v_ctype = 'PROCESS' AND v_step IS NOT NULL
               AND NOT EXISTS (SELECT 1 FROM mst_routing_step s WHERE s.step_id = v_step AND s.proc_id = v_cproc)) THEN
            RAISE EXCEPTION 'char % does not belong to this item/step', NEW.char_id USING ERRCODE = '23514', CONSTRAINT = 'ck_apqp_fmea_func_char_target';
        END IF;
    END IF;
    RETURN NEW;
END
$$;

-- 고장 — 수준: FE = 뿌리(ITEM·SYSTEM), FM = 2단(STEP·SUBSYSTEM), FC = 3단(ELEMENT·COMPONENT)
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
    RETURN NEW;
END
$$;

-- 관리계획서는 PFMEA 만 가리킨다
CREATE FUNCTION hb_guard_apqp_cp_fmea_type() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.fmea_id IS NOT NULL AND EXISTS (SELECT 1 FROM apqp_fmea f WHERE f.fmea_id = NEW.fmea_id AND f.fmea_type <> 'PFMEA') THEN
        RAISE EXCEPTION 'control plan refers to a non-PFMEA %', NEW.fmea_id USING ERRCODE = '23514', CONSTRAINT = 'ck_apqp_cp_fmea_type';
    END IF;
    RETURN NEW;
END
$$;
CREATE TRIGGER tg_apqp_cp_fmea_type BEFORE INSERT OR UPDATE OF fmea_id ON apqp_cp FOR EACH ROW EXECUTE FUNCTION hb_guard_apqp_cp_fmea_type();

INSERT INTO sys_prog (prog_cd, prog_nm_key, view_path, module_cd, actions, sort_no) VALUES
    ('apqp-dfmea', 'prog.apqpDfmea', 'apqp/dfmea-mgt', 'PMS', ARRAY['SEARCH','SAVE','APPROVE','PRINT','EXCEL']::varchar(20)[], 845);
