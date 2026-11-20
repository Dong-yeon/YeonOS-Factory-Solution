package io.smarthappy.module.qms;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

import io.smarthappy.module.wms.link.ShipQuery;
import java.util.Map;

import io.smarthappy.module.qms.DocDtos.Approval;
import io.smarthappy.platform.common.query.PageQuery;
import io.smarthappy.platform.common.query.SortField;
import lombok.Getter;
import lombok.Setter;

/** P3 QMS Q4 시정조치(8D)·고객 요구사항(CSR) DTO (docs/29) */
public final class CapaDtos {

    private CapaDtos() {
    }

    // ------------------------------------------------------------------ 시정조치

    @Getter
    @Setter
    public static class CapaRow {
        private long capaId;
        private String capaNo;
        private String source;
        private Long custId;
        private String custCd;
        private String custNm;
        private Long itemId;
        private String itemCd;
        private String itemNm;
        private String title;
        private LocalDate occurredOn;
        private String status;
        private long ownerUserId;
        private String ownerNm;
        private LocalDate containDue;
        private LocalDate closeDue;
        /** 목록 판정 — ON_TIME | CONTAIN_OVERDUE | CLOSE_OVERDUE (종결·취소는 null) */
        private String dueState;
        private Long csrId;
        private String csrLabel;
        private String d1Team;
        private String d2Problem;
        private String d3Contain;
        private String d4Cause;
        private String d5Action;
        private String d6Verify;
        private String d7Prevent;
        private String d8Close;
        private Long ncrId;
        /** 연결된 부적합 번호·상태(없으면 null) */
        private String ncrNo;
        private String ncrStatus;
        /** 연결된 부적합의 LOT 번호 — 상세에서만 채운다(출하 추적 바로가기, docs/29_P3 35장). 목록은 null */
        private String ncrLotNo;
        private Long aprId;
        private String aprStatus;
        private Long aprRequesterId;
        private OffsetDateTime containedAt;
        private OffsetDateTime closedAt;
        private String closedBy;
        private String cancelReason;
        private int spreadOpenCnt;
        private int rowVer;
        private OffsetDateTime updatedAt;
    }

    @Getter
    @Setter
    public static class SpreadRow {
        private long spreadId;
        private int lineNo;
        private String targetType;
        private String targetCd;
        private String targetNm;
        private String status;
        private String note;
    }

    /**
     * custReturns = 묶인 고객 반품(WMS ShipQuery.custReturns, docs/29_P3 44장 — WMS 없으면 빈 목록). 8D 에 고객이 있으면 번호로만 묶인
     * 다른 고객 반품은 빼고 그 건수를 returnsOtherCust 에(48장 R3-d), returnsTruncated = 계약 상한(200)에 닿아 더 있을 수 있음
     */
    public record CapaDetail(CapaRow capa, List<SpreadRow> spreads, Approval approval, boolean approvalRequired,
            List<ShipQuery.CustReturn> custReturns, int returnsOtherCust, boolean returnsTruncated, int returnsMax) {
    }

    public record SpreadForm(String targetType, String targetCd, String status, String note) {
    }

    public record CapaForm(String source, String custCd, String itemCd, String title, String occurredOn, Long ownerUserId,
            String d1Team, String d2Problem, String d3Contain, String d4Cause, String d5Action, String d6Verify, String d7Prevent,
            String d8Close, List<SpreadForm> spreads, String ncrNo, Integer rowVer) {
    }

    public record CapaValue(String source, Long custId, Long itemId, String title, LocalDate occurredOn, long ownerUserId,
            String d1Team, String d2Problem, String d3Contain, String d4Cause, String d5Action, String d6Verify, String d7Prevent,
            String d8Close, Integer rowVer) {
    }

    public record SpreadValue(int lineNo, String targetType, Long itemId, Long procId, Long custId, String status, String note) {
    }

    public record CancelForm(Integer rowVer, String reason) {
    }

    /** 기한 근거 — 고객 CSR override (없으면 정책) */
    @Getter
    @Setter
    public static class CsrDue {
        private long csrId;
        private Integer containDays;
        private Integer closeDays;
    }

    @Getter
    public static class CapaSearch {
        private final String keywordLike;
        private final String status;
        private final String source;
        private final String dueState;
        private final boolean withClosed;
        private final LocalDate today;
        private final PageQuery page;

        public CapaSearch(String keywordLike, String status, String source, String dueState, boolean withClosed, LocalDate today,
                PageQuery page) {
            this.keywordLike = keywordLike;
            this.status = status;
            this.source = source;
            this.dueState = dueState;
            this.withClosed = withClosed;
            this.today = today;
            this.page = page;
        }
    }

    public enum CapaSort implements SortField {
        CAPA_NO("capaNo"), OCCURRED_ON("occurredOn"), STATUS("status"), CLOSE_DUE("closeDue"), UPDATED_AT("updatedAt");

        private final String field;

        CapaSort(String field) {
            this.field = field;
        }

        @Override
        public String field() {
            return field;
        }
    }

    public record CapaExportRequest(String keyword, String status, String source, String dueState, Boolean withClosed,
            List<String> sort, List<String> columns) {
    }

    // ------------------------------------------------------------------ CSR

    @Getter
    @Setter
    public static class CsrRow {
        private long csrId;
        private long custId;
        private String custCd;
        private String custNm;
        private String csrNo;
        private String csrRev;
        private String title;
        private LocalDate effectiveFrom;
        private String overridesJson;
        private Map<String, Object> overrides;
        private String notifyReqJson;
        /** 4M 유형 → NOTIFY·APPROVE (docs/29_P3 52장 CN-1) */
        private Map<String, String> notifyReq;
        private String useYn;
        private String remark;
        private String targetText;
        private int rowVer;
        private OffsetDateTime updatedAt;
    }

    @Getter
    @Setter
    public static class CsrTargetRow {
        private String targetType;
        private String targetCd;
        private String targetNm;
    }

    public record CsrDetail(CsrRow csr, List<CsrTargetRow> targets) {
    }

    public record CsrTargetForm(String targetType, String targetCd) {
    }

    public record CsrForm(String custCd, String csrNo, String csrRev, String title, String effectiveFrom, Map<String, Object> overrides,
            String useYn, String remark, List<CsrTargetForm> targets, Integer rowVer, Map<String, String> notifyReq) {
    }

    public record CsrValue(Long custId, String csrNo, String csrRev, String title, LocalDate effectiveFrom, String overridesJson,
            String useYn, String remark, Integer rowVer, String notifyReqJson) {
    }

    public record CsrTargetValue(Long itemId, Long itemClassId) {
    }

    @Getter
    @Setter
    public static class Ref {
        private long id;
        private String useYn;
        private Long classId;
    }

    /** 부적합 번호로 찾은 것 — 8D 연결용 */
    @Getter
    @Setter
    public static class NcrRef {
        private long id;
        private String status;
    }

    /** 그리드 정렬 허용 — field = 그리드 열, 이름 = 매퍼 choose 키 */
    public enum CsrSort implements io.smarthappy.platform.common.query.SortField {
        CUST_CD("custCd"), CSR_NO("csrNo"), EFFECTIVE_FROM("effectiveFrom"), UPDATED_AT("updatedAt");

        private final String field;

        CsrSort(String field) {
            this.field = field;
        }

        @Override
        public String field() {
            return field;
        }
    }

    public record CsrPage(List<CsrRow> list, Long total) {
    }
}
