-- 기준정보·QMS: 측정 단위 공통코드 MEAS_UNIT (docs/68 Q2 (나), 2026-11-09 결정) — V9710(병합 때 재번호)
-- 공정 파라미터(mst_routing_param)·특성(qms_char)의 공학 단위를 글자에서 코드로. 수량 단위 UNIT(scale = 수량 자릿수)과는 다른 목록이다.
-- 회사 확장: 회사가 단위를 더할 수 있다(코드 최대 30자). 코드는 대문자 규칙이라 기호(mm, °C)는 코드 이름(i18n)으로 보인다.
--
-- 칸: unit_cd(새, 코드) + unit_nm(그대로, 이름 사본). 작업지시·검사 기준·관리계획 등은 지금처럼 unit_nm 글자를 복사하므로 그쪽 표는 바꾸지 않는다.
-- 서버가 저장 때 unit_cd 의 이름을 unit_nm 에 채운다. 기존 행은 unit_cd 가 비어 있고 글자만 있다:
--   - 이 마이그레이션은 행을 고치지 않는다(확정된 라우팅의 파라미터는 hb_guard_routing_param 이 바꾸지 못하게 막고, 확정본은 이력이다).
--   - 다음 저장 때 글자가 코드 이름과 같으면 그 코드로 채운다(서버·화면 같은 규칙). 목록에 없는 글자는 그대로 둔다(조용히 지우지 않는다).
INSERT INTO sys_code_grp (grp_cd, grp_nm_key, comp_ext_yn) VALUES ('MEAS_UNIT', 'code.grp.MEAS_UNIT', 'Y');
INSERT INTO sys_code (grp_cd, code, code_nm_key, sort_no) VALUES
    ('MEAS_UNIT', 'MM',    'code.MEAS_UNIT.MM',     10),
    ('MEAS_UNIT', 'UM',    'code.MEAS_UNIT.UM',     20),
    ('MEAS_UNIT', 'CM',    'code.MEAS_UNIT.CM',     30),
    ('MEAS_UNIT', 'M',     'code.MEAS_UNIT.M',      40),
    ('MEAS_UNIT', 'DEG',   'code.MEAS_UNIT.DEG',    50),
    ('MEAS_UNIT', 'DEG_C', 'code.MEAS_UNIT.DEG_C',  60),
    ('MEAS_UNIT', 'G',     'code.MEAS_UNIT.G',      70),
    ('MEAS_UNIT', 'KG',    'code.MEAS_UNIT.KG',     80),
    ('MEAS_UNIT', 'N',     'code.MEAS_UNIT.N',      90),
    ('MEAS_UNIT', 'KN',    'code.MEAS_UNIT.KN',    100),
    ('MEAS_UNIT', 'N_M',   'code.MEAS_UNIT.N_M',   110),
    ('MEAS_UNIT', 'KGF',   'code.MEAS_UNIT.KGF',   120),
    ('MEAS_UNIT', 'MPA',   'code.MEAS_UNIT.MPA',   130),
    ('MEAS_UNIT', 'KPA',   'code.MEAS_UNIT.KPA',   140),
    ('MEAS_UNIT', 'BAR',   'code.MEAS_UNIT.BAR',   150),
    ('MEAS_UNIT', 'A',     'code.MEAS_UNIT.A',     160),
    ('MEAS_UNIT', 'KA',    'code.MEAS_UNIT.KA',    170),
    ('MEAS_UNIT', 'V',     'code.MEAS_UNIT.V',     180),
    ('MEAS_UNIT', 'KW',    'code.MEAS_UNIT.KW',    190),
    ('MEAS_UNIT', 'S',     'code.MEAS_UNIT.S',     200),
    ('MEAS_UNIT', 'MS',    'code.MEAS_UNIT.MS',    210),
    ('MEAS_UNIT', 'MIN',   'code.MEAS_UNIT.MIN',   220),
    ('MEAS_UNIT', 'RPM',   'code.MEAS_UNIT.RPM',   230),
    ('MEAS_UNIT', 'HZ',    'code.MEAS_UNIT.HZ',    240),
    ('MEAS_UNIT', 'L_MIN', 'code.MEAS_UNIT.L_MIN', 250),
    ('MEAS_UNIT', 'PCT',   'code.MEAS_UNIT.PCT',   260),
    ('MEAS_UNIT', 'HV',    'code.MEAS_UNIT.HV',    270),
    ('MEAS_UNIT', 'HRC',   'code.MEAS_UNIT.HRC',   280);

ALTER TABLE mst_routing_param ADD COLUMN unit_cd varchar(30);
ALTER TABLE mst_routing_param ADD CONSTRAINT ck_mst_routing_param_unit_cd CHECK (unit_cd ~ '^[A-Z0-9][A-Z0-9_-]*$');
CREATE TRIGGER tg_mst_routing_param_code_unit BEFORE INSERT OR UPDATE OF unit_cd ON mst_routing_param
    FOR EACH ROW EXECUTE FUNCTION hb_check_code_col('MEAS_UNIT', 'unit_cd');
COMMENT ON COLUMN mst_routing_param.unit_cd IS '공학 단위 — 공통코드 MEAS_UNIT(회사 확장). 비어 있으면 unit_nm 글자만(옛 행·목록 밖 단위)';
COMMENT ON COLUMN mst_routing_param.unit_nm IS '공학 단위 표시(이름 사본) — unit_cd 가 있으면 서버가 그 코드 이름으로 채운다. 작업지시가 이 글자를 스냅샷한다';

ALTER TABLE qms_char ADD COLUMN unit_cd varchar(30);
ALTER TABLE qms_char ADD CONSTRAINT ck_qms_char_unit_cd CHECK (unit_cd ~ '^[A-Z0-9][A-Z0-9_-]*$');
CREATE TRIGGER tg_qms_char_code_unit BEFORE INSERT OR UPDATE OF unit_cd ON qms_char
    FOR EACH ROW EXECUTE FUNCTION hb_check_code_col('MEAS_UNIT', 'unit_cd');
COMMENT ON COLUMN qms_char.unit_cd IS '측정 단위 — 공통코드 MEAS_UNIT(회사 확장). 비어 있으면 unit_nm 글자만(옛 행·목록 밖 단위)';
COMMENT ON COLUMN qms_char.unit_nm IS '측정 단위 표시(이름 사본) — unit_cd 가 있으면 서버가 그 코드 이름으로 채운다. 검사 기준·관리계획이 이 글자를 복사한다';
