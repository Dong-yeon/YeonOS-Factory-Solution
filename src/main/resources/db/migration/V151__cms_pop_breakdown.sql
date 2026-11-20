-- =====================================================================
-- CMS C20 — POP 고장 신고 계약 (docs/33 11.16, 상위 채택: MES Q2·Q4·Q5 + 계약 보강 1~7)
-- - cms_mwo 에 현장 신고자: 작업자(mst_worker) + 단말(sys_terminal) — 배지 신고는 사무 계정(requested_by)이 없다
-- - report_req_id: POP 요청 id(clientReqId) — 같은 id 재요청은 처음 작업지시 번호를 돌려준다(sys_inbox 와 함께)
-- - 셋은 만든 뒤 바꿀 수 없고, 작업자·단말은 같은 회사(작업자는 사용 중)만
-- 임시 번호(docs/29 2.2) — 병합 때 이름만 바뀐다.
-- =====================================================================

ALTER TABLE cms_mwo ADD COLUMN reported_worker_id bigint;
ALTER TABLE cms_mwo ADD COLUMN reported_terminal_id bigint;
ALTER TABLE cms_mwo ADD COLUMN report_req_id varchar(64);
ALTER TABLE cms_mwo ADD CONSTRAINT fk_cms_mwo_reported_worker FOREIGN KEY (reported_worker_id) REFERENCES mst_worker (worker_id);
ALTER TABLE cms_mwo ADD CONSTRAINT fk_cms_mwo_reported_terminal FOREIGN KEY (reported_terminal_id) REFERENCES sys_terminal (terminal_id);
-- 현장 신고 = 작업자·단말·요청 id 셋이 함께, 사무 계정 신고자 없음
ALTER TABLE cms_mwo ADD CONSTRAINT ck_cms_mwo_pop_reporter CHECK (
    (reported_worker_id IS NULL AND reported_terminal_id IS NULL AND report_req_id IS NULL)
    OR (reported_worker_id IS NOT NULL AND reported_terminal_id IS NOT NULL AND report_req_id IS NOT NULL AND requested_by IS NULL));
CREATE UNIQUE INDEX uq_cms_mwo_report_req ON cms_mwo (comp_cd, report_req_id) WHERE report_req_id IS NOT NULL;
COMMENT ON COLUMN cms_mwo.reported_worker_id IS 'POP 고장 신고 작업자(배지) — 사무 계정 신고면 null';
COMMENT ON COLUMN cms_mwo.reported_terminal_id IS 'POP 고장 신고 단말';
COMMENT ON COLUMN cms_mwo.report_req_id IS 'POP 신고 요청 id(clientReqId) — 같은 요청 재전송 = 처음 번호';

CREATE FUNCTION hb_guard_cms_mwo_reporter() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'UPDATE' THEN
        IF NEW.reported_worker_id IS DISTINCT FROM OLD.reported_worker_id OR NEW.reported_terminal_id IS DISTINCT FROM OLD.reported_terminal_id
           OR NEW.report_req_id IS DISTINCT FROM OLD.report_req_id THEN
            RAISE EXCEPTION 'work order reporter is fixed' USING ERRCODE = '55000', CONSTRAINT = 'ck_cms_mwo_reporter_fixed';
        END IF;
        RETURN NEW;
    END IF;
    IF NEW.reported_worker_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM mst_worker w WHERE w.worker_id = NEW.reported_worker_id
            AND w.comp_cd = NEW.comp_cd AND w.use_yn = 'Y') THEN
        RAISE EXCEPTION 'worker % is not an active worker of company %', NEW.reported_worker_id, NEW.comp_cd
            USING ERRCODE = '23503', CONSTRAINT = 'fk_cms_mwo_reported_worker_comp';
    END IF;
    IF NEW.reported_terminal_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM sys_terminal t WHERE t.terminal_id = NEW.reported_terminal_id
            AND t.comp_cd = NEW.comp_cd AND t.plant_cd IS NOT DISTINCT FROM NEW.plant_cd) THEN
        RAISE EXCEPTION 'terminal % is not a terminal of company % in plant %', NEW.reported_terminal_id, NEW.comp_cd, NEW.plant_cd
            USING ERRCODE = '23503', CONSTRAINT = 'fk_cms_mwo_reported_terminal_comp';
    END IF;
    RETURN NEW;
END
$$;
CREATE TRIGGER tg_cms_mwo_reporter_guard BEFORE INSERT OR UPDATE ON cms_mwo FOR EACH ROW EXECUTE FUNCTION hb_guard_cms_mwo_reporter();
