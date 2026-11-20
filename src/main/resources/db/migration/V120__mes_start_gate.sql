-- =====================================================================
-- MES 착수 게이트 확장점 (docs/25 4.21, docs/29_P3 12.9 — QMS Q9 경계, MES 합의 2026-10-22)
-- - prd_wo_proc_event.gate_detail: 경고(WARN)를 확인하고 착수한 근거 — 문구가 아니라 출처:코드(참조) 나열(예 QUAL:WELD, QMS:QMS_FIRST_PENDING(LOT 12))
--   OK 착수·그 밖 이벤트는 NULL. 차단(BLOCK)은 이벤트가 남지 않는다(지금처럼)
-- - 정책 START_GATE_ON_ERROR: 게이트 빈이 오류로 끝났을 때 BLOCK(실패 닫힘, 기본) / WARN
-- =====================================================================

ALTER TABLE prd_wo_proc_event ADD COLUMN gate_detail varchar(500);
ALTER TABLE prd_wo_proc_event ADD CONSTRAINT ck_prd_wo_proc_event_gate_detail
    CHECK (gate_detail IS NULL OR (gate_mode = 'WARN' AND length(btrim(gate_detail)) > 0));
COMMENT ON COLUMN prd_wo_proc_event.gate_detail IS '경고 확인 착수의 근거 — 출처:코드(참조) 나열(docs/25 4.21). WARN 착수만';

INSERT INTO sys_policy_def (policy_key, value_type, default_value, allowed_values, min_value, max_value, plant_scope_yn, desc_key) VALUES
    ('START_GATE_ON_ERROR', 'ENUM', 'BLOCK', ARRAY['BLOCK','WARN'], NULL, NULL, 'N', 'policy.START_GATE_ON_ERROR');
