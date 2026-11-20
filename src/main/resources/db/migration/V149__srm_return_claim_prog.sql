-- =====================================================================
-- SRM 2부 R4c·R4d 반품 지시·클레임 화면 (docs/31 4.12.4) — 임시 번호, 병합 때 재번호
--
-- - srm-return: 반품 대상(QMS 닫힌 NCR 의 RETURN 처리 줄 — 공개 계약 SupplierQualityQuery.returnsToSupplier)에서 만들기,
--   작성 중 수정 → 확정(APPROVE) / 취소(사유). 출고(SHIPPED)는 WMS 반품 출고 게이트(R4-4, 나중)
-- - srm-claim: 근거 NCR(공개 계약 ncr(id) 로 확인·스냅샷) + 요구 조치·금액 → 보내기(통지 아웃박스 SRM_CLAIM_SENT) →
--   회신 입력(협력사 대신 — D4) → 반려(다시 요구) / 종결(APPROVE) / 취소(사유)
-- - 표는 V147(srm_return·srm_claim) — 여기는 화면 등록만
-- =====================================================================

INSERT INTO sys_prog (prog_cd, prog_nm_key, view_path, module_cd, actions, sort_no) VALUES
    ('srm-return', 'prog.srmReturn', 'srm/return-mgt', 'SRM', ARRAY['SEARCH','SAVE','APPROVE']::varchar(20)[], 866),
    ('srm-claim',  'prog.srmClaim',  'srm/claim-mgt',  'SRM', ARRAY['SEARCH','SAVE','APPROVE']::varchar(20)[], 867);
