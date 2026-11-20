package io.smarthappy.module.srm.link;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import lombok.Getter;
import lombok.Setter;

/** 반품 출고 게이트 (SRM 2부 R4-4, docs/31 4.12.7) — 회사 범위는 RLS */
@Mapper
public interface ReturnShipMapper {

    /** 확정·출고됨 반품(잠그지 않음) */
    Ret findByNo(@Param("returnNo") String returnNo);

    /** 반품 FOR UPDATE — 게이트의 첫 잠금 */
    Ret lockReturn(@Param("returnId") long returnId);

    /** 같은 출고 근거를 쓴 반품이 있나(회사 안) */
    boolean refUsed(@Param("trxRef") String trxRef);

    /** 그 LOT 를 받은 입고 이력의 근거(그 발주 행, 입고 행 — 입고 하나 = LOT 하나) */
    String receiptOfLot(@Param("poLineId") long poLineId, @Param("lotId") long lotId);

    /** 그 발주 행·LOT 의 순입고(발주 단위) — 입고 + 되돌림(되돌림 행도 원래 입고의 lot_id 를 받는다) */
    BigDecimal netOfLot(@Param("poLineId") long poLineId, @Param("lotId") long lotId);

    /** 출고 근거로 되돌린 입고량(발주 단위, 양수) — 없으면 null */
    BigDecimal reversedQty(@Param("shipRef") String shipRef);

    /** 발주 번호(오류 문구용) */
    String poNoOf(@Param("poLineId") long poLineId);

    int markShipped(@Param("returnId") long returnId, @Param("at") OffsetDateTime at, @Param("trxRef") String trxRef, @Param("by") String by);

    int markUnshipped(@Param("returnId") long returnId, @Param("trxRef") String trxRef, @Param("by") String by);

    @Getter
    @Setter
    class Ret {
        private long returnId;
        private String returnNo;
        private String status;
        private long custId;
        private String plantCd;
        private long lotId;
        private String lotNo;
        private long itemId;
        private String unitCd;
        private BigDecimal qty;
        private String replaceYn;
        private Long poLineId;
        private String shipRef;
        /** 품목 LOT 관리 여부 — 재고에서 뽑은 LOT 인자 판정 */
        private String lotCtrlYn;
        private String baseUnit;
        /** 지시 단위 → 기본 단위(같으면 1, 환산표 없으면 null) */
        private BigDecimal factor;
        private Integer baseScale;
        /** 교체 요구 발주 행 단위·그 단위 → 기본 단위(같으면 1, 환산표 없으면 null)·자릿수 (R4c-단위) */
        private String poUnit;
        private BigDecimal poFactor;
        private Integer poScale;
    }
}
