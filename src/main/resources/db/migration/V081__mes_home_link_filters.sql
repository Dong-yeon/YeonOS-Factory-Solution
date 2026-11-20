-- =====================================================================
-- MES 홈 카드 링크용 조회 칸 (docs/25 4.13, 홈 H17) — 인덱스만, 표·트리거 변경 없음
-- - prd-lot 열린 기간(최근 순)·보류만, prd-wo 발행+진행 중 묶음 조회가 회사 전체 행을 훑지 않게
-- - 수주 납기 조건은 기존 ix_sal_order_line_due(OPEN 부분 인덱스)로 충분
-- =====================================================================

CREATE INDEX ix_lot_master_created ON lot_master (comp_cd, created_at, lot_id);
CREATE INDEX ix_lot_master_hold ON lot_master (comp_cd, lot_id) WHERE hold_yn = 'Y';
CREATE INDEX ix_prd_wo_active ON prd_wo (comp_cd, plan_start) WHERE status IN ('RELEASED', 'IN_PROGRESS');
