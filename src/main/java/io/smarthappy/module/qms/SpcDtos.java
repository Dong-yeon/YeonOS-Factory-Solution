package io.smarthappy.module.qms;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

import lombok.Getter;
import lombok.Setter;

/** P3 QMS 2부 Q10 SPC DTO (docs/29_P3 12.6) — 관리도 단위 = 품목 × 특성 × 검사 유형 × 공정 */
public final class SpcDtos {

    private SpcDtos() {
    }

    /** 관리도 단위 한 줄(판정 확정 검사가 있는 계량형 특성) */
    @Getter
    @Setter
    public static class ChartUnit {
        private long itemId;
        private String itemCd;
        private String itemNm;
        private long charId;
        private String charNo;
        private String charNm;
        private String inspType;
        private Long procId;
        private String procCd;
        private String procNm;
        /** XBAR_R(계량형) · P(계수형) — docs/29_P3 28장 */
        private String chartType;
        private int subgroupCnt;
        private OffsetDateTime lastAt;
        private Long limitId;
        /** 고정 한계 있음(Y/N) — 그리드 YN 칸 */
        private String limitYn;
        /** 그리드 행 키 = 품목:특성:검사 유형:공정(없으면 0) */
        private String unitKey;
    }

    /** 단위 머리 — 품목·특성·공정 이름 */
    @Getter
    @Setter
    public static class UnitHead {
        private long itemId;
        private String itemCd;
        private String itemNm;
        private long charId;
        private String charNo;
        private String charNm;
        private String dataType;
        private String unitNm;
        private Integer scale;
        private Long procId;
        private String procCd;
        private String procNm;
    }

    /** 부분군 = 판정 확정 검사의 검사 행 하나 */
    @Getter
    @Setter
    public static class SubgroupHead {
        private long inspLineId;
        private long inspId;
        private String inspNo;
        private String lotNo;
        private BigDecimal lsl;
        private BigDecimal usl;
        private OffsetDateTime firstAt;
        /** p 관리도(계수형) 부분군만 — 살아 있는 OK/NG 샘플 수·NG 수(계량형은 null) */
        private Integer n;
        private Integer d;
    }

    /** 부분군의 살아 있는 측정값 하나 */
    @Getter
    @Setter
    public static class MeasVal {
        private long inspLineId;
        private BigDecimal value;
    }

    @Getter
    @Setter
    public static class LimitRow {
        private long limitId;
        private long itemId;
        private long charId;
        private String inspType;
        private Long procId;
        private BigDecimal center;
        private BigDecimal sigma;
        private int subgroupCnt;
        private OffsetDateTime fromAt;
        private OffsetDateTime toAt;
        private String rules;
        /** XBAR_R · P (docs/29_P3 28장) */
        private String chartType;
        private String status;
        private String remark;
        private String voidReason;
        private OffsetDateTime createdAt;
        private String createdBy;
        private int rowVer;
    }

    /** 관리도 조회 조건 — n = 최근 부분군 수, to = 끝 날짜(그날 끝까지), rules 없으면 고정 한계의 규칙 또는 기본 */
    public record ChartQuery(Long itemId, Long charId, String inspType, Long procId, Integer n, LocalDate to, List<String> rules) {
    }

    public record PointView(int index, long inspLineId, String inspNo, String lotNo, OffsetDateTime at, int n, double mean, double range,
            double xUcl, double xLcl, double rCl, double rUcl, double rLcl, Double z, List<String> rules) {
    }

    /**
     * 관리도 — source = FIXED(고정 한계로 그림) / CALC(보이는 부분군에서 계산). calc* = 보이는 부분군에서 계산한 값(고정 한계 정할 때 쓰임).
     * specMixed = 부분군 규격이 서로 달라 공정능력 없음
     */
    public record ChartView(UnitHead unit, String inspType, String source, LimitRow limit, Double center, Double sigma, Double calcCenter,
            Double calcSigma, List<String> rules, List<PointView> points, int excluded, SpcCalc.Capability capability, boolean specMixed,
            BigDecimal lsl, BigDecimal usl, int minSubgroups, String chartType) {
    }

    /** 고정 한계 정하기 — 조회 조건과 같은 구간에서 계산 */
    public record LimitForm(Long itemId, Long charId, String inspType, Long procId, Integer n, LocalDate to, List<String> rules,
            String remark) {
    }

    public record VoidForm(String reason, Integer rowVer) {
    }

    /** 그리드 정렬 허용 — field = 그리드 열, 이름 = 매퍼 choose 키 */
    public enum UnitSort implements io.smarthappy.platform.common.query.SortField {
        ITEM_CD("itemCd"), INSP_TYPE("inspType"), SUBGROUP_CNT("subgroupCnt"), LAST_AT("lastAt");

        private final String field;

        UnitSort(String field) {
            this.field = field;
        }

        @Override
        public String field() {
            return field;
        }
    }

    public record UnitPage(List<ChartUnit> list, Long total) {
    }
}
