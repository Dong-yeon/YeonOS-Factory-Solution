-- =====================================================================
-- QMS 출하 전 검수 (docs/78) — 임시 번호(docs/29 2.2, 병합 때 이름만 바뀐다)
--
-- - qms_preship_tpl / qms_preship_tpl_line: 검수 양식(품목 또는 품목군 하나) · 항목(해당 없음 허용·사진 필수)
-- - qms_preship_insp: 출하 1건 검수 OPEN → DONE (+ CANCELLED), 출하당 살아 있는 검수 하나
-- - qms_preship_result: 품목 × 양식 항목 스냅숏 + 판정 OK/NG/NA (머리 OPEN 일 때만)
-- - qms_preship_punch: 미결 — NG 한 번에 하나, OPEN → CLOSED(조치·재확인), 조건부 출하 허용(사유), NCR 연결(선택)
-- - 정책 QMS_PRESHIP_REQUIRED(회사, 기본 Y): N 이면 출하 확정 때 검수를 보지 않는다
-- - 화면 qms-preship-tpl · qms-preship · qms-preship-punch, 채번 QMS_PRESHIP(PSI-yyMMdd-seq, 규칙이 없으면 첫 시작 때 생성)
-- - 남의 표 변경 없음(wms_ship·wms_ship_line·mst_item·mst_item_class·qms_ncr 은 FK·읽기만)
-- =====================================================================

INSERT INTO sys_policy_def (policy_key, value_type, default_value, allowed_values, min_value, max_value, plant_scope_yn, desc_key) VALUES
    ('QMS_PRESHIP_REQUIRED', 'BOOL', 'Y', NULL, NULL, NULL, 'N', 'policy.QMS_PRESHIP_REQUIRED');

INSERT INTO sys_code_grp (grp_cd, grp_nm_key, comp_ext_yn) VALUES
    ('QMS_PRESHIP_STATUS', 'code.grp.QMS_PRESHIP_STATUS', 'N'),
    ('QMS_PRESHIP_JUDGE',  'code.grp.QMS_PRESHIP_JUDGE',  'N'),
    ('QMS_PUNCH_STATUS',   'code.grp.QMS_PUNCH_STATUS',   'N');
INSERT INTO sys_code (grp_cd, code, code_nm_key, sort_no) VALUES
    ('QMS_PRESHIP_STATUS', 'OPEN',      'code.QMS_PRESHIP_STATUS.OPEN',      1),
    ('QMS_PRESHIP_STATUS', 'DONE',      'code.QMS_PRESHIP_STATUS.DONE',      2),
    ('QMS_PRESHIP_STATUS', 'CANCELLED', 'code.QMS_PRESHIP_STATUS.CANCELLED', 3),
    ('QMS_PRESHIP_JUDGE',  'OK',        'code.QMS_PRESHIP_JUDGE.OK',         1),
    ('QMS_PRESHIP_JUDGE',  'NG',        'code.QMS_PRESHIP_JUDGE.NG',         2),
    ('QMS_PRESHIP_JUDGE',  'NA',        'code.QMS_PRESHIP_JUDGE.NA',         3),
    ('QMS_PUNCH_STATUS',   'OPEN',      'code.QMS_PUNCH_STATUS.OPEN',        1),
    ('QMS_PUNCH_STATUS',   'CLOSED',    'code.QMS_PUNCH_STATUS.CLOSED',      2);

-- ---------------------------------------------------------------------
-- 1. 양식 머리 — 대상은 품목 또는 품목군 하나
-- ---------------------------------------------------------------------
CREATE TABLE qms_preship_tpl (
    tpl_id         bigint         GENERATED ALWAYS AS IDENTITY,
    comp_cd        varchar(20)    NOT NULL,
    tpl_cd         varchar(30)    NOT NULL,
    tpl_nm         varchar(100)   NOT NULL,
    item_id        bigint,
    item_class_id  bigint,
    remark         varchar(500),
    use_yn         char(1)        NOT NULL DEFAULT 'Y',
    row_ver        integer        NOT NULL DEFAULT 0,
    created_at     timestamptz    NOT NULL DEFAULT now(),
    created_by     varchar(50)    NOT NULL,
    updated_at     timestamptz    NOT NULL DEFAULT now(),
    updated_by     varchar(50)    NOT NULL,
    CONSTRAINT pk_qms_preship_tpl PRIMARY KEY (tpl_id),
    CONSTRAINT fk_qms_preship_tpl_item FOREIGN KEY (item_id) REFERENCES mst_item (item_id),
    CONSTRAINT fk_qms_preship_tpl_class FOREIGN KEY (item_class_id) REFERENCES mst_item_class (class_id),
    CONSTRAINT uq_qms_preship_tpl_cd UNIQUE (comp_cd, tpl_cd),
    CONSTRAINT ck_qms_preship_tpl_target CHECK ((item_id IS NULL) <> (item_class_id IS NULL)),
    CONSTRAINT ck_qms_preship_tpl_cd CHECK (length(btrim(tpl_cd)) > 0),
    CONSTRAINT ck_qms_preship_tpl_nm CHECK (length(btrim(tpl_nm)) > 0),
    CONSTRAINT ck_qms_preship_tpl_use CHECK (use_yn IN ('Y', 'N'))
);
COMMENT ON TABLE qms_preship_tpl IS '출하 전 검수 양식 (docs/78) — 대상 품목 또는 품목군 하나. 사용 중 양식은 대상당 하나, 지우지 않는다(사용 중지)';
COMMENT ON COLUMN qms_preship_tpl.item_id IS '대상 품목 — 품목군 양식보다 우선';
COMMENT ON COLUMN qms_preship_tpl.item_class_id IS '대상 품목군(mst_item_class)';
CREATE UNIQUE INDEX uq_qms_preship_tpl_item ON qms_preship_tpl (comp_cd, item_id) WHERE use_yn = 'Y' AND item_id IS NOT NULL;
CREATE UNIQUE INDEX uq_qms_preship_tpl_class ON qms_preship_tpl (comp_cd, item_class_id) WHERE use_yn = 'Y' AND item_class_id IS NOT NULL;

-- ---------------------------------------------------------------------
-- 2. 양식 항목
-- ---------------------------------------------------------------------
CREATE TABLE qms_preship_tpl_line (
    tpl_line_id    bigint         GENERATED ALWAYS AS IDENTITY,
    comp_cd        varchar(20)    NOT NULL,
    tpl_id         bigint         NOT NULL,
    seq            integer        NOT NULL,
    grp_nm         varchar(60),
    check_text     varchar(300)   NOT NULL,
    na_allowed_yn  char(1)        NOT NULL DEFAULT 'N',
    photo_req_yn   char(1)        NOT NULL DEFAULT 'N',
    use_yn         char(1)        NOT NULL DEFAULT 'Y',
    row_ver        integer        NOT NULL DEFAULT 0,
    created_at     timestamptz    NOT NULL DEFAULT now(),
    created_by     varchar(50)    NOT NULL,
    updated_at     timestamptz    NOT NULL DEFAULT now(),
    updated_by     varchar(50)    NOT NULL,
    CONSTRAINT pk_qms_preship_tpl_line PRIMARY KEY (tpl_line_id),
    CONSTRAINT fk_qms_preship_tpl_line_tpl FOREIGN KEY (tpl_id) REFERENCES qms_preship_tpl (tpl_id),
    CONSTRAINT ck_qms_preship_tpl_line_seq CHECK (seq BETWEEN 1 AND 9999),
    CONSTRAINT ck_qms_preship_tpl_line_text CHECK (length(btrim(check_text)) > 0),
    CONSTRAINT ck_qms_preship_tpl_line_na CHECK (na_allowed_yn IN ('Y', 'N')),
    CONSTRAINT ck_qms_preship_tpl_line_photo CHECK (photo_req_yn IN ('Y', 'N')),
    CONSTRAINT ck_qms_preship_tpl_line_use CHECK (use_yn IN ('Y', 'N'))
);
COMMENT ON TABLE qms_preship_tpl_line IS '출하 전 검수 양식 항목 — 검수 시작 때 판정 줄로 복사(스냅숏). 지우지 않는다(사용 중지)';
COMMENT ON COLUMN qms_preship_tpl_line.na_allowed_yn IS 'Y = 판정 "해당 없음"(NA) 허용';
COMMENT ON COLUMN qms_preship_tpl_line.photo_req_yn IS 'Y = 사진 증빙이 하나 이상 있어야 OK';
CREATE INDEX ix_qms_preship_tpl_line_tpl ON qms_preship_tpl_line (comp_cd, tpl_id, seq);

-- ---------------------------------------------------------------------
-- 3. 검수 머리 — 출하 1건
-- ---------------------------------------------------------------------
CREATE TABLE qms_preship_insp (
    insp_id        bigint         GENERATED ALWAYS AS IDENTITY,
    comp_cd        varchar(20)    NOT NULL,
    insp_no        varchar(40)    NOT NULL,
    ship_id        bigint         NOT NULL,
    status         varchar(10)    NOT NULL DEFAULT 'OPEN',
    inspector_nm   varchar(100),
    witness_nm     varchar(100),
    remark         varchar(500),
    done_at        timestamptz,
    done_by        varchar(50),
    cancel_reason  varchar(500),
    row_ver        integer        NOT NULL DEFAULT 0,
    created_at     timestamptz    NOT NULL DEFAULT now(),
    created_by     varchar(50)    NOT NULL,
    updated_at     timestamptz    NOT NULL DEFAULT now(),
    updated_by     varchar(50)    NOT NULL,
    CONSTRAINT pk_qms_preship_insp PRIMARY KEY (insp_id),
    CONSTRAINT fk_qms_preship_insp_ship FOREIGN KEY (ship_id) REFERENCES wms_ship (ship_id),
    CONSTRAINT uq_qms_preship_insp_no UNIQUE (comp_cd, insp_no),
    CONSTRAINT ck_qms_preship_insp_status CHECK (status IN ('OPEN', 'DONE', 'CANCELLED')),
    CONSTRAINT ck_qms_preship_insp_done CHECK ((status = 'DONE') = (done_at IS NOT NULL AND done_by IS NOT NULL)),
    CONSTRAINT ck_qms_preship_insp_inspector CHECK (status <> 'DONE' OR coalesce(length(btrim(inspector_nm)), 0) > 0),
    CONSTRAINT ck_qms_preship_insp_cancel CHECK ((status = 'CANCELLED') = (coalesce(length(btrim(cancel_reason)), 0) > 0))
);
COMMENT ON TABLE qms_preship_insp IS '출하 전 검수 (docs/78) — 출하 1건, OPEN → DONE(검수자 필수) / CANCELLED(사유). 출하당 살아 있는 검수 하나';
COMMENT ON COLUMN qms_preship_insp.inspector_nm IS '검수자 이름(완료 때 필수)';
COMMENT ON COLUMN qms_preship_insp.witness_nm IS '고객 입회자 이름(선택)';
CREATE UNIQUE INDEX uq_qms_preship_insp_ship ON qms_preship_insp (comp_cd, ship_id) WHERE status <> 'CANCELLED';
CREATE INDEX ix_qms_preship_insp_status ON qms_preship_insp (comp_cd, status);

-- ---------------------------------------------------------------------
-- 4. 판정 줄 — 품목 × 양식 항목 스냅숏
-- ---------------------------------------------------------------------
CREATE TABLE qms_preship_result (
    result_id      bigint         GENERATED ALWAYS AS IDENTITY,
    comp_cd        varchar(20)    NOT NULL,
    insp_id        bigint         NOT NULL,
    item_id        bigint         NOT NULL,
    tpl_id         bigint         NOT NULL,
    tpl_line_id    bigint         NOT NULL,
    seq            integer        NOT NULL,
    grp_nm         varchar(60),
    check_text     varchar(300)   NOT NULL,
    na_allowed_yn  char(1)        NOT NULL,
    photo_req_yn   char(1)        NOT NULL,
    judge          varchar(2),
    remark         varchar(500),
    judged_at      timestamptz,
    judged_by      varchar(50),
    row_ver        integer        NOT NULL DEFAULT 0,
    created_at     timestamptz    NOT NULL DEFAULT now(),
    created_by     varchar(50)    NOT NULL,
    updated_at     timestamptz    NOT NULL DEFAULT now(),
    updated_by     varchar(50)    NOT NULL,
    CONSTRAINT pk_qms_preship_result PRIMARY KEY (result_id),
    CONSTRAINT fk_qms_preship_result_insp FOREIGN KEY (insp_id) REFERENCES qms_preship_insp (insp_id),
    CONSTRAINT fk_qms_preship_result_item FOREIGN KEY (item_id) REFERENCES mst_item (item_id),
    CONSTRAINT fk_qms_preship_result_tpl FOREIGN KEY (tpl_id) REFERENCES qms_preship_tpl (tpl_id),
    CONSTRAINT fk_qms_preship_result_line FOREIGN KEY (tpl_line_id) REFERENCES qms_preship_tpl_line (tpl_line_id),
    CONSTRAINT uq_qms_preship_result UNIQUE (insp_id, item_id, tpl_line_id),
    CONSTRAINT ck_qms_preship_result_judge CHECK (judge IS NULL OR judge IN ('OK', 'NG', 'NA')),
    CONSTRAINT ck_qms_preship_result_judged CHECK ((judge IS NULL) = (judged_at IS NULL AND judged_by IS NULL)),
    CONSTRAINT ck_qms_preship_result_na CHECK (judge IS DISTINCT FROM 'NA' OR na_allowed_yn = 'Y'),
    CONSTRAINT ck_qms_preship_result_ng CHECK (judge IS DISTINCT FROM 'NG' OR coalesce(length(btrim(remark)), 0) > 0)
);
COMMENT ON TABLE qms_preship_result IS '출하 전 검수 판정 줄 — 시작 때 양식 항목 스냅숏(분류·내용·허용·사진 필수 고정), 판정은 머리 OPEN 일 때만';
COMMENT ON COLUMN qms_preship_result.judge IS 'OK / NG(비고 필수, 미결 생성) / NA(허용 항목만), NULL = 미판정';
CREATE INDEX ix_qms_preship_result_insp ON qms_preship_result (comp_cd, insp_id, item_id, seq);

-- ---------------------------------------------------------------------
-- 5. 미결 — NG 한 번에 하나
-- ---------------------------------------------------------------------
CREATE TABLE qms_preship_punch (
    punch_id       bigint         GENERATED ALWAYS AS IDENTITY,
    comp_cd        varchar(20)    NOT NULL,
    insp_id        bigint         NOT NULL,
    result_id      bigint         NOT NULL,
    status         varchar(10)    NOT NULL DEFAULT 'OPEN',
    issue_text     varchar(500)   NOT NULL,
    action_text    varchar(500),
    closed_at      timestamptz,
    closed_by      varchar(50),
    ship_allow_yn  char(1)        NOT NULL DEFAULT 'N',
    allow_reason   varchar(500),
    allowed_at     timestamptz,
    allowed_by     varchar(50),
    ncr_id         bigint,
    row_ver        integer        NOT NULL DEFAULT 0,
    created_at     timestamptz    NOT NULL DEFAULT now(),
    created_by     varchar(50)    NOT NULL,
    updated_at     timestamptz    NOT NULL DEFAULT now(),
    updated_by     varchar(50)    NOT NULL,
    CONSTRAINT pk_qms_preship_punch PRIMARY KEY (punch_id),
    CONSTRAINT fk_qms_preship_punch_insp FOREIGN KEY (insp_id) REFERENCES qms_preship_insp (insp_id),
    CONSTRAINT fk_qms_preship_punch_result FOREIGN KEY (result_id) REFERENCES qms_preship_result (result_id),
    CONSTRAINT fk_qms_preship_punch_ncr FOREIGN KEY (ncr_id) REFERENCES qms_ncr (ncr_id),
    CONSTRAINT ck_qms_preship_punch_status CHECK (status IN ('OPEN', 'CLOSED')),
    CONSTRAINT ck_qms_preship_punch_closed CHECK ((status = 'CLOSED') = (closed_at IS NOT NULL AND closed_by IS NOT NULL
                                                   AND coalesce(length(btrim(action_text)), 0) > 0)),
    CONSTRAINT ck_qms_preship_punch_allow CHECK ((ship_allow_yn = 'Y') = (allowed_at IS NOT NULL AND allowed_by IS NOT NULL
                                                  AND coalesce(length(btrim(allow_reason)), 0) > 0)),
    CONSTRAINT ck_qms_preship_punch_allow_yn CHECK (ship_allow_yn IN ('Y', 'N')),
    CONSTRAINT ck_qms_preship_punch_issue CHECK (length(btrim(issue_text)) > 0)
);
COMMENT ON TABLE qms_preship_punch IS '출하 전 검수 미결(punch list) — NG 한 번에 하나, 닫기 = 조치 + 재확인 OK. 닫힌 미결은 이력으로 남는다';
COMMENT ON COLUMN qms_preship_punch.issue_text IS 'NG 때 비고 사본(무엇이 잘못됐나)';
COMMENT ON COLUMN qms_preship_punch.ship_allow_yn IS 'Y = 승인자가 사유와 함께 조건부 출하 허용(미결은 열린 채)';
COMMENT ON COLUMN qms_preship_punch.ncr_id IS '이어 둔 부적합(선택)';
CREATE UNIQUE INDEX uq_qms_preship_punch_open ON qms_preship_punch (comp_cd, result_id) WHERE status = 'OPEN';
CREATE INDEX ix_qms_preship_punch_insp ON qms_preship_punch (comp_cd, insp_id, status);
CREATE INDEX ix_qms_preship_punch_status ON qms_preship_punch (comp_cd, status, created_at);

-- ---------------------------------------------------------------------
-- 6. 트리거 — 같은 회사 참조(RLS 는 FK 를 보지 않는다)·전이·고정 칸
-- ---------------------------------------------------------------------
CREATE FUNCTION hb_guard_qms_preship_tpl() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'preship templates are never deleted (use_yn = N)' USING ERRCODE = '55000', CONSTRAINT = 'ck_qms_preship_tpl_no_delete';
    END IF;
    IF TG_OP = 'UPDATE' AND (NEW.tpl_cd, NEW.comp_cd) IS DISTINCT FROM (OLD.tpl_cd, OLD.comp_cd) THEN
        RAISE EXCEPTION 'template code is fixed' USING ERRCODE = '55000', CONSTRAINT = 'ck_qms_preship_tpl_fixed';
    END IF;
    IF NEW.item_id IS NOT NULL AND (TG_OP = 'INSERT' OR NEW.item_id IS DISTINCT FROM OLD.item_id)
       AND NOT EXISTS (SELECT 1 FROM mst_item i WHERE i.item_id = NEW.item_id AND i.comp_cd = NEW.comp_cd) THEN
        RAISE EXCEPTION 'item % does not belong to company %', NEW.item_id, NEW.comp_cd
            USING ERRCODE = '23503', CONSTRAINT = 'fk_qms_preship_tpl_item_comp';
    END IF;
    IF NEW.item_class_id IS NOT NULL AND (TG_OP = 'INSERT' OR NEW.item_class_id IS DISTINCT FROM OLD.item_class_id)
       AND NOT EXISTS (SELECT 1 FROM mst_item_class c WHERE c.class_id = NEW.item_class_id AND c.comp_cd = NEW.comp_cd) THEN
        RAISE EXCEPTION 'item class % does not belong to company %', NEW.item_class_id, NEW.comp_cd
            USING ERRCODE = '23503', CONSTRAINT = 'fk_qms_preship_tpl_class_comp';
    END IF;
    RETURN NEW;
END
$$;
CREATE TRIGGER tg_qms_preship_tpl_guard BEFORE INSERT OR UPDATE OR DELETE ON qms_preship_tpl FOR EACH ROW EXECUTE FUNCTION hb_guard_qms_preship_tpl();

CREATE FUNCTION hb_guard_qms_preship_tpl_line() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'preship template lines are never deleted (use_yn = N)' USING ERRCODE = '55000', CONSTRAINT = 'ck_qms_preship_tpl_line_no_delete';
    END IF;
    IF TG_OP = 'UPDATE' AND (NEW.tpl_id, NEW.comp_cd) IS DISTINCT FROM (OLD.tpl_id, OLD.comp_cd) THEN
        RAISE EXCEPTION 'template line owner is fixed' USING ERRCODE = '55000', CONSTRAINT = 'ck_qms_preship_tpl_line_fixed';
    END IF;
    IF TG_OP = 'INSERT' AND NOT EXISTS (SELECT 1 FROM qms_preship_tpl t WHERE t.tpl_id = NEW.tpl_id AND t.comp_cd = NEW.comp_cd) THEN
        RAISE EXCEPTION 'template % does not belong to company %', NEW.tpl_id, NEW.comp_cd
            USING ERRCODE = '23503', CONSTRAINT = 'fk_qms_preship_tpl_line_tpl_comp';
    END IF;
    RETURN NEW;
END
$$;
CREATE TRIGGER tg_qms_preship_tpl_line_guard BEFORE INSERT OR UPDATE OR DELETE ON qms_preship_tpl_line FOR EACH ROW EXECUTE FUNCTION hb_guard_qms_preship_tpl_line();

-- 머리: 새 검수는 OPEN, 같은 회사 출하, 번호·출하 고정, 전이 OPEN → DONE/CANCELLED, DONE → OPEN(완료 취소), CANCELLED 끝
CREATE FUNCTION hb_guard_qms_preship_insp() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'preship inspections are never deleted' USING ERRCODE = '55000', CONSTRAINT = 'ck_qms_preship_insp_no_delete';
    END IF;
    IF TG_OP = 'INSERT' THEN
        IF NEW.status <> 'OPEN' THEN
            RAISE EXCEPTION 'new preship inspection must be OPEN' USING ERRCODE = '55000', CONSTRAINT = 'ck_qms_preship_insp_transition';
        END IF;
        IF NOT EXISTS (SELECT 1 FROM wms_ship s WHERE s.ship_id = NEW.ship_id AND s.comp_cd = NEW.comp_cd) THEN
            RAISE EXCEPTION 'shipment % does not belong to company %', NEW.ship_id, NEW.comp_cd
                USING ERRCODE = '23503', CONSTRAINT = 'fk_qms_preship_insp_ship_comp';
        END IF;
        RETURN NEW;
    END IF;
    IF (NEW.insp_no, NEW.ship_id, NEW.comp_cd) IS DISTINCT FROM (OLD.insp_no, OLD.ship_id, OLD.comp_cd) THEN
        RAISE EXCEPTION 'preship inspection key is fixed' USING ERRCODE = '55000', CONSTRAINT = 'ck_qms_preship_insp_fixed';
    END IF;
    IF NEW.status IS DISTINCT FROM OLD.status AND NOT (
           (OLD.status = 'OPEN' AND NEW.status IN ('DONE', 'CANCELLED'))
        OR (OLD.status = 'DONE' AND NEW.status = 'OPEN')) THEN
        RAISE EXCEPTION 'preship inspection % -> % not allowed', OLD.status, NEW.status
            USING ERRCODE = '55000', CONSTRAINT = 'ck_qms_preship_insp_transition';
    END IF;
    IF OLD.status = 'CANCELLED' THEN
        RAISE EXCEPTION 'cancelled preship inspection is fixed' USING ERRCODE = '55000', CONSTRAINT = 'ck_qms_preship_insp_transition';
    END IF;
    RETURN NEW;
END
$$;
CREATE TRIGGER tg_qms_preship_insp_guard BEFORE INSERT OR UPDATE OR DELETE ON qms_preship_insp FOR EACH ROW EXECUTE FUNCTION hb_guard_qms_preship_insp();

-- 판정 줄: 같은 회사 머리·양식 항목, 스냅숏 칸 고정, 판정은 머리 OPEN 일 때만(미결 닫기 재확인은 머리 DONE 에서도 OK 로 — 열린 미결이 있는 줄만)
CREATE FUNCTION hb_guard_qms_preship_result() RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    v_status varchar(10);
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'preship results are never deleted' USING ERRCODE = '55000', CONSTRAINT = 'ck_qms_preship_result_no_delete';
    END IF;
    SELECT h.status INTO v_status FROM qms_preship_insp h WHERE h.insp_id = NEW.insp_id AND h.comp_cd = NEW.comp_cd;
    IF v_status IS NULL THEN
        RAISE EXCEPTION 'preship inspection % does not belong to company %', NEW.insp_id, NEW.comp_cd
            USING ERRCODE = '23503', CONSTRAINT = 'fk_qms_preship_result_insp_comp';
    END IF;
    IF TG_OP = 'INSERT' THEN
        IF v_status <> 'OPEN' THEN
            RAISE EXCEPTION 'preship inspection is not OPEN' USING ERRCODE = '55000', CONSTRAINT = 'ck_qms_preship_result_open';
        END IF;
        IF NOT EXISTS (SELECT 1 FROM qms_preship_tpl_line l WHERE l.tpl_line_id = NEW.tpl_line_id AND l.tpl_id = NEW.tpl_id AND l.comp_cd = NEW.comp_cd)
           OR NOT EXISTS (SELECT 1 FROM mst_item i WHERE i.item_id = NEW.item_id AND i.comp_cd = NEW.comp_cd) THEN
            RAISE EXCEPTION 'template line or item does not belong to company %', NEW.comp_cd
                USING ERRCODE = '23503', CONSTRAINT = 'fk_qms_preship_result_line_comp';
        END IF;
        RETURN NEW;
    END IF;
    IF (NEW.insp_id, NEW.item_id, NEW.tpl_id, NEW.tpl_line_id, NEW.seq, NEW.grp_nm, NEW.check_text, NEW.na_allowed_yn, NEW.photo_req_yn, NEW.comp_cd)
       IS DISTINCT FROM (OLD.insp_id, OLD.item_id, OLD.tpl_id, OLD.tpl_line_id, OLD.seq, OLD.grp_nm, OLD.check_text, OLD.na_allowed_yn, OLD.photo_req_yn, OLD.comp_cd) THEN
        RAISE EXCEPTION 'preship result snapshot is fixed' USING ERRCODE = '55000', CONSTRAINT = 'ck_qms_preship_result_fixed';
    END IF;
    IF (NEW.judge, NEW.remark) IS DISTINCT FROM (OLD.judge, OLD.remark) AND v_status <> 'OPEN'
       AND NOT (v_status = 'DONE' AND NEW.judge = 'OK'
                AND EXISTS (SELECT 1 FROM qms_preship_punch p WHERE p.result_id = NEW.result_id AND p.status = 'OPEN')) THEN
        RAISE EXCEPTION 'preship inspection is not OPEN' USING ERRCODE = '55000', CONSTRAINT = 'ck_qms_preship_result_open';
    END IF;
    RETURN NEW;
END
$$;
CREATE TRIGGER tg_qms_preship_result_guard BEFORE INSERT OR UPDATE OR DELETE ON qms_preship_result FOR EACH ROW EXECUTE FUNCTION hb_guard_qms_preship_result();

-- 미결: 같은 회사 판정 줄·머리·NCR, 판정 줄·머리 고정, 닫힌 미결은 되살리지 않는다, 허용은 되돌리지 않는다
CREATE FUNCTION hb_guard_qms_preship_punch() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'preship punch items are never deleted' USING ERRCODE = '55000', CONSTRAINT = 'ck_qms_preship_punch_no_delete';
    END IF;
    IF TG_OP = 'INSERT' THEN
        IF NEW.status <> 'OPEN' THEN
            RAISE EXCEPTION 'new punch item must be OPEN' USING ERRCODE = '55000', CONSTRAINT = 'ck_qms_preship_punch_transition';
        END IF;
        IF NOT EXISTS (SELECT 1 FROM qms_preship_result r WHERE r.result_id = NEW.result_id AND r.insp_id = NEW.insp_id AND r.comp_cd = NEW.comp_cd) THEN
            RAISE EXCEPTION 'preship result % does not belong to company %', NEW.result_id, NEW.comp_cd
                USING ERRCODE = '23503', CONSTRAINT = 'fk_qms_preship_punch_result_comp';
        END IF;
    ELSE
        IF (NEW.insp_id, NEW.result_id, NEW.issue_text, NEW.comp_cd) IS DISTINCT FROM (OLD.insp_id, OLD.result_id, OLD.issue_text, OLD.comp_cd) THEN
            RAISE EXCEPTION 'punch item key is fixed' USING ERRCODE = '55000', CONSTRAINT = 'ck_qms_preship_punch_fixed';
        END IF;
        IF OLD.status = 'CLOSED' AND (NEW.status, NEW.action_text, NEW.closed_at, NEW.closed_by)
                IS DISTINCT FROM (OLD.status, OLD.action_text, OLD.closed_at, OLD.closed_by) THEN
            RAISE EXCEPTION 'closed punch item is fixed' USING ERRCODE = '55000', CONSTRAINT = 'ck_qms_preship_punch_transition';
        END IF;
        IF OLD.ship_allow_yn = 'Y' AND (NEW.ship_allow_yn, NEW.allow_reason, NEW.allowed_at, NEW.allowed_by)
                IS DISTINCT FROM (OLD.ship_allow_yn, OLD.allow_reason, OLD.allowed_at, OLD.allowed_by) THEN
            RAISE EXCEPTION 'ship allowance is fixed' USING ERRCODE = '55000', CONSTRAINT = 'ck_qms_preship_punch_allow_fixed';
        END IF;
    END IF;
    IF NEW.ncr_id IS NOT NULL AND (TG_OP = 'INSERT' OR NEW.ncr_id IS DISTINCT FROM OLD.ncr_id)
       AND NOT EXISTS (SELECT 1 FROM qms_ncr n WHERE n.ncr_id = NEW.ncr_id AND n.comp_cd = NEW.comp_cd) THEN
        RAISE EXCEPTION 'ncr % does not belong to company %', NEW.ncr_id, NEW.comp_cd
            USING ERRCODE = '23503', CONSTRAINT = 'fk_qms_preship_punch_ncr_comp';
    END IF;
    RETURN NEW;
END
$$;
CREATE TRIGGER tg_qms_preship_punch_guard BEFORE INSERT OR UPDATE OR DELETE ON qms_preship_punch FOR EACH ROW EXECUTE FUNCTION hb_guard_qms_preship_punch();

-- ---------------------------------------------------------------------
-- 7. 감사·RLS·권한·보존·화면
-- ---------------------------------------------------------------------
CREATE TRIGGER tg_audit AFTER INSERT OR UPDATE OR DELETE ON qms_preship_tpl      FOR EACH ROW EXECUTE FUNCTION hb_audit('tpl_id', '', '');
CREATE TRIGGER tg_audit AFTER INSERT OR UPDATE OR DELETE ON qms_preship_tpl_line FOR EACH ROW EXECUTE FUNCTION hb_audit('tpl_line_id', '', '');
CREATE TRIGGER tg_audit AFTER INSERT OR UPDATE OR DELETE ON qms_preship_insp     FOR EACH ROW EXECUTE FUNCTION hb_audit('insp_id', '', '');
CREATE TRIGGER tg_audit AFTER INSERT OR UPDATE OR DELETE ON qms_preship_result   FOR EACH ROW EXECUTE FUNCTION hb_audit('result_id', '', '');
CREATE TRIGGER tg_audit AFTER INSERT OR UPDATE OR DELETE ON qms_preship_punch    FOR EACH ROW EXECUTE FUNCTION hb_audit('punch_id', '', '');

ALTER TABLE qms_preship_tpl ENABLE ROW LEVEL SECURITY;
ALTER TABLE qms_preship_tpl FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON qms_preship_tpl USING (comp_cd = hb_current_comp()) WITH CHECK (comp_cd = hb_current_comp());
ALTER TABLE qms_preship_tpl_line ENABLE ROW LEVEL SECURITY;
ALTER TABLE qms_preship_tpl_line FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON qms_preship_tpl_line USING (comp_cd = hb_current_comp()) WITH CHECK (comp_cd = hb_current_comp());
ALTER TABLE qms_preship_insp ENABLE ROW LEVEL SECURITY;
ALTER TABLE qms_preship_insp FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON qms_preship_insp USING (comp_cd = hb_current_comp()) WITH CHECK (comp_cd = hb_current_comp());
ALTER TABLE qms_preship_result ENABLE ROW LEVEL SECURITY;
ALTER TABLE qms_preship_result FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON qms_preship_result USING (comp_cd = hb_current_comp()) WITH CHECK (comp_cd = hb_current_comp());
ALTER TABLE qms_preship_punch ENABLE ROW LEVEL SECURITY;
ALTER TABLE qms_preship_punch FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON qms_preship_punch USING (comp_cd = hb_current_comp()) WITH CHECK (comp_cd = hb_current_comp());

REVOKE DELETE, TRUNCATE ON qms_preship_tpl, qms_preship_tpl_line, qms_preship_insp, qms_preship_result, qms_preship_punch FROM ${app_role};

INSERT INTO sys_retention_table (table_nm, class_cd) VALUES
    ('qms_preship_tpl', 'MASTER'), ('qms_preship_tpl_line', 'MASTER'),
    ('qms_preship_insp', 'QUALITY_RECORD'), ('qms_preship_result', 'QUALITY_RECORD'), ('qms_preship_punch', 'QUALITY_RECORD');

INSERT INTO sys_prog (prog_cd, prog_nm_key, view_path, module_cd, actions, sort_no) VALUES
    ('qms-preship-tpl',   'prog.qmsPreshipTpl',   'qms/preship-tpl',   'QMS', ARRAY['SEARCH','SAVE','EXCEL']::varchar(20)[], 671),
    ('qms-preship',       'prog.qmsPreship',      'qms/preship-insp',  'QMS', ARRAY['SEARCH','SAVE','APPROVE','EXCEL']::varchar(20)[], 672),
    ('qms-preship-punch', 'prog.qmsPreshipPunch', 'qms/preship-punch', 'QMS', ARRAY['SEARCH','SAVE','APPROVE','EXCEL']::varchar(20)[], 673);
