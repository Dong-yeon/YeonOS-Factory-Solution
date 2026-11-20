-- =====================================================================
-- V9850(임시, 병합 때 재번호) APS 8단계 — 계획 기준정보·부족 → 구매 요청 연결 (docs/38 17장, docs/63 G1·G2, 결정 AP-6·AP-7)
--
-- - aps_item_param: 계획 기준정보(공장 × 품목) — 안전재고, 로트 규칙(EX·FX·MIN) + 로트 수량, 배수, 자체 생산 리드타임, 확정 구간
-- - aps_mps_item 의 안전재고·로트 칸을 aps_item_param 으로 옮기고 칸을 지운다(값이 두 곳에 있지 않게 — 17.1)
-- - aps_mps_row: 계산 때 배수 사본 칸
-- - aps_plan_pr: 계획 자재 줄 ↔ 구매 요청 줄 연결(추가만). 요청은 SRM PrService 가 만든다 — 이 파일은 SRM 표를 바꾸지 않는다
-- - srm_source·srm_pr·srm_pr_line·mst_item 은 읽기만(트리거 확인)
-- =====================================================================

-- ---------------------------------------------------------------------
-- 1. 계획 기준정보
-- ---------------------------------------------------------------------
CREATE TABLE aps_item_param (
    param_id        bigint         GENERATED ALWAYS AS IDENTITY,
    comp_cd         varchar(20)    NOT NULL,
    plant_cd        varchar(20)    NOT NULL,
    item_id         bigint         NOT NULL,
    safety_qty      numeric(18,6)  NOT NULL DEFAULT 0,
    lot_rule        varchar(3)     NOT NULL DEFAULT 'EX',
    lot_qty         numeric(18,6),
    lot_mult        numeric(18,6),
    make_lead_days  smallint,
    fence_days      smallint,
    remark          varchar(500),
    use_yn          char(1)        NOT NULL DEFAULT 'Y',
    row_ver         integer        NOT NULL DEFAULT 0,
    created_at      timestamptz    NOT NULL DEFAULT now(),
    created_by      varchar(50)    NOT NULL,
    updated_at      timestamptz    NOT NULL DEFAULT now(),
    updated_by      varchar(50)    NOT NULL,
    CONSTRAINT pk_aps_item_param PRIMARY KEY (param_id),
    CONSTRAINT fk_aps_item_param_plant FOREIGN KEY (comp_cd, plant_cd) REFERENCES sys_plant (comp_cd, plant_cd),
    CONSTRAINT fk_aps_item_param_item FOREIGN KEY (item_id) REFERENCES mst_item (item_id),
    CONSTRAINT uq_aps_item_param UNIQUE (comp_cd, plant_cd, item_id),
    CONSTRAINT ck_aps_item_param_use CHECK (use_yn IN ('Y', 'N')),
    CONSTRAINT ck_aps_item_param_safety CHECK (safety_qty >= 0),
    -- 로트 규칙은 시스템 고정 값(계산이 값마다 분기 — 공통코드 아님, docs/38 17.2). EX 는 로트 수량 없음, FX·MIN 은 > 0 필수
    CONSTRAINT ck_aps_item_param_lot CHECK ((lot_rule = 'EX' AND lot_qty IS NULL)
                                            OR (lot_rule IN ('FX', 'MIN') AND lot_qty IS NOT NULL AND lot_qty > 0)),
    -- 배수는 EX·MIN 결과를 올림. FX 는 로트 수량이 이미 배수라 비운다
    CONSTRAINT ck_aps_item_param_mult CHECK (lot_mult IS NULL OR (lot_mult > 0 AND lot_rule <> 'FX')),
    CONSTRAINT ck_aps_item_param_days CHECK ((make_lead_days IS NULL OR make_lead_days BETWEEN 0 AND 365)
                                             AND (fence_days IS NULL OR fence_days BETWEEN 0 AND 60))
);
COMMENT ON TABLE aps_item_param IS '계획 기준정보(공장 × 품목, docs/38 17장) — 안전재고·로트 규칙·배수·자체 생산 리드타임·확정 구간. 구매 조건은 srm_source';
COMMENT ON COLUMN aps_item_param.lot_mult IS '배수 — 로트 규칙(EX·MIN) 결과를 이 배수로 올림(SAP 반올림 값). FX 는 비움';
COMMENT ON COLUMN aps_item_param.make_lead_days IS '자체 생산 리드타임(일) — 비우면 미정(MRP 가 라우팅으로 추정, 다음 차수)';
COMMENT ON COLUMN aps_item_param.fence_days IS '확정 구간(일) — 비우면 공장 정책(docs/63 AP-11, G6 에서 읽음)';
CREATE INDEX ix_aps_item_param_item ON aps_item_param (comp_cd, item_id);

-- FK 는 RLS 를 무시한다 — 품목이 같은 회사인지. 공장·품목은 고정(바꾸려면 사용 중지 뒤 새로)
CREATE FUNCTION hb_guard_aps_item_param() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'UPDATE' AND (NEW.plant_cd, NEW.item_id) IS DISTINCT FROM (OLD.plant_cd, OLD.item_id) THEN
        RAISE EXCEPTION 'item param % keeps its plant and item', OLD.param_id USING ERRCODE = '55000', CONSTRAINT = 'ck_aps_item_param_fixed';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM mst_item i WHERE i.item_id = NEW.item_id AND i.comp_cd = NEW.comp_cd) THEN
        RAISE EXCEPTION 'item param refers to another company item' USING ERRCODE = '23503', CONSTRAINT = 'fk_aps_item_param_comp';
    END IF;
    RETURN NEW;
END
$$;
CREATE TRIGGER tg_aps_item_param_guard BEFORE INSERT OR UPDATE ON aps_item_param FOR EACH ROW EXECUTE FUNCTION hb_guard_aps_item_param();
CREATE TRIGGER tg_aps_item_param_no_delete BEFORE DELETE ON aps_item_param FOR EACH ROW EXECUTE FUNCTION hb_forbid_delete_master();
CREATE TRIGGER tg_audit AFTER INSERT OR UPDATE OR DELETE ON aps_item_param FOR EACH ROW EXECUTE FUNCTION hb_audit('param_id', '', '');

ALTER TABLE aps_item_param ENABLE ROW LEVEL SECURITY;
ALTER TABLE aps_item_param FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON aps_item_param USING (comp_cd = hb_current_comp()) WITH CHECK (comp_cd = hb_current_comp());

-- ---------------------------------------------------------------------
-- 2. MPS 품목의 안전재고·로트 칸 옮기기 → 칸 삭제
--    회사마다 범위를 열고(V185 수법 — 소유자도 FORCE RLS 대상). 같은 공장·품목 행이 여럿이면 사용 중 → 최근 수정 하나
-- ---------------------------------------------------------------------
DO $$
DECLARE
    v_comps varchar[];
    v_comp  varchar;
BEGIN
    SELECT coalesce(array_agg(comp_cd), ARRAY[]::varchar[]) INTO v_comps FROM sys_comp;
    PERFORM set_config('app.scope', '', true);
    FOREACH v_comp IN ARRAY v_comps LOOP
        PERFORM set_config('app.comp_cd', v_comp, true);
        INSERT INTO aps_item_param (comp_cd, plant_cd, item_id, safety_qty, lot_rule, lot_qty, remark, use_yn, created_by, updated_by)
        SELECT DISTINCT ON (m.plant_cd, m.item_id) m.comp_cd, m.plant_cd, m.item_id, m.safety_qty, m.lot_rule, m.lot_qty,
               'MPS 품목에서 옮김(V9850)', m.use_yn, 'V9850', 'V9850'
          FROM aps_mps_item m
         WHERE m.comp_cd = v_comp
         ORDER BY m.plant_cd, m.item_id, (m.use_yn = 'Y') DESC, m.updated_at DESC, m.mps_item_id DESC;
    END LOOP;
    PERFORM set_config('app.comp_cd', '', true);
END
$$;

ALTER TABLE aps_mps_item DROP CONSTRAINT ck_aps_mps_item_lot;
ALTER TABLE aps_mps_item DROP CONSTRAINT ck_aps_mps_item_safety;
ALTER TABLE aps_mps_item DROP COLUMN safety_qty, DROP COLUMN lot_rule, DROP COLUMN lot_qty;
COMMENT ON TABLE aps_mps_item IS 'MPS 대상 품목(공장 × 품목, docs/38 16장) — 품목은 회사에서 한 공장 MPS 에만. 안전재고·로트는 aps_item_param(17장)';

-- 계산 때 배수 사본(추가만 표 — 칸 추가는 기존 행에 NULL)
ALTER TABLE aps_mps_row ADD COLUMN lot_mult numeric(18,6);

-- ---------------------------------------------------------------------
-- 3. 계획 자재 줄 ↔ 구매 요청 줄 연결 (추가만)
-- ---------------------------------------------------------------------
CREATE TABLE aps_plan_pr (
    plan_pr_id   bigint         GENERATED ALWAYS AS IDENTITY,
    comp_cd      varchar(20)    NOT NULL,
    plan_id      bigint         NOT NULL,
    plan_mat_id  bigint         NOT NULL,
    wo_id        bigint         NOT NULL,
    op_seq       integer        NOT NULL,
    item_id      bigint         NOT NULL,
    short_qty    numeric(18,6)  NOT NULL,
    pr_id        bigint         NOT NULL,
    pr_line_id   bigint         NOT NULL,
    created_at   timestamptz    NOT NULL DEFAULT now(),
    created_by   varchar(50)    NOT NULL,
    CONSTRAINT pk_aps_plan_pr PRIMARY KEY (plan_pr_id),
    CONSTRAINT fk_aps_plan_pr_plan FOREIGN KEY (plan_id) REFERENCES aps_plan (plan_id),
    CONSTRAINT fk_aps_plan_pr_item FOREIGN KEY (item_id) REFERENCES mst_item (item_id),
    CONSTRAINT fk_aps_plan_pr_pr FOREIGN KEY (pr_id) REFERENCES srm_pr (pr_id),
    CONSTRAINT ck_aps_plan_pr_qty CHECK (short_qty > 0)
    -- plan_mat_id: FK 없음 — 결과 보존 정리(hb_purge_aps_results)가 aps_plan_mat 을 지울 수 있다
    -- pr_line_id: FK 없음 — 작성 중 요청의 줄은 SRM 화면이 지울 수 있다(남의 동작을 바꾸지 않게). 살아 있는 연결은 조회 때 판정
);
COMMENT ON TABLE aps_plan_pr IS 'APS 계획 자재 부족 줄 → 구매 요청 줄 연결(docs/38 17.4) — 추가만. 요청이 취소·반려되거나 줄이 지워지면 연결은 죽은 것으로 본다';
CREATE INDEX ix_aps_plan_pr_plan ON aps_plan_pr (comp_cd, plan_id, plan_mat_id);
CREATE INDEX ix_aps_plan_pr_pr ON aps_plan_pr (comp_cd, pr_id);

-- 넣을 때: 계획·품목·요청이 같은 회사, 자재 줄이 그 계획·품목의 부족 줄, 요청 줄이 그 요청·품목
CREATE FUNCTION hb_guard_aps_plan_pr() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM aps_plan p WHERE p.plan_id = NEW.plan_id AND p.comp_cd = NEW.comp_cd)
       OR NOT EXISTS (SELECT 1 FROM srm_pr r WHERE r.pr_id = NEW.pr_id AND r.comp_cd = NEW.comp_cd) THEN
        RAISE EXCEPTION 'plan purchase link refers to another company' USING ERRCODE = '23503', CONSTRAINT = 'fk_aps_plan_pr_comp';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM aps_plan_mat m WHERE m.plan_mat_id = NEW.plan_mat_id AND m.plan_id = NEW.plan_id
                      AND m.comp_cd = NEW.comp_cd AND m.item_id = NEW.item_id AND m.wo_id = NEW.wo_id AND m.op_seq = NEW.op_seq
                      AND m.short_qty > 0) THEN
        RAISE EXCEPTION 'plan purchase link needs a short material row of the plan' USING ERRCODE = '23503', CONSTRAINT = 'fk_aps_plan_pr_mat';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM srm_pr_line l WHERE l.pr_line_id = NEW.pr_line_id AND l.pr_id = NEW.pr_id
                      AND l.comp_cd = NEW.comp_cd AND l.item_id = NEW.item_id) THEN
        RAISE EXCEPTION 'plan purchase link needs a request line of the same item' USING ERRCODE = '23503', CONSTRAINT = 'fk_aps_plan_pr_line';
    END IF;
    RETURN NEW;
END
$$;
CREATE TRIGGER tg_aps_plan_pr_guard BEFORE INSERT ON aps_plan_pr FOR EACH ROW EXECUTE FUNCTION hb_guard_aps_plan_pr();
CREATE TRIGGER tg_aps_plan_pr_append_only BEFORE UPDATE OR DELETE ON aps_plan_pr FOR EACH ROW EXECUTE FUNCTION hb_forbid_update_delete();
CREATE TRIGGER tg_audit AFTER INSERT ON aps_plan_pr FOR EACH ROW EXECUTE FUNCTION hb_audit('plan_pr_id', '', '');

ALTER TABLE aps_plan_pr ENABLE ROW LEVEL SECURITY;
ALTER TABLE aps_plan_pr FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON aps_plan_pr USING (comp_cd = hb_current_comp()) WITH CHECK (comp_cd = hb_current_comp());

-- ---------------------------------------------------------------------
-- 4. 권한 · 화면 · 보존
-- ---------------------------------------------------------------------
REVOKE DELETE, TRUNCATE ON aps_item_param FROM ${app_role};
REVOKE UPDATE, DELETE, TRUNCATE ON aps_plan_pr FROM ${app_role};

INSERT INTO sys_prog (prog_cd, prog_nm_key, view_path, module_cd, actions, sort_no) VALUES
    ('aps-item-param', 'prog.apsItemParam', 'aps/item-param', 'APS', ARRAY['SEARCH','SAVE']::varchar(20)[], 972);
INSERT INTO sys_retention_table (table_nm, class_cd) VALUES
    ('aps_item_param', 'PLANNING'), ('aps_plan_pr', 'PLANNING');
