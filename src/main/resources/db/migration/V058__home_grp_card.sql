-- 홈화면 3단계 (docs/37 15장) — 역할별 기본 카드 세트(권한그룹 × 카드 × 순서·숨김) + 관리 화면 home-card-mgt.
-- 임시 번호(docs/29 2.2): 병합 때 상위 세션이 다음 빈 번호로 이름만 바꾼다.
-- 겹쳐 쓰기: 사용자 설정(sys_home_user_card) > 그룹 기본(이 표) > 코드 기본 순서. 카드가 보이는지는 여전히 화면 권한(R1)이 정한다 —
-- 이 표는 순서·숨김만 정하고 권한을 주지 않는다. 카드 코드는 코드(HomeCardProvider 빈)에만 있으므로 FK 가 없다.

CREATE TABLE sys_home_grp_card (
    grp_card_id  bigint       GENERATED ALWAYS AS IDENTITY,
    comp_cd      varchar(20)  NOT NULL,
    auth_grp_cd  varchar(30)  NOT NULL,
    card_cd      varchar(40)  NOT NULL,
    sort_no      integer      NOT NULL,
    hidden_yn    char(1)      NOT NULL DEFAULT 'N',
    created_at   timestamptz  NOT NULL DEFAULT now(),
    created_by   varchar(50)  NOT NULL,
    updated_at   timestamptz  NOT NULL DEFAULT now(),
    updated_by   varchar(50)  NOT NULL,
    CONSTRAINT pk_sys_home_grp_card PRIMARY KEY (grp_card_id),
    CONSTRAINT uq_sys_home_grp_card UNIQUE (comp_cd, auth_grp_cd, card_cd),
    -- 회사 코드를 포함한 복합 FK — 같은 회사 권한그룹만 가리킨다(FK 검사는 RLS 를 무시하므로 comp_cd 를 키에 넣는다)
    CONSTRAINT fk_sys_home_grp_card_grp FOREIGN KEY (comp_cd, auth_grp_cd) REFERENCES sys_auth_group (comp_cd, auth_grp_cd),
    CONSTRAINT ck_sys_home_grp_card_cd CHECK (card_cd ~ '^[a-z][a-z0-9-]{1,39}$'),
    CONSTRAINT ck_sys_home_grp_card_sort CHECK (sort_no BETWEEN 1 AND 999),
    CONSTRAINT ck_sys_home_grp_card_hidden CHECK (hidden_yn IN ('Y', 'N'))
);
COMMENT ON TABLE sys_home_grp_card IS '홈 카드 역할별 기본 세트(순서·숨김, docs/37 15장). 사용자 설정이 없을 때 쓴다. 권한을 주지 않는다';

CREATE TRIGGER tg_audit AFTER INSERT OR UPDATE OR DELETE ON sys_home_grp_card
    FOR EACH ROW EXECUTE FUNCTION hb_audit('grp_card_id', '', '');

ALTER TABLE sys_home_grp_card ENABLE ROW LEVEL SECURITY;
ALTER TABLE sys_home_grp_card FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON sys_home_grp_card USING (comp_cd = hb_current_comp()) WITH CHECK (comp_cd = hb_current_comp());

-- 권한그룹 설정과 같은 보존 클래스(sys_auth_group = MASTER)
INSERT INTO sys_retention_table (table_nm, class_cd) VALUES ('sys_home_grp_card', 'MASTER');

INSERT INTO sys_prog (prog_cd, prog_nm_key, view_path, module_cd, actions, sort_no) VALUES
    ('home-card-mgt', 'prog.homeCardMgt', 'home/home-card-mgt', 'SYS', ARRAY['SEARCH', 'SAVE']::varchar(20)[], 951);
