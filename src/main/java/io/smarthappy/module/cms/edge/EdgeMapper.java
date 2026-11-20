package io.smarthappy.module.cms.edge;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import io.smarthappy.module.cms.edge.EdgeDtos.ClaimedCmd;
import io.smarthappy.module.cms.edge.EdgeDtos.CmdRow;
import io.smarthappy.module.cms.edge.EdgeDtos.ConsRow;
import io.smarthappy.module.cms.edge.EdgeDtos.LinkRef;
import io.smarthappy.module.cms.edge.EdgeDtos.LinkRow;
import io.smarthappy.module.cms.edge.EdgeDtos.LogRow;
import io.smarthappy.module.cms.edge.EdgeDtos.SessionRow;
import io.smarthappy.module.cms.edge.EdgeDtos.StepRow;
import io.smarthappy.module.cms.edge.EdgeDtos.TagRow;
import io.smarthappy.module.cms.edge.EdgeDtos.TerminalOpt;

/** 장비 연동 E0 (docs/65 16장). 회사 조건은 RLS */
@Mapper
public interface EdgeMapper {

    // ---------- 연동 마스터 ----------

    /** 사용 중 설비의 연동(연동이 꺼져 있어도 돌려준다 — 호출한 쪽이 use_yn 확인) */
    LinkRef findLink(@Param("equipCd") String equipCd);

    LinkRef findLinkById(@Param("equipId") long equipId);

    LinkRef lockLink(@Param("equipId") long equipId);

    List<LinkRef> linksOfTerminal(@Param("terminalId") long terminalId);

    int updateSeq(@Param("equipId") long equipId, @Param("seq") long seq);

    int updatePack(@Param("equipId") long equipId, @Param("state") String state, @Param("mode") String mode, @Param("at") Instant at);

    int touchSeen(@Param("equipId") long equipId, @Param("at") Instant at);

    int markLost(@Param("equipId") long equipId, @Param("at") Instant at);

    int clearLost(@Param("equipId") long equipId);

    /** 끊김 판정 대상 — 사용 중·알리지 않음·한 번이라도 본 연동 중 기준 시간을 넘긴 것 */
    List<LinkRef> lostCandidates(@Param("now") Instant now, @Param("miss") int miss);

    List<LinkRow> links(@Param("plantCd") String plantCd, @Param("keyword") String keyword);

    int insertLink(@Param("equipId") long equipId, @Param("f") EdgeDtos.LinkForm f, @Param("terminalId") long terminalId,
            @Param("by") String by);

    int updateLink(@Param("equipId") long equipId, @Param("f") EdgeDtos.LinkForm f, @Param("terminalId") long terminalId,
            @Param("by") String by);

    Long equipIdOf(@Param("equipCd") String equipCd);

    TerminalOpt collector(@Param("terminalCd") String terminalCd);

    List<TerminalOpt> collectors();

    // ---------- 태그 ----------

    List<TagRow> tags(@Param("equipId") long equipId);

    TagRow findTag(@Param("equipId") long equipId, @Param("tagCd") String tagCd);

    int insertTag(@Param("equipId") long equipId, @Param("f") EdgeDtos.TagForm f, @Param("min") BigDecimal min, @Param("max") BigDecimal max,
            @Param("states") String states, @Param("by") String by);

    int updateTag(@Param("f") EdgeDtos.TagForm f, @Param("min") BigDecimal min, @Param("max") BigDecimal max,
            @Param("states") String states, @Param("by") String by);

    // ---------- 명령 ----------

    long insertCmd(@Param("c") CmdRow c);

    CmdRow lockCmdByKey(@Param("cmdKey") UUID cmdKey);

    CmdRow findCmd(@Param("cmdId") long cmdId);

    int setCmdStatus(@Param("cmdId") long cmdId, @Param("status") String status, @Param("resultCd") String resultCd,
            @Param("readback") String readback);

    /** EDGE 큐 — 그 장치가 가져갈 REQUESTED 명령(만료 전), 잠금 skip locked */
    List<CmdRow> claimable(@Param("terminalId") long terminalId, @Param("limit") int limit);

    int markClaimed(@Param("cmdId") long cmdId, @Param("leaseSec") int leaseSec);

    List<CmdRow> cmds(@Param("equipId") long equipId, @Param("limit") int limit);

    List<StepRow> steps(@Param("cmdIds") List<Long> cmdIds);

    int insertStep(@Param("cmdId") long cmdId, @Param("phase") String phase, @Param("okYn") String okYn, @Param("value") String value,
            @Param("detail") String detail, @Param("src") String src, @Param("terminalId") Long terminalId, @Param("inboundId") Long inboundId,
            @Param("occurredAt") Instant occurredAt, @Param("method") String method, @Param("by") String by);

    /** 시간 초과 대상 — 만료 시각을 넘긴 진행 중 명령(잠금 skip locked) */
    List<CmdRow> overdue(@Param("now") Instant now);

    ClaimedCmd toClaimed(@Param("cmdId") long cmdId);

    /** DIRECT 명령의 아웃박스가 실제로 보냈나(SENT) */
    boolean outboxSent(@Param("bizKey") String bizKey);

    // ---------- 사건 ----------

    int insertLog(@Param("equipId") Long equipId, @Param("terminalId") Long terminalId, @Param("eventCd") String eventCd,
            @Param("seqFrom") Long seqFrom, @Param("seqTo") Long seqTo, @Param("detail") String detail, @Param("inboundId") Long inboundId,
            @Param("occurredAt") Instant occurredAt, @Param("by") String by);

    List<LogRow> logs(@Param("equipId") Long equipId, @Param("limit") int limit);

    // ---------- 소모품·장치 세션 ----------

    ConsRow lockCons(@Param("equipId") long equipId, @Param("consCd") String consCd);

    int upsertCons(@Param("equipId") long equipId, @Param("consCd") String consCd, @Param("level") String level);

    List<ConsRow> consumables(@Param("equipId") long equipId);

    SessionRow lockSession(@Param("terminalId") long terminalId);

    int insertSession(@Param("terminalId") long terminalId, @Param("bootId") String bootId, @Param("skewMs") Long skewMs);

    int updateSession(@Param("terminalId") long terminalId, @Param("bootId") String bootId, @Param("restart") boolean restart,
            @Param("skewMs") Long skewMs);

    int markSkewLogged(@Param("terminalId") long terminalId);
}
