-- =====================================================================
-- 시스템관리 QA 2부 O-4 (docs/qa/2026-10-22_module-boundary.md, docs/36 16.6) — 대리가 사용 중지되면 진행 중 위임 종료.
-- 임시 번호(병합 때 재번호, docs/29 2.2)
--
-- 1. apr_delegate.revoke_cd: SELF(위임자 회수) · DELEGATE_OFF(대리 사용 중지로 종료). NULL + revoked_at 있음 = 옛 본인 회수
-- 2. sys_user.use_yn Y → N 이면 그 사람이 대리인 **끝나지 않은**(to_date ≥ 회사 날짜) 위임을 종료 — 화면·엑셀·직접 SQL 어느 경로든.
--    위임자 알림은 앱(같은 트랜잭션의 revoked_at = now() 행)
-- =====================================================================

ALTER TABLE apr_delegate ADD COLUMN revoke_cd varchar(20);
ALTER TABLE apr_delegate ADD CONSTRAINT ck_apr_delegate_revoke_cd
    CHECK (revoke_cd IS NULL OR (revoke_cd IN ('SELF', 'DELEGATE_OFF') AND revoked_at IS NOT NULL));
COMMENT ON COLUMN apr_delegate.revoke_cd IS '회수 사유: SELF 위임자 회수 / DELEGATE_OFF 대리 사용 중지로 종료 (NULL + revoked_at = 옛 본인 회수)';

CREATE FUNCTION hb_end_delegations_of_disabled() RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    v_today date;
BEGIN
    SELECT (now() AT TIME ZONE c.time_zone)::date INTO v_today FROM sys_comp c WHERE c.comp_cd = NEW.comp_cd;
    UPDATE apr_delegate
       SET revoked_at = now(), revoke_cd = 'DELEGATE_OFF', row_ver = row_ver + 1,
           updated_at = now(), updated_by = 'system:delegate-off'
     WHERE comp_cd = NEW.comp_cd AND delegate_id = NEW.user_id AND revoked_at IS NULL AND to_date >= v_today;
    RETURN NULL;
END
$$;
COMMENT ON FUNCTION hb_end_delegations_of_disabled() IS '사용자 사용 중지 → 그가 대리인 끝나지 않은 위임 종료 (docs/36 16.6)';
CREATE TRIGGER tg_sys_user_end_delegations AFTER UPDATE OF use_yn ON sys_user
    FOR EACH ROW WHEN (OLD.use_yn = 'Y' AND NEW.use_yn = 'N')
    EXECUTE FUNCTION hb_end_delegations_of_disabled();
