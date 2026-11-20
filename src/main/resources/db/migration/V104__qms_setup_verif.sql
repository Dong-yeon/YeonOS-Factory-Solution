-- =====================================================================
-- P3 QMS 2부 Q7 초품(셋업 검증) (docs/29_P3 12.3) — 임시 번호, 병합 때 재번호
--
-- - qms_setup_verif: ECO 가 첫 LOT(FIRST_LOT)으로 표시한 LOT 의 초품 판정 — 근거 = 판정 확정된 Q6 검사, 결과는 그 검사 결과 복사
-- - ECO·LOT 마다 살아 있는 판정 하나(부분 유일), 정정 = 무효(사유) 뒤 다시. 삭제 금지
-- - eco_id 는 FK 를 두지 않는다: MES 표시 경로가 LOT → ECO(FOR UPDATE) 순서로 잠그므로 ECO FK 의 KEY SHARE 를 먼저 쥐면
--   잠금 순서가 뒤집힌다(docs/39 13.2 plm_eco_lot 과 같은 이유). 같은 회사·FIRST_LOT 표시는 트리거가 잠금 없이 확인
-- - PLM 표(plm_eco·plm_eco_lot)는 읽기만
-- =====================================================================

INSERT INTO sys_code_grp (grp_cd, grp_nm_key, comp_ext_yn) VALUES
    ('QMS_SETUP_STATUS', 'code.grp.QMS_SETUP_STATUS', 'N');
INSERT INTO sys_code (grp_cd, code, code_nm_key, sort_no) VALUES
    ('QMS_SETUP_STATUS', 'ACTIVE', 'code.QMS_SETUP_STATUS.ACTIVE', 1),
    ('QMS_SETUP_STATUS', 'VOID',   'code.QMS_SETUP_STATUS.VOID',   2);

CREATE TABLE qms_setup_verif (
    verif_id      bigint         GENERATED ALWAYS AS IDENTITY,
    comp_cd       varchar(20)    NOT NULL,
    eco_id        bigint         NOT NULL,
    lot_id        bigint         NOT NULL,
    insp_id       bigint         NOT NULL,
    result        varchar(2)     NOT NULL,
    status        varchar(6)     NOT NULL DEFAULT 'ACTIVE',
    note          varchar(1000),
    void_reason   varchar(500),
    voided_at     timestamptz,
    voided_by     varchar(50),
    row_ver       integer        NOT NULL DEFAULT 0,
    created_at    timestamptz    NOT NULL DEFAULT now(),
    created_by    varchar(50)    NOT NULL,
    updated_at    timestamptz    NOT NULL DEFAULT now(),
    updated_by    varchar(50)    NOT NULL,
    CONSTRAINT pk_qms_setup_verif PRIMARY KEY (verif_id),
    CONSTRAINT fk_qms_setup_verif_comp FOREIGN KEY (comp_cd) REFERENCES sys_comp (comp_cd),
    CONSTRAINT fk_qms_setup_verif_lot FOREIGN KEY (lot_id) REFERENCES lot_master (lot_id),
    CONSTRAINT fk_qms_setup_verif_insp FOREIGN KEY (insp_id) REFERENCES qms_insp (insp_id),
    CONSTRAINT ck_qms_setup_verif_result CHECK (result IN ('OK', 'NG')),
    CONSTRAINT ck_qms_setup_verif_status CHECK (status IN ('ACTIVE', 'VOID')),
    CONSTRAINT ck_qms_setup_verif_void CHECK ((status = 'VOID') = (voided_at IS NOT NULL)
        AND (status <> 'VOID' OR (coalesce(length(btrim(void_reason)), 0) > 0 AND voided_by IS NOT NULL)))
);
CREATE UNIQUE INDEX uq_qms_setup_verif_live ON qms_setup_verif (comp_cd, eco_id, lot_id) WHERE status = 'ACTIVE';
CREATE INDEX ix_qms_setup_verif_created ON qms_setup_verif (comp_cd, created_at DESC);
COMMENT ON TABLE qms_setup_verif IS '초품(셋업 검증) 판정 — ECO 첫 LOT × 근거 검사(판정 확정), 결과 복사, ECO·LOT 마다 살아 있는 판정 하나 (docs/29_P3 Q7)';
COMMENT ON COLUMN qms_setup_verif.eco_id IS 'PLM ECO — FK 없음(잠금 순서, docs/39 13.2). 같은 회사·첫 LOT 표시는 트리거가 확인';

CREATE FUNCTION hb_guard_qms_setup_verif() RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    v_insp qms_insp;
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'setup verdict cannot be deleted (void it)' USING ERRCODE = '55000', CONSTRAINT = 'ck_qms_setup_verif_no_delete';
    END IF;
    IF TG_OP = 'INSERT' THEN
        IF NEW.status <> 'ACTIVE' THEN
            RAISE EXCEPTION 'new setup verdict must be ACTIVE' USING ERRCODE = '55000', CONSTRAINT = 'ck_qms_setup_verif_active';
        END IF;
        -- 잠금 없이 읽기(FOR SHARE·FK 없음 — 잠금 순서)
        IF NOT EXISTS (SELECT 1 FROM plm_eco e WHERE e.eco_id = NEW.eco_id AND e.comp_cd = NEW.comp_cd) THEN
            RAISE EXCEPTION 'ECO % does not belong to company %', NEW.eco_id, NEW.comp_cd USING ERRCODE = '23503', CONSTRAINT = 'fk_qms_setup_verif_eco_comp';
        END IF;
        IF NOT EXISTS (SELECT 1 FROM plm_eco_lot m WHERE m.comp_cd = NEW.comp_cd AND m.eco_id = NEW.eco_id AND m.lot_id = NEW.lot_id
                          AND m.mark_reason = 'FIRST_LOT') THEN
            RAISE EXCEPTION 'lot % is not a first lot of ECO %', NEW.lot_id, NEW.eco_id USING ERRCODE = '23514', CONSTRAINT = 'ck_qms_setup_verif_first_lot';
        END IF;
        SELECT * INTO v_insp FROM qms_insp WHERE insp_id = NEW.insp_id AND comp_cd = NEW.comp_cd;
        IF NOT FOUND THEN
            RAISE EXCEPTION 'inspection % does not belong to company %', NEW.insp_id, NEW.comp_cd USING ERRCODE = '23503', CONSTRAINT = 'fk_qms_setup_verif_insp_comp';
        END IF;
        IF v_insp.lot_id <> NEW.lot_id OR v_insp.status <> 'JUDGED' OR v_insp.result IS DISTINCT FROM NEW.result THEN
            RAISE EXCEPTION 'evidence must be a judged inspection of the same lot with the same result'
                USING ERRCODE = '23514', CONSTRAINT = 'ck_qms_setup_verif_evidence';
        END IF;
        RETURN NEW;
    END IF;
    -- UPDATE: ACTIVE → VOID 만, 그 밖 칸 고정
    IF (NEW.comp_cd, NEW.eco_id, NEW.lot_id, NEW.insp_id, NEW.result) IS DISTINCT FROM (OLD.comp_cd, OLD.eco_id, OLD.lot_id, OLD.insp_id, OLD.result)
       OR NEW.note IS DISTINCT FROM OLD.note OR OLD.status = 'VOID' OR NEW.status <> 'VOID' THEN
        RAISE EXCEPTION 'setup verdict % can only be voided', OLD.verif_id USING ERRCODE = '55000', CONSTRAINT = 'ck_qms_setup_verif_immutable';
    END IF;
    RETURN NEW;
END
$$;
CREATE TRIGGER tg_qms_setup_verif_guard BEFORE INSERT OR UPDATE OR DELETE ON qms_setup_verif FOR EACH ROW EXECUTE FUNCTION hb_guard_qms_setup_verif();

CREATE TRIGGER tg_audit AFTER INSERT OR UPDATE OR DELETE ON qms_setup_verif FOR EACH ROW EXECUTE FUNCTION hb_audit('verif_id', '', '');

ALTER TABLE qms_setup_verif ENABLE ROW LEVEL SECURITY;
ALTER TABLE qms_setup_verif FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON qms_setup_verif USING (comp_cd = hb_current_comp()) WITH CHECK (comp_cd = hb_current_comp());

REVOKE DELETE, TRUNCATE ON qms_setup_verif FROM ${app_role};

INSERT INTO sys_prog (prog_cd, prog_nm_key, view_path, module_cd, actions, sort_no) VALUES
    ('qms-setup', 'prog.qmsSetup', 'qms/setup-verif', 'QMS', ARRAY['SEARCH','SAVE','DELETE']::varchar(20)[], 695);
INSERT INTO sys_retention_table (table_nm, class_cd) VALUES ('qms_setup_verif', 'QUALITY_RECORD');
