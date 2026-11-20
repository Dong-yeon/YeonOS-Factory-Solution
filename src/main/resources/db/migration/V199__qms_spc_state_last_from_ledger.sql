-- 결함 S-1 (docs/qa/2026-10-27_spc-projection-flaky.md, docs/29_P3 22.8): SPC 위반 투영의 last_by·last_at 이 동시 처리 때 원장 계산과 어긋남
-- act_id 는 행을 만들 때 받고 같은 이벤트 줄 세우기(V157 가드 advisory)는 그 뒤 BEFORE 트리거다. 번호가 작은 처리가 가드에서 밀려 늦게 커밋되면
-- 옛 투영 트리거(V186, NEW 그대로)가 큰 번호의 처리를 덮어 "마지막 커밋" 기준이 되고, 계산 함수 hb_qms_spc_event_state_calc 는 "act_id 최대" 기준이라 갈라졌다.
-- 고침: 투영 트리거가 NEW 대신 원장을 다시 읽는다 — 계산 함수와 같은 정의(상태 = VOID > CLOSE > 처리 있음, 마지막 = act_id 최대).
-- 가드가 같은 이벤트를 커밋까지 줄 세우므로 늦은 쪽 트리거는 먼저 커밋된 행을 본다(READ COMMITTED, plpgsql 문장마다 새 스냅숏).
-- 출발점: V186 의 hb_qms_spc_state_on_act(이 함수의 마지막 정의 — 이후 V 에서 다시 정의한 곳 없음)

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
     ORDER BY x.act_id DESC LIMIT 1;
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

-- 이미 틀어진 행(운영 데이터 포함 가능) 한 번 맞춤 — 계산 함수 기준, 다른 행만. 가드는 마이그레이션 동안만 끈다.
-- 마이그레이션 계정이 슈퍼유저가 아니면 FORCE RLS 를 받으므로 회사마다 범위를 연다(V186 채우기와 같은 방식)
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
