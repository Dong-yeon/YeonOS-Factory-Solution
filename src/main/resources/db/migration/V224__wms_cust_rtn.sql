-- =====================================================================
-- WMS 고객 반품 입고 (docs/59, 사용자 결정 R-1~R-8 2026-11-01) — 임시 번호(docs/29 2.2)
--
-- - wms_cust_rtn: 반품 입고 기록(근거 = 받은 사실, R-1). 출하 행·LOT 근거(R-7 근거 없는 반품 거부), 같은 LOT 으로 받음(계보 유지)
--   상태 RECEIVED → CANCELLED(취소 = QMS NCR 이 OPEN 일 때만, R-6 — 서비스가 NcrPort.cancelForClaim 으로)
--   ncr_id·ncr_no = QMS NCR 스냅숏(FK 없음 — srm_return 과 같은 방식, 모듈 경계), capa_no = 고객 클레임 번호를 사람이 적음(R-8)
-- - 원장 RCV_RTN(+, 들어옴): 출처 CUST_RTN·src_id = 반품 id 필수. 상태는 서비스가 BLOCKED 고정(판정 전 가용 금지 — QMS NCR 처분이 푼다)
-- - 영업 출하 수량(sal_order_line.shipped_qty)은 바꾸지 않는다(R-3) — 남의 표 변경 없음
-- - 출하 근거 FK 는 (comp_cd, ship_id) — 운송 정보(V219)와 같은 회사+id FK(uq_wms_ship_comp_id)
-- =====================================================================

INSERT INTO sys_code_grp (grp_cd, grp_nm_key, comp_ext_yn) VALUES
    ('WMS_CUST_RTN_STATUS', 'code.grp.WMS_CUST_RTN_STATUS', 'N'),
    ('WMS_CUST_RTN_REASON', 'code.grp.WMS_CUST_RTN_REASON', 'N');
INSERT INTO sys_code (grp_cd, code, code_nm_key, sort_no) VALUES
    ('WMS_CUST_RTN_STATUS', 'RECEIVED',  'code.WMS_CUST_RTN_STATUS.RECEIVED',  1),
    ('WMS_CUST_RTN_STATUS', 'CANCELLED', 'code.WMS_CUST_RTN_STATUS.CANCELLED', 2),
    ('WMS_CUST_RTN_REASON', 'QUALITY',    'code.WMS_CUST_RTN_REASON.QUALITY',    1),
    ('WMS_CUST_RTN_REASON', 'WRONG_SHIP', 'code.WMS_CUST_RTN_REASON.WRONG_SHIP', 2),
    ('WMS_CUST_RTN_REASON', 'OVER_QTY',   'code.WMS_CUST_RTN_REASON.OVER_QTY',   3),
    ('WMS_CUST_RTN_REASON', 'OTHER',      'code.WMS_CUST_RTN_REASON.OTHER',      4),
    ('INV_TRX_TYPE', 'RCV_RTN', 'code.INV_TRX_TYPE.RCV_RTN', 31);

-- ---------------------------------------------------------------------
-- 1. 원장 유형 판 교체(V181 판 + RCV_RTN)
-- ---------------------------------------------------------------------
ALTER TABLE inv_trx DROP CONSTRAINT ck_inv_trx_type;
ALTER TABLE inv_trx ADD CONSTRAINT ck_inv_trx_type CHECK (trx_type IN ('OPENING', 'ADJ_IN', 'ADJ_OUT', 'MOVE_OUT', 'MOVE_IN', 'STATUS_OUT',
                                                                        'STATUS_IN', 'COUNT_ADJ', 'RCV_PRD', 'RCV_MAT', 'ISSUE_WO',
                                                                        'CONV_OUT', 'CONV_IN', 'RCV_PO', 'SHIP', 'ISSUE_MRO', 'MRO_RTN',
                                                                        'RTN_SUP', 'SCRAP', 'RCV_RTN'));
ALTER TABLE inv_trx DROP CONSTRAINT ck_inv_trx_sign;
ALTER TABLE inv_trx ADD CONSTRAINT ck_inv_trx_sign CHECK (
    trx_type = 'COUNT_ADJ'
    OR (trx_type IN ('OPENING', 'ADJ_IN', 'MOVE_IN', 'STATUS_IN', 'RCV_PRD', 'RCV_MAT', 'CONV_IN', 'RCV_PO', 'MRO_RTN', 'RCV_RTN')
        AND (qty > 0) = (reversal_of_id IS NULL))
    OR (trx_type IN ('ADJ_OUT', 'MOVE_OUT', 'STATUS_OUT', 'ISSUE_WO', 'CONV_OUT', 'SHIP', 'ISSUE_MRO', 'RTN_SUP', 'SCRAP')
        AND (qty < 0) = (reversal_of_id IS NULL)));
-- 고객 반품 입고는 반품 기록에서만(사람의 조정 입고와 섞지 않음)
ALTER TABLE inv_trx ADD CONSTRAINT ck_inv_trx_cust_rtn_src CHECK (
    trx_type <> 'RCV_RTN' OR (src_type = 'CUST_RTN' AND src_id IS NOT NULL));

-- ---------------------------------------------------------------------
-- 2. 반품 입고 기록
-- ---------------------------------------------------------------------
CREATE TABLE wms_cust_rtn (
    rtn_id         bigint         GENERATED ALWAYS AS IDENTITY,
    comp_cd        varchar(20)    NOT NULL,
    rtn_no         varchar(40)    NOT NULL,
    plant_cd       varchar(20)    NOT NULL,
    cust_id        bigint         NOT NULL,
    ship_id        bigint         NOT NULL,
    ship_line_id   bigint         NOT NULL,
    item_id        bigint         NOT NULL,
    lot_id         bigint,
    qty            numeric(18,6)  NOT NULL,
    unit_cd        varchar(20)    NOT NULL,
    reason_cd      varchar(20)    NOT NULL,
    node_id        bigint         NOT NULL,
    remark         varchar(500),
    capa_no        varchar(40),
    status         varchar(10)    NOT NULL DEFAULT 'RECEIVED',
    ncr_id         bigint,
    ncr_no         varchar(40),
    client_req_id  varchar(64)    NOT NULL,
    received_at    timestamptz    NOT NULL,
    received_by    varchar(50)    NOT NULL,
    performed_by   bigint,
    cancel_reason  varchar(500),
    cancelled_at   timestamptz,
    cancelled_by   varchar(50),
    row_ver        integer        NOT NULL DEFAULT 0,
    created_at     timestamptz    NOT NULL DEFAULT now(),
    created_by     varchar(50)    NOT NULL,
    updated_at     timestamptz    NOT NULL DEFAULT now(),
    updated_by     varchar(50)    NOT NULL,
    CONSTRAINT pk_wms_cust_rtn PRIMARY KEY (rtn_id),
    CONSTRAINT uq_wms_cust_rtn_no UNIQUE (comp_cd, rtn_no),
    CONSTRAINT uq_wms_cust_rtn_req UNIQUE (comp_cd, client_req_id),
    CONSTRAINT fk_wms_cust_rtn_plant FOREIGN KEY (comp_cd, plant_cd) REFERENCES sys_plant (comp_cd, plant_cd),
    CONSTRAINT fk_wms_cust_rtn_ship FOREIGN KEY (comp_cd, ship_id) REFERENCES wms_ship (comp_cd, ship_id),
    CONSTRAINT fk_wms_cust_rtn_ship_line FOREIGN KEY (ship_line_id) REFERENCES wms_ship_line (ship_line_id),
    CONSTRAINT fk_wms_cust_rtn_cust FOREIGN KEY (cust_id) REFERENCES mst_cust (cust_id),
    CONSTRAINT fk_wms_cust_rtn_item FOREIGN KEY (item_id) REFERENCES mst_item (item_id),
    CONSTRAINT fk_wms_cust_rtn_lot FOREIGN KEY (lot_id) REFERENCES lot_master (lot_id),
    CONSTRAINT fk_wms_cust_rtn_node FOREIGN KEY (node_id) REFERENCES mst_equip_hier (node_id),
    CONSTRAINT fk_wms_cust_rtn_worker FOREIGN KEY (performed_by) REFERENCES mst_worker (worker_id),
    CONSTRAINT ck_wms_cust_rtn_qty CHECK (qty > 0),
    CONSTRAINT ck_wms_cust_rtn_status CHECK (status IN ('RECEIVED', 'CANCELLED')),
    CONSTRAINT ck_wms_cust_rtn_cancel CHECK ((status = 'CANCELLED') = (coalesce(length(btrim(cancel_reason)), 0) > 0
                                              AND cancelled_at IS NOT NULL AND cancelled_by IS NOT NULL)),
    CONSTRAINT ck_wms_cust_rtn_ncr CHECK ((ncr_id IS NULL) = (ncr_no IS NULL)),
    CONSTRAINT ck_wms_cust_rtn_capa CHECK (capa_no IS NULL OR length(btrim(capa_no)) > 0),
    CONSTRAINT ck_wms_cust_rtn_reason CHECK (reason_cd ~ '^[A-Z0-9_]{1,20}$')
);
COMMENT ON TABLE wms_cust_rtn IS '고객 반품 입고 (docs/59) — 출하 행·LOT 근거, 원장 RCV_RTN(BLOCKED), QMS NCR(CLAIM) 스냅숏. 취소 = NCR OPEN 일 때만';
COMMENT ON COLUMN wms_cust_rtn.lot_id IS '출하 피킹의 LOT 그대로(LOT 비관리 품목은 null — NCR 없음)';
COMMENT ON COLUMN wms_cust_rtn.qty IS '받은 수량(품목 기본 단위)';
COMMENT ON COLUMN wms_cust_rtn.ncr_id IS 'QMS NCR id 스냅숏(FK 없음 — 모듈 경계). QMS 없는 설치본·LOT 비관리 품목은 null';
COMMENT ON COLUMN wms_cust_rtn.capa_no IS '고객 클레임(CAPA) 번호 — 사람이 적음(R-8), 검사 없음';
CREATE INDEX ix_wms_cust_rtn_ship_line ON wms_cust_rtn (comp_cd, ship_line_id, lot_id) WHERE status = 'RECEIVED';
CREATE INDEX ix_wms_cust_rtn_plant ON wms_cust_rtn (comp_cd, plant_cd, received_at);

CREATE TRIGGER tg_audit AFTER INSERT OR UPDATE OR DELETE ON wms_cust_rtn FOR EACH ROW EXECUTE FUNCTION hb_audit('rtn_id', '', '');

ALTER TABLE wms_cust_rtn ENABLE ROW LEVEL SECURITY;
ALTER TABLE wms_cust_rtn FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON wms_cust_rtn USING (comp_cd = hb_current_comp()) WITH CHECK (comp_cd = hb_current_comp());
REVOKE DELETE, TRUNCATE ON wms_cust_rtn FROM ${app_role};
INSERT INTO sys_retention_table (table_nm, class_cd) VALUES ('wms_cust_rtn', 'QUALITY_RECORD');

-- 반품 원장 찾기(취소 때 역분개할 행)
CREATE INDEX ix_inv_trx_cust_rtn ON inv_trx (comp_cd, src_id) WHERE src_type = 'CUST_RTN';

-- ---------------------------------------------------------------------
-- 3. 화면
-- ---------------------------------------------------------------------
INSERT INTO sys_prog (prog_cd, prog_nm_key, view_path, module_cd, actions, sort_no) VALUES
    ('wms-cust-rtn', 'prog.wmsCustRtn', 'wms/cust-rtn', 'WMS', ARRAY['SEARCH','SAVE','APPROVE']::varchar(20)[], 466);
