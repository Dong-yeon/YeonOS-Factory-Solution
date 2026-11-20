-- CMS: 위험성평가 유해·위험요인 분류 CMS_HAZARD_CAT 를 회사가 늘릴 수 있게 (docs/68 5장 묶음 4) — V9705(병합 때 재번호)
-- 분류는 분기에 쓰이지 않는다(목록·보고서 묶음). 칸 너비 5 그대로 → 회사 코드 최대 5자(예: FIRE, FALL).
-- 위험 수준(CMS_RISK_LEVEL)·참여 역할(CMS_RA_ROLE, 법정 역할)은 시스템 고정 그대로. 기존 행은 시스템 코드라 그대로 통과.
UPDATE sys_code_grp SET comp_ext_yn = 'Y', code_max_len = 5 WHERE grp_cd = 'CMS_HAZARD_CAT';

ALTER TABLE cms_risk_item DROP CONSTRAINT ck_cms_risk_item_cat;
ALTER TABLE cms_risk_item ADD CONSTRAINT ck_cms_risk_item_cat CHECK (hazard_cat ~ '^[A-Z0-9][A-Z0-9_-]*$');
CREATE TRIGGER tg_cms_risk_item_code_cat BEFORE INSERT OR UPDATE OF hazard_cat ON cms_risk_item
    FOR EACH ROW EXECUTE FUNCTION hb_check_code_col('CMS_HAZARD_CAT', 'hazard_cat');
COMMENT ON COLUMN cms_risk_item.hazard_cat IS '유해·위험요인 분류 — 공통코드 CMS_HAZARD_CAT(시스템 7종 + 회사 코드)';
