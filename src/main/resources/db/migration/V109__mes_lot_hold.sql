-- =====================================================================
-- MES LOT 보류 근거 원장 (docs/25 4.19, QMS Q8 경계 — 사용자 결정 2026-10-21)
-- - lot_hold: 보류 근거 한 건 = 한 행(출처 MANUAL·MES_CORRECT·QMS_NCR·QMS_CONTAIN × 참조), 살아 있는 근거는 (출처, 참조)마다 하나.
--   해제는 같은 행의 released_* 를 한 번만 채운다(D33). 삭제 없음
-- - lot_master.hold_yn = 살아 있는 근거 ≥ 1, hold_reason = 살아 있는 근거 사유 요약 — lot_hold 트리거가 맞추고,
--   lot_master 가드가 근거와 다른 직접 변경을 거부한다(D36). hold_yn 을 읽는 곳(계보·입고·WMS 원장 트리거, 조회)은 그대로
-- - 기존 보류 LOT 은 MANUAL 근거 1행으로 옮긴다(D35 — 정정 자동 보류와 수동 보류를 지금은 구분할 수 없다)
-- =====================================================================

CREATE TABLE lot_hold (
    hold_id         bigint        GENERATED ALWAYS AS IDENTITY,
    comp_cd         varchar(20)   NOT NULL,
    lot_id          bigint        NOT NULL,
    source          varchar(20)   NOT NULL,
    ref_id          bigint,
    reason          varchar(500)  NOT NULL,
    held_at         timestamptz   NOT NULL DEFAULT now(),
    held_by         varchar(50)   NOT NULL,
    released_at     timestamptz,
    released_by     varchar(50),
    release_reason  varchar(500),
    CONSTRAINT pk_lot_hold PRIMARY KEY (hold_id),
    CONSTRAINT fk_lot_hold_lot FOREIGN KEY (lot_id) REFERENCES lot_master (lot_id),
    CONSTRAINT ck_lot_hold_source CHECK (source IN ('MANUAL', 'MES_CORRECT', 'QMS_NCR', 'QMS_CONTAIN')),
    -- 수동 보류만 참조 없음. 해제 사유는 NULL 이면 length 검사가 NULL(통과)이라 IS NOT NULL 을 따로
    CONSTRAINT ck_lot_hold_ref CHECK ((source = 'MANUAL') = (ref_id IS NULL)),
    CONSTRAINT ck_lot_hold_reason CHECK (length(btrim(reason)) > 0),
    CONSTRAINT ck_lot_hold_release CHECK (
        (released_at IS NULL AND released_by IS NULL AND release_reason IS NULL)
        OR (released_at IS NOT NULL AND released_by IS NOT NULL AND release_reason IS NOT NULL AND length(btrim(release_reason)) > 0))
);
COMMENT ON TABLE lot_hold IS 'LOT 보류 근거 (docs/25 4.19). 근거마다 한 행, 해제는 released_* 한 번만. lot_master.hold_yn = 살아 있는 근거 ≥ 1';
COMMENT ON COLUMN lot_hold.source IS 'MANUAL(LOT 화면) · MES_CORRECT(POP 실적 정정, ref = prd_result) · QMS_NCR · QMS_CONTAIN(QMS 는 LotHoldPort 로만)';
COMMENT ON COLUMN lot_hold.ref_id IS '출처의 행 id — 출처마다 표가 달라 FK 없음(MES_CORRECT 는 트리거가 확인, QMS_* 는 포트 호출 쪽 책임)';
-- 살아 있는 근거는 (출처, 참조)마다 하나 — MANUAL 은 LOT 당 하나
CREATE UNIQUE INDEX uq_lot_hold_live ON lot_hold (comp_cd, lot_id, source, ref_id) NULLS NOT DISTINCT WHERE released_at IS NULL;
CREATE INDEX ix_lot_hold_lot ON lot_hold (comp_cd, lot_id, hold_id);

-- 살아 있는 근거 사유 요약(보류 시각 순, 500자) — 없으면 null
CREATE FUNCTION hb_lot_hold_summary(p_lot bigint) RETURNS varchar
    LANGUAGE sql STABLE
AS $$
    SELECT left(string_agg(h.reason, ' / ' ORDER BY h.held_at, h.hold_id), 500)
      FROM lot_hold h WHERE h.lot_id = p_lot AND h.released_at IS NULL
$$;

CREATE FUNCTION hb_guard_lot_hold() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'INSERT' THEN
        IF NEW.released_at IS NOT NULL THEN
            RAISE EXCEPTION 'a hold starts live' USING ERRCODE = '23514', CONSTRAINT = 'ck_lot_hold_new';
        END IF;
        IF NOT EXISTS (SELECT 1 FROM lot_master m WHERE m.lot_id = NEW.lot_id AND m.comp_cd = NEW.comp_cd) THEN
            RAISE EXCEPTION 'lot % is not of company %', NEW.lot_id, NEW.comp_cd
                USING ERRCODE = '23503', CONSTRAINT = 'fk_lot_hold_lot_comp';
        END IF;
        IF NEW.source = 'MES_CORRECT' AND NOT EXISTS (SELECT 1 FROM prd_result r WHERE r.result_id = NEW.ref_id
                                                         AND r.comp_cd = NEW.comp_cd AND r.lot_id = NEW.lot_id) THEN
            RAISE EXCEPTION 'result % is not a result of lot %', NEW.ref_id, NEW.lot_id
                USING ERRCODE = '23503', CONSTRAINT = 'fk_lot_hold_ref_comp';
        END IF;
        RETURN NEW;
    END IF;
    -- 해제 한 번만: 나머지 칸 고정, released_* 는 null → 값
    IF OLD.released_at IS NOT NULL OR NEW.released_at IS NULL
       OR (NEW.hold_id, NEW.comp_cd, NEW.lot_id, NEW.source, NEW.reason, NEW.held_at, NEW.held_by)
          IS DISTINCT FROM (OLD.hold_id, OLD.comp_cd, OLD.lot_id, OLD.source, OLD.reason, OLD.held_at, OLD.held_by)
       OR NEW.ref_id IS DISTINCT FROM OLD.ref_id THEN
        RAISE EXCEPTION 'hold % can only be released once', OLD.hold_id USING ERRCODE = '55000', CONSTRAINT = 'ck_lot_hold_fixed';
    END IF;
    RETURN NEW;
END
$$;
CREATE TRIGGER tg_lot_hold_guard BEFORE INSERT OR UPDATE ON lot_hold FOR EACH ROW EXECUTE FUNCTION hb_guard_lot_hold();
CREATE TRIGGER tg_lot_hold_no_delete BEFORE DELETE ON lot_hold FOR EACH ROW EXECUTE FUNCTION hb_forbid_update_delete();

-- 근거가 바뀌면 LOT 의 보류 칸을 맞춘다(같은 트랜잭션 — 부르는 쪽이 LOT 행을 먼저 잠근다, 4.19 잠금 순서)
CREATE FUNCTION hb_sync_lot_hold() RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    v_reason varchar;
BEGIN
    -- LOT 행을 먼저 잠그고 요약을 읽는다 — 잠금을 기다린 뒤의 새 스냅샷이라 동시에 들어온 다른 근거가 빠지지 않는다
    PERFORM 1 FROM lot_master WHERE lot_id = NEW.lot_id FOR UPDATE;
    v_reason := hb_lot_hold_summary(NEW.lot_id);
    UPDATE lot_master
       SET hold_yn = CASE WHEN v_reason IS NULL THEN 'N' ELSE 'Y' END, hold_reason = v_reason,
           row_ver = row_ver + 1, updated_at = now(), updated_by = coalesce(NEW.released_by, NEW.held_by)
     WHERE lot_id = NEW.lot_id
       AND (hold_yn, hold_reason) IS DISTINCT FROM (CASE WHEN v_reason IS NULL THEN 'N' ELSE 'Y' END, v_reason);
    RETURN NULL;
END
$$;
CREATE TRIGGER tg_lot_hold_sync AFTER INSERT OR UPDATE ON lot_hold FOR EACH ROW EXECUTE FUNCTION hb_sync_lot_hold();

-- lot_master 가드: 기존 규칙(V034) + 새 LOT 은 보류 없이 + 보류 칸은 근거와 같아야(D36)
CREATE OR REPLACE FUNCTION hb_guard_lot_master() RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    v_reason varchar;
BEGIN
    IF TG_OP = 'UPDATE' THEN
        IF (NEW.lot_no, NEW.item_id, NEW.origin, NEW.qty_created, NEW.unit_cd, NEW.occurred_at)
               IS DISTINCT FROM (OLD.lot_no, OLD.item_id, OLD.origin, OLD.qty_created, OLD.unit_cd, OLD.occurred_at)
           OR NEW.wo_id IS DISTINCT FROM OLD.wo_id OR NEW.supplier_cust_id IS DISTINCT FROM OLD.supplier_cust_id
           OR NEW.supplier_lot_no IS DISTINCT FROM OLD.supplier_lot_no THEN
            RAISE EXCEPTION 'lot % keeps its number, item, quantity and origin', OLD.lot_no
                USING ERRCODE = '55000', CONSTRAINT = 'ck_lot_master_fixed';
        END IF;
        IF (NEW.hold_yn, NEW.hold_reason) IS DISTINCT FROM (OLD.hold_yn, OLD.hold_reason) THEN
            v_reason := hb_lot_hold_summary(NEW.lot_id);
            IF (NEW.hold_yn, NEW.hold_reason) IS DISTINCT FROM (CASE WHEN v_reason IS NULL THEN 'N' ELSE 'Y' END, v_reason) THEN
                RAISE EXCEPTION 'lot % hold follows its hold records (lot_hold)', OLD.lot_no
                    USING ERRCODE = '55000', CONSTRAINT = 'ck_lot_master_hold_basis';
            END IF;
        END IF;
        RETURN NEW;
    END IF;
    IF NEW.hold_yn <> 'N' THEN
        RAISE EXCEPTION 'a new lot starts without hold — add a hold record' USING ERRCODE = '55000', CONSTRAINT = 'ck_lot_master_hold_basis';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM mst_item i WHERE i.item_id = NEW.item_id AND i.comp_cd = NEW.comp_cd AND i.use_yn = 'Y'
                      AND i.unit_cd = NEW.unit_cd) THEN
        RAISE EXCEPTION 'item % is not an active item of company % (unit %)', NEW.item_id, NEW.comp_cd, NEW.unit_cd
            USING ERRCODE = '23503', CONSTRAINT = 'fk_lot_master_item_comp';
    END IF;
    IF NEW.wo_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM prd_wo w WHERE w.wo_id = NEW.wo_id AND w.comp_cd = NEW.comp_cd
                      AND w.item_id = NEW.item_id AND w.status IN ('RELEASED', 'IN_PROGRESS', 'DONE')) THEN
        RAISE EXCEPTION 'work order % is not a released order of the item', NEW.wo_id
            USING ERRCODE = '23503', CONSTRAINT = 'fk_lot_master_wo_item';
    END IF;
    IF NEW.supplier_cust_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM mst_cust c WHERE c.cust_id = NEW.supplier_cust_id
                      AND c.comp_cd = NEW.comp_cd AND c.use_yn = 'Y' AND c.cust_type IN ('PURCHASE', 'BOTH')) THEN
        RAISE EXCEPTION 'customer % is not an active supplier of company %', NEW.supplier_cust_id, NEW.comp_cd
            USING ERRCODE = '23503', CONSTRAINT = 'fk_lot_master_supplier_comp';
    END IF;
    RETURN NEW;
END
$$;

CREATE TRIGGER tg_audit AFTER INSERT OR UPDATE OR DELETE ON lot_hold FOR EACH ROW EXECUTE FUNCTION hb_audit('hold_id', '', '');

ALTER TABLE lot_hold ENABLE ROW LEVEL SECURITY;
ALTER TABLE lot_hold FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON lot_hold USING (comp_cd = hb_current_comp()) WITH CHECK (comp_cd = hb_current_comp());
REVOKE DELETE, TRUNCATE ON lot_hold FROM ${app_role};

INSERT INTO sys_retention_table (table_nm, class_cd) VALUES ('lot_hold', 'PRODUCTION_RECORD');

-- 기존 보류 → MANUAL 근거 1행. 마이그레이션 계정이 슈퍼유저가 아니면 FORCE RLS 를 받으므로 회사마다 범위를 연다(V084 선례)
-- V034 ck_lot_master_hold 는 사유 NULL 을 통과시켰다(length(btrim(NULL)) > 0 = NULL) — 그런 행도 이관이 막히지 않게 사유·사람을 채운다
DO $$
DECLARE
    v_comps varchar[];
    v_comp  varchar;
BEGIN
    PERFORM set_config('app.scope', 'SYSTEM', true);
    SELECT coalesce(array_agg(comp_cd), ARRAY[]::varchar[]) INTO v_comps FROM sys_comp;
    PERFORM set_config('app.scope', '', true);
    FOREACH v_comp IN ARRAY v_comps LOOP
        PERFORM set_config('app.comp_cd', v_comp, true);
        INSERT INTO lot_hold (comp_cd, lot_id, source, ref_id, reason, held_at, held_by)
        SELECT m.comp_cd, m.lot_id, 'MANUAL', NULL, coalesce(nullif(btrim(m.hold_reason), ''), '이관(사유 없음)'), m.updated_at,
               coalesce(nullif(btrim(m.updated_by), ''), 'migration')
          FROM lot_master m
         WHERE m.comp_cd = v_comp AND m.hold_yn = 'Y'
         ORDER BY m.lot_id;
    END LOOP;
    PERFORM set_config('app.comp_cd', '', true);
END
$$;

-- V034 ck_lot_master_hold 의 NULL 구멍을 막는다: 이관 뒤 보류 LOT 의 사유는 근거 요약(비어 있지 않음)이라 기존 행도 맞다
ALTER TABLE lot_master ADD CONSTRAINT ck_lot_master_hold_reason
    CHECK (hold_yn = 'N' OR coalesce(length(btrim(hold_reason)), 0) > 0) NOT VALID;
ALTER TABLE lot_master VALIDATE CONSTRAINT ck_lot_master_hold_reason;
