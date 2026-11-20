package io.smarthappy.module.wms.loc;

import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import io.smarthappy.module.wms.loc.LocAttrDtos.AttrForm;
import io.smarthappy.module.wms.loc.LocAttrDtos.ItemHit;
import io.smarthappy.module.wms.loc.LocAttrDtos.LocRow;
import io.smarthappy.module.wms.loc.LocAttrDtos.NodeHit;
import io.smarthappy.module.wms.loc.LocAttrDtos.PlantRow;

@Mapper
public interface LocAttrMapper {

    List<PlantRow> findPlants();

    /** 공장의 사용 중 LINE·CELL(보급 라인 선택지) */
    List<LocAttrDtos.LineOpt> findLines(@Param("plantCd") String plantCd);

    boolean plantExists(@Param("plantCd") String plantCd);

    List<LocRow> findTree(@Param("plantCd") String plantCd, @Param("withUnused") boolean withUnused);

    NodeHit findNode(@Param("nodeId") long nodeId);

    NodeHit findNodeByCode(@Param("plantCd") String plantCd, @Param("nodeCd") String nodeCd);

    ItemHit findItemByCode(@Param("itemCd") String itemCd);

    /** 하위 창고·로케이션 (자기 제외, 트리 순서) */
    List<NodeHit> findDescendants(@Param("nodeId") long nodeId);

    int insertAttr(@Param("nodeId") long nodeId, @Param("f") AttrForm f, @Param("by") String by);

    int updateAttr(@Param("nodeId") long nodeId, @Param("f") AttrForm f, @Param("by") String by);

    int upsertShared(@Param("nodeId") long nodeId, @Param("f") AttrForm f, @Param("by") String by);

    int upsertPickSeq(@Param("nodeId") long nodeId, @Param("seq") int seq, @Param("by") String by);

    /** 부모 행 잠금 — 하위 일괄 반영(하위 적용·피킹 순서 다시 매기기)을 같은 부모끼리 줄 세운다 */
    String lockNode(@Param("nodeId") long nodeId);
}
