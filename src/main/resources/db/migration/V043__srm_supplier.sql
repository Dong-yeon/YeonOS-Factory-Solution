-- =====================================================================
-- V043 SRM 협력사 정보 (P5 SRM 1부 S1 — docs/31 2장·4장, 결정 D2·D3)
--
-- - srm_supplier: P1 거래처(구매·겸용)의 1:1 확장. 거래처 기본정보(명·사업자번호·주소)는 mst_cust 가 주인 — 복사하지 않는다
-- - srm_supplier_cert: 인증서(IATF 16949·ISO 9001·SQ …). 갱신 = 새 행(이력), 삭제 없음. 판정은 서비스 CertStatus
-- - srm_source: 공급 품목(협력사 × 품목: 협력사 품번·리드타임·최소 발주·발주 배수·우선순위)
-- - srm_price: 간이 단가표(협력사 × 품목 × 통화 × 적용 기간). 사용 중 단가끼리 기간 겹침 금지(배제 제약)
-- - 공통코드 CURRENCY(시스템, attrs.scale = 금액 소수 자릿수)·PAY_TERMS·SUPPLIER_CERT(회사 확장)·TRADE_STATUS
-- - 정책 SRM_CERT_DUE_SOON_DAYS
-- ※ 병행 개발(docs/31 6장): main 에 늦게 들어가는 쪽이 번호를 다시 매긴다
-- =====================================================================

INSERT INTO sys_policy_def (policy_key, value_type, default_value, allowed_values, min_value, max_value, desc_key) VALUES
    ('SRM_CERT_DUE_SOON_DAYS', 'INT', '60', NULL, 1, 365, 'policy.SRM_CERT_DUE_SOON_DAYS');

INSERT INTO sys_code_grp (grp_cd, grp_nm_key, comp_ext_yn) VALUES
    ('CURRENCY',      'code.grp.CURRENCY',      'N'),
    ('PAY_TERMS',     'code.grp.PAY_TERMS',     'Y'),
    ('SUPPLIER_CERT', 'code.grp.SUPPLIER_CERT', 'Y'),
    ('TRADE_STATUS',  'code.grp.TRADE_STATUS',  'N');
INSERT INTO sys_code (grp_cd, code, code_nm_key, sort_no, attrs) VALUES
    ('CURRENCY', 'KRW', 'code.CURRENCY.KRW', 1, '{"scale": 0}'),
    ('CURRENCY', 'USD', 'code.CURRENCY.USD', 2, '{"scale": 2}'),
    ('CURRENCY', 'EUR', 'code.CURRENCY.EUR', 3, '{"scale": 2}'),
    ('CURRENCY', 'JPY', 'code.CURRENCY.JPY', 4, '{"scale": 0}'),
    ('CURRENCY', 'CNY', 'code.CURRENCY.CNY', 5, '{"scale": 2}');
INSERT INTO sys_code (grp_cd, code, code_nm_key, sort_no) VALUES
    ('PAY_TERMS', 'CASH',     'code.PAY_TERMS.CASH',     1),
    ('PAY_TERMS', 'NET30',    'code.PAY_TERMS.NET30',    2),
    ('PAY_TERMS', 'NET60',    'code.PAY_TERMS.NET60',    3),
    ('PAY_TERMS', 'EOM_NEXT', 'code.PAY_TERMS.EOM_NEXT', 4),
    ('SUPPLIER_CERT', 'IATF16949', 'code.SUPPLIER_CERT.IATF16949', 1),
    ('SUPPLIER_CERT', 'ISO9001',   'code.SUPPLIER_CERT.ISO9001',   2),
    ('SUPPLIER_CERT', 'ISO14001',  'code.SUPPLIER_CERT.ISO14001',  3),
    ('SUPPLIER_CERT', 'ISO45001',  'code.SUPPLIER_CERT.ISO45001',  4),
    ('SUPPLIER_CERT', 'SQ',        'code.SUPPLIER_CERT.SQ',        5),
    ('TRADE_STATUS', 'ACTIVE',  'code.TRADE_STATUS.ACTIVE',  1),
    ('TRADE_STATUS', 'HOLD',    'code.TRADE_STATUS.HOLD',    2),
    ('TRADE_STATUS', 'BLOCKED', 'code.TRADE_STATUS.BLOCKED', 3);

-- ---------------------------------------------------------------------
-- 1. 협력사 (거래처 1:1 확장)
-- ---------------------------------------------------------------------
CREATE TABLE srm_supplier (
    cust_id             bigint        NOT NULL,
    comp_cd             varchar(20)   NOT NULL,
    buyer_user_id       bigint,
    pay_terms_cd        varchar(30),
    currency_cd         varchar(30)   NOT NULL DEFAULT 'KRW',
    country_cd          char(2),
    control_country_cd  char(2),
    trade_status        varchar(10)   NOT NULL DEFAULT 'ACTIVE',
    status_reason       varchar(500),
    sec_assessed_on     date,
    remark              varchar(1000),
    row_ver             integer       NOT NULL DEFAULT 0,
    created_at          timestamptz   NOT NULL DEFAULT now(),
    created_by          varchar(50)   NOT NULL,
    updated_at          timestamptz   NOT NULL DEFAULT now(),
    updated_by          varchar(50)   NOT NULL,
    CONSTRAINT pk_srm_supplier PRIMARY KEY (cust_id),
    CONSTRAINT fk_srm_supplier_cust FOREIGN KEY (cust_id) REFERENCES mst_cust (cust_id),
    CONSTRAINT fk_srm_supplier_comp FOREIGN KEY (comp_cd) REFERENCES sys_comp (comp_cd),
    CONSTRAINT fk_srm_supplier_buyer FOREIGN KEY (buyer_user_id) REFERENCES sys_user (user_id),
    CONSTRAINT ck_srm_supplier_country CHECK (country_cd ~ '^[A-Z]{2}$' AND control_country_cd ~ '^[A-Z]{2}$'),
    CONSTRAINT ck_srm_supplier_status CHECK (trade_status IN ('ACTIVE', 'HOLD', 'BLOCKED')),
    -- 거래 보류·정지에는 이유가 있어야 한다(나중에 "왜 발주가 막혔나" 를 설명)
    CONSTRAINT ck_srm_supplier_reason CHECK (trade_status = 'ACTIVE' OR (status_reason IS NOT NULL AND length(btrim(status_reason)) > 0)),
    CONSTRAINT ck_srm_supplier_sec CHECK (sec_assessed_on BETWEEN DATE '2000-01-01' AND DATE '2999-12-31')
);
CREATE UNIQUE INDEX uq_srm_supplier_comp ON srm_supplier (comp_cd, cust_id);
COMMENT ON TABLE srm_supplier IS '협력사 — P1 거래처(구매·겸용)의 1:1 확장 (docs/31 D2). 거래처 기본정보는 mst_cust 가 주인';
COMMENT ON COLUMN srm_supplier.control_country_cd IS '지배 주체 국가(ISO 3166-1 alpha-2) — 원산지 규제 대응(docs/27 M12)';
COMMENT ON COLUMN srm_supplier.trade_status IS 'ACTIVE / HOLD(경고) / BLOCKED(새 발주 차단, 기존 발주는 그대로)';
COMMENT ON COLUMN srm_supplier.sec_assessed_on IS '마지막 보안 진단일 — 협력사 보안 질의 대응(docs/27 M15)';

CREATE FUNCTION hb_guard_srm_supplier() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'INSERT' THEN
        -- FK 는 회사를 보지 않는다 — 같은 회사의 구매 거래처만 협력사로
        IF NOT EXISTS (SELECT 1 FROM mst_cust c WHERE c.cust_id = NEW.cust_id AND c.comp_cd = NEW.comp_cd) THEN
            RAISE EXCEPTION 'customer % is not of company %', NEW.cust_id, NEW.comp_cd
                USING ERRCODE = '23503', CONSTRAINT = 'fk_srm_supplier_cust_comp';
        END IF;
        IF NOT EXISTS (SELECT 1 FROM mst_cust c WHERE c.cust_id = NEW.cust_id AND c.cust_type IN ('PURCHASE', 'BOTH')) THEN
            RAISE EXCEPTION 'customer % is not a purchase customer', NEW.cust_id
                USING ERRCODE = '23514', CONSTRAINT = 'ck_srm_supplier_cust_type';
        END IF;
    ELSIF NEW.cust_id <> OLD.cust_id OR NEW.comp_cd <> OLD.comp_cd THEN
        RAISE EXCEPTION 'supplier customer is fixed' USING ERRCODE = '55000', CONSTRAINT = 'ck_srm_supplier_fixed';
    END IF;
    IF NEW.buyer_user_id IS NOT NULL AND (TG_OP = 'INSERT' OR NEW.buyer_user_id IS DISTINCT FROM OLD.buyer_user_id)
       AND NOT EXISTS (SELECT 1 FROM sys_user u WHERE u.user_id = NEW.buyer_user_id AND u.comp_cd = NEW.comp_cd) THEN
        RAISE EXCEPTION 'user % is not a user of company %', NEW.buyer_user_id, NEW.comp_cd
            USING ERRCODE = '23503', CONSTRAINT = 'fk_srm_supplier_buyer_comp';
    END IF;
    -- 코드는 바꿀 때만 사용 중인지 본다(사용 중지된 코드를 들고 있는 기존 행은 그대로 저장 가능)
    IF (TG_OP = 'INSERT' OR NEW.currency_cd IS DISTINCT FROM OLD.currency_cd)
       AND NOT hb_code_usable(NEW.comp_cd, 'CURRENCY', NEW.currency_cd) THEN
        RAISE EXCEPTION 'currency % is not usable', NEW.currency_cd USING ERRCODE = '23503', CONSTRAINT = 'fk_srm_supplier_currency';
    END IF;
    IF NEW.pay_terms_cd IS NOT NULL AND (TG_OP = 'INSERT' OR NEW.pay_terms_cd IS DISTINCT FROM OLD.pay_terms_cd)
       AND NOT hb_code_usable(NEW.comp_cd, 'PAY_TERMS', NEW.pay_terms_cd) THEN
        RAISE EXCEPTION 'pay terms % is not usable', NEW.pay_terms_cd USING ERRCODE = '23503', CONSTRAINT = 'fk_srm_supplier_pay_terms';
    END IF;
    RETURN NEW;
END
$$;
CREATE TRIGGER tg_srm_supplier_guard BEFORE INSERT OR UPDATE ON srm_supplier FOR EACH ROW EXECUTE FUNCTION hb_guard_srm_supplier();
CREATE TRIGGER tg_srm_supplier_no_delete BEFORE DELETE ON srm_supplier FOR EACH ROW EXECUTE FUNCTION hb_forbid_delete_master();

-- 아래 세 테이블의 "같은 회사 협력사" 확인 (협력사 행이 있어야 하고 회사가 같아야)
CREATE FUNCTION hb_srm_supplier_of_comp(p_comp varchar, p_cust bigint) RETURNS boolean
    LANGUAGE sql STABLE
AS $$
    SELECT EXISTS (SELECT 1 FROM srm_supplier WHERE cust_id = p_cust AND comp_cd = p_comp)
$$;

-- ---------------------------------------------------------------------
-- 2. 인증서
-- ---------------------------------------------------------------------
CREATE TABLE srm_supplier_cert (
    cert_id       bigint        GENERATED ALWAYS AS IDENTITY,
    comp_cd       varchar(20)   NOT NULL,
    cust_id       bigint        NOT NULL,
    cert_type_cd  varchar(30)   NOT NULL,
    cert_no       varchar(100),
    issuer_nm     varchar(200),
    issued_on     date          NOT NULL,
    expires_on    date,
    use_yn        char(1)       NOT NULL DEFAULT 'Y',
    remark        varchar(500),
    row_ver       integer       NOT NULL DEFAULT 0,
    created_at    timestamptz   NOT NULL DEFAULT now(),
    created_by    varchar(50)   NOT NULL,
    updated_at    timestamptz   NOT NULL DEFAULT now(),
    updated_by    varchar(50)   NOT NULL,
    CONSTRAINT pk_srm_supplier_cert PRIMARY KEY (cert_id),
    CONSTRAINT fk_srm_supplier_cert_supplier FOREIGN KEY (cust_id) REFERENCES srm_supplier (cust_id),
    CONSTRAINT ck_srm_supplier_cert_period CHECK (expires_on IS NULL OR expires_on >= issued_on),
    CONSTRAINT ck_srm_supplier_cert_years CHECK (issued_on BETWEEN DATE '1950-01-01' AND DATE '2999-12-31'
                                                 AND (expires_on IS NULL OR expires_on <= DATE '2999-12-31')),
    CONSTRAINT ck_srm_supplier_cert_use_yn CHECK (use_yn IN ('Y', 'N'))
);
CREATE INDEX ix_srm_supplier_cert ON srm_supplier_cert (comp_cd, cust_id, cert_type_cd, expires_on);
COMMENT ON TABLE srm_supplier_cert IS '협력사 인증서 — 갱신은 새 행(이력), 삭제 없음, 잘못 넣은 행은 use_yn = N. 첨부는 sys_file(target srm-supplier / cert-{id})';

CREATE FUNCTION hb_guard_srm_supplier_cert() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'UPDATE' AND (NEW.cust_id <> OLD.cust_id OR NEW.cert_type_cd <> OLD.cert_type_cd OR NEW.issued_on <> OLD.issued_on) THEN
        RAISE EXCEPTION 'certificate target is fixed (add a new row)' USING ERRCODE = '55000', CONSTRAINT = 'ck_srm_supplier_cert_fixed';
    END IF;
    IF NOT hb_srm_supplier_of_comp(NEW.comp_cd, NEW.cust_id) THEN
        RAISE EXCEPTION 'supplier % is not of company %', NEW.cust_id, NEW.comp_cd
            USING ERRCODE = '23503', CONSTRAINT = 'fk_srm_supplier_cert_supplier_comp';
    END IF;
    IF TG_OP = 'INSERT' AND NOT hb_code_usable(NEW.comp_cd, 'SUPPLIER_CERT', NEW.cert_type_cd) THEN
        RAISE EXCEPTION 'certificate type % is not usable', NEW.cert_type_cd
            USING ERRCODE = '23503', CONSTRAINT = 'fk_srm_supplier_cert_type';
    END IF;
    RETURN NEW;
END
$$;
CREATE TRIGGER tg_srm_supplier_cert_guard BEFORE INSERT OR UPDATE ON srm_supplier_cert FOR EACH ROW EXECUTE FUNCTION hb_guard_srm_supplier_cert();
CREATE TRIGGER tg_srm_supplier_cert_no_delete BEFORE DELETE ON srm_supplier_cert FOR EACH ROW EXECUTE FUNCTION hb_forbid_delete_master();

-- ---------------------------------------------------------------------
-- 3. 공급 품목
-- ---------------------------------------------------------------------
CREATE TABLE srm_source (
    source_id         bigint         GENERATED ALWAYS AS IDENTITY,
    comp_cd           varchar(20)    NOT NULL,
    cust_id           bigint         NOT NULL,
    item_id           bigint         NOT NULL,
    supplier_item_cd  varchar(60),
    supplier_item_nm  varchar(200),
    lead_days         smallint       NOT NULL DEFAULT 0,
    moq               numeric(18,6),
    order_mult        numeric(18,6),
    priority          smallint       NOT NULL DEFAULT 1,
    use_yn            char(1)        NOT NULL DEFAULT 'Y',
    remark            varchar(500),
    row_ver           integer        NOT NULL DEFAULT 0,
    created_at        timestamptz    NOT NULL DEFAULT now(),
    created_by        varchar(50)    NOT NULL,
    updated_at        timestamptz    NOT NULL DEFAULT now(),
    updated_by        varchar(50)    NOT NULL,
    CONSTRAINT pk_srm_source PRIMARY KEY (source_id),
    CONSTRAINT fk_srm_source_supplier FOREIGN KEY (cust_id) REFERENCES srm_supplier (cust_id),
    CONSTRAINT fk_srm_source_item FOREIGN KEY (item_id) REFERENCES mst_item (item_id),
    CONSTRAINT uq_srm_source UNIQUE (comp_cd, cust_id, item_id),
    CONSTRAINT ck_srm_source_lead CHECK (lead_days BETWEEN 0 AND 365),
    CONSTRAINT ck_srm_source_moq CHECK (moq IS NULL OR moq > 0),
    CONSTRAINT ck_srm_source_mult CHECK (order_mult IS NULL OR order_mult > 0),
    CONSTRAINT ck_srm_source_priority CHECK (priority BETWEEN 1 AND 9),
    CONSTRAINT ck_srm_source_use_yn CHECK (use_yn IN ('Y', 'N'))
);
CREATE INDEX ix_srm_source_item ON srm_source (comp_cd, item_id, priority);
COMMENT ON TABLE srm_source IS '공급 품목 — 협력사 × 품목 한 행. 최소 발주·발주 배수는 품목 기본 단위. 우선순위 1 = 주 협력사';

CREATE FUNCTION hb_guard_srm_source() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'UPDATE' AND (NEW.cust_id <> OLD.cust_id OR NEW.item_id <> OLD.item_id) THEN
        RAISE EXCEPTION 'source target is fixed' USING ERRCODE = '55000', CONSTRAINT = 'ck_srm_source_fixed';
    END IF;
    IF NOT hb_srm_supplier_of_comp(NEW.comp_cd, NEW.cust_id) THEN
        RAISE EXCEPTION 'supplier % is not of company %', NEW.cust_id, NEW.comp_cd
            USING ERRCODE = '23503', CONSTRAINT = 'fk_srm_source_supplier_comp';
    END IF;
    IF NOT hb_item_of_comp(NEW.comp_cd, NEW.item_id) THEN
        RAISE EXCEPTION 'item % is not of company %', NEW.item_id, NEW.comp_cd
            USING ERRCODE = '23503', CONSTRAINT = 'fk_srm_source_item_comp';
    END IF;
    -- 새로 만들거나 다시 사용(N→Y)할 때는 사용 중 품목만 (P1 규칙: 사용 중지 = 새 참조만 차단)
    IF NEW.use_yn = 'Y' AND (TG_OP = 'INSERT' OR OLD.use_yn <> 'Y')
       AND NOT EXISTS (SELECT 1 FROM mst_item i WHERE i.item_id = NEW.item_id AND i.use_yn = 'Y') THEN
        RAISE EXCEPTION 'item % is not active', NEW.item_id USING ERRCODE = '23503', CONSTRAINT = 'fk_srm_source_item_active';
    END IF;
    RETURN NEW;
END
$$;
CREATE TRIGGER tg_srm_source_guard BEFORE INSERT OR UPDATE ON srm_source FOR EACH ROW EXECUTE FUNCTION hb_guard_srm_source();
CREATE TRIGGER tg_srm_source_no_delete BEFORE DELETE ON srm_source FOR EACH ROW EXECUTE FUNCTION hb_forbid_delete_master();

-- ---------------------------------------------------------------------
-- 4. 단가
-- ---------------------------------------------------------------------
CREATE TABLE srm_price (
    price_id     bigint         GENERATED ALWAYS AS IDENTITY,
    comp_cd      varchar(20)    NOT NULL,
    cust_id      bigint         NOT NULL,
    item_id      bigint         NOT NULL,
    currency_cd  varchar(30)    NOT NULL,
    unit_price   numeric(18,4)  NOT NULL,
    valid_from   date           NOT NULL,
    valid_to     date,
    use_yn       char(1)        NOT NULL DEFAULT 'Y',
    remark       varchar(500),
    row_ver      integer        NOT NULL DEFAULT 0,
    created_at   timestamptz    NOT NULL DEFAULT now(),
    created_by   varchar(50)    NOT NULL,
    updated_at   timestamptz    NOT NULL DEFAULT now(),
    updated_by   varchar(50)    NOT NULL,
    CONSTRAINT pk_srm_price PRIMARY KEY (price_id),
    CONSTRAINT fk_srm_price_supplier FOREIGN KEY (cust_id) REFERENCES srm_supplier (cust_id),
    CONSTRAINT fk_srm_price_item FOREIGN KEY (item_id) REFERENCES mst_item (item_id),
    CONSTRAINT ck_srm_price_positive CHECK (unit_price > 0),
    CONSTRAINT ck_srm_price_period CHECK (valid_to IS NULL OR valid_to >= valid_from),
    CONSTRAINT ck_srm_price_years CHECK (valid_from BETWEEN DATE '2000-01-01' AND DATE '2999-12-31'
                                        AND (valid_to IS NULL OR valid_to <= DATE '2999-12-31')),
    CONSTRAINT ck_srm_price_use_yn CHECK (use_yn IN ('Y', 'N')),
    -- 같은 협력사·품목·통화의 사용 중 단가는 기간이 겹치지 않는다(기준일 단가가 하나로 정해지게)
    CONSTRAINT ex_srm_price_period EXCLUDE USING gist (
        comp_cd WITH =, cust_id WITH =, item_id WITH =, currency_cd WITH =,
        daterange(valid_from, valid_to, '[]') WITH &&) WHERE (use_yn = 'Y')
);
COMMENT ON TABLE srm_price IS '간이 단가표 (docs/31 D3) — 품목 기본 단위당 단가. 새 단가 = 새 행(열린 이전 행은 서비스가 전날로 마감). 단가·통화·시작일은 고정';

CREATE FUNCTION hb_guard_srm_price() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'UPDATE' AND (NEW.cust_id <> OLD.cust_id OR NEW.item_id <> OLD.item_id OR NEW.currency_cd <> OLD.currency_cd
                             OR NEW.unit_price <> OLD.unit_price OR NEW.valid_from <> OLD.valid_from) THEN
        RAISE EXCEPTION 'price is fixed (add a new row)' USING ERRCODE = '55000', CONSTRAINT = 'ck_srm_price_fixed';
    END IF;
    IF NOT hb_srm_supplier_of_comp(NEW.comp_cd, NEW.cust_id) THEN
        RAISE EXCEPTION 'supplier % is not of company %', NEW.cust_id, NEW.comp_cd
            USING ERRCODE = '23503', CONSTRAINT = 'fk_srm_price_supplier_comp';
    END IF;
    IF NOT hb_item_of_comp(NEW.comp_cd, NEW.item_id) THEN
        RAISE EXCEPTION 'item % is not of company %', NEW.item_id, NEW.comp_cd
            USING ERRCODE = '23503', CONSTRAINT = 'fk_srm_price_item_comp';
    END IF;
    IF TG_OP = 'INSERT' AND NOT hb_code_usable(NEW.comp_cd, 'CURRENCY', NEW.currency_cd) THEN
        RAISE EXCEPTION 'currency % is not usable', NEW.currency_cd USING ERRCODE = '23503', CONSTRAINT = 'fk_srm_price_currency';
    END IF;
    RETURN NEW;
END
$$;
CREATE TRIGGER tg_srm_price_guard BEFORE INSERT OR UPDATE ON srm_price FOR EACH ROW EXECUTE FUNCTION hb_guard_srm_price();
CREATE TRIGGER tg_srm_price_no_delete BEFORE DELETE ON srm_price FOR EACH ROW EXECUTE FUNCTION hb_forbid_delete_master();
CREATE INDEX ix_srm_price_item ON srm_price (comp_cd, item_id, cust_id, valid_from);

-- ---------------------------------------------------------------------
-- 5. 감사 · RLS · 권한 · 화면 · 보존
-- ---------------------------------------------------------------------
CREATE TRIGGER tg_audit AFTER INSERT OR UPDATE OR DELETE ON srm_supplier FOR EACH ROW EXECUTE FUNCTION hb_audit('cust_id', '', '');
CREATE TRIGGER tg_audit AFTER INSERT OR UPDATE OR DELETE ON srm_supplier_cert FOR EACH ROW EXECUTE FUNCTION hb_audit('cert_id', '', '');
CREATE TRIGGER tg_audit AFTER INSERT OR UPDATE OR DELETE ON srm_source FOR EACH ROW EXECUTE FUNCTION hb_audit('source_id', '', '');
CREATE TRIGGER tg_audit AFTER INSERT OR UPDATE OR DELETE ON srm_price FOR EACH ROW EXECUTE FUNCTION hb_audit('price_id', '', '');

ALTER TABLE srm_supplier ENABLE ROW LEVEL SECURITY;
ALTER TABLE srm_supplier FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON srm_supplier USING (comp_cd = hb_current_comp()) WITH CHECK (comp_cd = hb_current_comp());
ALTER TABLE srm_supplier_cert ENABLE ROW LEVEL SECURITY;
ALTER TABLE srm_supplier_cert FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON srm_supplier_cert USING (comp_cd = hb_current_comp()) WITH CHECK (comp_cd = hb_current_comp());
ALTER TABLE srm_source ENABLE ROW LEVEL SECURITY;
ALTER TABLE srm_source FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON srm_source USING (comp_cd = hb_current_comp()) WITH CHECK (comp_cd = hb_current_comp());
ALTER TABLE srm_price ENABLE ROW LEVEL SECURITY;
ALTER TABLE srm_price FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON srm_price USING (comp_cd = hb_current_comp()) WITH CHECK (comp_cd = hb_current_comp());
REVOKE DELETE, TRUNCATE ON srm_supplier, srm_supplier_cert, srm_source, srm_price FROM ${app_role};

INSERT INTO sys_prog (prog_cd, prog_nm_key, view_path, module_cd, actions, sort_no) VALUES
    ('srm-supplier', 'prog.srmSupplier', 'srm/supplier-mgt', 'SRM', ARRAY['SEARCH','SAVE']::varchar(20)[], 500);
INSERT INTO sys_retention_table (table_nm, class_cd) VALUES
    ('srm_supplier', 'MASTER'), ('srm_source', 'MASTER'), ('srm_price', 'MASTER'),
    ('srm_supplier_cert', 'QUALITY_RECORD');
