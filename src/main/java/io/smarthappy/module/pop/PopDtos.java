package io.smarthappy.module.pop;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

import io.smarthappy.module.master.workforce.WorkforceDtos.GateResult;
import lombok.Getter;
import lombok.Setter;

/** POP 단말·배지·작업장 홈 DTO (P2 5a, docs/25 4.6) */
public final class PopDtos {

    private PopDtos() {
    }

    // ---------- 단말 관리(사무) ----------

    @Getter
    @Setter
    public static class TerminalRow {
        private long terminalId;
        private String terminalCd;
        private String terminalNm;
        private String plantCd;
        private String plantNm;
        private Long nodeId;
        private String nodeCd;
        private String nodeNm;
        private String terminalType;
        private OffsetDateTime activationExpiresAt;
        private boolean keyIssued;
        private OffsetDateTime activatedAt;
        private OffsetDateTime lastSeenAt;
        private OffsetDateTime revokedAt;
        private String revokeReason;
        private String remark;
        private int rowVer;
        private OffsetDateTime updatedAt;
        private String updatedBy;
        /** REVOKED·ACTIVE·KEY_MISSING·WAITING·EXPIRED — 조회 시점 계산(grid 상태 칸) */
        private String state;

        /**
         * grid 작업장 칸 — 코드 · 이름. 공장 전체면 null 이 아니라 빈 글자: hb.grid 는 null 칸에 format 을 부르지 않아
         * 화면의 "(공장 전체)" 표시(format)가 빠진다(빈 글자면 부른다)
         */
        public String getNodeRef() {
            return nodeCd == null ? "" : nodeCd + " · " + nodeNm;
        }

        /** 엑셀 이름 칸(docs/25 4.40) — 내보낼 때만 채운다(상태 이름, 작업장 없음 = 공장 전체) */
        private String stateNm;
        private String nodeRefNm;
    }

    /** 단말 grid 정렬 허용 목록(UI 단계 3 ①) — field = grid 열 이름, enum 이름 = 매퍼 choose 키 */
    public enum TerminalSort implements io.smarthappy.platform.common.query.SortField {
        TERMINAL_CD("terminalCd"), TERMINAL_NM("terminalNm"), PLANT_CD("plantCd"), TERMINAL_TYPE("terminalType"), LAST_SEEN_AT("lastSeenAt");

        private final String field;

        TerminalSort(String field) {
            this.field = field;
        }

        @Override
        public String field() {
            return field;
        }
    }

    /** 단말 grid 엑셀(docs/25 4.40) — 조건 없음, 정렬·보이는 열 */
    public record TerminalExport(List<String> sort, List<String> columns) {
    }

    public record TerminalPage(List<TerminalRow> list, Long total) {
    }

    @Getter
    @Setter
    public static class NodeOption {
        private long nodeId;
        private String plantCd;
        private String nodeCd;
        private String nodeNm;
        private String nodeType;
    }

    @Getter
    @Setter
    public static class PlantOption {
        private String plantCd;
        private String plantNm;
    }

    public record TerminalForm(String terminalCd, String terminalNm, String plantCd, Long nodeId, String terminalType,
            String remark, Integer rowVer) {
    }

    public record RevokeForm(String reason, Integer rowVer) {
    }

    /** 등록 코드는 이 응답에서 한 번만 보인다(저장은 해시) */
    public record ActivationCode(long terminalId, String code, OffsetDateTime expiresAt) {
    }

    // ---------- 단말 인증(POP) ----------

    @Getter
    @Setter
    public static class TerminalAuthRow {
        private long terminalId;
        private String terminalCd;
        private String terminalNm;
        private String plantCd;
        private Long nodeId;
        private String nodeCd;
        private String nodeNm;
        private String terminalType;
        private String keyHash;
        private OffsetDateTime lastSeenAt;
        private String plantUseYn;
        private String compStatus;
    }

    public record ActivateForm(String compCd, String code) {
    }

    public record BadgeForm(String badge, String pin) {
    }

    public record LangForm(String lang) {
    }

    @Getter
    @Setter
    public static class WorkerAuthRow {
        private long workerId;
        private String workerCd;
        private String workerNm;
        private String useYn;
        private String pinHash;
        private int pinFailCnt;
        private OffsetDateTime pinLockedUntil;
    }

    /** 단말 세션의 지금 작업자 (HttpSession 에 둔다) */
    public record PopWorker(long terminalId, long workerId, String workerCd, String workerNm, long since, long lastSeen)
            implements java.io.Serializable {
    }

    /** 배지 결과: PIN 이 필요하면 needPin = true(작업자는 아직 안 바뀜) */
    /** 감독자 승인 요청 (docs/25 4.37) — action = RESULT_CORRECT·PARAM_CORRECT, targetId = 정정할 원 행 */
    public record ApprovalForm(String badge, String pin, String action, Long targetId) {
    }

    /** 승인 토큰(단말 세션, 2분·한 번) — 정정 서비스가 같은 단말·요청 작업자·동작·대상·만료를 확인 */
    public record PopApproval(String token, long terminalId, long requesterId, long approverId, String approverCd,
            String approverNm, String action, long targetId, long issuedAt) implements java.io.Serializable {
    }

    /** 키오스크 빠져나오기 (docs/25 4.52, docs/50 K4·K5) — 감독자 배지 + PIN */
    public record KioskExitForm(String badge, String pin) {
    }

    /**
     * 빠져나오기 확인 — relockAfterSec = 화면이 다시 잠글 때까지(초). 서버는 잠금 상태를 갖지 않는다.
     * exitRef = 이 빠져나오기 기록(접속 로그 POP_KIOSK_EXIT)을 가리키는 임의 값(128비트, 추측 불가) — 화면이 풀지 못하면 unlock-failed 로 알린다(KS9)
     */
    public record KioskExitReply(String approverCd, String approverNm, int relockAfterSec, String exitRef) {
    }

    /** 키오스크 풀기 실패 알림(KS9, docs/50) — reason ∈ {stale, refused} */
    public record KioskUnlockFailedForm(String exitRef, String reason) {
    }

    public record ApprovalReply(String approvalToken, String approverCd, String approverNm, int expiresInSec) {
    }

    public record BadgeResult(boolean needPin, String workerCd, String workerNm) {
    }

    // ---------- 작업장 홈 ----------

    public record PopSession(String compCd, String terminalCd, String terminalNm, String plantCd, String nodeCd, String nodeNm,
            String zone, LocalDate workDay, String shiftCd, String shiftNm, String workerCd, String workerNm, boolean pinRequired,
            int idleMinutes, String lang, String terminalType, boolean breakdown, boolean pm) {
    }

    @Getter
    @Setter
    public static class TaskRow {
        private long woProcId;
        private long woId;
        private String woNo;
        private String itemCd;
        private String itemNm;
        private String planQty;
        private String unitCd;
        private int priority;
        private LocalDate planStart;
        private String woStatus;
        private int opSeq;
        private long procId;
        private String procCd;
        private String procNm;
        private String nodeCd;
        private String procStatus;
        private String equipCd;
        private String equipNm;
        private GateResult gate;
        /** 현행 계획(APS 일일 배정)의 자리 — 계획이 없거나 계획에 없는 공정이면 null(docs/25 4.56) */
        private PlanSlot plan;
    }

    /**
     * 일일 배정 자리(APS 계약 {@code ApsDispatchQuery.Slot} 에서 화면에 쓸 것만). seq = 그날 그 자원 안 순번, resCd = 설비(없으면 작업장) 코드.
     * ahead = 같은 자원에서 계획상 앞인데 아직 시작 안 한 공정 수(이 단말 목록 안), early = 그런데도 이 공정이 먼저 시작됨 — 표시만, 막지 않는다(AP-10)
     */
    public record PlanSlot(String planNo, int seq, String resCd, OffsetDateTime startAt, OffsetDateTime endAt, int ahead, boolean early) {
    }

    public record HomeView(PopSession session, List<TaskRow> tasks) {
    }
}
