-- =====================================================================
-- WMS 1부 W4 차감 전략 (docs/30 4.4) — 임시 번호(docs/29 2.2), 상위 세션이 병합 때 이름만 바꾼다
--
-- - 정책 WMS_ALLOC_STRATEGY: 출고 후보를 고르는 기본 순서(FIFO 입고일 / FEFO 유효기한 / LOC_SEQ 피킹 순서). 공장별 값 가능
--   품목별로 다르게 하려면 품목 클래스 속성 ALLOC_STRATEGY(같은 값)를 둔다 — mst_item 칸은 늘리지 않는다(D5 와 같은 방식)
-- - 표는 없다: 차감 전략은 순수 함수(AllocationStrategy)이고 후보는 현재고·원장에서 읽는다
-- =====================================================================

INSERT INTO sys_policy_def (policy_key, value_type, default_value, allowed_values, min_value, max_value, plant_scope_yn, desc_key) VALUES
    ('WMS_ALLOC_STRATEGY', 'ENUM', 'FIFO', ARRAY['FIFO','FEFO','LOC_SEQ'], NULL, NULL, 'Y', 'policy.WMS_ALLOC_STRATEGY');
