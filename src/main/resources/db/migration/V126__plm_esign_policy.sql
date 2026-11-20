-- =====================================================================
-- PLM 전자서명 연결 (docs/39 19장) — 회사 정책 PLM_ESIGN_REQUIRED(기본 N).
-- Y 면 ECO 결재 승인(결재를 끝내는 승인 포함)·PPAP 고객 판정 기록에 전자서명(sys_esign, docs/36 16장) 필수.
-- 표 변경 없음 — 서명 원장은 플랫폼 sys_esign 을 그대로 쓴다.
-- 임시 번호(PLM 대역 V9900~V9949) — 상위 세션이 병합 때 바꾼다.
-- =====================================================================

INSERT INTO sys_policy_def (policy_key, value_type, default_value, allowed_values, min_value, max_value, plant_scope_yn, desc_key) VALUES
    ('PLM_ESIGN_REQUIRED', 'BOOL', 'N', NULL, NULL, NULL, 'N', 'policy.PLM_ESIGN_REQUIRED');
