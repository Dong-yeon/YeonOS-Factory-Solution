-- =====================================================================
-- P6 APQP — PFMEA·관리계획서 목록 엑셀 (docs/41 11.6.1 M2 후속). 표 변경 없음
-- - apqp-fmea·apqp-cp 화면에 EXCEL 동작(목록 내려받기 — 양식 엑셀 PRINT 와 따로). 기존 권한자에게 자동 부여하지 않는다(관리자가 줌)
-- 임시 번호(docs/29 2.2) — 병합 때 이름만 바뀐다.
-- =====================================================================

UPDATE sys_prog SET actions = ARRAY['SEARCH','SAVE','APPROVE','PRINT','EXCEL']::varchar(20)[] WHERE prog_cd IN ('apqp-fmea', 'apqp-cp');
