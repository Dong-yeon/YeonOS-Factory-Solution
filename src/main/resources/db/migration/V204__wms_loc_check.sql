-- =====================================================================
-- WMS 정위치 점검·위치 정확도 (docs/55, docs/49 W-3 — 2026-10-29 사용자 결정) — 임시 번호(docs/29 2.2)
--
-- - wms_loc_check: 칸 × 공장 날짜 하나(UNIQUE). OPEN → DONE(되돌리지 않음). 완료 때 집계·일치 여부
-- - wms_loc_check_line: 스캔·판정 줄 — 판정 당시 장부 스냅숏(book). 쌓기만, 처리(review_*)는 null 에서 한 번만, 삭제 금지(보정 이력 보존)
-- - 이동 사유 그룹 WMS_MOVE_REASON(회사 확장 N) = LOC_CORRECT — 보정 이동 원장(src_type LOC_CHECK, src_id = 줄)
-- - 화면 wms-loc-check(사무 — 목록·보정·확인)
-- 실사(wms_count)와 섞지 않는다 — 수량은 실사 몫, 여기서는 위치만
-- =====================================================================

INSERT INTO sys_code_grp (grp_cd, grp_nm_key, comp_ext_yn) VALUES
    ('WMS_LOC_CHECK_STATUS', 'code.grp.WMS_LOC_CHECK_STATUS', 'N'),
    ('WMS_LOC_CHECK_RESULT', 'code.grp.WMS_LOC_CHECK_RESULT', 'N'),
    ('WMS_LOC_CHECK_REVIEW', 'code.grp.WMS_LOC_CHECK_REVIEW', 'N'),
    ('WMS_MOVE_REASON',      'code.grp.WMS_MOVE_REASON',      'N');
INSERT INTO sys_code (grp_cd, code, code_nm_key, sort_no) VALUES
    ('WMS_LOC_CHECK_STATUS', 'OPEN',        'code.WMS_LOC_CHECK_STATUS.OPEN',        1),
    ('WMS_LOC_CHECK_STATUS', 'DONE',        'code.WMS_LOC_CHECK_STATUS.DONE',        2),
    ('WMS_LOC_CHECK_RESULT', 'OK',          'code.WMS_LOC_CHECK_RESULT.OK',          1),
    ('WMS_LOC_CHECK_RESULT', 'MISPLACED',   'code.WMS_LOC_CHECK_RESULT.MISPLACED',   2),
    ('WMS_LOC_CHECK_RESULT', 'MISSING',     'code.WMS_LOC_CHECK_RESULT.MISSING',     3),
    ('WMS_LOC_CHECK_RESULT', 'UNKNOWN',     'code.WMS_LOC_CHECK_RESULT.UNKNOWN',     4),
    ('WMS_LOC_CHECK_REVIEW', 'CORRECTED',   'code.WMS_LOC_CHECK_REVIEW.CORRECTED',   1),
    ('WMS_LOC_CHECK_REVIEW', 'CONFIRMED',   'code.WMS_LOC_CHECK_REVIEW.CONFIRMED',   2),
    ('WMS_MOVE_REASON',      'LOC_CORRECT', 'code.WMS_MOVE_REASON.LOC_CORRECT',      1);

-- ---------------------------------------------------------------------
-- 1. 점검 머리
-- ---------------------------------------------------------------------
CREATE TABLE wms_loc_check (
    check_id        bigint         GENERATED ALWAYS AS IDENTITY,
    comp_cd         varchar(20)    NOT NULL,
    plant_cd        varchar(20)    NOT NULL,
    node_id         bigint         NOT NULL,
    check_date      date           NOT NULL,
    status          varchar(10)    NOT NULL DEFAULT 'OPEN',
    worker_id       bigint,
    ok_cnt          integer,
    misplaced_cnt   integer,
    missing_cnt     integer,
    unknown_cnt     integer,
    accurate_yn     char(1),
    done_at         timestamptz,
    done_by         varchar(50),
    row_ver         integer        NOT NULL DEFAULT 0,
    created_at      timestamptz    NOT NULL DEFAULT now(),
    created_by      varchar(50)    NOT NULL,
    updated_at      timestamptz    NOT NULL DEFAULT now(),
    updated_by      varchar(50)    NOT NULL,
    CONSTRAINT pk_wms_loc_check PRIMARY KEY (check_id),
    CONSTRAINT fk_wms_loc_check_plant FOREIGN KEY (comp_cd, plant_cd) REFERENCES sys_plant (comp_cd, plant_cd),
    CONSTRAINT fk_wms_loc_check_node FOREIGN KEY (node_id) REFERENCES mst_equip_hier (node_id),
    CONSTRAINT uq_wms_loc_check_day UNIQUE (comp_cd, node_id, check_date),
    CONSTRAINT ck_wms_loc_check_status CHECK (status IN ('OPEN', 'DONE')),
    CONSTRAINT ck_wms_loc_check_accurate CHECK (accurate_yn IN ('Y', 'N')),
    -- 완료 = 집계·일치·완료자 전부, OPEN = 전부 없음
    CONSTRAINT ck_wms_loc_check_done CHECK (
        (status = 'DONE') = (done_at IS NOT NULL AND done_by IS NOT NULL AND accurate_yn IS NOT NULL AND ok_cnt IS NOT NULL
                             AND misplaced_cnt IS NOT NULL AND missing_cnt IS NOT NULL AND unknown_cnt IS NOT NULL)
        AND (status = 'DONE' OR (done_at IS NULL AND done_by IS NULL AND accurate_yn IS NULL AND ok_cnt IS NULL
                                 AND misplaced_cnt IS NULL AND missing_cnt IS NULL AND unknown_cnt IS NULL)))
);
COMMENT ON TABLE wms_loc_check IS '정위치 점검 머리 (docs/55) — 칸 × 공장 날짜 하나(같은 날 같은 칸 1회 = 정확도 분모). OPEN → DONE';
COMMENT ON COLUMN wms_loc_check.accurate_yn IS '완료 때: 다른 위치·없음·미등록이 0 이면 Y — 위치 정확도 = Y 칸 ÷ DONE 칸';
CREATE INDEX ix_wms_loc_check_plant ON wms_loc_check (comp_cd, plant_cd, check_date);

CREATE FUNCTION hb_guard_wms_loc_check() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'INSERT' THEN
        IF NEW.status <> 'OPEN' THEN
            RAISE EXCEPTION 'new location check must be OPEN' USING ERRCODE = '55000', CONSTRAINT = 'ck_wms_loc_check_transition';
        END IF;
        -- 같은 회사·공장의 재고를 둘 수 있는 창고·로케이션
        IF NOT EXISTS (SELECT 1 FROM mst_equip_hier h LEFT JOIN wms_loc_attr a ON a.node_id = h.node_id
                        WHERE h.node_id = NEW.node_id AND h.comp_cd = NEW.comp_cd AND h.plant_cd = NEW.plant_cd
                          AND h.node_type IN ('WH', 'LOC') AND coalesce(a.stock_yn, 'Y') = 'Y') THEN
            RAISE EXCEPTION 'node % is not a stock location of plant %', NEW.node_id, NEW.plant_cd
                USING ERRCODE = '23514', CONSTRAINT = 'ck_wms_loc_check_node';
        END IF;
        RETURN NEW;
    END IF;
    IF (NEW.comp_cd, NEW.plant_cd, NEW.node_id, NEW.check_date, NEW.worker_id, NEW.created_at, NEW.created_by)
       IS DISTINCT FROM (OLD.comp_cd, OLD.plant_cd, OLD.node_id, OLD.check_date, OLD.worker_id, OLD.created_at, OLD.created_by) THEN
        RAISE EXCEPTION 'location check key is fixed' USING ERRCODE = '55000', CONSTRAINT = 'ck_wms_loc_check_fixed';
    END IF;
    IF OLD.status = 'DONE' AND (NEW.status, NEW.ok_cnt, NEW.misplaced_cnt, NEW.missing_cnt, NEW.unknown_cnt, NEW.accurate_yn, NEW.done_at, NEW.done_by)
            IS DISTINCT FROM (OLD.status, OLD.ok_cnt, OLD.misplaced_cnt, OLD.missing_cnt, OLD.unknown_cnt, OLD.accurate_yn, OLD.done_at, OLD.done_by) THEN
        RAISE EXCEPTION 'location check % is done', OLD.check_id USING ERRCODE = '55000', CONSTRAINT = 'ck_wms_loc_check_transition';
    END IF;
    RETURN NEW;
END
$$;
CREATE TRIGGER tg_guard BEFORE INSERT OR UPDATE ON wms_loc_check FOR EACH ROW EXECUTE FUNCTION hb_guard_wms_loc_check();
CREATE TRIGGER tg_no_delete BEFORE DELETE ON wms_loc_check FOR EACH ROW EXECUTE FUNCTION hb_forbid_delete_master();
CREATE TRIGGER tg_audit AFTER INSERT OR UPDATE OR DELETE ON wms_loc_check FOR EACH ROW EXECUTE FUNCTION hb_audit('check_id', '', '');

-- ---------------------------------------------------------------------
-- 2. 스캔·판정 줄
-- ---------------------------------------------------------------------
CREATE TABLE wms_loc_check_line (
    line_id         bigint         GENERATED ALWAYS AS IDENTITY,
    comp_cd         varchar(20)    NOT NULL,
    check_id        bigint         NOT NULL,
    scan_code       varchar(200),
    scan_type       varchar(10),
    item_id         bigint,
    lot_id          bigint,
    result          varchar(10)    NOT NULL,
    reason_cd       varchar(30),
    book            jsonb          NOT NULL DEFAULT '[]'::jsonb,
    scanned_at      timestamptz,
    worker_id       bigint,
    client_req_id   varchar(64),
    recorded_at     timestamptz    NOT NULL DEFAULT now(),
    recorded_by     varchar(50)    NOT NULL,
    review_action   varchar(10),
    review_reason   varchar(500),
    reviewed_at     timestamptz,
    reviewed_by     varchar(50),
    move_grp_id     uuid,
    CONSTRAINT pk_wms_loc_check_line PRIMARY KEY (line_id),
    CONSTRAINT fk_wms_loc_check_line_check FOREIGN KEY (check_id) REFERENCES wms_loc_check (check_id),
    CONSTRAINT fk_wms_loc_check_line_item FOREIGN KEY (item_id) REFERENCES mst_item (item_id),
    CONSTRAINT fk_wms_loc_check_line_lot FOREIGN KEY (lot_id) REFERENCES lot_master (lot_id),
    CONSTRAINT uq_wms_loc_check_line_req UNIQUE (comp_cd, client_req_id),
    CONSTRAINT ck_wms_loc_check_line_result CHECK (result IN ('OK', 'MISPLACED', 'MISSING', 'UNKNOWN')),
    CONSTRAINT ck_wms_loc_check_line_scan CHECK (scan_type IN ('LOT', 'ITEM', 'OTHER')),
    -- 없음(MISSING)은 완료 때 장부에서 — 스캔 원문 없음, 품목 필수. 나머지는 스캔 원문 필수
    CONSTRAINT ck_wms_loc_check_line_missing CHECK (
        (result = 'MISSING') = (scan_code IS NULL) AND (scan_code IS NULL) = (scan_type IS NULL)
        AND (result NOT IN ('OK', 'MISPLACED', 'MISSING') OR item_id IS NOT NULL)),
    CONSTRAINT ck_wms_loc_check_line_book CHECK (jsonb_typeof(book) = 'array'),
    -- 처리: 안 됨(전부 비움) / 보정 이동(다른 위치만, 이동 묶음 id) / 확인만(정상 아닌 줄, 사유 필수)
    CONSTRAINT ck_wms_loc_check_line_review CHECK (
        (review_action IS NULL AND review_reason IS NULL AND reviewed_at IS NULL AND reviewed_by IS NULL AND move_grp_id IS NULL)
        OR (review_action = 'CORRECTED' AND result = 'MISPLACED' AND reviewed_at IS NOT NULL AND reviewed_by IS NOT NULL
            AND move_grp_id IS NOT NULL)
        OR (review_action = 'CONFIRMED' AND result <> 'OK' AND reviewed_at IS NOT NULL AND reviewed_by IS NOT NULL
            AND move_grp_id IS NULL AND length(btrim(review_reason)) > 0))
);
COMMENT ON TABLE wms_loc_check_line IS '정위치 점검 줄 (docs/55) — 스캔 한 번 또는 완료 때 없음 한 건. book = 판정 당시 장부 [{nodeId,status,qty}]. 삭제 금지';
COMMENT ON COLUMN wms_loc_check_line.review_action IS '처리 — CORRECTED(보정 이동, move_grp_id) 또는 CONFIRMED(확인만, 사유). null 에서 한 번만';
CREATE INDEX ix_wms_loc_check_line_check ON wms_loc_check_line (comp_cd, check_id);
CREATE INDEX ix_wms_loc_check_line_open ON wms_loc_check_line (comp_cd, check_id) WHERE review_action IS NULL AND result <> 'OK';

CREATE FUNCTION hb_guard_wms_loc_check_line() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'INSERT' THEN
        IF NOT EXISTS (SELECT 1 FROM wms_loc_check c WHERE c.check_id = NEW.check_id AND c.comp_cd = NEW.comp_cd AND c.status = 'OPEN') THEN
            RAISE EXCEPTION 'location check % is not an OPEN check of company %', NEW.check_id, NEW.comp_cd
                USING ERRCODE = '55000', CONSTRAINT = 'ck_wms_loc_check_line_open';
        END IF;
        IF NEW.review_action IS NOT NULL THEN
            RAISE EXCEPTION 'new line cannot be reviewed' USING ERRCODE = '55000', CONSTRAINT = 'ck_wms_loc_check_line_fixed';
        END IF;
        RETURN NEW;
    END IF;
    -- 판정·장부는 고정, 처리는 null 에서 한 번만
    IF (NEW.comp_cd, NEW.check_id, NEW.scan_code, NEW.scan_type, NEW.item_id, NEW.lot_id, NEW.result, NEW.reason_cd, NEW.book, NEW.scanned_at,
        NEW.worker_id, NEW.client_req_id, NEW.recorded_at, NEW.recorded_by)
       IS DISTINCT FROM (OLD.comp_cd, OLD.check_id, OLD.scan_code, OLD.scan_type, OLD.item_id, OLD.lot_id, OLD.result, OLD.reason_cd, OLD.book,
        OLD.scanned_at, OLD.worker_id, OLD.client_req_id, OLD.recorded_at, OLD.recorded_by)
       OR (OLD.review_action IS NOT NULL AND (NEW.review_action, NEW.review_reason, NEW.reviewed_at, NEW.reviewed_by, NEW.move_grp_id)
                                             IS DISTINCT FROM (OLD.review_action, OLD.review_reason, OLD.reviewed_at, OLD.reviewed_by, OLD.move_grp_id)) THEN
        RAISE EXCEPTION 'location check line % is fixed', OLD.line_id USING ERRCODE = '55000', CONSTRAINT = 'ck_wms_loc_check_line_fixed';
    END IF;
    RETURN NEW;
END
$$;
CREATE TRIGGER tg_guard BEFORE INSERT OR UPDATE ON wms_loc_check_line FOR EACH ROW EXECUTE FUNCTION hb_guard_wms_loc_check_line();
CREATE TRIGGER tg_no_delete BEFORE DELETE ON wms_loc_check_line FOR EACH ROW EXECUTE FUNCTION hb_forbid_delete_master();
CREATE TRIGGER tg_audit AFTER INSERT OR UPDATE OR DELETE ON wms_loc_check_line FOR EACH ROW EXECUTE FUNCTION hb_audit('line_id', '', '');

ALTER TABLE wms_loc_check ENABLE ROW LEVEL SECURITY;
ALTER TABLE wms_loc_check FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON wms_loc_check USING (comp_cd = hb_current_comp()) WITH CHECK (comp_cd = hb_current_comp());
ALTER TABLE wms_loc_check_line ENABLE ROW LEVEL SECURITY;
ALTER TABLE wms_loc_check_line FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON wms_loc_check_line USING (comp_cd = hb_current_comp()) WITH CHECK (comp_cd = hb_current_comp());
REVOKE DELETE, TRUNCATE ON wms_loc_check, wms_loc_check_line FROM ${app_role};
INSERT INTO sys_retention_table (table_nm, class_cd) VALUES ('wms_loc_check', 'QUALITY_RECORD'), ('wms_loc_check_line', 'QUALITY_RECORD');

-- 보정 이동 원장 출처 = 점검 줄
CREATE INDEX ix_inv_trx_loc_check ON inv_trx (comp_cd, src_id) WHERE src_type = 'LOC_CHECK';

-- ---------------------------------------------------------------------
-- 3. 화면
-- ---------------------------------------------------------------------
INSERT INTO sys_prog (prog_cd, prog_nm_key, view_path, module_cd, actions, sort_no) VALUES
    ('wms-loc-check', 'prog.wmsLocCheck', 'wms/loc-check', 'WMS', ARRAY['SEARCH','SAVE']::varchar(20)[], 765);
