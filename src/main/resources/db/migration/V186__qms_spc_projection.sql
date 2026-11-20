-- =====================================================================
-- QMS SPC 목록 투영·요약 표 (docs/29_P3 22장 — QA 깊은 offset H3·H5, 상위 승인 Q-v·Q-w = A, Q-x = 한 PR) — 임시 번호(docs/29 2.2)
--
-- - 원장(qms_spc_event·qms_spc_event_act)은 **추가만 그대로** — 이 두 표는 원장 규약의 예외가 아니라 원장에서 파생된 읽기용 사본이다
-- - qms_spc_event_state: 이벤트 1:1 상태(OPEN·ACKED·CLOSED·VOID)·마지막 처리. 이벤트 INSERT → OPEN 행, 처리 INSERT → 상태 갱신
--   (종결·무효 뒤 처리 추가 금지라 마지막 처리 유형이 곧 상태 — 19장 SV-4)
-- - qms_spc_unit: 관리도 단위(품목·특성·검사 유형·공정) 1행 — 판정 확정 계량형 부분군 수·마지막 검사 시각.
--   qms_insp 판정 상태가 JUDGED 로 들어오거나 나갈 때(판정·무효) 그 검사의 계량형 특성 단위만 다시 센다(단위 advisory 잠금 → 동시 판정이 서로를 본다)
-- - 두 표 모두 트리거만 쓴다: 앱 계정의 직접 INSERT·UPDATE·DELETE 는 가드 트리거가 거부(트리거 깊이 1).
--   REVOKE 로 막지 않는 이유 — 트리거 함수는 호출자(앱) 권한으로 돌고, 권한을 넘기는 SECURITY DEFINER 는 RLS 우회 금지 규칙으로 쓰지 않는다
--   (inv_stock 선례 V048 과 같은 방식). DELETE·TRUNCATE 는 REVOKE
-- - 다시 계산: hb_qms_spc_event_state_calc() · hb_qms_spc_unit_calc() — 원장에서 같은 값을 만드는 읽기 함수(SECURITY INVOKER, RLS 그대로).
--   "다시 계산 = 지금 표" 를 시험으로 고정하고, 어긋나면 고치는 길(소유자가 가드를 잠시 끄고 이 함수 결과로 바꿔 넣는다 — 22장)
-- =====================================================================

-- ---------------------------------------------------------------------
-- 1. 위반 상태 투영
-- ---------------------------------------------------------------------
CREATE TABLE qms_spc_event_state (
    event_id      bigint         NOT NULL,
    comp_cd       varchar(20)    NOT NULL,
    status        varchar(6)     NOT NULL,
    item_id       bigint         NOT NULL,
    char_id       bigint         NOT NULL,
    insp_type     varchar(10)    NOT NULL,
    proc_id       bigint,
    rule_cd       varchar(3)     NOT NULL,
    occurred_at   timestamptz    NOT NULL,
    last_by       varchar(50),
    last_at       timestamptz,
    CONSTRAINT pk_qms_spc_event_state PRIMARY KEY (event_id),
    CONSTRAINT fk_qms_spc_event_state_event FOREIGN KEY (event_id) REFERENCES qms_spc_event (event_id),
    CONSTRAINT fk_qms_spc_event_state_comp FOREIGN KEY (comp_cd) REFERENCES sys_comp (comp_cd),
    CONSTRAINT ck_qms_spc_event_state_status CHECK (status IN ('OPEN', 'ACKED', 'CLOSED', 'VOID'))
);
-- 목록 기본(상태 묶음 + 발생 최근) · 건수 · 홈 카드
CREATE INDEX ix_qms_spc_event_state_list ON qms_spc_event_state (comp_cd, status, occurred_at DESC, event_id DESC);
-- 착수 게이트 Q9-3(품목·공정의 열린 위반)
CREATE INDEX ix_qms_spc_event_state_open ON qms_spc_event_state (comp_cd, item_id, proc_id) WHERE status IN ('OPEN', 'ACKED');
COMMENT ON TABLE qms_spc_event_state IS 'SPC 위반 상태 투영 — 원장(qms_spc_event·_act)에서 트리거로만 파생, 앱 직접 쓰기 금지 (docs/29_P3 22장 H3)';

-- 원장에서 다시 계산(읽기) — 19장 상태 파생 규칙 그대로
CREATE FUNCTION hb_qms_spc_event_state_calc()
    RETURNS TABLE (event_id bigint, comp_cd varchar, status varchar, item_id bigint, char_id bigint, insp_type varchar, proc_id bigint,
                   rule_cd varchar, occurred_at timestamptz, last_by varchar, last_at timestamptz)
    LANGUAGE sql STABLE
AS $$
    -- 처리 기록을 이벤트별로 한 번 묶어 붙인다(이벤트마다 부질의면 처리 기록을 행마다 훑는다 — 인덱스는 comp_cd 로 시작)
    SELECT e.event_id, e.comp_cd,
           (CASE WHEN a.has_void THEN 'VOID' WHEN a.has_close THEN 'CLOSED' WHEN a.event_id IS NOT NULL THEN 'ACKED' ELSE 'OPEN' END)::varchar,
           e.item_id, e.char_id, e.insp_type, e.proc_id, e.rule_cd, e.occurred_at, la.acted_by, la.acted_at
      FROM qms_spc_event e
      LEFT JOIN (SELECT x.event_id, bool_or(x.act_type = 'VOID') AS has_void, bool_or(x.act_type = 'CLOSE') AS has_close, max(x.act_id) AS last_id
                   FROM qms_spc_event_act x GROUP BY x.event_id) a ON a.event_id = e.event_id
      LEFT JOIN qms_spc_event_act la ON la.act_id = a.last_id
$$;

CREATE FUNCTION hb_qms_spc_state_on_event() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    INSERT INTO qms_spc_event_state (event_id, comp_cd, status, item_id, char_id, insp_type, proc_id, rule_cd, occurred_at)
    VALUES (NEW.event_id, NEW.comp_cd, 'OPEN', NEW.item_id, NEW.char_id, NEW.insp_type, NEW.proc_id, NEW.rule_cd, NEW.occurred_at);
    RETURN NULL;
END
$$;

-- 처리 추가 가드(BEFORE, V157)가 같은 이벤트를 advisory 로 줄 세우고 종결·무효 뒤 추가를 막은 뒤라 마지막 처리 = 상태
CREATE FUNCTION hb_qms_spc_state_on_act() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    UPDATE qms_spc_event_state
       SET status = CASE NEW.act_type WHEN 'VOID' THEN 'VOID' WHEN 'CLOSE' THEN 'CLOSED' ELSE 'ACKED' END,
           last_by = NEW.acted_by, last_at = NEW.acted_at
     WHERE event_id = NEW.event_id;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'SPC event state % missing (projection out of sync)', NEW.event_id
            USING ERRCODE = '55000', CONSTRAINT = 'ck_qms_spc_event_state_sync';
    END IF;
    RETURN NULL;
END
$$;

-- ---------------------------------------------------------------------
-- 2. 관리도 단위 요약
-- ---------------------------------------------------------------------
CREATE TABLE qms_spc_unit (
    unit_id       bigint         GENERATED ALWAYS AS IDENTITY,
    comp_cd       varchar(20)    NOT NULL,
    item_id       bigint         NOT NULL,
    char_id       bigint         NOT NULL,
    insp_type     varchar(10)    NOT NULL,
    proc_id       bigint,
    subgroup_cnt  integer        NOT NULL,
    last_at       timestamptz    NOT NULL,
    CONSTRAINT pk_qms_spc_unit PRIMARY KEY (unit_id),
    CONSTRAINT fk_qms_spc_unit_comp FOREIGN KEY (comp_cd) REFERENCES sys_comp (comp_cd),
    CONSTRAINT fk_qms_spc_unit_item FOREIGN KEY (item_id) REFERENCES mst_item (item_id),
    CONSTRAINT fk_qms_spc_unit_char FOREIGN KEY (char_id) REFERENCES qms_char (char_id),
    CONSTRAINT ck_qms_spc_unit_cnt CHECK (subgroup_cnt > 0)
);
CREATE UNIQUE INDEX uq_qms_spc_unit ON qms_spc_unit (comp_cd, item_id, char_id, insp_type, proc_id) NULLS NOT DISTINCT;
CREATE INDEX ix_qms_spc_unit_last ON qms_spc_unit (comp_cd, last_at DESC);
COMMENT ON TABLE qms_spc_unit IS 'SPC 관리도 단위 요약 — 판정 확정 계량형 부분군 수·마지막 검사 시각, qms_insp 판정 상태 트리거로만 갱신 (docs/29_P3 22장 H5)';

-- 원장에서 다시 계산(읽기) — 지금 단위 목록(SpcMapper.unitsQuery)과 같은 묶음
CREATE FUNCTION hb_qms_spc_unit_calc()
    RETURNS TABLE (comp_cd varchar, item_id bigint, char_id bigint, insp_type varchar, proc_id bigint, subgroup_cnt integer, last_at timestamptz)
    LANGUAGE sql STABLE
AS $$
    SELECT h.comp_cd, h.item_id, x.char_id, h.insp_type, h.proc_id, count(*)::integer, max(h.occurred_at)
      FROM qms_insp h
      JOIN qms_insp_line x ON x.insp_id = h.insp_id AND x.data_type = 'VARIABLE'
     WHERE h.status = 'JUDGED'
     GROUP BY h.comp_cd, h.item_id, x.char_id, h.insp_type, h.proc_id
$$;

-- 판정 상태가 JUDGED 로 들어오거나 나가면 그 검사의 계량형 특성 단위를 다시 센다.
-- 단위 키 = SpcEventService 의 단위 advisory 잠금과 같은 글자(품목:특성:유형:공정 0) — 판정 훅이 같은 단위를 다시 잠가도 같은 트랜잭션이라 그대로.
-- 여러 단위는 키 정렬 순서로 잠근다(교착 방지)
CREATE FUNCTION hb_qms_spc_unit_on_insp() RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    u record;
    v_cnt integer;
    v_last timestamptz;
BEGIN
    IF TG_OP = 'UPDATE' AND (OLD.status = 'JUDGED') = (NEW.status = 'JUDGED') THEN
        RETURN NULL;
    END IF;
    IF TG_OP = 'INSERT' AND NEW.status <> 'JUDGED' THEN
        RETURN NULL;
    END IF;
    FOR u IN SELECT DISTINCT x.char_id, NEW.item_id || ':' || x.char_id || ':' || NEW.insp_type || ':' || coalesce(NEW.proc_id, 0) AS unit_key
               FROM qms_insp_line x WHERE x.insp_id = NEW.insp_id AND x.data_type = 'VARIABLE'
              ORDER BY 2 LOOP
        PERFORM pg_advisory_xact_lock(hashtext(u.unit_key));
        SELECT count(*), max(h.occurred_at) INTO v_cnt, v_last
          FROM qms_insp h
          JOIN qms_insp_line x ON x.insp_id = h.insp_id AND x.char_id = u.char_id AND x.data_type = 'VARIABLE'
         WHERE h.comp_cd = NEW.comp_cd AND h.item_id = NEW.item_id AND h.insp_type = NEW.insp_type
           AND h.proc_id IS NOT DISTINCT FROM NEW.proc_id AND h.status = 'JUDGED';
        IF v_cnt = 0 THEN
            DELETE FROM qms_spc_unit s
             WHERE s.comp_cd = NEW.comp_cd AND s.item_id = NEW.item_id AND s.char_id = u.char_id AND s.insp_type = NEW.insp_type
               AND s.proc_id IS NOT DISTINCT FROM NEW.proc_id;
        ELSE
            INSERT INTO qms_spc_unit (comp_cd, item_id, char_id, insp_type, proc_id, subgroup_cnt, last_at)
            VALUES (NEW.comp_cd, NEW.item_id, u.char_id, NEW.insp_type, NEW.proc_id, v_cnt, v_last)
            ON CONFLICT (comp_cd, item_id, char_id, insp_type, proc_id) DO UPDATE
               SET subgroup_cnt = excluded.subgroup_cnt, last_at = excluded.last_at;
        END IF;
    END LOOP;
    RETURN NULL;
END
$$;

-- ---------------------------------------------------------------------
-- 3. RLS · 권한
-- ---------------------------------------------------------------------
ALTER TABLE qms_spc_event_state ENABLE ROW LEVEL SECURITY;
ALTER TABLE qms_spc_event_state FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON qms_spc_event_state USING (comp_cd = hb_current_comp()) WITH CHECK (comp_cd = hb_current_comp());
ALTER TABLE qms_spc_unit ENABLE ROW LEVEL SECURITY;
ALTER TABLE qms_spc_unit FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON qms_spc_unit USING (comp_cd = hb_current_comp()) WITH CHECK (comp_cd = hb_current_comp());

REVOKE TRUNCATE ON qms_spc_event_state, qms_spc_unit FROM ${app_role};
REVOKE DELETE ON qms_spc_event_state FROM ${app_role};

-- 보존 클래스는 원본(원장·검사)과 같게. 감사 트리거 없음 — 원장 투영이라 변경 이력은 원장·검사 표에 있다(inv_stock 선례, RetentionTest 예외 목록)
INSERT INTO sys_retention_table (table_nm, class_cd) VALUES ('qms_spc_event_state', 'QUALITY_RECORD'), ('qms_spc_unit', 'QUALITY_RECORD');

-- ---------------------------------------------------------------------
-- 4. 기존 행 채우기 — 마이그레이션 계정이 슈퍼유저가 아니면 FORCE RLS 를 받으므로 회사마다 범위를 연다(V109 선례).
--    가드 트리거는 채운 뒤에 만든다
-- ---------------------------------------------------------------------
DO $$
DECLARE
    v_comps varchar[];
    v_comp  varchar;
BEGIN
    PERFORM set_config('app.scope', 'SYSTEM', true);
    SELECT coalesce(array_agg(comp_cd), ARRAY[]::varchar[]) INTO v_comps FROM sys_comp;
    PERFORM set_config('app.scope', '', true);
    FOREACH v_comp IN ARRAY v_comps LOOP
        PERFORM set_config('app.comp_cd', v_comp, true);
        INSERT INTO qms_spc_event_state (event_id, comp_cd, status, item_id, char_id, insp_type, proc_id, rule_cd, occurred_at, last_by, last_at)
        SELECT c.event_id, c.comp_cd, c.status, c.item_id, c.char_id, c.insp_type, c.proc_id, c.rule_cd, c.occurred_at, c.last_by, c.last_at
          FROM hb_qms_spc_event_state_calc() c ORDER BY c.event_id;
        INSERT INTO qms_spc_unit (comp_cd, item_id, char_id, insp_type, proc_id, subgroup_cnt, last_at)
        SELECT c.comp_cd, c.item_id, c.char_id, c.insp_type, c.proc_id, c.subgroup_cnt, c.last_at FROM hb_qms_spc_unit_calc() c;
    END LOOP;
    PERFORM set_config('app.comp_cd', '', true);
END
$$;

-- ---------------------------------------------------------------------
-- 5. 트리거 — 갱신(원장·검사 쪽 AFTER) + 가드(투영 표 BEFORE: 트리거 안에서만)
-- ---------------------------------------------------------------------
CREATE TRIGGER tg_qms_spc_event_state AFTER INSERT ON qms_spc_event FOR EACH ROW EXECUTE FUNCTION hb_qms_spc_state_on_event();
CREATE TRIGGER tg_qms_spc_event_act_state AFTER INSERT ON qms_spc_event_act FOR EACH ROW EXECUTE FUNCTION hb_qms_spc_state_on_act();
CREATE TRIGGER tg_qms_insp_spc_unit AFTER INSERT OR UPDATE OF status ON qms_insp FOR EACH ROW EXECUTE FUNCTION hb_qms_spc_unit_on_insp();

CREATE FUNCTION hb_guard_qms_spc_projection() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF pg_trigger_depth() < 2 THEN
        RAISE EXCEPTION '% is a projection of the SPC ledger / inspection results (write the source rows instead)', TG_TABLE_NAME
            USING ERRCODE = '55000', CONSTRAINT = 'ck_qms_spc_projection';
    END IF;
    RETURN CASE WHEN TG_OP = 'DELETE' THEN OLD ELSE NEW END;
END
$$;
CREATE TRIGGER tg_qms_spc_event_state_guard BEFORE INSERT OR UPDATE OR DELETE ON qms_spc_event_state FOR EACH ROW
    EXECUTE FUNCTION hb_guard_qms_spc_projection();
CREATE TRIGGER tg_qms_spc_unit_guard BEFORE INSERT OR UPDATE OR DELETE ON qms_spc_unit FOR EACH ROW
    EXECUTE FUNCTION hb_guard_qms_spc_projection();
