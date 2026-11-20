package io.smarthappy.module.wms.count;

import java.time.OffsetDateTime;
import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 홈 카드 집계 (docs/37 17.3) — 마감 안 된 실사. 회사 조건은 RLS, plantCd null = 전체 공장 */
@Mapper
public interface WmsHomeMapper {

    CountOpenSummary countOpenSummary(@Param("plantCd") String plantCd);

    List<CountOpenRow> countOpenTop(@Param("plantCd") String plantCd, @Param("limit") int limit);

    record CountOpenSummary(long openCnt, long reviewCnt) {
    }

    record CountOpenRow(String countNo, String plantCd, String status, OffsetDateTime createdAt) {
    }
}
