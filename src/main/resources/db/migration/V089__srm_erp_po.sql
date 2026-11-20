-- =====================================================================
-- V9502 SRM ERP 발주 받기 (P5 SRM 1부 S3b — docs/31 3장 ERP 경계·4.7, 결정 D1). 임시 번호 — 병합 때 상위 세션이 이름만 바꾼다
--
-- - 정책 SRM_PO_SOURCE=ERP 인 회사는 ERP 발주를 엑셀(템플릿 SRM_ERP_PO)로 받아 SRM 발주(source = ERP)로 만든다
-- - ERP 발주 번호 ↔ SRM 발주는 외부 ID 매핑 mst_ext_id_map 의 새 대상 유형 'PO' (시스템 안 1:1 — 같은 번호 다시 받기 = 멱등)
-- - 남의 표 변경(P1 mst_ext_id_map, docs/31 6장 예고): 대상 유형 CHECK 교체 + 소속 확인 트리거에 PO 분기.
--   기준정보 외부 ID 화면(ExtService)은 ITEM·CUST 만 다루므로 PO 매핑은 SRM 만 쓴다
-- =====================================================================

ALTER TABLE mst_ext_id_map DROP CONSTRAINT ck_mst_ext_id_map_entity;
ALTER TABLE mst_ext_id_map ADD CONSTRAINT ck_mst_ext_id_map_entity CHECK (entity_type IN ('ITEM', 'CUST', 'PO'));

-- V026 함수 본문 그대로 + (S3b) PO 분기
CREATE OR REPLACE FUNCTION hb_guard_ext_id_map() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'UPDATE' AND (NEW.comp_cd <> OLD.comp_cd OR NEW.system_id <> OLD.system_id
                             OR NEW.entity_type <> OLD.entity_type OR NEW.internal_id <> OLD.internal_id) THEN
        -- 가리키는 대상이 바뀌면 송신 이력의 뜻이 바뀐다 — 끄고 새로 넣는다
        RAISE EXCEPTION 'mapping % target cannot be changed', OLD.map_id USING ERRCODE = '55000', CONSTRAINT = 'ck_mst_ext_id_map_fixed';
    END IF;
    IF TG_OP = 'INSERT' THEN
        IF NOT hb_ext_system_of_comp(NEW.comp_cd, NEW.system_id) THEN
            RAISE EXCEPTION 'system % does not belong to company %', NEW.system_id, NEW.comp_cd
                USING ERRCODE = '23503', CONSTRAINT = 'fk_mst_ext_id_map_system_comp';
        END IF;
        IF (NEW.entity_type = 'ITEM' AND NOT hb_item_of_comp(NEW.comp_cd, NEW.internal_id))
           OR (NEW.entity_type = 'CUST' AND NOT EXISTS (SELECT 1 FROM mst_cust c WHERE c.cust_id = NEW.internal_id AND c.comp_cd = NEW.comp_cd))
           OR (NEW.entity_type = 'PO' AND NOT EXISTS (SELECT 1 FROM srm_po p WHERE p.po_id = NEW.internal_id AND p.comp_cd = NEW.comp_cd
                                                         AND p.source = 'ERP')) THEN
            RAISE EXCEPTION '% % does not belong to company %', NEW.entity_type, NEW.internal_id, NEW.comp_cd
                USING ERRCODE = '23503', CONSTRAINT = 'fk_mst_ext_id_map_target';
        END IF;
    END IF;
    RETURN NEW;
END
$$;
