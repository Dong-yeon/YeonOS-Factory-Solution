package io.smarthappy.module.wms.ship;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

import lombok.Getter;
import lombok.Setter;

/** 출하(wms-ship) 값 — P4 2부 B6a (docs/30 12.7) */
public final class ShipDtos {

    /** 줄의 다른 칸(12.7.5) — 그 품목의 가용 재고 칸(출하 대기·폐기 위치 제외), 보류 LOT 은 표시만 */
    @Getter
    @Setter
    public static class StockRow {
        private long nodeId;
        private String path;
        private Long lotId;
        private String lotNo;
        private BigDecimal qty;
        private String holdYn;
    }

    /** 다른 칸 조회 결과 — scan 이 있으면 그 위치(하위 포함)·LOT 로 좁힌 것과 스캔 안내 */
    public record StockPick(List<StockRow> rows, String message) {
    }

    private ShipDtos() {
    }

    // ---------------------------------------------------------------- 입력

    /** 출하 지시 만들기 — 줄 = 같은 고객의 열린 수주 행, 수량은 품목 기본 단위 */
    public record CreateForm(String plantCd, String custCd, Long stageNodeId, String planDate, String remark, List<LineForm> lines) {
    }

    public record LineForm(Long orderLineId, BigDecimal qty) {
    }

    /** 피킹 확정 — 줄마다 출발 칸·LOT·수량(출발 칸 → 출하 대기 이동) */
    public record PickForm(Integer rowVer, String clientReqId, List<PickReq> picks) {
    }

    public record PickReq(Long shipLineId, Long fromNodeId, String lotNo, BigDecimal qty) {
    }

    /** 피킹 취소(이동 역분개) */
    public record UnpickForm(Integer rowVer, List<Long> pickIds, String reason) {
    }

    /** 출하 확정 — 라벨은 선택 */
    public record ConfirmForm(Integer rowVer, String clientReqId, Long printerId, Long tplId, Integer copies) {
    }

    /** 팔레트 만들기·빈 팔레트 취소 — 지시 머리 rowVer (B6b) */
    public record PalletForm(Integer rowVer, String remark) {
    }

    /** 피킹을 팔레트에 담기(palletId) 또는 빼기(palletId = null) */
    public record PalletPicksForm(Integer rowVer, Long palletId, List<Long> pickIds) {
    }

    /** 팔레트 닫기(봉인·SSCC) — 프린터를 고르면 팔레트 라벨 */
    public record PalletCloseForm(Integer rowVer, Long printerId, Long tplId, Integer copies) {
    }

    /** 팔레트 다시 열기(사유) */
    public record PalletReopenForm(Integer rowVer, String reason) {
    }

    /** 출하 취소(확정 뒤 — SHIP 역분개·수주 출하 수량 되돌림) · 지시 취소(DRAFT) */
    /** 운송 정보(docs/58) — rowVer = 머리의 transportRowVer(아직 없으면 null), 빈 값은 지움 */
    public record TransportForm(Integer rowVer, String carrierNm, String vehicleNo, String trackingNo) {
    }

    public record CancelForm(Integer rowVer, String clientReqId, String reason) {
    }

    // ---------------------------------------------------------------- 조회

    @Getter
    @Setter
    public static class ShipHead {
        private long shipId;
        private String shipNo;
        private String plantCd;
        private long custId;
        private String custCd;
        private String custNm;
        private long stageNodeId;
        private String stagePath;
        private LocalDate planDate;
        private String status;
        private String remark;
        private OffsetDateTime shippedAt;
        private String shippedBy;
        private String cancelReason;
        private int rowVer;
        private OffsetDateTime createdAt;
        private String createdBy;
        private int lineCount;
        /** 걸린 출하 특이사항 수 · 그중 확인 필수인데 아직 확인 안 된 수(docs/56) */
        private int noteCnt;
        private int noteOpenCnt;
        /** 운송 정보(docs/58, wms_ship_transport) — 없으면 모두 null. transportRowVer 는 머리 rowVer 와 따로 */
        private String carrierNm;
        private String vehicleNo;
        private String trackingNo;
        private Integer transportRowVer;
        private OffsetDateTime transportAt;
        private String transportBy;
    }

    @Getter
    @Setter
    public static class ShipLine {
        private long shipLineId;
        private int lineNo;
        private long orderLineId;
        private String orderNo;
        private int orderLineNo;
        private LocalDate dueDate;
        private long itemId;
        private String itemCd;
        private String itemNm;
        private String unitCd;
        private String lotCtrlYn;
        private BigDecimal qty;
        /** PICKED + SHIPPED 피킹 합 */
        private BigDecimal pickedQty;
        /** SHIPPED 피킹 합 */
        private BigDecimal shippedQty;
        /**
         * 출하 예약 경고(docs/53 (가), 열린 지시 DRAFT·PICKING 만 — 나머지는 null). 같은 품목 줄은 같은 값.
         * availQty = 공장 가용(가용 정의 하나), reservedOther = 다른 열린 지시의 미피킹 합, netQty = max(가용 − 다른 지시 예약, 0),
         * shortQty = max(이 지시의 그 품목 미피킹 합 − netQty, 0) — 막지 않는다(경고만, L1-3)
         */
        private BigDecimal availQty;
        private BigDecimal reservedOther;
        private BigDecimal netQty;
        private BigDecimal shortQty;
    }

    @Getter
    @Setter
    public static class ShipPick {
        private long pickId;
        private long shipLineId;
        private long fromNodeId;
        private String fromPath;
        private Long lotId;
        private String lotNo;
        private BigDecimal qty;
        private String status;
        private OffsetDateTime createdAt;
        private String createdBy;
        /** 담긴 팔레트(B6b) */
        private Long palletId;
        private String palletNo;
    }

    /** 출하 목록 그리드 정렬 허용 열(UI 단계 3 ①, 결정 docs/29 #45) — 매퍼가 enum 이름으로 열을 고르고 끝에 ship_id 를 붙인다 */
    public enum ShipSort implements io.smarthappy.platform.common.query.SortField {
        SHIP_NO("shipNo"), CUST_CD("custCd"), PLAN_DATE("planDate"), STATUS("status"), CREATED_AT("createdAt");

        private final String field;

        ShipSort(String field) {
            this.field = field;
        }

        @Override
        public String field() {
            return field;
        }
    }

    /** 팔레트 스캔(B6b-2) — text = 스캐너가 보낸 글자 그대로 */
    public record PalletScanForm(String text) {
    }

    /** 팔레트 스캔 결과 — 그 팔레트의 출하 지시 */
    public record PalletHit(long shipId, String shipNo, long palletId, String palletNo, String sscc, String status) {
    }

    /** 팔레트(B6b) — pickCount = 담긴 피킹 수(PICKED·SHIPPED) */
    @Getter
    @Setter
    public static class Pallet {
        private long palletId;
        private String palletNo;
        private String sscc;
        private String status;
        private OffsetDateTime closedAt;
        private String closedBy;
        private int pickCount;
        private int rowVer;
    }

    /** notes = 이 지시에 걸린 출하 특이사항(docs/56) — 걸린 줄·확인 여부 */
    public record ShipView(ShipHead head, List<ShipLine> lines, List<ShipPick> picks, List<Pallet> pallets,
            List<io.smarthappy.module.wms.shipnote.ShipNoteDtos.ShipNote> notes) {
    }

    /** 출하 지시에 넣을 수 있는 수주 행 — 남은 수량 = 수주 − 출하(투영) − 다른 열린 지시에 잡힌 양 */
    @Getter
    @Setter
    public static class OrderLineOpt {
        private long orderLineId;
        private String orderNo;
        private int lineNo;
        private String custPoNo;
        private long itemId;
        private String itemCd;
        private String itemNm;
        private String unitCd;
        private LocalDate dueDate;
        private BigDecimal qty;
        private BigDecimal shippedQty;
        private BigDecimal inOtherShips;
        private BigDecimal openQty;
        /** 공장을 함께 보냈을 때만(docs/53 (가)): 공장 가용·열린 지시 예약 합·순 가용 = max(가용 − 예약, 0) */
        private BigDecimal availQty;
        private BigDecimal reservedQty;
        private BigDecimal netQty;
    }

    /** 원장 SHIP 을 만들 피킹(확정 때) */
    @Getter
    @Setter
    public static class PickRow {
        private long pickId;
        private long shipLineId;
        private long orderLineId;
        private long itemId;
        private String itemCd;
        private String itemNm;
        private String unitCd;
        private Long lotId;
        private String lotNo;
        private BigDecimal qty;
        private long fromNodeId;
        private String status;
        /** 담긴 팔레트(B6b) — 번호·SSCC 는 ERP_SHIP 본문용(B6b-2) */
        private Long palletId;
        private String palletNo;
        private String sscc;
    }

    /** 출하 원장 행(취소 때 역분개 대상) */
    @Getter
    @Setter
    public static class ShipTrx {
        private long trxId;
        private long pickId;
        private long orderLineId;
        private BigDecimal qty;
    }

    /** 노드 선택지 */
    public record NodeOpt(long nodeId, String path, String locUsage) {
    }
}
