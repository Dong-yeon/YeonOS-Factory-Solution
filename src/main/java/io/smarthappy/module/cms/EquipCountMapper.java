package io.smarthappy.module.cms;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import io.smarthappy.module.cms.EquipCountDtos.CountRow;
import io.smarthappy.module.cms.EquipCountDtos.EquipOption;
import io.smarthappy.platform.common.query.PageQuery;

/** 수집 COUNT 조회·정정 (docs/25 4.36). 회사 조건은 RLS */
@Mapper
public interface EquipCountMapper {

    List<EquipOption> equips(@Param("plantCd") String plantCd);

    List<CountRow> findPage(@Param("plantCd") String plantCd, @Param("equipId") Long equipId, @Param("metricCd") String metricCd,
            @Param("from") Instant from, @Param("to") Instant to, @Param("page") PageQuery page);

    long count(@Param("plantCd") String plantCd, @Param("equipId") Long equipId, @Param("metricCd") String metricCd,
            @Param("from") Instant from, @Param("to") Instant to);

    CountRow findOne(@Param("countId") long countId);

    long insertReversal(@Param("o") CountRow original, @Param("qty") BigDecimal qty, @Param("reason") String reason,
            @Param("occurredAt") OffsetDateTime occurredAt, @Param("by") String by);
}
