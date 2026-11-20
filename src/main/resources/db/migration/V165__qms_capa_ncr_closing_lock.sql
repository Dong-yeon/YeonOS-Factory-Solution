-- =====================================================================
-- P3 QMS 8D ↔ NCR 연결 — 종결 결재 중 연결 고정 (docs/29_P3 CN-7) — 임시 번호, 병합 때 재번호
--
-- 지금까지는 서비스만 막았다(QMS_CAPA_STATE): 종결 결재 중(CLOSING) 8D 의 ncr_id 를 앱 계정 직접 SQL 로 바꿀 수 있었다.
-- hb_guard_qms_capa 의 "결재 중 내용 고정" 목록에 ncr_id 가 없어서다. 종결·취소(CLOSED·CANCELLED)는 이미 행 전체가 고정.
-- 같은 회사 확인 트리거(hb_check_qms_capa_ncr, Q8a)에 결재 중 고정을 더한다 — 제약 이름은 결재 중 고정과 같은 것.
-- =====================================================================

CREATE OR REPLACE FUNCTION hb_check_qms_capa_ncr() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'UPDATE' AND OLD.status = 'CLOSING' AND NEW.ncr_id IS DISTINCT FROM OLD.ncr_id THEN
        RAISE EXCEPTION 'CAPA % is in closing approval (NCR link is fixed)', OLD.capa_id
            USING ERRCODE = '55000', CONSTRAINT = 'ck_qms_capa_closing_locked';
    END IF;
    IF NEW.ncr_id IS NOT NULL AND (TG_OP = 'INSERT' OR NEW.ncr_id IS DISTINCT FROM OLD.ncr_id)
       AND NOT EXISTS (SELECT 1 FROM qms_ncr n WHERE n.ncr_id = NEW.ncr_id AND n.comp_cd = NEW.comp_cd) THEN
        RAISE EXCEPTION 'NCR % does not belong to company %', NEW.ncr_id, NEW.comp_cd USING ERRCODE = '23503', CONSTRAINT = 'fk_qms_capa_ncr_comp';
    END IF;
    RETURN NEW;
END
$$;
