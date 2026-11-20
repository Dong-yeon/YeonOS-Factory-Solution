package io.smarthappy.module.master.bom;

import java.math.BigDecimal;
import java.time.LocalDate;

import lombok.Getter;
import lombok.Setter;

/** BOM 전개·역전개 조회 DTO (P1 3b, docs/18 5.2·5.3) */
public final class BomTreeDtos {

    private BomTreeDtos() {
    }

    /** 보기: 단계별(1레벨) · 다단(들여쓰기) · 합산(말단 자재별 총 소요) */
    public enum View {
        SINGLE, MULTI, SUM
    }

    /**
     * 전개 한 행. reqQty = 모품목 생산 수량 기준 이 행의 필요량(자품목 기본 단위).
     * 계산 = (고정 수량이면 qty, 아니면 qty / 기준 수량 × 상위 필요량) × (1 + 손실률) × 단위 환산 × 대체 사용 비율
     */
    @Getter
    @Setter
    public static class ExplodeRow {
        private int lvl;
        /** 들여쓰기 표·엑셀용 레벨 표기 (예: ". . 2") */
        private String lvlMark;
        private String path;
        private Long bomId;
        private String bomRev;
        private Long lineId;
        private Integer lineNo;
        private String parentCd;
        private long itemId;
        private String itemCd;
        private String itemNm;
        private String spec;
        private String itemType;
        private String lineType;
        private BigDecimal qty;
        private String lineUnit;
        private BigDecimal baseQty;
        private BigDecimal scrapPct;
        private String fixedYn;
        private BigDecimal factor;
        private BigDecimal reqQty;
        private String itemUnit;
        private String issueMethod;
        private String altGrp;
        private Integer altPriority;
        private BigDecimal altPct;
        private Integer opSeq;
        private String position;
        /** 이 자품목이 기준일에 유효한 BOM 을 가지면 그 리비전 (말단이면 null) */
        private String childRev;
        /** 행 단위 → 자품목 기본 단위 환산이 없음 (환산 1 로 계산하고 표시) */
        private boolean noConv;
        /** 이미 위에 나온 품목 — 순환(저장 때 막으므로 정상이면 없음, 직접 SQL 등 비정상 데이터 표시용) */
        private boolean cycle;
    }

    /** 역전개 한 행 — 이 품목(또는 아래 품목)을 쓰는 상위 BOM */
    @Getter
    @Setter
    public static class WhereRow {
        private int lvl;
        private String lvlMark;
        private String path;
        private long bomId;
        private String rev;
        private String status;
        private LocalDate validFrom;
        private LocalDate validTo;
        private long parentId;
        private String parentCd;
        private String parentNm;
        private String parentType;
        private long childId;
        private String childCd;
        private Integer lineNo;
        private String lineType;
        private BigDecimal qty;
        private String lineUnit;
        private BigDecimal baseQty;
        /** 더 위에 쓰는 곳이 없는 모품목(완제품 쪽 끝) */
        private boolean top;
        private boolean cycle;
    }

    /** 전개 결과 + 머리 정보 */
    public record ExplodeResult(String itemCd, String itemNm, String itemUnit, String rev, LocalDate date, BigDecimal qty,
            View view, int maxLevel, java.util.List<ExplodeRow> rows) {
    }

    /** llc = 저레벨 코드(미폐기 구조에서 가장 깊게 나오는 레벨, 구조에 없으면 null) */
    public record WhereResult(String itemCd, String itemNm, LocalDate date, boolean allRevs, boolean topOnly, Integer llc,
            java.util.List<WhereRow> rows) {
    }

    public record ExplodeExport(String itemCd, Long bomId, String date, String qty, String view, java.util.List<String> columns) {
    }

    public record WhereExport(String itemCd, String date, Boolean allRevs, Boolean topOnly, java.util.List<String> columns) {
    }
}
