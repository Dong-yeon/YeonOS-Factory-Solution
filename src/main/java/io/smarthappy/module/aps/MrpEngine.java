package io.smarthappy.module.aps;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * MRP 전개 엔진 (docs/38 19.3~19.6) — 순수 계산(DB·스프링·시각 없음). 시험이 그대로 부른다. 같은 입력 → 같은 결과.
 *
 * - 품목을 저차 코드(LLC) → 품목 코드 순으로 한 번씩: 순소요(기존 공급부터 당겨 씀 — 재일정 가정) → 로트 → 리드타임 역산 → 계획 주문 → 전개
 * - MPS 품목은 순소요하지 않는다: 확정 MPS 칸 = 계획 주문(MPS), 수주·내시 줄과는 페깅만
 * - 차감할 때마다 페깅 한 줄. 열린 지시·발주는 페깅으로 당김·밀기·취소·줄임 예외(G7)
 * - BOM 전개는 {@link Exploder}(서비스는 MES 계약 {@code WoMaterialQuery.explode}) — 다른 날짜 BOM 에서 새 자품목·더 깊은 단계가 나오면
 * 끝까지 돈 뒤 {@link Restart}(서비스가 LLC 를 다시 정하고 처음부터)
 */
final class MrpEngine {

    // 공급
    static final String STOCK = "STOCK";
    static final String WO = "WO";
    static final String PO = "PO";
    static final String MPS = "MPS";
    static final String PLAN = "PLAN";
    // 수요
    static final String SO = "SO";
    static final String FC = "FC";
    static final String DEP = "DEP";
    static final String WODEP = "WODEP";
    static final String SAFE = "SAFE";

    static final int MAX_LEVEL = 30;

    private MrpEngine() {
    }

    // ------------------------------------------------------------------ 입력

    /**
     * 계획 품목 — leadDays null = 미정(MAKE·OUTSRC 기준정보 없음, BUY 공급원 없음 → 0일 + 예외). moq·orderMult 는 BUY 주 공급원
     */
    record Item(long itemId, String itemCd, String procType, int scale, boolean mps, BigDecimal stock, BigDecimal safety, String lotRule,
            BigDecimal lotQty, BigDecimal lotMult, BigDecimal moq, BigDecimal orderMult, Integer leadDays) {

        boolean buy() {
            return "BUY".equals(procType);
        }
    }

    /** 기존 공급 — kind WO·PO, 날짜 = 계획 완료일·도착 예정 */
    record Supply(long itemId, String kind, LocalDate date, BigDecimal qty, Long refId, String refNo) {
    }

    /** 미리 아는 수요 — kind WODEP(열린 지시 남은 소요, 날짜 = 지시 시작), SO·FC(MPS 품목 페깅용) */
    record Demand(long itemId, String kind, LocalDate date, BigDecimal qty, Long refId, String refNo) {
    }

    /** 확정 MPS 칸 — 날짜 = 완성일 */
    record MpsPlan(long itemId, LocalDate date, BigDecimal qty, long cellId) {
    }

    record Child(long itemId, BigDecimal qty) {
    }

    /** 한 단 BOM 전개 — null = 전개 실패(환산·가상 조립 BOM 없음), 빈 목록 = BOM 없음 */
    @FunctionalInterface
    interface Exploder {
        List<Child> explode(long itemId, BigDecimal qty, LocalDate on);
    }

    /**
     * @param first 첫날(공장 오늘) — 그 전 날짜는 첫날 + 밀림 표시
     * @param today 공장 오늘 — 계획 주문 시작일이 이보다 앞이면 늦음
     * @param llc   품목 → 저차 코드(items 의 모든 품목)
     */
    record Input(LocalDate first, int days, LocalDate today, int outDays, Map<Long, Item> items, Map<Long, Integer> llc,
            List<MpsPlan> mps, List<Demand> demands, List<Supply> supplies, int maxElems, int maxOrders) {
    }

    // ------------------------------------------------------------------ 결과

    /** 요소 — supply = 공급(S)/수요(D). start·late 는 계획 주문(MPS·PLAN), parentSeq 는 DEP */
    record Elem(int seq, long itemId, boolean supply, String kind, LocalDate date, BigDecimal qty, boolean pastDue, LocalDate start,
            boolean late, Integer parentSeq, Long refId, String refNo) {

        boolean order() {
            return MPS.equals(kind) || PLAN.equals(kind);
        }
    }

    record Peg(int demandSeq, int supplySeq, BigDecimal qty) {
    }

    record Exc(long itemId, String code, Integer elemSeq, LocalDate curDate, LocalDate sugDate, BigDecimal qty, String refNo) {
    }

    record Result(List<Elem> elems, List<Peg> pegs, List<Exc> excs) {

        long orderCount() {
            return elems.stream().filter(Elem::order).count();
        }
    }

    /** 다른 날짜 BOM 에서 새 자품목(unknown) 또는 LLC 를 넘는 연결(edges: 부모 → 자품목)이 나옴 — 서비스가 LLC 를 다시 정하고 처음부터 */
    static final class Restart extends RuntimeException {
        private static final long serialVersionUID = 1L;
        final transient Map<Long, Set<Long>> edges;
        final transient Set<Long> unknown;

        Restart(Map<Long, Set<Long>> edges, Set<Long> unknown) {
            super("mrp levels changed", null, false, false);
            this.edges = edges;
            this.unknown = unknown;
        }
    }

    /** 요소·계획 주문 상한 초과 */
    static final class TooLarge extends RuntimeException {
        private static final long serialVersionUID = 1L;

        TooLarge() {
            super("mrp too large", null, false, false);
        }
    }

    /** BOM 순환(단계 > {@link #MAX_LEVEL}) */
    static final class Loop extends RuntimeException {
        private static final long serialVersionUID = 1L;
        final long itemId;

        Loop(long itemId) {
            super("bom loop", null, false, false);
            this.itemId = itemId;
        }
    }

    // ------------------------------------------------------------------ 저차 코드

    /**
     * 저차 코드 = 뿌리에서 가장 긴 경로의 단계(뿌리 0). edges = 부모 → 자품목. 단계가 {@link #MAX_LEVEL} 을 넘으면(순환) {@link Loop}
     */
    static Map<Long, Integer> levels(Set<Long> items, Map<Long, Set<Long>> edges) {
        Map<Long, Integer> lv = new HashMap<>();
        items.forEach(i -> lv.put(i, 0));
        edges.values().forEach(cs -> cs.forEach(c -> lv.putIfAbsent(c, 0)));
        boolean changed = true;
        while (changed) {
            changed = false;
            for (Map.Entry<Long, Set<Long>> e : edges.entrySet()) {
                int p = lv.getOrDefault(e.getKey(), 0);
                for (Long c : e.getValue()) {
                    if (lv.get(c) <= p) {
                        if (p + 1 > MAX_LEVEL) {
                            throw new Loop(c);
                        }
                        lv.put(c, p + 1);
                        changed = true;
                    }
                }
            }
        }
        return lv;
    }

    // ------------------------------------------------------------------ 계산

    static Result run(Input in, Exploder ex) {
        return new Run(in, ex).go();
    }

    private static final class Run {
        final Input in;
        final Exploder ex;
        final LocalDate last;
        final List<Elem> elems = new ArrayList<>();
        final List<Peg> pegs = new ArrayList<>();
        final List<Exc> excs = new ArrayList<>();
        /** 부모 계획 주문이 만든 종속 수요(DEP) — 자품목별 */
        final Map<Long, List<Elem>> dep = new HashMap<>();
        final Map<Long, List<Demand>> known = new HashMap<>();
        final Map<Long, List<Supply>> sup = new HashMap<>();
        final Map<Long, List<MpsPlan>> mps = new HashMap<>();
        final Map<Long, Set<Long>> newEdges = new LinkedHashMap<>();
        final Set<Long> unknown = new LinkedHashSet<>();
        /** 공급 요소 → 페깅된 실수요(안전재고 아님) 중 가장 이른 날짜 */
        final Map<Integer, LocalDate> earliestReal = new HashMap<>();
        int orders;

        Run(Input in, Exploder ex) {
            this.in = in;
            this.ex = ex;
            this.last = in.first().plusDays(in.days() - 1L);
            in.demands().forEach(d -> known.computeIfAbsent(d.itemId(), k -> new ArrayList<>()).add(d));
            in.supplies().forEach(s -> sup.computeIfAbsent(s.itemId(), k -> new ArrayList<>()).add(s));
            in.mps().forEach(m -> mps.computeIfAbsent(m.itemId(), k -> new ArrayList<>()).add(m));
        }

        Result go() {
            List<Item> order = new ArrayList<>(in.items().values());
            order.sort(Comparator.comparing((Item i) -> in.llc().get(i.itemId())).thenComparing(Item::itemCd).thenComparing(Item::itemId));
            for (Item it : order) {
                if (it.mps()) {
                    mpsItem(it);
                } else {
                    netItem(it);
                }
            }
            if (!unknown.isEmpty() || !newEdges.isEmpty()) {
                throw new Restart(newEdges, unknown);
            }
            return new Result(elems, pegs, excs);
        }

        // ---------------------------------------------------------- 일반 품목: 순소요

        void netItem(Item it) {
            List<Elem> supplies = new ArrayList<>(existing(it));
            List<Elem> demands = new ArrayList<>(dep.getOrDefault(it.itemId(), List.of()));
            for (Demand d : sorted(known.getOrDefault(it.itemId(), List.of()))) {
                if (!WODEP.equals(d.kind()) || d.qty().signum() <= 0 || d.date().isAfter(last)) {
                    continue;
                }
                demands.add(add(it.itemId(), false, WODEP, d.date(), d.qty(), null, null, d.refId(), d.refNo()));
            }
            if (it.safety() != null && it.safety().signum() > 0) {
                demands.add(add(it.itemId(), false, SAFE, in.first(), it.safety(), null, null, null, null));
            }
            demands.sort(Comparator.comparing(Elem::date).thenComparing(e -> SAFE.equals(e.kind()) ? 1 : 0).thenComparing(Elem::seq));
            Map<Integer, BigDecimal> left = new HashMap<>();
            supplies.forEach(s -> left.put(s.seq(), s.qty()));
            List<Elem> planned = new ArrayList<>();
            int si = 0;
            int pi = 0;
            boolean leadReported = false;
            int i = 0;
            while (i < demands.size()) {
                LocalDate d = demands.get(i).date();
                List<Elem> uncDem = new ArrayList<>();
                List<BigDecimal> uncQty = new ArrayList<>();
                for (; i < demands.size() && demands.get(i).date().equals(d); i++) {
                    Elem dem = demands.get(i);
                    BigDecimal rem = dem.qty();
                    while (rem.signum() > 0 && si < supplies.size()) {
                        rem = take(dem, supplies.get(si), rem, left);
                        if (left.get(supplies.get(si).seq()).signum() == 0) {
                            si++;
                        }
                    }
                    while (rem.signum() > 0 && pi < planned.size()) {
                        rem = take(dem, planned.get(pi), rem, left);
                        if (left.get(planned.get(pi).seq()).signum() == 0) {
                            pi++;
                        }
                    }
                    if (rem.signum() > 0) {
                        uncDem.add(dem);
                        uncQty.add(rem);
                    }
                }
                if (uncDem.isEmpty()) {
                    continue;
                }
                BigDecimal net = uncQty.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
                BigDecimal q = lot(it, net);
                Elem plan = order(it, PLAN, d, q, null);
                left.put(plan.seq(), q);
                for (int k = 0; k < uncDem.size(); k++) {
                    take(uncDem.get(k), plan, uncQty.get(k), left);
                }
                if (left.get(plan.seq()).signum() > 0) {
                    planned.add(plan);
                }
                if (it.leadDays() == null && !leadReported) {
                    excs.add(new Exc(it.itemId(), it.buy() ? "NO_SOURCE" : "NO_LEAD", null, null, null, null, null));
                    leadReported = true;
                }
            }
            reschedule(supplies, left);
        }

        /** 열린 지시·발주 — 페깅으로 당김·밀기·취소·줄임(G7). 안전재고만 덮는 공급은 날짜 예외 없음 */
        void reschedule(List<Elem> supplies, Map<Integer, BigDecimal> left) {
            for (Elem s : supplies) {
                if (!WO.equals(s.kind()) && !PO.equals(s.kind())) {
                    continue;
                }
                BigDecimal rest = left.get(s.seq());
                if (rest.compareTo(s.qty()) == 0) {
                    excs.add(new Exc(s.itemId(), "CANCEL", s.seq(), s.date(), null, s.qty(), s.refNo()));
                    continue;
                }
                if (rest.signum() > 0) {
                    excs.add(new Exc(s.itemId(), "REDUCE", s.seq(), s.date(), null, rest, s.refNo()));
                }
                LocalDate e = earliestReal.get(s.seq());
                if (e == null) {
                    continue;
                }
                if (e.isBefore(s.date())) {
                    excs.add(new Exc(s.itemId(), "RESCHED_IN", s.seq(), s.date(), e, null, s.refNo()));
                } else if (e.isAfter(s.date().plusDays(in.outDays()))) {
                    excs.add(new Exc(s.itemId(), "RESCHED_OUT", s.seq(), s.date(), e, null, s.refNo()));
                }
            }
        }

        // ---------------------------------------------------------- MPS 품목: 계획 주문 = MPS 칸, 페깅만

        void mpsItem(Item it) {
            List<Elem> supplies = new ArrayList<>(existing(it));
            BigDecimal past = BigDecimal.ZERO;
            boolean leadReported = false;
            List<MpsPlan> plans = new ArrayList<>(mps.getOrDefault(it.itemId(), List.of()));
            plans.sort(Comparator.comparing(MpsPlan::date).thenComparing(MpsPlan::cellId));
            for (MpsPlan m : plans) {
                if (m.qty().signum() <= 0 || m.date().isAfter(last)) {
                    continue;
                }
                if (m.date().isBefore(in.first())) {
                    past = past.add(m.qty());
                    continue;
                }
                supplies.add(order(it, MPS, m.date(), m.qty(), m.cellId()));
                if (it.leadDays() == null && !leadReported) {
                    excs.add(new Exc(it.itemId(), "NO_LEAD", null, null, null, null, null));
                    leadReported = true;
                }
            }
            if (past.signum() > 0) {
                excs.add(new Exc(it.itemId(), "MPS_PAST", null, null, in.first(), past, null));
            }
            List<Elem> demands = new ArrayList<>();
            for (Demand d : sorted(known.getOrDefault(it.itemId(), List.of()))) {
                if ((!SO.equals(d.kind()) && !FC.equals(d.kind())) || d.qty().signum() <= 0 || d.date().isAfter(last)) {
                    continue;
                }
                demands.add(add(it.itemId(), false, d.kind(), d.date(), d.qty(), null, null, d.refId(), d.refNo()));
            }
            demands.sort(Comparator.comparing(Elem::date).thenComparing(Elem::seq));
            supplies.sort(Comparator.comparing((Elem e) -> STOCK.equals(e.kind()) ? 0 : 1).thenComparing(Elem::date).thenComparing(Elem::seq));
            Map<Integer, BigDecimal> left = new HashMap<>();
            supplies.forEach(s -> left.put(s.seq(), s.qty()));
            int si = 0;
            BigDecimal shortQty = BigDecimal.ZERO;
            LocalDate shortAt = null;
            for (Elem dem : demands) {
                BigDecimal rem = dem.qty();
                while (rem.signum() > 0 && si < supplies.size()) {
                    rem = take(dem, supplies.get(si), rem, left);
                    if (left.get(supplies.get(si).seq()).signum() == 0) {
                        si++;
                    }
                }
                if (rem.signum() > 0) {
                    shortQty = shortQty.add(rem);
                    shortAt = shortAt == null ? dem.date() : shortAt;
                }
            }
            if (shortQty.signum() > 0) {
                excs.add(new Exc(it.itemId(), "MPS_SHORT", null, shortAt, null, shortQty, null));
            }
            List<Elem> deps = dep.getOrDefault(it.itemId(), List.of());
            if (!deps.isEmpty()) {
                excs.add(new Exc(it.itemId(), "MPS_DEP", deps.get(0).seq(), deps.get(0).date(), null,
                        deps.stream().map(Elem::qty).reduce(BigDecimal.ZERO, BigDecimal::add), null));
            }
        }

        // ---------------------------------------------------------- 공통

        /** 재고(첫날) + 열린 지시·발주(지난 날짜는 첫날, 기간 밖은 뺌) — STOCK → 날짜 → 번호 순 */
        List<Elem> existing(Item it) {
            List<Elem> out = new ArrayList<>();
            if (it.stock() != null && it.stock().signum() > 0) {
                out.add(add(it.itemId(), true, STOCK, in.first(), it.stock(), null, null, null, null));
            }
            List<Supply> ss = new ArrayList<>(sup.getOrDefault(it.itemId(), List.of()));
            ss.sort(Comparator.comparing(Supply::date).thenComparing(s -> s.refNo() == null ? "" : s.refNo()));
            for (Supply s : ss) {
                if (s.qty().signum() <= 0 || s.date().isAfter(last)) {
                    continue;
                }
                out.add(add(it.itemId(), true, s.kind(), s.date(), s.qty(), null, null, s.refId(), s.refNo()));
            }
            return out;
        }

        /** 계획 주문(MPS·PLAN) — 시작일 = 완료일 − 리드타임, 늦음 예외, 만드는 품목이면 시작일 BOM 으로 전개 */
        Elem order(Item it, String kind, LocalDate due, BigDecimal q, Long refId) {
            if (++orders > in.maxOrders()) {
                throw new TooLarge();
            }
            LocalDate start = due.minusDays(it.leadDays() == null ? 0 : it.leadDays());
            boolean late = start.isBefore(in.today());
            Elem e = add(it.itemId(), true, kind, due, q, start, late, refId, null);
            if (late) {
                excs.add(new Exc(it.itemId(), "START_PAST", e.seq(), start, in.today(), q, null));
            }
            if (!it.buy()) {
                explode(it, e);
            }
            return e;
        }

        void explode(Item it, Elem plan) {
            boolean pastDue = plan.start().isBefore(in.first());
            LocalDate on = pastDue ? in.first() : plan.start();
            List<Child> cs = ex.explode(it.itemId(), plan.qty(), on);
            if (cs == null) {
                excs.add(new Exc(it.itemId(), "NO_BOM", plan.seq(), on, null, plan.qty(), null));
                return;
            }
            int lv = in.llc().get(it.itemId());
            TreeMap<Long, BigDecimal> sum = new TreeMap<>();
            cs.stream().filter(c -> c.qty() != null && c.qty().signum() > 0).forEach(c -> sum.merge(c.itemId(), c.qty(), BigDecimal::add));
            for (Map.Entry<Long, BigDecimal> c : sum.entrySet()) {
                Integer clv = in.llc().get(c.getKey());
                if (!in.items().containsKey(c.getKey()) || clv == null) {
                    unknown.add(c.getKey());
                    newEdges.computeIfAbsent(it.itemId(), k -> new LinkedHashSet<>()).add(c.getKey());
                    continue;
                }
                if (clv <= lv) {
                    newEdges.computeIfAbsent(it.itemId(), k -> new LinkedHashSet<>()).add(c.getKey());
                    continue;
                }
                Elem d = add(c.getKey(), false, DEP, on, c.getValue(), null, null, null, null, plan.seq(), pastDue);
                dep.computeIfAbsent(c.getKey(), k -> new ArrayList<>()).add(d);
            }
        }

        /** 수요 dem 이 공급 s 에서 rem 까지 가져감 → 남은 rem */
        BigDecimal take(Elem dem, Elem s, BigDecimal rem, Map<Integer, BigDecimal> left) {
            BigDecimal have = left.get(s.seq());
            BigDecimal t = have.min(rem);
            if (t.signum() <= 0) {
                return rem;
            }
            left.put(s.seq(), have.subtract(t));
            pegs.add(new Peg(dem.seq(), s.seq(), t));
            if (!SAFE.equals(dem.kind())) {
                earliestReal.merge(s.seq(), dem.date(), (a, b) -> a.isBefore(b) ? a : b);
            }
            return rem.subtract(t);
        }

        Elem add(long itemId, boolean supply, String kind, LocalDate date, BigDecimal qty, LocalDate start, Boolean late, Long refId,
                String refNo) {
            boolean pastDue = date.isBefore(in.first());
            return add(itemId, supply, kind, pastDue ? in.first() : date, qty, start, late, refId, refNo, null, pastDue);
        }

        Elem add(long itemId, boolean supply, String kind, LocalDate date, BigDecimal qty, LocalDate start, Boolean late, Long refId,
                String refNo, Integer parentSeq, boolean pastDue) {
            if (elems.size() >= in.maxElems()) {
                throw new TooLarge();
            }
            Elem e = new Elem(elems.size() + 1, itemId, supply, kind, date, qty, pastDue, start, Boolean.TRUE.equals(late), parentSeq, refId,
                    refNo);
            elems.add(e);
            return e;
        }

        static List<Demand> sorted(List<Demand> ds) {
            List<Demand> out = new ArrayList<>(ds);
            out.sort(Comparator.comparing(Demand::date).thenComparing(d -> d.refNo() == null ? "" : d.refNo()));
            return out;
        }
    }

    /**
     * 로트 (19.4) — MAKE·OUTSRC = 기준정보 로트(17.3). BUY = 기준정보 로트 → 주 공급원 MOQ·발주 배수({@code size(q, MIN, moq, order_mult)})
     */
    static BigDecimal lot(Item it, BigDecimal net) {
        BigDecimal q = LotSizing.size(net, it.lotRule(), it.lotQty(), it.lotMult(), it.scale());
        if (it.buy() && (it.moq() != null || it.orderMult() != null)) {
            q = LotSizing.size(q, LotSizing.MIN, it.moq(), it.orderMult(), it.scale());
        }
        return q;
    }

    /** 한 실행의 요소에서 품목의 예상 재고 누계(날짜 → 공급 → 수요 → 번호 순) — 화면 재고/소요 목록 */
    static List<BigDecimal> pab(List<Elem> itemElems) {
        List<BigDecimal> out = new ArrayList<>(itemElems.size());
        BigDecimal p = BigDecimal.ZERO;
        for (Elem e : itemElems) {
            p = e.supply() ? p.add(e.qty()) : p.subtract(e.qty());
            out.add(p);
        }
        return out;
    }

    /** 화면 정렬 — 날짜 → 공급 먼저 → 번호 */
    static final Comparator<Elem> LIST_ORDER = Comparator.comparing(Elem::date).thenComparing(e -> e.supply() ? 0 : 1)
            .thenComparing(Elem::seq);

}
