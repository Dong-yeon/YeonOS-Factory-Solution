-- PMS 착수·종료 승인 (docs/48 6장 ⑤, 사용자 결정 P-3·P-4 2026-10-30, docs/32 4.16)
-- - 정책 PMS_PROJ_APPROVAL: N(기본) = 지금처럼 PM 이 바로 착수·종료 / Y = 플랫폼 결재(문서 유형 PMS_PROJ_OPEN·PMS_PROJ_CLOSE)
-- - pms_project.apr_to = 결재 중인 목표 상태(ACTIVE = 착수, CLOSED = 종료), apr_id = 그 결재. 결재 중에는 머리 칸 잠금(트리거)
-- 표 소유 = PMS. 다른 모듈 표 변경 없음(apr_request 는 FK 로만 참조)

INSERT INTO sys_policy_def (policy_key, value_type, default_value, allowed_values, min_value, max_value, desc_key) VALUES
    ('PMS_PROJ_APPROVAL', 'BOOL', 'N', NULL, NULL, NULL, 'policy.PMS_PROJ_APPROVAL');

ALTER TABLE pms_project ADD COLUMN apr_to varchar(10);
ALTER TABLE pms_project ADD COLUMN apr_id bigint;
ALTER TABLE pms_project ADD CONSTRAINT fk_pms_project_apr FOREIGN KEY (apr_id) REFERENCES apr_request (apr_id);
ALTER TABLE pms_project ADD CONSTRAINT ck_pms_project_apr CHECK (
    (apr_to IS NULL OR apr_to IN ('ACTIVE', 'CLOSED')) AND (apr_id IS NULL OR apr_to IS NOT NULL));
CREATE INDEX ix_pms_project_apr ON pms_project (apr_id) WHERE apr_id IS NOT NULL;
COMMENT ON COLUMN pms_project.apr_to IS '착수·종료 결재 중인 목표 상태(ACTIVE·CLOSED) — 결재가 끝나면 비움';
COMMENT ON COLUMN pms_project.apr_id IS '착수·종료 결재(apr_request) — 결재 중일 때만';

-- 머리 검사 — V039 와 같고 결재 중 잠금을 더한다
CREATE OR REPLACE FUNCTION hb_guard_pms_project() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    -- 회사별 테이블 간 FK 는 RLS 를 무시한다 → 같은 회사 행인지 직접 확인
    IF NEW.cust_id IS NOT NULL AND (TG_OP = 'INSERT' OR NEW.cust_id IS DISTINCT FROM OLD.cust_id)
       AND NOT EXISTS (SELECT 1 FROM mst_cust c WHERE c.cust_id = NEW.cust_id AND c.comp_cd = NEW.comp_cd) THEN
        RAISE EXCEPTION 'customer % is not of company %', NEW.cust_id, NEW.comp_cd USING ERRCODE = '23503', CONSTRAINT = 'fk_pms_project_cust_comp';
    END IF;
    IF (TG_OP = 'INSERT' OR NEW.pm_user_id IS DISTINCT FROM OLD.pm_user_id)
       AND NOT EXISTS (SELECT 1 FROM sys_user u WHERE u.user_id = NEW.pm_user_id AND u.comp_cd = NEW.comp_cd) THEN
        RAISE EXCEPTION 'user % is not of company %', NEW.pm_user_id, NEW.comp_cd USING ERRCODE = '23503', CONSTRAINT = 'fk_pms_project_pm_comp';
    END IF;
    IF NEW.tmpl_id IS NOT NULL AND (TG_OP = 'INSERT' OR NEW.tmpl_id IS DISTINCT FROM OLD.tmpl_id)
       AND NOT EXISTS (SELECT 1 FROM pms_template t WHERE t.tmpl_id = NEW.tmpl_id AND t.comp_cd = NEW.comp_cd) THEN
        RAISE EXCEPTION 'template % is not of company %', NEW.tmpl_id, NEW.comp_cd USING ERRCODE = '23503', CONSTRAINT = 'fk_pms_project_tmpl_comp';
    END IF;
    IF TG_OP = 'INSERT' THEN
        IF NEW.status <> 'DRAFT' THEN
            RAISE EXCEPTION 'project starts as DRAFT' USING ERRCODE = '55000', CONSTRAINT = 'ck_pms_project_transition';
        END IF;
        RETURN NEW;
    END IF;
    IF NEW.proj_no <> OLD.proj_no THEN
        RAISE EXCEPTION 'project number is fixed' USING ERRCODE = '55000', CONSTRAINT = 'ck_pms_project_no_fixed';
    END IF;
    -- 닫힌 프로젝트는 바뀌지 않는다(재개 없음 — 새 프로젝트로)
    IF OLD.status IN ('CLOSED', 'CANCELLED') THEN
        RAISE EXCEPTION 'project % is closed', OLD.proj_no USING ERRCODE = '55000', CONSTRAINT = 'ck_pms_project_closed_fixed';
    END IF;
    -- 착수·종료 결재(docs/32 4.16): 결재 중에는 머리 칸이 그대로(작업에서 파생한 계획일·진척만 바뀔 수 있다).
    -- 결재 시작 = apr_to 를 채움(상태 그대로), 결재 번호는 한 번만, 끝 = apr_to·apr_id 를 비움 + 승인이면 상태 = apr_to
    IF OLD.apr_to IS NULL AND NEW.apr_to IS NOT NULL THEN
        IF NEW.status <> OLD.status OR NOT ((OLD.status = 'DRAFT' AND NEW.apr_to = 'ACTIVE') OR (OLD.status = 'ACTIVE' AND NEW.apr_to = 'CLOSED')) THEN
            RAISE EXCEPTION 'project % cannot request % from %', OLD.proj_no, NEW.apr_to, OLD.status
                USING ERRCODE = '55000', CONSTRAINT = 'ck_pms_project_approval';
        END IF;
    END IF;
    IF OLD.apr_to IS NOT NULL THEN
        IF (NEW.proj_nm, NEW.proj_type, NEW.cust_id, NEW.plant_cd, NEW.pm_user_id, NEW.tmpl_id, NEW.sop_date, NEW.remark, NEW.eco_id)
               IS DISTINCT FROM (OLD.proj_nm, OLD.proj_type, OLD.cust_id, OLD.plant_cd, OLD.pm_user_id, OLD.tmpl_id, OLD.sop_date, OLD.remark, OLD.eco_id)
           OR (NEW.apr_to IS NOT NULL AND (NEW.apr_to <> OLD.apr_to OR NEW.status <> OLD.status
                                          OR (OLD.apr_id IS NOT NULL AND NEW.apr_id IS DISTINCT FROM OLD.apr_id)))
           OR (NEW.apr_to IS NULL AND NEW.status NOT IN (OLD.status, OLD.apr_to)) THEN
            RAISE EXCEPTION 'project % is in approval', OLD.proj_no USING ERRCODE = '55000', CONSTRAINT = 'ck_pms_project_in_approval';
        END IF;
        RETURN NEW;
    END IF;
    IF NEW.status <> OLD.status AND NOT (
           (OLD.status = 'DRAFT'   AND NEW.status IN ('ACTIVE', 'CANCELLED'))
        OR (OLD.status = 'ACTIVE'  AND NEW.status IN ('ON_HOLD', 'CLOSED', 'CANCELLED'))
        OR (OLD.status = 'ON_HOLD' AND NEW.status IN ('ACTIVE', 'CANCELLED'))) THEN
        RAISE EXCEPTION 'project status % -> % is not allowed', OLD.status, NEW.status
            USING ERRCODE = '55000', CONSTRAINT = 'ck_pms_project_transition';
    END IF;
    RETURN NEW;
END
$$;
