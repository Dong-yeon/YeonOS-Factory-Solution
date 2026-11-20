package io.smarthappy.module.qms;

import java.time.OffsetDateTime;
import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import io.smarthappy.module.qms.InspDtos.GaugeOption;
import io.smarthappy.module.qms.InspDtos.InspRow;
import io.smarthappy.module.qms.InspDtos.LineRow;
import io.smarthappy.module.qms.InspDtos.LineValue;
import io.smarthappy.module.qms.InspDtos.LotRef;
import io.smarthappy.module.qms.InspDtos.MeasRow;
import io.smarthappy.module.qms.InspDtos.MeasValue;
import io.smarthappy.module.qms.InspDtos.OffHead;
import io.smarthappy.module.qms.InspDtos.OffRow;
import io.smarthappy.module.qms.InspDtos.PlanLine;
import io.smarthappy.platform.common.query.PageQuery;

@Mapper
public interface InspMapper {

    List<InspRow> findPage(@Param("keywordLike") String keywordLike, @Param("inspType") String inspType, @Param("status") String status,
            @Param("result") String result, @Param("limit") int limit);

    List<InspRow> findGrid(@Param("keywordLike") String keywordLike, @Param("inspType") String inspType, @Param("status") String status,
            @Param("result") String result, @Param("page") PageQuery page);

    long countList(@Param("keywordLike") String keywordLike, @Param("inspType") String inspType, @Param("status") String status,
            @Param("result") String result);

    InspRow findById(@Param("id") long id);

    OffHead offHead(@Param("id") long id);

    List<OffRow> offCandidates(@Param("itemId") long itemId, @Param("procId") Long procId, @Param("limit") int limit);

    List<OffRow> offOfLots(@Param("inspType") String inspType, @Param("procId") long procId, @Param("lotIds") java.util.Collection<Long> lotIds);

    /** 지시 LOT 들의 초품·종품(무효 빼고) 최근부터 — 공정 일치(procId null 이면 공정 없는 검사), 27.4 */
    List<OffRow> offOfWoLots(@Param("procId") Long procId, @Param("lotIds") java.util.Collection<Long> lotIds);

    InspRow lockInsp(@Param("id") long id);

    /** 엑셀 올리기 — 검사 번호로(id·번호·상태·생성 시각) */
    InspRow findInspByNo(@Param("no") String no);

    Long gaugeIdByCd(@Param("cd") String cd);

    long countLiveMeas();

    List<LineRow> findLines(@Param("id") long id);

    List<MeasRow> findLiveMeas(@Param("id") long id);

    LotRef findLot(@Param("lotNo") String lotNo);

    Long findProc(@Param("cd") String cd);

    List<PlanLine> findPlanLines(@Param("planId") long planId);

    long insertInsp(@Param("no") String no, @Param("type") String type, @Param("lotId") long lotId, @Param("itemId") long itemId,
            @Param("procId") Long procId, @Param("planId") long planId, @Param("lotQty") long lotQty, @Param("at") OffsetDateTime at,
            @Param("remark") String remark, @Param("by") String by, @Param("floor") InspDtos.MeasSource floor);

    void insertLines(@Param("inspId") long inspId, @Param("rows") List<LineValue> rows, @Param("by") String by);

    void insertMeas(@Param("rows") List<MeasValue> rows, @Param("src") InspDtos.MeasSource src, @Param("by") String by);

    /** 무효 행 — 가리키는 값 행이 그 검사의 살아 있는 값일 때만 1 */
    int insertVoid(@Param("inspId") long inspId, @Param("measId") long measId, @Param("at") OffsetDateTime at, @Param("by") String by);

    int setLineResult(@Param("lineId") long lineId, @Param("result") String result);

    int judge(@Param("id") long id, @Param("result") String result, @Param("rowVer") int rowVer, @Param("by") String by,
            @Param("workerId") Long workerId);

    List<String> findOpenNcrNos(@Param("id") long id);

    int voidInsp(@Param("id") long id, @Param("reason") String reason, @Param("rowVer") int rowVer, @Param("by") String by);

    List<GaugeOption> findGauges(@Param("gaugeClass") String gaugeClass);

    /** 게이지 자산의 계측기 클래스(GAUGE 가 아니거나 없으면 null) */
    String gaugeClassOf(@Param("assetId") long assetId);
}
