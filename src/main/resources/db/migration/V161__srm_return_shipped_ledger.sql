-- =====================================================================
-- SRM 2부 R4-4 뒷정리 — 반품 출고됨 ⇔ 반품 출고 원장 (docs/30 12.14 "리뷰 5" 안 (나), 상위 승인 2026-10-23) — 임시 번호(docs/29 2.2)
--
-- 커밋 때(지연 제약 트리거) 반품 하나에 대해: srm_return.status = 'SHIPPED' ⇔ 그 반품의 되돌리지 않은 RTN_SUP 원장 행이 있음.
-- 게이트(ReturnShipGate)를 거치지 않은 직접 UPDATE(앱 계정 SQL)·직접 원장 INSERT 로 둘이 어긋나면 커밋이 거부된다.
-- - 두 표 모두에서 걸어 어느 쪽만 바뀌어도 잡는다: srm_return 상태 바뀜 / inv_trx 의 출처 SRM_RETURN 행
-- - RLS 아래(호출자 권한, SECURITY DEFINER 없음) — 같은 회사 행만 보이고, 반품·원장은 늘 같은 회사(게이트·트리거가 보장)
-- - 모듈 사이 DB 결합(SRM 표 트리거가 WMS 원장을 읽음)은 의도한 것 — 근거·기각한 대안은 docs/30 12.14
-- - 남의 표 변경: srm_return(트리거 추가)
-- =====================================================================

CREATE FUNCTION hb_check_srm_return_ledger() RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    v_id     bigint;
    v_status varchar(20);
    v_live   boolean;
BEGIN
    IF TG_TABLE_NAME = 'srm_return' THEN
        v_id := NEW.return_id;
    ELSE
        v_id := NEW.src_id;
    END IF;
    SELECT r.status INTO v_status FROM srm_return r WHERE r.return_id = v_id;
    SELECT EXISTS (SELECT 1 FROM inv_trx t
                    WHERE t.src_type = 'SRM_RETURN' AND t.src_id = v_id AND t.trx_type = 'RTN_SUP' AND t.reversal_of_id IS NULL
                      AND NOT EXISTS (SELECT 1 FROM inv_trx x WHERE x.reversal_of_id = t.trx_id))
      INTO v_live;
    IF (v_status IS NOT DISTINCT FROM 'SHIPPED') <> v_live THEN
        RAISE EXCEPTION 'return % is % but its return shipment ledger is %', v_id, coalesce(v_status, 'missing'),
            CASE WHEN v_live THEN 'open' ELSE 'absent' END
            USING ERRCODE = '23514', CONSTRAINT = 'ck_srm_return_shipped_ledger';
    END IF;
    RETURN NULL;
END
$$;

-- 반품: 상태가 바뀐 행만(작성 중 편집은 보지 않는다)
CREATE CONSTRAINT TRIGGER tg_srm_return_shipped_ledger AFTER UPDATE ON srm_return
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW WHEN (NEW.status IS DISTINCT FROM OLD.status) EXECUTE FUNCTION hb_check_srm_return_ledger();

-- 원장: 반품 출처 행(출고·역분개)
CREATE CONSTRAINT TRIGGER tg_inv_trx_srm_return_ledger AFTER INSERT ON inv_trx
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW WHEN (NEW.src_type = 'SRM_RETURN') EXECUTE FUNCTION hb_check_srm_return_ledger();
