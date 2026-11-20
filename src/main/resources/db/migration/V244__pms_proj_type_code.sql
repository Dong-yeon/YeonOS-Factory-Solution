-- PMS: 프로젝트 유형 PMS_PROJ_TYPE 을 회사가 늘릴 수 있게 (docs/68 5장 묶음 5) — V9706(병합 때 재번호)
-- 유형은 분기에 쓰이지 않는다(목록·포트폴리오 묶음·템플릿 고르기, 기본값 APQP). 표 둘(템플릿·프로젝트) 같은 규칙.
-- 칸 너비 10 그대로 → 회사 코드 최대 10자. 기존 행은 시스템 코드라 그대로 통과.
UPDATE sys_code_grp SET comp_ext_yn = 'Y', code_max_len = 10 WHERE grp_cd = 'PMS_PROJ_TYPE';

ALTER TABLE pms_template DROP CONSTRAINT ck_pms_template_type;
ALTER TABLE pms_template ADD CONSTRAINT ck_pms_template_type CHECK (proj_type ~ '^[A-Z0-9][A-Z0-9_-]*$');
CREATE TRIGGER tg_pms_template_code_type BEFORE INSERT OR UPDATE OF proj_type ON pms_template
    FOR EACH ROW EXECUTE FUNCTION hb_check_code_col('PMS_PROJ_TYPE', 'proj_type');

ALTER TABLE pms_project DROP CONSTRAINT ck_pms_project_type;
ALTER TABLE pms_project ADD CONSTRAINT ck_pms_project_type CHECK (proj_type ~ '^[A-Z0-9][A-Z0-9_-]*$');
CREATE TRIGGER tg_pms_project_code_type BEFORE INSERT OR UPDATE OF proj_type ON pms_project
    FOR EACH ROW EXECUTE FUNCTION hb_check_code_col('PMS_PROJ_TYPE', 'proj_type');
COMMENT ON COLUMN pms_template.proj_type IS '프로젝트 유형 — 공통코드 PMS_PROJ_TYPE(시스템 5종 + 회사 코드)';
COMMENT ON COLUMN pms_project.proj_type IS '프로젝트 유형 — 공통코드 PMS_PROJ_TYPE(시스템 5종 + 회사 코드)';
