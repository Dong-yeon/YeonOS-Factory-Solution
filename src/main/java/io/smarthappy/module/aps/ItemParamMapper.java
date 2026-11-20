package io.smarthappy.module.aps;

import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import io.smarthappy.module.aps.ItemParamDtos.ParamCrit;
import io.smarthappy.module.aps.ItemParamDtos.ParamRow;
import io.smarthappy.module.aps.ItemParamDtos.ParamValue;
import io.smarthappy.module.aps.MpsDtos.ItemLookup;
import io.smarthappy.platform.common.query.PageQuery;

/** 계획 기준정보 (docs/38 17장) — 회사 조건은 RLS */
@Mapper
public interface ItemParamMapper {

    List<ParamRow> findPage(@Param("c") ParamCrit c, @Param("page") PageQuery page);

    long count(@Param("c") ParamCrit c);

    ParamRow find(@Param("id") long paramId);

    ItemLookup lookupItem(@Param("itemCd") String itemCd);

    String plantUseYn(@Param("plantCd") String plantCd);

    List<io.smarthappy.module.master.equip.EquipDtos.PlantRow> findPlants();

    long insert(@Param("v") ParamValue v, @Param("by") String by);

    int update(@Param("id") long paramId, @Param("v") ParamValue v, @Param("rowVer") int rowVer, @Param("by") String by);
}
