package io.smarthappy.module.wms.label;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.smarthappy.module.wms.label.LabelDtos.PrintItem;
import io.smarthappy.module.wms.label.LabelDtos.PreviewRequest;
import io.smarthappy.module.wms.label.LabelDtos.PreviewResult;
import io.smarthappy.module.wms.label.LabelDtos.PrinterForm;
import io.smarthappy.module.wms.label.LabelDtos.PrinterRow;
import io.smarthappy.module.wms.label.LabelDtos.SampleView;
import io.smarthappy.module.wms.label.LabelDtos.TestPrintRequest;
import io.smarthappy.module.wms.label.LabelDtos.TplForm;
import io.smarthappy.module.wms.label.LabelDtos.TplRow;
import io.smarthappy.module.wms.label.ZplRenderer.Issue;
import io.smarthappy.platform.common.db.PgErrors;
import io.smarthappy.platform.common.query.PageQuery;
import io.smarthappy.platform.common.web.BusinessException;
import io.smarthappy.platform.common.web.FieldErrors;
import io.smarthappy.platform.common.web.HbErrorCode;
import io.smarthappy.platform.grid.ColumnSpec;
import io.smarthappy.platform.messaging.InboxService;
import io.smarthappy.platform.policy.Policies;
import io.smarthappy.platform.policy.PolicyService;
import io.smarthappy.platform.security.LoginUser;

/**
 * 라벨 템플릿·프린터 관리(wms-label) — P4 W2, docs/30. 인쇄 자체는 {@link LabelPrintService}.
 * 템플릿 본문은 저장 때 {@link ZplRenderer#validate} 로 막는다(자리표시자 허용 목록·^FD 안·금지 명령).
 */
@Service
public class LabelService {

    static final String PROG = "wms-label";
    static final Pattern CODE = Pattern.compile("^[A-Z0-9][A-Z0-9_-]{0,19}$");
    static final Set<Integer> DPIS = Set.of(203, 300, 600);
    static final Set<String> USAGES = Set.of("ANY", "LOC", "ITEM", "LOT", "PALLET", "SHIP");
    /** 연결 방식(docs/65 3장) — AGENT 는 E2 */
    static final Set<String> CONN_MODES = Set.of("DIRECT", "AGENT");
    static final Pattern FONT_FILE = Pattern.compile("^[AERB]:[A-Z0-9_-]{1,30}\\.(TTF|TTE|FNT)$");

    static final Map<String, String[]> CONSTRAINT_FIELD = Map.of(
            "uq_wms_printer_cd", new String[] {"printerCd", "DUPLICATE"},
            "fk_wms_printer_plant", new String[] {"plantCd", "NOT_FOUND"},
            "uq_wms_label_tpl_cd", new String[] {"tplCd", "DUPLICATE"},
            "ck_wms_printer_host", new String[] {"host", "FORMAT"});

    private final LabelMapper mapper;
    private final LabelPrintService print;
    private final InboxService inbox;
    private final boolean allowLoopback;
    private final PolicyService policy;
    private final PrintTrackMapper track;

    public LabelService(LabelMapper mapper, LabelPrintService print, InboxService inbox,
            @Value("${happyboot.wms.print-allow-loopback:false}") boolean allowLoopback, PolicyService policy,
            PrintTrackMapper track) {
        this.mapper = mapper;
        this.print = print;
        this.inbox = inbox;
        this.allowLoopback = allowLoopback;
        this.policy = policy;
        this.track = track;
    }

    /** 주입 전 기본값 = 끔(fail-closed), docs/67 F3 */
    private volatile io.smarthappy.platform.config.LanDirectGate lanDirect = io.smarthappy.platform.config.LanDirectGate.fixed(false);

    @org.springframework.beans.factory.annotation.Autowired
    void setLanDirect(io.smarthappy.platform.config.LanDirectGate lanDirect) {
        this.lanDirect = lanDirect;
    }

    // ================================================================== 목록 그리드 (UI 단계 3 ①, 결정 docs/29 #45)

    static final List<ColumnSpec> TPL_COLUMNS = List.of(
            ColumnSpec.code("labelType", "wmsLabel.col.labelType", "WMS_LABEL_TYPE", 90).withSort(),
            ColumnSpec.text("tplCd", "wmsLabel.col.tplCd", 120).withSort(),
            ColumnSpec.text("tplNm", "wmsLabel.col.tplNm", 180).withFlex().withMinWidth(120).withSort(),
            ColumnSpec.dec("widthMm", "wmsLabel.col.widthMm", 1, 70).withMinScale(0),
            ColumnSpec.dec("heightMm", "wmsLabel.col.heightMm", 1, 70).withMinScale(0),
            ColumnSpec.text("sizeCd", "wmsLabel.col.sizeCd", 80).withPriority(3),
            ColumnSpec.of("dpi", "wmsLabel.col.dpi", ColumnSpec.ColumnType.INT, 60).withPriority(3),
            ColumnSpec.text("defaultYn", "wmsLabel.col.default", 60),             // 화면이 ★ 로 그린다(YN 형식은 사용/미사용 글자)
            ColumnSpec.of("useYn", "wmsLabel.col.use", ColumnSpec.ColumnType.YN, 70),
            ColumnSpec.of("updatedAt", "wmsLabel.col.updatedAt", ColumnSpec.ColumnType.DATETIME, 140).withSort().withPriority(3));
    static final List<String> TPL_SORT = List.of("labelType,asc", "tplCd,asc");

    static final List<ColumnSpec> PRINTER_COLUMNS = List.of(
            ColumnSpec.text("plantCd", "wmsLabel.col.plant", 80).withSort(),
            ColumnSpec.text("printerCd", "wmsLabel.col.printerCd", 120).withSort(),
            ColumnSpec.text("printerNm", "wmsLabel.col.printerNm", 160).withFlex().withMinWidth(110).withSort(),
            ColumnSpec.text("host", "wmsLabel.col.host", 130),
            ColumnSpec.text("port", "wmsLabel.col.port", 70),                      // 포트는 천 단위 쉼표 없이(폼과 같음)
            ColumnSpec.code("usage", "wmsLabel.col.usage", "WMS_PRINTER_USAGE", 90).withSort(),
            ColumnSpec.code("connMode", "wmsLabel.col.connMode", "EQUIP_CONN_MODE", 90),
            ColumnSpec.code("printerState", "wmsLabel.col.printerState", "WMS_PRINTER_STATE", 90),
            ColumnSpec.of("checkedAt", "wmsLabel.col.checkedAt", ColumnSpec.ColumnType.DATETIME, 140).withPriority(3),
            ColumnSpec.of("dpi", "wmsLabel.col.dpi", ColumnSpec.ColumnType.INT, 60).withPriority(3),
            ColumnSpec.of("useYn", "wmsLabel.col.use", ColumnSpec.ColumnType.YN, 70),
            ColumnSpec.of("updatedAt", "wmsLabel.col.updatedAt", ColumnSpec.ColumnType.DATETIME, 140).withSort().withPriority(3));
    static final List<String> PRINTER_SORT = List.of("plantCd,asc", "printerCd,asc");

    /** 그리드 한 쪽 */
    public record Page<T>(List<T> list, Long total) {
    }

    /** 템플릿 그리드 — 조건은 templates 와 같고 페이징·정렬(허용 열만)·건수. 기존 templates 는 그대로 */
    @Transactional(readOnly = true)
    public Page<TplRow> tplGrid(String labelType, String useYn, Integer page, Integer size, List<String> sort, Boolean total) {
        String t = blank(labelType) ? null : labelType.strip();
        if (t != null && !LabelFields.TYPES.contains(t)) {
            throw LabelPrintService.fieldError("labelType", "CODE");
        }
        String yn = yn(useYn);
        PageQuery pq = PageQuery.parse(page, size, sort, total, LabelDtos.TplSort.values(), policy.intValue(Policies.GRID_PAGE_SIZE_MAX),
                TPL_SORT);
        return new Page<>(mapper.findTplPage(t, yn, pq), pq.isWithTotal() ? mapper.countTpls(t, yn) : null);
    }

    /** 프린터 그리드 — 조건은 printers 와 같고 페이징·정렬·건수. 기존 printers 는 그대로 */
    @Transactional(readOnly = true)
    public Page<PrinterRow> printerGrid(String plantCd, String useYn, Integer page, Integer size, List<String> sort, Boolean total) {
        String p = blank(plantCd) ? null : plantCd.strip();
        String yn = yn(useYn);
        PageQuery pq = PageQuery.parse(page, size, sort, total, LabelDtos.PrinterSort.values(),
                policy.intValue(Policies.GRID_PAGE_SIZE_MAX), PRINTER_SORT);
        return new Page<>(mapper.findPrinterPage(p, yn, pq), pq.isWithTotal() ? mapper.countPrinters(p, yn) : null);
    }

    public List<ColumnSpec> tplColumns() {
        return TPL_COLUMNS;
    }

    public List<ColumnSpec> printerColumns() {
        return PRINTER_COLUMNS;
    }

    // ================================================================== 프린터

    @Transactional(readOnly = true)
    public List<PrinterRow> printers(String plantCd, String useYn) {
        return mapper.findPrinters(blank(plantCd) ? null : plantCd.strip(), yn(useYn));
    }

    @Transactional
    public long createPrinter(PrinterForm in) {
        LoginUser me = LabelPrintService.me();
        inbox.claimRequestKey("WMS_PRINTER_CREATE");
        PrinterForm f = validatePrinter(in, null);
        try {
            return mapper.insertPrinter(f, me.loginId());
        } catch (DataIntegrityViolationException e) {
            throw translate(e);
        }
    }

    @Transactional
    public void updatePrinter(long id, PrinterForm in) {
        LoginUser me = LabelPrintService.me();
        PrinterRow cur = mapper.findPrinter(id);
        if (cur == null) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        if (in == null || in.rowVer() == null) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        PrinterForm f = validatePrinter(in, cur);
        try {
            if (mapper.updatePrinter(id, f, me.loginId()) == 0) {
                throw new BusinessException(HbErrorCode.CONFLICT);
            }
        } catch (DataIntegrityViolationException e) {
            throw translate(e);
        }
    }

    private PrinterForm validatePrinter(PrinterForm in, PrinterRow cur) {
        if (in == null) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        FieldErrors e = FieldErrors.create();
        String plant = cur != null ? cur.getPlantCd() : blank(in.plantCd()) ? null : in.plantCd().strip().toUpperCase(Locale.ROOT);
        String cd = cur != null ? cur.getPrinterCd() : blank(in.printerCd()) ? null : in.printerCd().strip().toUpperCase(Locale.ROOT);
        if (cur != null) {
            if (in.plantCd() != null && !in.plantCd().strip().equalsIgnoreCase(cur.getPlantCd())) {
                e.add("plantCd", "FIXED");
            }
            if (in.printerCd() != null && !in.printerCd().strip().equalsIgnoreCase(cur.getPrinterCd())) {
                e.add("printerCd", "FIXED");
            }
        } else {
            if (plant == null) {
                e.add("plantCd", "REQUIRED");
            } else if (plant.length() > 20 || !mapper.plantActive(plant)) {
                e.add("plantCd", "NOT_FOUND");
            }
            if (cd == null) {
                e.add("printerCd", "REQUIRED");
            } else if (!CODE.matcher(cd).matches()) {
                e.add("printerCd", "FORMAT");
            }
        }
        String nm = text(in.printerNm(), "printerNm", 100, true, e);
        String host = blank(in.host()) ? null : in.host().strip();
        int port = in.port() == null ? 9100 : in.port();
        if (host == null) {
            e.add("host", "REQUIRED");
        } else if (!lanDirect.enabled()) {
            e.add("host", "LAN_DIRECT_OFF");   // SaaS: 서버가 사설망 프린터로 직접 연결하지 않는다(docs/67 F3)
        } else {
            PrinterAddress.Problem p = PrinterAddress.checkStatic(host, port, print.allowedPorts(), allowLoopback);
            if (p == PrinterAddress.Problem.PORT_NOT_ALLOWED) {
                e.add("port", "PORT_NOT_ALLOWED", String.join(",", print.allowedPorts().stream().map(String::valueOf).toList()));
            } else if (p != null) {
                e.add("host", p.name());
            }
        }
        int dpi = in.dpi() == null ? 203 : in.dpi();
        if (!DPIS.contains(dpi)) {
            e.add("dpi", "CODE");
        }
        String usage = blank(in.usage()) ? "ANY" : in.usage().strip();
        if (!USAGES.contains(usage)) {
            e.add("usage", "CODE");
        }
        String use = blank(in.useYn()) ? "Y" : in.useYn().strip();
        if (!"Y".equals(use) && !"N".equals(use)) {
            e.add("useYn", "CODE");
        }
        String remark = text(in.remark(), "remark", 500, false, e);
        // E1(docs/65 18.2·18.4): 한글 글꼴 · 용지 폭 · 상태 확인
        String cjk = blank(in.cjkFontYn()) ? (cur == null ? "N" : cur.getCjkFontYn()) : in.cjkFontYn().strip();
        if (!"Y".equals(cjk) && !"N".equals(cjk)) {
            e.add("cjkFontYn", "CODE");
        }
        String fontFile = blank(in.cjkFontFile()) ? null : in.cjkFontFile().strip().toUpperCase(Locale.ROOT);
        if (fontFile != null && !FONT_FILE.matcher(fontFile).matches()) {
            e.add("cjkFontFile", "FORMAT");
        }
        BigDecimal media = in.mediaWidthMm();
        if (media != null && (media.compareTo(BigDecimal.TEN) < 0 || media.compareTo(BigDecimal.valueOf(300)) > 0)) {
            e.add("mediaWidthMm", "CODE");
        } else if (media != null && media.stripTrailingZeros().scale() > 1) {
            e.add("mediaWidthMm", "SCALE", 1);
        }
        String check = blank(in.statusCheckYn()) ? (cur == null ? "Y" : cur.getStatusCheckYn()) : in.statusCheckYn().strip();
        if (!"Y".equals(check) && !"N".equals(check)) {
            e.add("statusCheckYn", "CODE");
        }
        String conn = blank(in.connMode()) ? (cur == null ? "DIRECT" : cur.getConnMode()) : in.connMode().strip();
        Long agent = null;
        if (!CONN_MODES.contains(conn)) {
            e.add("connMode", "CODE");
        } else if ("AGENT".equals(conn)) {
            // 에이전트 = 시스템관리에서 키를 발급한 장치(COLLECTOR, 폐기 안 됨) — 다른 회사 장치는 RLS 로 안 보인다
            agent = in.agentTerminalId();
            if (agent == null) {
                e.add("agentTerminalId", "REQUIRED");
            } else if (!mapper.agentActive(agent)) {
                e.add("agentTerminalId", "NOT_FOUND");
            }
        }
        e.throwIfAny();
        return new PrinterForm(plant, cd, nm, host, port, dpi, usage, use, remark, in.rowVer(), cjk, fontFile, media, check, conn,
                agent);
    }

    // ================================================================== 템플릿

    @Transactional(readOnly = true)
    public List<TplRow> templates(String labelType, String useYn) {
        String t = blank(labelType) ? null : labelType.strip();
        if (t != null && !LabelFields.TYPES.contains(t)) {
            throw LabelPrintService.fieldError("labelType", "CODE");
        }
        return mapper.findTpls(t, yn(useYn));
    }

    @Transactional(readOnly = true)
    public TplRow template(long id) {
        TplRow t = mapper.findTpl(id);
        if (t == null) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        return t;
    }

    @Transactional
    public long createTemplate(TplForm in) {
        LoginUser me = LabelPrintService.me();
        inbox.claimRequestKey("WMS_LABEL_TPL_CREATE");
        TplForm f = validateTpl(in, null);
        try {
            if ("Y".equals(f.defaultYn())) {
                mapper.clearDefault(f.labelType(), null, me.loginId());
            }
            return mapper.insertTpl(f, me.loginId());
        } catch (DataIntegrityViolationException e) {
            throw translate(e);
        }
    }

    @Transactional
    public void updateTemplate(long id, TplForm in) {
        LoginUser me = LabelPrintService.me();
        TplRow cur = mapper.findTpl(id);
        if (cur == null) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        if (in == null || in.rowVer() == null) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        TplForm f = validateTpl(in, cur);
        try {
            if ("Y".equals(f.defaultYn()) && !"Y".equals(cur.getDefaultYn())) {
                mapper.clearDefault(f.labelType(), id, me.loginId());
            }
            if (mapper.updateTpl(id, f, me.loginId()) == 0) {
                throw new BusinessException(HbErrorCode.CONFLICT);
            }
        } catch (DataIntegrityViolationException e) {
            throw translate(e);
        }
    }

    private TplForm validateTpl(TplForm in, TplRow cur) {
        if (in == null) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        FieldErrors e = FieldErrors.create();
        String type = cur != null ? cur.getLabelType() : blank(in.labelType()) ? null : in.labelType().strip();
        String cd = cur != null ? cur.getTplCd() : blank(in.tplCd()) ? null : in.tplCd().strip().toUpperCase(Locale.ROOT);
        if (cur != null) {
            if (in.labelType() != null && !in.labelType().strip().equals(cur.getLabelType())) {
                e.add("labelType", "FIXED");
            }
            if (in.tplCd() != null && !in.tplCd().strip().equalsIgnoreCase(cur.getTplCd())) {
                e.add("tplCd", "FIXED");
            }
        } else {
            if (type == null || !LabelFields.TYPES.contains(type)) {
                e.add("labelType", type == null ? "REQUIRED" : "CODE");
            }
            if (cd == null) {
                e.add("tplCd", "REQUIRED");
            } else if (!CODE.matcher(cd).matches()) {
                e.add("tplCd", "FORMAT");
            }
        }
        String nm = text(in.tplNm(), "tplNm", 100, true, e);
        BigDecimal w = size(in.widthMm(), "widthMm", e);
        BigDecimal h = size(in.heightMm(), "heightMm", e);
        int dpi = in.dpi() == null ? 203 : in.dpi();
        if (!DPIS.contains(dpi)) {
            e.add("dpi", "CODE");
        }
        String body = in.body() == null ? null : in.body().replace("\r\n", "\n");
        if (type != null && LabelFields.TYPES.contains(type)) {
            for (Issue i : ZplRenderer.validate(body, LabelFields.allowed(type))) {
                switch (i.problem()) {
                    case EMPTY -> e.add("body", "REQUIRED");
                    case TOO_LONG -> e.add("body", "TOO_LONG", ZplRenderer.MAX_BODY);
                    default -> e.add("body", i.problem().name(), i.detail() == null ? "" : i.detail());
                }
            }
        }
        String use = blank(in.useYn()) ? "Y" : in.useYn().strip();
        if (!"Y".equals(use) && !"N".equals(use)) {
            e.add("useYn", "CODE");
        }
        String def = blank(in.defaultYn()) ? "N" : in.defaultYn().strip();
        if (!"Y".equals(def) && !"N".equals(def)) {
            e.add("defaultYn", "CODE");
        } else if ("Y".equals(def) && "N".equals(use)) {
            e.add("defaultYn", "DEFAULT_INACTIVE");
        }
        String remark = text(in.remark(), "remark", 500, false, e);
        e.throwIfAny();
        return new TplForm(type, cd, nm, w, h, dpi, body, def, use, remark, in.rowVer());
    }

    private static BigDecimal size(BigDecimal v, String field, FieldErrors e) {
        if (v == null) {
            e.add(field, "REQUIRED");
            return null;
        }
        if (v.compareTo(BigDecimal.TEN) < 0 || v.compareTo(BigDecimal.valueOf(300)) > 0) {
            e.add(field, "CODE");
        } else if (v.stripTrailingZeros().scale() > 1) {
            e.add(field, "SCALE", 1);
        }
        return v;
    }

    // ================================================================== 미리보기·견본·시험 인쇄

    public SampleView sample(String labelType) {
        if (labelType == null || !LabelFields.TYPES.contains(labelType)) {
            throw LabelPrintService.fieldError("labelType", "CODE");
        }
        return new SampleView(labelType, LabelFields.sampleBody(labelType), LabelFields.sampleValues(labelType),
                List.copyOf(LabelFields.allowed(labelType)));
    }

    /** 저장 전 본문으로 렌더 — 인쇄하지 않는다 */
    public PreviewResult preview(PreviewRequest req) {
        if (req == null || req.labelType() == null || !LabelFields.TYPES.contains(req.labelType())) {
            throw LabelPrintService.fieldError("labelType", "CODE");
        }
        Set<String> allowed = LabelFields.allowed(req.labelType());
        FieldErrors e = FieldErrors.create();
        for (Issue i : ZplRenderer.validate(req.body(), allowed)) {
            e.add("body", i.problem() == ZplRenderer.Problem.EMPTY ? "REQUIRED" : i.problem().name(),
                    i.detail() == null ? "" : i.detail());
        }
        e.throwIfAny();
        Map<String, String> values = req.values() == null || req.values().isEmpty() ? LabelFields.sampleValues(req.labelType())
                : req.values();
        String zpl = LabelPrintService.render(req.body(), values, allowed);
        // 배치·넘침(docs/65 18.3) — 견본(또는 준 값) + 긴 값 두 벌. 템플릿 경고(한글 글꼴, 18.2)는 앞에
        BigDecimal w = req.widthMm() == null ? BigDecimal.valueOf(50) : req.widthMm();
        BigDecimal h = req.heightMm() == null ? BigDecimal.valueOf(30) : req.heightMm();
        int dpi = req.dpi() == null || !DPIS.contains(req.dpi()) ? 203 : req.dpi();
        ZplLayout.Layout layout = ZplLayout.layout(ZplRenderer.renderMarked(req.body(), values, allowed), w, h, dpi);
        List<String> warns = new java.util.ArrayList<>(ZplLint.warnings(req.body()));
        warns.addAll(layout.warnings());
        List<String> longWarns = List.of();
        try {
            longWarns = ZplLayout.layout(ZplRenderer.renderMarked(req.body(), LabelFields.longValues(req.labelType()), allowed), w, h, dpi)
                    .warnings();
        } catch (ZplRenderer.RenderException ex) {
            // 긴 값이 렌더 상한을 넘는 템플릿 — 경고 계산만 건너뛴다
        }
        return new PreviewResult(zpl, values, layout, List.copyOf(warns), longWarns);
    }

    // ================================================================== 템플릿 선택 규칙 (docs/65 18.4)

    static final Set<String> SCOPES = Set.of("CUST", "ITEM");

    /** 프린터 화면 에이전트 고르기 — 사용 중 장치(COLLECTOR). 키 발급·폐기는 시스템관리 sys-terminal */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> agentOptions() {
        return mapper.agentOptions(policy.intValue("WMS_PRINT_AGENT_OFFLINE_SEC"));
    }

    @Transactional(readOnly = true)
    public PrinterRow printer(long id) {
        PrinterRow p = mapper.findPrinter(id);
        if (p == null) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        return p;
    }

    /** 규칙 대상 고르기 — 사용 중 거래처·품목만, 다른 회사는 RLS 로 안 보인다 */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> ruleTargets(String scope, String q) {
        if (scope == null || !SCOPES.contains(scope)) {
            throw LabelPrintService.fieldError("scope", "CODE");
        }
        String s = q == null ? "" : q.strip();
        if (s.length() > 50) {
            throw LabelPrintService.fieldError("q", "TOO_LONG", 50);
        }
        String like = "%" + LabelPrintService.likeEscape(s) + "%";
        return "CUST".equals(scope) ? track.custOptions(like) : track.itemOptions(like);
    }

    @Transactional(readOnly = true)
    public List<LabelDtos.RuleRow> rules(String labelType, String useYn) {
        String t = blank(labelType) ? null : labelType.strip();
        if (t != null && !LabelFields.TYPES.contains(t)) {
            throw LabelPrintService.fieldError("labelType", "CODE");
        }
        return mapper.findRules(t, yn(useYn));
    }

    @Transactional
    public long createRule(LabelDtos.RuleForm in) {
        LoginUser me = LabelPrintService.me();
        inbox.claimRequestKey("WMS_LABEL_RULE_CREATE");
        LabelDtos.RuleForm f = validateRule(in, null);
        try {
            return mapper.insertRule(f, me.loginId());
        } catch (DataIntegrityViolationException e) {
            throw translateRule(e);
        }
    }

    @Transactional
    public void updateRule(long id, LabelDtos.RuleForm in) {
        LoginUser me = LabelPrintService.me();
        LabelDtos.RuleRow cur = mapper.findRule(id);
        if (cur == null) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        if (in == null || in.rowVer() == null) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        LabelDtos.RuleForm f = validateRule(in, cur);
        try {
            if (mapper.updateRule(id, f, me.loginId()) == 0) {
                throw new BusinessException(HbErrorCode.CONFLICT);
            }
        } catch (DataIntegrityViolationException e) {
            throw translateRule(e);
        }
    }

    private LabelDtos.RuleForm validateRule(LabelDtos.RuleForm in, LabelDtos.RuleRow cur) {
        if (in == null) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        FieldErrors e = FieldErrors.create();
        String type = cur != null ? cur.getLabelType() : blank(in.labelType()) ? null : in.labelType().strip();
        String scope = cur != null ? cur.getScopeCd() : blank(in.scopeCd()) ? null : in.scopeCd().strip();
        Long cust = cur != null ? cur.getCustId() : in.custId();
        Long item = cur != null ? cur.getItemId() : in.itemId();
        if (cur != null) {
            // 대상(유형·범위·거래처·품목)은 고정 — 바꾸려면 새 규칙(트리거도 막는다)
            if ((in.labelType() != null && !in.labelType().strip().equals(cur.getLabelType()))
                    || (in.scopeCd() != null && !in.scopeCd().strip().equals(cur.getScopeCd()))
                    || (in.custId() != null && !in.custId().equals(cur.getCustId()))
                    || (in.itemId() != null && !in.itemId().equals(cur.getItemId()))) {
                e.add("scopeCd", "FIXED");
            }
        } else {
            if (type == null || !LabelFields.TYPES.contains(type)) {
                e.add("labelType", type == null ? "REQUIRED" : "CODE");
            }
            if (scope == null || !SCOPES.contains(scope)) {
                e.add("scopeCd", scope == null ? "REQUIRED" : "CODE");
            } else if ("CUST".equals(scope)) {
                item = null;
                if (cust == null) {
                    e.add("custId", "REQUIRED");
                } else if (!mapper.custActive(cust)) {
                    e.add("custId", "NOT_FOUND");                 // 다른 회사 거래처도 여기서(RLS 로 안 보인다)
                }
            } else {
                cust = null;
                if (item == null) {
                    e.add("itemId", "REQUIRED");
                } else if (!mapper.itemActive(item)) {
                    e.add("itemId", "NOT_FOUND");
                }
            }
        }
        TplRow t = in.tplId() == null ? null : mapper.findTpl(in.tplId());
        if (in.tplId() == null) {
            e.add("tplId", "REQUIRED");
        } else if (t == null) {
            e.add("tplId", "NOT_FOUND");
        } else if (type != null && !t.getLabelType().equals(type)) {
            e.add("tplId", "LABEL_TYPE_MISMATCH");
        } else if (!"Y".equals(t.getUseYn())) {
            e.add("tplId", "INACTIVE");
        }
        String use = blank(in.useYn()) ? "Y" : in.useYn().strip();
        if (!"Y".equals(use) && !"N".equals(use)) {
            e.add("useYn", "CODE");
        }
        String remark = text(in.remark(), "remark", 500, false, e);
        e.throwIfAny();
        return new LabelDtos.RuleForm(type, scope, cust, item, in.tplId(), use, remark, in.rowVer());
    }

    private RuntimeException translateRule(DataIntegrityViolationException e) {
        String c = PgErrors.constraint(e).orElse(null);
        if ("uq_wms_label_tpl_rule".equals(c)) {
            return LabelPrintService.fieldError("scopeCd", "DUPLICATE");
        }
        if (c != null && c.startsWith("fk_wms_label_tpl_rule")) {
            return LabelPrintService.fieldError("tplId", "NOT_FOUND");
        }
        return e;
    }

    /** 저장된 템플릿으로 시험 인쇄(작업 기록 test_yn = Y) */
    @Transactional
    public long testPrint(long tplId, TestPrintRequest req) {
        TplRow t = template(tplId);
        if (req == null || req.printerId() == null) {
            throw LabelPrintService.fieldError("printerId", "REQUIRED");
        }
        Map<String, String> values = req.values() == null || req.values().isEmpty() ? LabelFields.sampleValues(t.getLabelType())
                : req.values();
        return print.request(t.getLabelType(), tplId, req.printerId(), 1, List.of(new PrintItem("TEST:" + t.getTplCd(), values)),
                true).get(0);
    }

    // ================================================================== 공통

    private RuntimeException translate(DataIntegrityViolationException e) {
        String c = PgErrors.constraint(e).orElse(null);
        if ("uq_wms_label_tpl_default".equals(c)) {
            return new BusinessException(HbErrorCode.CONFLICT);       // 같은 유형 기본을 두 사람이 동시에
        }
        String[] fe = c == null ? null : CONSTRAINT_FIELD.get(c);
        return fe == null ? e : LabelPrintService.fieldError(fe[0], fe[1]);
    }

    private static String yn(String v) {
        if (blank(v)) {
            return null;
        }
        if (!"Y".equals(v) && !"N".equals(v)) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        return v;
    }

    private static String text(String v, String field, int max, boolean required, FieldErrors e) {
        if (blank(v)) {
            if (required) {
                e.add(field, "REQUIRED");
            }
            return null;
        }
        String s = v.strip();
        if (s.length() > max) {
            e.add(field, "TOO_LONG", max);
        }
        if (s.codePoints().anyMatch(Character::isISOControl) && !"remark".equals(field)) {
            e.add(field, "FORMAT");
        }
        return s;
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
