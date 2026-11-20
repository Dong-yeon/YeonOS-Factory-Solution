package io.smarthappy.module.wms.inv;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

import lombok.Getter;
import lombok.Setter;

/** 고객 반품 입고 (docs/59) DTO */
public final class CustRtnDtos {

    private CustRtnDtos() {
    }

    /**
     * 반품 근거 한 줄 = 출하 행 × LOT(비관리 품목은 LOT 없음). shippedQty = 순 출하(SHIP 원장, 출하 취소분 빠짐),
     * returnedQty = 살아 있는(RECEIVED) 반품 합, openQty = 받을 수 있는 양(0 아래면 0)
     */
    @Getter
    @Setter
    public static class Source {
        private long shipId;
        private String shipNo;
        private OffsetDateTime shippedAt;
        private long shipLineId;
        private String plantCd;
        private long custId;
        private String custCd;
        private String custNm;
        private long itemId;
        private String itemCd;
        private String itemNm;
        private String unitCd;
        private String lotCtrlYn;
        private Long lotId;
        private String lotNo;
        private BigDecimal shippedQty;
        private BigDecimal returnedQty;
        private BigDecimal openQty;
    }

    /** 반품 입고 한 건(목록·상세) — ncrStatus 는 QMS 계약으로 채움(QMS 없으면 null) */
    @Getter
    @Setter
    public static class Rtn {
        private long rtnId;
        private String rtnNo;
        private String plantCd;
        private String status;
        private long shipId;
        private String shipNo;
        private long shipLineId;
        private String custCd;
        private String custNm;
        private long itemId;
        private String itemCd;
        private String itemNm;
        private String unitCd;
        private Long lotId;
        private String lotNo;
        private BigDecimal qty;
        private String reasonCd;
        private long nodeId;
        private String nodePath;
        private String remark;
        private String capaNo;
        private Long ncrId;
        private String ncrNo;
        private String ncrStatus;
        private String clientReqId;
        private OffsetDateTime receivedAt;
        private String receivedBy;
        private String cancelReason;
        private OffsetDateTime cancelledAt;
        private String cancelledBy;
        private int rowVer;
        /** 적치 검사 경고(W-12, 정책 WARN — 저장은 됨). 받기 응답에만, 목록·상세는 빈 목록 */
        private List<String> warnings = List.of();
    }

    /** 반품 입고 — 근거(출하 행·LOT), 수량(기본 단위), 사유, 받는 칸, 비고·고객 클레임 번호(선택), 요청 id(멱등) */
    public record CreateForm(String plantCd, Long shipLineId, Long lotId, BigDecimal qty, String reasonCd, Long nodeId, String remark,
            String capaNo, String entryMethod, String clientReqId) {
    }

    public record CancelForm(Integer rowVer, String reason) {
    }

    /** 받는 칸 후보(사무 입고와 같은 판정 — 출하 대기·폐기·재고 안 둠 제외). QC 용도 칸 먼저(R-5) */
    public record NodeOpt(long nodeId, String path, String locUsage) {
    }
}
