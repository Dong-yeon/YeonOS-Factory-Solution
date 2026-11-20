-- =====================================================================
-- CMS 3차 G3 — ISO 14224 고장 기록 칸: 메커니즘·탐지 방법·영향 (docs/33 16.6, docs/64 G3 결정 Q2 ① 선택 입력)
-- - cms_mwo 에 선택 칸 3개(NULL 허용 — 기존 행 그대로). 고장 수리(BM)에만 값을 둔다(CHECK)
-- - 목록은 공통코드: CMS_FAIL_MECH(ISO 14224 고장 메커니즘 상위 6 + 자주 쓰는 하위, 회사 추가 허용),
--   CMS_FAIL_DETECT(ISO 14224 탐지 방법, 회사 추가 허용), CMS_FAIL_IMPACT(정지·성능 저하·잠재, 회사 추가 불가 — 회사 간 비교 축)
--   회사별 고장 코드 표(cms_fail_code)가 아니라 공통코드로 둔 이유: 표준 목록이 있고 클래스 한정이 필요 없는 축 — 공통코드 전환 방향과 같다
-- - 값 검사: 새로 넣거나 바꿀 때만 사용 중 코드(hb_code_usable — 시스템 코드 또는 그 회사 코드). 완료 뒤 불변은 기존 ck_cms_mwo_done_fixed(행 전체 비교)
-- 이름은 표준 분류의 짧은 우리말 표기(attrs.iso = ISO 14224 표 번호, 요약). 임시 번호(docs/29 2.2) — 병합 때 이름만 바뀐다.
-- =====================================================================

INSERT INTO sys_code_grp (grp_cd, grp_nm_key, comp_ext_yn) VALUES
    ('CMS_FAIL_MECH',   'code.grp.CMS_FAIL_MECH',   'Y'),
    ('CMS_FAIL_DETECT', 'code.grp.CMS_FAIL_DETECT', 'Y'),
    ('CMS_FAIL_IMPACT', 'code.grp.CMS_FAIL_IMPACT', 'N');

INSERT INTO sys_code (grp_cd, code, code_nm_key, sort_no, attrs) VALUES
    ('CMS_FAIL_MECH', 'MECH',          'code.CMS_FAIL_MECH.MECH',          100, '{"iso":"1.0","grp":"MECH"}'),
    ('CMS_FAIL_MECH', 'MECH_LEAK',     'code.CMS_FAIL_MECH.MECH_LEAK',     101, '{"iso":"1.1","grp":"MECH"}'),
    ('CMS_FAIL_MECH', 'MECH_VIB',      'code.CMS_FAIL_MECH.MECH_VIB',      102, '{"iso":"1.2","grp":"MECH"}'),
    ('CMS_FAIL_MECH', 'MECH_ALIGN',    'code.CMS_FAIL_MECH.MECH_ALIGN',    103, '{"iso":"1.3","grp":"MECH"}'),
    ('CMS_FAIL_MECH', 'MECH_DEFORM',   'code.CMS_FAIL_MECH.MECH_DEFORM',   104, '{"iso":"1.4","grp":"MECH"}'),
    ('CMS_FAIL_MECH', 'MECH_LOOSE',    'code.CMS_FAIL_MECH.MECH_LOOSE',    105, '{"iso":"1.5","grp":"MECH"}'),
    ('CMS_FAIL_MECH', 'MECH_STICK',    'code.CMS_FAIL_MECH.MECH_STICK',    106, '{"iso":"1.6","grp":"MECH"}'),
    ('CMS_FAIL_MECH', 'MAT',           'code.CMS_FAIL_MECH.MAT',           200, '{"iso":"2.0","grp":"MAT"}'),
    ('CMS_FAIL_MECH', 'MAT_CAVIT',     'code.CMS_FAIL_MECH.MAT_CAVIT',     201, '{"iso":"2.1","grp":"MAT"}'),
    ('CMS_FAIL_MECH', 'MAT_CORR',      'code.CMS_FAIL_MECH.MAT_CORR',      202, '{"iso":"2.2","grp":"MAT"}'),
    ('CMS_FAIL_MECH', 'MAT_EROS',      'code.CMS_FAIL_MECH.MAT_EROS',      203, '{"iso":"2.3","grp":"MAT"}'),
    ('CMS_FAIL_MECH', 'MAT_WEAR',      'code.CMS_FAIL_MECH.MAT_WEAR',      204, '{"iso":"2.4","grp":"MAT"}'),
    ('CMS_FAIL_MECH', 'MAT_BREAK',     'code.CMS_FAIL_MECH.MAT_BREAK',     205, '{"iso":"2.5","grp":"MAT"}'),
    ('CMS_FAIL_MECH', 'MAT_FATIGUE',   'code.CMS_FAIL_MECH.MAT_FATIGUE',   206, '{"iso":"2.6","grp":"MAT"}'),
    ('CMS_FAIL_MECH', 'MAT_OVERHEAT',  'code.CMS_FAIL_MECH.MAT_OVERHEAT',  207, '{"iso":"2.7","grp":"MAT"}'),
    ('CMS_FAIL_MECH', 'MAT_BURST',     'code.CMS_FAIL_MECH.MAT_BURST',     208, '{"iso":"2.8","grp":"MAT"}'),
    ('CMS_FAIL_MECH', 'INST',          'code.CMS_FAIL_MECH.INST',          300, '{"iso":"3.0","grp":"INST"}'),
    ('CMS_FAIL_MECH', 'INST_CTRL',     'code.CMS_FAIL_MECH.INST_CTRL',     301, '{"iso":"3.1","grp":"INST"}'),
    ('CMS_FAIL_MECH', 'INST_NOSIG',    'code.CMS_FAIL_MECH.INST_NOSIG',    302, '{"iso":"3.2","grp":"INST"}'),
    ('CMS_FAIL_MECH', 'INST_BADSIG',   'code.CMS_FAIL_MECH.INST_BADSIG',   303, '{"iso":"3.3","grp":"INST"}'),
    ('CMS_FAIL_MECH', 'INST_ADJ',      'code.CMS_FAIL_MECH.INST_ADJ',      304, '{"iso":"3.4","grp":"INST"}'),
    ('CMS_FAIL_MECH', 'INST_SW',       'code.CMS_FAIL_MECH.INST_SW',       305, '{"iso":"3.5","grp":"INST"}'),
    ('CMS_FAIL_MECH', 'INST_COMMON',   'code.CMS_FAIL_MECH.INST_COMMON',   306, '{"iso":"3.6","grp":"INST"}'),
    ('CMS_FAIL_MECH', 'ELEC',          'code.CMS_FAIL_MECH.ELEC',          400, '{"iso":"4.0","grp":"ELEC"}'),
    ('CMS_FAIL_MECH', 'ELEC_SHORT',    'code.CMS_FAIL_MECH.ELEC_SHORT',    401, '{"iso":"4.1","grp":"ELEC"}'),
    ('CMS_FAIL_MECH', 'ELEC_OPEN',     'code.CMS_FAIL_MECH.ELEC_OPEN',     402, '{"iso":"4.2","grp":"ELEC"}'),
    ('CMS_FAIL_MECH', 'ELEC_NOPOWER',  'code.CMS_FAIL_MECH.ELEC_NOPOWER',  403, '{"iso":"4.3","grp":"ELEC"}'),
    ('CMS_FAIL_MECH', 'ELEC_BADPOWER', 'code.CMS_FAIL_MECH.ELEC_BADPOWER', 404, '{"iso":"4.4","grp":"ELEC"}'),
    ('CMS_FAIL_MECH', 'ELEC_EARTH',    'code.CMS_FAIL_MECH.ELEC_EARTH',    405, '{"iso":"4.5","grp":"ELEC"}'),
    ('CMS_FAIL_MECH', 'EXT',           'code.CMS_FAIL_MECH.EXT',           500, '{"iso":"5.0","grp":"EXT"}'),
    ('CMS_FAIL_MECH', 'EXT_BLOCK',     'code.CMS_FAIL_MECH.EXT_BLOCK',     501, '{"iso":"5.1","grp":"EXT"}'),
    ('CMS_FAIL_MECH', 'EXT_CONTAM',    'code.CMS_FAIL_MECH.EXT_CONTAM',    502, '{"iso":"5.2","grp":"EXT"}'),
    ('CMS_FAIL_MECH', 'EXT_MISC',      'code.CMS_FAIL_MECH.EXT_MISC',      503, '{"iso":"5.3","grp":"EXT"}'),
    ('CMS_FAIL_MECH', 'MISC',          'code.CMS_FAIL_MECH.MISC',          600, '{"iso":"6.0","grp":"MISC"}'),
    ('CMS_FAIL_MECH', 'MISC_NOCAUSE',  'code.CMS_FAIL_MECH.MISC_NOCAUSE',  601, '{"iso":"6.1","grp":"MISC"}'),
    ('CMS_FAIL_MECH', 'MISC_COMBINED', 'code.CMS_FAIL_MECH.MISC_COMBINED', 602, '{"iso":"6.2","grp":"MISC"}'),
    ('CMS_FAIL_MECH', 'MISC_OTHER',    'code.CMS_FAIL_MECH.MISC_OTHER',    603, '{"iso":"6.3","grp":"MISC"}'),
    ('CMS_FAIL_MECH', 'MISC_UNKNOWN',  'code.CMS_FAIL_MECH.MISC_UNKNOWN',  604, '{"iso":"6.4","grp":"MISC"}'),
    ('CMS_FAIL_DETECT', 'PERIODIC_PM', 'code.CMS_FAIL_DETECT.PERIODIC_PM', 1,  '{"iso":"1","preventive":true}'),
    ('CMS_FAIL_DETECT', 'FUNC_TEST',   'code.CMS_FAIL_DETECT.FUNC_TEST',   2,  '{"iso":"2","preventive":true}'),
    ('CMS_FAIL_DETECT', 'INSPECTION',  'code.CMS_FAIL_DETECT.INSPECTION',  3,  '{"iso":"3","preventive":true}'),
    ('CMS_FAIL_DETECT', 'PERIODIC_CM', 'code.CMS_FAIL_DETECT.PERIODIC_CM', 4,  '{"iso":"4","preventive":true}'),
    ('CMS_FAIL_DETECT', 'CBM',         'code.CMS_FAIL_DETECT.CBM',         5,  '{"iso":"5","preventive":true}'),
    ('CMS_FAIL_DETECT', 'PROD_INTERF', 'code.CMS_FAIL_DETECT.PROD_INTERF', 6,  '{"iso":"6"}'),
    ('CMS_FAIL_DETECT', 'CASUAL',      'code.CMS_FAIL_DETECT.CASUAL',      7,  '{"iso":"7"}'),
    ('CMS_FAIL_DETECT', 'CORRECTIVE',  'code.CMS_FAIL_DETECT.CORRECTIVE',  8,  '{"iso":"8"}'),
    ('CMS_FAIL_DETECT', 'ON_DEMAND',   'code.CMS_FAIL_DETECT.ON_DEMAND',   9,  '{"iso":"9"}'),
    ('CMS_FAIL_DETECT', 'OTHER',       'code.CMS_FAIL_DETECT.OTHER',       10, '{"iso":"10"}'),
    ('CMS_FAIL_IMPACT', 'STOP',        'code.CMS_FAIL_IMPACT.STOP',        1,  '{}'),
    ('CMS_FAIL_IMPACT', 'DEGRADED',    'code.CMS_FAIL_IMPACT.DEGRADED',    2,  '{}'),
    ('CMS_FAIL_IMPACT', 'INCIPIENT',   'code.CMS_FAIL_IMPACT.INCIPIENT',   3,  '{}');

ALTER TABLE cms_mwo ADD COLUMN fail_mech_cd varchar(30);
ALTER TABLE cms_mwo ADD COLUMN fail_detect_cd varchar(30);
ALTER TABLE cms_mwo ADD COLUMN fail_impact_cd varchar(20);
ALTER TABLE cms_mwo ADD CONSTRAINT ck_cms_mwo_iso_bm CHECK (
    mwo_type = 'BM' OR (fail_mech_cd IS NULL AND fail_detect_cd IS NULL AND fail_impact_cd IS NULL));
COMMENT ON COLUMN cms_mwo.fail_mech_cd IS '고장 메커니즘(ISO 14224, 공통코드 CMS_FAIL_MECH — 회사 추가 가능). 선택, 고장 수리만';
COMMENT ON COLUMN cms_mwo.fail_detect_cd IS '탐지 방법(ISO 14224, 공통코드 CMS_FAIL_DETECT — 회사 추가 가능). 알람 자동 신고 = CBM, 점검 NG 후속 = INSPECTION';
COMMENT ON COLUMN cms_mwo.fail_impact_cd IS '고장 영향(STOP 정지 / DEGRADED 성능 저하 / INCIPIENT 잠재, 공통코드 CMS_FAIL_IMPACT — 회사 추가 불가)';
CREATE INDEX ix_cms_mwo_fail_mech ON cms_mwo (comp_cd, fail_mech_cd) WHERE fail_mech_cd IS NOT NULL;

-- 새로 넣거나 바꿀 때만 사용 중 코드(시스템 또는 그 회사 코드). 바꾸지 않은 코드는 사용 중지돼도 둔다(새 참조만 차단)
CREATE FUNCTION hb_guard_cms_mwo_iso() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.fail_mech_cd IS NOT NULL AND (TG_OP = 'INSERT' OR NEW.fail_mech_cd IS DISTINCT FROM OLD.fail_mech_cd)
       AND NOT hb_code_usable(NEW.comp_cd, 'CMS_FAIL_MECH', NEW.fail_mech_cd) THEN
        RAISE EXCEPTION 'failure mechanism % is not usable', NEW.fail_mech_cd USING ERRCODE = '23503', CONSTRAINT = 'fk_cms_mwo_fail_mech_code';
    END IF;
    IF NEW.fail_detect_cd IS NOT NULL AND (TG_OP = 'INSERT' OR NEW.fail_detect_cd IS DISTINCT FROM OLD.fail_detect_cd)
       AND NOT hb_code_usable(NEW.comp_cd, 'CMS_FAIL_DETECT', NEW.fail_detect_cd) THEN
        RAISE EXCEPTION 'detection method % is not usable', NEW.fail_detect_cd USING ERRCODE = '23503', CONSTRAINT = 'fk_cms_mwo_fail_detect_code';
    END IF;
    IF NEW.fail_impact_cd IS NOT NULL AND (TG_OP = 'INSERT' OR NEW.fail_impact_cd IS DISTINCT FROM OLD.fail_impact_cd)
       AND NOT hb_code_usable(NEW.comp_cd, 'CMS_FAIL_IMPACT', NEW.fail_impact_cd) THEN
        RAISE EXCEPTION 'failure impact % is not usable', NEW.fail_impact_cd USING ERRCODE = '23503', CONSTRAINT = 'fk_cms_mwo_fail_impact_code';
    END IF;
    RETURN NEW;
END
$$;
CREATE TRIGGER tg_cms_mwo_iso_guard BEFORE INSERT OR UPDATE ON cms_mwo FOR EACH ROW EXECUTE FUNCTION hb_guard_cms_mwo_iso();
