package io.smarthappy.module.pms;

import static io.smarthappy.platform.common.text.VisibleText.isBlank;
import static io.smarthappy.module.pms.PmsInputs.date;
import static io.smarthappy.module.pms.PmsInputs.fieldError;
import static io.smarthappy.module.pms.PmsInputs.me;
import static io.smarthappy.module.pms.PmsInputs.visible;
import static io.smarthappy.module.pms.PmsInputs.upper;

import java.io.IOException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import io.smarthappy.module.pms.GateDtos.DecideForm;
import io.smarthappy.module.pms.GateDtos.DelivForm;
import io.smarthappy.module.pms.GateDtos.DelivRow;
import io.smarthappy.module.pms.GateDtos.DelivView;
import io.smarthappy.module.pms.GateDtos.DocLinkForm;
import io.smarthappy.module.pms.GateDtos.DocLinkResult;
import io.smarthappy.module.pms.GateDtos.HistRow;
import io.smarthappy.module.pms.GateDtos.PhaseReady;
import io.smarthappy.module.pms.GateDtos.PpapLinkForm;
import io.smarthappy.module.pms.PmsDtos.ProjectRow;
import io.smarthappy.module.pms.PmsDtos.TaskRow;
import io.smarthappy.module.pms.WbsTree.Placed;
import io.smarthappy.module.plm.link.EcoLookup;
import io.smarthappy.module.plm.link.EcoLookup.PpapView;
import io.smarthappy.module.apqp.link.ApqpLookup;
import io.smarthappy.module.qms.link.DocLookup;
import io.smarthappy.module.qms.link.DocLookup.DocRevView;
import io.smarthappy.platform.auth.Action;
import io.smarthappy.platform.auth.PermissionService;
import io.smarthappy.platform.common.db.PgErrors;
import io.smarthappy.platform.common.web.BusinessException;
import io.smarthappy.platform.common.web.FieldErrors;
import io.smarthappy.platform.common.web.HbErrorCode;
import io.smarthappy.platform.excel.ExcelExportService;
import io.smarthappy.platform.excel.ExcelExportService.ExportJob;
import io.smarthappy.platform.file.FileService;
import io.smarthappy.platform.file.FileService.FileInfo;
import io.smarthappy.platform.grid.ColumnSpec;
import io.smarthappy.platform.grid.ColumnSpec.ColumnType;
import io.smarthappy.platform.security.LoginUser;
import io.smarthappy.platform.time.BizClock;
import io.smarthappy.platform.user.UserDirectory;
import io.smarthappy.platform.user.UserOption;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;

/**
 * 산출물 현황 (pms-deliverable) — docs/32 PM3, D5·D6.
 * - 칸 추가·수정·삭제 = 프로젝트를 관리하는 사람(PM·PMO), 제출 = 산출물 담당·작업 담당·PM, 승인·반려·면제 = 이 화면 APPROVE
 * - 제출 = 파일 1개(FileService, 대상 pms-deliverable / D{id}) — 제출마다 새 파일, 승인본은 그 자리에서 바뀌지 않는다(새 제출).
 *   제출한 파일은 바로 잠근다(sys_file.locked_yn), 올리기 가능 여부는 DeliverableFileLock
 * - 같은 프로젝트의 산출물 변경은 머리 행 잠금으로 줄 세운다(게이트 상신의 준비율 확인과 엇갈리지 않게)
 */
@Service
@RequiredArgsConstructor
public class DeliverableService {

    static final String PROG = "pms-deliverable";
    static final String FILE_TARGET = "pms-deliverable";
    static final String DOC_REF = "QMS_DOC_REV";
    static final String PPAP_REF = "PLM_PPAP";
    /** PFMEA·관리계획서 리비전(docs/41 A3-1) — 한 번이라도 확정 = 승인(L2), APQP 계약 ApqpLookup 으로만 읽는다 */
    static final String FMEA_REF = "APQP_FMEA";
    static final String CP_REF = "APQP_CP";
    /** PLM 변경지시 화면 — PPAP 연결은 이 화면 SEARCH 도 있어야(D20) */
    static final String ECO_PROG = "plm-eco";
    /** QMS 관리문서 화면 — 문서 연결은 이 화면 SEARCH 도 있어야(D14) */
    static final String DOC_PROG = "qms-doc";
    static final String FMEA_PROG = "apqp-fmea";
    static final String CP_PROG = "apqp-cp";
    static final Set<String> READY = Set.of("APPROVED", "WAIVED");
    private static final Set<String> OPEN_DELIV = Set.of("NONE", "REJECTED");

    static final List<ColumnSpec> COLUMNS = List.of(
            ColumnSpec.text("phaseNm", "pms.col.phase", 160),
            ColumnSpec.text("wbsNo", "pms.col.wbs", 70),
            ColumnSpec.text("taskNm", "pms.col.taskNm", 200),
            ColumnSpec.text("delivNm", "pms.col.delivNm", 200),
            ColumnSpec.of("requiredYn", "pms.col.required", ColumnType.YN, 60),
            ColumnSpec.text("ownerNm", "pms.col.owner", 100),
            ColumnSpec.of("due", "pms.col.due", ColumnType.DATE, 100),
            ColumnSpec.code("status", "pms.col.delivStatus", "PMS_DELIV_STATUS", 90),
            ColumnSpec.text("fileName", "pms.col.file", 200),
            ColumnSpec.of("submittedAt", "pms.col.submittedAt", ColumnType.DATETIME, 140),
            ColumnSpec.text("decidedBy", "pms.col.decidedBy", 100),
            ColumnSpec.of("decidedAt", "pms.col.decidedAt", ColumnType.DATETIME, 140),
            ColumnSpec.text("waiveReason", "pms.col.waiveReason", 200));

    private final GateMapper gm;
    private final PmsMapper mapper;
    private final UserDirectory users;
    private final TaskService tasks;
    private final PermissionService permission;
    private final FileService files;
    private final ExcelExportService excel;
    private final BizClock clock;
    private final PmsDays days;
    private final PmsNotifier notifier;
    /** QMS 문서는 계약(DocLookup — 예외 없음, 없으면 빈 값)으로만 */
    private final org.springframework.beans.factory.ObjectProvider<DocLookup> docs;
    private final DeliverableDocSync docSync;
    private final org.springframework.beans.factory.ObjectProvider<EcoLookup> ecos;
    private final org.springframework.beans.factory.ObjectProvider<ApqpLookup> apqps;

    // ------------------------------------------------------------------ 조회

    @Transactional(readOnly = true)
    public DelivView view(long projId) {
        LocalDate today = clock.today();
        LocalDate lateRef = days.lateRef(today);
        ProjectRow p = project(projId, lateRef);
        LoginUser me = me();
        boolean manage = tasks.canManage(p, me);
        List<Placed<TaskRow>> placed = tasks.placed(projId);
        List<DelivRow> rows = decorate(placed, gm.findDeliverables(projId), lateRef);
        rows.stream().filter(d -> DOC_REF.equals(d.getRefType())).forEach(this::fillDoc);
        rows.stream().filter(d -> FMEA_REF.equals(d.getRefType()) || CP_REF.equals(d.getRefType())).forEach(this::fillApqp);
        if (rows.stream().anyMatch(d -> PPAP_REF.equals(d.getRefType()))) {
            fillPpap(rows, p);
        }
        boolean open = TaskService.OPEN.contains(p.getStatus());
        rows.forEach(d -> d.setCanSubmit(open && canSubmit(d, me, manage)));
        p.setCanManage(manage);
        List<GateDtos.TaskOption> opts = placed.stream()
                .map(x -> new GateDtos.TaskOption(x.row().getTaskId(), x.row().getWbsNo(), x.row().getTaskNm(), x.row().getTaskType()))
                .toList();
        return new DelivView(p, rows, phases(placed, rows), today, manage && open,
                open && permission.actions(PROG).contains(Action.APPROVE), opts);
    }

    @Transactional(readOnly = true)
    public List<HistRow> history(long delivId) {
        if (gm.findDeliverableProjId(delivId) == null) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        List<HistRow> rows = gm.findHist(delivId);
        for (HistRow h : rows) {
            if (h.getPpapId() != null) {
                h.setDocLabel("PPAP");
            } else if (h.getFmeaId() != null || h.getCpId() != null) {
                ApqpLookup.RevView r = h.getFmeaId() != null ? ApqpLookup.of(apqps).rev(ApqpLookup.FMEA, h.getFmeaId()).orElse(null)
                        : ApqpLookup.of(apqps).rev(ApqpLookup.CP, h.getCpId()).orElse(null);
                h.setDocLabel(r == null ? null : r.no() + " rev " + r.rev());
            } else if (h.getDocRevId() != null) {
                DocRevView r = DocLookup.of(docs).rev(h.getDocRevId()).orElse(null);
                h.setDocLabel(r == null ? null : r.docNo() + " " + r.rev());
            }
        }
        return rows;
    }

    /** 작업 정보·WBS 번호·단계(뿌리 작업)·실제 기한·지연(기한 &lt; 지연 기준일 lateRef, PmsDays)을 채우고 WBS 순서로 정렬 */
    static List<DelivRow> decorate(List<Placed<TaskRow>> placed, List<DelivRow> rows, LocalDate lateRef) {
        Map<Long, Integer> order = new HashMap<>();
        Map<Long, TaskRow> root = new HashMap<>();
        Map<Long, TaskRow> byId = new HashMap<>();
        TaskRow curRoot = null;
        for (int i = 0; i < placed.size(); i++) {
            TaskRow t = placed.get(i).row();
            if (placed.get(i).lvl() == 1) {
                curRoot = t;
            }
            order.put(t.getTaskId(), i);
            root.put(t.getTaskId(), curRoot);
            byId.put(t.getTaskId(), t);
        }
        for (DelivRow d : rows) {
            TaskRow t = byId.get(d.getTaskId());
            TaskRow r = root.get(d.getTaskId());
            if (t != null) {
                d.setWbsNo(t.getWbsNo());
            }
            if (r != null) {
                d.setPhaseWbs(r.getWbsNo());
                d.setPhaseNm(r.getTaskNm());
            }
            d.setLate(d.getDue() != null && d.getDue().isBefore(lateRef) && OPEN_DELIV.contains(d.getStatus()));
        }
        List<DelivRow> out = new ArrayList<>(rows);
        out.sort(Comparator.comparingInt((DelivRow d) -> order.getOrDefault(d.getTaskId(), Integer.MAX_VALUE))
                .thenComparingInt(DelivRow::getSeq));
        return out;
    }

    /** 단계별 준비율 — 뿌리 작업마다(뿌리에 걸린 잎 작업 산출물은 그 작업이 단계) */
    static List<PhaseReady> phases(List<Placed<TaskRow>> placed, List<DelivRow> rows) {
        List<PhaseReady> out = new ArrayList<>();
        for (Placed<TaskRow> x : placed) {
            if (x.lvl() != 1) {
                continue;
            }
            String wbs = x.row().getWbsNo();
            int req = 0;
            int ready = 0;
            for (DelivRow d : rows) {
                if (wbs.equals(d.getPhaseWbs()) && "Y".equals(d.getRequiredYn())) {
                    req++;
                    if (READY.contains(d.getStatus())) {
                        ready++;
                    }
                }
            }
            out.add(new PhaseReady(x.row().getTaskId(), wbs, x.row().getTaskNm(), req, ready, req == 0 ? 100 : ready * 100 / req));
        }
        return out;
    }

    /**
     * 게이트가 보는 산출물: 게이트의 부모(단계) 아래 모든 작업, 뿌리에 있는 게이트면 WBS 순서로 게이트 앞(게이트 포함)의 모든 작업.
     * 나중 단계의 산출물은 이 게이트를 막지 않는다
     */
    static List<DelivRow> scope(List<Placed<TaskRow>> placed, long gateTaskId, List<DelivRow> rows) {
        int at = -1;
        for (int i = 0; i < placed.size(); i++) {
            if (placed.get(i).row().getTaskId() == gateTaskId) {
                at = i;
            }
        }
        if (at < 0) {
            return List.of();
        }
        Set<Long> ids = new HashSet<>();
        Long parent = placed.get(at).row().getParentId();
        if (parent == null) {
            for (int i = 0; i <= at; i++) {
                ids.add(placed.get(i).row().getTaskId());
            }
        } else {
            int p = -1;
            for (int i = 0; i < placed.size(); i++) {
                if (placed.get(i).row().getTaskId() == parent) {
                    p = i;
                }
            }
            int base = placed.get(p).lvl();
            ids.add(parent);
            for (int i = p + 1; i < placed.size() && placed.get(i).lvl() > base; i++) {
                ids.add(placed.get(i).row().getTaskId());
            }
        }
        return rows.stream().filter(d -> ids.contains(d.getTaskId())).toList();
    }

    static int readyCount(List<DelivRow> scope) {
        return (int) scope.stream().filter(d -> "Y".equals(d.getRequiredYn()) && READY.contains(d.getStatus())).count();
    }

    static int requiredCount(List<DelivRow> scope) {
        return (int) scope.stream().filter(d -> "Y".equals(d.getRequiredYn())).count();
    }

    // ------------------------------------------------------------------ 칸 추가·수정·삭제 (PM·PMO)

    @Transactional
    public long create(long projId, DelivForm in) {
        LoginUser me = me();
        tasks.openForEdit(projId, me, true);
        if (in == null || in.taskId() == null) {
            throw fieldError("taskId", "REQUIRED");
        }
        TaskRow t = mapper.findTask(in.taskId());
        if (t == null || t.getProjId() != projId) {
            throw fieldError("taskId", "NOT_FOUND");
        }
        Value v = validate(in);
        return translate(() -> gm.insertDeliverable(projId, t.getTaskId(), v.nm, v.required, v.form, v.owner, v.due, me.loginId()));
    }

    @Transactional
    public void update(long delivId, DelivForm in) {
        LoginUser me = me();
        DelivRow cur = lockAndGet(delivId, me, true);
        if (in == null || in.rowVer() == null) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        if (in.taskId() != null && in.taskId() != cur.getTaskId()) {
            throw fieldError("taskId", "FIXED");
        }
        Value v = validate(in);
        if (translate(() -> (long) gm.updateDeliverable(delivId, v.nm, v.required, v.form, v.owner, v.due, in.rowVer(), me.loginId())) == 0) {
            throw new BusinessException(HbErrorCode.CONFLICT);
        }
    }

    @Transactional
    public void delete(long delivId) {
        LoginUser me = me();
        DelivRow cur = lockAndGet(delivId, me, true);
        if (!"NONE".equals(cur.getStatus()) || cur.getSubCnt() > 0 || gm.countDeliverableHist(delivId) > 0) {
            throw fieldError("delivId", "PMS_DELIV_HAS_HISTORY");
        }
        gm.deleteDeliverable(delivId);
    }

    // ------------------------------------------------------------------ 제출 (담당·PM)

    /** 파일 1개 제출 — 시작 전·반려·승인(새 판)·면제·제출 중(바꿔 내기) 어디서든 새 파일로 SUBMITTED */
    @Transactional
    public long submit(long delivId, MultipartFile file, Integer rowVer, String note) {
        LoginUser me = me();
        DelivRow cur = lockAndGet(delivId, me, false);
        if (rowVer == null || rowVer != cur.getRowVer()) {
            throw new BusinessException(HbErrorCode.CONFLICT);
        }
        ProjectRow p = mapper.findProject(cur.getProjId(), days.lateRef());
        if (!canSubmit(cur, me, tasks.canManage(p, me))) {
            throw new BusinessException(HbErrorCode.PMS_NOT_EDITABLE);
        }
        // QMS 문서·PPAP 에 연결된 칸은 파일을 받지 않는다 — 원본은 그 모듈 하나(D6·D18)
        requireState(!linked(cur));
        FieldErrors e = FieldErrors.create();
        String n = visible(note, "note", 500, false, e);
        e.throwIfAny();
        FileInfo f = files.upload(FILE_TARGET, "D" + delivId, file);
        if (gm.submitDeliverable(delivId, f.fileId(), rowVer, me.loginId()) == 0) {
            throw new BusinessException(HbErrorCode.CONFLICT);
        }
        gm.insertHist(cur.getProjId(), delivId, "SUBMIT", f.fileId(), n, me.loginId());
        gm.lockFile(f.fileId(), me.loginId());
        return f.fileId();
    }

    // ------------------------------------------------------------------ 결정 (APPROVE)

    @Transactional
    public void decide(long delivId, DecideForm in) {
        LoginUser me = me();
        DelivRow cur = lockAndGet(delivId, me, false);
        if (!permission.actions(PROG).contains(Action.APPROVE)) {
            throw new BusinessException(HbErrorCode.NO_PERMISSION);
        }
        if (in == null || in.rowVer() == null) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        if (in.rowVer() != cur.getRowVer()) {
            throw new BusinessException(HbErrorCode.CONFLICT);
        }
        String action = upper(in.action());
        String from = cur.getStatus();
        String to;
        boolean noteRequired;
        if ("APPROVE".equals(action)) {
            to = "APPROVED";
            noteRequired = false;
            requireState(Set.of("SUBMITTED").contains(from));
        } else if ("REJECT".equals(action)) {
            to = "REJECTED";
            noteRequired = true;
            requireState("SUBMITTED".equals(from));
        } else if ("WAIVE".equals(action)) {
            to = "WAIVED";
            noteRequired = true;
            requireState(Set.of("NONE", "SUBMITTED", "REJECTED").contains(from));
        } else if ("UNWAIVE".equals(action)) {
            to = "NONE";
            noteRequired = false;
            // 제출물이 있던 칸의 면제는 풀어서 "시작 전" 이 될 수 없다 — 다시 제출하면 된다
            requireState("WAIVED".equals(from) && cur.getRefId() == null);
        } else {
            throw fieldError("action", "CODE");
        }
        // 문서 연결 칸의 승인은 QMS 배포에서 파생 — 손 승인·반려는 받지 않는다(면제는 된다)
        requireState(!linked(cur) || !("APPROVE".equals(action) || "REJECT".equals(action)));
        FieldErrors e = FieldErrors.create();
        String note = visible(in.note(), "note", 500, noteRequired, e);
        e.throwIfAny();
        if (gm.decideDeliverable(delivId, to, note, in.rowVer(), me.loginId()) == 0) {
            throw new BusinessException(HbErrorCode.CONFLICT);
        }
        gm.insertHist(cur.getProjId(), delivId, action, null, note, me.loginId());
        if ("REJECTED".equals(to)) {
            ProjectRow p = mapper.findProject(cur.getProjId(), days.lateRef());
            notifier.delivRejected(cur.getOwnerUserId() != null ? cur.getOwnerUserId() : cur.getTaskOwnerUserId(), cur.getSubmittedBy(),
                    delivId, in.rowVer() + 1, p.getProjNo(), cur.getDelivNm());
        }
    }

    // ------------------------------------------------------------------ QMS 문서 연결 (PM6, D6·D14·D15)

    /**
     * 산출물을 QMS 문서 리비전에 연결 — 담당·PM, 그리고 qms-doc SEARCH. 문서는 번호로 찾고(QMS 공개 메서드), 리비전을 비우면 배포본 → 없으면
     * 작성·결재 중 리비전. 배포본이면 바로 APPROVED(배포 = 승인), 아니면 SUBMITTED 로 두고 배포 뒤 반영. 폐기본·같은 리비전 다시 연결은 거부
     */
    @Transactional
    public DocLinkResult linkDoc(long delivId, DocLinkForm in) {
        LoginUser me = me();
        DelivRow cur = lockAndGet(delivId, me, false);
        if (in == null || in.rowVer() == null) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        if (in.rowVer() != cur.getRowVer()) {
            throw new BusinessException(HbErrorCode.CONFLICT);
        }
        ProjectRow p = mapper.findProject(cur.getProjId(), clock.today());
        if (!canSubmit(cur, me, tasks.canManage(p, me))) {
            throw new BusinessException(HbErrorCode.PMS_NOT_EDITABLE);
        }
        if (!permission.actions(DOC_PROG).contains(Action.SEARCH)) {
            throw new BusinessException(HbErrorCode.NO_PERMISSION);
        }
        FieldErrors e = FieldErrors.create();
        String docNo = visible(in.docNo(), "docNo", 30, true, e);
        String rev = visible(in.rev(), "rev", 4, false, e);
        String note = visible(in.note(), "note", 500, false, e);
        e.throwIfAny();
        DocRevView r = resolveRev(docNo, rev);
        if (DOC_REF.equals(cur.getRefType()) && cur.getRefId() != null && cur.getRefId() == r.docRevId()) {
            throw fieldError("docNo", "PMS_DOC_SAME");
        }
        if (translate(() -> (long) gm.linkDeliverableDoc(delivId, DOC_REF, r.docRevId(), in.rowVer(), me.loginId())) == 0) {
            throw new BusinessException(HbErrorCode.CONFLICT);
        }
        gm.insertHistDoc(cur.getProjId(), delivId, r.docRevId(), null, null, null, note, me.loginId());
        String status = "SUBMITTED";
        if ("RELEASED".equals(r.status())) {
            String n = DeliverableDocSync.releasedNote(r);
            gm.approveFromDoc(delivId, n, me.loginId());
            gm.insertHist(cur.getProjId(), delivId, "APPROVE", null, n, me.loginId());
            status = "APPROVED";
        }
        return new DocLinkResult(status, r.docRevId(), r.docNo(), r.rev(), r.status());
    }

    /**
     * 산출물을 프로젝트 ECO 의 PPAP 패키지에 연결(PM7 ②, D18·D19·D20) — 담당·PM, plm-eco SEARCH. PLM 계약 EcoLookup.ppap 으로 읽는다.
     * 고객 승인(APPROVED)이면 바로 승인, 아니면 제출 중 → 승인되면 반영(버튼·게이트 상신·결정 직전). 잠정 승인은 승인이 아니다
     */
    @Transactional
    public DocLinkResult linkPpap(long delivId, PpapLinkForm in) {
        LoginUser me = me();
        DelivRow cur = lockAndGet(delivId, me, false);
        if (in == null || in.rowVer() == null) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        if (in.rowVer() != cur.getRowVer()) {
            throw new BusinessException(HbErrorCode.CONFLICT);
        }
        ProjectRow p = mapper.findProject(cur.getProjId(), clock.today());
        if (!canSubmit(cur, me, tasks.canManage(p, me))) {
            throw new BusinessException(HbErrorCode.PMS_NOT_EDITABLE);
        }
        if (!permission.actions(ECO_PROG).contains(Action.SEARCH)) {
            throw new BusinessException(HbErrorCode.NO_PERMISSION);
        }
        FieldErrors e = FieldErrors.create();
        String note = visible(in.note(), "note", 500, false, e);
        e.throwIfAny();
        if (p.getEcoId() == null) {
            throw fieldError("ppap", "PMS_NO_ECO");
        }
        PpapView ppap = docSync.ppapOf(p.getEcoId());
        if (ppap == null) {
            throw fieldError("ppap", "PMS_NO_PPAP");
        }
        if (PPAP_REF.equals(cur.getRefType()) && cur.getRefId() != null && cur.getRefId() == ppap.ppapId()) {
            throw fieldError("ppap", "PMS_PPAP_SAME");
        }
        if (translate(() -> (long) gm.linkDeliverableDoc(delivId, PPAP_REF, ppap.ppapId(), in.rowVer(), me.loginId())) == 0) {
            throw new BusinessException(HbErrorCode.CONFLICT);
        }
        gm.insertHistDoc(cur.getProjId(), delivId, null, ppap.ppapId(), null, null, note, me.loginId());
        String status = "SUBMITTED";
        if ("APPROVED".equals(ppap.status())) {
            String n = DeliverableDocSync.approvedNote(ppap);
            gm.approveFromDoc(delivId, n, me.loginId());
            gm.insertHist(cur.getProjId(), delivId, "APPROVE", null, n, me.loginId());
            status = "APPROVED";
        }
        return new DocLinkResult(status, ppap.ppapId(), null, null, ppap.status());
    }

    /**
     * 산출물을 PFMEA·관리계획서 리비전에 연결(docs/41 A3-1, L2) — 담당·PM, 그리고 그 APQP 화면 SEARCH. APQP 계약 ApqpLookup 으로만 읽는다.
     * 리비전을 비우면 확정본 → 없으면 작성·결재 중 리비전. 한 번이라도 확정된 리비전이면 바로 승인, 아니면 제출 중 → 확정 뒤 반영(QMS 문서와 같은 길).
     * 폐기본을 골라 연결하는 것·같은 리비전 다시 연결은 거부
     */
    @Transactional
    public DocLinkResult linkApqp(long delivId, GateDtos.ApqpLinkForm in) {
        LoginUser me = me();
        DelivRow cur = lockAndGet(delivId, me, false);
        if (in == null || in.rowVer() == null) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        if (in.rowVer() != cur.getRowVer()) {
            throw new BusinessException(HbErrorCode.CONFLICT);
        }
        ProjectRow p = mapper.findProject(cur.getProjId(), clock.today());
        if (!canSubmit(cur, me, tasks.canManage(p, me))) {
            throw new BusinessException(HbErrorCode.PMS_NOT_EDITABLE);
        }
        String kind = in.kind() == null ? null : in.kind().strip().toUpperCase(java.util.Locale.ROOT);
        if (!ApqpLookup.FMEA.equals(kind) && !ApqpLookup.CP.equals(kind)) {
            throw fieldError("kind", kind == null ? "REQUIRED" : "CODE");
        }
        if (!permission.actions(ApqpLookup.FMEA.equals(kind) ? FMEA_PROG : CP_PROG).contains(Action.SEARCH)) {
            throw new BusinessException(HbErrorCode.NO_PERMISSION);
        }
        FieldErrors e = FieldErrors.create();
        String no = visible(in.no(), "apqpNo", 30, true, e);
        String note = visible(in.note(), "note", 500, false, e);
        e.throwIfAny();
        List<ApqpLookup.RevView> revs = ApqpLookup.of(apqps).revsByNo(kind, no);
        if (revs.isEmpty()) {
            throw fieldError("apqpNo", "PMS_APQP_NOT_FOUND");
        }
        ApqpLookup.RevView r;
        if (in.rev() != null) {
            r = revs.stream().filter(x -> x.rev() == in.rev()).findFirst().orElseThrow(() -> fieldError("apqpRev", "PMS_APQP_REV_NOT_FOUND"));
        } else {
            r = revs.stream().filter(x -> "RELEASED".equals(x.status())).findFirst()
                    .or(() -> revs.stream().filter(x -> "DRAFT".equals(x.status()) || "IN_APPROVAL".equals(x.status())).findFirst())
                    .orElseThrow(() -> fieldError("apqpNo", "PMS_APQP_NO_REV"));
        }
        if ("OBSOLETE".equals(r.status())) {
            throw fieldError("apqpRev", "PMS_APQP_OBSOLETE");
        }
        String ref = ApqpLookup.FMEA.equals(kind) ? FMEA_REF : CP_REF;
        if (ref.equals(cur.getRefType()) && cur.getRefId() != null && cur.getRefId() == r.id()) {
            throw fieldError("apqpNo", "PMS_APQP_SAME");
        }
        if (translate(() -> (long) gm.linkDeliverableDoc(delivId, ref, r.id(), in.rowVer(), me.loginId())) == 0) {
            throw new BusinessException(HbErrorCode.CONFLICT);
        }
        gm.insertHistDoc(cur.getProjId(), delivId, null, null, FMEA_REF.equals(ref) ? r.id() : null, CP_REF.equals(ref) ? r.id() : null,
                note, me.loginId());
        String status = "SUBMITTED";
        if (r.everReleased()) {
            String n = DeliverableDocSync.releasedNote(r);
            gm.approveFromDoc(delivId, n, me.loginId());
            gm.insertHist(cur.getProjId(), delivId, "APPROVE", null, n, me.loginId());
            status = "APPROVED";
        }
        return new DocLinkResult(status, r.id(), r.no(), "rev " + r.rev(), r.status());
    }

    /** [QMS 상태 반영] — 배포된 리비전에 연결된 제출 중 칸을 승인으로. 반환 = 바뀐 칸 수 */
    @Transactional
    public int syncDocs(long projId) {
        LoginUser me = me();
        tasks.openForEdit(projId, me, false);
        return docSync.sync(projId, me.loginId());
    }

    /** 문서 번호(대소문자 무시, 사용 중 문서) → 리비전. QMS 계약 DocLookup.revsByDocNo(최근 것부터)로만 */
    private DocRevView resolveRev(String docNo, String rev) {
        List<DocRevView> revs = DocLookup.of(docs).revsByDocNo(docNo);
        if (revs.isEmpty()) {
            throw fieldError("docNo", "PMS_DOC_NOT_FOUND");
        }
        DocRevView r;
        if (rev != null) {
            r = revs.stream().filter(x -> x.rev().equalsIgnoreCase(rev)).findFirst().orElse(null);
            if (r == null) {
                throw fieldError("rev", "PMS_DOC_REV_NOT_FOUND");
            }
        } else {
            r = revs.stream().filter(x -> "RELEASED".equals(x.status())).findFirst()
                    .or(() -> revs.stream().filter(x -> "DRAFT".equals(x.status()) || "IN_APPROVAL".equals(x.status())).findFirst())
                    .orElse(null);
            if (r == null) {
                throw fieldError("docNo", "PMS_DOC_NO_REV");
            }
        }
        if ("OBSOLETE".equals(r.status())) {
            throw fieldError("rev", "PMS_DOC_OBSOLETE");
        }
        return r;
    }

    /** 문서·PPAP 에 연결된 칸 — 파일을 받지 않고 손 승인·반려도 받지 않는다 */
    static boolean linked(DelivRow d) {
        return DOC_REF.equals(d.getRefType()) || PPAP_REF.equals(d.getRefType()) || FMEA_REF.equals(d.getRefType())
                || CP_REF.equals(d.getRefType());
    }

    /** PPAP 연결 칸에 ECO 번호·PPAP 상태 — 프로젝트 ECO 의 패키지 하나(D18), 계약으로 */
    private void fillPpap(List<DelivRow> rows, ProjectRow p) {
        PpapView ppap = docSync.ppapOf(p.getEcoId());
        String ecoNo = p.getEcoId() == null ? null : EcoLookup.of(ecos).eco(p.getEcoId()).map(EcoLookup.EcoView::ecoNo).orElse(null);
        for (DelivRow d : rows) {
            if (PPAP_REF.equals(d.getRefType())) {
                d.setPpapEcoNo(ecoNo);
                d.setPpapStatus(ppap != null && ppap.ppapId() == d.getRefId() ? ppap.status() : null);
            }
        }
    }

    /** APQP 연결 칸 — 문서 칸 자리에 번호·제목·"rev n"·상태(계약으로, 지워진 작성 중 리비전이면 비어 있음) */
    private void fillApqp(DelivRow d) {
        String kind = FMEA_REF.equals(d.getRefType()) ? ApqpLookup.FMEA : ApqpLookup.CP;
        ApqpLookup.of(apqps).rev(kind, d.getRefId()).ifPresent(r -> {
            d.setDocNo(r.no());
            d.setDocNm(r.title());
            d.setDocRev("rev " + r.rev());
            d.setDocStatus(r.status());
        });
    }

    private void fillDoc(DelivRow d) {
        DocLookup.of(docs).rev(d.getRefId()).ifPresent(r -> {
            d.setDocNo(r.docNo());
            d.setDocNm(r.docNm());
            d.setDocRev(r.rev());
            d.setDocStatus(r.status());
        });
    }

    // ------------------------------------------------------------------ 엑셀

    @Transactional(readOnly = true)
    public long export(long projId, List<String> columns, HttpServletResponse response) throws IOException {
        DelivView v = view(projId);
        List<ColumnSpec> cols = ColumnSpec.selectExportable(COLUMNS, columns);
        return excel.export(response, new ExportJob(PROG, "prog.pmsDeliverable", cols, v.rows().size(), List.of("projId")),
                w -> v.rows().forEach(w::accept));
    }

    // ------------------------------------------------------------------ 내부

    private record Value(String nm, String required, String form, Long owner, LocalDate due) {
    }

    private Value validate(DelivForm in) {
        FieldErrors e = FieldErrors.create();
        String nm = visible(in.delivNm(), "delivNm", 200, true, e);
        String req = isBlank(in.requiredYn()) ? "Y" : in.requiredYn().strip();
        if (!"Y".equals(req) && !"N".equals(req)) {
            e.add("requiredYn", "CODE");
        }
        Long owner = null;
        if (!isBlank(in.ownerLoginId())) {
            String login = in.ownerLoginId().strip();
            UserOption u = users.activeByLogin(login);
            if (u == null) {
                e.add("ownerLoginId", "NO_SUCH_CODE");
            } else {
                owner = u.getUserId();
            }
        }
        LocalDate due = date(in.dueDate(), "dueDate", false, e);
        String form = FormDoc.check(DocLookup.of(docs), in.formDocNo(), "formDocNo", e);
        e.throwIfAny();
        return new Value(nm, req, form, owner, due);
    }

    /** 프로젝트 머리 잠금(열린 프로젝트) 뒤 다시 읽는다. manage 면 PM·PMO 만 */
    private DelivRow lockAndGet(long delivId, LoginUser me, boolean manage) {
        Long projId = gm.findDeliverableProjId(delivId);
        if (projId == null) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        tasks.openForEdit(projId, me, manage);
        DelivRow d = gm.findDeliverable(delivId);
        if (d == null) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        return d;
    }

    static boolean canSubmit(DelivRow d, LoginUser me, boolean manage) {
        return manage || Objects.equals(d.getOwnerUserId(), me.userId()) || Objects.equals(d.getTaskOwnerUserId(), me.userId());
    }

    private static void requireState(boolean ok) {
        if (!ok) {
            throw new BusinessException(HbErrorCode.PMS_DELIV_STATE);
        }
    }

    private ProjectRow project(long projId, LocalDate today) {
        ProjectRow p = mapper.findProject(projId, today);
        if (p == null) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        return p;
    }

    private static long translate(java.util.function.LongSupplier op) {
        try {
            return op.getAsLong();
        } catch (DataIntegrityViolationException ex) {
            String c = PgErrors.constraint(ex).orElse("");
            throw switch (c) {
                case "fk_pms_deliverable_owner_comp" -> fieldError("ownerLoginId", "NO_SUCH_CODE");
                case "ck_pms_deliverable_project_closed" -> new BusinessException(HbErrorCode.PMS_PROJECT_CLOSED);
                case "fk_pms_deliverable_doc_rev" -> fieldError("docNo", "PMS_DOC_NOT_FOUND");
                case "fk_pms_deliverable_ppap" -> fieldError("ppap", "PMS_NO_PPAP");
                default -> ex;
            };
        }
    }
}
