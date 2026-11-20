-- PMS 빠른 보완 (docs/48 6장 "빠른 보완", 사용자 결정 P-2 2026-10-30, docs/32 4.15)
-- - 이슈 유형 issue_type = 공통코드 PMS_ISSUE_TYPE(결함·위험·요청·질문, 회사가 더할 수 있음 comp_ext_yn = Y).
--   기존 행: 게이트·APQP 에서 온 것 = 위험, 나머지 = 요청(자료가 없어 정한 값 — 사람이 고칠 수 있음)
-- - 기각: 종료 방식 close_kind = DONE(해결 뒤 종료) / REJECTED(열린 이슈를 사유와 함께 닫음, 사유 = resolution). 기존 종료 = DONE
-- - 첨부는 플랫폼 sys_file(대상 화면 pms-issue) — 표 변경 없음, 잠금 규칙은 앱(IssueFileLock)

INSERT INTO sys_code_grp (grp_cd, grp_nm_key, comp_ext_yn) VALUES ('PMS_ISSUE_TYPE', 'code.grp.PMS_ISSUE_TYPE', 'Y');
INSERT INTO sys_code (grp_cd, code, code_nm_key, sort_no) VALUES
    ('PMS_ISSUE_TYPE', 'DEFECT',   'code.PMS_ISSUE_TYPE.DEFECT',   1),
    ('PMS_ISSUE_TYPE', 'RISK',     'code.PMS_ISSUE_TYPE.RISK',     2),
    ('PMS_ISSUE_TYPE', 'REQUEST',  'code.PMS_ISSUE_TYPE.REQUEST',  3),
    ('PMS_ISSUE_TYPE', 'QUESTION', 'code.PMS_ISSUE_TYPE.QUESTION', 4);
INSERT INTO sys_code_grp (grp_cd, grp_nm_key, comp_ext_yn) VALUES ('PMS_ISSUE_CLOSE', 'code.grp.PMS_ISSUE_CLOSE', 'N');
INSERT INTO sys_code (grp_cd, code, code_nm_key, sort_no) VALUES
    ('PMS_ISSUE_CLOSE', 'DONE',     'code.PMS_ISSUE_CLOSE.DONE',     1),
    ('PMS_ISSUE_CLOSE', 'REJECTED', 'code.PMS_ISSUE_CLOSE.REJECTED', 2);

-- 닫힌 이슈는 검사 트리거가 고치기를 막으므로 채우는 동안만 검사 트리거를 끈다(같은 트랜잭션, 감사 트리거는 그대로 — 채운 값도 이력에 남는다)
ALTER TABLE pms_issue DISABLE TRIGGER tg_pms_issue_guard;
ALTER TABLE pms_issue DISABLE TRIGGER tg_pms_issue_zcapa;
ALTER TABLE pms_issue DISABLE TRIGGER tg_pms_issue_zapqp;
ALTER TABLE pms_issue ADD COLUMN issue_type varchar(30) NOT NULL DEFAULT 'REQUEST';
UPDATE pms_issue SET issue_type = 'RISK' WHERE source IN ('GATE', 'APQP');
ALTER TABLE pms_issue ADD COLUMN close_kind varchar(8);
UPDATE pms_issue SET close_kind = 'DONE' WHERE status = 'CLOSED';
ALTER TABLE pms_issue ENABLE TRIGGER tg_pms_issue_guard;
ALTER TABLE pms_issue ENABLE TRIGGER tg_pms_issue_zcapa;
ALTER TABLE pms_issue ENABLE TRIGGER tg_pms_issue_zapqp;
ALTER TABLE pms_issue ADD CONSTRAINT ck_pms_issue_close_kind CHECK (
    (status = 'CLOSED') = (close_kind IS NOT NULL) AND (close_kind IS NULL OR close_kind IN ('DONE', 'REJECTED')));
CREATE INDEX ix_pms_issue_type ON pms_issue (comp_cd, issue_type);
COMMENT ON COLUMN pms_issue.issue_type IS '유형 — 공통코드 PMS_ISSUE_TYPE(결함·위험·요청·질문 + 회사 코드)';
COMMENT ON COLUMN pms_issue.close_kind IS '종료 방식 — DONE = 해결 뒤 종료, REJECTED = 기각(사유는 resolution)';

-- 전이·유형 검사 — V129 와 같고 기각 전이·유형 코드를 더한다
CREATE OR REPLACE FUNCTION hb_guard_pms_issue() RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    v_status varchar(10);
    v_obj_ok boolean;
BEGIN
    SELECT p.status INTO v_status FROM pms_project p WHERE p.proj_id = NEW.proj_id AND p.comp_cd = NEW.comp_cd;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'project % is not of company %', NEW.proj_id, NEW.comp_cd USING ERRCODE = '23503', CONSTRAINT = 'fk_pms_issue_proj_comp';
    END IF;
    IF v_status IN ('CLOSED', 'CANCELLED') THEN
        RAISE EXCEPTION 'project % is closed', NEW.proj_id USING ERRCODE = '55000', CONSTRAINT = 'ck_pms_issue_project_closed';
    END IF;
    IF TG_OP = 'UPDATE' THEN
        IF NEW.proj_id <> OLD.proj_id OR NEW.issue_no <> OLD.issue_no OR NEW.source <> OLD.source
           OR NEW.gate_review_id IS DISTINCT FROM OLD.gate_review_id THEN
            RAISE EXCEPTION 'issue keys are fixed' USING ERRCODE = '55000', CONSTRAINT = 'ck_pms_issue_key_fixed';
        END IF;
        IF OLD.status = 'CLOSED' THEN
            RAISE EXCEPTION 'issue % is closed', OLD.issue_no USING ERRCODE = '55000', CONSTRAINT = 'ck_pms_issue_closed';
        END IF;
        -- 종료 = 해결 뒤 종료(DONE) 또는 열린 이슈의 기각(REJECTED, 사유 = resolution) — docs/32 4.15
        IF NEW.status <> OLD.status AND NOT (
               (OLD.status = 'OPEN'        AND NEW.status IN ('IN_PROGRESS', 'RESOLVED'))
            OR (OLD.status = 'IN_PROGRESS' AND NEW.status IN ('OPEN', 'RESOLVED'))
            OR (OLD.status = 'RESOLVED'    AND NEW.status = 'IN_PROGRESS')
            OR (OLD.status = 'RESOLVED'    AND NEW.status = 'CLOSED' AND NEW.close_kind = 'DONE')
            OR (OLD.status IN ('OPEN', 'IN_PROGRESS') AND NEW.status = 'CLOSED' AND NEW.close_kind = 'REJECTED')) THEN
            RAISE EXCEPTION 'issue status % -> % is not allowed', OLD.status, NEW.status
                USING ERRCODE = '55000', CONSTRAINT = 'ck_pms_issue_transition';
        END IF;
    ELSIF NEW.status <> 'OPEN' THEN
        RAISE EXCEPTION 'issue starts as OPEN' USING ERRCODE = '55000', CONSTRAINT = 'ck_pms_issue_transition';
    END IF;
    -- 유형 = 공통코드 PMS_ISSUE_TYPE(회사가 더할 수 있음) 중 사용 중인 것
    IF (TG_OP = 'INSERT' OR NEW.issue_type IS DISTINCT FROM OLD.issue_type) AND NOT hb_code_usable(NEW.comp_cd, 'PMS_ISSUE_TYPE', NEW.issue_type) THEN
        RAISE EXCEPTION 'issue type % is not usable', NEW.issue_type USING ERRCODE = '23503', CONSTRAINT = 'fk_pms_issue_type_code';
    END IF;
    IF NEW.task_id IS NOT NULL AND (TG_OP = 'INSERT' OR NEW.task_id IS DISTINCT FROM OLD.task_id)
       AND NOT EXISTS (SELECT 1 FROM pms_task t WHERE t.task_id = NEW.task_id AND t.proj_id = NEW.proj_id AND t.comp_cd = NEW.comp_cd) THEN
        RAISE EXCEPTION 'task % is not in project %', NEW.task_id, NEW.proj_id USING ERRCODE = '23503', CONSTRAINT = 'fk_pms_issue_task_proj';
    END IF;
    IF NEW.owner_user_id IS NOT NULL AND (TG_OP = 'INSERT' OR NEW.owner_user_id IS DISTINCT FROM OLD.owner_user_id)
       AND NOT EXISTS (SELECT 1 FROM sys_user u WHERE u.user_id = NEW.owner_user_id AND u.comp_cd = NEW.comp_cd) THEN
        RAISE EXCEPTION 'user % is not of company %', NEW.owner_user_id, NEW.comp_cd USING ERRCODE = '23503', CONSTRAINT = 'fk_pms_issue_owner_comp';
    END IF;
    IF NEW.gate_review_id IS NOT NULL AND TG_OP = 'INSERT'
       AND NOT EXISTS (SELECT 1 FROM pms_gate_review r WHERE r.review_id = NEW.gate_review_id AND r.proj_id = NEW.proj_id
                          AND r.comp_cd = NEW.comp_cd) THEN
        RAISE EXCEPTION 'gate review % is not in project %', NEW.gate_review_id, NEW.proj_id
            USING ERRCODE = '23503', CONSTRAINT = 'fk_pms_issue_review_proj';
    END IF;
    -- 연결 객체 = 같은 회사의 행(FK 는 RLS 를 무시하므로 유형별로 직접 확인 — "A사 품목에 B사 이슈" 금지).
    -- plpgsql 의 IF 조건은 첫 THEN 에서 끝나므로 CASE 는 따로 계산한다
    IF NEW.obj_type IS NOT NULL AND (TG_OP = 'INSERT' OR NEW.obj_type IS DISTINCT FROM OLD.obj_type OR NEW.obj_id IS DISTINCT FROM OLD.obj_id) THEN
        v_obj_ok := CASE NEW.obj_type
            WHEN 'ITEM'  THEN EXISTS (SELECT 1 FROM mst_item x  WHERE x.item_id  = NEW.obj_id AND x.comp_cd = NEW.comp_cd)
            WHEN 'CUST'  THEN EXISTS (SELECT 1 FROM mst_cust x  WHERE x.cust_id  = NEW.obj_id AND x.comp_cd = NEW.comp_cd)
            WHEN 'EQUIP' THEN EXISTS (SELECT 1 FROM mst_equip x WHERE x.equip_id = NEW.obj_id AND x.comp_cd = NEW.comp_cd)
            WHEN 'ASSET' THEN EXISTS (SELECT 1 FROM mst_phys_asset x WHERE x.asset_id = NEW.obj_id AND x.comp_cd = NEW.comp_cd)
            WHEN 'LOT'   THEN EXISTS (SELECT 1 FROM lot_master x WHERE x.lot_id = NEW.obj_id AND x.comp_cd = NEW.comp_cd)
            ELSE false END;
        IF NOT v_obj_ok THEN
            RAISE EXCEPTION 'object %:% is not of company %', NEW.obj_type, NEW.obj_id, NEW.comp_cd
                USING ERRCODE = '23503', CONSTRAINT = 'fk_pms_issue_obj_comp';
        END IF;
    END IF;
    RETURN NEW;
END
$$;
