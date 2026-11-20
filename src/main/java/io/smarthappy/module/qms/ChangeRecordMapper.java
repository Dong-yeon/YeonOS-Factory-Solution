package io.smarthappy.module.qms;

import java.time.OffsetDateTime;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import io.smarthappy.module.qms.ChangeRecordDtos.EffectChar;
import io.smarthappy.module.qms.ChangeRecordDtos.EffectRow;
import io.smarthappy.module.qms.ChangeRecordDtos.ItemCounts;
import io.smarthappy.module.qms.ChangeRecordDtos.MeasPoint;
import io.smarthappy.module.qms.ChangeRecordDtos.RevertRow;

/** QMS 4M 3차 복귀·효과 (docs/29_P3 51장) — plm_eco 는 읽지 않는다(ECO 머리는 계약 EcoLookup) */
@Mapper
public interface ChangeRecordMapper {

    List<RevertRow> findReverts(@Param("ecoId") long ecoId);

    List<EffectRow> findEffects(@Param("ecoId") long ecoId);

    RevertRow findRevert(@Param("id") long id);

    EffectRow findEffect(@Param("id") long id);

    long insertRevert(@Param("ecoId") long ecoId, @Param("type") String type, @Param("on") LocalDate on, @Param("note") String note,
            @Param("by") String by);

    long insertEffect(@Param("ecoId") long ecoId, @Param("result") String result, @Param("note") String note, @Param("base") LocalDate base,
            @Param("n") int n, @Param("days") int days, @Param("by") String by);

    int voidRevert(@Param("id") long id, @Param("rowVer") int rowVer, @Param("reason") String reason, @Param("by") String by);

    int voidEffect(@Param("id") long id, @Param("rowVer") int rowVer, @Param("reason") String reason, @Param("by") String by);

    /** 복귀 기한 후보 — 복귀 조건 글이 있고 살아 있는 복귀 기록이 없는 4M 세부(임시 여부·적용·표시 끝은 계약으로 거른다) */
    List<Long> revertCandidates(@Param("limit") int limit);

    List<EffectChar> effectChars(@Param("ecoId") long ecoId);

    /** 살아 있는 측정값 — after = false 면 base 앞 마지막 n개, true 면 base 부터 처음 n개(판정 확정 검사만) */
    List<MeasPoint> meas(@Param("charId") long charId, @Param("dataType") String dataType, @Param("itemIds") Collection<Long> itemIds,
            @Param("base") OffsetDateTime base, @Param("after") boolean after, @Param("n") int n);

    List<ItemCounts> itemCounts(@Param("itemIds") Collection<Long> itemIds, @Param("from") OffsetDateTime from, @Param("to") OffsetDateTime to);
}
