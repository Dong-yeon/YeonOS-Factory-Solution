-- MES: LOT 수동 보류 사유 코드 LOT_HOLD_REASON (docs/68 Q1 (다), 2026-11-09 결정) — V9711(병합 때 재번호)
-- 집계(파레토)가 쓸모 있는 사유만 코드 + 메모로 나눈다. reason(글자)은 그대로 필수 — 메모가 없으면 서버가 코드 이름을 넣는다
-- (hb_lot_hold_summary·lot_master.hold_reason 요약이 지금처럼 글자로 보인다).
-- reason_cd 는 비어도 된다: 옛 행, 자동 근거(MES_CORRECT·QMS_* — 출처가 곧 분류), 코드 없이 글자만 보낸 API 호출(미분류).
-- 회사 확장(코드 최대 30자). 사유는 분기에 쓰이지 않는다.
INSERT INTO sys_code_grp (grp_cd, grp_nm_key, comp_ext_yn) VALUES ('LOT_HOLD_REASON', 'code.grp.LOT_HOLD_REASON', 'Y');
INSERT INTO sys_code (grp_cd, code, code_nm_key, sort_no) VALUES
    ('LOT_HOLD_REASON', 'QUALITY',     'code.LOT_HOLD_REASON.QUALITY',     10),
    ('LOT_HOLD_REASON', 'SPEC_OUT',    'code.LOT_HOLD_REASON.SPEC_OUT',    20),
    ('LOT_HOLD_REASON', 'MATERIAL',    'code.LOT_HOLD_REASON.MATERIAL',    30),
    ('LOT_HOLD_REASON', 'EQUIP',       'code.LOT_HOLD_REASON.EQUIP',       40),
    ('LOT_HOLD_REASON', 'CUSTOMER',    'code.LOT_HOLD_REASON.CUSTOMER',    50),
    ('LOT_HOLD_REASON', 'INVESTIGATE', 'code.LOT_HOLD_REASON.INVESTIGATE', 60),
    ('LOT_HOLD_REASON', 'OTHER',       'code.LOT_HOLD_REASON.OTHER',       90);

ALTER TABLE lot_hold ADD COLUMN reason_cd varchar(30);
ALTER TABLE lot_hold ADD CONSTRAINT ck_lot_hold_reason_cd CHECK (reason_cd ~ '^[A-Z0-9][A-Z0-9_-]*$');
CREATE TRIGGER tg_lot_hold_code_reason BEFORE INSERT OR UPDATE OF reason_cd ON lot_hold
    FOR EACH ROW EXECUTE FUNCTION hb_check_code_col('LOT_HOLD_REASON', 'reason_cd');
COMMENT ON COLUMN lot_hold.reason_cd IS '보류 사유 코드 — 공통코드 LOT_HOLD_REASON(회사 확장). 화면 수동 보류는 필수, 비면 미분류(옛 행·자동 근거·글자만 API)';
COMMENT ON COLUMN lot_hold.reason IS '보류 사유 글자(메모) — 메모가 없으면 코드 이름. 요약(lot_master.hold_reason)에 쓰인다';

-- 근거 행은 해제 말고는 고정(hb_guard_lot_hold) — 새 칸도 넣은 뒤 바꾸지 못하게(가드 함수는 그대로 두고 칸 하나만 더 막는다)
CREATE FUNCTION hb_guard_lot_hold_reason_cd() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.reason_cd IS DISTINCT FROM OLD.reason_cd THEN
        RAISE EXCEPTION 'hold % reason code is fixed', OLD.hold_id USING ERRCODE = '55000', CONSTRAINT = 'ck_lot_hold_fixed';
    END IF;
    RETURN NEW;
END
$$;
CREATE TRIGGER tg_lot_hold_guard_reason_cd BEFORE UPDATE ON lot_hold FOR EACH ROW EXECUTE FUNCTION hb_guard_lot_hold_reason_cd();
