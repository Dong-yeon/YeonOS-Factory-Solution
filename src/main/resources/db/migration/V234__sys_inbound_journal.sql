-- =====================================================================
-- 수신 원문 저널 (docs/65 5.2·16.2 — 장비 연동 E0 공통)
--
-- - 외부(엣지·WES·Fleet·라벨러)가 보낸 메시지를 **먼저 원문으로 저장하고 바로 200**, 처리는 정기 처리기가 비동기로.
--   처리 오류는 우리 쪽 격리(QUARANTINE)로 — 상대에 503 을 주지 않는다(현장 WES 는 503 이면 FIFO 로 다시 보내 뒤가 막힌다)
-- - 멱등 키 = 업무 키(경로 코드 + 경로가 정한 칸 값들 — 상관키 + 상태[+ seq])의 SHA-256. 상대 메시지 ID 는 쓰지 않는다
--   (같은 콜백 297회·ID 매번 다름 실측). 경로별 키 규칙은 코드의 InboundRoute 빈이 정한다(플랫폼 수신 계층)
-- - 같은 키·같은 내용 = 원래 행 dup_count + 1(새 행 없음), 같은 키·다른 내용 = CONFLICT 행(증거, 처리 안 함, conflict_of_id)
-- - 원문 칸(경로·키·내용·출처·받은 시각)은 트리거로 고정 — 상태·시도·중복 수만 바뀐다. 앱 DELETE 회수
-- 임시 번호(docs/29 2.2, 시스템관리 대역) — 병합 때 이름만 바뀐다.
-- =====================================================================

CREATE TABLE sys_inbound_msg (
    inbound_id      bigint        GENERATED ALWAYS AS IDENTITY,
    comp_cd         varchar(20)   NOT NULL,
    route_cd        varchar(30)   NOT NULL,
    source_id       bigint,
    source_cd       varchar(50)   NOT NULL,
    biz_key         varchar(300)  NOT NULL,
    key_hash        varchar(64)   NOT NULL,
    body_hash       varchar(64)   NOT NULL,
    body            jsonb         NOT NULL,
    part_key        varchar(100),
    order_no        bigint,
    occurred_at     timestamptz,
    sent_at         timestamptz,
    received_at     timestamptz   NOT NULL DEFAULT now(),
    skew_ms         bigint,
    status          varchar(12)   NOT NULL DEFAULT 'NEW',
    result_cd       varchar(50),
    attempts        integer       NOT NULL DEFAULT 0,
    next_try_at     timestamptz   NOT NULL DEFAULT now(),
    last_error      varchar(500),
    processed_at    timestamptz,
    dup_count       integer       NOT NULL DEFAULT 0,
    last_dup_at     timestamptz,
    conflict_of_id  bigint,
    CONSTRAINT pk_sys_inbound_msg PRIMARY KEY (inbound_id),
    CONSTRAINT fk_sys_inbound_msg_comp FOREIGN KEY (comp_cd) REFERENCES sys_comp (comp_cd),
    CONSTRAINT fk_sys_inbound_msg_conflict FOREIGN KEY (conflict_of_id) REFERENCES sys_inbound_msg (inbound_id),
    CONSTRAINT ck_sys_inbound_msg_route CHECK (route_cd ~ '^[A-Z][A-Z0-9_]{2,29}$'),
    CONSTRAINT ck_sys_inbound_msg_hash CHECK (key_hash ~ '^[0-9a-f]{64}$' AND body_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_sys_inbound_msg_status CHECK (status IN ('NEW', 'DONE', 'STALE', 'REJECTED', 'QUARANTINE', 'CONFLICT')),
    -- 충돌 행 = 증거(처리 안 함), 그 밖은 원래 행
    CONSTRAINT ck_sys_inbound_msg_conflict CHECK ((status = 'CONFLICT') = (conflict_of_id IS NOT NULL)),
    CONSTRAINT ck_sys_inbound_msg_counts CHECK (attempts >= 0 AND dup_count >= 0),
    CONSTRAINT ck_sys_inbound_msg_done CHECK (status IN ('NEW', 'CONFLICT') OR processed_at IS NOT NULL)
);
COMMENT ON TABLE sys_inbound_msg IS '수신 원문 저널 (docs/65 16.2) — 저장 후 200, 처리는 비동기. 업무 키 멱등·같은 키 다른 내용 = CONFLICT·처리 오류 = 격리';
COMMENT ON COLUMN sys_inbound_msg.biz_key IS '사람이 읽는 업무 키(경로가 정한 칸=값|…) — 유일 판단은 key_hash';
COMMENT ON COLUMN sys_inbound_msg.part_key IS '처리 줄 세움 단위(예: 설비 코드) — 같은 단위 안에서는 order_no(seq) 순으로 처리';
COMMENT ON COLUMN sys_inbound_msg.skew_ms IS '받은 시각 − 보낸 쪽 봉투 시각(sentAt). 장치 시계 차이 경고용';
-- 업무 키 하나에 원래 행 하나(충돌 증거 행은 여럿이어도 된다)
CREATE UNIQUE INDEX uq_sys_inbound_msg_key ON sys_inbound_msg (comp_cd, route_cd, key_hash) WHERE conflict_of_id IS NULL;
-- 처리기가 집는 대상만
CREATE INDEX ix_sys_inbound_msg_pending ON sys_inbound_msg (comp_cd, next_try_at, inbound_id) WHERE status = 'NEW';
CREATE INDEX ix_sys_inbound_msg_route ON sys_inbound_msg (comp_cd, route_cd, status, received_at);

CREATE FUNCTION hb_guard_inbound_msg() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    -- 원문 칸은 고정: 상태·결과·시도·중복 수만 바뀐다
    IF (to_jsonb(NEW) - 'status' - 'result_cd' - 'attempts' - 'next_try_at' - 'last_error' - 'processed_at' - 'dup_count' - 'last_dup_at')
       IS DISTINCT FROM
       (to_jsonb(OLD) - 'status' - 'result_cd' - 'attempts' - 'next_try_at' - 'last_error' - 'processed_at' - 'dup_count' - 'last_dup_at') THEN
        RAISE EXCEPTION 'inbound message % raw columns are fixed', OLD.inbound_id
            USING ERRCODE = '55000', CONSTRAINT = 'ck_sys_inbound_msg_raw_fixed';
    END IF;
    -- 충돌 증거 행은 상태를 바꾸지 않는다, 원래 행은 충돌로 바뀌지 않는다
    IF (OLD.status = 'CONFLICT') <> (NEW.status = 'CONFLICT') THEN
        RAISE EXCEPTION 'inbound message % conflict status is fixed', OLD.inbound_id
            USING ERRCODE = '55000', CONSTRAINT = 'ck_sys_inbound_msg_raw_fixed';
    END IF;
    RETURN NEW;
END
$$;
CREATE TRIGGER tg_sys_inbound_msg_guard BEFORE UPDATE ON sys_inbound_msg FOR EACH ROW EXECUTE FUNCTION hb_guard_inbound_msg();

ALTER TABLE sys_inbound_msg ENABLE ROW LEVEL SECURITY;
ALTER TABLE sys_inbound_msg FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON sys_inbound_msg USING (comp_cd = hb_current_comp()) WITH CHECK (comp_cd = hb_current_comp());
REVOKE DELETE, TRUNCATE ON sys_inbound_msg FROM ${app_role};

-- 보존: 업무 키 멱등의 근거라 지금은 KEEP(MESSAGE) — 정리 기준(처리 끝 + 기간, 중복 창)은 후속(docs/65 16 검토)
INSERT INTO sys_retention_table (table_nm, class_cd) VALUES ('sys_inbound_msg', 'MESSAGE');

INSERT INTO sys_code_grp (grp_cd, grp_nm_key, comp_ext_yn) VALUES ('INBOUND_STATUS', 'code.grp.INBOUND_STATUS', 'N');
INSERT INTO sys_code (grp_cd, code, code_nm_key, sort_no) VALUES
    ('INBOUND_STATUS', 'NEW',        'code.INBOUND_STATUS.NEW',        1),
    ('INBOUND_STATUS', 'DONE',       'code.INBOUND_STATUS.DONE',       2),
    ('INBOUND_STATUS', 'STALE',      'code.INBOUND_STATUS.STALE',      3),
    ('INBOUND_STATUS', 'REJECTED',   'code.INBOUND_STATUS.REJECTED',   4),
    ('INBOUND_STATUS', 'QUARANTINE', 'code.INBOUND_STATUS.QUARANTINE', 5),
    ('INBOUND_STATUS', 'CONFLICT',   'code.INBOUND_STATUS.CONFLICT',   6);

INSERT INTO sys_policy_def (policy_key, value_type, default_value, allowed_values, min_value, max_value, desc_key) VALUES
    ('EDGE_INBOUND_MAX_BACKLOG',     'INT', '10000', NULL, 100, 1000000, 'policy.EDGE_INBOUND_MAX_BACKLOG'),
    ('EDGE_INBOUND_RETRY_AFTER_SEC', 'INT', '30',    NULL, 1,   3600,    'policy.EDGE_INBOUND_RETRY_AFTER_SEC'),
    ('EDGE_INBOUND_MAX_ATTEMPTS',    'INT', '5',     NULL, 1,   100,     'policy.EDGE_INBOUND_MAX_ATTEMPTS');
