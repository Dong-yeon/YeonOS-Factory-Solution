package io.smarthappy.module.apqp;

import static io.smarthappy.module.apqp.ApqpInputs.blank;
import static io.smarthappy.module.apqp.ApqpInputs.fieldError;
import static io.smarthappy.module.apqp.ApqpInputs.me;
import static io.smarthappy.module.apqp.ApqpInputs.text;
import static io.smarthappy.module.apqp.ApqpInputs.upper;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.smarthappy.module.apqp.ApqpDtos.CharOption;
import io.smarthappy.module.apqp.ApqpDtos.CharRef;
import io.smarthappy.module.apqp.ApqpDtos.FailRow;
import io.smarthappy.module.apqp.ApqpDtos.FmeaRow;
import io.smarthappy.module.apqp.ApqpDtos.FuncRow;
import io.smarthappy.module.apqp.ApqpDtos.ItemOption;
import io.smarthappy.module.apqp.ApqpDtos.MoveForm;
import io.smarthappy.module.apqp.ApqpDtos.NodeRow;
import io.smarthappy.module.apqp.ApqpDtos.RiskRow;
import io.smarthappy.module.apqp.ApqpDtos.StepOption;
import io.smarthappy.module.apqp.ApqpDtos.SubmitForm;
import io.smarthappy.module.apqp.CpDtos.CompareResult;
import io.smarthappy.module.apqp.CpDtos.CompareRow;
import io.smarthappy.module.apqp.CpDtos.CpCopyForm;
import io.smarthappy.module.apqp.CpDtos.CpDetail;
import io.smarthappy.module.apqp.CpDtos.CpExportRequest;
import io.smarthappy.module.apqp.CpDtos.CpFilter;
import io.smarthappy.module.apqp.CpDtos.CpForm;
import io.smarthappy.module.apqp.CpDtos.CpLineForm;
import io.smarthappy.module.apqp.CpDtos.CpLineRow;
import io.smarthappy.module.apqp.CpDtos.CpPage;
import io.smarthappy.module.apqp.CpDtos.CpRow;
import io.smarthappy.module.apqp.CpDtos.CpSort;
import io.smarthappy.module.apqp.CpDtos.CpValue;
import io.smarthappy.module.apqp.CpDtos.FmeaOption;
import io.smarthappy.module.apqp.CpDtos.PullResult;
import io.smarthappy.module.apqp.CpDtos.RiskOption;
import io.smarthappy.module.apqp.CpDtos.SpecialGap;
import io.smarthappy.module.qms.link.InspPlanLookup;
import io.smarthappy.module.qms.link.InspPlanLookup.PlanLine;
import io.smarthappy.platform.auth.Action;
import io.smarthappy.platform.auth.PermissionService;
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
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;

/**
 * 관리계획서 (apqp-cp, docs/41 5장 · 결정 C1~C8). 같은 규칙을 트리거가 한 번 더 지킨다(직접 SQL·동시 요청) — 서비스는 칸 오류로 먼저.
 * - 단계(시작품·양산 전·양산)는 번호마다 고정, 다음 단계 = 새 번호로 복사(C1). 근거 PFMEA = 확정 리비전 하나, 새 FMEA 확정은 표시만(C2)
 * - 행 = 단계(라우팅 단계 또는 글자) × 특성(qms_char 또는 글자, C3) × 관리 방법. 샘플은 글자 + 수(C4), 주기는 검사계획과 같은 값
 * - PFMEA 에서 끌어오기는 추가만 — 같은 근거 위험행이나 같은 단계·특성·관리 방법이 있으면 건너뛴다(C7, 사람이 고친 행을 덮지 않는다)
 * - 확정(APPROVE) = 행마다 반응계획·검사 주기, 특별특성(근거 위험행·품목/공정 특성) 모두 행에 있음, rev 2+ 개정 사유 → 규격 스냅샷
 * - 검사계획 대조(C5): 검사 유형·특성 키가 있는 행 ↔ 같은 품목·공정·검사 유형의 오늘 유효한 확정 검사계획(QMS 계약 InspPlanLookup, 읽기만).
 *   양산 단계는 검사계획에 없는 검사 행이 있으면 확정 거부(C6), 다른 단계·차이는 알림. 검사계획은 QMS 소유 — 고쳐 쓰지 않는다
 */
@Service
@RequiredArgsConstructor
public class CpService {

    static final String PROG = "apqp-cp";
    static final String RULE = "APQP_CP";
    static final int MAX_LINES = 2000;
    static final List<String> PHASES = List.of("PROTO", "PRE_LAUNCH", "PRODUCTION");
    static final Set<String> CTRL_METHODS = Set.of("INSPECT", "SPC", "ERROR_PROOF", "SETUP_VERIFY", "WORK_STD", "OTHER");
    static final Set<String> INSP_TYPES = Set.of("RECEIVE", "PROCESS", "FINAL", "FIRST_OFF", "LAST_OFF", "PATROL");
    static final Set<String> FREQ_TYPES = Set.of("EVERY_LOT", "START", "END", "EVERY_N", "EVERY_HOURS");

    private final CpRepository repo;
    private final ApqpRepository apqp;
    private final NumberingService numbering;
    private final InboxService inbox;
    private final PolicyService policy;
    private final PermissionService permission;
    private final ObjectProvider<InspPlanLookup> inspPlans;
    private final BizClock clock;
    private final ApqpApproval approval;
    private final ApqpChangeWatch changeWatch;
    private final ExcelExportService excel;

    // ------------------------------------------------------------------ 목록 (grid)

    static final List<String> DEFAULT_SORT = List.of("cpNo,desc", "rev,desc");

    /** status: 비우면 현행(작성 중·확정), ALL, 또는 하나 */
    @Transactional(readOnly = true)
    public CpPage grid(String keyword, String status, String phase, Integer page, Integer size, List<String> sort, Boolean total) {
        CpFilter f = filter(keyword, status, phase);
        PageQuery pq = PageQuery.parse(page, size, sort, total, CpSort.values(), policy.intValue(Policies.GRID_PAGE_SIZE_MAX),
                DEFAULT_SORT);
        return new CpPage(repo.findCpPage(f, pq), pq.isWithTotal() ? repo.countCps(f) : null);
    }

    /** 목록 엑셀(EXCEL) — grid 와 같은 조건·정렬, 재검토 칸은 LABEL 열(X2)이 화면과 같은 문구로 */
    @Transactional(readOnly = true)
    public long export(CpExportRequest req, HttpServletResponse response) throws IOException {
        CpExportRequest r = req == null ? new CpExportRequest(null, null, null, null, null) : req;
        List<ColumnSpec> cols = ColumnSpec.selectExportable(GRID_COLUMNS, r.columns());
        CpFilter f = filter(r.keyword(), r.status(), r.phase());
        PageQuery pq = PageQuery.sortOnly(r.sort(), CpSort.values(), DEFAULT_SORT);
        List<String> keys = new ArrayList<>();
        if (!blank(r.keyword())) {
            keys.add("keyword");
        }
        if (!blank(r.status())) {
            keys.add("status");
        }
        if (!blank(r.phase())) {
            keys.add("phase");
        }
        return excel.export(response, new ExportJob(PROG, "prog.apqpCp", cols, repo.countCps(f), keys),
                w -> repo.eachCp(f, pq, w::accept));
    }

    private CpFilter filter(String keyword, String status, String phase) {
        String st = upper(status);
        List<String> statuses;
        if (st == null) {
            statuses = List.of("DRAFT", "IN_APPROVAL", "RELEASED");
        } else if ("ALL".equals(st)) {
            statuses = null;
        } else if (Set.of("DRAFT", "IN_APPROVAL", "RELEASED", "OBSOLETE").contains(st)) {
            statuses = List.of(st);
        } else {
            throw fieldError("status", "CODE");
        }
        String ph = upper(phase);
        if (ph != null && !PHASES.contains(ph)) {
            throw fieldError("phase", "CODE");
        }
        return new CpFilter(ApqpInputs.like(keyword), statuses, ph, null);
    }

    static final List<ColumnSpec> GRID_COLUMNS = List.of(
            ColumnSpec.text("cpNo", "apqp.col.cpNo", 120).withSort().withPriority(1),
            ColumnSpec.of("rev", "apqp.col.rev", ColumnType.INT, 50).withSort(),
            ColumnSpec.code("phase", "apqp.col.phase", "APQP_CP_PHASE", 90).withSort().withPriority(1),
            ColumnSpec.text("title", "apqp.col.title", 220).withSort().withPriority(1).withFlex(),
            ColumnSpec.text("itemCd", "apqp.col.itemCd", 120).withSort(),
            ColumnSpec.text("itemNm", "apqp.col.itemNm", 160).withPriority(3),
            ColumnSpec.text("fmeaNo", "apqp.col.fmeaNo", 120).withPriority(3),
            ColumnSpec.code("status", "apqp.col.status", "APQP_FMEA_STATUS", 80).withSort().withPriority(1),
            ColumnSpec.labels("review", "apqp.col.review", "apqp.review.", 110, "ROUTING", "FMEA"),
            ColumnSpec.of("lineCnt", "apqp.col.lineCnt", ColumnType.INT, 60).withSort(),
            ColumnSpec.of("updatedAt", "apqp.col.updatedAt", ColumnType.DATETIME, 140).withSort().withPriority(3));

    public List<ColumnSpec> gridColumns() {
        return GRID_COLUMNS;
    }

    // ------------------------------------------------------------------ 상세

    @Transactional(readOnly = true)
    public CpDetail get(long cpId) {
        CpRow c = found(cpId);
        boolean editable = permission.actions(PROG).contains(Action.SAVE) && "DRAFT".equals(c.getStatus());
        List<CpLineRow> lines = repo.findLines(cpId);
        List<StepOption> steps = c.getRoutingId() == null ? List.of() : apqp.findSteps(c.getRoutingId());
        List<CharOption> chars = apqp.findChars(c.getItemId(), c.getRoutingId());
        List<RiskOption> risks = c.getFmeaId() == null ? List.of() : riskOptions(c.getFmeaId());
        Long newer = c.isFmeaStale() ? repo.findReleasedFmeaIdByNo(c.getFmeaNo()) : null;
        List<SpecialGap> gaps = "DRAFT".equals(c.getStatus()) ? gaps(c, lines, risks, chars) : List.of();
        CompareResult cmp = compare(c, lines, steps);
        return new CpDetail(c, lines, steps, chars, risks, gaps, cmp, newer, editable, approval.on(),
                changeWatch.since(ApqpDocService.CP, cpId, c.getStatus(), c.getItemId(), c.getReleasedAt(), lines.stream()
                        .filter(l -> l.getCharId() != null)
                        .map(l -> CharRef.of(l.getCharId(), l.getCharNo(), l.getCharNm(), l.getCharSpecial() != null ? l.getCharSpecial() : l.getSpecialCls()))
                        .toList(), cmp.plans()));
    }

    /** 품목의 확정 PFMEA — 근거 고르기 */
    @Transactional(readOnly = true)
    public List<FmeaOption> fmeas(String itemCd) {
        ItemOption item = blank(itemCd) ? null : apqp.findItemByCd(itemCd.strip());
        return item == null ? List.of() : repo.findReleasedFmeas(item.getItemId());
    }

    // ------------------------------------------------------------------ 머리

    @Transactional
    public long create(CpForm in) {
        LoginUser me = me();
        inbox.claimRequestKey("APQP_CP_CREATE");
        if (in == null) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        String phase = upper(in.phase());
        if (phase == null || !PHASES.contains(phase)) {
            throw fieldError("phase", phase == null ? "REQUIRED" : "CODE");
        }
        CpValue v = validate(in, null);
        apqp.ensureNumberingRule(RULE, "관리계획서 번호", "CP-{yyyy}-{seq:4}", "YEAR", me.loginId());
        long id = repo.insertCp(numbering.next(RULE), 1, phase, v, null, me.loginId());
        if (Boolean.TRUE.equals(in.pullFromFmea()) && v.fmeaId() != null) {
            pullInto(id, v.fmeaId(), me.loginId());
        }
        return id;
    }

    @Transactional
    public void update(long cpId, CpForm in) {
        LoginUser me = me();
        CpRow cur = editableHead(cpId);
        if (in == null || in.rowVer() == null) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        if (!blank(in.phase()) && !cur.getPhase().equals(upper(in.phase()))) {
            throw fieldError("phase", "FIXED");
        }
        CpValue v = validate(in, cur);
        List<CpLineRow> lines = repo.findLines(cpId);
        if (!Objects.equals(v.routingId(), cur.getRoutingId()) && lines.stream().anyMatch(l -> l.getStepId() != null)) {
            throw fieldError("routingId", "APQP_ROUTING_USED");
        }
        if (!Objects.equals(v.fmeaId(), cur.getFmeaId()) && lines.stream().anyMatch(l -> l.getRiskId() != null)) {
            throw fieldError("fmeaId", "APQP_CP_FMEA_USED");
        }
        if (repo.updateCp(cpId, v, in.rowVer(), me.loginId()) == 0) {
            throw new BusinessException(HbErrorCode.CONFLICT);
        }
    }

    /** 작성 중 리비전 지우기(행 모두) — 확정본은 지우지 않는다 */
    @Transactional
    public void delete(long cpId, Integer rowVer) {
        editableHead(cpId);
        if (rowVer == null) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        List<Long> ids = repo.findLines(cpId).stream().map(CpLineRow::getLineId).toList();
        if (!ids.isEmpty()) {
            repo.deleteLines(ids);
        }
        if (repo.deleteCp(cpId, rowVer) == 0) {
            throw new BusinessException(HbErrorCode.CONFLICT);
        }
    }

    /** 확정(APPROVE, 결재 정책 N) — 조건 미달이면 행마다 사유. 같은 번호의 이전 확정본은 폐기 */
    @Transactional
    public void release(long cpId, Integer rowVer) {
        LoginUser me = me();
        CpRow c = editableHead(cpId);
        if (rowVer == null) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        approval.requireDirect();
        releaseCheck(c);
        repo.snapshotLines(cpId);
        repo.obsoleteReleased(c.getCpNo(), me.loginId());
        if (repo.releaseCp(cpId, rowVer, me.loginId()) == 0) {
            throw new BusinessException(HbErrorCode.CONFLICT);
        }
    }

    /**
     * 결재 상신(정책 APQP_RELEASE_APPROVAL = Y, A3-7) — 확정 조건을 먼저 보고 규격 스냅샷을 찍는다(결재 중엔 행을 못 고치므로 지금).
     * 승인 = 확정(CpApprovalDocType), 반려·회수 = 작성 중(스냅샷은 다음 확정·상신 때 다시 찍힌다)
     */
    @Transactional
    public long submit(long cpId, SubmitForm in) {
        LoginUser me = me();
        inbox.claimRequestKey("APQP_CP_SUBMIT");
        CpRow c = editableHead(cpId);
        if (in == null || in.rowVer() == null) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        if (in.rowVer() != c.getRowVer()) {
            throw new BusinessException(HbErrorCode.CONFLICT);
        }
        releaseCheck(c);
        String title = blank(in.title()) ? "관리계획서 " + c.getCpNo() + " rev " + c.getRev() + " " + c.getTitle() : in.title();
        long aprId = approval.submit(CpApprovalDocType.DOC_TYPE, cpId, title, in.lines());
        repo.snapshotLines(cpId);
        if (repo.submitCp(cpId, aprId, in.rowVer(), me.loginId()) == 0) {
            throw new BusinessException(HbErrorCode.CONFLICT);
        }
        return aprId;
    }

    /** 확정 조건(바로 확정·상신 공통) — 미달이면 APQP_RELEASE_CHECK + 행 키 */
    private void releaseCheck(CpRow c) {
        long cpId = c.getCpId();
        List<CpLineRow> lines = repo.findLines(cpId);
        FieldErrors e = FieldErrors.create();
        if (lines.isEmpty()) {
            e.add("lines", "APQP_CP_NO_LINES");
        }
        if (c.getRev() > 1 && blank(c.getChangeReason())) {
            e.add("changeReason", "REQUIRED");
        }
        if (c.getFmeaId() != null && "OBSOLETE".equals(c.getFmeaStatus())) {
            e.add("fmeaId", "APQP_CP_FMEA_STALE");
        }
        for (CpLineRow l : lines) {
            if (blank(l.getReactionPlan())) {
                e.row("LINE:" + l.getLineId()).add("reactionPlan", "REQUIRED");
            }
            if (("INSPECT".equals(l.getCtrlMethod()) || "SPC".equals(l.getCtrlMethod())) && l.getFreqType() == null) {
                e.row("LINE:" + l.getLineId()).add("freqType", "REQUIRED");
            }
        }
        List<RiskOption> risks = c.getFmeaId() == null ? List.of() : riskOptions(c.getFmeaId());
        for (SpecialGap g : gaps(c, lines, risks, apqp.findChars(c.getItemId(), c.getRoutingId()))) {
            e.row(g.kind() + ":" + g.id()).add("specialCls", "APQP_CP_SPECIAL_MISSING");
        }
        // 양산 단계: 검사계획에 없는 검사 행이 있으면 거부(C6) — QMS 가 없는 설치본은 대조하지 않는다
        if ("PRODUCTION".equals(c.getPhase())) {
            List<StepOption> steps = c.getRoutingId() == null ? List.of() : apqp.findSteps(c.getRoutingId());
            for (CompareRow r : compare(c, lines, steps).rows()) {
                if ("MISSING_IN_PLAN".equals(r.kind())) {
                    e.row("LINE:" + r.lineId()).add("inspType", "APQP_CP_NOT_IN_PLAN");
                }
            }
        }
        if (!e.isEmpty()) {
            throw new BusinessException(HbErrorCode.APQP_RELEASE_CHECK).withFieldErrors(e.list());
        }
    }

    /** 새 리비전 = 확정본 복사(작성 중, 스냅샷 비움). 근거 PFMEA 를 바꾸면 행의 근거 위험행은 비운다(새 FMEA 에서 다시 끌어오기) */
    @Transactional
    public long revise(long cpId, CpCopyForm in) {
        LoginUser me = me();
        inbox.claimRequestKey("APQP_CP_REVISE");
        String status = lockStatus(cpId);
        CpRow src = found(cpId);
        if (!"RELEASED".equals(status) || repo.draftExists(src.getCpNo())) {
            throw new BusinessException(HbErrorCode.APQP_CP_STATE);
        }
        FmeaRow b = basis(src, in);
        Long fmeaId = b == null ? null : b.getFmeaId();
        Long routingId = b == null ? src.getRoutingId() : b.getRoutingId();
        long id = repo.insertCp(src.getCpNo(), src.getRev() + 1, src.getPhase(), new CpValue(src.getItemId(), routingId, fmeaId,
                src.getProjId(), src.getTitle(), src.getTeam(), null), cpId, me.loginId());
        copyLines(cpId, id, !Objects.equals(fmeaId, src.getFmeaId()), src.getRoutingId(), routingId, me.loginId());
        return id;
    }

    /** 다음 단계 = 새 번호로 복사(C1) — 시작품 → 양산 전 → 양산 순서로만 */
    @Transactional
    public long nextPhase(long cpId, CpCopyForm in) {
        LoginUser me = me();
        inbox.claimRequestKey("APQP_CP_NEXT_PHASE");
        String status = lockStatus(cpId);
        CpRow src = found(cpId);
        if (!"RELEASED".equals(status)) {
            throw new BusinessException(HbErrorCode.APQP_CP_STATE);
        }
        String phase = in == null ? null : upper(in.phase());
        if (phase == null || !PHASES.contains(phase)) {
            throw fieldError("phase", phase == null ? "REQUIRED" : "CODE");
        }
        if (PHASES.indexOf(phase) <= PHASES.indexOf(src.getPhase())) {
            throw fieldError("phase", "APQP_CP_PHASE_ORDER");
        }
        FmeaRow b = basis(src, in);
        Long fmeaId = b == null ? null : b.getFmeaId();
        Long routingId = b == null ? src.getRoutingId() : b.getRoutingId();
        FieldErrors e = FieldErrors.create();
        String title = in.title() == null ? src.getTitle() : text(in.title(), "title", 200, true, e);
        e.throwIfAny();
        apqp.ensureNumberingRule(RULE, "관리계획서 번호", "CP-{yyyy}-{seq:4}", "YEAR", me.loginId());
        long id = repo.insertCp(numbering.next(RULE), 1, phase, new CpValue(src.getItemId(), routingId, fmeaId, src.getProjId(),
                title, src.getTeam(), null), cpId, me.loginId());
        copyLines(cpId, id, !Objects.equals(fmeaId, src.getFmeaId()), src.getRoutingId(), routingId, me.loginId());
        return id;
    }

    /**
     * 복사 때 근거 PFMEA — 지정하면 그것(같은 품목의 확정본), 아니면 지금 것(폐기됐으면 골라야). 라우팅이 달라도 된다(11.9) —
     * 새 관리계획서는 근거 PFMEA 의 라우팅을 따르고 행 단계는 {@link RoutingRelink} 규칙으로 다시 잇는다
     */
    private FmeaRow basis(CpRow src, CpCopyForm in) {
        Long fmeaId = in == null || in.fmeaId() == null ? src.getFmeaId() : in.fmeaId();
        if (fmeaId == null) {
            return null;
        }
        FmeaRow f = apqp.findFmea(fmeaId);
        // 관리계획서 근거는 PFMEA 만(DFMEA 는 공정 단계가 없다, A5-2)
        if (f == null || f.getItemId() != src.getItemId() || !FmeaService.PFMEA.equals(f.getFmeaType())) {
            throw fieldError("fmeaId", "NOT_FOUND");
        }
        if (!"RELEASED".equals(f.getStatus())) {
            throw fieldError("fmeaId", "APQP_CP_FMEA_STALE");
        }
        return f;
    }

    /**
     * 행 복사. 라우팅이 바뀌면(11.9) 단계를 같은 op_seq + 같은 공정의 새 단계로, 짝이 없으면 글자 단계("10 용접")로 —
     * 새 행이라 옛 단계는 원본 리비전 행에 그대로 남는다(다시 이을 때 보면 된다)
     */
    private void copyLines(long fromCp, long toCp, boolean dropRisk, Long fromRouting, Long toRouting, String by) {
        boolean moved = !Objects.equals(fromRouting, toRouting);
        List<StepOption> from = moved && fromRouting != null ? apqp.findSteps(fromRouting) : List.of();
        Map<Long, StepOption> fromById = from.stream().collect(Collectors.toMap(StepOption::getStepId, x -> x));
        Map<Long, StepOption> pairs = moved && toRouting != null ? RoutingRelink.pair(from, apqp.findSteps(toRouting)) : Map.of();
        for (CpLineRow l : repo.findLines(fromCp)) {
            if (dropRisk) {
                l.setRiskId(null);
            }
            if (moved && l.getStepId() != null) {
                StepOption to = pairs.get(l.getStepId());
                if (to != null) {
                    l.setStepId(to.getStepId());
                } else {
                    l.setStepText(RoutingRelink.text(fromById.get(l.getStepId()), l.getStepText() == null ? "-" : l.getStepText()));
                    l.setStepId(null);
                }
            }
            repo.insertLine(toCp, l, by);
        }
    }

    private CpValue validate(CpForm in, CpRow cur) {
        FieldErrors e = FieldErrors.create();
        long itemId = 0;
        if (cur != null) {
            itemId = cur.getItemId();
            if (!blank(in.itemCd()) && !cur.getItemCd().equals(in.itemCd().strip())) {
                e.add("itemCd", "FIXED");
            }
        } else if (blank(in.itemCd())) {
            e.add("itemCd", "REQUIRED");
        } else {
            ItemOption item = apqp.findItemByCd(in.itemCd().strip());
            if (item == null) {
                e.add("itemCd", "NO_SUCH_CODE");
            } else {
                itemId = item.getItemId();
            }
        }
        Long routingId = in.routingId();
        // 근거 PFMEA 를 고르면 라우팅은 그 FMEA 의 것(C2)
        if (in.fmeaId() != null && itemId != 0 && (cur == null || !in.fmeaId().equals(cur.getFmeaId()))) {
            FmeaRow f = apqp.findFmea(in.fmeaId());
            if (f == null || f.getItemId() != itemId || !FmeaService.PFMEA.equals(f.getFmeaType())) {
                e.add("fmeaId", "NOT_FOUND");
            } else if (!"RELEASED".equals(f.getStatus())) {
                e.add("fmeaId", "APQP_CP_FMEA_STALE");
            } else {
                routingId = f.getRoutingId();
            }
        } else if (in.fmeaId() != null && cur != null) {
            routingId = cur.getRoutingId();
        }
        if (routingId != null && itemId != 0 && (cur == null || !routingId.equals(cur.getRoutingId()))) {
            Long r = routingId;
            if (apqp.findRoutings(itemId).stream().noneMatch(x -> x.getRoutingId() == r)) {
                e.add("routingId", "NOT_FOUND");
            }
        }
        if (in.projId() != null && apqp.findProjects().stream().noneMatch(p -> p.getProjId() == in.projId())
                && (cur == null || !in.projId().equals(cur.getProjId()))) {
            e.add("projId", "NOT_FOUND");
        }
        String title = text(in.title(), "title", 200, true, e);
        String team = text(in.team(), "team", 500, false, e);
        String reason = text(in.changeReason(), "changeReason", 500, false, e);
        e.throwIfAny();
        return new CpValue(itemId, routingId, in.fmeaId(), in.projId(), title, team, reason);
    }

    // ------------------------------------------------------------------ 행

    @Transactional
    public long addLine(long cpId, CpLineForm in) {
        LoginUser me = me();
        CpRow c = editableHead(cpId);
        List<CpLineRow> lines = repo.findLines(cpId);
        if (lines.size() >= MAX_LINES) {
            throw fieldError("lines", "APQP_CP_TOO_MANY_LINES", MAX_LINES);
        }
        CpLineRow v = lineValue(c, in, null, lines);
        v.setLineNo(lines.stream().mapToInt(CpLineRow::getLineNo).max().orElse(0) + 1);
        long id = repo.insertLine(cpId, v, me.loginId());
        repo.touchCp(cpId, me.loginId());
        return id;
    }

    @Transactional
    public void updateLine(long lineId, CpLineForm in) {
        LoginUser me = me();
        long cpId = cpOf(lineId);
        CpRow c = editableHead(cpId);
        if (in == null || in.rowVer() == null) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        List<CpLineRow> lines = repo.findLines(cpId);
        CpLineRow cur = lines.stream().filter(l -> l.getLineId() == lineId).findFirst().orElseThrow();
        CpLineRow v = lineValue(c, in, cur, lines);
        if (repo.updateLine(lineId, v, in.rowVer(), me.loginId()) == 0) {
            throw new BusinessException(HbErrorCode.CONFLICT);
        }
        repo.touchCp(cpId, me.loginId());
    }

    @Transactional
    public void deleteLine(long lineId, Integer rowVer) {
        LoginUser me = me();
        long cpId = cpOf(lineId);
        editableHead(cpId);
        CpLineRow cur = repo.findLines(cpId).stream().filter(l -> l.getLineId() == lineId).findFirst().orElseThrow();
        if (rowVer == null || rowVer != cur.getRowVer()) {
            throw new BusinessException(HbErrorCode.CONFLICT);
        }
        repo.deleteLines(List.of(lineId));
        repo.touchCp(cpId, me.loginId());
    }

    /** 순서 — 위·아래 이웃과 순번을 맞바꾼다(순번 유일은 커밋 시점 검사) */
    @Transactional
    public void moveLine(long lineId, MoveForm in) {
        LoginUser me = me();
        long cpId = cpOf(lineId);
        editableHead(cpId);
        List<CpLineRow> lines = repo.findLines(cpId);
        int i = -1;
        for (int k = 0; k < lines.size(); k++) {
            if (lines.get(k).getLineId() == lineId) {
                i = k;
            }
        }
        if (in == null || in.rowVer() == null || in.rowVer() != lines.get(i).getRowVer()) {
            throw new BusinessException(HbErrorCode.CONFLICT);
        }
        String op = upper(in.op());
        int j = "UP".equals(op) ? i - 1 : "DOWN".equals(op) ? i + 1 : -2;
        if (j == -2) {
            throw fieldError("op", "CODE");
        }
        if (j < 0 || j >= lines.size()) {
            return;
        }
        CpLineRow a = lines.get(i);
        CpLineRow b = lines.get(j);
        repo.updateLineNo(a.getLineId(), b.getLineNo(), me.loginId());
        repo.updateLineNo(b.getLineId(), a.getLineNo(), me.loginId());
        repo.touchCp(cpId, me.loginId());
    }

    /** PFMEA 에서 끌어오기(C7) — 작성 중만, 추가만 */
    @Transactional
    public PullResult pull(long cpId) {
        LoginUser me = me();
        CpRow c = editableHead(cpId);
        if (c.getFmeaId() == null) {
            throw fieldError("fmeaId", "REQUIRED");
        }
        PullResult r = pullInto(cpId, c.getFmeaId(), me.loginId());
        if (r.added() > 0) {
            repo.touchCp(cpId, me.loginId());
        }
        return r;
    }

    private PullResult pullInto(long cpId, long fmeaId, String by) {
        List<CpLineRow> lines = new ArrayList<>(repo.findLines(cpId));
        int no = lines.stream().mapToInt(CpLineRow::getLineNo).max().orElse(0);
        int added = 0;
        int skipped = 0;
        for (RiskOption k : riskOptions(fmeaId)) {
            String method = !blank(k.detCtrl()) ? "INSPECT" : !blank(k.prevCtrl()) ? "WORK_STD" : "OTHER";
            boolean exists = lines.stream().anyMatch(l -> Objects.equals(l.getRiskId(), k.riskId())
                    || (k.stepId() != null && k.charId() != null && Objects.equals(l.getStepId(), k.stepId())
                            && Objects.equals(l.getCharId(), k.charId()) && method.equals(l.getCtrlMethod())));
            if (exists || lines.size() >= MAX_LINES) {
                skipped++;
                continue;
            }
            CpLineRow l = new CpLineRow();
            l.setLineNo(++no);
            l.setStepId(k.stepId());
            l.setStepText(k.stepId() == null ? cut(k.stepNm(), 200) : null);
            l.setCharId(k.charId());
            l.setCharText(k.charId() == null ? cut(k.fm(), 200) : null);
            l.setCtrlMethod(method);
            l.setEvalMethod(cut(!blank(k.detCtrl()) ? k.detCtrl() : k.prevCtrl(), 500));
            l.setRiskId(k.riskId());
            l.setSpecialCls(k.specialCls() == null || "NONE".equals(k.specialCls()) ? null : k.specialCls());
            l.setLineId(repo.insertLine(cpId, l, by));
            lines.add(l);
            added++;
        }
        return new PullResult(added, skipped);
    }

    private CpLineRow lineValue(CpRow c, CpLineForm in, CpLineRow cur, List<CpLineRow> lines) {
        if (in == null) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        FieldErrors e = FieldErrors.create();
        CpLineRow v = new CpLineRow();
        StepOption step = null;
        if (in.stepId() != null) {
            List<StepOption> steps = c.getRoutingId() == null ? List.of() : apqp.findSteps(c.getRoutingId());
            step = steps.stream().filter(s -> s.getStepId() == in.stepId()).findFirst().orElse(null);
            if (step == null) {
                e.add("stepId", "APQP_STEP_ROUTING");
            }
            v.setStepId(in.stepId());
        } else {
            v.setStepText(text(in.stepText(), "stepText", 200, true, e));
        }
        if (in.charId() != null) {
            CharOption ch = apqp.findChars(c.getItemId(), c.getRoutingId()).stream().filter(x -> x.getCharId() == in.charId()).findFirst()
                    .orElse(null);
            if (ch == null || ("PROCESS".equals(ch.getCharType()) && step != null && !Objects.equals(ch.getProcCd(), step.getProcCd()))) {
                e.add("charId", "APQP_CHAR_TARGET");
            }
            v.setCharId(in.charId());
        } else {
            v.setCharText(text(in.charText(), "charText", 200, true, e));
        }
        String method = upper(in.ctrlMethod());
        if (method == null) {
            e.add("ctrlMethod", "REQUIRED");
        } else if (!CTRL_METHODS.contains(method)) {
            e.add("ctrlMethod", "CODE");
        }
        v.setCtrlMethod(method);
        v.setEvalMethod(text(in.evalMethod(), "evalMethod", 500, false, e));
        String gauge = upper(in.gaugeClass());
        if (gauge != null && !apqp.codeUsable("GAUGE_CLASS", gauge)) {
            e.add("gaugeClass", "CODE");
        }
        v.setGaugeClass(gauge);
        String inspType = upper(in.inspType());
        if (inspType != null && !INSP_TYPES.contains(inspType)) {
            e.add("inspType", "CODE");
        }
        v.setInspType(inspType);
        if (in.sampleN() != null && (in.sampleN() < 1 || in.sampleN() > 1_000_000)) {
            e.add("sampleN", "RANGE", 1, 1_000_000);
        }
        v.setSampleN(in.sampleN());
        v.setSampleText(text(in.sampleText(), "sampleText", 100, false, e));
        String freq = upper(in.freqType());
        if (freq != null && !FREQ_TYPES.contains(freq)) {
            e.add("freqType", "CODE");
        } else if ("EVERY_N".equals(freq) && (in.freqN() == null || in.freqN() < 1 || in.freqN() > 1_000_000)) {
            e.add("freqN", "RANGE", 1, 1_000_000);
        } else if ("EVERY_HOURS".equals(freq) && (in.freqN() == null || in.freqN() < 1 || in.freqN() > 168)) {
            e.add("freqN", "RANGE", 1, 168);
        }
        v.setFreqType(freq);
        v.setFreqN("EVERY_N".equals(freq) || "EVERY_HOURS".equals(freq) ? in.freqN() : null);
        v.setReactionPlan(text(in.reactionPlan(), "reactionPlan", 500, false, e));
        if (in.riskId() != null && (c.getFmeaId() == null || apqp.findRisks(c.getFmeaId()).stream().noneMatch(r -> r.getRiskId() == in.riskId()))) {
            e.add("riskId", "NOT_FOUND");
        }
        v.setRiskId(in.riskId());
        String special = upper(in.specialCls());
        if (special != null && !apqp.codeUsable("SPECIAL_CHAR", special)) {
            e.add("specialCls", "CODE");
        }
        v.setSpecialCls(special);
        // 같은 단계 × 특성 × 관리 방법 두 번 금지(키로 가리킬 때)
        if (v.getStepId() != null && v.getCharId() != null && method != null && lines.stream().anyMatch(l -> (cur == null
                || l.getLineId() != cur.getLineId()) && Objects.equals(l.getStepId(), v.getStepId())
                && Objects.equals(l.getCharId(), v.getCharId()) && method.equals(l.getCtrlMethod()))) {
            e.add("charId", "DUPLICATE");
        }
        e.throwIfAny();
        return v;
    }

    // ------------------------------------------------------------------ 근거 PFMEA · 특별특성 점검

    /** 근거 PFMEA 위험행 → 단계(FM 이 달린 STEP 노드)·특성(FC 기능 → 없으면 FM 기능)·관리 — 단계 순서대로 */
    private List<RiskOption> riskOptions(long fmeaId) {
        Map<Long, NodeRow> nodes = apqp.findNodes(fmeaId).stream().collect(Collectors.toMap(NodeRow::getNodeId, Function.identity()));
        Map<Long, FuncRow> funcs = apqp.findFuncs(fmeaId).stream().collect(Collectors.toMap(FuncRow::getFuncId, Function.identity()));
        Map<Long, FailRow> fails = apqp.findFails(fmeaId).stream().collect(Collectors.toMap(FailRow::getFailId, Function.identity()));
        List<RiskOption> out = new ArrayList<>();
        Map<Long, Integer> order = new HashMap<>();
        for (RiskRow r : apqp.findRisks(fmeaId)) {
            FailRow fm = fails.get(r.getFmId());
            FailRow fc = fails.get(r.getFcId());
            FuncRow fmFunc = fm == null ? null : funcs.get(fm.getFuncId());
            FuncRow fcFunc = fc == null ? null : funcs.get(fc.getFuncId());
            NodeRow step = fmFunc == null ? null : nodes.get(fmFunc.getNodeId());
            FuncRow charFunc = fcFunc != null && fcFunc.getCharId() != null ? fcFunc : fmFunc;
            out.add(new RiskOption(r.getRiskId(), step == null ? null : step.getStepId(), step == null ? null : step.getNodeNm(),
                    fm == null ? null : fm.getFailText(), fc == null ? null : fc.getFailText(),
                    charFunc == null ? null : charFunc.getCharId(), charFunc == null ? null : charFunc.getCharNo(), r.getDetCtrl(),
                    r.getPrevCtrl(), r.getAp(), r.getSpecialCls()));
            order.put(r.getRiskId(), step == null ? Integer.MAX_VALUE : step.getSeq());
        }
        out.sort(Comparator.comparing((RiskOption o) -> order.get(o.riskId())).thenComparing(RiskOption::riskId));
        return out;
    }

    /**
     * 특별특성 중 행이 없는 것 — ① 근거 위험행에 특별특성(NONE 아님) → 그 위험행이나 그 특성을 가리키는 행
     * ② 품목 제품 특성·라우팅 공정 특성 중 특별특성 → 그 특성을 가리키는 행 (IATF 공개 요지 — 특별특성은 관리계획에)
     */
    private static List<SpecialGap> gaps(CpRow c, List<CpLineRow> lines, List<RiskOption> risks, List<CharOption> chars) {
        Set<Long> lineChars = lines.stream().map(CpLineRow::getCharId).filter(Objects::nonNull).collect(Collectors.toSet());
        Set<Long> lineRisks = lines.stream().map(CpLineRow::getRiskId).filter(Objects::nonNull).collect(Collectors.toSet());
        Map<String, SpecialGap> out = new LinkedHashMap<>();
        for (RiskOption r : risks) {
            if (r.specialCls() != null && !"NONE".equals(r.specialCls()) && !lineRisks.contains(r.riskId())
                    && (r.charId() == null || !lineChars.contains(r.charId()))) {
                out.put("RISK:" + r.riskId(), new SpecialGap("RISK", r.riskId(), r.charId(), r.charNo(), null, r.specialCls(),
                        (r.stepNm() == null ? "" : r.stepNm() + " · ") + r.fm() + " ← " + r.fc()));
            }
        }
        for (CharOption ch : chars) {
            if (ch.getSpecialCls() != null && !"NONE".equals(ch.getSpecialCls()) && !lineChars.contains(ch.getCharId())) {
                out.putIfAbsent("CHAR:" + ch.getCharId(), new SpecialGap("CHAR", ch.getCharId(), ch.getCharId(), ch.getCharNo(), ch.getCharNm(),
                        ch.getSpecialCls(), null));
            }
        }
        return new ArrayList<>(out.values());
    }

    /**
     * 검사계획 대조(C5) — 검사 유형·특성 키가 있는 행만. 공정 = 행 단계의 공정(글자 단계면 공정 무관 계획), 기준일 = 오늘(회사 날짜).
     * 같은 (공정, 검사 유형) 은 한 번만 묻는다. 샘플링은 CP 가 글자라 표시만(비교하지 않음)
     */
    private CompareResult compare(CpRow c, List<CpLineRow> lines, List<StepOption> steps) {
        InspPlanLookup look = InspPlanLookup.of(inspPlans);
        if (look == InspPlanLookup.NONE) {
            return new CompareResult(false, 0, 0, List.of(), List.of());
        }
        Map<Long, StepOption> stepById = steps.stream().collect(Collectors.toMap(StepOption::getStepId, Function.identity()));
        java.time.LocalDate today = clock.today();
        Map<String, List<PlanLine>> plans = new LinkedHashMap<>();
        Map<String, String> procOfKey = new HashMap<>();
        Map<String, Set<Long>> cpChars = new HashMap<>();
        List<CompareRow> rows = new ArrayList<>();
        int matched = 0;
        int skipped = 0;
        for (CpLineRow l : lines) {
            if (l.getInspType() == null || l.getCharId() == null) {
                skipped++;
                continue;
            }
            StepOption step = l.getStepId() == null ? null : stepById.get(l.getStepId());
            Long procId = step == null ? null : step.getProcId();
            String key = procId + "|" + l.getInspType();
            List<PlanLine> plan = plans.computeIfAbsent(key, k -> look.lines(c.getItemId(), procId, l.getInspType(), today));
            procOfKey.putIfAbsent(key, step == null ? null : step.getProcCd());
            cpChars.computeIfAbsent(key, k -> new java.util.HashSet<>()).add(l.getCharId());
            PlanLine p = plan.stream().filter(x -> x.charId() == l.getCharId()).findFirst().orElse(null);
            String cpSample = l.getSampleN() == null ? l.getSampleText() : l.getSampleN() + (l.getSampleText() == null ? "" : " " + l.getSampleText());
            if (p == null) {
                rows.add(new CompareRow("MISSING_IN_PLAN", l.getLineId(), l.getCharId(), l.getCharNo(), l.getCharNm(), l.getInspType(),
                        step == null ? null : step.getProcCd(), null, null, l.getGaugeClass(), null, l.getFreqType(), l.getFreqN(), null, null,
                        cpSample, null));
                continue;
            }
            boolean gaugeDiff = l.getGaugeClass() != null && p.gaugeClass() != null && !l.getGaugeClass().equals(p.gaugeClass());
            boolean freqDiff = l.getFreqType() != null && (!l.getFreqType().equals(p.freqType()) || !Objects.equals(l.getFreqN(), p.freqN()));
            if (gaugeDiff || freqDiff) {
                rows.add(new CompareRow("DIFF", l.getLineId(), l.getCharId(), l.getCharNo(), l.getCharNm(), l.getInspType(),
                        step == null ? null : step.getProcCd(), p.inspPlanId(), p.rev(), l.getGaugeClass(), p.gaugeClass(), l.getFreqType(),
                        l.getFreqN(), p.freqType(), p.freqN(), cpSample, p.samplingPlanCd()));
            } else {
                matched++;
            }
        }
        for (Map.Entry<String, List<PlanLine>> en : plans.entrySet()) {
            String inspType = en.getKey().substring(en.getKey().indexOf('|') + 1);
            Set<Long> have = cpChars.getOrDefault(en.getKey(), Set.of());
            for (PlanLine p : en.getValue()) {
                if (!have.contains(p.charId())) {
                    rows.add(new CompareRow("MISSING_IN_CP", null, p.charId(), p.charNo(), p.charNm(), inspType, procOfKey.get(en.getKey()),
                            p.inspPlanId(), p.rev(), null, p.gaugeClass(), null, null, p.freqType(), p.freqN(), null, p.samplingPlanCd()));
                }
            }
        }
        Map<Long, ApqpDtos.PlanRef> seen = new LinkedHashMap<>();
        for (Map.Entry<String, List<PlanLine>> en : plans.entrySet()) {
            String inspType = en.getKey().substring(en.getKey().indexOf('|') + 1);
            en.getValue().forEach(p -> seen.putIfAbsent(p.inspPlanId(),
                    new ApqpDtos.PlanRef(p.inspPlanId(), p.rev(), inspType, procOfKey.get(en.getKey()))));
        }
        return new CompareResult(true, matched, skipped, rows, List.copyOf(seen.values()));
    }

    private static String cut(String s, int max) {
        if (s == null) {
            return null;
        }
        String t = s.strip();
        return t.length() > max ? t.substring(0, max) : t;
    }

    // ------------------------------------------------------------------ 공통

    private CpRow found(long cpId) {
        CpRow c = repo.findCp(cpId);
        if (c == null) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        return c;
    }

    private String lockStatus(long cpId) {
        String status = repo.lockCp(cpId);
        if (status == null) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        return status;
    }

    /** 머리를 잠그고 작성 중인지 — 아니면 409 APQP_CP_STATE */
    private CpRow editableHead(long cpId) {
        if (!"DRAFT".equals(lockStatus(cpId))) {
            throw new BusinessException(HbErrorCode.APQP_CP_STATE);
        }
        return found(cpId);
    }

    private long cpOf(long lineId) {
        Long c = repo.cpOfLine(lineId);
        if (c == null) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        return c;
    }
}
