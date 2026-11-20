package io.smarthappy.module.pms;

import java.time.LocalDate;
import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import io.smarthappy.module.pms.PortfolioDtos.HoursRow;
import io.smarthappy.module.pms.PortfolioDtos.MyDeliv;
import io.smarthappy.module.pms.PortfolioDtos.MyTask;
import io.smarthappy.module.pms.PortfolioDtos.PortRow;
import io.smarthappy.platform.common.query.PageQuery;

/** PMS 5단계 포트폴리오·내 할 일·홈 카드 (docs/32 PM5). 회사 범위는 RLS */
@Mapper
public interface PortfolioMapper {

    /** 홈 카드 — 목록과 같은 조건 전체의 수·합(목록 상한 없음) */
    PortfolioDtos.CardStats portfolioCardStats(@Param("keywordLike") String keywordLike, @Param("projType") String projType,
            @Param("statuses") List<String> statuses, @Param("pmUserId") Long pmUserId, @Param("projId") Long projId,
            @Param("today") LocalDate today, @Param("lateRef") LocalDate lateRef);

    /** 홈 카드 항목 — 지연 프로젝트 많이 늦은 순 상위 */
    List<PortRow> findDelayedPortfolio(@Param("keywordLike") String keywordLike, @Param("projType") String projType,
            @Param("statuses") List<String> statuses, @Param("pmUserId") Long pmUserId, @Param("projId") Long projId,
            @Param("today") LocalDate today, @Param("lateRef") LocalDate lateRef, @Param("limit") int limit);

    List<PortRow> findPortfolio(@Param("keywordLike") String keywordLike, @Param("projType") String projType,
            @Param("statuses") List<String> statuses, @Param("pmUserId") Long pmUserId, @Param("projId") Long projId,
            @Param("today") LocalDate today, @Param("lateRef") LocalDate lateRef, @Param("limit") int limit);

    List<PortRow> findPortfolioPage(@Param("keywordLike") String keywordLike, @Param("projType") String projType,
            @Param("statuses") List<String> statuses, @Param("pmUserId") Long pmUserId, @Param("projId") Long projId,
            @Param("today") LocalDate today, @Param("lateRef") LocalDate lateRef, @Param("page") PageQuery page);

    long countPortfolio(@Param("keywordLike") String keywordLike, @Param("projType") String projType,
            @Param("statuses") List<String> statuses, @Param("pmUserId") Long pmUserId, @Param("projId") Long projId);

    /** 연도별 착수·종료(docs/32 4.20) — 목록과 같은 조건(portWhere) */
    List<PortfolioDtos.YearRow> portfolioYears(@Param("keywordLike") String keywordLike, @Param("projType") String projType,
            @Param("statuses") List<String> statuses, @Param("pmUserId") Long pmUserId, @Param("projId") Long projId);

    /** 유형별 프로젝트·진행 중 — 목록과 같은 조건 */
    List<PortfolioDtos.TypeRow> portfolioTypes(@Param("keywordLike") String keywordLike, @Param("projType") String projType,
            @Param("statuses") List<String> statuses, @Param("pmUserId") Long pmUserId, @Param("projId") Long projId);

    /** 계획 시작이 없는(작업 없는) 프로젝트 수 — 목록과 같은 조건 */
    int countPortfolioNoPlan(@Param("keywordLike") String keywordLike, @Param("projType") String projType,
            @Param("statuses") List<String> statuses, @Param("pmUserId") Long pmUserId, @Param("projId") Long projId);

    /** 차트 — 목록과 같은 조건(portWhere), 상태별 묶음(쪽 나눔 없이 전체) */
    List<PortfolioDtos.ChartRow> portfolioChart(@Param("keywordLike") String keywordLike, @Param("projType") String projType,
            @Param("statuses") List<String> statuses, @Param("pmUserId") Long pmUserId, @Param("projId") Long projId,
            @Param("lateRef") LocalDate lateRef);

    /** 내 잎 작업 중 끝나지 않았고 계획 종료 ≤ until(지연 포함) */
    List<MyTask> findMyTasks(@Param("userId") long userId, @Param("lateRef") LocalDate lateRef, @Param("until") LocalDate until,
            @Param("limit") int limit);

    long countMyTasks(@Param("userId") long userId, @Param("lateRef") LocalDate lateRef, @Param("until") LocalDate until,
            @Param("lateOnly") boolean lateOnly);

    /** 내 산출물(담당, 담당 없으면 작업 담당) 중 미제출·반려이고 기한 ≤ until */
    List<MyDeliv> findMyDelivs(@Param("userId") long userId, @Param("lateRef") LocalDate lateRef, @Param("until") LocalDate until,
            @Param("limit") int limit);

    long countMyDelivs(@Param("userId") long userId, @Param("until") LocalDate until);

    long countMyIssues(@Param("userId") long userId);

    /** 내 이슈 목록 — 홈 카드 숫자와 같은 조건(myIssueWhere) */
    List<io.smarthappy.module.pms.WorkDtos.IssueRow> findMyIssues(@Param("userId") long userId, @Param("lateRef") LocalDate lateRef,
            @Param("limit") int limit);

    /** 하루 요약 — 사람마다 지연·임박 건수(작업 + 산출물, 0건 사람 없음) */
    List<PortfolioDtos.DigestRow> digestCounts(@Param("lateRef") LocalDate lateRef, @Param("until") LocalDate until);

    /** from~to 사이 확정 행이 있는 날 */
    List<LocalDate> findConfirmedDays(@Param("userId") long userId, @Param("from") LocalDate from, @Param("to") LocalDate to);

    long countLogs(@Param("userId") long userId, @Param("from") LocalDate from, @Param("to") LocalDate to);

    List<HoursRow> hoursByCategory(@Param("projId") long projId);

    long countGatesInApproval();
}
