package io.smarthappy.module.cms;

import static io.smarthappy.module.cms.CmsSupport.blank;
import static io.smarthappy.module.cms.CmsSupport.date;
import static io.smarthappy.module.cms.CmsSupport.like;
import static io.smarthappy.module.cms.CmsSupport.me;
import static io.smarthappy.module.cms.CmsSupport.text;

import java.io.IOException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.smarthappy.module.cms.CmsDtos.TargetRow;
import io.smarthappy.module.cms.CmsRiskDtos.CopyForm;
import io.smarthappy.module.cms.CmsRiskDtos.ItemForm;
import io.smarthappy.module.cms.CmsRiskDtos.ItemRow;
import io.smarthappy.module.cms.CmsRiskDtos.LineIn;
import io.smarthappy.module.cms.CmsRiskDtos.MemberForm;
import io.smarthappy.module.cms.CmsRiskDtos.MemberRow;
import io.smarthappy.module.cms.CmsRiskDtos.WorkerOption;
import io.smarthappy.module.cms.CmsRiskDtos.NotifyForm;
import io.smarthappy.module.cms.CmsRiskDtos.ProgressForm;
import io.smarthappy.module.cms.CmsRiskDtos.RaForm;
import io.smarthappy.module.cms.CmsRiskDtos.RaPage;
import io.smarthappy.module.cms.CmsRiskDtos.RaRow;
import io.smarthappy.module.cms.CmsRiskDtos.RaSort;
import io.smarthappy.module.cms.CmsRiskDtos.ReleaseForm;
import io.smarthappy.platform.approval.ApprovalService;
import io.smarthappy.platform.approval.ApprovalService.LineSpec;
import io.smarthappy.platform.approval.ApprovalService.LineType;
import io.smarthappy.platform.auth.Action;
import io.smarthappy.platform.auth.PermissionService;
import io.smarthappy.platform.code.CodeService;
import io.smarthappy.platform.common.db.PgErrors;
import io.smarthappy.platform.common.query.DateRanges;
import io.smarthappy.platform.common.query.PageQuery;
import io.smarthappy.platform.common.web.BusinessException;
import io.smarthappy.platform.common.web.FieldErrors;
import io.smarthappy.platform.common.web.HbErrorCode;
import io.smarthappy.platform.excel.ExcelExportService;
import io.smarthappy.platform.excel.ExcelExportService.ExportJob;
import io.smarthappy.platform.file.FileService;
import io.smarthappy.platform.grid.ColumnSpec;
import io.smarthappy.platform.grid.ColumnSpec.ColumnType;
import io.smarthappy.platform.messaging.InboxService;
import io.smarthappy.platform.numbering.NumberingService;
import io.smarthappy.platform.policy.Policies;
import io.smarthappy.platform.policy.PolicyService;
import io.smarthappy.platform.security.LoginUser;
import io.smarthappy.platform.time.BizClock;
import io.smarthappy.platform.user.UserDirectory;
import io.smarthappy.platform.user.UserRef;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;

/**
 * 위험성평가 (cms-risk) — docs/33 4.7, D12·D17. 작성 중(DRAFT)에만 내용을 고치고, 확정(RELEASED) 뒤에는 개선 진행·주지만.
 * 확정 = 정책 CMS_RA_APPROVAL Y 면 결재(CMS_RA), N 이면 APPROVE 권한으로 바로. 확정 검사 = RiskMatrix.releaseProblems.
 */
@Service
@RequiredArgsConstructor
public class RiskService {

    static final String PROG = "cms-risk";
    static final String RULE = "CMS_RA";
    static final int MAX_ROWS = 1000;
    static final int MAX_DAYS = 3660;   // 평가는 해마다 몇 건 — 10년까지 한 번에
    static final int MAX_ITEMS = 500;
    static final Set<String> TYPES = Set.of("INITIAL", "REGULAR", "ADHOC", "CONTINUOUS");
    static final Set<String> METHODS = Set.of("FREQ_SEV", "THREE_STEP");
    static final Set<String> STATUSES = Set.of("DRAFT", "IN_APPROVAL", "RELEASED", "OBSOLETE");
    static final Set<String> LEVELS = Set.of("LOW", "MEDIUM", "HIGH");
    /** 유해·위험요인 분류 = 공통코드 CMS_HAZARD_CAT(시스템 7종 + 회사 코드, docs/68) */
    static final String CAT_GRP = "CMS_HAZARD_CAT";
    static final Set<String> ROLES = Set.of("MANAGER", "SUPERVISOR", "SAFETY", "WORKER_REP", "WORKER", "OTHER");

    /** 위험성평가표 엑셀 열 */
    static final List<ColumnSpec> COLUMNS = List.of(
            ColumnSpec.of("seq", "cms.risk.col.seq", ColumnType.INT, 60),
            ColumnSpec.text("taskText", "cms.risk.col.task", 180),
            ColumnSpec.text("equipCd", "cms.risk.col.equip", 100),
            ColumnSpec.code("hazardCat", "cms.risk.col.hazardCat", "CMS_HAZARD_CAT", 110),
            ColumnSpec.text("hazardText", "cms.risk.col.hazard", 220),
            ColumnSpec.text("currentMeasure", "cms.risk.col.currentMeasure", 200),
            ColumnSpec.of("likelihood", "cms.risk.col.likelihood", ColumnType.INT, 70),
            ColumnSpec.of("severity", "cms.risk.col.severity", ColumnType.INT, 70),
            ColumnSpec.code("riskLevel", "cms.risk.col.level", "CMS_RISK_LEVEL", 80),
            ColumnSpec.text("planMeasure", "cms.risk.col.plan", 200),
            ColumnSpec.text("ownerNm", "cms.risk.col.owner", 90),
            ColumnSpec.of("dueDate", "cms.risk.col.due", ColumnType.DATE, 100),
            ColumnSpec.of("doneDate", "cms.risk.col.done", ColumnType.DATE, 100),
            ColumnSpec.code("residualLevel", "cms.risk.col.residual", "CMS_RISK_LEVEL", 90));

    private final CmsRiskMapper mapper;
    private final CmsMapper cms;
    private final UserDirectory users;
    private final RiskReleaser releaser;
    private final ApprovalService approvals;
    private final PermissionService permission;
    private final NumberingService numbering;
    private final PolicyService policies;
    private final DateRanges ranges;
    private final InboxService inbox;
    private final CodeService codes;
    private final FileService files;
    private final ExcelExportService excel;
    private final BizClock clock;

    // ------------------------------------------------------------------ 조회

    /** 평가일 기간(필수) + 상태(여러 개 쉼표)·검색어 */
    @Transactional(readOnly = true)
    public List<RaRow> list(String from, String to, String status, String keyword) {
        RaCrit c = criteria(from, to, status, keyword);
        List<RaRow> rows = mapper.findRas(c.from(), c.to(), c.statuses(), c.keyword(), clock.today(), MAX_ROWS + 1);
        if (rows.size() > MAX_ROWS) {
            throw new BusinessException(HbErrorCode.CMS_TOO_MANY_ROWS, MAX_ROWS);
        }
        rows.forEach(r -> r.setReleasedAt(CmsSupport.at(r.getReleasedAt(), zoneOf(r))));
        return rows;
    }

    /** grid(UI 단계 3 ①, docs/33 15.1) — 목록과 같은 조건 + 서버 페이징·정렬(허용 목록 {@link RaSort}) */
    @Transactional(readOnly = true)
    public RaPage grid(String from, String to, String status, String keyword, Integer page, Integer size, List<String> sort,
            Boolean total) {
        RaCrit c = criteria(from, to, status, keyword);
        PageQuery pq = PageQuery.parse(page, size, sort, total, RaSort.values(), policies.intValue(Policies.GRID_PAGE_SIZE_MAX),
                List.of("assessDate,desc"));
        List<RaRow> rows = mapper.findRaPage(c.from(), c.to(), c.statuses(), c.keyword(), clock.today(), pq);
        rows.forEach(r -> r.setReleasedAt(CmsSupport.at(r.getReleasedAt(), zoneOf(r))));
        return new RaPage(rows, pq.isWithTotal() ? mapper.countRas(c.from(), c.to(), c.statuses(), c.keyword()) : null);
    }

    /** grid 열(화면 평가 목록과 같은 칸) */
    static final List<ColumnSpec> GRID_COLUMNS = List.of(
            ColumnSpec.text("raNo", "cms.risk.col.raNo", 120).withSort(),
            ColumnSpec.text("title", "cms.risk.col.title", 200).withSort(),
            ColumnSpec.code("raType", "cms.risk.col.type", "CMS_RA_TYPE", 90).withSort(),
            ColumnSpec.code("status", "cms.mwo.col.status", "CMS_RA_STATUS", 90).withSort(),
            ColumnSpec.of("assessDate", "cms.risk.col.assessDate", ColumnType.DATE, 100).withSort(),
            ColumnSpec.of("itemCnt", "cms.risk.col.itemCnt", ColumnType.INT, 70),
            ColumnSpec.of("highCnt", "cms.risk.col.highCnt", ColumnType.INT, 70),
            ColumnSpec.of("openCnt", "cms.risk.col.openCnt", ColumnType.INT, 70),
            ColumnSpec.of("overdueCnt", "cms.risk.col.overdueCnt", ColumnType.INT, 70));

    public List<ColumnSpec> gridColumns() {
        return GRID_COLUMNS;
    }

    /** 목록·grid 공통 조건 검증 */
    private record RaCrit(LocalDate from, LocalDate to, List<String> statuses, String keyword) {
    }

    private RaCrit criteria(String from, String to, String status, String keyword) {
        FieldErrors e = FieldErrors.create();
        LocalDate f = date(from, "from", e);
        LocalDate t = date(to, "to", e);
        e.throwIfAny();
        ranges.check(f, t, MAX_DAYS);
        List<String> st = null;
        if (!blank(status)) {
            st = Arrays.stream(status.split(",")).map(String::strip).filter(s -> !s.isEmpty()).toList();
            if (st.isEmpty() || !STATUSES.containsAll(st)) {
                throw new BusinessException(HbErrorCode.INVALID_VALUE);
            }
        }
        if (keyword != null && keyword.length() > 100) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        return new RaCrit(f, t, st, blank(keyword) ? null : like(keyword.strip().toUpperCase(Locale.ROOT)));
    }

    @Transactional(readOnly = true)
    public RaRow get(long raId) {
        RaRow r = mapper.findRa(raId, clock.today());
        if (r == null) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        LocalDate today = today(r);
        r.setReleasedAt(CmsSupport.at(r.getReleasedAt(), zoneOf(r)));   // 화면은 앞 16자를 그대로 — 평가 공장 시간대로
        List<ItemRow> items = mapper.findItems(raId);
        items.forEach(i -> i.setOverdue(RiskMatrix.overdue(i.getDueDate(), i.getDoneDate(), today)));
        r.setItems(items);
        r.setMembers(mapper.findMembers(raId));
        r.setApprovalRequired(policies.bool(Policies.CMS_RA_APPROVAL));
        r.setProblems("DRAFT".equals(r.getStatus()) ? problems(r) : List.of());
        return r;
    }

    private static List<RiskMatrix.Problem> problems(RaRow r) {
        return RiskMatrix.releaseProblems(
                r.getItems().stream().map(i -> new RiskMatrix.ItemCheck(i.getSeq(), i.getRiskLevel(), !blank(i.getPlanMeasure()),
                        i.getOwnerUserId() != null, i.getDueDate() != null)).toList(),
                r.getMembers().stream().map(m -> new RiskMatrix.MemberCheck(m.getRoleCd(), m.getParticipatedOn() != null)).toList());
    }

    private ZoneId zoneOf(RaRow r) {
        return r.getPlantCd() == null ? clock.zone() : clock.zone(r.getPlantCd());
    }

    private LocalDate today(RaRow r) {
        return clock.instant().atZone(zoneOf(r)).toLocalDate();
    }

    // ------------------------------------------------------------------ 머리

    @Transactional
    public long create(RaForm in) {
        LoginUser me = me();
        inbox.claimRequestKey("CMS_RA_CREATE");
        if (in == null) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        FieldErrors e = FieldErrors.create();
        RaRow r = new RaRow();
        String method = blank(in.method()) ? "FREQ_SEV" : in.method().strip();
        if (!METHODS.contains(method)) {
            e.add("method", "CODE");
        }
        r.setMethod(method);
        r.setMatrixCd(policies.value(Policies.CMS_RA_MATRIX));
        apply(r, in, e);
        e.throwIfAny();
        return insert(r, me);
    }

    private long insert(RaRow r, LoginUser me) {
        ensureRule();
        r.setRaNo(numbering.next(RULE));
        try {
            return mapper.insertRa(r, me.loginId());
        } catch (DataIntegrityViolationException ex) {
            throw translate(ex);
        }
    }

    void ensureRule() {
        if (!cms.ruleExists(RULE)) {
            // 회사가 규칙을 정하지 않았으면 기본(해마다 초기화) — 바꾸려면 채번 규칙만 고친다
            numbering.saveRule(RULE, "위험성평가", "RA-{yyyy}-{seq:3}", "YEAR", "SYSTEM");
        }
    }

    /** 수정 — 작성 중에만, 방법·행렬은 고정 */
    @Transactional
    public void update(long raId, RaForm in) {
        LoginUser me = me();
        if (in == null || in.rowVer() == null) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        RaRow r = lockDraft(raId);
        FieldErrors e = FieldErrors.create();
        if (!blank(in.method()) && !in.method().strip().equals(r.getMethod())) {
            e.add("method", "FIXED");
        }
        apply(r, in, e);
        e.throwIfAny();
        r.setRowVer(in.rowVer());
        try {
            if (mapper.updateRa(r, me.loginId()) == 0) {
                throw new BusinessException(HbErrorCode.CONFLICT);
            }
        } catch (DataIntegrityViolationException ex) {
            throw translate(ex);
        }
    }

    private void apply(RaRow r, RaForm in, FieldErrors e) {
        String type = blank(in.raType()) ? null : in.raType().strip();
        if (type == null) {
            e.add("raType", "REQUIRED");
        } else if (!TYPES.contains(type)) {
            e.add("raType", "CODE");
        }
        r.setRaType(type);
        r.setTitle(text(in.title(), "title", 200, true, e));
        r.setScopeText(text(in.scopeText(), "scopeText", 500, false, e));
        String plant = blank(in.plantCd()) ? null : in.plantCd().strip().toUpperCase(Locale.ROOT);
        if (plant != null && cms.findPlants().stream().noneMatch(p -> p.code().equals(plant))) {
            e.add("plantCd", "NO_SUCH_CODE");
        }
        r.setPlantCd(plant);
        LocalDate d = date(in.assessDate(), "assessDate", e);
        if (blank(in.assessDate())) {
            e.add("assessDate", "REQUIRED");
        } else if (d != null && d.isAfter(clock.today().plusDays(1))) {
            e.add("assessDate", "FUTURE");   // 시간대 차이 하루는 둔다
        }
        r.setAssessDate(d);
        r.setRemark(text(in.remark(), "remark", 500, false, e));
    }

    /** 이전 확정본을 복사해 새 평가(작성 중) — 끝낸 개선 대책은 현재 조치로, 개선 후 위험성이 새 위험성이 된다. 참여자는 새로 */
    @Transactional
    public long copy(long raId, CopyForm in) {
        LoginUser me = me();
        inbox.claimRequestKey("CMS_RA_COPY");
        RaRow src = mapper.findRa(raId, clock.today());
        if (src == null) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        if (!"RELEASED".equals(src.getStatus())) {
            throw new BusinessException(HbErrorCode.CMS_RA_STATE);
        }
        FieldErrors e = FieldErrors.create();
        RaRow r = new RaRow();
        r.setMethod(src.getMethod());
        r.setMatrixCd(src.getMatrixCd());   // 같은 행렬로 이어 간다(정책이 바뀌었어도)
        r.setPrevRaId(src.getRaId());
        apply(r, new RaForm(in == null || blank(in.raType()) ? "REGULAR" : in.raType(), null, src.getTitle(), src.getScopeText(),
                src.getPlantCd(), in == null || blank(in.assessDate()) ? clock.today().toString() : in.assessDate(), null, null), e);
        e.throwIfAny();
        long id = insert(r, me);
        mapper.copyItems(raId, id, me.loginId());
        return id;
    }

    // ------------------------------------------------------------------ 유해위험요인

    /** 항목 저장(추가·수정) — 작성 중에만. 수준은 서버가 계산(빈도·강도법) 또는 사람이 고름(3단계) */
    @Transactional
    public long saveItem(long raId, ItemForm in) {
        LoginUser me = me();
        RaRow ra = lockDraft(raId);
        if (in == null) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        ItemRow cur = null;
        if (in.itemId() != null) {
            cur = mapper.findItem(raId, in.itemId());
            if (cur == null) {
                throw new BusinessException(HbErrorCode.NOT_FOUND);
            }
            if (in.rowVer() == null) {
                throw new BusinessException(HbErrorCode.INVALID_VALUE);
            }
        } else if (ra.getItemCnt() >= MAX_ITEMS) {
            throw CmsSupport.fieldError("seq", "TOO_MANY_ITEMS", MAX_ITEMS);
        }
        FieldErrors e = FieldErrors.create();
        ItemRow i = new ItemRow();
        if (in.seq() == null) {
            e.add("seq", "REQUIRED");
        } else if (in.seq() < 1 || in.seq() > 9999) {
            e.add("seq", "RANGE", 1, 9999);
        }
        i.setSeq(in.seq() == null ? 0 : in.seq());
        i.setTaskText(text(in.taskText(), "taskText", 200, true, e));
        if (!blank(in.equipCd())) {
            String cd = in.equipCd().strip().toUpperCase(Locale.ROOT);
            TargetRow t = cd.length() > 30 ? null : cms.findEquipTarget(cd);
            if (t == null) {
                e.add("equipCd", "NO_SUCH_CODE");
            } else if (!"Y".equals(t.getUseYn()) && !(cur != null && Long.valueOf(t.getId()).equals(cur.getEquipId()))) {
                e.add("equipCd", "INACTIVE");
            } else {
                i.setEquipId(t.getId());
            }
        }
        String cat = blank(in.hazardCat()) ? null : in.hazardCat().strip();
        if (cat == null) {
            e.add("hazardCat", "REQUIRED");
        } else if (!codes.usable(CAT_GRP, cat)) {
            e.add("hazardCat", "CODE");
        }
        i.setHazardCat(cat);
        i.setHazardText(text(in.hazardText(), "hazardText", 500, true, e));
        i.setCurrentMeasure(text(in.currentMeasure(), "currentMeasure", 500, false, e));
        level(ra, in.likelihood(), in.severity(), in.riskLevel(), "", i, e, true);
        i.setPlanMeasure(text(in.planMeasure(), "planMeasure", 500, false, e));
        if (!blank(in.ownerLoginId())) {
            String login = in.ownerLoginId().strip();
            UserRef u = users.byLogin(login);
            if (u == null) {
                e.add("ownerLoginId", "NO_SUCH_CODE");
            } else if (!u.active() && !(cur != null && u.userId() == (cur.getOwnerUserId() == null ? -1 : cur.getOwnerUserId()))) {
                e.add("ownerLoginId", "INACTIVE");
            } else {
                i.setOwnerUserId(u.userId());
            }
        }
        i.setDueDate(date(in.dueDate(), "dueDate", e));
        e.throwIfAny();
        try {
            if (cur == null) {
                return mapper.insertItem(raId, i, me.loginId());
            }
            i.setItemId(cur.getItemId());
            i.setRowVer(in.rowVer());
            if (mapper.updateItem(raId, i, me.loginId()) == 0) {
                throw new BusinessException(HbErrorCode.CONFLICT);
            }
            return cur.getItemId();
        } catch (DuplicateKeyException ex) {
            throw CmsSupport.fieldError("seq", "DUPLICATE");
        } catch (DataIntegrityViolationException ex) {
            throw translate(ex);
        }
    }

    /**
     * 수준 정하기: 빈도·강도법 = 가능성·중대성(1~행렬 크기) → RiskMatrix, 3단계 판단법 = 수준을 고른다(가능성·중대성 없음).
     * prefix 는 칸 이름 앞(개선 후 = "residual")
     */
    private static void level(RaRow ra, Integer l, Integer s, String chosen, String prefix, ItemRow i, FieldErrors e, boolean required) {
        String lf = prefix.isEmpty() ? "likelihood" : prefix + "L";
        String sf = prefix.isEmpty() ? "severity" : prefix + "S";
        String vf = prefix.isEmpty() ? "riskLevel" : prefix + "Level";
        String result = null;
        Integer ll = null;
        Integer ss = null;
        if ("FREQ_SEV".equals(ra.getMethod())) {
            int n = RiskMatrix.size(ra.getMatrixCd());
            boolean ok = true;
            if (l == null) {
                if (required) {
                    e.add(lf, "REQUIRED");
                }
                ok = false;
            } else if (l < 1 || l > n) {
                e.add(lf, "RANGE", 1, n);
                ok = false;
            }
            if (s == null) {
                if (required) {
                    e.add(sf, "REQUIRED");
                }
                ok = false;
            } else if (s < 1 || s > n) {
                e.add(sf, "RANGE", 1, n);
                ok = false;
            }
            if (!required && (l == null) != (s == null)) {
                e.add(l == null ? lf : sf, "REQUIRED");   // 개선 후는 둘 다 넣거나 둘 다 비운다
            }
            if (ok) {
                ll = l;
                ss = s;
                result = RiskMatrix.level(l, s, ra.getMatrixCd()).name();
            }
        } else {
            if (blank(chosen)) {
                if (required) {
                    e.add(vf, "REQUIRED");
                }
            } else if (!LEVELS.contains(chosen.strip())) {
                e.add(vf, "CODE");
            } else {
                result = chosen.strip();
            }
        }
        if (prefix.isEmpty()) {
            i.setLikelihood(ll);
            i.setSeverity(ss);
            i.setRiskLevel(result);
        } else {
            i.setResidualL(ll);
            i.setResidualS(ss);
            i.setResidualLevel(result);
        }
    }

    /**
     * 요인 삭제 — 그 요인의 증빙 첨부도 같은 트랜잭션에서 지운다(C24, docs/33 13.11). 첨부를 먼저: 요인이 없어지면 첨부 잠금
     * ({@link RiskFileLock}, 없는 요인 = 잠김)이 지우기를 막는다. 공용 FileService.delete 가 호출 트랜잭션에 참여하므로 요인이 없거나
     * (다른 평가의 요인 포함) 삭제가 실패하면 첨부 지우기도 함께 롤백된다
     */
    @Transactional
    public void deleteItem(long raId, long itemId) {
        me();
        lockDraft(raId);
        if (mapper.itemOfRa(raId, itemId) == 0) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        files.list(PROG, String.valueOf(itemId)).forEach(f -> files.delete(f.fileId()));
        if (mapper.deleteItem(raId, itemId) == 0) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
    }

    /** 확정 뒤 개선 진행 — 완료일(미래 불가)·개선 후 위험성(완료했을 때만). 완료를 지우면 개선 후 위험성도 지운다 */
    @Transactional
    public void progress(long raId, long itemId, ProgressForm in) {
        LoginUser me = me();
        RaRow ra = lockStatus(raId, Set.of("RELEASED"));
        ItemRow cur = mapper.findItem(raId, itemId);
        if (cur == null) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        if (in == null || in.rowVer() == null) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        FieldErrors e = FieldErrors.create();
        ItemRow i = new ItemRow();
        i.setItemId(itemId);
        i.setRowVer(in.rowVer());
        LocalDate done = date(in.doneDate(), "doneDate", e);
        if (done != null && done.isAfter(today(ra))) {
            e.add("doneDate", "FUTURE");
        }
        i.setDoneDate(done);
        if (done != null) {
            level(ra, in.residualL(), in.residualS(), in.residualLevel(), "residual", i, e, false);
        } else if (in.residualL() != null || in.residualS() != null || !blank(in.residualLevel())) {
            e.add("doneDate", "REQUIRED");   // 개선 후 위험성은 완료했을 때만
        }
        e.throwIfAny();
        try {
            if (mapper.progressItem(raId, i, me.loginId()) == 0) {
                throw new BusinessException(HbErrorCode.CONFLICT);
            }
        } catch (DataIntegrityViolationException ex) {
            throw translate(ex);
        }
    }

    // ------------------------------------------------------------------ 참여·주지

    /** 참여자로 고를 작업자 — 사용 중만, 코드순 상한 {@value #WORKER_LIMIT}(기준정보 mst_worker 읽기, C19) */
    @Transactional(readOnly = true)
    public List<WorkerOption> workers() {
        return mapper.findWorkers(WORKER_LIMIT);
    }

    static final int WORKER_LIMIT = 2000;

    @Transactional
    public long addMember(long raId, MemberForm in) {
        LoginUser me = me();
        RaRow ra = lockDraft(raId);
        if (in == null) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        FieldErrors e = FieldErrors.create();
        MemberRow m = new MemberRow();
        if (!blank(in.loginId()) && !blank(in.workerCd())) {
            e.add("workerCd", "USER_OR_WORKER");   // 사용자·작업자 중 하나(DB CHECK ck_cms_risk_member_who 와 같은 규칙)
        } else if (!blank(in.workerCd())) {
            String cd = in.workerCd().strip().toUpperCase(Locale.ROOT);
            WorkerOption w = cd.length() > 20 ? null : mapper.findWorker(cd);
            if (w == null) {
                e.add("workerCd", "NO_SUCH_CODE");   // 없음·다른 회사·사용 중지
            } else {
                m.setWorkerId(w.workerId());
                m.setMemberNm(blank(in.memberNm()) ? w.workerNm() : text(in.memberNm(), "memberNm", 100, true, e));
            }
        } else if (!blank(in.loginId())) {
            String login = in.loginId().strip();
            UserRef u = users.byLogin(login);
            if (u == null) {
                e.add("loginId", "NO_SUCH_CODE");
            } else if (!u.active()) {
                e.add("loginId", "INACTIVE");   // 사용 중지·관리자 잠금 — 새 참여자로 넣지 않는다(평가 담당과 같은 규칙, QA U-2)
            } else {
                m.setUserId(u.userId());
                m.setMemberNm(blank(in.memberNm()) ? u.userNm() : text(in.memberNm(), "memberNm", 100, true, e));
            }
        } else {
            m.setMemberNm(text(in.memberNm(), "memberNm", 100, true, e));
        }
        String role = blank(in.roleCd()) ? null : in.roleCd().strip();
        if (role == null) {
            e.add("roleCd", "REQUIRED");
        } else if (!ROLES.contains(role)) {
            e.add("roleCd", "CODE");
        }
        m.setRoleCd(role);
        LocalDate today = today(ra);
        m.setParticipatedOn(notFuture(in.participatedOn(), "participatedOn", today, e));
        m.setNotifiedOn(notFuture(in.notifiedOn(), "notifiedOn", today, e));
        m.setRemark(text(in.remark(), "remark", 500, false, e));
        e.throwIfAny();
        try {
            return mapper.insertMember(raId, m, me.loginId());
        } catch (DataIntegrityViolationException ex) {
            throw translate(ex);
        }
    }

    @Transactional
    public void deleteMember(long raId, long memberId) {
        me();
        lockDraft(raId);
        if (mapper.deleteMember(raId, memberId) == 0) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
    }

    /** 주지일 — 작성 중·확정 뒤 모두(확정 뒤 근로자에게 알린 날) */
    @Transactional
    public void notify(long raId, long memberId, NotifyForm in) {
        LoginUser me = me();
        RaRow ra = lockStatus(raId, Set.of("DRAFT", "RELEASED"));
        FieldErrors e = FieldErrors.create();
        LocalDate d = notFuture(in == null ? null : in.notifiedOn(), "notifiedOn", today(ra), e);
        if (in == null || blank(in.notifiedOn())) {
            e.add("notifiedOn", "REQUIRED");
        }
        e.throwIfAny();
        if (mapper.notifyMember(raId, memberId, d, in.rowVer(), me.loginId()) == 0) {
            throw mapper.findMembers(raId).stream().anyMatch(m -> m.getMemberId() == memberId)
                    ? new BusinessException(HbErrorCode.CONFLICT) : new BusinessException(HbErrorCode.NOT_FOUND);
        }
    }

    private static LocalDate notFuture(String raw, String field, LocalDate today, FieldErrors e) {
        LocalDate d = date(raw, field, e);
        if (d != null && d.isAfter(today)) {
            e.add(field, "FUTURE");
            return null;
        }
        return d;
    }

    // ------------------------------------------------------------------ 확정·폐기

    /**
     * 확정 요청 — 확정 검사(항목·개선 대책·근로자 참여)를 통과해야. 정책 Y: 결재선 필수 → IN_APPROVAL + 상신(CMS_RA),
     * N: APPROVE 권한으로 바로 RELEASED. 결과: 결재 번호(바로 확정이면 0)
     */
    @Transactional
    public long release(long raId, ReleaseForm in) {
        LoginUser me = me();
        inbox.claimRequestKey("CMS_RA_RELEASE");
        if (in == null || in.rowVer() == null) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        RaRow ra = lockDraft(raId);
        if (ra.getRowVer() != in.rowVer()) {
            throw new BusinessException(HbErrorCode.CONFLICT);
        }
        ra.setItems(mapper.findItems(raId));
        ra.setMembers(mapper.findMembers(raId));
        List<RiskMatrix.Problem> ps = problems(ra);
        if (!ps.isEmpty()) {
            FieldErrors e = FieldErrors.create();
            ps.forEach(p -> {
                if (p.seq() == null) {
                    e.add(p.field(), p.code());
                } else {
                    e.row(String.valueOf(p.seq())).add(p.field(), p.code());
                }
            });
            throw new BusinessException(HbErrorCode.INVALID_VALUE).withFieldErrors(e.list());
        }
        if (!policies.bool(Policies.CMS_RA_APPROVAL)) {
            if (!permission.actions(PROG).contains(Action.APPROVE)) {
                throw new BusinessException(HbErrorCode.NO_PERMISSION);
            }
            releaser.release(raId, "DRAFT", in.rowVer(), me.loginId());
            return 0;
        }
        List<LineSpec> lines = new ArrayList<>();
        if (in.lines() != null) {
            for (LineIn l : in.lines()) {
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
        }
        if (mapper.setStatus(raId, "IN_APPROVAL", null, false, in.rowVer(), me.loginId()) == 0) {
            throw new BusinessException(HbErrorCode.CONFLICT);
        }
        String title = ra.getRaNo() + " " + ra.getTitle();
        long aprId = approvals.submit(RiskApprovalDocType.DOC_TYPE, String.valueOf(raId), title.length() > 200 ? title.substring(0, 200) : title,
                lines);
        mapper.setStatus(raId, "IN_APPROVAL", aprId, false, null, me.loginId());
        return aprId;
    }

    /** 폐기 — 작성 중(버림) 또는 확정본(더 쓰지 않음). DELETE 권한 */
    @Transactional
    public void obsolete(long raId, Integer rowVer) {
        LoginUser me = me();
        RaRow ra = lockStatus(raId, Set.of("DRAFT", "RELEASED"));
        if (rowVer == null || ra.getRowVer() != rowVer) {
            throw new BusinessException(rowVer == null ? HbErrorCode.INVALID_VALUE : HbErrorCode.CONFLICT);
        }
        if (mapper.setStatus(raId, "OBSOLETE", ra.getAprId(), false, rowVer, me.loginId()) == 0) {
            throw new BusinessException(HbErrorCode.CONFLICT);
        }
    }

    private RaRow lockDraft(long raId) {
        return lockStatus(raId, Set.of("DRAFT"));
    }

    private RaRow lockStatus(long raId, Set<String> allowed) {
        if (mapper.lockRa(raId) == null) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        RaRow r = mapper.findRa(raId, clock.today());
        if (!allowed.contains(r.getStatus())) {
            throw new BusinessException(HbErrorCode.CMS_RA_STATE);
        }
        return r;
    }

    // ------------------------------------------------------------------ 엑셀

    /** 위험성평가표(항목) — 평가 하나 */
    @Transactional(readOnly = true)
    public long export(long raId, HttpServletResponse response) throws IOException {
        RaRow r = get(raId);
        return excel.export(response, new ExportJob(PROG, "prog.cmsRisk", COLUMNS, r.getItems().size(), List.of("raId")),
                w -> r.getItems().forEach(w));
    }

    private static RuntimeException translate(DataIntegrityViolationException e) {
        String c = PgErrors.constraint(e).orElse("");
        return switch (c) {
            case "fk_cms_risk_assess_plant_comp" -> CmsSupport.fieldError("plantCd", "NO_SUCH_CODE");
            case "fk_cms_risk_item_equip_comp" -> CmsSupport.fieldError("equipCd", "NO_SUCH_CODE");
            case "fk_cms_risk_item_owner_comp" -> CmsSupport.fieldError("ownerLoginId", "NO_SUCH_CODE");
            case "fk_cms_risk_member_user_comp" -> CmsSupport.fieldError("loginId", "NO_SUCH_CODE");
            case "fk_cms_risk_member_worker_comp" -> CmsSupport.fieldError("workerCd", "NO_SUCH_CODE");   // 고른 사이 사용 중지 등
            case "ck_cms_risk_member_who" -> CmsSupport.fieldError("workerCd", "USER_OR_WORKER");
            case "uq_cms_risk_item_seq" -> CmsSupport.fieldError("seq", "DUPLICATE");
            default -> e;
        };
    }
}
