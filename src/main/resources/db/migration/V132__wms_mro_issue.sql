-- =====================================================================
-- WMS 2부 CMS 예비품 출고 연계 (docs/33 11.15 계약 SpareIssueLink · docs/30 12.8, D30~D33) — 임시 번호(docs/29 2.2)
--
-- - 원장 유형 ISSUE_MRO(−, 보전 작업지시 완료 때 사용 예비품 출고)·MRO_RTN(+, 덜 쓴 것 반납 — 역분개가 아니라 새 행, D32)
--   출처 = CMS_PART(src_id = cms_mwo_part.part_id). WMS 는 CMS 표를 읽지 않고 FK 도 걸지 않는다(값은 계약 인자로)
-- - 공장 정책 WMS_MRO_LINK(기본 N — 투입 차감 WMS_STOCK_LINK 와 따로). N 이면 CMS 는 기록만(지금과 같음)
-- - 남의 표 변경 없음
-- =====================================================================

INSERT INTO sys_policy_def (policy_key, value_type, default_value, allowed_values, min_value, max_value, plant_scope_yn, desc_key) VALUES
    ('WMS_MRO_LINK', 'BOOL', 'N', NULL, NULL, NULL, 'Y', 'policy.WMS_MRO_LINK');

INSERT INTO sys_code (grp_cd, code, code_nm_key, sort_no) VALUES
    ('INV_TRX_TYPE', 'ISSUE_MRO', 'code.INV_TRX_TYPE.ISSUE_MRO', 27),
    ('INV_TRX_TYPE', 'MRO_RTN',   'code.INV_TRX_TYPE.MRO_RTN',   28);

ALTER TABLE inv_trx DROP CONSTRAINT ck_inv_trx_type;
ALTER TABLE inv_trx ADD CONSTRAINT ck_inv_trx_type CHECK (trx_type IN ('OPENING', 'ADJ_IN', 'ADJ_OUT', 'MOVE_OUT', 'MOVE_IN', 'STATUS_OUT',
                                                                        'STATUS_IN', 'COUNT_ADJ', 'RCV_PRD', 'RCV_MAT', 'ISSUE_WO',
                                                                        'CONV_OUT', 'CONV_IN', 'RCV_PO', 'SHIP', 'ISSUE_MRO', 'MRO_RTN'));
ALTER TABLE inv_trx DROP CONSTRAINT ck_inv_trx_sign;
ALTER TABLE inv_trx ADD CONSTRAINT ck_inv_trx_sign CHECK (
    trx_type = 'COUNT_ADJ'
    OR (trx_type IN ('OPENING', 'ADJ_IN', 'MOVE_IN', 'STATUS_IN', 'RCV_PRD', 'RCV_MAT', 'CONV_IN', 'RCV_PO', 'MRO_RTN')
        AND (qty > 0) = (reversal_of_id IS NULL))
    OR (trx_type IN ('ADJ_OUT', 'MOVE_OUT', 'STATUS_OUT', 'ISSUE_WO', 'CONV_OUT', 'SHIP', 'ISSUE_MRO') AND (qty < 0) = (reversal_of_id IS NULL)));
-- 출고·반납은 출처 = 사용 예비품 줄
ALTER TABLE inv_trx ADD CONSTRAINT ck_inv_trx_mro_src CHECK (
    trx_type NOT IN ('ISSUE_MRO', 'MRO_RTN') OR (src_type = 'CMS_PART' AND src_id IS NOT NULL));
CREATE INDEX ix_inv_trx_cms_part ON inv_trx (comp_cd, src_id) WHERE src_type = 'CMS_PART';
