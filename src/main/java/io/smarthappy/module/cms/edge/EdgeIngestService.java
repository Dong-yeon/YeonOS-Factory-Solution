package io.smarthappy.module.cms.edge;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.smarthappy.module.cms.EquipEventService;
import io.smarthappy.module.cms.edge.EdgeDtos.CmdRow;
import io.smarthappy.module.cms.edge.EdgeDtos.ConsRow;
import io.smarthappy.module.cms.edge.EdgeDtos.HeartbeatResult;
import io.smarthappy.module.cms.edge.EdgeDtos.ItemResult;
import io.smarthappy.module.cms.edge.EdgeDtos.LinkRef;
import io.smarthappy.module.cms.edge.EdgeDtos.MessagesResult;
import io.smarthappy.module.cms.edge.EdgeDtos.SessionRow;
import io.smarthappy.module.pop.TerminalPrincipal;
import io.smarthappy.platform.common.web.BusinessException;
import io.smarthappy.platform.common.web.HbErrorCode;
import io.smarthappy.platform.messaging.InboundJournal;
import io.smarthappy.platform.messaging.InboundJournal.Stored;
import io.smarthappy.platform.policy.PolicyService;
import io.smarthappy.platform.time.BizClock;
import lombok.RequiredArgsConstructor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * 엣지·어댑터가 보내는 것을 받는다 (docs/65 16.2·16.7) — 설치형(DIRECT 어댑터)·SaaS(EDGE) 가 같은 API.
 *
 * - 메시지: 원문을 저널에 저장만(처리는 비동기) → 건별 STORED·DUP·CONFLICT·INVALID. 충돌은 사건 + 알람. 과부하면 아무것도 저장하지 않고 429
 * - 하트비트: 저널을 거치지 않고 last_seen 만. 끊겼던 설비는 복귀(알람 해제·UNKNOWN 닫기), birth(bootId 바뀜)·복귀 때 실린 현재값을 snapshot 상태로 저널에
 * - 시계 차이: 봉투 sentAt 과 서버 시각 차이가 정책을 넘으면 사건 CLOCK_SKEW(장치당 10분에 한 번)
 */
@Service
@RequiredArgsConstructor
public class EdgeIngestService {

    static final Pattern BOOT_ID = Pattern.compile("^[A-Za-z0-9_.:-]{1,64}$");
    static final Pattern CONS_CD = Pattern.compile("^[A-Z][A-Z0-9_]{0,29}$");
    static final int MAX_EQUIPS = 500;
    static final Duration SKEW_LOG_EVERY = Duration.ofMinutes(10);

    private final InboundJournal journal;
    private final EdgeMapper mapper;
    private final EdgeEvents events;
    private final EquipEventService ledger;
    private final PolicyService policies;
    private final BizClock clock;

    /** 과부하 — 저장 전에 판단, 호출한 쪽이 429 + Retry-After */
    public static final class Overloaded extends RuntimeException {
        private final int retryAfterSec;

        Overloaded(int retryAfterSec) {
            super("overloaded", null, false, false);
            this.retryAfterSec = retryAfterSec;
        }

        public int retryAfterSec() {
            return retryAfterSec;
        }
    }

    // ------------------------------------------------------------------ 메시지

    @Transactional
    public MessagesResult messages(TerminalPrincipal d, List<JsonNode> items, Instant sentAt) {
        if (journal.overloaded()) {
            throw new Overloaded(journal.retryAfterSec());
        }
        checkSkew(d, sentAt);
        String actor = EdgeSupport.actor(d);
        int stored = 0;
        int dups = 0;
        int conflicts = 0;
        int invalid = 0;
        List<ItemResult> out = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            JsonNode it = items.get(i);
            String route = it != null && it.isObject() ? EdgeSupport.upper(EdgeSupport.text(it, "route", 30)) : null;
            try {
                Stored s = journal.store(route, d.terminalId(), actor, it, sentAt);
                switch (s.outcome()) {
                    case "STORED" -> stored++;
                    case "DUP" -> dups++;
                    default -> {
                        conflicts++;
                        conflict(d, route, it, s);
                    }
                }
                out.add(new ItemResult(i, s.outcome()));
            } catch (InboundJournal.Invalid e) {
                invalid++;
                out.add(new ItemResult(i, "INVALID:" + e.code()));
            }
        }
        return new MessagesResult(stored, dups, conflicts, invalid, out);
    }

    /** 같은 업무 키·다른 내용 — 사건 + 알람(설비를 알 수 있을 때). 처리는 하지 않는다 */
    private void conflict(TerminalPrincipal d, String route, JsonNode it, Stored s) {
        Long equipId = null;
        if (EquipStateRoute.ROUTE.equals(route)) {
            LinkRef l = mapper.findLink(EdgeSupport.upper(EdgeSupport.text(it, "equipCd", 40)));
            equipId = l == null ? null : l.getEquipId();
        } else if (CmdResultRoute.ROUTE.equals(route)) {
            try {
                CmdRow c = mapper.lockCmdByKey(UUID.fromString(EdgeSupport.text(it, "cmdKey", 40)));
                equipId = c == null ? null : c.getEquipId();
            } catch (RuntimeException ignored) {
                // 키 형식이 틀리면 설비 없이
            }
        }
        Instant now = clock.instant();
        String detail = route + " #" + s.conflictOf() + " ≠ #" + s.inboundId();
        events.log(equipId, d.terminalId(), "CONFLICT", null, null, detail, s.inboundId(), now);
        if (equipId != null) {
            events.raise(equipId, EdgeEvents.LINK_CONFLICT, detail, now, d.terminalId(), "in:" + s.inboundId());
        }
    }

    // ------------------------------------------------------------------ 하트비트

    @Transactional
    public HeartbeatResult heartbeat(TerminalPrincipal d, JsonNode root) {
        String bootId = EdgeSupport.text(root, "bootId", 64);
        if (bootId == null || !BOOT_ID.matcher(bootId).matches()) {
            throw new BusinessException(HbErrorCode.BAD_REQUEST);
        }
        Instant sentAt = sentAt(root);
        JsonNode equips = root.get("equips");
        if (equips != null && !equips.isNull() && (!equips.isArray() || equips.size() > MAX_EQUIPS)) {
            throw new BusinessException(HbErrorCode.BAD_REQUEST);
        }
        Instant now = clock.instant();
        boolean restart = session(d, bootId, sentAt, true);
        boolean birth = restart || Boolean.TRUE.equals(EdgeSupport.bool(root, "birth"));
        if (birth) {
            events.log(null, d.terminalId(), "BIRTH", null, null, "boot " + bootId, null, now);
        }
        Map<String, LinkRef> mine = new HashMap<>();
        for (LinkRef l : mapper.linksOfTerminal(d.terminalId())) {
            mine.put(l.getEquipCd(), l);
        }
        List<String> unknown = new ArrayList<>();
        if (equips != null && equips.isArray()) {
            for (JsonNode it : equips) {
                String cd = EdgeSupport.upper(EdgeSupport.text(it, "equipCd", 40));
                LinkRef link = cd == null ? null : mine.get(cd);
                if (link == null) {
                    if (cd != null && unknown.size() < 50) {
                        unknown.add(cd);                 // 남의 설비·다른 회사 설비·없는 설비 — 무엇도 갱신하지 않는다
                    }
                    continue;
                }
                if (Boolean.FALSE.equals(EdgeSupport.bool(it, "online"))) {
                    continue;                            // 엣지는 살아 있지만 그 장비와 끊김 — 판정 작업이 끊김으로
                }
                mapper.touchSeen(link.getEquipId(), now);
                boolean back = link.getLostAt() != null && mapper.clearLost(link.getEquipId()) == 1;
                if (back) {
                    ledger.closeUnknown(link.getEquipId(), now);
                    events.clear(link.getEquipId(), EdgeEvents.LINK_LOST, now, d.terminalId(), "hb:" + bootId);
                    events.log(link.getEquipId(), d.terminalId(), "BACK", null, null, "lost at " + link.getLostAt(), null, now);
                }
                consumables(d, link, it.get("consumables"), now);
                if ((birth || back) && it.hasNonNull("packState") && it.hasNonNull("seq")) {
                    snapshot(d, cd, it, sentAt);
                }
            }
        }
        return new HeartbeatResult(now.atOffset(ZoneOffset.UTC), birth, unknown);
    }

    /** birth·복귀 때 현재값 — 일반 상태 메시지와 같은 업무 키(equipCd + seq)로 저널에(이미 받은 seq 면 중복으로 끝난다) */
    private void snapshot(TerminalPrincipal d, String equipCd, JsonNode it, Instant sentAt) {
        ObjectNode n = JsonNodeFactory.instance.objectNode();
        n.put("route", EquipStateRoute.ROUTE);
        n.put("equipCd", equipCd);
        n.set("seq", it.get("seq"));
        n.set("packState", it.get("packState"));
        if (it.hasNonNull("mode")) {
            n.set("mode", it.get("mode"));
        }
        if (it.hasNonNull("occurredAt")) {
            n.set("occurredAt", it.get("occurredAt"));
        }
        n.put("snapshot", true);
        try {
            journal.store(EquipStateRoute.ROUTE, d.terminalId(), EdgeSupport.actor(d), n, sentAt);
        } catch (InboundJournal.Invalid ignored) {
            // 형식이 틀린 현재값은 버린다 — 하트비트는 살아 있음만 알린다
        }
    }

    /** 소모품 수준이 바뀔 때만 알람: LOW = 부족 경고, EMPTY = 없음, OK = 둘 다 해제 */
    private void consumables(TerminalPrincipal d, LinkRef link, JsonNode list, Instant now) {
        if (list == null || !list.isArray()) {
            return;
        }
        int n = 0;
        for (JsonNode c : list) {
            if (++n > 20) {
                break;
            }
            String cd = EdgeSupport.upper(EdgeSupport.text(c, "cd", 30));
            String level = EdgeSupport.upper(EdgeSupport.text(c, "level", 10));
            if (cd == null || !CONS_CD.matcher(cd).matches() || level == null || !List.of("OK", "LOW", "EMPTY").contains(level)) {
                continue;
            }
            ConsRow prev = mapper.lockCons(link.getEquipId(), cd);
            String before = prev == null ? "OK" : prev.getLevelCd();
            if (prev != null && before.equals(level)) {
                continue;
            }
            mapper.upsertCons(link.getEquipId(), cd, level);
            if (before.equals(level)) {
                continue;
            }
            String ref = "hb:" + d.terminalId();
            String low = "CONS_LOW." + cd;
            String empty = "CONS_EMPTY." + cd;
            switch (level) {
                case "LOW" -> {
                    if ("EMPTY".equals(before)) {
                        events.clear(link.getEquipId(), empty, now, d.terminalId(), ref);
                    }
                    events.raise(link.getEquipId(), low, cd + " LOW", now, d.terminalId(), ref);
                }
                case "EMPTY" -> {
                    events.clear(link.getEquipId(), low, now, d.terminalId(), ref);
                    events.raise(link.getEquipId(), empty, cd + " EMPTY", now, d.terminalId(), ref);
                }
                default -> {
                    events.clear(link.getEquipId(), low, now, d.terminalId(), ref);
                    events.clear(link.getEquipId(), empty, now, d.terminalId(), ref);
                }
            }
        }
    }

    // ------------------------------------------------------------------ 장치 세션·시계 차이

    /** 메시지 쪽 시계 차이(세션이 없으면 만든다 — 다음 하트비트가 진짜 bootId 로 birth) */
    private void checkSkew(TerminalPrincipal d, Instant sentAt) {
        if (sentAt == null) {
            return;
        }
        session(d, null, sentAt, false);
    }

    /**
     * 세션 잠금·갱신 + 시계 차이 사건. bootId null = 메시지(세션 bootId 를 바꾸지 않음).
     * @return bootId 가 바뀌었거나 처음이면 true(birth)
     */
    private boolean session(TerminalPrincipal d, String bootId, Instant sentAt, boolean heartbeat) {
        Instant now = clock.instant();
        Long skew = sentAt == null ? null : Duration.between(sentAt, now).toMillis();
        SessionRow s = mapper.lockSession(d.terminalId());
        boolean restart;
        if (s == null) {
            mapper.insertSession(d.terminalId(), bootId == null ? "-" : bootId, skew);
            s = mapper.lockSession(d.terminalId());
            restart = bootId != null;
        } else {
            restart = bootId != null && !bootId.equals(s.getBootId());
            if (heartbeat) {
                mapper.updateSession(d.terminalId(), bootId, restart, skew);
            }
        }
        long limitMs = policies.intValue("EDGE_CLOCK_SKEW_SEC") * 1000L;
        if (skew != null && Math.abs(skew) > limitMs && (s.getSkewLoggedAt() == null
                || s.getSkewLoggedAt().toInstant().plus(SKEW_LOG_EVERY).isBefore(now))) {
            events.log(null, d.terminalId(), "CLOCK_SKEW", null, null, "skew " + skew + " ms", null, now);
            mapper.markSkewLogged(d.terminalId());
        }
        return restart;
    }

    static Instant sentAt(JsonNode root) {
        String s = EdgeSupport.text(root, "sentAt", 40);
        if (s == null) {
            return null;
        }
        try {
            return OffsetDateTime.parse(s).toInstant();
        } catch (DateTimeParseException e) {
            throw new BusinessException(HbErrorCode.BAD_REQUEST);
        }
    }

    /** 화면용: 그 설비의 소모품 마지막 수준 */
    @Transactional(readOnly = true)
    public Map<String, String> consumableLevels(long equipId) {
        Map<String, String> m = new LinkedHashMap<>();
        mapper.consumables(equipId).forEach(c -> m.put(c.getConsCd(), c.getLevelCd()));
        return m;
    }
}
