-- =====================================================================
-- 아웃박스 보존 정리 (docs/36 19.2 R1~R8, Q-O2 결정 2026-10-30) — 임시 V9717
-- - 새 보존 클래스 OUTBOX: 기준 STATUS_DONE(끝난 시각), 기본 90일·최소 30일, 정리 허용. sys_outbox 매핑 MESSAGE → OUTBOX.
--   sys_inbox 는 MESSAGE(KEEP) 그대로 — 수신 멱등 키라 지우면 같은 메시지를 다시 처리한다
-- - 지우는 것: SENT·CANCELED·CLOSED_MANUAL 중 끝난 시각이 회사 보존 기간(클래스 최소 이상)보다 오래된 행, 법적 보류 기간 밖.
--   끝난 시각 = SENT → sent_at, CLOSED_MANUAL → closed_at, CANCELED → created_at(취소는 보내기 전 NEW 에서만 — 표에 갱신 시각 칸이 없다).
--   남기는 것: NEW·SENDING·FAIL(처리 안 끝남 — 종결 결재 대기 FAIL 포함)
-- - 정리는 소유자 전용 hb_purge_outbox(p_now, p_limit)(앱 EXECUTE 없음 — 앱 계정은 sys_outbox DELETE 권한도 없다, V008).
--   회사당 한 번에 p_limit 행(기본 5,000)까지, outbox_id 순. 결과 = 회사·지운 수·남은 대상 수(0·0 인 회사는 줄 없음). 남은 수는 상한까지 지운 회사만 센다
-- - hb_purge_logs 는 기준 OCCURRED_AT 만 보므로 이 클래스를 건드리지 않는다
-- 임시 번호(docs/29 2.2) — 병합 때 이름만 바뀐다.
-- =====================================================================

-- ---------------------------------------------------------------------
-- 1. 보존 클래스·매핑
-- ---------------------------------------------------------------------
ALTER TABLE sys_retention_class DROP CONSTRAINT ck_sys_retention_class_basis;
ALTER TABLE sys_retention_class ADD CONSTRAINT ck_sys_retention_class_basis
    CHECK (basis IN ('OCCURRED_AT', 'ITEM_EOL', 'KEEP', 'STATUS_DONE'));

INSERT INTO sys_retention_class (class_cd, basis, default_days, min_days, purge_allowed_yn, desc_key) VALUES
    ('OUTBOX', 'STATUS_DONE', 90, 30, 'Y', 'retention.OUTBOX');
UPDATE sys_retention_table SET class_cd = 'OUTBOX' WHERE table_nm = 'sys_outbox';

-- ---------------------------------------------------------------------
-- 2. 판정 함수(정리 함수 안에서만 — 시험이 같은 판정을 쓰도록 이름을 붙였다)
-- ---------------------------------------------------------------------
-- 끝난 시각: SENT → sent_at(없으면 만든 시각), CLOSED_MANUAL → closed_at, CANCELED → 만든 시각(취소는 보내기 전에만 — 갱신 시각 칸이 없다)
CREATE FUNCTION hb_outbox_done_at(p_status varchar, p_sent_at timestamptz, p_closed_at timestamptz, p_created_at timestamptz)
    RETURNS timestamptz LANGUAGE sql IMMUTABLE
AS $$
    SELECT CASE p_status WHEN 'SENT' THEN coalesce(p_sent_at, p_created_at)
                         WHEN 'CLOSED_MANUAL' THEN coalesce(p_closed_at, p_created_at)
                         ELSE p_created_at END
$$;
-- 지울 수 있는 상태이고 끝난 시각이 기준보다 앞섬(NEW·SENDING·FAIL 은 늘 거짓)
CREATE FUNCTION hb_outbox_purgeable(p_status varchar, p_sent_at timestamptz, p_closed_at timestamptz, p_created_at timestamptz,
        p_cutoff timestamptz)
    RETURNS boolean LANGUAGE sql IMMUTABLE
AS $$
    SELECT p_status IN ('SENT', 'CANCELED', 'CLOSED_MANUAL')
       AND hb_outbox_done_at(p_status, p_sent_at, p_closed_at, p_created_at) < p_cutoff
$$;
-- 그 시각이 회사의 OUTBOX 법적 보류 기간(해제 안 됨) 안인가 — 호출하는 쪽이 회사 범위를 연 상태(RLS)
CREATE FUNCTION hb_outbox_on_hold(p_comp_cd varchar, p_at timestamptz)
    RETURNS boolean LANGUAGE sql STABLE
AS $$
    SELECT EXISTS (SELECT 1 FROM sys_legal_hold h
                    WHERE h.comp_cd = p_comp_cd AND h.class_cd = 'OUTBOX' AND h.released_at IS NULL
                      AND p_at >= h.from_at AND p_at < h.to_at)
$$;
REVOKE EXECUTE ON FUNCTION hb_outbox_done_at(varchar, timestamptz, timestamptz, timestamptz),
                           hb_outbox_purgeable(varchar, timestamptz, timestamptz, timestamptz, timestamptz),
                           hb_outbox_on_hold(varchar, timestamptz) FROM PUBLIC, ${app_role};

-- ---------------------------------------------------------------------
-- 3. 정리 함수 — 소유자 계정으로만(앱 EXECUTE 회수). 회사마다 범위를 열고 정리(hb_purge_logs 와 같은 순서)
-- ---------------------------------------------------------------------
CREATE FUNCTION hb_purge_outbox(p_now timestamptz DEFAULT now(), p_limit integer DEFAULT 5000)
    RETURNS TABLE (out_comp_cd varchar, out_deleted bigint, out_remaining bigint)
    LANGUAGE plpgsql
AS $$
DECLARE
    comps  varchar[];
    c_cd   varchar;
    keep   integer;
    cutoff timestamptz;
    ids    bigint[];
    n      bigint;
    rest   bigint;
BEGIN
    IF p_now IS NULL OR p_limit IS NULL OR p_limit < 1 OR p_limit > 100000 THEN
        RAISE EXCEPTION 'hb_purge_outbox: p_now required, p_limit 1..100000' USING ERRCODE = '22023';
    END IF;
    -- 회사 목록을 먼저 배열로(루프 중 범위를 바꾸므로 커서로 읽으면 RLS 판정이 달라질 수 있다)
    PERFORM set_config('app.comp_cd', '', true);
    PERFORM set_config('app.scope', 'SYSTEM', true);
    comps := ARRAY(SELECT c.comp_cd FROM sys_comp c ORDER BY c.comp_cd);
    PERFORM set_config('app.scope', '', true);

    FOREACH c_cd IN ARRAY comps LOOP
        PERFORM set_config('app.comp_cd', c_cd, true);
        SELECT greatest(coalesce(p.keep_days, k.default_days), k.min_days) INTO keep
          FROM sys_retention_class k
          LEFT JOIN sys_retention_policy p ON p.comp_cd = c_cd AND p.class_cd = k.class_cd
         WHERE k.class_cd = 'OUTBOX';
        cutoff := p_now - make_interval(days => keep);

        ids := ARRAY(
            SELECT o.outbox_id FROM sys_outbox o
             WHERE o.comp_cd = c_cd AND hb_outbox_purgeable(o.status, o.sent_at, o.closed_at, o.created_at, cutoff)
               AND NOT hb_outbox_on_hold(c_cd, hb_outbox_done_at(o.status, o.sent_at, o.closed_at, o.created_at))
             ORDER BY o.outbox_id
             LIMIT p_limit);
        n := 0;
        IF cardinality(ids) > 0 THEN
            DELETE FROM sys_outbox o WHERE o.comp_cd = c_cd AND o.outbox_id = ANY (ids)
               AND o.status IN ('SENT', 'CANCELED', 'CLOSED_MANUAL');   -- 읽은 뒤 바뀐 행은 지우지 않는다(끝난 상태는 다시 바뀌지 않지만 방어)
            GET DIAGNOSTICS n = ROW_COUNT;
        END IF;
        rest := 0;
        IF cardinality(ids) = p_limit THEN   -- 상한까지 지웠을 때만 남은 대상을 센다(대부분의 날은 세지 않는다)
            SELECT count(*) INTO rest FROM sys_outbox o
             WHERE o.comp_cd = c_cd AND hb_outbox_purgeable(o.status, o.sent_at, o.closed_at, o.created_at, cutoff)
               AND NOT hb_outbox_on_hold(c_cd, hb_outbox_done_at(o.status, o.sent_at, o.closed_at, o.created_at));
        END IF;
        IF n > 0 OR rest > 0 THEN
            out_comp_cd := c_cd; out_deleted := n; out_remaining := rest;
            RETURN NEXT;
        END IF;
    END LOOP;
    PERFORM set_config('app.comp_cd', '', true);
END
$$;
COMMENT ON FUNCTION hb_purge_outbox(timestamptz, integer) IS
    '아웃박스 보존 정리 — 소유자 전용(앱 EXECUTE 없음), 끝난 3상태(SENT·CANCELED·CLOSED_MANUAL)만, 회사 보존 기간·법적 보류, 회사당 p_limit 행(docs/36 19.2). out_remaining = 상한에 걸려 다음 실행에 남은 대상 수';
REVOKE EXECUTE ON FUNCTION hb_purge_outbox(timestamptz, integer) FROM PUBLIC, ${app_role};
