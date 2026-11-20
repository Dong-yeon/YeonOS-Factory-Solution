package io.smarthappy.module.srm.portal;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

import io.smarthappy.module.srm.asn.AsnDtos.AsnLineForm;
import lombok.Getter;
import lombok.Setter;

/**
 * 협력사 포털 SRM 응답·입력 (docs/42 7장 3, docs/31 4.13 P-1·P-2). 내부 화면 DTO 를 다시 쓰지 않는다 — 협력사가 볼 칸만
 * (입고 쪽은 받은 양만, 내부 메모·담당자·감사 행위자·ERP 번호 없음). 금액·수량은 글자(trim_scale).
 */
public final class PortalSrmDtos {

    private PortalSrmDtos() {
    }

    @Getter
    @Setter
    public static class SupplierInfo {
        private long custId;
        private String custCd;
        private String custNm;
        /** srm_supplier.trade_status — BLOCKED 면 새 입력 거부(docs/42 P1) */
        private String tradeStatus;
    }

    @Getter
    @Setter
    public static class PoRow {
        private long poId;
        private String poNo;
        private int rev;
        private LocalDate poDate;
        private String status;
        private String plantCd;
        private String plantNm;
        private String currencyCd;
        private String totalAmt;
        private OffsetDateTime issuedAt;
        private int lineCnt;
        private Integer ackSeq;
        private String ackStatus;
        private LocalDate ackOn;
        private LocalDate ackDueDate;
        /** 회신할 수 있나(발행·회신됨) — 화면 버튼용, 판정은 서버 */
        private boolean ackable;
    }

    @Getter
    @Setter
    public static class PoLine {
        private long poLineId;
        private int lineNo;
        private String itemCd;
        private String itemNm;
        private String supplierItemCd;
        private String unitCd;
        private String qty;
        private String cancelQty;
        private String recvQty;
        private String openQty;
        private String unitPrice;
        private String amt;
        private LocalDate dueDate;
        private String lineStatus;
        private String promisedQty;
        private LocalDate promisedFirst;
        private LocalDate promisedLast;
    }

    @Getter
    @Setter
    public static class AckRow {
        private long ackId;
        private int ackSeq;
        private int rev;
        private String source;
        private String ackStatus;
        private String rejectReason;
        private String ackByNm;
        private LocalDate ackOn;
        private String remark;
        private OffsetDateTime createdAt;
        private boolean valid;
        private boolean oldRev;
    }

    @Getter
    @Setter
    public static class AckLine {
        private long ackId;
        private long poLineId;
        private int lineNo;
        private String itemCd;
        private LocalDate promisedDate;
        private String promisedQty;
        private String remark;
    }

    public record PoDetail(PoRow po, List<PoLine> lines, List<AckRow> acks, List<AckLine> ackLines) {
    }

    @Getter
    @Setter
    public static class AsnRow {
        private long asnId;
        private String asnNo;
        private String plantCd;
        private String plantNm;
        private LocalDate shipDate;
        private LocalDate etaDate;
        private String supplierRef;
        private String carrierMemo;
        private String source;
        private String status;
        private OffsetDateTime sentAt;
        private String cancelReason;
        private String remark;
        private int rowVer;
        private int lineCnt;
        private int openLineCnt;
    }

    @Getter
    @Setter
    public static class AsnLine {
        private int lineNo;
        private long poLineId;
        private String poNo;
        private int poLineNo;
        private String itemCd;
        private String itemNm;
        private String unitCd;
        private String qty;
        private String recvQty;
        private String supplierLotNo;
        private LocalDate mfgDate;
        private LocalDate expiryDate;
        private Integer packCnt;
        private String remark;
    }

    public record AsnDetail(AsnRow asn, List<AsnLine> lines) {
    }

    /** ASN 에 넣을 수 있는 내 발주 행(그 공장, 발행된 원천 SRM 발주의 열린 행) */
    @Getter
    @Setter
    public static class PoLineOption {
        private long poLineId;
        private String poNo;
        private int lineNo;
        private String itemCd;
        private String itemNm;
        private String unitCd;
        private String openQty;
        private String notifiedQty;
        private String notifiableQty;
        private LocalDate dueDate;
    }

    @Getter
    @Setter
    public static class PlantOption {
        private String plantCd;
        private String plantNm;
    }

    /** 포털 ASN 입력 — 협력사 칸이 없다(로그인 소속) */
    public record AsnForm(String plantCd, String shipDate, String etaDate, String supplierRef, String carrierMemo, String remark,
            Integer rowVer, List<AsnLineForm> lines) {
    }

    /** 내 ASN 잠금 결과 — 포털이 고칠 수 있는 것은 출처 PORTAL 만 */
    @Getter
    @Setter
    public static class OwnAsn {
        private long asnId;
        private String source;
        private String status;
    }

    /** 내게 보낸 클레임(P-4) — 작성 중은 협력사가 모른다(보낸 적 있는 것만). 내부 칸(보낸 사람·종결한 사람·회신 넣은 사람) 없음 */
    @Getter
    @Setter
    public static class ClaimRow {
        private long claimId;
        private String claimNo;
        private String actionCd;
        private String title;
        private String requestText;
        private LocalDate replyDue;
        private String chargeAmt;
        private String currencyCd;
        private String status;
        private OffsetDateTime sentAt;
        private String answerCause;
        private String answerAction;
        private LocalDate answeredOn;
        /** MANUAL(거래 회사가 대신 입력)·PORTAL, null = 칸이 생기기 전 회신 */
        private String answerSource;
        private OffsetDateTime closedAt;
        private String cancelReason;
        private int ncrCnt;
        private int rowVer;
    }

    @Getter
    @Setter
    public static class ClaimNcr {
        private String ncrNo;
        private String itemCd;
        private String itemNm;
        private String ncQty;
        private String unitCd;
    }

    public record ClaimDetail(ClaimRow claim, List<ClaimNcr> ncrs) {
    }

    /** 포털 클레임 회신 — 글만(파일 없음), 회신일은 서버가 오늘로 */
    public record ClaimAnswerForm(Integer rowVer, String answerCause, String answerAction) {
    }
}
