-- =====================================================================
-- P3 QMS 관리문서 쓰기 계약 DocPort (docs/29_P3 20.2, 사용자 결정 Q-t + 상위 변경 DP-5·itemId) — 임시 번호, 병합 때 재번호
--
-- mst_doc_rev.source_key: 부르는 쪽 키(예 PMS:DELIV:{id}:{rev}) — 같은 키로 다시 부르면 처음 만든 리비전을 돌려준다(DP-3 멱등).
-- 회사 안에서 유일(값이 있을 때만), 형식 제한, 한 번 정하면 바뀌지 않음(별도 트리거 — 기존 리비전 가드는 그대로 둔다).
-- =====================================================================

ALTER TABLE mst_doc_rev ADD COLUMN source_key varchar(100);
ALTER TABLE mst_doc_rev ADD CONSTRAINT ck_mst_doc_rev_source_key CHECK (source_key IS NULL OR source_key ~ '^[A-Za-z0-9][A-Za-z0-9:._-]{0,99}$');
CREATE UNIQUE INDEX uq_mst_doc_rev_source_key ON mst_doc_rev (comp_cd, source_key) WHERE source_key IS NOT NULL;
COMMENT ON COLUMN mst_doc_rev.source_key IS '다른 모듈이 DocPort 로 만든 리비전의 부르는 쪽 키 — 멱등(같은 키 = 같은 리비전), 바뀌지 않음';

CREATE FUNCTION hb_guard_mst_doc_rev_source() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.source_key IS DISTINCT FROM OLD.source_key THEN
        RAISE EXCEPTION 'source key of document revision % cannot be changed', OLD.doc_rev_id
            USING ERRCODE = '55000', CONSTRAINT = 'ck_mst_doc_rev_source_fixed';
    END IF;
    RETURN NEW;
END
$$;
CREATE TRIGGER tg_mst_doc_rev_source BEFORE UPDATE ON mst_doc_rev FOR EACH ROW EXECUTE FUNCTION hb_guard_mst_doc_rev_source();
