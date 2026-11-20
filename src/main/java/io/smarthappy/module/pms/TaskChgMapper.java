package io.smarthappy.module.pms;

import java.time.LocalDate;
import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import io.smarthappy.module.pms.TaskChgDtos.ChgRow;

/** 담당자 일정변경 요청 (docs/32 4.13). 회사 범위는 RLS */
@Mapper
public interface TaskChgMapper {

    long insertReq(@Param("projId") long projId, @Param("taskId") long taskId, @Param("requesterId") long requesterId,
            @Param("beforeStart") LocalDate beforeStart, @Param("beforeEnd") LocalDate beforeEnd, @Param("newStart") LocalDate newStart,
            @Param("newEnd") LocalDate newEnd, @Param("reason") String reason, @Param("taskRowVer") int taskRowVer,
            @Param("status") String status, @Param("by") String by);

    int setAprId(@Param("id") long id, @Param("aprId") long aprId);

    ChgRow findReq(@Param("id") long id);

    /** 행 잠금 — 반환 = 상태(없으면 null) */
    String lockReq(@Param("id") long id);

    /** 이 작업의 열린 요청(REQUESTED·IN_APPROVAL) */
    ChgRow findOpenOfTask(@Param("taskId") long taskId);

    List<ChgRow> findReqsOfProject(@Param("projId") long projId, @Param("limit") int limit);

    /** 홈 카드 pms-chg-todo — 내가 PM 인 열린 프로젝트의 처리 대기 요청(내가 낸 것 제외) 수 */
    long countPmTodo(@Param("userId") long userId);

    /** 같은 조건, 오래된 순 */
    List<TaskChgDtos.PmTodoRow> findPmTodo(@Param("userId") long userId, @Param("limit") int limit);

    /** 열린 요청을 끝낸다(승인·반려·회수) — 0 = 이미 끝남 */
    int finish(@Param("id") long id, @Param("status") String status, @Param("note") String note, @Param("by") String by);
}
