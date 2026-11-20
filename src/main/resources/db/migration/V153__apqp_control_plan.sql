-- =====================================================================
-- P6 APQP A2 — 관리계획서 (docs/41 5장, 결정 C1~C8 — 2026-10-23 사용자 확인: 모두 추천안)
-- 임시 번호(docs/29 2.2) — 상위 세션이 병합 때 파일 이름만 바꾼다
--
-- 근거(공개 요지만): AIAG Control Plan 1판(2024)·APQP 3판 공개 소개 — 관리계획서는 단계(시작품·양산 전·양산)마다,
--       행 = 공정 단계 × 특성 × 관리 방법, PFMEA 와 연결. IATF 16949 8.5.1.1 공개 요지 — 특별특성 포함·개정 사유.
--       AIAG 양식의 칸 배치·문구는 복사하지 않는다
--
-- - apqp_cp: 머리(번호 × 리비전, 단계는 번호마다 고정 C1). 품목 × 라우팅 리비전, 근거 PFMEA 확정 리비전(C2, 선택). 확정본 불변
-- - apqp_cp_line: 행(단계 = 라우팅 단계 또는 글자, 특성 = qms_char 또는 글자 C3, 관리 방법·평가 방법·계측기 종류·검사 유형·
--   샘플(글자 + 수 C4)·주기(검사계획과 같은 값)·반응계획·근거 위험행). 규격·특별특성은 확정 때 스냅샷
-- 남의 표: 없음(mst_item·mst_routing(_step)·qms_char·pms_project 를 FK 로 가리키기만). 검사계획 대조(C5·C6)는 QMS 계약 뒤
-- =====================================================================

INSERT INTO sys_code_grp (grp_cd, grp_nm_key, comp_ext_yn) VALUES
    ('APQP_CP_PHASE',    'code.grp.APQP_CP_PHASE',    'N'),
    ('APQP_CTRL_METHOD', 'code.grp.APQP_CTRL_METHOD', 'N');
INSERT INTO sys_code (grp_cd, code, code_nm_key, sort_no) VALUES
    ('APQP_CP_PHASE', 'PROTO',      'code.APQP_CP_PHASE.PROTO',      1),
    ('APQP_CP_PHASE', 'PRE_LAUNCH', 'code.APQP_CP_PHASE.PRE_LAUNCH', 2),
    ('APQP_CP_PHASE', 'PRODUCTION', 'code.APQP_CP_PHASE.PRODUCTION', 3),
    ('APQP_CTRL_METHOD', 'INSPECT',      'code.APQP_CTRL_METHOD.INSPECT',      1),
    ('APQP_CTRL_METHOD', 'SPC',          'code.APQP_CTRL_METHOD.SPC',          2),
    ('APQP_CTRL_METHOD', 'ERROR_PROOF',  'code.APQP_CTRL_METHOD.ERROR_PROOF',  3),
    ('APQP_CTRL_METHOD', 'SETUP_VERIFY', 'code.APQP_CTRL_METHOD.SETUP_VERIFY', 4),
    ('APQP_CTRL_METHOD', 'WORK_STD',     'code.APQP_CTRL_METHOD.WORK_STD',     5),
    ('APQP_CTRL_METHOD', 'OTHER',        'code.APQP_CTRL_METHOD.OTHER',        6);

-- ---------------------------------------------------------------------
-- 1. 관리계획서 머리
-- ---------------------------------------------------------------------
CREATE TABLE apqp_cp (
    cp_id          bigint         GENERATED ALWAYS AS IDENTITY,
    comp_cd        varchar(20)    NOT NULL,
    cp_no          varchar(30)    NOT NULL,
    rev            integer        NOT NULL DEFAULT 1,
    phase          varchar(12)    NOT NULL,
    item_id        bigint         NOT NULL,
    routing_id     bigint,
    fmea_id        bigint,
    proj_id        bigint,
    title          varchar(200)   NOT NULL,
    team           varchar(500),
    change_reason  varchar(500),
    status         varchar(10)    NOT NULL DEFAULT 'DRAFT',
    released_at    timestamptz,
    released_by    varchar(50),
    prev_cp_id     bigint,
    row_ver        integer        NOT NULL DEFAULT 0,
    created_at     timestamptz    NOT NULL DEFAULT now(),
    created_by     varchar(50)    NOT NULL,
    updated_at     timestamptz    NOT NULL DEFAULT now(),
    updated_by     varchar(50)    NOT NULL,
    CONSTRAINT pk_apqp_cp PRIMARY KEY (cp_id),
    CONSTRAINT fk_apqp_cp_comp FOREIGN KEY (comp_cd) REFERENCES sys_comp (comp_cd),
    CONSTRAINT fk_apqp_cp_item FOREIGN KEY (item_id) REFERENCES mst_item (item_id),
    CONSTRAINT fk_apqp_cp_routing FOREIGN KEY (routing_id) REFERENCES mst_routing (routing_id),
    CONSTRAINT fk_apqp_cp_fmea FOREIGN KEY (fmea_id) REFERENCES apqp_fmea (fmea_id),
    CONSTRAINT fk_apqp_cp_proj FOREIGN KEY (proj_id) REFERENCES pms_project (proj_id),
    CONSTRAINT fk_apqp_cp_prev FOREIGN KEY (prev_cp_id) REFERENCES apqp_cp (cp_id),
    CONSTRAINT uq_apqp_cp_rev UNIQUE (comp_cd, cp_no, rev),
    CONSTRAINT ck_apqp_cp_rev CHECK (rev BETWEEN 1 AND 999),
    CONSTRAINT ck_apqp_cp_phase CHECK (phase IN ('PROTO', 'PRE_LAUNCH', 'PRODUCTION')),
    CONSTRAINT ck_apqp_cp_title CHECK (length(btrim(title)) > 0),
    CONSTRAINT ck_apqp_cp_status CHECK (status IN ('DRAFT', 'RELEASED', 'OBSOLETE')),
    CONSTRAINT ck_apqp_cp_released CHECK (status = 'DRAFT' OR released_at IS NOT NULL),
    -- 개정(rev 2 이상)은 확정 때 사유 필수(IATF 8.5.1.1 공개 요지)
    CONSTRAINT ck_apqp_cp_reason CHECK (status = 'DRAFT' OR rev = 1 OR length(btrim(coalesce(change_reason, ''))) > 0)
);
COMMENT ON TABLE apqp_cp IS '관리계획서 머리 — 번호 × 리비전, 단계는 번호마다 고정(다음 단계 = 새 번호로 복사, docs/41 C1). 근거 PFMEA = 확정 리비전 하나(C2)';
COMMENT ON COLUMN apqp_cp.change_reason IS '개정 사유 — rev 2 이상 확정 때 필수';
CREATE UNIQUE INDEX uq_apqp_cp_one_draft ON apqp_cp (comp_cd, cp_no) WHERE status = 'DRAFT';
CREATE UNIQUE INDEX uq_apqp_cp_one_released ON apqp_cp (comp_cd, cp_no) WHERE status = 'RELEASED';
CREATE INDEX ix_apqp_cp_item ON apqp_cp (comp_cd, item_id);
CREATE INDEX ix_apqp_cp_fmea ON apqp_cp (fmea_id) WHERE fmea_id IS NOT NULL;

CREATE FUNCTION hb_guard_apqp_cp() RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    v_fmea_item    bigint;
    v_fmea_routing bigint;
    v_fmea_status  varchar(10);
BEGIN
    IF TG_OP = 'DELETE' THEN
        IF OLD.status <> 'DRAFT' THEN
            RAISE EXCEPTION 'released control plan % cannot be deleted', OLD.cp_id USING ERRCODE = '55000', CONSTRAINT = 'ck_apqp_cp_fixed';
        END IF;
        RETURN OLD;
    END IF;
    IF TG_OP = 'UPDATE' THEN
        -- 확정본: RELEASED → OBSOLETE 만(나머지 칸 그대로)
        IF OLD.status <> 'DRAFT' THEN
            IF NOT (NEW.status = OLD.status OR (OLD.status = 'RELEASED' AND NEW.status = 'OBSOLETE'))
               OR (to_jsonb(NEW) - ARRAY['status', 'row_ver', 'updated_at', 'updated_by'])
                  IS DISTINCT FROM (to_jsonb(OLD) - ARRAY['status', 'row_ver', 'updated_at', 'updated_by']) THEN
                RAISE EXCEPTION 'control plan % is %', OLD.cp_id, OLD.status USING ERRCODE = '55000', CONSTRAINT = 'ck_apqp_cp_fixed';
            END IF;
            RETURN NEW;
        END IF;
        IF NEW.status NOT IN ('DRAFT', 'RELEASED') OR NEW.cp_no <> OLD.cp_no OR NEW.rev <> OLD.rev OR NEW.phase <> OLD.phase
           OR NEW.item_id <> OLD.item_id OR NEW.comp_cd <> OLD.comp_cd THEN
            RAISE EXCEPTION 'control plan % transition', OLD.cp_id USING ERRCODE = '55000', CONSTRAINT = 'ck_apqp_cp_transition';
        END IF;
        -- 확정: 행이 하나 이상, 특성을 가리키는 행은 규격 스냅샷이 있어야
        IF NEW.status = 'RELEASED' THEN
            IF NOT EXISTS (SELECT 1 FROM apqp_cp_line l WHERE l.cp_id = NEW.cp_id) THEN
                RAISE EXCEPTION 'control plan % has no lines', NEW.cp_id USING ERRCODE = '23514', CONSTRAINT = 'ck_apqp_cp_no_lines';
            END IF;
            IF EXISTS (SELECT 1 FROM apqp_cp_line l WHERE l.cp_id = NEW.cp_id AND l.char_id IS NOT NULL AND l.data_type IS NULL) THEN
                RAISE EXCEPTION 'control plan % lines without snapshot', NEW.cp_id USING ERRCODE = '23514', CONSTRAINT = 'ck_apqp_cp_snapshot';
            END IF;
        END IF;
    ELSIF NEW.status <> 'DRAFT' THEN
        RAISE EXCEPTION 'control plan starts as DRAFT' USING ERRCODE = '55000', CONSTRAINT = 'ck_apqp_cp_transition';
    END IF;
    -- 같은 회사(FK 는 RLS 를 무시) + 라우팅은 머리 품목의 것
    IF NOT EXISTS (SELECT 1 FROM mst_item x WHERE x.item_id = NEW.item_id AND x.comp_cd = NEW.comp_cd) THEN
        RAISE EXCEPTION 'item % is not of company %', NEW.item_id, NEW.comp_cd USING ERRCODE = '23503', CONSTRAINT = 'fk_apqp_cp_item_comp';
    END IF;
    IF NEW.routing_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM mst_routing x WHERE x.routing_id = NEW.routing_id
                                                     AND x.item_id = NEW.item_id AND x.comp_cd = NEW.comp_cd) THEN
        RAISE EXCEPTION 'routing % is not of item %', NEW.routing_id, NEW.item_id USING ERRCODE = '23503', CONSTRAINT = 'fk_apqp_cp_routing_item';
    END IF;
    -- 근거 PFMEA: 같은 회사·같은 품목·확정된 것, 라우팅이 같아야(새로 가리킬 때는 RELEASED 만)
    IF NEW.fmea_id IS NOT NULL THEN
        SELECT f.item_id, f.routing_id, f.status INTO v_fmea_item, v_fmea_routing, v_fmea_status
          FROM apqp_fmea f WHERE f.fmea_id = NEW.fmea_id AND f.comp_cd = NEW.comp_cd;
        IF NOT FOUND OR v_fmea_item <> NEW.item_id THEN
            RAISE EXCEPTION 'fmea % is not of item %', NEW.fmea_id, NEW.item_id USING ERRCODE = '23503', CONSTRAINT = 'fk_apqp_cp_fmea_item';
        END IF;
        IF v_fmea_routing IS DISTINCT FROM NEW.routing_id THEN
            RAISE EXCEPTION 'fmea % routing differs', NEW.fmea_id USING ERRCODE = '23514', CONSTRAINT = 'ck_apqp_cp_fmea_routing';
        END IF;
        IF (TG_OP = 'INSERT' OR NEW.fmea_id IS DISTINCT FROM OLD.fmea_id) AND v_fmea_status <> 'RELEASED' THEN
            RAISE EXCEPTION 'fmea % is %', NEW.fmea_id, v_fmea_status USING ERRCODE = '23514', CONSTRAINT = 'ck_apqp_cp_fmea_released';
        END IF;
    END IF;
    IF TG_OP = 'UPDATE' AND NEW.routing_id IS DISTINCT FROM OLD.routing_id
       AND EXISTS (SELECT 1 FROM apqp_cp_line l WHERE l.cp_id = NEW.cp_id AND l.step_id IS NOT NULL) THEN
        RAISE EXCEPTION 'control plan % has steps of the routing', NEW.cp_id USING ERRCODE = '23514', CONSTRAINT = 'ck_apqp_cp_routing_used';
    END IF;
    IF TG_OP = 'UPDATE' AND NEW.fmea_id IS DISTINCT FROM OLD.fmea_id
       AND EXISTS (SELECT 1 FROM apqp_cp_line l WHERE l.cp_id = NEW.cp_id AND l.risk_id IS NOT NULL) THEN
        RAISE EXCEPTION 'control plan % has risks of the fmea', NEW.cp_id USING ERRCODE = '23514', CONSTRAINT = 'ck_apqp_cp_fmea_used';
    END IF;
    IF NEW.proj_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM pms_project x WHERE x.proj_id = NEW.proj_id AND x.comp_cd = NEW.comp_cd) THEN
        RAISE EXCEPTION 'project % is not of company %', NEW.proj_id, NEW.comp_cd USING ERRCODE = '23503', CONSTRAINT = 'fk_apqp_cp_proj_comp';
    END IF;
    IF NEW.prev_cp_id IS NOT NULL AND TG_OP = 'INSERT'
       AND NOT EXISTS (SELECT 1 FROM apqp_cp x WHERE x.cp_id = NEW.prev_cp_id AND x.comp_cd = NEW.comp_cd AND x.item_id = NEW.item_id) THEN
        RAISE EXCEPTION 'previous control plan % is not of item %', NEW.prev_cp_id, NEW.item_id USING ERRCODE = '23503', CONSTRAINT = 'fk_apqp_cp_prev_item';
    END IF;
    RETURN NEW;
END
$$;
CREATE TRIGGER tg_apqp_cp_guard BEFORE INSERT OR UPDATE OR DELETE ON apqp_cp FOR EACH ROW EXECUTE FUNCTION hb_guard_apqp_cp();

-- ---------------------------------------------------------------------
-- 2. 관리계획서 행
-- ---------------------------------------------------------------------
CREATE TABLE apqp_cp_line (
    line_id        bigint          GENERATED ALWAYS AS IDENTITY,
    comp_cd        varchar(20)     NOT NULL,
    cp_id          bigint          NOT NULL,
    line_no        integer         NOT NULL,
    step_id        bigint,
    step_text      varchar(200),
    char_id        bigint,
    char_text      varchar(200),
    ctrl_method    varchar(14)     NOT NULL,
    eval_method    varchar(500),
    gauge_class    varchar(30),
    insp_type      varchar(10),
    sample_n       integer,
    sample_text    varchar(100),
    freq_type      varchar(12),
    freq_n         integer,
    reaction_plan  varchar(500),
    risk_id        bigint,
    -- 확정 때 스냅샷 (작성 중엔 비어 있고 화면은 특성을 따라간다 — 검사계획 행과 같은 방식)
    special_cls    varchar(30),
    nominal        numeric(18,6),
    lsl            numeric(18,6),
    usl            numeric(18,6),
    unit_nm        varchar(20),
    data_type      varchar(10),
    row_ver        integer         NOT NULL DEFAULT 0,
    created_at     timestamptz     NOT NULL DEFAULT now(),
    created_by     varchar(50)     NOT NULL,
    updated_at     timestamptz     NOT NULL DEFAULT now(),
    updated_by     varchar(50)     NOT NULL,
    CONSTRAINT pk_apqp_cp_line PRIMARY KEY (line_id),
    CONSTRAINT fk_apqp_cp_line_cp FOREIGN KEY (cp_id) REFERENCES apqp_cp (cp_id),
    CONSTRAINT fk_apqp_cp_line_step FOREIGN KEY (step_id) REFERENCES mst_routing_step (step_id),
    CONSTRAINT fk_apqp_cp_line_char FOREIGN KEY (char_id) REFERENCES qms_char (char_id),
    CONSTRAINT fk_apqp_cp_line_risk FOREIGN KEY (risk_id) REFERENCES apqp_fmea_risk (risk_id),
    -- 순번은 커밋 시점 검사 — 한 번에 두 행의 순번을 맞바꿀 수 있게
    CONSTRAINT uq_apqp_cp_line_no UNIQUE (cp_id, line_no) DEFERRABLE INITIALLY DEFERRED,
    CONSTRAINT ck_apqp_cp_line_no CHECK (line_no BETWEEN 1 AND 9999),
    CONSTRAINT ck_apqp_cp_line_step CHECK (step_id IS NOT NULL OR length(btrim(coalesce(step_text, ''))) > 0),
    CONSTRAINT ck_apqp_cp_line_char CHECK (char_id IS NOT NULL OR length(btrim(coalesce(char_text, ''))) > 0),
    CONSTRAINT ck_apqp_cp_line_method CHECK (ctrl_method IN ('INSPECT', 'SPC', 'ERROR_PROOF', 'SETUP_VERIFY', 'WORK_STD', 'OTHER')),
    CONSTRAINT ck_apqp_cp_line_insp_type CHECK (insp_type IS NULL OR insp_type IN ('RECEIVE', 'PROCESS', 'FINAL', 'FIRST_OFF', 'LAST_OFF', 'PATROL')),
    CONSTRAINT ck_apqp_cp_line_sample CHECK (sample_n IS NULL OR sample_n BETWEEN 1 AND 1000000),
    -- 주기 = 검사계획 행(qms_insp_plan_line)과 같은 값 — 대조가 값 비교로 끝나게(C5)
    CONSTRAINT ck_apqp_cp_line_freq CHECK (
        (freq_type IS NULL AND freq_n IS NULL)
        OR (freq_type IN ('EVERY_LOT', 'START', 'END') AND freq_n IS NULL)
        OR (freq_type = 'EVERY_N' AND freq_n IS NOT NULL AND freq_n BETWEEN 1 AND 1000000)
        OR (freq_type = 'EVERY_HOURS' AND freq_n IS NOT NULL AND freq_n BETWEEN 1 AND 168)),
    CONSTRAINT ck_apqp_cp_line_data_type CHECK (data_type IS NULL OR data_type IN ('VARIABLE', 'ATTRIBUTE'))
);
COMMENT ON TABLE apqp_cp_line IS '관리계획서 행 — 공정 단계 × 특성 × 관리 방법. 규격·특별특성은 확정 때 스냅샷, 근거 위험행(risk_id)은 머리 근거 PFMEA 의 것';
COMMENT ON COLUMN apqp_cp_line.insp_type IS '검사계획 대조용 검사 유형(INSPECT·SPC 행) — 대조는 QMS 읽기 계약 뒤(docs/41 C5)';
-- 같은 단계 × 특성 × 관리 방법 두 번 금지(키로 가리킬 때)
CREATE UNIQUE INDEX uq_apqp_cp_line_key ON apqp_cp_line (cp_id, step_id, char_id, ctrl_method) WHERE step_id IS NOT NULL AND char_id IS NOT NULL;
CREATE INDEX ix_apqp_cp_line_cp ON apqp_cp_line (comp_cd, cp_id);

CREATE FUNCTION hb_guard_apqp_cp_line() RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    v_status  varchar(10);
    v_item    bigint;
    v_routing bigint;
    v_fmea    bigint;
    v_ctype   varchar(10);
    v_citem   bigint;
    v_cproc   bigint;
    v_rec     apqp_cp_line;
BEGIN
    v_rec := CASE WHEN TG_OP = 'DELETE' THEN OLD ELSE NEW END;
    IF TG_OP = 'UPDATE' AND (NEW.cp_id <> OLD.cp_id OR NEW.comp_cd <> OLD.comp_cd) THEN
        RAISE EXCEPTION 'line % plan is fixed', OLD.line_id USING ERRCODE = '55000', CONSTRAINT = 'ck_apqp_cp_line_fixed';
    END IF;
    SELECT c.status, c.item_id, c.routing_id, c.fmea_id INTO v_status, v_item, v_routing, v_fmea
      FROM apqp_cp c WHERE c.cp_id = v_rec.cp_id AND c.comp_cd = v_rec.comp_cd;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'control plan % is not of company %', v_rec.cp_id, v_rec.comp_cd USING ERRCODE = '23503', CONSTRAINT = 'fk_apqp_cp_line_comp';
    END IF;
    IF v_status <> 'DRAFT' THEN
        RAISE EXCEPTION 'control plan % is %', v_rec.cp_id, v_status USING ERRCODE = '55000', CONSTRAINT = 'ck_apqp_cp_fixed';
    END IF;
    IF TG_OP = 'DELETE' THEN
        RETURN OLD;
    END IF;
    IF NEW.step_id IS NOT NULL AND (TG_OP = 'INSERT' OR NEW.step_id IS DISTINCT FROM OLD.step_id)
       AND (v_routing IS NULL OR NOT EXISTS (SELECT 1 FROM mst_routing_step s WHERE s.step_id = NEW.step_id
                                               AND s.routing_id = v_routing AND s.comp_cd = NEW.comp_cd)) THEN
        RAISE EXCEPTION 'step % is not of the control plan routing', NEW.step_id USING ERRCODE = '23503', CONSTRAINT = 'fk_apqp_cp_line_step_routing';
    END IF;
    IF NEW.char_id IS NOT NULL AND (TG_OP = 'INSERT' OR NEW.char_id IS DISTINCT FROM OLD.char_id OR NEW.step_id IS DISTINCT FROM OLD.step_id) THEN
        SELECT c.char_type, c.item_id, c.proc_id INTO v_ctype, v_citem, v_cproc
          FROM qms_char c WHERE c.char_id = NEW.char_id AND c.comp_cd = NEW.comp_cd;
        IF NOT FOUND THEN
            RAISE EXCEPTION 'char % is not of company %', NEW.char_id, NEW.comp_cd USING ERRCODE = '23503', CONSTRAINT = 'fk_apqp_cp_line_char_comp';
        END IF;
        -- 제품 특성 = 머리 품목, 공정 특성 = 행 단계의 공정(단계를 가리킬 때)
        IF (v_ctype = 'PRODUCT' AND v_citem <> v_item)
           OR (v_ctype = 'PROCESS' AND NEW.step_id IS NOT NULL
               AND NOT EXISTS (SELECT 1 FROM mst_routing_step s WHERE s.step_id = NEW.step_id AND s.proc_id = v_cproc)) THEN
            RAISE EXCEPTION 'char % does not belong to this item/step', NEW.char_id USING ERRCODE = '23514', CONSTRAINT = 'ck_apqp_cp_line_char_target';
        END IF;
    END IF;
    IF NEW.risk_id IS NOT NULL AND (TG_OP = 'INSERT' OR NEW.risk_id IS DISTINCT FROM OLD.risk_id)
       AND (v_fmea IS NULL OR NOT EXISTS (SELECT 1 FROM apqp_fmea_risk r WHERE r.risk_id = NEW.risk_id AND r.fmea_id = v_fmea)) THEN
        RAISE EXCEPTION 'risk % is not of the control plan fmea', NEW.risk_id USING ERRCODE = '23503', CONSTRAINT = 'fk_apqp_cp_line_risk_fmea';
    END IF;
    IF NEW.gauge_class IS NOT NULL AND (TG_OP = 'INSERT' OR NEW.gauge_class IS DISTINCT FROM OLD.gauge_class)
       AND NOT hb_code_usable(NEW.comp_cd, 'GAUGE_CLASS', NEW.gauge_class) THEN
        RAISE EXCEPTION 'gauge class % is not usable', NEW.gauge_class USING ERRCODE = '23514', CONSTRAINT = 'ck_apqp_cp_line_gauge';
    END IF;
    IF NEW.special_cls IS NOT NULL AND (TG_OP = 'INSERT' OR NEW.special_cls IS DISTINCT FROM OLD.special_cls)
       AND NOT hb_code_usable(NEW.comp_cd, 'SPECIAL_CHAR', NEW.special_cls) THEN
        RAISE EXCEPTION 'special class % is not usable', NEW.special_cls USING ERRCODE = '23514', CONSTRAINT = 'ck_apqp_cp_line_special';
    END IF;
    RETURN NEW;
END
$$;
CREATE TRIGGER tg_apqp_cp_line_guard BEFORE INSERT OR UPDATE OR DELETE ON apqp_cp_line FOR EACH ROW EXECUTE FUNCTION hb_guard_apqp_cp_line();

-- ---------------------------------------------------------------------
-- 3. 감사 · RLS · 권한 · 화면 · 보존
-- ---------------------------------------------------------------------
CREATE TRIGGER tg_audit AFTER INSERT OR UPDATE OR DELETE ON apqp_cp FOR EACH ROW EXECUTE FUNCTION hb_audit('cp_id', '', '');
CREATE TRIGGER tg_audit AFTER INSERT OR UPDATE OR DELETE ON apqp_cp_line FOR EACH ROW EXECUTE FUNCTION hb_audit('line_id', '', '');

ALTER TABLE apqp_cp ENABLE ROW LEVEL SECURITY;
ALTER TABLE apqp_cp FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON apqp_cp USING (comp_cd = hb_current_comp()) WITH CHECK (comp_cd = hb_current_comp());
ALTER TABLE apqp_cp_line ENABLE ROW LEVEL SECURITY;
ALTER TABLE apqp_cp_line FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON apqp_cp_line USING (comp_cd = hb_current_comp()) WITH CHECK (comp_cd = hb_current_comp());

REVOKE TRUNCATE ON apqp_cp, apqp_cp_line FROM ${app_role};

INSERT INTO sys_prog (prog_cd, prog_nm_key, view_path, module_cd, actions, sort_no) VALUES
    -- APPROVE = 확정(P8 과 같은 판단 — 결재선은 A3)
    ('apqp-cp', 'prog.apqpCp', 'apqp/cp-mgt', 'PMS', ARRAY['SEARCH','SAVE','APPROVE']::varchar(20)[], 848);

INSERT INTO sys_retention_table (table_nm, class_cd) VALUES
    ('apqp_cp', 'QUALITY_RECORD'), ('apqp_cp_line', 'QUALITY_RECORD');
