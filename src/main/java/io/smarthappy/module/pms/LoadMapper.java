package io.smarthappy.module.pms;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import lombok.Getter;
import lombok.Setter;

/** 사람 × 주 부하 (docs/32 4.26). 회사 범위는 RLS — 조건에 comp_cd 를 쓰지 않는다 */
@Mapper
public interface LoadMapper {

    /** 계획 몫을 나눌 작업 — 조회 범위와 겹치는 잎 TASK(취소 제외, 취소 프로젝트 제외, 계획 공수 &gt; 0). limit 만큼만(넘으면 부르는 쪽이 오류) */
    List<PlanTask> findPlanTasks(@Param("from") LocalDate from, @Param("to") LocalDate to, @Param("projId") Long projId,
            @Param("ownerId") Long ownerId, @Param("unassigned") boolean unassigned, @Param("limit") int limit);

    /** 확정 공수(pms_work_log CONFIRMED) 사람 × 주(월요일) 합 */
    List<ConfWeek> findConfirmed(@Param("from") LocalDate from, @Param("to") LocalDate to, @Param("projId") Long projId);

    List<UserRow> findUsers(@Param("ids") Collection<Long> ids);

    @Getter
    @Setter
    class PlanTask {
        private long taskId;
        private long projId;
        private String projNo;
        private String projNm;
        private String taskNm;
        private Long ownerUserId;
        private LocalDate planStart;
        private LocalDate planEnd;
        private BigDecimal planHours;
        private String status;
    }

    @Getter
    @Setter
    class ConfWeek {
        private long userId;
        private LocalDate weekStart;
        private BigDecimal hours;
    }

    @Getter
    @Setter
    class UserRow {
        private long userId;
        private String loginId;
        private String userNm;
        private String useYn;
    }
}
