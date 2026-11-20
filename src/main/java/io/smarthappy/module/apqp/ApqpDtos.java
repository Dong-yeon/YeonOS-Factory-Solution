package io.smarthappy.module.apqp;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

import io.smarthappy.platform.common.query.SortField;
import lombok.Getter;
import lombok.Setter;

/** P6 APQP A1 — PFMEA·AP 규칙표 DTO (docs/41 4장) */
public final class ApqpDtos {

    private ApqpDtos() {
    }

    // ------------------------------------------------------------------ AP 규칙표

    @Getter
    @Setter
    public static class ApSetRow {
        private long setId;
        private String setCd;
        private String setNm;
        private String sampleYn;
        private String remark;
        private String useYn;
        private int rowVer;
        private int ruleCnt;
        /** 확정·폐기 FMEA 가 쓴 수 — 0 이 아니면 규칙을 고칠 수 없다 */
        private int releasedCnt;
        private OffsetDateTime updatedAt;
        private String updatedBy;
    }

    @Getter
    @Setter
    public static class ApRuleRow {
        private long ruleId;
        private int seq;
        private int sevFrom;
        private int sevTo;
        private int occFrom;
        private int occTo;
        private int detFrom;
        private int detTo;
        private String ap;
    }

    /** 덮임 검사 결과 — 1,000 조합 중 빠진 수·겹친 수, 앞 몇 개 예시(S,O,D) */
    public record Coverage(int missing, int overlap, List<int[]> missingSamples, List<int[]> overlapSamples) {
        public boolean complete() {
            return missing == 0 && overlap == 0;
        }
    }

    public record ApSetView(ApSetRow set, List<ApRuleRow> rules, Coverage coverage) {
    }

    public record ApRuleForm(Integer sevFrom, Integer sevTo, Integer occFrom, Integer occTo, Integer detFrom, Integer detTo, String ap) {
    }

    /** rules 가 null 이면 규칙은 그대로(머리만), 아니면 표째 바꾼다 */
    public record ApSetForm(String setCd, String setNm, String remark, String useYn, Integer rowVer, List<ApRuleForm> rules) {
    }

    public record ApCopyForm(String setCd, String setNm) {
    }

    // ------------------------------------------------------------------ FMEA

    @Getter
    @Setter
    public static class FmeaRow {
        private long fmeaId;
        private String fmeaNo;
        private int rev;
        private String fmeaType;
        private String title;
        private String scope;
        private String team;
        private String drawingRev;
        private long itemId;
        private String itemCd;
        private String itemNm;
        private Long routingId;
        /** 공장 · 대안 · 리비전 (예: P1 · 1 · A) */
        private String routingText;
        /** DFMEA(A5-2) — 근거 BOM 리비전·도면 리비전(QMS DRAWING). 도면 글자는 조회 때 DocLookup 으로 채움 */
        private Long bomId;
        private String bomText;
        private Long docRevId;
        private String docRevText;
        private Long projId;
        private String projNo;
        private Long apSetId;
        private String apSetCd;
        private String status;
        private OffsetDateTime releasedAt;
        private String releasedBy;
        private Long prevFmeaId;
        private int riskCnt;
        private int highCnt;
        private int openActionCnt;
        /** 같은 번호에 작성 중(결재 중 포함) 리비전이 있음(확정본의 조치는 그쪽에서) */
        private boolean draftExists;
        /** 같은 품목·공장·대안의 라우팅 새 리비전이 확정됨(docs/41 A3-2 ① — 표시만) */
        private boolean routingStale;
        /** DFMEA: 같은 품목·대체 번호의 BOM 새 리비전이 확정됨(A5-2b D6 — 표시만) */
        private boolean bomStale;
        /** DFMEA: 같은 도면 문서의 새 리비전이 확정됨(QMS DocLookup — 서비스가 채운다) */
        private boolean drawingStale;
        /** 마지막 결재 요청(정책 APQP_RELEASE_APPROVAL) */
        private Long aprId;
        private int rowVer;
        private OffsetDateTime updatedAt;
        private String updatedBy;
        /**
         * 재검토(docs/41 11.6 M2) — ROUTING = 라우팅 새 리비전 확정(①), 폐기본은 없음. 정렬 키 아님. 글자는 LABEL 열(X2, 문구 키 apqp.review.*)이
         * 화면·엑셀 같은 문구로
         */
        public String getReview() {
            if ("OBSOLETE".equals(status)) {
                return null;
            }
            List<String> r = new java.util.ArrayList<>(3);
            if (routingStale) {
                r.add("ROUTING");
            }
            if (bomStale) {
                r.add("BOM");
            }
            if (drawingStale) {
                r.add("DRAWING");
            }
            return r.isEmpty() ? null : String.join(",", r);
        }
    }

    public enum FmeaSort implements SortField {
        FMEA_NO("fmeaNo"), REV("rev"), TITLE("title"), ITEM_CD("itemCd"), STATUS("status"), HIGH_CNT("highCnt"),
        OPEN_ACTIONS("openActionCnt"), UPDATED_AT("updatedAt");

        private final String field;

        FmeaSort(String field) {
            this.field = field;
        }

        @Override
        public String field() {
            return field;
        }
    }

    /** 목록 엑셀 — 조건은 grid 와 같음, sort·columns 는 hb.grid 가 보낸 그대로(허용 목록 검사) */
    public record FmeaExportRequest(String keyword, String status, Long projId, List<String> sort, List<String> columns) {
    }

    public record FmeaPage(List<FmeaRow> list, Long total) {
    }

    /** 목록 조건 — keywordLike 는 이미 이스케이프한 LIKE 값, statuses null = 전체 */
    /** fmeaType = PFMEA / DFMEA — 화면마다 자기 유형만 */
    public record FmeaFilter(String keywordLike, List<String> statuses, Long projId, String fmeaType) {
    }

    @Getter
    @Setter
    public static class NodeRow {
        private long nodeId;
        private Long parentId;
        private String nodeKind;
        private int seq;
        private String nodeNm;
        private Long stepId;
        private Integer opSeq;
        private String procCd;
        private String procNm;
        private String m4;
        /** DFMEA 하위 시스템·구성품의 자품목(BOM 에서 끌어옴) */
        private Long refItemId;
        private String refItemCd;
        private int rowVer;
    }

    @Getter
    @Setter
    public static class FuncRow {
        private long funcId;
        private long nodeId;
        private int seq;
        private String funcText;
        private Long charId;
        private String charNo;
        private String charNm;
        private String charSpecial;
        private int rowVer;
    }

    @Getter
    @Setter
    public static class FailRow {
        private long failId;
        private long funcId;
        private String failKind;
        private int seq;
        private String failText;
        private Integer severity;
        private Long defectId;
        private String defectCd;
        private String defectNm;
        /** PFMEA 고장 영향의 출처(DFMEA 고장 형태, A5-2b D7) — 글자 "DF-2026-0001 rev 1" */
        private Long srcFailId;
        private String srcText;
        private int rowVer;
    }

    @Getter
    @Setter
    public static class ChainRow {
        private long chainId;
        private long fmId;
        private long feId;
    }

    @Getter
    @Setter
    public static class RiskRow {
        private long riskId;
        private long fmId;
        private long fcId;
        private String prevCtrl;
        private String detCtrl;
        private Integer sev;
        private Integer occ;
        private Integer det;
        private String ap;
        private String specialCls;
        private int rowVer;
    }

    @Getter
    @Setter
    public static class ActionRow {
        private long actionId;
        private long riskId;
        private String actionKind;
        private String actionText;
        private Long ownerUserId;
        private String ownerLoginId;
        private String ownerNm;
        private LocalDate dueDate;
        private String status;
        private LocalDate doneOn;
        private String evidence;
        private Integer newOcc;
        private Integer newDet;
        private String newAp;
        private int rowVer;
    }

    /** 라우팅 단계 고르기 */
    @Getter
    @Setter
    public static class StepOption {
        private long stepId;
        private int opSeq;
        private long procId;
        private String procCd;
        private String procNm;
    }

    /**
     * 재검토 표시 ②③ 한 줄(docs/41 11.6) — 확정일 이후 그 품목에 적용된 ECO 또는 승인된 4M ECO. m4Types = QMS 4M 세부(4M 아니거나 세부 없음 = 빈 목록).
     * overlap = 4M 세부의 영향 특성 중 이 문서에도 있는 것(M4, 문서 특성 순서). null = 세부 없음(비교 못 함), 빈 목록 = 특성 겹침 없음(다른 영향은 상세에서).
     * 날짜는 회사 날짜(PLM 계약 그대로)
     */
    public record ChangeNote(long ecoId, String ecoNo, String title, String changeKind, String status, LocalDate approvedOn,
            LocalDate releasedOn, List<String> m4Types, List<CharRef> overlap, PlanOverlap planOverlap) {
    }

    /**
     * 관리계획서가 대조(C5)에서 본 검사계획 리비전 한 건 — 그 날 유효한 확정본(QMS InspPlanLookup). procCd null = 공정 무관 계획
     */
    public record PlanRef(long inspPlanId, String rev, String inspType, String procCd) {
    }

    /**
     * 4M 영향 검사계획과 이 관리계획서의 겹침(docs/41 11.6.2, M4 확장) — 관리계획서만(PFMEA 는 검사계획 연결이 없어 null).
     * hits = 대조가 본 검사계획과 같은 것(같은 키의 다른 리비전이면 그 리비전 id·rev), unknown = 키를 못 읽어 판단 못 함(QMS 없음·없는 id),
     * unrelated = 키가 달라 이 관리계획서와 무관. 4M 에 검사계획이 없으면 null
     */
    public record PlanOverlap(List<PlanRef> hits, int unknown, int unrelated) {
    }

    /**
     * 이 문서의 특성 한 개(M4 겹침 비교) — specialCls = 특성 마스터 원본(없으면 행 스냅샷). special = 특별특성(NONE·빈 값 아님)
     */
    public record CharRef(long charId, String charNo, String charNm, String specialCls, boolean special) {
        public static CharRef of(long charId, String charNo, String charNm, String specialCls) {
            return new CharRef(charId, charNo, charNm, specialCls, specialCls != null && !specialCls.isBlank() && !"NONE".equals(specialCls));
        }
    }

    /**
     * truncated = PLM 이 상한(500)에서 자름 — 화면은 "더 있음" 만 알린다.
     * own = 이 리비전을 올린 QMS 문서 리비전을 DOC 대상으로 담은 ECO(docs/41 11.10 E-3) — 재검토(rows)에서 빼고 "이 리비전을 배포한 ECO" 로
     */
    public record ChangeNotes(List<ChangeNote> rows, boolean truncated, List<ChangeNote> own) {
        public static final ChangeNotes EMPTY = new ChangeNotes(List.of(), false, List.of());
    }

    /**
     * 상세 한 번에 — 구조·기능·고장·사슬·위험·조치 + 고를 라우팅 단계. 권한 판정은 서버, 화면은 단추만 숨긴다.
     * issueView·issueSave = PMS 이슈 화면 SEARCH·SAVE(A3-5 — 연결 이슈 표시·"이슈로" 단추, 판정은 PMS API 가 다시)
     */
    public record FmeaDetail(FmeaRow fmea, List<NodeRow> nodes, List<FuncRow> funcs, List<FailRow> fails, List<ChainRow> chains,
            List<RiskRow> risks, List<ActionRow> actions, List<StepOption> steps, boolean editable, boolean actionEditable,
            boolean approvalOn, ChangeNotes changes, boolean issueView, boolean issueSave) {
    }

    // ------------------------------------------------------------------ 공정 흐름도 PFD (docs/41 13장 A5-1 — 저장하지 않고 만들어 보인다)

    /** 라우팅 단계 + 공정 분류·외주·표준 CT (PFD 기호 판정용) */
    @Getter
    @Setter
    public static class FlowStepRow {
        private long stepId;
        private int opSeq;
        private String procCd;
        private String procNm;
        private String procCat;
        private String outsrcYn;
        private BigDecimal stdCtSec;
    }

    /** 단계의 특성 — inFmea = PFMEA 기능에 붙음, inCp = 관리계획서 행에 있음. 특별특성은 마스터 원본(D2) */
    public record FlowChar(long charId, String charNo, String charNm, String charType, String specialCls, boolean special,
            boolean inFmea, boolean inCp) {
    }

    /**
     * PFD 한 단계 — symbol = 우리 기호 4종 PROCESS(가공)·INSPECT(검사, proc_cat INSPECT)·OUTSOURCE(외주, outsrc_yn)·PACK(포장).
     * cpLineCnt = 관리계획서 행 수, riskCnt·highCnt = 이 단계 고장 형태(FM)의 위험행 수·AP H 수
     */
    public record FlowStep(long stepId, int opSeq, String procCd, String procNm, String procCat, String symbol, BigDecimal stdCtSec,
            List<FlowChar> chars, int cpLineCnt, int riskCnt, int highCnt) {
    }

    /**
     * PFMEA 의 라우팅(리비전 그대로) + 그 PFMEA 의 위험행 + 그 PFMEA 를 가리키는 최신 관리계획서(폐기 빼고, 화면 권한 apqp-cp 있을 때만).
     * unplaced = 라우팅 단계에 놓이지 않은 위험행 수(손으로 만든 단계 노드 등), cpHidden = 관리계획서가 있으나 볼 권한 없음
     */
    public record FlowView(long fmeaId, String fmeaNo, int rev, String status, String itemCd, String itemNm, Long routingId,
            String routingText, Long cpId, String cpNo, Integer cpRev, String cpStatus, boolean cpHidden, List<FlowStep> steps,
            int unplaced) {
    }

    /** DFMEA 는 routingId·fromRouting·drawingRev 대신 bomId(확정 BOM 리비전)·docRevId(도면 리비전, 선택)·fromBom(1~2단계 끌어오기) */
    public record FmeaForm(String itemCd, Long routingId, Long projId, String title, String scope, String team, String drawingRev,
            Long apSetId, Boolean fromRouting, Integer rowVer, Long bomId, Long docRevId, Boolean fromBom) {
    }

    public record RowVerForm(Integer rowVer) {
    }

    /** 결재선 한 줄 — lineType = APPROVE·AGREE·REF(비우면 APPROVE) */
    public record AprLine(Long userId, String lineType) {
    }

    /** 결재 상신(정책 APQP_RELEASE_APPROVAL = Y) — 승인 = 확정, 반려·회수 = 작성 중 */
    /** 라우팅 바꾸기(11.9) — 적용도 서버가 다시 계산한다(미리보기 결과를 받지 않음) */
    public record RelinkForm(Long routingId, Integer rowVer) {
    }

    /** 단계 노드 한 줄 — matched = 새 라우팅에 같은 op_seq·공정이 있음(toStepId), 아니면 글자 단계(textNm)로 남는다 */
    public record RelinkStep(long nodeId, String nodeNm, Integer opSeq, String procCd, String procNm, Long fromStepId, Long toStepId,
            boolean matched, String textNm) {
    }

    /** 미리보기·적용 결과 — rowVer = 미리보기 때 머리 row_ver(적용에 그대로 보냄) */
    public record RelinkPlan(long routingId, String routingText, List<RelinkStep> steps, int matched, int unmatched, int rowVer) {
    }

    public record SubmitForm(Integer rowVer, String title, List<AprLine> lines) {
    }

    public record NodeForm(Long parentId, String nodeKind, String nodeNm, Long stepId, String m4, Integer rowVer) {
    }

    public record MoveForm(String op, Integer rowVer) {
    }

    public record FuncForm(Long nodeId, String funcText, Long charId, Integer rowVer) {
    }

    public record FailForm(Long funcId, String failKind, String failText, Integer severity, Long defectId, Integer rowVer, Long srcFailId) {
    }

    public record ChainForm(Long fmId, Long feId) {
    }

    public record RiskForm(Long fmId, Long fcId, String prevCtrl, String detCtrl, Integer occ, Integer det, String specialCls,
            Integer rowVer) {
    }

    public record ActionForm(Long riskId, String actionKind, String actionText, String ownerLoginId, String dueDate, String status,
            String doneOn, String evidence, Integer newOcc, Integer newDet, Integer rowVer) {
    }

    // ------------------------------------------------------------------ 고르기 목록

    @Getter
    @Setter
    public static class ItemOption {
        private long itemId;
        private String itemCd;
        private String itemNm;
    }

    @Getter
    @Setter
    public static class RoutingOption {
        private long routingId;
        private String plantCd;
        private int altNo;
        private String rev;
        private String status;
        private LocalDate validFrom;
    }

    @Getter
    @Setter
    public static class CharOption {
        private long charId;
        private String charType;
        private String charNo;
        private String charNm;
        private String specialCls;
        /** 공정 특성의 공정 코드(제품 특성은 null) */
        private String procCd;
        /** PFMEA: 이 품목의 확정 DFMEA 기능에 붙은 특성(A5-2b D7 — 같은 id, 표시만) */
        private boolean inDfmea;
    }

    /** 같은 품목 확정 DFMEA 의 고장 형태 — PFMEA 고장 영향 제안(A5-2b D7) */
    @Getter
    @Setter
    public static class DfmeaSource {
        private long failId;
        private String failText;
        private long fmeaId;
        private String fmeaNo;
        private int rev;
        private String nodeNm;
    }

    /** BOM 다시 잇기 — 자품목을 가리키는 하위 시스템 하나(matched = 새 BOM 에도 그 자품목 줄이 있음) */
    public record BomRelinkNode(long nodeId, String nodeNm, String itemCd, boolean matched) {
    }

    /** BOM 다시 잇기 미리보기·결과 — added = 새 BOM 줄 중 구조에 없는 자품목(적용 때 하위 시스템으로 더한다) */
    public record BomRelinkPlan(long bomId, String bomText, List<BomRelinkNode> nodes, List<BomChild> added, int matched, int unmatched,
            int rowVer) {
    }

    public record BomRelinkForm(Long bomId, Integer rowVer) {
    }

    @Getter
    @Setter
    public static class DefectOption {
        private long defectId;
        private String defectCd;
        private String defectNm;
    }

    @Getter
    @Setter
    public static class ProjOption {
        private long projId;
        private String projNo;
        private String projNm;
    }

    /** 서버 안에서 쓰는 머리 값(검증 뒤) */
    public record FmeaValue(long itemId, Long routingId, Long projId, String title, String scope, String team, String drawingRev,
            Long apSetId, String fmeaType, Long bomId, Long docRevId) {
    }

    /** DFMEA 대상 BOM 리비전(확정만, D5) */
    @Getter
    @Setter
    public static class BomOption {
        private long bomId;
        private int altNo;
        private String rev;
        private LocalDate validFrom;
    }

    /** BOM 한 줄의 자품목(끌어오기 D6) */
    @Getter
    @Setter
    public static class BomChild {
        private long itemId;
        private String itemCd;
        private String itemNm;
        private int lineNo;
    }
}
