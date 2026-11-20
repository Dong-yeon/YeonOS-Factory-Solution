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
import java.util.List;
import java.util.Objects;
import java.util.Set;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.smarthappy.module.apqp.link.ApqpLookup;
import io.smarthappy.module.apqp.link.ApqpLookup.ActionView;
import io.smarthappy.module.pms.PmsDtos.ProjectRow;
import io.smarthappy.module.pms.PmsDtos.TaskRow;
import io.smarthappy.module.pms.WorkDtos.ApqpIssueForm;
import io.smarthappy.module.pms.WorkDtos.ApqpIssueRow;
import io.smarthappy.module.pms.WorkDtos.EscalateForm;
import io.smarthappy.module.pms.WorkDtos.IssueForm;
import io.smarthappy.module.pms.WorkDtos.IssuePage;
import io.smarthappy.module.pms.WorkDtos.IssueRow;
import io.smarthappy.module.pms.WorkDtos.IssueSearch;
import io.smarthappy.module.pms.WorkDtos.IssueSort;
import io.smarthappy.module.pms.WorkDtos.IssueStatusForm;
import io.smarthappy.module.pms.WorkDtos.ObjRef;
import io.smarthappy.module.pms.WorkMapper.IssueValue;
import io.smarthappy.module.production.link.LotLookup;
import io.smarthappy.module.production.link.LotLookup.LotView;
import io.smarthappy.module.qms.link.CapaPort;
import io.smarthappy.module.qms.link.CapaPort.InternalRequest;
import io.smarthappy.platform.auth.Action;
import io.smarthappy.platform.auth.PermissionService;
import io.smarthappy.platform.common.db.PgErrors;
import io.smarthappy.platform.common.query.PageQuery;
import io.smarthappy.platform.common.web.BusinessException;
import io.smarthappy.platform.common.web.FieldErrors;
import io.smarthappy.platform.common.web.HbErrorCode;
import io.smarthappy.platform.excel.ExcelExportService;
import io.smarthappy.platform.excel.ExcelExportService.ExportJob;
import io.smarthappy.platform.grid.ColumnSpec;
import io.smarthappy.platform.grid.ColumnSpec.ColumnType;
import io.smarthappy.platform.messaging.InboxService;
import io.smarthappy.platform.numbering.NumberingService;
import io.smarthappy.platform.policy.Policies;
import io.smarthappy.platform.policy.PolicyService;
import io.smarthappy.platform.security.LoginUser;
import io.smarthappy.platform.time.BizClock;
import io.smarthappy.platform.user.UserDirectory;
import io.smarthappy.platform.user.UserOption;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;

/**
 * 이슈 (pms-issue) — docs/32 PM4, D10. 가벼운 할 일·위험 — 8D 는 QMS(병합 뒤 "시정조치로 올리기").
 * - 등록 = 화면 SAVE 가 있는 회사 사용자 누구나(열린 프로젝트), 수정·진행·해결 = PM·PMO·담당·등록자, 종료 = PM·PMO
 * - 객체 연결 = 같은 회사의 품목·거래처·설비·물리 자산(코드로 찾고 트리거가 다시 확인 — A사 품목에 B사 이슈 불가)
 * - 상태 OPEN → IN_PROGRESS → RESOLVED(해결 내용 필수) → CLOSED, RESOLVED → IN_PROGRESS(다시 열기). 닫힌 이슈는 불변(트리거)
 * - PM6: "시정조치로 올리기" = QMS 계약 CapaPort.createInternal(출처 INTERNAL) + capa_id 만 보관(D10·D13), qms-capa SAVE 도 필요(D14)
 * - A3-5(docs/41 11.7): "PFMEA 조치 → 이슈" = APQP 계약 ApqpLookup.action 으로 읽어 한 번 복사(source APQP + 근거 조치 id, 조치당 하나),
 *   그 뒤 상태는 따로(L7). apqp-fmea SEARCH 도 필요. 프로젝트 = FMEA 의 프로젝트(없을 때만 요청 값)
 * - PM7 ③: 객체 연결에 LOT — MES 계약 LotLookup(byNo 로 찾기, 번호 대소문자 그대로 / lots 로 목록 표시), 같은 회사 확인은 트리거(D20)
 */
@Service
@RequiredArgsConstructor
public class IssueService {

    static final String PROG = "pms-issue";
    /** 첨부 대상 = 화면(sys_file.target_type), id = "I" + 이슈 id — IssueFileLock */
    static final String FILE_TARGET = "pms-issue";
    static final String ISSUE_TYPE_GRP = "PMS_ISSUE_TYPE";
    /** QMS 시정조치 화면 — 올리기는 이 화면 SAVE 도 있어야(D14) */
    static final String CAPA_PROG = "qms-capa";
    /** PFMEA 화면 — 조치 → 이슈는 이 화면 SEARCH 도 있어야(A3-5) */
    static final String FMEA_PROG = "apqp-fmea";
    static final Set<String> OPEN = Set.of("OPEN", "IN_PROGRESS", "RESOLVED");
    private static final Set<String> STATUSES = Set.of("OPEN", "IN_PROGRESS", "RESOLVED", "CLOSED");
    private static final Set<String> SEVERITIES = Set.of("LOW", "MID", "HIGH");
    private static final Set<String> OBJ_TYPES = Set.of("ITEM", "CUST", "EQUIP", "ASSET", "LOT");
    private static final int LIST_LIMIT = 500;

    static final List<ColumnSpec> COLUMNS = List.of(
            ColumnSpec.text("issueNo", "pms.col.issueNo", 120),
            ColumnSpec.text("projNo", "pms.col.projNo", 110),
            ColumnSpec.text("projNm", "pms.col.projNm", 180),
            ColumnSpec.text("taskNm", "pms.col.taskNm", 160),
            ColumnSpec.text("title", "pms.col.issueTitle", 220),
            ColumnSpec.code("severity", "pms.col.severity", "PMS_ISSUE_SEVERITY", 70),
            ColumnSpec.code("status", "pms.col.issueStatus", "PMS_ISSUE_STATUS", 80),
            ColumnSpec.text("ownerNm", "pms.col.owner", 100),
            ColumnSpec.of("dueDate", "pms.col.due", ColumnType.DATE, 100),
            ColumnSpec.text("objType", "pms.col.objType", 70),
            ColumnSpec.text("objCd", "pms.col.objCd", 120),
            ColumnSpec.text("resolution", "pms.col.resolution", 220),
            ColumnSpec.of("resolvedOn", "pms.col.resolvedOn", ColumnType.DATE, 100),
            ColumnSpec.text("source", "pms.col.issueSource", 70));

    private final WorkMapper wm;
    private final PmsMapper mapper;
    private final UserDirectory users;
    private final TaskService tasks;
    private final NumberingService numbering;
    private final InboxService inbox;
    private final ExcelExportService excel;
    private final BizClock clock;
    private final PmsDays days;
    private final org.springframework.beans.factory.ObjectProvider<CapaPort> capas;
    private final org.springframework.beans.factory.ObjectProvider<LotLookup> lots;
    private final org.springframework.beans.factory.ObjectProvider<ApqpLookup> apqps;
    private final PermissionService permission;
    private final PolicyService policy;
    private final PmsNotifier notifier;

    private record Query(Long projId, List<String> statuses, String severity, Long ownerId, String objType, Long objId, String like,
            String issueType) {
    }

    @Transactional(readOnly = true)
    public List<IssueRow> list(IssueSearch s) {
        Query q = query(s);
        if (q == null) {
            return List.of();
        }
        LoginUser me = me();
        List<IssueRow> rows = wm.findIssues(q.projId, q.statuses, q.severity, q.ownerId, q.objType, q.objId, q.like, days.lateRef(),
                LIST_LIMIT, q.issueType);
        rows.forEach(r -> rights(r, me));
        fillLots(rows);
        return rows;
    }

    /**
     * grid(UI 단계 3 ②, docs/32 4.11) — 목록과 같은 조건(issueWhere) + 서버 페이징·정렬(허용 목록 {@link IssueSort}).
     * 기본 정렬 = 목록과 같음(심각도 높음 먼저 → 기한 → 최근)
     */
    @Transactional(readOnly = true)
    public IssuePage grid(IssueSearch s, Integer page, Integer size, List<String> sort, Boolean total) {
        PageQuery pq = PageQuery.parse(page, size, sort, total, IssueSort.values(), policy.intValue(Policies.GRID_PAGE_SIZE_MAX),
                List.of("severity,asc", "dueDate,asc"));
        Query q = query(s);
        if (q == null) {
            return new IssuePage(List.of(), pq.isWithTotal() ? 0L : null);
        }
        LoginUser me = me();
        List<IssueRow> rows = wm.findIssuePage(q.projId, q.statuses, q.severity, q.ownerId, q.objType, q.objId, q.like, days.lateRef(), pq, q.issueType);
        rows.forEach(r -> rights(r, me));
        fillLots(rows);
        return new IssuePage(rows, pq.isWithTotal() ? wm.countIssues(q.projId, q.statuses, q.severity, q.ownerId, q.objType, q.objId, q.like, q.issueType)
                : null);
    }

    /** grid 열(목록 표와 같은 칸 — 연결은 종류·코드 두 칸) */
    static final List<ColumnSpec> GRID_COLUMNS = List.of(
            ColumnSpec.text("issueNo", "pms.col.issueNo", 120).withSort().withPriority(1),
            ColumnSpec.text("title", "pms.col.issueTitle", 240).withSort().withPriority(1).withFlex(),
            ColumnSpec.text("projNo", "pms.col.project", 110).withSort(),
            ColumnSpec.code("severity", "pms.col.severity", "PMS_ISSUE_SEVERITY", 70).withSort().withPriority(1),
            ColumnSpec.code("status", "pms.col.issueStatus", "PMS_ISSUE_STATUS", 80).withSort().withPriority(1),
            ColumnSpec.code("issueType", "pms.col.issueType", ISSUE_TYPE_GRP, 70).withPriority(2),
            ColumnSpec.code("closeKind", "pms.col.closeKind", "PMS_ISSUE_CLOSE", 70).withPriority(3),
            ColumnSpec.text("ownerNm", "pms.col.owner", 90).withSort(),
            ColumnSpec.of("dueDate", "pms.col.due", ColumnType.DATE, 100).withSort(),
            ColumnSpec.text("objType", "pms.col.objType", 70).withPriority(3),
            ColumnSpec.text("objCd", "pms.col.objCd", 120).withPriority(3));

    public List<ColumnSpec> gridColumns() {
        return GRID_COLUMNS;
    }

    @Transactional(readOnly = true)
    public IssueRow get(long id) {
        IssueRow r = wm.findIssue(id, days.lateRef());
        if (r == null) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        rights(r, me());
        fillLots(List.of(r));
        if (r.getCapaId() != null) {
            CapaPort.of(capas).capa(r.getCapaId()).ifPresent(c -> {
                r.setCapaNo(c.capaNo());
                r.setCapaStatus(c.status());
            });
        }
        return r;
    }

    @Transactional(readOnly = true)
    public long export(IssueSearch s, HttpServletResponse response) throws IOException {
        Query q = query(s);
        List<ColumnSpec> cols = ColumnSpec.selectExportable(COLUMNS, s == null ? null : s.columns());
        long count = q == null ? 0 : wm.countIssues(q.projId, q.statuses, q.severity, q.ownerId, q.objType, q.objId, q.like, q.issueType);
        List<String> keys = new ArrayList<>();
        if (s != null) {
            if (s.projId() != null) {
                keys.add("projId");
            }
            if (!isBlank(s.status())) {
                keys.add("status");
            }
            if (!isBlank(s.severity())) {
                keys.add("severity");
            }
            if (Boolean.TRUE.equals(s.mine())) {
                keys.add("mine");
            }
            if (!isBlank(s.objType())) {
                keys.add("obj");
            }
            if (!isBlank(s.keyword())) {
                keys.add("keyword");
            }
        }
        // 목록과 같은 조건·같은 정렬, 상한은 엑셀 정책(count 로 먼저 거부)
        return excel.export(response, new ExportJob(PROG, "prog.pmsIssue", cols, count, keys), w -> {
            if (q != null) {
                List<IssueRow> rows = wm.findIssues(q.projId, q.statuses, q.severity, q.ownerId, q.objType, q.objId, q.like, days.lateRef(),
                        Integer.MAX_VALUE, q.issueType);
                fillLots(rows);
                rows.forEach(w::accept);
            }
        });
    }

    // ------------------------------------------------------------------ 등록·수정

    @Transactional
    public long create(IssueForm in) {
        LoginUser me = me();
        inbox.claimRequestKey("PMS_ISSUE_CREATE");
        if (in == null || in.projId() == null) {
            throw fieldError("projId", "REQUIRED");
        }
        lockOpen(in.projId());
        IssueValue v = validate(in, in.projId());
        GateDecider.ensureIssueRule(mapper, me.loginId());
        String no = numbering.next(GateDecider.ISSUE_RULE);
        long id = translate(() -> wm.insertIssue(no, v, me.loginId()));
        notifier.issueAssigned(v.ownerUserId(), id, 0, no, v.title());
        return id;
    }

    /**
     * PFMEA 조치 → 이슈(A3-5). 열린 조치만, 조치 하나에 이슈 하나(색인도 막음). 복사 = 제목 "[번호 rev] 조치", 내용 FM·FC·AP,
     * 심각도 AP H/M/L → HIGH/MID/LOW, 담당·기한, 대상 품목. 다른 회사 조치·프로젝트는 없는 것으로(RLS + 트리거)
     */
    @Transactional
    public long fromApqp(long actionId, ApqpIssueForm in) {
        LoginUser me = me();
        inbox.claimRequestKey("PMS_ISSUE_CREATE");
        if (!permission.actions(FMEA_PROG).contains(Action.SEARCH)) {
            throw new BusinessException(HbErrorCode.NO_PERMISSION);
        }
        ActionView a = ApqpLookup.of(apqps).action(actionId).orElseThrow(() -> new BusinessException(HbErrorCode.NOT_FOUND));
        if (!"OPEN".equals(a.status())) {
            throw fieldError("actionId", "PMS_APQP_ACTION_CLOSED");
        }
        Long req = in == null ? null : in.projId();
        if (a.projId() != null && req != null && !req.equals(a.projId())) {
            throw fieldError("projId", "PMS_APQP_PROJ_MISMATCH");
        }
        Long projId = a.projId() != null ? a.projId() : req;
        if (projId == null) {
            throw fieldError("projId", "REQUIRED");
        }
        lockOpen(projId);
        List<ApqpIssueRow> linked = wm.findApqpIssues(List.of(actionId));
        if (!linked.isEmpty()) {
            throw new BusinessException(HbErrorCode.PMS_APQP_ISSUE_EXISTS, linked.get(0).getIssueNo());
        }
        String ref = a.fmeaNo() + " rev " + a.rev();
        String title = cut("[" + ref + "] " + a.actionText(), 200);
        String detail = cut("PFMEA " + ref + "\nFM: " + nz(a.fm()) + "\nFC: " + nz(a.fc()) + "\nAP: " + nz(a.ap())
                + "\n" + a.actionKind() + ": " + a.actionText(), 2000);
        String sev = "H".equals(a.ap()) ? "HIGH" : "L".equals(a.ap()) ? "LOW" : "MID";
        // FMEA 조치 = 위험을 줄이는 일 → 유형 위험(P-2)
        IssueValue v = new IssueValue(projId, null, title, detail, sev, a.ownerUserId(), a.dueDate(), "ITEM", a.itemId(), "RISK");
        GateDecider.ensureIssueRule(mapper, me.loginId());
        String no = numbering.next(GateDecider.ISSUE_RULE);
        long id = translate(() -> wm.insertApqpIssue(no, v, actionId, me.loginId()));
        notifier.issueAssigned(v.ownerUserId(), id, 0, no, v.title());
        return id;
    }

    /** 조치 id 들 → 연결된 이슈(FMEA 화면 표시, 같은 회사만). 상한 LIST_LIMIT */
    @Transactional(readOnly = true)
    public List<ApqpIssueRow> apqpIssues(List<Long> actionIds) {
        List<Long> ids = actionIds == null ? List.of() : actionIds.stream().filter(Objects::nonNull).distinct().toList();
        if (ids.size() > LIST_LIMIT) {
            throw new BusinessException(HbErrorCode.TOO_MANY_ROWS, ids.size(), LIST_LIMIT);
        }
        return ids.isEmpty() ? List.of() : wm.findApqpIssues(ids);
    }

    private static String cut(String s, int max) {
        return s.length() > max ? s.substring(0, max) : s;
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    @Transactional
    public void update(long id, IssueForm in) {
        LoginUser me = me();
        IssueRow cur = lockAndGet(id);
        if (in == null || in.rowVer() == null) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        if (in.projId() != null && in.projId() != cur.getProjId()) {
            throw fieldError("projId", "FIXED");
        }
        if ("CLOSED".equals(cur.getStatus())) {
            throw new BusinessException(HbErrorCode.PMS_ISSUE_STATE);
        }
        rights(cur, me);
        if (!cur.isCanEdit()) {
            throw new BusinessException(HbErrorCode.PMS_NOT_EDITABLE);
        }
        IssueValue v = validate(in, cur.getProjId());
        if (translate(() -> (long) wm.updateIssue(id, v, in.rowVer(), me.loginId())) == 0) {
            throw new BusinessException(HbErrorCode.CONFLICT);
        }
        if (v.ownerUserId() != null && !v.ownerUserId().equals(cur.getOwnerUserId())) {
            notifier.issueAssigned(v.ownerUserId(), id, in.rowVer() + 1, cur.getIssueNo(), v.title());
        }
    }

    /**
     * 전이(트리거와 같은 표). 해결 = 해결 내용 필수 + 해결일 오늘, 다시 열기(RESOLVED → IN_PROGRESS) = 해결일 지움, 종료 = PM·PMO.
     * 기각(docs/32 4.15) = 요청 상태 REJECTED: 열린 이슈(OPEN·IN_PROGRESS) → CLOSED + 종료 방식 REJECTED, 사유(resolution) 필수, PM·PMO, 날짜 오늘
     */
    @Transactional
    public void changeStatus(long id, IssueStatusForm in) {
        LoginUser me = me();
        IssueRow cur = lockAndGet(id);
        if (in == null || in.rowVer() == null) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        if (in.rowVer() != cur.getRowVer()) {
            throw new BusinessException(HbErrorCode.CONFLICT);
        }
        rights(cur, me);
        String asked = upper(in.status());
        boolean reject = "REJECTED".equals(asked);
        String to = reject ? "CLOSED" : asked;
        String from = cur.getStatus();
        boolean ok = to != null && switch (from) {
            case "OPEN" -> reject || to.equals("IN_PROGRESS") || to.equals("RESOLVED");
            case "IN_PROGRESS" -> reject || to.equals("OPEN") || to.equals("RESOLVED");
            case "RESOLVED" -> !reject && (to.equals("CLOSED") || to.equals("IN_PROGRESS"));
            default -> false;
        };
        if (!ok) {
            throw new BusinessException(HbErrorCode.PMS_ISSUE_STATE);
        }
        if ("CLOSED".equals(to) ? !cur.isCanClose() : !cur.isCanEdit()) {
            throw new BusinessException(HbErrorCode.PMS_NOT_EDITABLE);
        }
        FieldErrors e = FieldErrors.create();
        String resolution = visible(in.resolution(), "resolution", 1000, "RESOLVED".equals(to) || reject, e);
        e.throwIfAny();
        String res = resolution == null ? cur.getResolution() : resolution;
        LocalDate on = reject ? clock.today() : switch (to) {
            case "RESOLVED" -> clock.today();
            case "CLOSED" -> cur.getResolvedOn();
            default -> null;
        };
        String closeKind = reject ? "REJECTED" : "CLOSED".equals(to) ? "DONE" : null;
        if (wm.updateIssueStatus(id, to, res, on, closeKind, in.rowVer(), me.loginId()) == 0) {
            throw new BusinessException(HbErrorCode.CONFLICT);
        }
        if ("RESOLVED".equals(to)) {
            ProjectRow p = mapper.findProject(cur.getProjId(), days.lateRef());
            notifier.issueResolved(cur.getCreatedBy(), p.getPmUserId(), id, in.rowVer() + 1, cur.getIssueNo(), cur.getTitle());
        }
    }

    // ------------------------------------------------------------------ 시정조치로 올리기 (PM6)

    /**
     * 열린 이슈(OPEN·IN_PROGRESS)를 QMS 시정조치로 — 고칠 수 있는 사람 + qms-capa SAVE. 출처 INTERNAL, 제목 = 이슈 제목,
     * 품목 = 이슈가 품목에 연결됐으면 그 품목, 담당 = 이슈 담당(없으면 본인), D2 = 이슈 번호·프로젝트·내용. 이슈 상태는 그대로. 반환 = 시정조치 id
     */
    @Transactional
    public long escalate(long id, EscalateForm in) {
        LoginUser me = me();
        IssueRow cur = lockAndGet(id);
        if (in == null || in.rowVer() == null) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        if (in.rowVer() != cur.getRowVer()) {
            throw new BusinessException(HbErrorCode.CONFLICT);
        }
        rights(cur, me);
        if (!cur.isCanEdit()) {
            throw new BusinessException(HbErrorCode.PMS_NOT_EDITABLE);
        }
        if (!Set.of("OPEN", "IN_PROGRESS").contains(cur.getStatus()) || cur.getCapaId() != null) {
            throw new BusinessException(HbErrorCode.PMS_ISSUE_STATE);
        }
        if (!permission.actions(CAPA_PROG).contains(Action.SAVE)) {
            throw new BusinessException(HbErrorCode.NO_PERMISSION);
        }
        String problem = "PMS " + cur.getIssueNo() + " / " + cur.getProjNo() + " " + cur.getProjNm()
                + (cur.getDetail() == null ? "" : "\n" + cur.getDetail());
        long capaId = CapaPort.of(capas).createInternal(new InternalRequest(cur.getTitle(),
                "ITEM".equals(cur.getObjType()) ? cur.getObjCd() : null, cur.getOwnerUserId() != null ? cur.getOwnerUserId() : me.userId(),
                problem.length() > 4000 ? problem.substring(0, 4000) : problem));
        if (translate(() -> (long) wm.linkCapa(id, capaId, in.rowVer(), me.loginId())) == 0) {
            throw new BusinessException(HbErrorCode.CONFLICT);
        }
        return capaId;
    }

    // ------------------------------------------------------------------ 내부

    /** 조회 조건. 연결 객체 코드가 이 회사에 없으면 null(빈 결과) */
    private Query query(IssueSearch s) {
        IssueSearch x = s == null ? new IssueSearch(null, null, null, null, null, null, null, null, null) : s;
        String st = upper(x.status());
        List<String> statuses;
        if (st == null) {
            statuses = List.copyOf(OPEN);
        } else if ("ALL".equals(st)) {
            statuses = null;
        } else if (STATUSES.contains(st)) {
            statuses = List.of(st);
        } else {
            throw fieldError("status", "CODE");
        }
        String sev = upper(x.severity());
        if (sev != null && !SEVERITIES.contains(sev)) {
            throw fieldError("severity", "CODE");
        }
        String objType = upper(x.objType());
        Long objId = null;
        if (objType != null) {
            if (!OBJ_TYPES.contains(objType)) {
                throw fieldError("objType", "CODE");
            }
            String cd = "LOT".equals(objType) ? (isBlank(x.objCd()) ? null : x.objCd().strip()) : upper(x.objCd());
            if (cd == null) {
                throw fieldError("objCd", "REQUIRED");
            }
            List<ObjRef> refs = findObj(objType, cd);
            if (refs.isEmpty()) {
                return null;
            }
            objId = refs.get(0).getObjId();
        }
        Long owner = Boolean.TRUE.equals(x.mine()) ? me().userId() : null;
        // 유형은 코드 형식만 본다(없는 코드면 빈 결과 — 회사 코드가 사용 중지돼도 옛 이슈를 찾을 수 있게)
        String type = upper(x.issueType());
        if (type != null && !type.matches("^[A-Z0-9][A-Z0-9_-]{0,29}$")) {
            throw fieldError("issueType", "CODE");
        }
        return new Query(x.projId(), statuses, sev, owner, objType, objId, PmsInputs.like(x.keyword()), type);
    }

    private IssueValue validate(IssueForm in, long projId) {
        FieldErrors e = FieldErrors.create();
        Long taskId = in.taskId();
        if (taskId != null) {
            TaskRow t = mapper.findTask(taskId);
            if (t == null || t.getProjId() != projId) {
                e.add("taskId", "NOT_FOUND");
            }
        }
        String title = visible(in.title(), "title", 200, true, e);
        String detail = visible(in.detail(), "detail", 2000, false, e);
        String sev = isBlank(in.severity()) ? "MID" : upper(in.severity());
        if (!SEVERITIES.contains(sev)) {
            e.add("severity", "CODE");
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
        String objType = upper(in.objType());
        Long objId = null;
        if (objType != null) {
            if (!OBJ_TYPES.contains(objType)) {
                e.add("objType", "CODE");
            } else {
                String cd = "LOT".equals(objType) ? (isBlank(in.objCd()) ? null : in.objCd().strip()) : upper(in.objCd());
                List<ObjRef> refs = cd == null ? List.of() : findObj(objType, cd);
                if (cd == null) {
                    e.add("objCd", "REQUIRED");
                } else if (refs.isEmpty()) {
                    e.add("objCd", "NO_SUCH_CODE");
                } else if (refs.size() > 1) {
                    e.add("objCd", "PMS_OBJ_AMBIGUOUS");
                } else {
                    objId = refs.get(0).getObjId();
                }
            }
        } else if (!isBlank(in.objCd())) {
            e.add("objType", "REQUIRED");
        }
        // 유형(P-2): 필수, 공통코드 PMS_ISSUE_TYPE 중 사용 중인 것(회사 코드 포함) — 트리거와 같은 함수
        String type = upper(in.issueType());
        if (type == null) {
            e.add("issueType", "REQUIRED");
        } else if (!type.matches("^[A-Z0-9][A-Z0-9_-]{0,29}$") || !wm.codeUsable(ISSUE_TYPE_GRP, type)) {
            e.add("issueType", "CODE");
        }
        e.throwIfAny();
        return new IssueValue(projId, taskId, title, detail, sev, owner, due, objType, objId, type);
    }

    /** 객체 코드 → 후보. LOT 은 MES 계약(LotLookup.byNo — 없거나 다른 회사면 빈 값), 나머지는 기준정보 코드(공장마다 같은 설비 코드 → 2개) */
    private List<ObjRef> findObj(String objType, String cd) {
        if ("LOT".equals(objType)) {
            return LotLookup.of(lots).byNo(cd).map(l -> {
                ObjRef r = new ObjRef();
                r.setObjType("LOT");
                r.setObjId(l.lotId());
                r.setObjCd(l.lotNo());
                r.setObjNm(l.itemNm());
                return List.of(r);
            }).orElse(List.of());
        }
        return cd.length() > 40 ? List.of() : wm.findObj(objType, cd);
    }

    /** LOT 에 연결된 이슈의 표시 코드·이름(LOT 번호·품목명) — MES 계약 lots(ids), 한 번에 MAX_IDS 씩 */
    private void fillLots(List<IssueRow> rows) {
        List<Long> ids = rows.stream().filter(r -> "LOT".equals(r.getObjType()) && r.getObjId() != null).map(IssueRow::getObjId)
                .distinct().toList();
        if (ids.isEmpty()) {
            return;
        }
        java.util.Map<Long, LotView> byId = new java.util.HashMap<>();
        for (int i = 0; i < ids.size(); i += LotLookup.MAX_IDS) {
            LotLookup.of(lots).lots(ids.subList(i, Math.min(ids.size(), i + LotLookup.MAX_IDS))).forEach(l -> byId.put(l.lotId(), l));
        }
        for (IssueRow r : rows) {
            LotView l = "LOT".equals(r.getObjType()) ? byId.get(r.getObjId()) : null;
            if (l != null) {
                r.setObjCd(l.lotNo());
                r.setObjNm(l.itemNm());
            }
        }
    }

    /** 고치기·진행 = PM·PMO·담당·등록자, 종료 = PM·PMO. 프로젝트가 닫혔으면 아무것도 못 한다 */
    private void rights(IssueRow r, LoginUser me) {
        // 목록 행마다 불린다 — late_cnt 를 쓰지 않으니 지연 기준일 계산(달력 조회)을 건너뛰고 오늘을 넘긴다
        ProjectRow p = mapper.findProject(r.getProjId(), clock.today());
        boolean open = p != null && TaskService.OPEN.contains(p.getStatus()) && !"CLOSED".equals(r.getStatus());
        boolean manage = p != null && tasks.canManage(p, me);
        r.setCanClose(open && manage);
        r.setCanEdit(open && (manage || Objects.equals(r.getOwnerUserId(), me.userId()) || me.loginId().equals(r.getCreatedBy())));
    }

    private void lockOpen(long projId) {
        String st = mapper.lockProjectShare(projId);
        if (st == null) {
            throw fieldError("projId", "NOT_FOUND");
        }
        if (!TaskService.OPEN.contains(st)) {
            throw new BusinessException(HbErrorCode.PMS_PROJECT_CLOSED);
        }
    }

    private IssueRow lockAndGet(long id) {
        Long projId = wm.findIssueProjId(id);
        if (projId == null) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        lockOpen(projId);
        return wm.findIssue(id, days.lateRef());
    }

    private static long translate(java.util.function.LongSupplier op) {
        try {
            return op.getAsLong();
        } catch (DataIntegrityViolationException ex) {
            String c = PgErrors.constraint(ex).orElse("");
            throw switch (c) {
                case "fk_pms_issue_obj_comp" -> fieldError("objCd", "NO_SUCH_CODE");
                case "fk_pms_issue_owner_comp" -> fieldError("ownerLoginId", "NO_SUCH_CODE");
                case "fk_pms_issue_task_proj" -> fieldError("taskId", "NOT_FOUND");
                case "ck_pms_issue_project_closed" -> new BusinessException(HbErrorCode.PMS_PROJECT_CLOSED);
                case "ck_pms_issue_capa_fixed", "uq_pms_issue_capa" -> new BusinessException(HbErrorCode.PMS_ISSUE_STATE);
                default -> ex;
            };
        }
    }
}
