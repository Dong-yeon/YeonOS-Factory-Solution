-- =====================================================================
-- QMS 현장 단말(POP) 검사 입력 (docs/29_P3 53장 FI-5, docs/25 4.57) — 임시 번호, 병합 때 재번호
--
-- - qms_insp_meas(측정 원장): 작업자 performed_by(mst_worker)·단말 terminal_id(sys_terminal)·요청 id client_req_id, 입력 방법 SCAN 추가.
--   단말 입력이면 셋 다 있어야 한다. 같은 요청 id 로 같은 (행, 샘플) 두 번 금지. 원장 규칙(추가만·두 시각·정정 = 무효 행)은 그대로
-- - qms_insp(검사 머리): 연 작업자·단말·요청 id, 판정 확정 작업자
-- - 같은 회사 작업자·단말은 트리거(회사별 표 사이 FK 는 RLS 를 못 본다)
-- =====================================================================

ALTER TABLE qms_insp_meas
    ADD COLUMN performed_by   bigint,
    ADD COLUMN terminal_id    bigint,
    ADD COLUMN client_req_id  varchar(64),
    ADD CONSTRAINT fk_qms_insp_meas_worker FOREIGN KEY (performed_by) REFERENCES mst_worker (worker_id),
    ADD CONSTRAINT fk_qms_insp_meas_terminal FOREIGN KEY (terminal_id) REFERENCES sys_terminal (terminal_id),
    -- 단말 입력 = 작업자·단말·요청 id 셋 다, 사무 입력 = 셋 다 없음
    ADD CONSTRAINT ck_qms_insp_meas_floor CHECK ((terminal_id IS NULL) = (performed_by IS NULL) AND (terminal_id IS NULL) = (client_req_id IS NULL)),
    ADD CONSTRAINT ck_qms_insp_meas_req_fmt CHECK (client_req_id IS NULL OR client_req_id ~ '^[A-Za-z0-9_.:-]{8,64}$');
ALTER TABLE qms_insp_meas DROP CONSTRAINT ck_qms_insp_meas_entry_method;
ALTER TABLE qms_insp_meas ADD CONSTRAINT ck_qms_insp_meas_entry_method CHECK (entry_method IN ('KEY', 'IMPORT', 'SCAN'));
CREATE UNIQUE INDEX uq_qms_insp_meas_req ON qms_insp_meas (comp_cd, client_req_id, insp_line_id, sample_no) WHERE client_req_id IS NOT NULL;
COMMENT ON COLUMN qms_insp_meas.performed_by IS '잰 작업자(현장 단말 입력, mst_worker) — 사무 입력은 비움(recorded_by 가 사람 계정)';
COMMENT ON COLUMN qms_insp_meas.terminal_id IS '현장 단말(POP) — 단말 입력이면 작업자·요청 id 와 함께 (docs/29_P3 53장)';
COMMENT ON COLUMN qms_insp_meas.entry_method IS '입력 방법 — KEY(화면·키패드)·IMPORT(엑셀 올리기)·SCAN(계측기 스캔·디지털 게이지, 현장 단말)';

ALTER TABLE qms_insp
    ADD COLUMN open_worker_id    bigint,
    ADD COLUMN open_terminal_id  bigint,
    ADD COLUMN open_req_id       varchar(64),
    ADD COLUMN judged_worker_id  bigint,
    ADD CONSTRAINT fk_qms_insp_open_worker FOREIGN KEY (open_worker_id) REFERENCES mst_worker (worker_id),
    ADD CONSTRAINT fk_qms_insp_open_terminal FOREIGN KEY (open_terminal_id) REFERENCES sys_terminal (terminal_id),
    ADD CONSTRAINT fk_qms_insp_judged_worker FOREIGN KEY (judged_worker_id) REFERENCES mst_worker (worker_id),
    ADD CONSTRAINT ck_qms_insp_open_floor CHECK ((open_terminal_id IS NULL) = (open_worker_id IS NULL) AND (open_terminal_id IS NULL) = (open_req_id IS NULL));
CREATE UNIQUE INDEX uq_qms_insp_open_req ON qms_insp (comp_cd, open_req_id) WHERE open_req_id IS NOT NULL;
COMMENT ON COLUMN qms_insp.open_terminal_id IS '현장 단말에서 연 검사 — 연 작업자·요청 id 와 함께 (docs/29_P3 53장)';
COMMENT ON COLUMN qms_insp.judged_worker_id IS '현장 단말에서 판정 확정(자동 OK·불합격 확정)한 작업자 — judged_by 는 단말 행위자';

-- 같은 회사 작업자·단말 (RLS 로 다른 회사 행은 안 보인다 → 없으면 거부)
CREATE FUNCTION hb_guard_qms_insp_floor() RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    v_worker   bigint;
    v_terminal bigint;
BEGIN
    IF TG_TABLE_NAME = 'qms_insp_meas' THEN
        v_worker := NEW.performed_by;
        v_terminal := NEW.terminal_id;
    ELSE
        v_worker := coalesce(NEW.judged_worker_id, NEW.open_worker_id);
        v_terminal := NEW.open_terminal_id;
        IF NEW.open_worker_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM mst_worker w WHERE w.worker_id = NEW.open_worker_id AND w.comp_cd = NEW.comp_cd) THEN
            RAISE EXCEPTION 'worker % does not belong to company %', NEW.open_worker_id, NEW.comp_cd
                USING ERRCODE = '23503', CONSTRAINT = 'fk_qms_insp_floor_worker_comp';
        END IF;
    END IF;
    IF v_worker IS NOT NULL AND NOT EXISTS (SELECT 1 FROM mst_worker w WHERE w.worker_id = v_worker AND w.comp_cd = NEW.comp_cd) THEN
        RAISE EXCEPTION 'worker % does not belong to company %', v_worker, NEW.comp_cd
            USING ERRCODE = '23503', CONSTRAINT = 'fk_qms_insp_floor_worker_comp';
    END IF;
    IF v_terminal IS NOT NULL AND NOT EXISTS (SELECT 1 FROM sys_terminal t WHERE t.terminal_id = v_terminal AND t.comp_cd = NEW.comp_cd) THEN
        RAISE EXCEPTION 'terminal % does not belong to company %', v_terminal, NEW.comp_cd
            USING ERRCODE = '23503', CONSTRAINT = 'fk_qms_insp_floor_terminal_comp';
    END IF;
    RETURN NEW;
END
$$;
CREATE TRIGGER tg_qms_insp_meas_floor BEFORE INSERT ON qms_insp_meas FOR EACH ROW EXECUTE FUNCTION hb_guard_qms_insp_floor();
CREATE TRIGGER tg_qms_insp_floor BEFORE INSERT OR UPDATE OF open_worker_id, open_terminal_id, judged_worker_id ON qms_insp
    FOR EACH ROW EXECUTE FUNCTION hb_guard_qms_insp_floor();
