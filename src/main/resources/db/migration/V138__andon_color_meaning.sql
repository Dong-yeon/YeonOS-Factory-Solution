-- =====================================================================
-- UI 디자인 단계 1 (docs/40 V-6, 부록 A) — 안돈 파랑·흰색의 뜻은 회사(공장) 정책값, 기본 = 관례.
-- 임시 번호(병합 때 재번호, docs/29 2.2)
-- 초록 가동 / 노랑 주의 / 빨강 정지·고장은 거의 공통이라 고정, 파랑·흰색은 현장마다 달라 정책으로 둔다(IEC 60073 을 자주 인용, 강제 표준 없음)
-- 색 이름(토큰 --hb-andon-blue·--hb-andon-white)은 고정, 뜻만 고른다 — 안돈 화면(P2 후반)이 이 값을 읽어 글자를 붙인다
-- =====================================================================
INSERT INTO sys_policy_def (policy_key, value_type, default_value, allowed_values, min_value, max_value, plant_scope_yn, desc_key) VALUES
    ('ANDON_BLUE_MEANING',  'ENUM', 'CALL',  ARRAY['CALL','MATERIAL','MAINT','QUALITY'], NULL, NULL, 'Y', 'policy.ANDON_BLUE_MEANING'),
    ('ANDON_WHITE_MEANING', 'ENUM', 'READY', ARRAY['READY','INFO','CHANGEOVER'],          NULL, NULL, 'Y', 'policy.ANDON_WHITE_MEANING');
