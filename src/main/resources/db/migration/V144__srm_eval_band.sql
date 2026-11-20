-- =====================================================================
-- SRM 2부 R4b — 평가 항목 점수 구간 srm_eval_band (docs/31 4.12.3, 결정 R4-5) — 임시 번호, 병합 때 재번호
--
-- - 입고 PPM(RECV_PPM) AUTO 항목의 점수 = 구간 표: PPM ≤ 상한인 가장 낮은 구간의 점수 비율 × 배점.
--   상한 NULL = "그 위 전부"(마지막 구간) — 항목마다 정확히 하나
-- - 작성 중 템플릿에서만 바꾼다(항목·등급과 같은 공용 가드 hb_guard_srm_eval_tmpl_child), 확정본은 불변
-- - 확정 때 검사: RECV_PPM 항목마다 끝 없는 구간이 있어야(V068 함수를 고치지 않고 확정 검사 트리거를 하나 더)
-- - PPM 값 자체는 QMS 수입검사 판정 계약(R4a) 뒤 — 그때까지 RECV_PPM 은 지금처럼 사람 입력
-- =====================================================================

CREATE TABLE srm_eval_band (
    band_id      bigint         GENERATED ALWAYS AS IDENTITY,
    comp_cd      varchar(20)    NOT NULL,
    tmpl_id      bigint         NOT NULL,
    item_id      bigint         NOT NULL,
    upper_ppm    numeric(12,2),
    score_pct    numeric(5,2)   NOT NULL,
    created_at   timestamptz    NOT NULL DEFAULT now(),
    created_by   varchar(50)    NOT NULL,
    updated_at   timestamptz    NOT NULL DEFAULT now(),
    updated_by   varchar(50)    NOT NULL,
    CONSTRAINT pk_srm_eval_band PRIMARY KEY (band_id),
    CONSTRAINT fk_srm_eval_band_comp FOREIGN KEY (comp_cd) REFERENCES sys_comp (comp_cd),
    CONSTRAINT fk_srm_eval_band_tmpl FOREIGN KEY (tmpl_id) REFERENCES srm_eval_tmpl (tmpl_id),
    CONSTRAINT fk_srm_eval_band_item FOREIGN KEY (item_id) REFERENCES srm_eval_item (item_id),
    -- 같은 항목에 같은 상한 둘 없음, 끝 없는 구간(NULL)도 하나만
    CONSTRAINT uq_srm_eval_band_upper UNIQUE NULLS NOT DISTINCT (item_id, upper_ppm),
    CONSTRAINT ck_srm_eval_band_upper CHECK (upper_ppm IS NULL OR upper_ppm >= 0),
    CONSTRAINT ck_srm_eval_band_pct CHECK (score_pct >= 0 AND score_pct <= 100)
);
CREATE INDEX ix_srm_eval_band_tmpl ON srm_eval_band (comp_cd, tmpl_id);
COMMENT ON TABLE srm_eval_band IS '평가 항목 점수 구간(RECV_PPM) — PPM ≤ 상한인 가장 낮은 구간의 점수 비율, 상한 NULL = 그 위 전부 (docs/31 4.12.3)';

-- 구간 행: 항목이 같은 템플릿·같은 회사의 AUTO RECV_PPM 항목이어야
CREATE FUNCTION hb_guard_srm_eval_band() RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    v_item record;
BEGIN
    IF TG_OP = 'UPDATE' AND NEW.item_id <> OLD.item_id THEN
        RAISE EXCEPTION 'band cannot move to another item' USING ERRCODE = '55000', CONSTRAINT = 'ck_srm_eval_band_move';
    END IF;
    SELECT i.comp_cd, i.tmpl_id, i.input_type, i.metric_cd INTO v_item FROM srm_eval_item i WHERE i.item_id = NEW.item_id;
    IF NOT FOUND OR v_item.comp_cd <> NEW.comp_cd OR v_item.tmpl_id <> NEW.tmpl_id THEN
        RAISE EXCEPTION 'item % is not in template % of company %', NEW.item_id, NEW.tmpl_id, NEW.comp_cd
            USING ERRCODE = '23503', CONSTRAINT = 'fk_srm_eval_band_item_tmpl';
    END IF;
    IF v_item.input_type <> 'AUTO' OR v_item.metric_cd IS DISTINCT FROM 'RECV_PPM' THEN
        RAISE EXCEPTION 'bands are only for AUTO RECV_PPM items' USING ERRCODE = '23514', CONSTRAINT = 'ck_srm_eval_band_metric';
    END IF;
    RETURN NEW;
END
$$;
CREATE TRIGGER tg_srm_eval_band_guard BEFORE INSERT OR UPDATE ON srm_eval_band
    FOR EACH ROW EXECUTE FUNCTION hb_guard_srm_eval_band();
-- 작성 중 템플릿의 행만(항목·등급과 같은 공용 가드 — tmpl_id 칸을 같은 이름으로 둔 이유)
CREATE TRIGGER tg_srm_eval_band_draft BEFORE INSERT OR UPDATE OR DELETE ON srm_eval_band
    FOR EACH ROW EXECUTE FUNCTION hb_guard_srm_eval_tmpl_child();

-- 확정 때: RECV_PPM 항목마다 끝 없는 구간(상한 NULL) 하나 — 없으면 어떤 PPM 도 점수를 못 찾는다
CREATE FUNCTION hb_check_srm_eval_tmpl_bands() RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    v_item_no integer;
BEGIN
    IF OLD.status = 'DRAFT' AND NEW.status = 'RELEASED' THEN
        SELECT i.item_no INTO v_item_no
          FROM srm_eval_item i
         WHERE i.tmpl_id = NEW.tmpl_id AND i.input_type = 'AUTO' AND i.metric_cd = 'RECV_PPM'
           AND NOT EXISTS (SELECT 1 FROM srm_eval_band b WHERE b.item_id = i.item_id AND b.upper_ppm IS NULL)
         ORDER BY i.item_no
         LIMIT 1;
        IF FOUND THEN
            RAISE EXCEPTION 'RECV_PPM item % needs bands ending with an open band', v_item_no
                USING ERRCODE = '55000', CONSTRAINT = 'ck_srm_eval_tmpl_bands';
        END IF;
    END IF;
    RETURN NEW;
END
$$;
CREATE TRIGGER tg_srm_eval_tmpl_bands BEFORE UPDATE ON srm_eval_tmpl
    FOR EACH ROW EXECUTE FUNCTION hb_check_srm_eval_tmpl_bands();

CREATE TRIGGER tg_audit AFTER INSERT OR UPDATE OR DELETE ON srm_eval_band FOR EACH ROW EXECUTE FUNCTION hb_audit('band_id', '', '');

ALTER TABLE srm_eval_band ENABLE ROW LEVEL SECURITY;
ALTER TABLE srm_eval_band FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON srm_eval_band USING (comp_cd = hb_current_comp()) WITH CHECK (comp_cd = hb_current_comp());
-- 구간은 작성 중 템플릿에서 한 벌 저장(지우고 다시) — 앱 DELETE 는 남기고 트리거가 작성 중만 허용(항목·등급과 같음)
REVOKE TRUNCATE ON srm_eval_band FROM ${app_role};

INSERT INTO sys_retention_table (table_nm, class_cd) VALUES ('srm_eval_band', 'MASTER');
