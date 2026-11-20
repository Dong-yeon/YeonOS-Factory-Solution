-- =====================================================================
-- Web Push 구독 (docs/45 9장 N2 — M-3 관리자 모바일) — V210·임시 V9715
-- - 사용자 × 기기(브라우저) 하나 = 행 하나. 엔드포인트는 푸시 서비스 주소(허용 호스트만 — 서비스가 검사), 키는 받는 기기의 공개 키·auth 비밀
-- - 만료 90일(다시 구독하면 늘어남) — 만료·사라진(404·410) 구독은 매일 정리
-- - 앱 계정은 자기 구독을 지울 수 있다(해지·정리) — 원장 아님. 감사 행에서 엔드포인트·키는 가린다
-- 임시 번호(docs/29 2.2) — 병합 때 이름만 바뀐다.
-- =====================================================================

CREATE TABLE sys_push_sub (
    push_sub_id   bigint         GENERATED ALWAYS AS IDENTITY,
    comp_cd       varchar(20)    NOT NULL,
    user_id       bigint         NOT NULL,
    endpoint      varchar(1000)  NOT NULL,
    p256dh        varchar(100)   NOT NULL,
    auth_secret   varchar(40)    NOT NULL,
    device_label  varchar(100),
    expires_at    timestamptz    NOT NULL,
    last_sent_at  timestamptz,
    created_at    timestamptz    NOT NULL DEFAULT now(),
    created_by    varchar(50)    NOT NULL,
    updated_at    timestamptz    NOT NULL DEFAULT now(),
    updated_by    varchar(50)    NOT NULL,
    CONSTRAINT pk_sys_push_sub PRIMARY KEY (push_sub_id),
    CONSTRAINT fk_sys_push_sub_comp FOREIGN KEY (comp_cd) REFERENCES sys_comp (comp_cd),
    CONSTRAINT fk_sys_push_sub_user FOREIGN KEY (user_id) REFERENCES sys_user (user_id),
    CONSTRAINT ck_sys_push_sub_endpoint CHECK (endpoint LIKE 'https://%'),
    CONSTRAINT ck_sys_push_sub_keys CHECK (p256dh ~ '^[A-Za-z0-9_-]{80,100}$' AND auth_secret ~ '^[A-Za-z0-9_-]{16,40}$')
);
-- 같은 기기(엔드포인트)는 회사 안에서 하나 — 다시 구독하면 그 행을 갱신
CREATE UNIQUE INDEX uq_sys_push_sub_endpoint ON sys_push_sub (comp_cd, endpoint);
CREATE INDEX ix_sys_push_sub_user ON sys_push_sub (comp_cd, user_id);
COMMENT ON TABLE sys_push_sub IS 'Web Push 구독 (사용자 × 기기, docs/45 9장). 등록한 기기에 새 알림함 알림을 아웃박스 WEB_PUSH 로 보낸다';
COMMENT ON COLUMN sys_push_sub.endpoint IS '푸시 서비스 주소 — 허용 호스트(happyboot.push.allowed-hosts)만, 구독·보낼 때 두 번 검사';

ALTER TABLE sys_push_sub ENABLE ROW LEVEL SECURITY;
ALTER TABLE sys_push_sub FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON sys_push_sub
    USING (comp_cd = hb_current_comp()) WITH CHECK (comp_cd = hb_current_comp());
CREATE TRIGGER tg_sys_push_sub_comp BEFORE INSERT OR UPDATE ON sys_push_sub
    FOR EACH ROW EXECUTE FUNCTION hb_check_user_comp();
CREATE TRIGGER tg_audit AFTER INSERT OR UPDATE OR DELETE ON sys_push_sub
    FOR EACH ROW EXECUTE FUNCTION hb_audit('push_sub_id', 'last_sent_at', 'endpoint,p256dh,auth_secret');
REVOKE TRUNCATE ON sys_push_sub FROM ${app_role};
INSERT INTO sys_retention_table (table_nm, class_cd) VALUES ('sys_push_sub', 'USER_SETTING');
