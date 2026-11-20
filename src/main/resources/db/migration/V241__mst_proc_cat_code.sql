-- 기준정보: 공정 분류 PROC_CAT 를 회사가 늘릴 수 있게 (docs/68 5장 묶음 2) — V9703(병합 때 재번호)
-- 분기는 시스템 코드 둘만(APQP 공정 흐름도: INSPECT = 검사, PACK = 포장, 나머지 = 가공) — 회사 코드는 "가공"으로 떨어진다.
-- 칸 너비 10 그대로(jOOQ 생성 코드 MstProc 도 그대로) → 회사 코드 최대 10자. 기존 행은 시스템 코드라 그대로 통과.
UPDATE sys_code_grp SET comp_ext_yn = 'Y', code_max_len = 10 WHERE grp_cd = 'PROC_CAT';

ALTER TABLE mst_proc DROP CONSTRAINT ck_mst_proc_cat;
ALTER TABLE mst_proc ADD CONSTRAINT ck_mst_proc_cat CHECK (proc_cat ~ '^[A-Z0-9][A-Z0-9_-]*$');
CREATE TRIGGER tg_mst_proc_code_cat BEFORE INSERT OR UPDATE OF proc_cat ON mst_proc
    FOR EACH ROW EXECUTE FUNCTION hb_check_code_col('PROC_CAT', 'proc_cat');
COMMENT ON COLUMN mst_proc.proc_cat IS '공정 분류 — 공통코드 PROC_CAT(시스템 9종 + 회사 코드). INSPECT·PACK 만 흐름도 분기';
