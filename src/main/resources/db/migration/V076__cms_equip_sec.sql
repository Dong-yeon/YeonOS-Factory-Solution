-- =====================================================================
-- CMS 1부 C5 — 설비 보안 대장 (docs/33 4.6, 결정 D11 · docs/27 M7 SEMI E187 대비)
--
-- - cms_equip_sec: 설비 1:1 고정 칸(네트워크·OS·지원 종료일·악성코드 검사·보안 등급·USB·원격 접속·검토일)
--   행이 없으면 "미등록" — 대장 화면은 사용 중 설비 전체에 이 표를 붙여 보여 준다(P1 mst_equip 은 고치지 않음, D15 와 같은 방식)
-- - 경고(지원 종료 OS·검사 기한 경과·등급 미달·미검토)는 저장하지 않고 조회 때 계산(SecWarn) — 날짜가 지나면 저절로 바뀐다
-- - 정책 CMS_SEC_SCAN_MAX_DAYS(검사 주기, 기본 90)·CMS_SEC_REVIEW_DAYS(검토 주기, 기본 365)
-- 임시 번호(docs/29 2.2) — 병합 때 이름만 바뀐다.
-- =====================================================================

INSERT INTO sys_policy_def (policy_key, value_type, default_value, allowed_values, min_value, max_value, desc_key) VALUES
    ('CMS_SEC_SCAN_MAX_DAYS', 'INT', '90',  NULL, 1, 730,  'policy.CMS_SEC_SCAN_MAX_DAYS'),
    ('CMS_SEC_REVIEW_DAYS',   'INT', '365', NULL, 1, 1095, 'policy.CMS_SEC_REVIEW_DAYS');

INSERT INTO sys_code_grp (grp_cd, grp_nm_key, comp_ext_yn) VALUES
    ('CMS_SEC_LEVEL',     'code.grp.CMS_SEC_LEVEL',     'N'),
    ('CMS_REMOTE_ACCESS', 'code.grp.CMS_REMOTE_ACCESS', 'N'),
    ('CMS_SEC_WARN',      'code.grp.CMS_SEC_WARN',      'N');
INSERT INTO sys_code (grp_cd, code, code_nm_key, sort_no) VALUES
    ('CMS_SEC_LEVEL', 'SL0', 'code.CMS_SEC_LEVEL.SL0', 1),
    ('CMS_SEC_LEVEL', 'SL1', 'code.CMS_SEC_LEVEL.SL1', 2),
    ('CMS_SEC_LEVEL', 'SL2', 'code.CMS_SEC_LEVEL.SL2', 3),
    ('CMS_SEC_LEVEL', 'SL3', 'code.CMS_SEC_LEVEL.SL3', 4),
    ('CMS_REMOTE_ACCESS', 'NONE',       'code.CMS_REMOTE_ACCESS.NONE',       1),
    ('CMS_REMOTE_ACCESS', 'VENDOR_VPN', 'code.CMS_REMOTE_ACCESS.VENDOR_VPN', 2),
    ('CMS_REMOTE_ACCESS', 'OTHER',      'code.CMS_REMOTE_ACCESS.OTHER',      3),
    -- 경고 종류(저장하지 않는 계산값의 이름 — 화면 필터·엑셀 글자)
    ('CMS_SEC_WARN', 'UNREGISTERED', 'code.CMS_SEC_WARN.UNREGISTERED', 1),
    ('CMS_SEC_WARN', 'EOS',          'code.CMS_SEC_WARN.EOS',          2),
    ('CMS_SEC_WARN', 'SCAN_OVERDUE', 'code.CMS_SEC_WARN.SCAN_OVERDUE', 3),
    ('CMS_SEC_WARN', 'SL_GAP',       'code.CMS_SEC_WARN.SL_GAP',       4),
    ('CMS_SEC_WARN', 'UNREVIEWED',   'code.CMS_SEC_WARN.UNREVIEWED',   5);

CREATE TABLE cms_equip_sec (
    sec_id          bigint        GENERATED ALWAYS AS IDENTITY,
    comp_cd         varchar(20)   NOT NULL,
    equip_id        bigint        NOT NULL,
    network_yn      char(1)       NOT NULL DEFAULT 'N',
    os_nm           varchar(100),
    os_ver          varchar(50),
    os_eos_date     date,
    last_scan_date  date,
    scan_tool       varchar(100),
    target_sl       varchar(3),
    current_sl      varchar(3),
    usb_ctrl_yn     char(1)       NOT NULL DEFAULT 'N',
    remote_access   varchar(10)   NOT NULL DEFAULT 'NONE',
    remote_text     varchar(200),
    review_date     date,
    remark          varchar(500),
    row_ver         integer       NOT NULL DEFAULT 0,
    created_at      timestamptz   NOT NULL DEFAULT now(),
    created_by      varchar(50)   NOT NULL,
    updated_at      timestamptz   NOT NULL DEFAULT now(),
    updated_by      varchar(50)   NOT NULL,
    CONSTRAINT pk_cms_equip_sec PRIMARY KEY (sec_id),
    CONSTRAINT fk_cms_equip_sec_comp FOREIGN KEY (comp_cd) REFERENCES sys_comp (comp_cd),
    CONSTRAINT fk_cms_equip_sec_equip FOREIGN KEY (equip_id) REFERENCES mst_equip (equip_id),
    CONSTRAINT uq_cms_equip_sec_equip UNIQUE (comp_cd, equip_id),
    CONSTRAINT ck_cms_equip_sec_network CHECK (network_yn IN ('Y', 'N')),
    CONSTRAINT ck_cms_equip_sec_usb CHECK (usb_ctrl_yn IN ('Y', 'N')),
    CONSTRAINT ck_cms_equip_sec_sl CHECK ((target_sl IS NULL OR target_sl IN ('SL0', 'SL1', 'SL2', 'SL3'))
                                         AND (current_sl IS NULL OR current_sl IN ('SL0', 'SL1', 'SL2', 'SL3'))),
    -- 원격 접속 "기타"는 방식을 글로 적는다
    CONSTRAINT ck_cms_equip_sec_remote CHECK (remote_access IN ('NONE', 'VENDOR_VPN', 'OTHER')
                                             AND (remote_access <> 'OTHER' OR length(btrim(coalesce(remote_text, ''))) > 0)),
    -- 검사 도구는 검사일이 있을 때만
    CONSTRAINT ck_cms_equip_sec_scan CHECK (scan_tool IS NULL OR last_scan_date IS NOT NULL),
    CONSTRAINT ck_cms_equip_sec_dates CHECK (
        (os_eos_date IS NULL OR os_eos_date BETWEEN DATE '1980-01-01' AND DATE '2999-12-31')
        AND (last_scan_date IS NULL OR last_scan_date >= DATE '2000-01-01')
        AND (review_date IS NULL OR review_date >= DATE '2000-01-01'))
);
COMMENT ON TABLE cms_equip_sec IS '설비 보안 대장 (docs/33 C5, D11, SEMI E187 대비) — 설비 1:1. 경고는 저장하지 않고 조회 때 계산';
COMMENT ON COLUMN cms_equip_sec.network_yn IS '공장 네트워크 연결 — N 이면 악성코드 검사 기한 경고에서 빠진다(오프라인 설비)';

CREATE FUNCTION hb_guard_cms_equip_sec() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'UPDATE' AND (NEW.equip_id <> OLD.equip_id OR NEW.comp_cd <> OLD.comp_cd) THEN
        RAISE EXCEPTION 'security row equipment is fixed' USING ERRCODE = '55000', CONSTRAINT = 'ck_cms_equip_sec_fixed';
    END IF;
    IF TG_OP = 'INSERT' AND NOT EXISTS (SELECT 1 FROM mst_equip e WHERE e.equip_id = NEW.equip_id AND e.comp_cd = NEW.comp_cd
                                           AND e.use_yn = 'Y') THEN
        RAISE EXCEPTION 'equipment % is not an active equipment of company %', NEW.equip_id, NEW.comp_cd
            USING ERRCODE = '23503', CONSTRAINT = 'fk_cms_equip_sec_equip_comp';
    END IF;
    RETURN NEW;
END
$$;
CREATE TRIGGER tg_cms_equip_sec_guard BEFORE INSERT OR UPDATE ON cms_equip_sec FOR EACH ROW EXECUTE FUNCTION hb_guard_cms_equip_sec();
CREATE TRIGGER tg_cms_equip_sec_no_delete BEFORE DELETE ON cms_equip_sec FOR EACH ROW EXECUTE FUNCTION hb_forbid_delete_master();
CREATE TRIGGER tg_audit AFTER INSERT OR UPDATE OR DELETE ON cms_equip_sec FOR EACH ROW EXECUTE FUNCTION hb_audit('sec_id', '', '');

ALTER TABLE cms_equip_sec ENABLE ROW LEVEL SECURITY;
ALTER TABLE cms_equip_sec FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON cms_equip_sec USING (comp_cd = hb_current_comp()) WITH CHECK (comp_cd = hb_current_comp());
REVOKE DELETE, TRUNCATE ON cms_equip_sec FROM ${app_role};

INSERT INTO sys_prog (prog_cd, prog_nm_key, view_path, module_cd, actions, sort_no) VALUES
    ('cms-equip-sec', 'prog.cmsEquipSec', 'cms/equip-sec-mgt', 'CMS', ARRAY['SEARCH','SAVE','EXCEL']::varchar(20)[], 580);
INSERT INTO sys_retention_table (table_nm, class_cd) VALUES ('cms_equip_sec', 'MASTER');
