-- =====================================================================
-- MES 역분개 행 맥락 (docs/25 4.5, 사용자 결정 2026-10-21) — 표 변경 없음
-- - 앞으로 만드는 역분개 행은 원래 행의 맥락을 그대로 갖는다: lot_genealogy (wo_id, wo_proc_id, equip_id), prd_result equip_id
--   (occurred_at 은 원장 규약대로 정정 시각 — 복사하지 않는다)
-- - 이미 쌓인 역분개 행은 맥락이 NULL 그대로다. 그래서 지시·공정·설비 기준 순수량은 계속 "되돌려진 원래 행 제외" 로 계산한다
-- - 검사는 AFTER INSERT 트리거 — 기존 거울·사유 CHECK 오류가 먼저 나오게(오류 우선순위 그대로)
-- =====================================================================

CREATE FUNCTION hb_check_genealogy_reversal_ctx() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.reversal_of_id IS NOT NULL AND EXISTS (
           SELECT 1 FROM lot_genealogy o
            WHERE o.gen_id = NEW.reversal_of_id AND o.comp_cd = NEW.comp_cd
              AND (o.wo_id, o.wo_proc_id, o.equip_id) IS DISTINCT FROM (NEW.wo_id, NEW.wo_proc_id, NEW.equip_id)) THEN
        RAISE EXCEPTION 'reversal keeps the context (work order, process, equipment) of genealogy row %', NEW.reversal_of_id
            USING ERRCODE = '23514', CONSTRAINT = 'ck_lot_genealogy_reversal_ctx';
    END IF;
    RETURN NULL;
END
$$;
CREATE TRIGGER tg_lot_genealogy_reversal_ctx AFTER INSERT ON lot_genealogy FOR EACH ROW EXECUTE FUNCTION hb_check_genealogy_reversal_ctx();

CREATE FUNCTION hb_check_result_reversal_ctx() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.reversal_of_id IS NOT NULL AND EXISTS (
           SELECT 1 FROM prd_result o
            WHERE o.result_id = NEW.reversal_of_id AND o.comp_cd = NEW.comp_cd
              AND o.equip_id IS DISTINCT FROM NEW.equip_id) THEN
        RAISE EXCEPTION 'reversal keeps the equipment of result %', NEW.reversal_of_id
            USING ERRCODE = '23514', CONSTRAINT = 'ck_prd_result_reversal_ctx';
    END IF;
    RETURN NULL;
END
$$;
CREATE TRIGGER tg_prd_result_reversal_ctx AFTER INSERT ON prd_result FOR EACH ROW EXECUTE FUNCTION hb_check_result_reversal_ctx();
