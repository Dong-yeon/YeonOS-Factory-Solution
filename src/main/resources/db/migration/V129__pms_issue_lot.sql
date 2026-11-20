-- =====================================================================
-- PMS 7단계 ③ — 이슈 객체 연결에 LOT (docs/32 PM7·4.9 ③, D9·D20)
-- 앱은 MES 계약 LotLookup(byNo·lots)으로만 찾고 보여 준다. MES 표(lot_master)는 같은 회사 확인 트리거가 읽기만 한다(쓰기 없음).
-- 이슈 가드 함수(V063)를 그대로 다시 만들고 객체 유형 확인에 LOT 한 줄만 더한다
-- =====================================================================

ALTER TABLE pms_issue DROP CONSTRAINT ck_pms_issue_obj_type;
ALTER TABLE pms_issue ADD CONSTRAINT ck_pms_issue_obj_type CHECK (obj_type IS NULL OR obj_type IN ('ITEM', 'CUST', 'EQUIP', 'ASSET', 'LOT'));

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
        IF NEW.status <> OLD.status AND NOT (
               (OLD.status = 'OPEN'        AND NEW.status IN ('IN_PROGRESS', 'RESOLVED'))
            OR (OLD.status = 'IN_PROGRESS' AND NEW.status IN ('OPEN', 'RESOLVED'))
            OR (OLD.status = 'RESOLVED'    AND NEW.status IN ('CLOSED', 'IN_PROGRESS'))) THEN
            RAISE EXCEPTION 'issue status % -> % is not allowed', OLD.status, NEW.status
                USING ERRCODE = '55000', CONSTRAINT = 'ck_pms_issue_transition';
        END IF;
    ELSIF NEW.status <> 'OPEN' THEN
        RAISE EXCEPTION 'issue starts as OPEN' USING ERRCODE = '55000', CONSTRAINT = 'ck_pms_issue_transition';
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
