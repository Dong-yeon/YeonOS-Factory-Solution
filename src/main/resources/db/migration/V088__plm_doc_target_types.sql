-- =====================================================================
-- PLM DOC 대상 확장 (docs/39 16.1, QMS 2부 Q5 착수 — 2026-10-21) — 임시 번호, 병합 때 재번호
--
-- - DOC 대상 문서 유형: DRAWING → DRAWING·WI·CP·INSP_STD
-- - DOC 대상만 품목을 비울 수 있다(공정·품목 클래스에 걸린 문서). 3b LOT 표시 판정은 DOC 를 보지 않는다
-- - 문서 적용 대상(mst_doc_target)과 품목이 안 맞으면 막지 않고 화면 경고(조회 SQL) — 트리거 변경 없음
-- QMS 표는 바꾸지 않는다(FK·읽기·행 잠금만)
-- =====================================================================

ALTER TABLE plm_eco_target ALTER COLUMN item_id DROP NOT NULL;
ALTER TABLE plm_eco_target ADD CONSTRAINT ck_plm_eco_target_item_req CHECK (target_type = 'DOC' OR item_id IS NOT NULL);

-- V082(구 V9903) 함수 + 문서 유형 4종, 품목 없는 DOC
CREATE OR REPLACE FUNCTION hb_guard_plm_eco_target() RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    v_row     plm_eco_target;
    v_status  varchar(10);
    v_item    bigint;
    v_rstatus varchar(12);
    v_doc_type varchar(10);
BEGIN
    v_row := CASE WHEN TG_OP = 'DELETE' THEN OLD ELSE NEW END;
    -- 대상은 ECO 가 작성 중일 때만 바꾼다. 예외: 상신 때 base_row_ver 고정(DRAFT→PENDING 같은 트랜잭션)과 반려 때 해제
    SELECT e.status INTO v_status FROM plm_eco e WHERE e.eco_id = v_row.eco_id AND e.comp_cd = v_row.comp_cd;
    IF v_status IS NULL THEN
        RAISE EXCEPTION 'ECO % is not of company %', v_row.eco_id, v_row.comp_cd
            USING ERRCODE = '23503', CONSTRAINT = 'fk_plm_eco_target_comp';
    END IF;
    IF v_status <> 'DRAFT' AND NOT (TG_OP = 'UPDATE' AND v_status IN ('PENDING', 'DRAFT')
            AND NEW.eco_id = OLD.eco_id AND NEW.seq = OLD.seq AND NEW.target_type = OLD.target_type
            AND NEW.item_id IS NOT DISTINCT FROM OLD.item_id AND NEW.bom_id IS NOT DISTINCT FROM OLD.bom_id
            AND NEW.routing_id IS NOT DISTINCT FROM OLD.routing_id
            AND NEW.equip_id IS NOT DISTINCT FROM OLD.equip_id AND NEW.proc_id IS NOT DISTINCT FROM OLD.proc_id
            AND NEW.doc_rev_id IS NOT DISTINCT FROM OLD.doc_rev_id
            AND NEW.change_desc IS NOT DISTINCT FROM OLD.change_desc) THEN
        RAISE EXCEPTION 'targets of % ECO % cannot be changed', v_status, v_row.eco_id
            USING ERRCODE = '55000', CONSTRAINT = 'ck_plm_eco_target_fixed';
    END IF;
    IF TG_OP = 'DELETE' THEN
        RETURN OLD;
    END IF;
    IF TG_OP = 'UPDATE' AND NEW.eco_id <> OLD.eco_id THEN
        RAISE EXCEPTION 'target ECO is fixed' USING ERRCODE = '55000', CONSTRAINT = 'ck_plm_eco_target_fixed';
    END IF;
    -- 품목은 DOC 대상에서만 비울 수 있다(CHECK) — 있으면 같은 회사
    IF NEW.item_id IS NOT NULL AND NOT hb_item_of_comp(NEW.comp_cd, NEW.item_id) THEN
        RAISE EXCEPTION 'item % is not of company %', NEW.item_id, NEW.comp_cd
            USING ERRCODE = '23503', CONSTRAINT = 'fk_plm_eco_target_comp';
    END IF;
    IF (NEW.equip_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM mst_equip q WHERE q.equip_id = NEW.equip_id
                                                    AND q.comp_cd = NEW.comp_cd))
       OR (NEW.proc_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM mst_proc p WHERE p.proc_id = NEW.proc_id
                                                      AND p.comp_cd = NEW.comp_cd)) THEN
        RAISE EXCEPTION 'equipment or process is not of company %', NEW.comp_cd
            USING ERRCODE = '23503', CONSTRAINT = 'fk_plm_eco_target_comp';
    END IF;
    IF NEW.doc_rev_id IS NOT NULL AND (TG_OP = 'INSERT' OR NEW.doc_rev_id IS DISTINCT FROM OLD.doc_rev_id) THEN
        -- 문서 리비전(QMS Q3, 도면·작업표준서·관리계획서·검사기준서) — 행을 잠가 같은 리비전을 두 ECO 에 동시에 넣는 것을 직렬화. QMS 표는 읽기·잠금만
        SELECT d.doc_type, r.status INTO v_doc_type, v_rstatus FROM mst_doc_rev r JOIN mst_doc d ON d.doc_id = r.doc_id
         WHERE r.doc_rev_id = NEW.doc_rev_id AND r.comp_cd = NEW.comp_cd FOR UPDATE OF r;
        IF v_doc_type IS NULL THEN
            RAISE EXCEPTION 'document revision % is not of company %', NEW.doc_rev_id, NEW.comp_cd
                USING ERRCODE = '23503', CONSTRAINT = 'fk_plm_eco_target_comp';
        END IF;
        IF v_doc_type NOT IN ('DRAWING', 'WI', 'CP', 'INSP_STD') THEN
            RAISE EXCEPTION 'document revision % is not of a change-controlled type', NEW.doc_rev_id
                USING ERRCODE = '23514', CONSTRAINT = 'ck_plm_eco_target_doc_type';
        END IF;
        IF v_rstatus <> 'DRAFT' THEN
            RAISE EXCEPTION 'document revision % is not a draft revision', NEW.doc_rev_id
                USING ERRCODE = '55000', CONSTRAINT = 'ck_plm_eco_target_draft';
        END IF;
        IF EXISTS (SELECT 1 FROM plm_eco_target t JOIN plm_eco e ON e.eco_id = t.eco_id
                    WHERE t.doc_rev_id = NEW.doc_rev_id AND t.target_id <> coalesce(NEW.target_id, -1)
                      AND e.status IN ('DRAFT', 'PENDING', 'APPROVED')) THEN
            RAISE EXCEPTION 'document revision % is already in an open ECO', NEW.doc_rev_id
                USING ERRCODE = '23505', CONSTRAINT = 'uq_plm_eco_target_open';
        END IF;
    END IF;
    IF NEW.bom_id IS NOT NULL AND (TG_OP = 'INSERT' OR NEW.bom_id IS DISTINCT FROM OLD.bom_id) THEN
        -- 리비전 행을 잠가 같은 리비전을 두 ECO 에 동시에 넣는 것을 직렬화
        SELECT b.item_id, b.status INTO v_item, v_rstatus FROM mst_bom b
         WHERE b.bom_id = NEW.bom_id AND b.comp_cd = NEW.comp_cd FOR UPDATE;
        IF v_item IS NULL THEN
            RAISE EXCEPTION 'BOM % is not of company %', NEW.bom_id, NEW.comp_cd
                USING ERRCODE = '23503', CONSTRAINT = 'fk_plm_eco_target_comp';
        END IF;
        IF v_item <> NEW.item_id THEN
            RAISE EXCEPTION 'BOM % is not of item %', NEW.bom_id, NEW.item_id
                USING ERRCODE = '23514', CONSTRAINT = 'ck_plm_eco_target_item';
        END IF;
        IF v_rstatus <> 'DRAFT' THEN
            RAISE EXCEPTION 'BOM % is not a draft revision', NEW.bom_id
                USING ERRCODE = '55000', CONSTRAINT = 'ck_plm_eco_target_draft';
        END IF;
        IF EXISTS (SELECT 1 FROM plm_eco_target t JOIN plm_eco e ON e.eco_id = t.eco_id
                    WHERE t.bom_id = NEW.bom_id AND t.target_id <> coalesce(NEW.target_id, -1)
                      AND e.status IN ('DRAFT', 'PENDING', 'APPROVED')) THEN
            RAISE EXCEPTION 'BOM % is already in an open ECO', NEW.bom_id
                USING ERRCODE = '23505', CONSTRAINT = 'uq_plm_eco_target_open';
        END IF;
    END IF;
    IF NEW.routing_id IS NOT NULL AND (TG_OP = 'INSERT' OR NEW.routing_id IS DISTINCT FROM OLD.routing_id) THEN
        SELECT r.item_id, r.status INTO v_item, v_rstatus FROM mst_routing r
         WHERE r.routing_id = NEW.routing_id AND r.comp_cd = NEW.comp_cd FOR UPDATE;
        IF v_item IS NULL THEN
            RAISE EXCEPTION 'routing % is not of company %', NEW.routing_id, NEW.comp_cd
                USING ERRCODE = '23503', CONSTRAINT = 'fk_plm_eco_target_comp';
        END IF;
        IF v_item <> NEW.item_id THEN
            RAISE EXCEPTION 'routing % is not of item %', NEW.routing_id, NEW.item_id
                USING ERRCODE = '23514', CONSTRAINT = 'ck_plm_eco_target_item';
        END IF;
        IF v_rstatus <> 'DRAFT' THEN
            RAISE EXCEPTION 'routing % is not a draft revision', NEW.routing_id
                USING ERRCODE = '55000', CONSTRAINT = 'ck_plm_eco_target_draft';
        END IF;
        IF EXISTS (SELECT 1 FROM plm_eco_target t JOIN plm_eco e ON e.eco_id = t.eco_id
                    WHERE t.routing_id = NEW.routing_id AND t.target_id <> coalesce(NEW.target_id, -1)
                      AND e.status IN ('DRAFT', 'PENDING', 'APPROVED')) THEN
            RAISE EXCEPTION 'routing % is already in an open ECO', NEW.routing_id
                USING ERRCODE = '23505', CONSTRAINT = 'uq_plm_eco_target_open';
        END IF;
    END IF;
    RETURN NEW;
END
$$;
