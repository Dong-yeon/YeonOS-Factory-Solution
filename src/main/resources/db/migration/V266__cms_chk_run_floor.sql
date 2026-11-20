-- =====================================================================
-- CMS — 현장 단말 예방점검 실행 계약 PmCheckPort (docs/25 4.58, 사용자 결정 2026-11-10 · 상위 승인: MES POP 세션이 CMS 몫까지)
-- - cms_chk_run 에 현장 처리자: 작업자(mst_worker) + 단말(sys_terminal) — 배지로 한 완료·건너뛰기는 사무 계정(performed_by)이 없다
-- - floor_req_id: POP 요청 id(clientReqId) — 같은 id 재요청은 처음 결과를 돌려준다
-- - 셋은 끝낼 때(DONE·SKIPPED) 함께만 들어가고(끝난 실행은 읽기 전용 — V065 가드 그대로), 작업자·단말은 같은 회사, 단말은 설비와 같은 공장
-- V151 이 cms_mwo(POP 고장 신고)에 한 것과 같은 모양. 임시 번호(docs/29 2.2, CMS 대역) — 병합 때 이름만 바뀐다.
-- =====================================================================

ALTER TABLE cms_chk_run ADD COLUMN floor_worker_id bigint;
ALTER TABLE cms_chk_run ADD COLUMN floor_terminal_id bigint;
ALTER TABLE cms_chk_run ADD COLUMN floor_req_id varchar(64);
ALTER TABLE cms_chk_run ADD CONSTRAINT fk_cms_chk_run_floor_worker FOREIGN KEY (floor_worker_id) REFERENCES mst_worker (worker_id);
ALTER TABLE cms_chk_run ADD CONSTRAINT fk_cms_chk_run_floor_terminal FOREIGN KEY (floor_terminal_id) REFERENCES sys_terminal (terminal_id);
-- 현장 처리 = 작업자·단말·요청 id 셋이 함께, 끝난 실행에만, 사무 계정 수행자 없음
ALTER TABLE cms_chk_run ADD CONSTRAINT ck_cms_chk_run_floor CHECK (
    (floor_worker_id IS NULL AND floor_terminal_id IS NULL AND floor_req_id IS NULL)
    OR (floor_worker_id IS NOT NULL AND floor_terminal_id IS NOT NULL AND floor_req_id IS NOT NULL
        AND performed_by IS NULL AND status IN ('DONE', 'SKIPPED')));
ALTER TABLE cms_chk_run ADD CONSTRAINT ck_cms_chk_run_floor_req CHECK (floor_req_id ~ '^[A-Za-z0-9_.:-]{8,64}$');
CREATE UNIQUE INDEX uq_cms_chk_run_floor_req ON cms_chk_run (comp_cd, floor_req_id) WHERE floor_req_id IS NOT NULL;
COMMENT ON COLUMN cms_chk_run.floor_worker_id IS '현장 단말에서 완료·건너뛴 작업자(배지) — 사무 계정이 했으면 null';
COMMENT ON COLUMN cms_chk_run.floor_terminal_id IS '현장 단말(POP) — 완료·건너뛰기를 받은 단말';
COMMENT ON COLUMN cms_chk_run.floor_req_id IS '현장 요청 id(clientReqId) — 같은 요청 재전송 = 처음 결과';

CREATE FUNCTION hb_guard_cms_chk_run_floor() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.floor_worker_id IS NULL THEN
        RETURN NEW;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM mst_worker w WHERE w.worker_id = NEW.floor_worker_id AND w.comp_cd = NEW.comp_cd AND w.use_yn = 'Y') THEN
        RAISE EXCEPTION 'worker % is not an active worker of company %', NEW.floor_worker_id, NEW.comp_cd
            USING ERRCODE = '23503', CONSTRAINT = 'fk_cms_chk_run_floor_worker_comp';
    END IF;
    -- 단말은 같은 회사 + 그 설비의 공장(자산 대상 실행은 단말로 처리하지 않는다)
    IF NOT EXISTS (SELECT 1 FROM sys_terminal t JOIN mst_equip e ON e.equip_id = NEW.equip_id
                    WHERE t.terminal_id = NEW.floor_terminal_id AND t.comp_cd = NEW.comp_cd AND e.comp_cd = NEW.comp_cd
                      AND t.plant_cd = e.plant_cd) THEN
        RAISE EXCEPTION 'terminal % is not a terminal of company % in the plant of this check run', NEW.floor_terminal_id, NEW.comp_cd
            USING ERRCODE = '23503', CONSTRAINT = 'fk_cms_chk_run_floor_terminal_comp';
    END IF;
    RETURN NEW;
END
$$;
CREATE TRIGGER tg_cms_chk_run_floor_guard BEFORE INSERT OR UPDATE OF floor_worker_id, floor_terminal_id, floor_req_id ON cms_chk_run
    FOR EACH ROW EXECUTE FUNCTION hb_guard_cms_chk_run_floor();
