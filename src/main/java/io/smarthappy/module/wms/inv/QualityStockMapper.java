package io.smarthappy.module.wms.inv;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import io.smarthappy.module.wms.inv.LotStockMapper.Cell;
import lombok.Getter;
import lombok.Setter;

/** QMS 판정·NCR → 재고 상태(docs/30 12.15) — QMS 표는 읽기만, 칸 잠금은 칸 순서 */
@Mapper
public interface QualityStockMapper {

    /** 검사 한 건(+ 품목 LOT 관리 여부) — 다른 회사·없는 id 면 null(RLS) */
    InspRow findInsp(@Param("inspId") long inspId);

    /** NCR 한 건(+ 품목 기본 단위·LOT 관리 여부) */
    NcrRow findNcr(@Param("ncrId") long ncrId);

    /** NCR 처분 행 — 행 번호 순 */
    List<DispRow> findDisps(@Param("ncrId") long ncrId);

    /** LOT 의 한 상태 칸(수량 > 0) — 위치 순 */
    List<Cell> cellsOf(@Param("lotId") long lotId, @Param("status") String status);

    /** 칸 잠금 — 칸 순서(품목·위치·LOT·상태, docs/30 11.6) */
    List<Cell> lockCells(@Param("stockIds") List<Long> stockIds);

    /** 출처 행 중 되돌리지 않은 원장 id */
    List<Long> liveTrxOf(@Param("srcType") String srcType, @Param("srcId") long srcId);

    /** LOT 재고가 있는 공장(수량 ≠ 0) */
    List<String> plantsOf(@Param("lotId") long lotId);

    /** 대사: QC 재고(LOT, 수량 &gt; 0)가 있는 공장 */
    List<String> qcPlants();

    /** 대사: 이 공장들의 QC 재고 LOT 중 수입·출하검사가 판정됐고 열린 검사가 없는데 판정 뒤 들어온 몫보다 QC 가 많은 LOT 수 */
    int countQcJudged(@Param("plants") List<String> plants);

    /** 대사: 무효 검사인데 되돌리지 않은 판정 원장이 남은 검사 수 */
    int countVoidLive();

    /** 이 LOT 의 살아 있는(JUDGED) 최신 수입·출하 판정 결과 — 없으면 null */
    String latestResult(@Param("lotId") long lotId);

    /** 판정(judgedAt) 뒤 BLOCKED 로 들어온 입고 몫(역분개 포함 순합) — 위치별, 양수만 */
    List<NodeQty> blockedReceiptsSince(@Param("lotId") long lotId, @Param("since") OffsetDateTime since);

    /** 이 LOT 의 판정 원장(QMS_INSP, 되돌리지 않은 것)이 있는 공장 — 스위치를 꺼도 NCR 종결을 반영할 곳 */
    List<String> reflectedPlants(@Param("lotId") long lotId);

    /** 이 LOT 에 반영된(되돌리지 않은) NCR 처분이 있으면 그 NCR 번호 하나 */
    String disposedNcrNo(@Param("lotId") long lotId);

    @Getter
    @Setter
    class NodeQty {
        private String plantCd;
        private long nodeId;
        private BigDecimal qty;
    }

    @Getter
    @Setter
    class InspRow {
        private long inspId;
        private String inspNo;
        private String inspType;
        private long lotId;
        private long itemId;
        private String status;
        private String result;
        private OffsetDateTime judgedAt;
        private String judgedBy;
        private OffsetDateTime voidedAt;
        private String voidedBy;
        private String lotCtrlYn;
    }

    @Getter
    @Setter
    class NcrRow {
        private long ncrId;
        private String ncrNo;
        private long lotId;
        private long itemId;
        private String unitCd;
        private String itemUnitCd;
        private String status;
        private OffsetDateTime closedAt;
        private String closedBy;
        private String lotCtrlYn;
        /** 클레임 NCR(출처 WMS_CUST_RTN)이면 그 반품이 받은 칸 — 처분은 이 칸의 BLOCKED 부터(QA CR-c, 2026-11-02 사용자 결정) */
        private Long rtnNodeId;
    }

    @Getter
    @Setter
    class DispRow {
        private long dispId;
        private int lineNo;
        private String dispType;
        private BigDecimal qty;
    }
}
