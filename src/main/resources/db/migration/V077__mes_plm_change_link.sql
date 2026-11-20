-- =====================================================================
-- MES P2 3c — PLM 연결: 투입 계보의 설계 변경 표시 사본 (docs/25 4.12, docs/39 12.3·13장)
-- - lot_genealogy.change_id(4단계부터 있던 칸, 지금까지 항상 NULL) → plm_eco FK
--   원본 표시는 PLM 의 plm_eco_lot(LOT 단위). 이 칸은 새 LOT 이 자식인 계보 행에 ChangeMarker.mark 가 돌려준 ECO 를 적은 사본
-- - hb_guard_lot_genealogy: 같은 회사 ECO 확인(fk_lot_genealogy_ctx_comp) + 역분개 행은 원래 행과 같은 change_id
-- - 잠금: FK 확인의 KEY SHARE 는 같은 트랜잭션이 mark 에서 이미 쥔 ECO 행에만 걸린다(새 대기 없음, 4.12)
-- =====================================================================

ALTER TABLE lot_genealogy
    ADD CONSTRAINT fk_lot_genealogy_change FOREIGN KEY (change_id) REFERENCES plm_eco (eco_id);
CREATE INDEX ix_lot_genealogy_change ON lot_genealogy (comp_cd, change_id) WHERE change_id IS NOT NULL;

-- V056 함수와 같고, 맥락 회사 확인에 change_id 한 줄 + 역분개 거울에 change_id
CREATE OR REPLACE FUNCTION hb_guard_lot_genealogy() RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    v_parent lot_master;
    v_child  lot_master;
    v_orig   lot_genealogy;
BEGIN
    -- 부모 LOT 을 잠근다 — 같은 LOT 에서 동시에 나가는 두 건이 같은 잔량을 쓰지 않게
    SELECT * INTO v_parent FROM lot_master WHERE lot_id = NEW.parent_lot_id AND comp_cd = NEW.comp_cd FOR UPDATE;
    SELECT * INTO v_child FROM lot_master WHERE lot_id = NEW.child_lot_id AND comp_cd = NEW.comp_cd;
    IF v_parent.lot_id IS NULL OR v_child.lot_id IS NULL THEN
        RAISE EXCEPTION 'lots % / % are not of company %', NEW.parent_lot_id, NEW.child_lot_id, NEW.comp_cd
            USING ERRCODE = '23503', CONSTRAINT = 'fk_lot_genealogy_lot_comp';
    END IF;
    IF NEW.unit_cd <> v_parent.unit_cd THEN
        RAISE EXCEPTION 'genealogy quantity is in the parent lot unit %', v_parent.unit_cd
            USING ERRCODE = '23514', CONSTRAINT = 'ck_lot_genealogy_unit';
    END IF;
    -- 분할·병합·라벨 바꿈·재작업·수리·선별은 같은 품목끼리. 투입만 다른 품목(자재 → 제품)
    IF NEW.rel_type <> 'CONSUME' AND v_parent.item_id <> v_child.item_id THEN
        RAISE EXCEPTION '% keeps the item', NEW.rel_type USING ERRCODE = '23514', CONSTRAINT = 'ck_lot_genealogy_item';
    END IF;
    IF (NEW.wo_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM prd_wo w WHERE w.wo_id = NEW.wo_id AND w.comp_cd = NEW.comp_cd))
       OR (NEW.wo_proc_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM prd_wo_proc p WHERE p.wo_proc_id = NEW.wo_proc_id
                                                         AND p.comp_cd = NEW.comp_cd AND p.wo_id = NEW.wo_id))
       OR (NEW.equip_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM mst_equip e WHERE e.equip_id = NEW.equip_id AND e.comp_cd = NEW.comp_cd))
       OR (NEW.performed_by IS NOT NULL AND NOT EXISTS (SELECT 1 FROM mst_worker k WHERE k.worker_id = NEW.performed_by
                                                           AND k.comp_cd = NEW.comp_cd))
       -- 3c: 설계 변경 표시 사본 — FK 는 RLS 를 무시하므로 같은 회사 ECO 인지 여기서 본다(잠금 없는 조회)
       OR (NEW.change_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM plm_eco c WHERE c.eco_id = NEW.change_id
                                                        AND c.comp_cd = NEW.comp_cd)) THEN
        RAISE EXCEPTION 'genealogy context is not of company %', NEW.comp_cd
            USING ERRCODE = '23503', CONSTRAINT = 'fk_lot_genealogy_ctx_comp';
    END IF;
    IF NEW.reversal_of_id IS NULL THEN
        -- 재작업·수리는 보류의 출구(D18) — 그 밖의 유형은 보류 LOT 을 부모로 쓰지 못한다
        IF v_parent.hold_yn = 'Y' AND NEW.rel_type NOT IN ('REWORK', 'REPAIR') THEN
            RAISE EXCEPTION 'lot % is on hold', v_parent.lot_no USING ERRCODE = '55000', CONSTRAINT = 'ck_lot_genealogy_hold';
        END IF;
        -- 재작업·수리로 나온 LOT 은 그 유형의 계보로만 태어난다(출처와 연결 유형이 같다)
        IF v_child.origin IN ('REWORK', 'REPAIR') AND v_child.origin <> NEW.rel_type THEN
            RAISE EXCEPTION 'lot % of origin % is born only by %', v_child.lot_no, v_child.origin, v_child.origin
                USING ERRCODE = '23514', CONSTRAINT = 'ck_lot_genealogy_origin';
        END IF;
        IF hb_lot_balance(v_parent.lot_id) < NEW.qty THEN
            RAISE EXCEPTION 'lot % has only % left', v_parent.lot_no, hb_lot_balance(v_parent.lot_id)
                USING ERRCODE = '23514', CONSTRAINT = 'ck_lot_genealogy_balance';
        END IF;
        RETURN NEW;
    END IF;
    -- 역분개: 같은 회사의 원래 행(역분개 아님)과 같은 연결·유형·단위, 수량은 정확히 반대
    SELECT * INTO v_orig FROM lot_genealogy WHERE gen_id = NEW.reversal_of_id AND comp_cd = NEW.comp_cd;
    IF v_orig.gen_id IS NULL OR v_orig.reversal_of_id IS NOT NULL
       OR (v_orig.parent_lot_id, v_orig.child_lot_id, v_orig.rel_type, v_orig.unit_cd, v_orig.qty, v_orig.change_id)
          IS DISTINCT FROM (NEW.parent_lot_id, NEW.child_lot_id, NEW.rel_type, NEW.unit_cd, -NEW.qty, NEW.change_id) THEN
        RAISE EXCEPTION 'reversal must mirror genealogy row %', NEW.reversal_of_id
            USING ERRCODE = '23514', CONSTRAINT = 'ck_lot_genealogy_reversal';
    END IF;
    RETURN NEW;
END
$$;
