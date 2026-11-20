-- QMS·WMS: 단위 칸 너비를 공통코드 코드 칸(30)에 맞춘다 (docs/68 Q5 (가), 2026-11-09 결정) — V9708(병합 때 재번호)
-- UNIT 은 회사 확장 그룹이라 회사 코드가 30자까지 된다. 품목·LOT 의 unit_cd 는 30 인데 이 두 표만 20 이라
-- 21~30자 단위 품목의 NCR·고객 반품 등록이 22001(값이 너무 김)로 실패했다. 넓히기만 하므로 기존 행은 그대로.
ALTER TABLE qms_ncr ALTER COLUMN unit_cd TYPE varchar(30);
ALTER TABLE wms_cust_rtn ALTER COLUMN unit_cd TYPE varchar(30);
