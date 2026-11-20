package io.smarthappy.module.qms;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import io.smarthappy.module.qms.SpcDtos.ChartUnit;
import io.smarthappy.module.qms.SpcDtos.LimitRow;
import io.smarthappy.module.qms.SpcDtos.MeasVal;
import io.smarthappy.module.qms.SpcDtos.SubgroupHead;
import io.smarthappy.module.qms.SpcDtos.UnitHead;
import io.smarthappy.platform.common.query.PageQuery;

@Mapper
public interface SpcMapper {

    List<ChartUnit> findUnits(@Param("keywordLike") String keywordLike, @Param("limit") int limit);

    List<ChartUnit> findUnitGrid(@Param("keywordLike") String keywordLike, @Param("page") PageQuery page);

    long countUnits(@Param("keywordLike") String keywordLike);

    UnitHead findUnitHead(@Param("itemId") long itemId, @Param("charId") long charId, @Param("procId") Long procId);

    /** 최근 부분군(최근 것부터) — 판정 확정 검사, 살아 있는 값이 하나라도 있는 검사 행 */
    List<SubgroupHead> findSubgroups(@Param("itemId") long itemId, @Param("charId") long charId, @Param("inspType") String inspType,
            @Param("procId") Long procId, @Param("to") OffsetDateTime to, @Param("limit") int limit);

    /** p 관리도 부분군(최근 것부터) — 판정 확정 검사의 계수형 행, n·d = 살아 있는 OK/NG 샘플 수·NG 수 */
    List<SubgroupHead> findPSubgroups(@Param("itemId") long itemId, @Param("charId") long charId, @Param("inspType") String inspType,
            @Param("procId") Long procId, @Param("to") OffsetDateTime to, @Param("limit") int limit);

    /** 살아 있는 측정값(무효 행·무효된 원래 행 제외), 샘플 순 */
    List<MeasVal> findValues(@Param("lineIds") Collection<Long> lineIds);

    LimitRow findLiveLimit(@Param("itemId") long itemId, @Param("charId") long charId, @Param("inspType") String inspType,
            @Param("procId") Long procId);

    LimitRow findLimit(@Param("id") long id);

    List<LimitRow> findLimits(@Param("itemId") long itemId, @Param("charId") long charId, @Param("inspType") String inspType,
            @Param("procId") Long procId);

    long insertLimit(@Param("itemId") long itemId, @Param("charId") long charId, @Param("inspType") String inspType,
            @Param("procId") Long procId, @Param("center") BigDecimal center, @Param("sigma") BigDecimal sigma,
            @Param("subgroupCnt") int subgroupCnt, @Param("fromAt") OffsetDateTime fromAt, @Param("toAt") OffsetDateTime toAt,
            @Param("rules") String rules, @Param("chartType") String chartType, @Param("remark") String remark, @Param("by") String by);

    int voidLimit(@Param("id") long id, @Param("reason") String reason, @Param("rowVer") int rowVer, @Param("by") String by);
}
