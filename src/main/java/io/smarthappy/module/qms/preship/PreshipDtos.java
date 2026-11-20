package io.smarthappy.module.qms.preship;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

import io.smarthappy.platform.common.query.SortField;
import lombok.Getter;
import lombok.Setter;

/** 출하 전 검수 (docs/78) — 양식·실행·미결 */
public final class PreshipDtos {

    private PreshipDtos() {
    }

    // ------------------------------------------------------------------ 양식

    @Getter
    @Setter
    public static class TplRow {
        private long tplId;
        private String tplCd;
        private String tplNm;
        private Long itemId;
        private Long itemClassId;
        /** ITEM / CLASS */
        private String targetType;
        private String targetCd;
        private String targetNm;
        private int lineCnt;
        private String remark;
        private String useYn;
        private int rowVer;
        private OffsetDateTime updatedAt;
        private String updatedBy;
    }

    @Getter
    @Setter
    public static class TplLine {
        private Long tplLineId;
        private int seq;
        private String grpNm;
        private String checkText;
        private String naAllowedYn;
        private String photoReqYn;
        private String useYn;
        private int rowVer;
    }

    public record TplView(TplRow head, List<TplLine> lines) {
    }

    /** 항목 입력 — tplLineId 가 있으면 수정, 없으면 추가. 목록에 없는 기존 항목은 사용 중지 */
    public record TplLineForm(Long tplLineId, Integer seq, String grpNm, String checkText, String naAllowedYn, String photoReqYn, String useYn) {
    }

    /** 대상은 itemCd 또는 classCd 하나(코드로 받아 서버가 id 로) */
    public record TplForm(String tplCd, String tplNm, String itemCd, String classCd, String remark, String useYn, Integer rowVer,
            List<TplLineForm> lines) {
    }

    /** 검증을 마친 값 */
    record TplSave(String tplCd, String tplNm, Long itemId, Long itemClassId, String remark, String useYn, Integer rowVer) {
    }

    record LineSave(Long tplLineId, int seq, String grpNm, String checkText, String naAllowedYn, String photoReqYn, String useYn) {
    }

    public record TplPage(List<TplRow> list, Long total) {
    }

    public enum TplSort implements SortField {
        CD("tplCd"), NM("tplNm"), TARGET("targetCd"), UPDATED_AT("updatedAt");

        private final String field;

        TplSort(String field) {
            this.field = field;
        }

        @Override
        public String field() {
            return field;
        }
    }

    // ------------------------------------------------------------------ 출하 목록·검수

    @Getter
    @Setter
    public static class ShipRow {
        private long shipId;
        private String shipNo;
        private String plantCd;
        private LocalDate planDate;
        private String custNm;
        private String shipStatus;
        /** Y = 양식이 걸린 품목이 있다(검수 필요) */
        private String requiredYn;
        private Long inspId;
        private String inspNo;
        /** NONE = 검수 없음(또는 취소만) */
        private String inspStatus;
        private int resultCnt;
        private int judgedCnt;
        private int openPunchCnt;
    }

    public record ShipPage(List<ShipRow> list, Long total) {
    }

    public enum ShipSort implements SortField {
        NO("shipNo"), PLAN_DATE("planDate"), CUST("custNm"), INSP_STATUS("inspStatus");

        private final String field;

        ShipSort(String field) {
            this.field = field;
        }

        @Override
        public String field() {
            return field;
        }
    }

    @Getter
    @Setter
    public static class InspHead {
        private long inspId;
        private String inspNo;
        private long shipId;
        private String shipNo;
        private String shipStatus;
        private String status;
        private String inspectorNm;
        private String witnessNm;
        private String remark;
        private OffsetDateTime doneAt;
        private String doneBy;
        private String cancelReason;
        private int rowVer;
        private OffsetDateTime createdAt;
        private String createdBy;
    }

    @Getter
    @Setter
    public static class ResultRow {
        private long resultId;
        private long inspId;
        private long itemId;
        private String itemCd;
        private String itemNm;
        private int seq;
        private String grpNm;
        private String checkText;
        private String naAllowedYn;
        private String photoReqYn;
        private String judge;
        private String remark;
        private OffsetDateTime judgedAt;
        private String judgedBy;
        private int rowVer;
        private int fileCnt;
        private Long openPunchId;
        private String openPunchAllowYn;
    }

    /** 출하 상세 — 검수가 없으면 insp·results 비어 있음. requiredItems = 양식이 걸린 출하 품목 코드 */
    public record ShipDetail(ShipRow ship, List<String> requiredItems, InspHead insp, List<ResultRow> results) {
    }

    public record StartForm(Long shipId) {
    }

    public record JudgeForm(Long resultId, Integer rowVer, String judge, String remark) {
    }

    public record HeadForm(Integer rowVer, String inspectorNm, String witnessNm, String remark) {
    }

    public record CancelForm(Integer rowVer, String reason) {
    }

    /** 판정 결과 — 바뀐 줄 + 새로 생긴 미결 id(없으면 null) */
    public record JudgeResult(ResultRow result, Long punchId) {
    }

    // ------------------------------------------------------------------ 미결

    @Getter
    @Setter
    public static class PunchRow {
        private long punchId;
        private long inspId;
        private String inspNo;
        private String inspStatus;
        private long shipId;
        private String shipNo;
        private String shipStatus;
        private long resultId;
        private String itemCd;
        private String itemNm;
        private String grpNm;
        private String checkText;
        private String photoReqYn;
        private String status;
        private String issueText;
        private String actionText;
        private OffsetDateTime closedAt;
        private String closedBy;
        private String shipAllowYn;
        private String allowReason;
        private OffsetDateTime allowedAt;
        private String allowedBy;
        private Long ncrId;
        private String ncrNo;
        private int fileCnt;
        private int rowVer;
        private OffsetDateTime createdAt;
        private String createdBy;
        /** 같은 줄의 판정 rowVer(닫기 = 재확인 OK) */
        private int resultRowVer;
    }

    public record PunchPage(List<PunchRow> list, Long total) {
    }

    public enum PunchSort implements SortField {
        CREATED_AT("createdAt"), SHIP("shipNo"), ITEM("itemCd"), STATUS("status");

        private final String field;

        PunchSort(String field) {
            this.field = field;
        }

        @Override
        public String field() {
            return field;
        }
    }

    public record CloseForm(Integer rowVer, String actionText) {
    }

    public record AllowForm(Integer rowVer, String reason) {
    }

    /** ncrNo 비우면 연결 해제 */
    public record NcrForm(Integer rowVer, String ncrNo) {
    }

    /** 양식이 걸린 출하 품목과 그 양식(품목 양식 > 품목군 양식) */
    @Getter
    @Setter
    public static class RequiredItem {
        private long itemId;
        private String itemCd;
        private long tplId;
    }

    // ------------------------------------------------------------------ 출하 확정 확인

    @Getter
    @Setter
    public static class LiveInsp {
        private long inspId;
        private String inspNo;
        private String status;
        private int blockingPunchCnt;
    }
}
