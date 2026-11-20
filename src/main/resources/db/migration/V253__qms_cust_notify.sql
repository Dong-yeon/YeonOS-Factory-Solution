-- =====================================================================
-- QMS 4M 4차 — 고객 통지 판단 (docs/29_P3 52장, docs/66 (라)) — 임시 번호, 병합 때 재번호
--
-- - qms_cust_csr.notify_req: 고객 요구사항(CSR)의 4M 유형별 통지 요구 {"MACHINE":"APPROVE","MATERIAL":"NOTIFY"} — 없는 유형 = 요구 없음
-- - 분류 = 시스템 고정 공통코드 QMS_4M_NOTIFY(판정 분기에 쓰임 — 회사 확장 없음). NONE 은 화면 선택용, 저장하지 않는다
-- - 정책 QMS_CUST_NOTIFY_GATE(WARN·BLOCK, 기본 WARN): BLOCK 이면 ECO 상신 규칙이 "승인 요구인데 ECO 고객 승인 필요 꺼짐" 을 막는다
-- - PCN 은 PLM plm_pcn 그대로(52장 A 채택) — 이 파일은 PLM 표를 바꾸지 않는다
-- =====================================================================

INSERT INTO sys_code_grp (grp_cd, grp_nm_key, comp_ext_yn) VALUES
    ('QMS_4M_NOTIFY', 'code.grp.QMS_4M_NOTIFY', 'N');
INSERT INTO sys_code (grp_cd, code, code_nm_key, sort_no) VALUES
    ('QMS_4M_NOTIFY', 'NONE',    'code.QMS_4M_NOTIFY.NONE',    1),
    ('QMS_4M_NOTIFY', 'NOTIFY',  'code.QMS_4M_NOTIFY.NOTIFY',  2),
    ('QMS_4M_NOTIFY', 'APPROVE', 'code.QMS_4M_NOTIFY.APPROVE', 3);

INSERT INTO sys_policy_def (policy_key, value_type, default_value, allowed_values, min_value, max_value, plant_scope_yn, desc_key) VALUES
    ('QMS_CUST_NOTIFY_GATE', 'ENUM', 'WARN', ARRAY['WARN','BLOCK'], NULL, NULL, 'N', 'policy.QMS_CUST_NOTIFY_GATE');

-- 키는 4M 유형 8개, 값은 NOTIFY·APPROVE 만 (CHECK 안에서 쓰므로 불변 함수)
CREATE FUNCTION hb_qms_notify_req_ok(p jsonb) RETURNS boolean
    LANGUAGE sql IMMUTABLE
AS $$
    SELECT jsonb_typeof(p) = 'object'
       AND NOT EXISTS (SELECT 1 FROM jsonb_each(p) e
                        WHERE e.key NOT IN ('MAN', 'MACHINE', 'MATERIAL', 'METHOD', 'MEASURE', 'ENV', 'SUPPLIER', 'SITE')
                           OR jsonb_typeof(e.value) <> 'string'
                           OR (e.value #>> '{}') NOT IN ('NOTIFY', 'APPROVE'))
$$;

ALTER TABLE qms_cust_csr
    ADD COLUMN notify_req jsonb NOT NULL DEFAULT '{}'::jsonb,
    ADD CONSTRAINT ck_qms_cust_csr_notify_req CHECK (hb_qms_notify_req_ok(notify_req));
COMMENT ON COLUMN qms_cust_csr.notify_req IS '4M 유형별 고객 통지 요구 — NOTIFY(통지만)·APPROVE(승인 필요), 없는 유형 = 요구 없음 (docs/29_P3 52장)';
