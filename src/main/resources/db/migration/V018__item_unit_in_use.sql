-- =====================================================================
-- V018 품목 기본 단위 변경 금지 — 쓰이는 품목 (사용자 결정 2026-10-19, docs/18 §10 "3a QA 반영")
--
-- BOM 머리(모품목, 기준 수량이 그 단위)·BOM 행(자품목, 상태 무관)·사용 중 단위 환산에 쓰인 품목은
-- 기본 단위를 바꿀 수 없다. 행 수량·환산의 뜻이 조용히 바뀌기 때문(확정본은 고칠 수도 없다).
-- 서비스(ItemService·ItemImportTemplate)가 칸 오류로 먼저 알리고, 이 트리거는 직접 SQL·동시 저장의 최종 방어.
-- 23503 → ApiExceptionHandler 가 409 IN_USE 로 바꾼다.
-- 사용 안 함(use_yn='N') 환산은 보지 않는다 — 되살릴 때 to_unit 을 지금 기본 단위로 다시 맞춘다(BomMapper.insertConv).
-- =====================================================================

CREATE FUNCTION hb_check_item_unit_in_use() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.unit_cd <> OLD.unit_cd AND (
           EXISTS (SELECT 1 FROM mst_bom b WHERE b.comp_cd = OLD.comp_cd AND b.item_id = OLD.item_id)
        OR EXISTS (SELECT 1 FROM mst_bom_line l WHERE l.comp_cd = OLD.comp_cd AND l.item_id = OLD.item_id)
        OR EXISTS (SELECT 1 FROM mst_unit_conv c
                    WHERE c.comp_cd = OLD.comp_cd AND c.item_id = OLD.item_id AND c.use_yn = 'Y')) THEN
        RAISE EXCEPTION 'unit of item % is in use (BOM or unit conversion)', OLD.item_id
            USING ERRCODE = '23503', CONSTRAINT = 'fk_mst_item_unit_in_use';
    END IF;
    RETURN NEW;
END
$$;

CREATE TRIGGER tg_mst_item_unit_in_use BEFORE UPDATE OF unit_cd ON mst_item
    FOR EACH ROW EXECUTE FUNCTION hb_check_item_unit_in_use();
