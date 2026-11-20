package io.smarthappy.module.cms;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import io.smarthappy.module.cms.CollectorDtos.EquipRef;
import io.smarthappy.module.cms.CollectorDtos.IngestResult;
import io.smarthappy.module.cms.CollectorDtos.NewCount;
import io.smarthappy.module.cms.CollectorDtos.NewEnergy;
import io.smarthappy.module.cms.CollectorDtos.NewInterval;
import io.smarthappy.module.cms.CollectorDtos.OpenInterval;
import io.smarthappy.module.cms.CollectorDtos.ReasonRef;
import io.smarthappy.module.cms.CollectorDtos.Rejected;
import io.smarthappy.module.cms.CollectorDtos.RunningProc;
import io.smarthappy.module.cms.link.AlarmSink;
import io.smarthappy.module.master.workforce.WorkDayResolver;
import io.smarthappy.module.master.workforce.WorkDayResolver.WorkDay;
import io.smarthappy.module.pop.TerminalPrincipal;
import io.smarthappy.platform.common.db.PgErrors;
import io.smarthappy.platform.metrics.HbMetrics;
import io.smarthappy.platform.policy.PolicyService;
import io.smarthappy.platform.time.BizClock;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.JsonNode;

/**
 * 수집기 표준 이벤트 처리 (P2 6단계, docs/25 4.1·4.8). **묶음 요청 하나 = 트랜잭션 하나**(docs/70 W1·W3, 2026-11-16).
 *
 * - 장치 seq 행은 묶음 처음에 한 번 잡는다(같은 장치 묶음끼리 줄 선다) — 누락(GAP)·마지막 seq 도 그때 한 번에
 * - COUNT 가 아닌 이벤트는 건마다 저장점(NESTED) — 거부·DB 오류는 그 건만 되돌리고 나머지는 저장. 거부된 건은 멱등 기록도 없어 다시 보낼 수 있다
 * - COUNT 는 검증·설비 조회만 건마다, 멱등 기록·원장은 묶음 끝에 한 문장씩(unnest). 그 문장이 실패하면 묶음 전체를 되돌리고
 *   건마다 저장점 방식으로 한 번 더(조용히 빠지는 건 없음)
 * - 같은 eventId 는 cms_equip_inbox 로 한 번만(보존 클래스 EQUIP_INBOX 기간 뒤 정리 — 그보다 오래된 재전송은 다시 처리, docs/70 R2).
 *   같은 묶음 안 같은 eventId 는 앞의 것만(뒤는 중복)
 * - STATE: 열린 구간과 다르면 닫고 새로 연다. 열린 구간 시작보다 이른 이벤트는 과거를 다시 쓰지 않고 LATE
 * - CONN: OFFLINE 이면 UNKNOWN 구간(통신 끊김은 비가동이 아니다), ONLINE 이면 UNKNOWN 을 닫는다
 * - PARAM: 그 설비에서 진행 중인 지시 공정의 스냅샷 파라미터만 / METER_READING: 계측기 구간 사용량
 * - ALARM: CMS 계약 {@link AlarmSink} 로 발생·해제(docs/25 4.17) — 빈이 없으면 받기만
 * - COUNT: goodCount·scrapCount 증가분을 cms_equip_count 원장에(docs/25 4.27) — POP 실적 자동 제안의 근거
 */
@Slf4j
@Service
public class EquipEventService {

    static final String CONSUMER = "EQUIP_EVENT";
    static final Set<String> TYPES = Set.of("COUNT", "STATE", "ALARM", "PARAM", "CONN", "METER_READING");
    static final Set<String> CATS = Set.of("PRD", "SBY", "ENG", "SDT", "UDT", "NST");
    private static final Pattern EVENT_ID = Pattern.compile("^[A-Za-z0-9_.:-]{8,100}$");
    private static final Pattern CODE = Pattern.compile("^[A-Z0-9][A-Z0-9_.-]{0,29}$");
    private static final Pattern ALARM_CODE = Pattern.compile("^[A-Z0-9][A-Z0-9_.:-]{0,49}$");
    private static final int ALARM_TEXT_MAX = 200;
    private static final Pattern DECIMAL = Pattern.compile("^-?\\d{1,12}(\\.\\d{1,6})?$");

    private final CollectorMapper mapper;
    private final HbMetrics metrics;
    private final PolicyService policies;
    private final WorkDayResolver workDays;
    private final BizClock clock;
    private final ObjectProvider<AlarmSink> alarms;
    private final TransactionTemplate tx;
    private final TransactionTemplate nested;

    EquipEventService(CollectorMapper mapper, HbMetrics metrics, PolicyService policies, WorkDayResolver workDays, BizClock clock,
            ObjectProvider<AlarmSink> alarms, PlatformTransactionManager txm) {
        this.mapper = mapper;
        this.metrics = metrics;
        this.policies = policies;
        this.workDays = workDays;
        this.clock = clock;
        this.alarms = alarms;
        this.tx = new TransactionTemplate(txm);
        this.nested = new TransactionTemplate(txm);
        this.nested.setPropagationBehavior(TransactionDefinition.PROPAGATION_NESTED);
    }

    /** 거부 — 코드만 응답에 담고 그 이벤트(저장점)는 되돌림 */
    public static class Rejection extends RuntimeException {
        private final String code;

        Rejection(String code) {
            super(code, null, false, false);
            this.code = code;
        }

        public String code() {
            return code;
        }
    }

    /** 묶음 끝 한 문장 쓰기(또는 저장점 밖 조회)가 실패 — 묶음 전체를 되돌리고 건마다 저장점 방식으로 다시 */
    static final class BatchWriteFailed extends RuntimeException {
        BatchWriteFailed(Throwable cause) {
            super(cause.getMessage(), cause, false, false);
        }
    }

    /** 검증을 마친 이벤트 */
    private record Prepared(String eventId, String type, Instant at, EquipRef equip) {
    }

    /** 묶음 하나 동안의 공통 값 — 설비 조회는 같은 코드면 한 번 */
    private final class Scope {
        final TerminalPrincipal d;
        final String actor;
        final Instant futureLimit;
        final Map<String, Optional<EquipRef>> equips = new HashMap<>();

        Scope(TerminalPrincipal d) {
            this.d = d;
            this.actor = actor(d);
            this.futureLimit = clock.instant().plus(Duration.ofSeconds(policies.intValue("POP_FUTURE_TOLERANCE_SEC")));
        }

        EquipRef equip(String equipCd) {
            return equips.computeIfAbsent(equipCd, c -> Optional.ofNullable(mapper.findEquip(c, d.plantCd(), d.nodeId()))).orElse(null);
        }
    }

    /** 처리를 기다리는 COUNT 한 건(묶음 끝에 한꺼번에) */
    private record PendingCount(int index, String eventId, List<NewCount> rows) {
    }

    /**
     * 묶음 하나를 트랜잭션 하나로 (docs/70 W1). 묶음 끝 한 문장이 예상 밖으로 실패하면 한 번 더 — 건마다 저장점 방식으로.
     *
     * @return 건별 결과(받음·중복 수, 거부는 eventId·코드 — 보낸 순서)
     */
    public IngestResult ingest(TerminalPrincipal d, List<JsonNode> list) {
        try {
            return tx.execute(s -> batch(d, list, true));
        } catch (BatchWriteFailed e) {
            log.warn("설비 이벤트 묶음 쓰기 실패 — 건마다 다시 device={} events={}", d.terminalId(), list.size(), e.getCause());
            return tx.execute(s -> batch(d, list, false));
        }
    }

    /** @param defer true = COUNT 를 묶음 끝에 한 문장으로, false = 모든 건을 건마다 저장점 안에서 */
    private IngestResult batch(TerminalPrincipal d, List<JsonNode> list, boolean defer) {
        trackSeqs(d, list);
        Scope sc = new Scope(d);
        String[] outcome = new String[list.size()];       // null = 받음, "" = 중복, 그 밖 = 거부 코드
        Set<String> seen = new HashSet<>();               // 이 묶음에서 이미 받은(또는 받을) eventId
        List<PendingCount> pending = new ArrayList<>();
        for (int i = 0; i < list.size(); i++) {
            JsonNode ev = list.get(i);
            try {
                if (defer && "COUNT".equals(upper(text(ev, "type")))) {
                    Prepared p;
                    List<NewCount> rows;
                    try {
                        p = prepare(sc, ev);
                        rows = countRows(sc, ev, p);
                    } catch (DataAccessException e) {
                        throw new BatchWriteFailed(e);    // 저장점 밖 조회 실패 — 트랜잭션이 깨졌으니 묶음째 다시
                    }
                    if (!seen.add(p.eventId())) {
                        outcome[i] = "";
                    } else {
                        pending.add(new PendingCount(i, p.eventId(), rows));
                    }
                    continue;
                }
                Boolean first = nested.execute(s -> {
                    Prepared p = prepare(sc, ev);
                    if (seen.contains(p.eventId()) || mapper.claimEvent(p.eventId()) == 0) {
                        return false;
                    }
                    apply(sc, ev, p);
                    return true;
                });
                if (Boolean.TRUE.equals(first)) {
                    seen.add(text(ev, "eventId"));
                    outcome[i] = null;
                } else {
                    outcome[i] = "";
                }
            } catch (Rejection r) {
                outcome[i] = r.code();
            } catch (BatchWriteFailed e) {
                throw e;
            } catch (RuntimeException e) {
                // 예상 못 한 오류도 그 건만(저장점으로 되돌림) — 나머지는 계속(내용은 서버 로그로만)
                log.warn("설비 이벤트 처리 실패 device={} eventId={}", d.terminalId(), text(ev, "eventId"), e);
                outcome[i] = "ERROR";
            }
        }
        if (!pending.isEmpty()) {
            writeCounts(sc, pending, outcome);
        }
        int accepted = 0;
        int duplicates = 0;
        List<Rejected> rejected = new ArrayList<>();
        for (int i = 0; i < outcome.length; i++) {
            if (outcome[i] == null) {
                accepted++;
            } else if (outcome[i].isEmpty()) {
                duplicates++;
                metrics.inboxDuplicate(CONSUMER);
            } else {
                JsonNode ev = list.get(i);
                String id = ev != null && ev.isObject() && ev.get("eventId") != null ? ev.get("eventId").asText() : null;
                rejected.add(new Rejected(id, outcome[i]));
            }
        }
        return new IngestResult(accepted, duplicates, rejected);
    }

    /** 묶음 끝: 멱등 기록 한 문장 → 처음 받은 것만 원장 한 문장 (docs/70 W3) */
    private void writeCounts(Scope sc, List<PendingCount> pending, String[] outcome) {
        try {
            Set<String> fresh = new HashSet<>(mapper.claimEvents(pending.stream().map(PendingCount::eventId).toArray(String[]::new)));
            List<NewCount> rows = new ArrayList<>();
            for (PendingCount p : pending) {
                if (fresh.contains(p.eventId())) {
                    rows.addAll(p.rows());
                    outcome[p.index()] = null;
                } else {
                    outcome[p.index()] = "";
                }
            }
            if (!rows.isEmpty()) {
                int n = rows.size();
                String[] equipIds = new String[n];
                String[] metricCds = new String[n];
                String[] qtys = new String[n];
                String[] eventIds = new String[n];
                String[] occurred = new String[n];
                for (int i = 0; i < n; i++) {
                    NewCount r = rows.get(i);
                    equipIds[i] = Long.toString(r.equipId());
                    metricCds[i] = r.metricCd();
                    qtys[i] = r.qty().toPlainString();
                    eventIds[i] = r.eventId();
                    occurred[i] = r.occurredAt().toString();
                }
                int saved = mapper.insertCounts(equipIds, metricCds, qtys, eventIds, occurred, sc.d.terminalId(), sc.actor);
                if (saved != n) {
                    throw new IllegalStateException("cms_equip_count 묶음 쓰기 " + saved + "/" + n);
                }
            }
        } catch (RuntimeException e) {
            throw new BatchWriteFailed(e);
        }
    }

    /**
     * seq 누락 기록 — 묶음 처음에 한 번(장치 seq 행을 잡고 커밋까지 쥔다). 거부된 이벤트도 도착은 했으니 센다.
     * seq ≤ 마지막 = 재전송·순서 뒤바뀜(누락 아님), 마지막 + 1 보다 크면 그 사이를 GAP 으로
     */
    private void trackSeqs(TerminalPrincipal d, List<JsonNode> list) {
        int firstIdx = -1;
        for (int i = 0; i < list.size() && firstIdx < 0; i++) {
            if (seqOf(list.get(i)) != null) {
                firstIdx = i;
            }
        }
        if (firstIdx < 0) {
            return;
        }
        long last;
        int from;
        long firstSeq = seqOf(list.get(firstIdx));
        if (mapper.ensureSeq(d.terminalId(), firstSeq) == 1) {
            last = firstSeq;            // 이 장치의 첫 seq — 새 행(이 트랜잭션이 쥔다)
            from = firstIdx + 1;
        } else {
            last = mapper.lockSeq(d.terminalId());
            from = firstIdx;
        }
        boolean moved = false;
        for (int i = from; i < list.size(); i++) {
            Long seq = seqOf(list.get(i));
            if (seq == null || seq <= last) {
                continue;
            }
            if (seq > last + 1) {
                mapper.insertConn(d.terminalId(), null, "GAP", last + 1, seq - 1, text(list.get(i), "eventId"), clock.instant(), actor(d));
            }
            last = seq;
            moved = true;
        }
        if (moved) {
            mapper.updateSeq(d.terminalId(), last);
        }
    }

    private static Long seqOf(JsonNode ev) {
        JsonNode s = ev == null ? null : ev.get("seq");
        return s == null || !s.isIntegralNumber() || s.asLong() < 0 ? null : s.asLong();
    }

    /**
     * 이벤트 한 건 — 호출한 쪽의 트랜잭션 하나(데모 시더). 수집 API 는 {@link #ingest} 를 쓴다.
     *
     * @return true = 처리함, false = 이미 받은 이벤트(중복)
     */
    @Transactional
    public boolean handle(TerminalPrincipal d, JsonNode ev) {
        Scope sc = new Scope(d);
        Prepared p = prepare(sc, ev);
        if (mapper.claimEvent(p.eventId()) == 0) {
            metrics.inboxDuplicate(CONSUMER);
            return false;
        }
        apply(sc, ev, p);
        return true;
    }

    /** 형식·종류·시각·설비 검사 — 거부는 Rejection */
    private Prepared prepare(Scope sc, JsonNode ev) {
        if (ev == null || !ev.isObject()) {
            throw new Rejection("FORMAT");
        }
        String eventId = text(ev, "eventId");
        if (eventId == null || !EVENT_ID.matcher(eventId).matches()) {
            throw new Rejection("EVENT_ID");
        }
        String type = upper(text(ev, "type"));
        if (type == null || !TYPES.contains(type)) {
            throw new Rejection("TYPE");
        }
        Instant at = instant(text(ev, "occurredAt"), "OCCURRED_AT");
        if (at.isAfter(sc.futureLimit)) {
            throw new Rejection("FUTURE");
        }
        String equipCd = upper(text(ev, "equipCd"));
        EquipRef equip = equipCd == null ? null : sc.equip(equipCd);
        if (equip == null && !type.equals("METER_READING")) {
            throw new Rejection("EQUIP");
        }
        return new Prepared(eventId, type, at, equip);
    }

    /** 멱등 기록을 마친 이벤트를 종류별로 */
    private void apply(Scope sc, JsonNode ev, Prepared p) {
        TerminalPrincipal d = sc.d;
        switch (p.type()) {
            case "STATE" -> state(d, ev, p.equip(), p.at(), p.eventId());
            case "CONN" -> conn(d, ev, p.equip(), p.at(), p.eventId());
            case "PARAM" -> param(d, ev, p.equip(), p.at(), p.eventId());
            case "METER_READING" -> meter(d, ev, p.at(), p.eventId());
            case "ALARM" -> alarm(d, ev, p.equip(), p.at(), p.eventId());
            default -> countRows(sc, ev, p).forEach(r -> mapper.insertCount(r, sc.actor));
        }
    }

    // ------------------------------------------------------------------ STATE

    private void state(TerminalPrincipal d, JsonNode ev, EquipRef equip, Instant at, String eventId) {
        String cat = upper(metric(ev, "state"));
        if (cat == null || !CATS.contains(cat)) {
            throw new Rejection("STATE");
        }
        String reasonCd = upper(metric(ev, "reason"));
        Long reasonId = null;
        if (reasonCd != null) {
            ReasonRef r = mapper.reason(reasonCd);
            if (r == null || !r.getStateCat().equals(cat)) {
                throw new Rejection("REASON");
            }
            reasonId = r.getReasonId();
        }
        mapper.lockEquip(equip.getEquipId());
        OpenInterval open = mapper.lockOpen(equip.getEquipId());
        if (open != null && open.getStateCat().equals(cat) && Objects.equals(open.getReasonId(), reasonId)) {
            if (at.isBefore(open.getStartAt().toInstant())) {
                throw new Rejection("LATE");
            }
            return;                 // 같은 상태 신호 — 구간 그대로
        }
        startInterval(d.terminalId(), actor(d), equip.getEquipId(), open, cat, reasonId, null, at, eventId);
    }

    /**
     * 열린 구간을 at 에 닫고 새 구간을 연다. at 이 열린 구간 시작 이하이거나 마지막 닫힌 끝보다 이르면 LATE.
     * packState = PackML 상태(엣지 경로, docs/65 16.6) — 수집기 경로는 null
     */
    private void startInterval(long terminalId, String actor, long equipId, OpenInterval open, String cat, Long reasonId, String packState,
            Instant at, String eventId) {
        if (open != null) {
            if (!at.isAfter(open.getStartAt().toInstant())) {
                throw new Rejection("LATE");
            }
            mapper.close(open.getStateLogId(), at);
        } else {
            OffsetDateTime last = mapper.lastEnd(equipId);
            if (last != null && at.isBefore(last.toInstant())) {
                throw new Rejection("LATE");
            }
        }
        RunningProc run = mapper.runningProc(equipId);
        try {
            mapper.insertInterval(new NewInterval(equipId, cat, reasonId, at, run == null ? null : run.getWoId(),
                    terminalId, eventId, at, packState), actor);
        } catch (DataIntegrityViolationException e) {
            if ("ex_cms_equip_state_log_overlap".equals(PgErrors.constraint(e).orElse(""))) {
                throw new Rejection("OVERLAP");
            }
            throw e;
        }
    }

    // ------------------------------------------------------------------ CONN

    private void conn(TerminalPrincipal d, JsonNode ev, EquipRef equip, Instant at, String eventId) {
        String online = metric(ev, "online");
        if (online == null || !(online.equalsIgnoreCase("true") || online.equalsIgnoreCase("false"))) {
            throw new Rejection("CONN");
        }
        boolean on = Boolean.parseBoolean(online.toLowerCase(Locale.ROOT));
        mapper.insertConn(d.terminalId(), equip.getEquipId(), on ? "ONLINE" : "OFFLINE", null, null, eventId, at, actor(d));
        mapper.lockEquip(equip.getEquipId());
        OpenInterval open = mapper.lockOpen(equip.getEquipId());
        if (on) {
            // 다시 연결: 끊김(UNKNOWN) 구간만 닫는다 — 다음 STATE 가 새 구간
            if (open != null && "UNKNOWN".equals(open.getStateCat()) && at.isAfter(open.getStartAt().toInstant())) {
                mapper.close(open.getStateLogId(), at);
            }
            return;
        }
        if (open != null && "UNKNOWN".equals(open.getStateCat())) {
            return;
        }
        startInterval(d.terminalId(), actor(d), equip.getEquipId(), open, "UNKNOWN", null, null, at, eventId);
    }

    // ------------------------------------------------------------------ 엣지 경로(docs/65 16.6·16.7)

    /**
     * PackML 상태 한 건을 같은 상태 원장에 — 분류(stateCat)는 호출한 쪽이 대응표로 정한 값. 같은 트랜잭션(MANDATORY).
     * 열린 구간과 분류·PackML 상태가 같으면 그대로.
     *
     * @return APPLIED(새 구간) / SAME(그대로) / LATE(열린 구간·마지막 끝보다 이른 옛 상태 — 되돌리지 않음)
     */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    public String applyPackState(long terminalId, String actor, long equipId, String stateCat, String packState, Instant at, String eventId,
            boolean snapshot) {
        mapper.lockEquip(equipId);
        OpenInterval open = mapper.lockOpen(equipId);
        if (snapshot && open == null) {
            // 재연결 스냅샷: 끊김(UNKNOWN) 구간이 닫힌 시각보다 이른 현재값은 그 시각부터 — 끊긴 동안을 다시 쓰지 않고 수렴
            OffsetDateTime last = mapper.lastEnd(equipId);
            if (last != null && at.isBefore(last.toInstant())) {
                at = last.toInstant();
            }
        }
        if (open != null && open.getStateCat().equals(stateCat) && open.getReasonId() == null
                && Objects.equals(open.getPackState(), packState)) {
            return at.isBefore(open.getStartAt().toInstant()) ? "LATE" : "SAME";
        }
        try {
            startInterval(terminalId, actor, equipId, open, stateCat, null, packState, at, eventId);
            return "APPLIED";
        } catch (Rejection r) {
            if ("LATE".equals(r.code()) || "OVERLAP".equals(r.code())) {
                return "LATE";
            }
            throw r;
        }
    }

    /** 하트비트 끊김 — UNKNOWN 구간을 at 부터(이미 UNKNOWN 이거나 at 이 열린 구간보다 이르면 그대로). @return 열었으면 true */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    public boolean openUnknown(long terminalId, String actor, long equipId, Instant at, String eventId) {
        mapper.lockEquip(equipId);
        OpenInterval open = mapper.lockOpen(equipId);
        if (open != null && "UNKNOWN".equals(open.getStateCat())) {
            return false;
        }
        try {
            startInterval(terminalId, actor, equipId, open, "UNKNOWN", null, null, at, eventId);
            return true;
        } catch (Rejection r) {
            return false;
        }
    }

    /** 다시 연결 — 열린 UNKNOWN 구간만 at 에 닫는다(다음 상태가 새 구간). @return 닫았으면 true */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    public boolean closeUnknown(long equipId, Instant at) {
        mapper.lockEquip(equipId);
        OpenInterval open = mapper.lockOpen(equipId);
        if (open != null && "UNKNOWN".equals(open.getStateCat()) && at.isAfter(open.getStartAt().toInstant())) {
            mapper.close(open.getStateLogId(), at);
            return true;
        }
        return false;
    }

    // ------------------------------------------------------------------ PARAM · METER_READING

    private void param(TerminalPrincipal d, JsonNode ev, EquipRef equip, Instant at, String eventId) {
        RunningProc run = mapper.runningProc(equip.getEquipId());
        if (run == null) {
            throw new Rejection("NO_RUNNING_PROC");
        }
        WorkDay wd = workDays.resolve(equip.getPlantCd(), at);
        int saved = 0;
        for (JsonNode m : metrics(ev)) {
            String name = upper(text(m, "name"));
            BigDecimal v = decimal(m.get("value"));
            if (name == null || v == null) {
                throw new Rejection("METRIC");
            }
            saved += mapper.insertParamAuto(run.getWoProcId(), name, v, equip.getEquipId(), d.terminalId(), reqId(eventId, name), at,
                    wd.workDay(), wd.shiftCd(), actor(d));
        }
        if (saved == 0) {
            throw new Rejection("NO_PARAM");
        }
    }

    private void meter(TerminalPrincipal d, JsonNode ev, Instant at, String eventId) {
        Instant from = instant(text(ev, "periodStart"), "PERIOD");
        if (!at.isAfter(from)) {
            throw new Rejection("PERIOD");
        }
        int n = 0;
        for (JsonNode m : metrics(ev)) {
            String meterCd = upper(text(m, "name"));
            BigDecimal v = decimal(m.get("value"));
            String unit = upper(text(m, "unit"));
            if (meterCd == null || !CODE.matcher(meterCd).matches() || v == null || v.signum() <= 0 || unit == null || unit.length() > 30) {
                throw new Rejection("METRIC");
            }
            try {
                mapper.insertEnergy(new NewEnergy(d.plantCd(), meterCd, from, at, v, unit, d.terminalId(), eventId, at), actor(d));
            } catch (DataIntegrityViolationException e) {
                if ("ex_cms_energy_reading_overlap".equals(PgErrors.constraint(e).orElse(""))) {
                    throw new Rejection("OVERLAP");
                }
                throw e;
            }
            n++;
        }
        if (n == 0) {
            throw new Rejection("METRIC");
        }
    }

    // ------------------------------------------------------------------ COUNT

    /**
     * goodCount·scrapCount = 이 이벤트 동안 늘어난 수(0 이상). 이름 count 는 goodCount 와 같다(카운터 하나뿐인 설비·가짜 수집기 — 6단계부터 보내던 이름).
     * 셋 다 없으면·음수·형식 오류는 METRIC. 다른 이름 메트릭은 무시. 쓰지 않고 원장 행만 돌려준다
     */
    private List<NewCount> countRows(Scope sc, JsonNode ev, Prepared p) {
        List<NewCount> rows = new ArrayList<>(2);
        for (JsonNode m : metrics(ev)) {
            String name = text(m, "name");
            String metric = "goodCount".equalsIgnoreCase(name) || "count".equalsIgnoreCase(name) ? "GOOD"
                    : "scrapCount".equalsIgnoreCase(name) ? "SCRAP" : null;
            if (metric == null) {
                continue;
            }
            BigDecimal v = decimal(m.get("value"));
            if (v == null || v.signum() < 0) {
                throw new Rejection("METRIC");
            }
            rows.add(new NewCount(p.equip().getEquipId(), metric, v, sc.d.terminalId(), p.eventId(), p.at()));
        }
        if (rows.isEmpty()) {
            throw new Rejection("METRIC");
        }
        return rows;
    }

    // ------------------------------------------------------------------ ALARM

    /** code(필수, 대문자 1~50자)·active(true 발생 / false 해제, 생략 = 발생)·text(200자에서 자름) — 거르기는 CMS 몫 */
    private void alarm(TerminalPrincipal d, JsonNode ev, EquipRef equip, Instant at, String eventId) {
        String raw = metric(ev, "code");
        String code = raw == null ? null : upper(raw.strip());
        if (code == null || !ALARM_CODE.matcher(code).matches()) {
            throw new Rejection("ALARM_CODE");
        }
        String active = metric(ev, "active");
        if (active != null && !(active.equalsIgnoreCase("true") || active.equalsIgnoreCase("false"))) {
            throw new Rejection("ALARM_ACTIVE");
        }
        AlarmSink sink = AlarmSink.of(alarms);
        if (active != null && active.equalsIgnoreCase("false")) {
            sink.clear(equip.getEquipId(), code, at, d.terminalId(), eventId);
            return;
        }
        String t = metric(ev, "text");
        t = t == null || t.isBlank() ? null : t.strip();
        if (t != null && t.length() > ALARM_TEXT_MAX) {
            t = t.substring(0, ALARM_TEXT_MAX);
        }
        sink.raise(equip.getEquipId(), code, t, at, d.terminalId(), eventId);
    }

    // ------------------------------------------------------------------ 도우미

    private static String actor(TerminalPrincipal d) {
        return "d" + d.terminalId() + ":" + d.terminalCd();
    }

    private static Iterable<JsonNode> metrics(JsonNode ev) {
        JsonNode m = ev.get("metrics");
        if (m == null || !m.isArray() || m.size() == 0 || m.size() > 100) {
            throw new Rejection("METRIC");
        }
        return m;
    }

    /** 메트릭 이름 → 값(글자). 없으면 null */
    private static String metric(JsonNode ev, String name) {
        JsonNode m = ev.get("metrics");
        if (m == null || !m.isArray()) {
            return null;
        }
        for (JsonNode x : m) {
            if (name.equalsIgnoreCase(text(x, "name"))) {
                JsonNode v = x.get("value");
                return v == null || v.isNull() ? null : v.isTextual() ? v.asText() : v.toString();
            }
        }
        return null;
    }

    private static String text(JsonNode n, String field) {
        JsonNode v = n == null ? null : n.get(field);
        if (v == null || v.isNull() || !v.isValueNode()) {
            return null;
        }
        String s = v.asText().strip();
        return s.isEmpty() || s.length() > 200 ? null : s;
    }

    private static String upper(String s) {
        return s == null ? null : s.toUpperCase(Locale.ROOT);
    }

    private static Instant instant(String raw, String code) {
        if (raw == null) {
            throw new Rejection(code);
        }
        try {
            return OffsetDateTime.parse(raw).toInstant();
        } catch (DateTimeParseException e) {
            throw new Rejection(code);
        }
    }

    /** 숫자 메트릭 — BigDecimal 로 읽은 값(Double 거치지 않음), 정수부 12·소수 6자리 */
    private static BigDecimal decimal(JsonNode v) {
        if (v == null || !(v.isNumber() || v.isTextual())) {
            return null;
        }
        String s = v.isNumber() ? v.decimalValue().stripTrailingZeros().toPlainString() : v.asText().strip();
        return DECIMAL.matcher(s).matches() ? new BigDecimal(s) : null;
    }

    /** 실측 요청 id(prd_param_actual 형식) — 같은 이벤트·메트릭이면 같은 값 */
    static String reqId(String eventId, String name) {
        try {
            byte[] h = MessageDigest.getInstance("SHA-256").digest((eventId + "|" + name).getBytes(StandardCharsets.UTF_8));
            return "c" + HexFormat.of().formatHex(h).substring(0, 40);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
