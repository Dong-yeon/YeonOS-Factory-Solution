-- =====================================================================
-- PLM 4단계 (docs/39 14장) — 임시 번호, 병합 때 재번호
--
-- - D4 ECO 필수 정책 PLM_ECO_REQUIRED: Y 면 BOM·라우팅 확정은 ECO 적용 경로(트랜잭션 설정 app.plm_eco_release)에서만
--   → mst_bom·mst_routing 에 BEFORE UPDATE 트리거 하나씩(남의 표 — 트리거만, 칸·데이터 변경 없음)
-- - plm_ppap / plm_ppap_elem: ECO 하나에 PPAP 패키지 하나, 18요소(요구 S/R/N·상태·첨부). 레벨별 요구 표는 넣지 않는다
-- - plm_pcn: 고객 통지(PCN) 기록 — 통지일·응답 기한·응답
-- =====================================================================

INSERT INTO sys_policy_def (policy_key, value_type, default_value, allowed_values, min_value, max_value, plant_scope_yn, desc_key) VALUES
    ('PLM_ECO_REQUIRED', 'BOOL', 'N', NULL, NULL, NULL, 'N', 'policy.PLM_ECO_REQUIRED');

INSERT INTO sys_code_grp (grp_cd, grp_nm_key, comp_ext_yn) VALUES
    ('PLM_PPAP_STATUS', 'code.grp.PLM_PPAP_STATUS', 'N'),
    ('PLM_PPAP_ELEM', 'code.grp.PLM_PPAP_ELEM', 'N'),
    ('PLM_PPAP_REQ', 'code.grp.PLM_PPAP_REQ', 'N'),
    ('PLM_PPAP_ELEM_STATUS', 'code.grp.PLM_PPAP_ELEM_STATUS', 'N'),
    ('PLM_PCN_STATUS', 'code.grp.PLM_PCN_STATUS', 'N');
INSERT INTO sys_code (grp_cd, code, code_nm_key, sort_no) VALUES
    ('PLM_PPAP_STATUS', 'DRAFT',     'code.PLM_PPAP_STATUS.DRAFT',     1),
    ('PLM_PPAP_STATUS', 'SUBMITTED', 'code.PLM_PPAP_STATUS.SUBMITTED', 2),
    ('PLM_PPAP_STATUS', 'APPROVED',  'code.PLM_PPAP_STATUS.APPROVED',  3),
    ('PLM_PPAP_STATUS', 'INTERIM',   'code.PLM_PPAP_STATUS.INTERIM',   4),
    ('PLM_PPAP_STATUS', 'REJECTED',  'code.PLM_PPAP_STATUS.REJECTED',  5),
    ('PLM_PPAP_ELEM', '1',  'code.PLM_PPAP_ELEM.1',  1),
    ('PLM_PPAP_ELEM', '2',  'code.PLM_PPAP_ELEM.2',  2),
    ('PLM_PPAP_ELEM', '3',  'code.PLM_PPAP_ELEM.3',  3),
    ('PLM_PPAP_ELEM', '4',  'code.PLM_PPAP_ELEM.4',  4),
    ('PLM_PPAP_ELEM', '5',  'code.PLM_PPAP_ELEM.5',  5),
    ('PLM_PPAP_ELEM', '6',  'code.PLM_PPAP_ELEM.6',  6),
    ('PLM_PPAP_ELEM', '7',  'code.PLM_PPAP_ELEM.7',  7),
    ('PLM_PPAP_ELEM', '8',  'code.PLM_PPAP_ELEM.8',  8),
    ('PLM_PPAP_ELEM', '9',  'code.PLM_PPAP_ELEM.9',  9),
    ('PLM_PPAP_ELEM', '10', 'code.PLM_PPAP_ELEM.10', 10),
    ('PLM_PPAP_ELEM', '11', 'code.PLM_PPAP_ELEM.11', 11),
    ('PLM_PPAP_ELEM', '12', 'code.PLM_PPAP_ELEM.12', 12),
    ('PLM_PPAP_ELEM', '13', 'code.PLM_PPAP_ELEM.13', 13),
    ('PLM_PPAP_ELEM', '14', 'code.PLM_PPAP_ELEM.14', 14),
    ('PLM_PPAP_ELEM', '15', 'code.PLM_PPAP_ELEM.15', 15),
    ('PLM_PPAP_ELEM', '16', 'code.PLM_PPAP_ELEM.16', 16),
    ('PLM_PPAP_ELEM', '17', 'code.PLM_PPAP_ELEM.17', 17),
    ('PLM_PPAP_ELEM', '18', 'code.PLM_PPAP_ELEM.18', 18),
    ('PLM_PPAP_REQ', 'S', 'code.PLM_PPAP_REQ.S', 1),
    ('PLM_PPAP_REQ', 'R', 'code.PLM_PPAP_REQ.R', 2),
    ('PLM_PPAP_REQ', 'N', 'code.PLM_PPAP_REQ.N', 3),
    ('PLM_PPAP_ELEM_STATUS', 'OPEN',   'code.PLM_PPAP_ELEM_STATUS.OPEN',   1),
    ('PLM_PPAP_ELEM_STATUS', 'DONE',   'code.PLM_PPAP_ELEM_STATUS.DONE',   2),
    ('PLM_PPAP_ELEM_STATUS', 'WAIVED', 'code.PLM_PPAP_ELEM_STATUS.WAIVED', 3),
    ('PLM_PCN_STATUS', 'SENT',     'code.PLM_PCN_STATUS.SENT',     1),
    ('PLM_PCN_STATUS', 'ACK',      'code.PLM_PCN_STATUS.ACK',      2),
    ('PLM_PCN_STATUS', 'APPROVED', 'code.PLM_PCN_STATUS.APPROVED', 3),
    ('PLM_PCN_STATUS', 'REJECTED', 'code.PLM_PCN_STATUS.REJECTED', 4);

-- ---------------------------------------------------------------------
-- 1. D4 ECO 필수 — BOM·라우팅 확정은 ECO 적용 경로에서만(정책 Y)
-- ---------------------------------------------------------------------
CREATE FUNCTION hb_guard_plm_eco_required() RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    v_val varchar(2000);
BEGIN
    IF NEW.status <> 'RELEASED' OR OLD.status = 'RELEASED' THEN
        RETURN NEW;
    END IF;
    SELECT p.policy_value INTO v_val FROM sys_policy p
     WHERE p.comp_cd = NEW.comp_cd AND p.plant_cd = '*' AND p.policy_key = 'PLM_ECO_REQUIRED';
    IF v_val IS NULL THEN
        SELECT d.default_value INTO v_val FROM sys_policy_def d WHERE d.policy_key = 'PLM_ECO_REQUIRED';
    END IF;
    IF v_val = 'Y' AND coalesce(current_setting('app.plm_eco_release', true), '') <> 'Y' THEN
        -- NEW 의 키 칸 이름이 표마다 달라(bom_id·routing_id) 메시지에는 표 이름만
        RAISE EXCEPTION '% row can be released only by an engineering change order (policy PLM_ECO_REQUIRED)', TG_TABLE_NAME
            USING ERRCODE = '55000', CONSTRAINT = 'ck_plm_eco_required';
    END IF;
    RETURN NEW;
END
$$;
COMMENT ON FUNCTION hb_guard_plm_eco_required() IS 'D4(docs/39 14.2): 정책 PLM_ECO_REQUIRED=Y 면 확정은 app.plm_eco_release=Y(ECO 적용 트랜잭션)에서만';
CREATE TRIGGER tg_plm_eco_required BEFORE UPDATE ON mst_bom FOR EACH ROW EXECUTE FUNCTION hb_guard_plm_eco_required();
CREATE TRIGGER tg_plm_eco_required BEFORE UPDATE ON mst_routing FOR EACH ROW EXECUTE FUNCTION hb_guard_plm_eco_required();

-- ---------------------------------------------------------------------
-- 2. PPAP 패키지 · 요소
-- ---------------------------------------------------------------------
CREATE TABLE plm_ppap (
    ppap_id        bigint         GENERATED ALWAYS AS IDENTITY,
    comp_cd        varchar(20)    NOT NULL,
    eco_id         bigint         NOT NULL,
    cust_id        bigint         NOT NULL,
    submit_level   smallint       NOT NULL DEFAULT 3,
    status         varchar(10)    NOT NULL DEFAULT 'DRAFT',
    submitted_on   date,
    decided_on     date,
    decision_ref   varchar(100),
    decision_note  varchar(1000),
    remark         varchar(1000),
    row_ver        integer        NOT NULL DEFAULT 0,
    created_at     timestamptz    NOT NULL DEFAULT now(),
    created_by     varchar(50)    NOT NULL,
    updated_at     timestamptz    NOT NULL DEFAULT now(),
    updated_by     varchar(50)    NOT NULL,
    CONSTRAINT pk_plm_ppap PRIMARY KEY (ppap_id),
    CONSTRAINT fk_plm_ppap_comp FOREIGN KEY (comp_cd) REFERENCES sys_comp (comp_cd),
    CONSTRAINT fk_plm_ppap_eco FOREIGN KEY (eco_id) REFERENCES plm_eco (eco_id),
    CONSTRAINT fk_plm_ppap_cust FOREIGN KEY (cust_id) REFERENCES mst_cust (cust_id),
    CONSTRAINT uq_plm_ppap_eco UNIQUE (comp_cd, eco_id),
    CONSTRAINT ck_plm_ppap_level CHECK (submit_level BETWEEN 1 AND 5),
    CONSTRAINT ck_plm_ppap_status CHECK (status IN ('DRAFT', 'SUBMITTED', 'APPROVED', 'INTERIM', 'REJECTED')),
    CONSTRAINT ck_plm_ppap_submitted CHECK (status = 'DRAFT' OR submitted_on IS NOT NULL),
    CONSTRAINT ck_plm_ppap_decided CHECK ((status IN ('APPROVED', 'INTERIM', 'REJECTED')) = (decided_on IS NOT NULL)),
    CONSTRAINT ck_plm_ppap_order CHECK (decided_on IS NULL OR decided_on >= submitted_on),
    CONSTRAINT ck_plm_ppap_approved CHECK (status <> 'APPROVED'
        OR (decision_ref IS NOT NULL AND length(btrim(decision_ref)) > 0))
);
COMMENT ON TABLE plm_ppap IS 'PPAP 패키지 (docs/39 14.4). ECO 하나에 하나, 승인 판정은 ECO 고객 승인을 채운다(칸은 ECO 에)';

CREATE FUNCTION hb_guard_plm_ppap() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM plm_eco e WHERE e.eco_id = NEW.eco_id AND e.comp_cd = NEW.comp_cd)
       OR NOT EXISTS (SELECT 1 FROM mst_cust c WHERE c.cust_id = NEW.cust_id AND c.comp_cd = NEW.comp_cd
                         AND c.cust_type IN ('SALES', 'BOTH')) THEN
        RAISE EXCEPTION 'PPAP references are not of company %', NEW.comp_cd
            USING ERRCODE = '23503', CONSTRAINT = 'fk_plm_ppap_ref_comp';
    END IF;
    IF TG_OP = 'INSERT' THEN
        IF NEW.status <> 'DRAFT' THEN
            RAISE EXCEPTION 'new PPAP must be DRAFT' USING ERRCODE = '55000', CONSTRAINT = 'ck_plm_ppap_transition';
        END IF;
        RETURN NEW;
    END IF;
    IF NEW.eco_id <> OLD.eco_id OR NEW.cust_id <> OLD.cust_id THEN
        RAISE EXCEPTION 'PPAP order and customer are fixed' USING ERRCODE = '55000', CONSTRAINT = 'ck_plm_ppap_fixed';
    END IF;
    IF OLD.status <> NEW.status AND NOT (
           (OLD.status = 'DRAFT' AND NEW.status = 'SUBMITTED')
        OR (OLD.status = 'SUBMITTED' AND NEW.status IN ('APPROVED', 'INTERIM', 'REJECTED'))
        OR (OLD.status IN ('INTERIM', 'REJECTED') AND NEW.status = 'DRAFT')) THEN
        RAISE EXCEPTION 'PPAP status % -> % is not allowed', OLD.status, NEW.status
            USING ERRCODE = '55000', CONSTRAINT = 'ck_plm_ppap_transition';
    END IF;
    IF OLD.status <> 'DRAFT' AND NEW.submit_level <> OLD.submit_level THEN
        RAISE EXCEPTION 'PPAP % is not DRAFT — level is fixed', OLD.ppap_id USING ERRCODE = '55000', CONSTRAINT = 'ck_plm_ppap_fixed';
    END IF;
    IF OLD.status = 'APPROVED' AND (NEW.decided_on IS DISTINCT FROM OLD.decided_on
           OR NEW.decision_ref IS DISTINCT FROM OLD.decision_ref OR NEW.decision_note IS DISTINCT FROM OLD.decision_note) THEN
        RAISE EXCEPTION 'approved PPAP % is closed', OLD.ppap_id USING ERRCODE = '55000', CONSTRAINT = 'ck_plm_ppap_fixed';
    END IF;
    RETURN NEW;
END
$$;
CREATE TRIGGER tg_plm_ppap_guard BEFORE INSERT OR UPDATE ON plm_ppap FOR EACH ROW EXECUTE FUNCTION hb_guard_plm_ppap();
CREATE TRIGGER tg_plm_ppap_no_delete BEFORE DELETE ON plm_ppap FOR EACH ROW EXECUTE FUNCTION hb_forbid_delete_master();

CREATE TABLE plm_ppap_elem (
    elem_id     bigint         GENERATED ALWAYS AS IDENTITY,
    comp_cd     varchar(20)    NOT NULL,
    ppap_id     bigint         NOT NULL,
    elem_no     smallint       NOT NULL,
    req_cd      char(1)        NOT NULL,
    status      varchar(10)    NOT NULL DEFAULT 'OPEN',
    file_id     bigint,
    note        varchar(1000),
    row_ver     integer        NOT NULL DEFAULT 0,
    created_at  timestamptz    NOT NULL DEFAULT now(),
    created_by  varchar(50)    NOT NULL,
    updated_at  timestamptz    NOT NULL DEFAULT now(),
    updated_by  varchar(50)    NOT NULL,
    CONSTRAINT pk_plm_ppap_elem PRIMARY KEY (elem_id),
    CONSTRAINT fk_plm_ppap_elem_ppap FOREIGN KEY (ppap_id) REFERENCES plm_ppap (ppap_id),
    CONSTRAINT fk_plm_ppap_elem_file FOREIGN KEY (file_id) REFERENCES sys_file (file_id),
    CONSTRAINT uq_plm_ppap_elem UNIQUE (comp_cd, ppap_id, elem_no),
    CONSTRAINT ck_plm_ppap_elem_no CHECK (elem_no BETWEEN 1 AND 18),
    CONSTRAINT ck_plm_ppap_elem_req CHECK (req_cd IN ('S', 'R', 'N')),
    CONSTRAINT ck_plm_ppap_elem_status CHECK (status IN ('OPEN', 'DONE', 'WAIVED'))
);
COMMENT ON TABLE plm_ppap_elem IS 'PPAP 요소 18개 (docs/39 14.4) — 요구 S 제출·R 보관·N 해당 없음, 첨부는 그 ECO 첨부 중 하나';

-- 요소는 패키지가 작성 중일 때만, 파일은 같은 ECO 의 사용 중 첨부만
CREATE FUNCTION hb_guard_plm_ppap_elem() RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    v_status varchar(10);
    v_eco    bigint;
BEGIN
    SELECT p.status, p.eco_id INTO v_status, v_eco FROM plm_ppap p WHERE p.ppap_id = NEW.ppap_id AND p.comp_cd = NEW.comp_cd;
    IF v_status IS NULL THEN
        RAISE EXCEPTION 'PPAP % is not of company %', NEW.ppap_id, NEW.comp_cd
            USING ERRCODE = '23503', CONSTRAINT = 'fk_plm_ppap_elem_comp';
    END IF;
    IF v_status <> 'DRAFT' THEN
        RAISE EXCEPTION 'elements of % PPAP % cannot be changed', v_status, NEW.ppap_id
            USING ERRCODE = '55000', CONSTRAINT = 'ck_plm_ppap_fixed';
    END IF;
    IF TG_OP = 'UPDATE' AND (NEW.ppap_id <> OLD.ppap_id OR NEW.elem_no <> OLD.elem_no) THEN
        RAISE EXCEPTION 'PPAP element is fixed' USING ERRCODE = '55000', CONSTRAINT = 'ck_plm_ppap_fixed';
    END IF;
    IF NEW.file_id IS NOT NULL AND (TG_OP = 'INSERT' OR NEW.file_id IS DISTINCT FROM OLD.file_id)
       AND NOT EXISTS (SELECT 1 FROM sys_file f WHERE f.file_id = NEW.file_id AND f.comp_cd = NEW.comp_cd
                          AND f.target_type = 'plm-eco' AND f.target_id = v_eco::text AND f.use_yn = 'Y') THEN
        RAISE EXCEPTION 'file % is not an attachment of ECO %', NEW.file_id, v_eco
            USING ERRCODE = '23503', CONSTRAINT = 'fk_plm_ppap_elem_comp';
    END IF;
    RETURN NEW;
END
$$;
CREATE TRIGGER tg_plm_ppap_elem_guard BEFORE INSERT OR UPDATE ON plm_ppap_elem FOR EACH ROW EXECUTE FUNCTION hb_guard_plm_ppap_elem();
CREATE TRIGGER tg_plm_ppap_elem_no_delete BEFORE DELETE ON plm_ppap_elem FOR EACH ROW EXECUTE FUNCTION hb_forbid_delete_master();

-- ---------------------------------------------------------------------
-- 3. 고객 통지 (PCN)
-- ---------------------------------------------------------------------
CREATE TABLE plm_pcn (
    pcn_id         bigint         GENERATED ALWAYS AS IDENTITY,
    comp_cd        varchar(20)    NOT NULL,
    pcn_no         varchar(50)    NOT NULL,
    eco_id         bigint         NOT NULL,
    cust_id        bigint         NOT NULL,
    notified_on    date           NOT NULL,
    response_due   date           NOT NULL,
    status         varchar(10)    NOT NULL DEFAULT 'SENT',
    responded_on   date,
    response_note  varchar(1000),
    note           varchar(1000),
    row_ver        integer        NOT NULL DEFAULT 0,
    created_at     timestamptz    NOT NULL DEFAULT now(),
    created_by     varchar(50)    NOT NULL,
    updated_at     timestamptz    NOT NULL DEFAULT now(),
    updated_by     varchar(50)    NOT NULL,
    CONSTRAINT pk_plm_pcn PRIMARY KEY (pcn_id),
    CONSTRAINT fk_plm_pcn_comp FOREIGN KEY (comp_cd) REFERENCES sys_comp (comp_cd),
    CONSTRAINT fk_plm_pcn_eco FOREIGN KEY (eco_id) REFERENCES plm_eco (eco_id),
    CONSTRAINT fk_plm_pcn_cust FOREIGN KEY (cust_id) REFERENCES mst_cust (cust_id),
    CONSTRAINT uq_plm_pcn_no UNIQUE (comp_cd, pcn_no),
    CONSTRAINT ck_plm_pcn_status CHECK (status IN ('SENT', 'ACK', 'APPROVED', 'REJECTED')),
    CONSTRAINT ck_plm_pcn_due CHECK (response_due >= notified_on),
    CONSTRAINT ck_plm_pcn_dates CHECK (notified_on BETWEEN DATE '2000-01-01' AND DATE '2999-12-31'
        AND response_due <= DATE '2999-12-31'),
    CONSTRAINT ck_plm_pcn_response CHECK ((status = 'SENT') = (responded_on IS NULL)),
    CONSTRAINT ck_plm_pcn_response_order CHECK (responded_on IS NULL OR responded_on >= notified_on)
);
COMMENT ON TABLE plm_pcn IS '고객 변경 통지 PCN (docs/39 14.5) — 기록만(발송 없음). 응답은 SENT 에서 한 번';
-- 같은 ECO·고객의 열린 통지는 하나
CREATE UNIQUE INDEX uq_plm_pcn_open ON plm_pcn (comp_cd, eco_id, cust_id) WHERE status = 'SENT';
CREATE INDEX ix_plm_pcn_eco ON plm_pcn (comp_cd, eco_id);

CREATE FUNCTION hb_guard_plm_pcn() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'INSERT' THEN
        IF NOT EXISTS (SELECT 1 FROM plm_eco e WHERE e.eco_id = NEW.eco_id AND e.comp_cd = NEW.comp_cd AND e.status <> 'CANCELLED')
           OR NOT EXISTS (SELECT 1 FROM mst_cust c WHERE c.cust_id = NEW.cust_id AND c.comp_cd = NEW.comp_cd
                             AND c.cust_type IN ('SALES', 'BOTH')) THEN
            RAISE EXCEPTION 'PCN references are not of company % (or ECO cancelled)', NEW.comp_cd
                USING ERRCODE = '23503', CONSTRAINT = 'fk_plm_pcn_ref_comp';
        END IF;
        IF NEW.status <> 'SENT' THEN
            RAISE EXCEPTION 'new PCN must be SENT' USING ERRCODE = '55000', CONSTRAINT = 'ck_plm_pcn_transition';
        END IF;
        RETURN NEW;
    END IF;
    -- 응답은 SENT 에서 한 번, 그 뒤 불변. 통지 내용(번호·지시·고객·통지일·기한)은 고정
    IF OLD.status <> 'SENT' OR NEW.pcn_no <> OLD.pcn_no OR NEW.eco_id <> OLD.eco_id OR NEW.cust_id <> OLD.cust_id
       OR NEW.notified_on <> OLD.notified_on OR NEW.response_due <> OLD.response_due THEN
        RAISE EXCEPTION 'PCN % is fixed', OLD.pcn_no USING ERRCODE = '55000', CONSTRAINT = 'ck_plm_pcn_transition';
    END IF;
    RETURN NEW;
END
$$;
CREATE TRIGGER tg_plm_pcn_guard BEFORE INSERT OR UPDATE ON plm_pcn FOR EACH ROW EXECUTE FUNCTION hb_guard_plm_pcn();
CREATE TRIGGER tg_plm_pcn_no_delete BEFORE DELETE ON plm_pcn FOR EACH ROW EXECUTE FUNCTION hb_forbid_delete_master();

-- ---------------------------------------------------------------------
-- 4. 감사 · RLS · 권한 · 보존
-- ---------------------------------------------------------------------
CREATE TRIGGER tg_audit AFTER INSERT OR UPDATE OR DELETE ON plm_ppap FOR EACH ROW EXECUTE FUNCTION hb_audit('ppap_id', '', '');
CREATE TRIGGER tg_audit AFTER INSERT OR UPDATE OR DELETE ON plm_ppap_elem FOR EACH ROW EXECUTE FUNCTION hb_audit('elem_id', '', '');
CREATE TRIGGER tg_audit AFTER INSERT OR UPDATE OR DELETE ON plm_pcn FOR EACH ROW EXECUTE FUNCTION hb_audit('pcn_id', '', '');

ALTER TABLE plm_ppap ENABLE ROW LEVEL SECURITY;
ALTER TABLE plm_ppap FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON plm_ppap USING (comp_cd = hb_current_comp()) WITH CHECK (comp_cd = hb_current_comp());
ALTER TABLE plm_ppap_elem ENABLE ROW LEVEL SECURITY;
ALTER TABLE plm_ppap_elem FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON plm_ppap_elem USING (comp_cd = hb_current_comp()) WITH CHECK (comp_cd = hb_current_comp());
ALTER TABLE plm_pcn ENABLE ROW LEVEL SECURITY;
ALTER TABLE plm_pcn FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON plm_pcn USING (comp_cd = hb_current_comp()) WITH CHECK (comp_cd = hb_current_comp());
REVOKE DELETE, TRUNCATE ON plm_ppap, plm_ppap_elem, plm_pcn FROM ${app_role};

INSERT INTO sys_retention_table (table_nm, class_cd) VALUES
    ('plm_ppap', 'MASTER'), ('plm_ppap_elem', 'MASTER'), ('plm_pcn', 'MASTER');
