package io.smarthappy.module.wms.shipnote;

import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import io.smarthappy.module.wms.shipnote.ShipNoteDtos.ClassOpt;
import io.smarthappy.module.wms.shipnote.ShipNoteDtos.MatchRow;
import io.smarthappy.module.wms.shipnote.ShipNoteDtos.Note;
import io.smarthappy.platform.common.query.PageQuery;

@Mapper
public interface ShipNoteMapper {

    List<Note> findPage(@Param("q") String q, @Param("useYn") String useYn, @Param("ackRequiredYn") String ackRequiredYn,
            @Param("page") PageQuery page);

    long count(@Param("q") String q, @Param("useYn") String useYn, @Param("ackRequiredYn") String ackRequiredYn);

    Note findNote(@Param("id") long id);

    List<ClassOpt> classes();

    Long custIdByCode(@Param("custCd") String custCd);

    Long classIdByCode(@Param("classCd") String classCd);

    Long itemIdByCode(@Param("itemCd") String itemCd);

    long insert(@Param("f") Note f, @Param("by") String by);

    int update(@Param("f") Note f, @Param("rowVer") int rowVer, @Param("by") String by);

    /** 출하 지시에 걸린 사용 중 규칙 × 걸린 줄(규칙 순서·줄 순서) + 현재 버전 확인 */
    List<MatchRow> matchShip(@Param("shipId") long shipId);

    int insertAck(@Param("shipId") long shipId, @Param("noteId") long noteId, @Param("noteVer") int noteVer, @Param("workerId") Long workerId,
            @Param("by") String by);
}
