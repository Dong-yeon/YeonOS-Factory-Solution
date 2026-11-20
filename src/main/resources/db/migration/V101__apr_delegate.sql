-- =====================================================================
-- 시스템관리 4c (docs/36 14.6) — 결재 위임·대결.
-- 임시 번호(병합 때 재번호, docs/29 2.2)
--
-- 1. apr_delegate (위임자 → 대리, 기간, 문서 유형) — 겹침 금지·자기 위임 금지·같은 회사 사용 중 사용자
-- 2. apr_line.acted_by (실제 처리자), apr_history.on_behalf_of (대결일 때 원 결재자)
-- =====================================================================

CREATE TABLE apr_delegate (
    deleg_id      bigint        GENERATED ALWAYS AS IDENTITY,
    comp_cd       varchar(20)   NOT NULL,
    delegator_id  bigint        NOT NULL,
    delegate_id   bigint        NOT NULL,
    doc_type      varchar(50),
    from_date     date          NOT NULL,
    to_date       date          NOT NULL,
    reason        varchar(500)  NOT NULL,
    revoked_at    timestamptz,
    row_ver       integer       NOT NULL DEFAULT 0,
    created_at    timestamptz   NOT NULL DEFAULT now(),
    created_by    varchar(80)   NOT NULL,
    updated_at    timestamptz   NOT NULL DEFAULT now(),
    updated_by    varchar(80)   NOT NULL,
    CONSTRAINT pk_apr_delegate PRIMARY KEY (deleg_id),
    CONSTRAINT fk_apr_delegate_comp FOREIGN KEY (comp_cd) REFERENCES sys_comp (comp_cd),
    CONSTRAINT fk_apr_delegate_from FOREIGN KEY (delegator_id) REFERENCES sys_user (user_id),
    CONSTRAINT fk_apr_delegate_to FOREIGN KEY (delegate_id) REFERENCES sys_user (user_id),
    CONSTRAINT ck_apr_delegate_self CHECK (delegator_id <> delegate_id),
    CONSTRAINT ck_apr_delegate_period CHECK (to_date >= from_date AND to_date <= from_date + 365),
    CONSTRAINT ck_apr_delegate_doc_type CHECK (doc_type IS NULL OR doc_type ~ '^[A-Z][A-Z0-9_]{1,49}$'),
    CONSTRAINT ck_apr_delegate_reason CHECK (coalesce(length(btrim(reason)), 0) > 0)
);
CREATE INDEX ix_apr_delegate_from ON apr_delegate (comp_cd, delegator_id, from_date);
CREATE INDEX ix_apr_delegate_to ON apr_delegate (comp_cd, delegate_id, from_date);
COMMENT ON TABLE apr_delegate IS '결재 위임 (위임자 본인이 만든다, 회수만 — 삭제 없음). 대결은 한 단계만 (docs/36 14.6)';

ALTER TABLE apr_delegate ENABLE ROW LEVEL SECURITY;
ALTER TABLE apr_delegate FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON apr_delegate
    USING (comp_cd = hb_current_comp()) WITH CHECK (comp_cd = hb_current_comp());

-- 같은 회사 사용 중 사용자 + 같은 위임자의 사용 중 위임 기간 겹침 금지(전체 ↔ 특정 유형도 겹침)
CREATE FUNCTION hb_check_apr_delegate() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'UPDATE' AND (NEW.delegator_id <> OLD.delegator_id OR NEW.delegate_id <> OLD.delegate_id
                             OR NEW.comp_cd <> OLD.comp_cd OR NEW.doc_type IS DISTINCT FROM OLD.doc_type
                             OR NEW.from_date <> OLD.from_date OR NEW.to_date <> OLD.to_date) THEN
        RAISE EXCEPTION 'delegation % is fixed — revoke and create a new one', OLD.deleg_id
            USING ERRCODE = '55000', CONSTRAINT = 'ck_apr_delegate_fixed';
    END IF;
    IF TG_OP = 'UPDATE' AND OLD.revoked_at IS NOT NULL THEN
        RAISE EXCEPTION 'delegation % already revoked', OLD.deleg_id
            USING ERRCODE = '55000', CONSTRAINT = 'ck_apr_delegate_revoked';
    END IF;
    IF TG_OP = 'INSERT' THEN
        -- 회사별 표 사이 FK 는 RLS 를 무시한다 — 같은 회사·사용 중 사용자인지 직접(위임자 행을 잠가 동시 위임을 한 줄로)
        PERFORM 1 FROM sys_user u WHERE u.user_id = NEW.delegator_id AND u.comp_cd = NEW.comp_cd FOR UPDATE;
        IF NOT FOUND THEN
            RAISE EXCEPTION 'delegator % not in company', NEW.delegator_id USING ERRCODE = '23503', CONSTRAINT = 'fk_apr_delegate_comp_user';
        END IF;
        IF NOT EXISTS (SELECT 1 FROM sys_user u WHERE u.user_id = NEW.delegate_id AND u.comp_cd = NEW.comp_cd
                          AND u.use_yn = 'Y' AND NOT (u.lock_yn = 'Y' AND u.locked_until IS NULL)) THEN
            RAISE EXCEPTION 'delegate % not usable in company', NEW.delegate_id USING ERRCODE = '23503', CONSTRAINT = 'fk_apr_delegate_comp_user';
        END IF;
        IF EXISTS (SELECT 1 FROM apr_delegate d
                    WHERE d.delegator_id = NEW.delegator_id AND d.revoked_at IS NULL
                      AND d.from_date <= NEW.to_date AND d.to_date >= NEW.from_date
                      AND (d.doc_type IS NULL OR NEW.doc_type IS NULL OR d.doc_type = NEW.doc_type)) THEN
            RAISE EXCEPTION 'delegation period overlaps' USING ERRCODE = '23P01', CONSTRAINT = 'ck_apr_delegate_overlap';
        END IF;
    END IF;
    RETURN NEW;
END
$$;
CREATE TRIGGER tg_apr_delegate_check BEFORE INSERT OR UPDATE ON apr_delegate
    FOR EACH ROW EXECUTE FUNCTION hb_check_apr_delegate();
CREATE TRIGGER tg_audit AFTER INSERT OR UPDATE OR DELETE ON apr_delegate
    FOR EACH ROW EXECUTE FUNCTION hb_audit('deleg_id', '', '');
REVOKE DELETE, TRUNCATE ON apr_delegate FROM ${app_role};
INSERT INTO sys_retention_table (table_nm, class_cd) VALUES ('apr_delegate', 'SYSTEM');

ALTER TABLE apr_line ADD COLUMN acted_by bigint;
ALTER TABLE apr_line ADD CONSTRAINT fk_apr_line_acted_by FOREIGN KEY (acted_by) REFERENCES sys_user (user_id);
COMMENT ON COLUMN apr_line.acted_by IS '실제 처리자 — 결재자와 다르면 대결 (docs/36 14.6)';
ALTER TABLE apr_history ADD COLUMN on_behalf_of bigint;
ALTER TABLE apr_history ADD CONSTRAINT fk_apr_history_on_behalf FOREIGN KEY (on_behalf_of) REFERENCES sys_user (user_id);
COMMENT ON COLUMN apr_history.on_behalf_of IS '대결일 때 원 결재자 (actor_id = 대리)';
