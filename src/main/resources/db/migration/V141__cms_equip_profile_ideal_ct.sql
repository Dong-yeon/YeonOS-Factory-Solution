-- =====================================================================
-- 설비 프로필 이론 사이클 (docs/33 11.8·14장, C9 수집 기준 — 임시 V9612, 상위 결정 2026-10-22)
-- - 수집 COUNT 원장(cms_equip_count)은 설비·GOOD/SCRAP 만 있고 품목·작업지시가 없다 → 실적의 품목별 표준 사이클을 쓸 수 없다
-- - 수집 기준 성능·OEE 의 이론 속도 = 설비 하나의 개당 이론 초(ISO 22400 의 설비 고정값). 비우면 수집 기준 성능·OEE 빈칸
-- - 한계: 한 설비에서 품목이 섞이면 근사(품목별 표준과 다를 수 있다) — docs/33 에 명시. 확정 실적 기준 KPI 는 그대로 품목별 표준
-- =====================================================================

ALTER TABLE cms_equip_profile ADD COLUMN ideal_ct_sec numeric(10,3);
ALTER TABLE cms_equip_profile ADD CONSTRAINT ck_cms_equip_profile_ideal_ct CHECK (ideal_ct_sec IS NULL OR ideal_ct_sec > 0);
COMMENT ON COLUMN cms_equip_profile.ideal_ct_sec IS '개당 이론 사이클(초) — C9 수집 기준 성능·OEE. 비우면 그 설비의 수집 기준 성능·OEE 빈칸';
