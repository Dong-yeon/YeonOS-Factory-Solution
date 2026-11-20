-- =====================================================================
-- CMS 3차 F3·G2 — 작업지시 기준 MTBF·MTTR(수집 없는 설비)·평균 반응(MTTA) 용어 (docs/33 16.3·16.5, docs/64 F3 결정 Q5 ①). 표 변경 없음
-- - 계산은 KpiService + CmsFailMapper(작업지시 원장 읽기만). 상태 원장 MTBF·MTTR(V231 용어)과 이름을 나눈다
-- 임시 번호(docs/29 2.2) — 병합 때 이름만 바뀐다.
-- =====================================================================

INSERT INTO sys_term (term_cd, category, term_ko, term_en, def_ko, def_en, sort_no) VALUES
    ('MTBF_WO', 'KPI', '평균 고장 간격(작업지시)', 'MTBF (work order)',
     '수집 장치가 없는 설비만: (기간 달력 시간 − 정지 시간 합) ÷ 고장 수. 고장 = 기간 안에 정지가 시작된(정지 기록이 없으면 신고된) 끝난 고장 수리 작업지시. 가동 시간을 모르는 달력 기준이라 상태 원장 MTBF 와 섞지 않는다.',
     'Equipment without a collector only: (calendar time of the period - total downtime) / failures. A failure is a finished breakdown work order whose downtime started (or, without downtime, was reported) in the period. Calendar based because run time is unknown, so it is not mixed with the state-ledger MTBF.', 160),
    ('MTTR_WO', 'KPI', '평균 수리 시간(작업지시)', 'MTTR (work order)',
     '수집 장치가 없는 설비만: 작업지시에 기록한 정지 시간 합 ÷ 정지를 기록한 고장 수.',
     'Equipment without a collector only: total downtime recorded on work orders / failures with recorded downtime.', 170),
    ('MTTA', 'KPI', '평균 반응 시간', 'MTTA',
     '고장 신고에서 착수까지 걸린 시간의 평균(끝난 고장 수리 작업지시 중 착수 시각이 있는 것). 수리 시간 분해(대기·수리·정지·공수)의 대기에 해당한다(EN 13306 의 지연·수리 시간 구분, 요약).',
     'Average time from the breakdown report to the start of work (finished breakdown work orders with a start time). The waiting part of the repair time breakdown (waiting, repair, downtime, labour) — EN 13306 delay vs repair time, summarised.', 180);
