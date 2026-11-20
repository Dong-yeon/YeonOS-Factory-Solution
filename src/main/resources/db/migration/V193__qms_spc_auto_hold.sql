-- =====================================================================
-- QMS SV-6 SPC 위반 → LOT 자동 보류 정책 (docs/29_P3 23장, 사용자 결정 2026-10-26 Q-u·Q-y·Q-z 추천 그대로) — V193(임시 V9226)
--
-- - 정책 QMS_SPC_AUTO_HOLD: OFF(기본) · LIMIT(한계 밖 규칙 WE1·R1 만) · ALL(고정 한계에 켜 둔 규칙 전부)
-- - 보류·해제는 MES 계약 LotHoldPort(출처 QMS_SPC, 참조 = SPC 이벤트 id — V191)로만. 표 변경 없음
-- =====================================================================

INSERT INTO sys_policy_def (policy_key, value_type, default_value, allowed_values, min_value, max_value, plant_scope_yn, desc_key) VALUES
    ('QMS_SPC_AUTO_HOLD', 'ENUM', 'OFF', ARRAY['OFF','LIMIT','ALL'], NULL, NULL, 'N', 'policy.QMS_SPC_AUTO_HOLD');
