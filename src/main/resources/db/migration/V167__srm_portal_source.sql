-- =====================================================================
-- SRM 협력사 포털 업무 (docs/42 7장 3 · docs/31 4.13 P-1·P-2) — 임시 번호(docs/29 2.2)
--
-- - 협력사가 포털에서 직접 넣은 납기 회신·ASN 은 출처 PORTAL(지금까지 MANUAL = 우리 직원 대리 입력, IMPORT = 엑셀)
-- - 4.13.3 "값 추가는 쓰는 쪽이 생길 때 같이" — 쓰는 쪽(module.srm.portal)과 같은 PR. EDI 는 P6 어댑터가 생길 때
-- - 표·정책 그 밖은 그대로: 협력사 격리는 RLS 가 아니라 포털 서비스의 cust_id 조건 + 가드 시험(docs/42 4장)
-- =====================================================================

ALTER TABLE srm_po_ack DROP CONSTRAINT ck_srm_po_ack_source;
ALTER TABLE srm_po_ack ADD CONSTRAINT ck_srm_po_ack_source CHECK (source IN ('MANUAL', 'IMPORT', 'PORTAL'));

ALTER TABLE srm_asn DROP CONSTRAINT ck_srm_asn_source;
ALTER TABLE srm_asn ADD CONSTRAINT ck_srm_asn_source CHECK (source IN ('MANUAL', 'IMPORT', 'PORTAL'));
