-- =====================================================================
-- CMS 보전 작업지시 CHECK 의 NULL 구멍 막기 (상위 세션 점검 2026-10-21, MES 발견)
--
-- `A OR length(btrim(x)) > 0` 은 x 가 NULL 이면 NULL → CHECK 가 통과로 본다. 그래서
--   ck_cms_mwo_symptom_any : 고장 신고(BM)인데 현상 코드·현상 글 둘 다 NULL 이 들어갈 수 있었다
--   ck_cms_mwo_done        : BM·CM 완료(DONE·CLOSED)인데 작업 내용 NULL 이 들어갈 수 있었다
-- 화면·API 는 서비스가 막고 있었다(직접 SQL·다른 경로만 뚫림). 조건 자체는 그대로, NULL 만 막는다.
-- 순서: DROP → ADD … NOT VALID(새 행·수정 행부터 적용) → 위반 행이 0 이면 VALIDATE(기존 행까지 보장).
-- 위반 행이 있으면 NOT VALID 로 두고 알림만 — 그 행을 고친 뒤 VALIDATE CONSTRAINT 를 손으로(docs/33 12장).
-- 임시 번호(docs/29 2.2) — 병합 때 이름만 바뀐다.
-- =====================================================================

ALTER TABLE cms_mwo DROP CONSTRAINT ck_cms_mwo_symptom_any;
ALTER TABLE cms_mwo ADD CONSTRAINT ck_cms_mwo_symptom_any CHECK (
    mwo_type <> 'BM' OR symptom_id IS NOT NULL OR (symptom_text IS NOT NULL AND length(btrim(symptom_text)) > 0)) NOT VALID;

ALTER TABLE cms_mwo DROP CONSTRAINT ck_cms_mwo_done;
ALTER TABLE cms_mwo ADD CONSTRAINT ck_cms_mwo_done CHECK (status NOT IN ('DONE', 'CLOSED') OR (
    finished_at IS NOT NULL
    AND (down_yn = 'N' OR down_end_at IS NOT NULL)
    AND (mwo_type = 'PM' OR (work_text IS NOT NULL AND length(btrim(work_text)) > 0))
    AND (mwo_type <> 'BM' OR (symptom_id IS NOT NULL AND cause_id IS NOT NULL AND action_id IS NOT NULL)))) NOT VALID;

-- VALIDATE 는 행 보안과 무관하게 표 전체를 본다. 위반 행이 있으면(check_violation) 그 검증만 되돌리고 NOT VALID 로 둔다
DO $$
BEGIN
    BEGIN
        ALTER TABLE cms_mwo VALIDATE CONSTRAINT ck_cms_mwo_symptom_any;
    EXCEPTION WHEN check_violation THEN
        RAISE NOTICE 'ck_cms_mwo_symptom_any left NOT VALID: existing rows violate it (fix them, then VALIDATE CONSTRAINT)';
    END;
    BEGIN
        ALTER TABLE cms_mwo VALIDATE CONSTRAINT ck_cms_mwo_done;
    EXCEPTION WHEN check_violation THEN
        RAISE NOTICE 'ck_cms_mwo_done left NOT VALID: existing rows violate it (fix them, then VALIDATE CONSTRAINT)';
    END;
END
$$;
