package io.smarthappy.module.srm.ack;

import static io.smarthappy.module.srm.SrmChecks.blank;
import static io.smarthappy.module.srm.SrmChecks.code;
import static io.smarthappy.module.srm.SrmChecks.date;
import static io.smarthappy.module.srm.SrmChecks.decimal;
import static io.smarthappy.module.srm.SrmChecks.me;
import static io.smarthappy.module.srm.SrmChecks.text;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.smarthappy.module.srm.SrmChecks;
import io.smarthappy.module.srm.ack.AckDtos.AckCheck;
import io.smarthappy.module.srm.ack.AckDtos.AckCrit;
import io.smarthappy.module.srm.ack.AckDtos.AckDetail;
import io.smarthappy.module.srm.ack.AckDtos.AckForm;
import io.smarthappy.module.srm.ack.AckDtos.AckLineForm;
import io.smarthappy.module.srm.ack.AckDtos.AckLineValue;
import io.smarthappy.module.srm.ack.AckDtos.AckPage;
import io.smarthappy.module.srm.ack.AckDtos.AckPoLine;
import io.smarthappy.module.srm.ack.AckDtos.AckPoRow;
import io.smarthappy.module.srm.ack.AckDtos.AckResult;
import io.smarthappy.module.srm.ack.AckDtos.AckSort;
import io.smarthappy.module.srm.ack.AckDtos.StatusCrit;
import io.smarthappy.module.srm.ack.AckDtos.StatusPage;
import io.smarthappy.module.srm.ack.AckDtos.StatusRow;
import io.smarthappy.module.srm.ack.AckDtos.StatusSort;
import io.smarthappy.module.srm.ack.AckDtos.Warning;
import io.smarthappy.module.srm.po.PoDtos.PoRow;
import io.smarthappy.module.srm.po.PoMapper;
import io.smarthappy.platform.common.query.DateRanges;
import io.smarthappy.platform.common.query.PageQuery;
import io.smarthappy.platform.common.web.BusinessException;
import io.smarthappy.platform.common.web.FieldErrors;
import io.smarthappy.platform.common.web.HbErrorCode;
import io.smarthappy.platform.grid.ColumnSpec;
import io.smarthappy.platform.grid.ColumnSpec.ColumnType;
import io.smarthappy.platform.messaging.InboxService;
import io.smarthappy.platform.policy.Policies;
import io.smarthappy.platform.policy.PolicyService;
import io.smarthappy.platform.security.LoginUser;
import io.smarthappy.platform.time.BizClock;
import lombok.RequiredArgsConstructor;

/**
 * 납기 회신 (srm-ack) · 발주 진행 현황 (srm-po-status) — SRM S5, docs/31 4.5.
 * 회신 = 묶음(발주 × 개정 × 순번) + 행(분할 납기). 추가만, 재회신 = 새 순번, 유효 = 지금 개정의 가장 큰 순번.
 * 발주 머리 FOR UPDATE → 상태·개정 확인 → 행 검사(Σ 약속 ≤ 열린 수량) → 묶음·행 INSERT → 상태 계산(같은 트랜잭션) → ISSUED ↔ ACKED.
 * ERP 가 발주의 주인이어도(SRM_PO_SOURCE=ERP) 회신은 여기서 한다(D1).
 */
@Service
@RequiredArgsConstructor
public class AckService {

    public static final String PROG = "srm-ack";
    public static final String STATUS_PROG = "srm-po-status";
    static final int MAX_LINES = 1000;
    static final int SEARCH_MAX_DAYS = 366;
    static final int QTY_INT_DIGITS = 12;
    static final int SOON_DAYS_DEFAULT = 7;
    static final Set<String> STATES = Set.of("OPEN", "NO_ACK", "ACKED", "ALL");
    static final Set<String> VIEWS = Set.of("ALL", "NO_ACK", "LATE", "DUE_SOON");

    private final AckMapper mapper;
    private final PoMapper pos;
    private final InboxService inbox;
    private final PolicyService policies;
    private final DateRanges ranges;
    private final BizClock clock;

    // ------------------------------------------------------------------ 조회

    @Transactional(readOnly = true)
    public List<AckPoRow> list(String from, String to, String state, String keyword) {
        List<AckPoRow> rows = mapper.findPos(criteria(from, to, state, keyword));
        LocalDate today = clock.today();
        rows.forEach(r -> r.setOverdue(overdue(r, today)));
        return rows;
    }

    /**
     * grid(UI 단계 3 ①, docs/31 5.2) — 목록과 같은 조건 + 서버 페이징·정렬(허용 목록 {@link AckSort}, 최대 3개, 모르는 열 400).
     * 기간 필수·상한도 목록과 같다(행 수 상한 대신 페이지)
     */
    @Transactional(readOnly = true)
    public AckPage grid(String from, String to, String state, String keyword, Integer page, Integer size, List<String> sort,
            Boolean total) {
        AckCrit c = criteria(from, to, state, keyword);
        PageQuery pq = PageQuery.parse(page, size, sort, total, AckSort.values(), policies.intValue(Policies.GRID_PAGE_SIZE_MAX),
                List.of("poDate,desc"));
        List<AckPoRow> rows = mapper.findPosPage(c, pq);
        LocalDate today = clock.today();
        rows.forEach(r -> r.setOverdue(overdue(r, today)));
        return new AckPage(rows, pq.isWithTotal() ? mapper.countPos(c) : null);
    }

    /** grid 열(화면 목록과 같은 칸, 개정·회신 순번을 따로) — 정렬 가능 = {@link AckSort} 에 있는 열 */
    static final List<ColumnSpec> GRID_COLUMNS = List.of(
            ColumnSpec.text("poNo", "srm.ack.col.poNo", 120).withSort().withPriority(1),
            ColumnSpec.of("rev", "srm.ack.col.rev", ColumnType.INT, 50).withPriority(3),
            ColumnSpec.of("poDate", "srm.ack.col.poDate", ColumnType.DATE, 100).withSort().withPriority(3),
            ColumnSpec.text("custNm", "srm.ack.col.supplier", 140).withSort(),
            ColumnSpec.of("issuedAt", "srm.ack.col.issued", ColumnType.DATETIME, 130).withSort().withPriority(3),
            ColumnSpec.of("ackDueDate", "srm.ack.col.due", ColumnType.DATE, 100).withSort(),
            ColumnSpec.code("ackStatus", "srm.ack.col.ack", "PO_ACK_STATUS", 90).withSort().withPriority(1),
            ColumnSpec.of("ackSeq", "srm.ack.col.ackSeq", ColumnType.INT, 60).withPriority(3),
            ColumnSpec.code("status", "srm.ack.col.status", "PO_STATUS", 100).withSort());

    public List<ColumnSpec> gridColumns() {
        return GRID_COLUMNS;
    }

    /** 목록·grid 공통 조건 검증 */
    private AckCrit criteria(String from, String to, String state, String keyword) {
        FieldErrors e = FieldErrors.create();
        LocalDate f = date(from, "from", false, e);
        LocalDate t = date(to, "to", false, e);
        e.throwIfAny();
        ranges.check(f, t, SEARCH_MAX_DAYS);
        String st = blank(state) ? "OPEN" : code(state);
        if (!STATES.contains(st) || keyword != null && keyword.length() > 100) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        return new AckCrit(f, t, st, like(keyword), clock.zone().getId(), dueDays());
    }

    @Transactional(readOnly = true)
    public AckDetail get(long poId) {
        AckPoRow po = mapper.findPo(poId, clock.zone().getId(), dueDays());
        if (po == null || po.getIssuedAt() == null) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);    // 발행한 적 없는 발주는 협력사가 모른다
        }
        po.setOverdue(overdue(po, clock.today()));
        return new AckDetail(po, mapper.findPoLines(poId), mapper.findAcks(poId), mapper.findAckLines(poId));
    }

    private static boolean overdue(AckPoRow r, LocalDate today) {
        boolean open = "ISSUED".equals(r.getStatus()) || "ACKED".equals(r.getStatus());
        boolean noAck = r.getAckStatus() == null || "REJECTED".equals(r.getAckStatus());
        return open && noAck && r.getAckDueDate() != null && today.isAfter(r.getAckDueDate());
    }

    private int dueDays() {
        return policies.intValue(Policies.SRM_ACK_DUE_DAYS);
    }

    private static String like(String keyword) {
        return blank(keyword) ? null : "%" + SrmChecks.like(keyword.strip()) + "%";
    }

    /** 발주 진행 현황 — 열린 발주의 열린 행: 회신 안 옴(기한 지남·거절) / 늦어질(약속 > 요구 납기) / 납기 임박 */
    @Transactional(readOnly = true)
    public List<StatusRow> status(String from, String to, String view, String custCd, String keyword, String soonDays) {
        return mapper.findStatus(statusCriteria(from, to, view, custCd, keyword, soonDays));
    }

    /**
     * 진행 현황 grid(UI 단계 3 ①, docs/31 5.2) — 목록과 같은 조건(보기·임박 일수 포함) + 서버 페이징·정렬(허용 목록 {@link StatusSort}).
     * 기본 정렬 = 목록과 같은 요구 납기·발주 번호·행 순서
     */
    @Transactional(readOnly = true)
    public StatusPage statusGrid(String from, String to, String view, String custCd, String keyword, String soonDays, Integer page,
            Integer size, List<String> sort, Boolean total) {
        StatusCrit c = statusCriteria(from, to, view, custCd, keyword, soonDays);
        PageQuery pq = PageQuery.parse(page, size, sort, total, StatusSort.values(), policies.intValue(Policies.GRID_PAGE_SIZE_MAX),
                List.of("dueDate,asc", "poNo,asc", "lineNo,asc"));
        return new StatusPage(mapper.findStatusPage(c, pq), pq.isWithTotal() ? mapper.countStatus(c) : null);
    }

    /** 진행 현황 grid 열(화면 목록 칸을 나눠서 — 개정·행·품목·열린·입고·약속 첫/끝·늦은 일수·남은 일수) */
    static final List<ColumnSpec> STATUS_COLUMNS = List.of(
            ColumnSpec.text("poNo", "srm.status.col.po", 120).withSort().withPriority(1),
            ColumnSpec.of("rev", "srm.ack.col.rev", ColumnType.INT, 50).withPriority(3),
            ColumnSpec.text("custNm", "srm.status.col.supplier", 120).withSort(),
            ColumnSpec.of("lineNo", "srm.status.col.lineNo", ColumnType.INT, 50).withSort().withPriority(3),
            ColumnSpec.text("itemCd", "srm.status.col.itemCd", 100).withSort(),
            ColumnSpec.text("itemNm", "srm.status.col.itemNm", 130).withPriority(3),
            ColumnSpec.dec("openQty", "srm.status.col.openQty", 4, 80).withMinScale(0),
            ColumnSpec.text("unitCd", "srm.status.col.unit", 50).withPriority(3),
            ColumnSpec.dec("recvQty", "srm.status.col.recvQty", 4, 80).withMinScale(0).withPriority(3),
            ColumnSpec.of("dueDate", "srm.status.col.dueDate", ColumnType.DATE, 125).withSort().withPriority(1),
            ColumnSpec.code("ackStatus", "srm.status.col.ack", "PO_ACK_STATUS", 110).withSort(),
            ColumnSpec.of("ackDueDate", "srm.status.col.ackDue", ColumnType.DATE, 100).withPriority(3),
            ColumnSpec.dec("promisedQty", "srm.status.col.promisedQty", 4, 80).withMinScale(0).withPriority(3),
            ColumnSpec.of("promisedLast", "srm.status.col.promisedLast", ColumnType.DATE, 125).withSort(),
            ColumnSpec.of("lateDays", "srm.status.col.lateDays", ColumnType.INT, 70).withSort().withPriority(3),
            ColumnSpec.of("dueInDays", "srm.status.col.dueInDays", ColumnType.INT, 70).withPriority(3));

    public List<ColumnSpec> statusColumns() {
        return STATUS_COLUMNS;
    }

    private StatusCrit statusCriteria(String from, String to, String view, String custCd, String keyword, String soonDays) {
        FieldErrors e = FieldErrors.create();
        LocalDate f = date(from, "from", false, e);
        LocalDate t = date(to, "to", false, e);
        int soon = SOON_DAYS_DEFAULT;
        if (!blank(soonDays)) {
            BigDecimal d = decimal(soonDays, "soonDays", 0, 2, false, e);
            if (d != null && (d.intValue() < 0 || d.intValue() > 60)) {
                e.add("soonDays", "RANGE", 0, 60);
            } else if (d != null) {
                soon = d.intValue();
            }
        }
        e.throwIfAny();
        ranges.check(f, t, SEARCH_MAX_DAYS);
        String v = blank(view) ? "ALL" : code(view);
        if (!VIEWS.contains(v) || keyword != null && keyword.length() > 100 || custCd != null && custCd.length() > 30) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        return new StatusCrit(f, t, v, code(custCd), like(keyword), clock.today(), clock.zone().getId(), dueDays(), soon);
    }

    // ------------------------------------------------------------------ 회신 입력(대리)

    @Transactional
    public AckResult record(long poId, AckForm in) {
        return record(poId, in, "MANUAL");
    }

    /**
     * 협력사 포털 회신(docs/42 7장 3, 출처 PORTAL) — 검사·상태 계산은 대리 입력과 같다.
     * 부르는 쪽(module.srm.portal)이 같은 트랜잭션에서 그 발주가 로그인 협력사 것인지 먼저 잠가 확인한다.
     */
    @Transactional
    public AckResult recordFromPortal(long poId, AckForm in) {
        return record(poId, in, "PORTAL");
    }

    private AckResult record(long poId, AckForm in, String source) {
        LoginUser me = me();
        inbox.claimRequestKey("SRM_PO_ACK");
        if (in == null || in.rev() == null) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        PoRow po = lockOpen(poId);
        if (po.getRev() != in.rev()) {
            throw new BusinessException(HbErrorCode.CONFLICT);       // 그 사이 개정 — 다시 조회
        }
        FieldErrors e = FieldErrors.create();
        LocalDate ackOn = ackOn(in.ackOn(), po.getPoDate(), e);
        String by = text(in.ackByNm(), "ackByNm", 100, false, e);
        String remark = text(in.remark(), "remark", 500, false, e);
        boolean reject = Boolean.TRUE.equals(in.reject());
        String reason = null;
        List<AckLineValue> lines = List.of();
        if (reject) {
            reason = text(in.rejectReason(), "rejectReason", 500, true, e);
            if (in.lines() != null && !in.lines().isEmpty()) {
                throw new BusinessException(HbErrorCode.INVALID_VALUE);   // 거절에는 약속 행이 없다
            }
        } else {
            lines = lines(in.lines(), poId, po.getPoDate(), e);
        }
        e.throwIfAny();
        long ackId = insert(po, source, reject ? "REJECTED" : "PARTIAL", reason, by, ackOn, remark, null, me.loginId());
        for (AckLineValue l : lines) {
            mapper.insertAckLine(ackId, l.poLineId(), l.promisedDate(), l.promisedQty(), l.remark(), me.loginId());
        }
        return finish(ackId, poId, me.loginId());
    }

    /** 회신일: 비우면 오늘, 미래 불가, 발주일 이후 */
    LocalDate ackOn(String raw, LocalDate poDate, FieldErrors e) {
        LocalDate today = clock.today();
        LocalDate d = blank(raw) ? today : date(raw, "ackOn", true, e);
        if (d != null && d.isAfter(today)) {
            e.add("ackOn", "FUTURE");
        } else if (d != null && d.isBefore(poDate)) {
            e.add("ackOn", "SRM_ACK_BEFORE_PO");
        }
        return d;
    }

    /** 회신할 수 있는 발주(ISSUED·ACKED)를 잠근다 — 엑셀 실행도 같은 확인 */
    PoRow lockOpen(long poId) {
        PoRow po = pos.lockPo(poId);
        if (po == null) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        if (!"ISSUED".equals(po.getStatus()) && !"ACKED".equals(po.getStatus())) {
            throw new BusinessException(HbErrorCode.SRM_STATE);      // 작성 중(개정 중)·취소·마감
        }
        return po;
    }

    /** 묶음 머리(발주 머리를 잠근 뒤) — 순번 = 이 발주 max + 1 */
    long insert(PoRow po, String source, String status, String reason, String ackByNm, LocalDate ackOn, String remark, Long jobId,
            String by) {
        int seq = mapper.nextSeq(po.getPoId());
        return mapper.insertAck(po.getPoId(), po.getRev(), seq, source, status, reason, ackByNm, ackOn, remark, jobId, by);
    }

    /**
     * 행 검사 — 행 오류 키 = 보낸 배열 순번. 다른 발주의 행 id 는 400. 같은 행·같은 날 두 줄 금지(DUPLICATE),
     * 약속 납기는 발주일 이후, 발주 행마다 Σ 약속 ≤ 열린 수량(넘으면 그 행의 모든 줄에 SRM_ACK_OVER).
     */
    private List<AckLineValue> lines(List<AckLineForm> in, long poId, LocalDate poDate, FieldErrors e) {
        List<AckLineValue> out = new ArrayList<>();
        if (in == null || in.isEmpty()) {
            e.add("lines", "LINES_REQUIRED");
            return out;
        }
        if (in.size() > MAX_LINES) {
            e.add("lines", "TOO_MANY_LINES", MAX_LINES);
            return out;
        }
        Map<Long, AckPoLine> poLines = new HashMap<>();
        mapper.findPoLines(poId).forEach(l -> poLines.put(l.getPoLineId(), l));
        Map<String, Integer> scales = new HashMap<>();
        Set<String> seen = new HashSet<>();
        Map<Long, List<Integer>> byLine = new LinkedHashMap<>();
        Map<Long, BigDecimal> sums = new HashMap<>();
        List<FieldErrors> errs = new ArrayList<>();
        List<AckLineValue> staged = new ArrayList<>();
        for (int i = 0; i < in.size(); i++) {
            AckLineForm f = in.get(i);
            FieldErrors le = FieldErrors.create();
            errs.add(le);
            staged.add(null);
            if (f == null || f.poLineId() == null) {
                le.add("poLineId", "REQUIRED");
                continue;
            }
            AckPoLine pl = poLines.get(f.poLineId());
            if (pl == null) {
                throw new BusinessException(HbErrorCode.INVALID_VALUE);   // 다른 발주(다른 회사 포함)의 행 id
            }
            if (!"OPEN".equals(pl.getLineStatus())) {
                le.add("poLineId", "SRM_LINE_CLOSED");
            }
            LocalDate d = date(f.promisedDate(), "promisedDate", true, le);
            if (d != null && d.isBefore(poDate)) {
                le.add("promisedDate", "SRM_ACK_BEFORE_PO");
            }
            int scale = scales.computeIfAbsent(pl.getUnitCd(), u -> {
                Integer s = pos.unitScale(u);
                return s == null ? 6 : Math.min(s, 6);
            });
            BigDecimal qty = decimal(f.promisedQty(), "promisedQty", scale, QTY_INT_DIGITS, true, le);
            if (d != null && !seen.add(f.poLineId() + "|" + d)) {
                le.add("promisedDate", "DUPLICATE");
            }
            String remark = text(f.remark(), "remark", 500, false, le);
            byLine.computeIfAbsent(f.poLineId(), k -> new ArrayList<>()).add(i);
            if (qty != null) {
                sums.merge(f.poLineId(), qty, BigDecimal::add);
            }
            if (le.isEmpty()) {
                staged.set(i, new AckLineValue(f.poLineId(), d, qty, remark));
            }
        }
        byLine.forEach((lineId, idx) -> {
            BigDecimal open = new BigDecimal(poLines.get(lineId).getOpenQty());
            if (sums.getOrDefault(lineId, BigDecimal.ZERO).compareTo(open) > 0) {
                idx.forEach(i -> errs.get(i).add("promisedQty", "SRM_ACK_OVER", poLines.get(lineId).getOpenQty()));
            }
        });
        for (int i = 0; i < staged.size(); i++) {
            String key = String.valueOf(i);
            FieldErrors le = errs.get(i);
            le.list().forEach(x -> e.row(key).add(x.field(), x.code(), x.args()));
            if (le.isEmpty() && staged.get(i) != null) {
                out.add(staged.get(i));
            }
        }
        return out;
    }

    /**
     * 행을 다 넣은 뒤(같은 트랜잭션): 상태 계산 — 거절이 아니면 열린 행 전부가 열린 수량만큼 약속되면 ACCEPTED, 아니면 PARTIAL.
     * 발주 상태: 수락·부분 → ISSUED 면 ACKED, 거절 → ACKED 면 ISSUED. 경고 = 모자람(SHORT)·요구 납기보다 늦음(LATE).
     */
    AckResult finish(long ackId, long poId, String by) {
        AckDtos.AckRow ack = mapper.findAcks(poId).stream().filter(a -> a.getAckId() == ackId).findFirst()
                .orElseThrow(() -> new BusinessException(HbErrorCode.NOT_FOUND));
        List<Warning> warnings = new ArrayList<>();
        String status = ack.getAckStatus();
        if (!"REJECTED".equals(status)) {
            boolean all = true;
            for (AckCheck c : mapper.ackCheck(ackId)) {
                if (c.getPromised().compareTo(c.getOpenQty()) < 0) {
                    all = false;
                    warnings.add(new Warning(c.getLineNo(), "SHORT"));
                }
                if (c.getLastDate() != null && c.getLastDate().isAfter(c.getDueDate())) {
                    warnings.add(new Warning(c.getLineNo(), "LATE"));
                }
            }
            status = all ? "ACCEPTED" : "PARTIAL";
            mapper.setAckStatus(ackId, status);
            mapper.changePoState(poId, "ISSUED", "ACKED", by);
        } else {
            mapper.changePoState(poId, "ACKED", "ISSUED", by);
        }
        return new AckResult(ackId, ack.getAckSeq(), status, warnings);
    }
}
