-- =====================================================================
-- 시스템관리 3단계 (docs/36 12장) — 접속·보안 로그, 변경 이력 모아 보기, 받은 의견함, 내 결재함.
-- 임시 번호(병합 때 재번호, docs/29 2.2)
--
-- 1. 화면 4개 (sys-approval 은 개인 화면 — 로그인한 모든 사용자, 결재 권한은 결재선)
-- 2. sys_feedback: 처리한 사람·시각 + 상태 전이(NEW → SEEN → DONE, SEEN → NEW 되돌리기, DONE 은 끝)
-- 3. 코드: ACCESS_EVENT(접속 로그 이벤트 — AccessEvent 와 같은 집합, 가드 시험), LOG_SEVERITY, LOG_RESULT, AUDIT_ACTION
-- 로그·감사 표는 그대로(추가만) — 화면은 회사 범위(RLS) 조회만
-- =====================================================================

-- ---------------------------------------------------------------------
-- 1. 화면
-- ---------------------------------------------------------------------
INSERT INTO sys_prog (prog_cd, prog_nm_key, view_path, module_cd, actions, sort_no, self_service_yn) VALUES
    ('sys-access-log', 'prog.sysAccessLog', 'admin/access-log',      'SYS', ARRAY['SEARCH']::varchar(20)[],         940, 'N'),
    ('sys-audit-log',  'prog.sysAuditLog',  'admin/audit-log',       'SYS', ARRAY['SEARCH']::varchar(20)[],         941, 'N'),
    ('sys-feedback',   'prog.sysFeedback',  'admin/feedback-mgt',    'SYS', ARRAY['SEARCH','SAVE']::varchar(20)[],  942, 'N'),
    ('sys-approval',   'prog.sysApproval',  'platform/approval-box', 'SYS', ARRAY['SEARCH','SAVE']::varchar(20)[],  943, 'Y');

-- ---------------------------------------------------------------------
-- 2. 받은 의견 처리
-- ---------------------------------------------------------------------
ALTER TABLE sys_feedback ADD COLUMN handled_by varchar(50);
ALTER TABLE sys_feedback ADD COLUMN handled_at timestamptz;
ALTER TABLE sys_feedback ADD CONSTRAINT ck_sys_feedback_handled
    CHECK ((handled_at IS NULL) = (handled_by IS NULL));   -- 이전에 SQL 로 바꾼 행이 있을 수 있어 상태와는 묶지 않는다

-- 상태 전이: NEW→SEEN, NEW→DONE, SEEN→DONE, SEEN→NEW(잘못 누름 되돌리기). DONE 은 끝(다시 열려면 새 의견).
-- 내용·보낸 사람·화면은 바꾸지 않는다(보낸 사람의 말 그대로)
CREATE FUNCTION hb_guard_feedback() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.content IS DISTINCT FROM OLD.content OR NEW.user_id IS DISTINCT FROM OLD.user_id
       OR NEW.prog_cd IS DISTINCT FROM OLD.prog_cd OR NEW.comp_cd IS DISTINCT FROM OLD.comp_cd
       OR NEW.created_at IS DISTINCT FROM OLD.created_at THEN
        RAISE EXCEPTION 'feedback % content is fixed', OLD.feedback_id
            USING ERRCODE = '55000', CONSTRAINT = 'ck_sys_feedback_fixed';
    END IF;
    IF NEW.status IS DISTINCT FROM OLD.status AND NOT (
           (OLD.status = 'NEW'  AND NEW.status IN ('SEEN', 'DONE'))
        OR (OLD.status = 'SEEN' AND NEW.status IN ('NEW', 'DONE'))) THEN
        RAISE EXCEPTION 'feedback % cannot go % -> %', OLD.feedback_id, OLD.status, NEW.status
            USING ERRCODE = '55000', CONSTRAINT = 'ck_sys_feedback_transition';
    END IF;
    RETURN NEW;
END
$$;
CREATE TRIGGER tg_sys_feedback_guard BEFORE UPDATE ON sys_feedback
    FOR EACH ROW EXECUTE FUNCTION hb_guard_feedback();

-- ---------------------------------------------------------------------
-- 3. 코드 (조회 조건·목록 표시)
-- ---------------------------------------------------------------------
INSERT INTO sys_code_grp (grp_cd, grp_nm_key, comp_ext_yn) VALUES
    ('ACCESS_EVENT', 'code.grp.ACCESS_EVENT', 'N'),
    ('LOG_SEVERITY', 'code.grp.LOG_SEVERITY', 'N'),
    ('LOG_RESULT',   'code.grp.LOG_RESULT',   'N'),
    ('AUDIT_ACTION', 'code.grp.AUDIT_ACTION', 'N');
INSERT INTO sys_code (grp_cd, code, code_nm_key, sort_no) VALUES
    ('ACCESS_EVENT', 'LOGIN_OK', 'code.ACCESS_EVENT.LOGIN_OK', 10),
    ('ACCESS_EVENT', 'LOGIN_FAIL', 'code.ACCESS_EVENT.LOGIN_FAIL', 20),
    ('ACCESS_EVENT', 'LOGIN_LOCKED', 'code.ACCESS_EVENT.LOGIN_LOCKED', 30),
    ('ACCESS_EVENT', 'LOGIN_DENIED', 'code.ACCESS_EVENT.LOGIN_DENIED', 40),
    ('ACCESS_EVENT', 'LOGIN_THROTTLED', 'code.ACCESS_EVENT.LOGIN_THROTTLED', 50),
    ('ACCESS_EVENT', 'LOGOUT', 'code.ACCESS_EVENT.LOGOUT', 60),
    ('ACCESS_EVENT', 'SESSION_EXPIRED', 'code.ACCESS_EVENT.SESSION_EXPIRED', 70),
    ('ACCESS_EVENT', 'SESSION_REVOKED', 'code.ACCESS_EVENT.SESSION_REVOKED', 80),
    ('ACCESS_EVENT', 'PAGE_VIEW', 'code.ACCESS_EVENT.PAGE_VIEW', 90),
    ('ACCESS_EVENT', 'ACTION', 'code.ACCESS_EVENT.ACTION', 100),
    ('ACCESS_EVENT', 'PERM_DENIED', 'code.ACCESS_EVENT.PERM_DENIED', 110),
    ('ACCESS_EVENT', 'TENANT_FORBIDDEN', 'code.ACCESS_EVENT.TENANT_FORBIDDEN', 120),
    ('ACCESS_EVENT', 'PASSWORD_CHANGED', 'code.ACCESS_EVENT.PASSWORD_CHANGED', 130),
    ('ACCESS_EVENT', 'FILE_UPLOAD', 'code.ACCESS_EVENT.FILE_UPLOAD', 140),
    ('ACCESS_EVENT', 'FILE_DOWNLOAD', 'code.ACCESS_EVENT.FILE_DOWNLOAD', 150),
    ('ACCESS_EVENT', 'FILE_DELETE', 'code.ACCESS_EVENT.FILE_DELETE', 160),
    ('ACCESS_EVENT', 'FILE_REJECTED', 'code.ACCESS_EVENT.FILE_REJECTED', 170),
    ('ACCESS_EVENT', 'TERMINAL_ACTIVATED', 'code.ACCESS_EVENT.TERMINAL_ACTIVATED', 180),
    ('ACCESS_EVENT', 'TERMINAL_DENIED', 'code.ACCESS_EVENT.TERMINAL_DENIED', 190),
    ('ACCESS_EVENT', 'BADGE_OK', 'code.ACCESS_EVENT.BADGE_OK', 200),
    ('ACCESS_EVENT', 'BADGE_FAIL', 'code.ACCESS_EVENT.BADGE_FAIL', 210),
    ('ACCESS_EVENT', 'BADGE_THROTTLED', 'code.ACCESS_EVENT.BADGE_THROTTLED', 220),
    ('ACCESS_EVENT', 'PIN_LOCKED', 'code.ACCESS_EVENT.PIN_LOCKED', 230),
    ('ACCESS_EVENT', 'PASSWORD_RESET', 'code.ACCESS_EVENT.PASSWORD_RESET', 240),
    ('ACCESS_EVENT', 'USER_LOCKED', 'code.ACCESS_EVENT.USER_LOCKED', 250),
    ('ACCESS_EVENT', 'USER_UNLOCKED', 'code.ACCESS_EVENT.USER_UNLOCKED', 260),
    ('ACCESS_EVENT', 'GRANT_DENIED', 'code.ACCESS_EVENT.GRANT_DENIED', 270),
    ('LOG_SEVERITY', 'INFO',  'code.LOG_SEVERITY.INFO',  1),
    ('LOG_SEVERITY', 'WARN',  'code.LOG_SEVERITY.WARN',  2),
    ('LOG_SEVERITY', 'ALERT', 'code.LOG_SEVERITY.ALERT', 3),
    ('LOG_RESULT', 'OK',     'code.LOG_RESULT.OK',     1),
    ('LOG_RESULT', 'DENIED', 'code.LOG_RESULT.DENIED', 2),
    ('LOG_RESULT', 'FAIL',   'code.LOG_RESULT.FAIL',   3),
    ('AUDIT_ACTION', 'INSERT', 'code.AUDIT_ACTION.INSERT', 1),
    ('AUDIT_ACTION', 'UPDATE', 'code.AUDIT_ACTION.UPDATE', 2),
    ('AUDIT_ACTION', 'DELETE', 'code.AUDIT_ACTION.DELETE', 3);
