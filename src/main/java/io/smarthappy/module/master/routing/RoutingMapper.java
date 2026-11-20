package io.smarthappy.module.master.routing;

import java.time.LocalDate;
import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.cursor.Cursor;

import io.smarthappy.module.master.routing.RoutingDtos.BomOpRow;
import io.smarthappy.module.master.routing.RoutingDtos.HeaderValue;
import io.smarthappy.module.master.routing.RoutingDtos.ItemLookup;
import io.smarthappy.module.master.routing.RoutingDtos.ParamRow;
import io.smarthappy.module.master.routing.RoutingDtos.ParamValue;
import io.smarthappy.module.master.routing.RoutingDtos.PlaceRow;
import io.smarthappy.module.master.routing.RoutingDtos.ProcForm;
import io.smarthappy.module.master.routing.RoutingDtos.ProcRow;
import io.smarthappy.module.master.routing.RoutingDtos.RoutingRow;
import io.smarthappy.module.master.routing.RoutingDtos.RoutingSearch;
import io.smarthappy.module.master.routing.RoutingDtos.StepRow;
import io.smarthappy.module.master.routing.RoutingDtos.StepValue;
import io.smarthappy.platform.common.query.PageQuery;

@Mapper
public interface RoutingMapper {

    // ---------- 공정 ----------

    List<ProcRow> findProcs(@Param("useYn") String useYn);

    List<ProcRow> findProcPage(@Param("useYn") String useYn, @Param("kw") String keywordLike, @Param("page") PageQuery page);

    long countProcs(@Param("useYn") String useYn, @Param("kw") String keywordLike);

    ProcRow findProc(@Param("procId") long procId);

    ProcRow findProcByCd(@Param("procCd") String procCd);

    long insertProc(@Param("f") ProcForm f, @Param("by") String by);

    int updateProc(@Param("id") long id, @Param("f") ProcForm f, @Param("by") String by);

    // ---------- 머리 ----------

    List<RoutingRow> findPage(@Param("q") RoutingSearch q);

    long count(@Param("q") RoutingSearch q);

    Cursor<RoutingRow> cursor(@Param("q") RoutingSearch q);

    RoutingRow findById(@Param("id") long id);

    ItemLookup findItemByCd(@Param("itemCd") String itemCd);

    /** 사용 중 공장 코드인지 */
    boolean plantActive(@Param("plantCd") String plantCd);

    List<String> findRevs(@Param("itemId") long itemId, @Param("plantCd") String plantCd, @Param("altNo") int altNo);

    Long findDraftId(@Param("itemId") long itemId, @Param("plantCd") String plantCd, @Param("altNo") int altNo);

    long insertHeader(@Param("f") HeaderValue f, @Param("by") String by);

    int updateHeader(@Param("id") long id, @Param("f") HeaderValue f, @Param("by") String by);

    /** 머리 버전을 올린다 — 구조 저장도 머리의 변경(동시 편집 감지). DRAFT 만 */
    int touch(@Param("id") long id, @Param("rowVer") int rowVer, @Param("by") String by);

    List<RoutingRow> findReleasedOverlaps(@Param("itemId") long itemId, @Param("plantCd") String plantCd,
            @Param("altNo") int altNo, @Param("from") LocalDate from, @Param("exceptId") long exceptId);

    int closeValidTo(@Param("id") long id, @Param("validTo") LocalDate validTo, @Param("by") String by);

    int release(@Param("id") long id, @Param("rowVer") int rowVer, @Param("by") String by);

    int obsolete(@Param("id") long id, @Param("rowVer") int rowVer, @Param("by") String by);

    // ---------- 단계·파라미터 ----------

    List<StepRow> findSteps(@Param("routingId") long routingId);

    List<ParamRow> findParams(@Param("routingId") long routingId);

    long insertStep(@Param("routingId") long routingId, @Param("s") StepValue s, @Param("by") String by);

    int updateStep(@Param("routingId") long routingId, @Param("s") StepValue s, @Param("by") String by);

    int deleteStep(@Param("routingId") long routingId, @Param("stepId") long stepId);

    long insertParam(@Param("stepId") long stepId, @Param("p") ParamValue p, @Param("by") String by);

    int updateParam(@Param("stepId") long stepId, @Param("p") ParamValue p, @Param("by") String by);

    int deleteParam(@Param("paramId") long paramId);

    int deleteParamsOfStep(@Param("stepId") long stepId);

    /** 새 리비전: 단계·파라미터 복사 (단계마다 트리거를 지난다) */
    int copySteps(@Param("fromId") long fromId, @Param("toId") long toId, @Param("by") String by);

    int copyParams(@Param("fromId") long fromId, @Param("toId") long toId, @Param("by") String by);

    // ---------- 찾기·점검 ----------

    List<PlaceRow> findPlaces(@Param("plantCd") String plantCd);

    /** 노드 한 건 (같은 회사 RLS). 공장·유형·사용 여부는 서비스가 본다 */
    PlaceRow findPlace(@Param("nodeId") long nodeId);

    List<io.smarthappy.module.master.equip.EquipDtos.PlantRow> findPlants();

    List<BomOpRow> unmatchedBomOps(@Param("routingId") long routingId, @Param("itemId") long itemId,
            @Param("date") LocalDate date);
}
