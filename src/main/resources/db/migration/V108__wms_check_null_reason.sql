-- =====================================================================
-- WMS CHECK 구멍 메우기 (상위 세션 점검 2026-10-22) — 임시 번호(docs/29 2.2)
--
-- `A OR (B AND length(btrim(x)) > 0)` 는 x 가 NULL 이면 length(...) 가 NULL → 식 전체 NULL → CHECK 통과.
-- 그래서 사유 없는 역분개(inv_trx, V048)·사유 없는 재발행(wms_print_job, V038)이 직접 SQL 로 들어갈 수 있었다.
-- 화면·서비스 경로는 사유를 먼저 검사하므로 실제로 생긴 행은 없을 것 — 그래도 있으면 마이그레이션을 멈추지 않고
-- 제약을 NOT VALID 로 남긴다(새 행만 검사, 기존 행은 그대로 — 원장은 고치지 않는다). 남았는지는 pg_constraint.convalidated 로 본다.
-- =====================================================================

-- inv_trx: 최신 정의(V048, V107 은 type·sign 만 바꿈) + remark NOT NULL
ALTER TABLE inv_trx DROP CONSTRAINT ck_inv_trx_correct;
ALTER TABLE inv_trx ADD CONSTRAINT ck_inv_trx_correct CHECK ((reversal_of_id IS NULL AND entry_method <> 'CORRECT')
    OR (reversal_of_id IS NOT NULL AND entry_method = 'CORRECT' AND remark IS NOT NULL AND length(btrim(remark)) > 0)) NOT VALID;

ALTER TABLE wms_print_job DROP CONSTRAINT ck_wms_print_job_reprint;
ALTER TABLE wms_print_job ADD CONSTRAINT ck_wms_print_job_reprint CHECK (reprint_of_id IS NULL
    OR (reprint_reason IS NOT NULL AND length(btrim(reprint_reason)) >= 2)) NOT VALID;

-- 기존 행 검사 — 위반 행이 있으면 NOT VALID 로 두고 알림만(검사 자체는 RLS 와 무관하게 표 전체를 본다)
DO $$
BEGIN
    BEGIN
        ALTER TABLE inv_trx VALIDATE CONSTRAINT ck_inv_trx_correct;
    EXCEPTION WHEN check_violation THEN
        RAISE NOTICE 'ck_inv_trx_correct left NOT VALID: existing reversal rows without remark';
    END;
    BEGIN
        ALTER TABLE wms_print_job VALIDATE CONSTRAINT ck_wms_print_job_reprint;
    EXCEPTION WHEN check_violation THEN
        RAISE NOTICE 'ck_wms_print_job_reprint left NOT VALID: existing reprint rows without reason';
    END;
END
$$;
