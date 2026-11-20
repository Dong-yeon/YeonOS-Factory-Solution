-- =====================================================================
-- PLM 3b 설계 변경 ↔ 생산 LOT 표시 (docs/39 12·13장) — 임시 번호, 병합 때 재번호
--
-- - plm_eco: 변경 종류(DESIGN·PROCESS_4M·TEMP) + 표시 구간(첫 N LOT / 표시 끝 날짜). 작성 중에만 수정
-- - plm_eco_target: 대상 유형 EQUIP(설비)·PROC(공정) — 4M 변경은 BOM·라우팅 대상이 없을 수 있다
-- - plm_eco_lot: ECO × LOT 변경 표시(원본, 추가만). lot_genealogy.change_id 는 MES 가 적는 사본(3c)
-- MES 표(lot_master·prd_wo·prd_wo_proc·lot_genealogy)는 읽기만 — 잠그지도, FK 를 걸지도 않는다(13.2 잠금 순서)
-- =====================================================================

INSERT INTO sys_code_grp (grp_cd, grp_nm_key, comp_ext_yn) VALUES
    ('PLM_CHANGE_KIND', 'code.grp.PLM_CHANGE_KIND', 'N'),
    ('PLM_MARK_REASON', 'code.grp.PLM_MARK_REASON', 'N');
INSERT INTO sys_code (grp_cd, code, code_nm_key, sort_no) VALUES
    ('PLM_CHANGE_KIND', 'DESIGN',     'code.PLM_CHANGE_KIND.DESIGN',     1),
    ('PLM_CHANGE_KIND', 'PROCESS_4M', 'code.PLM_CHANGE_KIND.PROCESS_4M', 2),
    ('PLM_CHANGE_KIND', 'TEMP',       'code.PLM_CHANGE_KIND.TEMP',       3),
    ('PLM_MARK_REASON', 'FIRST_LOT',  'code.PLM_MARK_REASON.FIRST_LOT',  1),
    ('PLM_MARK_REASON', '4M_WINDOW',  'code.PLM_MARK_REASON.4M_WINDOW',  2),
    ('PLM_MARK_REASON', 'TEMP',       'code.PLM_MARK_REASON.TEMP',       3),
    ('PLM_TARGET_TYPE', 'EQUIP',      'code.PLM_TARGET_TYPE.EQUIP',      4),
    ('PLM_TARGET_TYPE', 'PROC',       'code.PLM_TARGET_TYPE.PROC',       5);

INSERT INTO sys_policy_def (policy_key, value_type, default_value, allowed_values, min_value, max_value, plant_scope_yn, desc_key) VALUES
    ('PLM_MARK_FIRST_LOTS', 'INT', '1', NULL, 0, 100, 'N', 'policy.PLM_MARK_FIRST_LOTS');

-- ---------------------------------------------------------------------
-- 1. ECO 변경 종류·표시 구간
-- ---------------------------------------------------------------------
ALTER TABLE plm_eco
    ADD COLUMN change_kind varchar(12) NOT NULL DEFAULT 'DESIGN',
    ADD COLUMN mark_lots   integer,
    ADD COLUMN mark_until  date;
ALTER TABLE plm_eco
    ADD CONSTRAINT ck_plm_eco_change_kind CHECK (change_kind IN ('DESIGN', 'PROCESS_4M', 'TEMP')),
    ADD CONSTRAINT ck_plm_eco_mark_lots CHECK (mark_lots BETWEEN 1 AND 100),
    -- 표시 끝은 4M·임시만, 임시는 필수, 유효일 이후
    ADD CONSTRAINT ck_plm_eco_mark_until CHECK (
        (change_kind = 'DESIGN' AND mark_until IS NULL)
        OR (change_kind = 'PROCESS_4M')
        OR (change_kind = 'TEMP' AND mark_until IS NOT NULL)),
    ADD CONSTRAINT ck_plm_eco_mark_range CHECK (mark_until IS NULL OR mark_until >= effective_date);
COMMENT ON COLUMN plm_eco.change_kind IS '변경 종류 — DESIGN(설계, BOM·라우팅 리비전) / PROCESS_4M(설비·작업자·방법 등) / TEMP(임시 변경)';
COMMENT ON COLUMN plm_eco.mark_lots IS '변경 뒤 첫 N LOT 표시 개수 — 비우면 정책 PLM_MARK_FIRST_LOTS (mark_until 이 있으면 쓰지 않음)';
COMMENT ON COLUMN plm_eco.mark_until IS '4M·임시 표시 끝 날짜(포함, 회사 시간대) — 있으면 유효일~이날 LOT 모두 표시';

-- V050 함수 + 새 칸 3개 고정
CREATE OR REPLACE FUNCTION hb_guard_plm_eco() RETURNS trigger
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
    -- 결재받은 내용은 고정: 작성 중이 아니면 제목·사유·설명·유효일·담당·고객 지정·변경 종류·표시 구간을 바꾸지 않는다
    IF OLD.status <> 'DRAFT' AND (
           NEW.title <> OLD.title OR NEW.reason_cd <> OLD.reason_cd OR NEW.description IS DISTINCT FROM OLD.description
        OR NEW.effective_date <> OLD.effective_date OR NEW.owner_id <> OLD.owner_id
        OR NEW.cust_approval_req_yn <> OLD.cust_approval_req_yn OR NEW.cust_id IS DISTINCT FROM OLD.cust_id
        OR NEW.change_kind <> OLD.change_kind OR NEW.mark_lots IS DISTINCT FROM OLD.mark_lots
        OR NEW.mark_until IS DISTINCT FROM OLD.mark_until) THEN
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

-- ---------------------------------------------------------------------
-- 2. 대상 유형 EQUIP·PROC
-- ---------------------------------------------------------------------
ALTER TABLE plm_eco_target
    ADD COLUMN equip_id bigint,
    ADD COLUMN proc_id  bigint;
ALTER TABLE plm_eco_target
    ADD CONSTRAINT fk_plm_eco_target_equip FOREIGN KEY (equip_id) REFERENCES mst_equip (equip_id),
    ADD CONSTRAINT fk_plm_eco_target_proc FOREIGN KEY (proc_id) REFERENCES mst_proc (proc_id),
    DROP CONSTRAINT ck_plm_eco_target_type;
ALTER TABLE plm_eco_target ADD CONSTRAINT ck_plm_eco_target_type CHECK (
    (target_type = 'ITEM' AND bom_id IS NULL AND routing_id IS NULL AND equip_id IS NULL AND proc_id IS NULL)
    OR (target_type = 'BOM' AND bom_id IS NOT NULL AND routing_id IS NULL AND equip_id IS NULL AND proc_id IS NULL)
    OR (target_type = 'ROUTING' AND routing_id IS NOT NULL AND bom_id IS NULL AND equip_id IS NULL AND proc_id IS NULL)
    OR (target_type = 'EQUIP' AND equip_id IS NOT NULL AND bom_id IS NULL AND routing_id IS NULL AND proc_id IS NULL)
    OR (target_type = 'PROC' AND proc_id IS NOT NULL AND bom_id IS NULL AND routing_id IS NULL AND equip_id IS NULL));
CREATE UNIQUE INDEX uq_plm_eco_target_equip ON plm_eco_target (eco_id, item_id, equip_id) WHERE equip_id IS NOT NULL;
CREATE UNIQUE INDEX uq_plm_eco_target_proc ON plm_eco_target (eco_id, item_id, proc_id) WHERE proc_id IS NOT NULL;
-- 표시 판정: 지시 리비전·설비로 RELEASED ECO 대상 찾기
CREATE INDEX ix_plm_eco_target_bom_ref ON plm_eco_target (comp_cd, bom_id) WHERE bom_id IS NOT NULL;
CREATE INDEX ix_plm_eco_target_routing_ref ON plm_eco_target (comp_cd, routing_id) WHERE routing_id IS NOT NULL;

-- V050 함수 + 설비·공정 같은 회사 확인, 고정 칸에 설비·공정
CREATE OR REPLACE FUNCTION hb_guard_plm_eco_target() RETURNS trigger
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
            AND NEW.equip_id IS NOT DISTINCT FROM OLD.equip_id AND NEW.proc_id IS NOT DISTINCT FROM OLD.proc_id
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
    IF (NEW.equip_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM mst_equip q WHERE q.equip_id = NEW.equip_id
                                                    AND q.comp_cd = NEW.comp_cd))
       OR (NEW.proc_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM mst_proc p WHERE p.proc_id = NEW.proc_id
                                                      AND p.comp_cd = NEW.comp_cd)) THEN
        RAISE EXCEPTION 'equipment or process is not of company %', NEW.comp_cd
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

-- ---------------------------------------------------------------------
-- 3. 변경 표시 (ECO × LOT) — 원본. 추가만
-- ---------------------------------------------------------------------
CREATE TABLE plm_eco_lot (
    eco_lot_id    bigint         GENERATED ALWAYS AS IDENTITY,
    comp_cd       varchar(20)    NOT NULL,
    eco_id        bigint         NOT NULL,
    lot_id        bigint         NOT NULL,
    mark_reason   varchar(10)    NOT NULL,
    seq_no        integer        NOT NULL,
    wo_id         bigint,
    equip_id      bigint,
    occurred_at   timestamptz    NOT NULL,
    recorded_at   timestamptz    NOT NULL DEFAULT now(),
    recorded_by   varchar(50)    NOT NULL,
    CONSTRAINT pk_plm_eco_lot PRIMARY KEY (eco_lot_id),
    CONSTRAINT fk_plm_eco_lot_comp FOREIGN KEY (comp_cd) REFERENCES sys_comp (comp_cd),
    -- ECO 만 FK(이미 잠근 행). LOT·지시·설비는 FK 를 두지 않는다 — FK 확인이 ECO 잠금 뒤 MES 행에 KEY SHARE 를 걸어
    -- 잠금 순서(MES → ECO)가 뒤집힌다(docs/39 13.2). 같은 회사 확인은 아래 트리거가 잠금 없이 한다
    CONSTRAINT fk_plm_eco_lot_eco FOREIGN KEY (eco_id) REFERENCES plm_eco (eco_id),
    CONSTRAINT uq_plm_eco_lot UNIQUE (comp_cd, eco_id, lot_id),
    CONSTRAINT uq_plm_eco_lot_seq UNIQUE (comp_cd, eco_id, seq_no),
    CONSTRAINT ck_plm_eco_lot_reason CHECK (mark_reason IN ('FIRST_LOT', '4M_WINDOW', 'TEMP')),
    CONSTRAINT ck_plm_eco_lot_seq CHECK (seq_no >= 1)
);
COMMENT ON TABLE plm_eco_lot IS '설계·4M 변경 표시 원본 (docs/39 13장). ECO 하나에 첫 N LOT(도착 순) 또는 표시 구간 LOT. 추가만 — lot_genealogy.change_id 는 사본';
CREATE INDEX ix_plm_eco_lot_lot ON plm_eco_lot (comp_cd, lot_id);

CREATE FUNCTION hb_guard_plm_eco_lot() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM plm_eco e WHERE e.eco_id = NEW.eco_id AND e.comp_cd = NEW.comp_cd AND e.status = 'RELEASED') THEN
        RAISE EXCEPTION 'ECO % is not a released ECO of company %', NEW.eco_id, NEW.comp_cd
            USING ERRCODE = '23503', CONSTRAINT = 'fk_plm_eco_lot_ref_comp';
    END IF;
    -- 잠금 없는 조회(FOR KEY SHARE 아님) — 13.2
    IF NOT EXISTS (SELECT 1 FROM lot_master l WHERE l.lot_id = NEW.lot_id AND l.comp_cd = NEW.comp_cd)
       OR (NEW.wo_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM prd_wo w WHERE w.wo_id = NEW.wo_id AND w.comp_cd = NEW.comp_cd))
       OR (NEW.equip_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM mst_equip q WHERE q.equip_id = NEW.equip_id
                                                       AND q.comp_cd = NEW.comp_cd)) THEN
        RAISE EXCEPTION 'LOT, work order or equipment is not of company %', NEW.comp_cd
            USING ERRCODE = '23503', CONSTRAINT = 'fk_plm_eco_lot_ref_comp';
    END IF;
    RETURN NEW;
END
$$;
CREATE TRIGGER tg_plm_eco_lot_guard BEFORE INSERT ON plm_eco_lot FOR EACH ROW EXECUTE FUNCTION hb_guard_plm_eco_lot();
CREATE TRIGGER tg_plm_eco_lot_append_only BEFORE UPDATE OR DELETE ON plm_eco_lot FOR EACH ROW EXECUTE FUNCTION hb_forbid_update_delete();

ALTER TABLE plm_eco_lot ENABLE ROW LEVEL SECURITY;
ALTER TABLE plm_eco_lot FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON plm_eco_lot USING (comp_cd = hb_current_comp()) WITH CHECK (comp_cd = hb_current_comp());
REVOKE UPDATE, DELETE, TRUNCATE ON plm_eco_lot FROM ${app_role};

INSERT INTO sys_retention_table (table_nm, class_cd) VALUES ('plm_eco_lot', 'PRODUCTION_RECORD');
