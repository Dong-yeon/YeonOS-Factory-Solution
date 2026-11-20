-- =====================================================================
-- WMS 2부 B4a 자재 입고 RCV_MAT (docs/30 12.3, D-B3~D-B10 사용자 결정 2026-10-21) — 임시 번호(docs/29 2.2)
--
-- - inv_trx 유형 RCV_MAT(자재 입고, +). 출처 MAT_RECEIPT · src_id = 입고로 만든 RECEIVED LOT(lot_master.lot_id).
--   LOT 은 P2 LotService.receive 가 만든다(LOT 관리 품목이 아니어도 — 공급사·공급사 LOT 추적, 원장 lot_id 는 B1 규칙대로 관리 품목만)
-- - 화면 wms-rcv(SEARCH·SAVE)
-- - 대사: inv_reconcile_run.rcv_mat_over = 입고 원장 합(역분개 포함) > LOT 생성 수량인 LOT 수
-- - 남의 표(mst_*·prd_*·lot_*) 변경 없음
-- =====================================================================

INSERT INTO sys_code (grp_cd, code, code_nm_key, sort_no) VALUES
    ('INV_TRX_TYPE', 'RCV_MAT', 'code.INV_TRX_TYPE.RCV_MAT', 21);

ALTER TABLE inv_trx DROP CONSTRAINT ck_inv_trx_type;
ALTER TABLE inv_trx ADD CONSTRAINT ck_inv_trx_type CHECK (trx_type IN ('OPENING', 'ADJ_IN', 'ADJ_OUT', 'MOVE_OUT', 'MOVE_IN', 'STATUS_OUT',
                                                                        'STATUS_IN', 'COUNT_ADJ', 'RCV_PRD', 'RCV_MAT'));
ALTER TABLE inv_trx DROP CONSTRAINT ck_inv_trx_sign;
ALTER TABLE inv_trx ADD CONSTRAINT ck_inv_trx_sign CHECK (
    trx_type = 'COUNT_ADJ'
    OR (trx_type IN ('OPENING', 'ADJ_IN', 'MOVE_IN', 'STATUS_IN', 'RCV_PRD', 'RCV_MAT') AND (qty > 0) = (reversal_of_id IS NULL))
    OR (trx_type IN ('ADJ_OUT', 'MOVE_OUT', 'STATUS_OUT') AND (qty < 0) = (reversal_of_id IS NULL)));
-- 자재 입고는 출처(입고 LOT)가 반드시 있다 — 직접 SQL 로 출처 없는 입고 행을 막는다
ALTER TABLE inv_trx ADD CONSTRAINT ck_inv_trx_rcv_mat_src CHECK (
    trx_type <> 'RCV_MAT' OR (src_type = 'MAT_RECEIPT' AND src_id IS NOT NULL));

ALTER TABLE inv_reconcile_run ADD COLUMN rcv_mat_over integer;
ALTER TABLE inv_reconcile_run ADD CONSTRAINT ck_inv_reconcile_run_rcv_over CHECK (rcv_mat_over IS NULL OR rcv_mat_over >= 0);
COMMENT ON COLUMN inv_reconcile_run.rcv_mat_over IS '자재 입고 원장 합(역분개 포함)이 입고 LOT 생성 수량보다 많은 LOT 수 — 0 이 정상(B4a)';

INSERT INTO sys_prog (prog_cd, prog_nm_key, view_path, module_cd, actions, sort_no) VALUES
    ('wms-rcv', 'prog.wmsRcv', 'wms/receipt', 'WMS', ARRAY['SEARCH','SAVE']::varchar(20)[], 455);
