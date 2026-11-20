-- =====================================================================
-- LOT 보류 근거 출처에 QMS_SPC 추가 (QMS SV-6 SPC 자동 LOT 보류 — docs/29_P3 23.4 M-2, docs/25 4.43) — V191(임시 V9121)
--
-- - 최신 정의는 V109(ck_lot_hold_source) — 그 뒤 바꾼 마이그레이션 없음(grep). 값만 하나 더, 나머지 규칙 그대로
-- - ref_id: QMS_* 출처는 FK 없음(근거 행 확인은 부르는 쪽 QMS 책임 — QMS_NCR·QMS_CONTAIN 과 같음). QMS_SPC 의 ref = SPC 이벤트 id
-- - 쓰는 길은 LotHoldPort(MesLotHoldPort) 하나 — QMS 구현(판정 훅 보류·CLOSE/VOID 해제)은 이 PR 병합 뒤
-- =====================================================================

ALTER TABLE lot_hold DROP CONSTRAINT ck_lot_hold_source;
ALTER TABLE lot_hold ADD CONSTRAINT ck_lot_hold_source CHECK (source IN ('MANUAL', 'MES_CORRECT', 'QMS_NCR', 'QMS_CONTAIN', 'QMS_SPC'));

COMMENT ON COLUMN lot_hold.source IS 'MANUAL(LOT 화면) · MES_CORRECT(POP 실적 정정, ref = prd_result) · QMS_NCR · QMS_CONTAIN · QMS_SPC(SPC 이벤트, ref = 이벤트 id — QMS 는 LotHoldPort 로만)';
