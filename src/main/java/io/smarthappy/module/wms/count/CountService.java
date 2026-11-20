package io.smarthappy.module.wms.count;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.smarthappy.module.wms.count.CountDtos.AddLineForm;
import io.smarthappy.module.wms.count.CountDtos.CancelForm;
import io.smarthappy.module.wms.count.CountDtos.CountHead;
import io.smarthappy.module.wms.count.CountDtos.CountRow;
import io.smarthappy.module.wms.count.CountDtos.CountView;
import io.smarthappy.module.wms.count.CountDtos.CountsForm;
import io.smarthappy.module.wms.count.CountDtos.CreateForm;
import io.smarthappy.module.wms.count.CountDtos.LineExport;
import io.smarthappy.module.wms.count.CountDtos.LineRow;
import io.smarthappy.module.wms.count.CountDtos.LineView;
import io.smarthappy.module.wms.count.CountDtos.RecountForm;
import io.smarthappy.module.wms.inv.InvDtos.PostResult;
import io.smarthappy.module.wms.inv.InvDtos.TrxReq;
import io.smarthappy.module.wms.inv.InventoryService;
import io.smarthappy.platform.auth.Action;
import io.smarthappy.platform.auth.PermissionService;
import io.smarthappy.platform.common.query.PageQuery;
import io.smarthappy.platform.common.web.BusinessException;
import io.smarthappy.platform.common.web.FieldErrors;
import io.smarthappy.platform.common.web.HbErrorCode;
import io.smarthappy.platform.excel.ExcelExportService;
import io.smarthappy.platform.excel.ExcelExportService.ExportJob;
import io.smarthappy.platform.grid.ColumnSpec;
import io.smarthappy.platform.numbering.NumberingService;
import io.smarthappy.platform.policy.Policies;
import io.smarthappy.platform.policy.PolicyService;
import io.smarthappy.platform.security.LoginUser;
import io.smarthappy.platform.time.BizClock;
import lombok.RequiredArgsConstructor;

/**
 * 재고 실사 (docs/30 W5, 4.5) — 만들기(DRAFT) → 시작(COUNTING, 현재고 칸마다 줄) → 센 수량 입력 → 검토(REVIEW) → 확정(POSTED).
 *
 * <ul>
 * <li>장부 수량 = **센 시각까지 원장 합**(서버 계산) — 실사 중 이동이 있어도 차이 = 센 수량 − 그 시각 장부(D9, 이중 조정 없음)</li>
 * <li>확정 = 칸마다 마지막 재실사 줄의 차이를 원장 COUNT_ADJ(기초 재고 실사는 OPENING)로, 발생 시각 = 센 시각</li>
 * <li>차이율이 한도(머리 tol_pct — 정책 WMS_COUNT_TOL_PCT)를 넘는 줄이 있으면 APPROVE 권한</li>
 * <li>상태를 바꾸는 동작은 머리 행 FOR UPDATE → rowVer → 상태(409 WMS_COUNT_STATE) — 확정 두 번은 한 번만</li>
 * <li>블라인드 실사는 세는 동안(DRAFT·COUNTING) 장부·차이를 응답에 넣지 않는다</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class CountService {

    static final String PROG = "wms-count";
    static final String RULE = "WMS_COUNT";
    static final String POLICY_TOL = "WMS_COUNT_TOL_PCT";
    static final Set<String> TYPES = Set.of("OPENING", "CYCLE", "FULL");
    static final Set<String> STATUSES = Set.of("AVAILABLE", "QC", "HOLD", "BLOCKED");
    static final Duration FUTURE_TOLERANCE = Duration.ofMinutes(5);
    static final int MAX_LINES = 5000;
    static final BigDecimal LIMIT = new BigDecimal("1000000000000");

    private final CountMapper mapper;
    private final InventoryService inventory;
    private final NumberingService numbering;
    private final PolicyService policy;
    private final PermissionService permissions;
    private final BizClock clock;
    private final ExcelExportService excel;

    // ================================================================== 조회

    @Transactional(readOnly = true)
    public List<CountHead> list(String plantCd, String status) {
        String plant = plant(plantCd);
        String st = status == null || status.isBlank() ? null : status.strip();
        return mapper.findCounts(plant, st, 200);
    }

    /** 그리드 열(UI 단계 3 ①) — 정렬 표시 = CountSort 와 같은 열 */
    /**
     * 실사 줄 엑셀(③, docs/30 12.12.15) — 첫 열 줄 번호는 붙여넣기 열쇠(화면이 줄 번호로 맞춘다). 블라인드로 숨긴 장부·차이는 비운다(화면과 같은 규칙)
     */
    static final List<ColumnSpec> LINE_EXPORT_COLUMNS = List.of(
            ColumnSpec.of("lineId", "wmsCount.col.lineId", ColumnSpec.ColumnType.INT, 80),
            ColumnSpec.text("path", "wmsCount.col.path", 200),
            ColumnSpec.text("itemCd", "wmsCount.itemCd", 120),
            ColumnSpec.text("itemNm", "wmsCount.col.itemNm", 160),
            ColumnSpec.text("lotNo", "wmsCount.lotNo", 120),
            ColumnSpec.code("stockStatus", "wmsCount.stockStatus", "STOCK_STATUS", 90),
            ColumnSpec.of("recountNo", "wmsCount.col.recountNo", ColumnSpec.ColumnType.INT, 70),
            ColumnSpec.text("unitCd", "wmsCount.col.unit", 60),
            ColumnSpec.dec("bookQty", "wmsCount.col.book", 6, 100).withMinScale(0),
            ColumnSpec.dec("countedQty", "wmsCount.col.counted", 6, 100).withMinScale(0),
            ColumnSpec.dec("diffQty", "wmsCount.col.diff", 6, 100).withMinScale(0),
            ColumnSpec.text("countedPerson", "wmsCount.col.countedBy", 140));

    static final List<ColumnSpec> COUNT_COLUMNS = List.of(
            ColumnSpec.text("countNo", "wmsCount.col.countNo", 140).withSort(),
            ColumnSpec.code("countType", "wmsCount.col.type", "WMS_COUNT_TYPE", 90).withSort(),
            ColumnSpec.text("scopePath", "wmsCount.col.scope", 200).withFlex().withMinWidth(120),
            ColumnSpec.text("itemCd", "wmsCount.col.itemCd", 120).withPriority(3),
            ColumnSpec.code("status", "wmsCount.col.status", "WMS_COUNT_STATUS", 90).withSort(),
            ColumnSpec.of("countedCount", "wmsCount.col.countedCount", ColumnSpec.ColumnType.INT, 70),
            ColumnSpec.of("lineCount", "wmsCount.col.lineCount", ColumnSpec.ColumnType.INT, 70),
            ColumnSpec.of("createdAt", "wmsCount.col.createdAt", ColumnSpec.ColumnType.DATETIME, 140).withSort());
    static final List<String> COUNT_SORT = List.of("createdAt,desc");

    /** 실사 목록 그리드 — 조건은 list 와 같고 페이징·정렬(허용 열만)·건수. 기존 list 는 그대로 */
    @Transactional(readOnly = true)
    public Page<CountHead> grid(String plantCd, String status, Integer page, Integer size, List<String> sort, Boolean total) {
        PageQuery pq = PageQuery.parse(page, size, sort, total, CountDtos.CountSort.values(), policy.intValue(Policies.GRID_PAGE_SIZE_MAX),
                COUNT_SORT);
        String plant = plant(plantCd);
        String st = status == null || status.isBlank() ? null : status.strip();
        return new Page<>(mapper.findCountPage(plant, st, pq), pq.isWithTotal() ? mapper.countCounts(plant, st) : null);
    }

    public List<ColumnSpec> columns() {
        return COUNT_COLUMNS;
    }

    /** 그리드 한 쪽 */
    public record Page<T>(List<T> list, Long total) {
    }

    @Transactional(readOnly = true)
    public CountView view(long id) {
        CountHead h = mapper.findCount(id);
        if (h == null) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        boolean hide = "Y".equals(h.getBlindYn()) && ("DRAFT".equals(h.getStatus()) || "COUNTING".equals(h.getStatus()));
        if (hide) {
            h.setDiffCount(0);
        }
        List<LineView> lines = mapper.findLines(id).stream().map(l -> view(l, h.getTolPct(), hide)).toList();
        return new CountView(h, hide, lines);
    }

    /** 실사 줄 엑셀 — 재실사로 대체된 줄은 빼고(화면에서 입력할 수 있는 줄만), 블라인드면 장부·차이 비움 */
    @Transactional(readOnly = true)
    public long exportLines(long id, jakarta.servlet.http.HttpServletResponse response) throws java.io.IOException {
        CountView v = view(id);
        List<LineExport> rows = v.lines().stream().filter(LineView::latest).map(l -> new LineExport(l.lineId(), l.path(), l.itemCd(),
                l.itemNm(), l.lotNo(), l.stockStatus(), l.recountNo(), l.unitCd(), dec(l.bookQty()), dec(l.countedQty()), dec(l.diffQty()),
                l.countedQty() == null ? null : l.countedWorkerCd() != null ? l.countedWorkerNm() + " (" + l.countedWorkerCd() + ")"
                        : l.countedBy())).toList();
        return excel.export(response, new ExportJob(PROG, "prog.wmsCount", LINE_EXPORT_COLUMNS, rows.size(), List.of()),
                w -> rows.forEach(w));
    }

    private static BigDecimal dec(String s) {
        return s == null ? null : new BigDecimal(s);
    }

    static LineView view(LineRow l, BigDecimal tol, boolean hide) {
        BigDecimal pct = pct(l.getBookQty(), l.getDiffQty());
        boolean over = l.getDiffQty() != null && overTol(l.getBookQty(), l.getDiffQty(), tol);
        return new LineView(l.getLineId(), l.getNodeId(), l.getPath(), l.getItemId(), l.getItemCd(), l.getItemNm(), l.getUnitCd(),
                l.getLotNo(), l.getStockStatus(), l.getRecountNo(), hide ? null : text(l.getBookQty()), text(l.getCountedQty()), l.getCountedAt(),
                l.getCountedBy(), hide ? null : text(l.getDiffQty()), hide || pct == null ? null : text(pct), !hide && over,
                l.getAdjTrxId(), l.isLatest(), l.getCountedWorkerCd(), l.getCountedWorkerNm());
    }

    // ================================================================== 만들기·시작·줄 추가

    @Transactional
    public long create(CreateForm f) {
        if (f == null) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        LoginUser me = me();
        FieldErrors e = FieldErrors.create();
        String plant = null;
        try {
            plant = plant(f.plantCd());
        } catch (BusinessException ex) {
            e.add("plantCd", f.plantCd() == null || f.plantCd().isBlank() ? "REQUIRED" : "NOT_FOUND");
        }
        String type = f.countType() == null ? null : f.countType().strip();
        if (type == null || type.isEmpty()) {
            e.add("countType", "REQUIRED");
        } else if (!TYPES.contains(type)) {
            e.add("countType", "CODE");
        }
        if (f.scopeNodeId() == null) {
            e.add("scopeNodeId", "REQUIRED");
        } else if (plant != null && !mapper.nodeIn(f.scopeNodeId(), plant, null)) {
            e.add("scopeNodeId", "NOT_FOUND");
        }
        Long itemId = null;
        if (f.itemCd() != null && !f.itemCd().isBlank()) {
            String cd = f.itemCd().strip().toUpperCase(Locale.ROOT);
            itemId = cd.length() > 40 ? null : mapper.itemIdByCode(cd);
            if (itemId == null) {
                e.add("itemCd", "NO_SUCH_CODE");
            }
        }
        String blind = f.blindYn() == null || f.blindYn().isBlank() ? "Y" : f.blindYn().strip();
        if (!"Y".equals(blind) && !"N".equals(blind)) {
            e.add("blindYn", "CODE");
        }
        String remark = remark(f.remark(), "remark", e);
        e.throwIfAny();
        BigDecimal tol = BigDecimal.valueOf(policy.intValue(POLICY_TOL));
        String no = numbering.nextOrDefault(RULE, "재고 실사", "CNT-{yyMM}-{seq:4}", "MONTH");
        return mapper.insertCount(plant, no, type, f.scopeNodeId(), itemId, blind, tol, remark, me.loginId());
    }

    /** DRAFT → COUNTING. 기초 재고 실사(OPENING)는 줄을 만들지 않는다(빈 창고 — 줄 추가로 넣는다) */
    @Transactional
    public CountView start(long id, Integer rowVer) {
        LoginUser me = me();
        CountHead h = lock(id, rowVer, "DRAFT");
        mapper.markStarted(id, me.loginId());
        if (!"OPENING".equals(h.getCountType())) {
            int n = mapper.generateLines(id, h.getPlantCd(), h.getScopeNodeId(), h.getItemId(), me.loginId());
            if (n > MAX_LINES) {
                throw fieldError("scopeNodeId", "TOO_MANY_LINES", MAX_LINES);
            }
        }
        return view(id);
    }

    /** 장부에 없던 칸(찾은 물건) 또는 기초 재고 칸 — DRAFT·COUNTING 에서 범위·품목 조건 안만 */
    @Transactional
    public CountView addLine(long id, AddLineForm f) {
        LoginUser me = me();
        CountHead h = lockAny(id, f == null ? null : f.rowVer());
        if (!"DRAFT".equals(h.getStatus()) && !"COUNTING".equals(h.getStatus())) {
            throw new BusinessException(HbErrorCode.WMS_COUNT_STATE);
        }
        FieldErrors e = FieldErrors.create();
        if (f.nodeId() == null) {
            e.add("nodeId", "REQUIRED");
        } else if (!mapper.nodeIn(f.nodeId(), h.getPlantCd(), h.getScopeNodeId())) {
            e.add("nodeId", "OUT_OF_SCOPE");
        }
        Long itemId = null;
        if (f.itemCd() == null || f.itemCd().isBlank()) {
            e.add("itemCd", "REQUIRED");
        } else {
            String cd = f.itemCd().strip().toUpperCase(Locale.ROOT);
            itemId = cd.length() > 40 ? null : mapper.itemIdByCode(cd);
            if (itemId == null) {
                e.add("itemCd", "NO_SUCH_CODE");
            } else if (h.getItemId() != null && !h.getItemId().equals(itemId)) {
                e.add("itemCd", "OUT_OF_SCOPE");
            }
        }
        String status = f.stockStatus() == null || f.stockStatus().isBlank() ? "AVAILABLE" : f.stockStatus().strip();
        if (!STATUSES.contains(status)) {
            e.add("stockStatus", "CODE");
        }
        // LOT: 관리 품목은 필수·같은 품목, 비관리 품목은 비움(원장 규칙과 같게 — 확정 때 다시 막히지 않게 여기서)
        Long lotId = null;
        String lotNo = f.lotNo() == null || f.lotNo().isBlank() ? null : f.lotNo().strip();
        if (itemId != null) {
            boolean controlled = "Y".equals(mapper.lotCtrlYn(itemId));
            if (!controlled && lotNo != null) {
                e.add("lotNo", "LOT_NOT_CONTROLLED");
            } else if (controlled && lotNo == null) {
                e.add("lotNo", "LOT_REQUIRED");
            } else if (controlled) {
                CountDtos.LotRef l = lotNo.length() > 60 ? null : mapper.lotByNo(lotNo);
                if (l == null) {
                    e.add("lotNo", "NO_SUCH_CODE");
                } else if (l.getItemId() != itemId) {
                    e.add("lotNo", "LOT_MISMATCH");
                } else {
                    lotId = l.getLotId();
                }
            }
        }
        e.throwIfAny();
        mapper.touch(id, h.getRowVer(), me.loginId());
        try {
            mapper.insertLine(id, f.nodeId(), itemId, lotId, status, 0, me.loginId());
        } catch (DuplicateKeyException ex) {
            throw fieldError("itemCd", "DUPLICATE");          // 같은 칸이 이미 있다 — 그 줄에 센다
        }
        return view(id);
    }

    // ================================================================== 센 수량

    /**
     * 센 수량 입력(COUNTING). 줄마다 장부 = 센 시각까지 원장 합. 빈 수량은 건너뛴다(아직 안 셈). 줄 키 = lineId
     */
    @Transactional
    public CountView enter(long id, CountsForm f) {
        return enter(id, f, null);
    }

    /** 창고 PDA — workerId = 배지 작업자(줄의 센 사람). 사무 입력(null)은 센 사람을 비운다(counted_by = 로그인 사용자) */
    @Transactional
    public CountView enter(long id, CountsForm f, Long workerId) {
        LoginUser me = me();
        CountHead h = lock(id, f == null ? null : f.rowVer(), "COUNTING");
        if (f.rows() == null || f.rows().isEmpty()) {
            throw fieldError("rows", "REQUIRED");
        }
        Map<Long, LineRow> lines = mapper.findLines(id).stream().collect(Collectors.toMap(LineRow::getLineId, Function.identity()));
        FieldErrors e = FieldErrors.create();
        Instant now = clock.instant();
        List<Object[]> todo = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        for (CountRow r : f.rows()) {
            String key = r == null || r.lineId() == null ? "?" : String.valueOf(r.lineId());
            FieldErrors.RowScope row = e.row(key);
            LineRow l = r == null || r.lineId() == null ? null : lines.get(r.lineId());
            if (l == null || !seen.add(l.getLineId())) {
                row.add("lineId", "NOT_FOUND");
                continue;
            }
            if (!l.isLatest()) {
                row.add("lineId", "RECOUNTED");               // 재실사로 대체된 줄
                continue;
            }
            if (r.countedQty() == null || r.countedQty().isBlank()) {
                continue;
            }
            BigDecimal q = qty(r.countedQty(), row);
            OffsetDateTime at = countedAt(r.countedAt(), h, now, row);
            if (q != null && at != null) {
                todo.add(new Object[] {l, q, at});
            }
        }
        e.throwIfAny();
        for (Object[] t : todo) {
            LineRow l = (LineRow) t[0];
            OffsetDateTime at = (OffsetDateTime) t[2];
            BigDecimal book = inventory.ledgerQtyAsOf(l.getItemId(), l.getNodeId(), l.getStockStatus(), l.getLotId(), at);
            mapper.updateCounted(l.getLineId(), book, (BigDecimal) t[1], at, workerId, me.loginId());
        }
        mapper.touch(id, h.getRowVer(), me.loginId());
        return view(id);
    }

    // ================================================================== 검토·재실사·확정·취소

    /** COUNTING → REVIEW. 마지막 재실사 줄은 모두 세어 있어야 한다 */
    @Transactional
    public CountView submit(long id, Integer rowVer) {
        LoginUser me = me();
        lock(id, rowVer, "COUNTING");
        List<LineRow> lines = mapper.findLines(id);
        long uncounted = lines.stream().filter(l -> l.isLatest() && l.getCountedQty() == null).count();
        if (lines.isEmpty()) {
            throw fieldError("lines", "REQUIRED");
        }
        if (uncounted > 0) {
            throw fieldError("lines", "UNCOUNTED", uncounted);
        }
        mapper.updateStatus(id, rowVer, "COUNTING", "REVIEW", me.loginId());
        return view(id);
    }

    /** REVIEW → COUNTING, 고른 칸마다 재실사 번호 +1 줄(앞 줄은 남는다) */
    @Transactional
    public CountView recount(long id, RecountForm f) {
        LoginUser me = me();
        lock(id, f == null ? null : f.rowVer(), "REVIEW");
        if (f.lineIds() == null || f.lineIds().isEmpty()) {
            throw fieldError("lineIds", "REQUIRED");
        }
        Map<Long, LineRow> lines = mapper.findLines(id).stream().collect(Collectors.toMap(LineRow::getLineId, Function.identity()));
        FieldErrors e = FieldErrors.create();
        List<LineRow> pick = new ArrayList<>();
        for (Long lid : new java.util.LinkedHashSet<>(f.lineIds())) {
            LineRow l = lid == null ? null : lines.get(lid);
            if (l == null || !l.isLatest()) {
                e.row(String.valueOf(lid)).add("lineId", l == null ? "NOT_FOUND" : "RECOUNTED");
            } else if (l.getRecountNo() >= 99) {
                e.row(String.valueOf(lid)).add("lineId", "RECOUNT_LIMIT", 99);
            } else {
                pick.add(l);
            }
        }
        e.throwIfAny();
        mapper.updateStatus(id, f.rowVer(), "REVIEW", "COUNTING", me.loginId());
        for (LineRow l : pick) {
            mapper.insertLine(id, l.getNodeId(), l.getItemId(), l.getLotId(), l.getStockStatus(), l.getRecountNo() + 1, me.loginId());
        }
        return view(id);
    }

    /**
     * REVIEW → POSTED. 칸마다 마지막 줄의 차이 ≠ 0 이면 원장 한 행(COUNT_ADJ / OPENING), 발생 시각 = 센 시각.
     * 한도를 넘는 줄이 있으면 APPROVE. 원장 오류(재고 부족·마감 월)면 전부 롤백.
     */
    @Transactional
    public CountView post(long id, Integer rowVer) {
        LoginUser me = me();
        CountHead h = lock(id, rowVer, "REVIEW");
        List<LineRow> lines = mapper.findLines(id).stream().filter(LineRow::isLatest).toList();
        boolean opening = "OPENING".equals(h.getCountType());
        List<LineRow> diffs = lines.stream().filter(l -> l.getDiffQty() != null && l.getDiffQty().signum() != 0).toList();
        if (diffs.stream().anyMatch(l -> overTol(l.getBookQty(), l.getDiffQty(), h.getTolPct()))) {
            if (!permissions.actions(PROG).contains(Action.APPROVE)) {
                throw new BusinessException(HbErrorCode.WMS_COUNT_APPROVAL);
            }
        }
        FieldErrors e = FieldErrors.create();
        if (opening) {
            diffs.stream().filter(l -> l.getDiffQty().signum() < 0)
                    .forEach(l -> e.row(String.valueOf(l.getLineId())).add("countedQty", "OPENING_BELOW_BOOK"));
        }
        e.throwIfAny();
        // 원장 → 줄에 원장 id(머리가 REVIEW 일 때만 트리거가 허용) → 머리 POSTED. 머리 행은 위에서 잠가 두었다
        if (!diffs.isEmpty()) {
            List<TrxReq> rows = diffs.stream().map(l -> new TrxReq(opening ? "OPENING" : "COUNT_ADJ", l.getItemId(), l.getNodeId(),
                    l.getStockStatus(), l.getDiffQty(), null, "COUNT", l.getLineId(), null, h.getCountNo(),
                    l.getCountedAt().withOffsetSameInstant(ZoneOffset.UTC), "KEY", null, l.getLotId())).toList();
            PostResult r = inventory.post(rows, null);
            for (int i = 0; i < diffs.size(); i++) {
                mapper.linkTrx(diffs.get(i).getLineId(), r.trxIds().get(i), me.loginId());
            }
        }
        if (mapper.markPosted(id, me.loginId()) != 1) {
            throw new BusinessException(HbErrorCode.WMS_COUNT_STATE);
        }
        return view(id);
    }

    @Transactional
    public CountView cancel(long id, CancelForm f) {
        LoginUser me = me();
        CountHead h = lockAny(id, f == null ? null : f.rowVer());
        if ("POSTED".equals(h.getStatus()) || "CANCELLED".equals(h.getStatus())) {
            throw new BusinessException(HbErrorCode.WMS_COUNT_STATE);
        }
        String reason = f.reason() == null ? "" : f.reason().strip();
        if (reason.length() < 2) {
            throw fieldError("reason", "REQUIRED");
        }
        if (reason.length() > 500) {
            throw fieldError("reason", "TOO_LONG", 500);
        }
        mapper.markCancelled(id, reason, me.loginId());
        return view(id);
    }

    // ================================================================== 공통

    /** 머리 잠금 + rowVer + 상태 */
    private CountHead lock(long id, Integer rowVer, String status) {
        CountHead h = lockAny(id, rowVer);
        if (!status.equals(h.getStatus())) {
            throw new BusinessException(HbErrorCode.WMS_COUNT_STATE);
        }
        return h;
    }

    private CountHead lockAny(long id, Integer rowVer) {
        if (rowVer == null) {
            throw fieldError("rowVer", "REQUIRED");
        }
        CountHead h = mapper.lockCount(id);
        if (h == null) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        if (h.getRowVer() != rowVer) {
            throw new BusinessException(HbErrorCode.CONFLICT);
        }
        return h;
    }

    private String plant(String plantCd) {
        if (plantCd == null || plantCd.isBlank()) {
            throw fieldError("plantCd", "REQUIRED");
        }
        String p = plantCd.strip().toUpperCase(Locale.ROOT);
        if (p.length() > 20 || !mapper.plantExists(p)) {
            throw fieldError("plantCd", "NOT_FOUND");
        }
        return p;
    }

    private static BigDecimal qty(String v, FieldErrors.RowScope row) {
        BigDecimal q;
        try {
            q = new BigDecimal(v.strip());
        } catch (NumberFormatException ex) {
            row.add("countedQty", "FORMAT");
            return null;
        }
        if (q.signum() < 0) {
            row.add("countedQty", "NOT_NEGATIVE");
        } else if (q.stripTrailingZeros().scale() > 6) {
            row.add("countedQty", "SCALE", 6);
        } else if (q.compareTo(LIMIT) >= 0) {
            row.add("countedQty", "INT_DIGITS", 12);
        } else {
            return q;
        }
        return null;
    }

    /** 공장 현지 시각 → 시각. 비우면 지금. 미래(5분 넘게)·DST 없는 시각 거부. 기초 재고가 아니면 시작 시각보다 앞일 수 없다 */
    private OffsetDateTime countedAt(String local, CountHead h, Instant now, FieldErrors.RowScope row) {
        Instant at;
        if (local == null || local.isBlank()) {
            at = now;
        } else {
            LocalDateTime ldt;
            try {
                ldt = LocalDateTime.parse(local.strip());
            } catch (DateTimeParseException ex) {
                row.add("countedAt", "FORMAT");
                return null;
            }
            at = BizClock.toInstant(ldt, clock.zone(h.getPlantCd()));
            if (at == null) {
                row.add("countedAt", "DST_GAP");
                return null;
            }
        }
        if (at.isAfter(now.plus(FUTURE_TOLERANCE))) {
            row.add("countedAt", "FUTURE");
            return null;
        }
        if (!"OPENING".equals(h.getCountType()) && h.getStartedAt() != null && at.isBefore(h.getStartedAt().toInstant())) {
            row.add("countedAt", "BEFORE_START");
            return null;
        }
        return OffsetDateTime.ofInstant(at, ZoneOffset.UTC);
    }

    /** 차이율 % (장부 0 이면 null — 한도 검사에서는 항상 넘음) */
    static BigDecimal pct(BigDecimal book, BigDecimal diff) {
        if (book == null || diff == null || book.signum() == 0) {
            return null;
        }
        return diff.abs().multiply(BigDecimal.valueOf(100)).divide(book.abs(), 2, RoundingMode.HALF_UP);
    }

    static boolean overTol(BigDecimal book, BigDecimal diff, BigDecimal tol) {
        if (diff == null || diff.signum() == 0) {
            return false;
        }
        if (book == null || book.signum() == 0) {
            return true;
        }
        // |diff| × 100 > tol × |book| — 반올림 없이 비교
        return diff.abs().multiply(BigDecimal.valueOf(100)).compareTo(tol.multiply(book.abs())) > 0;
    }

    private static String remark(String r, String field, FieldErrors e) {
        if (r == null || r.isBlank()) {
            return null;
        }
        String s = r.strip();
        if (s.length() > 500) {
            e.add(field, "TOO_LONG", 500);
        }
        return s;
    }

    static String text(BigDecimal v) {
        return v == null ? null : v.signum() == 0 ? "0" : v.stripTrailingZeros().toPlainString();
    }

    /** 로그인 사용자, 없으면 요청 범위의 행위자(창고 PDA 단말 — 센 사람 칸에 단말이 남는다, docs/30 12.12) */
    static LoginUser me() {
        return InventoryService.me();
    }

    static BusinessException fieldError(String field, String code, Object... args) {
        return new BusinessException(HbErrorCode.INVALID_VALUE).withFieldErrors(FieldErrors.create().add(field, code, args).list());
    }
}
