-- =====================================================================
-- P6 APQP A3-5 — PFMEA 조치 → PMS 이슈 (docs/41 11.7, L7). PMS 표만(이 세션 소유)
-- - pms_issue.source 에 APQP, apqp_action_id(만든 근거 조치 — FK 없음: 작성 중 FMEA 를 지우면 조치도 지워지므로, 이력처럼 id 만)
-- - 조치 하나에 이슈 하나(부분 유일 색인), 한 번 정하면 고정, 같은 회사 조치만(트리거 — FK 가 없으니 직접 확인)
-- - 이슈와 조치의 상태는 따로(L7 — 동기화 없음)
-- 임시 번호(docs/29 2.2) — 병합 때 이름만 바뀐다.
-- =====================================================================

ALTER TABLE pms_issue DROP CONSTRAINT ck_pms_issue_source;
ALTER TABLE pms_issue ADD CONSTRAINT ck_pms_issue_source CHECK (source IN ('MANUAL', 'GATE', 'APQP'));
ALTER TABLE pms_issue ADD COLUMN apqp_action_id bigint;
ALTER TABLE pms_issue ADD CONSTRAINT ck_pms_issue_apqp CHECK ((source = 'APQP') = (apqp_action_id IS NOT NULL));
CREATE UNIQUE INDEX uq_pms_issue_apqp_action ON pms_issue (comp_cd, apqp_action_id) WHERE apqp_action_id IS NOT NULL;
COMMENT ON COLUMN pms_issue.apqp_action_id IS '"이슈로" 로 만든 근거 PFMEA 조치(apqp_fmea_action) — FK 없음, 한 번 정하면 고정 (docs/41 11.7)';

CREATE FUNCTION hb_check_pms_issue_apqp() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'UPDATE' AND NEW.apqp_action_id IS DISTINCT FROM OLD.apqp_action_id THEN
        RAISE EXCEPTION 'issue % source action is fixed', OLD.issue_no USING ERRCODE = '55000', CONSTRAINT = 'ck_pms_issue_apqp_fixed';
    END IF;
    IF TG_OP = 'INSERT' AND NEW.apqp_action_id IS NOT NULL
       AND NOT EXISTS (SELECT 1 FROM apqp_fmea_action a WHERE a.action_id = NEW.apqp_action_id AND a.comp_cd = NEW.comp_cd) THEN
        RAISE EXCEPTION 'fmea action % is not of company %', NEW.apqp_action_id, NEW.comp_cd
            USING ERRCODE = '23503', CONSTRAINT = 'fk_pms_issue_apqp_comp';
    END IF;
    RETURN NEW;
END
$$;
CREATE TRIGGER tg_pms_issue_zapqp BEFORE INSERT OR UPDATE ON pms_issue
    FOR EACH ROW EXECUTE FUNCTION hb_check_pms_issue_apqp();
