-- =====================================================================
-- 수집 COUNT 원장 (docs/25 4.27, 11.4a 4번 — 임시 V9115)
-- - 6단계(V051)는 COUNT 를 받기만 했다(sys_inbox). 실적 자동 제안(4.1, 11 D1)을 하려면 쌓아 둬야 한다
-- - 값 = 그 이벤트 동안 늘어난 수(증가분). 누적 카운터의 리셋·감소 처리는 수집기 몫 — MES 는 0 이상 증가분만 받는다
-- - 메트릭 goodCount(양품)·scrapCount(불량) — 품목 기본 단위 수로 본다(카운터 = 개수 설비)
-- - 원장 규약(01 3.1): occurred_at·recorded_at·entry_method, 정정은 역분개 행만, UPDATE/DELETE 차단. 설비·장치는 같은 회사(FK 는 RLS 를 안 본다)
-- =====================================================================

CREATE TABLE cms_equip_count (
    count_id        bigint         GENERATED ALWAYS AS IDENTITY,
    comp_cd         varchar(20)    NOT NULL,
    equip_id        bigint         NOT NULL,
    metric_cd       varchar(20)    NOT NULL,
    qty             numeric(18,6)  NOT NULL,
    terminal_id     bigint,
    event_id        varchar(100),
    reason          varchar(500),
    occurred_at     timestamptz    NOT NULL,
    recorded_at     timestamptz    NOT NULL DEFAULT now(),
    entry_method    varchar(10)    NOT NULL,
    recorded_by     varchar(50)    NOT NULL,
    reversal_of_id  bigint,
    CONSTRAINT pk_cms_equip_count PRIMARY KEY (count_id),
    CONSTRAINT fk_cms_equip_count_comp FOREIGN KEY (comp_cd) REFERENCES sys_comp (comp_cd),
    CONSTRAINT fk_cms_equip_count_equip FOREIGN KEY (equip_id) REFERENCES mst_equip (equip_id),
    CONSTRAINT fk_cms_equip_count_terminal FOREIGN KEY (terminal_id) REFERENCES sys_terminal (terminal_id),
    CONSTRAINT fk_cms_equip_count_reversal FOREIGN KEY (reversal_of_id) REFERENCES cms_equip_count (count_id),
    CONSTRAINT ck_cms_equip_count_metric CHECK (metric_cd IN ('GOOD', 'SCRAP')),
    CONSTRAINT ck_cms_equip_count_method CHECK (entry_method IN ('AUTO', 'CORRECT')),
    CONSTRAINT ck_cms_equip_count_qty CHECK (
        (reversal_of_id IS NULL AND entry_method = 'AUTO' AND qty >= 0)
        OR (reversal_of_id IS NOT NULL AND entry_method = 'CORRECT' AND qty <= 0 AND coalesce(length(btrim(reason)), 0) > 0))
);
COMMENT ON TABLE cms_equip_count IS '수집 생산 카운트 원장 (docs/25 4.27) — 이벤트마다 증가분, 실적 자동 제안의 근거. 확정 실적은 prd_result';
CREATE INDEX ix_cms_equip_count_equip ON cms_equip_count (comp_cd, equip_id, occurred_at);
CREATE UNIQUE INDEX uq_cms_equip_count_reversal ON cms_equip_count (reversal_of_id) WHERE reversal_of_id IS NOT NULL;

CREATE FUNCTION hb_guard_equip_count() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM mst_equip e WHERE e.equip_id = NEW.equip_id AND e.comp_cd = NEW.comp_cd)
       OR (NEW.terminal_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM sys_terminal t WHERE t.terminal_id = NEW.terminal_id
                                                          AND t.comp_cd = NEW.comp_cd)) THEN
        RAISE EXCEPTION 'count context is not of company %', NEW.comp_cd
            USING ERRCODE = '23503', CONSTRAINT = 'fk_cms_equip_count_ctx_comp';
    END IF;
    RETURN NEW;
END
$$;
CREATE TRIGGER tg_cms_equip_count_guard BEFORE INSERT ON cms_equip_count FOR EACH ROW EXECUTE FUNCTION hb_guard_equip_count();
CREATE TRIGGER tg_cms_equip_count_ledger BEFORE UPDATE OR DELETE ON cms_equip_count FOR EACH ROW EXECUTE FUNCTION hb_forbid_update_delete();

ALTER TABLE cms_equip_count ENABLE ROW LEVEL SECURITY;
ALTER TABLE cms_equip_count FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON cms_equip_count USING (comp_cd = hb_current_comp()) WITH CHECK (comp_cd = hb_current_comp());
REVOKE UPDATE, DELETE, TRUNCATE ON cms_equip_count FROM ${app_role};
INSERT INTO sys_retention_table (table_nm, class_cd) VALUES ('cms_equip_count', 'PRODUCTION_RECORD');
