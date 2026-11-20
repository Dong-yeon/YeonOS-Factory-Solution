-- =====================================================================
-- V017 BOM 머리의 모품목·대체 번호 변경 금지 (QA 3a 결함 #1 — docs/qa/bom-3a.md)
--
-- V015 hb_guard_bom 은 확정·폐기본만 불변으로 막아, 작성 중 머리의 item_id 를 직접 SQL 로 바꾸면
-- 행 트리거(hb_guard_bom_line)의 순환 검사를 거치지 않고 순환 구조가 생겼다(SA1 ← FG1 ← SA1).
-- 서비스는 원래 모품목·대체 번호를 바꾸지 않으므로(BomService.update) DB 에서도 상태와 관계없이 막는다.
-- 함수 본문은 V015 와 같고 "모품목·대체 번호" 검사 한 블록만 더했다. 트리거는 그대로(함수만 교체).
-- =====================================================================

CREATE OR REPLACE FUNCTION hb_guard_bom() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'BOM header cannot be deleted (use OBSOLETE)'
            USING ERRCODE = '55000', CONSTRAINT = 'ck_mst_bom_no_delete';
    END IF;
    IF NOT hb_item_of_comp(NEW.comp_cd, NEW.item_id) THEN
        RAISE EXCEPTION 'item % does not belong to company %', NEW.item_id, NEW.comp_cd
            USING ERRCODE = '23503', CONSTRAINT = 'fk_mst_bom_item_comp';
    END IF;
    IF TG_OP = 'INSERT' THEN
        IF NEW.status <> 'DRAFT' THEN
            RAISE EXCEPTION 'new BOM must be DRAFT' USING ERRCODE = '55000', CONSTRAINT = 'ck_mst_bom_draft';
        END IF;
        RETURN NEW;
    END IF;
    -- 모품목·대체 번호는 상태와 관계없이 바꾸지 않는다 — 바꾸면 행 트리거의 순환 검사를 거치지 않고 구조가 바뀐다
    IF NEW.item_id <> OLD.item_id OR NEW.alt_no <> OLD.alt_no THEN
        RAISE EXCEPTION 'BOM % parent item / alternative cannot be changed (create a new BOM)', OLD.bom_id
            USING ERRCODE = '55000', CONSTRAINT = 'ck_mst_bom_key_immutable';
    END IF;
    -- 상태 이동: DRAFT→RELEASED·OBSOLETE, RELEASED→OBSOLETE 만
    IF OLD.status <> NEW.status AND NOT (
           (OLD.status = 'DRAFT' AND NEW.status IN ('RELEASED', 'OBSOLETE'))
        OR (OLD.status = 'RELEASED' AND NEW.status = 'OBSOLETE')) THEN
        RAISE EXCEPTION 'BOM status % -> % is not allowed', OLD.status, NEW.status
            USING ERRCODE = '55000', CONSTRAINT = 'ck_mst_bom_transition';
    END IF;
    -- 확정본·폐기본은 내용 수정 금지. 확정본은 유효 종료일 마감(앞당기기)과 폐기만
    IF OLD.status IN ('RELEASED', 'OBSOLETE') AND (
           NEW.item_id <> OLD.item_id OR NEW.alt_no <> OLD.alt_no OR NEW.rev <> OLD.rev
        OR NEW.valid_from <> OLD.valid_from OR NEW.base_qty <> OLD.base_qty
        OR NEW.change_no IS DISTINCT FROM OLD.change_no OR NEW.change_reason IS DISTINCT FROM OLD.change_reason
        OR NEW.remark IS DISTINCT FROM OLD.remark OR NEW.released_at IS DISTINCT FROM OLD.released_at
        OR (OLD.status = 'OBSOLETE' AND NEW.valid_to IS DISTINCT FROM OLD.valid_to)
        OR (OLD.status = 'RELEASED' AND NEW.valid_to IS DISTINCT FROM OLD.valid_to
            AND (NEW.valid_to IS NULL OR (OLD.valid_to IS NOT NULL AND NEW.valid_to > OLD.valid_to)))) THEN
        RAISE EXCEPTION 'released/obsolete BOM % cannot be changed (create a new revision)', OLD.bom_id
            USING ERRCODE = '55000', CONSTRAINT = 'ck_mst_bom_immutable';
    END IF;
    RETURN NEW;
END
$$;
