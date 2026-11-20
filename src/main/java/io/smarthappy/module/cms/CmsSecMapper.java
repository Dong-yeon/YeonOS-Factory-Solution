package io.smarthappy.module.cms;

import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import io.smarthappy.module.cms.CmsSecDtos.SecRow;
import io.smarthappy.platform.common.query.PageQuery;

/** CMS 1부 C5 (docs/33 4.6) */
@Mapper
public interface CmsSecMapper {

    /** 사용 중 설비 전체 + 보안 행 (사용 중지 설비는 보안 행이 있을 때만) */
    List<SecRow> findSecs(@Param("keyword") String keyword, @Param("plantCd") String plantCd, @Param("limit") int limit);

    /** grid SQL 쪽(QA G-2) — findSecs 와 같은 조건 + 정렬(허용 목록)·쪽 */
    List<SecRow> findSecPage(@Param("keyword") String keyword, @Param("plantCd") String plantCd, @Param("sorts") List<PageQuery.Sort> sorts,
            @Param("page") PageQuery page);

    /** grid 메모리 경로(QA G-2 잔여) — 같은 조건, 저장 칸 정렬(sorts)로 전부(상한 limit) */
    List<SecRow> findSecsSorted(@Param("keyword") String keyword, @Param("plantCd") String plantCd, @Param("sorts") List<PageQuery.Sort> sorts,
            @Param("limit") int limit);

    long countSecs(@Param("keyword") String keyword, @Param("plantCd") String plantCd);

    SecRow findSec(@Param("equipId") long equipId);

    Long lockSec(@Param("equipId") long equipId);

    void insertSec(@Param("equipId") long equipId, @Param("s") SecRow s, @Param("by") String by);

    int updateSec(@Param("equipId") long equipId, @Param("s") SecRow s, @Param("by") String by);
}
