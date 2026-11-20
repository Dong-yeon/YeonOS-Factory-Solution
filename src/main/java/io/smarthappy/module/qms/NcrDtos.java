package io.smarthappy.module.qms;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

import io.smarthappy.module.production.link.LotHoldPort.HoldView;
import io.smarthappy.module.qms.DocDtos.Approval;
import lombok.Getter;
import lombok.Setter;

/** P3 QMS 2부 Q8a 부적합(NCR)·처분 DTO (docs/29_P3 12.7) */
public final class NcrDtos {

    private NcrDtos() {
    }

    @Getter
    @Setter
    public static class NcrRow {
        private long ncrId;
        private String ncrNo;
        private String source;
        private long lotId;
        private String lotNo;
        private long itemId;
        private String itemCd;
        private String itemNm;
        private String unitCd;
        private Long inspId;
        private String inspNo;
        private Long defectId;
        private String defectCd;
        private String defectNm;
        private BigDecimal ncQty;
        private String description;
        private OffsetDateTime occurredAt;
        private String status;
        private String holdYn;
        private Long aprId;
        private String aprStatus;
        private Long aprRequesterId;
        private OffsetDateTime closedAt;
        private String closedBy;
        private String cancelReason;
        /** 계약이 만든 NCR 의 근거(WMS_CUST_RTN 등, 손 NCR 은 null — docs/29_P3 38장) */
        private String srcType;
        private Long srcId;
        /** 계약 NCR 의 근거 수량(반품 수량) — 부적합 수량 상한(R3-c, docs/29_P3 47장). 손 NCR·이 칸 이전 클레임 NCR 은 null */
        private BigDecimal srcQty;
        private OffsetDateTime createdAt;
        private String createdBy;
        private int rowVer;
    }

    /** 이 부적합에 이어진 8D(번호·단계만 — 내용은 8D 화면 권한으로) */
    @Getter
    @Setter
    public static class CapaLink {
        private long capaId;
        private String capaNo;
        private String status;
    }

    @Getter
    @Setter
    public static class DispRow {
        private long dispId;
        private int lineNo;
        private String dispType;
        private BigDecimal qty;
        private String note;
        private String reworkLotNo;
    }

    /** NCR 을 열 LOT — 품목·단위를 그대로 복사 */
    @Getter
    @Setter
    public static class LotRef {
        private long lotId;
        private long itemId;
        private String unitCd;
    }

    @Getter
    @Setter
    public static class InspRef {
        private long inspId;
        private long lotId;
        private String status;
    }

    @Getter
    @Setter
    public static class DefectOption {
        private Long defectId;
        private String defectCd;
        private String defectNm;
    }

    /**
     * 상세 — holds = 그 LOT 의 살아 있는 보류 근거(모든 출처, MES 계약), autoHold = 정책 현재 값,
     * esignRequired = 종결·상신에 서명 필수(정책), signatures = 이 NCR 의 서명(docs/29_P3 12.10)
     */
    /** blockedQty = 처리 중(OPEN·PENDING) NCR 의 LOT 부적합(BLOCKED) 재고 — WMS 품질 상태 연동 공장·LOT 단위 = 기본 단위일 때만, 아니면 null (docs/30 12.15.13 B-2) */
    public record NcrDetail(NcrRow ncr, List<DispRow> disps, List<HoldView> holds, Approval approval, boolean autoHold,
            boolean esignRequired, List<QmsESign.SignView> signatures, List<CapaLink> capas, BigDecimal blockedQty) {
    }

    /** 직접 종결 — esignToken = 전자서명 토큰(정책 Y 면 필수) */
    public record CloseForm(Integer rowVer, String esignToken) {
    }

    /** 특채 결재 상신 — 결재선 + 전자서명 토큰(정책 Y 면 필수) */
    public record NcrSubmitForm(Integer rowVer, String title, List<DocDtos.LineItem> lines, String esignToken) {
    }

    public record CreateForm(String lotNo, String source, String inspNo, Long defectId, BigDecimal ncQty, String description) {
    }

    public record DispLine(String rowKey, String dispType, BigDecimal qty, String note, String reworkLotNo) {
    }

    /** OPEN 동안 머리 내용 + 처분 행(통째 바꾸기) */
    public record SaveForm(Integer rowVer, Long defectId, BigDecimal ncQty, String description, List<DispLine> disps) {
    }

    public record CancelForm(Integer rowVer, String reason) {
    }

    /** 그리드 정렬 허용 — field = 그리드 열, 이름 = 매퍼 choose 키 */
    public enum NcrSort implements io.smarthappy.platform.common.query.SortField {
        NCR_NO("ncrNo"), STATUS("status"), SOURCE("source"), LOT_NO("lotNo"), CREATED_AT("createdAt");

        private final String field;

        NcrSort(String field) {
            this.field = field;
        }

        @Override
        public String field() {
            return field;
        }
    }

    public record NcrPage(List<NcrRow> list, Long total) {
    }
}
