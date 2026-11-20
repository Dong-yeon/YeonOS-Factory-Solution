-- =====================================================================
-- SRM 협력사 포털 P-4 클레임 회신 (docs/31 4.13.1 · 4.15, docs/42 7장 3) — 임시 번호(docs/29 2.2)
--
-- - 회신을 누가 넣었는지: answer_source = MANUAL(우리 직원이 대신 입력, 기존 화면) / PORTAL(협력사 포털), answered_by = 넣은 사람(감사 행위자 문자열)
-- - 지난 회신 행은 비워 둔다(NULL = 이 칸이 생기기 전 회신 — 화면은 "대신 입력"으로 보인다). 채우지 않는 이유: 표가 FORCE RLS 라
--   마이그레이션(소유자, 회사 범위 없음)의 UPDATE 는 0행이고, 회사마다 범위를 열어 채우는 길은 RLS 우회 금지 규칙에 걸린다
-- - 회신 칸 고정 규칙(보냄·회신 받음에서만 바뀜)에 두 칸을 더한다 — hb_guard_srm_claim 본문은 V147 과 같고 그 비교 칸만 늘린다
-- =====================================================================

ALTER TABLE srm_claim ADD COLUMN answer_source varchar(10);
ALTER TABLE srm_claim ADD COLUMN answered_by varchar(50);
ALTER TABLE srm_claim ADD CONSTRAINT ck_srm_claim_answer_source
    CHECK (answer_source IS NULL OR (answer_source IN ('MANUAL', 'PORTAL') AND answer_cause IS NOT NULL AND answered_by IS NOT NULL));
COMMENT ON COLUMN srm_claim.answer_source IS '회신 입력 경로 — MANUAL(우리 직원 대신 입력)·PORTAL(협력사 포털), NULL = 칸이 생기기 전 회신';
COMMENT ON COLUMN srm_claim.answered_by IS '회신을 넣은 사람(감사 행위자 문자열) — 포털이면 협력사 사용자';

CREATE OR REPLACE FUNCTION hb_guard_srm_claim() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'INSERT' THEN
        IF NEW.status <> 'DRAFT' THEN
            RAISE EXCEPTION 'new claim must be DRAFT' USING ERRCODE = '55000', CONSTRAINT = 'ck_srm_claim_transition';
        END IF;
        IF NOT hb_srm_supplier_of_comp(NEW.comp_cd, NEW.cust_id) THEN
            RAISE EXCEPTION 'supplier % is not of company %', NEW.cust_id, NEW.comp_cd
                USING ERRCODE = '23503', CONSTRAINT = 'fk_srm_claim_supplier_comp';
        END IF;
    ELSE
        IF (NEW.claim_no, NEW.comp_cd, NEW.cust_id, NEW.created_at, NEW.created_by)
           IS DISTINCT FROM (OLD.claim_no, OLD.comp_cd, OLD.cust_id, OLD.created_at, OLD.created_by) THEN
            RAISE EXCEPTION 'number and supplier are fixed' USING ERRCODE = '55000', CONSTRAINT = 'ck_srm_claim_fixed';
        END IF;
        -- 요구 내용은 작성 중만, 회신 칸은 보낸 뒤(SENT) 또는 회신으로 넘어갈 때만
        IF OLD.status <> 'DRAFT' AND (NEW.action_cd, NEW.title, NEW.request_text, NEW.reply_due, NEW.charge_amt, NEW.currency_cd)
                                     IS DISTINCT FROM (OLD.action_cd, OLD.title, OLD.request_text, OLD.reply_due, OLD.charge_amt, OLD.currency_cd) THEN
            RAISE EXCEPTION '% claim request cannot be edited', OLD.status USING ERRCODE = '55000', CONSTRAINT = 'ck_srm_claim_draft';
        END IF;
        IF OLD.status NOT IN ('SENT', 'ANSWERED') AND (NEW.answer_cause, NEW.answer_action, NEW.answered_on, NEW.answer_source, NEW.answered_by)
                                                      IS DISTINCT FROM (OLD.answer_cause, OLD.answer_action, OLD.answered_on, OLD.answer_source,
                                                                        OLD.answered_by) THEN
            RAISE EXCEPTION 'answer of % claim cannot be changed', OLD.status USING ERRCODE = '55000', CONSTRAINT = 'ck_srm_claim_answer_state';
        END IF;
        IF NEW.status <> OLD.status THEN
            -- ANSWERED → SENT = 회신 반려(다시 요구)
            IF NOT ((OLD.status = 'DRAFT' AND NEW.status IN ('SENT', 'CANCELLED'))
                 OR (OLD.status = 'SENT' AND NEW.status IN ('ANSWERED', 'CANCELLED'))
                 OR (OLD.status = 'ANSWERED' AND NEW.status IN ('CLOSED', 'SENT'))) THEN
                RAISE EXCEPTION 'claim cannot go from % to %', OLD.status, NEW.status USING ERRCODE = '55000', CONSTRAINT = 'ck_srm_claim_transition';
            END IF;
            IF OLD.status = 'DRAFT' AND NEW.status = 'SENT'
               AND NOT EXISTS (SELECT 1 FROM srm_claim_ncr n WHERE n.claim_id = NEW.claim_id) THEN
                RAISE EXCEPTION 'claim has no nonconformance' USING ERRCODE = '55000', CONSTRAINT = 'ck_srm_claim_ncrs';
            END IF;
        END IF;
    END IF;
    IF NEW.currency_cd IS NOT NULL AND (TG_OP = 'INSERT' OR NEW.currency_cd IS DISTINCT FROM OLD.currency_cd)
       AND NOT hb_code_usable(NEW.comp_cd, 'CURRENCY', NEW.currency_cd) THEN
        RAISE EXCEPTION 'currency % is not usable', NEW.currency_cd USING ERRCODE = '23503', CONSTRAINT = 'fk_srm_claim_currency';
    END IF;
    RETURN NEW;
END
$$;
