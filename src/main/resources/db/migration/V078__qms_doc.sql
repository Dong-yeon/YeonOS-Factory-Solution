-- =====================================================================
-- P3 QMS Q3 관리문서 · 정합 점검 (docs/29 2장 Q3, 결정 D7, PLM D8) — 임시 번호, 병합 때 재번호
--
-- - 문서 mst_doc(유형 WI·INSP_STD·CP·PFMEA·DRAWING·OTHER, 유형은 만든 뒤 고정) + 적용 대상 mst_doc_target(품목·품목 클래스·공정)
--   + 리비전 mst_doc_rev(DRAFT → IN_APPROVAL → RELEASED → OBSOLETE). 배포본은 문서마다 하나, 작성 중(결재 중 포함)도 하나
-- - 배포는 결재(PLM·SRM 과 같은 ApprovalDocType) — 정책 QMS_DOC_APPROVAL = N 이면 APPROVE 권한으로 바로. 반려·회수 = DRAFT 로
-- - 다른 모듈(PLM 도면 = DRAWING, 검사계획 = INSP_STD)은 doc_rev_id 로 리비전을 가리킨다. PLM ECO 적용은 공개 서비스 메서드로 배포
-- - 첨부(대상 qms-doc × doc_rev_id)는 작성 중 리비전에만 올리고 지운다(FileLockRule), 배포 때 파일에 잠금 표시(sys_file.locked_yn)
-- - 검사계획 머리 doc_rev_id(Q1 에 자리만 있던 칸)에 FK + 같은 회사 · 검사기준서 · 폐기본 아님(새 참조) 검사
-- =====================================================================

INSERT INTO sys_code_grp (grp_cd, grp_nm_key, comp_ext_yn) VALUES
    ('DOC_TYPE',        'code.grp.DOC_TYPE',        'N'),
    ('DOC_REV_STATUS',  'code.grp.DOC_REV_STATUS',  'N'),
    ('DOC_TARGET_TYPE', 'code.grp.DOC_TARGET_TYPE', 'N'),
    ('QMS_CHECK',       'code.grp.QMS_CHECK',       'N');
INSERT INTO sys_code (grp_cd, code, code_nm_key, sort_no) VALUES
    ('DOC_TYPE', 'WI',       'code.DOC_TYPE.WI',       1),
    ('DOC_TYPE', 'INSP_STD', 'code.DOC_TYPE.INSP_STD', 2),
    ('DOC_TYPE', 'CP',       'code.DOC_TYPE.CP',       3),
    ('DOC_TYPE', 'PFMEA',    'code.DOC_TYPE.PFMEA',    4),
    ('DOC_TYPE', 'DRAWING',  'code.DOC_TYPE.DRAWING',  5),
    ('DOC_TYPE', 'OTHER',    'code.DOC_TYPE.OTHER',    6),
    ('DOC_REV_STATUS', 'DRAFT',       'code.DOC_REV_STATUS.DRAFT',       1),
    ('DOC_REV_STATUS', 'IN_APPROVAL', 'code.DOC_REV_STATUS.IN_APPROVAL', 2),
    ('DOC_REV_STATUS', 'RELEASED',    'code.DOC_REV_STATUS.RELEASED',    3),
    ('DOC_REV_STATUS', 'OBSOLETE',    'code.DOC_REV_STATUS.OBSOLETE',    4),
    ('DOC_TARGET_TYPE', 'ITEM',       'code.DOC_TARGET_TYPE.ITEM',       1),
    ('DOC_TARGET_TYPE', 'ITEM_CLASS', 'code.DOC_TARGET_TYPE.ITEM_CLASS', 2),
    ('DOC_TARGET_TYPE', 'PROC',       'code.DOC_TARGET_TYPE.PROC',       3),
    ('QMS_CHECK', 'SPECIAL_NOT_IN_PLAN', 'code.QMS_CHECK.SPECIAL_NOT_IN_PLAN', 1),
    ('QMS_CHECK', 'PLAN_NO_INSP_STD',    'code.QMS_CHECK.PLAN_NO_INSP_STD',    2),
    ('QMS_CHECK', 'PLAN_DOC_OBSOLETE',   'code.QMS_CHECK.PLAN_DOC_OBSOLETE',   3);

INSERT INTO sys_policy_def (policy_key, value_type, default_value, allowed_values, min_value, max_value, plant_scope_yn, desc_key) VALUES
    ('QMS_DOC_APPROVAL', 'BOOL', 'Y', NULL, NULL, NULL, 'N', 'policy.QMS_DOC_APPROVAL');

-- ---------------------------------------------------------------------
-- 1. 문서
-- ---------------------------------------------------------------------
CREATE TABLE mst_doc (
    doc_id      bigint        GENERATED ALWAYS AS IDENTITY,
    comp_cd     varchar(20)   NOT NULL,
    doc_no      varchar(30)   NOT NULL,
    doc_type    varchar(10)   NOT NULL,
    doc_nm      varchar(200)  NOT NULL,
    use_yn      char(1)       NOT NULL DEFAULT 'Y',
    remark      varchar(500),
    row_ver     integer       NOT NULL DEFAULT 0,
    created_at  timestamptz   NOT NULL DEFAULT now(),
    created_by  varchar(50)   NOT NULL,
    updated_at  timestamptz   NOT NULL DEFAULT now(),
    updated_by  varchar(50)   NOT NULL,
    CONSTRAINT pk_mst_doc PRIMARY KEY (doc_id),
    CONSTRAINT fk_mst_doc_comp FOREIGN KEY (comp_cd) REFERENCES sys_comp (comp_cd),
    CONSTRAINT uq_mst_doc_no UNIQUE (comp_cd, doc_no),
    CONSTRAINT ck_mst_doc_no CHECK (doc_no ~ '^[A-Z0-9][A-Z0-9._-]{0,29}$'),
    CONSTRAINT ck_mst_doc_type CHECK (doc_type IN ('WI', 'INSP_STD', 'CP', 'PFMEA', 'DRAWING', 'OTHER')),
    CONSTRAINT ck_mst_doc_nm CHECK (length(btrim(doc_nm)) > 0),
    CONSTRAINT ck_mst_doc_use_yn CHECK (use_yn IN ('Y', 'N'))
);
CREATE INDEX ix_mst_doc_type ON mst_doc (comp_cd, doc_type);
COMMENT ON TABLE mst_doc IS '관리문서 (작업표준서·검사기준서·관리계획서·PFMEA·도면·기타). 리비전은 mst_doc_rev, 다른 모듈은 doc_rev_id 로 가리킨다 (docs/29 Q3, PLM D8)';

-- 문서 번호·유형은 고정(다른 모듈이 유형으로 거른다), 삭제 대신 사용 중지
CREATE FUNCTION hb_guard_mst_doc() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'document cannot be deleted (use use_yn = N)' USING ERRCODE = '55000', CONSTRAINT = 'ck_mst_doc_no_delete';
    END IF;
    IF NEW.comp_cd <> OLD.comp_cd OR NEW.doc_no <> OLD.doc_no OR NEW.doc_type <> OLD.doc_type THEN
        RAISE EXCEPTION 'document % number/type cannot be changed', OLD.doc_id USING ERRCODE = '55000', CONSTRAINT = 'ck_mst_doc_key_immutable';
    END IF;
    RETURN NEW;
END
$$;
CREATE TRIGGER tg_mst_doc_guard BEFORE UPDATE OR DELETE ON mst_doc FOR EACH ROW EXECUTE FUNCTION hb_guard_mst_doc();

-- ---------------------------------------------------------------------
-- 2. 적용 대상 (품목 · 품목 클래스 · 공정 중 하나)
-- ---------------------------------------------------------------------
CREATE TABLE mst_doc_target (
    target_id      bigint        GENERATED ALWAYS AS IDENTITY,
    comp_cd        varchar(20)   NOT NULL,
    doc_id         bigint        NOT NULL,
    target_type    varchar(10)   NOT NULL,
    item_id        bigint,
    item_class_id  bigint,
    proc_id        bigint,
    created_at     timestamptz   NOT NULL DEFAULT now(),
    created_by     varchar(50)   NOT NULL,
    CONSTRAINT pk_mst_doc_target PRIMARY KEY (target_id),
    CONSTRAINT fk_mst_doc_target_comp FOREIGN KEY (comp_cd) REFERENCES sys_comp (comp_cd),
    CONSTRAINT fk_mst_doc_target_doc FOREIGN KEY (doc_id) REFERENCES mst_doc (doc_id),
    CONSTRAINT fk_mst_doc_target_item FOREIGN KEY (item_id) REFERENCES mst_item (item_id),
    CONSTRAINT fk_mst_doc_target_class FOREIGN KEY (item_class_id) REFERENCES mst_item_class (class_id),
    CONSTRAINT fk_mst_doc_target_proc FOREIGN KEY (proc_id) REFERENCES mst_proc (proc_id),
    CONSTRAINT ck_mst_doc_target_one CHECK (
        (target_type = 'ITEM' AND item_id IS NOT NULL AND item_class_id IS NULL AND proc_id IS NULL)
        OR (target_type = 'ITEM_CLASS' AND item_id IS NULL AND item_class_id IS NOT NULL AND proc_id IS NULL)
        OR (target_type = 'PROC' AND item_id IS NULL AND item_class_id IS NULL AND proc_id IS NOT NULL))
);
CREATE UNIQUE INDEX uq_mst_doc_target ON mst_doc_target (doc_id, target_type, coalesce(item_id, 0), coalesce(item_class_id, 0), coalesce(proc_id, 0));
CREATE INDEX ix_mst_doc_target_item ON mst_doc_target (comp_cd, item_id) WHERE item_id IS NOT NULL;
CREATE INDEX ix_mst_doc_target_class ON mst_doc_target (comp_cd, item_class_id) WHERE item_class_id IS NOT NULL;
CREATE INDEX ix_mst_doc_target_proc ON mst_doc_target (comp_cd, proc_id) WHERE proc_id IS NOT NULL;
COMMENT ON TABLE mst_doc_target IS '관리문서 적용 대상 — 한 벌로 바꾼다(지우고 넣기). 정합 점검이 검사계획 대상과 맞춰 본다';

CREATE FUNCTION hb_guard_mst_doc_target() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RETURN OLD;
    END IF;
    IF TG_OP = 'UPDATE' THEN
        RAISE EXCEPTION 'document target cannot be updated (delete and insert)' USING ERRCODE = '55000', CONSTRAINT = 'ck_mst_doc_target_no_update';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM mst_doc d WHERE d.doc_id = NEW.doc_id AND d.comp_cd = NEW.comp_cd) THEN
        RAISE EXCEPTION 'document % does not belong to company %', NEW.doc_id, NEW.comp_cd
            USING ERRCODE = '23503', CONSTRAINT = 'fk_mst_doc_target_doc_comp';
    END IF;
    -- 같은 회사 대상만. 사용 중지된 대상도 남길 수 있다(한 벌 저장이 그대로 둔 대상을 다시 넣는다) — 새 대상 검사는 서비스가
    IF (NEW.item_id IS NOT NULL AND NOT hb_item_of_comp(NEW.comp_cd, NEW.item_id))
       OR (NEW.item_class_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM mst_item_class c WHERE c.class_id = NEW.item_class_id AND c.comp_cd = NEW.comp_cd))
       OR (NEW.proc_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM mst_proc p WHERE p.proc_id = NEW.proc_id AND p.comp_cd = NEW.comp_cd)) THEN
        RAISE EXCEPTION 'document target does not belong to company %', NEW.comp_cd
            USING ERRCODE = '23503', CONSTRAINT = 'fk_mst_doc_target_comp_ref';
    END IF;
    RETURN NEW;
END
$$;
CREATE TRIGGER tg_mst_doc_target_guard BEFORE INSERT OR UPDATE OR DELETE ON mst_doc_target
    FOR EACH ROW EXECUTE FUNCTION hb_guard_mst_doc_target();

-- ---------------------------------------------------------------------
-- 3. 리비전
-- ---------------------------------------------------------------------
CREATE TABLE mst_doc_rev (
    doc_rev_id      bigint         GENERATED ALWAYS AS IDENTITY,
    comp_cd         varchar(20)    NOT NULL,
    doc_id          bigint         NOT NULL,
    rev             varchar(4)     NOT NULL,
    status          varchar(12)    NOT NULL DEFAULT 'DRAFT',
    change_summary  varchar(1000),
    source_rev_id   bigint,
    apr_id          bigint,
    submitted_at    timestamptz,
    submitted_by    varchar(50),
    released_at     timestamptz,
    released_by     varchar(50),
    obsoleted_at    timestamptz,
    obsoleted_by    varchar(50),
    row_ver         integer        NOT NULL DEFAULT 0,
    created_at      timestamptz    NOT NULL DEFAULT now(),
    created_by      varchar(50)    NOT NULL,
    updated_at      timestamptz    NOT NULL DEFAULT now(),
    updated_by      varchar(50)    NOT NULL,
    CONSTRAINT pk_mst_doc_rev PRIMARY KEY (doc_rev_id),
    CONSTRAINT fk_mst_doc_rev_comp FOREIGN KEY (comp_cd) REFERENCES sys_comp (comp_cd),
    CONSTRAINT fk_mst_doc_rev_doc FOREIGN KEY (doc_id) REFERENCES mst_doc (doc_id),
    CONSTRAINT fk_mst_doc_rev_source FOREIGN KEY (source_rev_id) REFERENCES mst_doc_rev (doc_rev_id),
    CONSTRAINT uq_mst_doc_rev UNIQUE (doc_id, rev),
    CONSTRAINT ck_mst_doc_rev_rev CHECK (rev ~ '^[A-Z0-9]{1,4}$'),
    CONSTRAINT ck_mst_doc_rev_status CHECK (status IN ('DRAFT', 'IN_APPROVAL', 'RELEASED', 'OBSOLETE')),
    -- 배포·폐기된 적이 있으면 그 시각이 있어야 한다 (CHECK 는 NULL 을 통과로 보므로 IS NOT NULL 로)
    CONSTRAINT ck_mst_doc_rev_released CHECK (status <> 'RELEASED' OR (released_at IS NOT NULL AND released_by IS NOT NULL)),
    CONSTRAINT ck_mst_doc_rev_approval CHECK (status <> 'IN_APPROVAL' OR (submitted_at IS NOT NULL AND apr_id IS NOT NULL))
);
-- 배포본은 문서마다 하나, 작성 중(결재 중 포함)도 하나
CREATE UNIQUE INDEX uq_mst_doc_rev_released ON mst_doc_rev (doc_id) WHERE status = 'RELEASED';
CREATE UNIQUE INDEX uq_mst_doc_rev_open ON mst_doc_rev (doc_id) WHERE status IN ('DRAFT', 'IN_APPROVAL');
CREATE INDEX ix_mst_doc_rev_doc ON mst_doc_rev (comp_cd, doc_id, doc_rev_id);
COMMENT ON TABLE mst_doc_rev IS '관리문서 리비전. 작성 중만 내용·첨부 변경, 배포는 결재(또는 정책 N 이면 APPROVE), 새 배포가 이전 배포본을 폐기 (docs/29 Q3)';
COMMENT ON COLUMN mst_doc_rev.source_rev_id IS '새 리비전을 복사해 온 리비전 — 첨부도 복사(FileService.copyAll)';

CREATE FUNCTION hb_guard_mst_doc_rev() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'document revision cannot be deleted (obsolete it)' USING ERRCODE = '55000', CONSTRAINT = 'ck_mst_doc_rev_no_delete';
    END IF;
    IF TG_OP = 'INSERT' THEN
        IF NOT EXISTS (SELECT 1 FROM mst_doc d WHERE d.doc_id = NEW.doc_id AND d.comp_cd = NEW.comp_cd) THEN
            RAISE EXCEPTION 'document % does not belong to company %', NEW.doc_id, NEW.comp_cd
                USING ERRCODE = '23503', CONSTRAINT = 'fk_mst_doc_rev_doc_comp';
        END IF;
        IF NEW.source_rev_id IS NOT NULL AND NOT EXISTS (
               SELECT 1 FROM mst_doc_rev r WHERE r.doc_rev_id = NEW.source_rev_id AND r.doc_id = NEW.doc_id) THEN
            RAISE EXCEPTION 'source revision % is not a revision of document %', NEW.source_rev_id, NEW.doc_id
                USING ERRCODE = '23503', CONSTRAINT = 'fk_mst_doc_rev_source_doc';
        END IF;
        IF NEW.status <> 'DRAFT' THEN
            RAISE EXCEPTION 'new document revision must be DRAFT' USING ERRCODE = '55000', CONSTRAINT = 'ck_mst_doc_rev_draft';
        END IF;
        RETURN NEW;
    END IF;
    IF NEW.comp_cd <> OLD.comp_cd OR NEW.doc_id <> OLD.doc_id OR NEW.source_rev_id IS DISTINCT FROM OLD.source_rev_id THEN
        RAISE EXCEPTION 'document revision % cannot move', OLD.doc_rev_id USING ERRCODE = '55000', CONSTRAINT = 'ck_mst_doc_rev_immutable';
    END IF;
    IF OLD.status <> NEW.status AND NOT (
           (OLD.status = 'DRAFT' AND NEW.status IN ('IN_APPROVAL', 'RELEASED', 'OBSOLETE'))
        OR (OLD.status = 'IN_APPROVAL' AND NEW.status IN ('DRAFT', 'RELEASED'))
        OR (OLD.status = 'RELEASED' AND NEW.status = 'OBSOLETE')) THEN
        RAISE EXCEPTION 'document revision status % -> % is not allowed', OLD.status, NEW.status
            USING ERRCODE = '55000', CONSTRAINT = 'ck_mst_doc_rev_transition';
    END IF;
    -- 내용은 작성 중에만
    IF OLD.status <> 'DRAFT' AND (NEW.rev <> OLD.rev OR NEW.change_summary IS DISTINCT FROM OLD.change_summary) THEN
        RAISE EXCEPTION '% document revision % cannot be changed', OLD.status, OLD.doc_rev_id
            USING ERRCODE = '55000', CONSTRAINT = 'ck_mst_doc_rev_locked';
    END IF;
    -- 배포·폐기 기록은 한 번 남으면 그대로
    IF (OLD.released_at IS NOT NULL AND NEW.released_at IS DISTINCT FROM OLD.released_at)
       OR (OLD.obsoleted_at IS NOT NULL AND NEW.obsoleted_at IS DISTINCT FROM OLD.obsoleted_at) THEN
        RAISE EXCEPTION 'release history of % cannot be changed', OLD.doc_rev_id
            USING ERRCODE = '55000', CONSTRAINT = 'ck_mst_doc_rev_locked';
    END IF;
    RETURN NEW;
END
$$;
CREATE TRIGGER tg_mst_doc_rev_guard BEFORE INSERT OR UPDATE OR DELETE ON mst_doc_rev
    FOR EACH ROW EXECUTE FUNCTION hb_guard_mst_doc_rev();

-- ---------------------------------------------------------------------
-- 4. 첨부 잠금 — 공통 규칙 (공통 변경: sys_file 칸 + 트리거 하나, 상위 세션 결정 2026-10-21)
--    앱: FileLockRule 빈(모듈마다 "이 대상은 지금 잠겼나")을 FileService 가 올리기·삭제 전에 묻는다 — 상태 전이(결재 중 등)까지
--    DB: 모듈이 근거로 굳힐 파일에 locked_yn = 'Y' 를 찍으면(한 방향) 이 트리거가 사용 중지·내용 변경·잠금 해제를 막는다.
--        대상별 판단 함수를 트리거에서 부르지 않는다 — 모듈 표를 모르는 범용 규칙이라 PMS·PLM 도 같은 칸을 쓴다
-- ---------------------------------------------------------------------
ALTER TABLE sys_file ADD COLUMN locked_yn char(1) NOT NULL DEFAULT 'N';
ALTER TABLE sys_file ADD CONSTRAINT ck_sys_file_locked_yn CHECK (locked_yn IN ('Y', 'N'));
COMMENT ON COLUMN sys_file.locked_yn IS '근거 파일 잠금(한 방향) — Y 면 사용 중지·내용 변경·해제 불가. 모듈이 상태 전이(배포 등) 때 찍는다';

CREATE FUNCTION hb_guard_sys_file_locked() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'DELETE' OR NEW.locked_yn <> 'Y'
       OR (NEW.comp_cd, NEW.target_type, NEW.target_id, NEW.orig_name, NEW.ext, NEW.content_type, NEW.size_bytes, NEW.sha256,
           NEW.storage_key, NEW.use_yn)
          IS DISTINCT FROM
          (OLD.comp_cd, OLD.target_type, OLD.target_id, OLD.orig_name, OLD.ext, OLD.content_type, OLD.size_bytes, OLD.sha256,
           OLD.storage_key, OLD.use_yn) THEN
        RAISE EXCEPTION 'file % is locked as evidence', OLD.file_id USING ERRCODE = '55000', CONSTRAINT = 'ck_sys_file_locked';
    END IF;
    RETURN NEW;
END
$$;
CREATE TRIGGER tg_sys_file_locked BEFORE UPDATE OR DELETE ON sys_file
    FOR EACH ROW WHEN (OLD.locked_yn = 'Y') EXECUTE FUNCTION hb_guard_sys_file_locked();

-- ---------------------------------------------------------------------
-- 5. 검사계획 → 검사기준서 리비전 (Q1 에서 자리만 둔 칸)
-- ---------------------------------------------------------------------
ALTER TABLE qms_insp_plan ADD CONSTRAINT fk_qms_insp_plan_doc_rev FOREIGN KEY (doc_rev_id) REFERENCES mst_doc_rev (doc_rev_id);
CREATE INDEX ix_qms_insp_plan_doc_rev ON qms_insp_plan (doc_rev_id) WHERE doc_rev_id IS NOT NULL;
COMMENT ON COLUMN qms_insp_plan.doc_rev_id IS '검사기준서 리비전(mst_doc_rev, 유형 INSP_STD) — 같은 회사, 새로 걸 때 폐기본 아님. 확정 뒤 고정';

-- 이름이 tg_qms_insp_plan_guard 뒤라 상태·불변 검사가 먼저 돈다
CREATE FUNCTION hb_check_qms_insp_plan_doc() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.doc_rev_id IS NULL OR (TG_OP = 'UPDATE' AND NEW.doc_rev_id IS NOT DISTINCT FROM OLD.doc_rev_id) THEN
        RETURN NEW;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM mst_doc_rev r JOIN mst_doc d ON d.doc_id = r.doc_id
                    WHERE r.doc_rev_id = NEW.doc_rev_id AND r.comp_cd = NEW.comp_cd) THEN
        RAISE EXCEPTION 'document revision % does not belong to company %', NEW.doc_rev_id, NEW.comp_cd
            USING ERRCODE = '23503', CONSTRAINT = 'fk_qms_insp_plan_doc_rev_comp';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM mst_doc_rev r JOIN mst_doc d ON d.doc_id = r.doc_id
                    WHERE r.doc_rev_id = NEW.doc_rev_id AND d.doc_type = 'INSP_STD' AND r.status <> 'OBSOLETE') THEN
        RAISE EXCEPTION 'document revision % is not an active inspection standard', NEW.doc_rev_id
            USING ERRCODE = '23514', CONSTRAINT = 'ck_qms_insp_plan_doc_rev';
    END IF;
    RETURN NEW;
END
$$;
CREATE TRIGGER tg_qms_insp_plan_zdoc BEFORE INSERT OR UPDATE ON qms_insp_plan
    FOR EACH ROW EXECUTE FUNCTION hb_check_qms_insp_plan_doc();

-- ---------------------------------------------------------------------
-- 6. 감사 · RLS · 권한 · 화면 · 보존
-- ---------------------------------------------------------------------
CREATE TRIGGER tg_audit AFTER INSERT OR UPDATE OR DELETE ON mst_doc FOR EACH ROW EXECUTE FUNCTION hb_audit('doc_id', '', '');
CREATE TRIGGER tg_audit AFTER INSERT OR UPDATE OR DELETE ON mst_doc_target FOR EACH ROW EXECUTE FUNCTION hb_audit('target_id', '', '');
CREATE TRIGGER tg_audit AFTER INSERT OR UPDATE OR DELETE ON mst_doc_rev FOR EACH ROW EXECUTE FUNCTION hb_audit('doc_rev_id', '', '');

ALTER TABLE mst_doc ENABLE ROW LEVEL SECURITY;
ALTER TABLE mst_doc FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON mst_doc USING (comp_cd = hb_current_comp()) WITH CHECK (comp_cd = hb_current_comp());
ALTER TABLE mst_doc_target ENABLE ROW LEVEL SECURITY;
ALTER TABLE mst_doc_target FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON mst_doc_target USING (comp_cd = hb_current_comp()) WITH CHECK (comp_cd = hb_current_comp());
ALTER TABLE mst_doc_rev ENABLE ROW LEVEL SECURITY;
ALTER TABLE mst_doc_rev FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON mst_doc_rev USING (comp_cd = hb_current_comp()) WITH CHECK (comp_cd = hb_current_comp());

REVOKE DELETE, TRUNCATE ON mst_doc, mst_doc_rev FROM ${app_role};
REVOKE TRUNCATE ON mst_doc_target FROM ${app_role};

INSERT INTO sys_prog (prog_cd, prog_nm_key, view_path, module_cd, actions, sort_no) VALUES
    ('qms-doc',         'prog.qmsDoc',         'qms/doc-mgt',         'QMS', ARRAY['SEARCH','SAVE','APPROVE','DELETE']::varchar(20)[], 640),
    ('qms-consistency', 'prog.qmsConsistency', 'qms/consistency',     'QMS', ARRAY['SEARCH','EXCEL']::varchar(20)[], 650);
-- 관리문서는 품질 문서(IATF 7.5.3.2.1), 대상은 문서와 함께
INSERT INTO sys_retention_table (table_nm, class_cd) VALUES
    ('mst_doc', 'QUALITY_RECORD'), ('mst_doc_target', 'QUALITY_RECORD'), ('mst_doc_rev', 'QUALITY_RECORD');
