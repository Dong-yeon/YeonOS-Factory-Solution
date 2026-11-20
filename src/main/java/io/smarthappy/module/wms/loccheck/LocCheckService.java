package io.smarthappy.module.wms.loccheck;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.smarthappy.module.wms.inv.InvDtos.ScanHit;
import io.smarthappy.module.wms.inv.InvDtos.TrxReq;
import io.smarthappy.module.wms.inv.InventoryService;
import io.smarthappy.module.wms.loccheck.LocCheckDtos.Book;
import io.smarthappy.module.wms.loccheck.LocCheckDtos.ConfirmForm;
import io.smarthappy.module.wms.loccheck.LocCheckDtos.CorrectForm;
import io.smarthappy.module.wms.loccheck.LocCheckDtos.Head;
import io.smarthappy.module.wms.loccheck.LocCheckDtos.Line;
import io.smarthappy.module.wms.loccheck.LocCheckDtos.NewLine;
import io.smarthappy.module.wms.loccheck.LocCheckDtos.ScanResult;
import io.smarthappy.module.wms.loccheck.LocCheckDtos.View;
import io.smarthappy.platform.common.web.BusinessException;
import io.smarthappy.platform.grid.ColumnSpec;
import io.smarthappy.platform.common.web.FieldErrors;
import io.smarthappy.platform.common.web.HbErrorCode;
import io.smarthappy.platform.tenant.TenantContext;
import io.smarthappy.platform.time.BizClock;
import lombok.RequiredArgsConstructor;
import tools.jackson.databind.json.JsonMapper;

/**
 * 정위치 점검 (docs/55, docs/49 W-3) — 위치를 스캔하고 그 칸의 물건을 스캔해 장부와 대조한다(위치만 — 수량은 실사 몫).
 * <ul>
 * <li>머리 = 칸 × 공장 날짜 하나(같은 날 같은 칸 1회). 줄 추가·완료 모두 머리 {@code FOR UPDATE} 뒤 상태 확인 — 둘째 완료·완료 중 줄 추가는 409</li>
 * <li>판정: 그 LOT(비관리 품목은 품목)의 수량 &gt; 0 칸이 이 칸이면 OK, 다른 칸에만 있으면 MISPLACED, 어디에도 없거나 못 읽으면 UNKNOWN.
 * 판정 당시 장부를 줄에 저장. 완료 때 이 칸 장부에 있는데 스캔 안 된 것 = MISSING(같은 트랜잭션·같은 장부 시점)</li>
 * <li>보정은 사람이 확인 뒤(결정): MISPLACED 줄 → 이동 원장 짝(출처 LOC_CHECK·사유 LOC_CORRECT). 줄 {@code FOR UPDATE} → 원장(재고 칸 잠금)
 * 순서. 출발·도착 출하 대기(STAGING) 칸은 막는다(피킹분은 출하 화면에서)</li>
 * </ul>
 * 온라인만(상위 검토) — PDA 는 대기열을 쓰지 않는다. clientReqId 는 응답이 끊겨 다시 보낼 때 같은 줄
 */
@Service
@RequiredArgsConstructor
public class LocCheckService {

    public static final String PROG = "wms-loc-check";
    static final String SRC = "LOC_CHECK";
    static final String REASON = "LOC_CORRECT";
    static final JsonMapper JSON = JsonMapper.builder().build();

    private final LocCheckMapper mapper;
    private final InventoryService inventory;
    private final BizClock clock;

    // ================================================================== PDA — 시작·스캔·완료

    /** 칸 점검 시작(또는 오늘 OPEN 이어 쓰기). 오늘 이미 완료한 칸이면 409 */
    @Transactional
    public View start(String plantCd, long nodeId, Long workerId) {
        if (!mapper.isStockNode(nodeId, plantCd)) {
            throw fieldError("nodeId", "NOT_STOCK_LOCATION");
        }
        LocalDate day = clock.instant().atZone(clock.zone(plantCd)).toLocalDate();
        mapper.insertHead(plantCd, nodeId, day, workerId, by());
        Head h = mapper.lockHeadByDay(nodeId, day);
        if (h == null) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        if ("DONE".equals(h.getStatus())) {
            throw done();
        }
        return new View(h, mapper.findLines(h.getCheckId()));
    }

    /**
     * 물건 스캔 한 번 = 한 줄. hit = 스캔 판별(StockService.scan — 사무·PDA 와 같은 규칙). 같은 요청 id 는 앞 줄, 같은 LOT(품목) 두 번은 앞 줄(duplicate)
     */
    @Transactional
    public ScanResult scan(long checkId, String plantCd, String text, ScanHit hit, String clientReqId, String scannedAt, Long workerId) {
        String req = clientReqId == null || clientReqId.isBlank() ? null : clientReqId.strip();
        if (req != null && req.length() > 64) {
            throw fieldError("clientReqId", "TOO_LONG");
        }
        OffsetDateTime at = scannedAt(scannedAt);
        Head h = lockOwn(checkId, plantCd);
        if (req != null) {
            Long prev = mapper.lineByReq(req);
            if (prev != null) {
                Line l = mapper.findLine(prev);
                if (l.getCheckId() != checkId) {
                    throw new BusinessException(HbErrorCode.DUPLICATE_REQUEST);
                }
                return new ScanResult(h, l, false);
            }
        }
        if ("DONE".equals(h.getStatus())) {
            throw done();
        }
        fresh(h);
        NewLine n = new NewLine();
        n.setCheckId(checkId);
        n.setScanCode(text == null ? "" : text.strip());
        n.setScanType(hit != null && ("LOT".equals(hit.type()) || "ITEM".equals(hit.type())) ? hit.type() : "OTHER");
        n.setScannedAt(at);
        n.setWorkerId(workerId);
        n.setClientReqId(req);
        n.setBy(by());
        Long item = null;
        Long lot = null;
        String unknown = null;
        if (hit == null || !hit.ok() || hit.itemId() == null) {
            unknown = hit != null && hit.ok() ? "NOT_ITEM" : "NOT_FOUND";
        } else if ("LOT".equals(hit.type())) {
            item = hit.itemId();
            lot = hit.lotId();
        } else if ("ITEM".equals(hit.type())) {
            item = hit.itemId();
            if ("Y".equals(mapper.itemLotCtrl(item))) {
                unknown = "LOT_REQUIRED";           // LOT 관리 품목은 LOT 라벨로 — 품목만으로는 어느 LOT 인지 모른다
            }
        } else {
            unknown = "NOT_ITEM";
        }
        n.setItemId(item);
        n.setLotId(lot);
        if (unknown == null) {
            Long dup = mapper.sameScan(checkId, item, lot);
            if (dup != null) {
                return new ScanResult(h, mapper.findLine(dup), true);
            }
            List<Book> book = mapper.bookOf(plantCd, item, lot);
            n.setBookJson(json(book));
            if (book.isEmpty()) {
                unknown = "NO_STOCK";              // 장부 어디에도 없음(이미 나간 것이 남음 포함)
            } else {
                n.setResult(book.stream().anyMatch(b -> b.getNodeId() == h.getNodeId()) ? "OK" : "MISPLACED");
            }
        }
        if (unknown != null) {
            n.setResult("UNKNOWN");
            n.setReasonCd(unknown);
            if (n.getBookJson() == null) {
                n.setBookJson("[]");
            }
        }
        mapper.insertLine(n);
        return new ScanResult(mapper.findHead(checkId), mapper.findLine(n.getLineId()), false);
    }

    /** 전수 확인 완료 — 이 칸 장부(수량 &gt; 0, 상태 무관)에 있는데 스캔 안 된 품목·LOT 마다 MISSING, 집계·일치 여부. 둘째 완료는 409 */
    @Transactional
    public View done(long checkId, String plantCd) {
        Head h = lockOwn(checkId, plantCd);
        if ("DONE".equals(h.getStatus())) {
            throw done();
        }
        fresh(h);
        String by = by();
        mapper.missingOf(checkId, h.getNodeId()).forEach(m -> {
            NewLine n = new NewLine();
            n.setCheckId(checkId);
            n.setItemId(m.getItemId());
            n.setLotId(m.getLotId());
            n.setResult("MISSING");
            n.setBookJson(m.getBookJson());
            n.setBy(by);
            mapper.insertLine(n);
        });
        mapper.done(checkId, by);
        return view(checkId);
    }

    // ================================================================== 사무 — 조회·보정·확인

    /** 그리드 열 — 정렬 표시 = LocCheckSort 와 같은 열 */
    static final List<ColumnSpec> COLUMNS = List.of(
            ColumnSpec.of("checkDate", "wmsLocCheck.col.checkDate",
                    ColumnSpec.ColumnType.DATE, 110).withSort(),
            ColumnSpec.text("path", "wmsLocCheck.col.path", 200).withSort().withFlex().withMinWidth(140),
            ColumnSpec.code("status", "wmsLocCheck.col.status", "WMS_LOC_CHECK_STATUS", 90).withSort(),
            ColumnSpec.of("openCnt", "wmsLocCheck.col.openCnt",
                    ColumnSpec.ColumnType.INT, 80).withSort(),
            ColumnSpec.of("lineCnt", "wmsLocCheck.col.lineCnt",
                    ColumnSpec.ColumnType.INT, 70).withPriority(2),
            ColumnSpec.text("accurateYn", "wmsLocCheck.col.accurate", 70).withPriority(2),
            ColumnSpec.text("doneBy", "wmsLocCheck.col.doneBy", 110).withPriority(3));
    static final List<String> SORT = List.of("checkDate,desc");
    static final int MAX_DAYS = 366;

    @Transactional(readOnly = true)
    public LocCheckDtos.Page<Head> grid(String plantCd, String from, String to, String status, Boolean openOnly, Integer page, Integer size,
            List<String> sort, Boolean total, int maxSize) {
        io.smarthappy.platform.common.query.PageQuery pq = io.smarthappy.platform.common.query.PageQuery.parse(page, size, sort, total,
                LocCheckDtos.LocCheckSort.values(), maxSize, SORT);
        FieldErrors e = FieldErrors.create();
        String plant = plantCd == null || plantCd.isBlank() ? null : plantCd.strip().toUpperCase(Locale.ROOT);
        if (plant == null) {
            e.add("plantCd", "REQUIRED");
        }
        LocalDate today = clock.instant().atZone(clock.zone(plant)).toLocalDate();
        LocalDate t = day(to, "to", e, today);
        LocalDate f = day(from, "from", e, t == null ? today.minusDays(29) : t.minusDays(29));
        String st = status == null || status.isBlank() ? null : status.strip().toUpperCase(Locale.ROOT);
        if (st != null && !st.equals("OPEN") && !st.equals("DONE")) {
            e.add("status", "CODE");
        }
        e.throwIfAny();
        if (f.isAfter(t)) {
            throw new BusinessException(HbErrorCode.INVALID_RANGE);
        }
        if (java.time.temporal.ChronoUnit.DAYS.between(f, t) + 1 > MAX_DAYS) {
            throw new BusinessException(HbErrorCode.RANGE_TOO_LONG, MAX_DAYS);
        }
        boolean open = Boolean.TRUE.equals(openOnly);
        return new LocCheckDtos.Page<>(mapper.findPage(plant, f, t, st, open, pq), pq.isWithTotal() ? mapper.count(plant, f, t, st, open) : null);
    }

    public List<ColumnSpec> columns() {
        return COLUMNS;
    }

    private static LocalDate day(String raw, String field, FieldErrors e, LocalDate dflt) {
        if (raw == null || raw.isBlank()) {
            return dflt;
        }
        try {
            return LocalDate.parse(raw.strip());
        } catch (DateTimeParseException ex) {
            e.add(field, "FORMAT");
            return null;
        }
    }

    @Transactional(readOnly = true)
    public View view(long id) {
        Head h = mapper.findHead(id);
        if (h == null) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        return new View(h, mapper.findLines(id));
    }

    /**
     * 보정 이동(사람이 확인 뒤) — 다른 위치(MISPLACED) 줄만, 처리 안 된 것만. 출발 칸·상태 = 판정 당시 장부 중 하나, 도착 = 점검한 칸, 같은 상태.
     * 수량이 지금 출발 칸보다 많으면 원장이 409(그새 바뀜). 보류 LOT·마감된 달은 원장 규칙 그대로
     */
    @Transactional
    public View correct(long lineId, CorrectForm f) {
        if (f == null) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        Line l = lockLine(lineId);
        Head h = mapper.findHead(l.getCheckId());
        if (!"MISPLACED".equals(l.getResult())) {
            throw fieldError("lineId", "NOT_MISPLACED");
        }
        FieldErrors e = FieldErrors.create();
        Map<String, Object> from = null;
        if (f.fromNodeId() == null) {
            e.add("fromNodeId", "REQUIRED");
        } else {
            String st = f.fromStatus() == null ? null : f.fromStatus().strip().toUpperCase(Locale.ROOT);
            from = book(l).stream().filter(b -> f.fromNodeId().equals(num(b.get("nodeId"))) && (st == null || st.equals(b.get("status"))))
                    .findFirst().orElse(null);
            if (from == null) {
                e.add("fromNodeId", "NOT_IN_BOOK");
            } else if ("STAGING".equals(mapper.nodeUsage(f.fromNodeId(), h.getPlantCd()))) {
                e.add("fromNodeId", "STAGING_LOCKED");
            }
        }
        if ("STAGING".equals(mapper.nodeUsage(h.getNodeId(), h.getPlantCd()))) {
            e.add("lineId", "STAGING_LOCKED");
        }
        BigDecimal qty = f.qty();
        if (qty == null || qty.signum() <= 0) {
            e.add("qty", "POSITIVE");
        } else if (qty.stripTrailingZeros().scale() > 6) {
            e.add("qty", "SCALE", 6);
        }
        String remark = f.remark() == null || f.remark().isBlank() ? null : f.remark().strip();
        if (remark != null && remark.length() > 500) {
            e.add("remark", "TOO_LONG", 500);
        }
        e.throwIfAny();
        String status = String.valueOf(from.get("status"));
        UUID grp = UUID.randomUUID();
        OffsetDateTime now = clock.now().toOffsetDateTime();
        inventory.postChecked(List.of(
                new TrxReq("MOVE_OUT", l.getItemId(), f.fromNodeId(), status, qty.negate(), grp, SRC, lineId, REASON, remark, now, "KEY", null,
                        l.getLotId()),
                new TrxReq("MOVE_IN", l.getItemId(), h.getNodeId(), status, qty, grp, SRC, lineId, REASON, remark, now, "KEY", null,
                        l.getLotId())), null);
        mapper.review(lineId, "CORRECTED", remark, grp.toString(), by());
        return view(l.getCheckId());
    }

    /** 확인만(사유 필수, 원장 없음) — 정상 아닌 줄, 처리 안 된 것만. 없어진 재고를 맞추는 것은 실사·조정 화면 */
    @Transactional
    public View confirm(long lineId, ConfirmForm f) {
        Line l = lockLine(lineId);
        if ("OK".equals(l.getResult())) {
            throw fieldError("lineId", "NOT_MISMATCH");
        }
        String reason = f == null || f.reason() == null || f.reason().isBlank() ? null : f.reason().strip();
        if (reason == null) {
            throw fieldError("reason", "REQUIRED");
        }
        if (reason.length() > 500) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE).withFieldErrors(FieldErrors.create().add("reason", "TOO_LONG", 500).list());
        }
        mapper.review(lineId, "CONFIRMED", reason, null, by());
        return view(l.getCheckId());
    }

    // ================================================================== 도움

    private Head lockOwn(long checkId, String plantCd) {
        Head h = mapper.lockHead(checkId);
        if (h == null || (plantCd != null && !plantCd.equals(h.getPlantCd()))) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        return h;
    }

    /** 줄 잠금 → 처리 안 됨 확인(두 번째 처리는 409) */
    private Line lockLine(long lineId) {
        if (mapper.lockLine(lineId) == null) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        Line l = mapper.findLine(lineId);
        if (l.getReviewAction() != null) {
            throw new BusinessException(HbErrorCode.CONFLICT);
        }
        return l;
    }

    /**
     * 점검일 = 오늘(공장 시간대)인 열린 점검만 이어 쓴다(사용자 결정 2026-10-30 L-1) — 자정을 넘긴 PDA 가 어제 머리에 스캔·완료하면
     * 그 시각의 장부가 "어제 점검" 정확도로 들어가 조용히 틀린다. 409 뒤 위치를 다시 스캔하면 오늘 머리가 열린다
     */
    private void fresh(Head h) {
        LocalDate today = clock.instant().atZone(clock.zone(h.getPlantCd())).toLocalDate();
        if (!today.equals(h.getCheckDate())) {
            throw new BusinessException(HbErrorCode.WMS_LOC_CHECK_STALE, h.getCheckDate().toString());
        }
    }

    private static BusinessException done() {
        return new BusinessException(HbErrorCode.CONFLICT, List.of(new BusinessException.Detail("wms.locCheck.done")));
    }

    private static OffsetDateTime scannedAt(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return OffsetDateTime.parse(raw.strip());
        } catch (DateTimeParseException e) {
            throw fieldError("scannedAt", "FORMAT");
        }
    }

    private static String json(List<Book> book) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Book b : book) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("nodeId", b.getNodeId());
            m.put("path", b.getPath());
            m.put("status", b.getStatus());
            m.put("qty", b.getQty().stripTrailingZeros().toPlainString());
            out.add(m);
        }
        return JSON.writeValueAsString(out);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> book(Line l) {
        return l.getBookJson() == null ? List.of() : JSON.readValue(l.getBookJson(), List.class);
    }

    private static Long num(Object v) {
        return v instanceof Number n ? n.longValue() : null;
    }

    private static String by() {
        return TenantContext.currentActor();
    }

    static BusinessException fieldError(String field, String code) {
        return new BusinessException(HbErrorCode.INVALID_VALUE).withFieldErrors(FieldErrors.create().add(field, code).list());
    }
}
