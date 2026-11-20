-- QMS COPQ(품질 비용) 1단계 (docs/29_P3 29장, 사용자 결정 2026-10-29 "단가 출처 = 품목 표준단가")
-- 1) mst_item.std_price — 품목 표준단가(회사 기준 통화, 기본 단위 1개당). ERP 주인 필드(정책 ERP_ITEM_SYNC_MODE=PULL 이면 MES 에서 안 바꿈)
-- 2) mst_item_price_hist — 단가가 바뀔 때마다 트리거가 쌓는 이력(추가만). COPQ 는 사건 시점의 단가를 쓴다(CQ-3) —
--    단가를 고쳐도 지난 달 금액이 조용히 바뀌지 않게. 앱이 직접 쓰지 못한다(트리거 안에서만)
-- 3) qms_copq_cost — 원장에서 못 뽑는 비용(예방·평가 등)의 수동 행. 월·구분·항목·금액
-- 4) 화면 qms-copq

-- ---------------------------------------------------------------------
-- 1. 품목 표준단가
-- ---------------------------------------------------------------------
ALTER TABLE mst_item ADD COLUMN std_price numeric(18,4);
ALTER TABLE mst_item ADD CONSTRAINT ck_mst_item_std_price CHECK (std_price IS NULL OR std_price >= 0);
COMMENT ON COLUMN mst_item.std_price IS '표준단가 — 회사 기준 통화, 기본 단위 1개당. 비어 있으면 단가 없음(0원과 다름). 바뀔 때마다 mst_item_price_hist (docs/29_P3 29장)';

-- ---------------------------------------------------------------------
-- 2. 단가 이력 (추가만, 트리거로만)
-- ---------------------------------------------------------------------
CREATE TABLE mst_item_price_hist (
    hist_id     bigint         GENERATED ALWAYS AS IDENTITY,
    comp_cd     varchar(20)    NOT NULL,
    item_id     bigint         NOT NULL,
    std_price   numeric(18,4),
    changed_at  timestamptz    NOT NULL DEFAULT now(),
    changed_by  varchar(50)    NOT NULL,
    CONSTRAINT pk_mst_item_price_hist PRIMARY KEY (hist_id),
    CONSTRAINT fk_mst_item_price_hist_comp FOREIGN KEY (comp_cd) REFERENCES sys_comp (comp_cd),
    CONSTRAINT fk_mst_item_price_hist_item FOREIGN KEY (item_id) REFERENCES mst_item (item_id),
    CONSTRAINT ck_mst_item_price_hist_price CHECK (std_price IS NULL OR std_price >= 0)
);
CREATE INDEX ix_mst_item_price_hist_at ON mst_item_price_hist (comp_cd, item_id, changed_at DESC, hist_id DESC);
COMMENT ON TABLE mst_item_price_hist IS '품목 표준단가 이력 — mst_item.std_price 가 바뀔 때 트리거가 한 행(비우면 null 행). 추가만 (docs/29_P3 29장 CQ-3)';

CREATE FUNCTION hb_mst_item_price_hist() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF (TG_OP = 'INSERT' AND NEW.std_price IS NOT NULL)
       OR (TG_OP = 'UPDATE' AND NEW.std_price IS DISTINCT FROM OLD.std_price) THEN
        INSERT INTO mst_item_price_hist (comp_cd, item_id, std_price, changed_by)
        VALUES (NEW.comp_cd, NEW.item_id, NEW.std_price, CASE WHEN TG_OP = 'INSERT' THEN NEW.created_by ELSE NEW.updated_by END);
    END IF;
    RETURN NULL;
END
$$;
CREATE TRIGGER tg_mst_item_price_hist AFTER INSERT OR UPDATE OF std_price ON mst_item FOR EACH ROW EXECUTE FUNCTION hb_mst_item_price_hist();

-- 이력은 mst_item 트리거 안에서만 쓰고(직접 INSERT 거부), 고치거나 지우지 않는다
CREATE FUNCTION hb_guard_mst_item_price_hist() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP <> 'INSERT' THEN
        RAISE EXCEPTION 'item price history is append-only' USING ERRCODE = '55000', CONSTRAINT = 'ck_mst_item_price_hist_append_only';
    END IF;
    IF pg_trigger_depth() < 2 THEN
        RAISE EXCEPTION 'item price history is written by mst_item only (change mst_item.std_price)'
            USING ERRCODE = '55000', CONSTRAINT = 'ck_mst_item_price_hist_by_trigger';
    END IF;
    RETURN NEW;
END
$$;
CREATE TRIGGER tg_mst_item_price_hist_guard BEFORE INSERT OR UPDATE OR DELETE ON mst_item_price_hist FOR EACH ROW
    EXECUTE FUNCTION hb_guard_mst_item_price_hist();

-- 변경 감사도 다른 회사별 표와 같게(추가만이라 INSERT 만 남는다 — 감사 예외 목록을 늘리지 않으려고)
CREATE TRIGGER tg_audit AFTER INSERT OR UPDATE OR DELETE ON mst_item_price_hist FOR EACH ROW EXECUTE FUNCTION hb_audit('hist_id', '', '');

ALTER TABLE mst_item_price_hist ENABLE ROW LEVEL SECURITY;
ALTER TABLE mst_item_price_hist FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON mst_item_price_hist USING (comp_cd = hb_current_comp()) WITH CHECK (comp_cd = hb_current_comp());
REVOKE UPDATE, DELETE, TRUNCATE ON mst_item_price_hist FROM ${app_role};

-- ---------------------------------------------------------------------
-- 3. 수동 비용 행
-- ---------------------------------------------------------------------
CREATE TABLE qms_copq_cost (
    cost_id     bigint         GENERATED ALWAYS AS IDENTITY,
    comp_cd     varchar(20)    NOT NULL,
    cost_month  date           NOT NULL,
    category    varchar(10)    NOT NULL,
    cost_item   varchar(100)   NOT NULL,
    amount      numeric(18,2)  NOT NULL,
    note        varchar(500),
    row_ver     integer        NOT NULL DEFAULT 0,
    created_at  timestamptz    NOT NULL DEFAULT now(),
    created_by  varchar(50)    NOT NULL,
    updated_at  timestamptz    NOT NULL DEFAULT now(),
    updated_by  varchar(50)    NOT NULL,
    CONSTRAINT pk_qms_copq_cost PRIMARY KEY (cost_id),
    CONSTRAINT fk_qms_copq_cost_comp FOREIGN KEY (comp_cd) REFERENCES sys_comp (comp_cd),
    CONSTRAINT ck_qms_copq_cost_month CHECK (cost_month = date_trunc('month', cost_month)::date),
    CONSTRAINT ck_qms_copq_cost_category CHECK (category IN ('PREVENTION', 'APPRAISAL', 'INTERNAL', 'EXTERNAL')),
    CONSTRAINT ck_qms_copq_cost_item CHECK (length(btrim(cost_item)) > 0),
    CONSTRAINT ck_qms_copq_cost_amount CHECK (amount >= 0)
);
CREATE INDEX ix_qms_copq_cost_month ON qms_copq_cost (comp_cd, cost_month, category);
COMMENT ON TABLE qms_copq_cost IS 'COPQ 수동 비용 행 — 월(1일)·PAF 구분·항목·금액(회사 기준 통화). 원장에서 못 뽑는 예방·평가 비용 등 (docs/29_P3 29장)';

CREATE TRIGGER tg_audit AFTER INSERT OR UPDATE OR DELETE ON qms_copq_cost FOR EACH ROW EXECUTE FUNCTION hb_audit('cost_id', '', '');

ALTER TABLE qms_copq_cost ENABLE ROW LEVEL SECURITY;
ALTER TABLE qms_copq_cost FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON qms_copq_cost USING (comp_cd = hb_current_comp()) WITH CHECK (comp_cd = hb_current_comp());
REVOKE TRUNCATE ON qms_copq_cost FROM ${app_role};

-- 원장 쪽 집계 인덱스: 종결 NCR 을 종결 시각으로 자른다
CREATE INDEX ix_qms_ncr_closed ON qms_ncr (comp_cd, closed_at) WHERE status = 'CLOSED';

-- ---------------------------------------------------------------------
-- 4. 화면
-- ---------------------------------------------------------------------
INSERT INTO sys_code_grp (grp_cd, grp_nm_key, comp_ext_yn) VALUES
    ('QMS_COPQ_CAT', 'code.grp.QMS_COPQ_CAT', 'N');
INSERT INTO sys_code (grp_cd, code, code_nm_key, sort_no) VALUES
    ('QMS_COPQ_CAT', 'PREVENTION', 'code.QMS_COPQ_CAT.PREVENTION', 1),
    ('QMS_COPQ_CAT', 'APPRAISAL',  'code.QMS_COPQ_CAT.APPRAISAL',  2),
    ('QMS_COPQ_CAT', 'INTERNAL',   'code.QMS_COPQ_CAT.INTERNAL',   3),
    ('QMS_COPQ_CAT', 'EXTERNAL',   'code.QMS_COPQ_CAT.EXTERNAL',   4);

INSERT INTO sys_prog (prog_cd, prog_nm_key, view_path, module_cd, actions, sort_no) VALUES
    ('qms-copq', 'prog.qmsCopq', 'qms/copq', 'QMS', ARRAY['SEARCH','SAVE','DELETE']::varchar(20)[], 698);

INSERT INTO sys_retention_table (table_nm, class_cd) VALUES
    ('mst_item_price_hist', 'MASTER'), ('qms_copq_cost', 'QUALITY_RECORD');
