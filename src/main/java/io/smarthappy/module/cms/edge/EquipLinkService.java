package io.smarthappy.module.cms.edge;

import java.math.BigDecimal;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import io.smarthappy.module.cms.EquipEventService;
import io.smarthappy.module.cms.edge.EdgeDtos.LinkForm;
import io.smarthappy.module.cms.edge.EdgeDtos.LinkRef;
import io.smarthappy.module.cms.edge.EdgeDtos.LinkRow;
import io.smarthappy.module.cms.edge.EdgeDtos.LogRow;
import io.smarthappy.module.cms.edge.EdgeDtos.TagForm;
import io.smarthappy.module.cms.edge.EdgeDtos.TagRow;
import io.smarthappy.module.cms.edge.EdgeDtos.TerminalOpt;
import io.smarthappy.module.wms.label.PrinterAddress;
import io.smarthappy.platform.common.web.BusinessException;
import io.smarthappy.platform.common.web.FieldErrors;
import io.smarthappy.platform.common.web.HbErrorCode;
import io.smarthappy.platform.messaging.InboundJournal;
import io.smarthappy.platform.policy.PolicyService;
import io.smarthappy.platform.tenant.TenantContext;
import io.smarthappy.platform.time.BizClock;
import lombok.extern.slf4j.Slf4j;

/**
 * 설비 연동 화면·끊김 판정 (docs/65 16.1·16.7) — 화면 cms-equip-link.
 * 끊김 판정은 {@link EquipLinkJudge} 한 곳(목록 표시와 정기 작업 SQL 이 같은 식).
 */
@Slf4j
@Service
public class EquipLinkService {

    public static final String PROG = "cms-equip-link";
    static final List<String> ROUTES = List.of(EquipStateRoute.ROUTE, CmdResultRoute.ROUTE);

    private final EdgeMapper mapper;
    private final EdgeEvents events;
    private final EquipEventService ledger;
    private final InboundJournal journal;
    private final PolicyService policies;
    private final BizClock clock;
    private final boolean allowLoopback;

    public EquipLinkService(EdgeMapper mapper, EdgeEvents events, EquipEventService ledger, InboundJournal journal, PolicyService policies,
            BizClock clock, @Value("${happyboot.cms.edge-allow-loopback:false}") boolean allowLoopback) {
        this.mapper = mapper;
        this.events = events;
        this.ledger = ledger;
        this.journal = journal;
        this.policies = policies;
        this.clock = clock;
        this.allowLoopback = allowLoopback;
    }

    /** 주입 전 기본값 = 끔(fail-closed), docs/67 F3 */
    private volatile io.smarthappy.platform.config.LanDirectGate lanDirect = io.smarthappy.platform.config.LanDirectGate.fixed(false);

    @org.springframework.beans.factory.annotation.Autowired
    void setLanDirect(io.smarthappy.platform.config.LanDirectGate lanDirect) {
        this.lanDirect = lanDirect;
    }

    // ------------------------------------------------------------------ 조회

    @Transactional(readOnly = true)
    public List<LinkRow> links(String plantCd, String keyword) {
        String k = keyword == null || keyword.isBlank() ? null : keyword.strip();
        if (k != null && k.length() > 100) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        List<LinkRow> rows = mapper.links(plantCd == null || plantCd.isBlank() ? null : plantCd, k == null ? null : EdgeSupport.like(k));
        int miss = policies.intValue("EQUIP_HB_MISS");
        Instant now = clock.instant();
        for (LinkRow r : rows) {
            String judge = r.getConnMode() == null ? EquipLinkJudge.OFF
                    : EquipLinkJudge.judge(r.getUseYn(), r.getLastSeenAt() == null ? null : r.getLastSeenAt().toInstant(), r.getHbSec(), miss, now);
            r.setJudge(judge);
            boolean known = EquipLinkJudge.ONLINE.equals(judge);
            String mode = r.getPackMode() == null ? "PRODUCTION" : r.getPackMode();
            // 연동 없음·꺼짐은 판정 대상이 아니다(빈 칸). 끊김·한 번도 못 봄 = 알 수 없음(NotAvailable), 분류 UNKNOWN 은 끊김만
            r.setMachineryState(EquipLinkJudge.OFF.equals(judge) ? null : PackMl.machineryItemState(known ? r.getPackState() : null, mode));
            r.setStateCat(EquipLinkJudge.LOST.equals(judge) ? "UNKNOWN"
                    : known && r.getPackState() != null ? PackMl.stateCat(r.getPackState(), mode) : null);
        }
        return rows;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> detail(long equipId) {
        LinkRef link = mapper.findLinkById(equipId);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("link", link);
        m.put("tags", link == null ? List.of() : mapper.tags(equipId));
        m.put("consumables", link == null ? List.of() : mapper.consumables(equipId));
        m.put("logs", mapper.logs(equipId, 50));
        return m;
    }

    @Transactional(readOnly = true)
    public List<TerminalOpt> collectors() {
        return mapper.collectors();
    }

    @Transactional(readOnly = true)
    public List<LogRow> logs(Long equipId, int limit) {
        return mapper.logs(equipId, Math.max(1, Math.min(limit, 500)));
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> inbound(String status, int limit) {
        return journal.list(ROUTES, status == null || status.isBlank() ? null : status, limit);
    }

    @Transactional
    public void requeue(long inboundId) {
        if (journal.requeue(inboundId, ROUTES) != 1) {
            throw new BusinessException(HbErrorCode.CONFLICT);
        }
    }

    // ------------------------------------------------------------------ 저장

    /** 연동 저장 — 없으면 만들고(rowVer null), 있으면 rowVer 로 고친다 */
    @Transactional
    public LinkRef save(long equipId, LinkForm f) {
        FieldErrors e = FieldErrors.create();
        String mode = f == null || f.connMode() == null ? null : f.connMode().strip();
        if (mode == null || mode.isEmpty()) {
            e.add("connMode", "REQUIRED");
        } else if (!List.of("DIRECT", "EDGE").contains(mode)) {
            e.add("connMode", "FORMAT");
        } else if ("DIRECT".equals(mode) && !lanDirect.enabled()) {
            e.add("connMode", "LAN_DIRECT_OFF");   // SaaS: 서버가 사설망 어댑터로 먼저 연결하지 않는다 — EDGE 로(docs/67 F3)
        }
        TerminalOpt t = null;
        String tcd = f == null || f.terminalCd() == null ? null : f.terminalCd().strip().toUpperCase(java.util.Locale.ROOT);
        if (tcd == null || tcd.isEmpty()) {
            e.add("terminalCd", "REQUIRED");
        } else if ((t = mapper.collector(tcd)) == null) {
            e.add("terminalCd", "NOT_FOUND");
        }
        String url = f == null || f.adapterUrl() == null || f.adapterUrl().isBlank() ? null : f.adapterUrl().strip();
        if (url != null) {
            if (!"DIRECT".equals(mode)) {
                e.add("adapterUrl", "EQUIP_URL_DIRECT_ONLY");
            } else {
                String p = urlProblem(url);
                if (p != null) {
                    e.add("adapterUrl", p);
                }
            }
        }
        int hb = f == null || f.hbSec() == null ? 5 : f.hbSec();
        if (hb < 1 || hb > 3600) {
            e.add("hbSec", "RANGE", 1, 3600);
        }
        String we = f == null || f.writeEnabledYn() == null ? "N" : f.writeEnabledYn();
        String use = f == null || f.useYn() == null ? "Y" : f.useYn();
        if (!List.of("Y", "N").contains(we)) {
            e.add("writeEnabledYn", "FORMAT");
        }
        if (!List.of("Y", "N").contains(use)) {
            e.add("useYn", "FORMAT");
        }
        String remark = f == null || f.remark() == null || f.remark().isBlank() ? null : f.remark().strip();
        if (remark != null && remark.length() > 500) {
            e.add("remark", "TOO_LONG", 500);
        }
        if (!e.isEmpty()) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE).withFieldErrors(e.list());
        }
        LinkForm clean = new LinkForm(mode, tcd, url, hb, we, use, remark, f.rowVer());
        LinkRef cur = mapper.findLinkById(equipId);
        String by = TenantContext.currentActor();
        if (cur == null) {
            if (f.rowVer() != null) {
                throw new BusinessException(HbErrorCode.CONFLICT);
            }
            try {
                mapper.insertLink(equipId, clean, t.getTerminalId(), by);
            } catch (DuplicateKeyException ex) {
                throw new BusinessException(HbErrorCode.CONFLICT);
            }
        } else if (f.rowVer() == null || mapper.updateLink(equipId, clean, t.getTerminalId(), by) != 1) {
            throw new BusinessException(HbErrorCode.CONFLICT);
        }
        return mapper.findLinkById(equipId);
    }

    /** 태그 저장 — tagId null 이면 추가. 코드·형식은 고정 */
    @Transactional
    public TagRow saveTag(long equipId, TagForm f) {
        if (mapper.findLinkById(equipId) == null) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        FieldErrors e = FieldErrors.create();
        String cd = f == null || f.tagCd() == null ? null : f.tagCd().strip().toUpperCase(java.util.Locale.ROOT);
        if (cd == null || cd.isEmpty()) {
            e.add("tagCd", "REQUIRED");
        } else if (!cd.matches("^[A-Z][A-Z0-9_.]{0,29}$")) {
            e.add("tagCd", "CODE");
        }
        String nm = f == null || f.tagNm() == null ? null : f.tagNm().strip();
        if (nm == null || nm.isEmpty()) {
            e.add("tagNm", "REQUIRED");
        } else if (nm.length() > 100) {
            e.add("tagNm", "TOO_LONG", 100);
        }
        String type = f == null ? null : f.dataType();
        if (!"NUM".equals(type) && !"BOOL".equals(type)) {
            e.add("dataType", "FORMAT");
        }
        BigDecimal min = null;
        BigDecimal max = null;
        if ("NUM".equals(type)) {
            min = num(f.minValue(), "minValue", e);
            max = num(f.maxValue(), "maxValue", e);
            if (min != null && max != null && min.compareTo(max) > 0) {
                e.add("maxValue", "INVALID_RANGE");
            }
        }
        String states = null;
        if (f != null && f.allowedStates() != null && !f.allowedStates().isEmpty()) {
            for (String s : f.allowedStates()) {
                if (PackMl.state(s) == null) {
                    e.add("allowedStates", "FORMAT");
                    break;
                }
            }
            states = String.join(",", f.allowedStates().stream().map(PackMl::state).filter(java.util.Objects::nonNull).distinct().toList());
        }
        String unit = f == null || f.unit() == null || f.unit().isBlank() ? null : f.unit().strip();
        if (unit != null && unit.length() > 20) {
            e.add("unit", "TOO_LONG", 20);
        }
        String writable = f == null || f.writableYn() == null ? "Y" : f.writableYn();
        String use = f == null || f.useYn() == null ? "Y" : f.useYn();
        if (!List.of("Y", "N").contains(writable) || !List.of("Y", "N").contains(use)) {
            e.add("useYn", "FORMAT");
        }
        if (!e.isEmpty()) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE).withFieldErrors(e.list());
        }
        String by = TenantContext.currentActor();
        TagForm clean = new TagForm(f.tagId(), cd, nm, type, null, null, unit, null, writable, use, f.rowVer());
        if (f.tagId() == null) {
            try {
                mapper.insertTag(equipId, clean, min, max, states, by);
            } catch (DuplicateKeyException ex) {
                throw new BusinessException(HbErrorCode.INVALID_VALUE).withFieldErrors(FieldErrors.create().add("tagCd", "DUPLICATE").list());
            }
        } else {
            TagRow cur = mapper.findTag(equipId, cd);
            if (cur == null || cur.getTagId() != f.tagId() || !cur.getDataType().equals(type) || f.rowVer() == null
                    || mapper.updateTag(clean, min, max, states, by) != 1) {
                throw new BusinessException(HbErrorCode.CONFLICT);
            }
        }
        return mapper.findTag(equipId, cd);
    }

    // ------------------------------------------------------------------ 끊김 판정(정기 작업)

    /**
     * 하트비트 끊김 판정 — 회사 범위 트랜잭션 안(TenantRunner). 기준 시간을 넘긴 설비마다: 알린 시각 기록(두 번 알리지 않음) + 사건 LOST +
     * 알람 LINK_LOST + 상태 원장 UNKNOWN 구간(기준 시각부터). @return 끊김으로 판정한 수
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public int judgeLost() {
        int miss = policies.intValue("EQUIP_HB_MISS");
        Instant now = clock.instant();
        int n = 0;
        for (LinkRef l : mapper.lostCandidates(now, miss)) {
            Instant seen = l.getLastSeenAt().toInstant();
            Instant deadline = EquipLinkJudge.deadline(seen, l.getHbSec(), miss);
            if (mapper.markLost(l.getEquipId(), now) != 1) {
                continue;
            }
            String detail = "last seen " + seen + ", " + l.getHbSec() + "s × " + miss;
            events.log(l.getEquipId(), l.getTerminalId(), "LOST", null, null, detail, null, deadline);
            events.raise(l.getEquipId(), EdgeEvents.LINK_LOST, detail, deadline, l.getTerminalId(), "hb:lost");
            ledger.openUnknown(l.getTerminalId(), TenantContext.currentActor(), l.getEquipId(), deadline, "hb:lost");
            n++;
        }
        return n;
    }

    // ------------------------------------------------------------------ 도우미

    /** 어댑터 주소 형식·사설 대역(저장 때 — DNS 없이). 보낼 때 다시 해석해 검사한다(EquipCmdHandler) */
    String urlProblem(String url) {
        if (url.length() > 200) {
            return "TOO_LONG";
        }
        try {
            URI u = new URI(url);
            if (!("http".equals(u.getScheme()) || "https".equals(u.getScheme())) || u.getHost() == null || u.getUserInfo() != null
                    || u.getQuery() != null || u.getFragment() != null) {
                return "FORMAT";
            }
            int port = u.getPort() > 0 ? u.getPort() : "https".equals(u.getScheme()) ? 443 : 80;
            String host = u.getHost().startsWith("[") ? u.getHost().substring(1, u.getHost().length() - 1) : u.getHost();
            PrinterAddress.Problem p = PrinterAddress.checkStatic(host, port, null, allowLoopback);
            return p == null ? null : p == PrinterAddress.Problem.NOT_PRIVATE ? "EQUIP_URL_PRIVATE" : "FORMAT";
        } catch (URISyntaxException ex) {
            return "FORMAT";
        }
    }

    private static BigDecimal num(String v, String field, FieldErrors e) {
        if (v == null || v.isBlank()) {
            e.add(field, "REQUIRED");
            return null;
        }
        String s = v.strip();
        if (!EdgeSupport.DECIMAL.matcher(s).matches()) {
            e.add(field, "FORMAT");
            return null;
        }
        return new BigDecimal(s);
    }
}
