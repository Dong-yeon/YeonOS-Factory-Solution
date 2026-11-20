-- =====================================================================
-- V028 라우팅 새 리비전: 사용 중지된 공정·작업장 참조도 복사 (QA 5 결함 #5 — docs/qa/bom-routing-3b-5.md)
--
-- V022 hb_guard_routing_step 은 단계 INSERT 마다 "사용 중 공정·작업장만" 을 요구한다. 새 리비전(RoutingService.newRevision)
-- 은 단계를 INSERT 로 복사하므로, 확정 라우팅이 쓰는 공정을 사용 중지(허용)하면 그 라우팅의 새 리비전이 아예 만들어지지 않았다
-- — 단종 공정을 다른 공정으로 바꾸는 개정판을 만들 길이 없었다(폐기 라우팅의 작업장 사용 중지도 같음).
-- 바꾼 규칙: 같은 품목·공장·대체 번호의 **다른 리비전이 이미 쓰는** 공정·작업장은 사용 중지여도 그대로 들일 수 있다(기존 참조 유지).
-- 처음 들이는 사용 중지 참조는 종전대로 거부. 회사·공장 일치·LINE/CELL 유형 검사는 그대로.
-- 사용 중지 참조가 남은 작성 중 라우팅은 확정 검사(RoutingService.release)가 그 단계에 procCd INACTIVE·nodeId WORK_CENTER 로 알린다
-- (BOM 의 사용 중지 자품목과 같은 흐름). 함수 본문은 V022 와 같고 사용 중 검사 두 곳만 바꿨다. 트리거는 그대로(함수만 교체).
-- =====================================================================

CREATE OR REPLACE FUNCTION hb_guard_routing_step() RETURNS trigger
    LANGUAGE plpgsql
AS $$
DECLARE
    v_row  mst_routing_step;
    v_head mst_routing;
    v_node mst_equip_hier;
BEGIN
    v_row := CASE WHEN TG_OP = 'DELETE' THEN OLD ELSE NEW END;
    SELECT * INTO v_head FROM mst_routing WHERE routing_id = v_row.routing_id;
    IF NOT FOUND OR v_head.comp_cd <> v_row.comp_cd THEN
        RAISE EXCEPTION 'routing % does not belong to company %', v_row.routing_id, v_row.comp_cd
            USING ERRCODE = '23503', CONSTRAINT = 'fk_mst_routing_step_routing_comp';
    END IF;
    IF v_head.status <> 'DRAFT' THEN
        RAISE EXCEPTION 'steps of % routing % cannot be changed (create a new revision)', v_head.status, v_row.routing_id
            USING ERRCODE = '55000', CONSTRAINT = 'ck_mst_routing_step_draft';
    END IF;
    IF TG_OP = 'DELETE' THEN
        RETURN OLD;
    END IF;
    IF TG_OP = 'UPDATE' AND (NEW.routing_id <> OLD.routing_id OR NEW.comp_cd <> OLD.comp_cd) THEN
        RAISE EXCEPTION 'step cannot move to another routing' USING ERRCODE = '55000', CONSTRAINT = 'ck_mst_routing_step_move';
    END IF;
    IF (TG_OP = 'INSERT' OR NEW.proc_id <> OLD.proc_id) AND NOT EXISTS (
           SELECT 1 FROM mst_proc p WHERE p.proc_id = NEW.proc_id AND p.comp_cd = NEW.comp_cd
              AND (p.use_yn = 'Y' OR EXISTS (
                   -- 기존 참조 유지: 같은 품목·공장·대체의 다른 리비전이 이미 쓰는 공정
                   SELECT 1 FROM mst_routing_step s JOIN mst_routing r ON r.routing_id = s.routing_id
                    WHERE s.proc_id = NEW.proc_id AND r.routing_id <> NEW.routing_id AND r.item_id = v_head.item_id
                      AND r.plant_cd = v_head.plant_cd AND r.alt_no = v_head.alt_no))) THEN
        RAISE EXCEPTION 'process % is not an active process of company %', NEW.proc_id, NEW.comp_cd
            USING ERRCODE = '23503', CONSTRAINT = 'fk_mst_routing_step_proc_comp';
    END IF;
    IF NEW.node_id IS NOT NULL AND (TG_OP = 'INSERT' OR NEW.node_id IS DISTINCT FROM OLD.node_id) THEN
        SELECT * INTO v_node FROM mst_equip_hier WHERE node_id = NEW.node_id;
        IF NOT FOUND OR v_node.comp_cd <> NEW.comp_cd OR v_node.plant_cd <> v_head.plant_cd THEN
            RAISE EXCEPTION 'work center % is not in plant %/%', NEW.node_id, NEW.comp_cd, v_head.plant_cd
                USING ERRCODE = '23503', CONSTRAINT = 'fk_mst_routing_step_node_plant';
        END IF;
        IF v_node.node_type NOT IN ('LINE', 'CELL') OR (v_node.use_yn <> 'Y' AND NOT EXISTS (
               -- 기존 참조 유지: 같은 품목·공장·대체의 다른 리비전이 이미 쓰는 작업장
               SELECT 1 FROM mst_routing_step s JOIN mst_routing r ON r.routing_id = s.routing_id
                WHERE s.node_id = NEW.node_id AND r.routing_id <> NEW.routing_id AND r.item_id = v_head.item_id
                  AND r.plant_cd = v_head.plant_cd AND r.alt_no = v_head.alt_no)) THEN
            RAISE EXCEPTION 'work center must be an active LINE or CELL' USING ERRCODE = '23514', CONSTRAINT = 'ck_mst_routing_step_node_type';
        END IF;
    END IF;
    RETURN NEW;
END
$$;
