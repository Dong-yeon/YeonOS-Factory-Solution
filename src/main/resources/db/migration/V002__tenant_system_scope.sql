-- =====================================================================
-- V002 회사 목록 조회 전용 범위 (SYSTEM scope)
-- 배치·정리 작업이 "사용 중인 회사 목록"을 알아야 회사별로 TenantRunner 를 돌릴 수 있다.
-- sys_comp 는 RLS 로 자기 회사 행만 보이므로, 회사 목록 조회만 허용하는 좁은 예외 정책을 둔다.
--   - 앱이 트랜잭션 로컬로 app.scope = 'SYSTEM' 을 설정한 경우에만
--   - sys_comp 의 SELECT 만 (INSERT/UPDATE/DELETE 는 tenant_isolation 만 적용)
--   - 다른 회사별 테이블에는 이 정책이 없다 → SYSTEM 범위에서도 업무 데이터는 0건
-- 이 정책은 TenantCatalogInspector 의 허용 목록(sys_comp.system_directory_read)에 등록되어 있다.
-- =====================================================================

CREATE FUNCTION hb_is_system_scope() RETURNS boolean
    LANGUAGE sql STABLE PARALLEL SAFE
AS $$
    SELECT coalesce(current_setting('app.scope', true), '') = 'SYSTEM'
$$;

COMMENT ON FUNCTION hb_is_system_scope() IS '회사 목록 조회 전용 범위 여부 (app.scope = SYSTEM)';

CREATE POLICY system_directory_read ON sys_comp
    FOR SELECT
    USING (hb_is_system_scope());
