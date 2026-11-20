-- =====================================================================
-- P6 APQP A3 1차 (docs/41 11장, 결정 L1~L7 — 2026-10-23 사용자: 모두 추천안)
-- 임시 번호(docs/29 2.2) — 상위 세션이 병합 때 파일 이름만 바꾼다
--
-- A3-1 PMS 산출물 ← APQP 확정: pms_deliverable.ref_type 에 APQP_FMEA·APQP_CP(ref_id = 리비전 id, L2), 이력 LINK 행에 fmea_id·cp_id.
--      승인 = 연결한 리비전이 한 번이라도 확정됨(RELEASED·OBSOLETE) — PMS 가 APQP 계약 ApqpLookup 으로 다시 읽어 반영(DeliverableDocSync)
-- A3-7 결재: 정책 APQP_RELEASE_APPROVAL(기본 N, L6) = Y 면 확정 대신 상신 → 상태 IN_APPROVAL(내용 고정) → 승인 = 확정, 반려·회수 = 작성 중.
--      "작성 중 리비전 하나" 규칙에 결재 중도 넣는다(같은 번호에 작성 중·결재 중 합쳐 하나)
-- A3-2 ① 라우팅 새 리비전 재검토 표시는 조회 계산(표 변경 없음)
-- 남의 표: pms_deliverable·pms_deliverable_hist(PMS — 이 세션이 맡는 모듈)만. QMS·PLM 표 변경 없음
-- =====================================================================

INSERT INTO sys_code (grp_cd, code, code_nm_key, sort_no) VALUES
    ('APQP_FMEA_STATUS', 'IN_APPROVAL', 'code.APQP_FMEA_STATUS.IN_APPROVAL', 2);
UPDATE sys_code SET sort_no = 3 WHERE grp_cd = 'APQP_FMEA_STATUS' AND code = 'RELEASED';
UPDATE sys_code SET sort_no = 4 WHERE grp_cd = 'APQP_FMEA_STATUS' AND code = 'OBSOLETE';

INSERT INTO sys_policy_def (policy_key, value_type, default_value, allowed_values, min_value, max_value, plant_scope_yn, desc_key) VALUES
    ('APQP_RELEASE_APPROVAL', 'BOOL', 'N', NULL, NULL, NULL, 'N', 'policy.APQP_RELEASE_APPROVAL');

-- ---------------------------------------------------------------------
-- 1. 결재 상태 — PFMEA · 관리계획서 머리
-- ---------------------------------------------------------------------
-- IN_APPROVAL(11자)이 들어가게 상태 칸을 넓힌다
ALTER TABLE apqp_fmea ALTER COLUMN status TYPE varchar(12);
ALTER TABLE apqp_cp ALTER COLUMN status TYPE varchar(12);
ALTER TABLE apqp_fmea ADD COLUMN apr_id bigint;
COMMENT ON COLUMN apqp_fmea.apr_id IS '마지막 결재 요청(apr_request) — 정책 APQP_RELEASE_APPROVAL = Y 일 때 상신';
ALTER TABLE apqp_fmea DROP CONSTRAINT ck_apqp_fmea_status;
ALTER TABLE apqp_fmea ADD CONSTRAINT ck_apqp_fmea_status CHECK (status IN ('DRAFT', 'IN_APPROVAL', 'RELEASED', 'OBSOLETE'));
ALTER TABLE apqp_fmea DROP CONSTRAINT ck_apqp_fmea_released;
ALTER TABLE apqp_fmea ADD CONSTRAINT ck_apqp_fmea_released
    CHECK (status IN ('DRAFT', 'IN_APPROVAL') OR (released_at IS NOT NULL AND ap_set_id IS NOT NULL));
DROP INDEX uq_apqp_fmea_one_draft;
CREATE UNIQUE INDEX uq_apqp_fmea_one_draft ON apqp_fmea (comp_cd, fmea_no) WHERE status IN ('DRAFT', 'IN_APPROVAL');

ALTER TABLE apqp_cp ADD COLUMN apr_id bigint;
COMMENT ON COLUMN apqp_cp.apr_id IS '마지막 결재 요청(apr_request) — 정책 APQP_RELEASE_APPROVAL = Y 일 때 상신';
ALTER TABLE apqp_cp DROP CONSTRAINT ck_apqp_cp_status;
ALTER TABLE apqp_cp ADD CONSTRAINT ck_apqp_cp_status CHECK (status IN ('DRAFT', 'IN_APPROVAL', 'RELEASED', 'OBSOLETE'));
ALTER TABLE apqp_cp DROP CONSTRAINT ck_apqp_cp_released;
ALTER TABLE apqp_cp ADD CONSTRAINT ck_apqp_cp_released CHECK (status IN ('DRAFT', 'IN_APPROVAL') OR released_at IS NOT NULL);
DROP INDEX uq_apqp_cp_one_draft;
CREATE UNIQUE INDEX uq_apqp_cp_one_draft ON apqp_cp (comp_cd, cp_no) WHERE status IN ('DRAFT', 'IN_APPROVAL');

-- PFMEA 머리 — V145 와 같고, 결재 중(IN_APPROVAL)은 내용 고정·DRAFT(반려·회수)·RELEASED(승인)로만
CREATE OR REPLACE FUNCTION hb_guard_apqp_fmea() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        IF OLD.status <> 'DRAFT' THEN
            RAISE EXCEPTION 'released fmea % cannot be deleted', OLD.fmea_id USING ERRCODE = '55000', CONSTRAINT = 'ck_apqp_fmea_fixed';
        END IF;
        RETURN OLD;
    END IF;
    IF TG_OP = 'UPDATE' THEN
        -- 확정본: 상태 RELEASED → OBSOLETE 만, 또는 상태 그대로 row_ver 만(확정본 조치 진행 때 머리 낡음 표시) — 나머지 칸 그대로
        IF OLD.status IN ('RELEASED', 'OBSOLETE') THEN
            IF NOT (NEW.status = OLD.status OR (OLD.status = 'RELEASED' AND NEW.status = 'OBSOLETE'))
               OR (to_jsonb(NEW) - ARRAY['status', 'row_ver', 'updated_at', 'updated_by'])
                  IS DISTINCT FROM (to_jsonb(OLD) - ARRAY['status', 'row_ver', 'updated_at', 'updated_by']) THEN
                RAISE EXCEPTION 'fmea % is %', OLD.fmea_id, OLD.status USING ERRCODE = '55000', CONSTRAINT = 'ck_apqp_fmea_fixed';
            END IF;
            RETURN NEW;
        END IF;
        -- 결재 중: 내용 그대로, 승인 = RELEASED(확정 칸), 반려·회수 = DRAFT
        IF OLD.status = 'IN_APPROVAL' THEN
            IF NEW.status NOT IN ('IN_APPROVAL', 'DRAFT', 'RELEASED')
               OR (to_jsonb(NEW) - ARRAY['status', 'row_ver', 'updated_at', 'updated_by', 'released_at', 'released_by'])
                  IS DISTINCT FROM (to_jsonb(OLD) - ARRAY['status', 'row_ver', 'updated_at', 'updated_by', 'released_at', 'released_by']) THEN
                RAISE EXCEPTION 'fmea % is IN_APPROVAL', OLD.fmea_id USING ERRCODE = '55000', CONSTRAINT = 'ck_apqp_fmea_fixed';
            END IF;
        ELSIF NEW.status NOT IN ('DRAFT', 'IN_APPROVAL', 'RELEASED') OR NEW.fmea_no <> OLD.fmea_no OR NEW.rev <> OLD.rev
           OR NEW.comp_cd <> OLD.comp_cd THEN
            RAISE EXCEPTION 'fmea % transition', OLD.fmea_id USING ERRCODE = '55000', CONSTRAINT = 'ck_apqp_fmea_transition';
        END IF;
        IF NEW.status IN ('IN_APPROVAL', 'RELEASED') AND NEW.status <> OLD.status
           AND EXISTS (SELECT 1 FROM apqp_fmea_risk r WHERE r.fmea_id = NEW.fmea_id AND r.ap IS NULL) THEN
            RAISE EXCEPTION 'fmea % has risks without ap', NEW.fmea_id USING ERRCODE = '23514', CONSTRAINT = 'ck_apqp_fmea_risk_ap';
        END IF;
        IF OLD.status = 'IN_APPROVAL' THEN
            RETURN NEW;
        END IF;
    ELSIF NEW.status <> 'DRAFT' THEN
        RAISE EXCEPTION 'fmea starts as DRAFT' USING ERRCODE = '55000', CONSTRAINT = 'ck_apqp_fmea_transition';
    END IF;
    -- 같은 회사(FK 는 RLS 를 무시) + 라우팅은 머리 품목의 것
    IF NOT EXISTS (SELECT 1 FROM mst_item x WHERE x.item_id = NEW.item_id AND x.comp_cd = NEW.comp_cd) THEN
        RAISE EXCEPTION 'item % is not of company %', NEW.item_id, NEW.comp_cd USING ERRCODE = '23503', CONSTRAINT = 'fk_apqp_fmea_item_comp';
    END IF;
    IF NEW.routing_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM mst_routing x WHERE x.routing_id = NEW.routing_id
                                                     AND x.item_id = NEW.item_id AND x.comp_cd = NEW.comp_cd) THEN
        RAISE EXCEPTION 'routing % is not of item %', NEW.routing_id, NEW.item_id USING ERRCODE = '23503', CONSTRAINT = 'fk_apqp_fmea_routing_item';
    END IF;
    IF TG_OP = 'UPDATE' AND NEW.routing_id IS DISTINCT FROM OLD.routing_id
       AND EXISTS (SELECT 1 FROM apqp_fmea_node n WHERE n.fmea_id = NEW.fmea_id AND n.step_id IS NOT NULL) THEN
        RAISE EXCEPTION 'fmea % has steps of the routing', NEW.fmea_id USING ERRCODE = '23514', CONSTRAINT = 'ck_apqp_fmea_routing_used';
    END IF;
    IF NEW.proj_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM pms_project x WHERE x.proj_id = NEW.proj_id AND x.comp_cd = NEW.comp_cd) THEN
        RAISE EXCEPTION 'project % is not of company %', NEW.proj_id, NEW.comp_cd USING ERRCODE = '23503', CONSTRAINT = 'fk_apqp_fmea_proj_comp';
    END IF;
    IF NEW.ap_set_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM apqp_ap_set x WHERE x.set_id = NEW.ap_set_id AND x.comp_cd = NEW.comp_cd) THEN
        RAISE EXCEPTION 'ap set % is not of company %', NEW.ap_set_id, NEW.comp_cd USING ERRCODE = '23503', CONSTRAINT = 'fk_apqp_fmea_ap_set_comp';
    END IF;
    IF NEW.prev_fmea_id IS NOT NULL AND TG_OP = 'INSERT'
       AND NOT EXISTS (SELECT 1 FROM apqp_fmea x WHERE x.fmea_id = NEW.prev_fmea_id AND x.comp_cd = NEW.comp_cd AND x.fmea_no = NEW.fmea_no) THEN
        RAISE EXCEPTION 'previous fmea % is not of %', NEW.prev_fmea_id, NEW.fmea_no USING ERRCODE = '23503', CONSTRAINT = 'fk_apqp_fmea_prev_no';
    END IF;
    RETURN NEW;
END
$$;

-- 하위 표 공통 — V145 와 같고, 확정본 조치 진행을 막는 "같은 번호의 작성 중" 에 결재 중도 넣는다
CREATE OR REPLACE FUNCTION hb_apqp_fmea_editable(p_fmea bigint, p_comp varchar, p_action boolean) RETURNS void
    LANGUAGE plpgsql
AS $$
DECLARE
    v_status varchar(12);
    v_no     varchar(30);
BEGIN
    SELECT f.status, f.fmea_no INTO v_status, v_no FROM apqp_fmea f WHERE f.fmea_id = p_fmea AND f.comp_cd = p_comp;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'fmea % is not of company %', p_fmea, p_comp USING ERRCODE = '23503', CONSTRAINT = 'fk_apqp_fmea_child_comp';
    END IF;
    IF v_status = 'DRAFT' THEN
        RETURN;
    END IF;
    IF p_action AND v_status = 'RELEASED'
       AND NOT EXISTS (SELECT 1 FROM apqp_fmea d WHERE d.comp_cd = p_comp AND d.fmea_no = v_no AND d.status IN ('DRAFT', 'IN_APPROVAL')) THEN
        RETURN;
    END IF;
    RAISE EXCEPTION 'fmea % is %', p_fmea, v_status USING ERRCODE = '55000', CONSTRAINT = 'ck_apqp_fmea_fixed';
END
$$;

-- 관리계획서 머리 — V153 과 같고, 결재 중은 내용 고정·DRAFT·RELEASED 로만. 확정 검사(행 있음·스냅샷)는 결재 상신 때도
CREATE OR REPLACE FUNCTION hb_guard_apqp_cp() RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    v_fmea_item    bigint;
    v_fmea_routing bigint;
    v_fmea_status  varchar(12);
BEGIN
    IF TG_OP = 'DELETE' THEN
        IF OLD.status <> 'DRAFT' THEN
            RAISE EXCEPTION 'released control plan % cannot be deleted', OLD.cp_id USING ERRCODE = '55000', CONSTRAINT = 'ck_apqp_cp_fixed';
        END IF;
        RETURN OLD;
    END IF;
    IF TG_OP = 'UPDATE' THEN
        -- 확정본: RELEASED → OBSOLETE 만(나머지 칸 그대로)
        IF OLD.status IN ('RELEASED', 'OBSOLETE') THEN
            IF NOT (NEW.status = OLD.status OR (OLD.status = 'RELEASED' AND NEW.status = 'OBSOLETE'))
               OR (to_jsonb(NEW) - ARRAY['status', 'row_ver', 'updated_at', 'updated_by'])
                  IS DISTINCT FROM (to_jsonb(OLD) - ARRAY['status', 'row_ver', 'updated_at', 'updated_by']) THEN
                RAISE EXCEPTION 'control plan % is %', OLD.cp_id, OLD.status USING ERRCODE = '55000', CONSTRAINT = 'ck_apqp_cp_fixed';
            END IF;
            RETURN NEW;
        END IF;
        IF OLD.status = 'IN_APPROVAL' THEN
            IF NEW.status NOT IN ('IN_APPROVAL', 'DRAFT', 'RELEASED')
               OR (to_jsonb(NEW) - ARRAY['status', 'row_ver', 'updated_at', 'updated_by', 'released_at', 'released_by'])
                  IS DISTINCT FROM (to_jsonb(OLD) - ARRAY['status', 'row_ver', 'updated_at', 'updated_by', 'released_at', 'released_by']) THEN
                RAISE EXCEPTION 'control plan % is IN_APPROVAL', OLD.cp_id USING ERRCODE = '55000', CONSTRAINT = 'ck_apqp_cp_fixed';
            END IF;
            RETURN NEW;
        END IF;
        IF NEW.status NOT IN ('DRAFT', 'IN_APPROVAL', 'RELEASED') OR NEW.cp_no <> OLD.cp_no OR NEW.rev <> OLD.rev OR NEW.phase <> OLD.phase
           OR NEW.item_id <> OLD.item_id OR NEW.comp_cd <> OLD.comp_cd THEN
            RAISE EXCEPTION 'control plan % transition', OLD.cp_id USING ERRCODE = '55000', CONSTRAINT = 'ck_apqp_cp_transition';
        END IF;
        -- 확정·상신: 행이 하나 이상, 특성을 가리키는 행은 규격 스냅샷이 있어야
        IF NEW.status IN ('IN_APPROVAL', 'RELEASED') THEN
            IF NOT EXISTS (SELECT 1 FROM apqp_cp_line l WHERE l.cp_id = NEW.cp_id) THEN
                RAISE EXCEPTION 'control plan % has no lines', NEW.cp_id USING ERRCODE = '23514', CONSTRAINT = 'ck_apqp_cp_no_lines';
            END IF;
            IF EXISTS (SELECT 1 FROM apqp_cp_line l WHERE l.cp_id = NEW.cp_id AND l.char_id IS NOT NULL AND l.data_type IS NULL) THEN
                RAISE EXCEPTION 'control plan % lines without snapshot', NEW.cp_id USING ERRCODE = '23514', CONSTRAINT = 'ck_apqp_cp_snapshot';
            END IF;
        END IF;
    ELSIF NEW.status <> 'DRAFT' THEN
        RAISE EXCEPTION 'control plan starts as DRAFT' USING ERRCODE = '55000', CONSTRAINT = 'ck_apqp_cp_transition';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM mst_item x WHERE x.item_id = NEW.item_id AND x.comp_cd = NEW.comp_cd) THEN
        RAISE EXCEPTION 'item % is not of company %', NEW.item_id, NEW.comp_cd USING ERRCODE = '23503', CONSTRAINT = 'fk_apqp_cp_item_comp';
    END IF;
    IF NEW.routing_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM mst_routing x WHERE x.routing_id = NEW.routing_id
                                                     AND x.item_id = NEW.item_id AND x.comp_cd = NEW.comp_cd) THEN
        RAISE EXCEPTION 'routing % is not of item %', NEW.routing_id, NEW.item_id USING ERRCODE = '23503', CONSTRAINT = 'fk_apqp_cp_routing_item';
    END IF;
    IF NEW.fmea_id IS NOT NULL THEN
        SELECT f.item_id, f.routing_id, f.status INTO v_fmea_item, v_fmea_routing, v_fmea_status
          FROM apqp_fmea f WHERE f.fmea_id = NEW.fmea_id AND f.comp_cd = NEW.comp_cd;
        IF NOT FOUND OR v_fmea_item <> NEW.item_id THEN
            RAISE EXCEPTION 'fmea % is not of item %', NEW.fmea_id, NEW.item_id USING ERRCODE = '23503', CONSTRAINT = 'fk_apqp_cp_fmea_item';
        END IF;
        IF v_fmea_routing IS DISTINCT FROM NEW.routing_id THEN
            RAISE EXCEPTION 'fmea % routing differs', NEW.fmea_id USING ERRCODE = '23514', CONSTRAINT = 'ck_apqp_cp_fmea_routing';
        END IF;
        IF (TG_OP = 'INSERT' OR NEW.fmea_id IS DISTINCT FROM OLD.fmea_id) AND v_fmea_status <> 'RELEASED' THEN
            RAISE EXCEPTION 'fmea % is %', NEW.fmea_id, v_fmea_status USING ERRCODE = '23514', CONSTRAINT = 'ck_apqp_cp_fmea_released';
        END IF;
    END IF;
    IF TG_OP = 'UPDATE' AND NEW.routing_id IS DISTINCT FROM OLD.routing_id
       AND EXISTS (SELECT 1 FROM apqp_cp_line l WHERE l.cp_id = NEW.cp_id AND l.step_id IS NOT NULL) THEN
        RAISE EXCEPTION 'control plan % has steps of the routing', NEW.cp_id USING ERRCODE = '23514', CONSTRAINT = 'ck_apqp_cp_routing_used';
    END IF;
    IF TG_OP = 'UPDATE' AND NEW.fmea_id IS DISTINCT FROM OLD.fmea_id
       AND EXISTS (SELECT 1 FROM apqp_cp_line l WHERE l.cp_id = NEW.cp_id AND l.risk_id IS NOT NULL) THEN
        RAISE EXCEPTION 'control plan % has risks of the fmea', NEW.cp_id USING ERRCODE = '23514', CONSTRAINT = 'ck_apqp_cp_fmea_used';
    END IF;
    IF NEW.proj_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM pms_project x WHERE x.proj_id = NEW.proj_id AND x.comp_cd = NEW.comp_cd) THEN
        RAISE EXCEPTION 'project % is not of company %', NEW.proj_id, NEW.comp_cd USING ERRCODE = '23503', CONSTRAINT = 'fk_apqp_cp_proj_comp';
    END IF;
    IF NEW.prev_cp_id IS NOT NULL AND TG_OP = 'INSERT'
       AND NOT EXISTS (SELECT 1 FROM apqp_cp x WHERE x.cp_id = NEW.prev_cp_id AND x.comp_cd = NEW.comp_cd AND x.item_id = NEW.item_id) THEN
        RAISE EXCEPTION 'previous control plan % is not of item %', NEW.prev_cp_id, NEW.item_id USING ERRCODE = '23503', CONSTRAINT = 'fk_apqp_cp_prev_item';
    END IF;
    RETURN NEW;
END
$$;

-- ---------------------------------------------------------------------
-- 2. PMS 산출물 ← APQP (A3-1)
-- ---------------------------------------------------------------------
ALTER TABLE pms_deliverable DROP CONSTRAINT ck_pms_deliverable_ref_type;
ALTER TABLE pms_deliverable ADD CONSTRAINT ck_pms_deliverable_ref_type
    CHECK (ref_type IS NULL OR ref_type IN ('FILE', 'QMS_DOC_REV', 'PLM_PPAP', 'APQP_FMEA', 'APQP_CP'));
COMMENT ON COLUMN pms_deliverable.ref_type IS '제출물 종류: FILE(첨부) / QMS_DOC_REV(QMS 문서 리비전, 배포 = 승인, D6) / PLM_PPAP(프로젝트 ECO 의 PPAP, 고객 승인 = 승인, D18·D19) / APQP_FMEA·APQP_CP(PFMEA·관리계획서 리비전, 한 번이라도 확정 = 승인, docs/41 L2)';

-- 연결 대상 = 같은 회사 — V121 과 같고 APQP 두 유형을 더한다
CREATE OR REPLACE FUNCTION hb_check_pms_deliverable_doc() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.ref_type IS NULL OR NOT (TG_OP = 'INSERT' OR NEW.ref_id IS DISTINCT FROM OLD.ref_id OR NEW.ref_type IS DISTINCT FROM OLD.ref_type) THEN
        RETURN NEW;
    END IF;
    IF NEW.ref_type = 'QMS_DOC_REV'
       AND NOT EXISTS (SELECT 1 FROM mst_doc_rev r WHERE r.doc_rev_id = NEW.ref_id AND r.comp_cd = NEW.comp_cd) THEN
        RAISE EXCEPTION 'document revision % is not of company %', NEW.ref_id, NEW.comp_cd
            USING ERRCODE = '23503', CONSTRAINT = 'fk_pms_deliverable_doc_rev';
    END IF;
    IF NEW.ref_type = 'PLM_PPAP'
       AND NOT EXISTS (SELECT 1 FROM plm_ppap x JOIN pms_project p ON p.eco_id = x.eco_id
                        WHERE x.ppap_id = NEW.ref_id AND x.comp_cd = NEW.comp_cd AND p.proj_id = NEW.proj_id) THEN
        RAISE EXCEPTION 'PPAP % is not the package of the ECO of project %', NEW.ref_id, NEW.proj_id
            USING ERRCODE = '23503', CONSTRAINT = 'fk_pms_deliverable_ppap';
    END IF;
    IF NEW.ref_type = 'APQP_FMEA'
       AND NOT EXISTS (SELECT 1 FROM apqp_fmea x WHERE x.fmea_id = NEW.ref_id AND x.comp_cd = NEW.comp_cd) THEN
        RAISE EXCEPTION 'fmea % is not of company %', NEW.ref_id, NEW.comp_cd USING ERRCODE = '23503', CONSTRAINT = 'fk_pms_deliverable_apqp';
    END IF;
    IF NEW.ref_type = 'APQP_CP'
       AND NOT EXISTS (SELECT 1 FROM apqp_cp x WHERE x.cp_id = NEW.ref_id AND x.comp_cd = NEW.comp_cd) THEN
        RAISE EXCEPTION 'control plan % is not of company %', NEW.ref_id, NEW.comp_cd USING ERRCODE = '23503', CONSTRAINT = 'fk_pms_deliverable_apqp';
    END IF;
    RETURN NEW;
END
$$;

ALTER TABLE pms_deliverable_hist ADD COLUMN fmea_id bigint;
ALTER TABLE pms_deliverable_hist ADD COLUMN cp_id bigint;
COMMENT ON COLUMN pms_deliverable_hist.fmea_id IS 'LINK 행: 연결한 PFMEA 리비전(apqp_fmea)';
COMMENT ON COLUMN pms_deliverable_hist.cp_id IS 'LINK 행: 연결한 관리계획서 리비전(apqp_cp)';
-- FK 는 두지 않는다: 작성 중 PFMEA·관리계획서는 지울 수 있고(APQP 가 PMS 표를 모르게), 연결 칸 ref_id 와 같은 방식.
-- 지워진 리비전에 연결된 칸은 PMS 가 계약(ApqpLookup)으로 못 읽어 "없음" 으로 보이고 승인되지 않는다
ALTER TABLE pms_deliverable_hist DROP CONSTRAINT ck_pms_deliverable_hist_doc;
ALTER TABLE pms_deliverable_hist ADD CONSTRAINT ck_pms_deliverable_hist_doc
    CHECK (CASE WHEN action = 'LINK' THEN num_nonnulls(doc_rev_id, ppap_id, fmea_id, cp_id) = 1
                ELSE doc_rev_id IS NULL AND ppap_id IS NULL AND fmea_id IS NULL AND cp_id IS NULL END);
