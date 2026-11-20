-- =====================================================================
-- P3 QMS 2부 Q10 SPC 최소형 (docs/29_P3 12.6) — 임시 번호, 병합 때 재번호
--
-- - 관리도는 Q6 측정값 원장에서 조회 때 계산(부분군 = 판정 확정된 검사 행, 살아 있는 값만) — 표 없음
-- - qms_spc_limit: 고정 관리한계(관리도 단위 = 품목 × 특성 × 검사 유형 × 공정) — 중심·군내 σ̂·산출 구간·활성 규칙
-- - 단위마다 살아 있는 한계 하나(부분 유일, 공정 없음도 같은 값으로), 정정 = 무효(사유) 뒤 다시. 삭제 금지
-- - 관리도 조회용 인덱스(검사 머리 품목·유형, 검사 행 특성)
-- =====================================================================

INSERT INTO sys_code_grp (grp_cd, grp_nm_key, comp_ext_yn) VALUES
    ('QMS_SPC_STATUS', 'code.grp.QMS_SPC_STATUS', 'N');
INSERT INTO sys_code (grp_cd, code, code_nm_key, sort_no) VALUES
    ('QMS_SPC_STATUS', 'ACTIVE', 'code.QMS_SPC_STATUS.ACTIVE', 1),
    ('QMS_SPC_STATUS', 'VOID',   'code.QMS_SPC_STATUS.VOID',   2);

CREATE TABLE qms_spc_limit (
    limit_id      bigint         GENERATED ALWAYS AS IDENTITY,
    comp_cd       varchar(20)    NOT NULL,
    item_id       bigint         NOT NULL,
    char_id       bigint         NOT NULL,
    insp_type     varchar(10)    NOT NULL,
    proc_id       bigint,
    center        numeric(24,10) NOT NULL,
    sigma         numeric(24,10) NOT NULL,
    subgroup_cnt  integer        NOT NULL,
    from_at       timestamptz    NOT NULL,
    to_at         timestamptz    NOT NULL,
    rules         varchar(30)    NOT NULL,
    status        varchar(6)     NOT NULL DEFAULT 'ACTIVE',
    remark        varchar(1000),
    void_reason   varchar(500),
    voided_at     timestamptz,
    voided_by     varchar(50),
    row_ver       integer        NOT NULL DEFAULT 0,
    created_at    timestamptz    NOT NULL DEFAULT now(),
    created_by    varchar(50)    NOT NULL,
    updated_at    timestamptz    NOT NULL DEFAULT now(),
    updated_by    varchar(50)    NOT NULL,
    CONSTRAINT pk_qms_spc_limit PRIMARY KEY (limit_id),
    CONSTRAINT fk_qms_spc_limit_comp FOREIGN KEY (comp_cd) REFERENCES sys_comp (comp_cd),
    CONSTRAINT fk_qms_spc_limit_item FOREIGN KEY (item_id) REFERENCES mst_item (item_id),
    CONSTRAINT fk_qms_spc_limit_char FOREIGN KEY (char_id) REFERENCES qms_char (char_id),
    CONSTRAINT fk_qms_spc_limit_proc FOREIGN KEY (proc_id) REFERENCES mst_proc (proc_id),
    CONSTRAINT ck_qms_spc_limit_type CHECK (insp_type IN ('RECEIVE', 'PROCESS', 'FINAL', 'FIRST_OFF', 'LAST_OFF', 'PATROL')),
    CONSTRAINT ck_qms_spc_limit_value CHECK (sigma > 0 AND subgroup_cnt >= 1 AND from_at <= to_at),
    -- 활성 규칙: WE1~WE4 를 쉼표로(순서대로, 하나 이상)
    CONSTRAINT ck_qms_spc_limit_rules CHECK (rules ~ '^WE[1-4](,WE[1-4]){0,3}$'),
    CONSTRAINT ck_qms_spc_limit_status CHECK (status IN ('ACTIVE', 'VOID')),
    CONSTRAINT ck_qms_spc_limit_void CHECK ((status = 'VOID') = (voided_at IS NOT NULL)
        AND (status <> 'VOID' OR (coalesce(length(btrim(void_reason)), 0) > 0 AND voided_by IS NOT NULL)))
);
CREATE UNIQUE INDEX uq_qms_spc_limit_live ON qms_spc_limit (comp_cd, item_id, char_id, insp_type, proc_id) NULLS NOT DISTINCT
    WHERE status = 'ACTIVE';
CREATE INDEX ix_qms_spc_limit_created ON qms_spc_limit (comp_cd, created_at DESC);
COMMENT ON TABLE qms_spc_limit IS 'SPC 고정 관리한계 — 품목 × 특성 × 검사 유형 × 공정마다 살아 있는 한계 하나, 정정 = 무효 뒤 다시 (docs/29_P3 Q10)';
COMMENT ON COLUMN qms_spc_limit.sigma IS '군내 σ̂ = 평균(R/d2(n)) — X̄ 한계 = center ± 3σ̂/√n, R 한계 = (d2 ± 3d3)σ̂';
COMMENT ON COLUMN qms_spc_limit.from_at IS '한계를 낸 부분군 구간의 첫 부분군 첫 측정 시각 (to_at = 마지막 부분군)';

CREATE FUNCTION hb_guard_qms_spc_limit() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'SPC limit cannot be deleted (void it)' USING ERRCODE = '55000', CONSTRAINT = 'ck_qms_spc_limit_no_delete';
    END IF;
    IF TG_OP = 'INSERT' THEN
        IF NEW.status <> 'ACTIVE' THEN
            RAISE EXCEPTION 'new SPC limit must be ACTIVE' USING ERRCODE = '55000', CONSTRAINT = 'ck_qms_spc_limit_active';
        END IF;
        IF NOT EXISTS (SELECT 1 FROM mst_item i WHERE i.item_id = NEW.item_id AND i.comp_cd = NEW.comp_cd) THEN
            RAISE EXCEPTION 'item % does not belong to company %', NEW.item_id, NEW.comp_cd USING ERRCODE = '23503', CONSTRAINT = 'fk_qms_spc_limit_item_comp';
        END IF;
        IF NOT EXISTS (SELECT 1 FROM qms_char c WHERE c.char_id = NEW.char_id AND c.comp_cd = NEW.comp_cd AND c.data_type = 'VARIABLE') THEN
            RAISE EXCEPTION 'char % is not a variable characteristic of company %', NEW.char_id, NEW.comp_cd
                USING ERRCODE = '23503', CONSTRAINT = 'fk_qms_spc_limit_char_comp';
        END IF;
        IF NEW.proc_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM mst_proc p WHERE p.proc_id = NEW.proc_id AND p.comp_cd = NEW.comp_cd) THEN
            RAISE EXCEPTION 'process % does not belong to company %', NEW.proc_id, NEW.comp_cd USING ERRCODE = '23503', CONSTRAINT = 'fk_qms_spc_limit_proc_comp';
        END IF;
        RETURN NEW;
    END IF;
    -- UPDATE: ACTIVE → VOID 만, 그 밖 칸 고정
    IF (NEW.comp_cd, NEW.item_id, NEW.char_id, NEW.insp_type, NEW.proc_id, NEW.center, NEW.sigma, NEW.subgroup_cnt, NEW.from_at, NEW.to_at,
        NEW.rules, NEW.remark, NEW.created_at, NEW.created_by)
       IS DISTINCT FROM (OLD.comp_cd, OLD.item_id, OLD.char_id, OLD.insp_type, OLD.proc_id, OLD.center, OLD.sigma, OLD.subgroup_cnt, OLD.from_at,
        OLD.to_at, OLD.rules, OLD.remark, OLD.created_at, OLD.created_by)
       OR OLD.status = 'VOID' OR NEW.status <> 'VOID' THEN
        RAISE EXCEPTION 'SPC limit % can only be voided', OLD.limit_id USING ERRCODE = '55000', CONSTRAINT = 'ck_qms_spc_limit_immutable';
    END IF;
    RETURN NEW;
END
$$;
CREATE TRIGGER tg_qms_spc_limit_guard BEFORE INSERT OR UPDATE OR DELETE ON qms_spc_limit FOR EACH ROW EXECUTE FUNCTION hb_guard_qms_spc_limit();

CREATE TRIGGER tg_audit AFTER INSERT OR UPDATE OR DELETE ON qms_spc_limit FOR EACH ROW EXECUTE FUNCTION hb_audit('limit_id', '', '');

ALTER TABLE qms_spc_limit ENABLE ROW LEVEL SECURITY;
ALTER TABLE qms_spc_limit FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON qms_spc_limit USING (comp_cd = hb_current_comp()) WITH CHECK (comp_cd = hb_current_comp());

REVOKE DELETE, TRUNCATE ON qms_spc_limit FROM ${app_role};

-- 관리도 조회: 품목·유형의 판정 확정 검사 → 그 특성 검사 행
CREATE INDEX ix_qms_insp_spc ON qms_insp (comp_cd, item_id, insp_type) WHERE status = 'JUDGED';
CREATE INDEX ix_qms_insp_line_char ON qms_insp_line (comp_cd, char_id);

INSERT INTO sys_prog (prog_cd, prog_nm_key, view_path, module_cd, actions, sort_no) VALUES
    ('qms-spc', 'prog.qmsSpc', 'qms/spc', 'QMS', ARRAY['SEARCH','SAVE','DELETE']::varchar(20)[], 696);
INSERT INTO sys_retention_table (table_nm, class_cd) VALUES ('qms_spc_limit', 'QUALITY_RECORD');
