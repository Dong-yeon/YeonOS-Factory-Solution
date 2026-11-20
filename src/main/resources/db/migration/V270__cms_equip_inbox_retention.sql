-- =====================================================================
-- 설비 이벤트 수신 멱등 기록 — 전용 표 + 보존 기간 (docs/70 4.5 W2·4.6, R2 추천 7일) — 임시 V9960
-- - 지금까지 /ext/v1/equip-events 의 eventId 중복 판정은 sys_inbox(소비자 EQUIP_EVENT) — 지우지 않는 표라
--   1천 건/초면 하루 약 17GB 가 영구히 쌓인다(docs/70 4.3). 설비 이벤트 몫만 전용 표로 옮기고 보존 기간 뒤 정리한다
-- - 보존 클래스 EQUIP_INBOX: 기준 KEEP(공통 정리 hb_purge_logs 대상 아님), 기본 7일·최소 3일, 회사 보존 기간(sys_retention_policy)·
--   법적 보류(sys_legal_hold) 그대로. 정리 = 앱 정기 작업(CmsJobs.equipInboxPurge, TenantRunner) — 이 표는 증거가 아니라 중복 판정 키
-- - 앱 DELETE 는 이 표에만. DELETE 트리거가 클래스 최소 기간보다 새 행·법적 보류 기간 행을 거부한다(앱 코드 실수의 바닥)
-- - 한계: 보존 기간보다 오래된 eventId 를 장치가 다시 보내면 다시 처리된다 — 재전송 창 ≤ 보존 기간을 장치 계약에(R2)
-- - seq 만으로 중복을 가리지 않는다: seq ≤ 마지막 은 재전송과 순서 뒤바뀜을 구분하지 못한다(docs/70 W2)
-- 임시 번호(docs/29 2.2) — 병합 때 이름만 바뀐다.
-- =====================================================================

-- ---------------------------------------------------------------------
-- 1. 표
-- ---------------------------------------------------------------------
CREATE TABLE cms_equip_inbox (
    comp_cd      varchar(20)  NOT NULL,
    event_id     varchar(100) NOT NULL,
    received_at  timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT pk_cms_equip_inbox PRIMARY KEY (comp_cd, event_id),
    CONSTRAINT fk_cms_equip_inbox_comp FOREIGN KEY (comp_cd) REFERENCES sys_comp (comp_cd),
    CONSTRAINT ck_cms_equip_inbox_event CHECK (event_id ~ '^[A-Za-z0-9_.:-]{8,100}$')
);
COMMENT ON TABLE cms_equip_inbox IS '설비 이벤트 수신 멱등 기록 (docs/70 W2) — 처리 트랜잭션 안에서 넣는다, 보존 클래스 EQUIP_INBOX 기간 뒤 정리(CmsJobs)';
COMMENT ON COLUMN cms_equip_inbox.received_at IS '받은(처리한) 시각 — 보존 기간 판정 기준';
-- 받은 순서로 쌓이므로 BRIN(작고 쓰기 비용이 거의 없다) — 정리 조회 "received_at < 기준" 용
CREATE INDEX ix_cms_equip_inbox_received ON cms_equip_inbox USING brin (received_at);

ALTER TABLE cms_equip_inbox ENABLE ROW LEVEL SECURITY;
ALTER TABLE cms_equip_inbox FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON cms_equip_inbox USING (comp_cd = hb_current_comp()) WITH CHECK (comp_cd = hb_current_comp());
REVOKE UPDATE, TRUNCATE ON cms_equip_inbox FROM ${app_role};

-- ---------------------------------------------------------------------
-- 2. 보존 클래스·매핑
-- ---------------------------------------------------------------------
INSERT INTO sys_retention_class (class_cd, basis, default_days, min_days, purge_allowed_yn, desc_key) VALUES
    ('EQUIP_INBOX', 'KEEP', 7, 3, 'Y', 'retention.EQUIP_INBOX');
INSERT INTO sys_retention_table (table_nm, class_cd) VALUES ('cms_equip_inbox', 'EQUIP_INBOX');

-- ---------------------------------------------------------------------
-- 3. DELETE 바닥 — 클래스 최소 기간 안의 행, 법적 보류(해제 안 됨) 기간의 행은 지울 수 없다
-- ---------------------------------------------------------------------
CREATE FUNCTION hb_guard_equip_inbox_delete() RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    floor_days integer;
BEGIN
    SELECT k.min_days INTO floor_days FROM sys_retention_class k WHERE k.class_cd = 'EQUIP_INBOX';
    IF OLD.received_at >= now() - make_interval(days => coalesce(floor_days, 3)) THEN
        RAISE EXCEPTION 'equip inbox row is within the minimum retention'
            USING ERRCODE = '55000', CONSTRAINT = 'ck_cms_equip_inbox_retention';
    END IF;
    IF EXISTS (SELECT 1 FROM sys_legal_hold h
                WHERE h.comp_cd = OLD.comp_cd AND h.class_cd = 'EQUIP_INBOX' AND h.released_at IS NULL
                  AND OLD.received_at >= h.from_at AND OLD.received_at < h.to_at) THEN
        RAISE EXCEPTION 'equip inbox row is under legal hold'
            USING ERRCODE = '55000', CONSTRAINT = 'ck_cms_equip_inbox_hold';
    END IF;
    RETURN OLD;
END
$$;
CREATE TRIGGER tg_cms_equip_inbox_delete BEFORE DELETE ON cms_equip_inbox FOR EACH ROW EXECUTE FUNCTION hb_guard_equip_inbox_delete();

-- ---------------------------------------------------------------------
-- 4. 기존 기록 옮기기 — sys_inbox(EQUIP_EVENT) → cms_equip_inbox (회사마다 범위를 열고, 회사 목록은 먼저 배열로)
--    옮긴 행은 받은 시각 그대로 — 7일 지난 것은 첫 정리에서 지워진다
-- ---------------------------------------------------------------------
DO $$
DECLARE
    comps varchar[];
    c_cd  varchar;
BEGIN
    PERFORM set_config('app.comp_cd', '', true);
    PERFORM set_config('app.scope', 'SYSTEM', true);
    comps := ARRAY(SELECT c.comp_cd FROM sys_comp c ORDER BY c.comp_cd);
    PERFORM set_config('app.scope', '', true);
    FOREACH c_cd IN ARRAY comps LOOP
        PERFORM set_config('app.comp_cd', c_cd, true);
        INSERT INTO cms_equip_inbox (comp_cd, event_id, received_at)
        SELECT i.comp_cd, i.message_id, i.received_at FROM sys_inbox i
         WHERE i.comp_cd = c_cd AND i.consumer_cd = 'EQUIP_EVENT'
        ON CONFLICT DO NOTHING;
        DELETE FROM sys_inbox i WHERE i.comp_cd = c_cd AND i.consumer_cd = 'EQUIP_EVENT';
    END LOOP;
    PERFORM set_config('app.comp_cd', '', true);
END
$$;
