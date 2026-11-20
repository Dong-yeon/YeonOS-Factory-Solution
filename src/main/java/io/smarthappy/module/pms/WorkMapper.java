package io.smarthappy.module.pms;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import io.smarthappy.module.pms.WorkDtos.IssueRow;
import io.smarthappy.module.pms.WorkDtos.LogRow;
import io.smarthappy.module.pms.WorkDtos.ObjRef;
import io.smarthappy.module.pms.WorkDtos.SummaryRow;
import io.smarthappy.module.pms.WorkDtos.Suggestion;
import io.smarthappy.platform.common.query.PageQuery;

/** PMS 4단계 공수·이슈 (docs/32 PM4). 회사 범위는 RLS, 공수는 늘 본인(userId) 조건 */
@Mapper
public interface WorkMapper {

    // ---------- 공수 ----------

    /** 사용자·날짜 잠금(트랜잭션 끝까지) — 하루 합계·한 벌 저장을 줄 세운다 */
    void lockDay(@Param("key") String key);

    List<LogRow> findLogs(@Param("userId") long userId, @Param("from") LocalDate from, @Param("to") LocalDate to);

    List<Suggestion> findSuggestions(@Param("userId") long userId, @Param("day") LocalDate day);

    List<WorkDtos.ProjOption> findOpenProjects();

    /** 이 날 이전(최근 14일) 같은 작업에 마지막으로 넣은 시간·분류 */
    LogRow findPrevious(@Param("userId") long userId, @Param("day") LocalDate day, @Param("projId") long projId,
            @Param("taskId") Long taskId);

    long insertLog(@Param("userId") long userId, @Param("day") LocalDate day, @Param("projId") long projId,
            @Param("taskId") Long taskId, @Param("categoryCd") String categoryCd, @Param("hours") BigDecimal hours,
            @Param("method") String method, @Param("note") String note, @Param("by") String by);

    int updateLog(@Param("id") long id, @Param("userId") long userId, @Param("taskId") Long taskId,
            @Param("categoryCd") String categoryCd, @Param("hours") BigDecimal hours, @Param("method") String method,
            @Param("note") String note, @Param("rowVer") int rowVer, @Param("by") String by);

    int deleteLog(@Param("id") long id, @Param("userId") long userId);

    int confirmDay(@Param("userId") long userId, @Param("day") LocalDate day, @Param("by") String by);

    int reopenDay(@Param("userId") long userId, @Param("day") LocalDate day, @Param("reason") String reason, @Param("by") String by);

    List<SummaryRow> summary(@Param("projId") long projId);

    // ---------- 이슈 ----------

    List<IssueRow> findIssues(@Param("projId") Long projId, @Param("statuses") List<String> statuses,
            @Param("severity") String severity, @Param("ownerId") Long ownerId, @Param("objType") String objType,
            @Param("objId") Long objId, @Param("keywordLike") String keywordLike, @Param("lateRef") LocalDate lateRef,
            @Param("limit") int limit, @Param("issueType") String issueType);

    long countIssues(@Param("projId") Long projId, @Param("statuses") List<String> statuses, @Param("severity") String severity,
            @Param("ownerId") Long ownerId, @Param("objType") String objType, @Param("objId") Long objId,
            @Param("keywordLike") String keywordLike, @Param("issueType") String issueType);

    List<IssueRow> findIssuePage(@Param("projId") Long projId, @Param("statuses") List<String> statuses,
            @Param("severity") String severity, @Param("ownerId") Long ownerId, @Param("objType") String objType,
            @Param("objId") Long objId, @Param("keywordLike") String keywordLike, @Param("lateRef") LocalDate lateRef,
            @Param("page") PageQuery page, @Param("issueType") String issueType);

    IssueRow findIssue(@Param("id") long id, @Param("lateRef") LocalDate lateRef);

    Long findIssueProjId(@Param("id") long id);

    long insertIssue(@Param("issueNo") String issueNo, @Param("f") IssueValue f, @Param("by") String by);

    /** PFMEA 조치에서 만든 이슈(A3-5) — source APQP + 근거 조치 id */
    long insertApqpIssue(@Param("issueNo") String issueNo, @Param("f") IssueValue f, @Param("actionId") long actionId, @Param("by") String by);

    /** 조치 id 들로 연결된 이슈(같은 회사 — RLS) */
    List<WorkDtos.ApqpIssueRow> findApqpIssues(@Param("actionIds") List<Long> actionIds);

    int updateIssue(@Param("id") long id, @Param("f") IssueValue f, @Param("rowVer") int rowVer, @Param("by") String by);

    /** 시정조치 연결 — 한 번만(트리거도 고정) */
    int linkCapa(@Param("id") long id, @Param("capaId") long capaId, @Param("rowVer") int rowVer, @Param("by") String by);

    int updateIssueStatus(@Param("id") long id, @Param("status") String status, @Param("resolution") String resolution,
            @Param("resolvedOn") LocalDate resolvedOn, @Param("closeKind") String closeKind, @Param("rowVer") int rowVer, @Param("by") String by);

    /** 이슈 첨부 잠금 판정(IssueFileLock) — 이슈 상태 + 프로젝트 상태, 없으면 null */
    WorkDtos.IssueLockRow findIssueLock(@Param("id") long id);

    /** 공통코드 사용 가능(시스템 + 회사 코드, 트리거와 같은 함수 hb_code_usable) */
    boolean codeUsable(@Param("grp") String grp, @Param("code") String code);

    /** 연결 객체 코드 → id (유형별, 같은 회사 — RLS). 설비는 공장마다 같은 코드가 있을 수 있어 목록 */
    List<ObjRef> findObj(@Param("objType") String objType, @Param("objCd") String objCd);

    /** 이슈 칸 값(서비스 검증 뒤) */
    record IssueValue(long projId, Long taskId, String title, String detail, String severity, Long ownerUserId, LocalDate dueDate,
            String objType, Long objId, String issueType) {
    }
}
