package io.smarthappy.module.wms.loccheck;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonRawValue;

import lombok.Getter;
import lombok.Setter;

/** 정위치 점검 (docs/55) — 화면·PDA·서비스가 같이 쓰는 모양 */
public final class LocCheckDtos {

    private LocCheckDtos() {
    }

    /** 점검 머리 — 칸 × 공장 날짜 하나. openCnt = 처리 안 된 불일치 줄 */
    @Getter
    @Setter
    public static class Head {
        private long checkId;
        private String plantCd;
        private long nodeId;
        private String nodeCd;
        private String path;
        private LocalDate checkDate;
        private String status;
        private Long workerId;
        private Integer okCnt;
        private Integer misplacedCnt;
        private Integer missingCnt;
        private Integer unknownCnt;
        private String accurateYn;
        private OffsetDateTime doneAt;
        private String doneBy;
        private OffsetDateTime createdAt;
        private String createdBy;
        private int lineCnt;
        private int openCnt;
    }

    /** 스캔·판정 줄 — book = 판정 당시 장부 [{nodeId, status, qty}](JSON 그대로) */
    @Getter
    @Setter
    public static class Line {
        private long lineId;
        private long checkId;
        private String scanCode;
        private String scanType;
        private Long itemId;
        private String itemCd;
        private String itemNm;
        private String unitCd;
        private Long lotId;
        private String lotNo;
        private String result;
        private String reasonCd;
        @JsonRawValue
        private String bookJson;
        private OffsetDateTime scannedAt;
        private Long workerId;
        @JsonIgnore
        private String clientReqId;
        private OffsetDateTime recordedAt;
        private String recordedBy;
        private String reviewAction;
        private String reviewReason;
        private OffsetDateTime reviewedAt;
        private String reviewedBy;
        private UUID moveGrpId;
    }

    /** 장부 한 칸(판정 때 읽음) */
    @Getter
    @Setter
    public static class Book {
        private long nodeId;
        private String path;
        private String status;
        private BigDecimal qty;
    }

    /** 완료 때 없음 한 건 — 그 칸 스냅숏 */
    @Getter
    @Setter
    public static class Missing {
        private long itemId;
        private Long lotId;
        private String bookJson;
    }

    /** 줄 INSERT */
    @Getter
    @Setter
    public static class NewLine {
        private Long lineId;
        private long checkId;
        private String scanCode;
        private String scanType;
        private Long itemId;
        private Long lotId;
        private String result;
        private String reasonCd;
        private String bookJson;
        private OffsetDateTime scannedAt;
        private Long workerId;
        private String clientReqId;
        private String by;
    }

    public record View(Head head, List<Line> lines) {
    }

    /** 스캔 결과 — duplicate = 같은 점검에서 이미 스캔한 LOT·품목(앞 줄을 돌려줌) */
    public record ScanResult(Head head, Line line, boolean duplicate) {
    }

    /** 물건 스캔 — scannedAt = 단말 시각(참고), clientReqId = 응답이 끊겨 다시 보낼 때 같은 줄 */
    public record LineForm(String text, String clientReqId, String scannedAt) {
    }

    /** 보정 이동 — 출발 칸·상태는 판정 당시 장부 중 하나, 수량은 지금 그 칸 수량 이하 */
    public record CorrectForm(Long fromNodeId, String fromStatus, BigDecimal qty, String remark) {
    }

    public record ConfirmForm(String reason) {
    }

    /** 목록 정렬 허용 열 */
    public enum LocCheckSort implements io.smarthappy.platform.common.query.SortField {
        CHECK_DATE("checkDate"), PATH("path"), STATUS("status"), OPEN_CNT("openCnt");

        private final String field;

        LocCheckSort(String field) {
            this.field = field;
        }

        @Override
        public String field() {
            return field;
        }
    }

    /** 홈 카드 집계 — 정확도 = accurateCnt ÷ doneCnt(기간 안 DONE 만, 완료 안 된 머리는 분모에서 빠짐) */
    public record CardSummary(long doneCnt, long accurateCnt, long openCnt) {
    }

    public record CardRow(long checkId, String plantCd, LocalDate checkDate, String path, String result, String itemCd, String lotNo) {
    }

    /** 그리드 한 쪽 */
    public record Page<T>(List<T> list, Long total) {
    }
}
