-- =====================================================================
-- 외부 메일 첨부 합계 상한 정책 (docs/36 18.6 A3, 상위 결정 Q2 2026-10-24). 표 변경 없음
-- - MAIL_ATTACH_MAX_MB: 메일 한 통 첨부 합계 최대 MB(기본 10). SMTP 서버 한도(보통 10~25MB)보다 키우지 않는다
-- 임시 번호(docs/29 2.2) — 병합 때 이름만 바뀐다.
-- =====================================================================

INSERT INTO sys_policy_def (policy_key, value_type, default_value, allowed_values, min_value, max_value, desc_key) VALUES
    ('MAIL_ATTACH_MAX_MB', 'INT', '10', NULL, 1, 25, 'policy.MAIL_ATTACH_MAX_MB');
