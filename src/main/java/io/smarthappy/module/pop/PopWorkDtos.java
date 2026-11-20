package io.smarthappy.module.pop;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

import io.smarthappy.module.master.workforce.WorkforceDtos.GateResult;
import io.smarthappy.platform.code.CodeService.CodeItem;
import lombok.Getter;
import lombok.Setter;

/** POP 착수·실적·투입 DTO (P2 5b, docs/25 4.7). 수량은 글자로 주고받는다(Double 금지) */
public final class PopWorkDtos {

    private PopWorkDtos() {
    }

    // ---------- 공정(단말 작업장 안) ----------

    @Getter
    @Setter
    public static class ProcRow {
        private long woProcId;
        private long woId;
        private String woNo;
        private String woStatus;
        private String plantCd;
        private long itemId;
        private String itemCd;
        private String itemNm;
        private String planQty;
        private String unitCd;
        private OffsetDateTime releasedAt;
        private int opSeq;
        private long procId;
        private String procCd;
        private String procNm;
        private String status;
        private Long equipId;
        private String equipCd;
        private String equipNm;
        private String nodeCd;
    }

    @Getter
    @Setter
    public static class ParamRow {
        private long woParamId;
        private String paramCd;
        private String paramNm;
        private String unitNm;
        private String targetVal;
        private String lsl;
        private String usl;
        /** 마지막 유효 실측(정정된 것 제외) */
        private Long lastActualId;
        private String lastValue;
        private String lastJudge;
    }

    @Getter
    @Setter
    public static class BomRow {
        private String itemCd;
        private String itemNm;
        private String reqQty;
        private String unitCd;
    }

    @Getter
    @Setter
    public static class DefectOption {
        private long defectId;
        private String defectCd;
        private String defectNm;
        private String catCd;
    }

    @Getter
    @Setter
    public static class ResultRow {
        private long resultId;
        private long woId;
        private long woProcId;
        private Long lotId;
        /** 실적 설비 — 정정(역분개) 행도 같은 값(docs/25 4.5) */
        private Long equipId;
        private String lotNo;
        private String goodQty;
        private String scrapQty;
        private String reworkQty;
        private String repairQty;
        private String unitCd;
        private LocalDate workDay;
        private String shiftCd;
        private OffsetDateTime occurredAt;
        private String recordedBy;
        private Long performedBy;
        private String workerNm;
        private Long reversalOfId;
        private boolean reversed;
        private String clientReqId;
    }

    @Getter
    @Setter
    public static class DefectLineRow {
        private String kind;
        private long defectId;
        private String qty;
    }

    @Getter
    @Setter
    public static class EventRow {
        private long eventId;
        private long woProcId;
        private String eventCd;
        private String gateMode;
    }

    @Getter
    @Setter
    public static class ParamActualRow {
        private long actualId;
        private long woProcId;
        private long woParamId;
        private String paramCd;
        private String value;
        private String judge;
        private Long reversalOfId;
        private boolean reversed;
    }

    /** 공정 화면: 공정·파라미터·이 공정 BOM·오늘 실적·선택지 */
    /** startGate = 착수 게이트 빈들(QMS 등)의 미리보기 — 빈이 없으면 (OK, 빈 목록) (docs/25 4.21) */
    /** plan = 일일 배정 자리(4.56), insp = 검사 입력을 쓸 수 있음(QMS 계약 구현 있음 — 4.57. 열 수 있는 유형은 화면이 따로 묻는다) */
    public record ProcView(ProcRow proc, GateResult gate, List<ParamRow> params, List<BomRow> bom, List<ResultRow> today,
            String todayGood, List<DefectOption> defects, List<CodeItem> reasons, StartGateView startGate, CountSuggest countSuggest,
            PopDtos.PlanSlot plan, boolean insp) {
    }

    /**
     * 수집 COUNT 실적 제안(docs/25 4.27) — 그 설비의 since(마지막 실적, 없으면 착수) 뒤 증가분 합. 확정은 사람.
     * 설비 없음·진행 중 아님·같은 설비에 진행 중 공정이 둘 이상이면 null(어느 공정 몫인지 모른다)
     */
    @Getter
    @Setter
    public static class CountSuggest {
        private java.time.OffsetDateTime since;
        private String goodQty;
        private String scrapQty;
    }

    /** 착수 게이트 이유 한 줄 — source = 빈 key(예 QMS), message = 요청 로캘 문구 */
    public record GateLine(String source, String code, String message) {
    }

    public record StartGateView(String mode, List<GateLine> reasons) {
    }

    // ---------- 입력 ----------

    /** occurredAt: ISO-8601(오프셋 포함) — 없으면 서버 지금. ack: 자격 WARN 을 확인하고 착수 */
    public record EventForm(String clientReqId, String reasonCd, Boolean ack) {
    }

    public record DefectLine(String kind, String defectCd, String qty) {
    }

    public record InputLine(String lotNo, String qty) {
    }

    /** fromCount = 수집 COUNT 제안을 확인해 그대로 보냄 → 실적 entry_method AUTO(값은 사람이 확정한 그대로 — 서버가 바꾸지 않는다) */
    public record ResultForm(String clientReqId, String occurredAt, String goodQty, String scrapQty, String reworkQty,
            String repairQty, List<DefectLine> defects, List<InputLine> inputs, Boolean fromCount) {
    }

    /** 정정 — approvalToken = 감독자 승인 토큰(정책이 켜진 공장만, docs/25 4.37) */
    public record CorrectForm(String reason, String approvalToken) {

        public CorrectForm(String reason) {
            this(reason, null);
        }
    }

    public record ParamForm(String clientReqId, String occurredAt, String paramCd, String value) {
    }

    // ---------- 응답 ----------

    public record EventReply(long eventId, String eventCd, String procStatus, String gateMode, boolean replayed) {
    }

    /** 입력하면 돌려준다: 오늘 양품 합 / 계획, 산출 LOT, 직전 불량 */
    public record ResultReply(long resultId, String lotNo, String todayGood, String planQty, String unitCd, String lastDefect,
            boolean replayed) {
    }

    public record ParamReply(long actualId, String judge, boolean replayed) {
    }

    public record LotScan(String lotNo, String itemCd, String itemNm, String balance, String unitCd, String holdYn) {
    }

    // ---------- 저장용 ----------

    /** gateDetail = 착수 경고 이유 요약(출처:코드(참조), 500자) — WARN 착수만, 그 밖 null (docs/25 4.21) */
    public record NewEvent(long woId, long woProcId, String eventCd, String reasonCd, String gateMode, Long equipId,
            long terminalId, LocalDate workDay, String shiftCd, String clientReqId, java.time.Instant occurredAt,
            String entryMethod, long performedBy, String gateDetail) {
    }

    public record NewResult(long woId, long woProcId, Long lotId, Long equipId, Long terminalId, java.math.BigDecimal goodQty,
            java.math.BigDecimal scrapQty, java.math.BigDecimal reworkQty, java.math.BigDecimal repairQty, String unitCd,
            LocalDate workDay, String shiftCd, String clientReqId, String reason, java.time.Instant occurredAt,
            String entryMethod, Long performedBy, Long reversalOfId) {
    }

    public record NewParam(long woProcId, long woParamId, String paramCd, java.math.BigDecimal value, String judge, Long equipId,
            Long terminalId, LocalDate workDay, String shiftCd, String clientReqId, String reason, java.time.Instant occurredAt,
            String entryMethod, Long performedBy, Long reversalOfId) {
    }

    /** 고장 신고 설비 행(docs/25 4.35) — 단말 공장·작업장(라인이면 아래 셀 포함) 범위 확인용 칸 포함, 화면에는 EquipOption 으로 */
    @Getter
    @Setter
    public static class BreakdownEquip {
        private Long equipId;
        private String equipCd;
        private String equipNm;
        private String nodeCd;
        private String plantCd;
        private Long nodeId;
        private Long parentNodeId;
        private String useYn;
    }

    /** 고장 신고 화면의 설비 선택지 */
    public record EquipOption(long equipId, String equipCd, String equipNm, String nodeCd) {
    }

    /** 고장 신고 — 현상 코드 또는 글 하나 이상(CMS 검사), clientReqId = 단말이 만든 요청 id(같은 id 재전송 = 처음 번호) */
    public record BreakdownForm(Long equipId, String symptomCd, String symptomText, String clientReqId) {
    }

    /**
     * 신고 결과 + 사진 한도(docs/25 4.44, CMS 계약 photoLimits — 판정과 같은 계산). photoMax 0 = 사진 단계 없음(NONE·회사가 막음).
     * 단말이 미리 줄이고 미리 막는 데만 쓰고, 최종 판정은 사진 올리기(CMS)
     */
    public record BreakdownReply(String reqNo, boolean duplicate, int photoMax, long photoBytes, java.util.List<String> photoExts) {
    }
}
