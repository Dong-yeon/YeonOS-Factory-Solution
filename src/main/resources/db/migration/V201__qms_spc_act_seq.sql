-- 결함 S-2 (docs/qa/2026-10-27_qms-import-ocap-pda.md 3.3·부록 A, docs/29_P3 22.9): SPC 위반 처리의 "번호 순서 ≠ 실제 처리 순서"
-- act_id 는 행을 만들 때(식별 열 기본값) 받고, 같은 이벤트 줄 세우기(V157 가드 advisory 잠금)는 그 뒤 BEFORE 트리거다.
-- 번호를 먼저 받은 종결이 가드 앞에서 밀린 사이 큰 번호의 확인이 먼저 커밋되면, 원장을 act_id 순으로 읽을 때 "종결 → 확인" 이 되어
-- 처리 이력이 거꾸로 보이고 투영·계산(V199·V186, 마지막 = act_id 최대)의 마지막 처리자가 종결한 사람이 아니게 된다(상태·건수는 맞음).
-- 고침: 가드가 잠금을 잡은 **뒤** 이벤트 안 순번 act_seq(그 이벤트 최대 + 1)를 매긴다 — 잠금이 커밋까지 가므로 순번 = 실제 처리 순서.
-- 이력(SpcEventMapper.findActs)·투영 트리거·계산 함수는 act_seq 로 정렬한다. 출발점: 가드 V157, 투영 트리거 V199, 계산 함수 V186(각각 마지막 정의 — grep 확인)

-- ---------------------------------------------------------------------
-- 1. 열 + 기존 행 채우기
-- ---------------------------------------------------------------------
ALTER TABLE qms_spc_event_act ADD COLUMN act_seq integer;
COMMENT ON COLUMN qms_spc_event_act.act_seq IS '이벤트 안 처리 순번(1부터) — 가드가 이벤트 잠금을 잡은 뒤 매김 = 실제 처리 순서. 정렬은 act_id 가 아니라 이것 (docs/29_P3 22.9 S-2)';

-- 기존 행: act_id 순으로 채우되 종결·무효는 맨 뒤 — 가드가 종결·무효 뒤 처리를 거부하므로(이벤트마다 한 번), act_id 가 종결보다 큰 처리는
-- 반드시 S-2 로 뒤바뀐 것이다. 처리 중(ACK·ACTION)끼리의 뒤바뀜은 남은 정보로 가릴 수 없어 act_id 순 그대로 둔다(acted_at 도 커밋 순서가 아님).
-- 원장 가드(UPDATE 거부)는 이 채우기 동안만 끈다. 마이그레이션 계정이 슈퍼유저가 아니면 FORCE RLS 를 받으므로 회사마다 범위를 연다(V199 와 같은 방식)
ALTER TABLE qms_spc_event_act DISABLE TRIGGER tg_qms_spc_event_act_guard;
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
        UPDATE qms_spc_event_act a
           SET act_seq = s.seq
          FROM (SELECT x.act_id, row_number() OVER (PARTITION BY x.event_id ORDER BY (x.act_type IN ('CLOSE', 'VOID')), x.act_id) AS seq
                  FROM qms_spc_event_act x) s
         WHERE s.act_id = a.act_id;
    END LOOP;
    PERFORM set_config('app.comp_cd', '', true);
END
$$;
ALTER TABLE qms_spc_event_act ENABLE TRIGGER tg_qms_spc_event_act_guard;

ALTER TABLE qms_spc_event_act ALTER COLUMN act_seq SET NOT NULL;
ALTER TABLE qms_spc_event_act ADD CONSTRAINT ck_qms_spc_event_act_seq CHECK (act_seq > 0);
-- 같은 이벤트에 같은 순번 둘이면 실패(잠금 밖 경로·REPEATABLE READ 등으로 줄 세우기가 깨지면 조용히 틀리지 않고 오류). 이력 조회 인덱스를 겸한다
CREATE UNIQUE INDEX uq_qms_spc_event_act_seq ON qms_spc_event_act (comp_cd, event_id, act_seq);
DROP INDEX ix_qms_spc_event_act_event;

-- ---------------------------------------------------------------------
-- 2. 가드: 잠금 뒤 순번(요청이 넣은 값은 무시하고 덮는다)
-- ---------------------------------------------------------------------
CREATE OR REPLACE FUNCTION hb_guard_qms_spc_event_act() RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    v_done boolean;
BEGIN
    IF TG_OP <> 'INSERT' THEN
        RAISE EXCEPTION 'SPC event actions are append-only' USING ERRCODE = '55000', CONSTRAINT = 'ck_qms_spc_event_act_append_only';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM qms_spc_event e WHERE e.event_id = NEW.event_id AND e.comp_cd = NEW.comp_cd) THEN
        RAISE EXCEPTION 'SPC event % does not belong to company %', NEW.event_id, NEW.comp_cd
            USING ERRCODE = '23503', CONSTRAINT = 'fk_qms_spc_event_act_event_comp';
    END IF;
    -- 같은 이벤트의 처리 입력을 줄 세운 뒤(둘 다 "열림" 을 보고 종결 두 번 같은 것 방지) 종결·무효 여부를 본다
    PERFORM pg_advisory_xact_lock(hashtext('qms_spc_event_act'), (NEW.event_id % 2147483647)::int);
    SELECT EXISTS (SELECT 1 FROM qms_spc_event_act a WHERE a.event_id = NEW.event_id AND a.act_type IN ('CLOSE', 'VOID')) INTO v_done;
    IF v_done THEN
        RAISE EXCEPTION 'SPC event % is already closed or voided', NEW.event_id USING ERRCODE = '55000', CONSTRAINT = 'ck_qms_spc_event_act_done';
    END IF;
    -- 순번은 잠금 안에서(S-2) — 앞선 처리는 커밋까지 잠금을 쥐므로 이 문장의 새 스냅숏(READ COMMITTED)이 그 행을 본다
    SELECT coalesce(max(a.act_seq), 0) + 1 INTO NEW.act_seq FROM qms_spc_event_act a WHERE a.event_id = NEW.event_id;
    RETURN NEW;
END
$$;

-- ---------------------------------------------------------------------
-- 3. 투영 트리거·계산 함수: 마지막 처리 = act_seq 최대
-- ---------------------------------------------------------------------
CREATE OR REPLACE FUNCTION hb_qms_spc_state_on_act() RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    v_status  varchar;
    v_by      varchar;
    v_at      timestamptz;
BEGIN
    SELECT CASE WHEN bool_or(x.act_type = 'VOID') THEN 'VOID' WHEN bool_or(x.act_type = 'CLOSE') THEN 'CLOSED' ELSE 'ACKED' END
      INTO v_status
      FROM qms_spc_event_act x WHERE x.event_id = NEW.event_id;
    SELECT x.acted_by, x.acted_at INTO v_by, v_at
      FROM qms_spc_event_act x WHERE x.event_id = NEW.event_id
     ORDER BY x.act_seq DESC LIMIT 1;
    UPDATE qms_spc_event_state
       SET status = v_status, last_by = v_by, last_at = v_at
     WHERE event_id = NEW.event_id;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'SPC event state % missing (projection out of sync)', NEW.event_id
            USING ERRCODE = '55000', CONSTRAINT = 'ck_qms_spc_event_state_sync';
    END IF;
    RETURN NULL;
END
$$;

CREATE OR REPLACE FUNCTION hb_qms_spc_event_state_calc()
    RETURNS TABLE (event_id bigint, comp_cd varchar, status varchar, item_id bigint, char_id bigint, insp_type varchar, proc_id bigint,
                   rule_cd varchar, occurred_at timestamptz, last_by varchar, last_at timestamptz)
    LANGUAGE sql STABLE
AS $$
    -- 처리 기록을 이벤트별로 한 번 묶어 붙인다(이벤트마다 부질의면 처리 기록을 행마다 훑는다 — 인덱스는 comp_cd 로 시작)
    SELECT e.event_id, e.comp_cd,
           (CASE WHEN a.has_void THEN 'VOID' WHEN a.has_close THEN 'CLOSED' WHEN a.event_id IS NOT NULL THEN 'ACKED' ELSE 'OPEN' END)::varchar,
           e.item_id, e.char_id, e.insp_type, e.proc_id, e.rule_cd, e.occurred_at, la.acted_by, la.acted_at
      FROM qms_spc_event e
      LEFT JOIN (SELECT x.event_id, bool_or(x.act_type = 'VOID') AS has_void, bool_or(x.act_type = 'CLOSE') AS has_close, max(x.act_seq) AS last_seq
                   FROM qms_spc_event_act x GROUP BY x.event_id) a ON a.event_id = e.event_id
      LEFT JOIN qms_spc_event_act la ON la.event_id = a.event_id AND la.act_seq = a.last_seq
$$;

-- ---------------------------------------------------------------------
-- 4. 투영 다시 맞춤 — 1 의 채우기로 마지막 처리가 바뀐 이벤트(과거 S-2 로 뒤바뀐 종결)만. 투영 가드는 이 동안만 끈다(V199 와 같은 블록)
-- ---------------------------------------------------------------------
ALTER TABLE qms_spc_event_state DISABLE TRIGGER tg_qms_spc_event_state_guard;
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
        UPDATE qms_spc_event_state s
           SET status = c.status, last_by = c.last_by, last_at = c.last_at
          FROM hb_qms_spc_event_state_calc() c
         WHERE c.event_id = s.event_id
           AND (s.status, s.last_by, s.last_at) IS DISTINCT FROM (c.status, c.last_by, c.last_at);
    END LOOP;
    PERFORM set_config('app.comp_cd', '', true);
END
$$;
ALTER TABLE qms_spc_event_state ENABLE TRIGGER tg_qms_spc_event_state_guard;
