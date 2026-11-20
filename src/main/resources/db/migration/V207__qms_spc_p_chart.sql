-- SPC 확장 PR 2: p 관리도(불량률) (docs/29_P3 28장, 사용자 결정 2026-10-29 "13.4·Q10-9 보류 3 추천대로")
-- 계수형(ATTRIBUTE) 특성의 판정 확정 검사 행 = 부분군 하나: n = 살아 있는 OK/NG 샘플 수, d = NG 수, p = d/n. 새 입력 없음(Q6 측정값 원장).
-- 관리한계 p̄ ± 3√(p̄(1-p̄)/n_i) — 부분군마다 다른 한계(표준식). 저장은 X̄ 한계와 같은 모양: center = p̄, sigma = √(p̄(1-p̄)) → ± 3σ/√n.
-- 규칙은 WE1(한계 밖 1점)·WE4(중심 같은 쪽 8점)만 — 근거 28.1 PC-4. 출발점: 한계 가드 V106, 위반 가드 V157, 단위 요약 V186(각각 마지막 정의 — grep 확인)

-- ---------------------------------------------------------------------
-- 1. 고정 관리한계: 관리도 종류
-- ---------------------------------------------------------------------
ALTER TABLE qms_spc_limit ADD COLUMN chart_type varchar(6) NOT NULL DEFAULT 'XBAR_R';
ALTER TABLE qms_spc_limit ADD CONSTRAINT ck_qms_spc_limit_chart CHECK (chart_type IN ('XBAR_R', 'P')
    AND (chart_type = 'XBAR_R' OR (rules IN ('WE1', 'WE4', 'WE1,WE4') AND center > 0 AND center < 1)));
COMMENT ON COLUMN qms_spc_limit.chart_type IS 'XBAR_R = 계량형 X̄-R(center = X̿, sigma = 군내 σ̂) · P = 계수형 p(center = p̄, sigma = √(p̄(1-p̄)), 한계 = p̄ ± 3σ/√n)';

CREATE OR REPLACE FUNCTION hb_guard_qms_spc_limit() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'SPC limit cannot be deleted (void it)' USING ERRCODE = '55000', CONSTRAINT = 'ck_qms_spc_limit_no_delete';
    END IF;
    IF TG_OP = 'INSERT' THEN
        IF NEW.status <> 'ACTIVE' THEN
            RAISE EXCEPTION 'new SPC limit must be ACTIVE' USING ERRCODE = '55000', CONSTRAINT = 'ck_qms_spc_limit_active';
        END IF;
        IF NOT EXISTS (SELECT 1 FROM mst_item i WHERE i.item_id = NEW.item_id AND i.comp_cd = NEW.comp_cd) THEN
            RAISE EXCEPTION 'item % does not belong to company %', NEW.item_id, NEW.comp_cd USING ERRCODE = '23503', CONSTRAINT = 'fk_qms_spc_limit_item_comp';
        END IF;
        -- 관리도 종류와 특성 자료형이 맞아야 한다(X̄-R = 계량형, p = 계수형)
        IF NOT EXISTS (SELECT 1 FROM qms_char c WHERE c.char_id = NEW.char_id AND c.comp_cd = NEW.comp_cd
                          AND c.data_type = CASE NEW.chart_type WHEN 'P' THEN 'ATTRIBUTE' ELSE 'VARIABLE' END) THEN
            RAISE EXCEPTION 'char % does not fit chart % in company %', NEW.char_id, NEW.chart_type, NEW.comp_cd
                USING ERRCODE = '23503', CONSTRAINT = 'fk_qms_spc_limit_char_comp';
        END IF;
        IF NEW.proc_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM mst_proc p WHERE p.proc_id = NEW.proc_id AND p.comp_cd = NEW.comp_cd) THEN
            RAISE EXCEPTION 'process % does not belong to company %', NEW.proc_id, NEW.comp_cd USING ERRCODE = '23503', CONSTRAINT = 'fk_qms_spc_limit_proc_comp';
        END IF;
        RETURN NEW;
    END IF;
    -- UPDATE: ACTIVE → VOID 만, 그 밖 칸 고정
    IF (NEW.comp_cd, NEW.item_id, NEW.char_id, NEW.insp_type, NEW.proc_id, NEW.center, NEW.sigma, NEW.subgroup_cnt, NEW.from_at, NEW.to_at,
        NEW.rules, NEW.remark, NEW.created_at, NEW.created_by, NEW.chart_type)
       IS DISTINCT FROM (OLD.comp_cd, OLD.item_id, OLD.char_id, OLD.insp_type, OLD.proc_id, OLD.center, OLD.sigma, OLD.subgroup_cnt, OLD.from_at,
        OLD.to_at, OLD.rules, OLD.remark, OLD.created_at, OLD.created_by, OLD.chart_type)
       OR OLD.status = 'VOID' OR NEW.status <> 'VOID' THEN
        RAISE EXCEPTION 'SPC limit % can only be voided', OLD.limit_id USING ERRCODE = '55000', CONSTRAINT = 'ck_qms_spc_limit_immutable';
    END IF;
    RETURN NEW;
END
$$;

-- ---------------------------------------------------------------------
-- 2. 위반 원장: 관리도 종류 — p 는 부분군 크기 1 이상(전수 검사면 수백), 규칙 WE1·WE4 만
--    point_mean = p, point_range = 불량 수 d, center = p̄, sigma = √(p̄(1-p̄)) (한계 행 사본)
-- ---------------------------------------------------------------------
ALTER TABLE qms_spc_event ADD COLUMN chart_type varchar(6) NOT NULL DEFAULT 'XBAR_R';
ALTER TABLE qms_spc_event DROP CONSTRAINT ck_qms_spc_event_n;
ALTER TABLE qms_spc_event ADD CONSTRAINT ck_qms_spc_event_n CHECK (sigma > 0 AND (
    (chart_type = 'XBAR_R' AND n BETWEEN 2 AND 25)
    OR (chart_type = 'P' AND n >= 1 AND rule_cd IN ('WE1', 'WE4'))));
COMMENT ON COLUMN qms_spc_event.chart_type IS 'XBAR_R(point_mean = X̄, point_range = R) · P(point_mean = p, point_range = 불량 수 d) — 한계 행과 같아야 함';

CREATE OR REPLACE FUNCTION hb_guard_qms_spc_event() RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    v_line  qms_insp_line;
    v_insp  qms_insp;
    v_limit qms_spc_limit;
BEGIN
    IF TG_OP <> 'INSERT' THEN
        RAISE EXCEPTION 'SPC event ledger is append-only (void it with an action)' USING ERRCODE = '55000', CONSTRAINT = 'ck_qms_spc_event_append_only';
    END IF;
    SELECT * INTO v_line FROM qms_insp_line WHERE insp_line_id = NEW.insp_line_id AND comp_cd = NEW.comp_cd;
    IF NOT FOUND OR v_line.insp_id <> NEW.insp_id OR v_line.char_id <> NEW.char_id THEN
        RAISE EXCEPTION 'inspection line % does not match event of company %', NEW.insp_line_id, NEW.comp_cd
            USING ERRCODE = '23503', CONSTRAINT = 'fk_qms_spc_event_line_comp';
    END IF;
    SELECT * INTO v_insp FROM qms_insp WHERE insp_id = NEW.insp_id;
    IF v_insp.status <> 'JUDGED' OR v_insp.item_id <> NEW.item_id OR v_insp.insp_type <> NEW.insp_type
       OR v_insp.proc_id IS DISTINCT FROM NEW.proc_id THEN
        RAISE EXCEPTION 'event must point to a judged inspection of the same unit' USING ERRCODE = '23514', CONSTRAINT = 'ck_qms_spc_event_insp';
    END IF;
    SELECT * INTO v_limit FROM qms_spc_limit WHERE limit_id = NEW.limit_id AND comp_cd = NEW.comp_cd;
    IF NOT FOUND OR v_limit.item_id <> NEW.item_id OR v_limit.char_id <> NEW.char_id OR v_limit.insp_type <> NEW.insp_type
       OR v_limit.proc_id IS DISTINCT FROM NEW.proc_id OR v_limit.chart_type <> NEW.chart_type THEN
        RAISE EXCEPTION 'limit % is not the limit of this unit in company %', NEW.limit_id, NEW.comp_cd
            USING ERRCODE = '23503', CONSTRAINT = 'fk_qms_spc_event_limit_comp';
    END IF;
    RETURN NEW;
END
$$;

-- ---------------------------------------------------------------------
-- 3. 단위 요약(H5): 계수형 특성도 단위로 — 계산 함수·판정 트리거가 VARIABLE·ATTRIBUTE 를 함께 센다
-- ---------------------------------------------------------------------
CREATE OR REPLACE FUNCTION hb_qms_spc_unit_calc()
    RETURNS TABLE (comp_cd varchar, item_id bigint, char_id bigint, insp_type varchar, proc_id bigint, subgroup_cnt integer, last_at timestamptz)
    LANGUAGE sql STABLE
AS $$
    SELECT h.comp_cd, h.item_id, x.char_id, h.insp_type, h.proc_id, count(*)::integer, max(h.occurred_at)
      FROM qms_insp h
      JOIN qms_insp_line x ON x.insp_id = h.insp_id AND x.data_type IN ('VARIABLE', 'ATTRIBUTE')
     WHERE h.status = 'JUDGED'
     GROUP BY h.comp_cd, h.item_id, x.char_id, h.insp_type, h.proc_id
$$;

CREATE OR REPLACE FUNCTION hb_qms_spc_unit_on_insp() RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    u record;
    v_cnt integer;
    v_last timestamptz;
BEGIN
    IF TG_OP = 'UPDATE' AND (OLD.status = 'JUDGED') = (NEW.status = 'JUDGED') THEN
        RETURN NULL;
    END IF;
    IF TG_OP = 'INSERT' AND NEW.status <> 'JUDGED' THEN
        RETURN NULL;
    END IF;
    FOR u IN SELECT DISTINCT x.char_id, NEW.item_id || ':' || x.char_id || ':' || NEW.insp_type || ':' || coalesce(NEW.proc_id, 0) AS unit_key
               FROM qms_insp_line x WHERE x.insp_id = NEW.insp_id AND x.data_type IN ('VARIABLE', 'ATTRIBUTE')
              ORDER BY 2 LOOP
        PERFORM pg_advisory_xact_lock(hashtext(u.unit_key));
        SELECT count(*), max(h.occurred_at) INTO v_cnt, v_last
          FROM qms_insp h
          JOIN qms_insp_line x ON x.insp_id = h.insp_id AND x.char_id = u.char_id AND x.data_type IN ('VARIABLE', 'ATTRIBUTE')
         WHERE h.comp_cd = NEW.comp_cd AND h.item_id = NEW.item_id AND h.insp_type = NEW.insp_type
           AND h.proc_id IS NOT DISTINCT FROM NEW.proc_id AND h.status = 'JUDGED';
        IF v_cnt = 0 THEN
            DELETE FROM qms_spc_unit s
             WHERE s.comp_cd = NEW.comp_cd AND s.item_id = NEW.item_id AND s.char_id = u.char_id AND s.insp_type = NEW.insp_type
               AND s.proc_id IS NOT DISTINCT FROM NEW.proc_id;
        ELSE
            INSERT INTO qms_spc_unit (comp_cd, item_id, char_id, insp_type, proc_id, subgroup_cnt, last_at)
            VALUES (NEW.comp_cd, NEW.item_id, u.char_id, NEW.insp_type, NEW.proc_id, v_cnt, v_last)
            ON CONFLICT (comp_cd, item_id, char_id, insp_type, proc_id) DO UPDATE
               SET subgroup_cnt = excluded.subgroup_cnt, last_at = excluded.last_at;
        END IF;
    END LOOP;
    RETURN NULL;
END
$$;

-- 이미 판정된 계수형 검사의 단위를 한 번 채움(계량형 단위는 그대로) — 투영 가드는 이 블록 동안만 끈다, 회사마다 범위(V186 채우기와 같은 방식)
ALTER TABLE qms_spc_unit DISABLE TRIGGER tg_qms_spc_unit_guard;
DO $$
DECLARE
    v_comps varchar[];
    v_comp  varchar;
BEGIN
    PERFORM set_config('app.scope', 'SYSTEM', true);
    SELECT coalesce(array_agg(comp_cd), ARRAY[]::varchar[]) INTO v_comps FROM sys_comp;
    PERFORM set_config('app.scope', '', true);
    FOREACH v_comp IN ARRAY v_comps LOOP
        PERFORM set_config('app.comp_cd', v_comp, true);
        INSERT INTO qms_spc_unit (comp_cd, item_id, char_id, insp_type, proc_id, subgroup_cnt, last_at)
        SELECT c.comp_cd, c.item_id, c.char_id, c.insp_type, c.proc_id, c.subgroup_cnt, c.last_at FROM hb_qms_spc_unit_calc() c
        ON CONFLICT (comp_cd, item_id, char_id, insp_type, proc_id) DO UPDATE
           SET subgroup_cnt = excluded.subgroup_cnt, last_at = excluded.last_at;
    END LOOP;
    PERFORM set_config('app.comp_cd', '', true);
END
$$;
ALTER TABLE qms_spc_unit ENABLE TRIGGER tg_qms_spc_unit_guard;
