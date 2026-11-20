package io.smarthappy.module.wms.asrs;

import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import io.smarthappy.module.pop.TerminalPrincipal;
import io.smarthappy.module.wms.asrs.AsrsDtos.AsrsRow;
import io.smarthappy.module.wms.asrs.AsrsDtos.CtrRow;
import io.smarthappy.module.wms.asrs.AsrsDtos.ExchangeIn;
import io.smarthappy.module.wms.asrs.AsrsDtos.ExchangeView;
import io.smarthappy.module.wms.asrs.AsrsDtos.LineRow;
import io.smarthappy.module.wms.asrs.AsrsDtos.ReqRow;
import io.smarthappy.module.wms.asrs.AsrsDtos.WesRequest;
import io.smarthappy.module.wms.scan.ScanKeyResolver;
import io.smarthappy.platform.common.web.BusinessException;
import io.smarthappy.platform.common.web.HbErrorCode;
import io.smarthappy.platform.messaging.InboundJournal;
import io.smarthappy.platform.policy.PolicyService;
import io.smarthappy.platform.tenant.TenantContext;
import io.smarthappy.platform.time.BizClock;
import tools.jackson.databind.JsonNode;

/**
 * 자동창고 장비 쪽 (docs/65 23장) — 엣지 요청 가져가기(EDGE), WES 콜백 저장(DIRECT — WES 형식을 우리 저널에), 경계 교환 판정, 오래 머문 요청.
 * 장치 키 → 그 자동창고 하나. 다른 자동창고·다른 회사 요청은 보이지 않는다.
 */
@Service
public class AsrsDeviceService {

    static final String POLICY_STALE = "WMS_ASRS_STALE_MIN";
    static final int CLAIM_MAX = 50;
    static final int CALLBACK_MAX = 500;

    private final AsrsMapper mapper;
    private final AsrsService asrs;
    private final InboundJournal journal;
    private final PolicyService policy;
    private final BizClock clock;

    public AsrsDeviceService(AsrsMapper mapper, AsrsService asrs, InboundJournal journal, PolicyService policy, BizClock clock) {
        this.mapper = mapper;
        this.asrs = asrs;
        this.journal = journal;
        this.policy = policy;
        this.clock = clock;
    }

    AsrsRow asrsOf(TerminalPrincipal d) {
        AsrsRow a = mapper.findByTerminal(d.terminalId());
        if (a == null) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        return a;
    }

    /** SaaS 엣지가 WES 로 넘길 요청을 가져간다(QUEUED → SENT) — 공장 안으로 밀 수 없으니 당긴다(3장) */
    @Transactional
    public List<WesRequest> claim(TerminalPrincipal d, Integer max) {
        AsrsRow a = asrsOf(d);
        if (!"EDGE".equals(a.getConnMode())) {
            throw new BusinessException(HbErrorCode.CONFLICT);
        }
        int n = Math.max(1, Math.min(CLAIM_MAX, max == null ? 10 : max));
        List<WesRequest> out = new ArrayList<>();
        for (long id : mapper.claimable(a.getAsrsId(), n)) {
            ReqRow r = mapper.findReq(id);
            out.add(asrs.wesRequest(r, a));
            mapper.setSendState(id, "SENT", null, actor());
            mapper.insertEvent(a.getAsrsId(), id, null, null, "CLAIMED", null, null, null, null, null, "EDGE",
                    clock.instant().atOffset(ZoneOffset.UTC), "AUTO", actor());
        }
        return out;
    }

    /**
     * 설치형 WES 콜백 — WES 형식(한 건 또는 {callbacks:[…]})을 그대로 수신 저널에(원문 먼저 저장 → 200, 처리는 비동기).
     * 형식이 깨진 한 건은 그 건만 INVALID. 엣지는 같은 경로를 /ext/v1/edge/messages {route: WES_CALLBACK} 로 보낸다.
     */
    @Transactional(noRollbackFor = InboundJournal.Invalid.class)
    public List<String> callback(TerminalPrincipal d, JsonNode body) {
        asrsOf(d);
        List<JsonNode> items = new ArrayList<>();
        if (body.has("callbacks") && body.get("callbacks").isArray()) {
            body.get("callbacks").forEach(items::add);
        } else {
            items.add(body);
        }
        if (items.isEmpty() || items.size() > CALLBACK_MAX) {
            throw new BusinessException(HbErrorCode.TOO_MANY_ROWS, items.size(), CALLBACK_MAX);
        }
        if (journal.overloaded()) {
            throw new Overloaded(journal.retryAfterSec());
        }
        List<String> out = new ArrayList<>();
        for (JsonNode it : items) {
            try {
                out.add(journal.store(WesCallbackRoute.ROUTE, d.terminalId(), "d" + d.terminalId() + ":" + d.terminalCd(), it, null).outcome());
            } catch (InboundJournal.Invalid e) {
                out.add("INVALID:" + e.code());
            }
        }
        return out;
    }

    /** 과부하 — 유일한 "다시 보내라"(429 + Retry-After) */
    public static final class Overloaded extends RuntimeException {
        private final int retryAfterSec;

        Overloaded(int s) {
            super(null, null, false, false);
            this.retryAfterSec = s;
        }

        public int retryAfterSec() {
            return retryAfterSec;
        }
    }

    /**
     * 컨베이어 경계 교환(8.3) — 투입 직전 스캔이 "요청한 그 용기"인가. GO / REJECT(미등록·불일치·요청 없음) / WAIT(격리 용기는 정지 대신 대기 아님 — REJECT).
     * 판정·사건만, 구동은 장비 몫.
     */
    @Transactional
    public ExchangeView exchange(TerminalPrincipal d, ExchangeIn in) {
        AsrsRow a = asrsOf(d);
        String dir = in == null || in.direction() == null ? "IN" : in.direction().strip().toUpperCase(Locale.ROOT);
        if (!"IN".equals(dir)) {
            throw AsrsService.fieldError("direction", "CODE");                // 출구 방향은 출고(결정 뒤)
        }
        String scanned = in == null || in.scanned() == null ? "" : in.scanned().strip();
        String ctrCd = ctrCode(scanned);
        if (ctrCd == null) {
            return record(a, null, null, "REJECT", "OTHER", scanned.isEmpty() ? "no-read" : "not a container");
        }
        CtrRow c = mapper.lockCtrByCd(ctrCd);
        if (c == null || c.getAsrsId() != a.getAsrsId()) {
            return record(a, null, null, "REJECT", "OTHER", "unregistered " + ctrCd);
        }
        if ("QUARANTINE".equals(c.getStatus())) {
            return record(a, c, null, "REJECT", c.getExcReason(), "quarantined");
        }
        LineRow l = mapper.pendingInLine(c.getCtrId());
        if (l == null) {
            return record(a, c, null, "REJECT", "OTHER", "no request");          // 요청 없는 용기는 넣지 않는다
        }
        return record(a, c, l, "GO", null, null);
    }

    private ExchangeView record(AsrsRow a, CtrRow c, LineRow l, String decision, String reason, String detail) {
        ReqRow r = l == null ? null : mapper.findReq(l.getReqId());
        mapper.insertEvent(a.getAsrsId(), r == null ? null : r.getReqId(), l == null ? null : l.getLineId(), c == null ? null : c.getCtrId(),
                "EXCHANGE", decision, reason, detail, null, null, a.getConnMode(), clock.instant().atOffset(ZoneOffset.UTC), "SCAN", actor());
        return new ExchangeView(decision, reason, c == null ? null : c.getCtrCd(), r == null ? null : r.getReqKey(),
                l == null ? null : l.getLineNo());
    }

    /** 스캔 글자 → 용기 코드: 내부 형식 CTR:{코드} 또는 맨 코드 */
    static String ctrCode(String scanned) {
        if (scanned == null || scanned.isEmpty() || scanned.length() > ScanKeyResolver.MAX_LEN) {
            return null;
        }
        String s = scanned.regionMatches(true, 0, "CTR:", 0, 4) ? scanned.substring(4) : scanned;
        s = s.strip().toUpperCase(Locale.ROOT);
        return s.matches("^[A-Z0-9][A-Z0-9._-]{0,29}$") ? s : null;
    }

    /** 정기 작업(회사 범위 트랜잭션 안) — 보낸 뒤 기준 시간 넘게 끝나지 않은 요청 = 경보 사건(한 번) */
    @Transactional(propagation = Propagation.MANDATORY)
    public int flagStale() {
        int n = 0;
        int minutes = policy.intValue(POLICY_STALE);
        for (long id : mapper.staleReqs(minutes)) {
            ReqRow r = mapper.findReq(id);
            mapper.insertEvent(r.getAsrsId(), id, null, null, "STALE_REQ", null, null, "over " + minutes + " min (" + r.getProgress() + ")",
                    null, null, "SYSTEM", clock.instant().atOffset(ZoneOffset.UTC), "AUTO", actor());
            n++;
        }
        return n;
    }

    private static String actor() {
        String a = TenantContext.currentActor();
        return a.length() > 50 ? a.substring(0, 50) : a;
    }
}
