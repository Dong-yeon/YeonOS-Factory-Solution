-- =====================================================================
-- QMS R3-c — NCR 처분 수량 합 ≤ 부적합 수량, 클레임 NCR 부적합 수량 ≤ 반품 수량 (QA docs/qa/2026-11-02_round3.md R3-c,
-- docs/29_P3 47장) — 임시 번호(docs/29 2.2), 병합 때 이름만 바뀐다.
--
-- 지금(V112·V223): 처분 합 = 부적합 수량은 상신·종결 때만 본다(OPEN 동안은 아무 값). 클레임 NCR 은 부적합 수량을 얼마든지 늘릴 수 있어
-- 종결 때 WMS 가 그 반품 칸을 다 쓰고 남는 몫을 다른(판정 전) 반품 칸에서 가져갔다(R3-c 재현).
-- 1) qms_ncr.src_qty — 계약이 만든 NCR 의 근거 수량(반품 수량, NcrPort.ClaimRequest.qty) 사본. 정체 칸(고정).
--    기존 클레임 NCR 은 NULL(마이그레이션은 RLS 때문에 회사 행을 못 읽어 채우지 않는다 — 검사 없음, 47장 R3C-5)
-- 2) CHECK nc_qty ≤ src_qty
-- 3) 처분 행 INSERT 때 머리를 잠근 뒤 (지금 합 + 새 행) ≤ 부적합 수량 — 동시 INSERT 도 머리 잠금으로 줄 선다
--    (잠금 문장과 합 문장을 나눈다 — FOR UPDATE 문장 안 집계는 옛 스냅숏, SKILL §2 SRM 교훈)
-- 4) 머리 UPDATE 로 부적합 수량을 지금 처분 합보다 작게 줄이기 거부(서비스는 처분 행을 먼저 지우고 머리를 고친다)
-- =====================================================================

ALTER TABLE qms_ncr ADD COLUMN src_qty numeric(18,3);
ALTER TABLE qms_ncr ADD CONSTRAINT ck_qms_ncr_src_qty CHECK (src_qty IS NULL OR (src_type IS NOT NULL AND src_qty > 0 AND nc_qty <= src_qty));
COMMENT ON COLUMN qms_ncr.src_qty IS '계약 NCR 의 근거 수량(고객 반품 수량 — NCR 단위) 사본, 정체 칸. 부적합 수량은 이보다 클 수 없다(R3-c). 이 칸 이전 클레임 NCR 은 NULL(검사 없음)';

CREATE OR REPLACE FUNCTION hb_guard_qms_ncr() RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    v_lot   lot_master;
    v_insp  qms_insp;
    v_sum   numeric;
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'NCR cannot be deleted (cancel it)' USING ERRCODE = '55000', CONSTRAINT = 'ck_qms_ncr_no_delete';
    END IF;
    IF TG_OP = 'INSERT' THEN
        IF NEW.status <> 'OPEN' THEN
            RAISE EXCEPTION 'new NCR must be OPEN' USING ERRCODE = '55000', CONSTRAINT = 'ck_qms_ncr_open';
        END IF;
        -- 계약 NCR 은 근거 수량을 함께(R3-c)
        IF NEW.src_type IS NOT NULL AND NEW.src_qty IS NULL THEN
            RAISE EXCEPTION 'NCR from a source needs the source quantity' USING ERRCODE = '23514', CONSTRAINT = 'ck_qms_ncr_src_qty_required';
        END IF;
        -- 잠금 없이 읽기(LOT 은 포트가 이미 잠갔거나 보류하지 않는 경우)
        SELECT * INTO v_lot FROM lot_master WHERE lot_id = NEW.lot_id AND comp_cd = NEW.comp_cd;
        IF NOT FOUND THEN
            RAISE EXCEPTION 'lot % does not belong to company %', NEW.lot_id, NEW.comp_cd USING ERRCODE = '23503', CONSTRAINT = 'fk_qms_ncr_lot_comp';
        END IF;
        IF v_lot.item_id <> NEW.item_id OR v_lot.unit_cd <> NEW.unit_cd THEN
            RAISE EXCEPTION 'item and unit must be copied from the lot' USING ERRCODE = '23514', CONSTRAINT = 'ck_qms_ncr_lot_copy';
        END IF;
        IF NEW.defect_id IS NOT NULL
           AND NOT EXISTS (SELECT 1 FROM qms_defect d WHERE d.defect_id = NEW.defect_id AND d.comp_cd = NEW.comp_cd) THEN
            RAISE EXCEPTION 'defect % does not belong to company %', NEW.defect_id, NEW.comp_cd
                USING ERRCODE = '23503', CONSTRAINT = 'fk_qms_ncr_defect_comp';
        END IF;
        IF NEW.insp_id IS NOT NULL THEN
            SELECT * INTO v_insp FROM qms_insp WHERE insp_id = NEW.insp_id AND comp_cd = NEW.comp_cd;
            IF NOT FOUND OR v_insp.lot_id <> NEW.lot_id OR v_insp.status <> 'JUDGED' THEN
                RAISE EXCEPTION 'evidence inspection must be a judged inspection of the same lot'
                    USING ERRCODE = '23514', CONSTRAINT = 'ck_qms_ncr_insp';
            END IF;
        END IF;
        RETURN NEW;
    END IF;
    -- UPDATE: 정체(번호·출처·LOT·근거·근거 수량·보류 여부·만든 기록)는 고정, 내용은 OPEN 동안만, 전이는 정해진 길만
    IF (NEW.comp_cd, NEW.ncr_no, NEW.source, NEW.lot_id, NEW.item_id, NEW.unit_cd, NEW.insp_id, NEW.hold_yn, NEW.created_at, NEW.created_by,
        NEW.src_type, NEW.src_id, NEW.src_qty)
       IS DISTINCT FROM (OLD.comp_cd, OLD.ncr_no, OLD.source, OLD.lot_id, OLD.item_id, OLD.unit_cd, OLD.insp_id, OLD.hold_yn, OLD.created_at,
                         OLD.created_by, OLD.src_type, OLD.src_id, OLD.src_qty) THEN
        RAISE EXCEPTION 'NCR identity is fixed' USING ERRCODE = '55000', CONSTRAINT = 'ck_qms_ncr_immutable';
    END IF;
    IF OLD.status IN ('CLOSED', 'CANCELLED')
       OR (OLD.status = 'PENDING' AND NEW.status NOT IN ('OPEN', 'CLOSED'))
       OR (OLD.status = 'OPEN' AND NEW.status NOT IN ('OPEN', 'PENDING', 'CLOSED', 'CANCELLED')) THEN
        RAISE EXCEPTION 'NCR % cannot move from % to %', OLD.ncr_id, OLD.status, NEW.status USING ERRCODE = '55000', CONSTRAINT = 'ck_qms_ncr_transition';
    END IF;
    IF OLD.status <> 'OPEN' AND (NEW.defect_id, NEW.nc_qty, NEW.description, NEW.occurred_at)
       IS DISTINCT FROM (OLD.defect_id, OLD.nc_qty, OLD.description, OLD.occurred_at) THEN
        RAISE EXCEPTION 'NCR % content can only change while OPEN', OLD.ncr_id USING ERRCODE = '55000', CONSTRAINT = 'ck_qms_ncr_immutable';
    END IF;
    IF NEW.defect_id IS DISTINCT FROM OLD.defect_id AND NEW.defect_id IS NOT NULL
       AND NOT EXISTS (SELECT 1 FROM qms_defect d WHERE d.defect_id = NEW.defect_id AND d.comp_cd = NEW.comp_cd) THEN
        RAISE EXCEPTION 'defect % does not belong to company %', NEW.defect_id, NEW.comp_cd USING ERRCODE = '23503', CONSTRAINT = 'fk_qms_ncr_defect_comp';
    END IF;
    -- 상신·종결은 불량 유형이 있어야(계약 NCR 은 비운 채 만들어진다 — docs/29_P3 38장 NP-4)
    IF NEW.status IN ('PENDING', 'CLOSED') AND OLD.status = 'OPEN' AND NEW.defect_id IS NULL THEN
        RAISE EXCEPTION 'NCR % needs a defect type before submit or close', OLD.ncr_id USING ERRCODE = '23514', CONSTRAINT = 'ck_qms_ncr_defect_required';
    END IF;
    v_sum := coalesce((SELECT sum(qty) FROM qms_ncr_disp WHERE ncr_id = NEW.ncr_id), 0);
    -- 부적합 수량을 지금 처분 합보다 작게 줄이지 않는다(R3-c — 처분 합 ≤ 부적합 수량은 늘 참)
    IF NEW.nc_qty < OLD.nc_qty AND NEW.nc_qty < v_sum THEN
        RAISE EXCEPTION 'NCR quantity cannot go below the disposition total' USING ERRCODE = '23514', CONSTRAINT = 'ck_qms_ncr_disp_over';
    END IF;
    -- 종결·상신은 처분 수량 합 = 부적합 수량일 때만
    IF NEW.status IN ('PENDING', 'CLOSED') AND OLD.status = 'OPEN' AND v_sum <> NEW.nc_qty THEN
        RAISE EXCEPTION 'disposition quantities must add up to the NCR quantity' USING ERRCODE = '23514', CONSTRAINT = 'ck_qms_ncr_disp_sum';
    END IF;
    RETURN NEW;
END
$$;

CREATE OR REPLACE FUNCTION hb_guard_qms_ncr_disp() RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    v_ncr  bigint := CASE WHEN TG_OP = 'DELETE' THEN OLD.ncr_id ELSE NEW.ncr_id END;
    v_comp varchar := CASE WHEN TG_OP = 'DELETE' THEN OLD.comp_cd ELSE NEW.comp_cd END;
    v_qty  numeric;
    v_sum  numeric;
BEGIN
    IF TG_OP = 'UPDATE' THEN
        RAISE EXCEPTION 'disposition lines are replaced, not updated' USING ERRCODE = '55000', CONSTRAINT = 'ck_qms_ncr_disp_no_update';
    END IF;
    -- NCR 머리를 잠그고 OPEN 확인(서비스도 머리를 먼저 잠근다 — 같은 순서)
    SELECT n.nc_qty INTO v_qty FROM qms_ncr n WHERE n.ncr_id = v_ncr AND n.comp_cd = v_comp AND n.status = 'OPEN' FOR UPDATE;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'NCR % is not open in company %', v_ncr, v_comp USING ERRCODE = '55000', CONSTRAINT = 'ck_qms_ncr_disp_open';
    END IF;
    -- 처분 합 ≤ 부적합 수량(R3-c). 합은 잠근 뒤 따로 읽는다 — 같은 머리에 동시 INSERT 는 위 잠금에서 줄 서고 앞 커밋을 본다
    IF TG_OP = 'INSERT' THEN
        v_sum := coalesce((SELECT sum(d.qty) FROM qms_ncr_disp d WHERE d.ncr_id = v_ncr), 0);
        IF v_sum + NEW.qty > v_qty THEN
            RAISE EXCEPTION 'disposition total would exceed the NCR quantity' USING ERRCODE = '23514', CONSTRAINT = 'ck_qms_ncr_disp_over';
        END IF;
    END IF;
    RETURN CASE WHEN TG_OP = 'DELETE' THEN OLD ELSE NEW END;
END
$$;
