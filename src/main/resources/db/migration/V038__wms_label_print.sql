-- =====================================================================
-- V038 라벨 템플릿·프린터·인쇄 큐 (P4 WMS 1부 W2 — docs/30 4장 W2, 결정 D6)
--
-- - wms_printer: 네트워크 라벨 프린터(공장별, ZPL, TCP). 주소는 사설 대역만 — 송신기가 보낼 때마다 해석해 다시 검사
-- - wms_label_tpl: 라벨 유형별 ZPL 템플릿 + {{자리표시자}}. 유형마다 기본 1건(부분 유일)
-- - wms_print_job: 인쇄 요청 기록(추가만). 상태 칸이 없다 — sys_outbox.status 를 message_id 로 이어 본다(송신기는 DB 를 쓰지 않는다, 01 5.8)
-- - 정책 WMS_PRINTER_PORTS: 프린터로 보낼 수 있는 TCP 포트
-- ※ 병행 개발(docs/29 6장): main 에 늦게 들어가는 쪽이 이 번호를 다시 매긴다
-- =====================================================================

INSERT INTO sys_policy_def (policy_key, value_type, default_value, allowed_values, min_value, max_value, plant_scope_yn, desc_key) VALUES
    -- 9100 = 원시 인쇄(Zebra·대부분 라벨 프린터), 6101 = 일부 프린터 설정 포트. 그 밖의 포트로는 보내지 않는다(서버를 내부망 포트 스캐너로 쓰지 못하게)
    ('WMS_PRINTER_PORTS', 'STRING', '9100,9101,9102,9103', NULL, NULL, NULL, 'N', 'policy.WMS_PRINTER_PORTS');

INSERT INTO sys_code_grp (grp_cd, grp_nm_key, comp_ext_yn) VALUES
    ('WMS_LABEL_TYPE', 'code.grp.WMS_LABEL_TYPE', 'N'),
    ('WMS_PRINTER_USAGE', 'code.grp.WMS_PRINTER_USAGE', 'N'),
    ('WMS_PRINT_STATUS', 'code.grp.WMS_PRINT_STATUS', 'N');
INSERT INTO sys_code (grp_cd, code, code_nm_key, sort_no) VALUES
    ('WMS_LABEL_TYPE', 'LOC',    'code.WMS_LABEL_TYPE.LOC',    1),
    ('WMS_LABEL_TYPE', 'ITEM',   'code.WMS_LABEL_TYPE.ITEM',   2),
    ('WMS_LABEL_TYPE', 'LOT',    'code.WMS_LABEL_TYPE.LOT',    3),
    ('WMS_LABEL_TYPE', 'PALLET', 'code.WMS_LABEL_TYPE.PALLET', 4),
    ('WMS_LABEL_TYPE', 'SHIP',   'code.WMS_LABEL_TYPE.SHIP',   5),
    ('WMS_PRINT_STATUS', 'NEW',     'code.WMS_PRINT_STATUS.NEW',     1),
    ('WMS_PRINT_STATUS', 'SENDING', 'code.WMS_PRINT_STATUS.SENDING', 2),
    ('WMS_PRINT_STATUS', 'SENT',    'code.WMS_PRINT_STATUS.SENT',    3),
    ('WMS_PRINT_STATUS', 'FAIL',    'code.WMS_PRINT_STATUS.FAIL',    4),
    ('WMS_PRINTER_USAGE', 'ANY',    'code.WMS_PRINTER_USAGE.ANY',    1),
    ('WMS_PRINTER_USAGE', 'LOC',    'code.WMS_PRINTER_USAGE.LOC',    2),
    ('WMS_PRINTER_USAGE', 'ITEM',   'code.WMS_PRINTER_USAGE.ITEM',   3),
    ('WMS_PRINTER_USAGE', 'LOT',    'code.WMS_PRINTER_USAGE.LOT',    4),
    ('WMS_PRINTER_USAGE', 'PALLET', 'code.WMS_PRINTER_USAGE.PALLET', 5),
    ('WMS_PRINTER_USAGE', 'SHIP',   'code.WMS_PRINTER_USAGE.SHIP',   6);

-- ---------------------------------------------------------------------
-- 1. 프린터
-- ---------------------------------------------------------------------
CREATE TABLE wms_printer (
    printer_id  bigint        GENERATED ALWAYS AS IDENTITY,
    comp_cd     varchar(20)   NOT NULL,
    plant_cd    varchar(20)   NOT NULL,
    printer_cd  varchar(20)   NOT NULL,
    printer_nm  varchar(100)  NOT NULL,
    lang        varchar(10)   NOT NULL DEFAULT 'ZPL',
    host        varchar(253)  NOT NULL,
    port        integer       NOT NULL DEFAULT 9100,
    dpi         smallint      NOT NULL DEFAULT 203,
    usage       varchar(10)   NOT NULL DEFAULT 'ANY',
    use_yn      char(1)       NOT NULL DEFAULT 'Y',
    remark      varchar(500),
    row_ver     integer       NOT NULL DEFAULT 0,
    created_at  timestamptz   NOT NULL DEFAULT now(),
    created_by  varchar(50)   NOT NULL,
    updated_at  timestamptz   NOT NULL DEFAULT now(),
    updated_by  varchar(50)   NOT NULL,
    CONSTRAINT pk_wms_printer PRIMARY KEY (printer_id),
    CONSTRAINT fk_wms_printer_plant FOREIGN KEY (comp_cd, plant_cd) REFERENCES sys_plant (comp_cd, plant_cd),
    CONSTRAINT uq_wms_printer_cd UNIQUE (comp_cd, plant_cd, printer_cd),
    CONSTRAINT ck_wms_printer_cd CHECK (printer_cd ~ '^[A-Z0-9][A-Z0-9_-]{0,19}$'),
    CONSTRAINT ck_wms_printer_nm CHECK (length(btrim(printer_nm)) > 0),
    CONSTRAINT ck_wms_printer_lang CHECK (lang IN ('ZPL')),
    -- 이름 또는 IP 글자만(공백·스킴·경로 금지). 사설 대역 여부는 서비스·송신기가(이름은 보낼 때 해석)
    CONSTRAINT ck_wms_printer_host CHECK (host ~ '^[A-Za-z0-9:.\-]{1,253}$'),
    CONSTRAINT ck_wms_printer_port CHECK (port BETWEEN 1 AND 65535),
    CONSTRAINT ck_wms_printer_dpi CHECK (dpi IN (203, 300, 600)),
    CONSTRAINT ck_wms_printer_usage CHECK (usage IN ('ANY', 'LOC', 'ITEM', 'LOT', 'PALLET', 'SHIP')),
    CONSTRAINT ck_wms_printer_use_yn CHECK (use_yn IN ('Y', 'N'))
);
COMMENT ON TABLE wms_printer IS '네트워크 라벨 프린터 (docs/30 W2, D6). 서버가 TCP 로 ZPL 을 보낸다(설치형). SaaS 인쇄 에이전트는 2부';
CREATE TRIGGER tg_wms_printer_no_delete BEFORE DELETE ON wms_printer FOR EACH ROW EXECUTE FUNCTION hb_forbid_delete_master();

-- ---------------------------------------------------------------------
-- 2. 라벨 템플릿
-- ---------------------------------------------------------------------
CREATE TABLE wms_label_tpl (
    tpl_id      bigint        GENERATED ALWAYS AS IDENTITY,
    comp_cd     varchar(20)   NOT NULL,
    label_type  varchar(10)   NOT NULL,
    tpl_cd      varchar(20)   NOT NULL,
    tpl_nm      varchar(100)  NOT NULL,
    width_mm    numeric(5,1)  NOT NULL,
    height_mm   numeric(5,1)  NOT NULL,
    dpi         smallint      NOT NULL DEFAULT 203,
    body        text          NOT NULL,
    default_yn  char(1)       NOT NULL DEFAULT 'N',
    use_yn      char(1)       NOT NULL DEFAULT 'Y',
    remark      varchar(500),
    row_ver     integer       NOT NULL DEFAULT 0,
    created_at  timestamptz   NOT NULL DEFAULT now(),
    created_by  varchar(50)   NOT NULL,
    updated_at  timestamptz   NOT NULL DEFAULT now(),
    updated_by  varchar(50)   NOT NULL,
    CONSTRAINT pk_wms_label_tpl PRIMARY KEY (tpl_id),
    CONSTRAINT fk_wms_label_tpl_comp FOREIGN KEY (comp_cd) REFERENCES sys_comp (comp_cd),
    CONSTRAINT uq_wms_label_tpl_cd UNIQUE (comp_cd, tpl_cd),
    CONSTRAINT ck_wms_label_tpl_cd CHECK (tpl_cd ~ '^[A-Z0-9][A-Z0-9_-]{0,19}$'),
    CONSTRAINT ck_wms_label_tpl_nm CHECK (length(btrim(tpl_nm)) > 0),
    CONSTRAINT ck_wms_label_tpl_type CHECK (label_type IN ('LOC', 'ITEM', 'LOT', 'PALLET', 'SHIP')),
    CONSTRAINT ck_wms_label_tpl_size CHECK (width_mm BETWEEN 10 AND 300 AND height_mm BETWEEN 10 AND 300),
    CONSTRAINT ck_wms_label_tpl_dpi CHECK (dpi IN (203, 300, 600)),
    CONSTRAINT ck_wms_label_tpl_body CHECK (length(body) BETWEEN 1 AND 32768),
    CONSTRAINT ck_wms_label_tpl_default CHECK (default_yn IN ('Y', 'N') AND (default_yn = 'N' OR use_yn = 'Y')),
    CONSTRAINT ck_wms_label_tpl_use_yn CHECK (use_yn IN ('Y', 'N'))
);
-- 유형마다 기본 템플릿 1건 — 기본 바꾸기는 서비스가 같은 트랜잭션에서 이전 기본을 내린다(동시에 둘이면 이 인덱스가 거부)
CREATE UNIQUE INDEX uq_wms_label_tpl_default ON wms_label_tpl (comp_cd, label_type) WHERE default_yn = 'Y';
COMMENT ON TABLE wms_label_tpl IS '라벨 템플릿 — ZPL 본문 + {{자리표시자}}(유형별 허용 목록, ZplRenderer). 값은 렌더 때 ^FH 로 이스케이프';
COMMENT ON COLUMN wms_label_tpl.body IS 'ZPL (^XA … ^XZ). 자리표시자는 ^FD…^FS 안에만. 제어문자 바꾸기(^CC ^CT ^CD)·설정 저장(^JU)·^FH 는 쓰지 않는다';

CREATE FUNCTION hb_guard_wms_label_tpl() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'UPDATE' AND (NEW.label_type <> OLD.label_type OR NEW.tpl_cd <> OLD.tpl_cd) THEN
        RAISE EXCEPTION 'label template type and code are fixed' USING ERRCODE = '55000', CONSTRAINT = 'ck_wms_label_tpl_fixed';
    END IF;
    RETURN NEW;
END
$$;
CREATE TRIGGER tg_wms_label_tpl_guard BEFORE UPDATE ON wms_label_tpl FOR EACH ROW EXECUTE FUNCTION hb_guard_wms_label_tpl();
CREATE TRIGGER tg_wms_label_tpl_no_delete BEFORE DELETE ON wms_label_tpl FOR EACH ROW EXECUTE FUNCTION hb_forbid_delete_master();

-- ---------------------------------------------------------------------
-- 3. 인쇄 작업 (추가만)
-- ---------------------------------------------------------------------
CREATE TABLE wms_print_job (
    job_id             bigint        GENERATED ALWAYS AS IDENTITY,
    comp_cd            varchar(20)   NOT NULL,
    plant_cd           varchar(20)   NOT NULL,
    printer_id         bigint        NOT NULL,
    tpl_id             bigint        NOT NULL,
    label_type         varchar(10)   NOT NULL,
    biz_key            varchar(100)  NOT NULL,
    label_values       jsonb         NOT NULL DEFAULT '{}'::jsonb,
    zpl                text          NOT NULL,
    zpl_sha256         char(64)      NOT NULL,
    copies             smallint      NOT NULL DEFAULT 1,
    test_yn            char(1)       NOT NULL DEFAULT 'N',
    outbox_message_id  uuid          NOT NULL,
    reprint_of_id      bigint,
    reprint_reason     varchar(200),
    requested_by       varchar(50)   NOT NULL,
    requested_at       timestamptz   NOT NULL DEFAULT now(),
    CONSTRAINT pk_wms_print_job PRIMARY KEY (job_id),
    CONSTRAINT fk_wms_print_job_printer FOREIGN KEY (printer_id) REFERENCES wms_printer (printer_id),
    CONSTRAINT fk_wms_print_job_tpl FOREIGN KEY (tpl_id) REFERENCES wms_label_tpl (tpl_id),
    CONSTRAINT fk_wms_print_job_reprint FOREIGN KEY (reprint_of_id) REFERENCES wms_print_job (job_id),
    CONSTRAINT fk_wms_print_job_plant FOREIGN KEY (comp_cd, plant_cd) REFERENCES sys_plant (comp_cd, plant_cd),
    CONSTRAINT uq_wms_print_job_message UNIQUE (comp_cd, outbox_message_id),
    CONSTRAINT ck_wms_print_job_type CHECK (label_type IN ('LOC', 'ITEM', 'LOT', 'PALLET', 'SHIP')),
    CONSTRAINT ck_wms_print_job_copies CHECK (copies BETWEEN 1 AND 99),
    CONSTRAINT ck_wms_print_job_test CHECK (test_yn IN ('Y', 'N')),
    CONSTRAINT ck_wms_print_job_zpl CHECK (length(zpl) BETWEEN 1 AND 49152 AND zpl_sha256 ~ '^[0-9a-f]{64}$'),
    -- 재발행은 사유가 있어야 한다(중복 라벨 = 중복 재고 위험, docs/30 5장)
    CONSTRAINT ck_wms_print_job_reprint CHECK (reprint_of_id IS NULL OR length(btrim(reprint_reason)) >= 2)
);
CREATE INDEX ix_wms_print_job_time ON wms_print_job (comp_cd, requested_at, job_id);
CREATE INDEX ix_wms_print_job_biz ON wms_print_job (comp_cd, label_type, biz_key);
COMMENT ON TABLE wms_print_job IS '라벨 인쇄 작업 (추가만). 상태는 sys_outbox(message_id) 에서 — 송신기는 DB 를 쓰지 않는다';

-- 프린터·템플릿·원 작업은 같은 회사, 프린터는 같은 공장
CREATE FUNCTION hb_guard_wms_print_job() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM wms_printer p WHERE p.printer_id = NEW.printer_id AND p.comp_cd = NEW.comp_cd
                      AND p.plant_cd = NEW.plant_cd) THEN
        RAISE EXCEPTION 'printer % is not a printer of company % plant %', NEW.printer_id, NEW.comp_cd, NEW.plant_cd
            USING ERRCODE = '23503', CONSTRAINT = 'fk_wms_print_job_printer_comp';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM wms_label_tpl t WHERE t.tpl_id = NEW.tpl_id AND t.comp_cd = NEW.comp_cd
                      AND t.label_type = NEW.label_type) THEN
        RAISE EXCEPTION 'template % is not a % template of company %', NEW.tpl_id, NEW.label_type, NEW.comp_cd
            USING ERRCODE = '23503', CONSTRAINT = 'fk_wms_print_job_tpl_comp';
    END IF;
    IF NEW.reprint_of_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM wms_print_job j WHERE j.job_id = NEW.reprint_of_id
                      AND j.comp_cd = NEW.comp_cd) THEN
        RAISE EXCEPTION 'job % is not of company %', NEW.reprint_of_id, NEW.comp_cd
            USING ERRCODE = '23503', CONSTRAINT = 'fk_wms_print_job_reprint_comp';
    END IF;
    RETURN NEW;
END
$$;
CREATE TRIGGER tg_wms_print_job_guard BEFORE INSERT ON wms_print_job FOR EACH ROW EXECUTE FUNCTION hb_guard_wms_print_job();
CREATE TRIGGER tg_wms_print_job_no_delete BEFORE DELETE ON wms_print_job FOR EACH ROW EXECUTE FUNCTION hb_forbid_delete_master();

-- ---------------------------------------------------------------------
-- 4. 감사 · RLS · 권한 · 화면 · 보존
-- ---------------------------------------------------------------------
CREATE TRIGGER tg_audit AFTER INSERT OR UPDATE OR DELETE ON wms_printer FOR EACH ROW EXECUTE FUNCTION hb_audit('printer_id', '', '');
CREATE TRIGGER tg_audit AFTER INSERT OR UPDATE OR DELETE ON wms_label_tpl FOR EACH ROW EXECUTE FUNCTION hb_audit('tpl_id', '', '');
CREATE TRIGGER tg_audit AFTER INSERT OR UPDATE OR DELETE ON wms_print_job FOR EACH ROW EXECUTE FUNCTION hb_audit('job_id', '', 'zpl');

ALTER TABLE wms_printer ENABLE ROW LEVEL SECURITY;
ALTER TABLE wms_printer FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON wms_printer USING (comp_cd = hb_current_comp()) WITH CHECK (comp_cd = hb_current_comp());
ALTER TABLE wms_label_tpl ENABLE ROW LEVEL SECURITY;
ALTER TABLE wms_label_tpl FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON wms_label_tpl USING (comp_cd = hb_current_comp()) WITH CHECK (comp_cd = hb_current_comp());
ALTER TABLE wms_print_job ENABLE ROW LEVEL SECURITY;
ALTER TABLE wms_print_job FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON wms_print_job USING (comp_cd = hb_current_comp()) WITH CHECK (comp_cd = hb_current_comp());
REVOKE DELETE, TRUNCATE ON wms_printer, wms_label_tpl FROM ${app_role};
REVOKE UPDATE, DELETE, TRUNCATE ON wms_print_job FROM ${app_role};

INSERT INTO sys_prog (prog_cd, prog_nm_key, view_path, module_cd, actions, sort_no) VALUES
    ('wms-label',     'prog.wmsLabel',    'wms/label-mgt', 'WMS', ARRAY['SEARCH','SAVE','PRINT']::varchar(20)[], 410),
    ('wms-print-job', 'prog.wmsPrintJob', 'wms/print-job', 'WMS', ARRAY['SEARCH','PRINT','APPROVE']::varchar(20)[], 420);
-- 로케이션 속성 화면에서 로케이션 라벨 인쇄
UPDATE sys_prog SET actions = actions || ARRAY['PRINT']::varchar(20)[] WHERE prog_cd = 'wms-loc';
INSERT INTO sys_retention_table (table_nm, class_cd) VALUES
    ('wms_printer', 'MASTER'), ('wms_label_tpl', 'MASTER'),
    -- LOT·출하 라벨 재발행 이력은 추적 근거 — 품질기록과 같게
    ('wms_print_job', 'QUALITY_RECORD');
