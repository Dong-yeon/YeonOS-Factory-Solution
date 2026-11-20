package io.smarthappy.module.wms.alloc;

import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import io.smarthappy.module.wms.alloc.AllocDtos.CandRow;
import io.smarthappy.module.wms.alloc.AllocDtos.ItemRow;

@Mapper
public interface AllocMapper {

    ItemRow findItem(@Param("itemId") Long itemId, @Param("itemCd") String itemCd);

    boolean plantExists(@Param("plantCd") String plantCd);

    boolean nodeOfPlant(@Param("nodeId") long nodeId, @Param("plantCd") String plantCd);

    List<CandRow> findCandidates(@Param("plantCd") String plantCd, @Param("itemId") long itemId, @Param("nodeId") Long nodeId);
}
