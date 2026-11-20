package io.smarthappy.module.production.erp;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.cursor.Cursor;

import io.smarthappy.module.production.erp.ErpDtos.ErpRow;
import io.smarthappy.module.production.erp.ErpDtos.ExportLog;

/** ERP 연계·내보내기 (docs/25 4.11) — 회사 조건은 RLS. ERP 품목 코드 = 사용 중 ERP 시스템(코드 순 첫째)의 ITEM 매핑 */
@Mapper
public interface ErpMapper {

    ErpRow woResult(@Param("woId") long woId, @Param("zone") String zone);

    ErpRow receipt(@Param("receiptId") long receiptId, @Param("zone") String zone);

    /** @param kind RESULT / RECEIPT, 기간 [fromAt, toAt) */
    List<ErpRow> rows(@Param("kind") String kind, @Param("fromAt") Instant fromAt, @Param("toAt") Instant toAt,
            @Param("zone") String zone, @Param("limit") Integer limit);

    Cursor<ErpRow> cursor(@Param("kind") String kind, @Param("fromAt") Instant fromAt, @Param("toAt") Instant toAt,
            @Param("zone") String zone, @Param("limit") Integer limit);

    long count(@Param("kind") String kind, @Param("fromAt") Instant fromAt, @Param("toAt") Instant toAt);

    long unmapped(@Param("kind") String kind, @Param("fromAt") Instant fromAt, @Param("toAt") Instant toAt);

    /** 기간이 겹치는 이전 내보내기 (최근 순) */
    List<ExportLog> exports(@Param("kind") String kind, @Param("from") LocalDate from, @Param("to") LocalDate to,
            @Param("limit") int limit);

    int insertExport(@Param("kind") String kind, @Param("from") LocalDate from, @Param("to") LocalDate to,
            @Param("rowCnt") long rowCnt, @Param("at") Instant at, @Param("by") String by);
}
