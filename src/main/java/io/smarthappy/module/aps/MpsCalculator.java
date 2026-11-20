package io.smarthappy.module.aps;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * MPS 계산 본체 (docs/38 16.3) — 순수 함수(DB·시각 없음), 품목 하나씩. 같은 입력 → 같은 결과.
 *
 * - 수요 = 수주 남은 수량(납기일, 지난 납기는 첫날) + 소진 뒤 남은 예측(거래처 × 예측 기간 안 max(예측, 수주) — 기간 첫날)
 * - 공급 = 가용 재고(첫날 전) + 열린 지시 남은 수량(계획 완료일, 지난 날짜는 첫날)
 * - 제안 = 안전재고 미달분 → 로트 규칙(EX 그대로·FX 배수·MIN 최소), 단위 자릿수로 올림
 * - 확정 구간 안 날짜는 현행 확정 MPS 값(그 기간 밖이면 제안)을 계산 값으로
 */
final class MpsCalculator {

    private MpsCalculator() {
    }

    /** 수주 행 — 남은 수량(> 0) */
    record Order(long custId, LocalDate due, BigDecimal qty) {
    }

    /** 내시 줄 — 기간 [start, end) */
    record Forecast(long custId, LocalDate start, LocalDate end, BigDecimal qty) {
    }

    /** 열린 지시 공급 — 계획 완료일 */
    record Supply(LocalDate date, BigDecimal qty) {
    }

    /**
     * 확정 구간 이월 값 — 현행 확정 MPS 의 기간 [from, to] 와 그 품목 칸 값(없는 날짜는 0). 현행 확정이 없으면 null
     */
    record Firmed(LocalDate from, LocalDate to, Map<LocalDate, BigDecimal> plan) {
    }

    /** 품목 입력 */
    record ItemIn(long itemId, int scale, BigDecimal stock, BigDecimal safety, String lotRule, BigDecimal lotQty, BigDecimal lotMult,
            List<Order> orders, List<Forecast> forecasts, List<Supply> supplies, Firmed firmed) {

        /** 배수 없음(7단계 시험·호출 그대로) */
        ItemIn(long itemId, int scale, BigDecimal stock, BigDecimal safety, String lotRule, BigDecimal lotQty, List<Order> orders,
                List<Forecast> forecasts, List<Supply> supplies, Firmed firmed) {
            this(itemId, scale, stock, safety, lotRule, lotQty, null, orders, forecasts, supplies, firmed);
        }
    }

    /** 칸 결과 */
    record Cell(LocalDate date, BigDecimal demandOrder, BigDecimal demandFcst, BigDecimal pastDue, BigDecimal supplyWo, BigDecimal prop,
            BigDecimal base, boolean fenced) {
    }

    /**
     * @param first    첫날
     * @param days     일수
     * @param fenceEnd 확정 구간 끝(미포함) — 이 날 전 칸은 구간
     */
    static List<Cell> calculate(ItemIn in, LocalDate first, int days, LocalDate fenceEnd) {
        LocalDate last = first.plusDays(days - 1L);
        BigDecimal[] order = zeros(days);
        BigDecimal[] fcst = zeros(days);
        BigDecimal[] supply = zeros(days);
        BigDecimal pastDue = BigDecimal.ZERO;
        for (Order o : in.orders()) {
            if (o.qty().signum() <= 0 || o.due().isAfter(last)) {
                continue;
            }
            if (o.due().isBefore(first)) {
                pastDue = pastDue.add(o.qty());
            }
            order[index(first, o.due())] = order[index(first, o.due())].add(o.qty());
        }
        // 소진: 같은 거래처의 그 기간 납기 수주(MPS 기간 밖 납기도 — 예측 기간이 기간 밖으로 걸칠 수 있다)
        Map<Long, List<Order>> byCust = new HashMap<>();
        in.orders().forEach(o -> byCust.computeIfAbsent(o.custId(), k -> new ArrayList<>()).add(o));
        for (Forecast f : in.forecasts()) {
            if (!f.end().isAfter(first) || f.start().isAfter(last) || f.qty().signum() <= 0) {
                continue;
            }
            BigDecimal rest = residual(f, byCust.getOrDefault(f.custId(), List.of()));
            if (rest.signum() > 0) {
                int i = index(first, f.start());
                fcst[i] = fcst[i].add(rest);
            }
        }
        for (Supply s : in.supplies()) {
            if (s.qty().signum() <= 0 || s.date().isAfter(last)) {
                continue;
            }
            supply[index(first, s.date())] = supply[index(first, s.date())].add(s.qty());
        }
        List<Cell> out = new ArrayList<>(days);
        BigDecimal pab = in.stock();
        for (int i = 0; i < days; i++) {
            LocalDate d = first.plusDays(i);
            BigDecimal avail = pab.add(supply[i]).subtract(order[i]).subtract(fcst[i]);
            BigDecimal shortage = in.safety().subtract(avail);
            BigDecimal prop = shortage.signum() > 0 ? LotSizing.size(shortage, in.lotRule(), in.lotQty(), in.lotMult(), in.scale()) : BigDecimal.ZERO;
            boolean fenced = d.isBefore(fenceEnd);
            BigDecimal base = prop;
            Firmed fm = in.firmed();
            if (fenced && fm != null && !d.isBefore(fm.from()) && !d.isAfter(fm.to())) {
                base = fm.plan().getOrDefault(d, BigDecimal.ZERO);
            }
            pab = avail.add(base);
            out.add(new Cell(d, order[i], fcst[i], i == 0 ? pastDue : BigDecimal.ZERO, supply[i], prop, base, fenced));
        }
        return out;
    }

    /**
     * 소진 뒤 남은 예측(M3 — 기간 수요 = max(예측, 수주)) = 예측 − 같은 거래처의 그 기간 [start, end) 납기 수주 남은 수량. 음수면 0 아래 값 그대로
     * (부르는 쪽이 > 0 만 쓴다). MPS 계산과 MRP 페깅(docs/38 19.5)이 같이 쓴다
     * @param custOrders 같은 거래처 수주(기간 밖 납기 포함 — 걸러서 쓴다)
     */
    static BigDecimal residual(Forecast f, List<Order> custOrders) {
        BigDecimal ordered = custOrders.stream().filter(o -> o.custId() == f.custId())
                .filter(o -> !o.due().isBefore(f.start()) && o.due().isBefore(f.end()))
                .map(Order::qty).reduce(BigDecimal.ZERO, BigDecimal::add);
        return f.qty().subtract(ordered);
    }

    /** 예상 재고 — pab(d) = pab(d−1) + 지시 공급 + 계획 − 수요 (화면·조회와 같은 식) */
    static List<BigDecimal> pab(BigDecimal stock, List<BigDecimal> supply, List<BigDecimal> demand, List<BigDecimal> plan) {
        List<BigDecimal> out = new ArrayList<>(plan.size());
        BigDecimal p = stock;
        for (int i = 0; i < plan.size(); i++) {
            p = p.add(supply.get(i)).add(plan.get(i)).subtract(demand.get(i));
            out.add(p);
        }
        return out;
    }

    /** 로트 규칙 — 부족분(> 0) → 계획 수량 */
    static BigDecimal lot(BigDecimal shortage, String rule, BigDecimal lotQty, int scale) {
        return LotSizing.size(shortage, rule, lotQty, null, scale);
    }

    private static int index(LocalDate first, LocalDate d) {
        return d.isBefore(first) ? 0 : (int) ChronoUnit.DAYS.between(first, d);
    }

    private static BigDecimal[] zeros(int n) {
        BigDecimal[] a = new BigDecimal[n];
        java.util.Arrays.fill(a, BigDecimal.ZERO);
        return a;
    }
}
