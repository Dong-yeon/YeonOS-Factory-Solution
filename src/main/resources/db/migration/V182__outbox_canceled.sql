-- =====================================================================
-- 아웃박스 취소 상태 CANCELED (docs/36 18.7, 상위 결정 2026-10-24) — 외부 메일 발행 취소(MailService.cancel)
-- - FAIL 은 장애로 센다(docs/37 홈 카드 "아웃박스 실패", docs/07 REJECTED → FAIL + 알림) — 취소가 섞이지 않게 상태를 따로
-- - CANCELED 는 릴레이가 집지 않는다(집는 조건 NEW·SENDING 그대로, 대기 인덱스도 그대로)
-- 임시 번호(docs/29 2.2) — 병합 때 이름만 바뀐다.
-- =====================================================================

ALTER TABLE sys_outbox DROP CONSTRAINT ck_sys_outbox_status;
ALTER TABLE sys_outbox ADD CONSTRAINT ck_sys_outbox_status
    CHECK (status IN ('NEW', 'SENDING', 'SENT', 'FAIL', 'CANCELED'));

COMMENT ON TABLE sys_outbox IS '아웃박스 — 업무 트랜잭션과 같이 커밋되는 발행 대기. 상태 NEW → SENDING → SENT / (재시도 NEW) / FAIL, 보내기 전 취소 NEW → CANCELED';
