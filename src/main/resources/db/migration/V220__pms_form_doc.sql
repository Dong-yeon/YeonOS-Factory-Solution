-- PMS 산출물 빈 서식 (docs/48 6장 ⑧, 사용자 결정 P-8 2026-11-01, docs/32 4.17)
-- - 서식 원본 = QMS 관리문서(D6). PMS 는 파일을 갖지 않고 문서 번호만 가리킨다 → 화면 [서식] 이 QMS 관리문서 화면을 연다
-- - 템플릿 산출물 칸(pms_template_item)·프로젝트 산출물(pms_deliverable)에 form_doc_no. 템플릿 → 프로젝트, 프로젝트 → 프로젝트 복사 때 따라간다
-- - 문서가 있는지는 서비스가 입력 때 QMS 계약(DocLookup.revsByDocNo)으로 본다 — 모듈 경계라 FK 를 두지 않는다(문서 번호는 바뀌지 않음, mst_doc 트리거)
-- 표 소유 = PMS. 다른 모듈 표 변경 없음

ALTER TABLE pms_template_item ADD COLUMN form_doc_no varchar(30);
ALTER TABLE pms_template_item ADD CONSTRAINT ck_pms_template_item_form CHECK (
    form_doc_no IS NULL OR (kind = 'DELIV' AND form_doc_no ~ '^[A-Z0-9][A-Z0-9._-]{0,29}$'));
COMMENT ON COLUMN pms_template_item.form_doc_no IS '빈 서식 = QMS 관리문서 번호(산출물 칸만, docs/32 4.17)';

ALTER TABLE pms_deliverable ADD COLUMN form_doc_no varchar(30);
ALTER TABLE pms_deliverable ADD CONSTRAINT ck_pms_deliverable_form CHECK (
    form_doc_no IS NULL OR form_doc_no ~ '^[A-Z0-9][A-Z0-9._-]{0,29}$');
COMMENT ON COLUMN pms_deliverable.form_doc_no IS '빈 서식 = QMS 관리문서 번호(docs/32 4.17) — 제출본 연결(ref_type QMS_DOC_REV)과 별개';
