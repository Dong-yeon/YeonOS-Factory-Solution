package io.smarthappy.module.cms;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import io.smarthappy.module.cms.CmsPmDtos.ItemRow;
import io.smarthappy.module.cms.CmsPmDtos.ItemValue;
import io.smarthappy.module.cms.CmsPmDtos.PlanRow;
import io.smarthappy.module.cms.CmsPmDtos.ResultRow;
import io.smarthappy.module.cms.CmsPmDtos.RunRow;
import io.smarthappy.module.cms.CmsPmDtos.TmplRow;
import io.smarthappy.module.cms.CmsPmDtos.TmplValue;
import io.smarthappy.platform.common.query.PageQuery;

/** CMS 1부 C2 (docs/33 4.3) */
@Mapper
public interface CmsPmMapper {

    // ---------- 점검표 ----------

    List<TmplRow> findTmpls(@Param("useYn") String useYn, @Param("keyword") String keyword);

    /** grid(UI 단계 3 ①) — 같은 조건 + 페이지·정렬(허용 목록) */
    List<TmplRow> findTmplPage(@Param("useYn") String useYn, @Param("keyword") String keyword, @Param("page") PageQuery page);

    long countTmpls(@Param("useYn") String useYn, @Param("keyword") String keyword);

    TmplRow findTmpl(@Param("id") long id);

    TmplRow findTmplByCd(@Param("tmplCd") String tmplCd);

    long insertTmpl(@Param("f") TmplValue f, @Param("by") String by);

    int updateTmpl(@Param("id") long id, @Param("f") TmplValue f, @Param("by") String by);

    /** 항목 한 벌 저장 전 머리 rowVer 올리기(동시 편집 409) */
    int touchTmpl(@Param("id") long id, @Param("rowVer") int rowVer, @Param("by") String by);

    List<ItemRow> findItems(@Param("tmplId") long tmplId);

    void insertItem(@Param("tmplId") long tmplId, @Param("v") ItemValue v, @Param("by") String by);

    int updateItem(@Param("tmplId") long tmplId, @Param("v") ItemValue v, @Param("by") String by);

    int deleteItem(@Param("tmplId") long tmplId, @Param("itemId") long itemId);

    // ---------- 계획 ----------

    List<PlanRow> findPlans(@Param("plantCd") String plantCd, @Param("keyword") String keyword, @Param("useYn") String useYn,
            @Param("equipId") Long equipId, @Param("limit") int limit);

    /** grid(UI 단계 3 ①) — 같은 조건 + 페이지·정렬(허용 목록) */
    List<PlanRow> findPlanPage(@Param("plantCd") String plantCd, @Param("keyword") String keyword, @Param("useYn") String useYn,
            @Param("equipId") Long equipId, @Param("page") PageQuery page);

    long countPlans(@Param("plantCd") String plantCd, @Param("keyword") String keyword, @Param("useYn") String useYn,
            @Param("equipId") Long equipId);

    PlanRow findPlan(@Param("id") long id);

    Long lockPlan(@Param("id") long id);

    boolean planCdExists(@Param("planCd") String planCd);

    long insertPlan(@Param("p") PlanRow p, @Param("by") String by);

    int updatePlan(@Param("p") PlanRow p, @Param("by") String by);

    /** 완료·건너뛰기 뒤 다음 예정 (row_ver 를 올린다 — 화면이 옛 rowVer 로 덮어쓰지 않게) */
    int advancePlan(@Param("id") long id, @Param("next") LocalDate next, @Param("lastDone") LocalDate lastDone,
            @Param("by") String by);

    /** 사용량 주기: 다음 예정 사용량 + 마지막 완료(날짜·누적) */
    int advanceUsagePlan(@Param("id") long id, @Param("next") BigDecimal next, @Param("lastDone") LocalDate lastDone,
            @Param("lastDoneUsage") BigDecimal lastDoneUsage, @Param("by") String by);

    /** 정기 작업 대상 후보: 사용 중 + 열린 예정 없음 + 다음 예정일 ≤ 상한(가장 이른 공장 시간대 오늘 + 최대 선행) */
    List<PlanRow> findPlansToGenerate(@Param("upTo") LocalDate upTo);

    /** 달력 보기(C27) — 그 공장·예정일 범위의 점검 실행(상태 무관) */
    List<CmsPmDtos.CalRunRow> findCalRuns(@Param("plantCd") String plantCd, @Param("from") LocalDate from, @Param("to") LocalDate to,
            @Param("limit") int limit);

    /** 달력 보기(C27) — 그 공장의 사용 중 달력 주기 계획 */
    List<CmsPmDtos.CalPlanRow> findCalPlans(@Param("plantCd") String plantCd, @Param("limit") int limit);

    int countCalUsagePlans(@Param("plantCd") String plantCd);

    /** 카운터의 사용 중 사용량 계획 — 읽기 입력 뒤 같은 트랜잭션 예정 생성 */
    List<Long> findUsagePlansOfCounter(@Param("counterId") long counterId);

    List<String> findActiveEquipOfClass(@Param("classId") long classId);

    // ---------- 점검 실행 ----------

    long insertRun(@Param("r") RunRow r, @Param("by") String by);

    /** 점검표 항목을 결과 행으로 스냅샷 */
    int snapshotResults(@Param("runId") long runId, @Param("tmplId") long tmplId, @Param("by") String by);

    List<RunRow> findRuns(@Param("from") LocalDate from, @Param("to") LocalDate to, @Param("statuses") List<String> statuses,
            @Param("execMode") String execMode, @Param("plantCd") String plantCd, @Param("keyword") String keyword,
            @Param("planId") Long planId, @Param("equipId") Long equipId, @Param("limit") int limit);

    /** grid(UI 단계 3 ①) — 같은 조건 + 페이지·정렬(허용 목록) */
    List<RunRow> findRunPage(@Param("from") LocalDate from, @Param("to") LocalDate to, @Param("statuses") List<String> statuses,
            @Param("execMode") String execMode, @Param("plantCd") String plantCd, @Param("keyword") String keyword,
            @Param("planId") Long planId, @Param("equipId") Long equipId, @Param("page") PageQuery page);

    long countRuns(@Param("from") LocalDate from, @Param("to") LocalDate to, @Param("statuses") List<String> statuses,
            @Param("execMode") String execMode, @Param("plantCd") String plantCd, @Param("keyword") String keyword,
            @Param("planId") Long planId, @Param("equipId") Long equipId);

    RunRow findRun(@Param("id") long id);

    RunRow findRunByMwo(@Param("mwoId") long mwoId);

    Long lockRun(@Param("id") long id);

    List<ResultRow> findResults(@Param("runId") long runId);

    int updateResult(@Param("runId") long runId, @Param("seq") int seq, @Param("valueNum") BigDecimal valueNum,
            @Param("valueText") String valueText, @Param("judge") String judge, @Param("remark") String remark,
            @Param("by") String by);

    int finishRun(@Param("id") long id, @Param("status") String status, @Param("performedAt") OffsetDateTime performedAt,
            @Param("performedBy") Long performedBy, @Param("result") String result, @Param("skipReasonCd") String skipReasonCd, @Param("skipReason") String skipReason,
            @Param("doneUsage") BigDecimal doneUsage, @Param("floorWorkerId") Long floorWorkerId,
            @Param("floorTerminalId") Long floorTerminalId, @Param("floorReqId") String floorReqId, @Param("rowVer") Integer rowVer,
            @Param("by") String by);

    /** 현장 단말(docs/25 4.58) — 그 공장·그 설비들의 열린 CHECK 방식 실행, 예정일 순 */
    List<RunRow> findFloorDue(@Param("plantCd") String plantCd, @Param("equipIds") List<Long> equipIds, @Param("limit") int limit);

    /** 현장 요청 id 로 끝낸 실행(멱등) — 없으면 null */
    Long findRunIdByFloorReq(@Param("reqId") String reqId);
}
