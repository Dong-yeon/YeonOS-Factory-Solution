-- =====================================================================
-- PMS 선후행 종류 FS·SS·FF (docs/48 ⑥, 사용자 결정 P-15 2026-11-02, docs/32 4.27) — 임시 번호(docs/29 2.2), 병합 때 이름만 바뀐다
--
-- - pms_task_dep·pms_template_dep 에 dep_type: FS(선행 종료 → 후행 시작, 지금 행 전부) / SS(시작 → 시작) / FF(종료 → 종료). SF 는 없다(P-15)
-- - 일정 식·밀기·실적 제약은 서비스(WbsSchedule·TaskService). 순환 검사·잎끼리·추가/삭제만 규칙은 V040·V222 트리거 그대로(종류와 무관)
-- 표 소유 = PMS. 다른 모듈 표 변경 없음
-- =====================================================================

ALTER TABLE pms_task_dep ADD COLUMN dep_type varchar(2) NOT NULL DEFAULT 'FS';
ALTER TABLE pms_task_dep ADD CONSTRAINT ck_pms_task_dep_type CHECK (dep_type IN ('FS', 'SS', 'FF'));
COMMENT ON COLUMN pms_task_dep.dep_type IS '선후행 종류 — FS: 후행 시작 ≥ 선행 종료 + 1 + lag / SS: 후행 시작 ≥ 선행 시작 + lag / FF: 후행 종료 ≥ 선행 종료 + lag (docs/32 4.27)';
COMMENT ON TABLE pms_task_dep IS '작업 의존(FS·SS·FF + 지연일). 위반은 막지 않고 경고, 후행 밀기는 사람이 확정 (docs/32 D4·4.27)';

ALTER TABLE pms_template_dep ADD COLUMN dep_type varchar(2) NOT NULL DEFAULT 'FS';
ALTER TABLE pms_template_dep ADD CONSTRAINT ck_pms_template_dep_type CHECK (dep_type IN ('FS', 'SS', 'FF'));
COMMENT ON COLUMN pms_template_dep.dep_type IS '선후행 종류 FS·SS·FF — 템플릿 → 프로젝트 복사 때 그대로 (docs/32 4.27)';
COMMENT ON TABLE pms_template_dep IS '템플릿 작업 의존(FS·SS·FF + lag, docs/32 4.18·4.27). 템플릿 → 프로젝트 복사 때 pms_task_dep 로. 추가·삭제만';
