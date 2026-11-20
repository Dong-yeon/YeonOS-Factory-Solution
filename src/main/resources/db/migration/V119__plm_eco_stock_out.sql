-- =====================================================================
-- PLM 4단계 남은 것 — 적용 조건 "재고 소진" (docs/39 18장)
-- ECO 를 유효일(DATE)이 아니라 옛 품목의 공장 가용 재고가 0 이 됐을 때 적용한다(STOCK_OUT).
-- 재고 수량은 WMS 계약 StockQuery 로만 읽는다(앱) — 이 파일은 칸·고정·같은 회사 확인만.
-- 임시 번호(PLM 대역 V9900~V9949) — 상위 세션이 병합 때 바꾼다.
-- =====================================================================

INSERT INTO sys_code_grp (grp_cd, grp_nm_key, comp_ext_yn) VALUES
    ('PLM_APPLY_COND', 'code.grp.PLM_APPLY_COND', 'N');
INSERT INTO sys_code (grp_cd, code, code_nm_key, sort_no) VALUES
    ('PLM_APPLY_COND', 'DATE',      'code.PLM_APPLY_COND.DATE',      1),
    ('PLM_APPLY_COND', 'STOCK_OUT', 'code.PLM_APPLY_COND.STOCK_OUT', 2);

ALTER TABLE plm_eco
    ADD COLUMN apply_cond      varchar(10)  NOT NULL DEFAULT 'DATE',
    ADD COLUMN stock_item_id   bigint,
    ADD COLUMN stock_plant_cd  varchar(20),
    ADD CONSTRAINT ck_plm_eco_apply_cond CHECK (apply_cond IN ('DATE', 'STOCK_OUT')),
    ADD CONSTRAINT ck_plm_eco_stock_out CHECK (
        (apply_cond = 'DATE' AND stock_item_id IS NULL AND stock_plant_cd IS NULL)
        OR (apply_cond = 'STOCK_OUT' AND stock_item_id IS NOT NULL AND stock_plant_cd IS NOT NULL)),
    ADD CONSTRAINT fk_plm_eco_stock_item FOREIGN KEY (stock_item_id) REFERENCES mst_item (item_id),
    ADD CONSTRAINT fk_plm_eco_stock_plant FOREIGN KEY (comp_cd, stock_plant_cd) REFERENCES sys_plant (comp_cd, plant_cd);

COMMENT ON COLUMN plm_eco.apply_cond IS '적용 조건 — DATE(유효일, 기본) · STOCK_OUT(소진 품목의 공장 가용 재고 0 일 때 적용, 유효일 = 가장 이른 날)';
COMMENT ON COLUMN plm_eco.stock_item_id IS 'STOCK_OUT 의 소진 품목(보통 대체되는 옛 자품목)';
COMMENT ON COLUMN plm_eco.stock_plant_cd IS 'STOCK_OUT 의 재고를 보는 공장';

-- 결재받은 내용은 고정(작성 중만 수정) + 소진 품목은 같은 회사. 기존 hb_guard_plm_eco 는 그대로 두고 따로 건다
CREATE FUNCTION hb_guard_plm_eco_stock_out() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.stock_item_id IS NOT NULL
       AND (TG_OP = 'INSERT' OR NEW.stock_item_id IS DISTINCT FROM OLD.stock_item_id)
       AND NOT EXISTS (SELECT 1 FROM mst_item i WHERE i.item_id = NEW.stock_item_id AND i.comp_cd = NEW.comp_cd) THEN
        RAISE EXCEPTION 'ECO stock-out item is not of company %', NEW.comp_cd
            USING ERRCODE = '23503', CONSTRAINT = 'fk_plm_eco_stock_item_comp';
    END IF;
    IF TG_OP = 'UPDATE' AND OLD.status <> 'DRAFT' AND (
           NEW.apply_cond <> OLD.apply_cond OR NEW.stock_item_id IS DISTINCT FROM OLD.stock_item_id
        OR NEW.stock_plant_cd IS DISTINCT FROM OLD.stock_plant_cd) THEN
        RAISE EXCEPTION 'ECO % is not DRAFT — apply condition is fixed', OLD.eco_id
            USING ERRCODE = '55000', CONSTRAINT = 'ck_plm_eco_fixed';
    END IF;
    RETURN NEW;
END
$$;
CREATE TRIGGER tg_plm_eco_stock_out BEFORE INSERT OR UPDATE ON plm_eco FOR EACH ROW EXECUTE FUNCTION hb_guard_plm_eco_stock_out();
