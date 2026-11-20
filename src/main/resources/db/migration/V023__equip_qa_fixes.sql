-- =====================================================================
-- V023 P1 4단계 QA 결함 수정 (docs/qa/equip-4.md #1·2·3·4·5·6·10·11) — QA 브랜치에서 V022 로 만들었으나 개발 세션 V022(라우팅)와 겹쳐 V023
--
-- V020·V021 은 적용본이라 고치지 않고 트리거 함수만 교체한다(CREATE OR REPLACE — 트리거 정의는 그대로).
--  - 장비 계층: 유형 불변(#2), 다시 사용 때 부모·공장 사용 확인(#3·#4), 새 노드는 사용 중 공장에만(#4),
--    부모 변경 때 공장 단위 advisory 잠금 후 순환·깊이(#5), 깊이는 옮기는 노드의 하위 높이까지(#1)
--  - 설비: 다시 사용(N→Y) 때도 위치·클래스 검사(#6)
--  - 설치 이력: 자산·설비 확인을 FOR SHARE 로(사용 중지와 직렬화, #11), 해체자·무효자 열은 그 전이 때만(#10),
--    recorded_at 은 기록 시각으로 덮는다(#10)
-- =====================================================================

CREATE OR REPLACE FUNCTION hb_guard_equip_hier() RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    v_parent mst_equip_hier;
    v_depth  integer;
    v_height integer := 0;
    v_cycle  boolean;
    v_reuse  boolean;
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'equipment node cannot be deleted (use use_yn = N)'
            USING ERRCODE = '55000', CONSTRAINT = 'ck_mst_equip_hier_no_delete';
    END IF;
    IF TG_OP = 'UPDATE' AND (NEW.comp_cd <> OLD.comp_cd OR NEW.plant_cd <> OLD.plant_cd) THEN
        RAISE EXCEPTION 'node % cannot move to another plant', OLD.node_id
            USING ERRCODE = '55000', CONSTRAINT = 'ck_mst_equip_hier_plant_fixed';
    END IF;
    -- 유형은 만든 뒤 불변: 바꾸면 하위·걸린 설비의 부모 규칙을 다시 보지 않게 된다(QA #2)
    IF TG_OP = 'UPDATE' AND NEW.node_type <> OLD.node_type THEN
        RAISE EXCEPTION 'node % type cannot change', OLD.node_id
            USING ERRCODE = '55000', CONSTRAINT = 'ck_mst_equip_hier_type_fixed';
    END IF;
    v_reuse := TG_OP = 'UPDATE' AND NEW.use_yn = 'Y' AND OLD.use_yn = 'N';
    -- 새 노드·다시 사용: 공장이 사용 중이어야 한다(QA #4)
    IF (TG_OP = 'INSERT' AND NEW.use_yn = 'Y') OR v_reuse THEN
        IF NOT EXISTS (SELECT 1 FROM sys_plant p WHERE p.comp_cd = NEW.comp_cd AND p.plant_cd = NEW.plant_cd AND p.use_yn = 'Y') THEN
            RAISE EXCEPTION 'plant %/% is not in use', NEW.comp_cd, NEW.plant_cd
                USING ERRCODE = '23514', CONSTRAINT = 'ck_mst_equip_hier_plant_use';
        END IF;
    END IF;
    IF NEW.parent_id IS NOT NULL THEN
        SELECT * INTO v_parent FROM mst_equip_hier WHERE node_id = NEW.parent_id;
        -- 회사별 테이블 간 참조는 RLS 를 무시하므로 같은 회사·같은 공장을 직접 확인한다
        IF NOT FOUND OR v_parent.comp_cd <> NEW.comp_cd OR v_parent.plant_cd <> NEW.plant_cd THEN
            RAISE EXCEPTION 'parent % is not in plant %/%', NEW.parent_id, NEW.comp_cd, NEW.plant_cd
                USING ERRCODE = '23503', CONSTRAINT = 'fk_mst_equip_hier_parent_plant';
        END IF;
    END IF;
    IF NOT hb_equip_parent_ok(v_parent.node_type, NEW.node_type) THEN
        RAISE EXCEPTION 'node type % cannot be under %', NEW.node_type, coalesce(v_parent.node_type, 'PLANT')
            USING ERRCODE = '23514', CONSTRAINT = 'ck_mst_equip_hier_parent_type';
    END IF;
    -- 다시 사용: 사용 중지 부모 아래 사용 중 하위 금지(QA #3 — 서비스만 막던 것)
    IF v_reuse AND NEW.parent_id IS NOT NULL AND v_parent.use_yn <> 'Y' THEN
        RAISE EXCEPTION 'parent % is not in use', NEW.parent_id
            USING ERRCODE = '23514', CONSTRAINT = 'ck_mst_equip_hier_parent_use';
    END IF;
    -- 새 부모 지정(추가·이동)일 때: 사용 중지 부모 금지, 순환, 깊이
    IF NEW.parent_id IS NOT NULL AND (TG_OP = 'INSERT' OR NEW.parent_id IS DISTINCT FROM OLD.parent_id) THEN
        IF v_parent.use_yn <> 'Y' THEN
            RAISE EXCEPTION 'parent % is not in use', NEW.parent_id
                USING ERRCODE = '23514', CONSTRAINT = 'ck_mst_equip_hier_parent_use';
        END IF;
        -- 같은 공장의 부모 변경을 한 줄로: 두 이동이 서로의 커밋 전 상태를 보고 함께 순환을 만들지 않게(QA #5).
        -- 잠금 뒤의 문장은 새 스냅숏이라 먼저 커밋된 이동을 본다
        PERFORM pg_advisory_xact_lock(hashtextextended('mst_equip_hier:' || NEW.comp_cd || ':' || NEW.plant_cd, 0));
        -- 새 부모에서 위로 올라가며 자기 자신을 만나면 순환. 깊이도 함께 센다
        WITH RECURSIVE up(node_id, parent_id, depth) AS (
            SELECT node_id, parent_id, 1 FROM mst_equip_hier WHERE node_id = NEW.parent_id
            UNION ALL
            SELECT h.node_id, h.parent_id, u.depth + 1 FROM up u JOIN mst_equip_hier h ON h.node_id = u.parent_id
             WHERE u.depth < 50 AND u.node_id <> NEW.node_id
        )
        SELECT bool_or(node_id = NEW.node_id), max(depth) INTO v_cycle, v_depth FROM up;
        IF v_cycle THEN
            RAISE EXCEPTION 'node % cannot move under its own descendant', NEW.node_id
                USING ERRCODE = '23514', CONSTRAINT = 'ck_mst_equip_hier_cycle';
        END IF;
        -- 옮기는 노드 아래 하위 높이(잎 = 0)까지 더해 10단 이하(QA #1)
        IF TG_OP = 'UPDATE' THEN
            WITH RECURSIVE down(node_id, h) AS (
                SELECT node_id, 1 FROM mst_equip_hier WHERE parent_id = NEW.node_id
                UNION ALL
                SELECT c.node_id, d.h + 1 FROM down d JOIN mst_equip_hier c ON c.parent_id = d.node_id WHERE d.h < 50
            )
            SELECT coalesce(max(h), 0) INTO v_height FROM down;
        END IF;
        IF v_depth + v_height >= 10 THEN
            RAISE EXCEPTION 'equipment tree deeper than 10 levels' USING ERRCODE = '23514', CONSTRAINT = 'ck_mst_equip_hier_depth';
        END IF;
    END IF;
    -- 사용 중지: 사용 중인 하위가 있으면 거부(고아 방지)
    IF TG_OP = 'UPDATE' AND NEW.use_yn = 'N' AND OLD.use_yn = 'Y'
       AND EXISTS (SELECT 1 FROM mst_equip_hier c WHERE c.parent_id = NEW.node_id AND c.use_yn = 'Y') THEN
        RAISE EXCEPTION 'node % has active children', NEW.node_id
            USING ERRCODE = '23514', CONSTRAINT = 'ck_mst_equip_hier_children_use';
    END IF;
    RETURN NEW;
END
$$;

CREATE OR REPLACE FUNCTION hb_guard_equip() RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    v_node  mst_equip_hier;
    v_reuse boolean;
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'equipment cannot be deleted (use use_yn = N)' USING ERRCODE = '55000', CONSTRAINT = 'ck_mst_equip_no_delete';
    END IF;
    -- 다시 사용(N→Y)도 새로 놓는 것과 같다: 그 사이 위치·클래스가 사용 중지됐을 수 있다(QA #6)
    v_reuse := TG_OP = 'UPDATE' AND NEW.use_yn = 'Y' AND OLD.use_yn = 'N';
    IF TG_OP = 'INSERT' OR NEW.class_id <> OLD.class_id OR v_reuse THEN
        IF NOT EXISTS (SELECT 1 FROM mst_equip_class c WHERE c.class_id = NEW.class_id AND c.comp_cd = NEW.comp_cd AND c.use_yn = 'Y') THEN
            RAISE EXCEPTION 'class % is not an active class of company %', NEW.class_id, NEW.comp_cd
                USING ERRCODE = '23503', CONSTRAINT = 'fk_mst_equip_class_comp';
        END IF;
    END IF;
    IF NEW.node_id IS NOT NULL AND (TG_OP = 'INSERT' OR NEW.node_id IS DISTINCT FROM OLD.node_id OR NEW.plant_cd <> OLD.plant_cd OR v_reuse) THEN
        SELECT * INTO v_node FROM mst_equip_hier WHERE node_id = NEW.node_id;
        IF NOT FOUND OR v_node.comp_cd <> NEW.comp_cd OR v_node.plant_cd <> NEW.plant_cd THEN
            RAISE EXCEPTION 'node % is not in plant %/%', NEW.node_id, NEW.comp_cd, NEW.plant_cd
                USING ERRCODE = '23503', CONSTRAINT = 'fk_mst_equip_node_plant';
        END IF;
        IF v_node.node_type NOT IN ('LINE', 'CELL') OR v_node.use_yn <> 'Y' THEN
            RAISE EXCEPTION 'equipment can be placed only on an active LINE or CELL' USING ERRCODE = '23514', CONSTRAINT = 'ck_mst_equip_node_type';
        END IF;
    END IF;
    RETURN NEW;
END
$$;

CREATE OR REPLACE FUNCTION hb_guard_asset_install() RETURNS trigger
    LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'install history cannot be deleted (void it)' USING ERRCODE = '55000', CONSTRAINT = 'ck_mst_asset_install_no_delete';
    END IF;
    IF TG_OP = 'INSERT' THEN
        -- FOR SHARE: 커밋 전 사용 중지 UPDATE 가 있으면 끝날 때까지 기다린 뒤 다시 판정한다(QA #11).
        -- 반대 순서(설치가 먼저)는 사용 중지 쪽이 대상 행을 FOR UPDATE 로 잡고 설치 수를 센다(AssetService)
        PERFORM 1 FROM mst_phys_asset a WHERE a.asset_id = NEW.asset_id AND a.comp_cd = NEW.comp_cd AND a.use_yn = 'Y' FOR SHARE;
        IF NOT FOUND THEN
            RAISE EXCEPTION 'asset/equipment not active in company %', NEW.comp_cd
                USING ERRCODE = '23503', CONSTRAINT = 'fk_mst_asset_install_comp';
        END IF;
        PERFORM 1 FROM mst_equip e WHERE e.equip_id = NEW.equip_id AND e.comp_cd = NEW.comp_cd AND e.use_yn = 'Y' FOR SHARE;
        IF NOT FOUND THEN
            RAISE EXCEPTION 'asset/equipment not active in company %', NEW.comp_cd
                USING ERRCODE = '23503', CONSTRAINT = 'fk_mst_asset_install_comp';
        END IF;
        IF NEW.void_yn <> 'N' OR NEW.voided_by IS NOT NULL OR NEW.voided_at IS NOT NULL
           OR (NEW.removed_at IS NOT NULL AND NEW.removed_by IS NULL) OR (NEW.removed_at IS NULL AND NEW.removed_by IS NOT NULL) THEN
            RAISE EXCEPTION 'new install row must not be void and removal needs its actor'
                USING ERRCODE = '55000', CONSTRAINT = 'ck_mst_asset_install_ledger';
        END IF;
        NEW.recorded_at := now();                                -- 기록 시각은 입력받지 않는다(QA #10)
        RETURN NEW;
    END IF;
    -- 허용되는 UPDATE 두 가지뿐: (1) 열린 구간 닫기(해체 시각 + 해체자) (2) 무효(사유 + 무효자·시각)
    IF NEW.comp_cd <> OLD.comp_cd OR NEW.asset_id <> OLD.asset_id OR NEW.equip_id <> OLD.equip_id
       OR NEW.installed_at <> OLD.installed_at OR NEW.installed_by <> OLD.installed_by OR NEW.recorded_at <> OLD.recorded_at
       OR NEW.remark IS DISTINCT FROM OLD.remark
       OR (OLD.removed_at IS NOT NULL AND NEW.removed_at IS DISTINCT FROM OLD.removed_at)
       OR OLD.void_yn = 'Y'
       -- 해체자는 닫는 그 UPDATE 에서만, 닫을 때는 필수(QA #10)
       OR (NEW.removed_by IS DISTINCT FROM OLD.removed_by AND NOT (OLD.removed_at IS NULL AND NEW.removed_at IS NOT NULL))
       OR (OLD.removed_at IS NULL AND NEW.removed_at IS NOT NULL AND NEW.removed_by IS NULL)
       -- 무효자·시각은 무효로 바꾸는 그 UPDATE 에서만, 그때는 필수(사유는 CHECK 가 본다)
       OR ((NEW.voided_by IS DISTINCT FROM OLD.voided_by OR NEW.voided_at IS DISTINCT FROM OLD.voided_at)
           AND NOT (OLD.void_yn = 'N' AND NEW.void_yn = 'Y'))
       OR (OLD.void_yn = 'N' AND NEW.void_yn = 'Y' AND (NEW.voided_by IS NULL OR NEW.voided_at IS NULL)) THEN
        RAISE EXCEPTION 'install history % is a ledger row (only close once or void)', OLD.install_id
            USING ERRCODE = '55000', CONSTRAINT = 'ck_mst_asset_install_ledger';
    END IF;
    RETURN NEW;
END
$$;
