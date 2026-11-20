-- =====================================================================
-- V008 아웃박스 · 인박스 · 결재 (P0 7단계, 01 5.4·5.8)
--
-- 아웃박스: 업무 트랜잭션 안에서 발행 → 릴레이 3단(집기 → 트랜잭션 밖 송신 → 결과 기록). 최소 1회 전달.
-- 인박스: (회사, 소비자, 메시지 ID) 처리 기록 — 처리 트랜잭션 안에서 넣어 중복이면 무시(롤백되면 기록도 없음).
-- 결재: 결재 엔진은 업무 테이블을 모른다. 문서 유형 핸들러가 같은 트랜잭션에서 업무 상태를 바꾼다.
-- =====================================================================

-- ---------------------------------------------------------------------
-- 1. 아웃박스
-- ---------------------------------------------------------------------
CREATE TABLE sys_outbox (
    outbox_id      bigint       GENERATED ALWAYS AS IDENTITY,
    comp_cd        varchar(20)  NOT NULL,
    message_id     uuid         NOT NULL,
    topic          varchar(50)  NOT NULL,
    biz_key        varchar(100),
    payload        jsonb        NOT NULL,
    status         varchar(10)  NOT NULL DEFAULT 'NEW',
    attempts       integer      NOT NULL DEFAULT 0,
    max_attempts   integer      NOT NULL DEFAULT 10,
    next_retry_at  timestamptz  NOT NULL DEFAULT now(),
    locked_at      timestamptz,
    sent_at        timestamptz,
    last_error     varchar(500),
    created_at     timestamptz  NOT NULL DEFAULT now(),
    created_by     varchar(80)  NOT NULL,
    CONSTRAINT pk_sys_outbox PRIMARY KEY (outbox_id),
    CONSTRAINT fk_sys_outbox_comp FOREIGN KEY (comp_cd) REFERENCES sys_comp (comp_cd),
    CONSTRAINT uq_sys_outbox_message UNIQUE (comp_cd, message_id),
    CONSTRAINT ck_sys_outbox_status CHECK (status IN ('NEW', 'SENDING', 'SENT', 'FAIL')),
    CONSTRAINT ck_sys_outbox_topic CHECK (topic ~ '^[A-Z][A-Z0-9_]{1,49}$'),
    CONSTRAINT ck_sys_outbox_attempts CHECK (attempts >= 0 AND max_attempts BETWEEN 1 AND 100),
    CONSTRAINT ck_sys_outbox_sending CHECK (status <> 'SENDING' OR locked_at IS NOT NULL)
);
-- 릴레이가 집는 대상만 (보낸 것·실패는 인덱스에서 빠진다)
CREATE INDEX ix_sys_outbox_pending ON sys_outbox (comp_cd, next_retry_at, outbox_id) WHERE status IN ('NEW', 'SENDING');
CREATE INDEX ix_sys_outbox_biz ON sys_outbox (comp_cd, topic, biz_key);
COMMENT ON TABLE sys_outbox IS '아웃박스 — 업무 트랜잭션과 같이 커밋되는 발행 대기. 상태 NEW → SENDING → SENT / (재시도 NEW) / FAIL';
COMMENT ON COLUMN sys_outbox.message_id IS '수신측 멱등키 (재전송돼도 같은 값)';
COMMENT ON COLUMN sys_outbox.locked_at IS '집은 시각 — 오래된 SENDING(프로세스 중단)은 다시 집는다. 결과 기록은 이 값이 같을 때만';

-- ---------------------------------------------------------------------
-- 2. 인박스 (수신 멱등)
-- ---------------------------------------------------------------------
CREATE TABLE sys_inbox (
    comp_cd      varchar(20)  NOT NULL,
    consumer_cd  varchar(50)  NOT NULL,
    message_id   varchar(100) NOT NULL,
    received_at  timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT pk_sys_inbox PRIMARY KEY (comp_cd, consumer_cd, message_id),
    CONSTRAINT fk_sys_inbox_comp FOREIGN KEY (comp_cd) REFERENCES sys_comp (comp_cd),
    CONSTRAINT ck_sys_inbox_consumer CHECK (consumer_cd ~ '^[A-Z][A-Z0-9_:-]{1,49}$'),
    CONSTRAINT ck_sys_inbox_message CHECK (message_id ~ '^[A-Za-z0-9_.:-]{8,100}$')
);
COMMENT ON TABLE sys_inbox IS '수신 처리 기록 (아웃박스 소비자·/ext·API 멱등키). 처리 트랜잭션 안에서 넣는다 — 중복이면 처리하지 않음';

-- ---------------------------------------------------------------------
-- 3. 결재
-- ---------------------------------------------------------------------
CREATE TABLE apr_request (
    apr_id         bigint       GENERATED ALWAYS AS IDENTITY,
    comp_cd        varchar(20)  NOT NULL,
    doc_type       varchar(50)  NOT NULL,
    biz_key        varchar(100) NOT NULL,
    title          varchar(200) NOT NULL,
    status         varchar(10)  NOT NULL DEFAULT 'PENDING',
    requester_id   bigint       NOT NULL,
    cur_seq        integer,
    requested_at   timestamptz  NOT NULL DEFAULT now(),
    finished_at    timestamptz,
    row_ver        integer      NOT NULL DEFAULT 0,
    created_at     timestamptz  NOT NULL DEFAULT now(),
    created_by     varchar(50)  NOT NULL,
    updated_at     timestamptz  NOT NULL DEFAULT now(),
    updated_by     varchar(50)  NOT NULL,
    CONSTRAINT pk_apr_request PRIMARY KEY (apr_id),
    CONSTRAINT fk_apr_request_comp FOREIGN KEY (comp_cd) REFERENCES sys_comp (comp_cd),
    CONSTRAINT fk_apr_request_user FOREIGN KEY (requester_id) REFERENCES sys_user (user_id),
    CONSTRAINT ck_apr_request_status CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED', 'WITHDRAWN')),
    CONSTRAINT ck_apr_request_doc_type CHECK (doc_type ~ '^[A-Z][A-Z0-9_]{1,49}$'),
    CONSTRAINT ck_apr_request_title CHECK (length(btrim(title)) > 0),
    CONSTRAINT ck_apr_request_finished CHECK ((status = 'PENDING') = (finished_at IS NULL))
);
-- 같은 업무 문서는 진행 중 결재 1건만 (중복 상신 방지)
CREATE UNIQUE INDEX uq_apr_request_active ON apr_request (comp_cd, doc_type, biz_key) WHERE status = 'PENDING';
CREATE INDEX ix_apr_request_requester ON apr_request (comp_cd, requester_id, requested_at);
COMMENT ON TABLE apr_request IS '결재 요청 — 결재 엔진은 업무 테이블을 모른다(doc_type + biz_key 로만 연결)';

CREATE TABLE apr_line (
    line_id      bigint       GENERATED ALWAYS AS IDENTITY,
    comp_cd      varchar(20)  NOT NULL,
    apr_id       bigint       NOT NULL,
    seq          integer      NOT NULL,
    approver_id  bigint       NOT NULL,
    line_type    varchar(10)  NOT NULL,
    status       varchar(10)  NOT NULL DEFAULT 'WAIT',
    acted_at     timestamptz,
    comment      varchar(1000),
    CONSTRAINT pk_apr_line PRIMARY KEY (line_id),
    CONSTRAINT fk_apr_line_comp FOREIGN KEY (comp_cd) REFERENCES sys_comp (comp_cd),
    CONSTRAINT fk_apr_line_request FOREIGN KEY (apr_id) REFERENCES apr_request (apr_id),
    CONSTRAINT fk_apr_line_user FOREIGN KEY (approver_id) REFERENCES sys_user (user_id),
    CONSTRAINT uq_apr_line_seq UNIQUE (comp_cd, apr_id, seq),
    CONSTRAINT ck_apr_line_type CHECK (line_type IN ('APPROVE', 'AGREE', 'REF')),
    CONSTRAINT ck_apr_line_status CHECK (status IN ('WAIT', 'CURRENT', 'APPROVED', 'REJECTED', 'SKIPPED', 'REF')),
    CONSTRAINT ck_apr_line_seq CHECK (seq BETWEEN 1 AND 99)
);
CREATE INDEX ix_apr_line_approver ON apr_line (comp_cd, approver_id, status);
COMMENT ON TABLE apr_line IS '결재선 — APPROVE/AGREE 는 순서대로 한 명씩(CURRENT), REF 는 참조(처리 없음)';

CREATE TABLE apr_history (
    hist_id      bigint        GENERATED ALWAYS AS IDENTITY,
    comp_cd      varchar(20)   NOT NULL,
    apr_id       bigint        NOT NULL,
    action       varchar(10)   NOT NULL,
    seq          integer,
    actor_id     bigint        NOT NULL,
    comment      varchar(1000),
    occurred_at  timestamptz   NOT NULL DEFAULT now(),
    CONSTRAINT pk_apr_history PRIMARY KEY (hist_id),
    CONSTRAINT fk_apr_history_comp FOREIGN KEY (comp_cd) REFERENCES sys_comp (comp_cd),
    CONSTRAINT fk_apr_history_request FOREIGN KEY (apr_id) REFERENCES apr_request (apr_id),
    CONSTRAINT ck_apr_history_action CHECK (action IN ('SUBMIT', 'APPROVE', 'AGREE', 'REJECT', 'WITHDRAW'))
);
CREATE INDEX ix_apr_history_request ON apr_history (comp_cd, apr_id, hist_id);
COMMENT ON TABLE apr_history IS '결재 처리 이력 (추가만 — 앱 UPDATE/DELETE 없음)';

-- 회사별 테이블 간 FK 는 RLS 를 무시한다 → 다른 회사 사용자·요청을 가리키지 못하게 확인
CREATE FUNCTION hb_check_apr_comp() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_TABLE_NAME = 'apr_request' THEN
        IF NOT EXISTS (SELECT 1 FROM sys_user u WHERE u.user_id = NEW.requester_id AND u.comp_cd = NEW.comp_cd) THEN
            RAISE EXCEPTION 'requester % does not belong to company %', NEW.requester_id, NEW.comp_cd
                USING ERRCODE = '23503', CONSTRAINT = 'fk_apr_request_user_comp';
        END IF;
    ELSE
        IF NOT EXISTS (SELECT 1 FROM sys_user u WHERE u.user_id = NEW.approver_id AND u.comp_cd = NEW.comp_cd) THEN
            RAISE EXCEPTION 'approver % does not belong to company %', NEW.approver_id, NEW.comp_cd
                USING ERRCODE = '23503', CONSTRAINT = 'fk_apr_line_user_comp';
        END IF;
        IF NOT EXISTS (SELECT 1 FROM apr_request r WHERE r.apr_id = NEW.apr_id AND r.comp_cd = NEW.comp_cd) THEN
            RAISE EXCEPTION 'request % does not belong to company %', NEW.apr_id, NEW.comp_cd
                USING ERRCODE = '23503', CONSTRAINT = 'fk_apr_line_request_comp';
        END IF;
    END IF;
    RETURN NEW;
END
$$;
CREATE TRIGGER tg_apr_request_comp BEFORE INSERT OR UPDATE ON apr_request
    FOR EACH ROW EXECUTE FUNCTION hb_check_apr_comp();
CREATE TRIGGER tg_apr_line_comp BEFORE INSERT OR UPDATE ON apr_line
    FOR EACH ROW EXECUTE FUNCTION hb_check_apr_comp();

CREATE TRIGGER tg_audit AFTER INSERT OR UPDATE OR DELETE ON apr_request
    FOR EACH ROW EXECUTE FUNCTION hb_audit('apr_id', '', '');
CREATE TRIGGER tg_audit AFTER INSERT OR UPDATE OR DELETE ON apr_line
    FOR EACH ROW EXECUTE FUNCTION hb_audit('line_id', '', '');

-- ---------------------------------------------------------------------
-- 4. RLS · 권한
-- ---------------------------------------------------------------------
ALTER TABLE sys_outbox ENABLE ROW LEVEL SECURITY;
ALTER TABLE sys_outbox FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON sys_outbox USING (comp_cd = hb_current_comp()) WITH CHECK (comp_cd = hb_current_comp());

ALTER TABLE sys_inbox ENABLE ROW LEVEL SECURITY;
ALTER TABLE sys_inbox FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON sys_inbox USING (comp_cd = hb_current_comp()) WITH CHECK (comp_cd = hb_current_comp());

ALTER TABLE apr_request ENABLE ROW LEVEL SECURITY;
ALTER TABLE apr_request FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON apr_request USING (comp_cd = hb_current_comp()) WITH CHECK (comp_cd = hb_current_comp());

ALTER TABLE apr_line ENABLE ROW LEVEL SECURITY;
ALTER TABLE apr_line FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON apr_line USING (comp_cd = hb_current_comp()) WITH CHECK (comp_cd = hb_current_comp());

ALTER TABLE apr_history ENABLE ROW LEVEL SECURITY;
ALTER TABLE apr_history FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON apr_history USING (comp_cd = hb_current_comp()) WITH CHECK (comp_cd = hb_current_comp());

-- 아웃박스는 상태만 바뀐다(삭제는 9단계 정리 프로시저), 인박스·결재 이력은 추가만, 결재 요청·결재선은 삭제 없음(회수는 상태)
REVOKE DELETE, TRUNCATE ON sys_outbox, apr_request, apr_line FROM ${app_role};
REVOKE UPDATE, DELETE, TRUNCATE ON sys_inbox, apr_history FROM ${app_role};

-- ---------------------------------------------------------------------
-- 5. 보존 클래스 등록 · 정책
-- ---------------------------------------------------------------------
INSERT INTO sys_retention_class (class_cd, basis, default_days, min_days, purge_allowed_yn, desc_key) VALUES
    ('MESSAGE',  'KEEP', NULL, NULL, 'N', 'retention.MESSAGE'),   -- 보낸 아웃박스·인박스 정리는 9단계(상태 조건이 필요해 OCCURRED_AT 정리와 다름)
    ('APPROVAL', 'KEEP', NULL, NULL, 'N', 'retention.APPROVAL');

INSERT INTO sys_retention_table (table_nm, class_cd) VALUES
    ('sys_outbox', 'MESSAGE'), ('sys_inbox', 'MESSAGE'),
    ('apr_request', 'APPROVAL'), ('apr_line', 'APPROVAL'), ('apr_history', 'APPROVAL');

INSERT INTO sys_policy_def (policy_key, value_type, default_value, allowed_values, min_value, max_value, desc_key) VALUES
    ('APR_SELF_APPROVE_ALLOWED', 'BOOL', 'N', NULL, NULL, NULL, 'policy.APR_SELF_APPROVE_ALLOWED'),
    ('APR_MAX_LINES',            'INT',  '10', NULL, 1, 30,     'policy.APR_MAX_LINES');
