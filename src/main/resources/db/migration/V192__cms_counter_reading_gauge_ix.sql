-- =====================================================================
-- CMS C29 — 카운터 grid 최근 게이지 (docs/33 13.16, QA M1 남은 비용 — 상위 "안 A"). 표 변경 없음
-- - 카운터 목록·grid 의 "마지막 계기 값" = 무효 아니고 계기 값이 있는 판독 중 최근 한 건.
--   기존 색인(ix_cms_counter_reading_counter)은 계기 값 없는 판독(증가량 직접·자동 입력)도 담아,
--   계기 값이 드문 계수기는 판독 전체를 훑었다(1M 판독 쪽당 약 25만 블록)
-- - 계기 값이 있는 무효 아닌 판독만 담은 부분 색인 → 계수기마다 역순 한 건
-- V192(임시 V9622)
-- =====================================================================

CREATE INDEX ix_cms_counter_reading_gauge ON cms_counter_reading (comp_cd, counter_id, read_at, reading_id)
    WHERE void_yn = 'N' AND gauge_value IS NOT NULL;
