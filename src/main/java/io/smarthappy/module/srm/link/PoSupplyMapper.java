package io.smarthappy.module.srm.link;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import lombok.Getter;
import lombok.Setter;

@Mapper
public interface PoSupplyMapper {

    /** 열린 발주 행 + 기본 단위 환산 계수(같은 단위 1, 환산표 없으면 null) + 유효 회신(거절 아님) 묶음 id */
    List<OpenLine> openLines(@Param("plantCd") String plantCd, @Param("itemIds") Collection<Long> itemIds);

    /** 발주 행들의 살아 있는 ASN(확정·입고 중) 남은 양 — 도착 예정일·ASN 순 */
    List<AsnSupply> asnSupplies(@Param("poLineIds") Collection<Long> poLineIds);

    /** 묶음들의 약속 줄 */
    List<Promise> promises(@Param("ackIds") Collection<Long> ackIds);

    @Getter
    @Setter
    class OpenLine {
        private long poId;
        private String poNo;
        private long custId;
        private long poLineId;
        private int lineNo;
        private long itemId;
        private LocalDate dueDate;
        private BigDecimal openQty;
        /** 입고 수량 — 약속을 이른 것부터 이만큼 지운다 */
        private BigDecimal recvQty;
        private String lineUnit;
        private String baseUnit;
        private BigDecimal factor;
        private Long ackId;
    }

    @Getter
    @Setter
    class Promise {
        private long ackId;
        private long poLineId;
        private LocalDate promisedDate;
        private BigDecimal promisedQty;
    }

    @Getter
    @Setter
    class AsnSupply {
        private long poLineId;
        private LocalDate etaDate;
        private BigDecimal remainQty;
    }
}
