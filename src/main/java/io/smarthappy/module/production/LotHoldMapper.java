package io.smarthappy.module.production;

import java.time.OffsetDateTime;
import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import lombok.Getter;
import lombok.Setter;

/** LOT 보류 근거 (docs/25 4.19). 회사 조건은 RLS. 잠금 순서 = LOT 행(lockLot) → 근거 행 */
@Mapper
public interface LotHoldMapper {

    /** LOT 행 잠금 — 없거나 다른 회사면 null */
    HoldLot lockLot(@Param("lotId") long lotId);

    /** 살아 있는 그 근거(잠금) — MANUAL 은 refId null */
    HoldRow liveHold(@Param("lotId") long lotId, @Param("source") String source, @Param("refId") Long refId);

    HoldRow findHold(@Param("holdId") long holdId);

    long insertHold(@Param("lotId") long lotId, @Param("source") String source, @Param("refId") Long refId,
            @Param("reasonCd") String reasonCd,
            @Param("reason") String reason, @Param("by") String by);

    int release(@Param("holdId") long holdId, @Param("reason") String reason, @Param("by") String by);

    /** 살아 있는 근거, lot_id → 보류 시각 순 */
    List<HoldRow> liveHolds(@Param("ids") List<Long> lotIds);

    @Getter
    @Setter
    class HoldLot {
        private long lotId;
        private String lotNo;
        private int rowVer;
    }

    @Getter
    @Setter
    class HoldRow {
        private long holdId;
        private long lotId;
        private String source;
        private Long refId;
        /** 사유 코드(LOT_HOLD_REASON, docs/68 Q1) — 비면 미분류 */
        private String reasonCd;
        private String reason;
        private OffsetDateTime heldAt;
        private String heldBy;
        private OffsetDateTime releasedAt;
    }
}
