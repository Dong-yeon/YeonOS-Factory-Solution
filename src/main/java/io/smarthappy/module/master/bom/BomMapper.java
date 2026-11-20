package io.smarthappy.module.master.bom;

import java.time.LocalDate;
import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.cursor.Cursor;

import io.smarthappy.module.master.bom.BomDtos.BomRow;
import io.smarthappy.module.master.bom.BomDtos.BomSearch;
import io.smarthappy.module.master.bom.BomDtos.ConvRow;
import io.smarthappy.module.master.bom.BomDtos.HeaderValue;
import io.smarthappy.module.master.bom.BomDtos.ItemLookup;
import io.smarthappy.module.master.bom.BomDtos.LineRow;
import io.smarthappy.module.master.bom.BomDtos.LineValue;

@Mapper
public interface BomMapper {

    List<BomRow> findPage(@Param("q") BomSearch q);

    long count(@Param("q") BomSearch q);

    Cursor<BomRow> cursor(@Param("q") BomSearch q);

    BomRow findById(@Param("id") long id);

    List<LineRow> findLines(@Param("bomId") long bomId);

    ItemLookup findItemByCd(@Param("itemCd") String itemCd);

    ItemLookup findItemById(@Param("itemId") long itemId);

    List<String> findRevs(@Param("itemId") long itemId, @Param("altNo") int altNo);

    Long findDraftId(@Param("itemId") long itemId, @Param("altNo") int altNo);

    long insertHeader(@Param("f") HeaderValue f, @Param("by") String by);

    int updateHeader(@Param("id") long id, @Param("f") HeaderValue f, @Param("by") String by);

    /** 머리 버전·변경 시각을 올린다 (행 저장도 머리의 변경 — 동시 편집 감지). DRAFT 만 */
    int touch(@Param("id") long id, @Param("rowVer") int rowVer, @Param("by") String by);

    long insertLine(@Param("bomId") long bomId, @Param("l") LineValue l, @Param("by") String by);

    int updateLine(@Param("bomId") long bomId, @Param("l") LineValue l, @Param("by") String by);

    int deleteLine(@Param("bomId") long bomId, @Param("lineId") long lineId, @Param("rowVer") int rowVer);

    /** 자품목 아래(미폐기 모든 리비전)에 모품목이 있는지 — DB 트리거와 같은 규칙, 친절한 행 오류용 */
    boolean wouldCycle(@Param("parentItemId") long parentItemId, @Param("childItemId") long childItemId);

    /** 같은 모품목·대체 번호의 확정본 중 [from, to) 와 겹치는 것 */
    List<BomRow> findReleasedOverlaps(@Param("itemId") long itemId, @Param("altNo") int altNo,
            @Param("from") LocalDate from, @Param("to") LocalDate to, @Param("exceptId") long exceptId);

    int closeValidTo(@Param("id") long id, @Param("validTo") LocalDate validTo, @Param("by") String by);

    int release(@Param("id") long id, @Param("rowVer") int rowVer, @Param("by") String by);

    int obsolete(@Param("id") long id, @Param("rowVer") int rowVer, @Param("by") String by);

    /** 새 리비전: 원본 행을 그대로 복사(행마다 순환 트리거를 지난다) */
    int copyLines(@Param("fromBomId") long fromBomId, @Param("toBomId") long toBomId, @Param("by") String by);

    /** 행 단위 → 자품목 기본 단위 환산이 있는지 (같은 단위면 true) */
    boolean convertible(@Param("itemId") long itemId, @Param("fromUnit") String fromUnit);

    boolean unitUsable(@Param("unitCd") String unitCd);

    // ---------- 전개·역전개 (P1 3b) ----------

    BomRow findEffective(@Param("itemId") long itemId, @Param("date") LocalDate date);

    List<io.smarthappy.module.master.bom.BomTreeDtos.ExplodeRow> explode(@Param("rootBomId") long rootBomId,
            @Param("date") LocalDate date, @Param("qty") java.math.BigDecimal qty, @Param("depthLimit") int depthLimit,
            @Param("rowLimit") int rowLimit);

    List<io.smarthappy.module.master.bom.BomTreeDtos.WhereRow> whereUsed(@Param("itemId") long itemId,
            @Param("date") LocalDate date, @Param("allRevs") boolean allRevs, @Param("depthLimit") int depthLimit,
            @Param("rowLimit") int rowLimit);

    // ---------- 변경 도구 (P1 3c) ----------

    List<BomChangeDtos.UseRow> bomsUsing(@Param("fromItemId") long fromItemId, @Param("toItemId") long toItemId);

    int replaceItem(@Param("bomId") long bomId, @Param("fromItemId") long fromItemId, @Param("toItemId") long toItemId,
            @Param("by") String by);

    int touchDraft(@Param("bomId") long bomId, @Param("by") String by);

    List<BomChangeDtos.Edge> edges();

    List<BomChangeDtos.LlcRow> itemsByIds(@Param("ids") java.util.Collection<Long> ids);

    // ---------- 단위 환산 ----------

    List<ConvRow> findConvs(@Param("itemId") long itemId);

    Long insertConv(@Param("itemId") long itemId, @Param("toUnit") String toUnit, @Param("fromUnit") String fromUnit,
            @Param("factor") java.math.BigDecimal factor, @Param("by") String by);

    int updateConv(@Param("itemId") long itemId, @Param("convId") long convId, @Param("rowVer") int rowVer,
            @Param("fromUnit") String fromUnit, @Param("factor") java.math.BigDecimal factor, @Param("by") String by);

    int disableConv(@Param("itemId") long itemId, @Param("convId") long convId, @Param("rowVer") int rowVer,
            @Param("by") String by);

    int touchItem(@Param("itemId") long itemId, @Param("rowVer") int rowVer, @Param("by") String by);
}
