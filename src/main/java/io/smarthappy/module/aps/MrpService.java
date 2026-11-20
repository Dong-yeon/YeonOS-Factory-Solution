package io.smarthappy.module.aps;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import io.smarthappy.module.aps.ApsPrDtos.SourceSrc;
import io.smarthappy.module.aps.MrpDtos.ElemInsert;
import io.smarthappy.module.aps.MrpDtos.ElemRow;
import io.smarthappy.module.aps.MrpDtos.ExcInsert;
import io.smarthappy.module.aps.MrpDtos.ExcRow;
import io.smarthappy.module.aps.MrpDtos.ItemInsert;
import io.smarthappy.module.aps.MrpDtos.ItemList;
import io.smarthappy.module.aps.MrpDtos.ItemRow;
import io.smarthappy.module.aps.MrpDtos.ItemSrc;
import io.smarthappy.module.aps.MrpDtos.ListLine;
import io.smarthappy.module.aps.MrpDtos.MpsCellSrc;
import io.smarthappy.module.aps.MrpDtos.MrpDetail;
import io.smarthappy.module.aps.MrpDtos.MrpForm;
import io.smarthappy.module.aps.MrpDtos.MrpInsert;
import io.smarthappy.module.aps.MrpDtos.MrpList;
import io.smarthappy.module.aps.MrpDtos.MrpRow;
import io.smarthappy.module.aps.MrpDtos.OrderList;
import io.smarthappy.module.aps.MrpDtos.ParamSrc;
import io.smarthappy.module.aps.MrpDtos.PegChain;
import io.smarthappy.module.aps.MrpDtos.PegLink;
import io.smarthappy.module.aps.MrpDtos.PegNode;
import io.smarthappy.module.aps.MrpDtos.PegRow;
import io.smarthappy.module.aps.MrpDtos.WoSrc;
import io.smarthappy.module.aps.MrpEngine.Child;
import io.smarthappy.module.aps.MrpEngine.Demand;
import io.smarthappy.module.aps.MrpEngine.Supply;
import io.smarthappy.module.production.link.WoMaterialQuery;
import io.smarthappy.module.sales.OrderService;
import io.smarthappy.module.sales.link.ForecastQuery;
import io.smarthappy.module.sales.link.OrderLineQuery;
import io.smarthappy.module.srm.link.PoSupplyQuery;
import io.smarthappy.module.wms.link.StockQuery;
import io.smarthappy.platform.common.text.VisibleText;
import io.smarthappy.platform.common.web.BusinessException;
import io.smarthappy.platform.common.web.FieldErrors;
import io.smarthappy.platform.common.web.HbErrorCode;
import io.smarthappy.platform.messaging.InboxService;
import io.smarthappy.platform.numbering.NumberingService;
import io.smarthappy.platform.policy.Policies;
import io.smarthappy.platform.policy.PolicyService;
import io.smarthappy.platform.security.CurrentUser;
import io.smarthappy.platform.security.LoginUser;
import io.smarthappy.platform.time.BizClock;
import lombok.RequiredArgsConstructor;

/**
 * MRP 자재소요계획 (aps-mrp) — APS 10단계, docs/38 19장.
 *
 * - 계산 = 한 트랜잭션(반복 읽기): 현행 확정 MPS·열린 지시·기준정보 → 품목 훑기(첫날 BOM, 저차 코드) → {@link MrpEngine} → 새 실행 기록
 * - BOM 전개는 MES 계약 {@link WoMaterialQuery#explode}(지시 발행과 같은 전개) 하나. 재고·발주·수주·내시도 각 모듈 계약으로 읽기만
 * - 계획 주문은 제안 — 지시·구매 요청을 만들지 않는다(AP-4, A3 에서 사람이 골라)
 */
@Service
@RequiredArgsConstructor
public class MrpService {

    static final String PROG = "aps-mrp";
    static final String RULE = "APS_MRP";
    static final int MIN_DAYS = 7;
    static final int MAX_DAYS = 92;
    static final int DEFAULT_DAYS = 28;
    static final int MAX_ITEMS = 2000;
    static final int MAX_ELEMS = 50000;
    static final int MAX_ORDERS = 10000;
    static final int MAX_RESTARTS = 10;
    static final int LIST_LIMIT = 500;
    static final int LIST_DEFAULT_DAYS = 30;
    static final int MAX_PERIOD_DAYS = 366;
    static final int EXC_LIMIT = 2000;
    static final int ORDER_LIMIT = 5000;
    static final int PEG_NODES = 500;
    /** 예측 소진에 쓰는 수주 납기 상한 = 마지막 날 + 한 달(MPS 와 같음) */
    private static final int ORDER_TAIL_DAYS = 31;
    private static final int CHUNK = 500;

    private final MrpMapper mapper;
    private final MpsMapper mpsMapper;
    private final ApsPrMapper prMapper;
    private final NumberingService numbering;
    private final InboxService inbox;
    private final BizClock clock;
    private final PolicyService policies;
    private final OrderService orders;
    private final ObjectProvider<WoMaterialQuery> woQueries;
    private final ObjectProvider<StockQuery> stockQueries;
    private final ObjectProvider<PoSupplyQuery> poQueries;
    private final ObjectProvider<OrderLineQuery> orderQueries;
    private final ObjectProvider<ForecastQuery> forecastQueries;

    // ------------------------------------------------------------------ 계산

    @Transactional(isolation = Isolation.REPEATABLE_READ)
    public long calculate(MrpForm in) {
        LoginUser me = me();
        inbox.claimRequestKey("APS_MRP_CALC");
        return calculate(in, me.loginId());
    }

    /** 계산 본체 — 회사 범위·트랜잭션은 호출자 */
    long calculate(MrpForm in, String by) {
        if (in == null) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        FieldErrors e = FieldErrors.create();
        String plant = null;
        if (VisibleText.isBlank(in.plantCd())) {
            e.add("plantCd", "REQUIRED");
        } else {
            plant = in.plantCd().strip().toUpperCase(Locale.ROOT);
        }
        int days = in.horizonDays() == null ? DEFAULT_DAYS : in.horizonDays();
        if (days < MIN_DAYS || days > MAX_DAYS) {
            e.add("horizonDays", "RANGE", MIN_DAYS, MAX_DAYS);
        }
        String nm = OrderService.text(in.mrpNm(), "mrpNm", 100, e);
        e.throwIfAny();
        if (plant.length() > 20 || !"Y".equals(mpsMapper.plantUseYn(plant))) {
            throw OrderService.fieldError("plantCd", "NO_SUCH_CODE");
        }
        LocalDate today = LocalDate.ofInstant(clock.instant(), clock.zone(plant));
        Calc c = new Calc(plant, today, days, Integer.parseInt(policies.value(Policies.APS_MRP_OUT_DAYS, plant)));
        MrpEngine.Result r = c.run();

        String no = numbering.nextOrDefault(RULE, "자재소요계획 번호", "MRP{yyMMdd}-{seq:3}", "DAY");
        List<ExcInsert> excs = new ArrayList<>();
        c.preExc.forEach(x -> excs.add(exc(x)));
        r.excs().forEach(x -> excs.add(exc(x)));
        long mrpId = mapper.insertMrp(new MrpInsert(no, plant, nm, today, days, c.mpsId, c.outDays, c.items.size(), (int) r.orderCount(),
                excs.size()), by);
        List<ItemInsert> items = new ArrayList<>();
        for (MrpEngine.Item it : c.items.values()) {
            SourceSrc src = c.sources.get(it.itemId());
            String leadSrc = it.leadDays() == null ? "NONE" : it.buy() ? "SOURCE" : "PARAM";
            items.add(new ItemInsert(it.itemId(), c.llc.get(it.itemId()), it.procType(), c.info.get(it.itemId()).getUnitCd(),
                    it.mps() ? "Y" : "N", it.stock(), it.safety(), it.lotRule(), it.lotQty(), it.lotMult(), it.moq(), it.orderMult(),
                    it.leadDays() == null ? 0 : it.leadDays(), leadSrc, it.buy() && src != null ? src.getCustId() : null));
        }
        chunks(items, rows -> mapper.insertItems(mrpId, rows));
        chunks(r.elems().stream().map(MrpService::elem).toList(), rows -> mapper.insertElems(mrpId, rows));
        chunks(r.pegs(), rows -> mapper.insertPegs(mrpId, rows));
        chunks(excs, rows -> mapper.insertExcs(mrpId, rows));
        return mrpId;
    }

    /** 한 번의 계산 — 입력 모으기·품목 훑기·저차 코드·다시 계산 */
    private final class Calc {
        final String plant;
        final LocalDate first;
        final int days;
        final LocalDate last;
        final int outDays;
        final WoMaterialQuery wq = WoMaterialQuery.of(woQueries);
        Long mpsId;
        final Set<Long> mpsItems = new LinkedHashSet<>();
        final List<MrpEngine.MpsPlan> mpsPlans = new ArrayList<>();
        final List<Demand> demands = new ArrayList<>();
        final List<Supply> supplies = new ArrayList<>();
        final List<MrpEngine.Exc> preExc = new ArrayList<>();
        /** 알게 된 품목(훑기 순) → 품목 정보 */
        final Map<Long, ItemSrc> info = new LinkedHashMap<>();
        final Map<Long, Set<Long>> edges = new HashMap<>();
        final Map<String, List<Child>> cache = new HashMap<>();
        final Map<Long, BigDecimal> stock = new HashMap<>();
        final Map<Long, ParamSrc> params = new HashMap<>();
        final Map<Long, SourceSrc> sources = new HashMap<>();
        final Set<Long> loaded = new java.util.HashSet<>();
        final Map<String, Integer> scales = orders.unitScales();
        Map<Long, Integer> llc;
        final Map<Long, MrpEngine.Item> items = new LinkedHashMap<>();

        Calc(String plant, LocalDate today, int days, int outDays) {
            this.plant = plant;
            this.first = today;
            this.days = days;
            this.last = today.plusDays(days - 1L);
            this.outDays = outDays;
        }

        MrpEngine.Result run() {
            Set<Long> roots = new LinkedHashSet<>();
            // 현행 확정 MPS — 독립 수요(R2·R3)
            MpsDtos.MpsRow cur = mpsMapper.findCurrent(plant);
            if (cur != null) {
                mpsId = cur.getMpsId();
                for (MpsCellSrc m : mapper.findMpsCells(cur.getMpsId())) {
                    mpsItems.add(m.getItemId());
                    mpsPlans.add(new MrpEngine.MpsPlan(m.getItemId(), m.getBucketDate(), m.getPlanQty(), m.getMpsCellId()));
                }
                roots.addAll(mpsItems);
            }
            // 열린 지시 — 공급(남은 수량, 계획 완료일) + 자재 남은 소요(계획 시작일)
            List<WoSrc> wos = mapper.findOpenWos(plant);
            Map<Long, WoSrc> woById = new HashMap<>();
            for (WoSrc w : wos) {
                woById.put(w.getWoId(), w);
                if (w.getRemainQty() != null && w.getRemainQty().signum() > 0) {
                    supplies.add(new Supply(w.getItemId(), MrpEngine.WO, w.getPlanEnd(), w.getRemainQty(), w.getWoId(), w.getWoNo()));
                }
            }
            List<Long> woIds = wos.stream().map(WoSrc::getWoId).toList();
            for (int i = 0; i < woIds.size(); i += WoMaterialQuery.MAX_IDS) {
                for (WoMaterialQuery.WoNeeds wn : wq.remaining(woIds.subList(i, Math.min(woIds.size(), i + WoMaterialQuery.MAX_IDS)))) {
                    WoSrc w = woById.get(wn.woId());
                    if (w == null) {
                        continue;
                    }
                    if (!wn.exploded()) {
                        preExc.add(new MrpEngine.Exc(w.getItemId(), "NO_BOM", null, w.getPlanStart(), null, null, w.getWoNo()));
                        continue;
                    }
                    for (WoMaterialQuery.Need n : wn.needs()) {
                        demands.add(new Demand(n.itemId(), MrpEngine.WODEP, w.getPlanStart(), n.remainingQty(), w.getWoId(), w.getWoNo()));
                        roots.add(n.itemId());
                    }
                }
            }
            roots.addAll(mapper.findParamItems(plant));
            discover(roots);
            mpsDemands();
            for (int attempt = 0; ; attempt++) {
                try {
                    llc = MrpEngine.levels(info.keySet(), edges);
                } catch (MrpEngine.Loop ex) {
                    throw loop(ex.itemId);
                }
                load();
                try {
                    return MrpEngine.run(new MrpEngine.Input(first, days, first, outDays, items, llc, mpsPlans, demands, supplies, MAX_ELEMS,
                            MAX_ORDERS), this::explode);
                } catch (MrpEngine.TooLarge ex) {
                    throw tooLarge();
                } catch (MrpEngine.Restart ex) {
                    if (attempt + 1 >= MAX_RESTARTS) {
                        throw loop(ex.unknown.isEmpty() ? ex.edges.keySet().iterator().next() : ex.unknown.iterator().next());
                    }
                    ex.edges.forEach((p, cs) -> edges.computeIfAbsent(p, k -> new LinkedHashSet<>()).addAll(cs));
                    discover(ex.unknown);
                }
            }
        }

        /** 품목 훑기 — 정보를 읽고, 만드는 품목은 첫날 BOM(수량 1) 한 단으로 자품목을 찾는다. 새 품목이 없을 때까지 */
        void discover(Collection<Long> start) {
            Set<Long> frontier = new TreeSet<>(start);
            frontier.removeAll(info.keySet());
            while (!frontier.isEmpty()) {
                List<Long> ids = new ArrayList<>(frontier);
                for (int i = 0; i < ids.size(); i += CHUNK) {
                    mapper.findItems(ids.subList(i, Math.min(ids.size(), i + CHUNK))).forEach(s -> info.put(s.getItemId(), s));
                }
                if (info.size() > MAX_ITEMS) {
                    throw tooLarge();
                }
                Set<Long> next = new TreeSet<>();
                for (Long id : ids) {
                    ItemSrc s = info.get(id);
                    if (s == null || "BUY".equals(s.getProcType())) {
                        continue;
                    }
                    List<Child> cs = explode(id, BigDecimal.ONE, first);
                    if (cs == null) {
                        continue;
                    }
                    for (Child ch : cs) {
                        edges.computeIfAbsent(id, k -> new LinkedHashSet<>()).add(ch.itemId());
                        if (!info.containsKey(ch.itemId())) {
                            next.add(ch.itemId());
                        }
                    }
                }
                frontier = next;
            }
        }

        /** MPS 품목 페깅용 수요(R9) — 수주 남은 수량(납기일) + 소진 뒤 남은 예측(기간 첫날), MPS 계산과 같은 규칙 */
        void mpsDemands() {
            if (mpsItems.isEmpty()) {
                return;
            }
            Map<Long, List<MpsCalculator.Order>> byItemCust = new HashMap<>();
            for (OrderLineQuery.OrderLineView l : OrderLineQuery.of(orderQueries).openLinesByItems(mpsItems, last.plusDays(ORDER_TAIL_DAYS))) {
                BigDecimal rest = l.qty().subtract(l.shippedQty() == null ? BigDecimal.ZERO : l.shippedQty());
                if (rest.signum() <= 0) {
                    continue;
                }
                byItemCust.computeIfAbsent(l.itemId(), k -> new ArrayList<>()).add(new MpsCalculator.Order(l.custId(), l.dueDate(), rest));
                if (!l.dueDate().isAfter(last)) {
                    demands.add(new Demand(l.itemId(), MrpEngine.SO, l.dueDate(), rest, l.lineId(), l.orderNo() + "-" + l.lineNo()));
                }
            }
            List<ForecastQuery.ForecastLineView> fcs = ForecastQuery.of(forecastQueries).latestLines(mpsItems, first, last);
            Map<Long, String> custs = new HashMap<>();
            Set<Long> custIds = new TreeSet<>();
            fcs.forEach(f -> custIds.add(f.custId()));
            if (!custIds.isEmpty()) {
                mapper.findCusts(custIds).forEach(cu -> custs.put(cu.getCustId(), cu.getCustCd()));
            }
            for (ForecastQuery.ForecastLineView f : fcs) {
                MpsCalculator.Forecast fc = new MpsCalculator.Forecast(f.custId(), f.bucketStart(), f.bucketEnd(), f.qty());
                if (!fc.end().isAfter(first) || fc.start().isAfter(last) || fc.qty().signum() <= 0) {
                    continue;
                }
                BigDecimal rest = MpsCalculator.residual(fc, byItemCust.getOrDefault(f.itemId(), List.of()));
                if (rest.signum() > 0) {
                    demands.add(new Demand(f.itemId(), MrpEngine.FC, fc.start().isBefore(first) ? first : fc.start(), rest, f.custId(),
                            custs.getOrDefault(f.custId(), String.valueOf(f.custId())) + " r" + f.revNo() + " " + f.bucketStart()));
                }
            }
        }

        /** 새로 알게 된 품목의 재고·기준정보·주 공급원·발주 공급 → 엔진 품목 */
        void load() {
            List<Long> fresh = info.keySet().stream().filter(id -> !loaded.contains(id)).toList();
            for (int i = 0; i < fresh.size(); i += CHUNK) {
                List<Long> ids = fresh.subList(i, Math.min(fresh.size(), i + CHUNK));
                stock.putAll(StockQuery.of(stockQueries).available(plant, ids));
                mapper.findParams(plant, ids).forEach(p -> params.put(p.getItemId(), p));
                prMapper.findSources(ids).forEach(s -> sources.put(s.getItemId(), s));
                for (PoSupplyQuery.Supply s : PoSupplyQuery.of(poQueries).open(plant, ids, last)) {
                    String ref = s.poNo() + "-" + s.lineNo();
                    if (!s.inBaseUnit()) {
                        preExc.add(new MrpEngine.Exc(s.itemId(), "UNIT", null, s.supplyDate(), null, s.openPoQty(), ref));
                        continue;
                    }
                    supplies.add(new Supply(s.itemId(), MrpEngine.PO, s.supplyDate(), s.qty(), s.poLineId(), ref));
                }
                loaded.addAll(ids);
            }
            items.clear();
            for (ItemSrc s : info.values()) {
                long id = s.getItemId();
                ParamSrc p = params.get(id);
                SourceSrc src = sources.get(id);
                boolean buy = "BUY".equals(s.getProcType());
                Integer lead = buy ? (src == null ? null : src.getLeadDays()) : (p == null ? null : p.getMakeLeadDays());
                BigDecimal st = stock.get(id);
                items.put(id, new MrpEngine.Item(id, s.getItemCd(), s.getProcType(), OrderService.scaleOf(scales, s.getUnitCd()),
                        mpsItems.contains(id), st == null ? BigDecimal.ZERO : st, p == null || p.getSafetyQty() == null ? BigDecimal.ZERO : p.getSafetyQty(),
                        p == null || p.getLotRule() == null ? LotSizing.EX : p.getLotRule(), p == null ? null : p.getLotQty(),
                        p == null ? null : p.getLotMult(), buy && src != null ? src.getMoq() : null, buy && src != null ? src.getOrderMult() : null,
                        lead));
            }
        }

        /** 한 단 전개(캐시) — MES 계약. null = 전개 실패 */
        List<Child> explode(long itemId, BigDecimal qty, LocalDate on) {
            String key = itemId + "|" + qty.stripTrailingZeros().toPlainString() + "|" + on;
            if (cache.containsKey(key)) {
                return cache.get(key);
            }
            WoMaterialQuery.WoNeeds wn = wq.explode(itemId, qty, on);
            List<Child> out = null;
            if (wn.exploded()) {
                Map<Long, BigDecimal> sum = new LinkedHashMap<>();
                wn.needs().forEach(n -> sum.merge(n.itemId(), n.remainingQty(), BigDecimal::add));
                out = sum.entrySet().stream().map(x -> new Child(x.getKey(), x.getValue())).toList();
            }
            cache.put(key, out);
            return out;
        }

        BusinessException loop(long itemId) {
            ItemSrc s = info.get(itemId);
            FieldErrors e = FieldErrors.create();
            e.add("plantCd", "APS_MRP_BOM_LOOP", s == null ? String.valueOf(itemId) : s.getItemCd());
            return asError(e);
        }
    }

    private static BusinessException tooLarge() {
        FieldErrors e = FieldErrors.create();
        e.add("plantCd", "APS_MRP_TOO_LARGE", MAX_ITEMS, MAX_ORDERS);
        return asError(e);
    }

    private static BusinessException asError(FieldErrors e) {
        return new BusinessException(HbErrorCode.INVALID_VALUE).withFieldErrors(e.list());
    }

    private static ElemInsert elem(MrpEngine.Elem x) {
        return new ElemInsert(x.seq(), x.itemId(), x.supply() ? "S" : "D", x.kind(), x.date(), x.qty(), x.pastDue() ? "Y" : "N", x.start(),
                x.late() ? "Y" : "N", x.parentSeq(), x.refId(), cut(x.refNo()));
    }

    private static ExcInsert exc(MrpEngine.Exc x) {
        return new ExcInsert(x.itemId(), x.code(), x.elemSeq(), x.curDate(), x.sugDate(), x.qty(), cut(x.refNo()));
    }

    private static String cut(String s) {
        return s == null || s.length() <= 100 ? s : s.substring(0, 100);
    }

    private static <T> void chunks(List<T> rows, java.util.function.Consumer<List<T>> f) {
        for (int i = 0; i < rows.size(); i += CHUNK) {
            f.accept(rows.subList(i, Math.min(rows.size(), i + CHUNK)));
        }
    }

    // ------------------------------------------------------------------ 조회

    @Transactional(readOnly = true)
    public List<io.smarthappy.module.master.equip.EquipDtos.PlantRow> plants() {
        return mpsMapper.findPlants();
    }

    @Transactional(readOnly = true)
    public MrpList list(String plantCd, String from, String to) {
        FieldErrors e = FieldErrors.create();
        LocalDate f = OrderService.date(from, "from", false, e);
        LocalDate t = OrderService.date(to, "to", false, e);
        e.throwIfAny();
        ZoneId zone = clock.zone();
        LocalDate today = LocalDate.ofInstant(clock.instant(), zone);
        t = t == null ? today : t;
        f = f == null ? t.minusDays(LIST_DEFAULT_DAYS - 1L) : f;
        if (f.isAfter(t)) {
            e.add("to", "INVALID_RANGE");
        } else if (ChronoUnit.DAYS.between(f, t) + 1 > MAX_PERIOD_DAYS) {
            e.add("to", "RANGE_TOO_LONG", ChronoUnit.DAYS.between(f, t) + 1, MAX_PERIOD_DAYS);
        }
        e.throwIfAny();
        List<MrpRow> rows = mapper.findList(VisibleText.isBlank(plantCd) ? null : plantCd.strip().toUpperCase(Locale.ROOT),
                f.atStartOfDay(zone).toOffsetDateTime(), t.plusDays(1).atStartOfDay(zone).toOffsetDateTime(), LIST_LIMIT + 1);
        boolean more = rows.size() > LIST_LIMIT;
        return new MrpList(more ? rows.subList(0, LIST_LIMIT) : rows, more);
    }

    /** 머리 + 품목 행 + 예외 */
    @Transactional(readOnly = true)
    public MrpDetail detail(long mrpId) {
        MrpRow m = mrp(mrpId);
        List<ExcRow> excs = mapper.findExcs(mrpId, EXC_LIMIT + 1);
        boolean more = excs.size() > EXC_LIMIT;
        return new MrpDetail(m, mapper.findItemRows(mrpId, null), more ? excs.subList(0, EXC_LIMIT) : excs, more);
    }

    /** 계획 주문(MPS·PLAN) — 품목·종류 거르기 */
    @Transactional(readOnly = true)
    public OrderList orders(long mrpId, Long itemId, String kind) {
        mrp(mrpId);
        String k = VisibleText.isBlank(kind) ? null : kind.strip().toUpperCase(Locale.ROOT);
        if (k != null && !MrpEngine.MPS.equals(k) && !MrpEngine.PLAN.equals(k)) {
            throw OrderService.fieldError("kind", "NO_SUCH_CODE");
        }
        List<ElemRow> rows = mapper.findOrders(mrpId, itemId, k, ORDER_LIMIT + 1);
        boolean more = rows.size() > ORDER_LIMIT;
        return new OrderList(more ? rows.subList(0, ORDER_LIMIT) : rows, more);
    }

    /** 재고/소요 목록(MD04 식) — 그 품목 요소(날짜 → 공급 → 번호) + 예상 재고 누계 + 페깅 상대 */
    @Transactional(readOnly = true)
    public ItemList itemList(long mrpId, long itemId) {
        mrp(mrpId);
        List<ItemRow> it = mapper.findItemRows(mrpId, itemId);
        if (it.isEmpty()) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        List<ElemRow> rows = mapper.findElems(mrpId, itemId, null);
        List<Integer> seqs = rows.stream().map(ElemRow::getElemSeq).toList();
        Map<Integer, List<PegRow>> pegsOf = new HashMap<>();
        Set<Integer> other = new TreeSet<>();
        if (!seqs.isEmpty()) {
            for (PegRow p : mapper.findPegs(mrpId, seqs, seqs)) {
                pegsOf.computeIfAbsent(p.getDemandSeq(), k -> new ArrayList<>()).add(p);
                pegsOf.computeIfAbsent(p.getSupplySeq(), k -> new ArrayList<>()).add(p);
                other.add(p.getDemandSeq());
                other.add(p.getSupplySeq());
            }
        }
        Map<Integer, ElemRow> bySeq = new HashMap<>();
        rows.forEach(r -> bySeq.put(r.getElemSeq(), r));
        other.removeAll(bySeq.keySet());
        if (!other.isEmpty()) {
            mapper.findElems(mrpId, null, other).forEach(r -> bySeq.put(r.getElemSeq(), r));
        }
        List<ListLine> lines = new ArrayList<>(rows.size());
        BigDecimal pab = BigDecimal.ZERO;
        for (ElemRow r : rows) {
            BigDecimal q = new BigDecimal(r.getQty());
            pab = "S".equals(r.getDir()) ? pab.add(q) : pab.subtract(q);
            List<PegLink> links = new ArrayList<>();
            for (PegRow p : pegsOf.getOrDefault(r.getElemSeq(), List.of())) {
                int o = p.getDemandSeq() == r.getElemSeq() ? p.getSupplySeq() : p.getDemandSeq();
                ElemRow x = bySeq.get(o);
                links.add(new PegLink(o, x == null ? null : x.getItemCd(), x == null ? null : x.getKind(), x == null ? null : x.getElemDate(),
                        x == null ? null : x.getRefNo(), p.getQty()));
            }
            lines.add(new ListLine(r, plain(pab), links));
        }
        return new ItemList(it.get(0), lines);
    }

    /**
     * 페깅 사슬 위로 — 공급이면 덮은 수요들, 수요가 DEP 면 부모 계획 주문 → 그 공급이 덮은 수요 … 독립 수요·상위 지시·안전재고에서 끝.
     * 마디 {@link #PEG_NODES} 개·단계 {@link MrpEngine#MAX_LEVEL} 까지
     */
    @Transactional(readOnly = true)
    public PegChain pegUp(long mrpId, int seq) {
        mrp(mrpId);
        List<ElemRow> start = mapper.findElems(mrpId, null, List.of(seq));
        if (start.isEmpty()) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        List<PegNode> nodes = new ArrayList<>();
        nodes.add(new PegNode(0, -1, start.get(0), null));
        List<Integer> level = List.of(0);
        boolean more = false;
        for (int depth = 0; depth <= MrpEngine.MAX_LEVEL * 2 && !level.isEmpty() && !more; depth++) {
            List<Integer> supplyIdx = level.stream().filter(i -> "S".equals(nodes.get(i).elem().getDir())).toList();
            List<Integer> demandIdx = level.stream().filter(i -> "D".equals(nodes.get(i).elem().getDir())).toList();
            List<Integer> next = new ArrayList<>();
            // 공급 → 그것이 덮은 수요
            if (!supplyIdx.isEmpty()) {
                Map<Integer, List<Integer>> idxBySeq = new HashMap<>();
                supplyIdx.forEach(i -> idxBySeq.computeIfAbsent(nodes.get(i).elem().getElemSeq(), k -> new ArrayList<>()).add(i));
                List<PegRow> ps = mapper.findPegs(mrpId, List.of(), idxBySeq.keySet());
                Map<Integer, ElemRow> dem = new HashMap<>();
                Set<Integer> ds = new TreeSet<>();
                ps.forEach(p -> ds.add(p.getDemandSeq()));
                if (!ds.isEmpty()) {
                    mapper.findElems(mrpId, null, ds).forEach(r -> dem.put(r.getElemSeq(), r));
                }
                for (PegRow p : ps) {
                    for (Integer from : idxBySeq.get(p.getSupplySeq())) {
                        if (nodes.size() >= PEG_NODES) {
                            more = true;
                            break;
                        }
                        nodes.add(new PegNode(nodes.size(), from, dem.get(p.getDemandSeq()), p.getQty()));
                        next.add(nodes.size() - 1);
                    }
                }
            }
            // 종속 수요 → 부모 계획 주문
            List<Integer> parentSeqs = demandIdx.stream().map(i -> nodes.get(i).elem().getParentSeq()).filter(java.util.Objects::nonNull)
                    .distinct().toList();
            if (!parentSeqs.isEmpty() && !more) {
                Map<Integer, ElemRow> par = new HashMap<>();
                mapper.findElems(mrpId, null, parentSeqs).forEach(r -> par.put(r.getElemSeq(), r));
                for (Integer i : demandIdx) {
                    Integer ps = nodes.get(i).elem().getParentSeq();
                    if (ps == null || par.get(ps) == null) {
                        continue;
                    }
                    if (nodes.size() >= PEG_NODES) {
                        more = true;
                        break;
                    }
                    nodes.add(new PegNode(nodes.size(), i, par.get(ps), nodes.get(i).elem().getQty()));
                    next.add(nodes.size() - 1);
                }
            }
            level = next;
        }
        return new PegChain(nodes, more);
    }

    // ------------------------------------------------------------------ 폐기

    @Transactional
    public void discard(long mrpId, ApsDtos.DiscardForm in) {
        LoginUser me = me();
        if (in == null || in.rowVer() == null) {
            throw new BusinessException(HbErrorCode.INVALID_VALUE);
        }
        FieldErrors e = FieldErrors.create();
        String reason = OrderService.text(in.reason(), "reason", 500, e);
        if (reason == null) {
            e.add("reason", "REQUIRED");
        }
        e.throwIfAny();
        String status = mapper.lockMrp(mrpId);
        if (status == null) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        if (!"CALC".equals(status)) {
            throw OrderService.fieldError("status", "STATE");
        }
        if (mapper.discard(mrpId, reason, in.rowVer(), me.loginId()) == 0) {
            throw new BusinessException(HbErrorCode.CONFLICT);
        }
    }

    // ------------------------------------------------------------------ 도우미

    private MrpRow mrp(long mrpId) {
        MrpRow m = mapper.findMrp(mrpId);
        if (m == null) {
            throw new BusinessException(HbErrorCode.NOT_FOUND);
        }
        return m;
    }

    private static String plain(BigDecimal v) {
        BigDecimal s = v.stripTrailingZeros();
        return (s.scale() < 0 ? s.setScale(0) : s).toPlainString();
    }

    private static LoginUser me() {
        return CurrentUser.get().orElseThrow(() -> new BusinessException(HbErrorCode.UNAUTHENTICATED));
    }
}
