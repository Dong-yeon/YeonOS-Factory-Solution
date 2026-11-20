-- =====================================================================
-- V9850(임시, 병합 때 재번호) APS 9단계 — 현행 계획·확정 구간(G6) → 일일 배정(G5) (docs/38 18장, docs/63 AP-10·AP-11)
--
-- - 정책 APS_FENCE_DAYS: 상세 일정 확정 구간(일, 기본 2) — 품목별 aps_item_param.fence_days 가 있으면 그것 우선
-- - 현행 계획 = 공장의 마지막 APPLIED 계획(칸 없음) — 찾는 인덱스만
-- - 남의 표 변경 없음
-- =====================================================================
INSERT INTO sys_policy_def (policy_key, value_type, default_value, allowed_values, min_value, max_value, desc_key) VALUES
    ('APS_FENCE_DAYS', 'INT', '2', NULL, 0, 60, 'policy.APS_FENCE_DAYS');

CREATE INDEX ix_aps_plan_applied ON aps_plan (comp_cd, plant_cd, applied_at DESC) WHERE status = 'APPLIED';
