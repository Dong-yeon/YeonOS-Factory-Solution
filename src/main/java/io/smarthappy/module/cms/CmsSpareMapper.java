package io.smarthappy.module.cms;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import io.smarthappy.module.cms.CmsSpareDtos.ItemRef;
import io.smarthappy.module.cms.CmsSpareDtos.PartOption;
import io.smarthappy.module.cms.CmsSpareDtos.PartRow;
import io.smarthappy.module.cms.CmsSpareDtos.SpareRow;
import io.smarthappy.module.cms.CmsSpareDtos.UsageRow;
import io.smarthappy.platform.common.query.PageQuery;

/** CMS 1부 C4 (docs/33 4.5) */
@Mapper
public interface CmsSpareMapper {

    List<SpareRow> findSpares(@Param("keyword") String keyword, @Param("useYn") String useYn, @Param("targetKind") String targetKind,
            @Param("limit") int limit);

    /** grid(UI 단계 3 ①) — 같은 조건 + 페이지·정렬(허용 목록) */
    List<SpareRow> findSparePage(@Param("keyword") String keyword, @Param("useYn") String useYn, @Param("targetKind") String targetKind,
            @Param("page") PageQuery page);

    long countSpares(@Param("keyword") String keyword, @Param("useYn") String useYn, @Param("targetKind") String targetKind);

    SpareRow findSpare(@Param("id") long id);

    Long lockSpare(@Param("id") long id);

    long insertSpare(@Param("s") SpareRow s, @Param("by") String by);

    int updateSpare(@Param("s") SpareRow s, @Param("by") String by);

    ItemRef findItemByCd(@Param("itemCd") String itemCd);

    List<ItemRef> findItems(@Param("keyword") String keyword, @Param("limit") int limit);

    /** 설비 하나의 후보: 그 설비 연결 + 그 클래스 연결(사용 중 연결·사용 중 품목) */
    List<PartOption> partOptions(@Param("equipId") Long equipId, @Param("classId") Long classId);

    List<PartRow> findParts(@Param("mwoId") long mwoId);

    long insertPart(@Param("mwoId") long mwoId, @Param("itemId") long itemId, @Param("qty") BigDecimal qty, @Param("unitCd") String unitCd,
            @Param("remark") String remark, @Param("by") String by);

    int deletePart(@Param("mwoId") long mwoId, @Param("partId") long partId);

    /** 사용 이력 — 기록 시각 기간 + 설비·클래스·품목(선택) */
    List<UsageRow> findUsage(@Param("from") OffsetDateTime from, @Param("to") OffsetDateTime to, @Param("equipId") Long equipId,
            @Param("classId") Long classId, @Param("itemId") Long itemId, @Param("limit") int limit);
}
