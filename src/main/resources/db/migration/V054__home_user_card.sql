-- 홈화면 2단계 (docs/37 14장) — 사용자별 카드 숨김·순서. 임시 번호(docs/29 2.2): 병합 때 상위 세션이 다음 빈 번호로 이름만 바꾼다.
-- 카드 코드는 코드(HomeCardProvider 빈)에만 있으므로 FK 가 없다 — 없어진 카드의 행은 읽을 때 무시하고, 다음 저장 때 사라진다.
-- 행이 없는 카드(새로 생긴 카드·처음 쓰는 사용자)는 보이고, 설정한 카드들 뒤에 기본 순서로 온다.

CREATE TABLE sys_home_user_card (
    pref_id     bigint       GENERATED ALWAYS AS IDENTITY,
    comp_cd     varchar(20)  NOT NULL,
    user_id     bigint       NOT NULL,
    card_cd     varchar(40)  NOT NULL,
    sort_no     integer      NOT NULL,
    hidden_yn   char(1)      NOT NULL DEFAULT 'N',
    created_at  timestamptz  NOT NULL DEFAULT now(),
    created_by  varchar(50)  NOT NULL,
    updated_at  timestamptz  NOT NULL DEFAULT now(),
    updated_by  varchar(50)  NOT NULL,
    CONSTRAINT pk_sys_home_user_card PRIMARY KEY (pref_id),
    CONSTRAINT uq_sys_home_user_card UNIQUE (comp_cd, user_id, card_cd),
    CONSTRAINT fk_sys_home_user_card_comp FOREIGN KEY (comp_cd) REFERENCES sys_comp (comp_cd),
    CONSTRAINT fk_sys_home_user_card_user FOREIGN KEY (user_id) REFERENCES sys_user (user_id),
    CONSTRAINT ck_sys_home_user_card_cd CHECK (card_cd ~ '^[a-z][a-z0-9-]{1,39}$'),
    CONSTRAINT ck_sys_home_user_card_sort CHECK (sort_no BETWEEN 1 AND 999),
    CONSTRAINT ck_sys_home_user_card_hidden CHECK (hidden_yn IN ('Y', 'N'))
);
COMMENT ON TABLE sys_home_user_card IS '홈 카드 사용자 설정(숨김·순서, docs/37 14장). 업무 데이터 아님 — 카드 계산은 코드';

-- 회사별 테이블 간 FK 는 RLS 를 무시한다 → 같은 회사 사용자인지(V009 hb_check_user_comp 재사용)
CREATE TRIGGER tg_sys_home_user_card_comp BEFORE INSERT OR UPDATE ON sys_home_user_card
    FOR EACH ROW EXECUTE FUNCTION hb_check_user_comp();
CREATE TRIGGER tg_audit AFTER INSERT OR UPDATE OR DELETE ON sys_home_user_card
    FOR EACH ROW EXECUTE FUNCTION hb_audit('pref_id', '', '');

ALTER TABLE sys_home_user_card ENABLE ROW LEVEL SECURITY;
ALTER TABLE sys_home_user_card FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON sys_home_user_card USING (comp_cd = hb_current_comp()) WITH CHECK (comp_cd = hb_current_comp());

INSERT INTO sys_retention_table (table_nm, class_cd) VALUES ('sys_home_user_card', 'USER_SETTING');
