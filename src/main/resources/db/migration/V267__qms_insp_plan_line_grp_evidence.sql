-- =====================================================================
-- QMS: 검사계획 행 분류(grp_nm)·증빙 필수(evidence_req) (docs/72 3.2) — 임시 번호 V9920, 병합 때 재번호
-- 근거: 공개 자료/표준 — 관리계획서·검사 기준서의 일반 구성(검사 항목을 외관·치수·기능으로 묶고, 외관 항목에 사진 기록을 요구)
--
-- - grp_nm: 화면·성적서에서 행을 묶는 분류(선택)
-- - evidence_req: NONE / PHOTO. "확인" 항목 자체는 기존 ATTRIBUTE + VISUAL 로 이미 표현된다 — 이 칸은 사진 증빙을 요구하는지만 적는다
--   검사 행(qms_insp_line)은 계획 행을 참조하고 확정 계획 행은 바뀌지 않으므로 검사 행에 복사하지 않는다
-- - 작성 중 계획만 바뀐다: 기존 행 트리거 hb_guard_qms_insp_plan_line 이 확정·폐기 계획 행의 모든 UPDATE 를 막는다(새 칸 포함)
-- =====================================================================

ALTER TABLE qms_insp_plan_line
    ADD COLUMN grp_nm       varchar(60),
    ADD COLUMN evidence_req varchar(10) NOT NULL DEFAULT 'NONE';
ALTER TABLE qms_insp_plan_line ADD CONSTRAINT ck_qms_insp_plan_line_evidence CHECK (evidence_req IN ('NONE', 'PHOTO'));
ALTER TABLE qms_insp_plan_line ADD CONSTRAINT ck_qms_insp_plan_line_grp
    CHECK (grp_nm IS NULL OR length(btrim(grp_nm)) > 0);
COMMENT ON COLUMN qms_insp_plan_line.grp_nm IS '화면·성적서 묶음 분류(선택, 예: 외관·치수·기능) — docs/72 3.2';
COMMENT ON COLUMN qms_insp_plan_line.evidence_req IS 'NONE / PHOTO(사진 증빙 필요 — 계획에 적어 두는 값. 판정 때 사진 유무 검사는 그 검사를 쓰는 화면 몫, docs/72 3.2)';

-- 시스템 고정 그룹(분기에 쓰이는 값 — 회사 확장 없음)
INSERT INTO sys_code_grp (grp_cd, grp_nm_key, comp_ext_yn) VALUES ('INSP_EVIDENCE', 'code.grp.INSP_EVIDENCE', 'N');
INSERT INTO sys_code (grp_cd, code, code_nm_key, sort_no) VALUES
    ('INSP_EVIDENCE', 'NONE',  'code.INSP_EVIDENCE.NONE',  1),
    ('INSP_EVIDENCE', 'PHOTO', 'code.INSP_EVIDENCE.PHOTO', 2);
