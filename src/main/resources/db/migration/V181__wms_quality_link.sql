-- =====================================================================
-- P4 2부 B3 품질 상태 연동 (docs/30 12.15, QMS 검토 docs/29_P3 21장) — 임시 번호(docs/29 2.2)
--
-- - QMS 판정·무효·NCR 종결이 계약 QualityStockLink(WMS)를 같은 트랜잭션에서 부른다 → 재고 상태 STATUS_OUT/IN·폐기 SCRAP
-- - 원장 유형 SCRAP(−, NCR 처분 폐기). 출처 QMS_INSP(src_id = qms_insp.insp_id — 상태 바꿈 짝)·QMS_NCR(src_id = qms_ncr_disp.disp_id)
--   FK 없음(값은 계약으로, QMS 표는 읽기만)
-- - 공장 정책 WMS_QUALITY_LINK(N 기본, D-B28) — 검사 운영이 준비된 공장부터
-- - 남의 표 변경 없음
-- =====================================================================

INSERT INTO sys_code (grp_cd, code, code_nm_key, sort_no) VALUES
    ('INV_TRX_TYPE', 'SCRAP', 'code.INV_TRX_TYPE.SCRAP', 30);

ALTER TABLE inv_trx DROP CONSTRAINT ck_inv_trx_type;
ALTER TABLE inv_trx ADD CONSTRAINT ck_inv_trx_type CHECK (trx_type IN ('OPENING', 'ADJ_IN', 'ADJ_OUT', 'MOVE_OUT', 'MOVE_IN', 'STATUS_OUT',
                                                                        'STATUS_IN', 'COUNT_ADJ', 'RCV_PRD', 'RCV_MAT', 'ISSUE_WO',
                                                                        'CONV_OUT', 'CONV_IN', 'RCV_PO', 'SHIP', 'ISSUE_MRO', 'MRO_RTN',
                                                                        'RTN_SUP', 'SCRAP'));
ALTER TABLE inv_trx DROP CONSTRAINT ck_inv_trx_sign;
ALTER TABLE inv_trx ADD CONSTRAINT ck_inv_trx_sign CHECK (
    trx_type = 'COUNT_ADJ'
    OR (trx_type IN ('OPENING', 'ADJ_IN', 'MOVE_IN', 'STATUS_IN', 'RCV_PRD', 'RCV_MAT', 'CONV_IN', 'RCV_PO', 'MRO_RTN')
        AND (qty > 0) = (reversal_of_id IS NULL))
    OR (trx_type IN ('ADJ_OUT', 'MOVE_OUT', 'STATUS_OUT', 'ISSUE_WO', 'CONV_OUT', 'SHIP', 'ISSUE_MRO', 'RTN_SUP', 'SCRAP')
        AND (qty < 0) = (reversal_of_id IS NULL)));
-- 폐기는 NCR 처분에서만(사람의 폐기는 조정 출고 + 사유 코드 그대로)
ALTER TABLE inv_trx ADD CONSTRAINT ck_inv_trx_scrap_src CHECK (
    trx_type <> 'SCRAP' OR (src_type = 'QMS_NCR' AND src_id IS NOT NULL));
-- QMS 출처 행은 출처 id 필수
ALTER TABLE inv_trx ADD CONSTRAINT ck_inv_trx_qms_src CHECK (
    src_type NOT IN ('QMS_INSP', 'QMS_NCR') OR src_id IS NOT NULL);
-- 출처로 찾기는 기존 ix_inv_trx_src(comp_cd, src_type, src_id) 그대로

INSERT INTO sys_policy_def (policy_key, value_type, default_value, allowed_values, min_value, max_value, plant_scope_yn, desc_key) VALUES
    ('WMS_QUALITY_LINK', 'BOOL', 'N', NULL, NULL, NULL, 'Y', 'policy.WMS_QUALITY_LINK');
