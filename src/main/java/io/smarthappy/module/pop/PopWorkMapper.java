package io.smarthappy.module.pop;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import io.smarthappy.module.pop.PopWorkDtos.BomRow;
import io.smarthappy.module.pop.PopWorkDtos.BreakdownEquip;
import io.smarthappy.module.pop.PopWorkDtos.DefectLineRow;
import io.smarthappy.module.pop.PopWorkDtos.DefectOption;
import io.smarthappy.module.pop.PopWorkDtos.EventRow;
import io.smarthappy.module.pop.PopWorkDtos.NewEvent;
import io.smarthappy.module.pop.PopWorkDtos.NewParam;
import io.smarthappy.module.pop.PopWorkDtos.NewResult;
import io.smarthappy.module.pop.PopWorkDtos.ParamActualRow;
import io.smarthappy.module.pop.PopWorkDtos.ParamRow;
import io.smarthappy.module.pop.PopWorkDtos.ProcRow;
import io.smarthappy.module.pop.PopWorkDtos.ResultRow;

/** POP 착수·실적·투입 (P2 5b). 회사 조건은 RLS, 단말 작업장 조건은 여기서 */
@Mapper
public interface PopWorkMapper {

    /** 단말 작업장(라인이면 아래 셀 포함, 없으면 공장 전체) 안의 지시 공정. lock = 공정 행 FOR UPDATE */
    ProcRow findProc(@Param("id") long id, @Param("nodeId") Long nodeId, @Param("plantCd") String plantCd,
            @Param("lock") boolean lock);

    List<ParamRow> params(@Param("woProcId") long woProcId);

    List<BomRow> bom(@Param("woId") long woId, @Param("opSeq") int opSeq);

    List<DefectOption> defectOptions();

    Long defectIdByCd(@Param("defectCd") String defectCd);

    // ---------- 이벤트 ----------

    long insertEvent(@Param("f") NewEvent f, @Param("by") String by);

    int updateProcStatus(@Param("id") long id, @Param("from") String from, @Param("to") String to, @Param("by") String by);

    int woInProgress(@Param("woId") long woId, @Param("by") String by);

    EventRow findEventByReq(@Param("req") String req);

    String procStatus(@Param("id") long id);

    // ---------- 실적 ----------

    long insertResult(@Param("f") NewResult f, @Param("by") String by);

    int insertDefect(@Param("resultId") long resultId, @Param("kind") String kind, @Param("defectId") long defectId,
            @Param("qty") BigDecimal qty);

    ResultRow findResult(@Param("id") long id);

    ResultRow findResultByReq(@Param("req") String req);

    List<DefectLineRow> defectsOf(@Param("resultId") long resultId);

    List<ResultRow> dayResults(@Param("woProcId") long woProcId, @Param("workDay") LocalDate workDay);

    String planQty(@Param("woId") long woId);

    String dayGood(@Param("woProcId") long woProcId, @Param("workDay") LocalDate workDay);

    String lastDefect(@Param("woProcId") long woProcId);

    /** 이 실적의 투입 계보(역분개 안 된 CONSUME) */
    List<Long> inputEdges(@Param("lotId") long lotId, @Param("woProcId") long woProcId);

    /** 정정 앞 잠금용 — 그 실적의 살아 있는 투입 계보의 부모(자재) LOT, LOT id 순 */
    List<Long> inputParents(@Param("lotId") long lotId, @Param("woProcId") long woProcId);

    // ---------- 파라미터 ----------

    ParamRow findParam(@Param("woProcId") long woProcId, @Param("paramCd") String paramCd);

    long insertParam(@Param("f") NewParam f, @Param("by") String by);

    ParamActualRow findParamActual(@Param("id") long id);

    ParamActualRow findParamByReq(@Param("req") String req);

    /** 그 설비에 배정된 진행(RUN·PAUSE) 공정 수 */
    int runningOnEquip(@Param("equipId") long equipId);

    /** 수집 COUNT 제안 — since = 마지막(역분개 안 된) 실적과 마지막 착수 중 늦은 것 */
    io.smarthappy.module.pop.PopWorkDtos.CountSuggest countSuggest(@Param("woProcId") long woProcId, @Param("equipId") long equipId,
            @Param("now") java.time.Instant now);

    // ---------------------------------------------------------------- 고장 신고(docs/25 4.35)

    /** 단말 공장의 사용 중 설비 — 작업장이 있으면 그 노드 + 바로 아래 노드(공정 범위 findProc 와 같은 규칙) */
    List<BreakdownEquip> breakdownEquips(@Param("plantCd") String plantCd, @Param("nodeId") Long nodeId);

    /** 설비 하나(범위 조건 없이 — 없음·사용 중지·다른 공장·작업장 밖을 서비스가 나눠 알린다) */
    BreakdownEquip findBreakdownEquip(@Param("equipId") long equipId);

    /** 감독자 승인 원장 (docs/25 4.37) — 추가만 */
    long insertApproval(@Param("a") io.smarthappy.module.pop.PopDtos.PopApproval a, @Param("targetId") long targetId,
            @Param("reversalId") long reversalId, @Param("reason") String reason, @Param("occurredAt") java.time.Instant occurredAt,
            @Param("by") String by);
}
