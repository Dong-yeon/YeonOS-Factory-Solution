-- =====================================================================
-- 같은 공급사 LOT 추가 입고 = 새 내부 LOT (docs/29 결정 #43, docs/25 4.25 — 임시 V9114)
-- - V034 의 유일 인덱스 uq_lot_master_supplier_lot("공급사·품목별 공급사 LOT 은 한 번")을 지운다.
--   현장에서는 같은 공급사 LOT(코일·로트 라벨)이 여러 번 나눠 들어온다(ASN 행 나눠 받기 — WMS R3b 시험에서 발견).
--   입고할 때마다 새 내부 LOT, 공급사 LOT 은 속성 — 리콜은 공급사 LOT 으로 찾아 관련 내부 LOT 전부를 본다.
-- - 같은 열로 일반 인덱스를 다시 만든다(공급사·품목·공급사 LOT 으로 찾기가 느려지지 않게). V034 는 고치지 않는다(적용된 파일)
-- =====================================================================

DROP INDEX uq_lot_master_supplier_lot;
CREATE INDEX ix_lot_master_supplier_lot ON lot_master (comp_cd, supplier_cust_id, item_id, supplier_lot_no)
    WHERE supplier_lot_no IS NOT NULL;
COMMENT ON INDEX ix_lot_master_supplier_lot IS '공급사 LOT 으로 찾기(리콜) — 같은 공급사 LOT 이 여러 내부 LOT 에 있을 수 있다(결정 #43)';
