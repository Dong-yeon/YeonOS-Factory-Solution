-- CMS: 점검 실행 건너뛰기 사유 코드 CMS_SKIP_REASON (docs/68 Q1 (다), 2026-11-09 결정) — V9712(병합 때 재번호)
-- 집계(어떤 이유로 PM 을 거르는지)가 쓸모 있는 사유만 코드 + 메모로. skip_reason(글자)은 그대로 — 메모가 없으면 서버가 코드 이름을 넣는다
-- (ck_cms_chk_run_skip: SKIPPED 면 글자 필수, 그대로).
-- skip_reason_cd 는 비어도 된다: 옛 행, 작업지시 취소로 건너뛴 예정(사유는 작업지시 취소 사유), 코드 없이 글자만 보낸 API(미분류).
-- 회사 확장(코드 최대 30자). 사유는 분기에 쓰이지 않는다.
INSERT INTO sys_code_grp (grp_cd, grp_nm_key, comp_ext_yn) VALUES ('CMS_SKIP_REASON', 'code.grp.CMS_SKIP_REASON', 'Y');
INSERT INTO sys_code (grp_cd, code, code_nm_key, sort_no) VALUES
    ('CMS_SKIP_REASON', 'NO_PROD',   'code.CMS_SKIP_REASON.NO_PROD',   10),
    ('CMS_SKIP_REASON', 'EQUIP_DOWN', 'code.CMS_SKIP_REASON.EQUIP_DOWN', 20),
    ('CMS_SKIP_REASON', 'NO_PART',   'code.CMS_SKIP_REASON.NO_PART',   30),
    ('CMS_SKIP_REASON', 'NO_STAFF',  'code.CMS_SKIP_REASON.NO_STAFF',  40),
    ('CMS_SKIP_REASON', 'REPLACED',  'code.CMS_SKIP_REASON.REPLACED',  50),
    ('CMS_SKIP_REASON', 'OTHER',     'code.CMS_SKIP_REASON.OTHER',     90);

ALTER TABLE cms_chk_run ADD COLUMN skip_reason_cd varchar(30);
ALTER TABLE cms_chk_run ADD CONSTRAINT ck_cms_chk_run_skip_reason_cd CHECK (skip_reason_cd ~ '^[A-Z0-9][A-Z0-9_-]*$');
-- 사유 코드는 건너뛴 예정에만
ALTER TABLE cms_chk_run ADD CONSTRAINT ck_cms_chk_run_skip_cd_status CHECK (skip_reason_cd IS NULL OR status = 'SKIPPED');
CREATE TRIGGER tg_cms_chk_run_code_skip_reason BEFORE INSERT OR UPDATE OF skip_reason_cd ON cms_chk_run
    FOR EACH ROW EXECUTE FUNCTION hb_check_code_col('CMS_SKIP_REASON', 'skip_reason_cd');
COMMENT ON COLUMN cms_chk_run.skip_reason_cd IS '건너뛰기 사유 코드 — 공통코드 CMS_SKIP_REASON(회사 확장). 화면 건너뛰기는 필수, 비면 미분류(옛 행·작업지시 취소·글자만 API)';
COMMENT ON COLUMN cms_chk_run.skip_reason IS '건너뛰기 사유 글자(메모) — 메모가 없으면 코드 이름';
