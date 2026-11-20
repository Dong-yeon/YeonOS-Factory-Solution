-- =====================================================================
-- V9300 자동창고 보관 용기 E4 (docs/65 23장 — 8장, 결정 Q1·Q2)
--
-- - wms_loc_attr.loc_usage + AUTO_STORE: 자동창고 전체 = 가상 로케이션 1개(원장 칸은 늘리지 않는다, Q1)
-- - wms_asrs: 자동창고 마스터(가상 로케이션·투입구·출구 노드, 장치 = COLLECTOR 키, DIRECT/EDGE, WES 주소)
-- - wms_ctr_type / wms_ctr / wms_ctr_content: 용기 물리 유형 / 개별 용기 / 칸별 내용 사본
-- - wms_asrs_req / wms_asrs_req_line: 입·출고 요청 / 라인(요청 진행 상태는 라인 집계로 계산 — 직접 쓰지 않음)
-- - wms_asrs_event: 요청·라인·용기 사건 원장(추가만)
-- - 스테이션 예외·대사 차이 사유 WMS_ASRS_EXC_REASON: 회사 확장(hb_check_code_col)
-- 임시 번호(docs/29 2.2, WMS 대역) — 병합 때 이름만 바뀐다.
-- =====================================================================

INSERT INTO sys_policy_def (policy_key, value_type, default_value, allowed_values, min_value, max_value, plant_scope_yn, desc_key) VALUES
    -- 시작 안 됨·진행 중으로 이보다 오래 머문 요청 = 경보(로봇이 닿지 못한 용기는 오류 없이 영원히 NOT_STARTED — 현장 시험)
    ('WMS_ASRS_STALE_MIN', 'INT', '30', NULL, 1, 1440, 'N', 'policy.WMS_ASRS_STALE_MIN');

INSERT INTO sys_code (grp_cd, code, code_nm_key, sort_no) VALUES ('WMS_LOC_USAGE', 'AUTO_STORE', 'code.WMS_LOC_USAGE.AUTO_STORE', 7);

INSERT INTO sys_code_grp (grp_cd, grp_nm_key, comp_ext_yn, code_max_len) VALUES
    ('WMS_CTR_STATUS', 'code.grp.WMS_CTR_STATUS', 'N', 30),
    ('WMS_ASRS_REQ_TYPE', 'code.grp.WMS_ASRS_REQ_TYPE', 'N', 30),
    ('WMS_ASRS_SEND', 'code.grp.WMS_ASRS_SEND', 'N', 30),
    ('WMS_ASRS_LINE_STATUS', 'code.grp.WMS_ASRS_LINE_STATUS', 'N', 30),
    ('WMS_ASRS_EVENT', 'code.grp.WMS_ASRS_EVENT', 'N', 30),
    ('WMS_ASRS_EXC_REASON', 'code.grp.WMS_ASRS_EXC_REASON', 'Y', 30);
INSERT INTO sys_code (grp_cd, code, code_nm_key, sort_no) VALUES
    ('WMS_CTR_STATUS', 'EMPTY',      'code.WMS_CTR_STATUS.EMPTY',      1),
    ('WMS_CTR_STATUS', 'LOADED',     'code.WMS_CTR_STATUS.LOADED',     2),
    ('WMS_CTR_STATUS', 'IN_SYSTEM',  'code.WMS_CTR_STATUS.IN_SYSTEM',  3),
    ('WMS_CTR_STATUS', 'QUARANTINE', 'code.WMS_CTR_STATUS.QUARANTINE', 4),
    ('WMS_ASRS_REQ_TYPE', 'IN',  'code.WMS_ASRS_REQ_TYPE.IN',  1),
    ('WMS_ASRS_REQ_TYPE', 'OUT', 'code.WMS_ASRS_REQ_TYPE.OUT', 2),
    ('WMS_ASRS_SEND', 'DRAFT',     'code.WMS_ASRS_SEND.DRAFT',     1),
    ('WMS_ASRS_SEND', 'QUEUED',    'code.WMS_ASRS_SEND.QUEUED',    2),
    ('WMS_ASRS_SEND', 'SENT',      'code.WMS_ASRS_SEND.SENT',      3),
    ('WMS_ASRS_SEND', 'CANCELLED', 'code.WMS_ASRS_SEND.CANCELLED', 4),
    ('WMS_ASRS_LINE_STATUS', 'NOT_STARTED', 'code.WMS_ASRS_LINE_STATUS.NOT_STARTED', 1),
    ('WMS_ASRS_LINE_STATUS', 'IN_PROGRESS', 'code.WMS_ASRS_LINE_STATUS.IN_PROGRESS', 2),
    ('WMS_ASRS_LINE_STATUS', 'DONE',        'code.WMS_ASRS_LINE_STATUS.DONE',        3),
    ('WMS_ASRS_LINE_STATUS', 'FAILED',      'code.WMS_ASRS_LINE_STATUS.FAILED',      4),
    ('WMS_ASRS_LINE_STATUS', 'CANCELLED',   'code.WMS_ASRS_LINE_STATUS.CANCELLED',   5),
    ('WMS_ASRS_EVENT', 'SENT',        'code.WMS_ASRS_EVENT.SENT',        1),
    ('WMS_ASRS_EVENT', 'CLAIMED',     'code.WMS_ASRS_EVENT.CLAIMED',     2),
    ('WMS_ASRS_EVENT', 'STATUS',      'code.WMS_ASRS_EVENT.STATUS',      3),
    ('WMS_ASRS_EVENT', 'POSTED',      'code.WMS_ASRS_EVENT.POSTED',      4),
    ('WMS_ASRS_EVENT', 'FAILED',      'code.WMS_ASRS_EVENT.FAILED',      5),
    ('WMS_ASRS_EVENT', 'STALE',       'code.WMS_ASRS_EVENT.STALE',       6),
    ('WMS_ASRS_EVENT', 'STALE_REQ',   'code.WMS_ASRS_EVENT.STALE_REQ',   7),
    ('WMS_ASRS_EVENT', 'EXCHANGE',    'code.WMS_ASRS_EVENT.EXCHANGE',    8),
    ('WMS_ASRS_EVENT', 'QUARANTINE',  'code.WMS_ASRS_EVENT.QUARANTINE',  9),
    ('WMS_ASRS_EVENT', 'CANCELLED',   'code.WMS_ASRS_EVENT.CANCELLED',   10),
    ('WMS_ASRS_EXC_REASON', 'DAMAGED',        'code.WMS_ASRS_EXC_REASON.DAMAGED',        1),
    ('WMS_ASRS_EXC_REASON', 'EMPTY_CTR',      'code.WMS_ASRS_EXC_REASON.EMPTY_CTR',      2),
    ('WMS_ASRS_EXC_REASON', 'SHORT',          'code.WMS_ASRS_EXC_REASON.SHORT',          3),
    ('WMS_ASRS_EXC_REASON', 'WRONG_ITEM',     'code.WMS_ASRS_EXC_REASON.WRONG_ITEM',     4),
    ('WMS_ASRS_EXC_REASON', 'SHAPE_MISMATCH', 'code.WMS_ASRS_EXC_REASON.SHAPE_MISMATCH', 5),
    ('WMS_ASRS_EXC_REASON', 'STOCK_SHORT',    'code.WMS_ASRS_EXC_REASON.STOCK_SHORT',    6),
    ('WMS_ASRS_EXC_REASON', 'WES_ERROR',      'code.WMS_ASRS_EXC_REASON.WES_ERROR',      7),
    ('WMS_ASRS_EXC_REASON', 'OTHER',          'code.WMS_ASRS_EXC_REASON.OTHER',          99);

-- ---------------------------------------------------------------------
-- 0. 로케이션 용도 AUTO_STORE (WMS 표 — 자기 표)
-- ---------------------------------------------------------------------
ALTER TABLE wms_loc_attr DROP CONSTRAINT ck_wms_loc_attr_usage;
ALTER TABLE wms_loc_attr ADD CONSTRAINT ck_wms_loc_attr_usage
    CHECK (loc_usage IN ('STORAGE', 'RECEIVING', 'QC', 'STAGING', 'LINESIDE', 'SCRAP', 'AUTO_STORE'));

-- ---------------------------------------------------------------------
-- 1. 자동창고
-- ---------------------------------------------------------------------
CREATE TABLE wms_asrs (
    asrs_id       bigint        GENERATED ALWAYS AS IDENTITY,
    comp_cd       varchar(20)   NOT NULL,
    plant_cd      varchar(20)   NOT NULL,
    asrs_cd       varchar(20)   NOT NULL,
    asrs_nm       varchar(100)  NOT NULL,
    store_node_id bigint        NOT NULL,
    in_node_id    bigint        NOT NULL,
    out_node_id   bigint        NOT NULL,
    terminal_id   bigint        NOT NULL,
    conn_mode     varchar(10)   NOT NULL DEFAULT 'DIRECT',
    wes_url       varchar(300),
    use_yn        char(1)       NOT NULL DEFAULT 'Y',
    remark        varchar(500),
    row_ver       integer       NOT NULL DEFAULT 0,
    created_at    timestamptz   NOT NULL DEFAULT now(),
    created_by    varchar(50)   NOT NULL,
    updated_at    timestamptz   NOT NULL DEFAULT now(),
    updated_by    varchar(50)   NOT NULL,
    CONSTRAINT pk_wms_asrs PRIMARY KEY (asrs_id),
    CONSTRAINT fk_wms_asrs_plant FOREIGN KEY (comp_cd, plant_cd) REFERENCES sys_plant (comp_cd, plant_cd),
    CONSTRAINT fk_wms_asrs_store FOREIGN KEY (store_node_id) REFERENCES mst_equip_hier (node_id),
    CONSTRAINT fk_wms_asrs_in FOREIGN KEY (in_node_id) REFERENCES mst_equip_hier (node_id),
    CONSTRAINT fk_wms_asrs_out FOREIGN KEY (out_node_id) REFERENCES mst_equip_hier (node_id),
    CONSTRAINT fk_wms_asrs_terminal FOREIGN KEY (terminal_id) REFERENCES sys_terminal (terminal_id),
    CONSTRAINT uq_wms_asrs_cd UNIQUE (comp_cd, plant_cd, asrs_cd),
    CONSTRAINT ck_wms_asrs_cd CHECK (asrs_cd ~ '^[A-Z0-9][A-Z0-9_-]{0,19}$'),
    CONSTRAINT ck_wms_asrs_nm CHECK (length(btrim(asrs_nm)) > 0),
    CONSTRAINT ck_wms_asrs_conn CHECK (conn_mode IN ('DIRECT', 'EDGE')),
    -- DIRECT 는 서버가 WES 로 미는 주소(사설 대역 검사는 보낼 때마다 — 라벨 프린터와 같은 PrinterAddress), EDGE 는 없음
    CONSTRAINT ck_wms_asrs_url CHECK ((conn_mode = 'DIRECT') = (wes_url IS NOT NULL)
        AND (wes_url IS NULL OR wes_url ~ '^https?://[A-Za-z0-9.:\-]{1,253}(:[0-9]{1,5})?(/[A-Za-z0-9._~/-]*)?$')),
    CONSTRAINT ck_wms_asrs_nodes CHECK (store_node_id <> in_node_id AND store_node_id <> out_node_id),
    CONSTRAINT ck_wms_asrs_use_yn CHECK (use_yn IN ('Y', 'N'))
);
-- 한 장치 = 한 자동창고(장치 키로 자동창고를 찾는다), 한 가상 로케이션 = 한 자동창고
CREATE UNIQUE INDEX uq_wms_asrs_terminal ON wms_asrs (comp_cd, terminal_id) WHERE use_yn = 'Y';
CREATE UNIQUE INDEX uq_wms_asrs_store ON wms_asrs (comp_cd, store_node_id);
COMMENT ON TABLE wms_asrs IS '자동창고 (docs/65 23장) — 전체 = 가상 로케이션 1개(Q1). 물리 위치(랙 슬롯)는 WES 주인(Q2)';

CREATE FUNCTION hb_guard_wms_asrs() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'UPDATE' AND (NEW.plant_cd <> OLD.plant_cd OR NEW.asrs_cd <> OLD.asrs_cd OR NEW.store_node_id <> OLD.store_node_id) THEN
        RAISE EXCEPTION 'asrs plant, code and store location are fixed' USING ERRCODE = '55000', CONSTRAINT = 'ck_wms_asrs_fixed';
    END IF;
    IF EXISTS (SELECT 1 FROM unnest(ARRAY[NEW.store_node_id, NEW.in_node_id, NEW.out_node_id]) n(id)
                WHERE NOT EXISTS (SELECT 1 FROM mst_equip_hier h WHERE h.node_id = n.id AND h.comp_cd = NEW.comp_cd
                                     AND h.plant_cd = NEW.plant_cd)) THEN
        RAISE EXCEPTION 'asrs locations must be of company % plant %', NEW.comp_cd, NEW.plant_cd
            USING ERRCODE = '23503', CONSTRAINT = 'fk_wms_asrs_node_comp';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM wms_loc_attr a WHERE a.node_id = NEW.store_node_id AND a.comp_cd = NEW.comp_cd
                      AND a.loc_usage = 'AUTO_STORE') THEN
        RAISE EXCEPTION 'store location % must have usage AUTO_STORE', NEW.store_node_id
            USING ERRCODE = '23514', CONSTRAINT = 'ck_wms_asrs_store_usage';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM sys_terminal t WHERE t.terminal_id = NEW.terminal_id AND t.comp_cd = NEW.comp_cd
                      AND t.terminal_type = 'COLLECTOR') THEN
        RAISE EXCEPTION 'terminal % is not a device of company %', NEW.terminal_id, NEW.comp_cd
            USING ERRCODE = '23503', CONSTRAINT = 'fk_wms_asrs_terminal_comp';
    END IF;
    RETURN NEW;
END
$$;
CREATE TRIGGER tg_wms_asrs_guard BEFORE INSERT OR UPDATE ON wms_asrs FOR EACH ROW EXECUTE FUNCTION hb_guard_wms_asrs();
CREATE TRIGGER tg_wms_asrs_no_delete BEFORE DELETE ON wms_asrs FOR EACH ROW EXECUTE FUNCTION hb_forbid_delete_master();

-- ---------------------------------------------------------------------
-- 2. 용기 유형 · 용기 · 칸별 내용(사본)
-- ---------------------------------------------------------------------
CREATE TABLE wms_ctr_type (
    type_id     bigint        GENERATED ALWAYS AS IDENTITY,
    comp_cd     varchar(20)   NOT NULL,
    type_cd     varchar(20)   NOT NULL,
    type_nm     varchar(100)  NOT NULL,
    length_mm   integer       NOT NULL,
    width_mm    integer       NOT NULL,
    height_mm   integer       NOT NULL,
    slot_cnt    smallint      NOT NULL DEFAULT 1,
    use_yn      char(1)       NOT NULL DEFAULT 'Y',
    row_ver     integer       NOT NULL DEFAULT 0,
    created_at  timestamptz   NOT NULL DEFAULT now(),
    created_by  varchar(50)   NOT NULL,
    updated_at  timestamptz   NOT NULL DEFAULT now(),
    updated_by  varchar(50)   NOT NULL,
    CONSTRAINT pk_wms_ctr_type PRIMARY KEY (type_id),
    CONSTRAINT fk_wms_ctr_type_comp FOREIGN KEY (comp_cd) REFERENCES sys_comp (comp_cd),
    CONSTRAINT uq_wms_ctr_type_cd UNIQUE (comp_cd, type_cd),
    CONSTRAINT ck_wms_ctr_type_cd CHECK (type_cd ~ '^[A-Z0-9][A-Z0-9_-]{0,19}$'),
    CONSTRAINT ck_wms_ctr_type_nm CHECK (length(btrim(type_nm)) > 0),
    CONSTRAINT ck_wms_ctr_type_size CHECK (length_mm BETWEEN 50 AND 5000 AND width_mm BETWEEN 50 AND 5000 AND height_mm BETWEEN 10 AND 5000),
    CONSTRAINT ck_wms_ctr_type_slots CHECK (slot_cnt BETWEEN 1 AND 64),
    CONSTRAINT ck_wms_ctr_type_use_yn CHECK (use_yn IN ('Y', 'N'))
);
COMMENT ON TABLE wms_ctr_type IS '자동창고 용기 물리 유형 (docs/65 8.1) — 치수·칸 수';
CREATE TRIGGER tg_wms_ctr_type_no_delete BEFORE DELETE ON wms_ctr_type FOR EACH ROW EXECUTE FUNCTION hb_forbid_delete_master();

CREATE TABLE wms_ctr (
    ctr_id      bigint        GENERATED ALWAYS AS IDENTITY,
    comp_cd     varchar(20)   NOT NULL,
    asrs_id     bigint        NOT NULL,
    ctr_cd      varchar(30)   NOT NULL,
    type_id     bigint        NOT NULL,
    status      varchar(12)   NOT NULL DEFAULT 'EMPTY',
    exc_reason  varchar(30),
    use_yn      char(1)       NOT NULL DEFAULT 'Y',
    row_ver     integer       NOT NULL DEFAULT 0,
    created_at  timestamptz   NOT NULL DEFAULT now(),
    created_by  varchar(50)   NOT NULL,
    updated_at  timestamptz   NOT NULL DEFAULT now(),
    updated_by  varchar(50)   NOT NULL,
    CONSTRAINT pk_wms_ctr PRIMARY KEY (ctr_id),
    CONSTRAINT fk_wms_ctr_asrs FOREIGN KEY (asrs_id) REFERENCES wms_asrs (asrs_id),
    CONSTRAINT fk_wms_ctr_type FOREIGN KEY (type_id) REFERENCES wms_ctr_type (type_id),
    CONSTRAINT uq_wms_ctr_cd UNIQUE (comp_cd, ctr_cd),
    -- 스캔 키 CTR:{코드} — 내부 형식의 번호와 같은 글자 규칙(대문자)
    CONSTRAINT ck_wms_ctr_cd CHECK (ctr_cd ~ '^[A-Z0-9][A-Z0-9._-]{0,29}$'),
    CONSTRAINT ck_wms_ctr_status CHECK (status IN ('EMPTY', 'LOADED', 'IN_SYSTEM', 'QUARANTINE')),
    CONSTRAINT ck_wms_ctr_quarantine CHECK ((status = 'QUARANTINE') = (exc_reason IS NOT NULL)),
    CONSTRAINT ck_wms_ctr_use_yn CHECK (use_yn IN ('Y', 'N'))
);
CREATE INDEX ix_wms_ctr_asrs ON wms_ctr (comp_cd, asrs_id, status);
COMMENT ON TABLE wms_ctr IS '자동창고 개별 용기 (docs/65 8.1) — EMPTY → LOADED(내용 정의) → IN_SYSTEM(랙에 넣음) ⇄ QUARANTINE(격리, 출고 할당 제외)';

CREATE FUNCTION hb_guard_wms_ctr() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'UPDATE' AND (NEW.ctr_cd <> OLD.ctr_cd OR NEW.asrs_id <> OLD.asrs_id) THEN
        RAISE EXCEPTION 'container code and asrs are fixed' USING ERRCODE = '55000', CONSTRAINT = 'ck_wms_ctr_fixed';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM wms_asrs a WHERE a.asrs_id = NEW.asrs_id AND a.comp_cd = NEW.comp_cd)
       OR NOT EXISTS (SELECT 1 FROM wms_ctr_type t WHERE t.type_id = NEW.type_id AND t.comp_cd = NEW.comp_cd) THEN
        RAISE EXCEPTION 'container context is not of company %', NEW.comp_cd
            USING ERRCODE = '23503', CONSTRAINT = 'fk_wms_ctr_comp';
    END IF;
    RETURN NEW;
END
$$;
CREATE TRIGGER tg_wms_ctr_guard BEFORE INSERT OR UPDATE ON wms_ctr FOR EACH ROW EXECUTE FUNCTION hb_guard_wms_ctr();
CREATE TRIGGER tg_wms_ctr_no_delete BEFORE DELETE ON wms_ctr FOR EACH ROW EXECUTE FUNCTION hb_forbid_delete_master();
CREATE TRIGGER tg_wms_ctr_code_reason BEFORE INSERT OR UPDATE OF exc_reason ON wms_ctr
    FOR EACH ROW EXECUTE FUNCTION hb_check_code_col('WMS_ASRS_EXC_REASON', 'exc_reason');

-- 칸별 내용 사본 — 원장(inv_trx)이 진실(Q2), 이 표는 WES 와 맞출 용기·칸 차원
CREATE TABLE wms_ctr_content (
    content_id  bigint          GENERATED ALWAYS AS IDENTITY,
    comp_cd     varchar(20)     NOT NULL,
    ctr_id      bigint          NOT NULL,
    slot_no     smallint        NOT NULL,
    item_id     bigint          NOT NULL,
    lot_id      bigint,
    stock_status varchar(12)    NOT NULL DEFAULT 'AVAILABLE',
    qty         numeric(18,6)   NOT NULL,
    updated_at  timestamptz     NOT NULL DEFAULT now(),
    updated_by  varchar(50)     NOT NULL,
    CONSTRAINT pk_wms_ctr_content PRIMARY KEY (content_id),
    CONSTRAINT fk_wms_ctr_content_ctr FOREIGN KEY (ctr_id) REFERENCES wms_ctr (ctr_id),
    CONSTRAINT fk_wms_ctr_content_item FOREIGN KEY (item_id) REFERENCES mst_item (item_id),
    CONSTRAINT fk_wms_ctr_content_lot FOREIGN KEY (lot_id) REFERENCES lot_master (lot_id),
    CONSTRAINT ck_wms_ctr_content_slot CHECK (slot_no BETWEEN 1 AND 64),
    CONSTRAINT ck_wms_ctr_content_qty CHECK (qty > 0),
    CONSTRAINT ck_wms_ctr_content_status CHECK (stock_status IN ('AVAILABLE', 'QC', 'HOLD', 'BLOCKED'))
);
CREATE UNIQUE INDEX uq_wms_ctr_content ON wms_ctr_content (comp_cd, ctr_id, slot_no, item_id, coalesce(lot_id, 0), stock_status);
COMMENT ON TABLE wms_ctr_content IS '자동창고 용기 칸별 내용 사본 (docs/65 23.1) — 원장 칸은 늘리지 않는다(Q1). 같은 칸에 같은 품목 다른 LOT = 경고';

CREATE FUNCTION hb_guard_wms_ctr_content() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM wms_ctr c JOIN wms_ctr_type t ON t.type_id = c.type_id
                    WHERE c.ctr_id = NEW.ctr_id AND c.comp_cd = NEW.comp_cd AND NEW.slot_no <= t.slot_cnt)
       OR NOT EXISTS (SELECT 1 FROM mst_item i WHERE i.item_id = NEW.item_id AND i.comp_cd = NEW.comp_cd)
       OR (NEW.lot_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM lot_master l WHERE l.lot_id = NEW.lot_id AND l.comp_cd = NEW.comp_cd
                                                     AND l.item_id = NEW.item_id)) THEN
        RAISE EXCEPTION 'container content context is not of company % (or slot out of type)', NEW.comp_cd
            USING ERRCODE = '23503', CONSTRAINT = 'fk_wms_ctr_content_comp';
    END IF;
    RETURN NEW;
END
$$;
CREATE TRIGGER tg_wms_ctr_content_guard BEFORE INSERT OR UPDATE ON wms_ctr_content FOR EACH ROW EXECUTE FUNCTION hb_guard_wms_ctr_content();

-- ---------------------------------------------------------------------
-- 3. 입·출고 요청 / 라인
-- ---------------------------------------------------------------------
CREATE TABLE wms_asrs_req (
    req_id       bigint        GENERATED ALWAYS AS IDENTITY,
    comp_cd      varchar(20)   NOT NULL,
    asrs_id      bigint        NOT NULL,
    req_type     varchar(4)    NOT NULL,
    req_key      uuid          NOT NULL,
    send_state   varchar(10)   NOT NULL DEFAULT 'DRAFT',
    outbox_message_id uuid,
    claimed_at   timestamptz,
    sent_at      timestamptz,
    remark       varchar(500),
    row_ver      integer       NOT NULL DEFAULT 0,
    created_at   timestamptz   NOT NULL DEFAULT now(),
    created_by   varchar(50)   NOT NULL,
    updated_at   timestamptz   NOT NULL DEFAULT now(),
    updated_by   varchar(50)   NOT NULL,
    CONSTRAINT pk_wms_asrs_req PRIMARY KEY (req_id),
    CONSTRAINT fk_wms_asrs_req_asrs FOREIGN KEY (asrs_id) REFERENCES wms_asrs (asrs_id),
    -- 외부 요청 id = 우리가 만든 reqKey(송신기는 DB 를 쓰지 않으니 멱등 키를 우리가 정한다). 지운 요청 id 는 다시 쓰지 않는다
    CONSTRAINT uq_wms_asrs_req_key UNIQUE (comp_cd, req_key),
    CONSTRAINT ck_wms_asrs_req_type CHECK (req_type IN ('IN', 'OUT')),
    CONSTRAINT ck_wms_asrs_req_send CHECK (send_state IN ('DRAFT', 'QUEUED', 'SENT', 'CANCELLED'))
);
CREATE INDEX ix_wms_asrs_req_asrs ON wms_asrs_req (comp_cd, asrs_id, send_state, req_id);
COMMENT ON TABLE wms_asrs_req IS '자동창고 입·출고 요청 (docs/65 8.2) — 보내기 상태만 저장, 진행 상태는 라인 집계로 계산(직접 쓰지 않음)';

CREATE TABLE wms_asrs_req_line (
    line_id      bigint          GENERATED ALWAYS AS IDENTITY,
    comp_cd      varchar(20)     NOT NULL,
    req_id       bigint          NOT NULL,
    line_no      smallint        NOT NULL,
    ctr_id       bigint,
    item_id      bigint,
    lot_id       bigint,
    qty          numeric(18,6),
    done_qty     numeric(18,6)   NOT NULL DEFAULT 0,
    status       varchar(12)     NOT NULL DEFAULT 'NOT_STARTED',
    wes_status   varchar(20),
    last_reason  varchar(30),
    started_at   timestamptz,
    finished_at  timestamptz,
    row_ver      integer         NOT NULL DEFAULT 0,
    updated_at   timestamptz     NOT NULL DEFAULT now(),
    updated_by   varchar(50)     NOT NULL,
    CONSTRAINT pk_wms_asrs_req_line PRIMARY KEY (line_id),
    CONSTRAINT fk_wms_asrs_req_line_req FOREIGN KEY (req_id) REFERENCES wms_asrs_req (req_id),
    CONSTRAINT fk_wms_asrs_req_line_ctr FOREIGN KEY (ctr_id) REFERENCES wms_ctr (ctr_id),
    CONSTRAINT fk_wms_asrs_req_line_item FOREIGN KEY (item_id) REFERENCES mst_item (item_id),
    CONSTRAINT fk_wms_asrs_req_line_lot FOREIGN KEY (lot_id) REFERENCES lot_master (lot_id),
    CONSTRAINT uq_wms_asrs_req_line_no UNIQUE (comp_cd, req_id, line_no),
    CONSTRAINT ck_wms_asrs_req_line_no CHECK (line_no BETWEEN 1 AND 999),
    CONSTRAINT ck_wms_asrs_req_line_status CHECK (status IN ('NOT_STARTED', 'IN_PROGRESS', 'DONE', 'FAILED', 'CANCELLED')),
    CONSTRAINT ck_wms_asrs_req_line_qty CHECK ((qty IS NULL OR qty > 0) AND done_qty >= 0),
    CONSTRAINT ck_wms_asrs_req_line_wes CHECK (wes_status IS NULL OR wes_status ~ '^[A-Z][A-Z0-9_]{0,19}$')
);
-- 용기 하나에 활성 입고 라인 하나(같은 용기를 두 번 넣지 않는다)
CREATE UNIQUE INDEX uq_wms_asrs_req_line_ctr ON wms_asrs_req_line (comp_cd, ctr_id) WHERE ctr_id IS NOT NULL
    AND status IN ('NOT_STARTED', 'IN_PROGRESS');
COMMENT ON TABLE wms_asrs_req_line IS '자동창고 요청 라인 (docs/65 8.2) — 입고 라인 = 용기 1, 출고 라인 = 품목(+LOT)·수량';

CREATE FUNCTION hb_guard_wms_asrs_req_line() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM wms_asrs_req r WHERE r.req_id = NEW.req_id AND r.comp_cd = NEW.comp_cd)
       OR (NEW.ctr_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM wms_ctr c JOIN wms_asrs_req r ON r.asrs_id = c.asrs_id
                                                   WHERE c.ctr_id = NEW.ctr_id AND c.comp_cd = NEW.comp_cd AND r.req_id = NEW.req_id))
       OR (NEW.item_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM mst_item i WHERE i.item_id = NEW.item_id AND i.comp_cd = NEW.comp_cd)) THEN
        RAISE EXCEPTION 'request line context is not of company % (or container of another asrs)', NEW.comp_cd
            USING ERRCODE = '23503', CONSTRAINT = 'fk_wms_asrs_req_line_comp';
    END IF;
    -- 끝난 라인은 되돌리지 않는다(늦게 온 옛 상태는 사건 STALE 로만)
    IF TG_OP = 'UPDATE' AND OLD.status IN ('DONE', 'FAILED', 'CANCELLED') AND NEW.status <> OLD.status THEN
        RAISE EXCEPTION 'request line % is finished (%)', OLD.line_id, OLD.status
            USING ERRCODE = '55000', CONSTRAINT = 'ck_wms_asrs_req_line_final';
    END IF;
    RETURN NEW;
END
$$;
CREATE TRIGGER tg_wms_asrs_req_line_guard BEFORE INSERT OR UPDATE ON wms_asrs_req_line FOR EACH ROW EXECUTE FUNCTION hb_guard_wms_asrs_req_line();
CREATE TRIGGER tg_wms_asrs_req_line_code_reason BEFORE INSERT OR UPDATE OF last_reason ON wms_asrs_req_line
    FOR EACH ROW EXECUTE FUNCTION hb_check_code_col('WMS_ASRS_EXC_REASON', 'last_reason');

-- ---------------------------------------------------------------------
-- 4. 사건 원장 (추가만)
-- ---------------------------------------------------------------------
CREATE TABLE wms_asrs_event (
    event_id        bigint        GENERATED ALWAYS AS IDENTITY,
    comp_cd         varchar(20)   NOT NULL,
    asrs_id         bigint        NOT NULL,
    req_id          bigint,
    line_id         bigint,
    ctr_id          bigint,
    event_cd        varchar(12)   NOT NULL,
    wes_status      varchar(20),
    reason_cd       varchar(30),
    detail          varchar(500),
    inbound_id      bigint,
    move_grp_id     uuid,
    src             varchar(10)   NOT NULL,
    occurred_at     timestamptz   NOT NULL,
    recorded_at     timestamptz   NOT NULL DEFAULT now(),
    entry_method    varchar(10)   NOT NULL DEFAULT 'AUTO',
    recorded_by     varchar(50)   NOT NULL,
    performed_by    bigint,
    reversal_of_id  bigint,
    CONSTRAINT pk_wms_asrs_event PRIMARY KEY (event_id),
    CONSTRAINT fk_wms_asrs_event_asrs FOREIGN KEY (asrs_id) REFERENCES wms_asrs (asrs_id),
    CONSTRAINT fk_wms_asrs_event_req FOREIGN KEY (req_id) REFERENCES wms_asrs_req (req_id),
    CONSTRAINT fk_wms_asrs_event_line FOREIGN KEY (line_id) REFERENCES wms_asrs_req_line (line_id),
    CONSTRAINT fk_wms_asrs_event_ctr FOREIGN KEY (ctr_id) REFERENCES wms_ctr (ctr_id),
    CONSTRAINT ck_wms_asrs_event_cd CHECK (event_cd IN ('SENT', 'CLAIMED', 'STATUS', 'POSTED', 'FAILED', 'STALE', 'STALE_REQ', 'EXCHANGE',
        'QUARANTINE', 'CANCELLED')),
    CONSTRAINT ck_wms_asrs_event_wes CHECK (wes_status IS NULL OR wes_status ~ '^[A-Z][A-Z0-9_]{0,19}$'),
    CONSTRAINT ck_wms_asrs_event_src CHECK (src IN ('DIRECT', 'EDGE', 'USER', 'SYSTEM')),
    CONSTRAINT ck_wms_asrs_event_method CHECK (entry_method IN ('AUTO', 'SCAN', 'KEY')),
    CONSTRAINT ck_wms_asrs_event_no_reversal CHECK (reversal_of_id IS NULL AND performed_by IS NULL)
);
CREATE INDEX ix_wms_asrs_event_req ON wms_asrs_event (comp_cd, req_id, event_id);
CREATE INDEX ix_wms_asrs_event_ctr ON wms_asrs_event (comp_cd, ctr_id, event_id);
CREATE INDEX ix_wms_asrs_event_time ON wms_asrs_event (comp_cd, occurred_at);
COMMENT ON TABLE wms_asrs_event IS '자동창고 사건 원장 (docs/65 23장) — 보냄·가져감·WES 상태·원장 반영·실패·늦음·오래 머묾·경계 교환·격리·취소';

CREATE FUNCTION hb_guard_wms_asrs_event() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM wms_asrs a WHERE a.asrs_id = NEW.asrs_id AND a.comp_cd = NEW.comp_cd)
       OR (NEW.req_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM wms_asrs_req r WHERE r.req_id = NEW.req_id AND r.comp_cd = NEW.comp_cd))
       OR (NEW.ctr_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM wms_ctr c WHERE c.ctr_id = NEW.ctr_id AND c.comp_cd = NEW.comp_cd)) THEN
        RAISE EXCEPTION 'asrs event context is not of company %', NEW.comp_cd
            USING ERRCODE = '23503', CONSTRAINT = 'fk_wms_asrs_event_comp';
    END IF;
    RETURN NEW;
END
$$;
CREATE TRIGGER tg_wms_asrs_event_guard BEFORE INSERT ON wms_asrs_event FOR EACH ROW EXECUTE FUNCTION hb_guard_wms_asrs_event();
CREATE TRIGGER tg_wms_asrs_event_ledger BEFORE UPDATE OR DELETE ON wms_asrs_event FOR EACH ROW EXECUTE FUNCTION hb_forbid_update_delete();
CREATE TRIGGER tg_wms_asrs_event_code_reason BEFORE INSERT ON wms_asrs_event
    FOR EACH ROW EXECUTE FUNCTION hb_check_code_col('WMS_ASRS_EXC_REASON', 'reason_cd');

-- ---------------------------------------------------------------------
-- 5. 감사 · RLS · 권한 · 화면 · 보존
-- ---------------------------------------------------------------------
CREATE TRIGGER tg_audit AFTER INSERT OR UPDATE OR DELETE ON wms_asrs FOR EACH ROW EXECUTE FUNCTION hb_audit('asrs_id', '', '');
CREATE TRIGGER tg_audit AFTER INSERT OR UPDATE OR DELETE ON wms_ctr_type FOR EACH ROW EXECUTE FUNCTION hb_audit('type_id', '', '');
CREATE TRIGGER tg_audit AFTER INSERT OR UPDATE OR DELETE ON wms_ctr FOR EACH ROW EXECUTE FUNCTION hb_audit('ctr_id', 'row_ver,updated_at,updated_by', '');
CREATE TRIGGER tg_audit AFTER INSERT OR UPDATE OR DELETE ON wms_ctr_content FOR EACH ROW EXECUTE FUNCTION hb_audit('content_id', 'updated_at,updated_by', '');
CREATE TRIGGER tg_audit AFTER INSERT OR UPDATE OR DELETE ON wms_asrs_req FOR EACH ROW EXECUTE FUNCTION hb_audit('req_id', 'row_ver,updated_at,updated_by', '');
CREATE TRIGGER tg_audit AFTER INSERT OR UPDATE OR DELETE ON wms_asrs_req_line FOR EACH ROW
    EXECUTE FUNCTION hb_audit('line_id', 'status,done_qty,wes_status,last_reason,started_at,finished_at,row_ver,updated_at,updated_by', '');

ALTER TABLE wms_asrs ENABLE ROW LEVEL SECURITY;
ALTER TABLE wms_asrs FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON wms_asrs USING (comp_cd = hb_current_comp()) WITH CHECK (comp_cd = hb_current_comp());
ALTER TABLE wms_ctr_type ENABLE ROW LEVEL SECURITY;
ALTER TABLE wms_ctr_type FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON wms_ctr_type USING (comp_cd = hb_current_comp()) WITH CHECK (comp_cd = hb_current_comp());
ALTER TABLE wms_ctr ENABLE ROW LEVEL SECURITY;
ALTER TABLE wms_ctr FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON wms_ctr USING (comp_cd = hb_current_comp()) WITH CHECK (comp_cd = hb_current_comp());
ALTER TABLE wms_ctr_content ENABLE ROW LEVEL SECURITY;
ALTER TABLE wms_ctr_content FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON wms_ctr_content USING (comp_cd = hb_current_comp()) WITH CHECK (comp_cd = hb_current_comp());
ALTER TABLE wms_asrs_req ENABLE ROW LEVEL SECURITY;
ALTER TABLE wms_asrs_req FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON wms_asrs_req USING (comp_cd = hb_current_comp()) WITH CHECK (comp_cd = hb_current_comp());
ALTER TABLE wms_asrs_req_line ENABLE ROW LEVEL SECURITY;
ALTER TABLE wms_asrs_req_line FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON wms_asrs_req_line USING (comp_cd = hb_current_comp()) WITH CHECK (comp_cd = hb_current_comp());
ALTER TABLE wms_asrs_event ENABLE ROW LEVEL SECURITY;
ALTER TABLE wms_asrs_event FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON wms_asrs_event USING (comp_cd = hb_current_comp()) WITH CHECK (comp_cd = hb_current_comp());

REVOKE DELETE, TRUNCATE ON wms_asrs, wms_ctr_type, wms_ctr, wms_asrs_req, wms_asrs_req_line FROM ${app_role};
-- 내용 사본은 칸을 비울 때 행을 지운다(감사 트리거가 남긴다) — TRUNCATE 만 막음
REVOKE TRUNCATE ON wms_ctr_content FROM ${app_role};
REVOKE UPDATE, DELETE, TRUNCATE ON wms_asrs_event FROM ${app_role};

INSERT INTO sys_prog (prog_cd, prog_nm_key, view_path, module_cd, actions, sort_no) VALUES
    ('wms-asrs', 'prog.wmsAsrs', 'wms/asrs', 'WMS', ARRAY['SEARCH','SAVE']::varchar(20)[], 430);

INSERT INTO sys_retention_table (table_nm, class_cd) VALUES
    ('wms_asrs', 'MASTER'), ('wms_ctr_type', 'MASTER'), ('wms_ctr', 'MASTER'), ('wms_ctr_content', 'MASTER'),
    ('wms_asrs_req', 'QUALITY_RECORD'), ('wms_asrs_req_line', 'QUALITY_RECORD'), ('wms_asrs_event', 'QUALITY_RECORD');
