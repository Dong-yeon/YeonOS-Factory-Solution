package io.smarthappy.module.qms;

import java.util.Collection;
import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import io.smarthappy.module.qms.SetupDtos.Counts;
import io.smarthappy.module.qms.SetupDtos.InspOption;
import io.smarthappy.module.qms.SetupDtos.InspRef;
import io.smarthappy.module.qms.SetupDtos.VerdictRow;

@Mapper
public interface SetupMapper {

    List<VerdictRow> findRecent(@Param("limit") int limit);

    List<VerdictRow> findActive(@Param("ecoId") long ecoId);

    VerdictRow findById(@Param("id") long id);

    List<InspOption> judgedInsps(@Param("lotIds") Collection<Long> lotIds);

    InspRef findInsp(@Param("id") long id);

    long insert(@Param("ecoId") long ecoId, @Param("lotId") long lotId, @Param("inspId") long inspId, @Param("result") String result,
            @Param("note") String note, @Param("by") String by);

    int voidVerdict(@Param("id") long id, @Param("reason") String reason, @Param("rowVer") int rowVer, @Param("by") String by);

    /** ECO 의 살아 있는 판정 OK·NG 수 — 요약 계약 ③ 용 */
    Counts counts(@Param("ecoId") long ecoId);
}
