-- =====================================================================
-- P3 QMS 2부 — 전자서명 연결 (docs/29_P3 12.10). 임시 번호(병합 때 재번호, docs/29 2.2)
--
-- 서명 원장·의미 코드는 플랫폼(sys_esign, ESIGN_MEANING) 그대로 쓴다 — QMS 는 정책 하나만 더한다.
-- - 정책 QMS_ESIGN_REQUIRED(BOOL, 기본 N): Y 면 NCR 직접 종결·특채 상신, 교정 기록 등록에 서명 토큰 필수
-- =====================================================================

INSERT INTO sys_policy_def (policy_key, value_type, default_value, allowed_values, min_value, max_value, plant_scope_yn, desc_key) VALUES
    ('QMS_ESIGN_REQUIRED', 'BOOL', 'N', NULL, NULL, NULL, 'N', 'policy.QMS_ESIGN_REQUIRED');
