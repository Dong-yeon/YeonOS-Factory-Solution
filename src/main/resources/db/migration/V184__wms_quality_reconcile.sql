-- =====================================================================
-- WMS B3 품질 상태 연동 대사 (docs/30 12.15.12) — 임시 번호(docs/29 2.2)
--
-- - inv_reconcile_run.qc_judged     = 스위치 Y 공장에 QC 재고가 남았는데 그 LOT 의 수입·출하검사가 판정(JUDGED)되고
--                                     열린 검사(OPEN)가 없는 LOT 수 — 판정 반영이 빠진 경로 검출
-- - inv_reconcile_run.qms_void_live = 무효(VOID) 검사인데 되돌리지 않은 판정 원장(QMS_INSP)이 남은 검사 수
-- - 둘 다 0 이 정상, 넘으면 대사 알림. 자동 보정 없음. 남의 표 변경 없음(qms_insp 는 읽기만)
-- =====================================================================

ALTER TABLE inv_reconcile_run ADD COLUMN qc_judged integer;
ALTER TABLE inv_reconcile_run ADD COLUMN qms_void_live integer;
ALTER TABLE inv_reconcile_run ADD CONSTRAINT ck_inv_reconcile_run_quality
    CHECK ((qc_judged IS NULL OR qc_judged >= 0) AND (qms_void_live IS NULL OR qms_void_live >= 0));
COMMENT ON COLUMN inv_reconcile_run.qc_judged IS '품질 연동 공장의 QC 재고 LOT 중 판정이 끝났는데(열린 검사 없음) 남은 LOT 수 — 0 이 정상(B3)';
COMMENT ON COLUMN inv_reconcile_run.qms_void_live IS '무효 검사인데 되돌리지 않은 판정 원장이 남은 검사 수 — 0 이 정상(B3)';
