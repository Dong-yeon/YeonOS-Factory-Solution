-- =====================================================================
-- CMS 설비·생산 관리자 대시보드 (docs/51 C2·C4, 11장 결정 Q1·Q2). 표 변경 없음 — 원장·KPI 계산(EquipKpi)은 읽기만
-- - API /api/cms/dashboard(권한 = cms-kpi SEARCH): KPI 5개(기간·앞 기간·일별)·비가동 Pareto(계획/비계획)·설비별 상태 시간·계획 대비 실적
--   화면(sys_prog cms-dashboard)은 차트 도구(docs/61 CH-1 비교 PoC) 결정 뒤 별도 PR — 여기서는 등록하지 않는다
-- - 정책: 대시보드 기본 생산 수 기준(KPI 화면 D35 의 기준 선택과 같은 값 — KPI 화면 기본값은 그대로 RESULT),
--         KPI 목표(0 = 목표 없음 — 타일에 목표·미달 표시를 하지 않는다)
-- - 용어 사전(sys_term, C4 KPI 정의 카드의 근거): OEE 정의를 계산(EquipKpi — 합한 분자 ÷ 합한 분모)과 맞추고 MTBF·MTTR 을 더한다
-- 임시 번호(docs/29 2.2) — 병합 때 이름만 바뀐다.
-- =====================================================================

INSERT INTO sys_policy_def (policy_key, value_type, default_value, allowed_values, min_value, max_value, plant_scope_yn, desc_key) VALUES
    ('CMS_DASH_BASIS',       'ENUM', 'RESULT', ARRAY['RESULT','COUNT'], NULL, NULL, 'Y', 'policy.CMS_DASH_BASIS'),
    ('CMS_TARGET_OEE_PCT',   'INT',  '0',      NULL,                    0,    100,  'Y', 'policy.CMS_TARGET_OEE_PCT'),
    ('CMS_TARGET_AVAIL_PCT', 'INT',  '0',      NULL,                    0,    100,  'Y', 'policy.CMS_TARGET_AVAIL_PCT'),
    ('CMS_TARGET_PERF_PCT',  'INT',  '0',      NULL,                    0,    150,  'Y', 'policy.CMS_TARGET_PERF_PCT'),
    ('CMS_TARGET_QUAL_PCT',  'INT',  '0',      NULL,                    0,    100,  'Y', 'policy.CMS_TARGET_QUAL_PCT'),
    ('CMS_TARGET_MTTR_MIN',  'INT',  '0',      NULL,                    0,    1440, 'Y', 'policy.CMS_TARGET_MTTR_MIN');

-- V024 의 OEE 정의는 "곱" 이었는데 계산(EquipKpi, docs/33 D20)은 합한 분자 ÷ 합한 분모다 — 정의 카드와 용어 사전이 같은 말을 하게 고친다
UPDATE sys_term
   SET def_ko = '완전 생산 시간(Σ 표준 시간 × 양품) ÷ 계획 시간. 한 품목·한 설비면 가용률 × 성능 × 품질과 같고, 여럿을 합칠 때는 비율을 곱하거나 평균내지 않고 분자·분모를 합한 뒤 나눈다(ISO 22400-2 OEE index).',
       def_en = 'Fully productive time (sum of standard time x good quantity) / planned time. For one item on one machine it equals availability x performance x quality; when combining, numerators and denominators are summed before dividing instead of multiplying or averaging ratios (ISO 22400-2 OEE index).'
 WHERE term_cd = 'OEE';
UPDATE sys_term
   SET def_ko = '가동 시간 ÷ 계획 시간. 계획 시간 = 수집된 시간 − 통신 끊김 − 비가동 계획(NST) − 계획 정지(SDT) (ISO 22400-2 Availability).',
       def_en = 'Run time / planned time. Planned time = collected time - connection loss - non-scheduled (NST) - scheduled down (SDT) (ISO 22400-2 Availability).'
 WHERE term_cd = 'AVAILABILITY';

INSERT INTO sys_term (term_cd, category, term_ko, term_en, def_ko, def_en, sort_no) VALUES
    ('MTBF', 'KPI', '평균 고장 간격', 'MTBF',
     '가동 시간 ÷ 고장 횟수. 고장 = 기간 안에 시작한 비계획 정지(UDT) 구간(사후 사유로 재분류한 것 포함). 고장 0 이면 값이 없다.',
     'Run time / number of failures. A failure is an unscheduled down (UDT) interval that started in the period, including reclassified ones. No value when there were no failures.', 140),
    ('MTTR', 'KPI', '평균 수리 시간', 'MTTR',
     '비계획 정지(UDT) 시간 ÷ 고장 횟수. 고장 0 이면 값이 없다(ISO 22400-2 MTTR).',
     'Unscheduled down (UDT) time / number of failures. No value when there were no failures (ISO 22400-2 MTTR).', 150);
