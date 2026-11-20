package io.smarthappy.module.cms;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import io.smarthappy.module.cms.CollectorDtos.ConnRow;
import io.smarthappy.module.cms.CollectorDtos.EquipRef;
import io.smarthappy.module.cms.CollectorDtos.EquipRow;
import io.smarthappy.module.cms.CollectorDtos.IntervalRow;
import io.smarthappy.module.cms.CollectorDtos.NewCount;
import io.smarthappy.module.cms.CollectorDtos.NewEnergy;
import io.smarthappy.module.cms.CollectorDtos.NewInterval;
import io.smarthappy.module.cms.CollectorDtos.OpenInterval;
import io.smarthappy.module.cms.CollectorDtos.PlantRow;
import io.smarthappy.module.cms.CollectorDtos.ReasonRef;
import io.smarthappy.module.cms.CollectorDtos.RunningProc;

/** 설비 상태·연결·Collector (P2 6단계). 회사 조건은 RLS, 장치 범위(공장·노드)는 여기서 */
@Mapper
public interface CollectorMapper {

    /** 장치 범위 안의 사용 중 설비(공장 + 노드가 있으면 그 아래) */
    EquipRef findEquip(@Param("equipCd") String equipCd, @Param("plantCd") String plantCd, @Param("nodeId") Long nodeId);

    // ---------- seq ----------

    int ensureSeq(@Param("terminalId") long terminalId, @Param("seq") long seq);

    Long lockSeq(@Param("terminalId") long terminalId);

    int updateSeq(@Param("terminalId") long terminalId, @Param("seq") long seq);

    // ---------- 상태 구간 ----------

    String lockEquip(@Param("equipId") long equipId);

    OpenInterval lockOpen(@Param("equipId") long equipId);

    OffsetDateTime lastEnd(@Param("equipId") long equipId);

    int close(@Param("id") long id, @Param("endAt") Instant endAt);

    long insertInterval(@Param("f") NewInterval f, @Param("by") String by);

    RunningProc runningProc(@Param("equipId") long equipId);

    ReasonRef reason(@Param("reasonCd") String reasonCd);

    // ---------- 연결 ----------

    int insertConn(@Param("terminalId") long terminalId, @Param("equipId") Long equipId, @Param("conn") String conn,
            @Param("seqFrom") Long seqFrom, @Param("seqTo") Long seqTo, @Param("eventId") String eventId,
            @Param("occurredAt") Instant occurredAt, @Param("by") String by);

    // ---------- 파라미터·에너지 ----------

    int insertParamAuto(@Param("woProcId") long woProcId, @Param("paramCd") String paramCd, @Param("value") BigDecimal value,
            @Param("equipId") long equipId, @Param("terminalId") long terminalId, @Param("reqId") String reqId,
            @Param("occurredAt") Instant occurredAt, @Param("workDay") java.time.LocalDate workDay, @Param("shiftCd") String shiftCd,
            @Param("by") String by);

    long insertEnergy(@Param("f") NewEnergy f, @Param("by") String by);

    long insertCount(@Param("f") NewCount f, @Param("by") String by);

    /** 묶음 COUNT 원장 한 문장(docs/70 W3) — 배열은 같은 길이(숫자·시각은 글자로, SQL 에서 형 변환) */
    int insertCounts(@Param("equipIds") String[] equipIds, @Param("metricCds") String[] metricCds, @Param("qtys") String[] qtys,
            @Param("eventIds") String[] eventIds, @Param("occurred") String[] occurred, @Param("terminalId") long terminalId,
            @Param("by") String by);

    // ---------- 수신 멱등(docs/70 W2) ----------

    /** 처음 받은 eventId 면 1 */
    int claimEvent(@Param("eventId") String eventId);

    /** 처음 받은 eventId 만 */
    List<String> claimEvents(@Param("eventIds") String[] eventIds);

    int purgeInbox(@Param("cutoff") Instant cutoff, @Param("limit") int limit);

    int inboxKeepDays();

    OffsetDateTime inboxHoldFrom();

    // ---------- 타임라인 ----------

    List<PlantRow> plants();

    List<EquipRow> equips(@Param("plantCd") String plantCd);

    List<IntervalRow> intervals(@Param("plantCd") String plantCd, @Param("from") Instant from, @Param("to") Instant to);

    List<ConnRow> conns(@Param("plantCd") String plantCd, @Param("from") Instant from, @Param("to") Instant to);
}
