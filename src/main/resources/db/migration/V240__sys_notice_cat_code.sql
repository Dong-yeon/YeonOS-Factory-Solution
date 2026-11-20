-- 시스템관리: 공지 분류 NOTICE_CAT 를 회사가 늘릴 수 있게 (docs/68 5장 묶음 1) — V9702(병합 때 재번호)
-- 분기에 쓰이지 않는 분류(공지·자료실 + 회사 코드, 예: 안전·품질 공지). 칸 너비 10 그대로 → 회사 코드 최대 10자.
-- 기존 행은 시스템 코드(NOTICE·LIBRARY)라 새 검사를 그대로 통과한다.
UPDATE sys_code_grp SET comp_ext_yn = 'Y', code_max_len = 10 WHERE grp_cd = 'NOTICE_CAT';

ALTER TABLE sys_notice DROP CONSTRAINT ck_sys_notice_cat;
ALTER TABLE sys_notice ADD CONSTRAINT ck_sys_notice_cat CHECK (cat_cd ~ '^[A-Z0-9][A-Z0-9_-]*$');
CREATE TRIGGER tg_sys_notice_code_cat BEFORE INSERT OR UPDATE OF cat_cd ON sys_notice
    FOR EACH ROW EXECUTE FUNCTION hb_check_code_col('NOTICE_CAT', 'cat_cd');
COMMENT ON COLUMN sys_notice.cat_cd IS '분류 — 공통코드 NOTICE_CAT(공지·자료실 + 회사 코드)';
