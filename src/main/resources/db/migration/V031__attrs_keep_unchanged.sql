-- =====================================================================
-- V031 클래스 속성: 바뀌지 않은 값은 다시 검사하지 않는다 (7단계 QA 결함 #4 — docs/qa/ext-class-7.md, docs/22 5b 검토)
--
-- 사용자 결정(2026-10-20): 속성 정의를 바꾼 뒤(선택지 삭제·자릿수 축소·길이) 이미 그 값을 가진 품목·설비는
-- **그 값을 그대로 둔다** — "새 참조만 막는다" 원칙(사용 중지 코드·참조, 6단계 QA 결함1)과 같게.
-- 지금까지는 다른 속성만 고쳐도 attrs 전체를 새 정의로 다시 검사해 저장이 막혔다.
--
-- - hb_check_attrs 에 p_old(이전 attrs) 를 받는다. 클래스가 같고 그 키의 값이 이전과 같으면 형식·정의 검사를 건너뛴다.
--   클래스가 바뀌면 p_old = null → 전부 검사. 필수 검사는 그대로.
-- - DATE 의 없는 날짜(2026-02-30)는 22008 대신 ck_attrs_value 로(QA O9)
-- =====================================================================

CREATE FUNCTION hb_check_attrs(p_comp varchar, p_type varchar, p_class bigint, p_attrs jsonb, p_required boolean,
                               p_old jsonb) RETURNS void
    LANGUAGE plpgsql STABLE
AS $$
DECLARE
    v_key  text;
    v_val  jsonb;
    v_prop mst_class_prop;
    v_txt  text;
    v_bad  boolean;
BEGIN
    IF jsonb_typeof(p_attrs) <> 'object' THEN
        RAISE EXCEPTION 'attributes must be an object' USING ERRCODE = '23514', CONSTRAINT = 'ck_attrs_object';
    END IF;
    IF p_class IS NULL THEN
        IF p_attrs <> '{}'::jsonb THEN
            RAISE EXCEPTION 'attributes need a class' USING ERRCODE = '23514', CONSTRAINT = 'ck_attrs_no_class';
        END IF;
        RETURN;
    END IF;
    -- 1) 키는 그 클래스에 정의된 것만, 값은 형식대로 (null 은 "값 없음"). 이전과 같은 값은 건너뛴다(정의가 바뀌어도 둔다)
    FOR v_key, v_val IN SELECT key, value FROM jsonb_each(p_attrs) LOOP
        CONTINUE WHEN p_old IS NOT NULL AND p_old ? v_key AND p_old -> v_key = v_val;
        SELECT * INTO v_prop FROM mst_class_prop
         WHERE comp_cd = p_comp AND class_type = p_type AND class_id = p_class AND prop_cd = v_key;
        IF NOT FOUND THEN
            RAISE EXCEPTION 'attribute % is not defined for the class', v_key USING ERRCODE = '23514', CONSTRAINT = 'ck_attrs_unknown';
        END IF;
        CONTINUE WHEN jsonb_typeof(v_val) = 'null';
        v_txt := v_val #>> '{}';
        v_bad := (v_prop.data_type IN ('TEXT', 'DATE', 'LIST') AND jsonb_typeof(v_val) <> 'string')
              OR (v_prop.data_type = 'NUM' AND (jsonb_typeof(v_val) <> 'number' OR scale(v_txt::numeric) > v_prop.scale
                                                OR abs(v_txt::numeric) >= 1e15))
              OR (v_prop.data_type = 'BOOL' AND jsonb_typeof(v_val) <> 'boolean')
              OR (v_prop.data_type = 'TEXT' AND (length(v_txt) > 500 OR length(btrim(v_txt)) = 0))
              OR (v_prop.data_type = 'DATE' AND v_txt !~ '^(19|20|21)[0-9]{2}-[0-9]{2}-[0-9]{2}$')
              OR (v_prop.data_type = 'LIST' AND NOT (jsonb_typeof(v_prop.options) = 'array' AND v_prop.options ? v_txt));
        IF NOT v_bad AND v_prop.data_type = 'DATE' THEN
            BEGIN
                PERFORM v_txt::date;
            EXCEPTION WHEN datetime_field_overflow OR invalid_datetime_format THEN
                v_bad := true;              -- 2026-02-30 같은 날짜
            END;
        END IF;
        IF v_bad THEN
            RAISE EXCEPTION 'attribute % has a wrong value', v_key USING ERRCODE = '23514', CONSTRAINT = 'ck_attrs_value';
        END IF;
    END LOOP;
    -- 2) 사용 중 필수 속성은 값 있음
    IF NOT p_required THEN
        RETURN;
    END IF;
    FOR v_prop IN SELECT * FROM mst_class_prop
                   WHERE comp_cd = p_comp AND class_type = p_type AND class_id = p_class AND required_yn = 'Y' AND use_yn = 'Y' LOOP
        IF NOT p_attrs ? v_prop.prop_cd OR jsonb_typeof(p_attrs -> v_prop.prop_cd) = 'null' THEN
            RAISE EXCEPTION 'attribute % is required', v_prop.prop_cd USING ERRCODE = '23514', CONSTRAINT = 'ck_attrs_required';
        END IF;
    END LOOP;
END
$$;

CREATE OR REPLACE FUNCTION hb_check_item_attrs() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.item_class_id IS NOT NULL AND (TG_OP = 'INSERT' OR NEW.item_class_id IS DISTINCT FROM OLD.item_class_id)
       AND NOT EXISTS (SELECT 1 FROM mst_item_class c WHERE c.class_id = NEW.item_class_id AND c.comp_cd = NEW.comp_cd AND c.use_yn = 'Y') THEN
        RAISE EXCEPTION 'item class % is not an active class of company %', NEW.item_class_id, NEW.comp_cd
            USING ERRCODE = '23503', CONSTRAINT = 'fk_mst_item_class_comp';
    END IF;
    IF TG_OP = 'INSERT' OR NEW.attrs IS DISTINCT FROM OLD.attrs OR NEW.item_class_id IS DISTINCT FROM OLD.item_class_id THEN
        -- 품목은 클래스·값을 함께 저장한다(속성 저장) — 바뀌면 필수까지. 클래스가 같으면 바뀌지 않은 값은 둔다
        PERFORM hb_check_attrs(NEW.comp_cd, 'ITEM', NEW.item_class_id, NEW.attrs, true,
                               CASE WHEN TG_OP = 'UPDATE' AND NEW.item_class_id IS NOT DISTINCT FROM OLD.item_class_id
                                    THEN OLD.attrs END);
    END IF;
    RETURN NEW;
END
$$;

CREATE OR REPLACE FUNCTION hb_check_equip_attrs() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    -- 설비는 폼에서 먼저 만들고(클래스 포함) 속성은 나중에 넣는다 — 필수 검사는 속성 값을 저장할 때만.
    -- 클래스만 바꿀 때는 지금 값이 새 클래스 정의에 맞는지(키·형식)만 본다. 클래스가 같으면 바뀌지 않은 값은 둔다
    IF TG_OP = 'INSERT' OR NEW.attrs IS DISTINCT FROM OLD.attrs OR NEW.class_id IS DISTINCT FROM OLD.class_id THEN
        PERFORM hb_check_attrs(NEW.comp_cd, 'EQUIP', NEW.class_id, NEW.attrs,
                               TG_OP = 'UPDATE' AND NEW.attrs IS DISTINCT FROM OLD.attrs,
                               CASE WHEN TG_OP = 'UPDATE' AND NEW.class_id IS NOT DISTINCT FROM OLD.class_id THEN OLD.attrs END);
    END IF;
    RETURN NEW;
END
$$;

DROP FUNCTION hb_check_attrs(varchar, varchar, bigint, jsonb, boolean);
