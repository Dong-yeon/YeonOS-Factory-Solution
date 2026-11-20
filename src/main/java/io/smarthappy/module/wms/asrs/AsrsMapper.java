package io.smarthappy.module.wms.asrs;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import io.smarthappy.module.wms.asrs.AsrsDtos.AsrsForm;
import io.smarthappy.module.wms.asrs.AsrsDtos.AsrsRow;
import io.smarthappy.module.wms.asrs.AsrsDtos.ContentRow;
import io.smarthappy.module.wms.asrs.AsrsDtos.CtrForm;
import io.smarthappy.module.wms.asrs.AsrsDtos.CtrRow;
import io.smarthappy.module.wms.asrs.AsrsDtos.CtrTypeForm;
import io.smarthappy.module.wms.asrs.AsrsDtos.CtrTypeRow;
import io.smarthappy.module.wms.asrs.AsrsDtos.DiffRow;
import io.smarthappy.module.wms.asrs.AsrsDtos.EventRow;
import io.smarthappy.module.wms.asrs.AsrsDtos.LineRow;
import io.smarthappy.module.wms.asrs.AsrsDtos.OurContent;
import io.smarthappy.module.wms.asrs.AsrsDtos.ReconRow;
import io.smarthappy.module.wms.asrs.AsrsDtos.ReqRow;
import io.smarthappy.module.wms.asrs.AsrsDtos.UrgentBy;

@Mapper
public interface AsrsMapper {

    // ---- 자동창고
    List<AsrsRow> findAsrs(@Param("plantCd") String plantCd);

    AsrsRow findAsrsById(@Param("id") long id);

    AsrsRow findByTerminal(@Param("terminalId") long terminalId);

    long insertAsrs(@Param("f") AsrsForm f, @Param("by") String by);

    int updateAsrs(@Param("id") long id, @Param("f") AsrsForm f, @Param("by") String by);

    /** 같은 회사 노드인가 + 그 노드의 용도 */
    Map<String, Object> nodeInfo(@Param("nodeId") long nodeId);

    boolean deviceActive(@Param("id") long id);

    // ---- 용기 유형·용기
    List<CtrTypeRow> findTypes();

    CtrTypeRow findType(@Param("id") long id);

    long insertType(@Param("f") CtrTypeForm f, @Param("by") String by);

    int updateType(@Param("id") long id, @Param("f") CtrTypeForm f, @Param("by") String by);

    List<CtrRow> findCtrs(@Param("asrsId") Long asrsId, @Param("status") String status, @Param("like") String like);

    CtrRow findCtr(@Param("id") long id);

    CtrRow lockCtr(@Param("id") long id);

    CtrRow lockCtrByCd(@Param("ctrCd") String ctrCd);

    long insertCtr(@Param("f") CtrForm f, @Param("by") String by);

    int updateCtr(@Param("id") long id, @Param("f") CtrForm f, @Param("by") String by);

    int setCtrStatus(@Param("id") long id, @Param("status") String status, @Param("reason") String reason, @Param("by") String by);

    List<ContentRow> contents(@Param("ctrId") long ctrId);

    void deleteContents(@Param("ctrId") long ctrId);

    void insertContent(@Param("ctrId") long ctrId, @Param("slotNo") int slotNo, @Param("itemId") long itemId, @Param("lotId") Long lotId,
            @Param("status") String status, @Param("qty") BigDecimal qty, @Param("by") String by);

    Long itemIdByCd(@Param("itemCd") String itemCd);

    Long lotIdByNo(@Param("itemId") long itemId, @Param("lotNo") String lotNo);

    /** 그 칸(노드)의 현재고 — LOT·상태 정확히 */
    BigDecimal stockAt(@Param("itemId") long itemId, @Param("nodeId") long nodeId, @Param("lotId") Long lotId,
            @Param("status") String status);

    // ---- 요청
    long insertReq(@Param("asrsId") long asrsId, @Param("type") String type, @Param("reqKey") UUID reqKey, @Param("remark") String remark,
            @Param("by") String by);

    void insertLine(@Param("reqId") long reqId, @Param("lineNo") int lineNo, @Param("ctrId") Long ctrId, @Param("itemId") Long itemId,
            @Param("lotId") Long lotId, @Param("qty") BigDecimal qty, @Param("by") String by);

    List<ReqRow> findReqs(@Param("asrsId") Long asrsId, @Param("sendState") String sendState, @Param("limit") int limit);

    ReqRow findReq(@Param("id") long id);

    ReqRow lockReq(@Param("id") long id);

    ReqRow lockReqByKey(@Param("asrsId") long asrsId, @Param("reqKey") UUID reqKey);

    int setSendState(@Param("id") long id, @Param("state") String state, @Param("messageId") UUID messageId, @Param("by") String by);

    List<LineRow> lines(@Param("reqId") long reqId);

    LineRow lockLine(@Param("reqId") long reqId, @Param("lineNo") int lineNo);

    int setLine(@Param("lineId") long lineId, @Param("status") String status, @Param("wesStatus") String wesStatus,
            @Param("doneQty") BigDecimal doneQty, @Param("reason") String reason, @Param("by") String by);

    int cancelLines(@Param("reqId") long reqId, @Param("by") String by);

    /** 엣지가 가져갈 요청(QUEUED) — 그 자동창고 것만, 잠금 */
    List<Long> claimable(@Param("asrsId") long asrsId, @Param("max") int max);

    /** 투입 직전 스캔한 용기의 기다리는 입고 라인 */
    LineRow pendingInLine(@Param("ctrId") long ctrId);

    /** 그 용기에 끝나지 않은 요청 라인이 있나(보내기 전 포함) */
    boolean hasActiveLine(@Param("ctrId") long ctrId);

    /** 오래 머문 요청(보낸 뒤 시작 안 함·진행 중) — 아직 경보 안 낸 것 */
    List<Long> staleReqs(@Param("minutes") int minutes);

    // ---- 원장
    void insertEvent(@Param("asrsId") long asrsId, @Param("reqId") Long reqId, @Param("lineId") Long lineId, @Param("ctrId") Long ctrId,
            @Param("eventCd") String eventCd, @Param("wesStatus") String wesStatus, @Param("reasonCd") String reasonCd,
            @Param("detail") String detail, @Param("inboundId") Long inboundId, @Param("moveGrpId") UUID moveGrpId, @Param("src") String src,
            @Param("occurredAt") OffsetDateTime occurredAt, @Param("method") String method, @Param("by") String by);

    List<EventRow> eventsOfReq(@Param("reqId") long reqId);

    List<EventRow> eventsOfCtr(@Param("ctrId") long ctrId);

    boolean codeUsable(@Param("grpCd") String grpCd, @Param("code") String code);

    // ---- 출고 (docs/65 25.1)
    /** 출고에 쓸 수 있는 수량 = 가상 로케이션 AVAILABLE − 격리 용기 내용 − 다른 열린 출고 라인 남은 수량. lotId 없으면 품목 합 */
    BigDecimal outFree(@Param("asrsId") long asrsId, @Param("nodeId") long nodeId, @Param("itemId") long itemId, @Param("lotId") Long lotId);

    /** 인계할 용기 칸(AVAILABLE, 그 품목·LOT·칸) — 잠금 */
    List<ContentRow> pickable(@Param("ctrId") long ctrId, @Param("itemId") long itemId, @Param("lotId") Long lotId, @Param("slot") Integer slot);

    void setContentQty(@Param("contentId") long contentId, @Param("qty") BigDecimal qty, @Param("by") String by);

    void deleteContent(@Param("contentId") long contentId);

    int markUrgent(@Param("id") long id, @Param("reason") String reason, @Param("by") String by);

    int outCount(@Param("days") int days);

    int urgentCount(@Param("days") int days);

    List<UrgentBy> urgentByUser(@Param("days") int days);

    // ---- 대사 (docs/65 25.3)
    ReconRow lockRecon(@Param("asrsId") long asrsId, @Param("snapKey") String snapKey);

    Long insertRecon(@Param("asrsId") long asrsId, @Param("snapKey") String snapKey, @Param("by") String by);

    boolean partExists(@Param("reconId") long reconId, @Param("partNo") int partNo);

    void insertPart(@Param("reconId") long reconId, @Param("partNo") int partNo, @Param("body") String body);

    List<String> parts(@Param("reconId") long reconId);

    void setReconParts(@Param("reconId") long reconId, @Param("expected") Integer expected);

    void finishRecon(@Param("reconId") long reconId, @Param("ctrCnt") int ctrCnt, @Param("skipped") int skipped, @Param("diffCnt") int diffCnt);

    List<OurContent> ourContents(@Param("asrsId") long asrsId);

    List<CtrRow> ctrsOf(@Param("asrsId") long asrsId);

    /** 움직이는 중인 용기 — 끝나지 않은 입고 라인 / 끝나지 않은 출고 라인에 사건이 있는 용기 */
    List<Long> movingCtrs(@Param("asrsId") long asrsId);

    void insertDiff(@Param("d") DiffRow d);

    List<ReconRow> recons(@Param("asrsId") Long asrsId, @Param("limit") int limit);

    ReconRow recon(@Param("id") long id);

    List<DiffRow> diffs(@Param("reconId") long reconId);

    DiffRow lockDiff(@Param("id") long id);

    int resolveDiff(@Param("id") long id, @Param("status") String status, @Param("adjReqId") String adjReqId, @Param("reason") String reason,
            @Param("remark") String remark, @Param("by") String by, @Param("rowVer") int rowVer);

    int openDiffCnt(@Param("ctrId") long ctrId);

    /** 대사 조정 때 사본 수량(칸·품목·LOT 합 — 상태 무관) */
    BigDecimal contentQty(@Param("ctrId") long ctrId, @Param("slot") int slot, @Param("itemId") long itemId, @Param("lotId") Long lotId);

    ContentRow contentOf(@Param("ctrId") long ctrId, @Param("slot") int slot, @Param("itemId") long itemId, @Param("lotId") Long lotId,
            @Param("status") String status);
}
