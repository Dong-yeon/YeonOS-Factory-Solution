package io.smarthappy.module.sales;

import java.time.LocalDate;
import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 홈 카드용 집계 (docs/37 6장) — 수주 매퍼와 나눠 둔다 */
@Mapper
public interface SalesHomeMapper {

    /** 열린 수주 행 중 납기 ≤ until — 지남(< today)·오늘·그 뒤로 나눠 센다 */
    DueSummary dueSummary(@Param("today") LocalDate today, @Param("until") LocalDate until);

    List<DueRow> dueTop(@Param("until") LocalDate until, @Param("limit") int limit);

    record DueSummary(long overdueCnt, long todayCnt, long soonCnt) {
    }

    record DueRow(String orderNo, int lineNo, String custNm, String itemCd, String qty, String unitCd, LocalDate dueDate,
            LocalDate orderDate) {
    }
}
