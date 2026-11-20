package io.smarthappy.module.admin;

import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import io.smarthappy.module.admin.OutboxDtos.FailTop;
import io.smarthappy.module.admin.OutboxDtos.OutboxDetailRow;
import io.smarthappy.module.admin.OutboxDtos.OutboxRow;
import io.smarthappy.module.admin.OutboxDtos.OutboxSearch;
import io.smarthappy.module.admin.OutboxDtos.TopicCount;

/**
 * 연계 현황·실패 재전송 (docs/36 19장). sys_outbox 는 회사별 표(RLS) — 다른 회사 행은 나타나지 않는다.
 * 재시도·종결은 조건부 UPDATE 한 문장(상태 FAIL·결재 대기 아님) — 0행이면 서비스가 409
 */
@Mapper
public interface OutboxMapper {

    List<OutboxRow> find(@Param("q") OutboxSearch q);

    long count(@Param("q") OutboxSearch q);

    OutboxDetailRow findOne(@Param("outboxId") long outboxId);

    /** 상태·결재 대기를 잠그고 읽는다(종결·결재 상신 전) — 없으면 null */
    OutboxDetailRow lockOne(@Param("outboxId") long outboxId);

    int retry(@Param("outboxId") long outboxId, @Param("by") String by);

    int close(@Param("outboxId") long outboxId, @Param("reason") String reason, @Param("by") String by);

    /** 결재 상신 뒤 대기 표시 — 사유·요청자는 승인 때 종결 칸으로(closed_at 은 승인 때) */
    int markClosePending(@Param("outboxId") long outboxId, @Param("aprId") long aprId, @Param("reason") String reason,
            @Param("by") String by);

    /** 결재 승인 — 대기 중일 때만(같은 행·문서 유형의 진행 중 결재는 하나 — uq_apr_request_active) */
    int closeApproved(@Param("outboxId") long outboxId);

    /** 결재 반려·회수 — 대기 표시를 지운다 */
    int clearClosePending(@Param("outboxId") long outboxId);

    List<TopicCount> failByTopic();

    long failClosePending();

    List<FailTop> failTop(@Param("limit") int limit);
}
