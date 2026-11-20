package io.smarthappy.module.wms.labeler;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import io.smarthappy.module.pop.TerminalPrincipal;
import io.smarthappy.module.wms.label.LabelDtos.TplRow;
import io.smarthappy.module.wms.label.LabelFields;
import io.smarthappy.module.wms.label.LabelMapper;
import io.smarthappy.module.wms.label.ZplRenderer;
import io.smarthappy.module.wms.labeler.LabelerDtos.Answer;
import io.smarthappy.module.wms.labeler.LabelerDtos.BoxRow;
import io.smarthappy.module.wms.labeler.LabelerDtos.DeviceEventIn;
import io.smarthappy.module.wms.labeler.LabelerDtos.DeviceEventView;
import io.smarthappy.module.wms.labeler.LabelerDtos.LabelerRow;
import io.smarthappy.module.wms.labeler.LabelerDtos.PrefetchItem;
import io.smarthappy.module.wms.labeler.LabelerDtos.ReleaseView;
import io.smarthappy.module.wms.labeler.LabelerDtos.RequestIn;
import io.smarthappy.module.wms.labeler.LabelerDtos.ResultIn;
import io.smarthappy.module.wms.labeler.LabelerDtos.ResultView;
import io.smarthappy.module.wms.scan.ScanKeyResolver;
import io.smarthappy.module.wms.ship.Sscc;
import io.smarthappy.platform.common.web.BusinessException;
import io.smarthappy.platform.common.web.FieldErrors;
import io.smarthappy.platform.common.web.HbErrorCode;
import io.smarthappy.platform.numbering.NumberingService;
import io.smarthappy.platform.policy.PolicyService;
import io.smarthappy.platform.tenant.TenantContext;
import io.smarthappy.platform.time.BizClock;
import tools.jackson.databind.json.JsonMapper;

/**
 * 오토라벨러 장비 쪽 (docs/65 21장) — 데이터 요청·결과 대조·미리 받기·결번·장비 사건.
 *
 * <ul>
 * <li>YeonOS 는 <b>기한 안에 데이터를 넣고 결과를 대조</b>할 뿐 — 인쇄 트리거·부착은 장비(PLC)</li>
 * <li>늦게 온 정답 = 오답: 기한(장비가 읽은 뒤 경과 + 서버 처리)을 넘기면 REJECT LATE — 그 박스에 쓰지 않는다</li>
 * <li>SSCC 는 출하 팔레트와 같은 일련번호(채번 규칙 WMS_SSCC), 박스에 한 번 붙으면 재라벨도 같은 번호. 미리 받은 번호를 안 쓰면 결번(재사용 없음)</li>
 * <li>멱등: 같은 requestId = 처음 답, (requestId, 단계) 한 번</li>
 * </ul>
 */
@Service
public class LabelerDeviceService {

    static final String REASON_GRP = "WMS_LABELER_REASON";
    static final String RULE_SSCC = "WMS_SSCC";                 // ShipService.RULE_SSCC 와 같은 줄 — 박스·팔레트 SSCC 가 겹치지 않게
    static final String POLICY_GS1 = "WMS_GS1_COMPANY_PREFIX";
    static final String POLICY_REJECT_FULL = "WMS_LABELER_REJECT_FULL";
    static final String POLICY_PREFETCH_HOURS = "WMS_LABELER_PREFETCH_HOURS";
    static final int PREFETCH_MAX = 200;
    static final Set<String> PHASES = Set.of("PRINTED", "APPLIED", "VERIFY", "REJECTED");
    static final Set<String> ACTIONS = Set.of("PRINT", "SKIP", "REJECT");
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final LabelerMapper mapper;
    private final LabelMapper labels;
    private final NumberingService numbering;
    private final PolicyService policy;
    private final BizClock clock;

    public LabelerDeviceService(LabelerMapper mapper, LabelMapper labels, NumberingService numbering, PolicyService policy,
            BizClock clock) {
        this.mapper = mapper;
        this.labels = labels;
        this.numbering = numbering;
        this.policy = policy;
        this.clock = clock;
    }

    /** 장치 키 → 라벨러. 없으면 404(이 장치는 라벨러가 아니다) */
    LabelerRow labelerOf(TerminalPrincipal d) {
        LabelerRow l = mapper.findByTerminal(d.terminalId());
        if (l == null) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        return l;
    }

    // ------------------------------------------------------------------ 요청

    /**
     * @param startNanos 요청을 받은 때(컨트롤러) — 서버 처리 시간을 기한에 넣는다
     */
    @Transactional
    public Answer request(TerminalPrincipal d, RequestIn in, long startNanos) {
        LabelerRow l = labelerOf(d);
        String reqId = requestId(in == null ? null : in.requestId());
        String prev = mapper.answerOf(l.getLabelerId(), reqId);
        if (prev != null) {
            return JSON.readValue(prev, Answer.class).asReplay();            // 같은 요청 다시 = 처음 답
        }
        String scanned = in.scanned() == null ? "" : in.scanned().strip();
        if (scanned.length() > 200) {
            throw fieldError("scanned", "TOO_LONG", 200);
        }
        boolean local = Boolean.TRUE.equals(in.answeredLocally());
        if (local && !"EDGE".equals(l.getConnMode())) {
            throw new BusinessException(HbErrorCode.CONFLICT);                // 현장 응답 올리기는 SaaS 엣지만
        }
        OffsetDateTime at = occurredAt(in.occurredAt());
        String src = local ? "EDGE" : "DIRECT";
        if (local) {
            return recordLocal(l, reqId, scanned, in, at, startNanos);
        }
        // --- 판정
        if (scanned.isEmpty()) {
            return answer(l, reqId, null, "REJECT", "NO_READ", null, scanned, at, src, startNanos, null);
        }
        BoxRow b = mapper.lockByCode(l.getPlantCd(), scanned);
        if (b == null) {
            return answer(l, reqId, null, "REJECT", "UNREGISTERED", null, scanned, at, src, startNanos, null);
        }
        if ("VERIFIED".equals(b.getStatus())) {
            return answer(l, reqId, b, "REJECT", "DUPLICATE", b.getSscc(), scanned, at, src, startNanos, null);
        }
        if ("SKIPPED".equals(b.getStatus()) || "Y".equals(b.getLabelSkipYn())) {
            if (!"SKIPPED".equals(b.getStatus())) {
                setState(b, "SKIPPED", b.getSscc(), "LABEL_SKIP", 0, null, true);
            }
            return answer(l, reqId, b, "SKIP", "LABEL_SKIP", null, scanned, at, src, startNanos, null);
        }
        if ("PREFETCHED".equals(b.getStatus())) {
            // 엣지가 미리 받은 박스가 다른 길(LAN 직접)로 옴 — 번호가 두 군데에 있으면 두 장이 될 수 있다
            return answer(l, reqId, b, "REJECT", "DUPLICATE", b.getSscc(), scanned, at, src, startNanos, null);
        }
        // REGISTERED · REQUESTED(응답 잃고 다시 읽음) · REJECTED(재라벨) → PRINT, SSCC 는 있으면 그대로
        String sscc = b.getSscc() != null ? b.getSscc() : newSscc();
        TplRow t = template(l);
        Map<String, String> values = values(b, sscc);
        String zpl = render(t, values);
        if ("REQUESTED".equals(b.getStatus())) {
            setState(b, "REJECTED", sscc, "OTHER", 0, null, false);          // 앞 요청은 결과 없이 끝남 — 이번 요청으로 다시
        }
        setState(b, "REQUESTED", sscc, null, 1, null, false);
        int elapsed = elapsed(in.ageMs(), startNanos);
        if (elapsed > l.getDeadlineMs()) {
            // 늦게 온 정답 = 오답: 이 박스에는 쓰지 않는다 — 박스는 리젝트(SSCC 는 남겨 재라벨 때 같은 번호)
            setState(b, "REJECTED", sscc, "LATE", 0, null, false);
            return answer(l, reqId, b, "REJECT", "LATE", sscc, scanned, at, src, startNanos, null);
        }
        return answer(l, reqId, b, "PRINT", null, sscc, scanned, at, src, startNanos, new Rendered(t, values, zpl));
    }

    record Rendered(TplRow tpl, Map<String, String> values, String zpl) {
    }

    /** SaaS 엣지가 현장에서 이미 답한 것 — 미리 받은 박스·번호와 맞는지만 본다(기한 검사 없음 — 저장 후 전달) */
    private Answer recordLocal(LabelerRow l, String reqId, String scanned, RequestIn in, OffsetDateTime at, long startNanos) {
        String action = in.action() == null ? null : in.action().strip();
        if (action == null || !ACTIONS.contains(action)) {
            throw fieldError("action", "CODE");
        }
        String reason = in.reasonCd() == null || in.reasonCd().isBlank() ? null : reason(in.reasonCd().strip());
        BoxRow b = scanned.isEmpty() ? null : mapper.lockByCode(l.getPlantCd(), scanned);
        if (!"PRINT".equals(action)) {
            return answer(l, reqId, b, action, reason == null ? "OTHER" : reason, b == null ? null : b.getSscc(), scanned, at, "EDGE",
                    startNanos, null);
        }
        String sscc = in.sscc() == null ? null : in.sscc().strip();
        boolean mine = b != null && l.getLabelerId() == (b.getPrefetchedBy() == null ? -1 : b.getPrefetchedBy())
                && "PREFETCHED".equals(b.getStatus());
        boolean relabel = b != null && "REJECTED".equals(b.getStatus());
        if (b == null || sscc == null || !sscc.equals(b.getSscc()) || !(mine || relabel)) {
            throw fieldError("sscc", "SSCC_MISMATCH");                      // 미리 받지 않은 박스·다른 번호 — 받으면 장부가 틀린다
        }
        setState(b, "REQUESTED", sscc, null, 1, null, false);
        TplRow t = template(l);
        Map<String, String> values = values(b, sscc);
        return answer(l, reqId, b, "PRINT", null, sscc, scanned, at, "EDGE", startNanos, new Rendered(t, values, render(t, values)));
    }

    private Answer answer(LabelerRow l, String reqId, BoxRow b, String action, String reason, String sscc, String scanned,
            OffsetDateTime at, String src, long startNanos, Rendered r) {
        int elapsed = (int) Math.min(Integer.MAX_VALUE, (System.nanoTime() - startNanos) / 1_000_000L);
        Answer a = new Answer(reqId, action, reason, b == null ? null : b.getBoxCd(), sscc, r == null ? null : r.tpl().getTplCd(),
                r == null ? null : r.tpl().getSizeCd(), r == null ? null : r.values(), r == null ? null : r.zpl(), elapsed, false);
        mapper.insertEvent(l.getLabelerId(), b == null ? null : b.getBoxId(), reqId, "REQUEST", action, reason, sscc,
                trim(scanned, 200), elapsed, JSON.writeValueAsString(a), null, src, at, "SCAN", actor());
        return a;
    }

    // ------------------------------------------------------------------ 결과

    @Transactional
    public ResultView result(TerminalPrincipal d, ResultIn in) {
        LabelerRow l = labelerOf(d);
        String reqId = requestId(in == null ? null : in.requestId());
        String phase = in.phase() == null ? null : in.phase().strip();
        if (phase == null || !PHASES.contains(phase)) {
            throw fieldError("phase", "CODE");
        }
        Long boxId = mapper.requestBox(l.getLabelerId(), reqId);
        if (boxId == null) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);              // 이 라벨러의 요청이 아니다(다른 장치·다른 회사 포함)
        }
        OffsetDateTime at = occurredAt(in.occurredAt());
        String detail = in.detail() == null || in.detail().isBlank() ? null : trim(in.detail().strip(), 300);
        if (boxId == 0) {
            // 박스 없는 답(no-read·미등록)에 대한 보고 — 장비 리젝트 확인만 남긴다
            return phaseOnly(l, reqId, phase, null, at, detail, in);
        }
        BoxRow b = mapper.lockBox(boxId);
        // 이 요청이 그 박스의 마지막 요청이고 박스가 아직 진행 중일 때만 반영 — 아니면 늦은 결과
        if (!"REQUESTED".equals(b.getStatus()) || !reqId.equals(mapper.lastRequestOf(boxId))) {
            // 같은 단계를 이미 받았으면 DUP(검증은 OK·NG 어느 쪽이든) — 장비 재전송을 늦은 결과로 세지 않는다
            for (String eventCd : "VERIFY".equals(phase) ? List.of("VERIFY_OK", "VERIFY_NG") : List.of(phase)) {
                if (mapper.phaseExists(l.getLabelerId(), reqId, eventCd)) {
                    return new ResultView(reqId, phase, "DUP", b.getStatus(), eventCd, null);
                }
            }
            mapper.insertEvent(l.getLabelerId(), boxId, reqId, "STALE", null, null, b.getSscc(), trim(in.scanned(), 200), null, null,
                    phase + " after " + b.getStatus(), "DIRECT", at, "AUTO", actor());
            return new ResultView(reqId, phase, "STALE", b.getStatus(), "STALE", null);
        }
        switch (phase) {
            case "PRINTED", "APPLIED" -> {
                return phaseOnly(l, reqId, phase, b, at, detail, in);
            }
            case "VERIFY" -> {
                String scanned = in.scanned() == null ? "" : in.scanned().strip();
                String read = ssccOf(scanned, l.getPlantCd());
                boolean ok = read != null && read.equals(b.getSscc());
                String eventCd = ok ? "VERIFY_OK" : "VERIFY_NG";
                if (mapper.phaseExists(l.getLabelerId(), reqId, "VERIFY_OK") || mapper.phaseExists(l.getLabelerId(), reqId, "VERIFY_NG")) {
                    return new ResultView(reqId, phase, "DUP", b.getStatus(), eventCd, null);
                }
                String reason = ok ? null : scanned.isEmpty() ? "VERIFY_NO_READ" : "VERIFY_MISMATCH";
                mapper.insertEvent(l.getLabelerId(), boxId, reqId, eventCd, null, reason, b.getSscc(), trim(scanned, 200), null, null,
                        ok ? null : "read=" + (read == null ? "-" : read), "DIRECT", at, "SCAN", actor());
                setState(b, ok ? "VERIFIED" : "REJECTED", b.getSscc(), reason, 0, null, ok);
                return new ResultView(reqId, phase, "MOVED", ok ? "VERIFIED" : "REJECTED", eventCd, reason);
            }
            default -> {
                if (mapper.phaseExists(l.getLabelerId(), reqId, "REJECTED")) {
                    return new ResultView(reqId, phase, "DUP", b.getStatus(), "REJECTED", null);
                }
                String raw = in.reasonCd() == null || in.reasonCd().isBlank() ? "OTHER" : in.reasonCd().strip();
                String reason = reason(raw);
                mapper.insertEvent(l.getLabelerId(), boxId, reqId, "REJECTED", null, reason, b.getSscc(), null, null, null,
                        reason.equals(raw) ? detail : trim("device=" + raw + (detail == null ? "" : " " + detail), 500), "DIRECT", at,
                        "AUTO", actor());
                setState(b, "REJECTED", b.getSscc(), reason, 0, null, false);
                return new ResultView(reqId, phase, "MOVED", "REJECTED", "REJECTED", reason);
            }
        }
    }

    private ResultView phaseOnly(LabelerRow l, String reqId, String phase, BoxRow b, OffsetDateTime at, String detail, ResultIn in) {
        String eventCd = "VERIFY".equals(phase) ? "VERIFY_NG" : phase;
        if (mapper.phaseExists(l.getLabelerId(), reqId, eventCd)) {
            return new ResultView(reqId, phase, "DUP", b == null ? null : b.getStatus(), eventCd, null);
        }
        String reason = "REJECTED".equals(phase) ? reason(in.reasonCd() == null || in.reasonCd().isBlank() ? "OTHER" : in.reasonCd().strip())
                : null;
        mapper.insertEvent(l.getLabelerId(), b == null ? null : b.getBoxId(), reqId, eventCd, null, reason, b == null ? null : b.getSscc(),
                trim(in.scanned(), 200), null, null, detail, "DIRECT", at, "AUTO", actor());
        return new ResultView(reqId, phase, "MOVED", b == null ? null : b.getStatus(), eventCd, reason);
    }


    /** 검증 스캔 → SSCC: GS1 (00) / ]C1 00… / 18자리 숫자. 못 읽으면 null */
    String ssccOf(String scanned, String plantCd) {
        if (scanned == null || scanned.isEmpty()) {
            return null;
        }
        if (scanned.matches("^[0-9]{18}$")) {
            return scanned;
        }
        ScanKeyResolver.Result r = ScanKeyResolver.resolve(scanned,
                new ScanKeyResolver.Context(plantCd, ScanKeyResolver.BareAs.NONE, clock.today()));
        return r.type() == ScanKeyResolver.Type.GS1 && r.gs1() != null ? r.gs1().sscc() : null;
    }

    // ------------------------------------------------------------------ 미리 받기·결번 (SaaS)

    @Transactional
    public List<PrefetchItem> prefetch(TerminalPrincipal d, Integer max) {
        LabelerRow l = labelerOf(d);
        if (!"EDGE".equals(l.getConnMode())) {
            throw new BusinessException(HbErrorCode.CONFLICT);                // 설치형(DIRECT)은 기한 안에 바로 묻는다
        }
        int n = Math.max(1, Math.min(PREFETCH_MAX, max == null ? 50 : max));
        TplRow t = template(l);
        List<PrefetchItem> out = new ArrayList<>();
        for (BoxRow b : mapper.lockForPrefetch(l.getPlantCd(), n)) {
            String sscc = b.getSscc() != null ? b.getSscc() : newSscc();
            setState(b, "PREFETCHED", sscc, null, 0, l.getLabelerId(), false);
            mapper.insertEvent(l.getLabelerId(), b.getBoxId(), null, "PREFETCH", null, null, sscc, null, null, null, null, "EDGE",
                    now(), "AUTO", actor());
            Map<String, String> v = values(b, sscc);
            out.add(new PrefetchItem(b.getBoxCd(), sscc, t.getTplCd(), t.getSizeCd(), v, render(t, v)));
        }
        return out;
    }

    /** 엣지가 쓰지 않은 미리 받기를 놓는다 — SSCC 결번(재사용·이월 없음, Q5) */
    @Transactional
    public ReleaseView release(TerminalPrincipal d, List<String> boxCds) {
        LabelerRow l = labelerOf(d);
        if (boxCds == null || boxCds.isEmpty() || boxCds.size() > PREFETCH_MAX) {
            throw fieldError("boxCds", boxCds == null || boxCds.isEmpty() ? "REQUIRED" : "TOO_MANY_LABELS", PREFETCH_MAX);
        }
        int n = 0;
        List<String> unknown = new ArrayList<>();
        for (String cd : boxCds) {
            BoxRow b = cd == null ? null : mapper.lockByCode(l.getPlantCd(), cd.strip());
            if (b == null || !"PREFETCHED".equals(b.getStatus()) || b.getPrefetchedBy() == null || b.getPrefetchedBy() != l.getLabelerId()) {
                unknown.add(cd);
                continue;
            }
            voidPrefetch(b, l.getLabelerId(), "EDGE");
            n++;
        }
        return new ReleaseView(n, unknown);
    }

    /** 정기 작업(회사 범위 트랜잭션 안) — 미리 받고 정책 시간 안에 안 쓴 박스 결번 */
    @Transactional(propagation = Propagation.MANDATORY)
    public int voidStalePrefetch() {
        int n = 0;
        for (BoxRow b : mapper.lockPrefetchedOlder(policy.intValue(POLICY_PREFETCH_HOURS))) {
            voidPrefetch(b, b.getPrefetchedBy(), "SYSTEM");
            n++;
        }
        return n;
    }

    private void voidPrefetch(BoxRow b, Long labelerId, String src) {
        mapper.insertEvent(labelerId, b.getBoxId(), null, "SSCC_VOID", null, "PREFETCH_UNUSED", b.getSscc(), null, null, null,
                "결번 — 다시 쓰지 않음", src, now(), "AUTO", actor());
        setState(b, "REGISTERED", null, "PREFETCH_UNUSED", 0, null, false);
    }

    // ------------------------------------------------------------------ 장비 사건

    @Transactional
    public DeviceEventView deviceEvent(TerminalPrincipal d, DeviceEventIn in) {
        LabelerRow l = labelerOf(d);
        String type = in == null || in.type() == null ? null : in.type().strip();
        if (type == null || !Set.of("ERROR", "STATE", "REJECT_FULL").contains(type)) {
            throw fieldError("type", "CODE");
        }
        String code = in.code() == null || in.code().isBlank() ? null : in.code().strip();
        if (code != null && !code.matches("^[A-Z][A-Z0-9_]{0,39}$")) {
            throw fieldError("code", "FORMAT");
        }
        String reply = null;
        if ("REJECT_FULL".equals(type)) {
            String v = policy.value(POLICY_REJECT_FULL, l.getPlantCd());
            reply = v == null || !Set.of("WAIT", "ALARM", "STOP").contains(v) ? "STOP" : v;
        }
        long id = mapper.insertDevLog(l.getLabelerId(), type, code, in.detail() == null ? null : trim(in.detail().strip(), 500), reply,
                occurredAt(in.occurredAt()), actor());
        return new DeviceEventView(id, reply);
    }

    // ------------------------------------------------------------------ 내부

    private void setState(BoxRow b, String status, String sscc, String reason, int attemptAdd, Long prefetchedBy, boolean done) {
        mapper.setBoxState(b.getBoxId(), status, sscc, reason, attemptAdd, prefetchedBy, done, actor());
        b.setStatus(status);
        b.setSscc(sscc);
        b.setPrefetchedBy(prefetchedBy);
    }

    /** 라벨 값 — PALLET 유형 칸(물류 단위 SSCC 라벨). barcode = SSCC, gs1 = (00)SSCC */
    static Map<String, String> values(BoxRow b, String sscc) {
        Map<String, String> v = new LinkedHashMap<>();
        v.put("palletNo", b.getBoxCd());
        v.put("sscc", sscc);
        v.put("itemCd", b.getItemCd());
        v.put("itemNm", b.getItemNm());
        v.put("qty", b.getQty().stripTrailingZeros().toPlainString());
        v.put("unitCd", b.getUnitCd());
        v.put("lotCount", b.getLotNo() == null ? "0" : "1");
        v.put("barcode", sscc);
        v.put("gs1", "(00)" + sscc);
        return v;
    }

    private TplRow template(LabelerRow l) {
        TplRow t = labels.findTpl(l.getTplId());
        if (t == null || !"Y".equals(t.getUseYn()) || !"PALLET".equals(t.getLabelType())) {
            throw new BusinessException(HbErrorCode.CONFLICT);                // 라벨러 템플릿이 사용 중지됨 — 라벨러 설정 확인
        }
        return t;
    }

    private static String render(TplRow t, Map<String, String> values) {
        try {
            return ZplRenderer.render(t.getBody(), values, LabelFields.allowed("PALLET"));
        } catch (ZplRenderer.RenderException e) {
            throw new BusinessException(HbErrorCode.CONFLICT);
        }
    }

    /** 새 SSCC — 업체코드 정책이 없거나 모양이 틀리면 409(라벨러는 SSCC 없이 못 돈다) */
    private String newSscc() {
        String prefix = policy.value(POLICY_GS1);
        if (prefix == null || !Sscc.prefixOk(prefix.strip())) {
            throw new BusinessException(HbErrorCode.WMS_SSCC_UNAVAILABLE);
        }
        long serial = Long.parseLong(numbering.nextOrDefault(RULE_SSCC, "SSCC 일련번호", "{seq:10}", "NONE"));
        String sscc = Sscc.build(0, prefix.strip(), serial);
        if (sscc == null) {
            throw new BusinessException(HbErrorCode.WMS_SSCC_UNAVAILABLE);
        }
        return sscc;
    }

    /** 장비가 보낸 사유 — 이 회사가 쓸 수 있는 코드가 아니면 OTHER(수신을 깨지 않는다, 원래 코드는 설명에) */
    String reason(String raw) {
        if (raw != null && raw.matches("^[A-Z0-9][A-Z0-9_-]{0,29}$") && mapper.codeUsable(REASON_GRP, raw)) {
            return raw;
        }
        return "OTHER";
    }

    private static String requestId(String s) {
        String r = s == null ? "" : s.strip();
        if (!r.matches("^[A-Za-z0-9._:-]{1,64}$")) {
            throw fieldError("requestId", r.isEmpty() ? "REQUIRED" : "FORMAT");
        }
        return r;
    }

    private static int elapsed(Integer ageMs, long startNanos) {
        long server = (System.nanoTime() - startNanos) / 1_000_000L;
        long age = ageMs == null || ageMs < 0 ? 0 : ageMs;
        return (int) Math.min(Integer.MAX_VALUE, age + server);
    }

    OffsetDateTime occurredAt(String s) {
        Instant now = clock.instant();
        if (s == null || s.isBlank()) {
            return now.atOffset(ZoneOffset.UTC);
        }
        try {
            Instant t = OffsetDateTime.parse(s.strip()).toInstant();
            return (t.isAfter(now.plusSeconds(60)) ? now : t).atOffset(ZoneOffset.UTC);
        } catch (DateTimeParseException e) {
            return now.atOffset(ZoneOffset.UTC);
        }
    }

    private OffsetDateTime now() {
        return clock.instant().atOffset(ZoneOffset.UTC);
    }

    private static String actor() {
        String a = TenantContext.currentActor();
        return a.length() > 50 ? a.substring(0, 50) : a;
    }

    static String trim(String s, int max) {
        if (s == null) {
            return null;
        }
        String t = s.codePoints().filter(c -> !Character.isISOControl(c) || c == 0x1d)
                .collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append).toString();
        return t.length() > max ? t.substring(0, max) : t;
    }

    static BusinessException fieldError(String field, String code, Object... args) {
        return new BusinessException(HbErrorCode.INVALID_VALUE).withFieldErrors(FieldErrors.create().add(field, code, args).list());
    }
}
