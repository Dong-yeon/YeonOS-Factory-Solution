package io.smarthappy.module.qms;

import java.time.LocalDate;
import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.cursor.Cursor;

import io.smarthappy.module.qms.QmsDtos.CandidateRow;
import io.smarthappy.module.qms.QmsDtos.CharChoice;
import io.smarthappy.module.qms.QmsDtos.DocChoice;
import io.smarthappy.module.qms.QmsDtos.HeaderValue;
import io.smarthappy.module.qms.QmsDtos.ItemLookup;
import io.smarthappy.module.qms.QmsDtos.LineRow;
import io.smarthappy.module.qms.QmsDtos.LineValue;
import io.smarthappy.module.qms.QmsDtos.PlanRow;
import io.smarthappy.module.qms.QmsDtos.PlanSearch;
import io.smarthappy.module.qms.QmsDtos.TargetLookup;

@Mapper
public interface InspPlanMapper {

    // ---------- 머리 ----------

    List<PlanRow> findPage(@Param("q") PlanSearch q);

    long count(@Param("q") PlanSearch q);

    Cursor<PlanRow> cursor(@Param("q") PlanSearch q);

    PlanRow findById(@Param("id") long id);

    /** 대상 키(검사 유형 × 품목|클래스 × 공정 × 대체)의 리비전들 */
    List<String> findRevs(@Param("k") HeaderValue k);

    Long findDraftId(@Param("k") HeaderValue k);

    long insertHeader(@Param("f") HeaderValue f, @Param("by") String by);

    int updateHeader(@Param("id") long id, @Param("f") HeaderValue f, @Param("by") String by);

    /** 머리 버전을 올린다 — 행 저장도 머리의 변경(동시 편집 감지). DRAFT 만 */
    int touch(@Param("id") long id, @Param("rowVer") int rowVer, @Param("by") String by);

    List<PlanRow> findReleasedOverlaps(@Param("k") HeaderValue k, @Param("from") LocalDate from, @Param("exceptId") long exceptId);

    int closeValidTo(@Param("id") long id, @Param("validTo") LocalDate validTo, @Param("by") String by);

    int release(@Param("id") long id, @Param("rowVer") int rowVer, @Param("by") String by);

    int obsolete(@Param("id") long id, @Param("rowVer") int rowVer, @Param("by") String by);

    // ---------- 행 ----------

    List<LineRow> findLines(@Param("planId") long planId);

    long insertLine(@Param("planId") long planId, @Param("l") LineValue l, @Param("by") String by);

    int updateLine(@Param("planId") long planId, @Param("l") LineValue l, @Param("by") String by);

    int deleteLine(@Param("planId") long planId, @Param("lineId") long lineId);

    /** 새 리비전: 행 복사(스냅샷은 비움 — 작성 중은 특성을 따라간다) */
    int copyLines(@Param("fromId") long fromId, @Param("toId") long toId, @Param("by") String by);

    // ---------- 찾기 ----------

    ItemLookup findItemByCd(@Param("itemCd") String itemCd);

    TargetLookup findClassByCd(@Param("classCd") String classCd);

    TargetLookup findProcByCd(@Param("procCd") String procCd);

    List<TargetLookup> findClasses();

    List<TargetLookup> findProcs();

    /** 계획 대상에 맞는 특성: 제품 특성 = 품목(itemId), 공정 특성 = 계획 공정과 같거나(procId) 계획에 공정 없음. 사용 중만 */
    List<CharChoice> findCharChoices(@Param("itemId") Long itemId, @Param("procId") Long procId);

    CharChoice findChar(@Param("charId") long charId);

    List<CandidateRow> findCandidates(@Param("inspType") String inspType, @Param("itemId") long itemId,
            @Param("classId") Long classId);

    // ---------- 검사기준서 연결 (Q3) ----------

    int linkDoc(@Param("id") long id, @Param("docRevId") Long docRevId, @Param("rowVer") int rowVer, @Param("by") String by);

    int copyDoc(@Param("fromId") long fromId, @Param("toId") long toId);

    List<DocChoice> docChoices();
}
