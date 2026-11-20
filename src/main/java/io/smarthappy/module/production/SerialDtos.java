package io.smarthappy.module.production;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

import io.smarthappy.platform.common.query.SortField;
import lombok.Getter;
import lombok.Setter;

/** 장비 시리얼 구성(as-built, docs/77) 요청·응답. 수량은 글자(trim_scale), 엑셀 숫자 칸은 …Num */
public final class SerialDtos {

    private SerialDtos() {
    }

    // ---------- 행 ----------

    /** 시리얼 목록 한 줄 */
    @Getter
    @Setter
    public static class SerialRow {
        private long serialId;
        private String serialNo;
        private long woId;
        private String woNo;
        private String plantCd;
        private String woStatus;
        private long itemId;
        private String itemCd;
        private String itemNm;
        private String planQty;
        private long unitLotId;
        private String unitLotNo;
        /** 장착 중 부품 행 수 */
        private int partCnt;
        private String remark;
        private int rowVer;
        private OffsetDateTime createdAt;
        private String createdBy;
    }

    /** 시리얼을 만들 수 있는 지시 */
    @Getter
    @Setter
    public static class SerialWo {
        private long woId;
        private String woNo;
        private String plantCd;
        private String status;
        private long itemId;
        private String itemCd;
        private String itemNm;
        private String unitCd;
        private String planQty;
        private int serialCnt;
    }

    /** 장착 원장 한 줄(이력) — removed = 이 장착 행이 탈거됨 */
    @Getter
    @Setter
    public static class PartRow {
        private long partId;
        private long serialId;
        private String evType;
        private long partItemId;
        private String partItemCd;
        private String partItemNm;
        private Long partLotId;
        private String partLotNo;
        private String partSerialNo;
        private String qty;
        private String unitCd;
        private Long woProcId;
        private Integer opSeq;
        private String procNm;
        private Long genId;
        private String reason;
        private OffsetDateTime occurredAt;
        private OffsetDateTime recordedAt;
        private String entryMethod;
        private String recordedBy;
        private String workerCd;
        private String workerNm;
        private Long reversalOfId;
        private boolean removed;
        private OffsetDateTime removedAt;
    }

    /** 잠근 시리얼 머리 */
    @Getter
    @Setter
    public static class LockedSerial {
        private long serialId;
        private String serialNo;
        private long woId;
        private String plantCd;
        private long unitLotId;
        private int rowVer;
    }

    @Getter
    @Setter
    public static class PartItem {
        private long itemId;
        private String itemCd;
        private String itemNm;
        private String unitCd;
        private String lotCtrlYn;
        private String useYn;
    }

    /** 지시 자재 스냅샷 한 줄(NORMAL) — 1대 대비용 */
    @Getter
    @Setter
    public static class WoBomLine {
        private long itemId;
        private String itemCd;
        private String itemNm;
        private String unitCd;
        private BigDecimal reqQty;
        private Integer opSeq;
        private String altGrp;
        private Integer altPriority;
        private BigDecimal scrapPct;
        private long srcBomId;
    }

    /** 원래 BOM 의 대체 그룹 후보(비율 0 행 포함) */
    @Getter
    @Setter
    public static class AltCand {
        private long bomId;
        private String altGrp;
        private long itemId;
        private String itemCd;
        private String itemNm;
        private int altPriority;
    }

    /** 장착 중 수량(품목별) */
    @Getter
    @Setter
    public static class Installed {
        private long serialId;
        private String serialNo;
        private long itemId;
        private String itemCd;
        private String itemNm;
        private String unitCd;
        private BigDecimal qty;
    }

    /** 역추적 한 줄 — 장비 시리얼 × 장착 행 */
    @Getter
    @Setter
    public static class TraceRow {
        private long partId;
        private long serialId;
        private String serialNo;
        private String woNo;
        private String itemCd;
        private String itemNm;
        private String partItemCd;
        private String partItemNm;
        private String partLotNo;
        private String partSerialNo;
        private String qty;
        private String unitCd;
        private OffsetDateTime occurredAt;
        private boolean removed;
        private OffsetDateTime removedAt;
        /** 비면 시작 LOT·시리얼을 그대로 장착, 그 밖 = 계보로 거쳐 온(장착된) LOT 번호 */
        private String via;

        /** 상태 칸(LABEL) — 장착 중 ACTIVE / 탈거됨 REMOVED */
        public String getState() {
            return removed ? "REMOVED" : "ACTIVE";
        }

        public BigDecimal getQtyNum() {
            return MesExport.num(qty);
        }
    }

    // ---------- 응답 ----------

    public record SerialPage(List<SerialRow> list, Long total) {
    }

    public record SerialDetail(SerialRow serial, List<PartRow> active, List<PartRow> history) {
    }

    /**
     * BOM 대비 한 줄 — status OK·MISSING·SHORT·OVER·SUBST·EXTRA. altGrp 가 있으면 대체 그룹 한 줄(itemCd = 주 품목),
     * installedItems = 실제로 들어간 품목 코드들
     */
    public record CompareLine(String status, String itemCd, String itemNm, String altGrp, Integer opSeq, String reqQty, String installedQty,
            String unitCd, List<String> installedItems) {
    }

    public record CompareView(String serialNo, String woNo, String planQty, List<CompareLine> lines) {
    }

    public record TracePage(List<TraceRow> list, Long total, boolean truncated) {
    }

    // ---------- 요청 ----------

    public record CreateForm(Long woId, Integer count, String remark) {
    }

    /** 장착 — qty 는 부품 기본 단위 10진 글자, occurredAt = 공장 현지 "yyyy-MM-ddTHH:mm"(비면 지금) */
    public record InstallForm(Integer rowVer, String itemCd, String lotNo, String partSerialNo, String qty, Integer opSeq, String workerCd,
            String occurredAt, String entryMethod) {
    }

    /** 탈거 — correct = 잘못 입력 정정(entry_method CORRECT) */
    public record RemoveForm(Integer rowVer, String reason, Boolean correct, String occurredAt) {
    }

    public record ReplaceForm(Integer rowVer, String reason, InstallForm install) {
    }

    public record SerialExport(String from, String to, String keyword, String itemCd, List<String> sort, List<String> columns) {
    }

    public record TraceExport(String lotNo, String itemCd, String partSerialNo, List<String> sort, List<String> columns) {
    }

    public enum SerialSort implements SortField {
        SERIAL_NO("serialNo"), WO_NO("woNo"), ITEM_CD("itemCd"), CREATED_AT("createdAt");

        private final String field;

        SerialSort(String field) {
            this.field = field;
        }

        @Override
        public String field() {
            return field;
        }
    }

    public enum TraceSort implements SortField {
        SERIAL_NO("serialNo"), OCCURRED_AT("occurredAt");

        private final String field;

        TraceSort(String field) {
            this.field = field;
        }

        @Override
        public String field() {
            return field;
        }
    }
}
