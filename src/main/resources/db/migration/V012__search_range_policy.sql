-- 조회 기간 상한 (docs/16 I4) — 화면별 maxDays 와 이 회사 정책 중 작은 값이 실제 상한.
-- 서버 공통 검사기 DateRanges.check 가 쓴다(시작 > 종료·상한 초과는 400).
INSERT INTO sys_policy_def (policy_key, value_type, default_value, allowed_values, min_value, max_value, desc_key) VALUES
    ('SEARCH_MAX_RANGE_DAYS', 'INT', '366', NULL, 1, 3660, 'policy.SEARCH_MAX_RANGE_DAYS');
