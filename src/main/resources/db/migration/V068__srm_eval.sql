-- =====================================================================
-- V9501 SRM 협력사 평가 (P5 SRM 1부 S6 — docs/31 2장·4.6, 결정 D10·D11). 임시 번호 — 병합 때 상위 세션이 이름만 바꾼다
--
-- - srm_eval_tmpl: 평가 템플릿 리비전(BOM 규칙: DRAFT → RELEASED → OBSOLETE, 확정본끼리 기간 겹침 배제, 템플릿마다 작성 중 하나)
-- - srm_eval_item: 항목(구역·항목·배점·가중치·과락 점수·입력 MANUAL / AUTO 지표) / srm_eval_grade: 등급 구간(최소 점수·HOLD 제안)
--     작성 중 템플릿만 편집. 확정 = 항목 1+ · 가중치 합 100 · 최소 점수 0 인 등급 필수(트리거가 최종)
-- - srm_eval: 평가 실행(협력사 × 기간 × 확정 템플릿 리비전, DRAFT → CONFIRMED / CANCELLED, 확정 뒤 불변)
--     확정 때 총점 = Σ(점수 / 배점 × 가중치) 반올림 2자리, 과락 = 과락 점수 미만 항목 하나라도, 등급 = 최소 점수 ≤ 총점 중 가장 높은 구간
--     → 트리거가 같은 식으로 다시 계산해 서비스 값과 맞는지 본다(직접 SQL 방어)
-- - srm_eval_score: 항목별 점수(AUTO 는 계산값·근거 글, 1부에서 OTD_RATE·RECV_PPM 은 수동)
-- =====================================================================

INSERT INTO sys_code_grp (grp_cd, grp_nm_key, comp_ext_yn) VALUES
    ('EVAL_STATUS', 'code.grp.EVAL_STATUS', 'N'),
    ('EVAL_METRIC', 'code.grp.EVAL_METRIC', 'N');
INSERT INTO sys_code (grp_cd, code, code_nm_key, sort_no) VALUES
    ('EVAL_STATUS', 'DRAFT',      'code.EVAL_STATUS.DRAFT',      1),
    ('EVAL_STATUS', 'CONFIRMED',  'code.EVAL_STATUS.CONFIRMED',  2),
    ('EVAL_STATUS', 'CANCELLED',  'code.EVAL_STATUS.CANCELLED',  3),
    ('EVAL_METRIC', 'ACK_RATE',   'code.EVAL_METRIC.ACK_RATE',   1),
    ('EVAL_METRIC', 'CERT_VALID', 'code.EVAL_METRIC.CERT_VALID', 2),
    ('EVAL_METRIC', 'OTD_RATE',   'code.EVAL_METRIC.OTD_RATE',   3),
    ('EVAL_METRIC', 'RECV_PPM',   'code.EVAL_METRIC.RECV_PPM',   4);

-- ---------------------------------------------------------------------
-- 1. 템플릿 머리 (리비전)
-- ---------------------------------------------------------------------
CREATE TABLE srm_eval_tmpl (
    tmpl_id      bigint         GENERATED ALWAYS AS IDENTITY,
    comp_cd      varchar(20)    NOT NULL,
    tmpl_cd      varchar(30)    NOT NULL,
    rev          integer        NOT NULL DEFAULT 0,
    tmpl_nm      varchar(200)   NOT NULL,
    status       varchar(10)    NOT NULL DEFAULT 'DRAFT',
    valid_from   date           NOT NULL,
    valid_to     date,
    remark       varchar(1000),
    released_by  varchar(50),
    released_at  timestamptz,
    row_ver      integer        NOT NULL DEFAULT 0,
    created_at   timestamptz    NOT NULL DEFAULT now(),
    created_by   varchar(50)    NOT NULL,
    updated_at   timestamptz    NOT NULL DEFAULT now(),
    updated_by   varchar(50)    NOT NULL,
    CONSTRAINT pk_srm_eval_tmpl PRIMARY KEY (tmpl_id),
    CONSTRAINT fk_srm_eval_tmpl_comp FOREIGN KEY (comp_cd) REFERENCES sys_comp (comp_cd),
    CONSTRAINT uq_srm_eval_tmpl_rev UNIQUE (comp_cd, tmpl_cd, rev),
    CONSTRAINT ck_srm_eval_tmpl_cd CHECK (tmpl_cd ~ '^[A-Z0-9][A-Z0-9_-]{0,29}$'),
    CONSTRAINT ck_srm_eval_tmpl_rev CHECK (rev BETWEEN 0 AND 999),
    CONSTRAINT ck_srm_eval_tmpl_status CHECK (status IN ('DRAFT', 'RELEASED', 'OBSOLETE')),
    CONSTRAINT ck_srm_eval_tmpl_period CHECK (valid_from BETWEEN DATE '2000-01-01' AND DATE '2999-12-31'
                                              AND (valid_to IS NULL OR valid_to BETWEEN valid_from AND DATE '2999-12-31')),
    CONSTRAINT ck_srm_eval_tmpl_released CHECK (status <> 'RELEASED' OR (released_by IS NOT NULL AND released_at IS NOT NULL)),
    CONSTRAINT ex_srm_eval_tmpl_period EXCLUDE USING gist (comp_cd WITH =, tmpl_cd WITH =, daterange(valid_from, valid_to, '[]') WITH &&)
        WHERE (status = 'RELEASED')
);
CREATE UNIQUE INDEX uq_srm_eval_tmpl_draft ON srm_eval_tmpl (comp_cd, tmpl_cd) WHERE status = 'DRAFT';
COMMENT ON TABLE srm_eval_tmpl IS '협력사 평가 템플릿 리비전 (docs/31 S6, D10) — BOM 리비전 규칙. 작성 중만 편집, 확정본끼리 기간 겹침 배제';

CREATE FUNCTION hb_guard_srm_eval_tmpl() RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    v_weight numeric;
BEGIN
    IF TG_OP = 'INSERT' THEN
        IF NEW.status <> 'DRAFT' THEN
            RAISE EXCEPTION 'new evaluation template must be DRAFT' USING ERRCODE = '55000', CONSTRAINT = 'ck_srm_eval_tmpl_transition';
        END IF;
        RETURN NEW;
    END IF;
    IF NEW.comp_cd <> OLD.comp_cd OR NEW.tmpl_cd <> OLD.tmpl_cd OR NEW.rev <> OLD.rev THEN
        RAISE EXCEPTION 'template code and revision are fixed' USING ERRCODE = '55000', CONSTRAINT = 'ck_srm_eval_tmpl_fixed';
    END IF;
    IF NEW.status <> OLD.status AND NOT (
           (OLD.status = 'DRAFT'    AND NEW.status IN ('RELEASED', 'OBSOLETE'))
        OR (OLD.status = 'RELEASED' AND NEW.status = 'OBSOLETE')) THEN
        RAISE EXCEPTION 'evaluation template cannot go from % to %', OLD.status, NEW.status
            USING ERRCODE = '55000', CONSTRAINT = 'ck_srm_eval_tmpl_transition';
    END IF;
    -- 확정본·폐기본: 내용 불변. 확정본은 끝 없는 기간을 한 번 마감(valid_to NULL → 날짜)할 수 있다 — 새 리비전 확정 때
    IF OLD.status <> 'DRAFT' AND (NEW.tmpl_nm IS DISTINCT FROM OLD.tmpl_nm OR NEW.valid_from IS DISTINCT FROM OLD.valid_from
            OR NEW.remark IS DISTINCT FROM OLD.remark OR NEW.released_by IS DISTINCT FROM OLD.released_by
            OR NEW.released_at IS DISTINCT FROM OLD.released_at
            OR (NEW.valid_to IS DISTINCT FROM OLD.valid_to AND NOT (OLD.status = 'RELEASED' AND OLD.valid_to IS NULL))) THEN
        RAISE EXCEPTION '% evaluation template cannot be edited', OLD.status USING ERRCODE = '55000', CONSTRAINT = 'ck_srm_eval_tmpl_draft';
    END IF;
    IF OLD.status = 'DRAFT' AND NEW.status = 'RELEASED' THEN
        IF NOT EXISTS (SELECT 1 FROM srm_eval_item i WHERE i.tmpl_id = NEW.tmpl_id) THEN
            RAISE EXCEPTION 'template has no items' USING ERRCODE = '55000', CONSTRAINT = 'ck_srm_eval_tmpl_items';
        END IF;
        SELECT sum(weight) INTO v_weight FROM srm_eval_item i WHERE i.tmpl_id = NEW.tmpl_id;
        IF v_weight <> 100 THEN
            RAISE EXCEPTION 'weights add up to %, not 100', v_weight USING ERRCODE = '55000', CONSTRAINT = 'ck_srm_eval_tmpl_weight';
        END IF;
        IF NOT EXISTS (SELECT 1 FROM srm_eval_grade g WHERE g.tmpl_id = NEW.tmpl_id AND g.min_score = 0) THEN
            RAISE EXCEPTION 'template needs a grade starting at 0' USING ERRCODE = '55000', CONSTRAINT = 'ck_srm_eval_tmpl_grades';
        END IF;
    END IF;
    RETURN NEW;
END
$$;
CREATE TRIGGER tg_srm_eval_tmpl_guard BEFORE INSERT OR UPDATE ON srm_eval_tmpl FOR EACH ROW EXECUTE FUNCTION hb_guard_srm_eval_tmpl();
CREATE TRIGGER tg_srm_eval_tmpl_no_delete BEFORE DELETE ON srm_eval_tmpl FOR EACH ROW EXECUTE FUNCTION hb_forbid_delete_master();

-- 작성 중 템플릿의 항목·등급만 바꿀 수 있다 (항목·등급 공용)
CREATE FUNCTION hb_guard_srm_eval_tmpl_child() RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    v_tmpl bigint;
    v_comp varchar;
    v_head record;
BEGIN
    v_tmpl := CASE WHEN TG_OP = 'DELETE' THEN OLD.tmpl_id ELSE NEW.tmpl_id END;
    v_comp := CASE WHEN TG_OP = 'DELETE' THEN OLD.comp_cd ELSE NEW.comp_cd END;
    IF TG_OP = 'UPDATE' AND NEW.tmpl_id <> OLD.tmpl_id THEN
        RAISE EXCEPTION 'row cannot move to another template' USING ERRCODE = '55000', CONSTRAINT = 'ck_srm_eval_tmpl_child_move';
    END IF;
    SELECT t.comp_cd, t.status INTO v_head FROM srm_eval_tmpl t WHERE t.tmpl_id = v_tmpl;
    IF NOT FOUND OR v_head.comp_cd <> v_comp THEN
        RAISE EXCEPTION 'template % does not belong to company %', v_tmpl, v_comp
            USING ERRCODE = '23503', CONSTRAINT = 'fk_srm_eval_tmpl_child_comp';
    END IF;
    IF v_head.status <> 'DRAFT' THEN
        RAISE EXCEPTION 'rows of % template % cannot be changed', v_head.status, v_tmpl
            USING ERRCODE = '55000', CONSTRAINT = 'ck_srm_eval_tmpl_child_draft';
    END IF;
    RETURN CASE WHEN TG_OP = 'DELETE' THEN OLD ELSE NEW END;
END
$$;

-- ---------------------------------------------------------------------
-- 2. 항목 · 등급 구간
-- ---------------------------------------------------------------------
CREATE TABLE srm_eval_item (
    item_id      bigint         GENERATED ALWAYS AS IDENTITY,
    comp_cd      varchar(20)    NOT NULL,
    tmpl_id      bigint         NOT NULL,
    item_no      integer        NOT NULL,
    section_nm   varchar(100),
    item_nm      varchar(200)   NOT NULL,
    max_score    numeric(7,2)   NOT NULL,
    weight       numeric(5,2)   NOT NULL,
    fail_below   numeric(7,2),
    input_type   varchar(10)    NOT NULL DEFAULT 'MANUAL',
    metric_cd    varchar(20),
    guide        varchar(1000),
    created_at   timestamptz    NOT NULL DEFAULT now(),
    created_by   varchar(50)    NOT NULL,
    updated_at   timestamptz    NOT NULL DEFAULT now(),
    updated_by   varchar(50)    NOT NULL,
    CONSTRAINT pk_srm_eval_item PRIMARY KEY (item_id),
    CONSTRAINT fk_srm_eval_item_tmpl FOREIGN KEY (tmpl_id) REFERENCES srm_eval_tmpl (tmpl_id),
    CONSTRAINT uq_srm_eval_item_no UNIQUE (comp_cd, tmpl_id, item_no),
    CONSTRAINT ck_srm_eval_item_no CHECK (item_no BETWEEN 1 AND 999),
    CONSTRAINT ck_srm_eval_item_max CHECK (max_score > 0),
    CONSTRAINT ck_srm_eval_item_weight CHECK (weight > 0 AND weight <= 100),
    CONSTRAINT ck_srm_eval_item_fail CHECK (fail_below IS NULL OR (fail_below > 0 AND fail_below <= max_score)),
    CONSTRAINT ck_srm_eval_item_input CHECK (input_type IN ('MANUAL', 'AUTO')),
    CONSTRAINT ck_srm_eval_item_metric CHECK ((input_type = 'AUTO') = (metric_cd IS NOT NULL)
                                              AND (metric_cd IS NULL OR metric_cd IN ('OTD_RATE', 'RECV_PPM', 'CERT_VALID', 'ACK_RATE')))
);
COMMENT ON TABLE srm_eval_item IS '평가 항목 — 점수 / 배점 × 가중치(합 100), 과락 점수 미만이면 등급과 관계없이 과락. AUTO = 지표 키(1부 계산: ACK_RATE·CERT_VALID)';

CREATE TABLE srm_eval_grade (
    grade_id         bigint        GENERATED ALWAYS AS IDENTITY,
    comp_cd          varchar(20)   NOT NULL,
    tmpl_id          bigint        NOT NULL,
    grade_cd         varchar(10)   NOT NULL,
    min_score        numeric(5,2)  NOT NULL,
    suggest_hold_yn  char(1)       NOT NULL DEFAULT 'N',
    created_at       timestamptz   NOT NULL DEFAULT now(),
    created_by       varchar(50)   NOT NULL,
    updated_at       timestamptz   NOT NULL DEFAULT now(),
    updated_by       varchar(50)   NOT NULL,
    CONSTRAINT pk_srm_eval_grade PRIMARY KEY (grade_id),
    CONSTRAINT fk_srm_eval_grade_tmpl FOREIGN KEY (tmpl_id) REFERENCES srm_eval_tmpl (tmpl_id),
    CONSTRAINT uq_srm_eval_grade_cd UNIQUE (comp_cd, tmpl_id, grade_cd),
    CONSTRAINT uq_srm_eval_grade_min UNIQUE (comp_cd, tmpl_id, min_score),
    CONSTRAINT ck_srm_eval_grade_cd CHECK (grade_cd ~ '^[A-Z][A-Z0-9+-]{0,9}$'),
    CONSTRAINT ck_srm_eval_grade_min CHECK (min_score BETWEEN 0 AND 100),
    CONSTRAINT ck_srm_eval_grade_hold CHECK (suggest_hold_yn IN ('Y', 'N'))
);
COMMENT ON TABLE srm_eval_grade IS '등급 구간 — 총점이 최소 점수 이상인 구간 중 가장 높은 것. HOLD 제안 = 그 등급이면 거래 보류를 제안(D11, 사람이 확정)';

CREATE TRIGGER tg_srm_eval_item_guard BEFORE INSERT OR UPDATE OR DELETE ON srm_eval_item
    FOR EACH ROW EXECUTE FUNCTION hb_guard_srm_eval_tmpl_child();
CREATE TRIGGER tg_srm_eval_grade_guard BEFORE INSERT OR UPDATE OR DELETE ON srm_eval_grade
    FOR EACH ROW EXECUTE FUNCTION hb_guard_srm_eval_tmpl_child();

-- ---------------------------------------------------------------------
-- 3. 평가 실행
-- ---------------------------------------------------------------------
CREATE TABLE srm_eval (
    eval_id               bigint         GENERATED ALWAYS AS IDENTITY,
    comp_cd               varchar(20)    NOT NULL,
    eval_no               varchar(30)    NOT NULL,
    cust_id               bigint         NOT NULL,
    tmpl_id               bigint         NOT NULL,
    period_from           date           NOT NULL,
    period_to             date           NOT NULL,
    status                varchar(10)    NOT NULL DEFAULT 'DRAFT',
    total_score           numeric(6,2),
    grade_cd              varchar(10),
    fail_yn               char(1),
    suggest_trade_status  varchar(10),
    confirmed_by          varchar(50),
    confirmed_at          timestamptz,
    cancel_reason         varchar(500),
    remark                varchar(1000),
    row_ver               integer        NOT NULL DEFAULT 0,
    created_at            timestamptz    NOT NULL DEFAULT now(),
    created_by            varchar(50)    NOT NULL,
    updated_at            timestamptz    NOT NULL DEFAULT now(),
    updated_by            varchar(50)    NOT NULL,
    CONSTRAINT pk_srm_eval PRIMARY KEY (eval_id),
    CONSTRAINT fk_srm_eval_comp FOREIGN KEY (comp_cd) REFERENCES sys_comp (comp_cd),
    CONSTRAINT fk_srm_eval_supplier FOREIGN KEY (cust_id) REFERENCES srm_supplier (cust_id),
    CONSTRAINT fk_srm_eval_tmpl FOREIGN KEY (tmpl_id) REFERENCES srm_eval_tmpl (tmpl_id),
    CONSTRAINT uq_srm_eval_no UNIQUE (comp_cd, eval_no),
    CONSTRAINT ck_srm_eval_status CHECK (status IN ('DRAFT', 'CONFIRMED', 'CANCELLED')),
    CONSTRAINT ck_srm_eval_period CHECK (period_from BETWEEN DATE '2000-01-01' AND DATE '2999-12-31' AND period_to >= period_from
                                         AND period_to <= DATE '2999-12-31'),
    CONSTRAINT ck_srm_eval_confirmed CHECK (status <> 'CONFIRMED' OR (total_score IS NOT NULL AND grade_cd IS NOT NULL
                                            AND fail_yn IS NOT NULL AND confirmed_by IS NOT NULL AND confirmed_at IS NOT NULL)),
    CONSTRAINT ck_srm_eval_fail_yn CHECK (fail_yn IS NULL OR fail_yn IN ('Y', 'N')),
    CONSTRAINT ck_srm_eval_suggest CHECK (suggest_trade_status IS NULL OR suggest_trade_status = 'HOLD'),
    CONSTRAINT ck_srm_eval_cancel CHECK (status <> 'CANCELLED' OR coalesce(length(btrim(cancel_reason)), 0) > 0)
);
CREATE UNIQUE INDEX uq_srm_eval_period ON srm_eval (comp_cd, cust_id, tmpl_id, period_from, period_to) WHERE status <> 'CANCELLED';
CREATE INDEX ix_srm_eval_supplier ON srm_eval (comp_cd, cust_id, period_to);
COMMENT ON TABLE srm_eval IS '협력사 평가 (docs/31 S6) — 협력사 × 기간 × 확정 템플릿 리비전. 확정(APPROVE) 뒤 불변, 같은 협력사·기간·템플릿 중복 금지';
COMMENT ON COLUMN srm_eval.suggest_trade_status IS 'HOLD = 과락 또는 HOLD 제안 등급 — 제안만(D11), 거래 상태는 사람이 협력사 정보에서 바꾼다';

CREATE TABLE srm_eval_score (
    score_id    bigint         GENERATED ALWAYS AS IDENTITY,
    comp_cd     varchar(20)    NOT NULL,
    eval_id     bigint         NOT NULL,
    item_id     bigint         NOT NULL,
    score       numeric(7,2),
    raw_value   numeric(18,4),
    auto_yn     char(1)        NOT NULL DEFAULT 'N',
    basis       varchar(500),
    comment     varchar(500),
    created_at  timestamptz    NOT NULL DEFAULT now(),
    created_by  varchar(50)    NOT NULL,
    updated_at  timestamptz    NOT NULL DEFAULT now(),
    updated_by  varchar(50)    NOT NULL,
    CONSTRAINT pk_srm_eval_score PRIMARY KEY (score_id),
    CONSTRAINT fk_srm_eval_score_eval FOREIGN KEY (eval_id) REFERENCES srm_eval (eval_id),
    CONSTRAINT fk_srm_eval_score_item FOREIGN KEY (item_id) REFERENCES srm_eval_item (item_id),
    CONSTRAINT uq_srm_eval_score UNIQUE (comp_cd, eval_id, item_id),
    CONSTRAINT ck_srm_eval_score_min CHECK (score IS NULL OR score >= 0),
    CONSTRAINT ck_srm_eval_score_auto CHECK (auto_yn IN ('Y', 'N'))
);
COMMENT ON TABLE srm_eval_score IS '평가 점수 — 평가를 만들 때 템플릿 항목마다 한 행. AUTO 계산값은 raw_value(지표 원값)·basis(근거 글)';

CREATE FUNCTION hb_guard_srm_eval() RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    v_total numeric;
    v_fail  boolean;
    v_grade varchar;
    v_hold  boolean;
BEGIN
    IF TG_OP = 'INSERT' THEN
        IF NEW.status <> 'DRAFT' THEN
            RAISE EXCEPTION 'new evaluation must be DRAFT' USING ERRCODE = '55000', CONSTRAINT = 'ck_srm_eval_transition';
        END IF;
        IF NOT hb_srm_supplier_of_comp(NEW.comp_cd, NEW.cust_id) THEN
            RAISE EXCEPTION 'supplier % is not of company %', NEW.cust_id, NEW.comp_cd
                USING ERRCODE = '23503', CONSTRAINT = 'fk_srm_eval_supplier_comp';
        END IF;
        IF NOT EXISTS (SELECT 1 FROM srm_eval_tmpl t WHERE t.tmpl_id = NEW.tmpl_id AND t.comp_cd = NEW.comp_cd) THEN
            RAISE EXCEPTION 'template % is not of company %', NEW.tmpl_id, NEW.comp_cd
                USING ERRCODE = '23503', CONSTRAINT = 'fk_srm_eval_tmpl_comp';
        END IF;
        IF NOT EXISTS (SELECT 1 FROM srm_eval_tmpl t WHERE t.tmpl_id = NEW.tmpl_id AND t.status = 'RELEASED') THEN
            RAISE EXCEPTION 'template % is not released', NEW.tmpl_id USING ERRCODE = '55000', CONSTRAINT = 'ck_srm_eval_tmpl_released';
        END IF;
        RETURN NEW;
    END IF;
    IF NEW.comp_cd <> OLD.comp_cd OR NEW.eval_no <> OLD.eval_no OR NEW.cust_id <> OLD.cust_id OR NEW.tmpl_id <> OLD.tmpl_id
       OR NEW.period_from <> OLD.period_from OR NEW.period_to <> OLD.period_to THEN
        RAISE EXCEPTION 'evaluation number, supplier, template and period are fixed'
            USING ERRCODE = '55000', CONSTRAINT = 'ck_srm_eval_fixed';
    END IF;
    IF OLD.status <> 'DRAFT' THEN
        RAISE EXCEPTION '% evaluation cannot be changed', OLD.status USING ERRCODE = '55000', CONSTRAINT = 'ck_srm_eval_final';
    END IF;
    IF NEW.status <> OLD.status AND NEW.status NOT IN ('CONFIRMED', 'CANCELLED') THEN
        RAISE EXCEPTION 'evaluation cannot go from % to %', OLD.status, NEW.status
            USING ERRCODE = '55000', CONSTRAINT = 'ck_srm_eval_transition';
    END IF;
    IF NEW.status = 'CONFIRMED' THEN
        -- 모든 항목 점수 필수 + 서비스(EvalScorer)와 같은 식으로 다시 계산해 맞는지
        IF EXISTS (SELECT 1 FROM srm_eval_item i WHERE i.tmpl_id = NEW.tmpl_id
                      AND NOT EXISTS (SELECT 1 FROM srm_eval_score s WHERE s.eval_id = NEW.eval_id AND s.item_id = i.item_id
                                         AND s.score IS NOT NULL)) THEN
            RAISE EXCEPTION 'every item needs a score' USING ERRCODE = '55000', CONSTRAINT = 'ck_srm_eval_scores';
        END IF;
        SELECT round(sum(s.score * i.weight / i.max_score), 2), bool_or(i.fail_below IS NOT NULL AND s.score < i.fail_below)
          INTO v_total, v_fail
          FROM srm_eval_score s JOIN srm_eval_item i ON i.item_id = s.item_id
         WHERE s.eval_id = NEW.eval_id;
        SELECT g.grade_cd, g.suggest_hold_yn = 'Y' INTO v_grade, v_hold FROM srm_eval_grade g
         WHERE g.tmpl_id = NEW.tmpl_id AND g.min_score <= v_total ORDER BY g.min_score DESC LIMIT 1;
        IF NEW.total_score IS DISTINCT FROM v_total OR NEW.fail_yn IS DISTINCT FROM (CASE WHEN v_fail THEN 'Y' ELSE 'N' END)
           OR NEW.grade_cd IS DISTINCT FROM v_grade
           OR NEW.suggest_trade_status IS DISTINCT FROM (CASE WHEN v_fail OR v_hold THEN 'HOLD' END) THEN
            RAISE EXCEPTION 'evaluation result does not match the scores (total %, grade %)', v_total, v_grade
                USING ERRCODE = '23514', CONSTRAINT = 'ck_srm_eval_total';
        END IF;
    ELSIF NEW.total_score IS NOT NULL OR NEW.grade_cd IS NOT NULL OR NEW.fail_yn IS NOT NULL OR NEW.suggest_trade_status IS NOT NULL
          OR NEW.confirmed_by IS NOT NULL OR NEW.confirmed_at IS NOT NULL THEN
        RAISE EXCEPTION 'result fields are set only at confirmation' USING ERRCODE = '55000', CONSTRAINT = 'ck_srm_eval_total';
    END IF;
    RETURN NEW;
END
$$;
CREATE TRIGGER tg_srm_eval_guard BEFORE INSERT OR UPDATE ON srm_eval FOR EACH ROW EXECUTE FUNCTION hb_guard_srm_eval();
CREATE TRIGGER tg_srm_eval_no_delete BEFORE DELETE ON srm_eval FOR EACH ROW EXECUTE FUNCTION hb_forbid_delete_master();

-- 점수: 작성 중 평가만, 그 평가 템플릿의 항목, 배점 이하
CREATE FUNCTION hb_guard_srm_eval_score() RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    v_eval record;
    v_item record;
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'evaluation scores are not deleted' USING ERRCODE = '55000', CONSTRAINT = 'ck_srm_eval_score_no_delete';
    END IF;
    IF TG_OP = 'UPDATE' AND (NEW.eval_id <> OLD.eval_id OR NEW.item_id <> OLD.item_id OR NEW.comp_cd <> OLD.comp_cd) THEN
        RAISE EXCEPTION 'score row cannot move' USING ERRCODE = '55000', CONSTRAINT = 'ck_srm_eval_score_move';
    END IF;
    SELECT e.comp_cd, e.status, e.tmpl_id INTO v_eval FROM srm_eval e WHERE e.eval_id = NEW.eval_id;
    IF NOT FOUND OR v_eval.comp_cd <> NEW.comp_cd THEN
        RAISE EXCEPTION 'evaluation % does not belong to company %', NEW.eval_id, NEW.comp_cd
            USING ERRCODE = '23503', CONSTRAINT = 'fk_srm_eval_score_eval_comp';
    END IF;
    IF v_eval.status <> 'DRAFT' THEN
        RAISE EXCEPTION 'scores of % evaluation cannot be changed', v_eval.status USING ERRCODE = '55000', CONSTRAINT = 'ck_srm_eval_score_draft';
    END IF;
    SELECT i.tmpl_id, i.max_score INTO v_item FROM srm_eval_item i WHERE i.item_id = NEW.item_id;
    IF NOT FOUND OR v_item.tmpl_id <> v_eval.tmpl_id THEN
        RAISE EXCEPTION 'item % is not an item of the evaluation template', NEW.item_id
            USING ERRCODE = '23503', CONSTRAINT = 'fk_srm_eval_score_item_tmpl';
    END IF;
    IF NEW.score > v_item.max_score THEN
        RAISE EXCEPTION 'score % is above the maximum %', NEW.score, v_item.max_score USING ERRCODE = '23514', CONSTRAINT = 'ck_srm_eval_score_max';
    END IF;
    RETURN NEW;
END
$$;
CREATE TRIGGER tg_srm_eval_score_guard BEFORE INSERT OR UPDATE OR DELETE ON srm_eval_score
    FOR EACH ROW EXECUTE FUNCTION hb_guard_srm_eval_score();

-- ---------------------------------------------------------------------
-- 4. 감사 · RLS · 권한 · 화면 · 보존
-- ---------------------------------------------------------------------
CREATE TRIGGER tg_audit AFTER INSERT OR UPDATE OR DELETE ON srm_eval_tmpl FOR EACH ROW EXECUTE FUNCTION hb_audit('tmpl_id', '', '');
CREATE TRIGGER tg_audit AFTER INSERT OR UPDATE OR DELETE ON srm_eval_item FOR EACH ROW EXECUTE FUNCTION hb_audit('item_id', '', '');
CREATE TRIGGER tg_audit AFTER INSERT OR UPDATE OR DELETE ON srm_eval_grade FOR EACH ROW EXECUTE FUNCTION hb_audit('grade_id', '', '');
CREATE TRIGGER tg_audit AFTER INSERT OR UPDATE OR DELETE ON srm_eval FOR EACH ROW EXECUTE FUNCTION hb_audit('eval_id', '', '');
CREATE TRIGGER tg_audit AFTER INSERT OR UPDATE OR DELETE ON srm_eval_score FOR EACH ROW EXECUTE FUNCTION hb_audit('score_id', '', '');

ALTER TABLE srm_eval_tmpl ENABLE ROW LEVEL SECURITY;
ALTER TABLE srm_eval_tmpl FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON srm_eval_tmpl USING (comp_cd = hb_current_comp()) WITH CHECK (comp_cd = hb_current_comp());
ALTER TABLE srm_eval_item ENABLE ROW LEVEL SECURITY;
ALTER TABLE srm_eval_item FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON srm_eval_item USING (comp_cd = hb_current_comp()) WITH CHECK (comp_cd = hb_current_comp());
ALTER TABLE srm_eval_grade ENABLE ROW LEVEL SECURITY;
ALTER TABLE srm_eval_grade FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON srm_eval_grade USING (comp_cd = hb_current_comp()) WITH CHECK (comp_cd = hb_current_comp());
ALTER TABLE srm_eval ENABLE ROW LEVEL SECURITY;
ALTER TABLE srm_eval FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON srm_eval USING (comp_cd = hb_current_comp()) WITH CHECK (comp_cd = hb_current_comp());
ALTER TABLE srm_eval_score ENABLE ROW LEVEL SECURITY;
ALTER TABLE srm_eval_score FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON srm_eval_score USING (comp_cd = hb_current_comp()) WITH CHECK (comp_cd = hb_current_comp());
-- 항목·등급은 작성 중 템플릿에서 한 벌 저장(지우고 다시) — 앱 DELETE 는 남기고 트리거가 작성 중만 허용
REVOKE DELETE, TRUNCATE ON srm_eval_tmpl, srm_eval, srm_eval_score FROM ${app_role};
REVOKE TRUNCATE ON srm_eval_item, srm_eval_grade FROM ${app_role};

INSERT INTO sys_prog (prog_cd, prog_nm_key, view_path, module_cd, actions, sort_no) VALUES
    ('srm-eval-tmpl', 'prog.srmEvalTmpl', 'srm/eval-tmpl', 'SRM', ARRAY['SEARCH','SAVE','APPROVE']::varchar(20)[], 870),
    ('srm-eval',      'prog.srmEval',     'srm/eval-mgt',  'SRM', ARRAY['SEARCH','SAVE','APPROVE']::varchar(20)[], 871);
INSERT INTO sys_retention_table (table_nm, class_cd) VALUES
    ('srm_eval_tmpl', 'MASTER'), ('srm_eval_item', 'MASTER'), ('srm_eval_grade', 'MASTER'),
    ('srm_eval', 'QUALITY_RECORD'), ('srm_eval_score', 'QUALITY_RECORD');
