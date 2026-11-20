-- =====================================================================
-- CMS 2부 C10 — 카운터 자동 입력 (docs/33 11.2 C10·11.10, 결정 D23)
--
-- - 정기 작업(매시, TenantRunner + JobLock)이 원장을 읽어 C3 카운터에 source = AUTO 증가량 행을 넣는다. 원장은 읽기만
--   SHOTS·CYCLES ← MES 실적(원래 행만, 정정된 것 제외 — C9 와 같은 규칙) 생산 수 ÷ 사이클당 수량
--   RUN_HOURS    ← 닫힌 PRD 상태 구간(실효 분류) 시간
--   금형·지그(ASSET) 카운터 = 그 시각 그 설비에 설치돼 있던 자산(P1 설치 이력, 무효 제외) — 구간은 설치 기간으로 자름
-- - 두 번 세지 않기: 원장 행 하나 × 카운터 하나 = 이 표 한 행(PK). 시각 워터마크 대신 원장 행 키를 쓴다 —
--   늦게 커밋된 실적·늦게 닫힌 구간도 다음 실행에서 잡힌다(시각 워터마크는 그 사이 행을 놓친다)
-- - 카운터를 만든 뒤 기록된 원장만 센다(지난 이력을 거슬러 채우지 않음), 정정은 빼지 않는다(검토 요청 D25)
-- 임시 번호(docs/29 2.2) — 병합 때 이름만 바뀐다.
-- =====================================================================

CREATE TABLE cms_counter_src (
    comp_cd     varchar(20)   NOT NULL,
    counter_id  bigint        NOT NULL,
    src_kind    varchar(10)   NOT NULL,
    src_id      bigint        NOT NULL,
    reading_id  bigint        NOT NULL,
    qty         numeric(24,12) NOT NULL,
    created_at  timestamptz   NOT NULL DEFAULT now(),
    CONSTRAINT pk_cms_counter_src PRIMARY KEY (counter_id, src_kind, src_id),
    CONSTRAINT fk_cms_counter_src_counter FOREIGN KEY (counter_id) REFERENCES cms_counter (counter_id),
    CONSTRAINT fk_cms_counter_src_reading FOREIGN KEY (reading_id) REFERENCES cms_counter_reading (reading_id),
    CONSTRAINT ck_cms_counter_src_kind CHECK (src_kind IN ('RESULT', 'STATE')),
    CONSTRAINT ck_cms_counter_src_qty CHECK (qty >= 0)
);
CREATE INDEX ix_cms_counter_src_reading ON cms_counter_src (comp_cd, reading_id);
COMMENT ON TABLE cms_counter_src IS '카운터 자동 입력의 출처 (docs/33 C10) — 원장 행 × 카운터 한 번만. 추가만(원장 변형)';

-- 같은 회사·같은 카운터의 AUTO 읽기만
CREATE FUNCTION hb_guard_cms_counter_src() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP <> 'INSERT' THEN
        RAISE EXCEPTION 'counter source is append-only' USING ERRCODE = '55000', CONSTRAINT = 'ck_cms_counter_src_ledger';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM cms_counter_reading r WHERE r.reading_id = NEW.reading_id AND r.comp_cd = NEW.comp_cd
                      AND r.counter_id = NEW.counter_id AND r.source = 'AUTO') THEN
        RAISE EXCEPTION 'counter source must point to an automatic reading of the same counter and company %', NEW.comp_cd
            USING ERRCODE = '23503', CONSTRAINT = 'fk_cms_counter_src_ctx_comp';
    END IF;
    RETURN NEW;
END
$$;
CREATE TRIGGER tg_cms_counter_src_guard BEFORE INSERT OR UPDATE OR DELETE ON cms_counter_src
    FOR EACH ROW EXECUTE FUNCTION hb_guard_cms_counter_src();

ALTER TABLE cms_counter_src ENABLE ROW LEVEL SECURITY;
ALTER TABLE cms_counter_src FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON cms_counter_src USING (comp_cd = hb_current_comp()) WITH CHECK (comp_cd = hb_current_comp());
REVOKE UPDATE, DELETE, TRUNCATE ON cms_counter_src FROM ${app_role};

INSERT INTO sys_retention_table (table_nm, class_cd) VALUES ('cms_counter_src', 'MAINT_RECORD');
