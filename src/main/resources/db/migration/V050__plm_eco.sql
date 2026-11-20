-- =====================================================================
-- PLM 1단계 설계 변경 (docs/39) — 임시 번호, 병합 때 재번호
--
-- - plm_ecr: 설계 변경 요청 (OPEN → ACCEPTED·REJECTED·CANCELLED)
-- - plm_eco: 설계 변경 지시 (DRAFT → PENDING(결재) → APPROVED → RELEASED, 반려·회수 = DRAFT, 취소)
-- - plm_eco_target: 영향 대상 — 품목 / BOM 작성 중 리비전 / 라우팅 작성 중 리비전. 상신 때 row_ver 고정
-- BOM·라우팅 표는 바꾸지 않는다 — 적용은 서비스가 기존 확정 흐름(BomService·RoutingService.release)을 부른다
-- =====================================================================

INSERT INTO sys_code_grp (grp_cd, grp_nm_key, comp_ext_yn) VALUES
    ('PLM_ECR_STATUS', 'code.grp.PLM_ECR_STATUS', 'N'),
    ('PLM_ECO_STATUS', 'code.grp.PLM_ECO_STATUS', 'N'),
    ('PLM_TARGET_TYPE', 'code.grp.PLM_TARGET_TYPE', 'N'),
    ('PLM_CUST_APR', 'code.grp.PLM_CUST_APR', 'N'),
    ('PLM_CHANGE_REASON', 'code.grp.PLM_CHANGE_REASON', 'Y');
INSERT INTO sys_code (grp_cd, code, code_nm_key, sort_no) VALUES
    ('PLM_ECR_STATUS', 'OPEN',      'code.PLM_ECR_STATUS.OPEN',      1),
    ('PLM_ECR_STATUS', 'ACCEPTED',  'code.PLM_ECR_STATUS.ACCEPTED',  2),
    ('PLM_ECR_STATUS', 'REJECTED',  'code.PLM_ECR_STATUS.REJECTED',  3),
    ('PLM_ECR_STATUS', 'CANCELLED', 'code.PLM_ECR_STATUS.CANCELLED', 4),
    ('PLM_ECO_STATUS', 'DRAFT',     'code.PLM_ECO_STATUS.DRAFT',     1),
    ('PLM_ECO_STATUS', 'PENDING',   'code.PLM_ECO_STATUS.PENDING',   2),
    ('PLM_ECO_STATUS', 'APPROVED',  'code.PLM_ECO_STATUS.APPROVED',  3),
    ('PLM_ECO_STATUS', 'RELEASED',  'code.PLM_ECO_STATUS.RELEASED',  4),
    ('PLM_ECO_STATUS', 'CANCELLED', 'code.PLM_ECO_STATUS.CANCELLED', 5),
    ('PLM_TARGET_TYPE', 'ITEM',     'code.PLM_TARGET_TYPE.ITEM',     1),
    ('PLM_TARGET_TYPE', 'BOM',      'code.PLM_TARGET_TYPE.BOM',      2),
    ('PLM_TARGET_TYPE', 'ROUTING',  'code.PLM_TARGET_TYPE.ROUTING',  3),
    ('PLM_CUST_APR', 'NA',          'code.PLM_CUST_APR.NA',          1),
    ('PLM_CUST_APR', 'PENDING',     'code.PLM_CUST_APR.PENDING',     2),
    ('PLM_CUST_APR', 'APPROVED',    'code.PLM_CUST_APR.APPROVED',    3),
    ('PLM_CUST_APR', 'REJECTED',    'code.PLM_CUST_APR.REJECTED',    4),
    ('PLM_CHANGE_REASON', 'CUSTOMER',   'code.PLM_CHANGE_REASON.CUSTOMER',   1),
    ('PLM_CHANGE_REASON', 'QUALITY',    'code.PLM_CHANGE_REASON.QUALITY',    2),
    ('PLM_CHANGE_REASON', 'COST',       'code.PLM_CHANGE_REASON.COST',       3),
    ('PLM_CHANGE_REASON', 'SUPPLIER',   'code.PLM_CHANGE_REASON.SUPPLIER',   4),
    ('PLM_CHANGE_REASON', 'DESIGN',     'code.PLM_CHANGE_REASON.DESIGN',     5),
    ('PLM_CHANGE_REASON', 'REGULATION', 'code.PLM_CHANGE_REASON.REGULATION', 6),
    ('PLM_CHANGE_REASON', 'OTHER',      'code.PLM_CHANGE_REASON.OTHER',      9);

-- 회사 사용자 확인은 hb_user_of_comp (CMS 마이그레이션에 있음) 를 그대로 쓴다

-- ---------------------------------------------------------------------
-- 1. 설계 변경 지시 (ECR 이 가리키므로 먼저)
-- ---------------------------------------------------------------------
CREATE TABLE plm_eco (
    eco_id                bigint         GENERATED ALWAYS AS IDENTITY,
    comp_cd               varchar(20)    NOT NULL,
    eco_no                varchar(50)    NOT NULL,
    title                 varchar(200)   NOT NULL,
    reason_cd             varchar(30)    NOT NULL,
    description           varchar(4000),
    effective_date        date           NOT NULL,
    owner_id              bigint         NOT NULL,
    cust_approval_req_yn  char(1)        NOT NULL DEFAULT 'N',
    cust_id               bigint,
    cust_approval_status  varchar(10)    NOT NULL DEFAULT 'NA',
    cust_approval_ref     varchar(100),
    cust_approved_on      date,
    status                varchar(10)    NOT NULL DEFAULT 'DRAFT',
    apr_id                bigint,
    submitted_at          timestamptz,
    approved_at           timestamptz,
    released_at           timestamptz,
    released_by           varchar(50),
    cancel_reason         varchar(500),
    row_ver               integer        NOT NULL DEFAULT 0,
    created_at            timestamptz    NOT NULL DEFAULT now(),
    created_by            varchar(50)    NOT NULL,
    updated_at            timestamptz    NOT NULL DEFAULT now(),
    updated_by            varchar(50)    NOT NULL,
    CONSTRAINT pk_plm_eco PRIMARY KEY (eco_id),
    CONSTRAINT fk_plm_eco_comp FOREIGN KEY (comp_cd) REFERENCES sys_comp (comp_cd),
    CONSTRAINT fk_plm_eco_owner FOREIGN KEY (owner_id) REFERENCES sys_user (user_id),
    CONSTRAINT fk_plm_eco_cust FOREIGN KEY (cust_id) REFERENCES mst_cust (cust_id),
    CONSTRAINT fk_plm_eco_apr FOREIGN KEY (apr_id) REFERENCES apr_request (apr_id),
    CONSTRAINT uq_plm_eco_no UNIQUE (comp_cd, eco_no),
    CONSTRAINT ck_plm_eco_title CHECK (length(btrim(title)) > 0),
    CONSTRAINT ck_plm_eco_date CHECK (effective_date BETWEEN DATE '2000-01-01' AND DATE '2999-12-31'),
    CONSTRAINT ck_plm_eco_status CHECK (status IN ('DRAFT', 'PENDING', 'APPROVED', 'RELEASED', 'CANCELLED')),
    CONSTRAINT ck_plm_eco_cust_req CHECK (cust_approval_req_yn IN ('Y', 'N')),
    CONSTRAINT ck_plm_eco_cust_status CHECK (cust_approval_status IN ('NA', 'PENDING', 'APPROVED', 'REJECTED')),
    -- 고객 승인 필요 = 고객 지정 + 상태가 NA 아님, 필요 없음 = 상태 NA·기록 없음
    CONSTRAINT ck_plm_eco_cust CHECK (
        (cust_approval_req_yn = 'Y' AND cust_id IS NOT NULL AND cust_approval_status <> 'NA')
        OR (cust_approval_req_yn = 'N' AND cust_approval_status = 'NA' AND cust_approval_ref IS NULL AND cust_approved_on IS NULL)),
    CONSTRAINT ck_plm_eco_cust_approved CHECK (cust_approval_status <> 'APPROVED'
        OR (cust_approval_ref IS NOT NULL AND length(btrim(cust_approval_ref)) > 0 AND cust_approved_on IS NOT NULL)),
    CONSTRAINT ck_plm_eco_cancel CHECK ((status = 'CANCELLED') = (cancel_reason IS NOT NULL AND length(btrim(cancel_reason)) > 0)),
    CONSTRAINT ck_plm_eco_released CHECK ((status = 'RELEASED') = (released_at IS NOT NULL)),
    CONSTRAINT ck_plm_eco_approved CHECK (status NOT IN ('APPROVED', 'RELEASED') OR approved_at IS NOT NULL)
);
COMMENT ON TABLE plm_eco IS '설계 변경 지시 ECO (docs/39). 내용은 작성 중에만 수정, 결재 = apr_request(PLM_ECO), 적용 = 대상 리비전 확정';
COMMENT ON COLUMN plm_eco.effective_date IS '유효일 — 적용 때 대상 BOM·라우팅 리비전의 valid_from 이 된다';
CREATE INDEX ix_plm_eco_status ON plm_eco (comp_cd, status, eco_id);

CREATE FUNCTION hb_guard_plm_eco() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF NOT hb_user_of_comp(NEW.comp_cd, NEW.owner_id)
       OR (NEW.cust_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM mst_cust c WHERE c.cust_id = NEW.cust_id
                                                      AND c.comp_cd = NEW.comp_cd AND c.cust_type IN ('SALES', 'BOTH')))
       OR (NEW.apr_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM apr_request a WHERE a.apr_id = NEW.apr_id
                                                     AND a.comp_cd = NEW.comp_cd AND a.doc_type = 'PLM_ECO'
                                                     AND a.biz_key = NEW.eco_id::text)) THEN
        RAISE EXCEPTION 'ECO references are not of company %', NEW.comp_cd
            USING ERRCODE = '23503', CONSTRAINT = 'fk_plm_eco_ref_comp';
    END IF;
    IF (TG_OP = 'INSERT' OR NEW.reason_cd IS DISTINCT FROM OLD.reason_cd)
       AND NOT hb_code_usable(NEW.comp_cd, 'PLM_CHANGE_REASON', NEW.reason_cd) THEN
        RAISE EXCEPTION 'reason % is not a usable code', NEW.reason_cd
            USING ERRCODE = '23503', CONSTRAINT = 'fk_plm_eco_reason';
    END IF;
    IF TG_OP = 'INSERT' THEN
        IF NEW.status <> 'DRAFT' THEN
            RAISE EXCEPTION 'new ECO must be DRAFT' USING ERRCODE = '55000', CONSTRAINT = 'ck_plm_eco_transition';
        END IF;
        RETURN NEW;
    END IF;
    IF NEW.eco_no <> OLD.eco_no THEN
        RAISE EXCEPTION 'ECO number is fixed' USING ERRCODE = '55000', CONSTRAINT = 'ck_plm_eco_fixed';
    END IF;
    IF OLD.status <> NEW.status AND NOT (
           (OLD.status = 'DRAFT' AND NEW.status IN ('PENDING', 'CANCELLED'))
        OR (OLD.status = 'PENDING' AND NEW.status IN ('APPROVED', 'DRAFT'))
        OR (OLD.status = 'APPROVED' AND NEW.status IN ('RELEASED', 'CANCELLED'))) THEN
        RAISE EXCEPTION 'ECO status % -> % is not allowed', OLD.status, NEW.status
            USING ERRCODE = '55000', CONSTRAINT = 'ck_plm_eco_transition';
    END IF;
    -- 결재받은 내용은 고정: 작성 중이 아니면 제목·사유·설명·유효일·담당·고객 지정을 바꾸지 않는다
    IF OLD.status <> 'DRAFT' AND (
           NEW.title <> OLD.title OR NEW.reason_cd <> OLD.reason_cd OR NEW.description IS DISTINCT FROM OLD.description
        OR NEW.effective_date <> OLD.effective_date OR NEW.owner_id <> OLD.owner_id
        OR NEW.cust_approval_req_yn <> OLD.cust_approval_req_yn OR NEW.cust_id IS DISTINCT FROM OLD.cust_id) THEN
        RAISE EXCEPTION 'ECO % is not DRAFT — content is fixed', OLD.eco_id
            USING ERRCODE = '55000', CONSTRAINT = 'ck_plm_eco_fixed';
    END IF;
    -- 고객 승인 기록은 적용 전(작성 중·결재 중·승인됨)까지만
    IF OLD.status IN ('RELEASED', 'CANCELLED') AND (
           NEW.cust_approval_status <> OLD.cust_approval_status OR NEW.cust_approval_ref IS DISTINCT FROM OLD.cust_approval_ref
        OR NEW.cust_approved_on IS DISTINCT FROM OLD.cust_approved_on) THEN
        RAISE EXCEPTION 'ECO % is closed', OLD.eco_id USING ERRCODE = '55000', CONSTRAINT = 'ck_plm_eco_fixed';
    END IF;
    -- 고객 승인 게이트 (docs/02 2.3 — 고객 승인 전 변경 적용 금지)
    IF NEW.status = 'RELEASED' AND OLD.status <> 'RELEASED'
       AND NEW.cust_approval_req_yn = 'Y' AND NEW.cust_approval_status <> 'APPROVED' THEN
        RAISE EXCEPTION 'ECO % needs customer approval before release', OLD.eco_id
            USING ERRCODE = '55000', CONSTRAINT = 'ck_plm_eco_cust_gate';
    END IF;
    RETURN NEW;
END
$$;
CREATE TRIGGER tg_plm_eco_guard BEFORE INSERT OR UPDATE ON plm_eco FOR EACH ROW EXECUTE FUNCTION hb_guard_plm_eco();
CREATE TRIGGER tg_plm_eco_no_delete BEFORE DELETE ON plm_eco FOR EACH ROW EXECUTE FUNCTION hb_forbid_delete_master();

-- ---------------------------------------------------------------------
-- 2. 영향 대상
-- ---------------------------------------------------------------------
CREATE TABLE plm_eco_target (
    target_id     bigint         GENERATED ALWAYS AS IDENTITY,
    comp_cd       varchar(20)    NOT NULL,
    eco_id        bigint         NOT NULL,
    seq           integer        NOT NULL,
    target_type   varchar(10)    NOT NULL,
    item_id       bigint         NOT NULL,
    bom_id        bigint,
    routing_id    bigint,
    change_desc   varchar(1000),
    base_row_ver  integer,
    row_ver       integer        NOT NULL DEFAULT 0,
    created_at    timestamptz    NOT NULL DEFAULT now(),
    created_by    varchar(50)    NOT NULL,
    updated_at    timestamptz    NOT NULL DEFAULT now(),
    updated_by    varchar(50)    NOT NULL,
    CONSTRAINT pk_plm_eco_target PRIMARY KEY (target_id),
    CONSTRAINT fk_plm_eco_target_eco FOREIGN KEY (eco_id) REFERENCES plm_eco (eco_id),
    CONSTRAINT fk_plm_eco_target_item FOREIGN KEY (item_id) REFERENCES mst_item (item_id),
    CONSTRAINT fk_plm_eco_target_bom FOREIGN KEY (bom_id) REFERENCES mst_bom (bom_id),
    CONSTRAINT fk_plm_eco_target_routing FOREIGN KEY (routing_id) REFERENCES mst_routing (routing_id),
    CONSTRAINT uq_plm_eco_target_seq UNIQUE (eco_id, seq) DEFERRABLE INITIALLY DEFERRED,
    CONSTRAINT ck_plm_eco_target_seq CHECK (seq BETWEEN 1 AND 9999),
    CONSTRAINT ck_plm_eco_target_type CHECK (
        (target_type = 'ITEM' AND bom_id IS NULL AND routing_id IS NULL)
        OR (target_type = 'BOM' AND bom_id IS NOT NULL AND routing_id IS NULL)
        OR (target_type = 'ROUTING' AND routing_id IS NOT NULL AND bom_id IS NULL))
);
COMMENT ON TABLE plm_eco_target IS 'ECO 영향 대상 (docs/39 3장). BOM·라우팅은 작성 중 리비전, base_row_ver = 상신 때 고정한 리비전 row_ver';
CREATE UNIQUE INDEX uq_plm_eco_target_item ON plm_eco_target (eco_id, item_id) WHERE target_type = 'ITEM';
CREATE UNIQUE INDEX uq_plm_eco_target_bom ON plm_eco_target (eco_id, bom_id) WHERE bom_id IS NOT NULL;
CREATE UNIQUE INDEX uq_plm_eco_target_routing ON plm_eco_target (eco_id, routing_id) WHERE routing_id IS NOT NULL;
CREATE INDEX ix_plm_eco_target_eco ON plm_eco_target (comp_cd, eco_id);
CREATE INDEX ix_plm_eco_target_item_ref ON plm_eco_target (comp_cd, item_id);

CREATE FUNCTION hb_guard_plm_eco_target() RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    v_row     plm_eco_target;
    v_status  varchar(10);
    v_item    bigint;
    v_rstatus varchar(10);
BEGIN
    v_row := CASE WHEN TG_OP = 'DELETE' THEN OLD ELSE NEW END;
    -- 대상은 ECO 가 작성 중일 때만 바꾼다. 예외: 상신 때 base_row_ver 고정(DRAFT→PENDING 같은 트랜잭션)과 반려 때 해제
    SELECT e.status INTO v_status FROM plm_eco e WHERE e.eco_id = v_row.eco_id AND e.comp_cd = v_row.comp_cd;
    IF v_status IS NULL THEN
        RAISE EXCEPTION 'ECO % is not of company %', v_row.eco_id, v_row.comp_cd
            USING ERRCODE = '23503', CONSTRAINT = 'fk_plm_eco_target_comp';
    END IF;
    IF v_status <> 'DRAFT' AND NOT (TG_OP = 'UPDATE' AND v_status IN ('PENDING', 'DRAFT')
            AND NEW.eco_id = OLD.eco_id AND NEW.seq = OLD.seq AND NEW.target_type = OLD.target_type
            AND NEW.item_id = OLD.item_id AND NEW.bom_id IS NOT DISTINCT FROM OLD.bom_id
            AND NEW.routing_id IS NOT DISTINCT FROM OLD.routing_id
            AND NEW.change_desc IS NOT DISTINCT FROM OLD.change_desc) THEN
        RAISE EXCEPTION 'targets of % ECO % cannot be changed', v_status, v_row.eco_id
            USING ERRCODE = '55000', CONSTRAINT = 'ck_plm_eco_target_fixed';
    END IF;
    IF TG_OP = 'DELETE' THEN
        RETURN OLD;
    END IF;
    IF TG_OP = 'UPDATE' AND NEW.eco_id <> OLD.eco_id THEN
        RAISE EXCEPTION 'target ECO is fixed' USING ERRCODE = '55000', CONSTRAINT = 'ck_plm_eco_target_fixed';
    END IF;
    IF NOT hb_item_of_comp(NEW.comp_cd, NEW.item_id) THEN
        RAISE EXCEPTION 'item % is not of company %', NEW.item_id, NEW.comp_cd
            USING ERRCODE = '23503', CONSTRAINT = 'fk_plm_eco_target_comp';
    END IF;
    IF NEW.bom_id IS NOT NULL AND (TG_OP = 'INSERT' OR NEW.bom_id IS DISTINCT FROM OLD.bom_id) THEN
        -- 리비전 행을 잠가 같은 리비전을 두 ECO 에 동시에 넣는 것을 직렬화
        SELECT b.item_id, b.status INTO v_item, v_rstatus FROM mst_bom b
         WHERE b.bom_id = NEW.bom_id AND b.comp_cd = NEW.comp_cd FOR UPDATE;
        IF v_item IS NULL THEN
            RAISE EXCEPTION 'BOM % is not of company %', NEW.bom_id, NEW.comp_cd
                USING ERRCODE = '23503', CONSTRAINT = 'fk_plm_eco_target_comp';
        END IF;
        IF v_item <> NEW.item_id THEN
            RAISE EXCEPTION 'BOM % is not of item %', NEW.bom_id, NEW.item_id
                USING ERRCODE = '23514', CONSTRAINT = 'ck_plm_eco_target_item';
        END IF;
        IF v_rstatus <> 'DRAFT' THEN
            RAISE EXCEPTION 'BOM % is not a draft revision', NEW.bom_id
                USING ERRCODE = '55000', CONSTRAINT = 'ck_plm_eco_target_draft';
        END IF;
        IF EXISTS (SELECT 1 FROM plm_eco_target t JOIN plm_eco e ON e.eco_id = t.eco_id
                    WHERE t.bom_id = NEW.bom_id AND t.target_id <> coalesce(NEW.target_id, -1)
                      AND e.status IN ('DRAFT', 'PENDING', 'APPROVED')) THEN
            RAISE EXCEPTION 'BOM % is already in an open ECO', NEW.bom_id
                USING ERRCODE = '23505', CONSTRAINT = 'uq_plm_eco_target_open';
        END IF;
    END IF;
    IF NEW.routing_id IS NOT NULL AND (TG_OP = 'INSERT' OR NEW.routing_id IS DISTINCT FROM OLD.routing_id) THEN
        SELECT r.item_id, r.status INTO v_item, v_rstatus FROM mst_routing r
         WHERE r.routing_id = NEW.routing_id AND r.comp_cd = NEW.comp_cd FOR UPDATE;
        IF v_item IS NULL THEN
            RAISE EXCEPTION 'routing % is not of company %', NEW.routing_id, NEW.comp_cd
                USING ERRCODE = '23503', CONSTRAINT = 'fk_plm_eco_target_comp';
        END IF;
        IF v_item <> NEW.item_id THEN
            RAISE EXCEPTION 'routing % is not of item %', NEW.routing_id, NEW.item_id
                USING ERRCODE = '23514', CONSTRAINT = 'ck_plm_eco_target_item';
        END IF;
        IF v_rstatus <> 'DRAFT' THEN
            RAISE EXCEPTION 'routing % is not a draft revision', NEW.routing_id
                USING ERRCODE = '55000', CONSTRAINT = 'ck_plm_eco_target_draft';
        END IF;
        IF EXISTS (SELECT 1 FROM plm_eco_target t JOIN plm_eco e ON e.eco_id = t.eco_id
                    WHERE t.routing_id = NEW.routing_id AND t.target_id <> coalesce(NEW.target_id, -1)
                      AND e.status IN ('DRAFT', 'PENDING', 'APPROVED')) THEN
            RAISE EXCEPTION 'routing % is already in an open ECO', NEW.routing_id
                USING ERRCODE = '23505', CONSTRAINT = 'uq_plm_eco_target_open';
        END IF;
    END IF;
    RETURN NEW;
END
$$;
CREATE TRIGGER tg_plm_eco_target_guard BEFORE INSERT OR UPDATE OR DELETE ON plm_eco_target
    FOR EACH ROW EXECUTE FUNCTION hb_guard_plm_eco_target();

-- ---------------------------------------------------------------------
-- 3. 설계 변경 요청
-- ---------------------------------------------------------------------
CREATE TABLE plm_ecr (
    ecr_id            bigint         GENERATED ALWAYS AS IDENTITY,
    comp_cd           varchar(20)    NOT NULL,
    ecr_no            varchar(50)    NOT NULL,
    title             varchar(200)   NOT NULL,
    reason_cd         varchar(30)    NOT NULL,
    description       varchar(4000),
    item_id           bigint,
    requester_id      bigint         NOT NULL,
    status            varchar(10)    NOT NULL DEFAULT 'OPEN',
    eco_id            bigint,
    decided_at        timestamptz,
    decided_by        varchar(50),
    decision_comment  varchar(1000),
    row_ver           integer        NOT NULL DEFAULT 0,
    created_at        timestamptz    NOT NULL DEFAULT now(),
    created_by        varchar(50)    NOT NULL,
    updated_at        timestamptz    NOT NULL DEFAULT now(),
    updated_by        varchar(50)    NOT NULL,
    CONSTRAINT pk_plm_ecr PRIMARY KEY (ecr_id),
    CONSTRAINT fk_plm_ecr_comp FOREIGN KEY (comp_cd) REFERENCES sys_comp (comp_cd),
    CONSTRAINT fk_plm_ecr_item FOREIGN KEY (item_id) REFERENCES mst_item (item_id),
    CONSTRAINT fk_plm_ecr_requester FOREIGN KEY (requester_id) REFERENCES sys_user (user_id),
    CONSTRAINT fk_plm_ecr_eco FOREIGN KEY (eco_id) REFERENCES plm_eco (eco_id),
    CONSTRAINT uq_plm_ecr_no UNIQUE (comp_cd, ecr_no),
    CONSTRAINT ck_plm_ecr_title CHECK (length(btrim(title)) > 0),
    CONSTRAINT ck_plm_ecr_status CHECK (status IN ('OPEN', 'ACCEPTED', 'REJECTED', 'CANCELLED')),
    CONSTRAINT ck_plm_ecr_eco CHECK ((status = 'ACCEPTED') = (eco_id IS NOT NULL)),
    CONSTRAINT ck_plm_ecr_decided CHECK ((status = 'OPEN') = (decided_at IS NULL)),
    CONSTRAINT ck_plm_ecr_reject CHECK (status <> 'REJECTED'
        OR (decision_comment IS NOT NULL AND length(btrim(decision_comment)) > 0))
);
COMMENT ON TABLE plm_ecr IS '설계 변경 요청 ECR (docs/39). 접수 = ECO 에 묶임, 닫힌 요청은 불변';
CREATE INDEX ix_plm_ecr_status ON plm_ecr (comp_cd, status, ecr_id);
CREATE INDEX ix_plm_ecr_eco ON plm_ecr (comp_cd, eco_id) WHERE eco_id IS NOT NULL;

CREATE FUNCTION hb_guard_plm_ecr() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF NOT hb_user_of_comp(NEW.comp_cd, NEW.requester_id)
       OR (NEW.item_id IS NOT NULL AND NOT hb_item_of_comp(NEW.comp_cd, NEW.item_id))
       OR (NEW.eco_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM plm_eco e WHERE e.eco_id = NEW.eco_id
                                                     AND e.comp_cd = NEW.comp_cd)) THEN
        RAISE EXCEPTION 'ECR references are not of company %', NEW.comp_cd
            USING ERRCODE = '23503', CONSTRAINT = 'fk_plm_ecr_ref_comp';
    END IF;
    IF (TG_OP = 'INSERT' OR NEW.reason_cd IS DISTINCT FROM OLD.reason_cd)
       AND NOT hb_code_usable(NEW.comp_cd, 'PLM_CHANGE_REASON', NEW.reason_cd) THEN
        RAISE EXCEPTION 'reason % is not a usable code', NEW.reason_cd
            USING ERRCODE = '23503', CONSTRAINT = 'fk_plm_ecr_reason';
    END IF;
    IF TG_OP = 'INSERT' THEN
        IF NEW.status <> 'OPEN' THEN
            RAISE EXCEPTION 'new ECR must be OPEN' USING ERRCODE = '55000', CONSTRAINT = 'ck_plm_ecr_transition';
        END IF;
        RETURN NEW;
    END IF;
    IF OLD.status <> 'OPEN' THEN
        RAISE EXCEPTION 'ECR % is closed', OLD.ecr_id USING ERRCODE = '55000', CONSTRAINT = 'ck_plm_ecr_transition';
    END IF;
    IF NEW.ecr_no <> OLD.ecr_no OR NEW.requester_id <> OLD.requester_id THEN
        RAISE EXCEPTION 'ECR number and requester are fixed' USING ERRCODE = '55000', CONSTRAINT = 'ck_plm_ecr_transition';
    END IF;
    -- 접수는 작성 중 ECO 에만 묶는다
    IF NEW.status = 'ACCEPTED' AND NOT EXISTS (SELECT 1 FROM plm_eco e WHERE e.eco_id = NEW.eco_id AND e.status = 'DRAFT') THEN
        RAISE EXCEPTION 'ECR can be accepted only into a DRAFT ECO'
            USING ERRCODE = '55000', CONSTRAINT = 'ck_plm_ecr_eco_draft';
    END IF;
    RETURN NEW;
END
$$;
CREATE TRIGGER tg_plm_ecr_guard BEFORE INSERT OR UPDATE ON plm_ecr FOR EACH ROW EXECUTE FUNCTION hb_guard_plm_ecr();
CREATE TRIGGER tg_plm_ecr_no_delete BEFORE DELETE ON plm_ecr FOR EACH ROW EXECUTE FUNCTION hb_forbid_delete_master();

-- ---------------------------------------------------------------------
-- 4. 감사 · RLS · 권한 · 화면 · 보존
-- ---------------------------------------------------------------------
CREATE TRIGGER tg_audit AFTER INSERT OR UPDATE OR DELETE ON plm_ecr FOR EACH ROW EXECUTE FUNCTION hb_audit('ecr_id', '', '');
CREATE TRIGGER tg_audit AFTER INSERT OR UPDATE OR DELETE ON plm_eco FOR EACH ROW EXECUTE FUNCTION hb_audit('eco_id', '', '');
CREATE TRIGGER tg_audit AFTER INSERT OR UPDATE OR DELETE ON plm_eco_target FOR EACH ROW EXECUTE FUNCTION hb_audit('target_id', '', '');

ALTER TABLE plm_ecr ENABLE ROW LEVEL SECURITY;
ALTER TABLE plm_ecr FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON plm_ecr USING (comp_cd = hb_current_comp()) WITH CHECK (comp_cd = hb_current_comp());
ALTER TABLE plm_eco ENABLE ROW LEVEL SECURITY;
ALTER TABLE plm_eco FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON plm_eco USING (comp_cd = hb_current_comp()) WITH CHECK (comp_cd = hb_current_comp());
ALTER TABLE plm_eco_target ENABLE ROW LEVEL SECURITY;
ALTER TABLE plm_eco_target FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON plm_eco_target USING (comp_cd = hb_current_comp()) WITH CHECK (comp_cd = hb_current_comp());
-- 머리는 지우지 않는다(취소·반려 상태로). 대상 행은 작성 중 ECO 에서만 지울 수 있다(트리거)
REVOKE DELETE, TRUNCATE ON plm_ecr, plm_eco FROM ${app_role};
REVOKE TRUNCATE ON plm_eco_target FROM ${app_role};

INSERT INTO sys_prog (prog_cd, prog_nm_key, view_path, module_cd, actions, sort_no) VALUES
    ('plm-ecr', 'prog.plmEcr', 'plm/ecr-mgt', 'PLM', ARRAY['SEARCH','SAVE','APPROVE']::varchar(20)[], 985),
    ('plm-eco', 'prog.plmEco', 'plm/eco-mgt', 'PLM', ARRAY['SEARCH','SAVE','APPROVE']::varchar(20)[], 986);
INSERT INTO sys_retention_table (table_nm, class_cd) VALUES
    ('plm_ecr', 'MASTER'), ('plm_eco', 'MASTER'), ('plm_eco_target', 'MASTER');
