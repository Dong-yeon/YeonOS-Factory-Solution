package io.smarthappy.module.master.workforce;

import java.time.LocalDate;
import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import io.smarthappy.module.master.workforce.WorkCalDtos.CalRow;
import io.smarthappy.module.master.workforce.WorkCalDtos.CalValue;
import io.smarthappy.platform.common.query.PageQuery;

/** 공장 휴일 달력 (docs/25 4.15). 회사 조건은 RLS */
@Mapper
public interface WorkCalMapper {

    /** 기간의 사용 중 예외 — 회사 공통 + (plantCd 가 있으면) 그 공장 */
    List<CalRow> findActive(@Param("plantCd") String plantCd, @Param("from") LocalDate from, @Param("to") LocalDate to);

    /** 예외 목록 그리드 — findActive 와 같은 조건 + 페이지·정렬 */
    List<CalRow> findActivePage(@Param("plantCd") String plantCd, @Param("from") LocalDate from, @Param("to") LocalDate to,
            @Param("page") PageQuery page);

    long countActive(@Param("plantCd") String plantCd, @Param("from") LocalDate from, @Param("to") LocalDate to);

    CalRow findById(@Param("id") long id);

    long insert(@Param("f") CalValue f, @Param("by") String by);

    int update(@Param("id") long id, @Param("dayType") String dayType, @Param("reason") String reason, @Param("rowVer") int rowVer,
            @Param("by") String by);

    int disable(@Param("id") long id, @Param("rowVer") int rowVer, @Param("by") String by);
}
