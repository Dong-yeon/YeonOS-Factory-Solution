-- 시스템관리: 회사가 시스템 코드를 자기 선택 목록에서 숨긴다 (docs/68 Q4 (나), 2026-11-09 결정) — V9709(병합 때 재번호)
-- 예: 프레스가 없는 회사가 공정 분류 PRESS 를 목록에서 뺀다.
-- 숨김 = 선택 목록(/api/platform/codes)에서만 빠진다. 저장 검사(hb_code_usable)·이름표(/codes/names)는 그대로라
--        이미 그 코드로 저장한 행은 계속 저장·표시된다(숨김이 옛 데이터를 깨지 않게).
-- 대상 = 회사 확장 그룹(comp_ext_yn = 'Y')의 시스템 코드 중 분기에 쓰이지 않는 것. 분기에 쓰이는 코드는 fixed_yn = 'Y' 로 막는다.

-- 1. 분기에 쓰이는 시스템 코드 표시 (숨김 금지)
ALTER TABLE sys_code ADD COLUMN fixed_yn char(1) NOT NULL DEFAULT 'N';
ALTER TABLE sys_code ADD CONSTRAINT ck_sys_code_fixed_yn CHECK (fixed_yn IN ('Y', 'N'));
COMMENT ON COLUMN sys_code.fixed_yn IS '서버 분기에 쓰이는 코드 — Y 면 회사가 숨길 수 없다(sys_comp_code_hide)';
UPDATE sys_code SET fixed_yn = 'Y'
 WHERE (grp_cd, code) IN (
    ('PROC_CAT', 'INSPECT'),     -- APQP 흐름도: 검사 공정
    ('PROC_CAT', 'PACK'),        -- APQP 흐름도: 포장 공정
    ('DOC_TYPE', 'DRAWING'),     -- PLM 파일 잠금·ECO 대상
    ('DOC_TYPE', 'WI'),          -- ECO 대상
    ('DOC_TYPE', 'CP'),          -- ECO 대상
    ('DOC_TYPE', 'INSP_STD'),    -- 검사계획 연결·ECO 대상
    ('DOC_TYPE', 'PFMEA'),       -- ECO 대상
    ('SPECIAL_CHAR', 'NONE'));   -- 특별특성 없음(기본값, CP·흐름도 분기)

-- 2. 회사별 숨김 표
CREATE TABLE sys_comp_code_hide (
    comp_cd     varchar(20)  NOT NULL,
    grp_cd      varchar(30)  NOT NULL,
    code        varchar(30)  NOT NULL,
    created_at  timestamptz  NOT NULL DEFAULT now(),
    created_by  varchar(50)  NOT NULL,
    CONSTRAINT pk_sys_comp_code_hide PRIMARY KEY (comp_cd, grp_cd, code),
    CONSTRAINT fk_sys_comp_code_hide_comp FOREIGN KEY (comp_cd) REFERENCES sys_comp (comp_cd),
    CONSTRAINT fk_sys_comp_code_hide_code FOREIGN KEY (grp_cd, code) REFERENCES sys_code (grp_cd, code)
);
COMMENT ON TABLE sys_comp_code_hide IS '회사가 숨긴 시스템 코드 — 선택 목록에서만 빠짐(저장 검사·이름표 그대로). 행이 있으면 숨김';

ALTER TABLE sys_comp_code_hide ENABLE ROW LEVEL SECURITY;
ALTER TABLE sys_comp_code_hide FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON sys_comp_code_hide
    USING (comp_cd = hb_current_comp()) WITH CHECK (comp_cd = hb_current_comp());

-- 회사 확장 그룹의, 분기에 쓰이지 않는 시스템 코드만
CREATE FUNCTION hb_check_comp_code_hide() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM sys_code_grp g WHERE g.grp_cd = NEW.grp_cd AND g.comp_ext_yn = 'Y') THEN
        RAISE EXCEPTION 'code group % does not allow company changes', NEW.grp_cd
            USING ERRCODE = '23514', CONSTRAINT = 'ck_sys_comp_code_hide_grp_ext';
    END IF;
    IF EXISTS (SELECT 1 FROM sys_code c WHERE c.grp_cd = NEW.grp_cd AND c.code = NEW.code AND c.fixed_yn = 'Y') THEN
        RAISE EXCEPTION 'system code %.% is used by the server and cannot be hidden', NEW.grp_cd, NEW.code
            USING ERRCODE = '23514', CONSTRAINT = 'ck_sys_comp_code_hide_fixed';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER tg_sys_comp_code_hide_check BEFORE INSERT OR UPDATE ON sys_comp_code_hide
    FOR EACH ROW EXECUTE FUNCTION hb_check_comp_code_hide();

CREATE TRIGGER tg_audit AFTER INSERT OR UPDATE OR DELETE ON sys_comp_code_hide
    FOR EACH ROW EXECUTE FUNCTION hb_audit('comp_cd,grp_cd,code', '', '');
INSERT INTO sys_retention_table (table_nm, class_cd) VALUES ('sys_comp_code_hide', 'MASTER');
