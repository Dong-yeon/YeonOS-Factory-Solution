-- =====================================================================
-- PMS 6단계 — 병합 뒤 연결 (docs/32 PM6·4.8, D6·D10·D13~D16)
-- ① 산출물 ↔ QMS 문서 리비전: ref_type 에 QMS_DOC_REV 추가, 이력에 LINK 행(doc_rev_id)
-- ② 이슈 → QMS 시정조치: pms_issue.capa_id(한 번 정하면 고정)
-- ③ 지연 판정의 휴일은 앱(WorkCalendar 회사 층)에서 — 표 변경 없음
-- QMS 표(mst_doc_rev·qms_capa)는 같은 회사 확인으로 읽기만 한다(쓰기 없음). FK 는 RLS 를 무시하므로 회사는 트리거가 확인
-- =====================================================================

-- ---------------------------------------------------------------------
-- 1. 산출물 연결 칸
-- ---------------------------------------------------------------------
ALTER TABLE pms_deliverable DROP CONSTRAINT ck_pms_deliverable_ref_type;
ALTER TABLE pms_deliverable ADD CONSTRAINT ck_pms_deliverable_ref_type CHECK (ref_type IS NULL OR ref_type IN ('FILE', 'QMS_DOC_REV'));
COMMENT ON COLUMN pms_deliverable.ref_type IS '제출물 종류: FILE(첨부) / QMS_DOC_REV(QMS 문서 리비전 — 배포본이면 승인, D6)';

-- 연결 리비전 = 같은 회사(이름이 tg_pms_deliverable_guard 뒤라 상태·전이 검사가 먼저 돈다)
CREATE FUNCTION hb_check_pms_deliverable_doc() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.ref_type = 'QMS_DOC_REV' AND (TG_OP = 'INSERT' OR NEW.ref_id IS DISTINCT FROM OLD.ref_id
                                         OR NEW.ref_type IS DISTINCT FROM OLD.ref_type)
       AND NOT EXISTS (SELECT 1 FROM mst_doc_rev r WHERE r.doc_rev_id = NEW.ref_id AND r.comp_cd = NEW.comp_cd) THEN
        RAISE EXCEPTION 'document revision % is not of company %', NEW.ref_id, NEW.comp_cd
            USING ERRCODE = '23503', CONSTRAINT = 'fk_pms_deliverable_doc_rev';
    END IF;
    RETURN NEW;
END
$$;
CREATE TRIGGER tg_pms_deliverable_zdoc BEFORE INSERT OR UPDATE ON pms_deliverable
    FOR EACH ROW EXECUTE FUNCTION hb_check_pms_deliverable_doc();

ALTER TABLE pms_deliverable_hist ADD COLUMN doc_rev_id bigint;
COMMENT ON COLUMN pms_deliverable_hist.doc_rev_id IS 'LINK 행: 연결한 QMS 문서 리비전(mst_doc_rev)';
ALTER TABLE pms_deliverable_hist DROP CONSTRAINT ck_pms_deliverable_hist_action;
ALTER TABLE pms_deliverable_hist ADD CONSTRAINT ck_pms_deliverable_hist_action
    CHECK (action IN ('SUBMIT', 'LINK', 'APPROVE', 'REJECT', 'WAIVE', 'UNWAIVE'));
ALTER TABLE pms_deliverable_hist ADD CONSTRAINT ck_pms_deliverable_hist_doc CHECK ((action = 'LINK') = (doc_rev_id IS NOT NULL));
ALTER TABLE pms_deliverable_hist ADD CONSTRAINT fk_pms_deliverable_hist_doc_rev FOREIGN KEY (doc_rev_id) REFERENCES mst_doc_rev (doc_rev_id);

-- ---------------------------------------------------------------------
-- 2. 이슈 → 시정조치
-- ---------------------------------------------------------------------
ALTER TABLE pms_issue ADD COLUMN capa_id bigint;
ALTER TABLE pms_issue ADD CONSTRAINT fk_pms_issue_capa FOREIGN KEY (capa_id) REFERENCES qms_capa (capa_id);
CREATE UNIQUE INDEX uq_pms_issue_capa ON pms_issue (comp_cd, capa_id) WHERE capa_id IS NOT NULL;
COMMENT ON COLUMN pms_issue.capa_id IS '"시정조치로 올리기" 로 만든 QMS 시정조치(qms_capa) — 한 번 정하면 고정 (D10·D13)';

CREATE FUNCTION hb_check_pms_issue_capa() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'UPDATE' AND OLD.capa_id IS NOT NULL AND NEW.capa_id IS DISTINCT FROM OLD.capa_id THEN
        RAISE EXCEPTION 'issue % is already linked to a corrective action', OLD.issue_no
            USING ERRCODE = '55000', CONSTRAINT = 'ck_pms_issue_capa_fixed';
    END IF;
    IF NEW.capa_id IS NOT NULL AND (TG_OP = 'INSERT' OR NEW.capa_id IS DISTINCT FROM OLD.capa_id)
       AND NOT EXISTS (SELECT 1 FROM qms_capa c WHERE c.capa_id = NEW.capa_id AND c.comp_cd = NEW.comp_cd) THEN
        RAISE EXCEPTION 'corrective action % is not of company %', NEW.capa_id, NEW.comp_cd
            USING ERRCODE = '23503', CONSTRAINT = 'fk_pms_issue_capa_comp';
    END IF;
    RETURN NEW;
END
$$;
CREATE TRIGGER tg_pms_issue_zcapa BEFORE INSERT OR UPDATE ON pms_issue
    FOR EACH ROW EXECUTE FUNCTION hb_check_pms_issue_capa();
