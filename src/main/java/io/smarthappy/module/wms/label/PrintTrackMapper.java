package io.smarthappy.module.wms.label;

import java.time.OffsetDateTime;
import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import io.smarthappy.module.wms.label.PrintTrackDtos.EventRow;
import io.smarthappy.module.wms.label.PrintTrackDtos.JobState;
import io.smarthappy.module.wms.label.PrintTrackDtos.PendingJob;
import io.smarthappy.module.wms.label.PrintTrackDtos.PrinterStatusRow;
import io.smarthappy.module.wms.label.PrintTrackDtos.ProbeTarget;

@Mapper
public interface PrintTrackMapper {

    String lockJob(@Param("jobId") long jobId);

    JobState jobState(@Param("jobId") long jobId);

    void insertEvent(@Param("jobId") long jobId, @Param("eventCd") String eventCd, @Param("reasonCd") String reasonCd,
            @Param("detail") String detail, @Param("src") String src, @Param("terminalId") Long terminalId,
            @Param("occurredAt") OffsetDateTime occurredAt, @Param("method") String method, @Param("by") String by,
            @Param("performedBy") Long performedBy);

    List<EventRow> events(@Param("jobId") long jobId);

    List<PendingJob> pendingJobs(@Param("printerId") long printerId);

    boolean hasUnsent(@Param("printerId") long printerId);

    void ensureStatus(@Param("printerId") long printerId, @Param("src") String src);

    PrinterStatusRow lockStatus(@Param("printerId") long printerId);

    void updateStatus(@Param("s") PrinterStatusRow s);

    PrinterStatusRow statusOf(@Param("printerId") long printerId);

    /** 규칙 화면 대상 고르기 — 사용 중 거래처·품목을 코드·이름으로(최대 20) */
    List<java.util.Map<String, Object>> custOptions(@Param("like") String like);

    List<java.util.Map<String, Object>> itemOptions(@Param("like") String like);

    List<ProbeTarget> probeTargets(@Param("periodSec") int periodSec, @Param("limit") int limit);

    // ---- 에이전트 큐 (docs/65 19.2)
    void insertDispatch(@Param("jobId") long jobId, @Param("printerId") long printerId, @Param("terminalId") long terminalId);

    PrintTrackDtos.DispatchRow lockDispatch(@Param("jobId") long jobId);

    List<Long> claimable(@Param("terminalId") long terminalId, @Param("max") int max);

    int markClaimed(@Param("jobId") long jobId, @Param("leaseSec") int leaseSec);

    int setStage(@Param("jobId") long jobId, @Param("stage") String stage);

    PrintTrackDtos.ClaimedJob claimedJob(@Param("jobId") long jobId);

    List<Long> leaseExpired();

    int queuedCount(@Param("terminalId") long terminalId);

    List<PrintTrackDtos.AgentPrinter> agentPrinters(@Param("terminalId") long terminalId);
}
