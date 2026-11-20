package io.smarthappy.module.wms.asrs;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonInclude;

import lombok.Getter;
import lombok.Setter;

/** 자동창고 보관 용기 DTO (docs/65 23장) */
public final class AsrsDtos {

    private AsrsDtos() {
    }

    @Getter
    @Setter
    public static class AsrsRow {
        private long asrsId;
        private String plantCd;
        private String asrsCd;
        private String asrsNm;
        private long storeNodeId;
        private String storeNodeCd;
        private long inNodeId;
        private String inNodeCd;
        private long outNodeId;
        private String outNodeCd;
        private long terminalId;
        private String terminalCd;
        private String connMode;
        private String wesUrl;
        private String useYn;
        private String remark;
        private int rowVer;
    }

    public record AsrsForm(String plantCd, String asrsCd, String asrsNm, Long storeNodeId, Long inNodeId, Long outNodeId, Long terminalId,
            String connMode, String wesUrl, String useYn, String remark, Integer rowVer) {
    }

    @Getter
    @Setter
    public static class CtrTypeRow {
        private long typeId;
        private String typeCd;
        private String typeNm;
        private int lengthMm;
        private int widthMm;
        private int heightMm;
        private int slotCnt;
        private String useYn;
        private int rowVer;
    }

    public record CtrTypeForm(String typeCd, String typeNm, Integer lengthMm, Integer widthMm, Integer heightMm, Integer slotCnt, String useYn,
            Integer rowVer) {
    }

    @Getter
    @Setter
    public static class CtrRow {
        private long ctrId;
        private long asrsId;
        private String asrsCd;
        private String ctrCd;
        private long typeId;
        private String typeCd;
        private int slotCnt;
        private String status;
        private String excReason;
        private String useYn;
        private int rowVer;
        /** 같은 칸에 같은 품목 다른 LOT 이 섞였나(피킹·실사 대상에서 빠짐 — 장비 규칙) */
        private boolean mixedLot;
        private int contentRows;
    }

    public record CtrForm(Long asrsId, String ctrCd, Long typeId, String useYn, Integer rowVer) {
    }

    @Getter
    @Setter
    public static class ContentRow {
        private long contentId;
        private long ctrId;
        private int slotNo;
        private long itemId;
        private String itemCd;
        private String itemNm;
        private Long lotId;
        private String lotNo;
        private String stockStatus;
        private BigDecimal qty;
    }

    public record ContentLine(Integer slotNo, String itemCd, String lotNo, String stockStatus, BigDecimal qty) {
    }

    /** 용기 내용 정의 — 전체 덮어쓰기(빠진 칸은 비워짐). rowVer = 용기 rowVer */
    public record ContentForm(List<ContentLine> lines, Integer rowVer) {
    }

    // ------------------------------------------------------------------ 요청

    @Getter
    @Setter
    public static class ReqRow {
        private long reqId;
        private long asrsId;
        private String asrsCd;
        private String reqType;
        private UUID reqKey;
        private String sendState;
        /** 라인 집계로 계산한 진행 상태(직접 저장하지 않는다) — NOT_STARTED·IN_PROGRESS·DONE·PARTIAL·FAILED·CANCELLED */
        private String progress;
        private int lineCnt;
        private int doneCnt;
        private OffsetDateTime sentAt;
        private OffsetDateTime createdAt;
        private String createdBy;
        private String remark;
        private int rowVer;
        /** 긴급 출고(Q-E4-4 — 긴급만 WES 로 우선순위를 보낸다) */
        private String urgentYn;
        private String urgentReason;
    }

    @Getter
    @Setter
    public static class LineRow {
        private long lineId;
        private long reqId;
        private int lineNo;
        private Long ctrId;
        private String ctrCd;
        private Long itemId;
        private String itemCd;
        private Long lotId;
        private String lotNo;
        private BigDecimal qty;
        private BigDecimal doneQty;
        private String status;
        private String wesStatus;
        private String lastReason;
        private OffsetDateTime startedAt;
        private OffsetDateTime finishedAt;
    }

    /** 입고 요청 — 라인 1 = 용기 1(내용 정의가 끝난 LOADED 용기) */
    public record InReqForm(Long asrsId, List<String> ctrCds, String remark) {
    }

    /** 출고 라인 — 품목 + 선택 LOT(없으면 WES 가 고름, Q-E4-3) + 수량 */
    public record OutLine(String itemCd, String lotNo, BigDecimal qty) {
    }

    public record OutReqForm(Long asrsId, List<OutLine> lines, String remark) {
    }

    /** 긴급 표시(APPROVE) — 사유 필수 */
    public record UrgentForm(Integer rowVer, String reason) {
    }

    @Getter
    @Setter
    public static class UrgentBy {
        private String markedBy;
        private int cnt;
    }

    /** 긴급 남용 감시 — 기간 안 출고 요청 수·긴급 수·표시한 사람별 건수 */
    public record UrgentStats(int days, int outCnt, int urgentCnt, List<UrgentBy> byUser) {
    }

    @Getter
    @Setter
    public static class EventRow {
        private long eventId;
        private Long reqId;
        private Long lineId;
        private Long ctrId;
        private String ctrCd;
        private String eventCd;
        private String wesStatus;
        private String reasonCd;
        private String detail;
        private String src;
        private OffsetDateTime occurredAt;
        private String recordedBy;
    }

    public record ReqView(ReqRow head, List<LineRow> lines, List<EventRow> events) {
    }

    // ------------------------------------------------------------------ 장비 API

    /** 엣지가 가져가는 요청 — WES 로 그대로 넘길 본문. priority 는 긴급만 "URGENT", 일반은 칸 자체가 없다(Q-E4-4) */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record WesRequest(UUID reqKey, String type, String asrsCd, String priority, List<WesLine> lines) {
    }

    public record WesLine(int lineNo, String ctrCd, List<WesContent> contents, String itemCd, String lotNo, BigDecimal qty) {
    }

    public record WesContent(int slot, String itemCd, String lotNo, BigDecimal qty) {
    }

    public record ExchangeIn(String scanned, String direction) {
    }

    public record ExchangeView(String decision, String reasonCd, String ctrCd, UUID reqKey, Integer lineNo) {
    }

    // ------------------------------------------------------------------ 대사 (docs/65 25.3)

    public record SnapContent(Integer slot, String itemCd, String lotNo, BigDecimal qty) {
    }

    public record SnapCtr(String ctrCd, List<SnapContent> contents) {
    }

    /** WES 전체 용기 정의 한 조각 — (snapKey, partNo) 재전송 = DUP, lastPart 의 partNo = 전체 조각 수 */
    public record SnapIn(String snapKey, Integer partNo, Boolean lastPart, List<SnapCtr> containers) {
    }

    /** RECEIVED(더 기다림) · DUP(이미 받은 조각·끝난 회차) · DONE(비교 끝) */
    public record SnapView(String result, long reconId, Integer diffCnt, Integer skippedCnt) {
    }

    @Getter
    @Setter
    public static class ReconRow {
        private long reconId;
        private long asrsId;
        private String asrsCd;
        private String snapKey;
        private String status;
        private int partCnt;
        private Integer expectedParts;
        private int ctrCnt;
        private int skippedCnt;
        private int diffCnt;
        private int openCnt;
        private OffsetDateTime startedAt;
        private OffsetDateTime finishedAt;
    }

    @Getter
    @Setter
    public static class DiffRow {
        private long diffId;
        private long reconId;
        private long asrsId;
        private Long ctrId;
        private String ctrCd;
        private int slotNo;
        private Long itemId;
        private String itemCd;
        private Long lotId;
        private String lotNo;
        private String stockStatus;
        private BigDecimal ourQty;
        private BigDecimal wesQty;
        private String diffKind;
        private String status;
        private String adjReqId;
        private String reasonCd;
        private String remark;
        private String resolvedBy;
        private OffsetDateTime resolvedAt;
        private int rowVer;
    }

    /** 우리 쪽 사본 한 줄(대사 비교) — IN_SYSTEM·QUARANTINE 용기 */
    @Getter
    @Setter
    public static class OurContent {
        private long ctrId;
        private String ctrCd;
        private int slotNo;
        private long itemId;
        private String itemCd;
        private Long lotId;
        private String lotNo;
        private String stockStatus;
        private BigDecimal qty;
    }

    public record ReconView(ReconRow head, List<DiffRow> diffs) {
    }

    /** 대사 차이 바로 조정(APPROVE, Q-E4-1) — 사유 = 재고 조정 사유 WMS_ADJ_REASON */
    public record AdjustIn(String reasonCd, String remark, Integer rowVer) {
    }

    public record DismissIn(String remark, Integer rowVer) {
    }
}
