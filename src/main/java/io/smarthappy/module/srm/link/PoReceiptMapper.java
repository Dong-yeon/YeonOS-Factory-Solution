package io.smarthappy.module.srm.link;

import java.math.BigDecimal;
import java.time.LocalDate;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import lombok.Getter;
import lombok.Setter;

@Mapper
public interface PoReceiptMapper {

    /** 행의 발주 id (잠그지 않음 — 머리를 먼저 잠그려고) */
    Long findPoId(@Param("poLineId") long poLineId);

    /** 발주 공장(잠그지 않음 — 발생 시각을 공장 날짜로 바꿀 때) */
    String plantOf(@Param("poId") long poId);

    /** 머리 FOR UPDATE */
    Head lockHead(@Param("poId") long poId);

    /** 행 FOR UPDATE + 기본 단위·환산 계수(같은 단위 1, 환산표 없으면 null)·발주 단위·기본 단위 자릿수 */
    Line lockLine(@Param("poLineId") long poLineId);

    /** 같은 근거의 이력이 있나(회사 안) */
    boolean refExists(@Param("trxRef") String trxRef);

    /** 그 행의 그날 순입고 */
    BigDecimal dayNet(@Param("poLineId") long poLineId, @Param("on") LocalDate on);

    /** 이력 1행 — 행 입고 수량보다 먼저(합계 = 이력, 커밋 때 검사) */
    int insertReceipt(@Param("poId") long poId, @Param("poLineId") long poLineId, @Param("on") LocalDate on,
            @Param("qty") BigDecimal qty, @Param("trxRef") String trxRef, @Param("asnLineId") Long asnLineId, @Param("lotId") Long lotId,
            @Param("by") String by);

    /** 입고 이력(qty &gt; 0, lot_id 비어 있음) 1행에 LOT — 바뀐 행 수 (R4-8) */
    int attachLot(@Param("trxRef") String trxRef, @Param("lotId") long lotId);

    /** 근거로 이력 행 찾기(되돌림 — R3) */
    ReceiptRef findReceiptByRef(@Param("trxRef") String trxRef);

    /** ASN 행의 머리 FOR UPDATE */
    AsnLock lockAsn(@Param("asnLineId") long asnLineId);

    /** 받은 것 있음 / 모든 행 다 받음 — 트리거 ck_srm_asn_recv_status 와 같은 판정 */
    AsnState asnState(@Param("asnId") long asnId);

    int changeAsnStatus(@Param("asnId") long asnId, @Param("from") String from, @Param("to") String to, @Param("by") String by);

    int addRecv(@Param("poLineId") long poLineId, @Param("qty") BigDecimal qty, @Param("by") String by);

    /** 하나라도 받음 / 열린 행 전부 다 받음 — 트리거 ck_srm_po_recv_status 와 같은 판정 */
    Summary summary(@Param("poId") long poId);

    /** 지금 개정의 유효 회신이 수락·부분인가 */
    boolean ackAccepted(@Param("poId") long poId);

    int changeStatus(@Param("poId") long poId, @Param("from") String from, @Param("to") String to, @Param("by") String by);

    @Getter
    @Setter
    class Head {
        private long poId;
        private String poNo;
        private int rev;
        private String status;
        private String source;
        private long custId;
        private String plantCd;
        private Long whId;
        private LocalDate poDate;
    }

    @Getter
    @Setter
    class Line {
        private long poLineId;
        private long poId;
        private int lineNo;
        private long itemId;
        private String unitCd;
        private BigDecimal qty;
        private BigDecimal cancelQty;
        private BigDecimal recvQty;
        private String lineStatus;
        private String baseUnit;
        private BigDecimal factor;
        private Integer unitScale;
        /** 품목 기본 단위 자릿수 — 환산 결과가 이를 넘으면 거부 */
        private Integer baseScale;
    }

    @Getter
    @Setter
    class Summary {
        private boolean anyRecv;
        private boolean full;
    }

    @Getter
    @Setter
    class ReceiptRef {
        private long poLineId;
        private LocalDate occurredOn;
        private Long asnLineId;
        private Long lotId;
    }

    @Getter
    @Setter
    class AsnLock {
        private long asnId;
        private long poLineId;
        private String status;
    }

    @Getter
    @Setter
    class AsnState {
        private boolean anyRecv;
        private boolean allRecv;
    }
}
