package io.smarthappy.module.qms;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

import io.smarthappy.module.qms.ChangeEffectCalc.AttrStats;
import io.smarthappy.module.qms.ChangeEffectCalc.VarStats;
import lombok.Getter;
import lombok.Setter;

/** QMS 4M 3차 — 임시 변경 복귀 확인 · 변경 효과 확인 DTO (docs/29_P3 51장) */
public final class ChangeRecordDtos {

    private ChangeRecordDtos() {
    }

    @Getter
    @Setter
    public static class RevertRow {
        private long revertId;
        private long ecoId;
        private String revertType;
        private LocalDate revertedOn;
        private String note;
        private String status;
        private String voidReason;
        private OffsetDateTime voidedAt;
        private String voidedBy;
        private int rowVer;
        private OffsetDateTime createdAt;
        private String createdBy;
    }

    @Getter
    @Setter
    public static class EffectRow {
        private long effectId;
        private long ecoId;
        private String result;
        private String note;
        private LocalDate baseDate;
        private int basisN;
        private int basisDays;
        private String status;
        private String voidReason;
        private OffsetDateTime voidedAt;
        private String voidedBy;
        private int rowVer;
        private OffsetDateTime createdAt;
        private String createdBy;
    }

    /**
     * 복귀 구획 — temp = 임시 변경 ECO, ready = 적용됨(기록 가능 조건), due = OVERDUE·DUE·DONE·null(임시 아님·적용 전),
     * markGap = 살아 있는 기록 복귀일과 지금 표시 끝의 차이 EARLY·LATE·null (51.2 RV-4 — 저장하지 않고 계산)
     */
    public record RevertView(boolean temp, boolean ready, String revertCond, LocalDate releasedOn, LocalDate markUntil, String due,
            String markGap, RevertRow live, List<RevertRow> history) {
    }

    /** 효과 구획 — ready = 적용됨 */
    public record EffectView(boolean ready, LocalDate releasedOn, EffectRow live, List<EffectRow> history) {
    }

    /** 상세 아래 기록 묶음 + 서명(정책·목록) */
    public record RecordsView(long ecoId, String ecoNo, boolean hasChange, RevertView revert, EffectView effect, boolean esignRequired,
            List<QmsESign.SignView> signatures) {
    }

    public record RevertForm(String revertType, String revertedOn, String note, String esignToken) {
    }

    public record EffectForm(String result, String note, Integer n, Integer days, String esignToken) {
    }

    public record VoidForm(Integer rowVer, String reason) {
    }

    // ------------------------------------------------------------------ 비교 표

    /** 비교 대상 특성(영향 행 중 특성) */
    @Getter
    @Setter
    public static class EffectChar {
        private int lineNo;
        private long charId;
        private String refLabel;
        private String charNm;
        private String dataType;
        private BigDecimal lsl;
        private BigDecimal usl;
        private Integer scale;
        private String unitNm;
        private Long itemId;
        private Long procId;
    }

    /** 측정 한 값 — 계량형은 value, 계수형은 okYn */
    @Getter
    @Setter
    public static class MeasPoint {
        private BigDecimal value;
        private String okYn;
    }

    /** 품목 하나의 한쪽 기간 집계 */
    @Getter
    @Setter
    public static class ItemCounts {
        private long itemId;
        private String itemCd;
        private String itemNm;
        private String unitCd;
        private long inspCnt;
        private long inspNg;
        private long ncrCnt;
        private BigDecimal ncrQty;
    }

    public record CharCmp(int lineNo, long charId, String refLabel, String charNm, String dataType, BigDecimal lsl, BigDecimal usl,
            Integer scale, String unitNm, VarStats beforeVar, VarStats afterVar, AttrStats beforeAttr, AttrStats afterAttr) {
    }

    public record ItemSide(long inspCnt, long inspNg, Double ngRate, long ncrCnt, String ncrQty) {
    }

    public record ItemCmp(long itemId, String itemCd, String itemNm, String unitCd, ItemSide before, ItemSide after) {
    }

    /**
     * 비교 표 — ready = 적용됨(아니면 표 없음). base = 적용일 0시(회사 시간대), 앞 기간 = [base − days, base), 뒤 = [base, min(base + days, 지금)).
     * afterPartial = 뒤 기간이 아직 다 차지 않음(afterDaysElapsed = 지난 날 수)
     */
    public record EffectCompare(boolean ready, LocalDate baseDate, int n, int days, boolean afterPartial, int afterDaysElapsed,
            List<CharCmp> chars, List<ItemCmp> items) {
    }
}
