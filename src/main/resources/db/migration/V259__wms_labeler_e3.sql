-- =====================================================================
-- V9300 오토라벨러 E3 (docs/65 21장 — 7장)
--
-- - wms_labeler: 라벨러 마스터(장치 = sys_terminal COLLECTOR 키, 연결 DIRECT/EDGE, 템플릿 = PALLET 유형 SSCC 라벨, 응답 기한)
-- - wms_labeler_box: 사전 등록 박스(추적 코드·라벨 생략·SSCC·상태 기계)
-- - wms_labeler_event: 박스 단위 원장(추가만) / wms_labeler_dev_log: 장비 단위 원장(추가만)
-- - 사유 코드 WMS_LABELER_REASON: 시스템 코드 + 회사 확장(장비마다 다른 리젝트 사유) — hb_check_code_col
-- 임시 번호(docs/29 2.2, WMS 대역) — 병합 때 이름만 바뀐다.
-- =====================================================================

INSERT INTO sys_policy_def (policy_key, value_type, default_value, allowed_values, min_value, max_value, plant_scope_yn, desc_key) VALUES
    -- 미리 받기(SaaS 엣지)로 SSCC 를 붙였는데 이 시간 안에 안 쓰면 결번 처리
    ('WMS_LABELER_PREFETCH_HOURS', 'INT',    '24',   NULL,               1,    720,  'N', 'policy.WMS_LABELER_PREFETCH_HOURS'),
    -- 리젝트 라인 만재 때 장비에 돌려줄 지시
    ('WMS_LABELER_REJECT_FULL',    'ENUM',   'STOP', ARRAY['WAIT','ALARM','STOP'], NULL, NULL, 'Y', 'policy.WMS_LABELER_REJECT_FULL');

INSERT INTO sys_code_grp (grp_cd, grp_nm_key, comp_ext_yn, code_max_len) VALUES
    ('WMS_LABELER_REASON', 'code.grp.WMS_LABELER_REASON', 'Y', 30),
    ('WMS_LABELER_BOX_STATUS', 'code.grp.WMS_LABELER_BOX_STATUS', 'N', 30),
    ('WMS_LABELER_EVENT', 'code.grp.WMS_LABELER_EVENT', 'N', 30);
INSERT INTO sys_code (grp_cd, code, code_nm_key, sort_no) VALUES
    ('WMS_LABELER_REASON', 'NO_READ',         'code.WMS_LABELER_REASON.NO_READ',         1),
    ('WMS_LABELER_REASON', 'UNREGISTERED',    'code.WMS_LABELER_REASON.UNREGISTERED',    2),
    ('WMS_LABELER_REASON', 'LABEL_SKIP',      'code.WMS_LABELER_REASON.LABEL_SKIP',      3),
    ('WMS_LABELER_REASON', 'DUPLICATE',       'code.WMS_LABELER_REASON.DUPLICATE',       4),
    ('WMS_LABELER_REASON', 'LATE',            'code.WMS_LABELER_REASON.LATE',            5),
    ('WMS_LABELER_REASON', 'VERIFY_MISMATCH', 'code.WMS_LABELER_REASON.VERIFY_MISMATCH', 6),
    ('WMS_LABELER_REASON', 'VERIFY_NO_READ',  'code.WMS_LABELER_REASON.VERIFY_NO_READ',  7),
    ('WMS_LABELER_REASON', 'NOT_APPLIED',     'code.WMS_LABELER_REASON.NOT_APPLIED',     8),
    ('WMS_LABELER_REASON', 'PRINT_FAIL',      'code.WMS_LABELER_REASON.PRINT_FAIL',      9),
    ('WMS_LABELER_REASON', 'PREFETCH_UNUSED', 'code.WMS_LABELER_REASON.PREFETCH_UNUSED', 10),
    ('WMS_LABELER_REASON', 'OTHER',           'code.WMS_LABELER_REASON.OTHER',           99),
    ('WMS_LABELER_BOX_STATUS', 'REGISTERED', 'code.WMS_LABELER_BOX_STATUS.REGISTERED', 1),
    ('WMS_LABELER_BOX_STATUS', 'PREFETCHED', 'code.WMS_LABELER_BOX_STATUS.PREFETCHED', 2),
    ('WMS_LABELER_BOX_STATUS', 'REQUESTED',  'code.WMS_LABELER_BOX_STATUS.REQUESTED',  3),
    ('WMS_LABELER_BOX_STATUS', 'VERIFIED',   'code.WMS_LABELER_BOX_STATUS.VERIFIED',   4),
    ('WMS_LABELER_BOX_STATUS', 'REJECTED',   'code.WMS_LABELER_BOX_STATUS.REJECTED',   5),
    ('WMS_LABELER_BOX_STATUS', 'SKIPPED',    'code.WMS_LABELER_BOX_STATUS.SKIPPED',    6),
    ('WMS_LABELER_EVENT', 'REQUEST',   'code.WMS_LABELER_EVENT.REQUEST',   1),
    ('WMS_LABELER_EVENT', 'PRINTED',   'code.WMS_LABELER_EVENT.PRINTED',   2),
    ('WMS_LABELER_EVENT', 'APPLIED',   'code.WMS_LABELER_EVENT.APPLIED',   3),
    ('WMS_LABELER_EVENT', 'VERIFY_OK', 'code.WMS_LABELER_EVENT.VERIFY_OK', 4),
    ('WMS_LABELER_EVENT', 'VERIFY_NG', 'code.WMS_LABELER_EVENT.VERIFY_NG', 5),
    ('WMS_LABELER_EVENT', 'REJECTED',  'code.WMS_LABELER_EVENT.REJECTED',  6),
    ('WMS_LABELER_EVENT', 'PREFETCH',  'code.WMS_LABELER_EVENT.PREFETCH',  7),
    ('WMS_LABELER_EVENT', 'SSCC_VOID', 'code.WMS_LABELER_EVENT.SSCC_VOID', 8),
    ('WMS_LABELER_EVENT', 'STALE',     'code.WMS_LABELER_EVENT.STALE',     9);

-- ---------------------------------------------------------------------
-- 1. 라벨러
-- ---------------------------------------------------------------------
CREATE TABLE wms_labeler (
    labeler_id   bigint        GENERATED ALWAYS AS IDENTITY,
    comp_cd      varchar(20)   NOT NULL,
    plant_cd     varchar(20)   NOT NULL,
    labeler_cd   varchar(20)   NOT NULL,
    labeler_nm   varchar(100)  NOT NULL,
    terminal_id  bigint        NOT NULL,
    conn_mode    varchar(10)   NOT NULL DEFAULT 'DIRECT',
    tpl_id       bigint        NOT NULL,
    deadline_ms  integer       NOT NULL DEFAULT 2000,
    use_yn       char(1)       NOT NULL DEFAULT 'Y',
    remark       varchar(500),
    row_ver      integer       NOT NULL DEFAULT 0,
    created_at   timestamptz   NOT NULL DEFAULT now(),
    created_by   varchar(50)   NOT NULL,
    updated_at   timestamptz   NOT NULL DEFAULT now(),
    updated_by   varchar(50)   NOT NULL,
    CONSTRAINT pk_wms_labeler PRIMARY KEY (labeler_id),
    CONSTRAINT fk_wms_labeler_plant FOREIGN KEY (comp_cd, plant_cd) REFERENCES sys_plant (comp_cd, plant_cd),
    CONSTRAINT fk_wms_labeler_terminal FOREIGN KEY (terminal_id) REFERENCES sys_terminal (terminal_id),
    CONSTRAINT fk_wms_labeler_tpl FOREIGN KEY (tpl_id) REFERENCES wms_label_tpl (tpl_id),
    CONSTRAINT uq_wms_labeler_cd UNIQUE (comp_cd, plant_cd, labeler_cd),
    CONSTRAINT ck_wms_labeler_cd CHECK (labeler_cd ~ '^[A-Z0-9][A-Z0-9_-]{0,19}$'),
    CONSTRAINT ck_wms_labeler_nm CHECK (length(btrim(labeler_nm)) > 0),
    CONSTRAINT ck_wms_labeler_conn CHECK (conn_mode IN ('DIRECT', 'EDGE')),
    -- 박스가 지나가는 시간(수 초) 안 — 0.2초 ~ 1분
    CONSTRAINT ck_wms_labeler_deadline CHECK (deadline_ms BETWEEN 200 AND 60000),
    CONSTRAINT ck_wms_labeler_use_yn CHECK (use_yn IN ('Y', 'N'))
);
-- 한 장치는 한 라벨러만 대신 말한다(장치 키로 라벨러를 찾는다)
CREATE UNIQUE INDEX uq_wms_labeler_terminal ON wms_labeler (comp_cd, terminal_id) WHERE use_yn = 'Y';
COMMENT ON TABLE wms_labeler IS '오토라벨러 (docs/65 21장) — 장치(COLLECTOR 키)가 /ext/v1/labeler/* 로 데이터 요청·결과 보고. 인쇄 트리거·부착은 장비 몫';

CREATE FUNCTION hb_guard_wms_labeler() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'UPDATE' AND (NEW.plant_cd <> OLD.plant_cd OR NEW.labeler_cd <> OLD.labeler_cd) THEN
        RAISE EXCEPTION 'labeler plant and code are fixed' USING ERRCODE = '55000', CONSTRAINT = 'ck_wms_labeler_fixed';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM sys_terminal t WHERE t.terminal_id = NEW.terminal_id AND t.comp_cd = NEW.comp_cd
                      AND t.terminal_type = 'COLLECTOR') THEN
        RAISE EXCEPTION 'terminal % is not a device of company %', NEW.terminal_id, NEW.comp_cd
            USING ERRCODE = '23503', CONSTRAINT = 'fk_wms_labeler_terminal_comp';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM wms_label_tpl l WHERE l.tpl_id = NEW.tpl_id AND l.comp_cd = NEW.comp_cd AND l.label_type = 'PALLET') THEN
        RAISE EXCEPTION 'template % is not a PALLET template of company %', NEW.tpl_id, NEW.comp_cd
            USING ERRCODE = '23503', CONSTRAINT = 'fk_wms_labeler_tpl_comp';
    END IF;
    RETURN NEW;
END
$$;
CREATE TRIGGER tg_wms_labeler_guard BEFORE INSERT OR UPDATE ON wms_labeler FOR EACH ROW EXECUTE FUNCTION hb_guard_wms_labeler();
CREATE TRIGGER tg_wms_labeler_no_delete BEFORE DELETE ON wms_labeler FOR EACH ROW EXECUTE FUNCTION hb_forbid_delete_master();

-- ---------------------------------------------------------------------
-- 2. 사전 등록 박스 — 상태 기계
-- ---------------------------------------------------------------------
CREATE TABLE wms_labeler_box (
    box_id         bigint          GENERATED ALWAYS AS IDENTITY,
    comp_cd        varchar(20)     NOT NULL,
    plant_cd       varchar(20)     NOT NULL,
    box_cd         varchar(60)     NOT NULL,
    item_id        bigint          NOT NULL,
    qty            numeric(18,6)   NOT NULL,
    lot_no         varchar(60),
    ref_no         varchar(60),
    label_skip_yn  char(1)         NOT NULL DEFAULT 'N',
    sscc           char(18),
    status         varchar(12)     NOT NULL DEFAULT 'REGISTERED',
    attempt_cnt    integer         NOT NULL DEFAULT 0,
    last_reason_cd varchar(30),
    prefetched_at  timestamptz,
    prefetched_by  bigint,
    done_at        timestamptz,
    row_ver        integer         NOT NULL DEFAULT 0,
    created_at     timestamptz     NOT NULL DEFAULT now(),
    created_by     varchar(50)     NOT NULL,
    updated_at     timestamptz     NOT NULL DEFAULT now(),
    updated_by     varchar(50)     NOT NULL,
    CONSTRAINT pk_wms_labeler_box PRIMARY KEY (box_id),
    CONSTRAINT fk_wms_labeler_box_plant FOREIGN KEY (comp_cd, plant_cd) REFERENCES sys_plant (comp_cd, plant_cd),
    CONSTRAINT fk_wms_labeler_box_item FOREIGN KEY (item_id) REFERENCES mst_item (item_id),
    CONSTRAINT fk_wms_labeler_box_prefetch FOREIGN KEY (prefetched_by) REFERENCES wms_labeler (labeler_id),
    -- 앞단 스캐너가 읽는 값 — 글자 종류·길이만(박스 GTIN·작업 바코드 등)
    CONSTRAINT ck_wms_labeler_box_cd CHECK (box_cd ~ '^[A-Za-z0-9][A-Za-z0-9._/#:-]{0,59}$'),
    CONSTRAINT ck_wms_labeler_box_qty CHECK (qty > 0),
    CONSTRAINT ck_wms_labeler_box_skip CHECK (label_skip_yn IN ('Y', 'N')),
    CONSTRAINT ck_wms_labeler_box_sscc CHECK (sscc IS NULL OR sscc ~ '^[0-9]{18}$'),
    CONSTRAINT ck_wms_labeler_box_status CHECK (status IN ('REGISTERED', 'PREFETCHED', 'REQUESTED', 'VERIFIED', 'REJECTED', 'SKIPPED')),
    -- 라벨을 붙이는 단계(미리 받기·요청·검증·리젝트)는 SSCC 가 있다
    CONSTRAINT ck_wms_labeler_box_sscc_need CHECK (status IN ('REGISTERED', 'SKIPPED') OR sscc IS NOT NULL),
    CONSTRAINT ck_wms_labeler_box_prefetch CHECK ((status = 'PREFETCHED') = (prefetched_by IS NOT NULL)),
    CONSTRAINT ck_wms_labeler_box_attempt CHECK (attempt_cnt >= 0)
);
-- 같은 추적 코드는 회사 안 하나(끝나지 않은 박스) — 앞단 스캔으로 박스를 하나만 찾게
CREATE UNIQUE INDEX uq_wms_labeler_box_cd ON wms_labeler_box (comp_cd, box_cd) WHERE status NOT IN ('VERIFIED', 'SKIPPED');
-- SSCC 는 회사 안 하나(팔레트와 같은 일련번호 — 박스끼리 겹치지 않게 DB 도 막는다)
CREATE UNIQUE INDEX uq_wms_labeler_box_sscc ON wms_labeler_box (comp_cd, sscc) WHERE sscc IS NOT NULL;
CREATE INDEX ix_wms_labeler_box_status ON wms_labeler_box (comp_cd, plant_cd, status, box_id);
COMMENT ON TABLE wms_labeler_box IS '오토라벨러 사전 등록 박스 (docs/65 21.1) — REGISTERED → (PREFETCHED) → REQUESTED → VERIFIED / REJECTED(재라벨 같은 SSCC) / SKIPPED';

-- 전이 허용 + SSCC 는 붙인 뒤 바꾸지 않는다(같은 물류 단위 — GS1). 미리 받기 결번(PREFETCHED → REGISTERED)만 비울 수 있다
CREATE FUNCTION hb_guard_wms_labeler_box() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'INSERT' THEN
        IF NEW.status <> 'REGISTERED' OR NEW.sscc IS NOT NULL OR NEW.attempt_cnt <> 0 THEN
            RAISE EXCEPTION 'box must be registered empty' USING ERRCODE = '23514', CONSTRAINT = 'ck_wms_labeler_box_insert';
        END IF;
        IF NOT EXISTS (SELECT 1 FROM mst_item i WHERE i.item_id = NEW.item_id AND i.comp_cd = NEW.comp_cd) THEN
            RAISE EXCEPTION 'item % is not of company %', NEW.item_id, NEW.comp_cd
                USING ERRCODE = '23503', CONSTRAINT = 'fk_wms_labeler_box_item_comp';
        END IF;
        RETURN NEW;
    END IF;
    IF NEW.box_cd <> OLD.box_cd OR NEW.plant_cd <> OLD.plant_cd OR NEW.item_id <> OLD.item_id THEN
        RAISE EXCEPTION 'box target is fixed' USING ERRCODE = '55000', CONSTRAINT = 'ck_wms_labeler_box_fixed';
    END IF;
    IF OLD.sscc IS NOT NULL AND NEW.sscc IS DISTINCT FROM OLD.sscc
       AND NOT (OLD.status = 'PREFETCHED' AND NEW.status = 'REGISTERED' AND NEW.sscc IS NULL) THEN
        RAISE EXCEPTION 'box SSCC is fixed once assigned' USING ERRCODE = '55000', CONSTRAINT = 'ck_wms_labeler_box_sscc_fixed';
    END IF;
    IF NEW.status <> OLD.status AND NOT (
           (OLD.status = 'REGISTERED' AND NEW.status IN ('PREFETCHED', 'REQUESTED', 'SKIPPED'))
        OR (OLD.status = 'PREFETCHED' AND NEW.status IN ('REQUESTED', 'REGISTERED', 'SKIPPED'))
        OR (OLD.status = 'REQUESTED'  AND NEW.status IN ('VERIFIED', 'REJECTED'))
        OR (OLD.status = 'REJECTED'   AND NEW.status IN ('REQUESTED'))) THEN
        RAISE EXCEPTION 'box status % → % not allowed', OLD.status, NEW.status
            USING ERRCODE = '55000', CONSTRAINT = 'ck_wms_labeler_box_transition';
    END IF;
    RETURN NEW;
END
$$;
CREATE TRIGGER tg_wms_labeler_box_guard BEFORE INSERT OR UPDATE ON wms_labeler_box FOR EACH ROW EXECUTE FUNCTION hb_guard_wms_labeler_box();
CREATE TRIGGER tg_wms_labeler_box_no_delete BEFORE DELETE ON wms_labeler_box FOR EACH ROW EXECUTE FUNCTION hb_forbid_delete_master();
CREATE TRIGGER tg_wms_labeler_box_code_reason BEFORE INSERT OR UPDATE OF last_reason_cd ON wms_labeler_box
    FOR EACH ROW EXECUTE FUNCTION hb_check_code_col('WMS_LABELER_REASON', 'last_reason_cd');

-- ---------------------------------------------------------------------
-- 3. 박스 단위 원장 (추가만)
-- ---------------------------------------------------------------------
CREATE TABLE wms_labeler_event (
    event_id        bigint        GENERATED ALWAYS AS IDENTITY,
    comp_cd         varchar(20)   NOT NULL,
    labeler_id      bigint,
    box_id          bigint,
    request_id      varchar(64),
    event_cd        varchar(12)   NOT NULL,
    action_cd       varchar(10),
    reason_cd       varchar(30),
    sscc            char(18),
    scanned         varchar(200),
    elapsed_ms      integer,
    answer          jsonb,
    detail          varchar(500),
    src             varchar(10)   NOT NULL,
    occurred_at     timestamptz   NOT NULL,
    recorded_at     timestamptz   NOT NULL DEFAULT now(),
    entry_method    varchar(10)   NOT NULL DEFAULT 'AUTO',
    recorded_by     varchar(50)   NOT NULL,
    performed_by    bigint,
    reversal_of_id  bigint,
    CONSTRAINT pk_wms_labeler_event PRIMARY KEY (event_id),
    CONSTRAINT fk_wms_labeler_event_labeler FOREIGN KEY (labeler_id) REFERENCES wms_labeler (labeler_id),
    CONSTRAINT fk_wms_labeler_event_box FOREIGN KEY (box_id) REFERENCES wms_labeler_box (box_id),
    CONSTRAINT ck_wms_labeler_event_cd CHECK (event_cd IN ('REQUEST', 'PRINTED', 'APPLIED', 'VERIFY_OK', 'VERIFY_NG', 'REJECTED',
        'PREFETCH', 'SSCC_VOID', 'STALE')),
    CONSTRAINT ck_wms_labeler_event_action CHECK (action_cd IS NULL OR action_cd IN ('PRINT', 'SKIP', 'REJECT')),
    -- 요청 사건은 라벨러·요청 id·답이 있다(멱등 키, 같은 요청 다시 = 처음 답)
    CONSTRAINT ck_wms_labeler_event_request CHECK (event_cd <> 'REQUEST'
        OR (labeler_id IS NOT NULL AND request_id IS NOT NULL AND action_cd IS NOT NULL AND answer IS NOT NULL)),
    CONSTRAINT ck_wms_labeler_event_req_id CHECK (request_id IS NULL OR request_id ~ '^[A-Za-z0-9._:-]{1,64}$'),
    CONSTRAINT ck_wms_labeler_event_sscc CHECK (sscc IS NULL OR sscc ~ '^[0-9]{18}$'),
    CONSTRAINT ck_wms_labeler_event_elapsed CHECK (elapsed_ms IS NULL OR elapsed_ms >= 0),
    CONSTRAINT ck_wms_labeler_event_src CHECK (src IN ('DIRECT', 'EDGE', 'USER', 'SYSTEM')),
    CONSTRAINT ck_wms_labeler_event_method CHECK (entry_method IN ('AUTO', 'SCAN', 'KEY')),
    CONSTRAINT ck_wms_labeler_event_no_reversal CHECK (reversal_of_id IS NULL AND performed_by IS NULL)
);
-- 멱등: 라벨러·요청마다 요청 사건 하나, 결과 단계(인쇄·부착·검증·리젝트)도 요청마다 한 번
CREATE UNIQUE INDEX uq_wms_labeler_event_request ON wms_labeler_event (comp_cd, labeler_id, request_id) WHERE event_cd = 'REQUEST';
CREATE UNIQUE INDEX uq_wms_labeler_event_phase ON wms_labeler_event (comp_cd, labeler_id, request_id, event_cd)
    WHERE event_cd IN ('PRINTED', 'APPLIED', 'VERIFY_OK', 'VERIFY_NG', 'REJECTED');
CREATE INDEX ix_wms_labeler_event_box ON wms_labeler_event (comp_cd, box_id, event_id);
CREATE INDEX ix_wms_labeler_event_time ON wms_labeler_event (comp_cd, occurred_at);
COMMENT ON TABLE wms_labeler_event IS '오토라벨러 박스 단위 원장 (docs/65 21.2) — 요청·답·인쇄·부착·검증·리젝트·미리 받기·결번';

CREATE FUNCTION hb_guard_wms_labeler_event() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF (NEW.labeler_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM wms_labeler l WHERE l.labeler_id = NEW.labeler_id AND l.comp_cd = NEW.comp_cd))
       OR (NEW.box_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM wms_labeler_box b WHERE b.box_id = NEW.box_id AND b.comp_cd = NEW.comp_cd)) THEN
        RAISE EXCEPTION 'labeler event context is not of company %', NEW.comp_cd
            USING ERRCODE = '23503', CONSTRAINT = 'fk_wms_labeler_event_comp';
    END IF;
    RETURN NEW;
END
$$;
CREATE TRIGGER tg_wms_labeler_event_guard BEFORE INSERT ON wms_labeler_event FOR EACH ROW EXECUTE FUNCTION hb_guard_wms_labeler_event();
CREATE TRIGGER tg_wms_labeler_event_ledger BEFORE UPDATE OR DELETE ON wms_labeler_event FOR EACH ROW EXECUTE FUNCTION hb_forbid_update_delete();
CREATE TRIGGER tg_wms_labeler_event_code_reason BEFORE INSERT ON wms_labeler_event
    FOR EACH ROW EXECUTE FUNCTION hb_check_code_col('WMS_LABELER_REASON', 'reason_cd');

-- ---------------------------------------------------------------------
-- 4. 장비 단위 원장 (추가만)
-- ---------------------------------------------------------------------
CREATE TABLE wms_labeler_dev_log (
    log_id          bigint        GENERATED ALWAYS AS IDENTITY,
    comp_cd         varchar(20)   NOT NULL,
    labeler_id      bigint        NOT NULL,
    event_cd        varchar(12)   NOT NULL,
    code            varchar(40),
    detail          varchar(500),
    reply_cd        varchar(10),
    occurred_at     timestamptz   NOT NULL,
    recorded_at     timestamptz   NOT NULL DEFAULT now(),
    entry_method    varchar(10)   NOT NULL DEFAULT 'AUTO',
    recorded_by     varchar(50)   NOT NULL,
    performed_by    bigint,
    reversal_of_id  bigint,
    CONSTRAINT pk_wms_labeler_dev_log PRIMARY KEY (log_id),
    CONSTRAINT fk_wms_labeler_dev_log_labeler FOREIGN KEY (labeler_id) REFERENCES wms_labeler (labeler_id),
    CONSTRAINT ck_wms_labeler_dev_log_cd CHECK (event_cd IN ('ERROR', 'STATE', 'REJECT_FULL')),
    CONSTRAINT ck_wms_labeler_dev_log_code CHECK (code IS NULL OR code ~ '^[A-Z][A-Z0-9_]{0,39}$'),
    CONSTRAINT ck_wms_labeler_dev_log_reply CHECK (reply_cd IS NULL OR reply_cd IN ('WAIT', 'ALARM', 'STOP')),
    CONSTRAINT ck_wms_labeler_dev_log_method CHECK (entry_method = 'AUTO'),
    CONSTRAINT ck_wms_labeler_dev_log_no_reversal CHECK (reversal_of_id IS NULL AND performed_by IS NULL)
);
CREATE INDEX ix_wms_labeler_dev_log ON wms_labeler_dev_log (comp_cd, labeler_id, occurred_at);
COMMENT ON TABLE wms_labeler_dev_log IS '오토라벨러 장비 단위 원장 (docs/65 21.4) — 오류·상태·리젝트 라인 만재(돌려준 지시)';

CREATE FUNCTION hb_guard_wms_labeler_dev_log() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM wms_labeler l WHERE l.labeler_id = NEW.labeler_id AND l.comp_cd = NEW.comp_cd) THEN
        RAISE EXCEPTION 'labeler % is not of company %', NEW.labeler_id, NEW.comp_cd
            USING ERRCODE = '23503', CONSTRAINT = 'fk_wms_labeler_dev_log_comp';
    END IF;
    RETURN NEW;
END
$$;
CREATE TRIGGER tg_wms_labeler_dev_log_guard BEFORE INSERT ON wms_labeler_dev_log FOR EACH ROW EXECUTE FUNCTION hb_guard_wms_labeler_dev_log();
CREATE TRIGGER tg_wms_labeler_dev_log_ledger BEFORE UPDATE OR DELETE ON wms_labeler_dev_log FOR EACH ROW EXECUTE FUNCTION hb_forbid_update_delete();

-- ---------------------------------------------------------------------
-- 5. 감사 · RLS · 권한 · 화면 · 보존
-- ---------------------------------------------------------------------
CREATE TRIGGER tg_audit AFTER INSERT OR UPDATE OR DELETE ON wms_labeler FOR EACH ROW EXECUTE FUNCTION hb_audit('labeler_id', '', '');
-- 박스: 상태·시도 수는 원장이 이력이라 감사에서 빼고 등록·수정(품목 외 칸)만 남긴다
CREATE TRIGGER tg_audit AFTER INSERT OR UPDATE OR DELETE ON wms_labeler_box FOR EACH ROW
    EXECUTE FUNCTION hb_audit('box_id', 'status,attempt_cnt,last_reason_cd,prefetched_at,prefetched_by,done_at,sscc,row_ver,updated_at,updated_by', '');

ALTER TABLE wms_labeler ENABLE ROW LEVEL SECURITY;
ALTER TABLE wms_labeler FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON wms_labeler USING (comp_cd = hb_current_comp()) WITH CHECK (comp_cd = hb_current_comp());
ALTER TABLE wms_labeler_box ENABLE ROW LEVEL SECURITY;
ALTER TABLE wms_labeler_box FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON wms_labeler_box USING (comp_cd = hb_current_comp()) WITH CHECK (comp_cd = hb_current_comp());
ALTER TABLE wms_labeler_event ENABLE ROW LEVEL SECURITY;
ALTER TABLE wms_labeler_event FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON wms_labeler_event USING (comp_cd = hb_current_comp()) WITH CHECK (comp_cd = hb_current_comp());
ALTER TABLE wms_labeler_dev_log ENABLE ROW LEVEL SECURITY;
ALTER TABLE wms_labeler_dev_log FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON wms_labeler_dev_log USING (comp_cd = hb_current_comp()) WITH CHECK (comp_cd = hb_current_comp());

REVOKE DELETE, TRUNCATE ON wms_labeler, wms_labeler_box FROM ${app_role};
REVOKE UPDATE, DELETE, TRUNCATE ON wms_labeler_event, wms_labeler_dev_log FROM ${app_role};

INSERT INTO sys_prog (prog_cd, prog_nm_key, view_path, module_cd, actions, sort_no) VALUES
    ('wms-labeler', 'prog.wmsLabeler', 'wms/labeler', 'WMS', ARRAY['SEARCH','SAVE']::varchar(20)[], 425);

INSERT INTO sys_retention_table (table_nm, class_cd) VALUES
    ('wms_labeler', 'MASTER'), ('wms_labeler_box', 'QUALITY_RECORD'),
    ('wms_labeler_event', 'QUALITY_RECORD'), ('wms_labeler_dev_log', 'PRODUCTION_RECORD');
