-- APS A30 재판정 (docs/38 8장, 2026-10-22): 입고 예정(SRM 열린 발주) 포함을 기본 켬으로.
-- 근거: SRM 2부 R1 입고 차감(recv_qty) + WMS B4b 구매 입고 RCV_PO(PoReceiptGate 호출)가 main 에 들어와
--       발주와 연결해 받은 수량은 재고(+)와 동시에 발주 열린 수량에서 빠진다 — 이중 계산 사유가 사라짐.
-- 서비스는 매번 값을 넣으므로(빈 값 = Y) 열 기본값은 직접 INSERT 의 일관성만 맞춘다. 이미 만든 계획 행은 그대로(결과 불변).
ALTER TABLE aps_plan ALTER COLUMN mat_po SET DEFAULT 'Y';
COMMENT ON COLUMN aps_plan.mat_po IS '입고 예정(SRM 열린 발주 = 수량 − 취소 − 입고) 포함 여부 — 기본 Y(A30 재판정 2026-10-22)';
