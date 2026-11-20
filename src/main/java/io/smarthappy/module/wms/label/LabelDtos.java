package io.smarthappy.module.wms.label;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import io.smarthappy.platform.common.query.PageQuery;
import io.smarthappy.platform.common.query.SortField;
import lombok.Getter;
import lombok.Setter;

/** 라벨 템플릿·프린터·인쇄 작업 DTO (P4 W2, docs/30) */
public final class LabelDtos {

    private LabelDtos() {
    }

    @Getter
    @Setter
    public static class PrinterRow {
        private long printerId;
        private String plantCd;
        private String printerCd;
        private String printerNm;
        private String lang;
        private String host;
        private int port;
        private int dpi;
        private String usage;
        private String useYn;
        private String remark;
        private int rowVer;
        private OffsetDateTime updatedAt;
        private String updatedBy;
        /** E1(docs/65 18.2·18.4) */
        private String cjkFontYn;
        private String cjkFontFile;
        private BigDecimal mediaWidthMm;
        private String statusCheckYn;
        /** DIRECT(서버가 TCP) / AGENT(에이전트가 당겨 감, E2) */
        private String connMode;
        private Long agentTerminalId;
        private String agentTerminalCd;
        /** 프린터 마지막 상태(wms_printer_status) — 목록·상세 */
        private String printerState;
        private String statusErrors;
        private String statusWarnings;
        private Long odometer;
        private OffsetDateTime checkedAt;
        /** 에이전트 마지막 접속(sys_terminal.last_seen_at, AGENT 만) */
        private OffsetDateTime agentSeenAt;
    }

    public record PrinterForm(String plantCd, String printerCd, String printerNm, String host, Integer port, Integer dpi,
            String usage, String useYn, String remark, Integer rowVer, String cjkFontYn, String cjkFontFile, BigDecimal mediaWidthMm,
            String statusCheckYn, String connMode, Long agentTerminalId) {
    }

    @Getter
    @Setter
    public static class TplRow {
        private long tplId;
        private String labelType;
        private String tplCd;
        private String tplNm;
        private BigDecimal widthMm;
        private BigDecimal heightMm;
        /** 크기 ID(폭x높이 mm) */
        private String sizeCd;
        private int dpi;
        private String body;
        private String defaultYn;
        private String useYn;
        private String remark;
        private int rowVer;
        private OffsetDateTime updatedAt;
        private String updatedBy;
    }

    public record TplForm(String labelType, String tplCd, String tplNm, BigDecimal widthMm, BigDecimal heightMm, Integer dpi,
            String body, String defaultYn, String useYn, String remark, Integer rowVer) {
    }

    /** 미리보기: 저장 전 본문으로 렌더(인쇄 안 함). values 가 없으면 견본 값 */
    public record PreviewRequest(String labelType, String body, Map<String, String> values, BigDecimal widthMm, BigDecimal heightMm,
            Integer dpi) {
    }

    /**
     * 미리보기 결과 — ZPL 글자 + 배치(SVG 로 그릴 요소, 근사) + 경고(템플릿 경고 · 견본 값 넘침 · 긴 값 넘침, docs/65 18.2·18.3)
     */
    public record PreviewResult(String zpl, Map<String, String> values, ZplLayout.Layout layout, List<String> warnings,
            List<String> longWarnings, List<String> warningTexts, List<String> longWarningTexts) {

        public PreviewResult(String zpl, Map<String, String> values, ZplLayout.Layout layout, List<String> warnings,
                List<String> longWarnings) {
            this(zpl, values, layout, warnings, longWarnings, null, null);
        }

        /** 경고 코드(CODE:인자) → 문구(wmsLabel.warn.CODE) — 컨트롤러가 요청 언어로 */
        public PreviewResult withTexts(java.util.function.Function<String, String> tr) {
            return new PreviewResult(zpl, values, layout, warnings, longWarnings, warnings.stream().map(tr).toList(),
                    longWarnings.stream().map(tr).toList());
        }
    }

    public record SampleView(String labelType, String body, Map<String, String> values, List<String> fields) {
    }

    public record TestPrintRequest(Long printerId, Map<String, String> values) {
    }

    /** 다른 모듈·화면이 부르는 인쇄 한 장 — bizKey 는 라벨이 가리키는 대상(LOC:{nodeId}, LOT:{lotNo} …) */
    public record PrintItem(String bizKey, Map<String, String> values, Long custId, Long itemId) {

        /** 문맥 없음 = 템플릿은 유형 기본(규칙 없이) — 기존 호출부 그대로 */
        public PrintItem(String bizKey, Map<String, String> values) {
            this(bizKey, values, null, null);
        }
    }

    /** 템플릿 선택 규칙(docs/65 18.4) */
    @Getter
    @Setter
    public static class RuleRow {
        private long ruleId;
        private String labelType;
        private String scopeCd;
        private Long custId;
        private String custCd;
        private String custNm;
        private Long itemId;
        private String itemCd;
        private String itemNm;
        private long tplId;
        private String tplCd;
        private String tplNm;
        private String useYn;
        private String remark;
        private int rowVer;
        private OffsetDateTime updatedAt;
    }

    public record RuleForm(String labelType, String scopeCd, Long custId, Long itemId, Long tplId, String useYn, String remark,
            Integer rowVer) {
    }

    @Getter
    @Setter
    public static class JobRow {
        private long jobId;
        private String plantCd;
        private String labelType;
        private String bizKey;
        private int copies;
        private String testYn;
        private Long reprintOfId;
        private String reprintReason;
        private String requestedBy;
        private OffsetDateTime requestedAt;
        private long printerId;
        private String printerCd;
        private String printerNm;
        private long tplId;
        private String tplCd;
        /** sys_outbox.status — NEW/SENDING/SENT/FAIL */
        private String status;
        private Integer attempts;
        private String lastError;
        private OffsetDateTime sentAt;
        private String zplSha256;
        /** 인쇄 상태 3단(docs/65 18.1) — 사건 원장 마지막 상태, 없으면 아웃박스에서 */
        private String printState;
        private String connMode;
        /** 경고 사건 수(WARN·VERIFY_NG) */
        private Integer warnCount;
        /** 상세에서만 */
        private String labelValues;
        private String zpl;
    }

    public record ReprintForm(String reason, Long printerId) {
    }

    @Getter
    public static class JobSearch {
        private final OffsetDateTime fromAt;
        private final OffsetDateTime toAt;
        private final String labelType;
        private final String status;
        private final String bizKeyLike;
        private final String plantCd;
        private final PageQuery page;
        /** 인쇄 상태 3단 필터(docs/65 18.1) — 선택 */
        @Setter
        private String printState;

        public JobSearch(OffsetDateTime fromAt, OffsetDateTime toAt, String labelType, String status, String bizKeyLike,
                String plantCd, PageQuery page) {
            this.fromAt = fromAt;
            this.toAt = toAt;
            this.labelType = labelType;
            this.status = status;
            this.bizKeyLike = bizKeyLike;
            this.plantCd = plantCd;
            this.page = page;
        }
    }

    public record JobQuery(LocalDate from, LocalDate to, String labelType, String status, String bizKey, String plantCd,
            String printState) {

        public JobQuery(LocalDate from, LocalDate to, String labelType, String status, String bizKey, String plantCd) {
            this(from, to, labelType, status, bizKey, plantCd, null);
        }
    }

    public enum JobSort implements SortField {
        REQUESTED_AT("requestedAt"), JOB_ID("jobId");

        private final String field;

        JobSort(String field) {
            this.field = field;
        }

        @Override
        public String field() {
            return field;
        }
    }

    /** 템플릿 목록 그리드 정렬 허용 열(UI 단계 3 ①, 결정 docs/29 #45) — 끝에 tpl_id */
    public enum TplSort implements SortField {
        LABEL_TYPE("labelType"), TPL_CD("tplCd"), TPL_NM("tplNm"), UPDATED_AT("updatedAt");

        private final String field;

        TplSort(String field) {
            this.field = field;
        }

        @Override
        public String field() {
            return field;
        }
    }

    /** 프린터 목록 그리드 정렬 허용 열 — 끝에 printer_id */
    public enum PrinterSort implements SortField {
        PLANT_CD("plantCd"), PRINTER_CD("printerCd"), PRINTER_NM("printerNm"), USAGE("usage"), UPDATED_AT("updatedAt");

        private final String field;

        PrinterSort(String field) {
            this.field = field;
        }

        @Override
        public String field() {
            return field;
        }
    }
}
