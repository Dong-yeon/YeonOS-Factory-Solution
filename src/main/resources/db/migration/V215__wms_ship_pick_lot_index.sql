-- =====================================================================
-- WMS 출하 추적(docs/57) — LOT 이 나간 출하를 LOT 에서 찾는 길(ShipQuery.byLots). 임시 번호(docs/29 2.2)
-- 피킹은 LOT 관리 품목만 lot_id 가 있다 — 부분 색인. 원장 쪽은 기존 출처 색인 ix_inv_trx_src(comp_cd, src_type, src_id)
-- =====================================================================
CREATE INDEX ix_wms_ship_pick_lot ON wms_ship_pick (comp_cd, lot_id) WHERE lot_id IS NOT NULL;
