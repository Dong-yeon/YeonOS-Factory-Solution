-- =====================================================================
-- 수집 COUNT 원장 역분개 행 검사 (docs/25 4.27 후속, 상위 지시 2026-10-22 — 임시 V9116)
-- - V139 는 역분개 행의 모양(CORRECT·qty ≤ 0·사유)만 CHECK 로 막았다. 무엇을 되돌리는지는 보지 않았다
-- - 역분개 행은: 원 행이 있고(같은 회사 — RLS 로 다른 회사 행은 보이지 않는다), 원 행이 역분개 행이 아니며,
--   설비·메트릭이 같고, qty = -원 qty. 한 원 행에 역분개 하나는 uq_cms_equip_count_reversal(V139)
-- - V139 는 고치지 않는다(적용된 파일) — 같은 함수를 바꿔 쓴다
-- =====================================================================

CREATE OR REPLACE FUNCTION hb_guard_equip_count() RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    o cms_equip_count%ROWTYPE;
BEGIN
    IF NOT EXISTS (SELECT 1 FROM mst_equip e WHERE e.equip_id = NEW.equip_id AND e.comp_cd = NEW.comp_cd)
       OR (NEW.terminal_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM sys_terminal t WHERE t.terminal_id = NEW.terminal_id
                                                          AND t.comp_cd = NEW.comp_cd)) THEN
        RAISE EXCEPTION 'count context is not of company %', NEW.comp_cd
            USING ERRCODE = '23503', CONSTRAINT = 'fk_cms_equip_count_ctx_comp';
    END IF;
    IF NEW.reversal_of_id IS NOT NULL THEN
        SELECT * INTO o FROM cms_equip_count c WHERE c.count_id = NEW.reversal_of_id AND c.comp_cd = NEW.comp_cd;
        IF NOT FOUND OR o.reversal_of_id IS NOT NULL OR o.equip_id <> NEW.equip_id OR o.metric_cd <> NEW.metric_cd
           OR NEW.qty <> -o.qty THEN
            RAISE EXCEPTION 'reversal row must mirror an original count row of the same company (equipment, metric, -qty)'
                USING ERRCODE = '23514', CONSTRAINT = 'ck_cms_equip_count_reversal_match';
        END IF;
    END IF;
    RETURN NEW;
END
$$;
