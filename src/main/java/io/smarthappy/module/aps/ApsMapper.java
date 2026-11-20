package io.smarthappy.module.aps;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import io.smarthappy.module.aps.ApsDtos.ApplyOpSrc;
import io.smarthappy.module.aps.ApsDtos.ApplyWoSrc;
import io.smarthappy.module.aps.ApsDtos.CmpOp;
import io.smarthappy.module.aps.ApsDtos.EquipRow;
import io.smarthappy.module.aps.ApsDtos.ItemBrief;
import io.smarthappy.module.aps.ApsDtos.MatOutRow;
import io.smarthappy.module.aps.ApsDtos.NodeRow;
import io.smarthappy.module.aps.ApsDtos.OpOutRow;
import io.smarthappy.module.aps.ApsDtos.OpSrc;
import io.smarthappy.module.aps.ApsDtos.PinOp;
import io.smarthappy.module.aps.ApsDtos.PinRow;
import io.smarthappy.module.aps.ApsDtos.PinSrc;
import io.smarthappy.module.aps.ApsDtos.PlanCrit;
import io.smarthappy.module.aps.ApsDtos.PlanInsert;
import io.smarthappy.module.aps.ApsDtos.PlanMatRow;
import io.smarthappy.module.aps.ApsDtos.PlanOpRow;
import io.smarthappy.module.aps.ApsDtos.PlanRow;
import io.smarthappy.module.aps.ApsDtos.PlanWoKey;
import io.smarthappy.module.aps.ApsDtos.PlanWoRow;
import io.smarthappy.module.aps.ApsDtos.ShiftRow;
import io.smarthappy.module.aps.ApsDtos.SupplyOutRow;
import io.smarthappy.module.aps.ApsDtos.WoOutRow;
import io.smarthappy.module.aps.ApsDtos.WoSrc;
import io.smarthappy.module.master.equip.EquipDtos.PlantRow;
import io.smarthappy.platform.common.query.PageQuery;

@Mapper
public interface ApsMapper {

    // ---------- 계산 입력 (지시·라우팅·설비·근무조는 읽기만) ----------

    List<PlantRow> findPlants();

    String plantUseYn(@Param("plantCd") String plantCd);

    List<ShiftRow> findShifts(@Param("plantCd") String plantCd);

    /** 계획 대상 지시: 공장의 계획·발행·진행 중, 계획 시작일 ≤ 마지막 날 */
    List<WoSrc> findWos(@Param("plantCd") String plantCd, @Param("lastDay") LocalDate lastDay, @Param("limit") int limit);

    /** 대상 지시의 공정: 발행 지시 = 스냅샷(잔량 = 계획 − 순 양품), 계획 지시 = 확정 라우팅 단계 */
    List<OpSrc> findOps(@Param("plantCd") String plantCd, @Param("lastDay") LocalDate lastDay, @Param("limit") int limit);

    List<PinSrc> findPins(@Param("plantCd") String plantCd);

    List<NodeRow> findNodes(@Param("plantCd") String plantCd);

    List<EquipRow> findEquips(@Param("plantCd") String plantCd);

    // ---------- 계획 저장 ----------

    long insertPlan(@Param("p") PlanInsert p, @Param("by") String by);

    int insertWos(@Param("planId") long planId, @Param("rows") List<WoOutRow> rows);

    List<PlanWoKey> findPlanWoKeys(@Param("planId") long planId);

    int insertOps(@Param("planId") long planId, @Param("rows") List<OpOutRow> rows);

    int discard(@Param("id") long id, @Param("reason") String reason, @Param("rowVer") int rowVer, @Param("by") String by);

    // ---------- 자재 (5단계) ----------

    int insertMats(@Param("planId") long planId, @Param("rows") List<MatOutRow> rows);

    int insertSupplies(@Param("planId") long planId, @Param("rows") List<SupplyOutRow> rows);

    List<PlanMatRow> findPlanMats(@Param("id") long id);

    // ---------- 반영 (2단계) ----------

    String lockPlan(@Param("id") long id);

    List<Long> lockWos(@Param("planId") long planId);

    List<ApplyWoSrc> findApplyWos(@Param("planId") long planId);

    /** 계획의 발행 지시 공정(계획 못 한 공정 포함) — 낡음 판정·설비 배정 */
    List<ApplyOpSrc> findApplyOps(@Param("planId") long planId);

    List<String> findNewWos(@Param("planId") long planId, @Param("plantCd") String plantCd, @Param("lastDay") LocalDate lastDay);

    int markApplied(@Param("id") long id, @Param("rowVer") int rowVer, @Param("by") String by, @Param("woCnt") int woCnt,
            @Param("opCnt") int opCnt);

    // ---------- 수동 고정 (3단계) ----------

    PinOp findPinOp(@Param("id") long planOpId);

    PinOp findPinOpOf(@Param("planId") long planId, @Param("woId") long woId, @Param("opSeq") int opSeq);

    List<PinOp> findCurEquips(@Param("planId") long planId);

    long insertPin(@Param("op") PinOp op, @Param("kind") String kind, @Param("equipId") Long equipId, @Param("nodeId") Long nodeId,
            @Param("startAt") OffsetDateTime startAt, @Param("remark") String remark, @Param("by") String by);

    PinRow findPin(@Param("id") long id);

    int movePin(@Param("id") long id, @Param("kind") String kind, @Param("equipId") Long equipId, @Param("nodeId") Long nodeId,
            @Param("startAt") OffsetDateTime startAt, @Param("rowVer") int rowVer, @Param("by") String by);

    int releasePin(@Param("id") long id, @Param("rowVer") int rowVer, @Param("by") String by);

    List<PinRow> findPinList(@Param("plantCd") String plantCd);

    // ---------- 비교 (2단계) ----------

    List<CmpOp> findCmpOps(@Param("id") long id);

    // ---------- 조회 ----------

    /** 계산 시각 [from, to) — 회사 시간대 날짜를 서비스가 시각으로 바꿔 넘긴다 */
    List<PlanRow> findPlans(@Param("c") PlanCrit c, @Param("limit") int limit);

    List<PlanRow> findPlanPage(@Param("c") PlanCrit c, @Param("page") PageQuery page);

    long countPlans(@Param("c") PlanCrit c);

    List<OpSrc> findRoutingOps(@Param("routingId") long routingId, @Param("qty") BigDecimal qty);

    List<ItemBrief> findItemBriefs(@Param("ids") Collection<Long> ids);

    PlanRow findPlan(@Param("id") long id);

    String findWindows(@Param("id") long id);

    String findOffDays(@Param("id") long id);

    List<PlanWoRow> findPlanWos(@Param("id") long id);

    /** scheduled = true 면 계획된 공정(자원·시각 순), false 면 계획 못 한 공정 */
    List<PlanOpRow> findPlanOps(@Param("id") long id, @Param("scheduled") boolean scheduled);
}
