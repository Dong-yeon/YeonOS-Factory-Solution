package io.smarthappy.module.plm.link;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import lombok.Getter;
import lombok.Setter;

/**
 * 변경 표시·조회 (PLM 3b, docs/39 13장). MES 표(lot_master·prd_wo·prd_wo_proc·mst_equip)는 잠금 없는 조회만,
 * 잠그는 것은 plm_eco 행 하나씩(NO KEY UPDATE) — 잠금 순서 MES → ECO.
 */
@Mapper
public interface PlmLinkMapper {

    /** LOT 품목·지시 (다른 회사면 RLS 로 null) */
    LotRef findLot(@Param("lotId") long lotId);

    /** 지시 스냅샷 리비전 */
    LotRef findWo(@Param("woId") long woId);

    boolean equipVisible(@Param("equipId") long equipId);

    /** 이 LOT 에 맞는 대상이 있는 RELEASED ECO (eco_id 오름차순 = 잠금 순서) — 판정은 13.2 표 */
    List<Long> candidates(@Param("lotId") long lotId, @Param("itemId") long itemId, @Param("woId") Long woId,
            @Param("bomId") Long bomId, @Param("routingId") Long routingId, @Param("equipId") Long equipId,
            @Param("day") LocalDate day);

    /** ECO 행 잠금 + 상태 다시 확인 (RELEASED 아니면 null) */
    MarkEco lockReleased(@Param("ecoId") long ecoId);

    boolean marked(@Param("ecoId") long ecoId, @Param("lotId") long lotId);

    int countMarks(@Param("ecoId") long ecoId);

    void insertMark(@Param("ecoId") long ecoId, @Param("lotId") long lotId, @Param("reason") String reason,
            @Param("seqNo") int seqNo, @Param("woId") Long woId, @Param("equipId") Long equipId,
            @Param("occurredAt") OffsetDateTime occurredAt, @Param("by") String by);

    /** 이 LOT 에 표시된 ECO 중 가장 늦게 적용된 것 */
    Long latestMark(@Param("lotId") long lotId);

    /** (가) 리비전 경로 + (나) 표시 — ECO 하나에 한 줄 */
    List<TraceRow> ecosOfLot(@Param("lotId") long lotId);

    /** 표시된 LOT, 도착 순 (계약 EcoTrace.markedLots) */
    List<MarkRow> markedLots(@Param("ecoId") long ecoId);

    /** 품목의 FIRST_LOT 표시, 최근부터 (계약 EcoTrace.firstLotsOfItem) */
    List<MarkRow> firstLotsOfItem(@Param("itemId") long itemId, @Param("limit") int limit);

    /** 출하 확인 — LOT 에 걸린 적용 ECO 중 PPAP 가 APPROVED 아닌 것 (계약 EcoTrace.shipChecks) */
    List<ShipRow> shipChecks(@Param("ids") java.util.Collection<Long> ids);

    /** ECO 의 PPAP 패키지 (계약 EcoLookup.ppap) */
    PpapRow ppapOf(@Param("ecoId") long ecoId);

    /** ECO 머리 (계약 EcoLookup) */
    EcoHead ecoHead(@Param("ecoId") long ecoId);

    /** 대상 — 품목 + 유형별 참조 id(EQUIP·PROC·DOC 만, 그 밖은 null) */
    List<TargetRef> ecoTargets(@Param("ecoId") long ecoId);

    /** ECO 번호로 머리 (계약 EcoLookup.byNo) */
    EcoHead ecoHeadByNo(@Param("ecoNo") String ecoNo);

    /** 여러 ECO 머리·대상 (계약 EcoLookup.ecos — N+1 방지) */
    List<EcoHead> ecoHeads(@Param("ids") java.util.Collection<Long> ids);

    List<TargetRef> ecoTargetsOf(@Param("ids") java.util.Collection<Long> ids);

    /**
     * 품목별 ECO 머리 (계약 releasedFor·approvedFor, docs/39 24장) — basis RELEASED = 적용 시각, APPROVED = 승인 시각 기준,
     * from 이상(회사 자정의 시각), limit 행(상한 + 1 로 잘림 판정)
     */
    List<EcoHead> ecoHeadsForItem(@Param("itemId") long itemId, @Param("basis") String basis, @Param("from") OffsetDateTime from,
            @Param("limit") int limit);

    /** ECO 고객 칸 (계약 EcoLookup.custId — QMS 52장) */
    Long ecoCustId(@Param("ecoId") long ecoId);

    /** ECO 의 PCN, 통지일·id 순 (계약 EcoLookup.pcns — QMS 52장) */
    List<PcnRow> pcnsOf(@Param("ecoId") long ecoId);

    @Getter
    @Setter
    class PcnRow {
        private long pcnId;
        private String pcnNo;
        private long custId;
        private LocalDate notifiedOn;
        private LocalDate responseDue;
        private String status;
        private LocalDate respondedOn;
    }

    @Getter
    @Setter
    class MarkRow {
        private long ecoId;
        private String ecoNo;
        private long lotId;
        private String lotNo;
        private String markReason;
        private int seqNo;
        private OffsetDateTime occurredAt;
    }

    @Getter
    @Setter
    class PpapRow {
        private long ppapId;
        private String status;
        private LocalDate decidedOn;
    }

    @Getter
    @Setter
    class ShipRow {
        private long lotId;
        private long ecoId;
        private String ecoNo;
        private String ppapStatus;
    }

    @Getter
    @Setter
    class EcoHead {
        private long ecoId;
        private String ecoNo;
        private String title;
        private String changeKind;
        private String status;
        private LocalDate effectiveDate;
        private Integer markLots;
        private LocalDate markUntil;
        private String custApprovalStatus;
        private OffsetDateTime approvedAt;
        private OffsetDateTime releasedAt;
    }

    @Getter
    @Setter
    class TargetRef {
        private long ecoId;
        private String targetType;
        /** DOC 대상은 비울 수 있다(docs/39 16.1) */
        private Long itemId;
        private Long refId;
    }

    @Getter
    @Setter
    class LotRef {
        private long itemId;
        private Long woId;
        private Long bomId;
        private Long routingId;
    }

    @Getter
    @Setter
    class MarkEco {
        private long ecoId;
        private String changeKind;
        private Integer markLots;
        private LocalDate markUntil;
    }

    @Getter
    @Setter
    class TraceRow {
        private long ecoId;
        private String ecoNo;
        private String title;
        private LocalDate effectiveDate;
        private String custApprovalStatus;
        private String via;
    }
}
