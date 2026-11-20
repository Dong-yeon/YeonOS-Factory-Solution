package io.smarthappy.module.cms;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import io.smarthappy.module.cms.CmsCounterDtos.CounterRow;
import io.smarthappy.module.cms.CmsCounterDtos.ReadingRow;
import io.smarthappy.platform.common.query.PageQuery;

/** CMS 1부 C3 (docs/33 4.4) */
@Mapper
public interface CmsCounterMapper {

    List<CounterRow> findCounters(@Param("keyword") String keyword, @Param("useYn") String useYn, @Param("targetType") String targetType,
            @Param("equipId") Long equipId, @Param("assetId") Long assetId, @Param("limit") int limit);

    /** grid(UI 단계 3 ①) — 목록과 같은 조건(대상 한정 없이) + 페이지·정렬(허용 목록) */
    List<CounterRow> findCounterPage(@Param("keyword") String keyword, @Param("useYn") String useYn, @Param("targetType") String targetType,
            @Param("equipId") Long equipId, @Param("assetId") Long assetId, @Param("page") PageQuery page);

    long countCounters(@Param("keyword") String keyword, @Param("useYn") String useYn, @Param("targetType") String targetType,
            @Param("equipId") Long equipId, @Param("assetId") Long assetId);

    CounterRow findCounter(@Param("id") long id);

    CounterRow findCounterByCd(@Param("counterCd") String counterCd);

    /** 같은 카운터 입력·무효를 줄 세운다 */
    Long lockCounter(@Param("id") long id);

    long insertCounter(@Param("c") CounterRow c, @Param("by") String by);

    int updateCounter(@Param("c") CounterRow c, @Param("by") String by);

    /** 마지막 읽기부터 limit 행, 각 행까지의 누적 포함 */
    List<ReadingRow> findReadings(@Param("counterId") long counterId, @Param("limit") int limit);

    ReadingRow findReading(@Param("counterId") long counterId, @Param("readingId") long readingId);

    long insertReading(@Param("counterId") long counterId, @Param("readAt") OffsetDateTime readAt, @Param("gauge") BigDecimal gauge,
            @Param("delta") BigDecimal delta, @Param("resetYn") String resetYn, @Param("remark") String remark, @Param("by") String by);

    int voidReading(@Param("readingId") long readingId, @Param("reason") String reason, @Param("by") String by);

    /** 누적(무효 제외) */
    BigDecimal total(@Param("counterId") long counterId);

    /** from 이후(포함) 증가량 합 — 2부 KPI·자동 입력 검산용 */
    BigDecimal totalSince(@Param("counterId") long counterId, @Param("from") OffsetDateTime from);
}
