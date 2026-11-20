-- =====================================================================
-- P6 APQP A1 — PFMEA (docs/41 4장, 결정 P1~P8 — 2026-10-23 사용자 확인)
-- 임시 번호(docs/29 2.2) — 상위 세션이 병합 때 파일 이름만 바꾼다
--
-- 근거: AIAG & VDA FMEA(2019) 공개 소개의 구조(공정 아이템 › 공정 단계 › 작업요소 4M)·기능·고장사슬(FE–FM–FC)·
--       위험(S·O·D)·조치 우선순위(AP H/M/L). 핸드북의 AP 표 값·양식·문구는 넣지 않는다(저작권 — AP 규칙표는 회사 입력)
--
-- - apqp_ap_set / apqp_ap_rule: 회사가 입력하는 AP 규칙표(S·O·D 범위 → H/M/L). 시드 없음. 확정 FMEA 가 쓴 표는 고칠 수 없다
-- - apqp_fmea: 머리(번호 × 리비전, 품목 × 라우팅 리비전, 프로젝트 선택). DRAFT → RELEASED → OBSOLETE, 확정본 불변
-- - apqp_fmea_node: 구조 트리 ITEM(1) › STEP(라우팅 단계) › ELEMENT(4M)
-- - apqp_fmea_func: 구조 요소의 기능(+ 특성 qms_char 선택)
-- - apqp_fmea_fail: 고장 FE(ITEM 기능)·FM(STEP 기능)·FC(ELEMENT 기능), FE 에 심각도, FM 에 불량 유형(선택)
-- - apqp_fmea_chain: FM ↔ FE (N:M)
-- - apqp_fmea_risk: (FM, FC) 한 쌍의 예방·검출 관리·O·D, S(연결 FE 최대)·AP 는 서버가 계산해 저장
-- - apqp_fmea_action: 최적화 조치. 확정본에서도 상태·증빙·조치 뒤 O·D 를 갱신(P7) — 같은 번호의 새 리비전 작성 중엔 새 리비전에서만
-- 남의 표: 없음(기준정보 mst_item·mst_routing(_step)·qms_char·qms_defect, PMS pms_project 를 FK 로 가리키기만)
-- =====================================================================

INSERT INTO sys_code_grp (grp_cd, grp_nm_key, comp_ext_yn) VALUES
    ('APQP_FMEA_STATUS', 'code.grp.APQP_FMEA_STATUS', 'N'),
    ('APQP_M4',          'code.grp.APQP_M4',          'N'),
    ('APQP_AP',          'code.grp.APQP_AP',          'N'),
    ('APQP_ACTION_STATUS', 'code.grp.APQP_ACTION_STATUS', 'N');
INSERT INTO sys_code (grp_cd, code, code_nm_key, sort_no) VALUES
    ('APQP_FMEA_STATUS', 'DRAFT',    'code.APQP_FMEA_STATUS.DRAFT',    1),
    ('APQP_FMEA_STATUS', 'RELEASED', 'code.APQP_FMEA_STATUS.RELEASED', 2),
    ('APQP_FMEA_STATUS', 'OBSOLETE', 'code.APQP_FMEA_STATUS.OBSOLETE', 3),
    ('APQP_M4', 'MAN',      'code.APQP_M4.MAN',      1),
    ('APQP_M4', 'MACHINE',  'code.APQP_M4.MACHINE',  2),
    ('APQP_M4', 'MATERIAL', 'code.APQP_M4.MATERIAL', 3),
    ('APQP_M4', 'METHOD',   'code.APQP_M4.METHOD',   4),
    ('APQP_M4', 'MEASURE',  'code.APQP_M4.MEASURE',  5),
    ('APQP_M4', 'ENV',      'code.APQP_M4.ENV',      6),
    ('APQP_AP', 'H', 'code.APQP_AP.H', 1),
    ('APQP_AP', 'M', 'code.APQP_AP.M', 2),
    ('APQP_AP', 'L', 'code.APQP_AP.L', 3),
    ('APQP_ACTION_STATUS', 'OPEN',      'code.APQP_ACTION_STATUS.OPEN',      1),
    ('APQP_ACTION_STATUS', 'DONE',      'code.APQP_ACTION_STATUS.DONE',      2),
    ('APQP_ACTION_STATUS', 'CANCELLED', 'code.APQP_ACTION_STATUS.CANCELLED', 3);

-- ---------------------------------------------------------------------
-- 1. AP 규칙표 (회사 입력, 시드 없음)
-- ---------------------------------------------------------------------
CREATE TABLE apqp_ap_set (
    set_id      bigint        GENERATED ALWAYS AS IDENTITY,
    comp_cd     varchar(20)   NOT NULL,
    set_cd      varchar(20)   NOT NULL,
    set_nm      varchar(100)  NOT NULL,
    sample_yn   char(1)       NOT NULL DEFAULT 'N',
    remark      varchar(500),
    use_yn      char(1)       NOT NULL DEFAULT 'N',
    row_ver     integer       NOT NULL DEFAULT 0,
    created_at  timestamptz   NOT NULL DEFAULT now(),
    created_by  varchar(50)   NOT NULL,
    updated_at  timestamptz   NOT NULL DEFAULT now(),
    updated_by  varchar(50)   NOT NULL,
    CONSTRAINT pk_apqp_ap_set PRIMARY KEY (set_id),
    CONSTRAINT fk_apqp_ap_set_comp FOREIGN KEY (comp_cd) REFERENCES sys_comp (comp_cd),
    CONSTRAINT uq_apqp_ap_set_cd UNIQUE (comp_cd, set_cd),
    CONSTRAINT ck_apqp_ap_set_cd CHECK (set_cd ~ '^[A-Z0-9][A-Z0-9_-]{0,19}$'),
    CONSTRAINT ck_apqp_ap_set_nm CHECK (length(btrim(set_nm)) > 0),
    CONSTRAINT ck_apqp_ap_set_sample CHECK (sample_yn IN ('Y', 'N')),
    CONSTRAINT ck_apqp_ap_set_use CHECK (use_yn IN ('Y', 'N'))
);
COMMENT ON TABLE apqp_ap_set IS 'AP(조치 우선순위) 규칙표 — 회사가 입력(핸드북 표 시드 없음). 사용(Y)은 S·O·D 1~10 의 1,000 조합이 빠짐·겹침 없이 덮일 때만(서비스 확인)';
COMMENT ON COLUMN apqp_ap_set.sample_yn IS '예시 표시 — 데모 회사의 "예시(핸드북 아님)" 규칙표';

CREATE TABLE apqp_ap_rule (
    rule_id     bigint        GENERATED ALWAYS AS IDENTITY,
    comp_cd     varchar(20)   NOT NULL,
    set_id      bigint        NOT NULL,
    seq         integer       NOT NULL,
    sev_from      smallint      NOT NULL,
    sev_to        smallint      NOT NULL,
    occ_from      smallint      NOT NULL,
    occ_to        smallint      NOT NULL,
    det_from      smallint      NOT NULL,
    det_to        smallint      NOT NULL,
    ap          char(1)       NOT NULL,
    created_at  timestamptz   NOT NULL DEFAULT now(),
    created_by  varchar(50)   NOT NULL,
    CONSTRAINT pk_apqp_ap_rule PRIMARY KEY (rule_id),
    CONSTRAINT fk_apqp_ap_rule_set FOREIGN KEY (set_id) REFERENCES apqp_ap_set (set_id),
    CONSTRAINT uq_apqp_ap_rule_seq UNIQUE (comp_cd, set_id, seq),
    CONSTRAINT ck_apqp_ap_rule_sev CHECK (sev_from BETWEEN 1 AND 10 AND sev_to BETWEEN sev_from AND 10),
    CONSTRAINT ck_apqp_ap_rule_occ CHECK (occ_from BETWEEN 1 AND 10 AND occ_to BETWEEN occ_from AND 10),
    CONSTRAINT ck_apqp_ap_rule_det CHECK (det_from BETWEEN 1 AND 10 AND det_to BETWEEN det_from AND 10),
    CONSTRAINT ck_apqp_ap_rule_ap CHECK (ap IN ('H', 'M', 'L'))
);
COMMENT ON TABLE apqp_ap_rule IS 'AP 규칙 한 줄 = S·O·D 범위 → H/M/L. 추가·삭제만(고칠 때는 표째 다시 저장)';
CREATE INDEX ix_apqp_ap_rule_set ON apqp_ap_rule (comp_cd, set_id);

-- 규칙 행: 같은 회사 표, 확정(RELEASED·OBSOLETE) FMEA 가 쓴 표는 바꿀 수 없다(그 AP 의 근거가 바뀐다 — 복사해서 새 표로)
CREATE FUNCTION hb_guard_apqp_ap_rule() RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    v_set  bigint := CASE WHEN TG_OP = 'DELETE' THEN OLD.set_id ELSE NEW.set_id END;
    v_comp varchar(20) := CASE WHEN TG_OP = 'DELETE' THEN OLD.comp_cd ELSE NEW.comp_cd END;
BEGIN
    IF TG_OP = 'UPDATE' THEN
        RAISE EXCEPTION 'ap rules are insert/delete only' USING ERRCODE = '55000', CONSTRAINT = 'ck_apqp_ap_rule_fixed';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM apqp_ap_set s WHERE s.set_id = v_set AND s.comp_cd = v_comp) THEN
        RAISE EXCEPTION 'ap set % is not of company %', v_set, v_comp USING ERRCODE = '23503', CONSTRAINT = 'fk_apqp_ap_rule_set_comp';
    END IF;
    IF EXISTS (SELECT 1 FROM apqp_fmea f WHERE f.ap_set_id = v_set AND f.status IN ('RELEASED', 'OBSOLETE')) THEN
        RAISE EXCEPTION 'ap set % is used by a released fmea', v_set USING ERRCODE = '55000', CONSTRAINT = 'ck_apqp_ap_set_released';
    END IF;
    RETURN CASE WHEN TG_OP = 'DELETE' THEN OLD ELSE NEW END;
END
$$;

-- ---------------------------------------------------------------------
-- 2. FMEA 머리
-- ---------------------------------------------------------------------
CREATE TABLE apqp_fmea (
    fmea_id       bigint         GENERATED ALWAYS AS IDENTITY,
    comp_cd       varchar(20)    NOT NULL,
    fmea_no       varchar(30)    NOT NULL,
    rev           integer        NOT NULL DEFAULT 1,
    fmea_type     varchar(6)     NOT NULL DEFAULT 'PFMEA',
    item_id       bigint         NOT NULL,
    routing_id    bigint,
    proj_id       bigint,
    title         varchar(200)   NOT NULL,
    scope         varchar(1000),
    team          varchar(500),
    drawing_rev   varchar(30),
    ap_set_id     bigint,
    status        varchar(10)    NOT NULL DEFAULT 'DRAFT',
    released_at   timestamptz,
    released_by   varchar(50),
    prev_fmea_id  bigint,
    row_ver       integer        NOT NULL DEFAULT 0,
    created_at    timestamptz    NOT NULL DEFAULT now(),
    created_by    varchar(50)    NOT NULL,
    updated_at    timestamptz    NOT NULL DEFAULT now(),
    updated_by    varchar(50)    NOT NULL,
    CONSTRAINT pk_apqp_fmea PRIMARY KEY (fmea_id),
    CONSTRAINT fk_apqp_fmea_comp FOREIGN KEY (comp_cd) REFERENCES sys_comp (comp_cd),
    CONSTRAINT fk_apqp_fmea_item FOREIGN KEY (item_id) REFERENCES mst_item (item_id),
    CONSTRAINT fk_apqp_fmea_routing FOREIGN KEY (routing_id) REFERENCES mst_routing (routing_id),
    CONSTRAINT fk_apqp_fmea_proj FOREIGN KEY (proj_id) REFERENCES pms_project (proj_id),
    CONSTRAINT fk_apqp_fmea_ap_set FOREIGN KEY (ap_set_id) REFERENCES apqp_ap_set (set_id),
    CONSTRAINT fk_apqp_fmea_prev FOREIGN KEY (prev_fmea_id) REFERENCES apqp_fmea (fmea_id),
    CONSTRAINT uq_apqp_fmea_rev UNIQUE (comp_cd, fmea_no, rev),
    CONSTRAINT ck_apqp_fmea_rev CHECK (rev BETWEEN 1 AND 999),
    CONSTRAINT ck_apqp_fmea_type CHECK (fmea_type IN ('PFMEA')),
    CONSTRAINT ck_apqp_fmea_title CHECK (length(btrim(title)) > 0),
    CONSTRAINT ck_apqp_fmea_status CHECK (status IN ('DRAFT', 'RELEASED', 'OBSOLETE')),
    CONSTRAINT ck_apqp_fmea_released CHECK (status = 'DRAFT' OR (released_at IS NOT NULL AND ap_set_id IS NOT NULL))
);
COMMENT ON TABLE apqp_fmea IS 'PFMEA 머리 — 번호 × 리비전. 품목 × 라우팅 리비전(없으면 글자 단계), PMS 프로젝트 선택. DRAFT → RELEASED(확정본 불변) → OBSOLETE(다음 리비전 확정 때)';
COMMENT ON COLUMN apqp_fmea.drawing_rev IS '근거 도면 리비전(글자) — 품목 리비전 표가 없어 기록만(docs/41 2장)';
CREATE UNIQUE INDEX uq_apqp_fmea_one_draft ON apqp_fmea (comp_cd, fmea_no) WHERE status = 'DRAFT';
CREATE UNIQUE INDEX uq_apqp_fmea_one_released ON apqp_fmea (comp_cd, fmea_no) WHERE status = 'RELEASED';
CREATE INDEX ix_apqp_fmea_item ON apqp_fmea (comp_cd, item_id);
CREATE INDEX ix_apqp_fmea_proj ON apqp_fmea (proj_id) WHERE proj_id IS NOT NULL;

CREATE FUNCTION hb_guard_apqp_fmea() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        IF OLD.status <> 'DRAFT' THEN
            RAISE EXCEPTION 'released fmea % cannot be deleted', OLD.fmea_id USING ERRCODE = '55000', CONSTRAINT = 'ck_apqp_fmea_fixed';
        END IF;
        RETURN OLD;
    END IF;
    IF TG_OP = 'UPDATE' THEN
        -- 확정본: 상태 RELEASED → OBSOLETE 만, 또는 상태 그대로 row_ver 만(확정본 조치 진행 때 머리 낡음 표시) — 나머지 칸 그대로
        IF OLD.status <> 'DRAFT' THEN
            IF NOT (NEW.status = OLD.status OR (OLD.status = 'RELEASED' AND NEW.status = 'OBSOLETE'))
               OR (to_jsonb(NEW) - ARRAY['status', 'row_ver', 'updated_at', 'updated_by'])
                  IS DISTINCT FROM (to_jsonb(OLD) - ARRAY['status', 'row_ver', 'updated_at', 'updated_by']) THEN
                RAISE EXCEPTION 'fmea % is %', OLD.fmea_id, OLD.status USING ERRCODE = '55000', CONSTRAINT = 'ck_apqp_fmea_fixed';
            END IF;
            RETURN NEW;
        END IF;
        IF NEW.status NOT IN ('DRAFT', 'RELEASED') OR NEW.fmea_no <> OLD.fmea_no OR NEW.rev <> OLD.rev
           OR NEW.comp_cd <> OLD.comp_cd THEN
            RAISE EXCEPTION 'fmea % transition', OLD.fmea_id USING ERRCODE = '55000', CONSTRAINT = 'ck_apqp_fmea_transition';
        END IF;
        IF NEW.status = 'RELEASED' AND EXISTS (SELECT 1 FROM apqp_fmea_risk r WHERE r.fmea_id = NEW.fmea_id AND r.ap IS NULL) THEN
            RAISE EXCEPTION 'fmea % has risks without ap', NEW.fmea_id USING ERRCODE = '23514', CONSTRAINT = 'ck_apqp_fmea_risk_ap';
        END IF;
    ELSIF NEW.status <> 'DRAFT' THEN
        RAISE EXCEPTION 'fmea starts as DRAFT' USING ERRCODE = '55000', CONSTRAINT = 'ck_apqp_fmea_transition';
    END IF;
    -- 같은 회사(FK 는 RLS 를 무시) + 라우팅은 머리 품목의 것
    IF NOT EXISTS (SELECT 1 FROM mst_item x WHERE x.item_id = NEW.item_id AND x.comp_cd = NEW.comp_cd) THEN
        RAISE EXCEPTION 'item % is not of company %', NEW.item_id, NEW.comp_cd USING ERRCODE = '23503', CONSTRAINT = 'fk_apqp_fmea_item_comp';
    END IF;
    IF NEW.routing_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM mst_routing x WHERE x.routing_id = NEW.routing_id
                                                     AND x.item_id = NEW.item_id AND x.comp_cd = NEW.comp_cd) THEN
        RAISE EXCEPTION 'routing % is not of item %', NEW.routing_id, NEW.item_id USING ERRCODE = '23503', CONSTRAINT = 'fk_apqp_fmea_routing_item';
    END IF;
    IF TG_OP = 'UPDATE' AND NEW.routing_id IS DISTINCT FROM OLD.routing_id
       AND EXISTS (SELECT 1 FROM apqp_fmea_node n WHERE n.fmea_id = NEW.fmea_id AND n.step_id IS NOT NULL) THEN
        RAISE EXCEPTION 'fmea % has steps of the routing', NEW.fmea_id USING ERRCODE = '23514', CONSTRAINT = 'ck_apqp_fmea_routing_used';
    END IF;
    IF NEW.proj_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM pms_project x WHERE x.proj_id = NEW.proj_id AND x.comp_cd = NEW.comp_cd) THEN
        RAISE EXCEPTION 'project % is not of company %', NEW.proj_id, NEW.comp_cd USING ERRCODE = '23503', CONSTRAINT = 'fk_apqp_fmea_proj_comp';
    END IF;
    IF NEW.ap_set_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM apqp_ap_set x WHERE x.set_id = NEW.ap_set_id AND x.comp_cd = NEW.comp_cd) THEN
        RAISE EXCEPTION 'ap set % is not of company %', NEW.ap_set_id, NEW.comp_cd USING ERRCODE = '23503', CONSTRAINT = 'fk_apqp_fmea_ap_set_comp';
    END IF;
    IF NEW.prev_fmea_id IS NOT NULL AND TG_OP = 'INSERT'
       AND NOT EXISTS (SELECT 1 FROM apqp_fmea x WHERE x.fmea_id = NEW.prev_fmea_id AND x.comp_cd = NEW.comp_cd AND x.fmea_no = NEW.fmea_no) THEN
        RAISE EXCEPTION 'previous fmea % is not of %', NEW.prev_fmea_id, NEW.fmea_no USING ERRCODE = '23503', CONSTRAINT = 'fk_apqp_fmea_prev_no';
    END IF;
    RETURN NEW;
END
$$;
CREATE TRIGGER tg_apqp_fmea_guard BEFORE INSERT OR UPDATE OR DELETE ON apqp_fmea FOR EACH ROW EXECUTE FUNCTION hb_guard_apqp_fmea();

CREATE TRIGGER tg_apqp_ap_rule_guard BEFORE INSERT OR UPDATE OR DELETE ON apqp_ap_rule FOR EACH ROW EXECUTE FUNCTION hb_guard_apqp_ap_rule();

-- ---------------------------------------------------------------------
-- 3. 구조 · 기능 · 고장 · 사슬 · 위험 · 조치
-- ---------------------------------------------------------------------
CREATE TABLE apqp_fmea_node (
    node_id     bigint         GENERATED ALWAYS AS IDENTITY,
    comp_cd     varchar(20)    NOT NULL,
    fmea_id     bigint         NOT NULL,
    parent_id   bigint,
    node_kind   varchar(8)     NOT NULL,
    seq         integer        NOT NULL DEFAULT 0,
    node_nm     varchar(200)   NOT NULL,
    step_id     bigint,
    m4          varchar(10),
    row_ver     integer        NOT NULL DEFAULT 0,
    created_at  timestamptz    NOT NULL DEFAULT now(),
    created_by  varchar(50)    NOT NULL,
    updated_at  timestamptz    NOT NULL DEFAULT now(),
    updated_by  varchar(50)    NOT NULL,
    CONSTRAINT pk_apqp_fmea_node PRIMARY KEY (node_id),
    CONSTRAINT fk_apqp_fmea_node_fmea FOREIGN KEY (fmea_id) REFERENCES apqp_fmea (fmea_id),
    CONSTRAINT fk_apqp_fmea_node_parent FOREIGN KEY (parent_id) REFERENCES apqp_fmea_node (node_id),
    CONSTRAINT fk_apqp_fmea_node_step FOREIGN KEY (step_id) REFERENCES mst_routing_step (step_id),
    CONSTRAINT ck_apqp_fmea_node_kind CHECK (node_kind IN ('ITEM', 'STEP', 'ELEMENT')),
    CONSTRAINT ck_apqp_fmea_node_nm CHECK (length(btrim(node_nm)) > 0),
    CONSTRAINT ck_apqp_fmea_node_shape CHECK (
        (node_kind = 'ITEM' AND parent_id IS NULL AND step_id IS NULL AND m4 IS NULL)
        OR (node_kind = 'STEP' AND parent_id IS NOT NULL AND m4 IS NULL)
        OR (node_kind = 'ELEMENT' AND parent_id IS NOT NULL AND step_id IS NULL
            AND m4 IN ('MAN', 'MACHINE', 'MATERIAL', 'METHOD', 'MEASURE', 'ENV')))
);
COMMENT ON TABLE apqp_fmea_node IS 'FMEA 구조 트리 — ITEM(공정 아이템, FMEA 마다 1개) › STEP(공정 단계, 라우팅 단계 선택) › ELEMENT(작업요소, 4M)';
CREATE UNIQUE INDEX uq_apqp_fmea_node_item ON apqp_fmea_node (fmea_id) WHERE node_kind = 'ITEM';
CREATE INDEX ix_apqp_fmea_node_fmea ON apqp_fmea_node (comp_cd, fmea_id);

CREATE TABLE apqp_fmea_func (
    func_id     bigint         GENERATED ALWAYS AS IDENTITY,
    comp_cd     varchar(20)    NOT NULL,
    fmea_id     bigint         NOT NULL,
    node_id     bigint         NOT NULL,
    seq         integer        NOT NULL DEFAULT 0,
    func_text   varchar(500)   NOT NULL,
    char_id     bigint,
    row_ver     integer        NOT NULL DEFAULT 0,
    created_at  timestamptz    NOT NULL DEFAULT now(),
    created_by  varchar(50)    NOT NULL,
    updated_at  timestamptz    NOT NULL DEFAULT now(),
    updated_by  varchar(50)    NOT NULL,
    CONSTRAINT pk_apqp_fmea_func PRIMARY KEY (func_id),
    CONSTRAINT fk_apqp_fmea_func_fmea FOREIGN KEY (fmea_id) REFERENCES apqp_fmea (fmea_id),
    CONSTRAINT fk_apqp_fmea_func_node FOREIGN KEY (node_id) REFERENCES apqp_fmea_node (node_id),
    CONSTRAINT fk_apqp_fmea_func_char FOREIGN KEY (char_id) REFERENCES qms_char (char_id),
    CONSTRAINT ck_apqp_fmea_func_text CHECK (length(btrim(func_text)) > 0)
);
COMMENT ON TABLE apqp_fmea_func IS '구조 요소의 기능·요구사항. 특성(qms_char)을 가리키면 제품 특성 = 머리 품목, 공정 특성 = 그 단계의 공정';
CREATE INDEX ix_apqp_fmea_func_fmea ON apqp_fmea_func (comp_cd, fmea_id);

CREATE TABLE apqp_fmea_fail (
    fail_id     bigint         GENERATED ALWAYS AS IDENTITY,
    comp_cd     varchar(20)    NOT NULL,
    fmea_id     bigint         NOT NULL,
    func_id     bigint         NOT NULL,
    fail_kind   varchar(2)     NOT NULL,
    seq         integer        NOT NULL DEFAULT 0,
    fail_text   varchar(500)   NOT NULL,
    severity    smallint,
    defect_id   bigint,
    row_ver     integer        NOT NULL DEFAULT 0,
    created_at  timestamptz    NOT NULL DEFAULT now(),
    created_by  varchar(50)    NOT NULL,
    updated_at  timestamptz    NOT NULL DEFAULT now(),
    updated_by  varchar(50)    NOT NULL,
    CONSTRAINT pk_apqp_fmea_fail PRIMARY KEY (fail_id),
    CONSTRAINT fk_apqp_fmea_fail_fmea FOREIGN KEY (fmea_id) REFERENCES apqp_fmea (fmea_id),
    CONSTRAINT fk_apqp_fmea_fail_func FOREIGN KEY (func_id) REFERENCES apqp_fmea_func (func_id),
    CONSTRAINT fk_apqp_fmea_fail_defect FOREIGN KEY (defect_id) REFERENCES qms_defect (defect_id),
    CONSTRAINT ck_apqp_fmea_fail_kind CHECK (fail_kind IN ('FE', 'FM', 'FC')),
    CONSTRAINT ck_apqp_fmea_fail_text CHECK (length(btrim(fail_text)) > 0),
    CONSTRAINT ck_apqp_fmea_fail_sev CHECK ((fail_kind = 'FE' AND severity BETWEEN 1 AND 10)
                                            OR (fail_kind <> 'FE' AND severity IS NULL)),
    CONSTRAINT ck_apqp_fmea_fail_defect CHECK (defect_id IS NULL OR fail_kind = 'FM')
);
COMMENT ON TABLE apqp_fmea_fail IS '고장 — FE(고장 영향, ITEM 기능, 심각도 1~10) / FM(고장 형태, STEP 기능, 불량 유형 선택) / FC(고장 원인, ELEMENT 기능)';
CREATE INDEX ix_apqp_fmea_fail_fmea ON apqp_fmea_fail (comp_cd, fmea_id);

CREATE TABLE apqp_fmea_chain (
    chain_id    bigint         GENERATED ALWAYS AS IDENTITY,
    comp_cd     varchar(20)    NOT NULL,
    fmea_id     bigint         NOT NULL,
    fm_id       bigint         NOT NULL,
    fe_id       bigint         NOT NULL,
    created_at  timestamptz    NOT NULL DEFAULT now(),
    created_by  varchar(50)    NOT NULL,
    CONSTRAINT pk_apqp_fmea_chain PRIMARY KEY (chain_id),
    CONSTRAINT fk_apqp_fmea_chain_fmea FOREIGN KEY (fmea_id) REFERENCES apqp_fmea (fmea_id),
    CONSTRAINT fk_apqp_fmea_chain_fm FOREIGN KEY (fm_id) REFERENCES apqp_fmea_fail (fail_id),
    CONSTRAINT fk_apqp_fmea_chain_fe FOREIGN KEY (fe_id) REFERENCES apqp_fmea_fail (fail_id),
    CONSTRAINT uq_apqp_fmea_chain UNIQUE (comp_cd, fm_id, fe_id)
);
COMMENT ON TABLE apqp_fmea_chain IS '고장사슬 FM → FE (N:M, 같은 FMEA). FM 의 심각도 = 연결 FE 심각도의 최댓값';

CREATE TABLE apqp_fmea_risk (
    risk_id      bigint         GENERATED ALWAYS AS IDENTITY,
    comp_cd      varchar(20)    NOT NULL,
    fmea_id      bigint         NOT NULL,
    fm_id        bigint         NOT NULL,
    fc_id        bigint         NOT NULL,
    prev_ctrl    varchar(500),
    det_ctrl     varchar(500),
    sev          smallint,
    occ          smallint,
    det          smallint,
    ap           char(1),
    special_cls  varchar(30),
    row_ver      integer        NOT NULL DEFAULT 0,
    created_at   timestamptz    NOT NULL DEFAULT now(),
    created_by   varchar(50)    NOT NULL,
    updated_at   timestamptz    NOT NULL DEFAULT now(),
    updated_by   varchar(50)    NOT NULL,
    CONSTRAINT pk_apqp_fmea_risk PRIMARY KEY (risk_id),
    CONSTRAINT fk_apqp_fmea_risk_fmea FOREIGN KEY (fmea_id) REFERENCES apqp_fmea (fmea_id),
    CONSTRAINT fk_apqp_fmea_risk_fm FOREIGN KEY (fm_id) REFERENCES apqp_fmea_fail (fail_id),
    CONSTRAINT fk_apqp_fmea_risk_fc FOREIGN KEY (fc_id) REFERENCES apqp_fmea_fail (fail_id),
    CONSTRAINT uq_apqp_fmea_risk UNIQUE (comp_cd, fm_id, fc_id),
    CONSTRAINT ck_apqp_fmea_risk_sev CHECK (sev IS NULL OR sev BETWEEN 1 AND 10),
    CONSTRAINT ck_apqp_fmea_risk_occ CHECK (occ IS NULL OR occ BETWEEN 1 AND 10),
    CONSTRAINT ck_apqp_fmea_risk_det CHECK (det IS NULL OR det BETWEEN 1 AND 10),
    CONSTRAINT ck_apqp_fmea_risk_ap CHECK (ap IS NULL OR ap IN ('H', 'M', 'L'))
);
COMMENT ON TABLE apqp_fmea_risk IS '위험 = (FM, FC) 한 쌍: 예방·검출 관리, O·D(입력), S(연결 FE 최대)·AP(규칙표) 는 서버가 계산해 저장. FC 는 FM 단계 아래 작업요소의 것';
COMMENT ON COLUMN apqp_fmea_risk.special_cls IS '특별특성 제안(공통코드 SPECIAL_CHAR) — 관리계획서(A2)로 넘길 표시';
CREATE INDEX ix_apqp_fmea_risk_fmea ON apqp_fmea_risk (comp_cd, fmea_id);

CREATE TABLE apqp_fmea_action (
    action_id      bigint         GENERATED ALWAYS AS IDENTITY,
    comp_cd        varchar(20)    NOT NULL,
    fmea_id        bigint         NOT NULL,
    risk_id        bigint         NOT NULL,
    action_kind    varchar(7)     NOT NULL,
    action_text    varchar(500)   NOT NULL,
    owner_user_id  bigint,
    due_date       date,
    status         varchar(10)    NOT NULL DEFAULT 'OPEN',
    done_on        date,
    evidence       varchar(500),
    new_occ        smallint,
    new_det        smallint,
    new_ap         char(1),
    row_ver        integer        NOT NULL DEFAULT 0,
    created_at     timestamptz    NOT NULL DEFAULT now(),
    created_by     varchar(50)    NOT NULL,
    updated_at     timestamptz    NOT NULL DEFAULT now(),
    updated_by     varchar(50)    NOT NULL,
    CONSTRAINT pk_apqp_fmea_action PRIMARY KEY (action_id),
    CONSTRAINT fk_apqp_fmea_action_fmea FOREIGN KEY (fmea_id) REFERENCES apqp_fmea (fmea_id),
    CONSTRAINT fk_apqp_fmea_action_risk FOREIGN KEY (risk_id) REFERENCES apqp_fmea_risk (risk_id),
    CONSTRAINT fk_apqp_fmea_action_owner FOREIGN KEY (owner_user_id) REFERENCES sys_user (user_id),
    CONSTRAINT ck_apqp_fmea_action_kind CHECK (action_kind IN ('PREVENT', 'DETECT')),
    CONSTRAINT ck_apqp_fmea_action_text CHECK (length(btrim(action_text)) > 0),
    CONSTRAINT ck_apqp_fmea_action_status CHECK (status IN ('OPEN', 'DONE', 'CANCELLED')),
    CONSTRAINT ck_apqp_fmea_action_done CHECK ((status = 'DONE') = (done_on IS NOT NULL)),
    CONSTRAINT ck_apqp_fmea_action_occ CHECK (new_occ IS NULL OR new_occ BETWEEN 1 AND 10),
    CONSTRAINT ck_apqp_fmea_action_det CHECK (new_det IS NULL OR new_det BETWEEN 1 AND 10),
    CONSTRAINT ck_apqp_fmea_action_ap CHECK (new_ap IS NULL OR new_ap IN ('H', 'M', 'L'))
);
COMMENT ON TABLE apqp_fmea_action IS '최적화 조치(예방·검출). 확정본에서도 상태·완료일·증빙·조치 뒤 O·D·AP 만 갱신(P7) — 같은 번호의 새 리비전 작성 중엔 새 리비전에서만';
CREATE INDEX ix_apqp_fmea_action_fmea ON apqp_fmea_action (comp_cd, fmea_id);
CREATE INDEX ix_apqp_fmea_action_owner ON apqp_fmea_action (owner_user_id) WHERE status = 'OPEN';

-- 하위 표 공통: 같은 회사의 FMEA, 작성 중(DRAFT)에만 바꾼다(조치는 아래 예외)
CREATE FUNCTION hb_apqp_fmea_editable(p_fmea bigint, p_comp varchar, p_action boolean) RETURNS void
    LANGUAGE plpgsql
AS $$
DECLARE
    v_status varchar(10);
    v_no     varchar(30);
BEGIN
    SELECT f.status, f.fmea_no INTO v_status, v_no FROM apqp_fmea f WHERE f.fmea_id = p_fmea AND f.comp_cd = p_comp;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'fmea % is not of company %', p_fmea, p_comp USING ERRCODE = '23503', CONSTRAINT = 'fk_apqp_fmea_child_comp';
    END IF;
    IF v_status = 'DRAFT' THEN
        RETURN;
    END IF;
    IF p_action AND v_status = 'RELEASED'
       AND NOT EXISTS (SELECT 1 FROM apqp_fmea d WHERE d.comp_cd = p_comp AND d.fmea_no = v_no AND d.status = 'DRAFT') THEN
        RETURN;
    END IF;
    RAISE EXCEPTION 'fmea % is %', p_fmea, v_status USING ERRCODE = '55000', CONSTRAINT = 'ck_apqp_fmea_fixed';
END
$$;

CREATE FUNCTION hb_guard_apqp_fmea_node() RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    v_parent_kind varchar(8);
    v_routing     bigint;
BEGIN
    IF TG_OP = 'DELETE' THEN
        PERFORM hb_apqp_fmea_editable(OLD.fmea_id, OLD.comp_cd, false);
        RETURN OLD;
    END IF;
    IF TG_OP = 'UPDATE' AND (NEW.fmea_id <> OLD.fmea_id OR NEW.node_kind <> OLD.node_kind OR NEW.comp_cd <> OLD.comp_cd) THEN
        RAISE EXCEPTION 'node % kind/fmea is fixed', OLD.node_id USING ERRCODE = '55000', CONSTRAINT = 'ck_apqp_fmea_node_fixed';
    END IF;
    PERFORM hb_apqp_fmea_editable(NEW.fmea_id, NEW.comp_cd, false);
    IF NEW.parent_id IS NOT NULL THEN
        SELECT p.node_kind INTO v_parent_kind FROM apqp_fmea_node p WHERE p.node_id = NEW.parent_id AND p.fmea_id = NEW.fmea_id;
        IF NOT FOUND OR (NEW.node_kind = 'STEP' AND v_parent_kind <> 'ITEM') OR (NEW.node_kind = 'ELEMENT' AND v_parent_kind <> 'STEP') THEN
            RAISE EXCEPTION 'node % parent % breaks ITEM > STEP > ELEMENT', NEW.node_id, NEW.parent_id
                USING ERRCODE = '23514', CONSTRAINT = 'ck_apqp_fmea_node_level';
        END IF;
    END IF;
    IF NEW.step_id IS NOT NULL AND (TG_OP = 'INSERT' OR NEW.step_id IS DISTINCT FROM OLD.step_id) THEN
        SELECT f.routing_id INTO v_routing FROM apqp_fmea f WHERE f.fmea_id = NEW.fmea_id;
        IF v_routing IS NULL OR NOT EXISTS (SELECT 1 FROM mst_routing_step s WHERE s.step_id = NEW.step_id
                                               AND s.routing_id = v_routing AND s.comp_cd = NEW.comp_cd) THEN
            RAISE EXCEPTION 'step % is not of the fmea routing', NEW.step_id USING ERRCODE = '23503', CONSTRAINT = 'fk_apqp_fmea_node_step_routing';
        END IF;
    END IF;
    RETURN NEW;
END
$$;
CREATE TRIGGER tg_apqp_fmea_node_guard BEFORE INSERT OR UPDATE OR DELETE ON apqp_fmea_node FOR EACH ROW EXECUTE FUNCTION hb_guard_apqp_fmea_node();

CREATE FUNCTION hb_guard_apqp_fmea_func() RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    v_kind   varchar(8);
    v_step   bigint;
    v_ctype  varchar(10);
    v_citem  bigint;
    v_cproc  bigint;
BEGIN
    IF TG_OP = 'DELETE' THEN
        PERFORM hb_apqp_fmea_editable(OLD.fmea_id, OLD.comp_cd, false);
        RETURN OLD;
    END IF;
    IF TG_OP = 'UPDATE' AND (NEW.fmea_id <> OLD.fmea_id OR NEW.node_id <> OLD.node_id OR NEW.comp_cd <> OLD.comp_cd) THEN
        RAISE EXCEPTION 'function % node is fixed', OLD.func_id USING ERRCODE = '55000', CONSTRAINT = 'ck_apqp_fmea_func_fixed';
    END IF;
    PERFORM hb_apqp_fmea_editable(NEW.fmea_id, NEW.comp_cd, false);
    SELECT n.node_kind, coalesce(n.step_id, p.step_id) INTO v_kind, v_step
      FROM apqp_fmea_node n LEFT JOIN apqp_fmea_node p ON p.node_id = n.parent_id
     WHERE n.node_id = NEW.node_id AND n.fmea_id = NEW.fmea_id;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'node % is not in fmea %', NEW.node_id, NEW.fmea_id USING ERRCODE = '23503', CONSTRAINT = 'fk_apqp_fmea_func_node_fmea';
    END IF;
    IF NEW.char_id IS NOT NULL AND (TG_OP = 'INSERT' OR NEW.char_id IS DISTINCT FROM OLD.char_id) THEN
        SELECT c.char_type, c.item_id, c.proc_id INTO v_ctype, v_citem, v_cproc
          FROM qms_char c WHERE c.char_id = NEW.char_id AND c.comp_cd = NEW.comp_cd;
        IF NOT FOUND THEN
            RAISE EXCEPTION 'char % is not of company %', NEW.char_id, NEW.comp_cd USING ERRCODE = '23503', CONSTRAINT = 'fk_apqp_fmea_func_char_comp';
        END IF;
        -- 제품 특성 = 머리 품목, 공정 특성 = 이 요소가 속한 라우팅 단계의 공정(단계를 가리킬 때)
        IF (v_ctype = 'PRODUCT' AND NOT EXISTS (SELECT 1 FROM apqp_fmea f WHERE f.fmea_id = NEW.fmea_id AND f.item_id = v_citem))
           OR (v_ctype = 'PROCESS' AND v_step IS NOT NULL
               AND NOT EXISTS (SELECT 1 FROM mst_routing_step s WHERE s.step_id = v_step AND s.proc_id = v_cproc)) THEN
            RAISE EXCEPTION 'char % does not belong to this item/step', NEW.char_id USING ERRCODE = '23514', CONSTRAINT = 'ck_apqp_fmea_func_char_target';
        END IF;
    END IF;
    RETURN NEW;
END
$$;
CREATE TRIGGER tg_apqp_fmea_func_guard BEFORE INSERT OR UPDATE OR DELETE ON apqp_fmea_func FOR EACH ROW EXECUTE FUNCTION hb_guard_apqp_fmea_func();

CREATE FUNCTION hb_guard_apqp_fmea_fail() RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    v_kind varchar(8);
BEGIN
    IF TG_OP = 'DELETE' THEN
        PERFORM hb_apqp_fmea_editable(OLD.fmea_id, OLD.comp_cd, false);
        RETURN OLD;
    END IF;
    IF TG_OP = 'UPDATE' AND (NEW.fmea_id <> OLD.fmea_id OR NEW.func_id <> OLD.func_id OR NEW.fail_kind <> OLD.fail_kind
                              OR NEW.comp_cd <> OLD.comp_cd) THEN
        RAISE EXCEPTION 'failure % kind/function is fixed', OLD.fail_id USING ERRCODE = '55000', CONSTRAINT = 'ck_apqp_fmea_fail_fixed';
    END IF;
    PERFORM hb_apqp_fmea_editable(NEW.fmea_id, NEW.comp_cd, false);
    SELECT n.node_kind INTO v_kind FROM apqp_fmea_func f JOIN apqp_fmea_node n ON n.node_id = f.node_id
     WHERE f.func_id = NEW.func_id AND f.fmea_id = NEW.fmea_id;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'function % is not in fmea %', NEW.func_id, NEW.fmea_id USING ERRCODE = '23503', CONSTRAINT = 'fk_apqp_fmea_fail_func_fmea';
    END IF;
    -- 구조 수준: FE = ITEM, FM = STEP, FC = ELEMENT
    IF (NEW.fail_kind = 'FE' AND v_kind <> 'ITEM') OR (NEW.fail_kind = 'FM' AND v_kind <> 'STEP')
       OR (NEW.fail_kind = 'FC' AND v_kind <> 'ELEMENT') THEN
        RAISE EXCEPTION '% does not belong to a % node', NEW.fail_kind, v_kind USING ERRCODE = '23514', CONSTRAINT = 'ck_apqp_fmea_fail_level';
    END IF;
    IF NEW.defect_id IS NOT NULL AND (TG_OP = 'INSERT' OR NEW.defect_id IS DISTINCT FROM OLD.defect_id)
       AND NOT EXISTS (SELECT 1 FROM qms_defect d WHERE d.defect_id = NEW.defect_id AND d.comp_cd = NEW.comp_cd) THEN
        RAISE EXCEPTION 'defect % is not of company %', NEW.defect_id, NEW.comp_cd USING ERRCODE = '23503', CONSTRAINT = 'fk_apqp_fmea_fail_defect_comp';
    END IF;
    RETURN NEW;
END
$$;
CREATE TRIGGER tg_apqp_fmea_fail_guard BEFORE INSERT OR UPDATE OR DELETE ON apqp_fmea_fail FOR EACH ROW EXECUTE FUNCTION hb_guard_apqp_fmea_fail();

CREATE FUNCTION hb_guard_apqp_fmea_chain() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        PERFORM hb_apqp_fmea_editable(OLD.fmea_id, OLD.comp_cd, false);
        RETURN OLD;
    END IF;
    IF TG_OP = 'UPDATE' THEN
        RAISE EXCEPTION 'chains are insert/delete only' USING ERRCODE = '55000', CONSTRAINT = 'ck_apqp_fmea_chain_fixed';
    END IF;
    PERFORM hb_apqp_fmea_editable(NEW.fmea_id, NEW.comp_cd, false);
    IF NOT EXISTS (SELECT 1 FROM apqp_fmea_fail x WHERE x.fail_id = NEW.fm_id AND x.fmea_id = NEW.fmea_id AND x.fail_kind = 'FM')
       OR NOT EXISTS (SELECT 1 FROM apqp_fmea_fail x WHERE x.fail_id = NEW.fe_id AND x.fmea_id = NEW.fmea_id AND x.fail_kind = 'FE') THEN
        RAISE EXCEPTION 'chain must link FM % to FE % of fmea %', NEW.fm_id, NEW.fe_id, NEW.fmea_id
            USING ERRCODE = '23514', CONSTRAINT = 'ck_apqp_fmea_chain_kind';
    END IF;
    RETURN NEW;
END
$$;
CREATE TRIGGER tg_apqp_fmea_chain_guard BEFORE INSERT OR UPDATE OR DELETE ON apqp_fmea_chain FOR EACH ROW EXECUTE FUNCTION hb_guard_apqp_fmea_chain();

CREATE FUNCTION hb_guard_apqp_fmea_risk() RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    v_fm_node bigint;
BEGIN
    IF TG_OP = 'DELETE' THEN
        PERFORM hb_apqp_fmea_editable(OLD.fmea_id, OLD.comp_cd, false);
        RETURN OLD;
    END IF;
    IF TG_OP = 'UPDATE' AND (NEW.fmea_id <> OLD.fmea_id OR NEW.fm_id <> OLD.fm_id OR NEW.fc_id <> OLD.fc_id OR NEW.comp_cd <> OLD.comp_cd) THEN
        RAISE EXCEPTION 'risk % pair is fixed', OLD.risk_id USING ERRCODE = '55000', CONSTRAINT = 'ck_apqp_fmea_risk_fixed';
    END IF;
    PERFORM hb_apqp_fmea_editable(NEW.fmea_id, NEW.comp_cd, false);
    SELECT f.node_id INTO v_fm_node FROM apqp_fmea_fail x JOIN apqp_fmea_func f ON f.func_id = x.func_id
     WHERE x.fail_id = NEW.fm_id AND x.fmea_id = NEW.fmea_id AND x.fail_kind = 'FM';
    -- FC 는 FM 단계 바로 아래 작업요소의 기능에 달린 것(고장사슬 = 이 단계의 원인)
    IF NOT FOUND OR NOT EXISTS (SELECT 1 FROM apqp_fmea_fail x JOIN apqp_fmea_func f ON f.func_id = x.func_id
                                  JOIN apqp_fmea_node n ON n.node_id = f.node_id
                                 WHERE x.fail_id = NEW.fc_id AND x.fmea_id = NEW.fmea_id AND x.fail_kind = 'FC'
                                   AND n.parent_id = v_fm_node) THEN
        RAISE EXCEPTION 'risk must pair FM % with FC % under its step', NEW.fm_id, NEW.fc_id
            USING ERRCODE = '23514', CONSTRAINT = 'ck_apqp_fmea_risk_pair';
    END IF;
    IF NEW.special_cls IS NOT NULL AND (TG_OP = 'INSERT' OR NEW.special_cls IS DISTINCT FROM OLD.special_cls)
       AND NOT hb_code_usable(NEW.comp_cd, 'SPECIAL_CHAR', NEW.special_cls) THEN
        RAISE EXCEPTION 'special class % is not usable', NEW.special_cls USING ERRCODE = '23514', CONSTRAINT = 'ck_apqp_fmea_risk_special';
    END IF;
    RETURN NEW;
END
$$;
CREATE TRIGGER tg_apqp_fmea_risk_guard BEFORE INSERT OR UPDATE OR DELETE ON apqp_fmea_risk FOR EACH ROW EXECUTE FUNCTION hb_guard_apqp_fmea_risk();

CREATE FUNCTION hb_guard_apqp_fmea_action() RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    v_status varchar(10);
BEGIN
    IF TG_OP = 'DELETE' THEN
        PERFORM hb_apqp_fmea_editable(OLD.fmea_id, OLD.comp_cd, false);
        RETURN OLD;
    END IF;
    IF TG_OP = 'UPDATE' AND (NEW.fmea_id <> OLD.fmea_id OR NEW.risk_id <> OLD.risk_id OR NEW.comp_cd <> OLD.comp_cd) THEN
        RAISE EXCEPTION 'action % risk is fixed', OLD.action_id USING ERRCODE = '55000', CONSTRAINT = 'ck_apqp_fmea_action_fixed';
    END IF;
    SELECT f.status INTO v_status FROM apqp_fmea f WHERE f.fmea_id = NEW.fmea_id AND f.comp_cd = NEW.comp_cd;
    -- 확정본: 이미 있는 조치의 진행 칸만(내용·종류·담당·기한은 그대로) — 새 조치는 작성 중 리비전에서
    IF v_status = 'RELEASED' AND (TG_OP = 'INSERT'
        OR NEW.action_kind <> OLD.action_kind OR NEW.action_text <> OLD.action_text
        OR NEW.owner_user_id IS DISTINCT FROM OLD.owner_user_id OR NEW.due_date IS DISTINCT FROM OLD.due_date) THEN
        RAISE EXCEPTION 'fmea % is RELEASED — only progress of an action', NEW.fmea_id USING ERRCODE = '55000', CONSTRAINT = 'ck_apqp_fmea_fixed';
    END IF;
    PERFORM hb_apqp_fmea_editable(NEW.fmea_id, NEW.comp_cd, true);
    IF TG_OP = 'INSERT' OR NEW.risk_id <> OLD.risk_id THEN
        IF NOT EXISTS (SELECT 1 FROM apqp_fmea_risk r WHERE r.risk_id = NEW.risk_id AND r.fmea_id = NEW.fmea_id) THEN
            RAISE EXCEPTION 'risk % is not in fmea %', NEW.risk_id, NEW.fmea_id USING ERRCODE = '23503', CONSTRAINT = 'fk_apqp_fmea_action_risk_fmea';
        END IF;
    END IF;
    IF NEW.owner_user_id IS NOT NULL AND (TG_OP = 'INSERT' OR NEW.owner_user_id IS DISTINCT FROM OLD.owner_user_id)
       AND NOT EXISTS (SELECT 1 FROM sys_user u WHERE u.user_id = NEW.owner_user_id AND u.comp_cd = NEW.comp_cd) THEN
        RAISE EXCEPTION 'user % is not of company %', NEW.owner_user_id, NEW.comp_cd USING ERRCODE = '23503', CONSTRAINT = 'fk_apqp_fmea_action_owner_comp';
    END IF;
    RETURN NEW;
END
$$;
CREATE TRIGGER tg_apqp_fmea_action_guard BEFORE INSERT OR UPDATE OR DELETE ON apqp_fmea_action FOR EACH ROW EXECUTE FUNCTION hb_guard_apqp_fmea_action();

-- ---------------------------------------------------------------------
-- 4. 감사 · RLS · 권한 · 화면 · 보존
-- ---------------------------------------------------------------------
CREATE TRIGGER tg_audit AFTER INSERT OR UPDATE OR DELETE ON apqp_ap_set FOR EACH ROW EXECUTE FUNCTION hb_audit('set_id', '', '');
CREATE TRIGGER tg_audit AFTER INSERT OR UPDATE OR DELETE ON apqp_ap_rule FOR EACH ROW EXECUTE FUNCTION hb_audit('rule_id', '', '');
CREATE TRIGGER tg_audit AFTER INSERT OR UPDATE OR DELETE ON apqp_fmea FOR EACH ROW EXECUTE FUNCTION hb_audit('fmea_id', '', '');
CREATE TRIGGER tg_audit AFTER INSERT OR UPDATE OR DELETE ON apqp_fmea_node FOR EACH ROW EXECUTE FUNCTION hb_audit('node_id', '', '');
CREATE TRIGGER tg_audit AFTER INSERT OR UPDATE OR DELETE ON apqp_fmea_func FOR EACH ROW EXECUTE FUNCTION hb_audit('func_id', '', '');
CREATE TRIGGER tg_audit AFTER INSERT OR UPDATE OR DELETE ON apqp_fmea_fail FOR EACH ROW EXECUTE FUNCTION hb_audit('fail_id', '', '');
CREATE TRIGGER tg_audit AFTER INSERT OR UPDATE OR DELETE ON apqp_fmea_chain FOR EACH ROW EXECUTE FUNCTION hb_audit('chain_id', '', '');
CREATE TRIGGER tg_audit AFTER INSERT OR UPDATE OR DELETE ON apqp_fmea_risk FOR EACH ROW EXECUTE FUNCTION hb_audit('risk_id', '', '');
CREATE TRIGGER tg_audit AFTER INSERT OR UPDATE OR DELETE ON apqp_fmea_action FOR EACH ROW EXECUTE FUNCTION hb_audit('action_id', '', '');

ALTER TABLE apqp_ap_set ENABLE ROW LEVEL SECURITY;
ALTER TABLE apqp_ap_set FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON apqp_ap_set USING (comp_cd = hb_current_comp()) WITH CHECK (comp_cd = hb_current_comp());
ALTER TABLE apqp_ap_rule ENABLE ROW LEVEL SECURITY;
ALTER TABLE apqp_ap_rule FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON apqp_ap_rule USING (comp_cd = hb_current_comp()) WITH CHECK (comp_cd = hb_current_comp());
ALTER TABLE apqp_fmea ENABLE ROW LEVEL SECURITY;
ALTER TABLE apqp_fmea FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON apqp_fmea USING (comp_cd = hb_current_comp()) WITH CHECK (comp_cd = hb_current_comp());
ALTER TABLE apqp_fmea_node ENABLE ROW LEVEL SECURITY;
ALTER TABLE apqp_fmea_node FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON apqp_fmea_node USING (comp_cd = hb_current_comp()) WITH CHECK (comp_cd = hb_current_comp());
ALTER TABLE apqp_fmea_func ENABLE ROW LEVEL SECURITY;
ALTER TABLE apqp_fmea_func FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON apqp_fmea_func USING (comp_cd = hb_current_comp()) WITH CHECK (comp_cd = hb_current_comp());
ALTER TABLE apqp_fmea_fail ENABLE ROW LEVEL SECURITY;
ALTER TABLE apqp_fmea_fail FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON apqp_fmea_fail USING (comp_cd = hb_current_comp()) WITH CHECK (comp_cd = hb_current_comp());
ALTER TABLE apqp_fmea_chain ENABLE ROW LEVEL SECURITY;
ALTER TABLE apqp_fmea_chain FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON apqp_fmea_chain USING (comp_cd = hb_current_comp()) WITH CHECK (comp_cd = hb_current_comp());
ALTER TABLE apqp_fmea_risk ENABLE ROW LEVEL SECURITY;
ALTER TABLE apqp_fmea_risk FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON apqp_fmea_risk USING (comp_cd = hb_current_comp()) WITH CHECK (comp_cd = hb_current_comp());
ALTER TABLE apqp_fmea_action ENABLE ROW LEVEL SECURITY;
ALTER TABLE apqp_fmea_action FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON apqp_fmea_action USING (comp_cd = hb_current_comp()) WITH CHECK (comp_cd = hb_current_comp());

-- 규칙·사슬은 추가·삭제만, 규칙표는 지우지 않는다(사용 안 함으로). 작성 중 FMEA·하위 행은 트리거 조건 안에서 지울 수 있다
REVOKE UPDATE, TRUNCATE ON apqp_ap_rule, apqp_fmea_chain FROM ${app_role};
REVOKE DELETE, TRUNCATE ON apqp_ap_set FROM ${app_role};
REVOKE TRUNCATE ON apqp_fmea, apqp_fmea_node, apqp_fmea_func, apqp_fmea_fail, apqp_fmea_risk, apqp_fmea_action FROM ${app_role};

INSERT INTO sys_prog (prog_cd, prog_nm_key, view_path, module_cd, actions, sort_no) VALUES
    -- APPROVE = 확정(리비전 확정, P8 — 결재선은 A3)
    ('apqp-fmea', 'prog.apqpFmea', 'apqp/fmea-mgt', 'PMS', ARRAY['SEARCH','SAVE','APPROVE']::varchar(20)[], 846),
    ('apqp-ap',   'prog.apqpAp',   'apqp/ap-mgt',   'PMS', ARRAY['SEARCH','SAVE']::varchar(20)[], 847);
INSERT INTO sys_retention_table (table_nm, class_cd) VALUES
    ('apqp_ap_set', 'MASTER'), ('apqp_ap_rule', 'MASTER'),
    ('apqp_fmea', 'QUALITY_RECORD'), ('apqp_fmea_node', 'QUALITY_RECORD'), ('apqp_fmea_func', 'QUALITY_RECORD'),
    ('apqp_fmea_fail', 'QUALITY_RECORD'), ('apqp_fmea_chain', 'QUALITY_RECORD'), ('apqp_fmea_risk', 'QUALITY_RECORD'),
    ('apqp_fmea_action', 'QUALITY_RECORD');
