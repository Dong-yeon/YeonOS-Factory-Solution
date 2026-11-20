-- =====================================================================
-- 장비 연동 E0 — 연결 방식·논리 태그·명령 4단·연동 사건·하트비트 (docs/65 16장, 결정 Q3)
--
-- - cms_equip_link: 설비 1:1 연동 마스터(P1 mst_equip 은 고치지 않는다). conn_mode DIRECT(서버가 LAN 어댑터로 밀어 보냄, 설치형) /
--   EDGE(공장 안 엣지가 바깥으로 당겨 감, SaaS). terminal_id = 그 설비를 대신 말하는 장치(sys_terminal COLLECTOR) — 받는 길은 두 형태가 같다.
--   실행 칸(last_seen_at·last_seq·pack_*·lost_at)은 감사 무시 칸 — "지금 끊겼나"는 저장하지 않고 조회 때 판정, lost_at 은 알린 시각
-- - cms_equip_tag: 논리 태그 쓰기 허용 목록(서버 1차 검사 — 엣지가 2차). Modbus 주소는 서버에 없다
-- - cms_equip_cmd: 명령(의도) + 큐·상태. cms_equip_cmd_step: 4단 원장(INTENT·SENT·CLAIM·WRITE·READBACK·RESULT·TIMEOUT·RESOLVE, 추가만)
-- - cms_equip_link_log: 연동 사건 원장(GAP·REORDER·STALE·CONFLICT·CLOCK_SKEW·LOST·BACK·BIRTH·DENIED, 추가만)
-- - cms_equip_consumable: 소모품 마지막 수준(바뀔 때만 알람) / cms_edge_session: 장치별 bootId·마지막 하트비트·시계 차이
-- - (남의 표 변경, MES P2 6단계) cms_equip_state_log.pack_state — PackML 상태를 같은 상태 원장에(Q3). null 허용: 기존 경로·행 영향 없음
-- - (남의 표 변경, P1 기준정보) mst_ext_system.conn_mode — WES·Fleet(E4·E5)이 쓸 자리, 기본 DIRECT
-- 임시 번호(docs/29 2.2, CMS 대역) — 병합 때 이름만 바뀐다.
-- =====================================================================

INSERT INTO sys_policy_def (policy_key, value_type, default_value, allowed_values, min_value, max_value, desc_key) VALUES
    ('EQUIP_HB_MISS',         'INT', '3',   NULL, 2, 20,    'policy.EQUIP_HB_MISS'),
    ('EDGE_CLOCK_SKEW_SEC',   'INT', '60',  NULL, 1, 3600,  'policy.EDGE_CLOCK_SKEW_SEC'),
    ('EQUIP_CMD_TIMEOUT_SEC', 'INT', '120', NULL, 5, 86400, 'policy.EQUIP_CMD_TIMEOUT_SEC');

INSERT INTO sys_code_grp (grp_cd, grp_nm_key, comp_ext_yn) VALUES
    ('EQUIP_CONN_MODE',  'code.grp.EQUIP_CONN_MODE',  'N'),
    ('PACKML_STATE',     'code.grp.PACKML_STATE',     'N'),
    ('EQUIP_CMD_STATUS', 'code.grp.EQUIP_CMD_STATUS', 'N'),
    ('EQUIP_LINK_EVENT', 'code.grp.EQUIP_LINK_EVENT', 'N');
INSERT INTO sys_code (grp_cd, code, code_nm_key, sort_no) VALUES
    ('EQUIP_CONN_MODE', 'DIRECT', 'code.EQUIP_CONN_MODE.DIRECT', 1),
    ('EQUIP_CONN_MODE', 'EDGE',   'code.EQUIP_CONN_MODE.EDGE',   2),
    ('EQUIP_CONN_MODE', 'AGENT',  'code.EQUIP_CONN_MODE.AGENT',  3),
    ('PACKML_STATE', 'IDLE',      'code.PACKML_STATE.IDLE',      1),
    ('PACKML_STATE', 'STARTING',  'code.PACKML_STATE.STARTING',  2),
    ('PACKML_STATE', 'EXECUTE',   'code.PACKML_STATE.EXECUTE',   3),
    ('PACKML_STATE', 'HELD',      'code.PACKML_STATE.HELD',      4),
    ('PACKML_STATE', 'SUSPENDED', 'code.PACKML_STATE.SUSPENDED', 5),
    ('PACKML_STATE', 'STOPPED',   'code.PACKML_STATE.STOPPED',   6),
    ('PACKML_STATE', 'ABORTED',   'code.PACKML_STATE.ABORTED',   7),
    ('PACKML_STATE', 'COMPLETE',  'code.PACKML_STATE.COMPLETE',  8),
    ('EQUIP_CMD_STATUS', 'REQUESTED', 'code.EQUIP_CMD_STATUS.REQUESTED', 1),
    ('EQUIP_CMD_STATUS', 'SENT',      'code.EQUIP_CMD_STATUS.SENT',      2),
    ('EQUIP_CMD_STATUS', 'CLAIMED',   'code.EQUIP_CMD_STATUS.CLAIMED',   3),
    ('EQUIP_CMD_STATUS', 'WRITTEN',   'code.EQUIP_CMD_STATUS.WRITTEN',   4),
    ('EQUIP_CMD_STATUS', 'DONE',      'code.EQUIP_CMD_STATUS.DONE',      5),
    ('EQUIP_CMD_STATUS', 'FAILED',    'code.EQUIP_CMD_STATUS.FAILED',    6),
    ('EQUIP_CMD_STATUS', 'REJECTED',  'code.EQUIP_CMD_STATUS.REJECTED',  7),
    ('EQUIP_CMD_STATUS', 'EXPIRED',   'code.EQUIP_CMD_STATUS.EXPIRED',   8),
    ('EQUIP_CMD_STATUS', 'UNKNOWN',   'code.EQUIP_CMD_STATUS.UNKNOWN',   9),
    ('EQUIP_LINK_EVENT', 'GAP',        'code.EQUIP_LINK_EVENT.GAP',        1),
    ('EQUIP_LINK_EVENT', 'REORDER',    'code.EQUIP_LINK_EVENT.REORDER',    2),
    ('EQUIP_LINK_EVENT', 'STALE',      'code.EQUIP_LINK_EVENT.STALE',      3),
    ('EQUIP_LINK_EVENT', 'CONFLICT',   'code.EQUIP_LINK_EVENT.CONFLICT',   4),
    ('EQUIP_LINK_EVENT', 'CLOCK_SKEW', 'code.EQUIP_LINK_EVENT.CLOCK_SKEW', 5),
    ('EQUIP_LINK_EVENT', 'LOST',       'code.EQUIP_LINK_EVENT.LOST',       6),
    ('EQUIP_LINK_EVENT', 'BACK',       'code.EQUIP_LINK_EVENT.BACK',       7),
    ('EQUIP_LINK_EVENT', 'BIRTH',      'code.EQUIP_LINK_EVENT.BIRTH',      8),
    ('EQUIP_LINK_EVENT', 'DENIED',     'code.EQUIP_LINK_EVENT.DENIED',     9);

-- ---------------------------------------------------------------------
-- 1. 설비 연동 마스터 (1:1)
-- ---------------------------------------------------------------------
CREATE TABLE cms_equip_link (
    equip_id          bigint        NOT NULL,
    comp_cd           varchar(20)   NOT NULL,
    conn_mode         varchar(10)   NOT NULL,
    terminal_id       bigint        NOT NULL,
    adapter_url       varchar(200),
    hb_sec            integer       NOT NULL DEFAULT 5,
    write_enabled_yn  char(1)       NOT NULL DEFAULT 'N',
    use_yn            char(1)       NOT NULL DEFAULT 'Y',
    remark            varchar(500),
    -- 실행 칸(감사 무시)
    last_seen_at      timestamptz,
    last_seq          bigint,
    pack_state        varchar(10),
    pack_mode         varchar(12),
    pack_at           timestamptz,
    lost_at           timestamptz,
    row_ver           integer       NOT NULL DEFAULT 0,
    created_at        timestamptz   NOT NULL DEFAULT now(),
    created_by        varchar(50)   NOT NULL,
    updated_at        timestamptz   NOT NULL DEFAULT now(),
    updated_by        varchar(50)   NOT NULL,
    CONSTRAINT pk_cms_equip_link PRIMARY KEY (equip_id),
    CONSTRAINT fk_cms_equip_link_equip FOREIGN KEY (equip_id) REFERENCES mst_equip (equip_id),
    CONSTRAINT fk_cms_equip_link_terminal FOREIGN KEY (terminal_id) REFERENCES sys_terminal (terminal_id),
    CONSTRAINT ck_cms_equip_link_mode CHECK (conn_mode IN ('DIRECT', 'EDGE')),
    -- 어댑터 주소는 DIRECT 만(서버가 밀어 보낼 곳). 형식·사설 대역은 서비스가(저장 때 형식, 보낼 때 해석 결과)
    CONSTRAINT ck_cms_equip_link_url CHECK (adapter_url IS NULL OR (conn_mode = 'DIRECT' AND adapter_url ~ '^https?://[^\s/]+(:[0-9]{1,5})?(/[^\s]*)?$')),
    CONSTRAINT ck_cms_equip_link_hb CHECK (hb_sec BETWEEN 1 AND 3600),
    CONSTRAINT ck_cms_equip_link_yn CHECK (write_enabled_yn IN ('Y', 'N') AND use_yn IN ('Y', 'N')),
    CONSTRAINT ck_cms_equip_link_seq CHECK (last_seq IS NULL OR last_seq >= 0),
    CONSTRAINT ck_cms_equip_link_pack CHECK (pack_state IS NULL OR pack_state IN
        ('IDLE', 'STARTING', 'EXECUTE', 'HELD', 'SUSPENDED', 'STOPPED', 'ABORTED', 'COMPLETE')),
    CONSTRAINT ck_cms_equip_link_pack_mode CHECK (pack_mode IS NULL OR pack_mode IN ('PRODUCTION', 'MAINTENANCE', 'MANUAL'))
);
CREATE UNIQUE INDEX uq_cms_equip_link_comp ON cms_equip_link (comp_cd, equip_id);
CREATE INDEX ix_cms_equip_link_terminal ON cms_equip_link (comp_cd, terminal_id);
COMMENT ON TABLE cms_equip_link IS '설비 연동 (docs/65 16.1) — 연결 방식 DIRECT/EDGE·대신 말하는 장치·하트비트 주기·쓰기 활성화. 실행 칸은 감사 무시';
COMMENT ON COLUMN cms_equip_link.lost_at IS '끊김을 알린 시각(두 번 알리지 않게). 지금 끊겼는지는 last_seen_at + hb_sec × EQUIP_HB_MISS 로 조회 때 판정';

CREATE FUNCTION hb_guard_cms_equip_link() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'UPDATE' AND (NEW.equip_id <> OLD.equip_id OR NEW.comp_cd <> OLD.comp_cd) THEN
        RAISE EXCEPTION 'equipment link key is fixed' USING ERRCODE = '55000', CONSTRAINT = 'ck_cms_equip_link_fixed';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM mst_equip e WHERE e.equip_id = NEW.equip_id AND e.comp_cd = NEW.comp_cd) THEN
        RAISE EXCEPTION 'equipment % is not of company %', NEW.equip_id, NEW.comp_cd
            USING ERRCODE = '23503', CONSTRAINT = 'fk_cms_equip_link_ctx_comp';
    END IF;
    -- 대신 말하는 장치 = 같은 회사의 수집 장치(COLLECTOR)만 — POP·PDA 쿠키 단말은 아니다
    IF (TG_OP = 'INSERT' OR NEW.terminal_id <> OLD.terminal_id)
       AND NOT EXISTS (SELECT 1 FROM sys_terminal t WHERE t.terminal_id = NEW.terminal_id AND t.comp_cd = NEW.comp_cd
                          AND t.terminal_type = 'COLLECTOR') THEN
        RAISE EXCEPTION 'terminal % is not a collector of company %', NEW.terminal_id, NEW.comp_cd
            USING ERRCODE = '23503', CONSTRAINT = 'fk_cms_equip_link_terminal_comp';
    END IF;
    RETURN NEW;
END
$$;
CREATE TRIGGER tg_cms_equip_link_guard BEFORE INSERT OR UPDATE ON cms_equip_link FOR EACH ROW EXECUTE FUNCTION hb_guard_cms_equip_link();
CREATE TRIGGER tg_cms_equip_link_no_delete BEFORE DELETE ON cms_equip_link FOR EACH ROW EXECUTE FUNCTION hb_forbid_delete_master();
CREATE TRIGGER tg_audit AFTER INSERT OR UPDATE OR DELETE ON cms_equip_link FOR EACH ROW
    EXECUTE FUNCTION hb_audit('equip_id', 'last_seen_at,last_seq,pack_state,pack_mode,pack_at,lost_at', '');

-- ---------------------------------------------------------------------
-- 2. 논리 태그 쓰기 허용 목록
-- ---------------------------------------------------------------------
CREATE TABLE cms_equip_tag (
    tag_id          bigint         GENERATED ALWAYS AS IDENTITY,
    comp_cd         varchar(20)    NOT NULL,
    equip_id        bigint         NOT NULL,
    tag_cd          varchar(30)    NOT NULL,
    tag_nm          varchar(100)   NOT NULL,
    data_type       varchar(10)    NOT NULL,
    min_value       numeric(18,6),
    max_value       numeric(18,6),
    unit            varchar(20),
    allowed_states  varchar(10)[],
    writable_yn     char(1)        NOT NULL DEFAULT 'Y',
    use_yn          char(1)        NOT NULL DEFAULT 'Y',
    row_ver         integer        NOT NULL DEFAULT 0,
    created_at      timestamptz    NOT NULL DEFAULT now(),
    created_by      varchar(50)    NOT NULL,
    updated_at      timestamptz    NOT NULL DEFAULT now(),
    updated_by      varchar(50)    NOT NULL,
    CONSTRAINT pk_cms_equip_tag PRIMARY KEY (tag_id),
    CONSTRAINT fk_cms_equip_tag_link FOREIGN KEY (equip_id) REFERENCES cms_equip_link (equip_id),
    CONSTRAINT uq_cms_equip_tag_cd UNIQUE (comp_cd, equip_id, tag_cd),
    CONSTRAINT ck_cms_equip_tag_cd CHECK (tag_cd ~ '^[A-Z][A-Z0-9_.]{0,29}$'),
    CONSTRAINT ck_cms_equip_tag_nm CHECK (length(btrim(tag_nm)) > 0),
    CONSTRAINT ck_cms_equip_tag_type CHECK (data_type IN ('NUM', 'BOOL')),
    CONSTRAINT ck_cms_equip_tag_range CHECK (
        (data_type = 'BOOL' AND min_value IS NULL AND max_value IS NULL)
        OR (data_type = 'NUM' AND min_value IS NOT NULL AND max_value IS NOT NULL AND min_value <= max_value)),
    CONSTRAINT ck_cms_equip_tag_states CHECK (allowed_states IS NULL OR allowed_states <@
        ARRAY['IDLE', 'STARTING', 'EXECUTE', 'HELD', 'SUSPENDED', 'STOPPED', 'ABORTED', 'COMPLETE']::varchar(10)[]),
    CONSTRAINT ck_cms_equip_tag_yn CHECK (writable_yn IN ('Y', 'N') AND use_yn IN ('Y', 'N'))
);
COMMENT ON TABLE cms_equip_tag IS '논리 태그 쓰기 허용 목록 (docs/65 16.4) — 형식·범위·쓸 수 있는 PackML 상태(인터록). 주소 대응은 엣지 설정';
COMMENT ON COLUMN cms_equip_tag.allowed_states IS '이 PackML 상태일 때만 쓰기(비면 상태 무관) — 서버 1차, 엣지·PLC 가 최종';

CREATE FUNCTION hb_guard_cms_equip_tag() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'UPDATE' AND (NEW.equip_id <> OLD.equip_id OR NEW.tag_cd <> OLD.tag_cd OR NEW.comp_cd <> OLD.comp_cd
                             OR NEW.data_type <> OLD.data_type) THEN
        RAISE EXCEPTION 'tag key and type are fixed' USING ERRCODE = '55000', CONSTRAINT = 'ck_cms_equip_tag_fixed';
    END IF;
    IF TG_OP = 'INSERT' AND NOT EXISTS (SELECT 1 FROM cms_equip_link l WHERE l.equip_id = NEW.equip_id AND l.comp_cd = NEW.comp_cd) THEN
        RAISE EXCEPTION 'equipment link % is not of company %', NEW.equip_id, NEW.comp_cd
            USING ERRCODE = '23503', CONSTRAINT = 'fk_cms_equip_tag_ctx_comp';
    END IF;
    RETURN NEW;
END
$$;
CREATE TRIGGER tg_cms_equip_tag_guard BEFORE INSERT OR UPDATE ON cms_equip_tag FOR EACH ROW EXECUTE FUNCTION hb_guard_cms_equip_tag();
CREATE TRIGGER tg_cms_equip_tag_no_delete BEFORE DELETE ON cms_equip_tag FOR EACH ROW EXECUTE FUNCTION hb_forbid_delete_master();
CREATE TRIGGER tg_audit AFTER INSERT OR UPDATE OR DELETE ON cms_equip_tag FOR EACH ROW EXECUTE FUNCTION hb_audit('tag_id', '', '');

-- ---------------------------------------------------------------------
-- 3. 명령(의도·큐·상태)
-- ---------------------------------------------------------------------
CREATE TABLE cms_equip_cmd (
    cmd_id        bigint        GENERATED ALWAYS AS IDENTITY,
    comp_cd       varchar(20)   NOT NULL,
    cmd_key       uuid          NOT NULL,
    equip_id      bigint        NOT NULL,
    tag_id        bigint        NOT NULL,
    tag_cd        varchar(30)   NOT NULL,
    cmd_value     varchar(50)   NOT NULL,
    reason        varchar(500)  NOT NULL,
    conn_mode     varchar(10)   NOT NULL,
    terminal_id   bigint        NOT NULL,
    status        varchar(10)   NOT NULL DEFAULT 'REQUESTED',
    result_cd     varchar(50),
    readback      varchar(50),
    requested_at  timestamptz   NOT NULL DEFAULT now(),
    requested_by  varchar(50)   NOT NULL,
    expires_at    timestamptz   NOT NULL,
    claimed_at    timestamptz,
    lease_until   timestamptz,
    finished_at   timestamptz,
    CONSTRAINT pk_cms_equip_cmd PRIMARY KEY (cmd_id),
    CONSTRAINT uq_cms_equip_cmd_key UNIQUE (cmd_key),
    CONSTRAINT fk_cms_equip_cmd_tag FOREIGN KEY (tag_id) REFERENCES cms_equip_tag (tag_id),
    CONSTRAINT fk_cms_equip_cmd_link FOREIGN KEY (equip_id) REFERENCES cms_equip_link (equip_id),
    CONSTRAINT fk_cms_equip_cmd_terminal FOREIGN KEY (terminal_id) REFERENCES sys_terminal (terminal_id),
    CONSTRAINT ck_cms_equip_cmd_status CHECK (status IN
        ('REQUESTED', 'SENT', 'CLAIMED', 'WRITTEN', 'DONE', 'FAILED', 'REJECTED', 'EXPIRED', 'UNKNOWN')),
    CONSTRAINT ck_cms_equip_cmd_mode CHECK (conn_mode IN ('DIRECT', 'EDGE')),
    CONSTRAINT ck_cms_equip_cmd_reason CHECK (length(btrim(reason)) > 0),
    CONSTRAINT ck_cms_equip_cmd_finished CHECK ((status IN ('DONE', 'FAILED', 'REJECTED', 'EXPIRED', 'UNKNOWN')) = (finished_at IS NOT NULL)),
    CONSTRAINT ck_cms_equip_cmd_claim CHECK (status <> 'CLAIMED' OR (claimed_at IS NOT NULL AND lease_until IS NOT NULL))
);
-- 같은 설비·태그에 진행 중 명령은 하나(서로 덮어쓰는 쓰기를 막는다)
CREATE UNIQUE INDEX uq_cms_equip_cmd_active ON cms_equip_cmd (comp_cd, equip_id, tag_cd)
    WHERE status IN ('REQUESTED', 'SENT', 'CLAIMED', 'WRITTEN');
CREATE INDEX ix_cms_equip_cmd_queue ON cms_equip_cmd (comp_cd, terminal_id, cmd_id) WHERE status = 'REQUESTED';
CREATE INDEX ix_cms_equip_cmd_equip ON cms_equip_cmd (comp_cd, equip_id, requested_at);
COMMENT ON TABLE cms_equip_cmd IS '장비 명령 (docs/65 16.4) — 의도 + 큐(EDGE 는 엣지가 lease 로 가져감)·상태. 이력은 단계 원장';
COMMENT ON COLUMN cms_equip_cmd.cmd_key IS '엣지·어댑터가 결과 보고에 쓰는 멱등 키(재전송돼도 같은 값)';

CREATE FUNCTION hb_guard_cms_equip_cmd() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'INSERT' THEN
        IF NEW.status <> 'REQUESTED'
           OR NOT EXISTS (SELECT 1 FROM cms_equip_tag g WHERE g.tag_id = NEW.tag_id AND g.comp_cd = NEW.comp_cd
                             AND g.equip_id = NEW.equip_id AND g.tag_cd = NEW.tag_cd)
           OR NOT EXISTS (SELECT 1 FROM sys_terminal t WHERE t.terminal_id = NEW.terminal_id AND t.comp_cd = NEW.comp_cd) THEN
            RAISE EXCEPTION 'equipment command context is not of company %', NEW.comp_cd
                USING ERRCODE = '23503', CONSTRAINT = 'fk_cms_equip_cmd_ctx_comp';
        END IF;
        RETURN NEW;
    END IF;
    -- 의도 칸은 고정
    IF (NEW.cmd_key, NEW.comp_cd, NEW.equip_id, NEW.tag_id, NEW.tag_cd, NEW.cmd_value, NEW.reason, NEW.conn_mode, NEW.terminal_id,
        NEW.requested_at, NEW.requested_by, NEW.expires_at)
       IS DISTINCT FROM
       (OLD.cmd_key, OLD.comp_cd, OLD.equip_id, OLD.tag_id, OLD.tag_cd, OLD.cmd_value, OLD.reason, OLD.conn_mode, OLD.terminal_id,
        OLD.requested_at, OLD.requested_by, OLD.expires_at) THEN
        RAISE EXCEPTION 'command % intent is fixed', OLD.cmd_id USING ERRCODE = '55000', CONSTRAINT = 'ck_cms_equip_cmd_fixed';
    END IF;
    -- 끝난 명령은 바뀌지 않는다. 단 UNKNOWN(결과 불명)은 늦게 온 결과·사람 확인으로 한 번 확정할 수 있다
    IF OLD.status IN ('DONE', 'FAILED', 'REJECTED', 'EXPIRED')
       OR (OLD.status = 'UNKNOWN' AND NEW.status NOT IN ('UNKNOWN', 'DONE', 'FAILED', 'REJECTED')) THEN
        IF NEW.status IS DISTINCT FROM OLD.status OR NEW.result_cd IS DISTINCT FROM OLD.result_cd
           OR NEW.readback IS DISTINCT FROM OLD.readback THEN
            RAISE EXCEPTION 'command % is finished', OLD.cmd_id USING ERRCODE = '55000', CONSTRAINT = 'ck_cms_equip_cmd_finished_fixed';
        END IF;
    END IF;
    RETURN NEW;
END
$$;
CREATE TRIGGER tg_cms_equip_cmd_guard BEFORE INSERT OR UPDATE ON cms_equip_cmd FOR EACH ROW EXECUTE FUNCTION hb_guard_cms_equip_cmd();

-- ---------------------------------------------------------------------
-- 4. 명령 단계 원장 — 의도·보냄·가져감·쓰기·다시 읽기·결과(추가만)
-- ---------------------------------------------------------------------
CREATE TABLE cms_equip_cmd_step (
    step_id         bigint        GENERATED ALWAYS AS IDENTITY,
    comp_cd         varchar(20)   NOT NULL,
    cmd_id          bigint        NOT NULL,
    phase           varchar(10)   NOT NULL,
    ok_yn           char(1),
    step_value      varchar(50),
    detail          varchar(500),
    src             varchar(10)   NOT NULL,
    terminal_id     bigint,
    inbound_id      bigint,
    occurred_at     timestamptz   NOT NULL,
    recorded_at     timestamptz   NOT NULL DEFAULT now(),
    entry_method    varchar(10)   NOT NULL,
    recorded_by     varchar(50)   NOT NULL,
    performed_by    bigint,
    reversal_of_id  bigint,
    CONSTRAINT pk_cms_equip_cmd_step PRIMARY KEY (step_id),
    CONSTRAINT fk_cms_equip_cmd_step_cmd FOREIGN KEY (cmd_id) REFERENCES cms_equip_cmd (cmd_id),
    CONSTRAINT fk_cms_equip_cmd_step_terminal FOREIGN KEY (terminal_id) REFERENCES sys_terminal (terminal_id),
    CONSTRAINT ck_cms_equip_cmd_step_phase CHECK (phase IN ('INTENT', 'SENT', 'CLAIM', 'WRITE', 'READBACK', 'RESULT', 'TIMEOUT', 'RESOLVE')),
    CONSTRAINT ck_cms_equip_cmd_step_ok CHECK (ok_yn IS NULL OR ok_yn IN ('Y', 'N')),
    CONSTRAINT ck_cms_equip_cmd_step_src CHECK (src IN ('SERVER', 'EDGE', 'USER')),
    CONSTRAINT ck_cms_equip_cmd_step_method CHECK (entry_method IN ('AUTO', 'KEY')),
    -- 단계는 정정하지 않는다(다음 단계로) — 원장 칸 규약만
    CONSTRAINT ck_cms_equip_cmd_step_no_reversal CHECK (reversal_of_id IS NULL AND performed_by IS NULL)
);
CREATE INDEX ix_cms_equip_cmd_step_cmd ON cms_equip_cmd_step (comp_cd, cmd_id, step_id);
COMMENT ON TABLE cms_equip_cmd_step IS '장비 명령 단계 원장 (docs/65 5.3·16.4) — 의도·보냄·가져감·쓰기·다시 읽기·결과. 순서가 뒤바뀌어 와도 모두 남는다';

CREATE FUNCTION hb_guard_cms_equip_cmd_step() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM cms_equip_cmd c WHERE c.cmd_id = NEW.cmd_id AND c.comp_cd = NEW.comp_cd)
       OR (NEW.terminal_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM sys_terminal t WHERE t.terminal_id = NEW.terminal_id
                                                          AND t.comp_cd = NEW.comp_cd)) THEN
        RAISE EXCEPTION 'command step context is not of company %', NEW.comp_cd
            USING ERRCODE = '23503', CONSTRAINT = 'fk_cms_equip_cmd_step_ctx_comp';
    END IF;
    RETURN NEW;
END
$$;
CREATE TRIGGER tg_cms_equip_cmd_step_guard BEFORE INSERT ON cms_equip_cmd_step FOR EACH ROW EXECUTE FUNCTION hb_guard_cms_equip_cmd_step();
CREATE TRIGGER tg_cms_equip_cmd_step_ledger BEFORE UPDATE OR DELETE ON cms_equip_cmd_step FOR EACH ROW EXECUTE FUNCTION hb_forbid_update_delete();

-- ---------------------------------------------------------------------
-- 5. 연동 사건 원장(추가만)
-- ---------------------------------------------------------------------
CREATE TABLE cms_equip_link_log (
    log_id          bigint        GENERATED ALWAYS AS IDENTITY,
    comp_cd         varchar(20)   NOT NULL,
    equip_id        bigint,
    terminal_id     bigint,
    event_cd        varchar(12)   NOT NULL,
    seq_from        bigint,
    seq_to          bigint,
    detail          varchar(500),
    inbound_id      bigint,
    occurred_at     timestamptz   NOT NULL,
    recorded_at     timestamptz   NOT NULL DEFAULT now(),
    entry_method    varchar(10)   NOT NULL DEFAULT 'AUTO',
    recorded_by     varchar(50)   NOT NULL,
    performed_by    bigint,
    reversal_of_id  bigint,
    CONSTRAINT pk_cms_equip_link_log PRIMARY KEY (log_id),
    CONSTRAINT fk_cms_equip_link_log_equip FOREIGN KEY (equip_id) REFERENCES mst_equip (equip_id),
    CONSTRAINT fk_cms_equip_link_log_terminal FOREIGN KEY (terminal_id) REFERENCES sys_terminal (terminal_id),
    CONSTRAINT ck_cms_equip_link_log_event CHECK (event_cd IN
        ('GAP', 'REORDER', 'STALE', 'CONFLICT', 'CLOCK_SKEW', 'LOST', 'BACK', 'BIRTH', 'DENIED')),
    CONSTRAINT ck_cms_equip_link_log_gap CHECK (event_cd <> 'GAP' OR (seq_from IS NOT NULL AND seq_to >= seq_from)),
    CONSTRAINT ck_cms_equip_link_log_target CHECK (equip_id IS NOT NULL OR terminal_id IS NOT NULL),
    CONSTRAINT ck_cms_equip_link_log_method CHECK (entry_method = 'AUTO'),
    CONSTRAINT ck_cms_equip_link_log_no_reversal CHECK (reversal_of_id IS NULL AND performed_by IS NULL)
);
CREATE INDEX ix_cms_equip_link_log_equip ON cms_equip_link_log (comp_cd, equip_id, occurred_at);
CREATE INDEX ix_cms_equip_link_log_time ON cms_equip_link_log (comp_cd, occurred_at);
COMMENT ON TABLE cms_equip_link_log IS '장비 연동 사건 원장 (docs/65 16.2·16.7) — 누락·역전·늦음·충돌·시계 차이·끊김·복귀·birth·전이 거부';

CREATE FUNCTION hb_guard_cms_equip_link_log() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF (NEW.equip_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM mst_equip e WHERE e.equip_id = NEW.equip_id AND e.comp_cd = NEW.comp_cd))
       OR (NEW.terminal_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM sys_terminal t WHERE t.terminal_id = NEW.terminal_id
                                                          AND t.comp_cd = NEW.comp_cd)) THEN
        RAISE EXCEPTION 'link log context is not of company %', NEW.comp_cd
            USING ERRCODE = '23503', CONSTRAINT = 'fk_cms_equip_link_log_ctx_comp';
    END IF;
    RETURN NEW;
END
$$;
CREATE TRIGGER tg_cms_equip_link_log_guard BEFORE INSERT ON cms_equip_link_log FOR EACH ROW EXECUTE FUNCTION hb_guard_cms_equip_link_log();
CREATE TRIGGER tg_cms_equip_link_log_ledger BEFORE UPDATE OR DELETE ON cms_equip_link_log FOR EACH ROW EXECUTE FUNCTION hb_forbid_update_delete();

-- ---------------------------------------------------------------------
-- 6. 소모품 마지막 수준 · 장치 세션
-- ---------------------------------------------------------------------
CREATE TABLE cms_equip_consumable (
    comp_cd     varchar(20)  NOT NULL,
    equip_id    bigint       NOT NULL,
    cons_cd     varchar(30)  NOT NULL,
    level_cd    varchar(10)  NOT NULL,
    changed_at  timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT pk_cms_equip_consumable PRIMARY KEY (comp_cd, equip_id, cons_cd),
    CONSTRAINT fk_cms_equip_consumable_link FOREIGN KEY (equip_id) REFERENCES cms_equip_link (equip_id),
    CONSTRAINT ck_cms_equip_consumable_cd CHECK (cons_cd ~ '^[A-Z][A-Z0-9_]{0,29}$'),
    CONSTRAINT ck_cms_equip_consumable_level CHECK (level_cd IN ('OK', 'LOW', 'EMPTY'))
);
COMMENT ON TABLE cms_equip_consumable IS '소모품(용지·리본 등) 마지막 수준 (docs/65 5.5) — 바뀔 때만 알람(부족 경고 → 없음)';

CREATE TABLE cms_edge_session (
    comp_cd         varchar(20)  NOT NULL,
    terminal_id     bigint       NOT NULL,
    boot_id         varchar(64)  NOT NULL,
    started_at      timestamptz  NOT NULL DEFAULT now(),
    last_hb_at      timestamptz  NOT NULL DEFAULT now(),
    skew_ms         bigint,
    skew_logged_at  timestamptz,
    CONSTRAINT pk_cms_edge_session PRIMARY KEY (comp_cd, terminal_id),
    CONSTRAINT fk_cms_edge_session_terminal FOREIGN KEY (terminal_id) REFERENCES sys_terminal (terminal_id),
    CONSTRAINT ck_cms_edge_session_boot CHECK (boot_id ~ '^[A-Za-z0-9_.:-]{1,64}$')
);
COMMENT ON TABLE cms_edge_session IS '엣지·어댑터 장치 세션 (docs/65 16.7) — bootId 가 바뀌면 birth. 시계 차이 경고는 장치당 10분에 한 번';

-- ---------------------------------------------------------------------
-- 7. (남의 표 변경) 상태 원장 PackML 칸 · 외부 시스템 연결 방식
-- ---------------------------------------------------------------------
ALTER TABLE cms_equip_state_log ADD COLUMN pack_state varchar(10);
ALTER TABLE cms_equip_state_log ADD CONSTRAINT ck_cms_equip_state_log_pack CHECK (pack_state IS NULL OR pack_state IN
    ('IDLE', 'STARTING', 'EXECUTE', 'HELD', 'SUSPENDED', 'STOPPED', 'ABORTED', 'COMPLETE'));
COMMENT ON COLUMN cms_equip_state_log.pack_state IS 'PackML 상태(docs/65 16.6) — 엣지 경로만. state_cat 은 PackML × mode 에서 대응표로';

ALTER TABLE mst_ext_system ADD COLUMN conn_mode varchar(10) NOT NULL DEFAULT 'DIRECT';
ALTER TABLE mst_ext_system ADD CONSTRAINT ck_mst_ext_system_conn_mode CHECK (conn_mode IN ('DIRECT', 'EDGE', 'AGENT'));
COMMENT ON COLUMN mst_ext_system.conn_mode IS '연결 방식 (docs/65 3장) — DIRECT 서버가 직접 / EDGE 엣지 중계 / AGENT 당기는 에이전트';

-- ---------------------------------------------------------------------
-- 8. RLS · 권한 · 화면 · 보존
-- ---------------------------------------------------------------------
ALTER TABLE cms_equip_link ENABLE ROW LEVEL SECURITY;
ALTER TABLE cms_equip_link FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON cms_equip_link USING (comp_cd = hb_current_comp()) WITH CHECK (comp_cd = hb_current_comp());
ALTER TABLE cms_equip_tag ENABLE ROW LEVEL SECURITY;
ALTER TABLE cms_equip_tag FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON cms_equip_tag USING (comp_cd = hb_current_comp()) WITH CHECK (comp_cd = hb_current_comp());
ALTER TABLE cms_equip_cmd ENABLE ROW LEVEL SECURITY;
ALTER TABLE cms_equip_cmd FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON cms_equip_cmd USING (comp_cd = hb_current_comp()) WITH CHECK (comp_cd = hb_current_comp());
ALTER TABLE cms_equip_cmd_step ENABLE ROW LEVEL SECURITY;
ALTER TABLE cms_equip_cmd_step FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON cms_equip_cmd_step USING (comp_cd = hb_current_comp()) WITH CHECK (comp_cd = hb_current_comp());
ALTER TABLE cms_equip_link_log ENABLE ROW LEVEL SECURITY;
ALTER TABLE cms_equip_link_log FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON cms_equip_link_log USING (comp_cd = hb_current_comp()) WITH CHECK (comp_cd = hb_current_comp());
ALTER TABLE cms_equip_consumable ENABLE ROW LEVEL SECURITY;
ALTER TABLE cms_equip_consumable FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON cms_equip_consumable USING (comp_cd = hb_current_comp()) WITH CHECK (comp_cd = hb_current_comp());
ALTER TABLE cms_edge_session ENABLE ROW LEVEL SECURITY;
ALTER TABLE cms_edge_session FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON cms_edge_session USING (comp_cd = hb_current_comp()) WITH CHECK (comp_cd = hb_current_comp());

REVOKE DELETE, TRUNCATE ON cms_equip_link, cms_equip_tag, cms_equip_cmd, cms_equip_consumable, cms_edge_session FROM ${app_role};
REVOKE UPDATE, DELETE, TRUNCATE ON cms_equip_cmd_step, cms_equip_link_log FROM ${app_role};

INSERT INTO sys_prog (prog_cd, prog_nm_key, view_path, module_cd, actions, sort_no) VALUES
    ('cms-equip-link', 'prog.cmsEquipLink', 'cms/equip-link', 'CMS', ARRAY['SEARCH','SAVE','FN1']::varchar(20)[], 650);

INSERT INTO sys_retention_table (table_nm, class_cd) VALUES
    ('cms_equip_link', 'MASTER'), ('cms_equip_tag', 'MASTER'), ('cms_equip_consumable', 'MASTER'), ('cms_edge_session', 'MASTER'),
    ('cms_equip_cmd', 'PRODUCTION_RECORD'), ('cms_equip_cmd_step', 'PRODUCTION_RECORD'), ('cms_equip_link_log', 'PRODUCTION_RECORD');
