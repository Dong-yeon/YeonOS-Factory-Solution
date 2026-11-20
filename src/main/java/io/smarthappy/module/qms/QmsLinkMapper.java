package io.smarthappy.module.qms;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import lombok.Getter;
import lombok.Setter;

/** QMS 공개 계약(module.qms.link) 구현용 조회 — 다른 모듈은 이 매퍼가 아니라 계약을 쓴다 */
@Mapper
public interface QmsLinkMapper {

    /** 문서 번호(대소문자 무시)로 사용 중 문서 id — 없으면 null */
    Long findDocIdByNo(@Param("docNo") String docNo);

    /** 협력사 수입검사 품질 품목·단위 한 줄 (docs/29_P3 16장) */
    @Getter
    @Setter
    class QualityRow {
        private long itemId;
        private String itemCd;
        private String unitCd;
        private BigDecimal inspectedQty;
        private BigDecimal rejectedQty;
        private int inspectionCnt;
        private int ngCnt;
        private int openNcrCnt;
        private BigDecimal otherNcQty;
    }

    /** 닫힌 NCR 의 반품 처리 줄 */
    @Getter
    @Setter
    class ReturnRow {
        private long ncrId;
        private String ncrNo;
        private int lineNo;
        private long lotId;
        private String lotNo;
        private String supplierLotNo;
        private long itemId;
        private String itemCd;
        private BigDecimal qty;
        private String unitCd;
        private String defectCd;
        private String defectNm;
        private OffsetDateTime closedAt;
    }

    /** NCR 한 건 */
    @Getter
    @Setter
    class NcrRow {
        private long ncrId;
        private String ncrNo;
        private String status;
        private String source;
        private long lotId;
        private String lotNo;
        private String supplierLotNo;
        private Long supplierCustId;
        private long itemId;
        private String itemCd;
        private BigDecimal ncQty;
        private BigDecimal returnQty;
        private String unitCd;
        private String defectCd;
        private String defectNm;
        private String description;
        private String inspNo;
        private OffsetDateTime occurredAt;
        private OffsetDateTime closedAt;
    }

    List<QualityRow> receiptQuality(@Param("custId") long custId, @Param("fromTs") OffsetDateTime fromTs, @Param("toTs") OffsetDateTime toTs);

    List<ReturnRow> supplierReturns(@Param("custId") long custId, @Param("fromTs") OffsetDateTime fromTs, @Param("toTs") OffsetDateTime toTs,
            @Param("limit") int limit);

    NcrRow ncrDetail(@Param("ncrId") long ncrId);

    List<NcrRow> supplierNcrs(@Param("custId") long custId, @Param("fromTs") OffsetDateTime fromTs, @Param("toTs") OffsetDateTime toTs,
            @Param("limit") int limit);

    /** 검사계획 행 (docs/29_P3 18장) — 규격은 확정 스냅샷 */
    @Getter
    @Setter
    class PlanLineRow {
        private int lineNo;
        private long charId;
        private String charNo;
        private String charNm;
        private String specialCls;
        private String dataType;
        private BigDecimal nominal;
        private BigDecimal lsl;
        private BigDecimal usl;
        private String unitNm;
        private Integer scale;
        private String methodNm;
        private String gaugeClass;
        private String samplingPlanCd;
        private String freqType;
        private Integer freqN;
        private String reactionPlan;
    }

    List<PlanLineRow> planLines(@Param("planId") long planId);

    /** 검사계획 머리 키 (InspPlanLookup.keys) */
    @Getter
    @Setter
    class PlanKeyRow {
        private long inspPlanId;
        private String inspType;
        private Long itemId;
        private Long itemClassId;
        private Long procId;
        private int altNo;
        private String rev;
        private String status;
    }

    List<PlanKeyRow> planKeys(@Param("ids") List<Long> ids);

    /** 4M 변경 세부 한 줄 (docs/29_P3 20장) — 배열은 쉼표로 이은 글자(m4 = 저장 순서, id = 행 번호 순) */
    @Getter
    @Setter
    class ChangeRow {
        private long ecoId;
        private String ecoNo;
        private String m4Text;
        private String beforeDesc;
        private String afterDesc;
        private String reasonDesc;
        private String impactDesc;
        private String revertCond;
        private String charIdText;
        private String inspPlanIdText;
    }

    List<ChangeRow> changeDetails(@Param("ids") List<Long> ecoIds);
}
