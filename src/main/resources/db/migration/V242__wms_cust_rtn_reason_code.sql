-- WMS: 고객 반품 사유 WMS_CUST_RTN_REASON 을 회사가 늘릴 수 있게 (docs/68 5장 묶음 3) — V9704(병합 때 재번호)
-- 사유는 분기에 쓰이지 않는다(목록·ERP 송신 값). 칸 너비 20 그대로 → 회사 코드 최대 20자.
-- 형식 CHECK 는 회사 코드 규칙('-' 허용)에 맞추고, 존재 검사는 hb_check_code_col 로. 기존 행은 시스템 코드라 그대로 통과.
UPDATE sys_code_grp SET comp_ext_yn = 'Y', code_max_len = 20 WHERE grp_cd = 'WMS_CUST_RTN_REASON';

ALTER TABLE wms_cust_rtn DROP CONSTRAINT ck_wms_cust_rtn_reason;
ALTER TABLE wms_cust_rtn ADD CONSTRAINT ck_wms_cust_rtn_reason CHECK (reason_cd ~ '^[A-Z0-9][A-Z0-9_-]*$');
CREATE TRIGGER tg_wms_cust_rtn_code_reason BEFORE INSERT OR UPDATE OF reason_cd ON wms_cust_rtn
    FOR EACH ROW EXECUTE FUNCTION hb_check_code_col('WMS_CUST_RTN_REASON', 'reason_cd');
COMMENT ON COLUMN wms_cust_rtn.reason_cd IS '반품 사유 — 공통코드 WMS_CUST_RTN_REASON(시스템 4종 + 회사 코드)';
