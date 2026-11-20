-- SRM 클레임 NCR "살아 있음" 칸 + 부분 유일 색인 (QA QC-1, docs/31 4.12.4)
--   V147 의 "한 NCR 은 살아 있는 클레임 하나" 는 트리거가 EXISTS 로만 봐서, 작성 중 클레임 둘이 같은 NCR 을 동시에 넣으면(PUT 으로 고칠 때)
--   서로의 미커밋 행을 못 보고 둘 다 커밋됐다. 부모 상태를 줄에 live_yn 으로 내려 두고 부분 유일 색인이 보장한다 — 경합하면 뒤 트랜잭션은
--   앞 트랜잭션 끝까지 기다렸다가 거부된다(색인 이름이 예전 제약 이름 uq_srm_claim_ncr_live 그대로라 서비스 번역은 그대로).
--   live_yn 은 클레임이 취소될 때 AFTER 트리거가 N 으로만 바꾼다(취소는 끝 상태 — 되살림 없음).

ALTER TABLE srm_claim_ncr ADD COLUMN live_yn char(1) NOT NULL DEFAULT 'Y';
ALTER TABLE srm_claim_ncr ADD CONSTRAINT ck_srm_claim_ncr_live_yn CHECK (live_yn IN ('Y', 'N'));
COMMENT ON COLUMN srm_claim_ncr.live_yn IS '클레임이 살아 있음(취소 아님) — 클레임 취소 때 트리거가 N. 부분 유일 색인 uq_srm_claim_ncr_live 의 조건';

CREATE OR REPLACE FUNCTION hb_guard_srm_claim_ncr() RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    v_row  srm_claim_ncr;
    v_head record;
BEGIN
    v_row := CASE WHEN TG_OP = 'DELETE' THEN OLD ELSE NEW END;
    SELECT c.status, c.comp_cd INTO v_head FROM srm_claim c WHERE c.claim_id = v_row.claim_id;
    IF NOT FOUND OR v_head.comp_cd <> v_row.comp_cd THEN
        RAISE EXCEPTION 'claim % does not belong to company %', v_row.claim_id, v_row.comp_cd
            USING ERRCODE = '23503', CONSTRAINT = 'fk_srm_claim_ncr_claim_comp';
    END IF;
    -- 클레임 취소에 따른 살아 있음 내림(Y → N, 다른 칸 그대로)만 작성 중이 아니어도 허용
    IF TG_OP = 'UPDATE' AND v_head.status = 'CANCELLED' AND OLD.live_yn = 'Y' AND NEW.live_yn = 'N'
       AND (NEW.claim_id, NEW.ncr_id, NEW.ncr_no, NEW.item_id, NEW.nc_qty, NEW.unit_cd)
           IS NOT DISTINCT FROM (OLD.claim_id, OLD.ncr_id, OLD.ncr_no, OLD.item_id, OLD.nc_qty, OLD.unit_cd) THEN
        RETURN NEW;
    END IF;
    IF v_head.status <> 'DRAFT' THEN
        RAISE EXCEPTION 'nonconformances of % claim cannot be changed', v_head.status USING ERRCODE = '55000', CONSTRAINT = 'ck_srm_claim_ncr_draft';
    END IF;
    IF TG_OP = 'DELETE' THEN
        RETURN OLD;
    END IF;
    IF TG_OP = 'UPDATE' AND (NEW.claim_id, NEW.ncr_id) IS DISTINCT FROM (OLD.claim_id, OLD.ncr_id) THEN
        RAISE EXCEPTION 'line cannot move' USING ERRCODE = '55000', CONSTRAINT = 'ck_srm_claim_ncr_move';
    END IF;
    -- 작성 중 클레임의 줄은 늘 살아 있다(직접 N 으로 넣어 유일 색인을 피하지 못하게)
    NEW.live_yn := 'Y';
    RETURN NEW;
END
$$;

CREATE FUNCTION hb_srm_claim_ncr_release() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    UPDATE srm_claim_ncr
       SET live_yn = 'N', updated_at = now(), updated_by = NEW.updated_by
     WHERE claim_id = NEW.claim_id AND comp_cd = NEW.comp_cd AND live_yn = 'Y';
    RETURN NULL;
END
$$;
CREATE TRIGGER tg_srm_claim_ncr_release AFTER UPDATE OF status ON srm_claim
    FOR EACH ROW WHEN (NEW.status = 'CANCELLED' AND OLD.status IS DISTINCT FROM 'CANCELLED')
    EXECUTE FUNCTION hb_srm_claim_ncr_release();

-- 이미 취소된 클레임의 줄을 회사마다 N 으로(FORCE RLS — 회사 범위를 열어야 보인다). 겹친 살아 있는 줄(QC-1 로 생긴 것)이 있으면 멈춘다.
DO $$
DECLARE
    v_comps varchar[];
    v_comp  varchar;
    v_dup   bigint;
BEGIN
    SELECT coalesce(array_agg(comp_cd), ARRAY[]::varchar[]) INTO v_comps FROM sys_comp;
    PERFORM set_config('app.scope', '', true);
    FOREACH v_comp IN ARRAY v_comps LOOP
        PERFORM set_config('app.comp_cd', v_comp, true);
        UPDATE srm_claim_ncr n
           SET live_yn = 'N', updated_at = now(), updated_by = 'SYSTEM'
          FROM srm_claim c
         WHERE c.claim_id = n.claim_id AND c.comp_cd = v_comp AND n.comp_cd = v_comp AND c.status = 'CANCELLED' AND n.live_yn = 'Y';
        SELECT n.ncr_id INTO v_dup FROM srm_claim_ncr n
         WHERE n.comp_cd = v_comp AND n.live_yn = 'Y' GROUP BY n.ncr_id HAVING count(*) > 1 LIMIT 1;
        IF v_dup IS NOT NULL THEN
            RAISE EXCEPTION 'company % nonconformance % is in more than one live claim — cancel one before migrating', v_comp, v_dup;
        END IF;
    END LOOP;
    PERFORM set_config('app.comp_cd', '', true);
END
$$;

CREATE UNIQUE INDEX uq_srm_claim_ncr_live ON srm_claim_ncr (comp_cd, ncr_id) WHERE live_yn = 'Y';
COMMENT ON TABLE srm_claim_ncr IS '클레임 근거 NCR — id·번호·수량 스냅샷(QMS 계약 값), 작성 중만 편집. 한 NCR 은 살아 있는 클레임 하나(부분 유일 색인 uq_srm_claim_ncr_live)';
