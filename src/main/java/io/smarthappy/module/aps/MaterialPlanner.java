package io.smarthappy.module.aps;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import io.smarthappy.module.aps.FiniteScheduler.OpIn;
import io.smarthappy.module.aps.FiniteScheduler.WoIn;

/**
 * 자재 가용 판정 (docs/38 14.3, A27·A32·A33) — DB·스프링 없는 계산만(시험이 그대로 부른다). 스케줄러가 공정을 넣을 때마다 부른다.
 *
 * - 공급 시간축: 품목마다 (시각, 수량) — 현재고는 계산 시작 시각, 입고 예정은 그 날 00:00(공장 현지)
 * - 소요는 투입 공정의 시작에 필요(A32) — 지시에 없는 공정 순번(이미 끝난 공정·공정 지정 없음)은 지시의 첫 남은 공정에 붙인다
 * - 할당은 스케줄러가 부르는 순서(규칙 순서) 그대로 앞에서부터 차감(A33): 충족 시각 = 공급 누계 − 앞 할당 ≥ 필요가 되는 가장 이른 공급 시각
 * - WARN: 시각은 그대로, 필요 시각에 모자란 만큼 부족. DELAY: 스케줄러가 {@link #coveredAt} 을 공정의 가장 이른 시작에 더한다
 */
final class MaterialPlanner {

    enum Mode { WARN, DELAY }

    /** 계산이 공정을 못 넣게 하는 표시 — DELAY 에서 기간 안에 덮이지 않음 */
    static final Instant NEVER = Instant.MAX;

    record Supply(long itemId, Instant at, BigDecimal qty) {
    }

    /** 남은 소요 한 줄 — opSeq null = 공정 지정 없음 */
    record Need(long itemId, Integer opSeq, BigDecimal qty, String unitCd) {
    }

    /** 결과 한 줄 — needAt null = 그 공정을 계획하지 못함(판정 없음) */
    static final class MatOut {
        final long woId;
        final int opSeq;
        final Integer srcOpSeq;
        final long itemId;
        final BigDecimal needQty;
        final String unitCd;
        Instant needAt;
        BigDecimal allocQty = BigDecimal.ZERO;
        BigDecimal shortQty = BigDecimal.ZERO;
        Instant coveredAt;

        MatOut(long woId, int opSeq, Need n) {
            this.woId = woId;
            this.opSeq = opSeq;
            this.srcOpSeq = n.opSeq();
            this.itemId = n.itemId();
            this.needQty = n.qty();
            this.unitCd = n.unitCd();
        }
    }

    final Mode mode;
    private final Map<Long, List<Supply>> supply = new HashMap<>();
    private final Map<Long, List<Need>> needs;
    /** 품목별 앞 지시들이 가져간 양 */
    private final Map<Long, BigDecimal> reserved = new HashMap<>();
    private final List<MatOut> outs = new ArrayList<>();
    private final Set<Long> shortWos = new HashSet<>();

    /** @param needs 지시 id → 남은 소요(없는 지시는 소요 없음) */
    MaterialPlanner(Mode mode, List<Supply> supplies, Map<Long, List<Need>> needs) {
        this.mode = mode;
        this.needs = needs;
        for (Supply s : supplies) {
            supply.computeIfAbsent(s.itemId(), k -> new ArrayList<>()).add(s);
        }
        supply.values().forEach(l -> l.sort(Comparator.comparing(Supply::at)));
    }

    /** 이 공정에 붙는 소요 — 지시에 없는 순번·지정 없음은 첫 남은 공정으로 */
    List<Need> needsOf(WoIn w, OpIn op) {
        List<Need> all = needs.getOrDefault(w.woId, List.of());
        if (all.isEmpty()) {
            return List.of();
        }
        int first = w.ops.stream().mapToInt(o -> o.opSeq).min().orElse(op.opSeq);
        Set<Integer> seqs = new HashSet<>();
        w.ops.forEach(o -> seqs.add(o.opSeq));
        List<Need> out = new ArrayList<>();
        for (Need n : all) {
            int at = n.opSeq() != null && seqs.contains(n.opSeq()) ? n.opSeq() : first;
            if (at == op.opSeq) {
                out.add(n);
            }
        }
        return out;
    }

    /** DELAY: 이 공정의 소요가 모두 덮이는 가장 이른 시각(소요 없으면 null, 기간 안에 안 덮이면 {@link #NEVER}) */
    Instant coveredAt(WoIn w, OpIn op) {
        Instant max = null;
        for (Need n : needsOf(w, op)) {
            Instant c = cover(n.itemId(), n.qty());
            if (c == null) {
                return NEVER;
            }
            if (max == null || c.isAfter(max)) {
                max = c;
            }
        }
        return max;
    }

    /** 계획된 공정(시작 start)의 소요를 할당한다 */
    void commit(WoIn w, OpIn op, Instant start) {
        for (Need n : needsOf(w, op)) {
            MatOut m = new MatOut(w.woId, op.opSeq, n);
            BigDecimal before = reserved.getOrDefault(n.itemId(), BigDecimal.ZERO);
            BigDecimal atNeed = cum(n.itemId(), start).subtract(before).max(BigDecimal.ZERO);
            m.needAt = start;
            m.allocQty = atNeed.min(n.qty());
            m.shortQty = n.qty().subtract(m.allocQty);
            m.coveredAt = cover(n.itemId(), n.qty());
            // 덮이면 필요 수량 전부를 잡는다(늦게 오는 몫도 이 지시 것). 끝내 안 덮이면 남은 공급만
            BigDecimal take = m.coveredAt != null ? n.qty() : cum(n.itemId(), Instant.MAX).subtract(before).max(BigDecimal.ZERO);
            reserved.put(n.itemId(), before.add(take));
            if (m.shortQty.signum() > 0) {
                shortWos.add(w.woId);
            }
            outs.add(m);
        }
    }

    /** 계획하지 못한 공정 — 판정 없이 줄만 남긴다(공급을 잡지 않음) */
    void skip(WoIn w, OpIn op) {
        for (Need n : needsOf(w, op)) {
            outs.add(new MatOut(w.woId, op.opSeq, n));
        }
    }

    boolean isShort(long woId) {
        return shortWos.contains(woId);
    }

    List<MatOut> outs() {
        return outs;
    }

    /** 시각 t 까지(포함) 공급 누계 */
    private BigDecimal cum(long itemId, Instant t) {
        BigDecimal sum = BigDecimal.ZERO;
        for (Supply s : supply.getOrDefault(itemId, List.of())) {
            if (s.at().isAfter(t)) {
                break;
            }
            sum = sum.add(s.qty());
        }
        return sum;
    }

    /** 공급 누계 − 앞 할당 ≥ qty 가 되는 가장 이른 공급 시각, 없으면 null */
    private Instant cover(long itemId, BigDecimal qty) {
        BigDecimal left = qty.add(reserved.getOrDefault(itemId, BigDecimal.ZERO));
        BigDecimal sum = BigDecimal.ZERO;
        for (Supply s : supply.getOrDefault(itemId, List.of())) {
            sum = sum.add(s.qty());
            if (sum.compareTo(left) >= 0) {
                return s.at();
            }
        }
        return null;
    }
}
