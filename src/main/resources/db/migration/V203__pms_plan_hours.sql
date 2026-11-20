-- =====================================================================
-- PMS 계획 공수·소진율 (docs/48 6장 1·7장 R-2, docs/32) — 임시 번호(docs/29 2.2), 병합 때 이름만 바뀐다
--
-- - 저장 단위 = 시간(pms_work_log.hours 와 같음). 화면은 회사 정책 PMS_HOURS_PER_DAY(기본 8)로 MD 전환
-- - pms_template_task.std_hours: 템플릿 작업 표준 공수 → 프로젝트를 템플릿으로 만들 때 계획 공수로 복사
-- - pms_task.plan_hours: 잎 작업은 입력, 요약 작업은 자식 합(취소 제외)을 서버가 계산해 저장(날짜·진척과 같은 방식, 수동 칸 아님)
-- - TASK 유형만(마일스톤·게이트는 기간 0 — 공수 없음). 실적 = 확정 공수(CONFIRMED)만, 미확정(DRAFT)은 따로 표시(조회 때 계산)
-- 표 소유 = PMS. 다른 모듈 표 변경 없음
-- =====================================================================

INSERT INTO sys_policy_def (policy_key, value_type, default_value, allowed_values, min_value, max_value, desc_key) VALUES
    ('PMS_HOURS_PER_DAY', 'INT', '8', NULL, 1, 24, 'policy.PMS_HOURS_PER_DAY');

ALTER TABLE pms_template_task ADD COLUMN std_hours numeric(7,1);
ALTER TABLE pms_template_task ADD CONSTRAINT ck_pms_template_task_hours
    CHECK (std_hours IS NULL OR (task_type = 'TASK' AND std_hours BETWEEN 0 AND 99999));
COMMENT ON COLUMN pms_template_task.std_hours IS '표준 공수(시간) — 프로젝트 생성 때 계획 공수로 복사. TASK 만';

ALTER TABLE pms_task ADD COLUMN plan_hours numeric(7,1);
ALTER TABLE pms_task ADD CONSTRAINT ck_pms_task_hours
    CHECK (plan_hours IS NULL OR (task_type = 'TASK' AND plan_hours BETWEEN 0 AND 99999));
COMMENT ON COLUMN pms_task.plan_hours IS '계획 공수(시간) — 잎 작업 입력, 요약 작업은 자식 합(취소 제외, 서버 계산). TASK 만';
