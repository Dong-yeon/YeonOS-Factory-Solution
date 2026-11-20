package io.smarthappy.module.wms.loccheck;

import java.time.LocalDate;
import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import io.smarthappy.module.wms.loccheck.LocCheckDtos.Book;
import io.smarthappy.module.wms.loccheck.LocCheckDtos.Head;
import io.smarthappy.module.wms.loccheck.LocCheckDtos.Line;
import io.smarthappy.module.wms.loccheck.LocCheckDtos.Missing;
import io.smarthappy.module.wms.loccheck.LocCheckDtos.NewLine;

/** 정위치 점검 (docs/55) — 회사 범위는 RLS */
@Mapper
public interface LocCheckMapper {

    boolean isStockNode(@Param("nodeId") long nodeId, @Param("plantCd") String plantCd);

    int insertHead(@Param("plantCd") String plantCd, @Param("nodeId") long nodeId, @Param("checkDate") LocalDate checkDate,
            @Param("workerId") Long workerId, @Param("by") String by);

    Head lockHeadByDay(@Param("nodeId") long nodeId, @Param("checkDate") LocalDate checkDate);

    Head lockHead(@Param("id") long id);

    Head findHead(@Param("id") long id);

    List<Line> findLines(@Param("checkId") long checkId);

    Line findLine(@Param("lineId") long lineId);

    Long lockLine(@Param("lineId") long lineId);

    Long lineByReq(@Param("req") String req);

    Long sameScan(@Param("checkId") long checkId, @Param("itemId") long itemId, @Param("lotId") Long lotId);

    String itemLotCtrl(@Param("itemId") long itemId);

    List<Book> bookOf(@Param("plantCd") String plantCd, @Param("itemId") long itemId, @Param("lotId") Long lotId);

    List<Missing> missingOf(@Param("checkId") long checkId, @Param("nodeId") long nodeId);

    int insertLine(@Param("r") NewLine r);

    int done(@Param("id") long id, @Param("by") String by);

    int review(@Param("lineId") long lineId, @Param("action") String action, @Param("reason") String reason, @Param("grp") String grp,
            @Param("by") String by);

    List<Head> findPage(@Param("plantCd") String plantCd, @Param("from") LocalDate from, @Param("to") LocalDate to,
            @Param("status") String status, @Param("openOnly") boolean openOnly, @Param("page") io.smarthappy.platform.common.query.PageQuery page);

    long count(@Param("plantCd") String plantCd, @Param("from") LocalDate from, @Param("to") LocalDate to, @Param("status") String status,
            @Param("openOnly") boolean openOnly);

    LocCheckDtos.CardSummary cardSummary(@Param("plantCd") String plantCd, @Param("from") LocalDate from, @Param("to") LocalDate to);

    List<LocCheckDtos.CardRow> cardOpen(@Param("plantCd") String plantCd, @Param("limit") int limit);

    String nodeUsage(@Param("nodeId") long nodeId, @Param("plantCd") String plantCd);
}
