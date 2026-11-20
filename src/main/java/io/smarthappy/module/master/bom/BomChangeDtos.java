package io.smarthappy.module.master.bom;

import java.math.BigDecimal;
import java.util.List;

import lombok.Getter;
import lombok.Setter;

/** BOM 변경 도구 DTO — 비교·복사·일괄 대체·저레벨 코드 (P1 3c, docs/18 5.5·5.6·C4·C5) */
public final class BomChangeDtos {

    private BomChangeDtos() {
    }

    /** 비교 한 줄: 키 = (자품목, 행 유형, 투입 공정). status = ADDED·REMOVED·CHANGED·SAME, changed = 바뀐 칸 이름 */
    public record CompareRow(String status, String itemCd, String itemNm, String lineType, Integer opSeq, Side left,
            Side right, List<String> changed) {
    }

    /** 한쪽 행의 비교 대상 값 (없으면 null) */
    public record Side(Integer lineNo, BigDecimal qty, String unitCd, BigDecimal scrapPct, String fixedYn,
            String issueMethod, String altGrp, Integer altPriority, BigDecimal altPct, String position) {
    }

    public record CompareHead(long bomId, String itemCd, String itemNm, String rev, String status, BigDecimal baseQty) {
    }

    public record CompareResult(CompareHead left, CompareHead right, int added, int removed, int changedCount, int same,
            List<CompareRow> rows) {
    }

    /** 다른 품목으로 복사: 대상 품목 + 시작일(비우면 오늘) */
    public record CopyRequest(String itemCd, String validFrom) {
    }

    /**
     * 일괄 대체 조건. 변경 번호·사유는 새로 만드는 리비전 머리에 넣는다(비우면 사유 = "일괄 대체: X → Y" — docs/60 R2).
     * 이미 있는 작성 중 BOM 에 반영할 때는 그 머리의 변경 번호·사유를 건드리지 않는다(남이 쓰던 작성 중일 수 있다)
     */
    public record ReplaceRequest(String fromItemCd, String toItemCd, String validFrom, List<Long> bomIds, String changeNo,
            String changeReason) {
        /** 예전 호출(변경 번호 없이) */
        public ReplaceRequest(String fromItemCd, String toItemCd, String validFrom, List<Long> bomIds) {
            this(fromItemCd, toItemCd, validFrom, bomIds, null, null);
        }
    }

    /**
     * 일괄 대체 미리보기 한 줄 = 모품목 하나.
     * action: DRAFT(작성 중 BOM 에 반영) · NEW_REV(확정본을 복사한 새 리비전) · SKIP(reason)
     */
    @Getter
    @Setter
    public static class ReplaceRow {
        private long parentId;
        private int altNo;
        private String parentCd;
        private String parentNm;
        private Long sourceBomId;
        private String sourceRev;
        private String sourceStatus;
        private String action;
        private String reason;
        private String newRev;
        private int lineCnt;
        /** 행 단위가 새 자품목 기본 단위도 아니고 환산도 없음 — 확정 전에 단위·소요량 확인 */
        private boolean unitWarn;
    }

    public record ReplacePreview(String fromItemCd, String fromItemNm, String toItemCd, String toItemNm, String validFrom,
            List<ReplaceRow> rows) {
    }

    /** 실행 결과: 모품목마다 만든·고친 작성 중 BOM. rowVer = 반영 뒤 머리 버전(결과 목록에서 바로 일괄 확정 — docs/60 R1) */
    public record ReplaceDone(String parentCd, String parentNm, long bomId, String rev, String action, int lines, int rowVer) {
    }

    /** 저레벨 코드 — 구조에서 가장 깊게 나오는 레벨(0 = 최상위). MRP 가 낮은 레벨부터 계산하는 순서 */
    @Getter
    @Setter
    public static class LlcRow {
        private long itemId;
        private String itemCd;
        private String itemNm;
        private String itemType;
        private int llc;
    }

    /** 이 자품목을 쓰는 BOM(미폐기) — 일괄 대체 미리보기 재료 */
    @Getter
    @Setter
    public static class UseRow {
        private long bomId;
        private int altNo;
        private long parentId;
        private String parentCd;
        private String parentNm;
        private String rev;
        private String status;
        private java.time.LocalDate validFrom;
        private java.time.LocalDate validTo;
        private int lineCnt;
        private boolean unitWarn;
    }

    /** 구조 간선 (모품목 → 자품목, 미폐기 리비전) */
    @Getter
    @Setter
    public static class Edge {
        private long parentId;
        private long childId;
    }
}
