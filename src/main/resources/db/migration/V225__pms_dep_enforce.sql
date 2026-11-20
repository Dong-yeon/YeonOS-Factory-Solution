-- PMS 선행 미완 실적 제약 (docs/48 ⑥ 중 실적 제약, 사용자 결정 P-12 2026-11-01, docs/32 4.21)
-- - 정책 PMS_DEP_ENFORCE: OFF = 검사 없음 / WARN(기본) = 지금처럼 위반 표시만 / BLOCK = FS 선행이 끝나지 않은(완료·취소 아님) 작업에
--   실적 시작을 넣지 못한다. 단 PM(관리 가능)은 사유를 적으면 통과 — 그 기록 = pms_task_dep_waive
-- - pms_task_dep_waive: 통과한 사람·사유·그때 열린 선행(이름 목록). 넣기만(고치기·지우기 REVOKE)
-- 표 소유 = PMS. 다른 모듈 표 변경 없음

INSERT INTO sys_policy_def (policy_key, value_type, default_value, allowed_values, min_value, max_value, desc_key) VALUES
    ('PMS_DEP_ENFORCE', 'ENUM', 'WARN', ARRAY['OFF','WARN','BLOCK'], NULL, NULL, 'policy.PMS_DEP_ENFORCE');

CREATE TABLE pms_task_dep_waive (
    waive_id    bigint        GENERATED ALWAYS AS IDENTITY,
    comp_cd     varchar(20)   NOT NULL,
    proj_id     bigint        NOT NULL,
    task_id     bigint        NOT NULL,
    reason      varchar(500)  NOT NULL,
    open_preds  varchar(2000) NOT NULL,
    created_at  timestamptz   NOT NULL DEFAULT now(),
    created_by  varchar(50)   NOT NULL,
    CONSTRAINT pk_pms_task_dep_waive PRIMARY KEY (waive_id),
    CONSTRAINT fk_pms_task_dep_waive_proj FOREIGN KEY (proj_id) REFERENCES pms_project (proj_id),
    CONSTRAINT fk_pms_task_dep_waive_task FOREIGN KEY (task_id) REFERENCES pms_task (task_id),
    CONSTRAINT ck_pms_task_dep_waive_reason CHECK (length(btrim(reason)) > 0),
    CONSTRAINT ck_pms_task_dep_waive_preds CHECK (length(btrim(open_preds)) > 0)
);
COMMENT ON TABLE pms_task_dep_waive IS '선행 미완 실적 통과 기록(정책 PMS_DEP_ENFORCE=BLOCK 에서 PM 이 사유로 통과, docs/32 4.21). 넣기만';
CREATE INDEX ix_pms_task_dep_waive_task ON pms_task_dep_waive (comp_cd, task_id);

-- 회사별 테이블 간 FK 는 RLS 를 무시한다 → 작업이 같은 회사·같은 프로젝트인지 직접 확인
CREATE FUNCTION hb_guard_pms_task_dep_waive() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pms_task k WHERE k.task_id = NEW.task_id AND k.proj_id = NEW.proj_id AND k.comp_cd = NEW.comp_cd) THEN
        RAISE EXCEPTION 'task % is not in project % of company %', NEW.task_id, NEW.proj_id, NEW.comp_cd
            USING ERRCODE = '23503', CONSTRAINT = 'fk_pms_task_dep_waive_task_comp';
    END IF;
    RETURN NEW;
END
$$;
CREATE TRIGGER tg_pms_task_dep_waive_guard BEFORE INSERT ON pms_task_dep_waive
    FOR EACH ROW EXECUTE FUNCTION hb_guard_pms_task_dep_waive();

CREATE TRIGGER tg_audit AFTER INSERT OR UPDATE OR DELETE ON pms_task_dep_waive FOR EACH ROW EXECUTE FUNCTION hb_audit('waive_id', '', '');
ALTER TABLE pms_task_dep_waive ENABLE ROW LEVEL SECURITY;
ALTER TABLE pms_task_dep_waive FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON pms_task_dep_waive USING (comp_cd = hb_current_comp()) WITH CHECK (comp_cd = hb_current_comp());
REVOKE UPDATE, DELETE, TRUNCATE ON pms_task_dep_waive FROM ${app_role};
INSERT INTO sys_retention_table (table_nm, class_cd) VALUES ('pms_task_dep_waive', 'QUALITY_RECORD');
