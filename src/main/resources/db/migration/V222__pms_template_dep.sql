-- PMS 템플릿 선후행 + 프로젝트 → 템플릿으로 저장 (docs/48 6장 ⑧, 사용자 결정 P-7 2026-11-01, docs/32 4.18)
-- - pms_template_dep: 템플릿 작업 사이 FS 의존(lag). 프로젝트 의존(pms_task_dep, V040)과 같은 규칙 — 같은 템플릿의 잎 작업끼리, 순환 금지,
--   추가·삭제만. 템플릿 → 프로젝트 복사 때 pms_task_dep 로 따라간다
-- - 의존이 걸린 템플릿 작업은 하위 작업을 가질 수 없다(프로젝트와 같은 이유 — 요약이 되면 의존 뜻이 흐려진다)
-- 표 소유 = PMS. 다른 모듈 표 변경 없음

CREATE TABLE pms_template_dep (
    tdep_id     bigint        GENERATED ALWAYS AS IDENTITY,
    comp_cd     varchar(20)   NOT NULL,
    tmpl_id     bigint        NOT NULL,
    pred_tt_id  bigint        NOT NULL,
    succ_tt_id  bigint        NOT NULL,
    lag_days    integer       NOT NULL DEFAULT 0,
    created_at  timestamptz   NOT NULL DEFAULT now(),
    created_by  varchar(50)   NOT NULL,
    CONSTRAINT pk_pms_template_dep PRIMARY KEY (tdep_id),
    CONSTRAINT fk_pms_template_dep_tmpl FOREIGN KEY (tmpl_id) REFERENCES pms_template (tmpl_id),
    CONSTRAINT fk_pms_template_dep_pred FOREIGN KEY (pred_tt_id) REFERENCES pms_template_task (tt_id),
    CONSTRAINT fk_pms_template_dep_succ FOREIGN KEY (succ_tt_id) REFERENCES pms_template_task (tt_id),
    CONSTRAINT uq_pms_template_dep UNIQUE (comp_cd, pred_tt_id, succ_tt_id),
    CONSTRAINT ck_pms_template_dep_self CHECK (pred_tt_id <> succ_tt_id),
    CONSTRAINT ck_pms_template_dep_lag CHECK (lag_days BETWEEN -365 AND 365)
);
COMMENT ON TABLE pms_template_dep IS '템플릿 작업 의존(FS + lag, docs/32 4.18). 템플릿 → 프로젝트 복사 때 pms_task_dep 로. 추가·삭제만';
CREATE INDEX ix_pms_template_dep_tmpl ON pms_template_dep (comp_cd, tmpl_id);
CREATE INDEX ix_pms_template_dep_succ ON pms_template_dep (comp_cd, succ_tt_id);

CREATE FUNCTION hb_guard_pms_template_dep() RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    v_cycle boolean;
BEGIN
    IF TG_OP = 'DELETE' THEN
        RETURN OLD;
    END IF;
    IF TG_OP = 'UPDATE' THEN
        RAISE EXCEPTION 'template dependency rows are insert/delete only' USING ERRCODE = '55000', CONSTRAINT = 'ck_pms_template_dep_fixed';
    END IF;
    -- 회사별 테이블 간 FK 는 RLS 를 무시한다 → 두 작업이 같은 회사·같은 템플릿의 잎(하위 없음)인지 직접 확인
    IF (SELECT count(*) FROM pms_template_task k WHERE k.tt_id IN (NEW.pred_tt_id, NEW.succ_tt_id)
           AND k.tmpl_id = NEW.tmpl_id AND k.comp_cd = NEW.comp_cd
           AND NOT EXISTS (SELECT 1 FROM pms_template_task c WHERE c.parent_id = k.tt_id)) <> 2 THEN
        RAISE EXCEPTION 'dependency tasks must be leaf tasks of template %', NEW.tmpl_id
            USING ERRCODE = '23514', CONSTRAINT = 'ck_pms_template_dep_tasks';
    END IF;
    -- 순환(UNION 으로 중복 제거 — 마름모에서 경로 폭증 없음, V040 과 같은 방식)
    WITH RECURSIVE nxt(tt_id) AS (
        SELECT NEW.succ_tt_id
        UNION
        SELECT d.succ_tt_id FROM nxt n JOIN pms_template_dep d ON d.pred_tt_id = n.tt_id
    )
    SELECT bool_or(tt_id = NEW.pred_tt_id) INTO v_cycle FROM nxt;
    IF v_cycle THEN
        RAISE EXCEPTION 'template dependency % -> % makes a cycle', NEW.pred_tt_id, NEW.succ_tt_id
            USING ERRCODE = '23514', CONSTRAINT = 'ck_pms_template_dep_cycle';
    END IF;
    RETURN NEW;
END
$$;
CREATE TRIGGER tg_pms_template_dep_guard BEFORE INSERT OR UPDATE OR DELETE ON pms_template_dep
    FOR EACH ROW EXECUTE FUNCTION hb_guard_pms_template_dep();

-- 의존이 걸린 템플릿 작업은 하위를 가질 수 없다(서비스가 먼저 칸 오류로)
CREATE FUNCTION hb_guard_pms_template_dep_parent() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.parent_id IS NOT NULL AND (TG_OP = 'INSERT' OR NEW.parent_id IS DISTINCT FROM OLD.parent_id)
       AND EXISTS (SELECT 1 FROM pms_template_dep d WHERE d.pred_tt_id = NEW.parent_id OR d.succ_tt_id = NEW.parent_id) THEN
        RAISE EXCEPTION 'template task % has dependencies and cannot have sub-tasks', NEW.parent_id
            USING ERRCODE = '23514', CONSTRAINT = 'ck_pms_template_task_parent_has_deps';
    END IF;
    RETURN NEW;
END
$$;
CREATE TRIGGER tg_pms_template_dep_parent BEFORE INSERT OR UPDATE ON pms_template_task
    FOR EACH ROW EXECUTE FUNCTION hb_guard_pms_template_dep_parent();

-- 감사 · RLS · 권한 · 보존(템플릿 = 기준정보)
CREATE TRIGGER tg_audit AFTER INSERT OR UPDATE OR DELETE ON pms_template_dep FOR EACH ROW EXECUTE FUNCTION hb_audit('tdep_id', '', '');
ALTER TABLE pms_template_dep ENABLE ROW LEVEL SECURITY;
ALTER TABLE pms_template_dep FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON pms_template_dep USING (comp_cd = hb_current_comp()) WITH CHECK (comp_cd = hb_current_comp());
REVOKE UPDATE, TRUNCATE ON pms_template_dep FROM ${app_role};
INSERT INTO sys_retention_table (table_nm, class_cd) VALUES ('pms_template_dep', 'MASTER');
