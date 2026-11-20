-- =====================================================================
-- CMS C19 — 위험성평가 참여자에 작업자(mst_worker) 고르기 (docs/33 13.6, 상위 결정: 기준정보 읽기 OK)
-- - cms_risk_member.worker_id: 사용자(user_id)·작업자(worker_id)·이름만 중 하나. 둘 다는 안 된다
-- - 같은 회사 작업자만(넣거나 바꿀 때), 확정 뒤 바꿀 수 없음(user_id 와 같은 규칙) — 가드 함수 교체
-- - 사용 중지 작업자는 새로 고를 수 없다(이미 넣은 행은 그대로 — 기록)
-- 임시 번호(docs/29 2.2) — 병합 때 이름만 바뀐다.
-- =====================================================================

ALTER TABLE cms_risk_member ADD COLUMN worker_id bigint;
ALTER TABLE cms_risk_member ADD CONSTRAINT fk_cms_risk_member_worker FOREIGN KEY (worker_id) REFERENCES mst_worker (worker_id);
ALTER TABLE cms_risk_member ADD CONSTRAINT ck_cms_risk_member_who CHECK (user_id IS NULL OR worker_id IS NULL);
CREATE INDEX ix_cms_risk_member_worker ON cms_risk_member (comp_cd, worker_id) WHERE worker_id IS NOT NULL;
COMMENT ON COLUMN cms_risk_member.worker_id IS '현장 작업자(배지 계정) — 사용자와 함께 쓰지 않는다. member_nm 은 그때 작업자 이름 스냅샷';

-- 작성 중: 추가·수정·삭제. 확정 뒤(RELEASED): 주지일만 채운다. 그 밖: 불변 (V083 + 작업자)
CREATE OR REPLACE FUNCTION hb_guard_cms_risk_member() RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    r   record;
    st  varchar;
BEGIN
    r := CASE WHEN TG_OP = 'DELETE' THEN OLD ELSE NEW END;
    IF TG_OP = 'UPDATE' AND (NEW.ra_id <> OLD.ra_id OR NEW.comp_cd <> OLD.comp_cd) THEN
        RAISE EXCEPTION 'member assessment is fixed' USING ERRCODE = '55000', CONSTRAINT = 'ck_cms_risk_member_fixed';
    END IF;
    SELECT a.status INTO st FROM cms_risk_assess a WHERE a.ra_id = r.ra_id AND a.comp_cd = r.comp_cd FOR UPDATE;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'assessment % is not of company %', r.ra_id, r.comp_cd USING ERRCODE = '23503', CONSTRAINT = 'fk_cms_risk_member_ra_comp';
    END IF;
    IF st <> 'DRAFT' AND (TG_OP <> 'UPDATE' OR st <> 'RELEASED' OR NEW.user_id IS DISTINCT FROM OLD.user_id
           OR NEW.worker_id IS DISTINCT FROM OLD.worker_id
           OR NEW.member_nm <> OLD.member_nm OR NEW.role_cd <> OLD.role_cd OR NEW.participated_on IS DISTINCT FROM OLD.participated_on) THEN
        RAISE EXCEPTION 'member of % assessment is fixed (only notification date)', st
            USING ERRCODE = '55000', CONSTRAINT = 'ck_cms_risk_member_released_fixed';
    END IF;
    IF TG_OP <> 'DELETE' AND NEW.user_id IS NOT NULL AND (TG_OP = 'INSERT' OR NEW.user_id IS DISTINCT FROM OLD.user_id)
       AND NOT hb_user_of_comp(NEW.comp_cd, NEW.user_id) THEN
        RAISE EXCEPTION 'user % is not of company %', NEW.user_id, NEW.comp_cd USING ERRCODE = '23503', CONSTRAINT = 'fk_cms_risk_member_user_comp';
    END IF;
    IF TG_OP <> 'DELETE' AND NEW.worker_id IS NOT NULL AND (TG_OP = 'INSERT' OR NEW.worker_id IS DISTINCT FROM OLD.worker_id)
       AND NOT EXISTS (SELECT 1 FROM mst_worker w WHERE w.worker_id = NEW.worker_id AND w.comp_cd = NEW.comp_cd AND w.use_yn = 'Y') THEN
        RAISE EXCEPTION 'worker % is not an active worker of company %', NEW.worker_id, NEW.comp_cd
            USING ERRCODE = '23503', CONSTRAINT = 'fk_cms_risk_member_worker_comp';
    END IF;
    RETURN r;
END
$$;
