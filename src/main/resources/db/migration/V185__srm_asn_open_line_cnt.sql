-- SRM ASN 덜 받은 행 수 칸 (docs/31 5.6, QA offset M5 남은 것)
--   srm_asn.open_line_cnt = ASN 행 중 qty > Σ 입고 이력(그 asn_line_id) 인 행 수. 트리거로만 유지한다(앱은 쓰지 않음).
--   다시 세는 때: 입고 이력 INSERT(asn_line_id 있는 것 — 입고·되돌림), ASN 행 INSERT·DELETE·qty UPDATE.
--   잠금: 다시 세는 함수가 ASN 머리 행을 먼저 FOR UPDATE 로 잡고 다음 문장에서 센다 — 기다린 쪽은 앞 트랜잭션이 커밋한 입고까지 본다.
--   순서: 칸 추가 → 기존 행 채우기(회사마다 범위, 선례 V109·V178) → 칸 보호(guard 교체) → 트리거. 보호를 먼저 바꾸면 채우기가 거부된다.

ALTER TABLE srm_asn ADD COLUMN open_line_cnt integer NOT NULL DEFAULT 0;
ALTER TABLE srm_asn ADD CONSTRAINT ck_srm_asn_open_cnt_range CHECK (open_line_cnt >= 0);
COMMENT ON COLUMN srm_asn.open_line_cnt IS '덜 받은 행 수(qty > Σ 입고 이력) — 트리거만 유지(docs/31 5.6), 앱·직접 UPDATE 거부(ck_srm_asn_open_cnt)';

-- 그 ASN 의 덜 받은 행 수(계산만 — 잠그지 않는다). 채우기·대사·시험이 같이 쓴다
CREATE FUNCTION hb_srm_asn_open_cnt(p_asn bigint) RETURNS integer
    LANGUAGE sql STABLE
AS $$
    SELECT count(*)::int
      FROM srm_asn_line l
     WHERE l.asn_id = p_asn
       AND l.qty > coalesce((SELECT sum(x.qty) FROM srm_po_receipt x WHERE x.asn_line_id = l.asn_line_id), 0)
$$;

DO $$
DECLARE
    v_comps varchar[];
    v_comp  varchar;
BEGIN
    SELECT coalesce(array_agg(comp_cd), ARRAY[]::varchar[]) INTO v_comps FROM sys_comp;
    PERFORM set_config('app.scope', '', true);
    FOREACH v_comp IN ARRAY v_comps LOOP
        PERFORM set_config('app.comp_cd', v_comp, true);
        UPDATE srm_asn a SET open_line_cnt = hb_srm_asn_open_cnt(a.asn_id)
         WHERE a.comp_cd = v_comp AND hb_srm_asn_open_cnt(a.asn_id) <> 0;
    END LOOP;
    PERFORM set_config('app.comp_cd', '', true);
END
$$;

CREATE OR REPLACE FUNCTION hb_guard_srm_asn() RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    v_any boolean;
    v_all boolean;
    v_bad record;
BEGIN
    IF TG_OP = 'INSERT' THEN
        IF NEW.status <> 'DRAFT' THEN
            RAISE EXCEPTION 'new advance shipping notice must be DRAFT' USING ERRCODE = '55000', CONSTRAINT = 'ck_srm_asn_transition';
        END IF;
        IF NOT hb_srm_supplier_of_comp(NEW.comp_cd, NEW.cust_id) THEN
            RAISE EXCEPTION 'supplier % is not of company %', NEW.cust_id, NEW.comp_cd
                USING ERRCODE = '23503', CONSTRAINT = 'fk_srm_asn_supplier_comp';
        END IF;
        IF NEW.import_job_id IS NOT NULL
           AND NOT EXISTS (SELECT 1 FROM sys_import_job j WHERE j.job_id = NEW.import_job_id AND j.comp_cd = NEW.comp_cd) THEN
            RAISE EXCEPTION 'import job % is not of company %', NEW.import_job_id, NEW.comp_cd
                USING ERRCODE = '23503', CONSTRAINT = 'fk_srm_asn_job_comp';
        END IF;
        -- 덜 받은 행 수는 줄이 붙을 때 트리거가 센다(V185)
        IF NEW.open_line_cnt <> 0 THEN
            RAISE EXCEPTION 'open line count is kept by triggers' USING ERRCODE = '55000', CONSTRAINT = 'ck_srm_asn_open_cnt';
        END IF;
        RETURN NEW;
    END IF;
    -- 덜 받은 행 수는 트리거(hb_srm_asn_open_refresh) 안에서만 바뀐다 — 앱·직접 SQL 의 UPDATE 는 거부(V048 inv_stock 과 같은 꼴)
    IF NEW.open_line_cnt IS DISTINCT FROM OLD.open_line_cnt AND pg_trigger_depth() < 2 THEN
        RAISE EXCEPTION 'open line count is kept by triggers' USING ERRCODE = '55000', CONSTRAINT = 'ck_srm_asn_open_cnt';
    END IF;
    IF (NEW.asn_no, NEW.comp_cd, NEW.cust_id, NEW.source, NEW.created_at, NEW.created_by)
       IS DISTINCT FROM (OLD.asn_no, OLD.comp_cd, OLD.cust_id, OLD.source, OLD.created_at, OLD.created_by)
       OR NEW.import_job_id IS DISTINCT FROM OLD.import_job_id THEN
        RAISE EXCEPTION 'number, supplier and source are fixed' USING ERRCODE = '55000', CONSTRAINT = 'ck_srm_asn_fixed';
    END IF;
    IF OLD.status <> 'DRAFT' AND (NEW.plant_cd, NEW.ship_date, NEW.eta_date) IS DISTINCT FROM (OLD.plant_cd, OLD.ship_date, OLD.eta_date)
       OR OLD.status <> 'DRAFT' AND (NEW.supplier_ref IS DISTINCT FROM OLD.supplier_ref OR NEW.carrier_memo IS DISTINCT FROM OLD.carrier_memo
                                     OR NEW.remark IS DISTINCT FROM OLD.remark) THEN
        RAISE EXCEPTION '% advance shipping notice cannot be edited', OLD.status USING ERRCODE = '55000', CONSTRAINT = 'ck_srm_asn_draft';
    END IF;
    IF NEW.status <> OLD.status THEN
        IF NOT ((OLD.status = 'DRAFT' AND NEW.status IN ('SENT', 'CANCELLED'))
             OR (OLD.status = 'SENT' AND NEW.status IN ('PARTIAL_RECEIVED', 'RECEIVED', 'CLOSED', 'CANCELLED'))
             OR (OLD.status IN ('PARTIAL_RECEIVED', 'RECEIVED')
                 AND NEW.status IN ('SENT', 'PARTIAL_RECEIVED', 'RECEIVED', 'CLOSED'))) THEN
            RAISE EXCEPTION 'advance shipping notice cannot go from % to %', OLD.status, NEW.status
                USING ERRCODE = '55000', CONSTRAINT = 'ck_srm_asn_transition';
        END IF;
        SELECT * INTO v_any, v_all FROM hb_srm_asn_recv_state(NEW.asn_id);
        -- 받은 것이 있으면 취소 불가(D6 과 같은 규칙)
        IF NEW.status = 'CANCELLED' AND v_any THEN
            RAISE EXCEPTION 'a received advance shipping notice cannot be cancelled' USING ERRCODE = '55000', CONSTRAINT = 'ck_srm_asn_cancel_received';
        END IF;
        -- 입고 상태로 들고 나는 전이는 받은 양과 맞아야(마감·취소 제외 — 발주 ck_srm_po_recv_status 와 같은 모양)
        IF NEW.status IN ('SENT', 'PARTIAL_RECEIVED', 'RECEIVED') AND OLD.status IN ('SENT', 'PARTIAL_RECEIVED', 'RECEIVED') THEN
            -- IF 조건 안에 CASE … THEN 을 쓰지 않는다(PL/pgSQL 이 첫 THEN 에서 조건을 끊음 — R1 교훈)
            IF NOT ((NEW.status = 'RECEIVED' AND v_all)
                    OR (NEW.status = 'PARTIAL_RECEIVED' AND v_any AND NOT v_all)
                    OR (NEW.status = 'SENT' AND NOT v_any)) THEN
                RAISE EXCEPTION 'status % does not match the received quantities', NEW.status
                    USING ERRCODE = '55000', CONSTRAINT = 'ck_srm_asn_recv_status';
            END IF;
        END IF;
        -- 확정: 행이 하나 이상 + 한도(R3-4) — 발주 행마다 Σ 남은 ASN(살아 있는 것, 이 ASN 포함) ≤ 주문 × (1 + 초과율) − 입고
        IF OLD.status = 'DRAFT' AND NEW.status = 'SENT' THEN
            IF NOT EXISTS (SELECT 1 FROM srm_asn_line l WHERE l.asn_id = NEW.asn_id) THEN
                RAISE EXCEPTION 'advance shipping notice has no lines' USING ERRCODE = '55000', CONSTRAINT = 'ck_srm_asn_lines';
            END IF;
            SELECT pl.po_line_id INTO v_bad
              FROM srm_po_line pl
             WHERE pl.po_line_id IN (SELECT l.po_line_id FROM srm_asn_line l WHERE l.asn_id = NEW.asn_id)
               AND (SELECT coalesce(sum(greatest(al.qty - coalesce((SELECT sum(x.qty) FROM srm_po_receipt x WHERE x.asn_line_id = al.asn_line_id), 0), 0)), 0)
                      FROM srm_asn_line al JOIN srm_asn a ON a.asn_id = al.asn_id
                     WHERE al.po_line_id = pl.po_line_id
                       AND (a.asn_id = NEW.asn_id OR a.status IN ('SENT', 'PARTIAL_RECEIVED')))
                   > (pl.qty - pl.cancel_qty) * (100 + hb_srm_recv_over_pct(NEW.comp_cd, NEW.plant_cd)) / 100 - pl.recv_qty
             LIMIT 1;
            IF FOUND THEN
                RAISE EXCEPTION 'notified quantity exceeds what order line % can still receive', v_bad.po_line_id
                    USING ERRCODE = '23514', CONSTRAINT = 'ck_srm_asn_over';
            END IF;
        END IF;
    END IF;
    RETURN NEW;
END
$$;

-- 다시 세기: 머리 행 잠금 → (다음 문장) 세기 → 바뀐 때만 UPDATE
CREATE FUNCTION hb_srm_asn_open_refresh(p_asn bigint) RETURNS void
    LANGUAGE plpgsql
AS $$
DECLARE
    v_cnt integer;
BEGIN
    PERFORM 1 FROM srm_asn a WHERE a.asn_id = p_asn FOR UPDATE;
    v_cnt := hb_srm_asn_open_cnt(p_asn);
    UPDATE srm_asn SET open_line_cnt = v_cnt WHERE asn_id = p_asn AND open_line_cnt <> v_cnt;
END
$$;

CREATE FUNCTION hb_srm_asn_open_on_receipt() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    PERFORM hb_srm_asn_open_refresh(l.asn_id) FROM srm_asn_line l WHERE l.asn_line_id = NEW.asn_line_id;
    RETURN NULL;
END
$$;
CREATE TRIGGER tg_srm_po_receipt_asn_open AFTER INSERT ON srm_po_receipt
    FOR EACH ROW WHEN (NEW.asn_line_id IS NOT NULL) EXECUTE FUNCTION hb_srm_asn_open_on_receipt();

CREATE FUNCTION hb_srm_asn_open_on_line() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    PERFORM hb_srm_asn_open_refresh(CASE WHEN TG_OP = 'DELETE' THEN OLD.asn_id ELSE NEW.asn_id END);
    RETURN NULL;
END
$$;
CREATE TRIGGER tg_srm_asn_line_open AFTER INSERT OR DELETE OR UPDATE OF qty ON srm_asn_line
    FOR EACH ROW EXECUTE FUNCTION hb_srm_asn_open_on_line();
