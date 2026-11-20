-- =====================================================================
-- P3 QMS Q4 시정조치(8D)·고객 요구사항(CSR) (docs/29 2장 Q4, 결정 D7·D8) — 임시 번호, 병합 때 재번호
--
-- - 시정조치 qms_capa: 출처(고객 클레임·내부·협력사·심사) × 거래처·품목(선택), D1~D8 텍스트 구역(D8), 담당·기한, 수평전개, 첨부, 종결 결재.
--   상태 OPEN → CONTAINED → ANALYZED → ACTIONED → VERIFIED → (CLOSING 결재 중) → CLOSED, 어디서나 CANCELLED(사유).
--   단계마다 채워야 할 D 구역은 CHECK(상태별 필수), 전이 표·종결 뒤 불변은 트리거. 반려·회수 = ACTIONED 로(D7 다시)
-- - 기한: 만들 때 발생일 + 정책 QMS_CAPA_CONTAIN_DAYS / QMS_CAPA_CLOSE_DAYS — 고객 클레임이면 그 고객 CSR 의 override 가 이긴다. 행에 스냅샷
-- - 2부 NCR 연결 칸 ncr_id 는 자리만(FK 는 2부)
-- - CSR qms_cust_csr: 거래처 × 문서 번호 × 리비전, 시작일, override(정해진 키만), 적용 품목·품목 클래스(없으면 그 고객 전체)
-- =====================================================================

INSERT INTO sys_code_grp (grp_cd, grp_nm_key, comp_ext_yn) VALUES
    ('CAPA_SOURCE', 'code.grp.CAPA_SOURCE', 'N'),
    ('CAPA_STATUS', 'code.grp.CAPA_STATUS', 'N'),
    ('CAPA_SPREAD_STATUS', 'code.grp.CAPA_SPREAD_STATUS', 'N'),
    ('CAPA_SPREAD_TARGET', 'code.grp.CAPA_SPREAD_TARGET', 'N'),
    ('CAPA_DUE_STATE', 'code.grp.CAPA_DUE_STATE', 'N');
INSERT INTO sys_code (grp_cd, code, code_nm_key, sort_no) VALUES
    ('CAPA_SOURCE', 'CUSTOMER_CLAIM', 'code.CAPA_SOURCE.CUSTOMER_CLAIM', 1),
    ('CAPA_SOURCE', 'INTERNAL',       'code.CAPA_SOURCE.INTERNAL',       2),
    ('CAPA_SOURCE', 'SUPPLIER',       'code.CAPA_SOURCE.SUPPLIER',       3),
    ('CAPA_SOURCE', 'AUDIT',          'code.CAPA_SOURCE.AUDIT',          4),
    ('CAPA_STATUS', 'OPEN',      'code.CAPA_STATUS.OPEN',      1),
    ('CAPA_STATUS', 'CONTAINED', 'code.CAPA_STATUS.CONTAINED', 2),
    ('CAPA_STATUS', 'ANALYZED',  'code.CAPA_STATUS.ANALYZED',  3),
    ('CAPA_STATUS', 'ACTIONED',  'code.CAPA_STATUS.ACTIONED',  4),
    ('CAPA_STATUS', 'VERIFIED',  'code.CAPA_STATUS.VERIFIED',  5),
    ('CAPA_STATUS', 'CLOSING',   'code.CAPA_STATUS.CLOSING',   6),
    ('CAPA_STATUS', 'CLOSED',    'code.CAPA_STATUS.CLOSED',    7),
    ('CAPA_STATUS', 'CANCELLED', 'code.CAPA_STATUS.CANCELLED', 8),
    ('CAPA_SPREAD_STATUS', 'OPEN', 'code.CAPA_SPREAD_STATUS.OPEN', 1),
    ('CAPA_SPREAD_STATUS', 'DONE', 'code.CAPA_SPREAD_STATUS.DONE', 2),
    ('CAPA_SPREAD_STATUS', 'NA',   'code.CAPA_SPREAD_STATUS.NA',   3),
    ('CAPA_SPREAD_TARGET', 'ITEM', 'code.CAPA_SPREAD_TARGET.ITEM', 1),
    ('CAPA_SPREAD_TARGET', 'PROC', 'code.CAPA_SPREAD_TARGET.PROC', 2),
    ('CAPA_SPREAD_TARGET', 'CUST', 'code.CAPA_SPREAD_TARGET.CUST', 3),
    ('CAPA_DUE_STATE', 'ON_TIME',         'code.CAPA_DUE_STATE.ON_TIME',         1),
    ('CAPA_DUE_STATE', 'CONTAIN_OVERDUE', 'code.CAPA_DUE_STATE.CONTAIN_OVERDUE', 2),
    ('CAPA_DUE_STATE', 'CLOSE_OVERDUE',   'code.CAPA_DUE_STATE.CLOSE_OVERDUE',   3);

INSERT INTO sys_policy_def (policy_key, value_type, default_value, allowed_values, min_value, max_value, plant_scope_yn, desc_key) VALUES
    ('QMS_CAPA_CONTAIN_DAYS', 'INT', '2',  NULL, 0, 365, 'N', 'policy.QMS_CAPA_CONTAIN_DAYS'),
    ('QMS_CAPA_CLOSE_DAYS',   'INT', '60', NULL, 1, 3650, 'N', 'policy.QMS_CAPA_CLOSE_DAYS');

-- ---------------------------------------------------------------------
-- 1. 고객 요구사항(CSR)
-- ---------------------------------------------------------------------
CREATE TABLE qms_cust_csr (
    csr_id          bigint        GENERATED ALWAYS AS IDENTITY,
    comp_cd         varchar(20)   NOT NULL,
    cust_id         bigint        NOT NULL,
    csr_no          varchar(50)   NOT NULL,
    csr_rev         varchar(20)   NOT NULL,
    title           varchar(200)  NOT NULL,
    effective_from  date          NOT NULL,
    overrides       jsonb         NOT NULL DEFAULT '{}'::jsonb,
    use_yn          char(1)       NOT NULL DEFAULT 'Y',
    remark          varchar(500),
    row_ver         integer       NOT NULL DEFAULT 0,
    created_at      timestamptz   NOT NULL DEFAULT now(),
    created_by      varchar(50)   NOT NULL,
    updated_at      timestamptz   NOT NULL DEFAULT now(),
    updated_by      varchar(50)   NOT NULL,
    CONSTRAINT pk_qms_cust_csr PRIMARY KEY (csr_id),
    CONSTRAINT fk_qms_cust_csr_comp FOREIGN KEY (comp_cd) REFERENCES sys_comp (comp_cd),
    CONSTRAINT fk_qms_cust_csr_cust FOREIGN KEY (cust_id) REFERENCES mst_cust (cust_id),
    CONSTRAINT uq_qms_cust_csr UNIQUE (comp_cd, cust_id, csr_no, csr_rev),
    CONSTRAINT ck_qms_cust_csr_title CHECK (length(btrim(title)) > 0 AND length(btrim(csr_no)) > 0 AND length(btrim(csr_rev)) > 0),
    CONSTRAINT ck_qms_cust_csr_use_yn CHECK (use_yn IN ('Y', 'N')),
    -- override 는 정해진 키만, 값은 양의 정수 (서비스가 칸 오류로 먼저)
    CONSTRAINT ck_qms_cust_csr_overrides CHECK (jsonb_typeof(overrides) = 'object'
        AND overrides - ARRAY['CONTAIN_DAYS', 'CLOSE_DAYS', 'NOTIFY_HOURS', 'RETENTION_YEARS'] = '{}'::jsonb)
);
CREATE INDEX ix_qms_cust_csr_cust ON qms_cust_csr (comp_cd, cust_id, effective_from DESC);
COMMENT ON TABLE qms_cust_csr IS '고객 요구사항(CSR) — 거래처별 문서·리비전·시작일·override(CONTAIN_DAYS·CLOSE_DAYS·NOTIFY_HOURS·RETENTION_YEARS), 적용 품목/클래스 (docs/29 Q4)';
COMMENT ON COLUMN qms_cust_csr.overrides IS '회사 정책을 이 고객에게만 바꾸는 값. 시정조치 기한은 CONTAIN_DAYS·CLOSE_DAYS 를 쓴다';

CREATE TABLE qms_cust_csr_target (
    target_id      bigint        GENERATED ALWAYS AS IDENTITY,
    comp_cd        varchar(20)   NOT NULL,
    csr_id         bigint        NOT NULL,
    item_id        bigint,
    item_class_id  bigint,
    created_at     timestamptz   NOT NULL DEFAULT now(),
    created_by     varchar(50)   NOT NULL,
    CONSTRAINT pk_qms_cust_csr_target PRIMARY KEY (target_id),
    CONSTRAINT fk_qms_cust_csr_target_comp FOREIGN KEY (comp_cd) REFERENCES sys_comp (comp_cd),
    CONSTRAINT fk_qms_cust_csr_target_csr FOREIGN KEY (csr_id) REFERENCES qms_cust_csr (csr_id),
    CONSTRAINT fk_qms_cust_csr_target_item FOREIGN KEY (item_id) REFERENCES mst_item (item_id),
    CONSTRAINT fk_qms_cust_csr_target_class FOREIGN KEY (item_class_id) REFERENCES mst_item_class (class_id),
    CONSTRAINT ck_qms_cust_csr_target_one CHECK ((item_id IS NULL) <> (item_class_id IS NULL))
);
CREATE UNIQUE INDEX uq_qms_cust_csr_target ON qms_cust_csr_target (csr_id, coalesce(item_id, 0), coalesce(item_class_id, 0));
COMMENT ON TABLE qms_cust_csr_target IS 'CSR 적용 품목·품목 클래스 — 없으면 그 고객 전체. 한 벌로 바꾼다';

CREATE FUNCTION hb_guard_qms_cust_csr() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'CSR cannot be deleted (use use_yn = N)' USING ERRCODE = '55000', CONSTRAINT = 'ck_qms_cust_csr_no_delete';
    END IF;
    IF TG_OP = 'UPDATE' AND (NEW.comp_cd <> OLD.comp_cd OR NEW.cust_id <> OLD.cust_id) THEN
        RAISE EXCEPTION 'CSR % customer cannot be changed', OLD.csr_id USING ERRCODE = '55000', CONSTRAINT = 'ck_qms_cust_csr_key_immutable';
    END IF;
    IF TG_OP = 'INSERT' AND NOT EXISTS (SELECT 1 FROM mst_cust c WHERE c.cust_id = NEW.cust_id AND c.comp_cd = NEW.comp_cd) THEN
        RAISE EXCEPTION 'customer % does not belong to company %', NEW.cust_id, NEW.comp_cd
            USING ERRCODE = '23503', CONSTRAINT = 'fk_qms_cust_csr_cust_comp';
    END IF;
    RETURN NEW;
END
$$;
CREATE TRIGGER tg_qms_cust_csr_guard BEFORE INSERT OR UPDATE OR DELETE ON qms_cust_csr FOR EACH ROW EXECUTE FUNCTION hb_guard_qms_cust_csr();

CREATE FUNCTION hb_guard_qms_cust_csr_target() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RETURN OLD;
    END IF;
    IF TG_OP = 'UPDATE' THEN
        RAISE EXCEPTION 'CSR target cannot be updated (delete and insert)' USING ERRCODE = '55000', CONSTRAINT = 'ck_qms_cust_csr_target_no_update';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM qms_cust_csr c WHERE c.csr_id = NEW.csr_id AND c.comp_cd = NEW.comp_cd)
       OR (NEW.item_id IS NOT NULL AND NOT hb_item_of_comp(NEW.comp_cd, NEW.item_id))
       OR (NEW.item_class_id IS NOT NULL AND NOT EXISTS (
               SELECT 1 FROM mst_item_class k WHERE k.class_id = NEW.item_class_id AND k.comp_cd = NEW.comp_cd)) THEN
        RAISE EXCEPTION 'CSR target does not belong to company %', NEW.comp_cd
            USING ERRCODE = '23503', CONSTRAINT = 'fk_qms_cust_csr_target_comp_ref';
    END IF;
    RETURN NEW;
END
$$;
CREATE TRIGGER tg_qms_cust_csr_target_guard BEFORE INSERT OR UPDATE OR DELETE ON qms_cust_csr_target
    FOR EACH ROW EXECUTE FUNCTION hb_guard_qms_cust_csr_target();

-- ---------------------------------------------------------------------
-- 2. 시정조치(8D)
-- ---------------------------------------------------------------------
CREATE TABLE qms_capa (
    capa_id        bigint         GENERATED ALWAYS AS IDENTITY,
    comp_cd        varchar(20)    NOT NULL,
    capa_no        varchar(30)    NOT NULL,
    source         varchar(16)    NOT NULL,
    cust_id        bigint,
    item_id        bigint,
    title          varchar(200)   NOT NULL,
    occurred_on    date           NOT NULL,
    status         varchar(10)    NOT NULL DEFAULT 'OPEN',
    owner_user_id  bigint         NOT NULL,
    contain_due    date           NOT NULL,
    close_due      date           NOT NULL,
    csr_id         bigint,
    d1_team        varchar(2000),
    d2_problem     varchar(4000),
    d3_contain     varchar(4000),
    d4_cause       varchar(4000),
    d5_action      varchar(4000),
    d6_verify      varchar(4000),
    d7_prevent     varchar(4000),
    d8_close       varchar(2000),
    ncr_id         bigint,
    apr_id         bigint,
    contained_at   timestamptz,
    closed_at      timestamptz,
    closed_by      varchar(50),
    cancel_reason  varchar(500),
    row_ver        integer        NOT NULL DEFAULT 0,
    created_at     timestamptz    NOT NULL DEFAULT now(),
    created_by     varchar(50)    NOT NULL,
    updated_at     timestamptz    NOT NULL DEFAULT now(),
    updated_by     varchar(50)    NOT NULL,
    CONSTRAINT pk_qms_capa PRIMARY KEY (capa_id),
    CONSTRAINT fk_qms_capa_comp FOREIGN KEY (comp_cd) REFERENCES sys_comp (comp_cd),
    CONSTRAINT fk_qms_capa_cust FOREIGN KEY (cust_id) REFERENCES mst_cust (cust_id),
    CONSTRAINT fk_qms_capa_item FOREIGN KEY (item_id) REFERENCES mst_item (item_id),
    CONSTRAINT fk_qms_capa_owner FOREIGN KEY (owner_user_id) REFERENCES sys_user (user_id),
    CONSTRAINT fk_qms_capa_csr FOREIGN KEY (csr_id) REFERENCES qms_cust_csr (csr_id),
    CONSTRAINT uq_qms_capa_no UNIQUE (comp_cd, capa_no),
    CONSTRAINT ck_qms_capa_source CHECK (source IN ('CUSTOMER_CLAIM', 'INTERNAL', 'SUPPLIER', 'AUDIT')),
    -- 고객 클레임·협력사는 거래처 필수
    CONSTRAINT ck_qms_capa_cust CHECK (source NOT IN ('CUSTOMER_CLAIM', 'SUPPLIER') OR cust_id IS NOT NULL),
    CONSTRAINT ck_qms_capa_title CHECK (length(btrim(title)) > 0),
    CONSTRAINT ck_qms_capa_status CHECK (status IN ('OPEN', 'CONTAINED', 'ANALYZED', 'ACTIONED', 'VERIFIED', 'CLOSING', 'CLOSED', 'CANCELLED')),
    CONSTRAINT ck_qms_capa_due CHECK (contain_due >= occurred_on AND close_due >= contain_due),
    -- 단계마다 채워야 할 D 구역 (CHECK 는 NULL 을 통과로 보므로 coalesce 로 길이)
    CONSTRAINT ck_qms_capa_d3 CHECK (status IN ('OPEN', 'CANCELLED') OR (coalesce(length(btrim(d1_team)), 0) > 0
        AND coalesce(length(btrim(d2_problem)), 0) > 0 AND coalesce(length(btrim(d3_contain)), 0) > 0)),
    CONSTRAINT ck_qms_capa_d4 CHECK (status IN ('OPEN', 'CONTAINED', 'CANCELLED') OR coalesce(length(btrim(d4_cause)), 0) > 0),
    CONSTRAINT ck_qms_capa_d6 CHECK (status IN ('OPEN', 'CONTAINED', 'ANALYZED', 'CANCELLED')
        OR (coalesce(length(btrim(d5_action)), 0) > 0 AND coalesce(length(btrim(d6_verify)), 0) > 0)),
    CONSTRAINT ck_qms_capa_d7 CHECK (status NOT IN ('VERIFIED', 'CLOSING', 'CLOSED') OR coalesce(length(btrim(d7_prevent)), 0) > 0),
    CONSTRAINT ck_qms_capa_d8 CHECK (status NOT IN ('CLOSING', 'CLOSED') OR coalesce(length(btrim(d8_close)), 0) > 0),
    CONSTRAINT ck_qms_capa_closed CHECK (status <> 'CLOSED' OR (closed_at IS NOT NULL AND closed_by IS NOT NULL)),
    CONSTRAINT ck_qms_capa_closing CHECK (status <> 'CLOSING' OR apr_id IS NOT NULL),
    CONSTRAINT ck_qms_capa_cancel CHECK (status <> 'CANCELLED' OR coalesce(length(btrim(cancel_reason)), 0) > 0)
);
CREATE INDEX ix_qms_capa_status ON qms_capa (comp_cd, status, close_due);
CREATE INDEX ix_qms_capa_cust ON qms_capa (comp_cd, cust_id) WHERE cust_id IS NOT NULL;
COMMENT ON TABLE qms_capa IS '시정조치(8D) — D1~D8 텍스트 구역, 단계별 필수 CHECK, 종결 결재(QMS_CAPA), 종결·취소 뒤 불변 (docs/29 Q4, D8)';
COMMENT ON COLUMN qms_capa.contain_due IS '초기 대응 기한 — 만들 때 발생일 + 정책(고객 CSR CONTAIN_DAYS 가 이김) 스냅샷';
COMMENT ON COLUMN qms_capa.ncr_id IS '2부 부적합(NCR) 연결 — 자리만, FK 는 2부';
COMMENT ON COLUMN qms_capa.csr_id IS '기한을 정할 때 쓴 고객 CSR (근거 표시)';

CREATE FUNCTION hb_guard_qms_capa() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'CAPA cannot be deleted (cancel it)' USING ERRCODE = '55000', CONSTRAINT = 'ck_qms_capa_no_delete';
    END IF;
    IF TG_OP = 'INSERT' THEN
        IF NEW.status <> 'OPEN' THEN
            RAISE EXCEPTION 'new CAPA must be OPEN' USING ERRCODE = '55000', CONSTRAINT = 'ck_qms_capa_open';
        END IF;
    ELSE
        IF NEW.comp_cd <> OLD.comp_cd OR NEW.capa_no <> OLD.capa_no THEN
            RAISE EXCEPTION 'CAPA % key cannot be changed', OLD.capa_id USING ERRCODE = '55000', CONSTRAINT = 'ck_qms_capa_immutable';
        END IF;
        IF OLD.status IN ('CLOSED', 'CANCELLED') THEN
            RAISE EXCEPTION '% CAPA % cannot be changed', OLD.status, OLD.capa_id USING ERRCODE = '55000', CONSTRAINT = 'ck_qms_capa_immutable';
        END IF;
        -- 전이 표: 한 단계씩 앞으로, 결재 중 → 종결/ACTIONED(반려·회수), 결재 없는 종결(VERIFIED → CLOSED), 어디서나 취소(결재 중 제외)
        IF OLD.status <> NEW.status AND NOT (
               (OLD.status = 'OPEN' AND NEW.status = 'CONTAINED')
            OR (OLD.status = 'CONTAINED' AND NEW.status = 'ANALYZED')
            OR (OLD.status = 'ANALYZED' AND NEW.status = 'ACTIONED')
            OR (OLD.status = 'ACTIONED' AND NEW.status = 'VERIFIED')
            OR (OLD.status = 'VERIFIED' AND NEW.status IN ('CLOSING', 'CLOSED'))
            OR (OLD.status = 'CLOSING' AND NEW.status IN ('CLOSED', 'ACTIONED'))
            OR (OLD.status NOT IN ('CLOSING') AND NEW.status = 'CANCELLED')) THEN
            RAISE EXCEPTION 'CAPA status % -> % is not allowed', OLD.status, NEW.status
                USING ERRCODE = '55000', CONSTRAINT = 'ck_qms_capa_transition';
        END IF;
        -- 결재 중에는 내용 고정(상태·결재·종결 칸만)
        IF OLD.status = 'CLOSING' AND (NEW.title, NEW.d1_team, NEW.d2_problem, NEW.d3_contain, NEW.d4_cause, NEW.d5_action,
                NEW.d6_verify, NEW.d7_prevent, NEW.d8_close, NEW.owner_user_id, NEW.cust_id, NEW.item_id, NEW.occurred_on,
                NEW.contain_due, NEW.close_due)
             IS DISTINCT FROM (OLD.title, OLD.d1_team, OLD.d2_problem, OLD.d3_contain, OLD.d4_cause, OLD.d5_action,
                OLD.d6_verify, OLD.d7_prevent, OLD.d8_close, OLD.owner_user_id, OLD.cust_id, OLD.item_id, OLD.occurred_on,
                OLD.contain_due, OLD.close_due) THEN
            RAISE EXCEPTION 'CAPA % is in closing approval', OLD.capa_id USING ERRCODE = '55000', CONSTRAINT = 'ck_qms_capa_closing_locked';
        END IF;
        -- 출처·기한은 만들 때 정한 값(기한은 근거 스냅샷)
        IF NEW.source <> OLD.source OR NEW.contain_due <> OLD.contain_due OR NEW.close_due <> OLD.close_due
           OR NEW.csr_id IS DISTINCT FROM OLD.csr_id THEN
            RAISE EXCEPTION 'CAPA % source/due dates are fixed', OLD.capa_id USING ERRCODE = '55000', CONSTRAINT = 'ck_qms_capa_due_fixed';
        END IF;
    END IF;
    -- 같은 회사 거래처·품목·담당자(새 참조)
    IF NEW.cust_id IS NOT NULL AND (TG_OP = 'INSERT' OR NEW.cust_id IS DISTINCT FROM OLD.cust_id)
       AND NOT EXISTS (SELECT 1 FROM mst_cust c WHERE c.cust_id = NEW.cust_id AND c.comp_cd = NEW.comp_cd) THEN
        RAISE EXCEPTION 'customer % does not belong to company %', NEW.cust_id, NEW.comp_cd
            USING ERRCODE = '23503', CONSTRAINT = 'fk_qms_capa_cust_comp';
    END IF;
    IF NEW.item_id IS NOT NULL AND (TG_OP = 'INSERT' OR NEW.item_id IS DISTINCT FROM OLD.item_id)
       AND NOT hb_item_of_comp(NEW.comp_cd, NEW.item_id) THEN
        RAISE EXCEPTION 'item % does not belong to company %', NEW.item_id, NEW.comp_cd
            USING ERRCODE = '23503', CONSTRAINT = 'fk_qms_capa_item_comp';
    END IF;
    IF (TG_OP = 'INSERT' OR NEW.owner_user_id <> OLD.owner_user_id) AND NOT hb_user_of_comp(NEW.comp_cd, NEW.owner_user_id) THEN
        RAISE EXCEPTION 'user % does not belong to company %', NEW.owner_user_id, NEW.comp_cd
            USING ERRCODE = '23503', CONSTRAINT = 'fk_qms_capa_owner_comp';
    END IF;
    RETURN NEW;
END
$$;
CREATE TRIGGER tg_qms_capa_guard BEFORE INSERT OR UPDATE OR DELETE ON qms_capa FOR EACH ROW EXECUTE FUNCTION hb_guard_qms_capa();

-- 수평전개 — 같은 원인이 있을 만한 다른 품목·공정·거래처. 종결 전까지 한 벌 저장, VERIFIED 로 가려면 OPEN 행이 없어야(서비스 + 아래 트리거)
CREATE TABLE qms_capa_spread (
    spread_id    bigint        GENERATED ALWAYS AS IDENTITY,
    comp_cd      varchar(20)   NOT NULL,
    capa_id      bigint        NOT NULL,
    line_no      integer       NOT NULL,
    target_type  varchar(4)    NOT NULL,
    item_id      bigint,
    proc_id      bigint,
    cust_id      bigint,
    status       varchar(4)    NOT NULL DEFAULT 'OPEN',
    note         varchar(500),
    created_at   timestamptz   NOT NULL DEFAULT now(),
    created_by   varchar(50)   NOT NULL,
    CONSTRAINT pk_qms_capa_spread PRIMARY KEY (spread_id),
    CONSTRAINT fk_qms_capa_spread_comp FOREIGN KEY (comp_cd) REFERENCES sys_comp (comp_cd),
    CONSTRAINT fk_qms_capa_spread_capa FOREIGN KEY (capa_id) REFERENCES qms_capa (capa_id),
    CONSTRAINT fk_qms_capa_spread_item FOREIGN KEY (item_id) REFERENCES mst_item (item_id),
    CONSTRAINT fk_qms_capa_spread_proc FOREIGN KEY (proc_id) REFERENCES mst_proc (proc_id),
    CONSTRAINT fk_qms_capa_spread_cust FOREIGN KEY (cust_id) REFERENCES mst_cust (cust_id),
    CONSTRAINT uq_qms_capa_spread_no UNIQUE (capa_id, line_no),
    CONSTRAINT ck_qms_capa_spread_target CHECK (
        (target_type = 'ITEM' AND item_id IS NOT NULL AND proc_id IS NULL AND cust_id IS NULL)
        OR (target_type = 'PROC' AND item_id IS NULL AND proc_id IS NOT NULL AND cust_id IS NULL)
        OR (target_type = 'CUST' AND item_id IS NULL AND proc_id IS NULL AND cust_id IS NOT NULL)),
    CONSTRAINT ck_qms_capa_spread_status CHECK (status IN ('OPEN', 'DONE', 'NA'))
);
COMMENT ON TABLE qms_capa_spread IS '시정조치 수평전개 대상 — 머리가 진행 중일 때만 바뀐다(한 벌 저장)';

CREATE FUNCTION hb_guard_qms_capa_spread() RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    v_row  qms_capa_spread;
    v_head qms_capa;
BEGIN
    v_row := CASE WHEN TG_OP = 'DELETE' THEN OLD ELSE NEW END;
    SELECT * INTO v_head FROM qms_capa WHERE capa_id = v_row.capa_id;
    IF NOT FOUND OR v_head.comp_cd <> v_row.comp_cd THEN
        RAISE EXCEPTION 'CAPA % does not belong to company %', v_row.capa_id, v_row.comp_cd
            USING ERRCODE = '23503', CONSTRAINT = 'fk_qms_capa_spread_capa_comp';
    END IF;
    IF v_head.status IN ('VERIFIED', 'CLOSING', 'CLOSED', 'CANCELLED') THEN
        RAISE EXCEPTION 'spread of % CAPA % cannot be changed', v_head.status, v_row.capa_id
            USING ERRCODE = '55000', CONSTRAINT = 'ck_qms_capa_spread_locked';
    END IF;
    IF TG_OP = 'DELETE' THEN
        RETURN OLD;
    END IF;
    IF TG_OP = 'UPDATE' THEN
        RAISE EXCEPTION 'spread rows are replaced as a set' USING ERRCODE = '55000', CONSTRAINT = 'ck_qms_capa_spread_no_update';
    END IF;
    IF (NEW.item_id IS NOT NULL AND NOT hb_item_of_comp(NEW.comp_cd, NEW.item_id))
       OR (NEW.proc_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM mst_proc p WHERE p.proc_id = NEW.proc_id AND p.comp_cd = NEW.comp_cd))
       OR (NEW.cust_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM mst_cust c WHERE c.cust_id = NEW.cust_id AND c.comp_cd = NEW.comp_cd)) THEN
        RAISE EXCEPTION 'spread target does not belong to company %', NEW.comp_cd
            USING ERRCODE = '23503', CONSTRAINT = 'fk_qms_capa_spread_comp_ref';
    END IF;
    RETURN NEW;
END
$$;
CREATE TRIGGER tg_qms_capa_spread_guard BEFORE INSERT OR UPDATE OR DELETE ON qms_capa_spread
    FOR EACH ROW EXECUTE FUNCTION hb_guard_qms_capa_spread();

-- 효과 검증(VERIFIED)으로 가려면 수평전개에 OPEN 이 없어야 한다 — 머리 트리거와 다른 이름(뒤)으로 두어 전이 검사가 먼저
CREATE FUNCTION hb_check_qms_capa_verify() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.status = 'VERIFIED' AND OLD.status <> 'VERIFIED'
       AND EXISTS (SELECT 1 FROM qms_capa_spread s WHERE s.capa_id = NEW.capa_id AND s.status = 'OPEN') THEN
        RAISE EXCEPTION 'CAPA % has open spread rows', NEW.capa_id USING ERRCODE = '23514', CONSTRAINT = 'ck_qms_capa_spread_open';
    END IF;
    RETURN NEW;
END
$$;
CREATE TRIGGER tg_qms_capa_zverify BEFORE UPDATE ON qms_capa FOR EACH ROW EXECUTE FUNCTION hb_check_qms_capa_verify();

-- ---------------------------------------------------------------------
-- 3. 감사 · RLS · 권한 · 화면 · 보존
-- ---------------------------------------------------------------------
CREATE TRIGGER tg_audit AFTER INSERT OR UPDATE OR DELETE ON qms_cust_csr FOR EACH ROW EXECUTE FUNCTION hb_audit('csr_id', '', '');
CREATE TRIGGER tg_audit AFTER INSERT OR UPDATE OR DELETE ON qms_cust_csr_target FOR EACH ROW EXECUTE FUNCTION hb_audit('target_id', '', '');
CREATE TRIGGER tg_audit AFTER INSERT OR UPDATE OR DELETE ON qms_capa FOR EACH ROW EXECUTE FUNCTION hb_audit('capa_id', '', '');
CREATE TRIGGER tg_audit AFTER INSERT OR UPDATE OR DELETE ON qms_capa_spread FOR EACH ROW EXECUTE FUNCTION hb_audit('spread_id', '', '');

ALTER TABLE qms_cust_csr ENABLE ROW LEVEL SECURITY;
ALTER TABLE qms_cust_csr FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON qms_cust_csr USING (comp_cd = hb_current_comp()) WITH CHECK (comp_cd = hb_current_comp());
ALTER TABLE qms_cust_csr_target ENABLE ROW LEVEL SECURITY;
ALTER TABLE qms_cust_csr_target FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON qms_cust_csr_target USING (comp_cd = hb_current_comp()) WITH CHECK (comp_cd = hb_current_comp());
ALTER TABLE qms_capa ENABLE ROW LEVEL SECURITY;
ALTER TABLE qms_capa FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON qms_capa USING (comp_cd = hb_current_comp()) WITH CHECK (comp_cd = hb_current_comp());
ALTER TABLE qms_capa_spread ENABLE ROW LEVEL SECURITY;
ALTER TABLE qms_capa_spread FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON qms_capa_spread USING (comp_cd = hb_current_comp()) WITH CHECK (comp_cd = hb_current_comp());

REVOKE DELETE, TRUNCATE ON qms_cust_csr, qms_capa FROM ${app_role};
REVOKE TRUNCATE ON qms_cust_csr_target, qms_capa_spread FROM ${app_role};

INSERT INTO sys_prog (prog_cd, prog_nm_key, view_path, module_cd, actions, sort_no) VALUES
    ('qms-capa', 'prog.qmsCapa', 'qms/capa-mgt', 'QMS', ARRAY['SEARCH','SAVE','APPROVE','EXCEL']::varchar(20)[], 660),
    ('qms-csr',  'prog.qmsCsr',  'qms/csr-mgt',  'QMS', ARRAY['SEARCH','SAVE']::varchar(20)[], 670);
-- 8D 는 품질기록, CSR 은 기준정보
INSERT INTO sys_retention_table (table_nm, class_cd) VALUES
    ('qms_capa', 'QUALITY_RECORD'), ('qms_capa_spread', 'QUALITY_RECORD'),
    ('qms_cust_csr', 'MASTER'), ('qms_cust_csr_target', 'MASTER');
