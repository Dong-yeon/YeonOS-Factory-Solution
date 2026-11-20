package io.smarthappy.module.production;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;

import lombok.Getter;
import lombok.Setter;

/** LOT·계보 DTO (P2 4단계, docs/25 4.5). 응답 수량은 글자(trim_scale) */
public final class LotDtos {

    private LotDtos() {
    }

    @Getter
    @Setter
    public static class LotRow {
        private long lotId;
        private String lotNo;
        private long itemId;
        private String itemCd;
        private String itemNm;
        private String origin;
        private String qtyCreated;
        private String balance;

        /** 엑셀 숫자 칸(docs/25 4.40) */
        public java.math.BigDecimal getBalanceNum() {
            return MesExport.num(balance);
        }

        public java.math.BigDecimal getQtyCreatedNum() {
            return MesExport.num(qtyCreated);
        }
        private String unitCd;
        private Long woId;
        private String woNo;
        private Long supplierCustId;
        private String supplierCd;
        private String supplierNm;
        private String supplierLotNo;
        private OffsetDateTime occurredAt;
        private String holdYn;
        private String holdReason;
        private String remark;
        private int rowVer;
        private OffsetDateTime createdAt;
        private String createdBy;

        /** grid 출처 칸 — 지시 번호, 없으면 공급사 LOT 번호 */
        public String getSource() {
            return woNo != null ? woNo : supplierLotNo;
        }

        /** 엑셀 상태 이름(docs/25 4.40) — 내보낼 때만 채운다 */
        private String stateNm;

        /** grid 상태 칸 — HELD(보류)·EMPTY(잔량 0)·ACTIVE. 병합·분할을 못 하는 LOT 을 화면이 가린다(서버도 LOT_HOLD·EMPTY_LOT 으로 막음) */
        public String getState() {
            return "Y".equals(holdYn) ? "HELD" : "0".equals(balance) ? "EMPTY" : "ACTIVE";
        }
    }

    /** 계보 한 행(직접 연결 — 상세 화면) */
    @Getter
    @Setter
    public static class EdgeRow {
        private long genId;
        private long parentLotId;
        private String parentLotNo;
        private long childLotId;
        private String childLotNo;
        private String relType;
        private String qty;
        private String unitCd;
        /** 맥락(지시·공정·설비) — 역분개 행도 원래 행과 같은 값(docs/25 4.5, V110 이후 행) */
        private Long woId;
        private Long woProcId;
        private Long equipId;
        private String woNo;
        private String reason;
        private OffsetDateTime occurredAt;
        private OffsetDateTime recordedAt;
        private String entryMethod;
        private String recordedBy;
        private String performedBy;
        private Long reversalOfId;
        private boolean reversed;
        /** 설계 변경 표시 사본(3c) — 이 계보의 자식 LOT 을 만들 때 ChangeMarker 가 돌려준 ECO */
        private Long changeId;
    }

    /** 추적 한 줄 — 깊이·LOT·(이 LOT 과 바로 위/아래 LOT 사이의) 연결 유형·순수량 */
    @Getter
    @Setter
    public static class TraceRow {
        private int lvl;
        private long lotId;
        private String lotNo;
        private long viaLotId;
        private String itemCd;
        private String itemNm;
        private String origin;
        private String relType;
        private String qty;
        private String unitCd;
        private String woNo;
        private String supplierLotNo;
        private String holdYn;
        private boolean cycle;
    }

    public record LotList(List<LotRow> rows, boolean more) {
    }

    /** LOT grid 정렬 허용 목록(UI 단계 3 ①) — field = grid 열 이름, enum 이름 = 매퍼 choose 키 */
    public enum LotSort implements io.smarthappy.platform.common.query.SortField {
        LOT_NO("lotNo"), ITEM_CD("itemCd"), ORIGIN("origin"), CREATED_AT("createdAt");

        private final String field;

        LotSort(String field) {
            this.field = field;
        }

        @Override
        public String field() {
            return field;
        }
    }

    /** LOT grid 엑셀(docs/25 4.40) */
    public record LotExport(String from, String to, String itemCd, String keyword, String origin, String holdOnly, List<String> sort,
            List<String> columns) {
    }

    public record LotPage(List<LotRow> list, Long total) {
    }

    /** holds = 살아 있는 보류 근거(docs/25 4.19) */
    public record LotDetail(LotRow lot, List<EdgeRow> parents, List<EdgeRow> children, List<LotHoldMapper.HoldRow> holds) {
    }

    public record TraceView(LotRow root, String dir, List<TraceRow> rows, boolean truncated, int depthLimit) {
    }

    // ---------- 요청 ----------

    public record ReceiveForm(String itemCd, String qty, String supplierCd, String supplierLotNo, String occurredAt,
            String remark) {
    }

    public record SplitForm(List<String> qtys, String reason, String occurredAt) {
    }

    /** 병합: 부모 LOT 들 — 수량을 비우면 잔량 전부 */
    public record MergeForm(List<MergePart> parts, String reason, String occurredAt) {
    }

    public record MergePart(Long lotId, String qty) {
    }

    /** reasonCd = 보류 사유 코드(LOT_HOLD_REASON, docs/68 Q1 — 화면은 필수), reason = 메모(코드가 있으면 선택) 또는 해제 사유 */
    public record HoldForm(String holdYn, String reasonCd, String reason, Integer rowVer) {
    }

    /** 보류 근거 하나 해제 — rowVer 는 LOT 의 것 */
    public record HoldReleaseForm(String reason, Integer rowVer) {
    }

    /** LOT 단위 재작업·수리(7b, D18) — 보류여도 된다. 비우면 잔량 전부 */
    public record ReworkLotForm(String kind, String qty, String reason, String occurredAt) {
    }

    public record ReasonForm(String reason) {
    }

    // ---------- 내부 ----------

    public record NewLot(String lotNo, long itemId, String origin, BigDecimal qty, String unitCd, Long woId,
            Long supplierCustId, String supplierLotNo, Instant occurredAt, String remark) {
    }

    public record NewEdge(long parentLotId, long childLotId, String relType, BigDecimal qty, String unitCd, Long woId,
            Long woProcId, Long equipId, String reason, Instant occurredAt, String entryMethod, Long performedBy,
            Long reversalOfId, Long changeId) {
    }

    @Getter
    @Setter
    public static class LockedLot {
        private long lotId;
        private String lotNo;
        private long itemId;
        private String itemCd;
        private String unitCd;
        private String holdYn;
        private BigDecimal balance;
    }
}
