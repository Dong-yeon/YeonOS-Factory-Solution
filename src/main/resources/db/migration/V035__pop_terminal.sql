-- =====================================================================
-- V035 POP 기반 — 단말 등록·키, 작업자 PIN 잠금 (P2 5a — docs/25 4.6, 01 D4·11 D4)
--
-- - sys_terminal: 관리자가 등록한 현장 단말(공장·작업장 바인딩). 등록 코드(한 번·24시간)와 단말 키는 해시만 저장
--   폐기는 한 번만(되돌리지 않음). 키 다시 발급 = 기존 키 무효
-- - mst_worker: PIN 실패 횟수·잠금 시각(5회 → 15분)
-- - 정책 POP_PIN_REQUIRED(공장별), POP_WORKER_IDLE_MIN
-- =====================================================================

INSERT INTO sys_policy_def (policy_key, value_type, default_value, allowed_values, min_value, max_value, plant_scope_yn, desc_key) VALUES
    ('POP_PIN_REQUIRED',    'BOOL', 'N',  NULL, NULL, NULL, 'Y', 'policy.POP_PIN_REQUIRED'),
    ('POP_WORKER_IDLE_MIN', 'INT',  '30', NULL, 1,    720,  'N', 'policy.POP_WORKER_IDLE_MIN');

INSERT INTO sys_code_grp (grp_cd, grp_nm_key, comp_ext_yn) VALUES ('TERMINAL_TYPE', 'code.grp.TERMINAL_TYPE', 'N');
INSERT INTO sys_code (grp_cd, code, code_nm_key, sort_no) VALUES
    ('TERMINAL_TYPE', 'POP', 'code.TERMINAL_TYPE.POP', 1),
    ('TERMINAL_TYPE', 'PDA', 'code.TERMINAL_TYPE.PDA', 2);

ALTER TABLE mst_worker ADD COLUMN pin_fail_cnt smallint NOT NULL DEFAULT 0;
ALTER TABLE mst_worker ADD COLUMN pin_locked_until timestamptz;
ALTER TABLE mst_worker ADD CONSTRAINT ck_mst_worker_pin_fail CHECK (pin_fail_cnt BETWEEN 0 AND 100);

CREATE TABLE sys_terminal (
    terminal_id            bigint        GENERATED ALWAYS AS IDENTITY,
    comp_cd                varchar(20)   NOT NULL,
    terminal_cd            varchar(20)   NOT NULL,
    terminal_nm            varchar(100)  NOT NULL,
    plant_cd               varchar(20)   NOT NULL,
    node_id                bigint,
    terminal_type          varchar(10)   NOT NULL DEFAULT 'POP',
    activation_hash        varchar(64),
    activation_expires_at  timestamptz,
    key_hash               varchar(64),
    activated_at           timestamptz,
    last_seen_at           timestamptz,
    revoked_at             timestamptz,
    revoke_reason          varchar(500),
    remark                 varchar(500),
    row_ver                integer       NOT NULL DEFAULT 0,
    created_at             timestamptz   NOT NULL DEFAULT now(),
    created_by             varchar(50)   NOT NULL,
    updated_at             timestamptz   NOT NULL DEFAULT now(),
    updated_by             varchar(50)   NOT NULL,
    CONSTRAINT pk_sys_terminal PRIMARY KEY (terminal_id),
    CONSTRAINT fk_sys_terminal_plant FOREIGN KEY (comp_cd, plant_cd) REFERENCES sys_plant (comp_cd, plant_cd),
    CONSTRAINT fk_sys_terminal_node FOREIGN KEY (node_id) REFERENCES mst_equip_hier (node_id),
    CONSTRAINT uq_sys_terminal_cd UNIQUE (comp_cd, terminal_cd),
    CONSTRAINT ck_sys_terminal_cd CHECK (terminal_cd ~ '^[A-Z0-9][A-Z0-9_-]{0,19}$'),
    CONSTRAINT ck_sys_terminal_nm CHECK (length(btrim(terminal_nm)) > 0),
    CONSTRAINT ck_sys_terminal_type CHECK (terminal_type IN ('POP', 'PDA')),
    CONSTRAINT ck_sys_terminal_hash CHECK ((activation_hash IS NULL OR activation_hash ~ '^[0-9a-f]{64}$')
                                           AND (key_hash IS NULL OR key_hash ~ '^[0-9a-f]{64}$')),
    CONSTRAINT ck_sys_terminal_activation CHECK ((activation_hash IS NULL) = (activation_expires_at IS NULL)),
    CONSTRAINT ck_sys_terminal_revoke CHECK ((revoked_at IS NULL) = (revoke_reason IS NULL)
                                             AND (revoked_at IS NULL OR (key_hash IS NULL AND activation_hash IS NULL)))
);
COMMENT ON TABLE sys_terminal IS 'POP·PDA 단말 (docs/25 4.6, 01 D4). 등록 코드·단말 키는 sha256 해시만. 폐기는 되돌리지 않는다';
-- 키 해시는 단말마다 다르다(같은 키를 두 단말이 갖지 않게)
CREATE UNIQUE INDEX uq_sys_terminal_key ON sys_terminal (key_hash) WHERE key_hash IS NOT NULL;

CREATE FUNCTION hb_guard_terminal() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'UPDATE' THEN
        IF NEW.terminal_cd <> OLD.terminal_cd THEN
            RAISE EXCEPTION 'terminal code is fixed' USING ERRCODE = '55000', CONSTRAINT = 'ck_sys_terminal_fixed';
        END IF;
        IF OLD.revoked_at IS NOT NULL THEN
            RAISE EXCEPTION 'terminal % is revoked', OLD.terminal_cd USING ERRCODE = '55000', CONSTRAINT = 'ck_sys_terminal_revoked';
        END IF;
    END IF;
    -- 작업장: 같은 회사·같은 공장의 사용 중 라인·셀 (바뀔 때만)
    IF NEW.node_id IS NOT NULL AND (TG_OP = 'INSERT' OR NEW.node_id IS DISTINCT FROM OLD.node_id OR NEW.plant_cd <> OLD.plant_cd)
       AND NOT EXISTS (SELECT 1 FROM mst_equip_hier h WHERE h.node_id = NEW.node_id AND h.comp_cd = NEW.comp_cd
                          AND h.plant_cd = NEW.plant_cd AND h.node_type IN ('LINE', 'CELL') AND h.use_yn = 'Y') THEN
        RAISE EXCEPTION 'node % is not an active line or cell of plant %', NEW.node_id, NEW.plant_cd
            USING ERRCODE = '23503', CONSTRAINT = 'fk_sys_terminal_node_plant';
    END IF;
    RETURN NEW;
END
$$;
CREATE TRIGGER tg_sys_terminal_guard BEFORE INSERT OR UPDATE ON sys_terminal FOR EACH ROW EXECUTE FUNCTION hb_guard_terminal();
CREATE TRIGGER tg_sys_terminal_no_delete BEFORE DELETE ON sys_terminal FOR EACH ROW EXECUTE FUNCTION hb_forbid_delete_master();
-- 마지막 사용 시각은 감사에서 뺀다(요청마다 바뀜), 해시는 가림
CREATE TRIGGER tg_audit AFTER INSERT OR UPDATE OR DELETE ON sys_terminal FOR EACH ROW
    EXECUTE FUNCTION hb_audit('terminal_id', 'last_seen_at', 'activation_hash,key_hash');

ALTER TABLE sys_terminal ENABLE ROW LEVEL SECURITY;
ALTER TABLE sys_terminal FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON sys_terminal USING (comp_cd = hb_current_comp()) WITH CHECK (comp_cd = hb_current_comp());
REVOKE DELETE, TRUNCATE ON sys_terminal FROM ${app_role};

INSERT INTO sys_prog (prog_cd, prog_nm_key, view_path, module_cd, actions, sort_no) VALUES
    ('sys-terminal', 'prog.sysTerminal', 'production/terminal-mgt', 'PRD', ARRAY['SEARCH','SAVE']::varchar(20)[], 420);
INSERT INTO sys_retention_table (table_nm, class_cd) VALUES ('sys_terminal', 'MASTER');
