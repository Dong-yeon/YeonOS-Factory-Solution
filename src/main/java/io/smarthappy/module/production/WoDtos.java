package io.smarthappy.module.production;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

import lombok.Getter;
import lombok.Setter;

/** 작업지시 DTO (P2 3단계, docs/25 4.4). 응답 수량은 글자(trim_scale) — 브라우저 Double 손실을 피한다 */
public final class WoDtos {

    private WoDtos() {
    }

    @Getter
    @Setter
    public static class WoRow {
        private long woId;
        private String woNo;
        private String plantCd;
        private String plantNm;
        private long itemId;
        private String itemCd;
        private String itemNm;
        private String planQty;

        /** 엑셀 숫자 칸(docs/25 4.40) */
        public java.math.BigDecimal getPlanQtyNum() {
            return MesExport.num(planQty);
        }
        private String unitCd;
        private LocalDate planStart;
        private LocalDate planEnd;
        private long routingId;
        private String routingRev;
        private Long bomId;
        private String bomRev;
        private Long orderLineId;
        private String orderNo;
        private Integer orderLineNo;
        private String custNm;
        private int priority;
        private String status;
        private OffsetDateTime releasedAt;
        private String releasedBy;
        private String cancelReason;
        private String remark;
        private int procCnt;
        private int rowVer;
        private OffsetDateTime updatedAt;
        private String updatedBy;

        /** grid 수주 칸 — 수주 번호-행 번호(없으면 null) */
        public String getOrderRef() {
            return orderNo == null ? null : orderNo + "-" + orderLineNo;
        }
    }

    @Getter
    @Setter
    public static class ProcRow {
        private long woProcId;
        private int opSeq;
        private String procCd;
        private String procNm;
        private Long nodeId;
        private String nodeCd;
        private String stdCtSec;
        private int unitsPerCycle;
        private String setupMin;
        private String status;
        private Long equipId;
        private String equipCd;
        private String equipNm;
        private int rowVer;
        private List<ParamRow> params;
    }

    @Getter
    @Setter
    public static class ParamRow {
        private long woProcId;
        private String paramCd;
        private String paramNm;
        private String unitNm;
        private String targetVal;
        private String lsl;
        private String usl;
    }

    @Getter
    @Setter
    public static class BomRow {
        private int seq;
        private String itemCd;
        private String itemNm;
        private String lineType;
        private String reqQty;
        private String unitCd;
        private Integer opSeq;
        private String issueMethod;
        private String altGrp;
        private Integer altPriority;
        private String altPct;
        private String srcQty;
        private String srcUnitCd;
        private String scrapPct;
        private String phantomPath;
    }

    public record WoList(List<WoRow> rows, boolean more) {
    }

    /** 지시 grid 정렬 허용 목록(UI 단계 3 ①) — field = grid 열 이름, enum 이름 = 매퍼 choose 키 */
    public enum WoSort implements io.smarthappy.platform.common.query.SortField {
        WO_NO("woNo"), ITEM_CD("itemCd"), PLAN_START("planStart"), PLAN_END("planEnd"), PRIORITY("priority"), STATUS("status");

        private final String field;

        WoSort(String field) {
            this.field = field;
        }

        @Override
        public String field() {
            return field;
        }
    }

    /** grid 엑셀(docs/25 4.40) — 목록과 같은 조건 + grid 정렬·보이는 열 */
    public record WoExport(String plantCd, String from, String to, String status, String itemCd, String keyword, List<String> sort,
            List<String> columns) {
    }

    public record WoPage(List<WoRow> list, Long total) {
    }

    public record WoDetail(WoRow wo, List<ProcRow> procs, List<BomRow> boms) {
    }

    /** 선택지 — 품목·공장·계획 시작일에 맞는 확정 리비전, 진행 중 수주 행, 공장 설비 */
    public record WoOptions(String itemCd, String itemNm, String unitCd, List<RevOption> routings, List<RevOption> boms,
            List<OrderLineOption> orderLines) {
    }

    @Getter
    @Setter
    public static class RevOption {
        private long id;
        private String rev;
        private int altNo;
        private LocalDate validFrom;
        private LocalDate validTo;
    }

    @Getter
    @Setter
    public static class OrderLineOption {
        private long lineId;
        private String orderNo;
        private int lineNo;
        private String custNm;
        private String qty;
        private LocalDate dueDate;
    }

    @Getter
    @Setter
    public static class EquipOption {
        private long equipId;
        private String equipCd;
        private String equipNm;
    }

    /** 수량은 글자로 받는다 */
    public record WoForm(String plantCd, String itemCd, String planQty, String planStart, String planEnd, Long routingId,
            Long bomId, Long orderLineId, Integer priority, String remark, Integer rowVer) {
    }

    public record WoValue(String plantCd, Long itemId, BigDecimal planQty, String unitCd, LocalDate planStart,
            LocalDate planEnd, Long routingId, Long bomId, Long orderLineId, int priority, String remark) {
    }

    public record ActForm(String reason, Integer rowVer) {
    }

    public record AssignForm(String equipCd, Integer rowVer) {
    }

    // ---------- 발행(스냅샷) 내부 ----------

    @Getter
    @Setter
    public static class RefRow {
        private long id;
        private long itemId;
        private String plantCd;
        private String status;
        private LocalDate validFrom;
        private LocalDate validTo;
        private BigDecimal baseQty;
    }

    @Getter
    @Setter
    public static class SrcBomLine {
        private long lineId;
        private long bomId;
        private long itemId;
        private String itemCd;
        private String lineType;
        private BigDecimal qty;
        private String unitCd;
        private String itemUnit;
        private BigDecimal factor;
        private BigDecimal scrapPct;
        private String fixedYn;
        private String issueMethod;
        private Integer opSeq;
        private String altGrp;
        private Integer altPriority;
        private BigDecimal altPct;
    }

    public record SnapBom(int seq, long itemId, String lineType, BigDecimal reqQty, String unitCd, Integer opSeq,
            String issueMethod, String altGrp, Integer altPriority, BigDecimal altPct, long srcBomId, long srcLineId,
            BigDecimal srcQty, String srcUnitCd, BigDecimal scrapPct, String phantomPath) {
    }
}
