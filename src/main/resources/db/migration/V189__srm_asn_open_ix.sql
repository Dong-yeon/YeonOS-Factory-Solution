-- SRM 열린 ASN 부분 색인 (docs/31 5.6 뒤 정리) — WMS 도착 예정 목록(AsnQuery#expected): 공장 + 보냄·입고 중 + 덜 받은 행 > 0, 도착 예정일·번호 순.
--   기간 조건이 없어도 열린 ASN 만 담은 작은 색인을 순서대로 읽고 limit 에서 멈춘다(100만 건 중 열린 1.5%: 칸만 300ms → 색인 3ms).
--   색인에 드는 행은 열린 ASN 뿐이라 다 받은·마감 ASN 이 쌓여도 크기가 늘지 않는다.
CREATE INDEX ix_srm_asn_open ON srm_asn (comp_cd, plant_cd, eta_date, asn_no)
    WHERE status IN ('SENT', 'PARTIAL_RECEIVED') AND open_line_cnt > 0;
