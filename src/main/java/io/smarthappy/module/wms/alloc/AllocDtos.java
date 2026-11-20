package io.smarthappy.module.wms.alloc;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

import lombok.Getter;
import lombok.Setter;

/** 출고 후보 미리보기 (P4 W4, docs/30 4.4) */
public final class AllocDtos {

    private AllocDtos() {
    }

    /**
     * @param nodeId        범위(창고·로케이션, 하위 포함) — 비우면 공장 전체
     * @param strategy      비우면 품목 속성 → 공장 정책 순서
     * @param availableOnly 비우면 true(합격분만)
     * @param excludeExpired 비우면 true(오늘 기준 유효기한 지난 것 제외)
     */
    public record PreviewForm(String plantCd, Long itemId, String itemCd, String qty, Long nodeId, String strategy, Boolean availableOnly,
            Boolean excludeExpired) {
    }

    /** 후보 원천 행(현재고 + 선입 기준 시각 + 피킹 순서) */
    @Getter
    @Setter
    public static class CandRow {
        private long nodeId;
        private String nodeCd;
        private String path;
        private int pickSeq;
        private Long lotId;
        private String lotNo;
        private String stockStatus;
        private BigDecimal qty;
        /** 지금 남은 재고가 들어오기 시작한 시각(그 키의 잔량이 마지막으로 0 이던 뒤 첫 입고) */
        private OffsetDateTime fifoAt;
        /** 꺼낼 수 있는 칸인가(가용 정의 AvailSql.availLoc — 출하 대기·검사 용도·폐기 칸이면 false) */
        private boolean availLoc;
        /** 보류 아닌 LOT 인가(가용 정의 AvailSql.lotFree — 보류 LOT 이면 false, A-1) */
        private boolean lotFree;
    }

    @Getter
    @Setter
    public static class ItemRow {
        private long itemId;
        private String itemCd;
        private String itemNm;
        private String unitCd;
        private String useYn;
        /** 클래스 속성 ALLOC_STRATEGY(글자 그대로) */
        private String allocAttr;
        /** 클래스 속성 SHELF_LIFE_DAYS(글자 그대로) */
        private String shelfLifeAttr;
    }

    public record PickView(long nodeId, String nodeCd, String path, String stockStatus, Long lotId, LocalDate fifoDate, LocalDate expiryDate,
            String availQty, String takeQty, String lotNo) {
    }

    public record SkipView(long nodeId, String path, String stockStatus, String qty, String reason, String lotNo) {
    }

    /**
     * @param strategySource REQUEST(화면에서 고름) · ITEM(품목 클래스 속성) · POLICY(공장·회사 정책)
     */
    public record Preview(String itemCd, String itemNm, String unitCd, String strategy, String strategySource, boolean availableOnly,
            LocalDate expiredBefore, Integer shelfLifeDays, String qty, String allocated, String shortage, List<PickView> picks,
            List<SkipView> skipped) {
    }
}
