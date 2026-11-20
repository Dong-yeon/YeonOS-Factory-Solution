-- =====================================================================
-- V9950(병합 때 재번호) 품목코드 = 시스템 코드 + ERP·고객 품번 매핑 (docs/17 9장, 2026-11-09)
--
-- 1) 정책 ITEM_CD_MODE: AUTO = 신규 품목코드를 채번 규칙 ITEM 으로, MANUAL = 직접 입력(지금 동작).
--    기본값 MANUAL — 기본 AUTO 전환은 사용자 결정 질문(docs/17 9.3 Q1). 기존 품목 코드는 고치지 않는다.
-- 2) 품목코드는 만든 뒤 바꾸지 않는다 — 서비스(ItemService, 400 FIXED)가 먼저 알리고
--    이 트리거는 직접 SQL·다른 매퍼의 최종 방어(55000 → 409). 라벨·LOT·ERP 송신에 찍힌 코드와 어긋나지 않게.
--    잘못 만든 품목은 새 품목 + 옛 품목 사용 중지(docs/17 9.2 (b')).
-- 3) 품목 조회 "품목코드" 칸이 외부 품번(ERP·고객)도 찾는다 — 대소문자 무시 앞부분 검색용 표현식 인덱스.
-- =====================================================================

INSERT INTO sys_policy_def (policy_key, value_type, default_value, allowed_values, min_value, max_value, desc_key) VALUES
    ('ITEM_CD_MODE', 'ENUM', 'MANUAL', ARRAY['AUTO','MANUAL'], NULL, NULL, 'policy.ITEM_CD_MODE');

CREATE FUNCTION hb_forbid_item_cd_change() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.item_cd IS DISTINCT FROM OLD.item_cd THEN
        RAISE EXCEPTION 'item code cannot be changed after creation (item %)', OLD.item_id
            USING ERRCODE = '55000', CONSTRAINT = 'ck_mst_item_cd_fixed';
    END IF;
    RETURN NEW;
END
$$;
COMMENT ON FUNCTION hb_forbid_item_cd_change() IS '품목코드(시스템 코드) 고정 — 바꾸려면 새 품목 + 옛 품목 사용 중지 (docs/17 9장)';

CREATE TRIGGER tg_mst_item_cd_fixed BEFORE UPDATE OF item_cd ON mst_item
    FOR EACH ROW EXECUTE FUNCTION hb_forbid_item_cd_change();

-- 외부 품번 검색(대소문자 무시 앞부분) — 사용 중 매핑만 본다
CREATE INDEX ix_mst_ext_id_map_ext_upper ON mst_ext_id_map (comp_cd, entity_type, upper(external_id) text_pattern_ops)
    WHERE use_yn = 'Y';
