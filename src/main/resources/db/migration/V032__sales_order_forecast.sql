-- =====================================================================
-- V032 수주·내시 (P2 2단계 — docs/25 4.3)
--
-- - sal_order / sal_order_line: 수주 머리(채번 SAL_ORDER, 고객 발주 번호는 거래처 안 유일) · 행(품목·수량·납기·상태).
--   행은 지우지 않고 취소, 끝난 행(DONE·CANCELLED)은 바꾸지 않는다. 머리 상태는 행에서 서비스가 계산
-- - sal_forecast_rev / sal_forecast_line: 내시 — 받을 때마다 새 버전(거래처별 rev_no), 이전 버전은 그대로.
--   두 테이블 모두 수정·삭제 차단(hb_forbid_update_delete + REVOKE). 정정 = 새 버전이라 reversal_of_id 는 두지 않는다
-- =====================================================================

INSERT INTO sys_code_grp (grp_cd, grp_nm_key, comp_ext_yn) VALUES
    ('SAL_ORDER_STATUS', 'code.grp.SAL_ORDER_STATUS', 'N'),
    ('SAL_LINE_STATUS',  'code.grp.SAL_LINE_STATUS',  'N'),
    ('FCST_BUCKET',      'code.grp.FCST_BUCKET',      'N'),
    ('FCST_SOURCE',      'code.grp.FCST_SOURCE',      'N');
INSERT INTO sys_code (grp_cd, code, code_nm_key, sort_no) VALUES
    ('SAL_ORDER_STATUS', 'OPEN',      'code.SAL_ORDER_STATUS.OPEN',      1),
    ('SAL_ORDER_STATUS', 'CLOSED',    'code.SAL_ORDER_STATUS.CLOSED',    2),
    ('SAL_ORDER_STATUS', 'CANCELLED', 'code.SAL_ORDER_STATUS.CANCELLED', 3),
    ('SAL_LINE_STATUS',  'OPEN',      'code.SAL_LINE_STATUS.OPEN',       1),
    ('SAL_LINE_STATUS',  'DONE',      'code.SAL_LINE_STATUS.DONE',       2),
    ('SAL_LINE_STATUS',  'CANCELLED', 'code.SAL_LINE_STATUS.CANCELLED',  3),
    ('FCST_BUCKET',      'DAY',       'code.FCST_BUCKET.DAY',            1),
    ('FCST_BUCKET',      'WEEK',      'code.FCST_BUCKET.WEEK',           2),
    ('FCST_BUCKET',      'MONTH',     'code.FCST_BUCKET.MONTH',          3),
    ('FCST_SOURCE',      'FILE',      'code.FCST_SOURCE.FILE',           1),
    ('FCST_SOURCE',      'MANUAL',    'code.FCST_SOURCE.MANUAL',         2),
    ('FCST_SOURCE',      'EDI',       'code.FCST_SOURCE.EDI',            3);

-- 거래 기록(수주·내시): 상법·세법 보존 기간 이상 — 지우지 않는다(정리 대상은 P6 운영 정책)
INSERT INTO sys_retention_class (class_cd, basis, default_days, min_days, purge_allowed_yn, desc_key) VALUES
    ('TRADE_RECORD', 'KEEP', NULL, NULL, 'N', 'retention.TRADE_RECORD');

-- ---------------------------------------------------------------------
-- 1. 수주
-- ---------------------------------------------------------------------
CREATE TABLE sal_order (
    order_id    bigint        GENERATED ALWAYS AS IDENTITY,
    comp_cd     varchar(20)   NOT NULL,
    order_no    varchar(50)   NOT NULL,
    cust_id     bigint        NOT NULL,
    cust_po_no  varchar(50),
    order_date  date          NOT NULL,
    status      varchar(10)   NOT NULL DEFAULT 'OPEN',
    remark      varchar(1000),
    row_ver     integer       NOT NULL DEFAULT 0,
    created_at  timestamptz   NOT NULL DEFAULT now(),
    created_by  varchar(50)   NOT NULL,
    updated_at  timestamptz   NOT NULL DEFAULT now(),
    updated_by  varchar(50)   NOT NULL,
    CONSTRAINT pk_sal_order PRIMARY KEY (order_id),
    CONSTRAINT fk_sal_order_comp FOREIGN KEY (comp_cd) REFERENCES sys_comp (comp_cd),
    CONSTRAINT fk_sal_order_cust FOREIGN KEY (cust_id) REFERENCES mst_cust (cust_id),
    CONSTRAINT uq_sal_order_no UNIQUE (comp_cd, order_no),
    CONSTRAINT ck_sal_order_status CHECK (status IN ('OPEN', 'CLOSED', 'CANCELLED')),
    -- 고객 발주 번호: 영문·숫자·기호(바코드·EDI 로 오가는 값), 앞뒤 공백 없음
    CONSTRAINT ck_sal_order_po CHECK (cust_po_no ~ '^[\x21-\x7E]([\x20-\x7E]*[\x21-\x7E])?$'),
    CONSTRAINT ck_sal_order_date CHECK (order_date BETWEEN DATE '2000-01-01' AND DATE '2999-12-31')
);
COMMENT ON TABLE sal_order IS '수주 머리 (docs/25 4.3). 상태는 행에서 계산 — 열린 행이 있으면 OPEN, 모두 취소면 CANCELLED, 그 밖 CLOSED';
-- 같은 고객 발주를 두 번 넣지 않게(엑셀 올리기의 업무 키). 비운 수주는 여러 개 가능
CREATE UNIQUE INDEX uq_sal_order_po ON sal_order (comp_cd, cust_id, cust_po_no) WHERE cust_po_no IS NOT NULL;
CREATE INDEX ix_sal_order_date ON sal_order (comp_cd, order_date, order_id);

CREATE TABLE sal_order_line (
    line_id      bigint         GENERATED ALWAYS AS IDENTITY,
    comp_cd      varchar(20)    NOT NULL,
    order_id     bigint         NOT NULL,
    line_no      integer        NOT NULL,
    item_id      bigint         NOT NULL,
    qty          numeric(18,6)  NOT NULL,
    due_date     date           NOT NULL,
    status       varchar(10)    NOT NULL DEFAULT 'OPEN',
    cancel_reason varchar(500),
    remark       varchar(1000),
    row_ver      integer        NOT NULL DEFAULT 0,
    created_at   timestamptz    NOT NULL DEFAULT now(),
    created_by   varchar(50)    NOT NULL,
    updated_at   timestamptz    NOT NULL DEFAULT now(),
    updated_by   varchar(50)    NOT NULL,
    CONSTRAINT pk_sal_order_line PRIMARY KEY (line_id),
    CONSTRAINT fk_sal_order_line_order FOREIGN KEY (order_id) REFERENCES sal_order (order_id),
    CONSTRAINT fk_sal_order_line_item FOREIGN KEY (item_id) REFERENCES mst_item (item_id),
    CONSTRAINT uq_sal_order_line_no UNIQUE (comp_cd, order_id, line_no),
    CONSTRAINT ck_sal_order_line_no CHECK (line_no BETWEEN 1 AND 99999),
    CONSTRAINT ck_sal_order_line_qty CHECK (qty > 0),
    CONSTRAINT ck_sal_order_line_due CHECK (due_date BETWEEN DATE '2000-01-01' AND DATE '2999-12-31'),
    CONSTRAINT ck_sal_order_line_status CHECK (status IN ('OPEN', 'DONE', 'CANCELLED')),
    CONSTRAINT ck_sal_order_line_cancel CHECK ((status = 'CANCELLED') = (cancel_reason IS NOT NULL AND length(btrim(cancel_reason)) > 0))
);
COMMENT ON TABLE sal_order_line IS '수주 행 — 지우지 않고 취소(사유 필수). 끝난 행(DONE·CANCELLED)은 바꾸지 않는다. PARTIAL 은 3단계 이후 자리';
CREATE INDEX ix_sal_order_line_order ON sal_order_line (comp_cd, order_id, line_no);
CREATE INDEX ix_sal_order_line_due ON sal_order_line (comp_cd, due_date) WHERE status = 'OPEN';

CREATE FUNCTION hb_guard_sal_order() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'UPDATE' THEN
        IF NEW.cust_id <> OLD.cust_id OR NEW.order_no <> OLD.order_no THEN
            RAISE EXCEPTION 'order customer and number are fixed' USING ERRCODE = '55000', CONSTRAINT = 'ck_sal_order_fixed';
        END IF;
        -- 끝난 수주는 그대로(상태가 OPEN 으로 돌아가는 일도 없다 — 끝난 행은 다시 열지 않는다)
        IF OLD.status <> 'OPEN' THEN
            RAISE EXCEPTION 'order % is closed', OLD.order_no USING ERRCODE = '55000', CONSTRAINT = 'ck_sal_order_closed';
        END IF;
        RETURN NEW;
    END IF;
    -- 새 수주는 같은 회사의 사용 중 판매 거래처만(FK 는 회사를 보지 않는다)
    IF NOT EXISTS (SELECT 1 FROM mst_cust c WHERE c.cust_id = NEW.cust_id AND c.comp_cd = NEW.comp_cd
                      AND c.use_yn = 'Y' AND c.cust_type IN ('SALES', 'BOTH')) THEN
        RAISE EXCEPTION 'customer % is not an active sales customer of company %', NEW.cust_id, NEW.comp_cd
            USING ERRCODE = '23503', CONSTRAINT = 'fk_sal_order_cust_comp';
    END IF;
    IF NEW.status <> 'OPEN' THEN
        RAISE EXCEPTION 'a new order starts open' USING ERRCODE = '23514', CONSTRAINT = 'ck_sal_order_status_new';
    END IF;
    RETURN NEW;
END
$$;
CREATE TRIGGER tg_sal_order_guard BEFORE INSERT OR UPDATE ON sal_order FOR EACH ROW EXECUTE FUNCTION hb_guard_sal_order();
CREATE TRIGGER tg_sal_order_no_delete BEFORE DELETE ON sal_order FOR EACH ROW EXECUTE FUNCTION hb_forbid_delete_master();

CREATE FUNCTION hb_guard_sal_order_line() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'UPDATE' THEN
        IF NEW.order_id <> OLD.order_id OR NEW.line_no <> OLD.line_no OR NEW.item_id <> OLD.item_id THEN
            RAISE EXCEPTION 'order line target is fixed (cancel and add a new line)' USING ERRCODE = '55000', CONSTRAINT = 'ck_sal_order_line_fixed';
        END IF;
        IF OLD.status <> 'OPEN' THEN
            RAISE EXCEPTION 'order line % is closed', OLD.line_no USING ERRCODE = '55000', CONSTRAINT = 'ck_sal_order_line_closed';
        END IF;
        RETURN NEW;
    END IF;
    -- 새 행: 같은 회사의 열린 수주, 같은 회사의 사용 중 품목
    IF NOT EXISTS (SELECT 1 FROM sal_order o WHERE o.order_id = NEW.order_id AND o.comp_cd = NEW.comp_cd) THEN
        RAISE EXCEPTION 'order % is not of company %', NEW.order_id, NEW.comp_cd USING ERRCODE = '23503', CONSTRAINT = 'fk_sal_order_line_order_comp';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM sal_order o WHERE o.order_id = NEW.order_id AND o.status = 'OPEN') THEN
        RAISE EXCEPTION 'order % is closed', NEW.order_id USING ERRCODE = '55000', CONSTRAINT = 'ck_sal_order_closed';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM mst_item i WHERE i.item_id = NEW.item_id AND i.comp_cd = NEW.comp_cd AND i.use_yn = 'Y') THEN
        RAISE EXCEPTION 'item % is not an active item of company %', NEW.item_id, NEW.comp_cd
            USING ERRCODE = '23503', CONSTRAINT = 'fk_sal_order_line_item_comp';
    END IF;
    IF NEW.status <> 'OPEN' THEN
        RAISE EXCEPTION 'a new order line starts open' USING ERRCODE = '23514', CONSTRAINT = 'ck_sal_order_line_status_new';
    END IF;
    RETURN NEW;
END
$$;
CREATE TRIGGER tg_sal_order_line_guard BEFORE INSERT OR UPDATE ON sal_order_line FOR EACH ROW EXECUTE FUNCTION hb_guard_sal_order_line();
CREATE TRIGGER tg_sal_order_line_no_delete BEFORE DELETE ON sal_order_line FOR EACH ROW EXECUTE FUNCTION hb_forbid_delete_master();

-- ---------------------------------------------------------------------
-- 2. 내시 (버전마다 보관 — 덮어쓰기 금지)
-- ---------------------------------------------------------------------
CREATE TABLE sal_forecast_rev (
    rev_id        bigint        GENERATED ALWAYS AS IDENTITY,
    comp_cd       varchar(20)   NOT NULL,
    cust_id       bigint        NOT NULL,
    rev_no        integer       NOT NULL,
    received_at   timestamptz   NOT NULL DEFAULT now(),
    source        varchar(10)   NOT NULL,
    import_job_id bigint,
    remark        varchar(500),
    created_by    varchar(50)   NOT NULL,
    CONSTRAINT pk_sal_forecast_rev PRIMARY KEY (rev_id),
    CONSTRAINT fk_sal_forecast_rev_comp FOREIGN KEY (comp_cd) REFERENCES sys_comp (comp_cd),
    CONSTRAINT fk_sal_forecast_rev_cust FOREIGN KEY (cust_id) REFERENCES mst_cust (cust_id),
    CONSTRAINT fk_sal_forecast_rev_job FOREIGN KEY (import_job_id) REFERENCES sys_import_job (job_id),
    CONSTRAINT uq_sal_forecast_rev_no UNIQUE (comp_cd, cust_id, rev_no),
    CONSTRAINT ck_sal_forecast_rev_no CHECK (rev_no >= 1),
    CONSTRAINT ck_sal_forecast_rev_source CHECK (source IN ('FILE', 'MANUAL', 'EDI'))
);
COMMENT ON TABLE sal_forecast_rev IS '내시 버전 — 받을 때마다 새 행(거래처별 rev_no 1, 2, …), 수정·삭제 없음 (docs/25 4.3)';

CREATE TABLE sal_forecast_line (
    line_id      bigint         GENERATED ALWAYS AS IDENTITY,
    comp_cd      varchar(20)    NOT NULL,
    rev_id       bigint         NOT NULL,
    item_id      bigint         NOT NULL,
    bucket_type  varchar(10)    NOT NULL,
    bucket_start date           NOT NULL,
    qty          numeric(18,6)  NOT NULL,
    firm_yn      char(1)        NOT NULL DEFAULT 'N',
    CONSTRAINT pk_sal_forecast_line PRIMARY KEY (line_id),
    CONSTRAINT fk_sal_forecast_line_rev FOREIGN KEY (rev_id) REFERENCES sal_forecast_rev (rev_id),
    CONSTRAINT fk_sal_forecast_line_item FOREIGN KEY (item_id) REFERENCES mst_item (item_id),
    CONSTRAINT uq_sal_forecast_line UNIQUE (rev_id, item_id, bucket_type, bucket_start),
    CONSTRAINT ck_sal_forecast_line_bucket CHECK (bucket_type IN ('DAY', 'WEEK', 'MONTH')),
    -- 주는 월요일, 월은 1일 시작 — 같은 주를 두 날짜로 적어 비교가 어긋나지 않게
    CONSTRAINT ck_sal_forecast_line_start CHECK (
        bucket_start BETWEEN DATE '2000-01-01' AND DATE '2999-12-31'
        AND (bucket_type <> 'WEEK' OR extract(isodow FROM bucket_start) = 1)
        AND (bucket_type <> 'MONTH' OR extract(day FROM bucket_start) = 1)),
    -- 0 = 그 기간 수요 없음(이전 버전 대비 사라진 수요). 빈 칸은 행 없음
    CONSTRAINT ck_sal_forecast_line_qty CHECK (qty >= 0),
    CONSTRAINT ck_sal_forecast_line_firm CHECK (firm_yn IN ('Y', 'N'))
);
COMMENT ON TABLE sal_forecast_line IS '내시 행 — 버전 안 (품목·기간 유형·시작) 유일, 수정·삭제 없음';
CREATE INDEX ix_sal_forecast_line_item ON sal_forecast_line (comp_cd, item_id, bucket_start);

CREATE FUNCTION hb_guard_sal_forecast() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_TABLE_NAME = 'sal_forecast_rev' THEN
        IF NOT EXISTS (SELECT 1 FROM mst_cust c WHERE c.cust_id = NEW.cust_id AND c.comp_cd = NEW.comp_cd
                          AND c.use_yn = 'Y' AND c.cust_type IN ('SALES', 'BOTH')) THEN
            RAISE EXCEPTION 'customer % is not an active sales customer of company %', NEW.cust_id, NEW.comp_cd
                USING ERRCODE = '23503', CONSTRAINT = 'fk_sal_forecast_rev_cust_comp';
        END IF;
        IF NEW.import_job_id IS NOT NULL
           AND NOT EXISTS (SELECT 1 FROM sys_import_job j WHERE j.job_id = NEW.import_job_id AND j.comp_cd = NEW.comp_cd) THEN
            RAISE EXCEPTION 'import job % is not of company %', NEW.import_job_id, NEW.comp_cd
                USING ERRCODE = '23503', CONSTRAINT = 'fk_sal_forecast_rev_job_comp';
        END IF;
        RETURN NEW;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM sal_forecast_rev r WHERE r.rev_id = NEW.rev_id AND r.comp_cd = NEW.comp_cd) THEN
        RAISE EXCEPTION 'forecast revision % is not of company %', NEW.rev_id, NEW.comp_cd
            USING ERRCODE = '23503', CONSTRAINT = 'fk_sal_forecast_line_rev_comp';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM mst_item i WHERE i.item_id = NEW.item_id AND i.comp_cd = NEW.comp_cd AND i.use_yn = 'Y') THEN
        RAISE EXCEPTION 'item % is not an active item of company %', NEW.item_id, NEW.comp_cd
            USING ERRCODE = '23503', CONSTRAINT = 'fk_sal_forecast_line_item_comp';
    END IF;
    RETURN NEW;
END
$$;
CREATE TRIGGER tg_sal_forecast_rev_guard BEFORE INSERT ON sal_forecast_rev FOR EACH ROW EXECUTE FUNCTION hb_guard_sal_forecast();
CREATE TRIGGER tg_sal_forecast_line_guard BEFORE INSERT ON sal_forecast_line FOR EACH ROW EXECUTE FUNCTION hb_guard_sal_forecast();
CREATE TRIGGER tg_sal_forecast_rev_append_only BEFORE UPDATE OR DELETE ON sal_forecast_rev FOR EACH ROW EXECUTE FUNCTION hb_forbid_update_delete();
CREATE TRIGGER tg_sal_forecast_line_append_only BEFORE UPDATE OR DELETE ON sal_forecast_line FOR EACH ROW EXECUTE FUNCTION hb_forbid_update_delete();

-- ---------------------------------------------------------------------
-- 3. 감사 · RLS · 권한 · 화면 · 보존
-- ---------------------------------------------------------------------
CREATE TRIGGER tg_audit AFTER INSERT OR UPDATE OR DELETE ON sal_order FOR EACH ROW EXECUTE FUNCTION hb_audit('order_id', '', '');
CREATE TRIGGER tg_audit AFTER INSERT OR UPDATE OR DELETE ON sal_order_line FOR EACH ROW EXECUTE FUNCTION hb_audit('line_id', '', '');

ALTER TABLE sal_order ENABLE ROW LEVEL SECURITY;
ALTER TABLE sal_order FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON sal_order USING (comp_cd = hb_current_comp()) WITH CHECK (comp_cd = hb_current_comp());
ALTER TABLE sal_order_line ENABLE ROW LEVEL SECURITY;
ALTER TABLE sal_order_line FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON sal_order_line USING (comp_cd = hb_current_comp()) WITH CHECK (comp_cd = hb_current_comp());
ALTER TABLE sal_forecast_rev ENABLE ROW LEVEL SECURITY;
ALTER TABLE sal_forecast_rev FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON sal_forecast_rev USING (comp_cd = hb_current_comp()) WITH CHECK (comp_cd = hb_current_comp());
ALTER TABLE sal_forecast_line ENABLE ROW LEVEL SECURITY;
ALTER TABLE sal_forecast_line FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON sal_forecast_line USING (comp_cd = hb_current_comp()) WITH CHECK (comp_cd = hb_current_comp());
REVOKE DELETE, TRUNCATE ON sal_order, sal_order_line FROM ${app_role};
REVOKE UPDATE, DELETE, TRUNCATE ON sal_forecast_rev, sal_forecast_line FROM ${app_role};

INSERT INTO sys_prog (prog_cd, prog_nm_key, view_path, module_cd, actions, sort_no) VALUES
    ('sal-order',    'prog.salOrder',    'sales/order-mgt',    'SAL', ARRAY['SEARCH','SAVE']::varchar(20)[], 300),
    ('sal-forecast', 'prog.salForecast', 'sales/forecast-mgt', 'SAL', ARRAY['SEARCH','SAVE']::varchar(20)[], 310);
INSERT INTO sys_retention_table (table_nm, class_cd) VALUES
    ('sal_order', 'TRADE_RECORD'), ('sal_order_line', 'TRADE_RECORD'),
    ('sal_forecast_rev', 'TRADE_RECORD'), ('sal_forecast_line', 'TRADE_RECORD');
