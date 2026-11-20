-- =====================================================================
-- QMS 고객 반품 → 클레임 NCR 계약 NcrPort (docs/29_P3 38장, 사용자 결정 R-2 (가), docs/59 7장 2단계)
-- 1) qms_ncr 에 만든 근거(src_type·src_id) — 같은 반품 두 번 = 같은 NCR(회사별 유일)
-- 2) 계약 NCR 은 불량 유형을 비운 채 만들 수 있다(반품 순간엔 원인 모름) — 상신·종결 전에 채워야
-- 3) 트리거: 근거 칸은 정체(고정), 불량 유형 null 안전, 상신·종결에 불량 유형 필수
-- 임시 번호(docs/29 2.2) — 병합 때 이름만 바뀐다.
-- =====================================================================

ALTER TABLE qms_ncr ADD COLUMN src_type varchar(20);
ALTER TABLE qms_ncr ADD COLUMN src_id bigint;
ALTER TABLE qms_ncr ALTER COLUMN defect_id DROP NOT NULL;
ALTER TABLE qms_ncr ADD CONSTRAINT ck_qms_ncr_src CHECK ((src_type IS NULL) = (src_id IS NULL) AND (src_type IS NULL OR src_type IN ('WMS_CUST_RTN')));
ALTER TABLE qms_ncr ADD CONSTRAINT ck_qms_ncr_src_claim CHECK (src_type IS NULL OR source = 'CLAIM');
ALTER TABLE qms_ncr ADD CONSTRAINT ck_qms_ncr_defect_src CHECK (defect_id IS NOT NULL OR src_type IS NOT NULL);
CREATE UNIQUE INDEX uq_qms_ncr_src ON qms_ncr (comp_cd, src_type, src_id) WHERE src_type IS NOT NULL;
COMMENT ON COLUMN qms_ncr.src_type IS '다른 모듈 계약이 만든 NCR 의 근거 종류(WMS_CUST_RTN = 고객 반품 입고) — 손으로 만든 NCR 은 null. 정체 칸(고정)';
COMMENT ON COLUMN qms_ncr.src_id IS '근거 id(반품 id) — (comp_cd, src_type, src_id) 유일 = 같은 반품 두 번이 같은 NCR';
COMMENT ON COLUMN qms_ncr.defect_id IS '불량 유형 — 계약 NCR(src_type 있음)만 비운 채 만들 수 있고 상신·종결 전에 채운다(ck_qms_ncr_defect_required)';

CREATE OR REPLACE FUNCTION hb_guard_qms_ncr() RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    v_lot   lot_master;
    v_insp  qms_insp;
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'NCR cannot be deleted (cancel it)' USING ERRCODE = '55000', CONSTRAINT = 'ck_qms_ncr_no_delete';
    END IF;
    IF TG_OP = 'INSERT' THEN
        IF NEW.status <> 'OPEN' THEN
            RAISE EXCEPTION 'new NCR must be OPEN' USING ERRCODE = '55000', CONSTRAINT = 'ck_qms_ncr_open';
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
    -- UPDATE: 정체(번호·출처·LOT·근거·보류 여부·만든 기록)는 고정, 내용은 OPEN 동안만, 전이는 정해진 길만
    IF (NEW.comp_cd, NEW.ncr_no, NEW.source, NEW.lot_id, NEW.item_id, NEW.unit_cd, NEW.insp_id, NEW.hold_yn, NEW.created_at, NEW.created_by,
        NEW.src_type, NEW.src_id)
       IS DISTINCT FROM (OLD.comp_cd, OLD.ncr_no, OLD.source, OLD.lot_id, OLD.item_id, OLD.unit_cd, OLD.insp_id, OLD.hold_yn, OLD.created_at,
                         OLD.created_by, OLD.src_type, OLD.src_id) THEN
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
    -- 종결·상신은 처분 수량 합 = 부적합 수량일 때만
    IF NEW.status IN ('PENDING', 'CLOSED') AND OLD.status = 'OPEN'
       AND coalesce((SELECT sum(qty) FROM qms_ncr_disp WHERE ncr_id = NEW.ncr_id), 0) <> NEW.nc_qty THEN
        RAISE EXCEPTION 'disposition quantities must add up to the NCR quantity' USING ERRCODE = '23514', CONSTRAINT = 'ck_qms_ncr_disp_sum';
    END IF;
    RETURN NEW;
END
$$;
