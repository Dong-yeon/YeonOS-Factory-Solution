-- =====================================================================
-- PLM 2단계 도면 리비전·첨부 고정 (docs/39 15장) — 임시 번호, 병합 때 재번호
--
-- - plm_eco_target 유형 DOC: doc_rev_id → mst_doc_rev(QMS Q3). 문서 유형 DRAWING 의 작성 중 리비전만, 같은 회사, 열린 ECO 하나
--   QMS 표는 바꾸지 않는다(FK·읽기·행 잠금만). 적용 때 배포는 DocService.releaseFromChange
-- - PPAP 요소 파일: 그 ECO 첨부 또는 그 패키지 증빙(plm-eco × PPAP-{ppap_id}) — ECO 첨부는 상신 뒤 고정(D7)이라 증빙을 따로 둔다
-- - 첨부 고정(D7)은 앱 FileLockRule(PlmFileLock) + 전이 때 sys_file.locked_yn — sys_file 에 대상별 트리거를 걸지 않는다
-- =====================================================================

INSERT INTO sys_code (grp_cd, code, code_nm_key, sort_no) VALUES
    ('PLM_TARGET_TYPE', 'DOC', 'code.PLM_TARGET_TYPE.DOC', 6);

ALTER TABLE plm_eco_target ADD COLUMN doc_rev_id bigint;
ALTER TABLE plm_eco_target
    ADD CONSTRAINT fk_plm_eco_target_doc_rev FOREIGN KEY (doc_rev_id) REFERENCES mst_doc_rev (doc_rev_id),
    DROP CONSTRAINT ck_plm_eco_target_type;
ALTER TABLE plm_eco_target ADD CONSTRAINT ck_plm_eco_target_type CHECK (
    (target_type = 'ITEM' AND bom_id IS NULL AND routing_id IS NULL AND equip_id IS NULL AND proc_id IS NULL AND doc_rev_id IS NULL)
    OR (target_type = 'BOM' AND bom_id IS NOT NULL AND routing_id IS NULL AND equip_id IS NULL AND proc_id IS NULL AND doc_rev_id IS NULL)
    OR (target_type = 'ROUTING' AND routing_id IS NOT NULL AND bom_id IS NULL AND equip_id IS NULL AND proc_id IS NULL AND doc_rev_id IS NULL)
    OR (target_type = 'EQUIP' AND equip_id IS NOT NULL AND bom_id IS NULL AND routing_id IS NULL AND proc_id IS NULL AND doc_rev_id IS NULL)
    OR (target_type = 'PROC' AND proc_id IS NOT NULL AND bom_id IS NULL AND routing_id IS NULL AND equip_id IS NULL AND doc_rev_id IS NULL)
    OR (target_type = 'DOC' AND doc_rev_id IS NOT NULL AND bom_id IS NULL AND routing_id IS NULL AND equip_id IS NULL AND proc_id IS NULL));
CREATE UNIQUE INDEX uq_plm_eco_target_doc ON plm_eco_target (eco_id, doc_rev_id) WHERE doc_rev_id IS NOT NULL;
-- 문서 리비전 → 걸린 ECO (첨부 잠금 판정)
CREATE INDEX ix_plm_eco_target_doc_ref ON plm_eco_target (comp_cd, doc_rev_id) WHERE doc_rev_id IS NOT NULL;

-- V060 함수 + 도면 리비전(같은 회사·DRAWING·작성 중·열린 ECO 하나), 고정 칸에 doc_rev_id
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
            AND NEW.item_id = OLD.item_id AND NEW.bom_id IS NOT DISTINCT FROM OLD.bom_id
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
    IF NOT hb_item_of_comp(NEW.comp_cd, NEW.item_id) THEN
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
        -- 도면 리비전(QMS Q3) — 행을 잠가 같은 리비전을 두 ECO 에 동시에 넣는 것을 직렬화. QMS 표는 읽기·잠금만
        SELECT d.doc_type, r.status INTO v_doc_type, v_rstatus FROM mst_doc_rev r JOIN mst_doc d ON d.doc_id = r.doc_id
         WHERE r.doc_rev_id = NEW.doc_rev_id AND r.comp_cd = NEW.comp_cd FOR UPDATE OF r;
        IF v_doc_type IS NULL THEN
            RAISE EXCEPTION 'document revision % is not of company %', NEW.doc_rev_id, NEW.comp_cd
                USING ERRCODE = '23503', CONSTRAINT = 'fk_plm_eco_target_comp';
        END IF;
        IF v_doc_type <> 'DRAWING' THEN
            RAISE EXCEPTION 'document revision % is not a drawing', NEW.doc_rev_id
                USING ERRCODE = '23514', CONSTRAINT = 'ck_plm_eco_target_drawing';
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

-- V069 함수 + PPAP 증빙 첨부(plm-eco × PPAP-{ppap_id})
CREATE OR REPLACE FUNCTION hb_guard_plm_ppap_elem() RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    v_status varchar(10);
    v_eco    bigint;
BEGIN
    SELECT p.status, p.eco_id INTO v_status, v_eco FROM plm_ppap p WHERE p.ppap_id = NEW.ppap_id AND p.comp_cd = NEW.comp_cd;
    IF v_status IS NULL THEN
        RAISE EXCEPTION 'PPAP % is not of company %', NEW.ppap_id, NEW.comp_cd
            USING ERRCODE = '23503', CONSTRAINT = 'fk_plm_ppap_elem_comp';
    END IF;
    IF v_status <> 'DRAFT' THEN
        RAISE EXCEPTION 'elements of % PPAP % cannot be changed', v_status, NEW.ppap_id
            USING ERRCODE = '55000', CONSTRAINT = 'ck_plm_ppap_fixed';
    END IF;
    IF TG_OP = 'UPDATE' AND (NEW.ppap_id <> OLD.ppap_id OR NEW.elem_no <> OLD.elem_no) THEN
        RAISE EXCEPTION 'PPAP element is fixed' USING ERRCODE = '55000', CONSTRAINT = 'ck_plm_ppap_fixed';
    END IF;
    IF NEW.file_id IS NOT NULL AND (TG_OP = 'INSERT' OR NEW.file_id IS DISTINCT FROM OLD.file_id)
       AND NOT EXISTS (SELECT 1 FROM sys_file f WHERE f.file_id = NEW.file_id AND f.comp_cd = NEW.comp_cd
                          AND f.target_type = 'plm-eco' AND f.target_id IN (v_eco::text, 'PPAP-' || NEW.ppap_id)
                          AND f.use_yn = 'Y') THEN
        RAISE EXCEPTION 'file % is not an attachment of ECO % or its PPAP', NEW.file_id, v_eco
            USING ERRCODE = '23503', CONSTRAINT = 'fk_plm_ppap_elem_comp';
    END IF;
    RETURN NEW;
END
$$;
