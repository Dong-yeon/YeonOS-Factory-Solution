-- =====================================================================
-- P3 QMS 2부 Q9 — 착수 게이트 QMS 규칙 (docs/29_P3 12.9·12.11). 임시 번호(병합 때 재번호, docs/29 2.2)
--
-- MES StartGate 확장점의 QMS 구현 빈(QmsStartGate)이 읽는 정책 두 개 — 표는 없다(읽기만).
-- - QMS_GATE_FIRST_PENDING: 같은 품목에 다른 지시가 만든 첫 LOT 의 초품 판정이 없으면(Q9-1)
-- - QMS_GATE_GAUGE: 공정 검사계획이 요구하는 계측기 클래스에 쓸 수 있는 게이지가 없으면(Q9-2)
-- 값 OFF·WARN·BLOCK, 기본 WARN(사용자 Q-m)
-- =====================================================================

INSERT INTO sys_policy_def (policy_key, value_type, default_value, allowed_values, min_value, max_value, plant_scope_yn, desc_key) VALUES
    ('QMS_GATE_FIRST_PENDING', 'ENUM', 'WARN', ARRAY['OFF','WARN','BLOCK'], NULL, NULL, 'N', 'policy.QMS_GATE_FIRST_PENDING'),
    ('QMS_GATE_GAUGE',         'ENUM', 'WARN', ARRAY['OFF','WARN','BLOCK'], NULL, NULL, 'N', 'policy.QMS_GATE_GAUGE');
