package io.smarthappy.module.master.workforce;

import java.time.LocalDate;
import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import io.smarthappy.module.master.equip.EquipDtos.PlantRow;
import io.smarthappy.module.master.workforce.WorkforceDtos.GateReqRow;
import io.smarthappy.module.master.workforce.WorkforceDtos.QualForm;
import io.smarthappy.module.master.workforce.WorkforceDtos.QualRow;
import io.smarthappy.module.master.workforce.WorkforceDtos.ReqRow;
import io.smarthappy.module.master.workforce.WorkforceDtos.ShiftRow;
import io.smarthappy.module.master.workforce.WorkforceDtos.ShiftValue;
import io.smarthappy.module.master.workforce.WorkforceDtos.WorkerQualRow;
import io.smarthappy.module.master.workforce.WorkforceDtos.WorkerQualValue;
import io.smarthappy.module.master.workforce.WorkforceDtos.WorkerRow;
import io.smarthappy.module.master.workforce.WorkforceDtos.WorkerValue;
import io.smarthappy.platform.common.query.PageQuery;

@Mapper
public interface WorkforceMapper {

    // ---------- 공통 ----------

    String plantUseYn(@Param("plantCd") String plantCd);

    List<PlantRow> findPlants();

    // ---------- 근무조 ----------

    List<ShiftRow> findShifts(@Param("plantCd") String plantCd, @Param("activeOnly") boolean activeOnly);

    ShiftRow findShift(@Param("id") long id);

    long insertShift(@Param("f") ShiftValue f, @Param("by") String by);

    int updateShift(@Param("id") long id, @Param("f") ShiftValue f, @Param("by") String by);

    // ---------- 자격 ----------

    List<QualRow> findQuals(@Param("useYn") String useYn, @Param("today") LocalDate today);

    List<QualRow> findQualPage(@Param("useYn") String useYn, @Param("today") LocalDate today, @Param("page") PageQuery page);

    long countQuals(@Param("useYn") String useYn);

    QualRow findQual(@Param("id") long id);

    QualRow findQualByCd(@Param("qualCd") String qualCd);

    long insertQual(@Param("f") QualForm f, @Param("by") String by);

    int updateQual(@Param("id") long id, @Param("f") QualForm f, @Param("by") String by);

    List<ReqRow> findReqs(@Param("qualId") Long qualId, @Param("procId") Long procId);

    ReqRow findReq(@Param("id") long id);

    List<WorkforceDtos.ProcOption> findActiveProcs();

    Long findProcId(@Param("procCd") String procCd, @Param("activeOnly") boolean activeOnly);

    long insertReq(@Param("procId") long procId, @Param("qualId") long qualId, @Param("gateMode") String gateMode,
            @Param("by") String by);

    int updateReq(@Param("id") long id, @Param("gateMode") String gateMode, @Param("useYn") String useYn,
            @Param("rowVer") int rowVer, @Param("by") String by);

    // ---------- 작업자 ----------

    List<WorkerRow> findWorkers(@Param("plantCd") String plantCd, @Param("keywordLike") String keywordLike,
            @Param("useYn") String useYn, @Param("today") LocalDate today);

    WorkerRow findWorker(@Param("id") long id, @Param("today") LocalDate today);

    List<ShiftRow> findShiftPage(@Param("plantCd") String plantCd, @Param("page") PageQuery page);

    long countShifts(@Param("plantCd") String plantCd);

    List<WorkerRow> findWorkerPage(@Param("plantCd") String plantCd, @Param("keywordLike") String keywordLike,
            @Param("useYn") String useYn, @Param("today") LocalDate today, @Param("page") PageQuery page);

    long countWorkers(@Param("plantCd") String plantCd, @Param("keywordLike") String keywordLike, @Param("useYn") String useYn);


    long insertWorker(@Param("f") WorkerValue f, @Param("by") String by);

    int updateWorker(@Param("id") long id, @Param("f") WorkerValue f, @Param("by") String by);

    int updateBadge(@Param("id") long id, @Param("badgeHash") String badgeHash, @Param("rowVer") int rowVer,
            @Param("by") String by);

    /** PIN 지정·지우기 — 틀린 횟수·잠금도 푼다 */
    int updatePin(@Param("id") long id, @Param("pinHash") String pinHash, @Param("rowVer") int rowVer,
            @Param("by") String by);

    Long findWorkerIdByBadge(@Param("badgeHash") String badgeHash);

    List<WorkerQualRow> findWorkerQuals(@Param("workerId") long workerId);

    WorkerQualRow findWorkerQual(@Param("id") long id);

    long insertWorkerQual(@Param("workerId") long workerId, @Param("f") WorkerQualValue f, @Param("by") String by);

    int updateWorkerQual(@Param("id") long id, @Param("f") WorkerQualValue f, @Param("by") String by);

    // ---------- 게이트 ----------

    List<GateReqRow> findGateReqs(@Param("procId") long procId);

    List<WorkerQualRow> findHeld(@Param("workerId") long workerId, @Param("qualIds") List<Long> qualIds);
}
