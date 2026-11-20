-- QMS: 문서 유형 DOC_TYPE 을 회사가 늘릴 수 있게 (docs/68 5장 묶음 6) — V9707(병합 때 재번호)
-- 분기는 시스템 코드만: INSP_STD(검사계획 연결)·DRAWING(PLM 도면 파일 잠금)·ECO 변경 대상 5종(DRAWING·WI·CP·INSP_STD·PFMEA).
-- 회사 코드(예: 설비 매뉴얼 MANUAL, 고객 사양 SPEC)는 OTHER 처럼 위 연결 없이 문서·리비전·결재만 쓴다.
-- 칸 너비 10 그대로(PLM 트리거 함수 변수 v_doc_type varchar(10) 도 그대로) → 회사 코드 최대 10자. 기존 행은 시스템 코드라 그대로 통과.
UPDATE sys_code_grp SET comp_ext_yn = 'Y', code_max_len = 10 WHERE grp_cd = 'DOC_TYPE';

ALTER TABLE mst_doc DROP CONSTRAINT ck_mst_doc_type;
ALTER TABLE mst_doc ADD CONSTRAINT ck_mst_doc_type CHECK (doc_type ~ '^[A-Z0-9][A-Z0-9_-]*$');
CREATE TRIGGER tg_mst_doc_code_type BEFORE INSERT OR UPDATE OF doc_type ON mst_doc
    FOR EACH ROW EXECUTE FUNCTION hb_check_code_col('DOC_TYPE', 'doc_type');
COMMENT ON COLUMN mst_doc.doc_type IS '문서 유형 — 공통코드 DOC_TYPE(시스템 7종 + 회사 코드). 연결 분기는 시스템 코드만';
