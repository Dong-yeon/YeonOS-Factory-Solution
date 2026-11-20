-- =====================================================================
-- CMS C25 — POP 고장 신고 사진 한도 정책 (docs/33 11.17, 사용자 결정 Q2 2026-10-24). 표 변경 없음
-- - CMS_POP_PHOTO_MB: 사진 한 장 최대 MB(공용 FILE_MAX_MB 와 작은 쪽)
-- - CMS_POP_PHOTO_MAX: 작업지시 하나에 POP 단말이 붙이는 사진 수
-- 임시 번호(docs/29 2.2) — 병합 때 이름만 바뀐다.
-- =====================================================================

INSERT INTO sys_policy_def (policy_key, value_type, default_value, allowed_values, min_value, max_value, desc_key) VALUES
    ('CMS_POP_PHOTO_MB',  'INT', '5', NULL, 1, 20, 'policy.CMS_POP_PHOTO_MB'),
    ('CMS_POP_PHOTO_MAX', 'INT', '3', NULL, 1, 10, 'policy.CMS_POP_PHOTO_MAX');
