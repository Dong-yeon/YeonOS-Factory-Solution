-- =====================================================================
-- CMS C26 — 깊은 offset·판독 합 (docs/33 13.13, QA docs/qa/2026-10-24_offset-deep-page.md H4·M1·M6). 표 변경 없음
-- - 계수기 누계(판독 합)·판독 수를 화면마다 계수기별로 다시 센다(카운터·예방점검 계획·점검 실행 grid)
-- - 무효 아닌 판독만 담은 부분 색인 + delta_qty 포함 → 합·수가 색인만 읽기(index-only)로 끝난다
--   (누계 칸 + 트리거는 정정·무효·동시 입력마다 맞춰야 할 것이 늘어 고르지 않았다 — 13.13 결정)
-- 임시 번호(docs/29 2.2) — 병합 때 이름만 바뀐다.
-- =====================================================================

CREATE INDEX ix_cms_counter_reading_sum ON cms_counter_reading (comp_cd, counter_id) INCLUDE (delta_qty) WHERE void_yn = 'N';
