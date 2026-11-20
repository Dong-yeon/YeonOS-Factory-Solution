package io.smarthappy.module.aps;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import io.smarthappy.platform.common.query.SortField;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;

/** APS 계획 DTO (docs/38). 응답 수량은 글자(trim_scale) — 브라우저 Double 손실을 피한다 */
public final class ApsDtos {

    private ApsDtos() {
    }

    // ---------- 요청 ----------

    /** 새 계획 계산 — horizonStart 는 yyyy-MM-dd, 일수 기본 7, 외주 리드타임 기본 24시간 */
    /** matMode = WARN(기본)·DELAY, matPo = 입고 예정 포함(기본 true, A30 재판정 2026-10-22) — 5단계 A27·A30 */
    public record PlanForm(String plantCd, String horizonStart, Integer horizonDays, Integer outsrcLeadHr, String planNm, String matMode,
            Boolean matPo) {
    }

    public record DiscardForm(Integer rowVer, String reason) {
    }

    /** 새 고정(3단계) — 계획 공정에서 시작, resKey = EQUIP:{id}·NODE:{id}·OUTSRC, startAt = ISO 순간 */
    public record PinForm(Long planOpId, String resKey, String startAt, String remark) {
    }

    /** 고정 옮기기·해제 */
    public record PinMoveForm(String resKey, String startAt, Integer rowVer) {
    }

    public record PinSaved(long pinId, int rowVer) {
    }

    /** 고정 대상 공정: 계획 공정 + 지금 지시·공정 상태·배정 설비 */
    @Getter
    @Setter
    public static class PinOp {
        private long planOpId;
        private long planId;
        private String plantCd;
        private long woId;
        private int opSeq;
        private Long nodeId;
        private String outsrcYn;
        private String woStatus;
        private String procStatus;
        private Long curEquipId;
    }

    /** 사용 중 고정 목록 행 */
    @Getter
    @Setter
    public static class PinRow {
        private long pinId;
        private String plantCd;
        private long woId;
        private String woNo;
        private String woStatus;
        private int opSeq;
        private String procCd;
        private String procNm;
        private String resKind;
        private Long equipId;
        private Long nodeId;
        private String resCd;
        private OffsetDateTime startAt;
        private long srcPlanId;
        private String srcPlanNo;
        private String updatedBy;
        private OffsetDateTime updatedAt;
        private int rowVer;
    }

    /** 반영 — 계획 rowVer (2단계, docs/38 11장) */
    public record ApplyForm(Integer rowVer) {
    }

    // ---------- 응답 ----------

    @Getter
    @Setter
    public static class PlanRow {
        private long planId;
        private String planNo;
        private String plantCd;
        private String plantNm;
        private String planNm;
        private String ruleCd;
        private LocalDate horizonStart;
        private int horizonDays;
        private int outsrcLeadHr;
        private String timeZone;
        private OffsetDateTime schedFrom;
        private OffsetDateTime schedTo;
        private String status;
        private OffsetDateTime calculatedAt;
        private String calculatedBy;
        private int woCnt;
        private int opCnt;
        private int lateWoCnt;
        private int unschedOpCnt;
        private OffsetDateTime makespanEnd;
        private int calcMs;
        private String discardReason;
        private String remark;
        private String matMode;
        private String matPo;
        private int matShortWoCnt;
        /** 계산 때 계획 기간 안 쉬는 날 수(4단계) */
        private int offDayCnt;
        /** 결과 행 보존 정리 시각(5단계 A35) — 있으면 결과·간트 없음 */
        private OffsetDateTime purgedAt;
        private OffsetDateTime appliedAt;
        /** 공장의 현행 계획(마지막 반영) — Y/N (docs/38 18.1) */
        private String currentYn;
        private String appliedBy;
        private Integer appliedWoCnt;
        private Integer appliedOpCnt;
        private int rowVer;
    }

    @Getter
    @Setter
    public static class PlanWoRow {
        private long planWoId;
        private int seq;
        private long woId;
        private String woNo;
        private String woStatus;
        private String itemCd;
        private String itemNm;
        private String planQty;
        private String unitCd;
        private int priority;
        private LocalDate planStart;
        private LocalDate planEnd;
        private LocalDate dueDate;
        private String dueSrc;
        private OffsetDateTime startAt;
        private OffsetDateTime endAt;
        private String lateYn;
        private String excCd;
        private String matCd;
    }

    @Getter
    @Setter
    public static class PlanOpRow {
        private long planOpId;
        private long woId;
        private String woNo;
        private int seq;
        private String lateYn;
        private int opSeq;
        private String procCd;
        private String procNm;
        private Long nodeId;
        private String nodeCd;
        private String nodeNm;
        private Long equipId;
        private String equipCd;
        private String equipNm;
        private String resKind;
        private String qty;
        private String unitCd;
        private int setupSec;
        private int runSec;
        private OffsetDateTime startAt;
        private OffsetDateTime endAt;
        private String excCd;
        private Long pinId;
        private String warnCd;
        /** 이 공정에 자재 부족이 있음(5단계) */
        private String matShortYn;
        /** 간트 끌어 놓기가 받을 수 있는 자원 키(지금 후보) — 간트 응답에서만 */
        private List<String> cands;
    }

    // ---------- 6단계 CTP 납기 답변 (docs/38 15장) ----------

    /** 납기 답변 질의 — qty 는 품목 기본 단위 10진 글자, wantDate 선택, priority 선택(없으면 맨 뒤), horizonDays 기본 14 */
    public record CtpForm(String plantCd, String itemCd, String qty, String wantDate, Integer priority, Integer horizonDays) {
    }

    /** 자재 줄 이름 */
    @Getter
    @Setter
    public static class ItemBrief {
        private long itemId;
        private String itemCd;
        private String itemNm;
    }

    /** 답 — 공정 한 줄(가상 지시) */
    public record CtpOp(int opSeq, String procCd, String procNm, String resKind, String resCd, OffsetDateTime startAt,
            OffsetDateTime endAt, String excCd) {
    }

    /** 답 — 자재 한 줄(가상 지시, DELAY 판정) */
    public record CtpMat(long itemId, String itemCd, String itemNm, int opSeq, BigDecimal needQty, BigDecimal allocQty,
            BigDecimal shortQty, String unitCd, OffsetDateTime needAt, OffsetDateTime coveredAt) {
    }

    /**
     * 납기 답변 — 저장하지 않는다(A37). endAt = 가상 지시 끝(계획 못 하면 null, excCd 에 이유), lateYn = 희망 납기(없으면 기간 끝) 넘김.
     * woCnt = 함께 계산한 지금 지시 수, from·to = 계산 구간
     */
    public record CtpResult(String plantCd, long itemId, String itemCd, String itemNm, BigDecimal qty, String unitCd, LocalDate wantDate,
            int priority, String timeZone, OffsetDateTime from, OffsetDateTime to, OffsetDateTime startAt, OffsetDateTime endAt,
            String lateYn, String excCd, int woCnt, int calcMs, List<CtpOp> ops, List<CtpMat> mats) {
    }

    /** 계획 목록·grid 공통 조건(검증된 값) — MyBatis OGNL 이 읽게 getter 클래스 */
    @Getter
    @AllArgsConstructor
    public static class PlanCrit {
        private String plantCd;
        private OffsetDateTime from;
        private OffsetDateTime to;
    }

    /** grid 정렬 허용 목록(UI 단계 3) — field = grid 열 이름, enum 이름 = 매퍼 choose 키 */
    public enum PlanSort implements SortField {
        PLAN_NO("planNo"), PLAN_NM("planNm"), PLANT_CD("plantCd"), HORIZON_START("horizonStart"), LATE_WO_CNT("lateWoCnt"),
        UNSCHED_OP_CNT("unschedOpCnt"), STATUS("status"), CALCULATED_AT("calculatedAt");

        private final String field;

        PlanSort(String field) {
            this.field = field;
        }

        @Override
        public String field() {
            return field;
        }
    }

    /** grid 응답 한 쪽 */
    public record PlanPage(List<PlanRow> list, Long total) {
    }

    public record PlanList(List<PlanRow> rows, boolean more) {
    }

    /** mats = 자재 할당 결과(5단계 — 부족 줄 먼저) */
    public record PlanDetail(PlanRow plan, List<PlanWoRow> wos, List<PlanOpRow> unscheduled, List<PlanMatRow> mats) {
    }

    /** 간트 자원 행 — key = kind:id, 막대(PlanOpRow)는 같은 key 로 붙는다 */
    public record GanttRes(String key, String kind, Long equipId, Long nodeId, String code, String name) {
    }

    /** 계산 때 쉬는 날(4단계) — source = WORK_CAL·COMP_CAL·WEEKLY, reason = 달력 사유(주간 쉬는 날은 없음) */
    public record OffDay(LocalDate day, String source, String reason) {
    }

    /** 근무 구간 [시작, 끝] (epoch 초) */
    public record WorkWindow(long start, long end) {
    }

    /** 날 경계(공장 현지 00:00) — 간트 축 눈금. 마지막 하나는 계획 끝 */
    public record DayTick(LocalDate date, long start) {
    }

    /** pins = 같은 공장의 지금 사용 중 고정(다음 계산 입력 — 간트에 점선으로) */
    public record Gantt(PlanRow plan, List<DayTick> days, List<GanttRes> resources, List<PlanOpRow> ops, List<WorkWindow> windows,
            List<PinRow> pins, List<OffDay> offDays) {
    }

    // ---------- 자재 (5단계) ----------

    /** 자재 할당 결과 INSERT */
    public record MatOutRow(long planWoId, long woId, int opSeq, Integer srcOpSeq, long itemId, BigDecimal needQty, String unitCd,
            OffsetDateTime needAt, BigDecimal allocQty, BigDecimal shortQty, OffsetDateTime coveredAt) {
    }

    /** 계산에 쓴 공급 INSERT — kind STOCK·PO */
    public record SupplyOutRow(long itemId, String kind, OffsetDateTime supplyAt, BigDecimal qty, String unitCd, String poNo,
            Integer poLineNo, String promisedYn, String baseUnitYn) {
    }

    @Getter
    @Setter
    public static class PlanMatRow {
        private long woId;
        private String woNo;
        private int opSeq;
        private Integer srcOpSeq;
        private String itemCd;
        private String itemNm;
        private String needQty;
        private String allocQty;
        private String shortQty;
        private String unitCd;
        private OffsetDateTime needAt;
        private OffsetDateTime coveredAt;
        /** 이 품목의 계산 때 공급(재고 + 입고 예정, 기본 단위 줄만) 합 */
        private String supplyQty;
        /** 8단계(docs/38 17.4): 자재 줄 id·품목 조달 유형·살아 있는 구매 요청 번호(없으면 null) */
        private long planMatId;
        private String procType;
        private String prNo;
    }

    // ---------- 반영 (2단계) ----------

    /** 설비 배정 변경 한 건 — 지금 → 계획. rowVer = 지시 공정 row_ver(계산 때와 같을 때만 반영) */
    public record ApplyEquip(long woProcId, int opSeq, String procCd, String fromEquipCd, String toEquipCd, int rowVer) {
    }

    /** 지시 한 건의 바뀔 값. pushed = 새 완료일이 계산 때 완료일(납기)보다 뒤 */
    public record ApplyWo(long woId, String woNo, String woStatus, LocalDate fromStart, LocalDate fromEnd, LocalDate toStart,
            LocalDate toEnd, boolean pushed, List<ApplyEquip> equips) {
    }

    /** 낡은 계획 사유 — code = APS_WO_CHANGED·APS_PROC_CHANGED·APS_NEW_WO·APS_OLD_PLAN */
    public record StaleRow(String woNo, String code) {
    }

    /** 반영 미리보기 — wos 는 바뀌는 지시만, stale 이 있거나 expired(계획 끝이 지남)면 반영 불가 */
    public record ApplyPreview(PlanRow plan, List<ApplyWo> wos, List<StaleRow> stale, boolean expired, int woCnt, int opCnt,
            int pushedCnt, List<FencedWo> fenced, int fenceDays) {
    }

    /** 확정 구간 안이라 반영이 바꾸지 않는 지시(docs/38 18.3) — 지금 시작 → 계획 시작, 그 지시에 쓴 구간 일수 */
    public record FencedWo(long woId, String woNo, String woStatus, LocalDate fromStart, LocalDate fromEnd, LocalDate toStart,
            LocalDate toEnd, int fenceDays, int equipCnt) {
    }

    public record ApplyResult(int woCnt, int opCnt) {
    }

    /** 반영 대상: 계획된 지시(계산 때 사본) + 지금 지시 */
    @Getter
    @Setter
    public static class ApplyWoSrc {
        private long woId;
        private String woNo;
        private Integer woRowVer;
        private LocalDate snapEnd;
        private OffsetDateTime startAt;
        private OffsetDateTime endAt;
        private String excCd;
        private String curStatus;
        private int curRowVer;
        private LocalDate curStart;
        private LocalDate curEnd;
        /** 품목별 확정 구간(계획 기준정보, 없으면 null) */
        private Integer itemFenceDays;
        /** 이 계획에서 사유 있는 고정을 쓴 공정이 있음 */
        private boolean pinReason;
    }

    /** 반영 대상 공정: 발행 지시 공정(계산 때 사본) + 지금 공정 */
    @Getter
    @Setter
    public static class ApplyOpSrc {
        private long woId;
        private long woProcId;
        private int opSeq;
        private String procCd;
        private Integer procRowVer;
        private String resKind;
        private Long equipId;
        private String equipCd;
        private boolean scheduled;
        private int curRowVer;
        private Long curEquipId;
        private String curEquipCd;
    }

    // ---------- 비교 (2단계) ----------

    /** 공정 하나의 계획(비교용) */
    @Getter
    @Setter
    public static class CmpOp {
        private long woId;
        private String woNo;
        private int opSeq;
        private String procCd;
        private String procNm;
        private String resKind;
        private String resCd;
        private OffsetDateTime startAt;
        private OffsetDateTime endAt;
        private String excCd;
    }

    /**
     * 두 계획의 같은 (지시, 공정) — kind = SAME·MOVED·RES(자원 바뀜, 시각도 바뀔 수 있음)·SCHEDULED(A 못 함 → B 계획)·
     * UNSCHEDULED(A 계획 → B 못 함)·ONLY_A·ONLY_B. shiftSec = B 시작 − A 시작(둘 다 계획됐을 때)
     */
    public record CompareRow(long woId, String woNo, int opSeq, String procCd, String procNm, String kind,
            OffsetDateTime aStart, OffsetDateTime aEnd, String aRes, String aExc,
            OffsetDateTime bStart, OffsetDateTime bEnd, String bRes, String bExc, Long shiftSec) {
    }

    public record Compare(PlanRow a, PlanRow b, List<CompareRow> rows, Map<String, Integer> kinds) {
    }

    // ---------- 계산 입력(매퍼) ----------

    @Getter
    @Setter
    public static class ShiftRow {
        private LocalTime startTime;
        private LocalTime endTime;
        private int breakMin;
    }

    @Getter
    @Setter
    public static class WoSrc {
        private long woId;
        private String woNo;
        private String status;
        private long itemId;
        private BigDecimal planQty;
        private String unitCd;
        private int priority;
        private LocalDate planStart;
        private LocalDate planEnd;
        private LocalDate dueDate;
        private String dueSrc;
        private int rowVer;
    }

    /** 계산 입력: 사용 중 고정 (3단계) */
    @Getter
    @Setter
    public static class PinSrc {
        private long pinId;
        private long woId;
        private int opSeq;
        private String resKind;
        private Long equipId;
        private Long nodeId;
        private OffsetDateTime startAt;
    }

    @Getter
    @Setter
    public static class OpSrc {
        private long woId;
        private Long woProcId;
        private int opSeq;
        private long procId;
        private String procCd;
        private String procNm;
        private Long nodeId;
        private Long equipId;
        private String outsrcYn;
        private BigDecimal stdCtSec;
        private int unitsPerCycle;
        private BigDecimal setupMin;
        private String procStatus;
        private Integer procRowVer;
        private BigDecimal qty;
    }

    @Getter
    @Setter
    public static class NodeRow {
        private long nodeId;
        private Long parentId;
        private String nodeCd;
        private String nodeNm;
    }

    @Getter
    @Setter
    public static class EquipRow {
        private long equipId;
        private String equipCd;
        private String equipNm;
        private Long nodeId;
        private String useYn;
    }

    /** 계산 결과 행(INSERT) */
    public record WoOutRow(int seq, long woId, String woNo, String woStatus, long itemId, BigDecimal planQty, String unitCd,
            int priority, LocalDate planStart, LocalDate planEnd, OffsetDateTime startAt, OffsetDateTime endAt, String lateYn,
            String excCd, int woRowVer, LocalDate dueDate, String dueSrc, String matCd) {
    }

    public record OpOutRow(long planWoId, long woId, Long woProcId, int opSeq, long procId, String procCd, String procNm,
            Long nodeId, Long equipId, String resKind, BigDecimal qty, int setupSec, int runSec, OffsetDateTime startAt,
            OffsetDateTime endAt, String excCd, Integer procRowVer, Long pinId, String warnCd) {
    }

    public record PlanInsert(String planNo, String plantCd, String planNm, LocalDate horizonStart, int horizonDays,
            int outsrcLeadHr, String timeZone, OffsetDateTime schedFrom, OffsetDateTime schedTo, String workWindows, int woCnt,
            int opCnt, int lateWoCnt, int unschedOpCnt, OffsetDateTime makespanEnd, int calcMs, String offDays, String matMode, String matPo,
            int matShortWoCnt) {
    }

    @Getter
    @Setter
    public static class PlanWoKey {
        private long planWoId;
        private long woId;
    }
}
