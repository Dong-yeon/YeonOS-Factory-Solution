-- 시스템관리: 공통코드 전환 기반 (docs/68 5장 묶음 0) — V9701(병합 때 재번호)
-- 1) 그룹별 회사 코드 최대 길이 code_max_len — 회사 코드를 담는 업무 칸이 30자보다 좁은 그룹(공정 분류 10자 등)이
--    칸을 넓히지 않고(남의 표·함수 변수·jOOQ 를 건드리지 않고) 회사 코드를 받게 한다. 기존 그룹은 30 그대로.
-- 2) hb_check_comp_code: 길이 검사를 더한다(기존 두 검사는 그대로).
-- 3) hb_check_code_col(그룹, 칸): 업무 표에 거는 범용 트리거 함수 — 넣을 때·값을 바꿀 때만 hb_code_usable 로 확인.
--    값이 그대로인 UPDATE 는 보지 않는다(회사 코드를 사용 중지해도 옛 행은 고칠 수 있게 — 기존 트리거들과 같은 규칙).
--    다른 회사 코드는 hb_code_usable 이 NEW.comp_cd 로만 찾으므로(+ sys_comp_code RLS) 통과하지 못한다.

ALTER TABLE sys_code_grp ADD COLUMN code_max_len smallint NOT NULL DEFAULT 30;
ALTER TABLE sys_code_grp ADD CONSTRAINT ck_sys_code_grp_max_len CHECK (code_max_len BETWEEN 1 AND 30);
COMMENT ON COLUMN sys_code_grp.code_max_len IS '회사 코드 최대 길이 — 그 코드를 담는 업무 칸 너비(기본 30)';

CREATE OR REPLACE FUNCTION hb_check_comp_code() RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    v_max smallint;
BEGIN
    SELECT g.code_max_len INTO v_max FROM sys_code_grp g WHERE g.grp_cd = NEW.grp_cd AND g.comp_ext_yn = 'Y';
    IF NOT FOUND THEN
        RAISE EXCEPTION 'code group % does not allow company codes', NEW.grp_cd
            USING ERRCODE = '23514', CONSTRAINT = 'ck_sys_comp_code_grp_ext';
    END IF;
    IF EXISTS (SELECT 1 FROM sys_code c WHERE c.grp_cd = NEW.grp_cd AND c.code = NEW.code) THEN
        RAISE EXCEPTION 'code % already exists as a system code in group %', NEW.code, NEW.grp_cd
            USING ERRCODE = '23505', CONSTRAINT = 'uq_sys_comp_code_system';
    END IF;
    IF (TG_OP = 'INSERT' OR NEW.code IS DISTINCT FROM OLD.code) AND length(NEW.code) > v_max THEN
        RAISE EXCEPTION 'code % is longer than % in group %', NEW.code, v_max, NEW.grp_cd
            USING ERRCODE = '23514', CONSTRAINT = 'ck_sys_comp_code_len';
    END IF;
    RETURN NEW;
END
$$;

-- 범용: CREATE TRIGGER tg_{표}_code_{칸} BEFORE INSERT OR UPDATE OF {칸} ON {표}
--         FOR EACH ROW EXECUTE FUNCTION hb_check_code_col('{그룹}', '{칸}');
-- 표에 comp_cd 칸이 있어야 한다. NULL 은 통과(필수 여부는 NOT NULL 이 맡는다)
CREATE FUNCTION hb_check_code_col() RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    v_grp  varchar := TG_ARGV[0];
    v_col  varchar := TG_ARGV[1];
    v_new  varchar := to_jsonb(NEW) ->> v_col;
BEGIN
    IF v_new IS NULL OR (TG_OP = 'UPDATE' AND v_new IS NOT DISTINCT FROM (to_jsonb(OLD) ->> v_col)) THEN
        RETURN NEW;
    END IF;
    IF NOT hb_code_usable(NEW.comp_cd, v_grp, v_new) THEN
        RAISE EXCEPTION '% % is not a usable % code', v_col, v_new, v_grp
            USING ERRCODE = '23503', CONSTRAINT = 'fk_' || TG_TABLE_NAME || '_' || v_col || '_code';
    END IF;
    RETURN NEW;
END
$$;
