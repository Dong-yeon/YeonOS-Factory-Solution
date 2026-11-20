package io.smarthappy.module.qms;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.smarthappy.module.production.link.LotLookup;
import io.smarthappy.module.production.link.LotLookup.LotOrigin;
import io.smarthappy.module.production.link.LotLookup.LotView;
import io.smarthappy.module.production.link.LotTraceQuery;
import io.smarthappy.module.production.link.LotTraceQuery.TraceResult;
import io.smarthappy.module.production.link.LotTraceQuery.TraceRow;
import io.smarthappy.module.qms.LotTraceDtos.CustSum;
import io.smarthappy.module.qms.LotTraceDtos.LotQms;
import io.smarthappy.module.qms.LotTraceDtos.OriginLine;
import io.smarthappy.module.qms.LotTraceDtos.RootLot;
import io.smarthappy.module.qms.LotTraceDtos.ShipLine;
import io.smarthappy.module.qms.LotTraceDtos.StockLine;
import io.smarthappy.module.qms.LotTraceDtos.SupplierSum;
import io.smarthappy.module.qms.LotTraceDtos.TraceForm;
import io.smarthappy.module.qms.LotTraceDtos.TraceView;
import io.smarthappy.module.qms.LotTraceDtos.UnitQty;
import io.smarthappy.module.wms.link.ShipQuery;
import io.smarthappy.module.wms.link.ShipQuery.LotShipment;
import io.smarthappy.module.wms.link.ShipQuery.LotStockRow;
import io.smarthappy.platform.common.web.BusinessException;
import io.smarthappy.platform.common.web.FieldErrors;
import io.smarthappy.platform.common.web.HbErrorCode;
import io.smarthappy.platform.excel.ExcelExportService;
import io.smarthappy.platform.excel.ExcelExportService.ExportJob;
import io.smarthappy.platform.grid.ColumnSpec;
import io.smarthappy.platform.time.BizClock;
import jakarta.servlet.http.HttpServletResponse;

/**
 * 출하 추적(리콜) 화면 qms-lot-trace — docs/57 2.1·2.4. 문제 LOT → 계보로 이어진 아래 LOT(MES LotTraceQuery.forward) → 어느 고객에게 어느 출하로
 * 얼마나 나갔나(WMS ShipQuery.byLots)·아직 어디에 있나(WMS ShipQuery.lotStock). 읽기만, 한 트랜잭션, 잠금 없음, 다른 모듈 표를 직접 읽지 않는다.
 * - 잘림은 숨기지 않는다: 계보가 깊이·줄 상한에 걸리면 truncated(화면 노란 줄)
 * - 같은 LOT 이 여러 뿌리·경로로 나오면 출하·재고는 LOT 기준 한 번만 조회하고, 출하 줄은 뿌리별로 다시 묶는다(경로 = 가장 얕은 것)
 * - MES·WMS 가 없는 설치본은 각 계약의 NONE — 빈 결과
 * - LOT 마다 이미 있는 QMS 일(열린 NCR·걸린 봉쇄 — 37장)을 함께 준다: QMS 표만(QmsLotWorkMapper), 500개씩
 * - 역추적(BACKWARD, docs/29_P3 32장): 클레임 LOT → 원인 LOT(LotTraceQuery.backward) → 출처·공급사(LotLookup.origins)·아직 남은 원인 LOT(lotStock). 출하는 보지 않는다
 */
@Service
public class LotTraceService {

    static final String PROG = "qms-lot-trace";
    /** 한 번에 넣을 수 있는 LOT 번호 수 */
    static final int MAX_INPUT = 100;
    static final int DEFAULT_DEPTH = LotTraceQuery.MAX_DEPTH;
    static final String FORWARD = "FORWARD";
    static final String BACKWARD = "BACKWARD";

    static final List<ColumnSpec> EXPORT_COLUMNS = List.of(
            ColumnSpec.text("rootLotNo", "qms.lotTrace.col.root", 140),
            ColumnSpec.text("lotNo", "qms.lotTrace.col.lot", 140),
            ColumnSpec.text("itemCd", "qms.lotTrace.col.itemCd", 120),
            ColumnSpec.text("itemNm", "qms.lotTrace.col.itemNm", 180),
            ColumnSpec.of("depth", "qms.lotTrace.col.depth", ColumnSpec.ColumnType.INT, 60),
            ColumnSpec.text("path", "qms.lotTrace.col.path", 260),
            ColumnSpec.text("custCd", "qms.lotTrace.col.custCd", 100),
            ColumnSpec.text("custNm", "qms.lotTrace.col.custNm", 160),
            ColumnSpec.text("shipNo", "qms.lotTrace.col.shipNo", 130),
            ColumnSpec.text("plantCd", "qms.lotTrace.col.plant", 70),
            ColumnSpec.of("shippedAt", "qms.lotTrace.col.shippedAt", ColumnSpec.ColumnType.DATETIME, 140),
            ColumnSpec.dec("qty", "qms.lotTrace.col.qty", 6, 100).withMinScale(0),
            ColumnSpec.text("unitCd", "qms.lotTrace.col.unit", 60),
            ColumnSpec.text("pallets", "qms.lotTrace.col.pallets", 160),
            ColumnSpec.text("ssccs", "qms.lotTrace.col.ssccs", 180));

    /** 역추적 엑셀 = 원인 LOT 목록(공급사 연락·입고 검사 이력 확인용) */
    static final List<ColumnSpec> BACKWARD_COLUMNS = List.of(
            ColumnSpec.text("rootLotNo", "qms.lotTrace.col.root", 140),
            ColumnSpec.text("lotNo", "qms.lotTrace.col.originLot", 140),
            ColumnSpec.text("itemCd", "qms.lotTrace.col.itemCd", 120),
            ColumnSpec.text("itemNm", "qms.lotTrace.col.itemNm", 180),
            ColumnSpec.of("depth", "qms.lotTrace.col.depth", ColumnSpec.ColumnType.INT, 60),
            ColumnSpec.text("path", "qms.lotTrace.col.path", 260),
            ColumnSpec.code("relType", "qms.lotTrace.col.relType", "LOT_REL_TYPE", 90),
            ColumnSpec.code("origin", "qms.lotTrace.col.origin", "LOT_ORIGIN", 90),
            ColumnSpec.text("supplierCd", "qms.lotTrace.col.supplierCd", 100),
            ColumnSpec.text("supplierNm", "qms.lotTrace.col.supplierNm", 160),
            ColumnSpec.text("supplierLotNo", "qms.lotTrace.col.supplierLotNo", 140));

    private final ObjectProvider<LotLookup> lotLookup;
    private final ObjectProvider<LotTraceQuery> traceQuery;
    private final ObjectProvider<ShipQuery> shipQuery;
    private final ExcelExportService excel;
    private final BizClock clock;
    private final QmsLotWorkMapper mapper;

    public LotTraceService(ObjectProvider<LotLookup> lotLookup, ObjectProvider<LotTraceQuery> traceQuery, ObjectProvider<ShipQuery> shipQuery,
            ExcelExportService excel, BizClock clock, QmsLotWorkMapper mapper) {
        this.lotLookup = lotLookup;
        this.traceQuery = traceQuery;
        this.shipQuery = shipQuery;
        this.excel = excel;
        this.clock = clock;
        this.mapper = mapper;
    }

    @Transactional(readOnly = true)
    public TraceView trace(TraceForm in) {
        Input q = validate(in);
        LotLookup lots = LotLookup.of(lotLookup);
        Map<Long, LotView> roots = new LinkedHashMap<>();
        List<String> unknown = new ArrayList<>();
        for (String no : q.lotNos()) {
            lots.byNo(no).ifPresentOrElse(v -> roots.putIfAbsent(v.lotId(), v), () -> unknown.add(no));
        }
        boolean back = BACKWARD.equals(q.direction());
        if (roots.isEmpty()) {
            return new TraceView(q.direction(), q.depth(), List.of(), unknown, 0, false, List.of(), List.of(), List.of(), List.of(), List.of(),
                    List.of());
        }
        LotTraceQuery tq = LotTraceQuery.of(traceQuery);
        TraceResult tr = back ? tq.backward(roots.keySet(), q.depth()) : tq.forward(roots.keySet(), q.depth());
        // 계보 줄이 없으면(MES 없음) 뿌리 자신만
        List<TraceRow> rows = tr.rows().isEmpty()
                ? roots.keySet().stream().map(id -> new TraceRow(id, id, 0, null, null, false)).toList() : tr.rows();
        Set<Long> ids = new LinkedHashSet<>();
        rows.forEach(r -> ids.add(r.lotId()));
        List<Long> idList = List.copyOf(ids);
        Map<Long, LotView> info = new HashMap<>(roots);
        chunks(idList, LotLookup.MAX_IDS).forEach(c -> lots.lots(c).forEach(v -> info.put(v.lotId(), v)));
        ShipQuery ship = ShipQuery.of(shipQuery);
        List<LotShipment> shipped = new ArrayList<>();
        List<LotStockRow> stocked = new ArrayList<>();
        for (List<Long> c : chunks(idList, ShipQuery.LOTS_MAX)) {
            if (!back) {
                shipped.addAll(ship.byLots(c));
            }
            stocked.addAll(ship.lotStock(c));
        }

        // (뿌리, LOT) → 가장 얕은 줄 — 경로·깊이
        Map<Long, Map<Long, TraceRow>> reach = new LinkedHashMap<>();
        for (TraceRow r : rows) {
            reach.computeIfAbsent(r.rootLotId(), k -> new LinkedHashMap<>()).merge(r.lotId(), r, (a, b) -> a.depth() <= b.depth() ? a : b);
        }
        java.time.ZoneId zone = clock.zone();
        List<StockLine> stock = stocked.stream().map(s -> {
            LotView v = info.get(s.lotId());
            return new StockLine(v == null ? String.valueOf(s.lotId()) : v.lotNo(), v == null ? null : v.itemCd(), v == null ? null : v.itemNm(),
                    s.plantCd(), s.path(), s.locUsage(), s.stockStatus(), s.qty(),
                    v == null || v.occurredAt() == null ? null : v.occurredAt().atZoneSameInstant(zone).toLocalDate());
        }).toList();
        List<RootLot> rootList = roots.values().stream().map(v -> new RootLot(v.lotId(), v.lotNo(), v.itemCd(), v.itemNm())).toList();
        List<LotQms> qms = qms(info, idList);
        if (back) {
            Map<Long, LotOrigin> origin = new HashMap<>();
            chunks(idList, LotLookup.MAX_IDS).forEach(c -> lots.origins(c).forEach(o -> origin.put(o.lotId(), o)));
            List<OriginLine> lines = originLines(info, origin, rows, reach);
            return new TraceView(q.direction(), q.depth(), rootList, unknown, ids.size(), tr.truncated(), List.of(), List.of(), lines,
                    suppliers(info, origin, idList), stock, qms);
        }
        Map<Long, List<LotShipment>> shipByLot = new HashMap<>();
        shipped.forEach(s -> shipByLot.computeIfAbsent(s.lotId(), k -> new ArrayList<>()).add(s));
        List<ShipLine> lines = new ArrayList<>();
        reach.forEach((root, byLot) -> byLot.forEach((lotId, r) -> {
            for (LotShipment s : shipByLot.getOrDefault(lotId, List.of())) {
                lines.add(line(info, byLot, root, r, s));
            }
        }));
        lines.sort(Comparator.comparing(ShipLine::getRootLotNo).thenComparing(ShipLine::getDepth).thenComparing(ShipLine::getLotNo)
                .thenComparing(l -> l.getShippedAt() == null ? "" : l.getShippedAt().toString()).thenComparing(ShipLine::getShipNo));
        return new TraceView(q.direction(), q.depth(), rootList, unknown, ids.size(), tr.truncated(), customers(shipped), lines, List.of(), List.of(),
                stock, qms);
    }

    /** 닿은 LOT 의 열린 NCR·걸린 봉쇄(37장) — 500개씩, 일이 있는 LOT 만, LOT 번호 순 */
    List<LotQms> qms(Map<Long, LotView> info, List<Long> ids) {
        Map<Long, List<String>> ncr = new LinkedHashMap<>();
        Map<Long, List<String>> contain = new LinkedHashMap<>();
        for (List<Long> c : chunks(ids, LotLookup.MAX_IDS)) {
            for (QmsLotWorkMapper.LotWork w : mapper.findOpenWork(c)) {
                (("NCR".equals(w.kind())) ? ncr : contain).computeIfAbsent(w.lotId(), k -> new ArrayList<>()).add(w.no());
            }
        }
        Set<Long> lots = new LinkedHashSet<>(ncr.keySet());
        lots.addAll(contain.keySet());
        return lots.stream().map(id -> new LotQms(no(info, id), ncr.getOrDefault(id, List.of()), contain.getOrDefault(id, List.of())))
                .sorted(Comparator.comparing(LotQms::lotNo)).toList();
    }

    /** 엑셀(EXCEL 권한) — 같은 조회를 다시 해 정방향은 출하 줄(고객 연락용), 역추적은 원인 LOT 줄(공급사 연락용)만 쓴다 */
    @Transactional(readOnly = true)
    public long export(TraceForm in, HttpServletResponse response) throws IOException {
        TraceView v = trace(in);
        List<String> params = List.of("lotNos", "depth", "direction");
        if (BACKWARD.equals(v.direction())) {
            List<OriginLine> lines = v.origins();
            return excel.export(response, new ExportJob(PROG, "prog.qmsLotTrace", BACKWARD_COLUMNS, lines.size(), params), lines::forEach);
        }
        List<ShipLine> lines = v.shipments();
        return excel.export(response, new ExportJob(PROG, "prog.qmsLotTrace", EXPORT_COLUMNS, lines.size(), params), lines::forEach);
    }

    /**
     * 원인 LOT 줄 — 뿌리마다 닿은 LOT 한 줄(가장 얕은 줄의 깊이·경로·연결 종류). leaf = 같은 뿌리의 어느 줄도 이 LOT 을 거쳐 더 올라가지 않음.
     * 순서 = 뿌리 번호 → 깊이 → LOT 번호
     */
    static List<OriginLine> originLines(Map<Long, LotView> info, Map<Long, LotOrigin> origin, List<TraceRow> rows,
            Map<Long, Map<Long, TraceRow>> reach) {
        Map<Long, Set<Long>> passed = new HashMap<>();
        rows.forEach(r -> {
            if (r.viaLotId() != null) {
                passed.computeIfAbsent(r.rootLotId(), k -> new java.util.HashSet<>()).add(r.viaLotId());
            }
        });
        List<OriginLine> out = new ArrayList<>();
        reach.forEach((root, byLot) -> byLot.forEach((lotId, r) -> {
            LotView v = info.get(lotId);
            LotOrigin o = origin.get(lotId);
            OriginLine l = new OriginLine();
            l.setRootLotNo(no(info, root));
            l.setLotNo(no(info, lotId));
            l.setItemCd(v == null ? null : v.itemCd());
            l.setItemNm(v == null ? null : v.itemNm());
            l.setDepth(r.depth());
            l.setPath(path(info, byLot, r, " ← "));
            l.setRelType(r.relType());
            if (o != null) {
                l.setOrigin(o.origin());
                l.setSupplierCd(o.supplierCd());
                l.setSupplierNm(o.supplierNm());
                l.setSupplierLotNo(o.supplierLotNo());
            }
            l.setLeaf(!passed.getOrDefault(root, Set.of()).contains(lotId));
            out.add(l);
        }));
        out.sort(Comparator.comparing(OriginLine::getRootLotNo).thenComparing(OriginLine::getDepth).thenComparing(OriginLine::getLotNo));
        return out;
    }

    /** 공급사별 — 닿은 LOT 중 입고(RECEIVED)만, LOT 기준 한 번(여러 뿌리에서 닿아도). 공급사 없는 입고는 한 줄로 맨 뒤 */
    static List<SupplierSum> suppliers(Map<Long, LotView> info, Map<Long, LotOrigin> origin, List<Long> ids) {
        Map<Long, List<LotOrigin>> bySup = new LinkedHashMap<>();
        for (Long id : ids) {
            LotOrigin o = origin.get(id);
            if (o != null && "RECEIVED".equals(o.origin())) {
                bySup.computeIfAbsent(o.supplierCustId(), k -> new ArrayList<>()).add(o);
            }
        }
        List<SupplierSum> out = new ArrayList<>();
        bySup.forEach((sup, list) -> out.add(new SupplierSum(sup, list.get(0).supplierCd(), list.get(0).supplierNm(), list.size(),
                list.stream().map(o -> no(info, o.lotId())).sorted().toList(),
                list.stream().map(LotOrigin::supplierLotNo).filter(x -> x != null).distinct().sorted().toList())));
        out.sort(Comparator.comparing(SupplierSum::supplierCd, Comparator.nullsLast(Comparator.naturalOrder())));
        return out;
    }

    private static ShipLine line(Map<Long, LotView> info, Map<Long, TraceRow> byLot, long root, TraceRow r, LotShipment s) {
        LotView v = info.get(s.lotId());
        ShipLine l = new ShipLine();
        l.setRootLotNo(no(info, root));
        l.setLotNo(no(info, s.lotId()));
        l.setItemCd(v == null ? null : v.itemCd());
        l.setItemNm(v == null ? null : v.itemNm());
        l.setDepth(r.depth());
        l.setPath(path(info, byLot, r, " → "));
        l.setCustId(s.custId());
        l.setCustCd(s.custCd());
        l.setCustNm(s.custNm());
        l.setShipNo(s.shipNo());
        l.setPlantCd(s.plantCd());
        l.setShippedAt(s.shippedAt());
        l.setQty(s.qty());
        l.setUnitCd(s.unitCd());
        l.setPallets(String.join(", ", s.palletNos()));
        l.setSsccs(String.join(", ", s.ssccs()));
        return l;
    }

    /** 뿌리 sep … sep 이 LOT (via 를 거슬러, 같은 뿌리의 가장 얕은 줄로). 경로가 끊기면 거기까지. sep = 정방향 " → ", 역추적 " ← " */
    static String path(Map<Long, LotView> info, Map<Long, TraceRow> byLot, TraceRow r, String sep) {
        List<String> nos = new ArrayList<>();
        TraceRow cur = r;
        Set<Long> seen = new java.util.HashSet<>();
        while (cur != null && seen.add(cur.lotId())) {
            nos.add(0, no(info, cur.lotId()));
            cur = cur.viaLotId() == null ? null : byLot.get(cur.viaLotId());
        }
        return String.join(sep, nos);
    }

    private static String no(Map<Long, LotView> info, long lotId) {
        LotView v = info.get(lotId);
        return v == null ? String.valueOf(lotId) : v.lotNo();
    }

    /** 고객별 — 출하 수·관련 LOT 수·단위별 순 수량, 고객 코드 순 */
    static List<CustSum> customers(List<LotShipment> shipped) {
        Map<Long, List<LotShipment>> byCust = new LinkedHashMap<>();
        shipped.forEach(s -> byCust.computeIfAbsent(s.custId(), k -> new ArrayList<>()).add(s));
        List<CustSum> out = new ArrayList<>();
        byCust.forEach((cust, list) -> {
            Map<String, BigDecimal> qty = new TreeMap<>();
            list.forEach(s -> qty.merge(s.unitCd(), s.qty(), BigDecimal::add));
            out.add(new CustSum(cust, list.get(0).custCd(), list.get(0).custNm(), (int) list.stream().map(LotShipment::shipId).distinct().count(),
                    (int) list.stream().map(LotShipment::lotId).distinct().count(),
                    qty.entrySet().stream().map(e -> new UnitQty(e.getKey(), e.getValue())).toList()));
        });
        out.sort(Comparator.comparing(CustSum::custCd, Comparator.nullsLast(Comparator.naturalOrder())));
        return out;
    }

    static <T> List<List<T>> chunks(List<T> all, int size) {
        List<List<T>> out = new ArrayList<>();
        for (int i = 0; i < all.size(); i += size) {
            out.add(all.subList(i, Math.min(all.size(), i + size)));
        }
        return out;
    }

    record Input(List<String> lotNos, int depth, String direction) {
    }

    /** LOT 번호: 앞뒤 공백 빼고 빈 것·중복 무시, 1~100개, 한 개 60자 이하 / 깊이 0~10(비우면 10) / 방향 FORWARD·BACKWARD(비우면 FORWARD) */
    static Input validate(TraceForm in) {
        if (in == null) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        FieldErrors e = FieldErrors.create();
        List<String> nos = in.lotNos() == null ? List.of()
                : in.lotNos().stream().filter(x -> x != null && !x.isBlank()).map(String::strip).distinct().toList();
        if (nos.isEmpty()) {
            e.add("lotNos", "REQUIRED");
        } else if (nos.size() > MAX_INPUT) {
            e.add("lotNos", "TOO_MANY_ITEMS", MAX_INPUT);
        } else if (nos.stream().anyMatch(x -> x.length() > 60)) {
            e.add("lotNos", "TOO_LONG", 60);
        }
        int depth = in.depth() == null ? DEFAULT_DEPTH : in.depth();
        if (depth < 0 || depth > LotTraceQuery.MAX_DEPTH) {
            e.add("depth", "RANGE", 0, LotTraceQuery.MAX_DEPTH);
        }
        String dir = in.direction() == null || in.direction().isBlank() ? FORWARD : in.direction();
        if (!FORWARD.equals(dir) && !BACKWARD.equals(dir)) {
            e.add("direction", "CODE");
        }
        e.throwIfAny();
        return new Input(nos, depth, dir);
    }
}
