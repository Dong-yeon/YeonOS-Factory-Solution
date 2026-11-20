-- =====================================================================
-- QMS 4M 3차 — 임시 변경 복귀 확인 · 변경 효과 확인 (docs/29_P3 51장, docs/66 (나)·(다)) — 임시 번호, 병합 때 재번호
--
-- - qms_change_revert: 임시(TEMP) ECO 가 적용(RELEASED)된 뒤 "언제 누가 표준으로 돌렸나" — ECO 마다 살아 있는 기록 하나,
--   정정 = 무효(사유) 뒤 다시, 삭제 금지 (초품 qms_setup_verif 와 같은 모양)
-- - qms_change_effect: 적용된 ECO 의 변경 효과 판정(OK/NG) + 판정 때 본 비교 조건 사본 — 같은 규칙
-- - qms_change(V094) 는 ECO DRAFT 일 때만 고칠 수 있어 칸을 더하지 않고 새 표로 나눴다(51.1)
-- - PLM 표(plm_eco)는 읽기만, 잠금 없이(FK 도 두지 않음 — MES 표시 경로가 LOT → ECO 순서로 잠근다, docs/39 13.2).
--   ECO 가 RELEASED 가 되면 상태가 다시 바뀌지 않으므로(ck_plm_eco_transition) 잠금 없는 읽기로 충분하다
-- - 표시 끝(plm_eco.mark_until)은 바꾸지 않는다(Q4-2) — 차이는 조회 때 경고
-- =====================================================================

INSERT INTO sys_code_grp (grp_cd, grp_nm_key, comp_ext_yn) VALUES
    ('QMS_4M_REVERT_TYPE', 'code.grp.QMS_4M_REVERT_TYPE', 'N'),
    ('QMS_4M_EFFECT',      'code.grp.QMS_4M_EFFECT',      'N');
INSERT INTO sys_code (grp_cd, code, code_nm_key, sort_no) VALUES
    ('QMS_4M_REVERT_TYPE', 'RESTORED',   'code.QMS_4M_REVERT_TYPE.RESTORED',   1),
    ('QMS_4M_REVERT_TYPE', 'SUPERSEDED', 'code.QMS_4M_REVERT_TYPE.SUPERSEDED', 2),
    ('QMS_4M_EFFECT',      'OK',         'code.QMS_4M_EFFECT.OK',              1),
    ('QMS_4M_EFFECT',      'NG',         'code.QMS_4M_EFFECT.NG',              2);

-- ---------------------------------------------------------------------
-- 1. 임시 변경 복귀 확인
-- ---------------------------------------------------------------------
CREATE TABLE qms_change_revert (
    revert_id     bigint         GENERATED ALWAYS AS IDENTITY,
    comp_cd       varchar(20)    NOT NULL,
    eco_id        bigint         NOT NULL,
    revert_type   varchar(12)    NOT NULL,
    reverted_on   date           NOT NULL,
    note          varchar(1000)  NOT NULL,
    status        varchar(6)     NOT NULL DEFAULT 'ACTIVE',
    void_reason   varchar(500),
    voided_at     timestamptz,
    voided_by     varchar(50),
    row_ver       integer        NOT NULL DEFAULT 0,
    created_at    timestamptz    NOT NULL DEFAULT now(),
    created_by    varchar(50)    NOT NULL,
    updated_at    timestamptz    NOT NULL DEFAULT now(),
    updated_by    varchar(50)    NOT NULL,
    CONSTRAINT pk_qms_change_revert PRIMARY KEY (revert_id),
    CONSTRAINT fk_qms_change_revert_comp FOREIGN KEY (comp_cd) REFERENCES sys_comp (comp_cd),
    CONSTRAINT fk_qms_change_revert_change FOREIGN KEY (eco_id) REFERENCES qms_change (eco_id),
    CONSTRAINT ck_qms_change_revert_type CHECK (revert_type IN ('RESTORED', 'SUPERSEDED')),
    CONSTRAINT ck_qms_change_revert_note CHECK (length(btrim(note)) > 0),
    CONSTRAINT ck_qms_change_revert_status CHECK (status IN ('ACTIVE', 'VOID')),
    CONSTRAINT ck_qms_change_revert_void CHECK ((status = 'VOID') = (voided_at IS NOT NULL)
        AND (status <> 'VOID' OR (coalesce(length(btrim(void_reason)), 0) > 0 AND voided_by IS NOT NULL)))
);
CREATE UNIQUE INDEX uq_qms_change_revert_live ON qms_change_revert (comp_cd, eco_id) WHERE status = 'ACTIVE';
CREATE INDEX ix_qms_change_revert_eco ON qms_change_revert (comp_cd, eco_id, revert_id);
COMMENT ON TABLE qms_change_revert IS '임시 변경 표준 복귀 확인 — TEMP·RELEASED ECO 의 4M 세부마다 살아 있는 기록 하나, 정정 = 무효 뒤 다시 (docs/29_P3 51.2)';
COMMENT ON COLUMN qms_change_revert.reverted_on IS '표준으로 돌아온 날(회사 날짜, 사람이 입력) — 적용일 ≤ 복귀일 ≤ 오늘은 서비스가 본다';

-- ---------------------------------------------------------------------
-- 2. 변경 효과 판정
-- ---------------------------------------------------------------------
CREATE TABLE qms_change_effect (
    effect_id     bigint         GENERATED ALWAYS AS IDENTITY,
    comp_cd       varchar(20)    NOT NULL,
    eco_id        bigint         NOT NULL,
    result        varchar(2)     NOT NULL,
    note          varchar(2000)  NOT NULL,
    base_date     date           NOT NULL,
    basis_n       integer        NOT NULL,
    basis_days    integer        NOT NULL,
    status        varchar(6)     NOT NULL DEFAULT 'ACTIVE',
    void_reason   varchar(500),
    voided_at     timestamptz,
    voided_by     varchar(50),
    row_ver       integer        NOT NULL DEFAULT 0,
    created_at    timestamptz    NOT NULL DEFAULT now(),
    created_by    varchar(50)    NOT NULL,
    updated_at    timestamptz    NOT NULL DEFAULT now(),
    updated_by    varchar(50)    NOT NULL,
    CONSTRAINT pk_qms_change_effect PRIMARY KEY (effect_id),
    CONSTRAINT fk_qms_change_effect_comp FOREIGN KEY (comp_cd) REFERENCES sys_comp (comp_cd),
    CONSTRAINT fk_qms_change_effect_change FOREIGN KEY (eco_id) REFERENCES qms_change (eco_id),
    CONSTRAINT ck_qms_change_effect_result CHECK (result IN ('OK', 'NG')),
    CONSTRAINT ck_qms_change_effect_note CHECK (length(btrim(note)) > 0),
    CONSTRAINT ck_qms_change_effect_basis CHECK (basis_n BETWEEN 5 AND 200 AND basis_days BETWEEN 1 AND 180),
    CONSTRAINT ck_qms_change_effect_status CHECK (status IN ('ACTIVE', 'VOID')),
    CONSTRAINT ck_qms_change_effect_void CHECK ((status = 'VOID') = (voided_at IS NOT NULL)
        AND (status <> 'VOID' OR (coalesce(length(btrim(void_reason)), 0) > 0 AND voided_by IS NOT NULL)))
);
CREATE UNIQUE INDEX uq_qms_change_effect_live ON qms_change_effect (comp_cd, eco_id) WHERE status = 'ACTIVE';
CREATE INDEX ix_qms_change_effect_eco ON qms_change_effect (comp_cd, eco_id, effect_id);
COMMENT ON TABLE qms_change_effect IS '변경 효과 판정(OK/NG) — RELEASED ECO 의 4M 세부마다 살아 있는 판정 하나, 근거 = 판정 때 본 비교 조건 사본 (docs/29_P3 51.3)';

-- ---------------------------------------------------------------------
-- 3. 트리거 — 같은 회사 4M 세부 · ECO 상태(잠금 없이) · 무효만 · 삭제 금지
-- ---------------------------------------------------------------------
CREATE FUNCTION hb_guard_qms_change_record() RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    v_kind   varchar;
    v_status varchar;
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION '% record cannot be deleted (void it)', TG_TABLE_NAME
            USING ERRCODE = '55000', CONSTRAINT = 'ck_' || TG_TABLE_NAME || '_no_delete';
    END IF;
    IF TG_OP = 'INSERT' THEN
        IF NEW.status <> 'ACTIVE' THEN
            RAISE EXCEPTION 'new % record must be ACTIVE', TG_TABLE_NAME
                USING ERRCODE = '55000', CONSTRAINT = 'ck_' || TG_TABLE_NAME || '_active';
        END IF;
        IF NOT EXISTS (SELECT 1 FROM qms_change c WHERE c.eco_id = NEW.eco_id AND c.comp_cd = NEW.comp_cd) THEN
            RAISE EXCEPTION '4M detail % does not belong to company %', NEW.eco_id, NEW.comp_cd
                USING ERRCODE = '23503', CONSTRAINT = 'fk_' || TG_TABLE_NAME || '_change_comp';
        END IF;
        -- 잠금 없이 읽기(FOR SHARE·FK 없음 — 잠금 순서). RELEASED 는 끝 상태
        SELECT e.change_kind, e.status INTO v_kind, v_status FROM plm_eco e WHERE e.eco_id = NEW.eco_id AND e.comp_cd = NEW.comp_cd;
        IF v_status IS DISTINCT FROM 'RELEASED' OR (TG_TABLE_NAME = 'qms_change_revert' AND v_kind IS DISTINCT FROM 'TEMP') THEN
            RAISE EXCEPTION '% needs a released % ECO (ECO % is % %)', TG_TABLE_NAME,
                CASE WHEN TG_TABLE_NAME = 'qms_change_revert' THEN 'TEMP' ELSE 'any' END, NEW.eco_id, v_kind, v_status
                USING ERRCODE = '55000', CONSTRAINT = 'ck_' || TG_TABLE_NAME || '_eco';
        END IF;
        RETURN NEW;
    END IF;
    -- UPDATE: ACTIVE → VOID 만, 기록 칸 고정(무효 칸·감사 칸만 바뀐다)
    IF OLD.status = 'VOID' OR NEW.status <> 'VOID'
       OR (to_jsonb(NEW) - ARRAY['status', 'void_reason', 'voided_at', 'voided_by', 'row_ver', 'updated_at', 'updated_by'])
          IS DISTINCT FROM (to_jsonb(OLD) - ARRAY['status', 'void_reason', 'voided_at', 'voided_by', 'row_ver', 'updated_at', 'updated_by']) THEN
        RAISE EXCEPTION '% record can only be voided', TG_TABLE_NAME
            USING ERRCODE = '55000', CONSTRAINT = 'ck_' || TG_TABLE_NAME || '_immutable';
    END IF;
    RETURN NEW;
END
$$;
CREATE TRIGGER tg_qms_change_revert_guard BEFORE INSERT OR UPDATE OR DELETE ON qms_change_revert
    FOR EACH ROW EXECUTE FUNCTION hb_guard_qms_change_record();
CREATE TRIGGER tg_qms_change_effect_guard BEFORE INSERT OR UPDATE OR DELETE ON qms_change_effect
    FOR EACH ROW EXECUTE FUNCTION hb_guard_qms_change_record();

-- ---------------------------------------------------------------------
-- 4. 감사 · RLS · 권한 · 보존
-- ---------------------------------------------------------------------
CREATE TRIGGER tg_audit AFTER INSERT OR UPDATE OR DELETE ON qms_change_revert FOR EACH ROW EXECUTE FUNCTION hb_audit('revert_id', '', '');
CREATE TRIGGER tg_audit AFTER INSERT OR UPDATE OR DELETE ON qms_change_effect FOR EACH ROW EXECUTE FUNCTION hb_audit('effect_id', '', '');

ALTER TABLE qms_change_revert ENABLE ROW LEVEL SECURITY;
ALTER TABLE qms_change_revert FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON qms_change_revert USING (comp_cd = hb_current_comp()) WITH CHECK (comp_cd = hb_current_comp());
ALTER TABLE qms_change_effect ENABLE ROW LEVEL SECURITY;
ALTER TABLE qms_change_effect FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON qms_change_effect USING (comp_cd = hb_current_comp()) WITH CHECK (comp_cd = hb_current_comp());

REVOKE DELETE, TRUNCATE ON qms_change_revert, qms_change_effect FROM ${app_role};

INSERT INTO sys_retention_table (table_nm, class_cd) VALUES
    ('qms_change_revert', 'QUALITY_RECORD'), ('qms_change_effect', 'QUALITY_RECORD');
