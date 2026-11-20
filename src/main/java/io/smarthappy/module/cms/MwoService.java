package io.smarthappy.module.cms;

import static io.smarthappy.module.cms.CmsSupport.at;
import static io.smarthappy.module.cms.CmsSupport.blank;
import static io.smarthappy.module.cms.CmsSupport.date;
import static io.smarthappy.module.cms.CmsSupport.dateTime;
import static io.smarthappy.module.cms.CmsSupport.fieldError;
import static io.smarthappy.module.cms.CmsSupport.like;
import static io.smarthappy.module.cms.CmsSupport.me;
import static io.smarthappy.module.cms.CmsSupport.now;
import static io.smarthappy.module.cms.CmsSupport.text;
import static io.smarthappy.module.cms.CmsSupport.yn;

import java.io.IOException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Pattern;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import io.smarthappy.module.cms.CmsDtos.Created;
import io.smarthappy.module.cms.CmsDtos.Option;
import io.smarthappy.module.cms.CmsDtos.FailCodeRow;
import io.smarthappy.module.cms.CmsDtos.LaborForm;
import io.smarthappy.module.cms.CmsDtos.LaborRow;
import io.smarthappy.module.cms.CmsDtos.MwoCrit;
import io.smarthappy.module.cms.CmsDtos.MwoExport;
import io.smarthappy.module.cms.CmsDtos.MwoPage;
import io.smarthappy.module.cms.CmsDtos.MwoForm;
import io.smarthappy.module.cms.CmsDtos.MwoRow;
import io.smarthappy.module.cms.CmsDtos.MwoSort;
import io.smarthappy.module.cms.CmsDtos.PopPhotoTarget;
import io.smarthappy.module.cms.CmsDtos.ProfileRow;
import io.smarthappy.module.cms.CmsDtos.TargetRow;
import io.smarthappy.module.cms.CmsDtos.TransitionForm;
import io.smarthappy.module.cms.link.BreakdownReport;
import io.smarthappy.module.cms.link.BreakdownReport.ReportResult;
import io.smarthappy.module.cms.link.SpareIssueLink;
import io.smarthappy.module.cms.link.SpareIssueLink.PartIssue;
import io.smarthappy.platform.common.db.PgErrors;
import io.smarthappy.platform.common.query.PageQuery;
import io.smarthappy.platform.excel.ExcelExportService;
import io.smarthappy.platform.excel.ExcelExportService.ExportJob;
import io.smarthappy.platform.file.FileService;
import io.smarthappy.platform.grid.ColumnSpec;
import io.smarthappy.platform.grid.ColumnSpec.ColumnType;
import io.smarthappy.platform.common.query.DateRanges;
import io.smarthappy.platform.common.web.BusinessException;
import io.smarthappy.platform.common.web.FieldErrors;
import io.smarthappy.platform.common.web.HbErrorCode;
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
 * 보전 작업지시 (cms-mwo) + 고장 신고 (cms-breakdown) — docs/33 C1·4.1, 결정 D2·D3.
 *
 * 상태 REQ(신고) → OPEN(접수) → WIP(착수) → DONE(완료) → CLOSED(확인), REQ·OPEN → CANCELLED. 전이 표·완료 뒤 불변·닫을 때 필수는
 * DB(트리거·CHECK)가 최종이고, 서비스는 같은 규칙을 칸 오류로 먼저 알려 준다. 시각 입력·표시는 대상 공장 시간대(docs/25 D5).
 * PM 은 C2 계획만 만든다 — 이 서비스는 BM·CM 만 등록한다.
 */
@Service
@RequiredArgsConstructor
public class MwoService {

    static final String PROG = "cms-mwo";
    static final String PROG_BREAKDOWN = "cms-breakdown";
    static final String RULE = "CMS_MWO";
    static final int MAX_ROWS = 1000;
    static final int MAX_DAYS = 366;
    static final Set<String> STATUSES = Set.of("REQ", "OPEN", "WIP", "DONE", "CLOSED", "CANCELLED");
    static final Set<String> ACTIVE = Set.of("REQ", "OPEN", "WIP");
    private static final Map<String, String> CODE_FIELD = Map.of("SYMPTOM", "symptomCd", "CAUSE", "causeCd", "ACTION", "actionCd");

    private final CmsMapper mapper;
    private final UserDirectory users;
    private final InboxService inbox;
    private final NumberingService numbering;
    private final DateRanges ranges;
    private final BizClock clock;
    private final ChkRunService runs;
    private final CmsSpareMapper spareMapper;
    private final ObjectProvider<SpareIssueLink> spareLink;
    private final ExcelExportService excel;
    private final PolicyService policies;
    private final MessageSource messages;
    private final FileService files;

    // ------------------------------------------------------------------ 조회

    /** scope: ALL(기본) / MINE(내 담당) / REQUESTED(내 신고). source: 없음(전체) / POP(POP 고장 신고만). 기간 = 신고 날짜(회사 시간대) */
    @Transactional(readOnly = true)
    public List<MwoRow> list(String from, String to, String status, String mwoType, String plantCd, String keyword,
            String scope, String source, Long equipId) {
        List<MwoRow> rows = mapper.findMwos(criteria(from, to, status, mwoType, plantCd, keyword, scope, source, equipId), MAX_ROWS + 1);
        if (rows.size() > MAX_ROWS) {
            throw new BusinessException(HbErrorCode.CMS_TOO_MANY_ROWS, MAX_ROWS);
        }
        Map<String, ZoneId> zones = new HashMap<>();
        rows.forEach(r -> decorate(r, zones));
        return rows;
    }

    /**
     * grid(UI 단계 3 ①, docs/33 15.1) — 목록과 같은 조건 + 서버 페이징·정렬(허용 목록 {@link MwoSort}, 최대 3개, 모르는 열 400).
     * 기간 필수·상한도 목록과 같다(행 수 상한 대신 페이지)
     */
    @Transactional(readOnly = true)
    public MwoPage grid(String from, String to, String status, String mwoType, String plantCd, String keyword, String scope,
            String source, Integer page, Integer size, List<String> sort, Boolean total) {
        MwoCrit c = criteria(from, to, status, mwoType, plantCd, keyword, scope, source, null);
        PageQuery pq = PageQuery.parse(page, size, sort, total, MwoSort.values(), policies.intValue(Policies.GRID_PAGE_SIZE_MAX),
                List.of("reportedAtText,desc"));
        List<MwoRow> rows = mapper.findMwoPage(c, pq);
        Map<String, ZoneId> zones = new HashMap<>();
        rows.forEach(r -> decorate(r, zones));
        return new MwoPage(rows, pq.isWithTotal() ? mapper.countMwos(c) : null);
    }

    /** grid 열(화면 목록과 같은 칸) — 정렬 가능 = {@link MwoSort} 에 있는 열 */
    static final List<ColumnSpec> GRID_COLUMNS = List.of(
            ColumnSpec.text("mwoNo", "cms.mwo.col.mwoNo", 120).withSort(),
            ColumnSpec.code("mwoType", "cms.mwo.col.type", "CMS_MWO_TYPE", 80).withSort(),
            ColumnSpec.text("targetCd", "cms.mwo.col.target", 110).withSort(),
            ColumnSpec.text("targetNm", "cms.mwo.col.targetNm", 150),
            ColumnSpec.text("title", "cms.mwo.col.title", 200).withSort(),
            ColumnSpec.text("priority", "cms.mwo.col.priority", 70).withSort(),   // 화면이 이름으로 바꿔 보인다(코드 그룹 없음)
            ColumnSpec.code("status", "cms.mwo.col.status", "CMS_MWO_STATUS", 80).withSort(),
            ColumnSpec.text("assigneeNm", "cms.mwo.col.assignee", 90),
            ColumnSpec.text("reportedAtText", "cms.mwo.col.reportedAt", 130).withSort(),
            ColumnSpec.of("dueDate", "cms.mwo.col.dueDate", ColumnType.DATE, 100).withSort());

    public List<ColumnSpec> gridColumns() {
        return GRID_COLUMNS;
    }

    /** 목록·grid·엑셀 공통 조건 검증 */
    private MwoCrit criteria(String from, String to, String status, String mwoType, String plantCd, String keyword, String scope,
            String source, Long equipId) {
        FieldErrors e = FieldErrors.create();
        LocalDate f = date(from, "from", e);
        LocalDate t = date(to, "to", e);
        e.throwIfAny();
        ranges.check(f, t, MAX_DAYS);
        List<String> statuses = null;
        if (!blank(status)) {
            statuses = Arrays.stream(status.split(",")).map(String::strip).filter(s -> !s.isEmpty()).toList();
            if (statuses.isEmpty() || !STATUSES.containsAll(statuses)) {
                throw new BusinessException(HbErrorCode.INVALID_VALUE);
            }
        }
        if (!blank(mwoType) && !Set.of("BM", "CM", "PM").contains(mwoType) || keyword != null && keyword.length() > 100
                || !blank(scope) && !Set.of("ALL", "MINE", "REQUESTED").contains(scope) || !blank(source) && !"POP".equals(source)) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        long myId = me().userId();
        ZoneId zone = clock.zone();
        return new MwoCrit(f.atStartOfDay(zone).toOffsetDateTime(), t.plusDays(1).atStartOfDay(zone).toOffsetDateTime(), statuses,
                blank(mwoType) ? null : mwoType, blank(plantCd) ? null : plantCd.strip().toUpperCase(Locale.ROOT), equipId,
                "MINE".equals(scope) ? myId : null, "REQUESTED".equals(scope) ? myId : null,
                blank(keyword) ? null : like(keyword.strip().toUpperCase(Locale.ROOT)), "POP".equals(source));
    }

    /** 엑셀 열(C14) — 화면 목록 + 닫을 때 기록(현상·원인·조치·작업 내용)·정지·공수. 시각은 대상 공장 현지 */
    static final List<ColumnSpec> COLUMNS = List.of(
            ColumnSpec.text("mwoNo", "cms.mwo.col.mwoNo", 120),
            ColumnSpec.code("mwoType", "cms.mwo.col.type", "CMS_MWO_TYPE", 80),
            ColumnSpec.code("status", "cms.mwo.col.status", "CMS_MWO_STATUS", 80),
            ColumnSpec.text("plantCd", "cms.col.plant", 70),
            ColumnSpec.text("targetCd", "cms.mwo.col.target", 110),
            ColumnSpec.text("targetNm", "cms.mwo.col.targetNm", 160),
            ColumnSpec.text("title", "cms.mwo.col.title", 200),
            ColumnSpec.of("priority", "cms.mwo.col.priority", ColumnType.INT, 70),
            ColumnSpec.text("requesterText", "cms.mwo.col.requestedBy", 120),
            ColumnSpec.text("reportedAtText", "cms.mwo.col.reportedAt", 130),
            ColumnSpec.text("assigneeNm", "cms.mwo.col.assignee", 90),
            ColumnSpec.of("dueDate", "cms.mwo.col.dueDate", ColumnType.DATE, 100),
            ColumnSpec.text("startedAtText", "cms.mwo.col.startedAt", 130),
            ColumnSpec.text("finishedAtText", "cms.mwo.col.finishedAtX", 130),
            ColumnSpec.text("symptomLabel", "cms.mwo.col.symptom", 140),
            ColumnSpec.text("causeNm", "cms.mwo.col.cause", 120),
            ColumnSpec.text("actionNm", "cms.mwo.col.action", 120),
            ColumnSpec.code("failMechCd", "cms.mwo.col.failMech", "CMS_FAIL_MECH", 120),
            ColumnSpec.code("failDetectCd", "cms.mwo.col.failDetect", "CMS_FAIL_DETECT", 120),
            ColumnSpec.code("failImpactCd", "cms.mwo.col.failImpact", "CMS_FAIL_IMPACT", 90),
            ColumnSpec.text("workText", "cms.mwo.col.workText", 240),
            ColumnSpec.of("downYn", "cms.mwo.col.down", ColumnType.YN, 70),
            ColumnSpec.of("downMinutes", "cms.mwo.col.downMinutes", ColumnType.INT, 80),
            ColumnSpec.of("laborMin", "cms.mwo.col.laborMin", ColumnType.INT, 80));

    /** 엑셀 — 목록과 같은 조건·같은 상한(MAX_ROWS)·같은 순서 */
    @Transactional(readOnly = true)
    public long export(MwoExport req, HttpServletResponse response) throws IOException {
        MwoExport r = req == null ? new MwoExport(null, null, null, null, null, null, null, null, null, null) : req;
        if (r.columns() != null && !r.columns().isEmpty()) {
            return gridExport(r, response);
        }
        List<MwoRow> rows = list(r.from(), r.to(), r.status(), r.mwoType(), r.plantCd(), r.keyword(), r.scope(), r.source(), null);
        List<String> keys = new ArrayList<>(List.of("from", "to"));
        if (!blank(r.status())) {
            keys.add("status");
        }
        if (!blank(r.mwoType())) {
            keys.add("mwoType");
        }
        if (!blank(r.plantCd())) {
            keys.add("plantCd");
        }
        if (!blank(r.keyword())) {
            keys.add("keyword");
        }
        if (!blank(r.scope())) {
            keys.add("scope");
        }
        if (!blank(r.source())) {
            keys.add("source");
        }
        Locale locale = LocaleContextHolder.getLocale();
        rows.forEach(m -> m.setRequesterText(requester(m, locale)));
        return excel.export(response, new ExportJob(PROG, "prog.cmsMwo", COLUMNS, rows.size(), keys), w -> rows.forEach(w));
    }

    /** 신고자 글자 — POP 신고는 작업자(POP 단말), 사무 계정은 이름(화면 상세와 같은 글자). 자동 생성(PM·점검)은 빈 칸 */
    private String requester(MwoRow m, Locale locale) {
        if (m.getReportedWorkerNm() != null) {
            return messages.getMessage("js.cms.mwo.popReporter", new Object[] {m.getReportedWorkerNm(),
                    m.getReportedTerminalCd() == null ? "" : m.getReportedTerminalCd()}, locale);
        }
        return m.getRequestedNm();
    }

    /** grid 메뉴 엑셀 열 바꿈 — 우선순위는 화면처럼 이름(높음·보통·낮음) */
    static final Map<String, List<ColumnSpec>> EXPORT_REPLACE = Map.of(
            "priority", List.of(ColumnSpec.text("priorityNm", "cms.mwo.col.priority", 70)));

    /** grid 메뉴 엑셀(C21) — grid 와 같은 조건(기간 필수)·정렬(허용 목록)·보이는 열. 행 수 상한은 공용 엑셀 정책 */
    private long gridExport(MwoExport r, HttpServletResponse response) throws IOException {
        MwoCrit c = criteria(r.from(), r.to(), r.status(), r.mwoType(), r.plantCd(), r.keyword(), r.scope(), r.source(), null);
        List<ColumnSpec> cols = CmsSupport.exportColumns(GRID_COLUMNS, r.columns(), EXPORT_REPLACE);
        PageQuery pq = PageQuery.sortOnly(r.sort(), MwoSort.values(), List.of("reportedAtText,desc"));
        List<String> keys = new ArrayList<>(List.of("from", "to"));
        for (String[] k : new String[][] {{"status", r.status()}, {"mwoType", r.mwoType()}, {"plantCd", r.plantCd()}, {"keyword", r.keyword()},
                {"scope", r.scope()}, {"source", r.source()}}) {
            if (!blank(k[1])) {
                keys.add(k[0]);
            }
        }
        Locale locale = LocaleContextHolder.getLocale();
        Map<String, ZoneId> zones = new HashMap<>();
        return excel.export(response, new ExportJob(PROG, "prog.cmsMwo", cols, mapper.countMwos(c), keys), w -> mapper.findMwoPage(c, pq).forEach(m -> {
            decorate(m, zones);
            m.setPriorityNm(messages.getMessage("js.cms.prio." + m.getPriority(), null, String.valueOf(m.getPriority()), locale));
            w.accept(m);
        }));
    }

    @Transactional(readOnly = true)
    public MwoRow get(long mwoId) {
        MwoRow m = mapper.findMwo(mwoId);
        if (m == null) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        decorate(m, new HashMap<>());
        return m;
    }

    /** 고장 신고 화면용 — 내가 신고한 것만(남의 신고는 404) */
    @Transactional(readOnly = true)
    public MwoRow getMine(long mwoId) {
        MwoRow m = get(mwoId);
        if (!Objects.equals(m.getRequestedBy(), me().userId())) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        return m;
    }

    /** 시각을 공장 시간대로, 기한 지남 표시 */
    private void decorate(MwoRow m, Map<String, ZoneId> zones) {
        ZoneId z = zones.computeIfAbsent(m.getPlantCd() == null ? "" : m.getPlantCd(),
                p -> p.isEmpty() ? clock.zone() : clock.zone(p));
        m.setTimeZone(z.getId());
        m.setReportedAt(at(m.getReportedAt(), z));
        m.setStartedAt(at(m.getStartedAt(), z));
        m.setFinishedAt(at(m.getFinishedAt(), z));
        m.setDownStartAt(at(m.getDownStartAt(), z));
        m.setDownEndAt(at(m.getDownEndAt(), z));
        m.setClosedAt(at(m.getClosedAt(), z));
        LocalDate today = clock.instant().atZone(z).toLocalDate();
        m.setOverdue(m.getDueDate() != null && ACTIVE.contains(m.getStatus()) && m.getDueDate().isBefore(today));
    }

    // ------------------------------------------------------------------ 등록

    /** 고장 신고 (cms-breakdown): 고장(BM)·REQ·신고자 = 나. 현상(코드 또는 글) 하나면 된다 */
    @Transactional
    public Created report(MwoForm in) {
        LoginUser me = me();
        inbox.claimRequestKey("CMS_BREAKDOWN_REPORT");
        if (in == null) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        return insert(in, "BM", "REQ", me, false);
    }

    /** 작업지시 등록 (cms-mwo): 고장(BM)·개선(CM), 바로 OPEN. PM 은 예방점검 계획(C2)이 만든다 */
    @Transactional
    public Created create(MwoForm in) {
        LoginUser me = me();
        inbox.claimRequestKey("CMS_MWO_CREATE");
        if (in == null) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        String type = blank(in.mwoType()) ? null : in.mwoType().strip();
        if (type == null) {
            throw fieldError("mwoType", "REQUIRED");
        }
        if (!"BM".equals(type) && !"CM".equals(type)) {
            throw fieldError("mwoType", "CODE");
        }
        return insert(in, type, "OPEN", me, true);
    }

    /** POP 신고 멱등 소비자(sys_inbox, message_id = clientReqId) */
    static final String POP_CONSUMER = "CMS_POP_BREAKDOWN";
    static final Pattern CLIENT_REQ = Pattern.compile("^[A-Za-z0-9_.:-]{8,64}$");
    static final int POP_TEXT_MAX = 200;

    /**
     * POP 고장 신고 (C20, {@link BreakdownReport} 구현이 부른다) — 호출자(POP) 트랜잭션 안. 고장(BM)·신고(REQ), 신고자 = 작업자 + 단말(사무 계정 없음).
     * 검사 → 멱등(sys_inbox) → 채번·행 — 모두 같은 트랜잭션이라 호출 쪽이 롤백하면 번호·멱등 기록도 없다.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public ReportResult reportFromPop(BreakdownReport.Report r) {
        FieldErrors e = FieldErrors.create();
        if (r == null) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        if (r.clientReqId() == null || !CLIENT_REQ.matcher(r.clientReqId()).matches()) {
            e.add("clientReqId", "FORMAT");
        }
        Option terminal = mapper.findPopTerminal(r.terminalId());   // RLS — 다른 회사 단말은 없음
        if (terminal == null) {
            e.add("terminalId", "NO_SUCH_CODE");
        }
        if (!mapper.popWorkerActive(r.workerId())) {
            e.add("workerId", "NO_SUCH_CODE");   // 없음·다른 회사·사용 중지
        }
        TargetRow equip = mapper.findEquipTargetById(r.equipId());
        if (equip == null) {
            e.add("equipId", "NO_SUCH_CODE");
        } else if (!"Y".equals(equip.getUseYn())) {
            e.add("equipId", "INACTIVE");
        } else if (terminal != null && !Objects.equals(terminal.name(), equip.getPlantCd())) {
            e.add("equipId", "PLANT");   // 단말 공장의 설비만(MES 도 확인하지만 CMS 가 다시)
        }
        if (r.symptomText() != null && r.symptomText().strip().length() > POP_TEXT_MAX) {
            e.add("symptomText", "TOO_LONG", POP_TEXT_MAX);
        }
        e.throwIfAny();
        if (!inbox.firstTime(POP_CONSUMER, r.clientReqId())) {
            String no = mapper.findMwoNoByReportReq(r.clientReqId());
            if (no == null) {
                throw new BusinessException(HbErrorCode.DUPLICATE_REQUEST);   // 멱등 기록만 있고 행이 없음 — 일어나지 않아야(같은 트랜잭션)
            }
            return new ReportResult(no, true);
        }
        MwoForm in = new MwoForm("BM", "EQUIP", equip.getCode(), null, null, r.symptomCd(), r.symptomText(), null, null, null,
                null, null, null, null, null, null, null, null, null, null);
        String actor = "t" + r.terminalId() + ":" + terminal.code();   // POP 행위자 관례(TerminalPrincipal.actor)
        Created c = insert(in, "BM", "REQ", null, actor, false, m -> {
            m.setReportedWorkerId(r.workerId());
            m.setReportedTerminalId(r.terminalId());
            m.setReportReqId(r.clientReqId());
        });
        return new ReportResult(c.mwoNo(), false);
    }

    /** POP 사진(C25) — 이미지만, 한도는 회사 정책(docs/33 11.17 Q2) */
    static final Set<String> POP_PHOTO_EXT = Set.of("jpg", "jpeg", "png");
    static final String POLICY_POP_PHOTO_MB = "CMS_POP_PHOTO_MB";
    static final String POLICY_POP_PHOTO_MAX = "CMS_POP_PHOTO_MAX";

    /**
     * POP 신고 사진 (C25, {@link BreakdownReport#attachPhoto}) — 호출자(POP) 트랜잭션 안. 신고한 단말만(Q4)·신고·접수 때만(Q3)·
     * 이미지·크기·개수 한도(Q2)·같은 사진 재전송 = 처음 파일. 저장·검사는 공용 {@link FileService#attachAs}(Q1).
     * 작업지시 행을 잠가 같은 신고의 동시 사진이 개수 한도를 함께 넘지 않게 한다. 개수는 이 단말이 붙인 것만 센다(사무 화면 첨부 제외)
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public BreakdownReport.PhotoResult attachPopPhoto(BreakdownReport.Photo p) {
        if (p == null) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        FieldErrors e = FieldErrors.create();
        if (blank(p.reqNo())) {
            e.add("reqNo", "REQUIRED");
        }
        if (p.clientReqId() == null || !CLIENT_REQ.matcher(p.clientReqId()).matches()) {
            e.add("clientReqId", "FORMAT");
        }
        Option terminal = mapper.findPopTerminal(p.terminalId());   // RLS — 다른 회사 단말은 없음
        if (terminal == null) {
            e.add("terminalId", "NO_SUCH_CODE");
        }
        if (!mapper.popWorkerActive(p.workerId())) {
            e.add("workerId", "NO_SUCH_CODE");
        }
        e.throwIfAny();
        PopPhotoTarget m = mapper.lockPopPhotoTarget(p.reqNo().strip());
        if (m == null || !"BM".equals(m.getMwoType()) || !Objects.equals(m.getReportedTerminalId(), p.terminalId())) {
            throw fieldError("reqNo", "NO_SUCH_CODE");   // 없음·다른 회사·사무 신고·다른 단말의 신고 — 구분하지 않는다
        }
        if (!p.clientReqId().equals(m.getReportReqId())) {
            throw fieldError("clientReqId", "MISMATCH");
        }
        if (!Set.of("REQ", "OPEN").contains(m.getStatus())) {
            throw new BusinessException(HbErrorCode.CMS_MWO_STATE);   // 착수 뒤 사진은 보전 담당이 사무 화면에서
        }
        String target = String.valueOf(m.getMwoId());
        String actor = "t" + p.terminalId() + ":" + terminal.code();
        String mine = "t" + p.terminalId() + ":";
        int max = policies.intValue(POLICY_POP_PHOTO_MAX);
        int before = files.countAttached(PROG_BREAKDOWN, target, mine);
        FileService.Attached a = files.attachAs(PROG_BREAKDOWN, target, p.fileName(), p.size(), p.content(), actor, popPhotoOptions());
        if (a.duplicate()) {
            return new BreakdownReport.PhotoResult(a.file().fileId(), true, before);
        }
        if (before >= max) {   // 새 사진인데 이미 한도 — 저장한 파일·행은 트랜잭션 롤백으로 지워진다(attachAs 의 afterCompletion)
            throw new BusinessException(HbErrorCode.CONFLICT).withFieldErrors(FieldErrors.create().add("file", "PHOTO_LIMIT", max).list());
        }
        return new BreakdownReport.PhotoResult(a.file().fileId(), false, before + 1);
    }

    /** POP 사진 조건 — attachPopPhoto 와 popPhotoLimits 가 같은 것을 쓴다(이미지만·작은 한도·같은 사진 재전송) */
    private FileService.AttachOptions popPhotoOptions() {
        return new FileService.AttachOptions(POP_PHOTO_EXT, policies.intValue(POLICY_POP_PHOTO_MB), true);
    }

    /** POP 사진 한도(C28, {@link BreakdownReport#photoLimits}) — 개수 정책 + 공용 첨부의 같은 크기·형식 계산. 호출자 트랜잭션 안(SUPPORTS) */
    @Transactional(propagation = Propagation.SUPPORTS, readOnly = true)
    public BreakdownReport.PhotoLimits popPhotoLimits() {
        FileService.AttachLimits l = files.limitsOf(popPhotoOptions());
        return new BreakdownReport.PhotoLimits(policies.intValue(POLICY_POP_PHOTO_MAX), l.maxBytes(), l.exts());
    }

    /** 그 설비에 고를 현상(C20) — 현상·사용 중, 클래스 한정은 그 클래스만 */
    @Transactional(readOnly = true)
    public List<BreakdownReport.Symptom> popSymptoms(long equipId) {
        return mapper.findPopSymptoms(equipId);
    }

    private Created insert(MwoForm in, String type, String status, LoginUser me, boolean full) {
        return insert(in, type, status, me.userId(), me.loginId(), full, m -> {
        });
    }

    private Created insert(MwoForm in, String type, String status, Long requestedBy, String by, boolean full,
            Consumer<MwoRow> extra) {
        FieldErrors e = FieldErrors.create();
        TargetRow target = target(in.targetType(), in.targetCd(), e);
        e.throwIfAny();
        ZoneId zone = target.getPlantCd() == null ? clock.zone() : clock.zone(target.getPlantCd());
        MwoRow m = new MwoRow();
        m.setMwoType(type);
        m.setStatus(status);
        m.setTargetType(target.getTargetType());
        m.setPlantCd(target.getPlantCd());
        if ("EQUIP".equals(target.getTargetType())) {
            m.setEquipId(target.getId());
            m.setTargetClassId(target.getClassId());
        } else {
            m.setAssetId(target.getId());
        }
        m.setRequestedBy(requestedBy);
        m.setDownYn("N");
        OffsetDateTime reported = dateTime(in.reportedAt(), "reportedAt", e, zone, clock);
        m.setReportedAt(reported == null ? now(clock, zone) : reported);
        content(m, null, in, e, zone, full);
        if (full && !blank(in.dueDate())) {
            m.setDueDate(date(in.dueDate(), "dueDate", e));
        }
        if (full && in.parentMwoId() != null && mapper.findMwo(in.parentMwoId()) == null) {
            e.add("parentMwoId", "NO_SUCH_CODE");
        } else if (full) {
            m.setParentMwoId(in.parentMwoId());
        }
        e.throwIfAny();
        if ("OPEN".equals(status) && m.getAssigneeId() == null) {
            m.setAssigneeId(defaultOwner(m));
        }
        extra.accept(m);
        ensureRule();
        m.setMwoNo(numbering.next(RULE));
        try {
            long id = mapper.insertMwo(m, by);
            return new Created(id, m.getMwoNo());
        } catch (DataIntegrityViolationException ex) {
            throw translate(ex);
        }
    }

    /** 대상 찾기 — 설비(사용 중) 또는 금형·지그(사용 중, 게이지 제외 — 교정은 QMS) */
    TargetRow target(String targetType, String targetCd, FieldErrors e) {
        String type = blank(targetType) ? "EQUIP" : targetType.strip();
        if (!"EQUIP".equals(type) && !"ASSET".equals(type)) {
            e.add("targetType", "CODE");
            return null;
        }
        if (blank(targetCd)) {
            e.add("targetCd", "REQUIRED");
            return null;
        }
        String cd = targetCd.strip().toUpperCase(Locale.ROOT);
        TargetRow t = cd.length() > 30 ? null : "EQUIP".equals(type) ? mapper.findEquipTarget(cd) : mapper.findAssetTarget(cd);
        if (t == null || "ASSET".equals(type) && "GAUGE".equals(t.getAssetType())) {
            e.add("targetCd", "NO_SUCH_CODE");
            return null;
        }
        if (!"Y".equals(t.getUseYn())) {
            e.add("targetCd", "INACTIVE");
            return null;
        }
        return t;
    }

    /** 설비 프로필의 기본 보전 담당(사용 중일 때만) */
    Long defaultOwner(MwoRow m) {
        if (m.getEquipId() == null) {
            return null;
        }
        ProfileRow p = mapper.findProfile(m.getEquipId());
        Long owner = p == null ? null : p.getOwnerUserId();
        return owner != null && users.isActive(owner) ? owner : null;
    }

    void ensureRule() {
        if (!mapper.ruleExists(RULE)) {
            // 회사가 규칙을 아직 정하지 않았으면 기본 규칙(월마다 초기화) — 바꾸려면 채번 규칙만 고친다
            numbering.saveRule(RULE, "보전 작업지시", "MWO-{yyMM}-{seq:4}", "MONTH", "SYSTEM");
        }
    }

    // ------------------------------------------------------------------ 내용 고치기·상태 전이

    /** 내용 수정 — REQ·OPEN·WIP 에서만. 유형·대상은 고정 */
    @Transactional
    public void update(long mwoId, MwoForm in) {
        LoginUser me = me();
        MwoRow cur = load(mwoId, in == null ? null : in.rowVer(), ACTIVE);
        FieldErrors e = FieldErrors.create();
        fixed(cur, in, e);
        ZoneId zone = zoneOf(cur);
        OffsetDateTime reported = time(in.reportedAt(), "reportedAt", e, zone, cur.getReportedAt());
        if (reported != null) {
            cur.setReportedAt(reported);
        }
        content(cur, cur, in, e, zone, true);
        if ("WIP".equals(cur.getStatus())) {
            OffsetDateTime started = time(in.startedAt(), "startedAt", e, zone, cur.getStartedAt());
            if (started != null) {
                cur.setStartedAt(started);
            }
        }
        cur.setDueDate(date(in.dueDate(), "dueDate", e));
        order(cur, e);
        e.throwIfAny();
        write(cur, null, me);
    }

    /** 접수 REQ → OPEN. 담당을 안 주면 설비 프로필의 기본 담당 */
    @Transactional
    public void accept(long mwoId, TransitionForm in) {
        LoginUser me = me();
        MwoRow cur = load(mwoId, in == null ? null : in.rowVer(), Set.of("REQ"));
        FieldErrors e = FieldErrors.create();
        if (!blank(in.assigneeLoginId())) {
            cur.setAssigneeId(user(in.assigneeLoginId(), "assigneeLoginId", null, e));
        } else if (cur.getAssigneeId() == null) {
            cur.setAssigneeId(defaultOwner(cur));
        }
        e.throwIfAny();
        cur.setStatus("OPEN");
        write(cur, null, me);
    }

    /** 착수 OPEN → WIP (시각 비면 지금) */
    @Transactional
    public void start(long mwoId, TransitionForm in) {
        LoginUser me = me();
        MwoRow cur = load(mwoId, in == null ? null : in.rowVer(), Set.of("OPEN"));
        FieldErrors e = FieldErrors.create();
        ZoneId zone = zoneOf(cur);
        OffsetDateTime started = dateTime(in.startedAt(), "startedAt", e, zone, clock);
        cur.setStartedAt(started == null ? now(clock, zone) : started);
        if (cur.getAssigneeId() == null) {
            cur.setAssigneeId(me.userId());   // 담당 없이 착수하면 착수한 사람이 담당
        }
        order(cur, e);
        e.throwIfAny();
        cur.setStatus("WIP");
        write(cur, null, me);
    }

    /** 완료 WIP → DONE — 보낸 내용을 함께 저장하고 닫을 때 필수를 검사한다 */
    @Transactional
    public void finish(long mwoId, MwoForm in) {
        LoginUser me = me();
        MwoRow cur = load(mwoId, in == null ? null : in.rowVer(), Set.of("WIP"));
        FieldErrors e = FieldErrors.create();
        fixed(cur, in, e);
        ZoneId zone = zoneOf(cur);
        OffsetDateTime reported = time(in.reportedAt(), "reportedAt", e, zone, cur.getReportedAt());
        if (reported != null) {
            cur.setReportedAt(reported);
        }
        content(cur, cur, in, e, zone, true);
        OffsetDateTime started = time(in.startedAt(), "startedAt", e, zone, cur.getStartedAt());
        if (started != null) {
            cur.setStartedAt(started);
        }
        cur.setDueDate(date(in.dueDate(), "dueDate", e));
        OffsetDateTime finished = dateTime(in.finishedAt(), "finishedAt", e, zone, clock);
        cur.setFinishedAt(finished == null && blank(in.finishedAt()) ? now(clock, zone) : finished);
        order(cur, e);
        // 닫을 때 필수 (docs/11 Top #13)
        if ("BM".equals(cur.getMwoType())) {
            if (cur.getSymptomId() == null) {
                e.add("symptomCd", "REQUIRED");
            }
            if (cur.getCauseId() == null) {
                e.add("causeCd", "REQUIRED");
            }
            if (cur.getActionId() == null) {
                e.add("actionCd", "REQUIRED");
            }
        }
        if (!"PM".equals(cur.getMwoType()) && blank(cur.getWorkText())) {
            e.add("workText", "REQUIRED");
        }
        if ("Y".equals(cur.getDownYn()) && cur.getDownEndAt() == null) {
            e.add("downEndAt", "REQUIRED");
        }
        e.throwIfAny();
        cur.setStatus("DONE");
        write(cur, null, me);
        if ("PM".equals(cur.getMwoType())) {
            runs.completeForMwo(mwoId, me, cur.getFinishedAt());   // 점검 결과 필수 검사 + 계획 다음 예정 (같은 트랜잭션), 수행 시각 = 완료 시각
        }
        // 사용 예비품 재고 출고(WMS 계약, docs/33 11.15) — 같은 트랜잭션, 부족·마감 409 면 완료 전체 롤백(D31). 빈 목록도 그대로 넘긴다
        List<PartIssue> issues = spareMapper.findParts(mwoId).stream().map(p -> new PartIssue(p.getPartId(), p.getItemId(), p.getQty())).toList();
        SpareIssueLink.of(spareLink).onMwoDone(mwoId, cur.getPlantCd(), cur.getFinishedAt().toInstant(), issues, me.loginId());
    }

    /** 확인 DONE → CLOSED (APPROVE). 내용은 바꾸지 않는다 */
    @Transactional
    public void close(long mwoId, TransitionForm in) {
        LoginUser me = me();
        MwoRow cur = load(mwoId, in == null ? null : in.rowVer(), Set.of("DONE"));
        closeRow(cur, me);
    }

    /** 신고자 확인 (cms-breakdown) — 내가 신고한 것만 */
    @Transactional
    public void confirmMine(long mwoId, TransitionForm in) {
        LoginUser me = me();
        MwoRow cur = load(mwoId, in == null ? null : in.rowVer(), null);
        if (!Objects.equals(cur.getRequestedBy(), me.userId())) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        if (!"DONE".equals(cur.getStatus())) {
            throw new BusinessException(HbErrorCode.CMS_MWO_STATE);
        }
        closeRow(cur, me);
    }

    private void closeRow(MwoRow cur, LoginUser me) {
        cur.setStatus("CLOSED");
        cur.setClosedAt(now(clock, zoneOf(cur)));
        write(cur, me.userId(), me);
    }

    /** 취소 REQ·OPEN → CANCELLED (DELETE), 사유 필수 */
    @Transactional
    public void cancel(long mwoId, TransitionForm in) {
        LoginUser me = me();
        MwoRow cur = load(mwoId, in == null ? null : in.rowVer(), Set.of("REQ", "OPEN"));
        FieldErrors e = FieldErrors.create();
        String reason = text(in.reason(), "reason", 500, true, e);
        e.throwIfAny();
        cur.setStatus("CANCELLED");
        cur.setCancelReason(reason);
        write(cur, null, me);
        if ("PM".equals(cur.getMwoType())) {
            runs.skipForMwo(mwoId, reason, me);   // 그 예정은 건너뜀 → 다음 예정
        }
    }

    // ------------------------------------------------------------------ 예방점검(C2)

    /**
     * 예방점검 작업지시(PM·OPEN) — 정기 작업·"지금 생성"(PmGenerator)이 계획 행을 잠근 트랜잭션 안에서 부른다.
     * 제목 = 계획 이름, 담당 = 계획 담당 또는 설비 기본 담당, 기한 = 예정일(사용량 주기는 도달한 날). 신고자 없음(정기 작업)
     */
    long createPm(io.smarthappy.module.cms.CmsPmDtos.PlanRow p, LocalDate due, java.math.BigDecimal dueUsage, String by) {
        MwoRow m = new MwoRow();
        m.setMwoType("PM");
        m.setStatus("OPEN");
        m.setTargetType(p.getTargetType());
        m.setEquipId(p.getEquipId());
        m.setAssetId(p.getAssetId());
        m.setPlantCd(p.getPlantCd());
        m.setTitle(p.getPlanNm());
        m.setPriority(2);
        m.setReportedAt(now(clock, p.getPlantCd() == null ? clock.zone() : clock.zone(p.getPlantCd())));
        m.setDownYn("N");
        m.setDueDate(due);
        m.setPmPlanId(p.getPlanId());
        m.setDueRefDate(due);
        m.setDueRefUsage(dueUsage);   // 사용량 주기(C3)의 멱등 키
        m.setAssigneeId(p.getAssigneeId() != null ? p.getAssigneeId() : defaultOwner(m));
        ensureRule();
        m.setMwoNo(numbering.next(RULE));
        return mapper.insertMwo(m, by);
    }

    /**
     * 알람 자동 고장 신고(C8, D22) — 수집 트랜잭션 안에서 {@code CmsAlarmSink} 가 부른다. 고장 수리(BM)·신고(REQ), 신고자 없음(장치),
     * 접수 시각 = 알람 발생(미래면 지금), 우선순위 1, 현상 = 정의의 현상 코드 + 장치 코드·문구
     */
    long createFromAlarm(long equipId, String plantCd, Long classId, String title, Long symptomId, String symptomText, OffsetDateTime raisedAt,
            String by) {
        MwoRow m = new MwoRow();
        m.setMwoType("BM");
        m.setStatus("REQ");
        m.setTargetType("EQUIP");
        m.setEquipId(equipId);
        m.setTargetClassId(classId);
        m.setPlantCd(plantCd);
        m.setTitle(title.length() > 200 ? title.substring(0, 200) : title);
        m.setSymptomId(symptomId);
        m.setSymptomText(symptomText.length() > 1000 ? symptomText.substring(0, 1000) : symptomText);
        m.setPriority(1);
        m.setFailDetectCd("CBM");   // 탐지 방법 = 상시 상태 감시(ISO 14224, docs/33 16.6) — 사람이 바꿀 수 있다
        OffsetDateTime now = now(clock, zoneOf(m));
        m.setReportedAt(raisedAt.isAfter(now) ? now : raisedAt.atZoneSameInstant(zoneOf(m)).toOffsetDateTime());
        m.setDownYn("N");
        m.setAssigneeId(defaultOwner(m));
        ensureRule();
        m.setMwoNo(numbering.next(RULE));
        return mapper.insertMwo(m, by);
    }

    /**
     * 점검 NG 항목의 후속 작업지시 — 고장 수리(BM·OPEN), 현상 글 = 항목·값, 상위 = 그 점검의 작업지시(WO 방식), 출처 = 점검 실행.
     * NG 가 아닌 항목은 `NOT_NG`
     */
    @Transactional
    public Created followUp(long runId, Integer seq) {
        LoginUser me = me();
        inbox.claimRequestKey("CMS_RUN_FOLLOW_UP");
        io.smarthappy.module.cms.CmsPmDtos.RunRow run = runs.get(runId);
        if (seq == null) {
            throw fieldError("seq", "REQUIRED");
        }
        io.smarthappy.module.cms.CmsPmDtos.ResultRow r = run.getResults().stream().filter(x -> x.getSeq() == seq).findFirst()
                .orElseThrow(() -> new BusinessException(HbErrorCode.NOT_FOUND));
        if (!"NG".equals(r.getJudge())) {
            throw fieldError("seq", "NOT_NG");
        }
        FieldErrors e = FieldErrors.create();
        TargetRow t = target(run.getTargetType(), run.getTargetCd(), e);
        e.throwIfAny();
        MwoRow m = new MwoRow();
        m.setMwoType("BM");
        m.setStatus("OPEN");
        m.setTargetType(t.getTargetType());
        m.setEquipId("EQUIP".equals(t.getTargetType()) ? t.getId() : null);
        m.setAssetId("ASSET".equals(t.getTargetType()) ? t.getId() : null);
        m.setTargetClassId(t.getClassId());
        m.setPlantCd(t.getPlantCd());
        String value = r.getValueNum() != null ? r.getValueNumText() + (r.getUnitNm() == null ? "" : " " + r.getUnitNm())
                : r.getValueText() != null ? r.getValueText() : "NG";
        String title = "점검 NG: " + r.getItemNm();
        m.setTitle(title.length() > 200 ? title.substring(0, 200) : title);
        String sym = run.getPlanNm() + " · " + r.getItemNm() + " = " + value;
        m.setSymptomText(sym.length() > 1000 ? sym.substring(0, 1000) : sym);
        m.setPriority(2);
        m.setReportedAt(now(clock, zoneOf(m)));
        m.setRequestedBy(me.userId());
        m.setDownYn("N");
        m.setParentMwoId(run.getMwoId());
        m.setSrcRunId(runId);
        m.setFailDetectCd("INSPECTION");   // 탐지 방법 = 점검(ISO 14224, docs/33 16.6)
        m.setAssigneeId(defaultOwner(m));
        ensureRule();
        m.setMwoNo(numbering.next(RULE));
        try {
            return new Created(mapper.insertMwo(m, me.loginId()), m.getMwoNo());
        } catch (DataIntegrityViolationException ex) {
            throw translate(ex);
        }
    }

    // ------------------------------------------------------------------ 공수

    @Transactional(readOnly = true)
    public List<LaborRow> labor(long mwoId) {
        get(mwoId);
        return mapper.findLabor(mwoId);
    }

    @Transactional
    public long addLabor(long mwoId, LaborForm in) {
        LoginUser me = me();
        inbox.claimRequestKey("CMS_MWO_LABOR_ADD");
        lockState(mwoId, Set.of("OPEN", "WIP"));
        if (in == null) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        FieldErrors e = FieldErrors.create();
        Long userId = blank(in.loginId()) ? Long.valueOf(me.userId()) : user(in.loginId(), "loginId", null, e);
        LocalDate day = date(in.workDate(), "workDate", e);
        if (blank(in.workDate())) {
            e.add("workDate", "REQUIRED");
        } else if (day != null && day.isAfter(clock.today())) {
            e.add("workDate", "FUTURE");
        }
        if (in.minutes() == null) {
            e.add("minutes", "REQUIRED");
        } else if (in.minutes() < 1 || in.minutes() > 1440) {
            e.add("minutes", "RANGE", 1, 1440);
        }
        String remark = text(in.remark(), "remark", 500, false, e);
        e.throwIfAny();
        try {
            return mapper.insertLabor(mwoId, userId, day, in.minutes(), remark, me.loginId());
        } catch (DataIntegrityViolationException ex) {
            throw translate(ex);
        }
    }

    @Transactional
    public void deleteLabor(long mwoId, long laborId) {
        me();
        lockState(mwoId, Set.of("OPEN", "WIP"));
        try {
            if (mapper.deleteLabor(mwoId, laborId) == 0) {
                throw new BusinessException(HbErrorCode.NOT_FOUND);
            }
        } catch (DataIntegrityViolationException ex) {
            throw translate(ex);
        }
    }

    // ------------------------------------------------------------------ 내부

    /** 행 잠금 → 다시 읽기 → rowVer·상태 확인. 남의 회사·없는 행 404, rowVer 다름 409, 상태 아님 409 CMS_MWO_STATE */
    private MwoRow load(long mwoId, Integer rowVer, Set<String> allowed) {
        if (rowVer == null) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        if (mapper.lockMwo(mwoId) == null) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        MwoRow cur = mapper.findMwo(mwoId);
        if (cur.getRowVer() != rowVer) {
            throw new BusinessException(HbErrorCode.CONFLICT);
        }
        if (allowed != null && !allowed.contains(cur.getStatus())) {
            throw new BusinessException(HbErrorCode.CMS_MWO_STATE);
        }
        return cur;
    }

    private void lockState(long mwoId, Set<String> allowed) {
        if (mapper.lockMwo(mwoId) == null) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        if (!allowed.contains(mapper.findMwo(mwoId).getStatus())) {
            throw new BusinessException(HbErrorCode.CMS_MWO_STATE);
        }
    }

    private ZoneId zoneOf(MwoRow m) {
        return m.getPlantCd() == null ? clock.zone() : clock.zone(m.getPlantCd());
    }

    /**
     * 화면(datetime-local)은 저장할 때마다 분 단위로 다시 보낸다 — 지금 값과 같은 분이면 지금 값(초·µs 포함)을 그대로 둔다.
     * 그러지 않으면 손대지 않은 착수 시각이 분으로 잘려 신고 시각보다 앞서게 된다(BEFORE_REPORTED).
     */
    private OffsetDateTime time(String raw, String field, FieldErrors e, ZoneId zone, OffsetDateTime current) {
        if (current != null && !blank(raw)
                && raw.strip().equals(current.atZoneSameInstant(zone).toLocalDateTime().truncatedTo(ChronoUnit.MINUTES).toString())) {
            return current;
        }
        return dateTime(raw, field, e, zone, clock);
    }

    /** 유형·대상은 바꾸지 않는다 — 보냈는데 다르면 FIXED */
    private static void fixed(MwoRow cur, MwoForm in, FieldErrors e) {
        if (!blank(in.mwoType()) && !in.mwoType().strip().equals(cur.getMwoType())) {
            e.add("mwoType", "FIXED");
        }
        if (!blank(in.targetType()) && !in.targetType().strip().equals(cur.getTargetType())) {
            e.add("targetType", "FIXED");
        }
        if (!blank(in.targetCd()) && !in.targetCd().strip().toUpperCase(Locale.ROOT).equals(cur.getTargetCd())) {
            e.add("targetCd", "FIXED");
        }
    }

    /**
     * 내용 칸 적용 (등록·수정·완료 공통). PUT 의미 — 보낸 값으로 바꾸고, 빈 선택 칸은 지운다.
     * full = false(고장 신고)면 현상·정지·우선순위·제목만.
     */
    private void content(MwoRow m, MwoRow cur, MwoForm in, FieldErrors e, ZoneId zone, boolean full) {
        int prio = in.priority() == null ? 2 : in.priority();
        if (prio < 1 || prio > 3) {
            e.add("priority", "RANGE", 1, 3);
        }
        m.setPriority(prio);
        m.setSymptomId(code("SYMPTOM", in.symptomCd(), m, cur == null ? null : cur.getSymptomId(), e));
        m.setSymptomText(text(in.symptomText(), "symptomText", 1000, false, e));
        if ("BM".equals(m.getMwoType()) && m.getSymptomId() == null && m.getSymptomText() == null && blank(in.symptomCd())) {
            e.add("symptomCd", "REQUIRED");   // 고장 신고는 현상(코드 또는 글) 하나는 있어야
        }
        String title = text(in.title(), "title", 200, false, e);
        if (title == null) {
            title = autoTitle(m, in);
            if (title == null) {
                e.add("title", "REQUIRED");
            }
        }
        m.setTitle(title);
        String down = yn(in.downYn(), "N", "downYn", e);
        m.setDownYn(down);
        if ("Y".equals(down)) {
            OffsetDateTime ds = time(in.downStartAt(), "downStartAt", e, zone, cur == null ? null : cur.getDownStartAt());
            OffsetDateTime de = time(in.downEndAt(), "downEndAt", e, zone, cur == null ? null : cur.getDownEndAt());
            if (blank(in.downStartAt()) && !full) {
                ds = now(clock, zone);   // 고장 신고: "멈춤" 만 누르면 지금부터 정지(대상 공장 시간대)
            } else if (blank(in.downStartAt())) {
                e.add("downStartAt", "REQUIRED");
            }
            if (ds != null && de != null && de.isBefore(ds)) {
                e.add("downEndAt", "BEFORE_START");
            }
            m.setDownStartAt(ds);
            m.setDownEndAt(de);
        } else {
            m.setDownStartAt(null);
            m.setDownEndAt(null);
        }
        if (!full) {
            return;
        }
        if (!blank(in.assigneeLoginId())) {
            m.setAssigneeId(user(in.assigneeLoginId(), "assigneeLoginId", cur == null ? null : cur.getAssigneeId(), e));
        } else {
            m.setAssigneeId(null);
        }
        m.setCauseId(code("CAUSE", in.causeCd(), m, cur == null ? null : cur.getCauseId(), e));
        m.setActionId(code("ACTION", in.actionCd(), m, cur == null ? null : cur.getActionId(), e));
        m.setWorkText(text(in.workText(), "workText", 2000, false, e));
        // ISO 14224 칸(G3, docs/33 16.6) — 선택 입력, 고장 수리만
        m.setFailMechCd(iso(ISO_MECH, in.failMechCd(), "failMechCd", m, cur == null ? null : cur.getFailMechCd(), e));
        m.setFailDetectCd(iso(ISO_DETECT, in.failDetectCd(), "failDetectCd", m, cur == null ? null : cur.getFailDetectCd(), e));
        m.setFailImpactCd(iso(ISO_IMPACT, in.failImpactCd(), "failImpactCd", m, cur == null ? null : cur.getFailImpactCd(), e));
    }

    static final String ISO_MECH = "CMS_FAIL_MECH";
    static final String ISO_DETECT = "CMS_FAIL_DETECT";
    static final String ISO_IMPACT = "CMS_FAIL_IMPACT";
    private static final java.util.regex.Pattern CODE_FORMAT = java.util.regex.Pattern.compile("^[A-Z0-9][A-Z0-9_-]{0,29}$");

    /**
     * ISO 14224 공통코드 칸 → 코드. 비우면 null. 고장 수리가 아니면 BM_ONLY. 바꾸지 않은 코드는 사용 중지돼도 둔다(새 참조만 차단 — 트리거와 같은 규칙),
     * 새 값은 사용 중인 시스템 코드 또는 그 회사 코드(hb_code_usable)
     */
    private String iso(String grp, String raw, String field, MwoRow m, String current, FieldErrors e) {
        if (blank(raw)) {
            return null;
        }
        String cd = raw.strip().toUpperCase(Locale.ROOT);
        if (!"BM".equals(m.getMwoType())) {
            e.add(field, "BM_ONLY");
            return null;
        }
        if (cd.equals(current)) {
            return cd;
        }
        if (!CODE_FORMAT.matcher(cd).matches() || !mapper.codeUsable(grp, cd)) {
            e.add(field, "CODE");
            return null;
        }
        return cd;
    }

    /** 제목을 비우면 현상 이름·글 앞부분으로 */
    private String autoTitle(MwoRow m, MwoForm in) {
        if (m.getSymptomId() != null) {
            FailCodeRow f = mapper.findFailCode(m.getSymptomId());
            return f == null ? null : f.getFailNm();
        }
        if (m.getSymptomText() != null) {
            String s = m.getSymptomText().replaceAll("\\s+", " ");
            return s.length() > 50 ? s.substring(0, 50) : s;
        }
        return null;
    }

    /**
     * 고장 코드 칸 → id. 바꾸지 않은 코드는 사용 중지돼도 둔다(새 참조만 차단). 설비 클래스 한정 코드는 그 클래스 설비에만
     * (금형·지그에는 클래스 한정 코드를 쓰지 않는다)
     */
    private Long code(String type, String raw, MwoRow m, Long currentId, FieldErrors e) {
        String field = CODE_FIELD.get(type);
        if (blank(raw)) {
            return null;
        }
        String cd = raw.strip().toUpperCase(Locale.ROOT);
        FailCodeRow f = cd.length() > 20 ? null : mapper.findFailCodeByCd(type, cd);
        if (f == null) {
            e.add(field, "NO_SUCH_CODE");
            return null;
        }
        if (f.getFailId() == (currentId == null ? -1 : currentId)) {
            return f.getFailId();
        }
        if (!"Y".equals(f.getUseYn())) {
            e.add(field, "INACTIVE");
            return null;
        }
        if (f.getEquipClassId() != null && !f.getEquipClassId().equals(m.getTargetClassId())) {
            e.add(field, "CLASS_MISMATCH");
            return null;
        }
        return f.getFailId();
    }

    /** 로그인 아이디 → 사용자 id. 바꾸지 않은 사람은 사용 중지돼도 둔다 */
    private Long user(String loginId, String field, Long currentId, FieldErrors e) {
        String login = loginId.strip();
        UserRef u = users.byLogin(login);
        if (u == null) {
            e.add(field, "NO_SUCH_CODE");
            return null;
        }
        if (!Objects.equals(u.userId(), currentId) && !u.active()) {
            e.add(field, "INACTIVE");
            return null;
        }
        return u.userId();
    }

    /** 신고 ≤ 착수 ≤ 완료 */
    private static void order(MwoRow m, FieldErrors e) {
        if (m.getStartedAt() != null && m.getReportedAt() != null && m.getStartedAt().isBefore(m.getReportedAt())) {
            e.add("startedAt", "BEFORE_REPORTED");
        }
        if (m.getFinishedAt() != null && m.getStartedAt() != null && m.getFinishedAt().isBefore(m.getStartedAt())) {
            e.add("finishedAt", "BEFORE_START");
        }
    }

    private void write(MwoRow m, Long closedBy, LoginUser me) {
        try {
            if (mapper.updateMwo(m, closedBy, me.loginId()) == 0) {
                throw new BusinessException(HbErrorCode.CONFLICT);
            }
        } catch (DataIntegrityViolationException ex) {
            throw translate(ex);
        }
    }

    /** 트리거·제약 → 칸 오류. 서비스가 먼저 거르므로 여기 오는 것은 동시 변경(사용 중지·전이) */
    private static RuntimeException translate(DataIntegrityViolationException e) {
        String c = PgErrors.constraint(e).orElse("");
        return switch (c) {
            case "fk_cms_mwo_equip_comp", "fk_cms_mwo_asset_comp", "fk_cms_mwo_equip", "fk_cms_mwo_asset" ->
                    fieldError("targetCd", "NO_SUCH_CODE");
            case "fk_cms_mwo_symptom_comp", "fk_cms_mwo_symptom" -> fieldError("symptomCd", "NO_SUCH_CODE");
            case "fk_cms_mwo_reported_worker_comp", "fk_cms_mwo_reported_worker" -> fieldError("workerId", "NO_SUCH_CODE");
            case "fk_cms_mwo_reported_terminal_comp", "fk_cms_mwo_reported_terminal" -> fieldError("terminalId", "NO_SUCH_CODE");
            case "fk_cms_mwo_cause_comp", "fk_cms_mwo_cause" -> fieldError("causeCd", "NO_SUCH_CODE");
            case "fk_cms_mwo_action_comp", "fk_cms_mwo_action" -> fieldError("actionCd", "NO_SUCH_CODE");
            case "fk_cms_mwo_assignee_comp", "fk_cms_mwo_assignee" -> fieldError("assigneeLoginId", "NO_SUCH_CODE");
            case "fk_cms_mwo_labor_user_comp", "fk_cms_mwo_labor_user" -> fieldError("loginId", "NO_SUCH_CODE");
            case "fk_cms_mwo_fail_mech_code" -> fieldError("failMechCd", "CODE");
            case "fk_cms_mwo_fail_detect_code" -> fieldError("failDetectCd", "CODE");
            case "fk_cms_mwo_fail_impact_code" -> fieldError("failImpactCd", "CODE");
            case "ck_cms_mwo_transition", "ck_cms_mwo_done_fixed", "ck_cms_mwo_labor_status" ->
                    new BusinessException(HbErrorCode.CMS_MWO_STATE);
            default -> e;
        };
    }
}
