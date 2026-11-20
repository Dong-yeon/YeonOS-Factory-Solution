package io.smarthappy.module.qms;

import java.math.BigDecimal;
import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import io.smarthappy.module.qms.SpcDtos.SubgroupHead;
import io.smarthappy.module.qms.SpcEventDtos.ActRow;
import io.smarthappy.module.qms.SpcEventDtos.EventRow;
import io.smarthappy.module.qms.SpcEventDtos.JudgedLine;
import io.smarthappy.platform.common.query.PageQuery;

/** SPC 위반 이벤트 원장 (docs/29_P3 19장) */
@Mapper
public interface SpcEventMapper {

    /** 판정 확정된 검사의 계량형 행 */
    List<JudgedLine> findJudgedLines(@Param("inspId") long inspId);

    /** 같은 단위 판정을 줄 세움(트랜잭션 끝까지) */
    int lockUnit(@Param("key") String key);

    /** 그 검사 행까지(자신 포함)의 최근 부분군 — 최근 것부터. 순서 = 첫 측정 시각, 같으면 검사 행 id */
    List<SubgroupHead> findSubgroupsUpTo(@Param("itemId") long itemId, @Param("charId") long charId, @Param("inspType") String inspType,
            @Param("procId") Long procId, @Param("lineId") long lineId, @Param("limit") int limit);

    /** findPSubgroups 와 같은 p 부분군 — 그 검사 행 자신까지 */
    List<SubgroupHead> findPSubgroupsUpTo(@Param("itemId") long itemId, @Param("charId") long charId, @Param("inspType") String inspType,
            @Param("procId") Long procId, @Param("lineId") long lineId, @Param("limit") int limit);

    /** 같은 행·규칙이 이미 있으면 null */
    Long insertEvent(@Param("limitId") long limitId, @Param("line") JudgedLine line, @Param("insp") long inspId, @Param("rule") String rule,
            @Param("n") int n, @Param("mean") BigDecimal mean, @Param("range") BigDecimal range, @Param("z") BigDecimal z,
            @Param("center") BigDecimal center, @Param("sigma") BigDecimal sigma, @Param("chartType") String chartType, @Param("by") String by);

    /** 그 검사의 열린 이벤트 전부에 VOID — 넣은 행 수 */
    /** 그 검사의 열린 이벤트에 VOID — 닫은 이벤트 id */
    List<Long> voidOpenOfInsp(@Param("inspId") long inspId, @Param("note") String note, @Param("by") String by);

    long findInspLot(@Param("inspId") long inspId);

    List<EventRow> findGrid(@Param("q") EventSearch q, @Param("page") PageQuery page);

    long countGrid(@Param("q") EventSearch q);

    EventRow findById(@Param("id") long id);

    List<ActRow> findActs(@Param("id") long id);

    long insertAct(@Param("id") long eventId, @Param("type") String type, @Param("note") String note, @Param("linkType") String linkType,
            @Param("linkNo") String linkNo, @Param("by") String by);

    /** 착수 게이트 Q9-3 — 이 품목·공정의 열린(OPEN·ACKED) 이벤트, 최근 것부터 */
    List<EventRow> findOpenOfItemProc(@Param("itemId") long itemId, @Param("procId") long procId, @Param("limit") int limit);

    /** 조회 조건 — status = OPEN·ACKED·CLOSED·VOID·OPEN_ALL(OPEN+ACKED), 단위(itemId·charId·inspType·procId)는 관리도에서 열 때 */
    /** joinItem = 품목 코드 정렬(그때만 품목 표 조인 — 지연 조인 안쪽을 가볍게) */
    record EventSearch(String keywordLike, List<String> statuses, Long itemId, Long charId, String inspType, Long procId, boolean unit,
            boolean joinItem) {
    }
}
