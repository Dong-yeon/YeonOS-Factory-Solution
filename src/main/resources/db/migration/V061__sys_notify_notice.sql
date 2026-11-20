-- =====================================================================
-- 시스템관리 2b (docs/36 11장) — 알림 구독·사내 알림함·공지.
-- 임시 번호(병합 때 재번호, docs/29 2.2)
--
-- 1. sys_prog.self_service_yn: 로그인한 모든 사용자가 그 화면의 액션을 갖는 개인 화면(행 없이, Q13)
-- 2. sys_notify_sub (사용자 × 이벤트 구독) — 행 없음 = 이벤트 기본값
-- 3. sys_notify (알림함) — 추가만 + 읽음 표시만, 같은 (사용자, 이벤트, 중복 키) 한 번
-- 4. sys_notice (공지·자료) + sys_notice_read (읽음)
-- 5. 화면 2개 (sys-inbox self-service · sys-notice)
-- 이벤트 정의는 코드(NotifyEvent) — 전역 표 없음
-- =====================================================================

-- ---------------------------------------------------------------------
-- 1. 개인 화면 표시
-- ---------------------------------------------------------------------
ALTER TABLE sys_prog ADD COLUMN self_service_yn char(1) NOT NULL DEFAULT 'N';
ALTER TABLE sys_prog ADD CONSTRAINT ck_sys_prog_self_service CHECK (self_service_yn IN ('Y', 'N'));
COMMENT ON COLUMN sys_prog.self_service_yn IS
    'Y = 로그인한 모든 사용자가 이 화면의 모든 액션을 가진다(권한 행 없음). 자기 데이터만 다루는 화면에만 (docs/36 Q13)';

-- ---------------------------------------------------------------------
-- 2. 구독
-- ---------------------------------------------------------------------
CREATE TABLE sys_notify_sub (
    sub_id      bigint       GENERATED ALWAYS AS IDENTITY,
    comp_cd     varchar(20)  NOT NULL,
    user_id     bigint       NOT NULL,
    event_cd    varchar(30)  NOT NULL,
    inbox_yn    char(1)      NOT NULL,
    row_ver     integer      NOT NULL DEFAULT 0,
    created_at  timestamptz  NOT NULL DEFAULT now(),
    created_by  varchar(50)  NOT NULL,
    updated_at  timestamptz  NOT NULL DEFAULT now(),
    updated_by  varchar(50)  NOT NULL,
    CONSTRAINT pk_sys_notify_sub PRIMARY KEY (sub_id),
    CONSTRAINT fk_sys_notify_sub_comp FOREIGN KEY (comp_cd) REFERENCES sys_comp (comp_cd),
    CONSTRAINT fk_sys_notify_sub_user FOREIGN KEY (user_id) REFERENCES sys_user (user_id),
    CONSTRAINT ck_sys_notify_sub_event CHECK (event_cd ~ '^[A-Z][A-Z0-9_]{1,29}$'),
    CONSTRAINT ck_sys_notify_sub_inbox CHECK (inbox_yn IN ('Y', 'N'))
);
CREATE UNIQUE INDEX uq_sys_notify_sub ON sys_notify_sub (comp_cd, user_id, event_cd);
COMMENT ON TABLE sys_notify_sub IS '알림 구독 (사용자 × 이벤트). 행 없음 = 이벤트 기본값 (docs/36 11.2)';

ALTER TABLE sys_notify_sub ENABLE ROW LEVEL SECURITY;
ALTER TABLE sys_notify_sub FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON sys_notify_sub
    USING (comp_cd = hb_current_comp()) WITH CHECK (comp_cd = hb_current_comp());
CREATE TRIGGER tg_sys_notify_sub_comp BEFORE INSERT OR UPDATE ON sys_notify_sub
    FOR EACH ROW EXECUTE FUNCTION hb_check_user_comp();
CREATE TRIGGER tg_audit AFTER INSERT OR UPDATE OR DELETE ON sys_notify_sub
    FOR EACH ROW EXECUTE FUNCTION hb_audit('user_id,event_cd', '', '');
REVOKE DELETE, TRUNCATE ON sys_notify_sub FROM ${app_role};
INSERT INTO sys_retention_table (table_nm, class_cd) VALUES ('sys_notify_sub', 'USER_SETTING');

-- ---------------------------------------------------------------------
-- 3. 알림함
-- ---------------------------------------------------------------------
CREATE TABLE sys_notify (
    notify_id     bigint        GENERATED ALWAYS AS IDENTITY,
    comp_cd       varchar(20)   NOT NULL,
    user_id       bigint        NOT NULL,
    event_cd      varchar(30)   NOT NULL,
    dedup_key     varchar(100)  NOT NULL,
    title_key     varchar(100)  NOT NULL,
    title_args    jsonb         NOT NULL DEFAULT '[]',
    link_prog_cd  varchar(50),
    link_params   jsonb         NOT NULL DEFAULT '{}',
    lines         jsonb         NOT NULL DEFAULT '[]',
    created_at    timestamptz   NOT NULL DEFAULT now(),
    created_by    varchar(80)   NOT NULL,
    read_at       timestamptz,
    CONSTRAINT pk_sys_notify PRIMARY KEY (notify_id),
    CONSTRAINT fk_sys_notify_comp FOREIGN KEY (comp_cd) REFERENCES sys_comp (comp_cd),
    CONSTRAINT fk_sys_notify_user FOREIGN KEY (user_id) REFERENCES sys_user (user_id),
    CONSTRAINT fk_sys_notify_prog FOREIGN KEY (link_prog_cd) REFERENCES sys_prog (prog_cd),
    CONSTRAINT ck_sys_notify_event CHECK (event_cd ~ '^[A-Z][A-Z0-9_]{1,29}$'),
    -- 문구는 저장하지 않는다 — 읽는 사람 언어로 그때 번역(키 + 인자)
    CONSTRAINT ck_sys_notify_title_key CHECK (title_key ~ '^notify\.[A-Za-z0-9_.]{1,90}$'),
    CONSTRAINT ck_sys_notify_dedup CHECK (length(dedup_key) > 0),
    CONSTRAINT ck_sys_notify_args CHECK (jsonb_typeof(title_args) = 'array'),
    CONSTRAINT ck_sys_notify_params CHECK (jsonb_typeof(link_params) = 'object'),
    CONSTRAINT ck_sys_notify_lines CHECK (jsonb_typeof(lines) = 'array'),
    CONSTRAINT ck_sys_notify_read CHECK (read_at IS NULL OR read_at >= created_at)
);
-- 재시도·이중 호출 멱등: 같은 사람에게 같은 일은 한 번
CREATE UNIQUE INDEX uq_sys_notify_dedup ON sys_notify (comp_cd, user_id, event_cd, dedup_key);
CREATE INDEX ix_sys_notify_user ON sys_notify (comp_cd, user_id, notify_id DESC);
CREATE INDEX ix_sys_notify_unread ON sys_notify (comp_cd, user_id) WHERE read_at IS NULL;
COMMENT ON TABLE sys_notify IS '사내 알림함 (추가만 + 읽음 표시만). 문구 대신 키 + 인자 (docs/36 11.2)';

ALTER TABLE sys_notify ENABLE ROW LEVEL SECURITY;
ALTER TABLE sys_notify FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON sys_notify
    USING (comp_cd = hb_current_comp()) WITH CHECK (comp_cd = hb_current_comp());
CREATE TRIGGER tg_sys_notify_comp BEFORE INSERT ON sys_notify
    FOR EACH ROW EXECUTE FUNCTION hb_check_user_comp();

-- 읽음 표시는 한 번, 비우거나 되돌리지 않는다(앱은 read_at 열만 UPDATE 권한)
CREATE FUNCTION hb_guard_notify_read() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF OLD.read_at IS NOT NULL AND NEW.read_at IS DISTINCT FROM OLD.read_at THEN
        RAISE EXCEPTION 'notify % already read', OLD.notify_id
            USING ERRCODE = '55000', CONSTRAINT = 'ck_sys_notify_read_once';
    END IF;
    RETURN NEW;
END
$$;
CREATE TRIGGER tg_sys_notify_read BEFORE UPDATE ON sys_notify
    FOR EACH ROW EXECUTE FUNCTION hb_guard_notify_read();
REVOKE UPDATE, DELETE, TRUNCATE ON sys_notify FROM ${app_role};
GRANT UPDATE (read_at) ON sys_notify TO ${app_role};
INSERT INTO sys_retention_table (table_nm, class_cd) VALUES ('sys_notify', 'MESSAGE');

-- ---------------------------------------------------------------------
-- 4. 공지·자료
-- ---------------------------------------------------------------------
CREATE TABLE sys_notice (
    notice_id   bigint        GENERATED ALWAYS AS IDENTITY,
    comp_cd     varchar(20)   NOT NULL,
    cat_cd      varchar(10)   NOT NULL DEFAULT 'NOTICE',
    title       varchar(200)  NOT NULL,
    body        text          NOT NULL DEFAULT '',
    pin_yn      char(1)       NOT NULL DEFAULT 'N',
    post_from   date          NOT NULL,
    post_to     date,
    use_yn      char(1)       NOT NULL DEFAULT 'Y',
    row_ver     integer       NOT NULL DEFAULT 0,
    created_at  timestamptz   NOT NULL DEFAULT now(),
    created_by  varchar(50)   NOT NULL,
    updated_at  timestamptz   NOT NULL DEFAULT now(),
    updated_by  varchar(50)   NOT NULL,
    CONSTRAINT pk_sys_notice PRIMARY KEY (notice_id),
    CONSTRAINT fk_sys_notice_comp FOREIGN KEY (comp_cd) REFERENCES sys_comp (comp_cd),
    CONSTRAINT ck_sys_notice_cat CHECK (cat_cd IN ('NOTICE', 'LIBRARY')),
    CONSTRAINT ck_sys_notice_title CHECK (length(btrim(title)) > 0),
    CONSTRAINT ck_sys_notice_body CHECK (length(body) <= 20000),
    CONSTRAINT ck_sys_notice_pin CHECK (pin_yn IN ('Y', 'N')),
    CONSTRAINT ck_sys_notice_period CHECK (post_to IS NULL OR post_to >= post_from),
    CONSTRAINT ck_sys_notice_use_yn CHECK (use_yn IN ('Y', 'N'))
);
CREATE INDEX ix_sys_notice_post ON sys_notice (comp_cd, post_from DESC);
COMMENT ON TABLE sys_notice IS '공지·자료실 (본문은 글자만 — Q16). 삭제 없음 — 사용 중지. 첨부는 sys_file(대상 sys-notice)';

ALTER TABLE sys_notice ENABLE ROW LEVEL SECURITY;
ALTER TABLE sys_notice FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON sys_notice
    USING (comp_cd = hb_current_comp()) WITH CHECK (comp_cd = hb_current_comp());
CREATE TRIGGER tg_sys_notice_no_delete BEFORE DELETE ON sys_notice
    FOR EACH ROW EXECUTE FUNCTION hb_forbid_delete_master();
CREATE TRIGGER tg_audit AFTER INSERT OR UPDATE OR DELETE ON sys_notice
    FOR EACH ROW EXECUTE FUNCTION hb_audit('notice_id', '', '');
REVOKE DELETE, TRUNCATE ON sys_notice FROM ${app_role};
INSERT INTO sys_retention_table (table_nm, class_cd) VALUES ('sys_notice', 'SYSTEM');

CREATE TABLE sys_notice_read (
    comp_cd    varchar(20)  NOT NULL,
    notice_id  bigint       NOT NULL,
    user_id    bigint       NOT NULL,
    read_at    timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT pk_sys_notice_read PRIMARY KEY (comp_cd, notice_id, user_id),
    CONSTRAINT fk_sys_notice_read_comp FOREIGN KEY (comp_cd) REFERENCES sys_comp (comp_cd),
    CONSTRAINT fk_sys_notice_read_notice FOREIGN KEY (notice_id) REFERENCES sys_notice (notice_id),
    CONSTRAINT fk_sys_notice_read_user FOREIGN KEY (user_id) REFERENCES sys_user (user_id)
);
CREATE INDEX ix_sys_notice_read_user ON sys_notice_read (comp_cd, user_id);
COMMENT ON TABLE sys_notice_read IS '공지 읽음 (추가만) — 안 읽은 공지 수 (docs/36 Q14)';

ALTER TABLE sys_notice_read ENABLE ROW LEVEL SECURITY;
ALTER TABLE sys_notice_read FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON sys_notice_read
    USING (comp_cd = hb_current_comp()) WITH CHECK (comp_cd = hb_current_comp());
CREATE TRIGGER tg_sys_notice_read_comp BEFORE INSERT ON sys_notice_read
    FOR EACH ROW EXECUTE FUNCTION hb_check_user_comp();

-- 공지도 같은 회사인지 (회사별 표 사이 FK 는 RLS 를 무시한다)
CREATE FUNCTION hb_check_notice_read_comp() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM sys_notice n WHERE n.notice_id = NEW.notice_id AND n.comp_cd = NEW.comp_cd) THEN
        RAISE EXCEPTION 'notice % does not belong to company %', NEW.notice_id, NEW.comp_cd
            USING ERRCODE = '23503', CONSTRAINT = 'fk_sys_notice_read_notice_comp';
    END IF;
    RETURN NEW;
END
$$;
CREATE TRIGGER tg_sys_notice_read_notice BEFORE INSERT ON sys_notice_read
    FOR EACH ROW EXECUTE FUNCTION hb_check_notice_read_comp();
REVOKE UPDATE, DELETE, TRUNCATE ON sys_notice_read FROM ${app_role};
INSERT INTO sys_retention_table (table_nm, class_cd) VALUES ('sys_notice_read', 'USER_SETTING');

-- ---------------------------------------------------------------------
-- 5. 화면
-- ---------------------------------------------------------------------
INSERT INTO sys_prog (prog_cd, prog_nm_key, view_path, module_cd, actions, sort_no, self_service_yn) VALUES
    ('sys-inbox',  'prog.sysInbox',  'platform/inbox',     'SYS', ARRAY['SEARCH','SAVE']::varchar(20)[], 938, 'Y'),
    ('sys-notice', 'prog.sysNotice', 'admin/notice-mgt',   'SYS', ARRAY['SEARCH','SAVE']::varchar(20)[], 939, 'N');

-- 공지 분류·게시 상태 (조회 조건·목록 표시)
INSERT INTO sys_code_grp (grp_cd, grp_nm_key, comp_ext_yn) VALUES
    ('NOTICE_CAT', 'code.grp.NOTICE_CAT', 'N'),
    ('NOTICE_STATE', 'code.grp.NOTICE_STATE', 'N');
INSERT INTO sys_code (grp_cd, code, code_nm_key, sort_no) VALUES
    ('NOTICE_CAT', 'NOTICE',  'code.NOTICE_CAT.NOTICE',  1),
    ('NOTICE_CAT', 'LIBRARY', 'code.NOTICE_CAT.LIBRARY', 2),
    ('NOTICE_STATE', 'POSTED',    'code.NOTICE_STATE.POSTED',    1),
    ('NOTICE_STATE', 'SCHEDULED', 'code.NOTICE_STATE.SCHEDULED', 2),
    ('NOTICE_STATE', 'ENDED',     'code.NOTICE_STATE.ENDED',     3),
    ('NOTICE_STATE', 'OFF',       'code.NOTICE_STATE.OFF',       4);
