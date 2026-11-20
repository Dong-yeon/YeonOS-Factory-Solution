package io.smarthappy.module.wms.label;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import io.smarthappy.module.wms.label.LabelDtos.JobQuery;
import io.smarthappy.module.wms.label.LabelDtos.JobRow;
import io.smarthappy.module.wms.label.LabelDtos.JobSearch;
import io.smarthappy.module.wms.label.LabelDtos.JobSort;
import io.smarthappy.module.wms.label.LabelDtos.PrintItem;
import io.smarthappy.module.wms.label.LabelDtos.PrinterRow;
import io.smarthappy.module.wms.label.LabelDtos.ReprintForm;
import io.smarthappy.module.wms.label.LabelDtos.TplRow;
import io.smarthappy.module.wms.label.ZplRenderer.RenderException;
import io.smarthappy.platform.auth.Action;
import io.smarthappy.platform.auth.PermissionService;
import io.smarthappy.platform.common.query.DateRanges;
import io.smarthappy.platform.common.query.PageQuery;
import io.smarthappy.platform.common.web.BusinessException;
import io.smarthappy.platform.common.web.FieldErrors;
import io.smarthappy.platform.common.web.HbErrorCode;
import io.smarthappy.platform.messaging.OutboxService;
import io.smarthappy.platform.policy.Policies;
import io.smarthappy.platform.policy.PolicyService;
import io.smarthappy.platform.security.CurrentUser;
import io.smarthappy.platform.security.LoginUser;
import io.smarthappy.platform.time.BizClock;
import tools.jackson.databind.json.JsonMapper;

/**
 * 라벨 인쇄 큐 (docs/30 W2) — **다른 모듈이 부르는 계약**: {@link #request}(업무 트랜잭션 MANDATORY).
 *
 * 템플릿 고르기 → 렌더(값 이스케이프) → 아웃박스 LABEL_PRINT 발행 → 인쇄 작업 행. 프린터에 붙는 것은 송신기
 * ({@link LabelPrintHandler})가 트랜잭션 밖에서 한다 — 업무가 롤백되면 인쇄도 없다. 상태는 아웃박스에서 파생.
 * LOT·파렛트·출하 라벨 재발행은 중복 라벨(= 중복 재고) 위험이라 APPROVE 권한 + 사유.
 */
@Service
public class LabelPrintService {

    public static final String TOPIC = "LABEL_PRINT";
    public static final String PROG_JOB = "wms-print-job";
    static final String AGENT = "AGENT";
    static final String POLICY_PORTS = "WMS_PRINTER_PORTS";
    static final int MAX_ITEMS = 500;
    static final int MAX_ZPL_BYTES = 40_000;          // 아웃박스 본문 64KB 안에 JSON 이스케이프 여유
    static final int MAX_QUERY_DAYS = 92;
    static final Set<String> APPROVE_REPRINT = Set.of("LOT", "PALLET", "SHIP");
    static final List<String> JOB_SORT = List.of("requestedAt,desc");
    static final Set<String> STATUSES = Set.of("NEW", "SENDING", "SENT", "FAIL", "CLOSED_MANUAL");   // 수동 종결 — 연계 현황(docs/36 19장 O8)
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final DateTimeFormatter PRINTED_AT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final LabelMapper mapper;
    private final OutboxService outbox;
    private final PolicyService policy;
    private final BizClock clock;
    private final PermissionService permissions;
    private final DateRanges ranges;
    private final boolean allowLoopback;
    private final PrintTrackService track;
    private final PrintAgentService agents;

    public LabelPrintService(LabelMapper mapper, OutboxService outbox, PolicyService policy, BizClock clock,
            PermissionService permissions, DateRanges ranges, PrintTrackService track, PrintAgentService agents,
            @Value("${happyboot.wms.print-allow-loopback:false}") boolean allowLoopback) {
        this.mapper = mapper;
        this.outbox = outbox;
        this.policy = policy;
        this.clock = clock;
        this.permissions = permissions;
        this.ranges = ranges;
        this.allowLoopback = allowLoopback;
        this.track = track;
        this.agents = agents;
    }

    /** 주입 전 기본값 = 끔(fail-closed), docs/67 F3 */
    private volatile io.smarthappy.platform.config.LanDirectGate lanDirect = io.smarthappy.platform.config.LanDirectGate.fixed(false);

    @org.springframework.beans.factory.annotation.Autowired
    void setLanDirect(io.smarthappy.platform.config.LanDirectGate lanDirect) {
        this.lanDirect = lanDirect;
    }

    // ------------------------------------------------------------------ 계약

    /**
     * 라벨 인쇄 요청. 항목마다 작업 1건 + 아웃박스 1건. 반환 = 작업 id.
     *
     * @param tplId null 이면 그 유형의 기본 템플릿
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public List<Long> request(String labelType, Long tplId, long printerId, int copies, List<PrintItem> items) {
        return request(labelType, tplId, printerId, copies, items, false);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    List<Long> request(String labelType, Long tplId, long printerId, int copies, List<PrintItem> items, boolean test) {
        LoginUser me = me();
        if (labelType == null || !LabelFields.TYPES.contains(labelType)) {
            throw fieldError("labelType", "CODE");
        }
        if (copies < 1 || copies > 99) {
            throw fieldError("copies", "CODE");
        }
        if (items == null || items.isEmpty()) {
            throw fieldError("items", "REQUIRED");
        }
        if (items.size() > MAX_ITEMS) {
            throw fieldError("items", "TOO_MANY_LABELS", MAX_ITEMS);
        }
        PrinterRow p = usablePrinter(printerId, labelType);
        // 템플릿: 지정 > 규칙(거래처 > 품목, docs/65 18.4) > 유형 기본. 같은 템플릿은 한 번만 읽고 검사
        Map<Long, TplRow> tpls = new LinkedHashMap<>();
        TplRow fixed = tplId == null ? null : checkFits(template(labelType, tplId), p);
        Set<String> allowed = LabelFields.allowed(labelType);
        String printedAt = clock.now().format(PRINTED_AT);
        List<Long> ids = new ArrayList<>(items.size());
        for (PrintItem it : items) {
            String bizKey = it == null || it.bizKey() == null ? "" : it.bizKey().strip();
            if (bizKey.isEmpty() || bizKey.length() > 100) {
                throw fieldError("bizKey", bizKey.isEmpty() ? "REQUIRED" : "TOO_LONG", 100);
            }
            TplRow t = fixed;
            if (t == null) {
                Long ruled = mapper.ruleTplId(labelType, it.custId(), it.itemId());
                long key = ruled == null ? 0L : ruled;
                t = tpls.get(key);
                if (t == null) {
                    t = checkFits(template(labelType, ruled), p);
                    tpls.put(key, t);
                }
            }
            Map<String, String> values = new LinkedHashMap<>(it.values() == null ? Map.of() : it.values());
            values.putIfAbsent("printedAt", printedAt);
            values.putIfAbsent("printedBy", me.loginId());
            String zpl = render(t.getBody(), values, allowed);
            long job = enqueue(p, t.getTplId(), labelType, bizKey, values, zpl, copies, test, null, null, me);
            // 한글 값 + 한글 글꼴 없는 프린터 = 글자가 빠질 수 있다(docs/65 18.2) — 막지 않고 작업에 경고
            if (!"Y".equals(p.getCjkFontYn()) && ZplLint.hasCjk(cjkValues(values))) {
                track.note(job, PrintStates.WARN, "NO_CJK_FONT", null, "SYSTEM", null, "AUTO");
            }
            ids.add(job);
        }
        return ids;
    }

    /** 재발행 — 원 작업의 렌더 결과를 그대로(같은 ZPL), 프린터는 바꿀 수 있다 */
    @Transactional
    public long reprint(long jobId, ReprintForm in) {
        LoginUser me = me();
        JobRow j = mapper.findJob(jobId);
        if (j == null) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        String reason = in == null || in.reason() == null ? "" : in.reason().strip();
        if (reason.length() < 2 || reason.length() > 200) {
            throw fieldError("reason", reason.length() < 2 ? "REQUIRED" : "TOO_LONG", 200);
        }
        if (APPROVE_REPRINT.contains(j.getLabelType())) {
            permissions.require(PROG_JOB, Action.APPROVE);
        }
        long printerId = in.printerId() == null ? j.getPrinterId() : in.printerId();
        PrinterRow p = usablePrinter(printerId, j.getLabelType());
        TplRow t = mapper.findTpl(j.getTplId());
        if (t != null && t.getDpi() != p.getDpi()) {
            throw fieldError("printerId", "DPI_MISMATCH", t.getDpi(), p.getDpi());    // 같은 ZPL 을 dpi 다른 프린터로 — 크기가 바뀐다
        }
        Map<String, String> values = readValues(j.getLabelValues());
        return enqueue(p, j.getTplId(), j.getLabelType(), j.getBizKey(), values, j.getZpl(), j.getCopies(),
                "Y".equals(j.getTestYn()), jobId, reason, me);
    }

    /**
     * 결과 불명(UNKNOWN)·프린터 오류 작업을 사람이 닫는다(docs/65 18.1) — LOT·파렛트·출하는 재발행과 같게 APPROVE
     * ("찍혔다"고 잘못 닫으면 라벨 없는 재고, "안 찍혔다"고 닫고 재발행하면 같은 번호 라벨 두 장)
     */
    @Transactional
    public String resolve(long jobId, PrintTrackDtos.ResolveForm in) {
        JobRow j = mapper.findJob(jobId);
        if (j == null) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        if (APPROVE_REPRINT.contains(j.getLabelType())) {
            permissions.require(PROG_JOB, Action.APPROVE);
        }
        // 에이전트 작업: 큐 행 잠금 — 가져간 중(CLAIMED)이면 409, 아직 안 가져갔으면(QUEUED) 닫을 수 있다
        PrintTrackDtos.DispatchRow d = agents.lockForClose(jobId);
        String to = track.resolve(jobId, in, d != null && "QUEUED".equals(d.getStage()));
        if (d != null) {
            agents.close(jobId);
        }
        return to;
    }

    /**
     * 다른 프린터로 넘기기(docs/65 19.2) — 아직 안 가져간(QUEUED)·결과 불명·프린터 오류·실패 작업만. 원 작업을 닫고(CANCELLED, 사유)
     * 같은 ZPL 로 새 작업(재발행 경로 — reprint_of_id, LOT·파렛트·출하는 APPROVE). 가져간 중·보낸 뒤(SENT)는 넘기지 않는다.
     */
    @Transactional
    public long move(long jobId, PrintTrackDtos.MoveForm in) {
        JobRow j = mapper.findJob(jobId);
        if (j == null) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        if (in == null || in.printerId() == null) {
            throw fieldError("printerId", "REQUIRED");
        }
        if (in.printerId() == j.getPrinterId()) {
            throw fieldError("printerId", "SAME_PRINTER");
        }
        String reason = in.reason() == null ? "" : in.reason().strip();
        if (reason.length() < 2 || reason.length() > 200) {
            throw fieldError("reason", reason.length() < 2 ? "REQUIRED" : "TOO_LONG", 200);
        }
        if (APPROVE_REPRINT.contains(j.getLabelType())) {
            permissions.require(PROG_JOB, Action.APPROVE);
        }
        PrintTrackDtos.DispatchRow d = agents.lockForClose(jobId);
        String state = track.state(jobId);
        boolean queued = PrintStates.QUEUED.equals(state) && d != null && "QUEUED".equals(d.getStage());
        if (!queued && !PrintStates.UNKNOWN.equals(state) && !PrintStates.FAILED.equals(state) && !PrintStates.PRINTER_ERROR.equals(state)) {
            throw new BusinessException(HbErrorCode.CONFLICT);       // 설치형 대기(아웃박스가 그래도 보낸다)·보낸 뒤·끝난 작업
        }
        if (!PrintStates.FAILED.equals(state)) {
            track.move(jobId, PrintStates.CANCELLED, "MOVED", reason, "USER", null, "KEY", null);
        }
        if (d != null) {
            agents.close(jobId);
        }
        return reprint(jobId, new ReprintForm(reason, in.printerId()));
    }

    // ------------------------------------------------------------------ 조회

    public record Page(List<JobRow> list, Long total) {
    }

    @Transactional(readOnly = true)
    public Page jobs(JobQuery q, Integer page, Integer size, List<String> sort, Boolean total) {
        if (q == null || q.from() == null || q.to() == null) {
            throw fieldError("from", "REQUIRED");
        }
        ranges.check(q.from(), q.to(), MAX_QUERY_DAYS);
        String type = blank(q.labelType()) ? null : q.labelType();
        if (type != null && !LabelFields.TYPES.contains(type)) {
            throw fieldError("labelType", "CODE");
        }
        String status = blank(q.status()) ? null : q.status();
        if (status != null && !STATUSES.contains(status)) {
            throw fieldError("status", "CODE");
        }
        String biz = blank(q.bizKey()) ? null : q.bizKey().strip();
        if (biz != null && biz.length() > 100) {
            throw fieldError("bizKey", "TOO_LONG", 100);
        }
        PageQuery pq = PageQuery.parse(page, size, sort, total, JobSort.values(), policy.intValue(Policies.GRID_PAGE_SIZE_MAX),
                JOB_SORT);
        var zone = clock.zone();
        JobSearch s = new JobSearch(q.from().atStartOfDay(zone).toOffsetDateTime(),
                q.to().plusDays(1).atStartOfDay(zone).toOffsetDateTime(), type, status,
                biz == null ? null : "%" + likeEscape(biz) + "%", blank(q.plantCd()) ? null : q.plantCd().strip(), pq);
        String ps = blank(q.printState()) ? null : q.printState().strip();
        if (ps != null && !PrintStates.isState(ps)) {
            throw fieldError("printState", "CODE");
        }
        s.setPrintState(ps);
        return new Page(mapper.findJobs(s), pq.isWithTotal() ? mapper.countJobs(s) : null);
    }

    @Transactional(readOnly = true)
    public JobRow job(long jobId) {
        JobRow j = mapper.findJob(jobId);
        if (j == null) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        return j;
    }

    // ------------------------------------------------------------------ 내부

    PrinterRow usablePrinter(long printerId, String labelType) {
        PrinterRow p = mapper.findPrinter(printerId);
        if (p == null) {
            throw fieldError("printerId", "NOT_FOUND");
        }
        if (!"Y".equals(p.getUseYn())) {
            throw fieldError("printerId", "INACTIVE");
        }
        if (!"ANY".equals(p.getUsage()) && !p.getUsage().equals(labelType)) {
            throw fieldError("printerId", "USAGE_MISMATCH");
        }
        if (!lanDirect.enabled()) {
            throw fieldError("printerId", "LAN_DIRECT_OFF");   // docs/67 F3
        }
        PrinterAddress.Problem prob = PrinterAddress.checkStatic(p.getHost(), p.getPort(), allowedPorts(), allowLoopback);
        if (prob != null) {
            throw fieldError("printerId", prob.name());
        }
        if (AGENT.equals(p.getConnMode()) && p.getAgentTerminalId() == null) {
            throw fieldError("printerId", "NO_AGENT");
        }
        return p;
    }

    private TplRow template(String labelType, Long tplId) {
        TplRow t = tplId == null ? mapper.findDefaultTpl(labelType) : mapper.findTpl(tplId);
        if (t == null) {
            throw fieldError("tplId", tplId == null ? "NO_DEFAULT_TEMPLATE" : "NOT_FOUND");
        }
        if (!t.getLabelType().equals(labelType)) {
            throw fieldError("tplId", "LABEL_TYPE_MISMATCH");
        }
        if (!"Y".equals(t.getUseYn())) {
            throw fieldError("tplId", "INACTIVE");
        }
        return t;
    }

    /** 템플릿이 프린터에 맞나(docs/65 18.4) — dpi 가 다르면 점 좌표가 그대로 커지거나 작아지고, 용지보다 넓으면 잘린다 */
    static TplRow checkFits(TplRow t, PrinterRow p) {
        if (t.getDpi() != p.getDpi()) {
            throw fieldError("tplId", "DPI_MISMATCH", t.getDpi(), p.getDpi());
        }
        if (p.getMediaWidthMm() != null && t.getWidthMm() != null && t.getWidthMm().compareTo(p.getMediaWidthMm()) > 0) {
            throw fieldError("tplId", "MEDIA_TOO_SMALL", t.getWidthMm().stripTrailingZeros().toPlainString(),
                    p.getMediaWidthMm().stripTrailingZeros().toPlainString());
        }
        return t;
    }

    /** 한글이 들어갈 수 있는 칸의 값만 */
    static Map<String, String> cjkValues(Map<String, String> values) {
        Map<String, String> out = new LinkedHashMap<>();
        values.forEach((k, v) -> {
            if (ZplLint.CJK_FIELDS.contains(k)) {
                out.put(k, v);
            }
        });
        return out;
    }

    static String render(String body, Map<String, String> values, Set<String> allowed) {
        String zpl;
        try {
            zpl = ZplRenderer.render(body, values, allowed);
        } catch (RenderException e) {
            if (e.code().startsWith("TEMPLATE_")) {
                throw fieldError("tplId", "TEMPLATE_INVALID");
            }
            throw fieldError(e.field() == null ? "values" : "values." + e.field(), e.code(), e.field() == null ? "" : e.field());
        }
        if (zpl.getBytes(StandardCharsets.UTF_8).length > MAX_ZPL_BYTES) {
            throw fieldError("values", "RENDERED_TOO_LONG");
        }
        return zpl;
    }

    private long enqueue(PrinterRow p, long tplId, String labelType, String bizKey, Map<String, String> values, String zpl,
            int copies, boolean test, Long reprintOf, String reason, LoginUser me) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("host", p.getHost());
        payload.put("port", p.getPort());
        payload.put("copies", copies);
        payload.put("zpl", zpl);
        if (AGENT.equals(p.getConnMode())) {
            // SaaS·망 분리(docs/65 19.2): 서버는 공장 안으로 밀 수 없다 — 아웃박스 없이 그 프린터 담당 에이전트의 큐에
            long job = mapper.insertJob(p.getPlantCd(), p.getPrinterId(), tplId, labelType, bizKey, JSON.writeValueAsString(values), zpl,
                    sha256(zpl), copies, test ? "Y" : "N", null, reprintOf, reason, me.loginId(), AGENT);
            agents.enqueue(job, p.getPrinterId(), p.getAgentTerminalId());
            return job;
        }
        UUID messageId = outbox.publish(TOPIC, labelType + ":" + bizKey, payload);
        return mapper.insertJob(p.getPlantCd(), p.getPrinterId(), tplId, labelType, bizKey, JSON.writeValueAsString(values), zpl,
                sha256(zpl), copies, test ? "Y" : "N", messageId, reprintOf, reason, me.loginId(), "DIRECT");
    }

    /** 정책 WMS_PRINTER_PORTS — 숫자가 아닌 조각은 무시 */
    Set<Integer> allowedPorts() {
        Set<Integer> out = new LinkedHashSet<>();
        String v = policy.value(POLICY_PORTS);
        if (v != null) {
            for (String s : v.split(",")) {
                try {
                    out.add(Integer.parseInt(s.strip()));
                } catch (NumberFormatException e) {
                    // 무시
                }
            }
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> readValues(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        Map<String, Object> raw = JSON.readValue(json, Map.class);
        Map<String, String> out = new LinkedHashMap<>();
        raw.forEach((k, v) -> out.put(k, v == null ? null : String.valueOf(v)));
        return out;
    }

    static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static String likeEscape(String s) {
        return s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    static BusinessException fieldError(String field, String code, Object... args) {
        return new BusinessException(HbErrorCode.INVALID_VALUE).withFieldErrors(FieldErrors.create().add(field, code, args).list());
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    static LoginUser me() {
        return CurrentUser.get().orElseThrow(() -> new BusinessException(HbErrorCode.UNAUTHENTICATED));
    }
}
