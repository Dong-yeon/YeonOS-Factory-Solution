-- QMS 관리문서 유형에 DFMEA (docs/29_P3 30장 — APQP DFMEA 확정본을 QMS 문서로 배포하는 계약 요청, 사용자 결정 D1~D9 범위)
-- 유형은 만든 뒤 고정(V078 규칙 그대로), 적용 대상·결재 정책(QMS_DOC_APPROVAL)은 유형과 무관 — PFMEA 와 같다.
-- 설계 변경(PLM ECO) 대상 유형(hb_guard_plm_eco_target, V187)에는 넣지 않는다(요청 범위 밖 — PLM 결정 사항)

ALTER TABLE mst_doc DROP CONSTRAINT ck_mst_doc_type;
ALTER TABLE mst_doc ADD CONSTRAINT ck_mst_doc_type CHECK (doc_type IN ('WI', 'INSP_STD', 'CP', 'PFMEA', 'DFMEA', 'DRAWING', 'OTHER'));

-- 고르기 순서: … PFMEA · DFMEA · 도면 · 기타
UPDATE sys_code SET sort_no = sort_no + 1 WHERE grp_cd = 'DOC_TYPE' AND code IN ('DRAWING', 'OTHER');
INSERT INTO sys_code (grp_cd, code, code_nm_key, sort_no) VALUES ('DOC_TYPE', 'DFMEA', 'code.DOC_TYPE.DFMEA', 5);

COMMENT ON TABLE mst_doc IS '관리문서 (작업표준서·검사기준서·관리계획서·PFMEA·DFMEA·도면·기타). 리비전은 mst_doc_rev, 다른 모듈은 doc_rev_id 로 가리킨다 (docs/29 Q3, PLM D8)';
