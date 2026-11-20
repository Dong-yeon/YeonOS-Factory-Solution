package io.smarthappy.module.wms.inv;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import lombok.Getter;
import lombok.Setter;

/** 생산 입고 → 재고 원장 (docs/30 11장). MES 표 prd_receipt 는 읽기만 */
@Mapper
public interface ReceiptPostingMapper {

    @Getter
    @Setter
    class SrcReceipt {
        private long receiptId;
        private String plantCd;
        private long itemId;
        private String unitCd;
        private String itemUnitCd;
        private String lotCtrlYn;
        private long lotId;
        private long nodeId;
        private BigDecimal qty;
        private OffsetDateTime occurredAt;
        private String entryMethod;
        private String recordedBy;
        private Long performedBy;
        private Long reversalOfId;
        private String reason;
        private String defaultStatus;
        private String nodeUseYn;
        private String stockYn;
        private String locUsage;
    }

    SrcReceipt findReceipt(@Param("id") long id);

    /** 입고 가능 위치 후보(docs/44 S2) — 이 공장 WH·LOC 전부 + 사용·재고 둠·기본 상태(원장 트리거와 같은 칸) */
    List<RcvNode> receiptNodes(@Param("plantCd") String plantCd);

    /** 품목 LOT 관리 여부 — 없는 품목(다른 회사 포함)이면 null */
    String lotCtrlOf(@Param("itemId") long itemId);

    @Getter
    @Setter
    class RcvNode {
        private long nodeId;
        private String nodeCd;
        private String path;
        private String useYn;
        private String stockYn;
        private String locUsage;
        private String defaultStatus;
    }

    List<Long> allReceiptIds();

    /** 멱등 키 PRD_RECEIPT:{id} 로 이미 들어간 원장 id */
    Long trxOf(@Param("reqId") String reqId);

    int countReceipts();

    int countPosted();

    /** 원장에 없는 생산 입고 수 (LOT → LOT 변환은 prd_receipt 가 아니므로 애초에 대상 아님) */
    int countMissing();

    boolean backfillDone();

    long insertRun(@Param("total") int total, @Param("posted") int posted, @Param("existed") int existed, @Param("failed") int failed,
            @Param("detail") String detail, @Param("by") String by);

    BackfillRun lastRun();

    @Getter
    @Setter
    class BackfillRun {
        private long runId;
        private OffsetDateTime runAt;
        private String runBy;
        private int total;
        private int posted;
        private int existed;
        private int failed;
        private String detail;
    }
}
