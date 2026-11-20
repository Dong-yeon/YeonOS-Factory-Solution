package io.smarthappy.module.qms;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import io.smarthappy.module.master.bom.BomService;
import io.smarthappy.module.qms.DocDtos.ActionForm;
import io.smarthappy.module.qms.DocDtos.Approval;
import io.smarthappy.module.qms.DocDtos.AprLineRow;
import io.smarthappy.module.qms.DocDtos.DocDetail;
import io.smarthappy.module.qms.DocDtos.DocForm;
import io.smarthappy.module.qms.DocDtos.DocRow;
import io.smarthappy.module.qms.DocDtos.DocSearch;
import io.smarthappy.module.qms.DocDtos.DocSort;
import io.smarthappy.module.qms.DocDtos.LineItem;
import io.smarthappy.module.qms.DocDtos.NewRevForm;
import io.smarthappy.module.qms.DocDtos.Ref;
import io.smarthappy.module.qms.DocDtos.RevForm;
import io.smarthappy.module.qms.DocDtos.RevRow;
import io.smarthappy.module.qms.DocDtos.SubmitForm;
import io.smarthappy.module.qms.DocDtos.TargetForm;
import io.smarthappy.module.qms.DocDtos.TargetValue;
import io.smarthappy.platform.approval.ApprovalService;
import io.smarthappy.platform.approval.ApprovalService.LineSpec;
import io.smarthappy.platform.approval.ApprovalService.LineType;
import io.smarthappy.platform.code.CodeService;
import io.smarthappy.platform.common.db.PgErrors;
import io.smarthappy.platform.common.query.PageQuery;
import io.smarthappy.platform.common.web.BusinessException;
import io.smarthappy.platform.common.web.FieldErrors;
import io.smarthappy.platform.common.web.HbErrorCode;
import io.smarthappy.platform.file.FileService;
import io.smarthappy.platform.grid.ColumnSpec;
import io.smarthappy.platform.messaging.InboxService;
import io.smarthappy.platform.numbering.NumberingService;
import io.smarthappy.platform.policy.Policies;
import io.smarthappy.platform.policy.PolicyService;
import io.smarthappy.platform.security.CurrentUser;
import io.smarthappy.platform.security.LoginUser;
import io.smarthappy.platform.user.UserDirectory;
import io.smarthappy.platform.user.UserOption;
import io.smarthappy.platform.common.text.VisibleText;
import lombok.RequiredArgsConstructor;

/**
 * 관리문서 (qms-doc) — P3 QMS Q3, docs/29 D7·PLM D8.
 *
 * - 문서 = 번호(비우면 채번)·유형(고정)·이름·적용 대상(한 벌). 만들 때 첫 리비전(A0, 작성 중)을 함께 만든다
 * - 리비전: 작성 중에만 내용·첨부 변경, 작성 중(결재 중 포함)은 문서마다 하나. 새 리비전 = 마지막 리비전 복사 + 첨부 복사(FileService.copyAll)
 * - 배포: 정책 QMS_DOC_APPROVAL = Y 면 결재(QMS_DOC — 승인 = 배포, 반려·회수 = 작성 중), N 이면 APPROVE 권한으로 바로.
 *   배포가 이전 배포본을 폐기하고 첨부에 잠금 표시(DocReleaser). 폐기 = DELETE 권한(배포본 또는 작성 중 버리기)
 * - 다른 모듈: PLM ECO 적용은 {@link #releaseFromChange(long)}, 리비전 조회는 {@link #revision(long)} — 표를 직접 고치지 않는다
 */
@Service
@RequiredArgsConstructor
public class DocService {

    static final String PROG = "qms-doc";
    static final String RULE = "QMS_DOC";
    static final Pattern DOC_NO = Pattern.compile("^[A-Z0-9][A-Z0-9._-]{0,29}$");
    /** 문서 유형 = 공통코드 DOC_TYPE(시스템 7종 + 회사 코드, docs/68). 연결 분기(INSP_STD·DRAWING·ECO 대상)는 시스템 코드만 */
    static final String TYPE_GRP = "DOC_TYPE";
    static final Set<String> TARGET_TYPES = Set.of("ITEM", "ITEM_CLASS", "PROC");
    static final List<String> DEFAULT_SORT = List.of("docNo,asc");
    static final int MAX_TARGETS = 200;

    static final List<ColumnSpec> COLUMNS = List.of(
            ColumnSpec.text("docNo", "qms.doc.col.docNo", 130).withSort().withPriority(1),
            ColumnSpec.code("docType", "qms.doc.col.docType", "DOC_TYPE", 100).withSort().withPriority(1),
            ColumnSpec.text("docNm", "qms.doc.col.docNm", 200).withSort().withPriority(1).withFlex().withMinWidth(120),
            ColumnSpec.text("releasedRev", "qms.doc.col.released", 80).withPriority(1),
            ColumnSpec.of("releasedAt", "qms.doc.col.releasedAt", ColumnSpec.ColumnType.DATETIME, 130).withSort().withPriority(3),
            ColumnSpec.text("openRev", "qms.doc.col.open", 70).withPriority(2),
            ColumnSpec.code("openStatus", "qms.doc.rev.col.status", "DOC_REV_STATUS", 80).withPriority(2),
            ColumnSpec.text("targetText", "qms.doc.col.targets", 160).withPriority(3).withFlex().withMinWidth(100),
            ColumnSpec.of("useYn", "qms.doc.col.useYn", ColumnSpec.ColumnType.YN, 60).withPriority(3));

    private final DocMapper mapper;
    private final UserDirectory users;
    private final DocReleaser releaser;
    private final CodeService codes;
    private final ApprovalService approvals;
    private final FileService files;
    private final PolicyService policies;
    private final InboxService inbox;
    private final NumberingService numbering;

    public record Page(List<DocRow> list, Long total) {
    }

    // ------------------------------------------------------------------ 조회

    @Transactional(readOnly = true)
    public Page list(String keyword, String docType, Boolean withUnused, Integer page, Integer size, List<String> sort, Boolean total) {
        PageQuery pq = PageQuery.parse(page, size, sort, total, DocSort.values(), policies.intValue(Policies.GRID_PAGE_SIZE_MAX),
                DEFAULT_SORT);
        String kw = VisibleText.isBlank(keyword) ? null : keyword.strip();
        String type = VisibleText.isBlank(docType) ? null : docType.strip();
        if ((kw != null && kw.length() > 100) || (type != null && !CodeService.isCodeFormat(type))) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        DocSearch q = new DocSearch(kw == null ? null : "%" + BomService.likeEscape(kw) + "%", type, Boolean.TRUE.equals(withUnused), pq);
        return new Page(mapper.findPage(q), pq.isWithTotal() ? mapper.count(q) : null);
    }

    public List<ColumnSpec> columns() {
        return COLUMNS;
    }

    @Transactional(readOnly = true)
    public DocDetail detail(long docId) {
        LoginUser me = SamplingService.me();
        DocRow doc = find(docId);
        List<RevRow> revs = mapper.findRevs(docId);
        RevRow open = revs.stream().filter(r -> "IN_APPROVAL".equals(r.getStatus())).findFirst().orElse(null);
        Approval apr = new Approval(null, null, false, false, List.of());
        if (open != null && open.getAprId() != null) {
            List<AprLineRow> lines = mapper.findAprLines(open.getAprId());
            boolean pending = "PENDING".equals(open.getAprStatus());
            boolean myTurn = pending && lines.stream().anyMatch(l -> "CURRENT".equals(l.getStatus()) && l.getApproverId() == me.userId());
            boolean anyActed = lines.stream().anyMatch(l -> "APPROVED".equals(l.getStatus()));
            boolean canWithdraw = pending && !anyActed && Objects.equals(open.getAprRequesterId(), me.userId());
            apr = new Approval(open.getAprId(), open.getAprStatus(), myTurn, canWithdraw, lines);
        }
        return new DocDetail(doc, mapper.findTargets(docId), revs, apr, policies.bool(Policies.QMS_DOC_APPROVAL));
    }

    @Transactional(readOnly = true)
    public List<UserOption> users() {
        return users.activeUsers();   // 회사 사용자만(공통 사용자 고르기, docs/42 15장)
    }

    /** 다른 모듈(PLM 도면·검사계획)이 리비전 하나를 확인할 때 — 없거나 다른 회사면 NOT_FOUND */
    @Transactional(readOnly = true)
    public RevRow revision(long docRevId) {
        RevRow r = mapper.findRev(docRevId);
        if (r == null) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        return r;
    }

    // ------------------------------------------------------------------ 문서 저장

    @Transactional
    public long create(DocForm in) {
        LoginUser me = SamplingService.me();
        inbox.claimRequestKey("QMS_DOC_CREATE");
        if (in == null) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        FieldErrors e = FieldErrors.create();
        String no = VisibleText.isBlank(in.docNo()) ? null : in.docNo().strip().toUpperCase(Locale.ROOT);
        if (no != null && !DOC_NO.matcher(no).matches()) {
            e.add("docNo", "FORMAT");
        }
        String type = VisibleText.isBlank(in.docType()) ? null : in.docType().strip();
        if (type == null) {
            e.add("docType", "REQUIRED");
        } else if (!codes.usable(TYPE_GRP, type)) {
            e.add("docType", "CODE");
        }
        String nm = VisibleText.reason(in.docNm(), "docNm", 1, 200, e);
        String useYn = yn(in.useYn(), e);
        String remark = VisibleText.optional(in.remark(), "remark", 500, e);
        List<TargetValue> targets = targets(in.targets(), List.of(), e);
        e.throwIfAny();
        if (no == null) {
            no = numbering.nextOrDefault(RULE, "관리문서 번호", "DOC{yyMM}-{seq:4}", "MONTH");
        }
        long id;
        try {
            id = mapper.insertDoc(no, type, nm, useYn, remark, me.loginId());
        } catch (DuplicateKeyException ex) {
            throw SamplingService.fieldError("docNo", "DUPLICATE");
        }
        if (!targets.isEmpty()) {
            mapper.insertTargets(id, targets, me.loginId());
        }
        mapper.insertRev(id, "A0", null, null, me.loginId());
        return id;
    }

    /** 이름·사용·비고 + 대상 한 벌(번호·유형은 고정). 그대로 둔 대상은 사용 중지돼 있어도 남는다 */
    @Transactional
    public void update(long docId, DocForm in) {
        LoginUser me = SamplingService.me();
        if (in == null || in.rowVer() == null) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        DocRow cur = find(docId);
        FieldErrors e = FieldErrors.create();
        if (!VisibleText.isBlank(in.docNo()) && !in.docNo().strip().toUpperCase(Locale.ROOT).equals(cur.getDocNo())) {
            e.add("docNo", "FIXED");
        }
        if (!VisibleText.isBlank(in.docType()) && !in.docType().strip().equals(cur.getDocType())) {
            e.add("docType", "FIXED");
        }
        String nm = VisibleText.reason(in.docNm(), "docNm", 1, 200, e);
        String useYn = yn(in.useYn(), e);
        String remark = VisibleText.optional(in.remark(), "remark", 500, e);
        List<TargetValue> keep = mapper.findTargets(docId).stream()
                .map(t -> new TargetValue(t.getTargetType(), "ITEM".equals(t.getTargetType()) ? t.getRefId() : null,
                        "ITEM_CLASS".equals(t.getTargetType()) ? t.getRefId() : null, "PROC".equals(t.getTargetType()) ? t.getRefId() : null))
                .toList();
        List<TargetValue> targets = targets(in.targets(), keep, e);
        e.throwIfAny();
        if (mapper.updateDoc(docId, nm, useYn, remark, in.rowVer(), me.loginId()) == 0) {
            throw new BusinessException(HbErrorCode.CONFLICT);
        }
        mapper.deleteTargets(docId);
        if (!targets.isEmpty()) {
            mapper.insertTargets(docId, targets, me.loginId());
        }
    }

    /** 대상 한 벌 검증 — 코드로 찾고, 새로 거는 대상은 사용 중만(지금 걸려 있던 대상은 그대로 허용). 행 키 = 배열 순번 */
    private List<TargetValue> targets(List<TargetForm> raw, List<TargetValue> current, FieldErrors e) {
        List<TargetForm> in = raw == null ? List.of() : raw;
        if (in.size() > MAX_TARGETS || in.stream().anyMatch(Objects::isNull)) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        List<TargetValue> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < in.size(); i++) {
            TargetForm t = in.get(i);
            FieldErrors.RowScope row = e.row(String.valueOf(i));
            String type = VisibleText.isBlank(t.targetType()) ? null : t.targetType().strip();
            String cd = VisibleText.isBlank(t.targetCd()) ? null : t.targetCd().strip().toUpperCase(Locale.ROOT);
            if (type == null || !TARGET_TYPES.contains(type)) {
                row.add("targetType", type == null ? "REQUIRED" : "CODE");
                continue;
            }
            if (cd == null) {
                row.add("targetCd", "REQUIRED");
                continue;
            }
            Ref ref = switch (type) {
                case "ITEM" -> mapper.findItem(cd);
                case "ITEM_CLASS" -> mapper.findClass(cd);
                default -> mapper.findProc(cd);
            };
            if (ref == null) {
                row.add("targetCd", "NO_SUCH_CODE");
                continue;
            }
            TargetValue v = new TargetValue(type, "ITEM".equals(type) ? ref.getId() : null, "ITEM_CLASS".equals(type) ? ref.getId() : null,
                    "PROC".equals(type) ? ref.getId() : null);
            if (!"Y".equals(ref.getUseYn()) && !current.contains(v)) {
                row.add("targetCd", "INACTIVE");
                continue;
            }
            if (!seen.add(type + ":" + ref.getId())) {
                row.add("targetCd", "DUPLICATE");
                continue;
            }
            out.add(v);
        }
        return out;
    }

    // ------------------------------------------------------------------ 리비전

    /** 새 리비전 — 작성 중(결재 중 포함)이 없을 때, 마지막 리비전의 변경 요약은 비우고 첨부를 복사 */
    @Transactional
    public long newRevision(long docId, NewRevForm in) {
        LoginUser me = SamplingService.me();
        inbox.claimRequestKey("QMS_DOC_NEWREV");
        find(docId);
        mapper.lockDoc(docId);
        List<RevRow> revs = mapper.findRevs(docId);
        if (revs.stream().anyMatch(r -> "DRAFT".equals(r.getStatus()) || "IN_APPROVAL".equals(r.getStatus()))) {
            throw new BusinessException(HbErrorCode.QMS_DOC_OPEN_EXISTS);
        }
        FieldErrors e = FieldErrors.create();
        String rev = VisibleText.isBlank(in == null ? null : in.rev()) ? null : in.rev().strip().toUpperCase(Locale.ROOT);
        if (rev != null && !BomService.REV.matcher(rev).matches()) {
            e.add("rev", "FORMAT");
        }
        String summary = VisibleText.optional(in == null ? null : in.changeSummary(), "changeSummary", 1000, e);
        e.throwIfAny();
        // 복사 원본 = 배포본, 없으면 가장 최근 리비전
        RevRow src = revs.stream().filter(r -> "RELEASED".equals(r.getStatus())).findFirst().orElse(revs.isEmpty() ? null : revs.get(0));
        long id;
        try {
            id = mapper.insertRev(docId, rev != null ? rev : nextRev(docId), src == null ? null : src.getDocRevId(), summary, me.loginId());
        } catch (DuplicateKeyException ex) {
            if (rev != null && PgErrors.constraint(ex).filter("uq_mst_doc_rev"::equals).isPresent()) {
                throw SamplingService.fieldError("rev", "DUPLICATE");
            }
            throw new BusinessException(HbErrorCode.QMS_DOC_OPEN_EXISTS);
        }
        if (src != null) {
            files.copyAll(PROG, String.valueOf(src.getDocRevId()), String.valueOf(id));
        }
        return id;
    }

    @Transactional
    public void updateRevision(long revId, RevForm in) {
        LoginUser me = SamplingService.me();
        if (in == null || in.rowVer() == null) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        RevRow cur = lockedRev(revId);
        if (!"DRAFT".equals(cur.getStatus())) {
            throw new BusinessException(HbErrorCode.QMS_DOC_STATE);
        }
        FieldErrors e = FieldErrors.create();
        String rev = VisibleText.isBlank(in.rev()) ? cur.getRev() : in.rev().strip().toUpperCase(Locale.ROOT);
        if (!BomService.REV.matcher(rev).matches()) {
            e.add("rev", "FORMAT");
        }
        String summary = VisibleText.optional(in.changeSummary(), "changeSummary", 1000, e);
        e.throwIfAny();
        try {
            if (mapper.updateRev(revId, rev, summary, in.rowVer(), me.loginId()) == 0) {
                throw new BusinessException(HbErrorCode.CONFLICT);
            }
        } catch (DuplicateKeyException ex) {
            throw SamplingService.fieldError("rev", "DUPLICATE");
        }
    }

    /** 결재 상신(정책 Y) — 결재선은 결재 엔진이 검증. 승인 = 배포(DocApprovalDocType) */
    @Transactional
    public long submit(long revId, SubmitForm in) {
        LoginUser me = SamplingService.me();
        inbox.claimRequestKey("QMS_DOC_SUBMIT");
        if (in == null || in.rowVer() == null) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        RevRow cur = lockedRev(revId);
        if (cur.getRowVer() != in.rowVer()) {
            throw new BusinessException(HbErrorCode.CONFLICT);
        }
        if (!"DRAFT".equals(cur.getStatus())) {
            throw new BusinessException(HbErrorCode.QMS_DOC_STATE);
        }
        List<LineSpec> lines = new ArrayList<>();
        for (LineItem l : in.lines() == null ? List.<LineItem>of() : in.lines()) {
            LineType type;
            try {
                type = l == null || l.userId() == null ? null : LineType.valueOf(l.lineType() == null ? "APPROVE" : l.lineType());
            } catch (IllegalArgumentException ex) {
                type = null;
            }
            if (type == null) {
                throw new BusinessException(HbErrorCode.INVALID_VALUE);
            }
            lines.add(new LineSpec(l.userId(), type));
        }
        String title = VisibleText.isBlank(in.title()) ? cur.getDocNo() + " / " + cur.getRev() + " " + cur.getDocNm() : in.title().strip();
        long aprId = approvals.submit(DocApprovalDocType.DOC_TYPE, String.valueOf(revId), title.length() > 200 ? title.substring(0, 200) : title,
                lines);
        if (mapper.submitRev(revId, aprId, in.rowVer(), me.loginId()) == 0) {
            throw new BusinessException(HbErrorCode.CONFLICT);
        }
        return aprId;
    }

    /** 결재 없이 배포(정책 N, APPROVE 권한) */
    @Transactional
    public void release(long revId, ActionForm in) {
        LoginUser me = SamplingService.me();
        int ver = ver(in);
        RevRow cur = lockedRev(revId);
        if (policies.bool(Policies.QMS_DOC_APPROVAL)) {
            throw new BusinessException(HbErrorCode.QMS_DOC_APPROVAL_REQUIRED);
        }
        if (cur.getRowVer() != ver) {
            throw new BusinessException(HbErrorCode.CONFLICT);
        }
        if (!"DRAFT".equals(cur.getStatus())) {
            throw new BusinessException(HbErrorCode.QMS_DOC_STATE);
        }
        releaser.release(revId, me.loginId());
    }

    /** 폐기 — 배포본(현행 없음이 된다) 또는 작성 중 버리기. 결재 중은 회수 뒤에 */
    @Transactional
    public void obsolete(long revId, ActionForm in) {
        LoginUser me = SamplingService.me();
        int ver = ver(in);
        RevRow cur = lockedRev(revId);
        if (!"DRAFT".equals(cur.getStatus()) && !"RELEASED".equals(cur.getStatus())) {
            throw new BusinessException(HbErrorCode.QMS_DOC_STATE);
        }
        if (mapper.obsoleteRev(revId, ver, me.loginId()) == 0) {
            throw new BusinessException(HbErrorCode.CONFLICT);
        }
    }

    /**
     * PLM ECO 적용(docs/39 D8) — ECO 결재가 이미 끝났으므로 문서 결재 없이 작성 중 리비전을 배포한다. 부르는 쪽 업무 트랜잭션 안에서만.
     * 결재 중 리비전은 배포하지 않는다(문서 결재와 ECO 결재가 겹침 — 409)
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void releaseFromChange(long docRevId) {
        String by = CurrentUser.get().map(LoginUser::loginId).orElse("SYSTEM");
        RevRow cur = lockedRev(docRevId);
        if (!"DRAFT".equals(cur.getStatus())) {
            throw new BusinessException(HbErrorCode.QMS_DOC_STATE);
        }
        releaser.release(docRevId, by);
    }

    // ------------------------------------------------------------------ 쓰기 계약 DocPort (docs/29_P3 20.2)

    static final java.util.regex.Pattern SOURCE_KEY = java.util.regex.Pattern.compile("^[A-Za-z0-9][A-Za-z0-9:._-]{0,99}$");

    /**
     * 다른 모듈이 산출물을 작성 중 리비전으로 올린다 — 부르는 쪽 트랜잭션 안(MANDATORY), 거부는 예외.
     * 순서: 키 잠금(advisory) → 같은 키 리비전이 있으면 그대로 → 문서(새로 채번 또는 번호로 찾아 머리 FOR UPDATE) → 작성 중·결재 중 리비전 있으면 거부
     * → 리비전 INSERT(DRAFT, source_key) → 대상 품목 → 파일 attachAs(qms-doc × 리비전). 결재·배포는 하지 않는다
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public io.smarthappy.module.qms.link.DocPort.DocRevRef publishDraft(io.smarthappy.module.qms.link.DocPort.DocDraft in) {
        LoginUser me = SamplingService.me();
        if (in == null) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        FieldErrors e = FieldErrors.create();
        String key = VisibleText.isBlank(in.sourceKey()) ? null : in.sourceKey().strip();
        if (key == null) {
            e.add("sourceKey", "REQUIRED");
        } else if (!SOURCE_KEY.matcher(key).matches()) {
            e.add("sourceKey", "FORMAT");
        }
        String no = VisibleText.isBlank(in.docNo()) ? null : in.docNo().strip().toUpperCase(Locale.ROOT);
        String type = VisibleText.isBlank(in.docType()) ? null : in.docType().strip();
        if (type != null && !codes.usable(TYPE_GRP, type)) {
            e.add("docType", "CODE");
        }
        String summary = VisibleText.optional(in.changeSummary(), "changeSummary", 1000, e);
        List<io.smarthappy.module.qms.link.DocPort.DocFile> fs = in.files() == null ? List.of() : in.files();
        if (fs.size() > io.smarthappy.module.qms.link.DocPort.MAX_FILES) {
            e.add("files", "TOO_MANY", io.smarthappy.module.qms.link.DocPort.MAX_FILES);
        } else {
            for (int i = 0; i < fs.size(); i++) {
                io.smarthappy.module.qms.link.DocPort.DocFile f = fs.get(i);
                if (f == null || VisibleText.isBlank(f.fileName()) || f.content() == null) {
                    e.row(String.valueOf(i)).add("file", "REQUIRED");
                }
            }
        }
        e.throwIfAny();
        mapper.lockSourceKey(key);
        RevRow done = mapper.findRevBySource(key);
        if (done != null) {
            return new io.smarthappy.module.qms.link.DocPort.DocRevRef(done.getDocId(), done.getDocNo(), done.getDocRevId(), done.getRev(),
                    done.getStatus(), false);
        }
        Ref item = null;
        if (in.itemId() != null) {
            item = mapper.findItemById(in.itemId());
            if (item == null || !"Y".equals(item.getUseYn())) {
                throw SamplingService.fieldError("itemId", item == null ? "NO_SUCH_CODE" : "INACTIVE");
            }
        }
        long docId;
        String docNo;
        if (no == null) {
            FieldErrors n = FieldErrors.create();
            if (type == null) {
                n.add("docType", "REQUIRED");
            }
            String nm = VisibleText.reason(in.docNm(), "docNm", 1, 200, n);
            n.throwIfAny();
            docNo = numbering.nextOrDefault(RULE, "관리문서 번호", "DOC{yyMM}-{seq:4}", "MONTH");
            docId = mapper.insertDoc(docNo, type, nm, "Y", null, me.loginId());
            if (item != null) {
                mapper.insertTargets(docId, List.of(new TargetValue("ITEM", item.getId(), null, null)), me.loginId());
            }
        } else {
            DocRow d = mapper.findDocByNo(no);
            if (d == null) {
                throw SamplingService.fieldError("docNo", "NOT_FOUND");
            }
            if (!"Y".equals(d.getUseYn())) {
                throw SamplingService.fieldError("docNo", "INACTIVE");
            }
            if (type != null && !type.equals(d.getDocType())) {
                throw SamplingService.fieldError("docType", "FIXED");
            }
            docId = d.getDocId();
            docNo = d.getDocNo();
            mapper.lockDoc(docId);
            if (mapper.findRevs(docId).stream().anyMatch(r -> "DRAFT".equals(r.getStatus()) || "IN_APPROVAL".equals(r.getStatus()))) {
                throw new BusinessException(HbErrorCode.QMS_DOC_OPEN_EXISTS);
            }
            final long itemId = item == null ? 0 : item.getId();
            if (item != null && mapper.findTargets(docId).stream()
                    .noneMatch(t -> "ITEM".equals(t.getTargetType()) && t.getRefId() == itemId)) {
                mapper.insertTargets(docId, List.of(new TargetValue("ITEM", itemId, null, null)), me.loginId());
            }
        }
        String rev = mapper.findRevs(docId).isEmpty() ? "A0" : nextRev(docId);
        long revId = mapper.insertRevSourced(docId, rev, summary, key, me.loginId());
        for (io.smarthappy.module.qms.link.DocPort.DocFile f : fs) {
            files.attachAs(PROG, String.valueOf(revId), f.fileName(), f.content().length, new java.io.ByteArrayInputStream(f.content()),
                    me.loginId());
        }
        return new io.smarthappy.module.qms.link.DocPort.DocRevRef(docId, docNo, revId, rev, "DRAFT", true);
    }

    // ------------------------------------------------------------------ 공통

    String nextRev(long docId) {
        String max = mapper.findRevCodes(docId).stream().filter(r -> BomService.AUTO_REV.matcher(r).matches())
                .max(Comparator.comparingInt(String::length).thenComparing(Comparator.naturalOrder())).orElse(null);
        return max == null ? "A0" : BomService.nextAutoRev(max);
    }

    private RevRow lockedRev(long revId) {
        if (mapper.lockRev(revId) == null) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        return mapper.findRev(revId);
    }

    private DocRow find(long docId) {
        DocRow d = mapper.findDoc(docId);
        if (d == null) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        return d;
    }

    private static int ver(ActionForm in) {
        if (in == null || in.rowVer() == null) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        return in.rowVer();
    }

    private static String yn(String v, FieldErrors e) {
        String s = VisibleText.isBlank(v) ? "Y" : v.strip();
        if (!"Y".equals(s) && !"N".equals(s)) {
            e.add("useYn", "CODE");
        }
        return s;
    }
}
