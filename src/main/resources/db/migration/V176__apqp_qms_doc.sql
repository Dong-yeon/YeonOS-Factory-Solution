-- =====================================================================
-- P6 APQP A3-3 — 확정 PFMEA·관리계획서 → QMS 관리문서 (docs/41 11.8, B1~B7)
-- - apqp_qms_doc: APQP 리비전 하나가 올린 QMS 문서 리비전(DocPort.publish 결과). 넣기만 한다(고치기·지우기 없음)
-- - 같은 번호(fmea_no·cp_no)의 다음 리비전은 같은 QMS 문서의 새 리비전으로(B3) — 그래서 src_no 를 같이 둔다
-- - QMS 쪽 id(doc_id·doc_rev_id)는 FK 없음(모듈 간 id — 이력과 같은 방식). 원본 APQP 리비전은 같은 회사·확정본만(트리거)
-- 11.8 B4 는 apqp_fmea·apqp_cp 칸을 적었으나, 확정본 행은 트리거가 칸 변경을 막으므로(가드 함수를 다시 쓰지 않으려고) 따로 표로 둔다
-- 임시 번호(docs/29 2.2) — 병합 때 이름만 바뀐다.
-- =====================================================================

CREATE TABLE apqp_qms_doc (
    link_id     bigint        GENERATED ALWAYS AS IDENTITY,
    comp_cd     varchar(20)   NOT NULL,
    src_kind    varchar(4)    NOT NULL,
    src_id      bigint        NOT NULL,
    src_no      varchar(30)   NOT NULL,
    src_rev     integer       NOT NULL,
    doc_id      bigint        NOT NULL,
    doc_no      varchar(30)   NOT NULL,
    doc_rev_id  bigint        NOT NULL,
    row_ver     integer       NOT NULL DEFAULT 0,
    use_yn      char(1)       NOT NULL DEFAULT 'Y',
    created_at  timestamptz   NOT NULL DEFAULT now(),
    created_by  varchar(50)   NOT NULL,
    updated_at  timestamptz   NOT NULL DEFAULT now(),
    updated_by  varchar(50)   NOT NULL,
    CONSTRAINT pk_apqp_qms_doc PRIMARY KEY (link_id),
    CONSTRAINT uq_apqp_qms_doc_src UNIQUE (comp_cd, src_kind, src_id),
    CONSTRAINT ck_apqp_qms_doc_kind CHECK (src_kind IN ('FMEA', 'CP')),
    CONSTRAINT ck_apqp_qms_doc_use CHECK (use_yn IN ('Y', 'N'))
);
CREATE INDEX ix_apqp_qms_doc_no ON apqp_qms_doc (comp_cd, src_kind, src_no);
COMMENT ON TABLE apqp_qms_doc IS 'APQP 리비전 → QMS 관리문서 리비전 (A3-3, docs/41 11.8) — 넣기만, QMS id 는 FK 없음';
COMMENT ON COLUMN apqp_qms_doc.src_kind IS 'FMEA = apqp_fmea, CP = apqp_cp';
COMMENT ON COLUMN apqp_qms_doc.src_no IS '원본 번호(fmea_no·cp_no) — 다음 리비전이 같은 QMS 문서를 찾는 키';
COMMENT ON COLUMN apqp_qms_doc.doc_rev_id IS 'QMS mst_doc_rev — 상태는 DocLookup.rev 로 읽는다';

CREATE FUNCTION hb_guard_apqp_qms_doc() RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    v_ok boolean;
BEGIN
    IF TG_OP <> 'INSERT' THEN
        RAISE EXCEPTION 'apqp_qms_doc rows are fixed' USING ERRCODE = '55000', CONSTRAINT = 'ck_apqp_qms_doc_fixed';
    END IF;
    -- 원본 = 같은 회사·같은 번호·리비전의 확정본(B7)
    v_ok := CASE NEW.src_kind
        WHEN 'FMEA' THEN EXISTS (SELECT 1 FROM apqp_fmea x WHERE x.fmea_id = NEW.src_id AND x.comp_cd = NEW.comp_cd
                                    AND x.fmea_no = NEW.src_no AND x.rev = NEW.src_rev AND x.status = 'RELEASED')
        WHEN 'CP'   THEN EXISTS (SELECT 1 FROM apqp_cp x WHERE x.cp_id = NEW.src_id AND x.comp_cd = NEW.comp_cd
                                    AND x.cp_no = NEW.src_no AND x.rev = NEW.src_rev AND x.status = 'RELEASED')
        ELSE false END;
    IF NOT v_ok THEN
        RAISE EXCEPTION '% % is not a released revision of company %', NEW.src_kind, NEW.src_id, NEW.comp_cd
            USING ERRCODE = '23503', CONSTRAINT = 'fk_apqp_qms_doc_src';
    END IF;
    RETURN NEW;
END
$$;
CREATE TRIGGER tg_apqp_qms_doc_guard BEFORE INSERT OR UPDATE OR DELETE ON apqp_qms_doc
    FOR EACH ROW EXECUTE FUNCTION hb_guard_apqp_qms_doc();
CREATE TRIGGER tg_audit AFTER INSERT OR UPDATE OR DELETE ON apqp_qms_doc FOR EACH ROW EXECUTE FUNCTION hb_audit('link_id', '', '');

ALTER TABLE apqp_qms_doc ENABLE ROW LEVEL SECURITY;
ALTER TABLE apqp_qms_doc FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON apqp_qms_doc USING (comp_cd = hb_current_comp()) WITH CHECK (comp_cd = hb_current_comp());
REVOKE TRUNCATE ON apqp_qms_doc FROM ${app_role};

INSERT INTO sys_retention_table (table_nm, class_cd) VALUES ('apqp_qms_doc', 'QUALITY_RECORD');
