-- =====================================================================
-- WMS 고객 반품 — QMS 8D 반품 연계 조회용 색인 (2026-11-02, 상위 요청) — 임시 번호(docs/29 2.2)
--
-- - QMS 8D 상세가 module.wms.link.ShipQuery.custReturns 로 wms_cust_rtn 을
--   `btrim(capa_no) = ?` OR `ncr_id = any(?)` 로 읽는다. 색인이 없으면 8D 상세를 열 때마다 회사 안 반품 전체를 훑는다.
-- - 식 btrim(capa_no) 는 QMS 쿼리와 글자 그대로 같아야 색인을 탄다(바꾸면 함께 바꿀 것).
-- - 맨 앞 comp_cd: RLS 정책(comp_cd = hb_current_comp())이 늘 붙는 조건. 부분 색인(null 제외) — capa_no·ncr_id 는 비는 행이 많다
-- =====================================================================

CREATE INDEX ix_wms_cust_rtn_capa ON wms_cust_rtn (comp_cd, btrim(capa_no)) WHERE capa_no IS NOT NULL;
CREATE INDEX ix_wms_cust_rtn_ncr ON wms_cust_rtn (comp_cd, ncr_id) WHERE ncr_id IS NOT NULL;
