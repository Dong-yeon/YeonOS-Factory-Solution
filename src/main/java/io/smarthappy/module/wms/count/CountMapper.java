package io.smarthappy.module.wms.count;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import io.smarthappy.module.wms.count.CountDtos.CountHead;
import io.smarthappy.module.wms.count.CountDtos.LineRow;
import io.smarthappy.module.wms.count.CountDtos.PeriodRow;

@Mapper
public interface CountMapper {

    // ---- 참조
    boolean plantExists(@Param("plantCd") String plantCd);

    /** 같은 공장의 WH·LOC 이고 (scope 가 있으면) 그 하위인지 */
    boolean nodeIn(@Param("nodeId") long nodeId, @Param("plantCd") String plantCd, @Param("scopeId") Long scopeId);

    Long itemIdByCode(@Param("itemCd") String itemCd);

    String lotCtrlYn(@Param("itemId") long itemId);

    CountDtos.LotRef lotByNo(@Param("lotNo") String lotNo);

    // ---- 실사 머리
    long insertCount(@Param("plantCd") String plantCd, @Param("countNo") String countNo, @Param("countType") String countType,
            @Param("scopeNodeId") long scopeNodeId, @Param("itemId") Long itemId, @Param("blindYn") String blindYn,
            @Param("tolPct") BigDecimal tolPct, @Param("remark") String remark, @Param("by") String by);

    List<CountHead> findCountPage(@Param("plantCd") String plantCd, @Param("status") String status,
            @Param("page") io.smarthappy.platform.common.query.PageQuery page);

    long countCounts(@Param("plantCd") String plantCd, @Param("status") String status);

    List<CountHead> findCounts(@Param("plantCd") String plantCd, @Param("status") String status, @Param("limit") int limit);

    CountHead findCount(@Param("id") long id);

    CountHead lockCount(@Param("id") long id);

    int updateStatus(@Param("id") long id, @Param("rowVer") int rowVer, @Param("from") String from, @Param("to") String to,
            @Param("by") String by);

    int markStarted(@Param("id") long id, @Param("by") String by);

    int markPosted(@Param("id") long id, @Param("by") String by);

    int markCancelled(@Param("id") long id, @Param("reason") String reason, @Param("by") String by);

    int touch(@Param("id") long id, @Param("rowVer") int rowVer, @Param("by") String by);

    // ---- 실사 줄
    int generateLines(@Param("countId") long countId, @Param("plantCd") String plantCd, @Param("scopeId") long scopeId,
            @Param("itemId") Long itemId, @Param("by") String by);

    long insertLine(@Param("countId") long countId, @Param("nodeId") long nodeId, @Param("itemId") long itemId,
            @Param("lotId") Long lotId, @Param("status") String status, @Param("recountNo") int recountNo, @Param("by") String by);

    List<LineRow> findLines(@Param("countId") long countId);

    LineRow findLine(@Param("countId") long countId, @Param("lineId") long lineId);

    int updateCounted(@Param("lineId") long lineId, @Param("bookQty") BigDecimal bookQty, @Param("countedQty") BigDecimal countedQty,
            @Param("countedAt") OffsetDateTime countedAt, @Param("workerId") Long workerId, @Param("by") String by);

    int linkTrx(@Param("lineId") long lineId, @Param("trxId") long trxId, @Param("by") String by);

    int countOpen(@Param("plantCd") String plantCd);

    // ---- 기간 마감
    List<PeriodRow> findPeriods(@Param("plantCd") String plantCd, @Param("fromPeriod") String fromPeriod, @Param("toPeriod") String toPeriod);

    /** 그리드 — 그 해 1~12월을 만들어 마감 행과 붙인다(행 없는 달 = 열림, 목록과 같은 행) */
    List<PeriodRow> findPeriodPage(@Param("plantCd") String plantCd, @Param("year") String year,
            @Param("page") io.smarthappy.platform.common.query.PageQuery page);

    PeriodRow findPeriod(@Param("plantCd") String plantCd, @Param("period") String period);

    String lockPeriod(@Param("plantCd") String plantCd, @Param("period") String period);

    int insertClose(@Param("plantCd") String plantCd, @Param("period") String period, @Param("by") String by);

    int reclose(@Param("id") long id, @Param("rowVer") int rowVer, @Param("by") String by);

    int reopen(@Param("id") long id, @Param("rowVer") int rowVer, @Param("reason") String reason, @Param("by") String by);
}
