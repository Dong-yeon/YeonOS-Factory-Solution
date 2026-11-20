-- =====================================================================
-- 부서장 (docs/36 9.7 D-1, 사용자 결정 2026-11-01) — V218·임시 V9716
-- - sys_dept.head_user_id: 선택. 새로 넣거나 바꿀 때만 같은 회사·회사 사용자(INTERNAL)·사용 중인지 본다(트리거)
--   이미 장으로 걸린 사람이 나중에 사용 중지·퇴사해도 행은 그대로 — 조회(UserDirectory.deptHeadOf)가 건너뛴다(부서 소속 Q10 과 같은 규칙)
-- - 회사별 표 사이 FK 는 RLS 를 무시하므로 같은 회사를 트리거로 직접 확인한다(hb_check_user_dept 와 같은 모양)
-- 임시 번호(docs/29 2.2) — 병합 때 이름만 바뀐다.
-- =====================================================================

ALTER TABLE sys_dept ADD COLUMN head_user_id bigint;
ALTER TABLE sys_dept ADD CONSTRAINT fk_sys_dept_head FOREIGN KEY (head_user_id) REFERENCES sys_user (user_id);
CREATE INDEX ix_sys_dept_head ON sys_dept (comp_cd, head_user_id) WHERE head_user_id IS NOT NULL;
COMMENT ON COLUMN sys_dept.head_user_id IS '부서장 (선택, 한 부서 한 사람). 결재선 기본값의 근거 — UserDirectory.deptHeadOf (docs/36 9.7)';

CREATE FUNCTION hb_check_dept_head() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.head_user_id IS NOT NULL AND (TG_OP = 'INSERT' OR NEW.head_user_id IS DISTINCT FROM OLD.head_user_id) THEN
        IF NOT EXISTS (SELECT 1 FROM sys_user u WHERE u.user_id = NEW.head_user_id AND u.comp_cd = NEW.comp_cd
                                                  AND u.user_type = 'INTERNAL') THEN
            RAISE EXCEPTION 'head user % is not an internal user of company %', NEW.head_user_id, NEW.comp_cd
                USING ERRCODE = '23503', CONSTRAINT = 'fk_sys_dept_head_comp';
        END IF;
        -- 사용자 행을 FOR SHARE 로 잡아 동시에 그 사람을 사용 중지하는 것과 직렬화(확인 후 실행 사이에 꺼지지 않게)
        PERFORM 1 FROM sys_user u WHERE u.user_id = NEW.head_user_id AND u.use_yn = 'Y' FOR SHARE;
        IF NOT FOUND THEN
            RAISE EXCEPTION 'head user % is not in use', NEW.head_user_id
                USING ERRCODE = '23514', CONSTRAINT = 'ck_sys_dept_head_use';
        END IF;
    END IF;
    RETURN NEW;
END
$$;
CREATE TRIGGER tg_sys_dept_head BEFORE INSERT OR UPDATE OF head_user_id ON sys_dept
    FOR EACH ROW EXECUTE FUNCTION hb_check_dept_head();
