package io.smarthappy.module.admin;

import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import io.smarthappy.module.admin.LogDtos.AccessRow;
import io.smarthappy.module.admin.LogDtos.AccessSearch;
import io.smarthappy.module.admin.LogDtos.AuditRow;
import io.smarthappy.module.admin.LogDtos.AuditSearch;
import io.smarthappy.module.admin.LogDtos.FeedbackRow;
import io.smarthappy.module.admin.LogDtos.FeedbackSearch;

/** 시스템관리 3단계 (docs/36 12장). 로그·감사·의견은 회사별 표(RLS) — 다른 회사 행은 나타나지 않는다 */
@Mapper
public interface LogMapper {

    List<AccessRow> findAccess(@Param("q") AccessSearch q);

    long countAccess(@Param("q") AccessSearch q);

    List<AuditRow> findAudit(@Param("q") AuditSearch q);

    long countAudit(@Param("q") AuditSearch q);

    /** 한 행 — before/after 는 글자(jsonb) */
    java.util.Map<String, Object> findAuditOne(@Param("auditId") long auditId);

    List<String> auditTablesPresent();

    List<FeedbackRow> findFeedback(@Param("q") FeedbackSearch q);

    long countFeedback(@Param("q") FeedbackSearch q);

    FeedbackRow findFeedbackOne(@Param("feedbackId") long feedbackId);

    int updateFeedbackStatus(@Param("feedbackId") long feedbackId, @Param("status") String status,
            @Param("rowVer") int rowVer, @Param("by") String by);
}
