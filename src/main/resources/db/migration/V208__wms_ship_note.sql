-- =====================================================================
-- WMS 출하 특이사항 (docs/56, docs/49 W-4 — 2026-10-29 사용자 결정) — 임시 번호(docs/29 2.2)
--
-- - wms_ship_note: 규칙 — 조건 고객·품목군·품목(비우면 전체, 품목군·품목 동시 불가), 제목·내용, 확인 필수. 기준정보처럼 고침(row_ver), 삭제 없음(사용 끔)
-- - wms_ship_note_ack: 출하 지시 × 규칙 × 규칙 버전 확인 기록 — 쌓기만(누가 언제 봤는지). 글을 고치면 버전이 올라 다시 확인
-- - 화면 wms-ship-note(규칙 관리). 배지·확인은 출하(wms-ship)·PDA 피킹에서
-- 영업 표는 읽지 않는다 — 고객은 wms_ship.cust_id, 품목은 wms_ship_line.item_id (새 계약 없음, 상위 정정 (가))
-- =====================================================================

-- ---------------------------------------------------------------------
-- 1. 규칙
-- ---------------------------------------------------------------------
CREATE TABLE wms_ship_note (
    note_id          bigint         GENERATED ALWAYS AS IDENTITY,
    comp_cd          varchar(20)    NOT NULL,
    cust_id          bigint,
    item_class_id    bigint,
    item_id          bigint,
    title            varchar(100)   NOT NULL,
    body             varchar(1000),
    ack_required_yn  char(1)        NOT NULL DEFAULT 'N',
    use_yn           char(1)        NOT NULL DEFAULT 'Y',
    sort_no          integer        NOT NULL DEFAULT 0,
    row_ver          integer        NOT NULL DEFAULT 0,
    created_at       timestamptz    NOT NULL DEFAULT now(),
    created_by       varchar(50)    NOT NULL,
    updated_at       timestamptz    NOT NULL DEFAULT now(),
    updated_by       varchar(50)    NOT NULL,
    CONSTRAINT pk_wms_ship_note PRIMARY KEY (note_id),
    CONSTRAINT fk_wms_ship_note_comp FOREIGN KEY (comp_cd) REFERENCES sys_comp (comp_cd),
    CONSTRAINT fk_wms_ship_note_cust FOREIGN KEY (cust_id) REFERENCES mst_cust (cust_id),
    CONSTRAINT fk_wms_ship_note_class FOREIGN KEY (item_class_id) REFERENCES mst_item_class (class_id),
    CONSTRAINT fk_wms_ship_note_item FOREIGN KEY (item_id) REFERENCES mst_item (item_id),
    CONSTRAINT ck_wms_ship_note_target CHECK (item_class_id IS NULL OR item_id IS NULL),
    CONSTRAINT ck_wms_ship_note_title CHECK (length(btrim(title)) > 0),
    CONSTRAINT ck_wms_ship_note_ack_yn CHECK (ack_required_yn IN ('Y', 'N')),
    CONSTRAINT ck_wms_ship_note_use_yn CHECK (use_yn IN ('Y', 'N')),
    CONSTRAINT ck_wms_ship_note_sort CHECK (sort_no BETWEEN 0 AND 9999)
);
COMMENT ON TABLE wms_ship_note IS '출하 특이사항 규칙 (docs/56) — 고객·품목군·품목 조건(비우면 전체)에 걸리는 출하 지시·피킹에 배지. 확인 필수면 확인 전 피킹·확정 불가';
COMMENT ON COLUMN wms_ship_note.row_ver IS '규칙 버전 — 확인 기록(wms_ship_note_ack.note_ver)이 이 값과 같아야 유효(글을 고치면 다시 확인)';
CREATE INDEX ix_wms_ship_note_cust ON wms_ship_note (comp_cd, cust_id) WHERE use_yn = 'Y';

-- 조건 대상은 같은 회사 것만(FK 는 RLS 를 보지 않는다), 회사는 고정
CREATE FUNCTION hb_guard_wms_ship_note() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'UPDATE' AND NEW.comp_cd IS DISTINCT FROM OLD.comp_cd THEN
        RAISE EXCEPTION 'ship note company is fixed' USING ERRCODE = '55000', CONSTRAINT = 'ck_wms_ship_note_fixed';
    END IF;
    IF (NEW.cust_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM mst_cust c WHERE c.cust_id = NEW.cust_id AND c.comp_cd = NEW.comp_cd))
       OR (NEW.item_class_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM mst_item_class k WHERE k.class_id = NEW.item_class_id AND k.comp_cd = NEW.comp_cd))
       OR (NEW.item_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM mst_item i WHERE i.item_id = NEW.item_id AND i.comp_cd = NEW.comp_cd)) THEN
        RAISE EXCEPTION 'ship note target belongs to another company' USING ERRCODE = '23514', CONSTRAINT = 'ck_wms_ship_note_comp';
    END IF;
    RETURN NEW;
END
$$;
CREATE TRIGGER tg_guard BEFORE INSERT OR UPDATE ON wms_ship_note FOR EACH ROW EXECUTE FUNCTION hb_guard_wms_ship_note();
CREATE TRIGGER tg_no_delete BEFORE DELETE ON wms_ship_note FOR EACH ROW EXECUTE FUNCTION hb_forbid_delete_master();
CREATE TRIGGER tg_audit AFTER INSERT OR UPDATE OR DELETE ON wms_ship_note FOR EACH ROW EXECUTE FUNCTION hb_audit('note_id', '', '');

-- ---------------------------------------------------------------------
-- 2. 확인 기록
-- ---------------------------------------------------------------------
CREATE TABLE wms_ship_note_ack (
    ack_id      bigint         GENERATED ALWAYS AS IDENTITY,
    comp_cd     varchar(20)    NOT NULL,
    ship_id     bigint         NOT NULL,
    note_id     bigint         NOT NULL,
    note_ver    integer        NOT NULL,
    worker_id   bigint,
    acked_at    timestamptz    NOT NULL DEFAULT now(),
    acked_by    varchar(50)    NOT NULL,
    CONSTRAINT pk_wms_ship_note_ack PRIMARY KEY (ack_id),
    CONSTRAINT fk_wms_ship_note_ack_ship FOREIGN KEY (ship_id) REFERENCES wms_ship (ship_id),
    CONSTRAINT fk_wms_ship_note_ack_note FOREIGN KEY (note_id) REFERENCES wms_ship_note (note_id),
    CONSTRAINT fk_wms_ship_note_ack_worker FOREIGN KEY (worker_id) REFERENCES mst_worker (worker_id),
    CONSTRAINT uq_wms_ship_note_ack UNIQUE (ship_id, note_id, note_ver)
);
COMMENT ON TABLE wms_ship_note_ack IS '출하 특이사항 확인 (docs/56) — 지시 × 규칙 × 규칙 버전, 쌓기만. worker_id = PDA 배지 작업자';
CREATE INDEX ix_wms_ship_note_ack_ship ON wms_ship_note_ack (comp_cd, ship_id);

-- 지시·규칙·작업자는 같은 회사, 쌓기만
CREATE FUNCTION hb_guard_wms_ship_note_ack() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP <> 'INSERT' THEN
        RAISE EXCEPTION 'ship note acknowledgement is append-only' USING ERRCODE = '55000', CONSTRAINT = 'ck_wms_ship_note_ack_fixed';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM wms_ship s WHERE s.ship_id = NEW.ship_id AND s.comp_cd = NEW.comp_cd)
       OR NOT EXISTS (SELECT 1 FROM wms_ship_note n WHERE n.note_id = NEW.note_id AND n.comp_cd = NEW.comp_cd)
       OR (NEW.worker_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM mst_worker w WHERE w.worker_id = NEW.worker_id AND w.comp_cd = NEW.comp_cd)) THEN
        RAISE EXCEPTION 'ship note acknowledgement crosses companies' USING ERRCODE = '23514', CONSTRAINT = 'ck_wms_ship_note_ack_comp';
    END IF;
    RETURN NEW;
END
$$;
CREATE TRIGGER tg_guard BEFORE INSERT OR UPDATE ON wms_ship_note_ack FOR EACH ROW EXECUTE FUNCTION hb_guard_wms_ship_note_ack();
CREATE TRIGGER tg_no_delete BEFORE DELETE ON wms_ship_note_ack FOR EACH ROW EXECUTE FUNCTION hb_forbid_delete_master();
CREATE TRIGGER tg_audit AFTER INSERT OR UPDATE OR DELETE ON wms_ship_note_ack FOR EACH ROW EXECUTE FUNCTION hb_audit('ack_id', '', '');

ALTER TABLE wms_ship_note ENABLE ROW LEVEL SECURITY;
ALTER TABLE wms_ship_note FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON wms_ship_note USING (comp_cd = hb_current_comp()) WITH CHECK (comp_cd = hb_current_comp());
ALTER TABLE wms_ship_note_ack ENABLE ROW LEVEL SECURITY;
ALTER TABLE wms_ship_note_ack FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON wms_ship_note_ack USING (comp_cd = hb_current_comp()) WITH CHECK (comp_cd = hb_current_comp());
REVOKE DELETE, TRUNCATE ON wms_ship_note, wms_ship_note_ack FROM ${app_role};
REVOKE UPDATE ON wms_ship_note_ack FROM ${app_role};
INSERT INTO sys_retention_table (table_nm, class_cd) VALUES ('wms_ship_note', 'MASTER'), ('wms_ship_note_ack', 'QUALITY_RECORD');

-- ---------------------------------------------------------------------
-- 3. 화면
-- ---------------------------------------------------------------------
INSERT INTO sys_prog (prog_cd, prog_nm_key, view_path, module_cd, actions, sort_no) VALUES
    ('wms-ship-note', 'prog.wmsShipNote', 'wms/ship-note', 'WMS', ARRAY['SEARCH','SAVE']::varchar(20)[], 466);
