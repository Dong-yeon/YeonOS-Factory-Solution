-- =====================================================================
-- V9710(병합 때 재번호) 협력사 포털 사용자 — 플랫폼 (docs/42 2장, 사용자 결정 SRM R3c)
-- - 협력사 포털 사용자 = sys_user 의 한 유형(SUPPLIER) + 소속 협력사 cust_id. 계정 표를 새로 만들지 않는다
-- - 유형·소속 협력사는 만든 뒤 바꾸지 않는다 — 내부 계정을 협력사로 돌리면 남은 권한 그룹·결재선·위임이 넘어가고,
--   협력사 계정을 다른 협력사로 옮기면 그 사람이 앞 협력사 자료를 본 세션·기록과 섞인다(새 계정을 만든다, P3)
-- - 협력사 사용자는 권한 그룹·결재선(요청자 포함)·위임에 들어가지 않는다 — 서비스 검사 + 아래 트리거(두 겹)
-- =====================================================================

ALTER TABLE sys_user ADD COLUMN user_type varchar(10) NOT NULL DEFAULT 'INTERNAL';
ALTER TABLE sys_user ADD COLUMN cust_id bigint;
ALTER TABLE sys_user ADD CONSTRAINT ck_sys_user_type CHECK (user_type IN ('INTERNAL', 'SUPPLIER'));
-- SUPPLIER ⇔ 소속 협력사 있음. 협력사 사용자는 부서에 들지 않는다(회사 조직이 아니다)
ALTER TABLE sys_user ADD CONSTRAINT ck_sys_user_type_cust CHECK ((user_type = 'SUPPLIER') = (cust_id IS NOT NULL));
ALTER TABLE sys_user ADD CONSTRAINT ck_sys_user_type_dept CHECK (user_type = 'INTERNAL' OR dept_id IS NULL);
ALTER TABLE sys_user ADD CONSTRAINT fk_sys_user_cust FOREIGN KEY (cust_id) REFERENCES mst_cust (cust_id);
CREATE INDEX ix_sys_user_cust ON sys_user (comp_cd, cust_id) WHERE cust_id IS NOT NULL;
COMMENT ON COLUMN sys_user.user_type IS '사용자 유형 INTERNAL(회사 사용자) · SUPPLIER(협력사 포털 사용자, docs/42). 만든 뒤 바꾸지 않는다';
COMMENT ON COLUMN sys_user.cust_id IS '협력사 포털 사용자의 소속 협력사(mst_cust, 구매·겸용). SUPPLIER 만, 만든 뒤 바꾸지 않는다';

-- 유형·소속 고정 + 새 협력사 사용자는 같은 회사의 사용 중 구매·겸용 거래처 소속만
-- (회사별 표 사이 FK 는 RLS 를 무시한다 → 회사를 직접 본다. 거래처 행을 FOR SHARE 로 잡아 동시 사용 중지와 직렬화)
CREATE FUNCTION hb_check_user_type() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'UPDATE' AND (NEW.user_type <> OLD.user_type OR NEW.cust_id IS DISTINCT FROM OLD.cust_id) THEN
        RAISE EXCEPTION 'user % type and supplier are fixed — create a new account', OLD.user_id
            USING ERRCODE = '55000', CONSTRAINT = 'ck_sys_user_type_fixed';
    END IF;
    IF TG_OP = 'INSERT' AND NEW.cust_id IS NOT NULL THEN
        PERFORM 1 FROM mst_cust c
         WHERE c.cust_id = NEW.cust_id AND c.comp_cd = NEW.comp_cd AND c.use_yn = 'Y'
           AND c.cust_type IN ('PURCHASE', 'BOTH')
           FOR SHARE;
        IF NOT FOUND THEN
            RAISE EXCEPTION 'customer % is not an active supplier of company %', NEW.cust_id, NEW.comp_cd
                USING ERRCODE = '23503', CONSTRAINT = 'fk_sys_user_cust_comp';
        END IF;
    END IF;
    RETURN NEW;
END
$$;
CREATE TRIGGER tg_sys_user_type BEFORE INSERT OR UPDATE OF user_type, cust_id ON sys_user
    FOR EACH ROW EXECUTE FUNCTION hb_check_user_type();

-- 회사 사용자만 들어가는 자리: 권한 그룹 구성원 · 결재 요청자·결재선 · 위임자·대리
CREATE FUNCTION hb_require_internal_user() RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    a bigint;
    b bigint;
BEGIN
    IF TG_TABLE_NAME = 'sys_user_auth' THEN
        a := NEW.user_id;
    ELSIF TG_TABLE_NAME = 'apr_request' THEN
        a := NEW.requester_id;
    ELSIF TG_TABLE_NAME = 'apr_line' THEN
        a := NEW.approver_id;
    ELSE
        a := NEW.delegator_id;
        b := NEW.delegate_id;
    END IF;
    IF EXISTS (SELECT 1 FROM sys_user u WHERE u.user_id IN (a, b) AND u.user_type <> 'INTERNAL') THEN
        RAISE EXCEPTION 'supplier portal user cannot be used in %', TG_TABLE_NAME
            USING ERRCODE = '23514', CONSTRAINT = 'ck_internal_user_only';
    END IF;
    RETURN NEW;
END
$$;
CREATE TRIGGER tg_sys_user_auth_internal BEFORE INSERT OR UPDATE ON sys_user_auth
    FOR EACH ROW EXECUTE FUNCTION hb_require_internal_user();
CREATE TRIGGER tg_apr_request_internal BEFORE INSERT OR UPDATE OF requester_id ON apr_request
    FOR EACH ROW EXECUTE FUNCTION hb_require_internal_user();
CREATE TRIGGER tg_apr_line_internal BEFORE INSERT OR UPDATE OF approver_id ON apr_line
    FOR EACH ROW EXECUTE FUNCTION hb_require_internal_user();
CREATE TRIGGER tg_apr_delegate_internal BEFORE INSERT OR UPDATE OF delegator_id, delegate_id ON apr_delegate
    FOR EACH ROW EXECUTE FUNCTION hb_require_internal_user();

-- 사용자 유형 코드 (사용자 관리 목록·조회 조건)
INSERT INTO sys_code_grp (grp_cd, grp_nm_key, comp_ext_yn) VALUES
    ('USER_TYPE', 'code.grp.USER_TYPE', 'N');
INSERT INTO sys_code (grp_cd, code, code_nm_key, sort_no) VALUES
    ('USER_TYPE', 'INTERNAL', 'code.USER_TYPE.INTERNAL', 1),
    ('USER_TYPE', 'SUPPLIER', 'code.USER_TYPE.SUPPLIER', 2);
