-- =====================================================================
-- 연계 현황·실패 재전송 (docs/36 19장, docs/49 6장 1·W-2 사용자 결정 2026-10-29) — V206·임시 V9713
-- - 새 상태 CLOSED_MANUAL(수동 종결): SENT 와 섞지 않는다. 릴레이가 집지 않고(집는 조건 NEW·SENDING 그대로),
--   FAIL 집계(운영 지표·홈 카드)에서 빠지고, 행은 남는다. 종결 칸(누가·언제·사유)이 모두 있어야 한다
-- - 재시도·종결 기록 칸: 재시도 수·마지막 재시도 누가/언제, 종결 누가/언제/사유, 종결 결재 id(정책 Y 일 때 결재 대기)
-- - 화면 sys-outbox: SEARCH · SAVE(재시도) · FN1(수동 종결) — 기본 권한은 관리자 그룹만
-- - 정책 OUTBOX_CLOSE_APPROVAL(회사, 기본 N): Y 면 종결이 결재(문서 유형 OUTBOX_CLOSE)를 거친다
-- - WMS 인쇄 상태 코드에 CLOSED_MANUAL(인쇄 이력이 아웃박스 상태를 그대로 보인다 — 날 코드로 보이지 않게)
-- 임시 번호(docs/29 2.2) — 병합 때 이름만 바뀐다.
-- =====================================================================

ALTER TABLE sys_outbox
    ADD COLUMN retry_cnt     integer      NOT NULL DEFAULT 0,
    ADD COLUMN retried_at    timestamptz,
    ADD COLUMN retried_by    varchar(80),
    ADD COLUMN closed_at     timestamptz,
    ADD COLUMN closed_by     varchar(80),
    ADD COLUMN close_reason  varchar(500),
    ADD COLUMN close_apr_id  bigint;

-- 상태 칸 10자 → 20자: CLOSED_MANUAL 이 13자. varchar 길이만 늘리는 것은 표를 다시 쓰지 않는다(PostgreSQL 메타데이터 변경)
ALTER TABLE sys_outbox ALTER COLUMN status TYPE varchar(20);
ALTER TABLE sys_outbox DROP CONSTRAINT ck_sys_outbox_status;
ALTER TABLE sys_outbox ADD CONSTRAINT ck_sys_outbox_status
    CHECK (status IN ('NEW', 'SENDING', 'SENT', 'FAIL', 'CANCELED', 'CLOSED_MANUAL'));
ALTER TABLE sys_outbox ADD CONSTRAINT ck_sys_outbox_closed
    CHECK (status <> 'CLOSED_MANUAL' OR (closed_at IS NOT NULL AND closed_by IS NOT NULL AND close_reason IS NOT NULL));
ALTER TABLE sys_outbox ADD CONSTRAINT ck_sys_outbox_retry_cnt CHECK (retry_cnt >= 0);

-- 화면 조회(기간 = 만든 시각, 최신순)
CREATE INDEX ix_sys_outbox_created ON sys_outbox (comp_cd, created_at DESC, outbox_id DESC);
-- 홈 카드·운영 지표(FAIL 만 — 작은 부분 인덱스)
CREATE INDEX ix_sys_outbox_fail ON sys_outbox (comp_cd, created_at, outbox_id) WHERE status = 'FAIL';

COMMENT ON TABLE sys_outbox IS '아웃박스 — 업무 트랜잭션과 같이 커밋되는 발행 대기. 상태 NEW → SENDING → SENT / (재시도 NEW) / FAIL, 보내기 전 취소 NEW → CANCELED, 실패 수동 종결 FAIL → CLOSED_MANUAL(docs/36 19장)';
COMMENT ON COLUMN sys_outbox.retry_cnt IS '연계 현황 화면에서 재시도한 횟수 (FAIL → NEW, attempts 는 0 으로)';
COMMENT ON COLUMN sys_outbox.close_apr_id IS '수동 종결 결재 대기(apr_request) — 있으면 행은 FAIL 그대로, 재시도·다시 종결 불가. 승인 = CLOSED_MANUAL, 반려·회수 = 비움';

INSERT INTO sys_prog (prog_cd, prog_nm_key, view_path, module_cd, actions, sort_no, self_service_yn) VALUES
    ('sys-outbox', 'prog.sysOutbox', 'admin/outbox-mgt', 'SYS', ARRAY['SEARCH', 'SAVE', 'FN1']::varchar(20)[], 955, 'N');

INSERT INTO sys_policy_def (policy_key, value_type, default_value, allowed_values, min_value, max_value, plant_scope_yn, desc_key) VALUES
    ('OUTBOX_CLOSE_APPROVAL', 'BOOL', 'N', NULL, NULL, NULL, 'N', 'policy.OUTBOX_CLOSE_APPROVAL');

INSERT INTO sys_code (grp_cd, code, code_nm_key, sort_no) VALUES
    ('WMS_PRINT_STATUS', 'CLOSED_MANUAL', 'code.WMS_PRINT_STATUS.CLOSED_MANUAL', 5);
