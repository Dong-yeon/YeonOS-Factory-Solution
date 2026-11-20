-- =====================================================================
-- V9301 자동창고 E4 조각 2 — 출고·긴급·대사 바로 조정 (docs/65 25장, 사용자 결정 Q-E4-1~4 2026-11-10)
--
-- - 출고 라인 SHORT(덜 집고 완료 — Q-E4-2), 긴급 표시(OUT 만, APPROVE, 사유 — Q-E4-4)
-- - 대사 회차·받은 조각(원문)·차이 줄(OPEN → ADJUSTED/DISMISSED 끝 상태 고정 — Q-E4-1)
-- - 조정 원장은 StockService.adjust 로만(이 표에는 조정 요청 id 만 — 유일)
-- 임시 번호(docs/29 2.2, WMS 대역) — 병합 때 이름만 바뀐다.
-- =====================================================================

INSERT INTO sys_code (grp_cd, code, code_nm_key, sort_no) VALUES
    ('WMS_ASRS_LINE_STATUS', 'SHORT', 'code.WMS_ASRS_LINE_STATUS.SHORT', 6),
    ('WMS_ASRS_EVENT', 'URGENT',     'code.WMS_ASRS_EVENT.URGENT',     11),
    ('WMS_ASRS_EVENT', 'SHORT',      'code.WMS_ASRS_EVENT.SHORT',      12),
    ('WMS_ASRS_EVENT', 'RECON',      'code.WMS_ASRS_EVENT.RECON',      13),
    ('WMS_ASRS_EVENT', 'RECON_DIFF', 'code.WMS_ASRS_EVENT.RECON_DIFF', 14),
    ('WMS_ASRS_EVENT', 'ADJUSTED',   'code.WMS_ASRS_EVENT.ADJUSTED',   15),
    ('WMS_ASRS_EVENT', 'DISMISSED',  'code.WMS_ASRS_EVENT.DISMISSED',  16),
    ('WMS_ASRS_EXC_REASON', 'RECON_DIFF', 'code.WMS_ASRS_EXC_REASON.RECON_DIFF', 8),
    ('WMS_ASRS_EXC_REASON', 'OVER_PICK',  'code.WMS_ASRS_EXC_REASON.OVER_PICK',  9);

INSERT INTO sys_code_grp (grp_cd, grp_nm_key, comp_ext_yn, code_max_len) VALUES
    ('WMS_ASRS_DIFF_KIND', 'code.grp.WMS_ASRS_DIFF_KIND', 'N', 30),
    ('WMS_ASRS_DIFF_STATUS', 'code.grp.WMS_ASRS_DIFF_STATUS', 'N', 30);
INSERT INTO sys_code (grp_cd, code, code_nm_key, sort_no) VALUES
    ('WMS_ASRS_DIFF_KIND', 'QTY',     'code.WMS_ASRS_DIFF_KIND.QTY',     1),
    ('WMS_ASRS_DIFF_KIND', 'UNKNOWN', 'code.WMS_ASRS_DIFF_KIND.UNKNOWN', 2),
    ('WMS_ASRS_DIFF_STATUS', 'OPEN',      'code.WMS_ASRS_DIFF_STATUS.OPEN',      1),
    ('WMS_ASRS_DIFF_STATUS', 'ADJUSTED',  'code.WMS_ASRS_DIFF_STATUS.ADJUSTED',  2),
    ('WMS_ASRS_DIFF_STATUS', 'DISMISSED', 'code.WMS_ASRS_DIFF_STATUS.DISMISSED', 3);

-- ---------------------------------------------------------------------
-- 1. 긴급 출고 (Q-E4-4 — 긴급만 WES 로 우선순위를 보낸다)
-- ---------------------------------------------------------------------
ALTER TABLE wms_asrs_req ADD COLUMN urgent_yn char(1) NOT NULL DEFAULT 'N';
ALTER TABLE wms_asrs_req ADD COLUMN urgent_reason varchar(200);
ALTER TABLE wms_asrs_req ADD CONSTRAINT ck_wms_asrs_req_urgent CHECK (urgent_yn IN ('Y', 'N')
    AND (urgent_yn = 'N' OR (req_type = 'OUT' AND urgent_reason IS NOT NULL AND length(btrim(urgent_reason)) >= 2)));

-- 긴급 표시는 되돌리지 않는다(남용 기록이 지워지지 않게)
CREATE FUNCTION hb_guard_wms_asrs_req() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF OLD.urgent_yn = 'Y' AND (NEW.urgent_yn <> 'Y' OR NEW.urgent_reason IS DISTINCT FROM OLD.urgent_reason) THEN
        RAISE EXCEPTION 'urgent mark of request % is fixed', OLD.req_id USING ERRCODE = '55000', CONSTRAINT = 'ck_wms_asrs_req_urgent_fixed';
    END IF;
    IF NEW.req_type <> OLD.req_type OR NEW.req_key <> OLD.req_key OR NEW.asrs_id <> OLD.asrs_id THEN
        RAISE EXCEPTION 'request type, key and asrs are fixed' USING ERRCODE = '55000', CONSTRAINT = 'ck_wms_asrs_req_fixed';
    END IF;
    RETURN NEW;
END
$$;
CREATE TRIGGER tg_wms_asrs_req_guard BEFORE UPDATE ON wms_asrs_req FOR EACH ROW EXECUTE FUNCTION hb_guard_wms_asrs_req();

-- ---------------------------------------------------------------------
-- 2. 라인 SHORT (Q-E4-2 — 덜 집고 완료, 끝 상태)
-- ---------------------------------------------------------------------
ALTER TABLE wms_asrs_req_line DROP CONSTRAINT ck_wms_asrs_req_line_status;
ALTER TABLE wms_asrs_req_line ADD CONSTRAINT ck_wms_asrs_req_line_status
    CHECK (status IN ('NOT_STARTED', 'IN_PROGRESS', 'DONE', 'FAILED', 'CANCELLED', 'SHORT'));
-- 출고 라인 = 품목·수량 필수, 반영 수량은 라인 수량을 넘지 않는다
ALTER TABLE wms_asrs_req_line ADD CONSTRAINT ck_wms_asrs_req_line_done CHECK (qty IS NULL OR done_qty <= qty);

CREATE OR REPLACE FUNCTION hb_guard_wms_asrs_req_line() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM wms_asrs_req r WHERE r.req_id = NEW.req_id AND r.comp_cd = NEW.comp_cd)
       OR (NEW.ctr_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM wms_ctr c JOIN wms_asrs_req r ON r.asrs_id = c.asrs_id
                                                   WHERE c.ctr_id = NEW.ctr_id AND c.comp_cd = NEW.comp_cd AND r.req_id = NEW.req_id))
       OR (NEW.item_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM mst_item i WHERE i.item_id = NEW.item_id AND i.comp_cd = NEW.comp_cd))
       OR (NEW.lot_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM lot_master l WHERE l.lot_id = NEW.lot_id AND l.comp_cd = NEW.comp_cd
                                                     AND l.item_id = NEW.item_id)) THEN
        RAISE EXCEPTION 'request line context is not of company % (or container of another asrs)', NEW.comp_cd
            USING ERRCODE = '23503', CONSTRAINT = 'fk_wms_asrs_req_line_comp';
    END IF;
    -- 끝난 라인은 되돌리지 않는다(늦게 온 옛 상태는 사건 STALE 로만)
    IF TG_OP = 'UPDATE' AND OLD.status IN ('DONE', 'FAILED', 'CANCELLED', 'SHORT') AND NEW.status <> OLD.status THEN
        RAISE EXCEPTION 'request line % is finished (%)', OLD.line_id, OLD.status
            USING ERRCODE = '55000', CONSTRAINT = 'ck_wms_asrs_req_line_final';
    END IF;
    -- 반영 수량은 줄지 않는다(원장 반영분)
    IF TG_OP = 'UPDATE' AND NEW.done_qty < OLD.done_qty THEN
        RAISE EXCEPTION 'done qty of line % cannot decrease', OLD.line_id USING ERRCODE = '55000', CONSTRAINT = 'ck_wms_asrs_req_line_done_qty';
    END IF;
    RETURN NEW;
END
$$;

-- ---------------------------------------------------------------------
-- 3. 사건 코드
-- ---------------------------------------------------------------------
ALTER TABLE wms_asrs_event DROP CONSTRAINT ck_wms_asrs_event_cd;
ALTER TABLE wms_asrs_event ADD CONSTRAINT ck_wms_asrs_event_cd CHECK (event_cd IN ('SENT', 'CLAIMED', 'STATUS', 'POSTED', 'FAILED', 'STALE',
    'STALE_REQ', 'EXCHANGE', 'QUARANTINE', 'CANCELLED', 'URGENT', 'SHORT', 'RECON', 'RECON_DIFF', 'ADJUSTED', 'DISMISSED'));
CREATE INDEX ix_wms_asrs_event_cd ON wms_asrs_event (comp_cd, event_cd, occurred_at);

-- ---------------------------------------------------------------------
-- 4. 대사 (Q-E4-1)
-- ---------------------------------------------------------------------
CREATE TABLE wms_asrs_recon (
    recon_id     bigint        GENERATED ALWAYS AS IDENTITY,
    comp_cd      varchar(20)   NOT NULL,
    asrs_id      bigint        NOT NULL,
    snap_key     varchar(64)   NOT NULL,
    status       varchar(10)   NOT NULL DEFAULT 'RECEIVING',
    part_cnt     integer       NOT NULL DEFAULT 0,
    expected_parts integer,
    ctr_cnt      integer       NOT NULL DEFAULT 0,
    skipped_cnt  integer       NOT NULL DEFAULT 0,
    diff_cnt     integer       NOT NULL DEFAULT 0,
    started_at   timestamptz   NOT NULL DEFAULT now(),
    finished_at  timestamptz,
    created_by   varchar(50)   NOT NULL,
    CONSTRAINT pk_wms_asrs_recon PRIMARY KEY (recon_id),
    CONSTRAINT fk_wms_asrs_recon_asrs FOREIGN KEY (asrs_id) REFERENCES wms_asrs (asrs_id),
    -- WES 스냅숏 id — 같은 스냅숏은 한 회차(조각 재전송 = 멱등)
    CONSTRAINT uq_wms_asrs_recon_key UNIQUE (comp_cd, asrs_id, snap_key),
    CONSTRAINT ck_wms_asrs_recon_key CHECK (snap_key ~ '^[A-Za-z0-9._:-]{1,64}$'),
    CONSTRAINT ck_wms_asrs_recon_status CHECK (status IN ('RECEIVING', 'DONE')),
    CONSTRAINT ck_wms_asrs_recon_done CHECK ((status = 'DONE') = (finished_at IS NOT NULL))
);
CREATE INDEX ix_wms_asrs_recon_asrs ON wms_asrs_recon (comp_cd, asrs_id, recon_id);
COMMENT ON TABLE wms_asrs_recon IS '자동창고 대사 회차 (docs/65 25.3) — WES 전체 용기 정의(조각)를 받아 마지막 조각에서 비교';

CREATE TABLE wms_asrs_recon_part (
    comp_cd     varchar(20)   NOT NULL,
    recon_id    bigint        NOT NULL,
    part_no     integer       NOT NULL,
    body        jsonb         NOT NULL,
    received_at timestamptz   NOT NULL DEFAULT now(),
    CONSTRAINT pk_wms_asrs_recon_part PRIMARY KEY (recon_id, part_no),
    CONSTRAINT fk_wms_asrs_recon_part_recon FOREIGN KEY (recon_id) REFERENCES wms_asrs_recon (recon_id),
    CONSTRAINT ck_wms_asrs_recon_part_no CHECK (part_no BETWEEN 1 AND 10000)
);
COMMENT ON TABLE wms_asrs_recon_part IS '대사 받은 조각 원문 (docs/65 25.3) — (회차, 조각 번호) 하나 = 재전송 멱등. 추가만';

CREATE TABLE wms_asrs_recon_diff (
    diff_id      bigint          GENERATED ALWAYS AS IDENTITY,
    comp_cd      varchar(20)     NOT NULL,
    recon_id     bigint          NOT NULL,
    asrs_id      bigint          NOT NULL,
    ctr_id       bigint,
    ctr_cd       varchar(30)     NOT NULL,
    slot_no      smallint        NOT NULL,
    item_id      bigint,
    item_cd      varchar(50)     NOT NULL,
    lot_id       bigint,
    lot_no       varchar(50),
    stock_status varchar(12),
    our_qty      numeric(18,6)   NOT NULL,
    wes_qty      numeric(18,6)   NOT NULL,
    diff_kind    varchar(10)     NOT NULL,
    status       varchar(10)     NOT NULL DEFAULT 'OPEN',
    adj_req_id   varchar(64),
    reason_cd    varchar(30),
    remark       varchar(500),
    resolved_by  varchar(50),
    resolved_at  timestamptz,
    row_ver      integer         NOT NULL DEFAULT 0,
    created_at   timestamptz     NOT NULL DEFAULT now(),
    CONSTRAINT pk_wms_asrs_recon_diff PRIMARY KEY (diff_id),
    CONSTRAINT fk_wms_asrs_recon_diff_recon FOREIGN KEY (recon_id) REFERENCES wms_asrs_recon (recon_id),
    CONSTRAINT fk_wms_asrs_recon_diff_asrs FOREIGN KEY (asrs_id) REFERENCES wms_asrs (asrs_id),
    CONSTRAINT fk_wms_asrs_recon_diff_ctr FOREIGN KEY (ctr_id) REFERENCES wms_ctr (ctr_id),
    CONSTRAINT fk_wms_asrs_recon_diff_item FOREIGN KEY (item_id) REFERENCES mst_item (item_id),
    CONSTRAINT fk_wms_asrs_recon_diff_lot FOREIGN KEY (lot_id) REFERENCES lot_master (lot_id),
    CONSTRAINT ck_wms_asrs_recon_diff_kind CHECK (diff_kind IN ('QTY', 'UNKNOWN')),
    CONSTRAINT ck_wms_asrs_recon_diff_status CHECK (status IN ('OPEN', 'ADJUSTED', 'DISMISSED')),
    CONSTRAINT ck_wms_asrs_recon_diff_qty CHECK (our_qty >= 0 AND wes_qty >= 0 AND (diff_kind = 'UNKNOWN' OR our_qty <> wes_qty)),
    -- 조정은 원장(StockService.adjust)을 거친 것만 — 요청 id 가 있어야 ADJUSTED, 끝난 줄은 누가·언제가 있다
    CONSTRAINT ck_wms_asrs_recon_diff_adj CHECK ((status = 'ADJUSTED') = (adj_req_id IS NOT NULL)
        AND (status = 'OPEN' OR (resolved_by IS NOT NULL AND resolved_at IS NOT NULL))
        AND (status <> 'ADJUSTED' OR (diff_kind = 'QTY' AND reason_cd IS NOT NULL AND item_id IS NOT NULL AND ctr_id IS NOT NULL))
        AND (status <> 'DISMISSED' OR length(btrim(remark)) >= 2)),
    CONSTRAINT ck_wms_asrs_recon_diff_status_cd CHECK (stock_status IS NULL OR stock_status IN ('AVAILABLE', 'QC', 'HOLD', 'BLOCKED'))
);
-- 같은 차이를 두 번 조정하지 않는다(조정 요청 id = 원장 clientReqId)
CREATE UNIQUE INDEX uq_wms_asrs_recon_diff_adj ON wms_asrs_recon_diff (comp_cd, adj_req_id) WHERE adj_req_id IS NOT NULL;
CREATE INDEX ix_wms_asrs_recon_diff_recon ON wms_asrs_recon_diff (comp_cd, recon_id, diff_id);
CREATE INDEX ix_wms_asrs_recon_diff_ctr ON wms_asrs_recon_diff (comp_cd, ctr_id) WHERE status = 'OPEN';
COMMENT ON TABLE wms_asrs_recon_diff IS '대사 차이 줄 (docs/65 25.3) — OPEN → ADJUSTED(StockService.adjust 경유)/DISMISSED, 끝 상태 고정';

CREATE FUNCTION hb_guard_wms_asrs_recon() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_TABLE_NAME = 'wms_asrs_recon' THEN
        IF NOT EXISTS (SELECT 1 FROM wms_asrs a WHERE a.asrs_id = NEW.asrs_id AND a.comp_cd = NEW.comp_cd) THEN
            RAISE EXCEPTION 'recon asrs is not of company %', NEW.comp_cd USING ERRCODE = '23503', CONSTRAINT = 'fk_wms_asrs_recon_comp';
        END IF;
        IF TG_OP = 'UPDATE' AND (OLD.status = 'DONE' OR NEW.snap_key <> OLD.snap_key OR NEW.asrs_id <> OLD.asrs_id) THEN
            RAISE EXCEPTION 'recon % is finished or fixed', OLD.recon_id USING ERRCODE = '55000', CONSTRAINT = 'ck_wms_asrs_recon_final';
        END IF;
    ELSIF TG_TABLE_NAME = 'wms_asrs_recon_part' THEN
        IF NOT EXISTS (SELECT 1 FROM wms_asrs_recon r WHERE r.recon_id = NEW.recon_id AND r.comp_cd = NEW.comp_cd AND r.status = 'RECEIVING') THEN
            RAISE EXCEPTION 'recon part needs a receiving recon of company %', NEW.comp_cd
                USING ERRCODE = '23503', CONSTRAINT = 'fk_wms_asrs_recon_part_comp';
        END IF;
    ELSE
        IF NOT EXISTS (SELECT 1 FROM wms_asrs_recon r WHERE r.recon_id = NEW.recon_id AND r.comp_cd = NEW.comp_cd AND r.asrs_id = NEW.asrs_id)
           OR (NEW.ctr_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM wms_ctr c WHERE c.ctr_id = NEW.ctr_id AND c.comp_cd = NEW.comp_cd
                                                         AND c.asrs_id = NEW.asrs_id))
           OR (NEW.item_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM mst_item i WHERE i.item_id = NEW.item_id AND i.comp_cd = NEW.comp_cd)) THEN
            RAISE EXCEPTION 'recon diff context is not of company %', NEW.comp_cd USING ERRCODE = '23503', CONSTRAINT = 'fk_wms_asrs_recon_diff_comp';
        END IF;
        IF TG_OP = 'UPDATE' AND (OLD.status <> 'OPEN'
               OR (NEW.recon_id, NEW.ctr_id, NEW.slot_no, NEW.item_id, NEW.lot_id, NEW.stock_status, NEW.our_qty, NEW.wes_qty, NEW.diff_kind)
                  IS DISTINCT FROM (OLD.recon_id, OLD.ctr_id, OLD.slot_no, OLD.item_id, OLD.lot_id, OLD.stock_status, OLD.our_qty, OLD.wes_qty,
                                    OLD.diff_kind)) THEN
            RAISE EXCEPTION 'recon diff % is finished or its measured values are fixed', OLD.diff_id
                USING ERRCODE = '55000', CONSTRAINT = 'ck_wms_asrs_recon_diff_final';
        END IF;
    END IF;
    RETURN NEW;
END
$$;
CREATE TRIGGER tg_wms_asrs_recon_guard BEFORE INSERT OR UPDATE ON wms_asrs_recon FOR EACH ROW EXECUTE FUNCTION hb_guard_wms_asrs_recon();
CREATE TRIGGER tg_wms_asrs_recon_part_guard BEFORE INSERT ON wms_asrs_recon_part FOR EACH ROW EXECUTE FUNCTION hb_guard_wms_asrs_recon();
CREATE TRIGGER tg_wms_asrs_recon_part_ledger BEFORE UPDATE OR DELETE ON wms_asrs_recon_part FOR EACH ROW EXECUTE FUNCTION hb_forbid_update_delete();
CREATE TRIGGER tg_wms_asrs_recon_diff_guard BEFORE INSERT OR UPDATE ON wms_asrs_recon_diff FOR EACH ROW EXECUTE FUNCTION hb_guard_wms_asrs_recon();
CREATE TRIGGER tg_wms_asrs_recon_diff_code_reason BEFORE INSERT OR UPDATE OF reason_cd ON wms_asrs_recon_diff
    FOR EACH ROW EXECUTE FUNCTION hb_check_code_col('WMS_ADJ_REASON', 'reason_cd');

-- ---------------------------------------------------------------------
-- 5. 감사 · RLS · 권한 · 화면 · 보존
-- ---------------------------------------------------------------------
CREATE TRIGGER tg_audit AFTER INSERT OR UPDATE OR DELETE ON wms_asrs_recon FOR EACH ROW
    EXECUTE FUNCTION hb_audit('recon_id', 'part_cnt,expected_parts,ctr_cnt,skipped_cnt,diff_cnt,status,finished_at', '');
CREATE TRIGGER tg_audit AFTER INSERT OR UPDATE OR DELETE ON wms_asrs_recon_diff FOR EACH ROW EXECUTE FUNCTION hb_audit('diff_id', 'row_ver', '');

ALTER TABLE wms_asrs_recon ENABLE ROW LEVEL SECURITY;
ALTER TABLE wms_asrs_recon FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON wms_asrs_recon USING (comp_cd = hb_current_comp()) WITH CHECK (comp_cd = hb_current_comp());
ALTER TABLE wms_asrs_recon_part ENABLE ROW LEVEL SECURITY;
ALTER TABLE wms_asrs_recon_part FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON wms_asrs_recon_part USING (comp_cd = hb_current_comp()) WITH CHECK (comp_cd = hb_current_comp());
ALTER TABLE wms_asrs_recon_diff ENABLE ROW LEVEL SECURITY;
ALTER TABLE wms_asrs_recon_diff FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON wms_asrs_recon_diff USING (comp_cd = hb_current_comp()) WITH CHECK (comp_cd = hb_current_comp());

REVOKE DELETE, TRUNCATE ON wms_asrs_recon, wms_asrs_recon_diff FROM ${app_role};
REVOKE UPDATE, DELETE, TRUNCATE ON wms_asrs_recon_part FROM ${app_role};

UPDATE sys_prog SET actions = ARRAY['SEARCH','SAVE','APPROVE']::varchar(20)[] WHERE prog_cd = 'wms-asrs';

INSERT INTO sys_retention_table (table_nm, class_cd) VALUES
    ('wms_asrs_recon', 'QUALITY_RECORD'), ('wms_asrs_recon_part', 'QUALITY_RECORD'), ('wms_asrs_recon_diff', 'QUALITY_RECORD');
