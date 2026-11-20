package io.smarthappy.module.master.equip;

import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import io.smarthappy.module.master.equip.EquipDtos.NodeForm;
import io.smarthappy.module.master.equip.EquipDtos.NodeRow;
import io.smarthappy.module.master.equip.EquipDtos.PlantForm;
import io.smarthappy.module.master.equip.EquipDtos.PlantRow;
import io.smarthappy.platform.common.query.PageQuery;

@Mapper
public interface EquipMapper {

    List<PlantRow> findPlants(@Param("useYn") String useYn);

    List<PlantRow> findPlantPage(@Param("useYn") String useYn, @Param("page") PageQuery page);

    long countPlants(@Param("useYn") String useYn);

    PlantRow findPlant(@Param("plantCd") String plantCd);

    int insertPlant(@Param("f") PlantForm f, @Param("by") String by);

    int updatePlant(@Param("f") PlantForm f, @Param("by") String by);

    int activeNodes(@Param("plantCd") String plantCd);

    List<NodeRow> findTree(@Param("plantCd") String plantCd, @Param("withUnused") boolean withUnused);

    NodeRow findNode(@Param("nodeId") long nodeId);

    long insertNode(@Param("f") NodeForm f, @Param("by") String by);

    int updateNode(@Param("id") long id, @Param("f") NodeForm f, @Param("by") String by);
}
