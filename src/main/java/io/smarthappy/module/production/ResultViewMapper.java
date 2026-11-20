package io.smarthappy.module.production;

import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.cursor.Cursor;

import io.smarthappy.module.production.ResultViewDtos.DefectSum;
import io.smarthappy.module.production.ResultViewDtos.ResultLine;
import io.smarthappy.module.production.ResultViewDtos.ResultQuery;
import io.smarthappy.platform.common.query.PageQuery;

/** 사무 실적 조회 (docs/25 4.16). 회사 조건은 RLS. 보기는 서버 enum → <choose>(문자열 치환 없음) */
@Mapper
public interface ResultViewMapper {

    List<ResultLine> rows(@Param("q") ResultQuery q, @Param("limit") Integer limit);

    /** 그리드 한 쪽 — 정렬은 PageQuery(보기별 화이트리스트 enum), 마지막에 보기별 고정 키 */
    List<ResultLine> page(@Param("q") ResultQuery q, @Param("page") PageQuery page);

    Cursor<ResultLine> cursor(@Param("q") ResultQuery q, @Param("limit") Integer limit);

    long count(@Param("q") ResultQuery q);

    List<DefectSum> defects(@Param("q") ResultQuery q);

    Long findWorkerId(@Param("workerCd") String workerCd);
}
