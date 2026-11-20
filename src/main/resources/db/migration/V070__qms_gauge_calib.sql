-- =====================================================================
-- P3 QMS Q2a 계측기 교정 (docs/29 2장 Q2a, 결정 D4·D5) — 임시 번호, 병합 때 재번호
--
-- - 계측기 마스터는 새로 만들지 않는다: P1 물리 자산(asset_type = GAUGE)에 계측기 클래스 칸(gauge_class) 하나만 더한다
--   (Q1 검사계획 행의 계측기 클래스와 같은 회사 확장 코드 GAUGE_CLASS — "이 행을 잴 수 있는 게이지" 를 같은 코드로 찾는다)
-- - 교정 기록 qms_gauge_calib = 원장 변형(P1 4b 설치 이력과 같은 모양): 추가 + 무효 1회만, 삭제 금지.
--   다음 교정일 = 교정일 + 그때 주기(스냅샷) — 생성 칸이라 주기를 나중에 바꿔도 과거 행은 그대로
-- - 최근 교정일(mst_phys_asset.last_calib_date)은 유효 교정 기록이 생기면 파생값(D4): 교정 추가·무효 트리거가 같은
--   트랜잭션에서 맞추고, 그 밖의 경로(물리 자산 화면·직접 SQL)로는 파생값과 다르게 못 바꾼다. 유효 기록 0건이면 예전처럼 입력
-- - 사용 가능 판정은 GaugeStatus(순수 함수) — 막는 것은 2부(측정값 입력·품질 게이트, D5)
-- =====================================================================

INSERT INTO sys_code_grp (grp_cd, grp_nm_key, comp_ext_yn) VALUES
    ('CALIB_RESULT', 'code.grp.CALIB_RESULT', 'N'),
    ('CALIB_AGENCY', 'code.grp.CALIB_AGENCY', 'N'),
    ('GAUGE_STATUS', 'code.grp.GAUGE_STATUS', 'N');
INSERT INTO sys_code (grp_cd, code, code_nm_key, sort_no) VALUES
    ('CALIB_RESULT', 'OK',      'code.CALIB_RESULT.OK',      1),
    ('CALIB_RESULT', 'LIMITED', 'code.CALIB_RESULT.LIMITED', 2),
    ('CALIB_RESULT', 'NG',      'code.CALIB_RESULT.NG',      3),
    ('CALIB_AGENCY', 'INTERNAL', 'code.CALIB_AGENCY.INTERNAL', 1),
    ('CALIB_AGENCY', 'EXTERNAL', 'code.CALIB_AGENCY.EXTERNAL', 2),
    ('GAUGE_STATUS', 'OK',        'code.GAUGE_STATUS.OK',        1),
    ('GAUGE_STATUS', 'DUE_SOON',  'code.GAUGE_STATUS.DUE_SOON',  2),
    ('GAUGE_STATUS', 'OVERDUE',   'code.GAUGE_STATUS.OVERDUE',   3),
    ('GAUGE_STATUS', 'NG',        'code.GAUGE_STATUS.NG',        4),
    ('GAUGE_STATUS', 'NO_RECORD', 'code.GAUGE_STATUS.NO_RECORD', 5),
    ('GAUGE_STATUS', 'INACTIVE',  'code.GAUGE_STATUS.INACTIVE',  6);

INSERT INTO sys_policy_def (policy_key, value_type, default_value, allowed_values, min_value, max_value, plant_scope_yn, desc_key) VALUES
    ('QMS_CALIB_DUE_SOON_DAYS', 'INT', '30', NULL, 0, 365, 'N', 'policy.QMS_CALIB_DUE_SOON_DAYS');

-- ---------------------------------------------------------------------
-- 1. 물리 자산(P1) — 계측기 클래스 칸 (남의 표 변경: PR 본문에 적음)
-- ---------------------------------------------------------------------
ALTER TABLE mst_phys_asset ADD COLUMN gauge_class varchar(30);
-- 게이지만, 육안(VISUAL)은 계측기가 아니다
ALTER TABLE mst_phys_asset ADD CONSTRAINT ck_mst_phys_asset_gauge_class
    CHECK (gauge_class IS NULL OR (asset_type = 'GAUGE' AND gauge_class <> 'VISUAL'));
CREATE INDEX ix_mst_phys_asset_gauge ON mst_phys_asset (comp_cd, gauge_class) WHERE asset_type = 'GAUGE';
COMMENT ON COLUMN mst_phys_asset.gauge_class IS '계측기 클래스(GAUGE_CLASS, 게이지만) — QMS 검사계획 행의 계측기 클래스와 같은 코드 (docs/29 Q1-1)';
COMMENT ON COLUMN mst_phys_asset.last_calib_date IS '최근 교정일 — 유효 교정 기록(qms_gauge_calib)이 있으면 그 최댓값(트리거가 맞춤, 직접 변경 불가). 없으면 입력값';

-- ---------------------------------------------------------------------
-- 2. 교정 기록 (원장 변형: 추가 + 무효 1회)
-- ---------------------------------------------------------------------
CREATE TABLE qms_gauge_calib (
    calib_id       bigint        GENERATED ALWAYS AS IDENTITY,
    comp_cd        varchar(20)   NOT NULL,
    asset_id       bigint        NOT NULL,
    calib_date     date          NOT NULL,
    result         varchar(10)   NOT NULL,
    limit_note     varchar(500),
    agency         varchar(10)   NOT NULL,
    cust_id        bigint,
    cert_no        varchar(50),
    cycle_days     integer       NOT NULL,
    next_due_date  date          GENERATED ALWAYS AS (calib_date + cycle_days) STORED,
    remark         varchar(500),
    recorded_at    timestamptz   NOT NULL DEFAULT now(),
    void_yn        char(1)       NOT NULL DEFAULT 'N',
    void_reason    varchar(500),
    voided_by      varchar(50),
    voided_at      timestamptz,
    created_at     timestamptz   NOT NULL DEFAULT now(),
    created_by     varchar(50)   NOT NULL,
    CONSTRAINT pk_qms_gauge_calib PRIMARY KEY (calib_id),
    CONSTRAINT fk_qms_gauge_calib_comp FOREIGN KEY (comp_cd) REFERENCES sys_comp (comp_cd),
    CONSTRAINT fk_qms_gauge_calib_asset FOREIGN KEY (asset_id) REFERENCES mst_phys_asset (asset_id),
    CONSTRAINT fk_qms_gauge_calib_cust FOREIGN KEY (cust_id) REFERENCES mst_cust (cust_id),
    CONSTRAINT ck_qms_gauge_calib_result CHECK (result IN ('OK', 'LIMITED', 'NG')),
    -- 제한 사용은 제한 내용 필수 (CHECK 는 NULL 을 통과로 보므로 coalesce)
    CONSTRAINT ck_qms_gauge_calib_limit CHECK (result <> 'LIMITED' OR coalesce(length(btrim(limit_note)), 0) > 0),
    -- 외부 기관은 거래처 필수, 사내는 거래처 없음
    CONSTRAINT ck_qms_gauge_calib_agency CHECK ((agency = 'INTERNAL' AND cust_id IS NULL) OR (agency = 'EXTERNAL' AND cust_id IS NOT NULL)),
    CONSTRAINT ck_qms_gauge_calib_cycle CHECK (cycle_days BETWEEN 1 AND 3650),
    CONSTRAINT ck_qms_gauge_calib_date CHECK (calib_date >= DATE '2000-01-01'),
    CONSTRAINT ck_qms_gauge_calib_void CHECK (
        (void_yn = 'N' AND void_reason IS NULL AND voided_by IS NULL AND voided_at IS NULL)
        OR (void_yn = 'Y' AND coalesce(length(btrim(void_reason)), 0) > 0 AND voided_by IS NOT NULL AND voided_at IS NOT NULL))
);
-- 계측기의 최근 유효 기록 찾기(상태 판정·목록)
CREATE INDEX ix_qms_gauge_calib_asset ON qms_gauge_calib (comp_cd, asset_id, calib_date DESC, calib_id DESC) WHERE void_yn = 'N';
CREATE INDEX ix_qms_gauge_calib_hist ON qms_gauge_calib (comp_cd, asset_id, calib_id);
COMMENT ON TABLE qms_gauge_calib IS '계측기 교정 기록 (원장 변형: 추가 + 무효 1회만, 삭제 금지). 성적서는 첨부(target qms-gauge-calib, calib_id) (docs/29 Q2a)';
COMMENT ON COLUMN qms_gauge_calib.cycle_days IS '그때 교정 주기(일) 스냅샷 — 물리 자산의 주기를 바꿔도 이 행의 다음 교정일은 그대로';
COMMENT ON COLUMN qms_gauge_calib.result IS 'OK 합격 / LIMITED 제한 사용(limit_note) / NG 불합격 — 판정은 GaugeStatus';

CREATE FUNCTION hb_guard_qms_gauge_calib() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'calibration record cannot be deleted (void it)' USING ERRCODE = '55000', CONSTRAINT = 'ck_qms_gauge_calib_no_delete';
    END IF;
    IF TG_OP = 'INSERT' THEN
        -- 같은 회사의 사용 중 게이지만. 행을 잠가 같은 계측기의 교정 추가·무효·자산 변경을 줄 세운다(최근 교정일 계산이 서로를 본다)
        PERFORM 1 FROM mst_phys_asset a
          WHERE a.asset_id = NEW.asset_id AND a.comp_cd = NEW.comp_cd AND a.asset_type = 'GAUGE' AND a.use_yn = 'Y' FOR UPDATE;
        IF NOT FOUND THEN
            RAISE EXCEPTION 'gauge % is not an active gauge of company %', NEW.asset_id, NEW.comp_cd
                USING ERRCODE = '23503', CONSTRAINT = 'fk_qms_gauge_calib_asset_comp';
        END IF;
        IF NEW.cust_id IS NOT NULL AND NOT EXISTS (
               SELECT 1 FROM mst_cust c WHERE c.cust_id = NEW.cust_id AND c.comp_cd = NEW.comp_cd AND c.use_yn = 'Y') THEN
            RAISE EXCEPTION 'customer % is not active in company %', NEW.cust_id, NEW.comp_cd
                USING ERRCODE = '23503', CONSTRAINT = 'fk_qms_gauge_calib_cust_comp';
        END IF;
        IF NEW.void_yn <> 'N' THEN
            RAISE EXCEPTION 'new calibration record must not be void' USING ERRCODE = '55000', CONSTRAINT = 'ck_qms_gauge_calib_ledger';
        END IF;
        RETURN NEW;
    END IF;
    -- UPDATE 는 무효 한 번뿐: void_yn N → Y 와 무효 칸 외에는 그대로
    IF OLD.void_yn = 'Y' OR NEW.void_yn <> 'Y'
       OR (NEW.comp_cd, NEW.asset_id, NEW.calib_date, NEW.result, NEW.limit_note, NEW.agency, NEW.cust_id, NEW.cert_no,
           NEW.cycle_days, NEW.remark, NEW.recorded_at, NEW.created_at, NEW.created_by)
          IS DISTINCT FROM
          (OLD.comp_cd, OLD.asset_id, OLD.calib_date, OLD.result, OLD.limit_note, OLD.agency, OLD.cust_id, OLD.cert_no,
           OLD.cycle_days, OLD.remark, OLD.recorded_at, OLD.created_at, OLD.created_by) THEN
        RAISE EXCEPTION 'calibration record % is a ledger row (only void once)', OLD.calib_id
            USING ERRCODE = '55000', CONSTRAINT = 'ck_qms_gauge_calib_ledger';
    END IF;
    PERFORM 1 FROM mst_phys_asset a WHERE a.asset_id = OLD.asset_id FOR UPDATE;
    RETURN NEW;
END
$$;
CREATE TRIGGER tg_qms_gauge_calib_guard BEFORE INSERT OR UPDATE OR DELETE ON qms_gauge_calib
    FOR EACH ROW EXECUTE FUNCTION hb_guard_qms_gauge_calib();

-- 최근 교정일 = 유효 기록의 최댓값 (D4). 잠금은 BEFORE 트리거가 잡았고, 이 문장은 그 뒤의 새 스냅샷으로 계산한다
-- (같은 문장 안 집계는 잠금을 기다린 뒤에도 옛 스냅샷 — SRM 교훈). row_ver 를 올려 열려 있던 자산 화면 저장은 409
CREATE FUNCTION hb_sync_gauge_last_calib() RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    v_last date;
BEGIN
    SELECT max(c.calib_date) INTO v_last FROM qms_gauge_calib c WHERE c.asset_id = NEW.asset_id AND c.void_yn = 'N';
    UPDATE mst_phys_asset a
       SET last_calib_date = v_last, row_ver = a.row_ver + 1, updated_at = now(),
           updated_by = coalesce(NEW.voided_by, NEW.created_by)
     WHERE a.asset_id = NEW.asset_id AND a.last_calib_date IS DISTINCT FROM v_last;
    RETURN NULL;
END
$$;
CREATE TRIGGER tg_qms_gauge_calib_sync AFTER INSERT OR UPDATE ON qms_gauge_calib
    FOR EACH ROW EXECUTE FUNCTION hb_sync_gauge_last_calib();

-- 물리 자산 쪽 방어: 계측기 클래스는 회사 코드(새 참조만), 유효 교정 기록이 있으면 최근 교정일은 파생값만·게이지 유형 고정.
-- 이름이 tg_mst_phys_asset_check(P1) 뒤라 P1 검사가 먼저 돈다
CREATE FUNCTION hb_guard_phys_asset_gauge() RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    v_last date;
    v_has  boolean;
BEGIN
    IF NEW.gauge_class IS NOT NULL AND (TG_OP = 'INSERT' OR NEW.gauge_class IS DISTINCT FROM OLD.gauge_class)
       AND NOT hb_code_usable(NEW.comp_cd, 'GAUGE_CLASS', NEW.gauge_class) THEN
        RAISE EXCEPTION 'gauge class % is not available for company %', NEW.gauge_class, NEW.comp_cd
            USING ERRCODE = '23503', CONSTRAINT = 'fk_mst_phys_asset_gauge_class';
    END IF;
    IF TG_OP = 'UPDATE' AND (NEW.last_calib_date IS DISTINCT FROM OLD.last_calib_date OR NEW.asset_type <> OLD.asset_type) THEN
        SELECT max(c.calib_date), count(*) > 0 INTO v_last, v_has
          FROM qms_gauge_calib c WHERE c.asset_id = NEW.asset_id AND c.void_yn = 'N';
        IF v_has AND NEW.asset_type <> OLD.asset_type THEN
            RAISE EXCEPTION 'gauge % has calibration records (type is fixed)', NEW.asset_id
                USING ERRCODE = '23514', CONSTRAINT = 'ck_mst_phys_asset_gauge_calib';
        END IF;
        IF v_has AND NEW.last_calib_date IS DISTINCT FROM v_last THEN
            RAISE EXCEPTION 'last calibration date of gauge % comes from calibration records', NEW.asset_id
                USING ERRCODE = '23514', CONSTRAINT = 'ck_mst_phys_asset_calib_derived';
        END IF;
    END IF;
    RETURN NEW;
END
$$;
CREATE TRIGGER tg_mst_phys_asset_gauge BEFORE INSERT OR UPDATE ON mst_phys_asset
    FOR EACH ROW EXECUTE FUNCTION hb_guard_phys_asset_gauge();

-- ---------------------------------------------------------------------
-- 3. 감사 · RLS · 권한 · 화면 · 보존
-- ---------------------------------------------------------------------
CREATE TRIGGER tg_audit AFTER INSERT OR UPDATE OR DELETE ON qms_gauge_calib FOR EACH ROW EXECUTE FUNCTION hb_audit('calib_id', '', '');

ALTER TABLE qms_gauge_calib ENABLE ROW LEVEL SECURITY;
ALTER TABLE qms_gauge_calib FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON qms_gauge_calib USING (comp_cd = hb_current_comp()) WITH CHECK (comp_cd = hb_current_comp());

REVOKE DELETE, TRUNCATE ON qms_gauge_calib FROM ${app_role};

INSERT INTO sys_prog (prog_cd, prog_nm_key, view_path, module_cd, actions, sort_no) VALUES
    ('qms-gauge-calib', 'prog.qmsGaugeCalib', 'qms/gauge-calib', 'QMS', ARRAY['SEARCH','SAVE','DELETE','EXCEL']::varchar(20)[], 620);
-- 교정 기록은 품질기록(IATF 7.1.5.2.1 — 부품 생산·서비스 기간 + 1년)
INSERT INTO sys_retention_table (table_nm, class_cd) VALUES ('qms_gauge_calib', 'QUALITY_RECORD');
