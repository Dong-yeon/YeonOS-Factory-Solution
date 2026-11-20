-- =====================================================================
-- WMS 2부 B6b-1 팔레트·SSCC (docs/30 12.10, D-B21~D-B25) — 임시 번호(docs/29 2.2)
--
-- - wms_pallet: 출하 지시 하나 안의 팔레트(물류 단위). OPEN → CLOSED(봉인, SSCC 부여) ↔ OPEN(다시 열기), 빈 OPEN → CANCELLED
-- - wms_ship_pick.pallet_id: PICKED 피킹을 팔레트에 담는다(같은 지시·OPEN 팔레트만). 재고 원장은 바뀌지 않는다(D-B25)
-- - 정책: 회사 WMS_GS1_COMPANY_PREFIX(GS1 업체코드, 비면 SSCC 없음), 공장 WMS_SHIP_PALLET_REQUIRED(기본 N)
-- - 남의 표 변경 없음
-- =====================================================================

INSERT INTO sys_policy_def (policy_key, value_type, default_value, allowed_values, min_value, max_value, plant_scope_yn, desc_key) VALUES
    ('WMS_GS1_COMPANY_PREFIX',    'STRING', '',  NULL, NULL, NULL, 'N', 'policy.WMS_GS1_COMPANY_PREFIX'),
    ('WMS_SHIP_PALLET_REQUIRED',  'BOOL',   'N', NULL, NULL, NULL, 'Y', 'policy.WMS_SHIP_PALLET_REQUIRED');

INSERT INTO sys_code_grp (grp_cd, grp_nm_key, comp_ext_yn) VALUES ('WMS_PALLET_STATUS', 'code.grp.WMS_PALLET_STATUS', 'N');
INSERT INTO sys_code (grp_cd, code, code_nm_key, sort_no) VALUES
    ('WMS_PALLET_STATUS', 'OPEN',      'code.WMS_PALLET_STATUS.OPEN',      1),
    ('WMS_PALLET_STATUS', 'CLOSED',    'code.WMS_PALLET_STATUS.CLOSED',    2),
    ('WMS_PALLET_STATUS', 'CANCELLED', 'code.WMS_PALLET_STATUS.CANCELLED', 3);

CREATE TABLE wms_pallet (
    pallet_id      bigint         GENERATED ALWAYS AS IDENTITY,
    comp_cd        varchar(20)    NOT NULL,
    ship_id        bigint         NOT NULL,
    pallet_no      varchar(40)    NOT NULL,
    sscc           char(18),
    status         varchar(10)    NOT NULL DEFAULT 'OPEN',
    closed_at      timestamptz,
    closed_by      varchar(50),
    remark         varchar(500),
    row_ver        integer        NOT NULL DEFAULT 0,
    created_at     timestamptz    NOT NULL DEFAULT now(),
    created_by     varchar(50)    NOT NULL,
    updated_at     timestamptz    NOT NULL DEFAULT now(),
    updated_by     varchar(50)    NOT NULL,
    CONSTRAINT pk_wms_pallet PRIMARY KEY (pallet_id),
    CONSTRAINT fk_wms_pallet_ship FOREIGN KEY (ship_id) REFERENCES wms_ship (ship_id),
    CONSTRAINT uq_wms_pallet_no UNIQUE (comp_cd, pallet_no),
    CONSTRAINT uq_wms_pallet_sscc UNIQUE (comp_cd, sscc),
    CONSTRAINT ck_wms_pallet_status CHECK (status IN ('OPEN', 'CLOSED', 'CANCELLED')),
    CONSTRAINT ck_wms_pallet_sscc CHECK (sscc IS NULL OR sscc ~ '^[0-9]{18}$'),
    CONSTRAINT ck_wms_pallet_closed CHECK ((status = 'CLOSED') = (closed_at IS NOT NULL AND closed_by IS NOT NULL))
);
COMMENT ON TABLE wms_pallet IS '출하 팔레트 (docs/30 12.10, B6b) — 출하 지시 하나 안, 닫을 때 SSCC(GS1 AI 00). 재고 원장과 무관한 묶음 문서';
COMMENT ON COLUMN wms_pallet.sscc IS 'GS1 SSCC 18자리(확장 0 + 업체코드 + 일련 + 검사 숫자) — 닫을 때 한 번 부여, 바뀌지 않는다. 업체코드 정책이 비면 NULL';
CREATE INDEX ix_wms_pallet_ship ON wms_pallet (comp_cd, ship_id);

ALTER TABLE wms_ship_pick ADD COLUMN pallet_id bigint;
ALTER TABLE wms_ship_pick ADD CONSTRAINT fk_wms_ship_pick_pallet FOREIGN KEY (pallet_id) REFERENCES wms_pallet (pallet_id);
COMMENT ON COLUMN wms_ship_pick.pallet_id IS '담긴 팔레트(B6b) — PICKED 일 때만 바꿀 수 있고, 같은 지시·OPEN 팔레트만';
CREATE INDEX ix_wms_ship_pick_pallet ON wms_ship_pick (comp_cd, pallet_id) WHERE pallet_id IS NOT NULL;

-- 팔레트: 전이 표·고정 칸·SSCC 는 한 번만·지시 = 같은 회사
CREATE FUNCTION hb_guard_wms_pallet() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM wms_ship s WHERE s.ship_id = NEW.ship_id AND s.comp_cd = NEW.comp_cd) THEN
        RAISE EXCEPTION 'shipment % is not of company %', NEW.ship_id, NEW.comp_cd USING ERRCODE = '23503', CONSTRAINT = 'fk_wms_pallet_ship_comp';
    END IF;
    IF TG_OP = 'INSERT' THEN
        IF NEW.status <> 'OPEN' OR NEW.sscc IS NOT NULL THEN
            RAISE EXCEPTION 'new pallet must be OPEN without SSCC' USING ERRCODE = '55000', CONSTRAINT = 'ck_wms_pallet_transition';
        END IF;
        RETURN NEW;
    END IF;
    IF (NEW.ship_id, NEW.pallet_no, NEW.comp_cd) IS DISTINCT FROM (OLD.ship_id, OLD.pallet_no, OLD.comp_cd)
       OR (OLD.sscc IS NOT NULL AND NEW.sscc IS DISTINCT FROM OLD.sscc) THEN
        RAISE EXCEPTION 'pallet key and SSCC are fixed' USING ERRCODE = '55000', CONSTRAINT = 'ck_wms_pallet_fixed';
    END IF;
    IF OLD.status = 'CANCELLED' OR NOT (NEW.status = OLD.status
            OR (OLD.status, NEW.status) IN (('OPEN', 'CLOSED'), ('CLOSED', 'OPEN'), ('OPEN', 'CANCELLED'))) THEN
        RAISE EXCEPTION 'pallet % cannot go % -> %', OLD.pallet_no, OLD.status, NEW.status
            USING ERRCODE = '55000', CONSTRAINT = 'ck_wms_pallet_transition';
    END IF;
    IF NEW.status = 'CANCELLED' AND EXISTS (SELECT 1 FROM wms_ship_pick p WHERE p.pallet_id = NEW.pallet_id) THEN
        RAISE EXCEPTION 'pallet % is not empty', OLD.pallet_no USING ERRCODE = '55000', CONSTRAINT = 'ck_wms_pallet_not_empty';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER tg_guard BEFORE INSERT OR UPDATE ON wms_pallet FOR EACH ROW EXECUTE FUNCTION hb_guard_wms_pallet();

-- 피킹의 팔레트: PICKED 일 때만 바꾸고, 넣는 팔레트는 같은 지시·OPEN. 빼기(NULL)는 OPEN 팔레트에서만
CREATE FUNCTION hb_guard_wms_ship_pick_pallet() RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    v_ship bigint;
BEGIN
    IF NEW.pallet_id IS NOT DISTINCT FROM OLD.pallet_id THEN
        RETURN NEW;
    END IF;
    IF OLD.status <> 'PICKED' THEN
        RAISE EXCEPTION 'pick % is not PICKED', OLD.pick_id USING ERRCODE = '55000', CONSTRAINT = 'ck_wms_ship_pick_pallet';
    END IF;
    SELECT l.ship_id INTO v_ship FROM wms_ship_line l WHERE l.ship_line_id = NEW.ship_line_id;
    IF OLD.pallet_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM wms_pallet t WHERE t.pallet_id = OLD.pallet_id AND t.status = 'OPEN') THEN
        RAISE EXCEPTION 'pallet of pick % is not OPEN', OLD.pick_id USING ERRCODE = '55000', CONSTRAINT = 'ck_wms_ship_pick_pallet';
    END IF;
    IF NEW.pallet_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM wms_pallet t WHERE t.pallet_id = NEW.pallet_id AND t.status = 'OPEN'
                                                    AND t.ship_id = v_ship AND t.comp_cd = NEW.comp_cd) THEN
        RAISE EXCEPTION 'pallet % is not an OPEN pallet of shipment %', NEW.pallet_id, v_ship
            USING ERRCODE = '55000', CONSTRAINT = 'ck_wms_ship_pick_pallet';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER tg_guard_pallet BEFORE UPDATE OF pallet_id ON wms_ship_pick FOR EACH ROW EXECUTE FUNCTION hb_guard_wms_ship_pick_pallet();

CREATE TRIGGER tg_audit AFTER INSERT OR UPDATE OR DELETE ON wms_pallet FOR EACH ROW EXECUTE FUNCTION hb_audit('pallet_id', '', '');
ALTER TABLE wms_pallet ENABLE ROW LEVEL SECURITY;
ALTER TABLE wms_pallet FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON wms_pallet USING (comp_cd = hb_current_comp()) WITH CHECK (comp_cd = hb_current_comp());
REVOKE DELETE, TRUNCATE ON wms_pallet FROM ${app_role};
INSERT INTO sys_retention_table (table_nm, class_cd) VALUES ('wms_pallet', 'QUALITY_RECORD');
