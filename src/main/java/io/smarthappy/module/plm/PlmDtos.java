package io.smarthappy.module.plm;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

import lombok.Getter;
import lombok.Setter;

/** 설계 변경 DTO (PLM 1단계, docs/39) */
public final class PlmDtos {

    private PlmDtos() {
    }

    // ------------------------------------------------------------------ ECR

    @Getter
    @Setter
    public static class EcrRow {
        private long ecrId;
        private String ecrNo;
        private String title;
        private String reasonCd;
        private String description;
        private Long itemId;
        private String itemCd;
        private String itemNm;
        private long requesterId;
        private String requesterNm;
        private String status;
        private Long ecoId;
        private String ecoNo;
        private OffsetDateTime decidedAt;
        private String decidedBy;
        private String decisionComment;
        /** 지금 사용자가 요청자인가(화면 버튼용 — 판정은 서버) */
        private boolean mine;
        private int rowVer;
        private OffsetDateTime createdAt;
        private OffsetDateTime updatedAt;
        private String updatedBy;
    }

    public record EcrForm(String title, String reasonCd, String description, String itemCd, Integer rowVer) {
    }

    /** 접수: ecoId 를 주면 그 작성 중 지시에 묶고, 비우면 새 지시(유효일 비우면 오늘) */
    public record EcrAcceptForm(Integer rowVer, Long ecoId, String effectiveDate, String comment) {
    }

    public record EcrActForm(Integer rowVer, String comment) {
    }

    /** 요청 grid 정렬 허용 목록(UI 단계 3, docs/39 21.2) — field = grid 열 이름, enum 이름 = 매퍼 choose 키 */
    public enum EcrSort implements io.smarthappy.platform.common.query.SortField {
        ECR_NO("ecrNo"), TITLE("title"), ITEM_CD("itemCd"), STATUS("status"), ECO_NO("ecoNo");

        private final String field;

        EcrSort(String field) {
            this.field = field;
        }

        @Override
        public String field() {
            return field;
        }
    }

    /** 요청 grid 한 쪽 */
    public record EcrPage(List<EcrRow> list, Long total) {
    }

    public record EcrList(List<EcrRow> rows, boolean more) {
    }

    // ------------------------------------------------------------------ ECO

    @Getter
    @Setter
    public static class EcoRow {
        private long ecoId;
        private String ecoNo;
        private String title;
        private String reasonCd;
        private String description;
        private LocalDate effectiveDate;
        private long ownerId;
        private String ownerNm;
        private String custApprovalReqYn;
        private Long custId;
        private String custCd;
        private String custNm;
        private String custApprovalStatus;
        private String custApprovalRef;
        private LocalDate custApprovedOn;
        private String status;
        private Long aprId;
        private String aprStatus;
        private Long aprRequesterId;
        private OffsetDateTime submittedAt;
        private OffsetDateTime approvedAt;
        private OffsetDateTime releasedAt;
        private String releasedBy;
        private String cancelReason;
        private String changeKind;
        private Integer markLots;
        private LocalDate markUntil;
        /** 적용 조건 DATE·STOCK_OUT, 소진 품목·공장 (docs/39 18장) */
        private String applyCond;
        private Long stockItemId;
        private String stockItemCd;
        private String stockItemNm;
        private String stockPlantCd;
        /** STOCK_OUT 이고 적용 전일 때만 — 상세 조회 시점의 가용 재고(WMS StockQuery), 그 밖은 null */
        private java.math.BigDecimal stockAvailable;
        private int targetCnt;
        private int markCnt;
        private int rowVer;
        private OffsetDateTime createdAt;
        private OffsetDateTime updatedAt;
        private String updatedBy;
    }

    /** 영향 대상 + 지금 리비전 상태(refStatus·refRowVer) — changed = 상신 때 고정한 row_ver 와 다름 */
    @Getter
    @Setter
    public static class TargetRow {
        private long targetId;
        private int seq;
        private String targetType;
        /** DOC 대상은 비울 수 있다(16.1) */
        private Long itemId;
        private String itemCd;
        private String itemNm;
        private Long bomId;
        private Long routingId;
        private Long equipId;
        private String equipCd;
        private String equipNm;
        private Long procId;
        private String procCd;
        private String procNm;
        private Long docRevId;
        private String docNo;
        private String docNm;
        /** DOC 대상의 품목이 그 문서 적용 대상(품목·품목 클래스)에 없음 — 경고만(16.1) */
        private boolean docTargetWarn;
        private String rev;
        private String plantCd;
        private String refStatus;
        private Integer refRowVer;
        private LocalDate refValidFrom;
        private String refChangeNo;
        private String changeDesc;
        private Integer baseRowVer;
        private int rowVer;
        private boolean changed;
    }

    /**
     * changeKind 비우면 DESIGN, markLots 비우면 정책, markUntil = 4M·임시 표시 끝 날짜(3b).
     * applyCond 비우면 DATE, STOCK_OUT 이면 stockItemCd·stockPlantCd 필수(18장)
     */
    public record EcoForm(String title, String reasonCd, String description, String effectiveDate, Long ownerId,
            String custApprovalReqYn, String custCd, String changeKind, Integer markLots, String markUntil,
            String applyCond, String stockItemCd, String stockPlantCd, Integer rowVer) {
    }

    /** 대상 한 벌 — targetId 가 있으면 수정, 없으면 추가, 빠진 기존 행은 삭제. refId = BOM·라우팅 id, refCd = 설비·공정 코드(EQUIP·PROC) */
    public record TargetItem(String rowKey, Long targetId, String targetType, String itemCd, Long refId, String refCd,
            String changeDesc) {
    }

    public record TargetSet(Integer rowVer, List<TargetItem> targets) {
    }

    public record LineItem(Long userId, String lineType) {
    }

    public record SubmitForm(Integer rowVer, String title, List<LineItem> lines) {
    }

    public record CustApprovalForm(Integer rowVer, String status, String ref, String approvedOn) {
    }

    public record EcoActForm(Integer rowVer, String reason) {
    }

    /** 지시 복사 결과(docs/39 27장) — 새 지시 id, 옮긴 대상 수, 뺀 대상 수(리비전 대상·사용 중지 품목·설비·공정) */
    public record EcoCopyResult(long ecoId, int copied, int skipped) {
    }

    /** 지시 grid 정렬 허용 목록(UI 단계 3, docs/39 21.2) */
    public enum EcoSort implements io.smarthappy.platform.common.query.SortField {
        ECO_NO("ecoNo"), TITLE("title"), EFFECTIVE_DATE("effectiveDate"), OWNER("ownerNm"), STATUS("status");

        private final String field;

        EcoSort(String field) {
            this.field = field;
        }

        @Override
        public String field() {
            return field;
        }
    }

    /** 지시 grid 한 쪽 */
    public record EcoPage(List<EcoRow> list, Long total) {
    }

    public record EcoList(List<EcoRow> rows, boolean more) {
    }

    /** ECO 에서 일괄 대체 실행 (4단계) — bomIds = 미리보기에서 고른 원본 BOM, 시작일은 ECO 유효일 */
    public record ReplaceForm(Integer rowVer, String fromItemCd, String toItemCd, List<Long> bomIds) {
    }

    @Getter
    @Setter
    public static class AprLineRow {
        private int seq;
        private long approverId;
        private String approverNm;
        private String lineType;
        private String status;
        private OffsetDateTime actedAt;
        private String comment;
    }

    /** 결재 요약 — 화면이 승인·반려·회수 버튼을 보일지 정한다(판정은 결재 엔진) */
    public record Approval(Long aprId, String status, boolean myTurn, boolean canWithdraw, List<AprLineRow> lines) {
    }

    /** 표시된 LOT (plm_eco_lot, 3b) — 도착 순 */
    @Getter
    @Setter
    public static class MarkRow {
        private int seqNo;
        private String markReason;
        private long lotId;
        private String lotNo;
        private String itemCd;
        private String itemNm;
        private String woNo;
        private String equipCd;
        private OffsetDateTime occurredAt;
        private OffsetDateTime recordedAt;
    }

    public record MarkList(List<MarkRow> rows, boolean more) {
    }

    /** quality = QMS 품질 요약(계약 EcoQualityView, docs/39 16.2) — QMS 없으면 null */
    public record EcoDetail(EcoRow eco, List<TargetRow> targets, List<EcrRow> ecrs, Approval approval,
            io.smarthappy.module.plm.link.EcoQualityView.Summary quality, List<SignView> signs) {
    }

    /**
     * 영향 대상 후보 한 줄(docs/39 20.7) — 역전개(유효일 기준 확정 리비전) + 그 모품목의 작성 중 리비전.
     * state: 1단계 = IN_THIS·OPEN_ECO·NO_DRAFT·ADDABLE, 2단계 이상 = IN_THIS(품목 대상 있음)·ITEM(품목 대상 후보)
     */
    @Getter
    @Setter
    public static class ImpactRow {
        private int level;
        private String path;
        private long parentId;
        private String parentCd;
        private String parentNm;
        private long bomId;
        private String rev;
        private Long draftBomId;
        private String draftRev;
        private String openEcoNo;
        private String state;
    }

    /** scope = PARENT(1단계 상위만) · ALL(전체 레벨), on = 기준일(ECO 유효일) */
    public record ImpactResult(String itemCd, String itemNm, java.time.LocalDate on, String scope, List<ImpactRow> rows) {
    }

    /** 전자서명 한 줄(docs/39 19장) — changed = 지금 내용 해시가 서명한 해시와 다름("서명 뒤 바뀜") */
    public record SignView(long esignId, long userId, String signerNm, String meaningCd, String authMethod,
            OffsetDateTime signedAt, boolean changed) {
    }

    /** 결재 승인(PLM 화면) — esignToken = hb.esign 토큰(정책 PLM_ESIGN_REQUIRED=Y 면 필수) */
    public record EcoApproveForm(String comment, String esignToken) {
    }

    // ------------------------------------------------------------------ PPAP·PCN (4단계, docs/39 14.4·14.5)

    @Getter
    @Setter
    public static class PpapRow {
        private long ppapId;
        private long ecoId;
        private long custId;
        private String custCd;
        private String custNm;
        private int submitLevel;
        private String status;
        private LocalDate submittedOn;
        private LocalDate decidedOn;
        private String decisionRef;
        private String decisionNote;
        private String remark;
        private int rowVer;
        private OffsetDateTime updatedAt;
        private String updatedBy;
    }

    @Getter
    @Setter
    public static class PpapElemRow {
        private long elemId;
        private int elemNo;
        private String reqCd;
        private String status;
        private Long fileId;
        private String fileName;
        private String note;
        private int rowVer;
        /** APQP 근거(38장) — 요소 6 = FMEA·7 = CP, label = 연결한 때의 스냅샷 */
        private String apqpKind;
        private Long apqpRevId;
        private String apqpLabel;
        /** 지금 상태(RELEASED·OBSOLETE 등) — APQP 조회 권한이 있고 읽힐 때만, 아니면 null(label 만) */
        private String apqpStatus;
    }

    /** 패키지 없음 = ppap null. apqpKinds = 이 사람이 근거로 연결할 수 있는 APQP 종류(FMEA·CP — 그 화면 SEARCH, 38장 Q4) */
    public record PpapDetail(PpapRow ppap, List<PpapElemRow> elems, List<SignView> signs, List<PpapMailRow> mails, boolean mailEnabled,
            List<String> apqpKinds) {
    }

    /** 지시 대상 품목 — APQP 후보 범위 */
    @Getter
    @Setter
    public static class PpapApqpItem {
        private long itemId;
        private String itemCd;
        private String itemNm;
    }

    /** APQP 근거 후보 한 건(38장 Q1) — 현행 확정본, phase = 관리계획서 단계(PFMEA 는 null) */
    public record PpapApqpCand(String kind, long revId, String no, int rev, String title, String phase, String itemCd, String itemNm,
            java.time.OffsetDateTime releasedAt) {
    }

    /** 요소 6 후보(PFMEA)·요소 7 후보(관리계획서, 양산 단계 먼저) */
    public record PpapApqpCandidates(List<PpapApqpCand> fmea, List<PpapApqpCand> cp) {
    }

    /** 요소 6·7 근거 연결 — revId null = 끊기, rowVer = 패키지 row_ver */
    public record PpapApqpForm(Integer rowVer, Integer elemNo, Long revId) {
    }

    /** 패키지 만들기 — 고객 비우면 ECO 고객, 레벨 비우면 3 */
    public record PpapCreateForm(String custCd, Integer submitLevel, String remark) {
    }

    /** 요소 한 줄 — elemNo 로 찾는다(1~18), 보낸 것만 바꾼다 */
    public record PpapElemItem(Integer elemNo, String reqCd, String status, Long fileId, String note) {
    }

    /** 작성 중 패키지 저장 — 레벨·비고 + 요소 */
    public record PpapForm(Integer rowVer, Integer submitLevel, String remark, List<PpapElemItem> elems) {
    }

    public record PpapSubmitForm(Integer rowVer, String submittedOn) {
    }

    /** 고객 판정 — decision APPROVED·INTERIM·REJECTED, 승인이면 ref(PSW·승인 번호) 필수 */
    public record PpapDecideForm(Integer rowVer, String decision, String decidedOn, String ref, String note, String esignToken) {
    }

    public record PpapActForm(Integer rowVer) {
    }

    @Getter
    @Setter
    public static class PcnRow {
        private long pcnId;
        private String pcnNo;
        private long ecoId;
        private long custId;
        private String custCd;
        private String custNm;
        private LocalDate notifiedOn;
        private LocalDate responseDue;
        private String status;
        private LocalDate respondedOn;
        private String responseNote;
        private String note;
        /** 응답 기한이 지났는데 아직 통지 상태 */
        private boolean overdue;
        private int rowVer;
        /** 메일 발송 이력(docs/39 23장, 오래된 것부터) — 목록 조회 때 서비스가 채운다 */
        private List<PcnMailRow> mails = List.of();
        /** 메일 설정이 켜져 있는가 — 꺼져 있으면 화면이 [메일 보내기]를 숨긴다(E8) */
        private boolean mailEnabled;
    }

    /**
     * PCN 메일 발송 이력 한 줄 — 받는 이름·주소는 보낸 때의 스냅샷. 발송 결과(32장)는 플랫폼 MailService.statuses 로 채운다:
     * mailState = PENDING·SENT·FAILED(못 찾으면 null), mailReason = 실패·재시도 이유 코드, 문구는 보는 사람 언어로
     */
    @Getter
    @Setter
    public static class PcnMailRow extends MailRow {
        private long pcnId;
        /** 붙인 변경 통지서 사본(33장) — 없으면 null. 내려받기는 플랫폼 파일 API(plm-eco 조회 권한) */
        private Long noticeFileId;
        private String noticeFileNm;
    }

    /** 고객 메일 발송 이력 한 줄의 공통 칸(PCN 23·32장, PPAP 37장) — 결과는 {@link PlmMailStatus} 가 채운다 */
    @Getter
    @Setter
    public abstract static class MailRow {
        private long mailId;
        private String toNm;
        private String toAddr;
        private OffsetDateTime sentAt;
        private String sentBy;
        /** 아웃박스 메시지 id(uuid 글자 — 이 저장소 MyBatis 에 UUID 형 처리기가 없다) — 결과 조회용, 화면에 내리지 않는다 */
        @com.fasterxml.jackson.annotation.JsonIgnore
        private String outboxId;
        private String mailState;
        private String mailReason;
        private String stateNm;
        private String reasonNm;
        /** 메일 서버가 받은 시각(SENT 일 때) */
        private OffsetDateTime acceptedAt;
    }

    /** PPAP 제출 메일 발송 이력 한 줄(37장) — 첨부 = 그 시점 요약서 사본 */
    @Getter
    @Setter
    public static class PpapMailRow extends MailRow {
        private long ppapId;
        private Long summaryFileId;
        private String summaryFileNm;
    }

    /** PPAP 메일 본문 재료 — 패키지·지시·회사(이름·기본 언어) */
    @Getter
    @Setter
    public static class PpapMailSource {
        private long ppapId;
        private String status;
        private int submitLevel;
        private LocalDate submittedOn;
        private long ecoId;
        private String ecoNo;
        private String ecoTitle;
        private String ecoStatus;
        private String compNm;
        private String localeCd;
    }

    /** PPAP 메일 보내기 — 담당자 id 1~10명(주소 직접 입력 칸 없음 — 37장 P1) */
    public record PpapMailForm(List<Long> contactIds) {
    }

    /** 메일 받는 사람 후보 — 통지 고객의 사용 중 담당자 중 메일이 있는 사람 */
    @Getter
    @Setter
    public static class PcnContact {
        private long contactId;
        private String contactNm;
        private String deptNm;
        private String email;
        private boolean main;
    }

    /** 받는 사람 후보 + 메일 설정 켜짐 + 미리보기(제목·본문 — 인사 줄 이름은 자리표시) */
    public record PcnMailTargets(boolean enabled, List<PcnContact> contacts, String subject, String preview) {
    }

    /** 메일 보내기 — 담당자 id 1~10명, attachNotice = 그 시점 변경 통지서 엑셀을 첨부(33장, 비우면 첨부 안 함) */
    public record PcnMailForm(List<Long> contactIds, Boolean attachNotice) {
    }

    /** 메일 본문에 쓰는 통지·지시 값 (잠근 통지 기준) */
    @Getter
    @Setter
    public static class PcnMailSource {
        private long pcnId;
        private String pcnNo;
        private String status;
        private LocalDate responseDue;
        private String note;
        private long ecoId;
        private String ecoNo;
        private String ecoTitle;
        private String reasonCd;
        private LocalDate effectiveDate;
        /** 결재 전(작성 중·결재 중)이면 본문에 초안 표시(33장 F5) */
        private String ecoStatus;
        private String compNm;
        private String localeCd;
    }

    public record PcnForm(String custCd, String notifiedOn, String responseDue, String note) {
    }

    /** 엑셀 내보내기 요청(docs/39 25장) — grid 와 같은 조건·정렬, columns = 보이는 열 */
    public record EcoExport(String status, String keyword, String itemCd, List<String> sort, List<String> columns) {
    }

    public record EcrExport(String status, String keyword, String itemCd, List<String> sort, List<String> columns) {
    }

    /** 고객 응답 — status ACK·APPROVED·REJECTED */
    public record PcnResponseForm(Integer rowVer, String status, String respondedOn, String note) {
    }

    // ------------------------------------------------------------------ 선택지

    /** 대상 후보 — 작성 중 BOM·라우팅 리비전. openEcoNo = 이미 묶인 열린 지시 */
    @Getter
    @Setter
    public static class Candidate {
        private String targetType;
        private long refId;
        private long itemId;
        private String itemCd;
        private String itemNm;
        private String rev;
        private int altNo;
        private String plantCd;
        private LocalDate validFrom;
        private int rowVer;
        private String openEcoNo;
    }

    @Getter
    @Setter
    public static class RefInfo {
        private long refId;
        private long itemId;
        private String status;
        private int rowVer;
        private String rev;
        private String baseQty;
        private String remark;
        private String openEcoNo;
    }

    /** 도면 대상 고르기(2단계) — 문서 번호의 열린 리비전(작성 중·결재 중) */
    @Getter
    @Setter
    public static class DocRef {
        private long docId;
        private String docType;
        private Long docRevId;
        private String rev;
        private String status;
        private String openEcoNo;
    }

    @Getter
    @Setter
    public static class CustOption {
        private long custId;
        private String custCd;
        private String custNm;
    }

    @Getter
    @Setter
    public static class ItemRef {
        private long itemId;
        private String itemCd;
        private String itemNm;
        private String useYn;
    }
}
