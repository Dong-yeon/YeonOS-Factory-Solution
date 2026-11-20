package io.smarthappy.module.wms.alloc;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 차감 전략 (docs/30 W4, 4.4) — **순수 함수**: 후보(위치 × LOT × 상태별 재고)와 요청 수량을 받아 어디서 얼마를 꺼낼지 정한다.
 * DB 를 보지 않는다. 2부의 투입(ISSUE_WO)·피킹이 같은 함수를 부른다.
 *
 * <ul>
 * <li>꺼낼 수 있는 상태: AVAILABLE, {@code availableOnly = false} 면 QC 도. HOLD·BLOCKED 는 항상 제외</li>
 * <li>꺼낼 수 있는 칸: {@code availLoc = false}(가용 정의 밖 — 출하 대기·검사 용도·폐기 칸, docs/53 2장) 면 상태와 무관하게 제외(LOCATION)</li>
 * <li>유효기한: {@code excludeExpiredOn} 이 있으면 유효기한 &lt; 그날 인 후보 제외(유효기한 당일까지 사용 가능)</li>
 * <li>정렬: 지정 LOT → 전략 키 → 결정적 동률 깨기(위치 id → LOT id, LOT 없음 먼저). 같은 입력이면 항상 같은 결과</li>
 * <li>FIFO = 입고일 → 피킹 순서, FEFO = 유효기한(없으면 맨 뒤) → 입고일 → 피킹 순서, LOC_SEQ = 피킹 순서 → 입고일</li>
 * <li>{@code allowPartialLot = false}: LOT 이 있는 후보는 통째로만(남은 요청보다 크면 건너뜀 — 파렛트·포장 단위). LOT 없는 후보는 항상 나눠 꺼낸다</li>
 * <li>결과 합 + 부족량 = 요청 수량(항상)</li>
 * </ul>
 */
public final class AllocationStrategy {

    public enum Strategy {
        FIFO, FEFO, LOC_SEQ
    }

    /** 왜 후보에서 빠졌는지 */
    public enum SkipReason {
        STATUS, EXPIRED, WHOLE_LOT, EMPTY, LOCATION, HOLD
    }

    /**
     * 후보 한 칸.
     *
     * @param fifoDate  선입 기준일(입고일) — 없으면 맨 뒤
     * @param expiryDate 유효기한(그날까지 사용 가능) — 없으면 기한 없음
     * @param availLoc   꺼낼 수 있는 칸인가(가용 정의 AvailSql.availLoc) — 아니면 LOCATION 으로 건너뜀
     * @param lotFree    보류 아닌 LOT 인가(가용 정의 AvailSql.lotFree, A-1) — 아니면 HOLD 로 건너뜀
     */
    public record Candidate(long nodeId, int pickSeq, Long lotId, String lotNo, LocalDate fifoDate, LocalDate expiryDate, String status,
            BigDecimal qty, boolean availLoc, boolean lotFree) {

        /** 보류를 따지지 않는 후보(보류 아닌 LOT) */
        public Candidate(long nodeId, int pickSeq, Long lotId, String lotNo, LocalDate fifoDate, LocalDate expiryDate, String status,
                BigDecimal qty, boolean availLoc) {
            this(nodeId, pickSeq, lotId, lotNo, fifoDate, expiryDate, status, qty, availLoc, true);
        }

        /** 칸 용도를 따지지 않는 후보(꺼낼 수 있는 칸) */
        public Candidate(long nodeId, int pickSeq, Long lotId, String lotNo, LocalDate fifoDate, LocalDate expiryDate, String status,
                BigDecimal qty) {
            this(nodeId, pickSeq, lotId, lotNo, fifoDate, expiryDate, status, qty, true);
        }
    }

    /**
     * @param designatedLots 이 LOT 들을 먼저(순서 = 목록 순서). 후보에 없는 지정 LOT 은 결과 {@code missingLots} 로
     */
    public record Options(Strategy strategy, List<Long> designatedLots, boolean availableOnly, LocalDate excludeExpiredOn,
            boolean allowPartialLot) {

        public Options {
            Objects.requireNonNull(strategy, "strategy");
            designatedLots = designatedLots == null ? List.of() : List.copyOf(designatedLots);
        }

        public static Options of(Strategy s) {
            return new Options(s, List.of(), true, null, true);
        }
    }

    public record Pick(Candidate from, BigDecimal qty) {
    }

    public record Skip(Candidate candidate, SkipReason reason) {
    }

    /** picks 합 + shortage = 요청 수량 */
    public record Result(List<Pick> picks, BigDecimal shortage, List<Skip> skipped, List<Long> missingLots) {

        public BigDecimal allocated() {
            return picks.stream().map(Pick::qty).reduce(BigDecimal.ZERO, BigDecimal::add);
        }

        public boolean complete() {
            return shortage.signum() == 0;
        }
    }

    private AllocationStrategy() {
    }

    /**
     * @throws IllegalArgumentException 요청 수량이 없거나 0 이하
     */
    public static Result allocate(List<Candidate> candidates, BigDecimal qty, Options opt) {
        if (qty == null || qty.signum() <= 0) {
            throw new IllegalArgumentException("qty must be positive");
        }
        Objects.requireNonNull(opt, "options");
        List<Candidate> usable = new ArrayList<>();
        List<Skip> skipped = new ArrayList<>();
        for (Candidate c : candidates == null ? List.<Candidate>of() : candidates) {
            if (c == null) {
                continue;
            }
            SkipReason r = skipReason(c, opt);
            if (r == null) {
                usable.add(c);
            } else {
                skipped.add(new Skip(c, r));
            }
        }
        usable.sort(order(opt));

        Set<Long> present = new LinkedHashSet<>();
        usable.forEach(c -> {
            if (c.lotId() != null) {
                present.add(c.lotId());
            }
        });
        List<Long> missing = opt.designatedLots().stream().filter(l -> l != null && !present.contains(l)).distinct().toList();

        List<Pick> picks = new ArrayList<>();
        BigDecimal left = qty;
        for (Candidate c : usable) {
            if (left.signum() == 0) {
                break;
            }
            if (c.qty().compareTo(left) <= 0) {
                picks.add(new Pick(c, c.qty()));
                left = left.subtract(c.qty());
            } else if (opt.allowPartialLot() || c.lotId() == null) {
                picks.add(new Pick(c, left));
                left = BigDecimal.ZERO;
            } else {
                skipped.add(new Skip(c, SkipReason.WHOLE_LOT));
            }
        }
        return new Result(List.copyOf(picks), left, List.copyOf(skipped), missing);
    }

    private static SkipReason skipReason(Candidate c, Options opt) {
        if (c.qty() == null || c.qty().signum() <= 0) {
            return SkipReason.EMPTY;
        }
        if (!c.availLoc()) {
            return SkipReason.LOCATION;
        }
        if (!c.lotFree()) {
            return SkipReason.HOLD;
        }
        boolean statusOk = "AVAILABLE".equals(c.status()) || (!opt.availableOnly() && "QC".equals(c.status()));
        if (!statusOk) {
            return SkipReason.STATUS;
        }
        if (opt.excludeExpiredOn() != null && c.expiryDate() != null && c.expiryDate().isBefore(opt.excludeExpiredOn())) {
            return SkipReason.EXPIRED;
        }
        return null;
    }

    static Comparator<Candidate> order(Options opt) {
        List<Long> designated = opt.designatedLots();
        Comparator<Candidate> byDesignated = Comparator.comparingInt(c -> {
            int i = c.lotId() == null ? -1 : designated.indexOf(c.lotId());
            return i < 0 ? Integer.MAX_VALUE : i;
        });
        Comparator<Candidate> fifo = Comparator.comparing(Candidate::fifoDate, Comparator.nullsLast(Comparator.naturalOrder()));
        Comparator<Candidate> expiry = Comparator.comparing(Candidate::expiryDate, Comparator.nullsLast(Comparator.naturalOrder()));
        Comparator<Candidate> seq = Comparator.comparingInt(Candidate::pickSeq);
        Comparator<Candidate> key = switch (opt.strategy()) {
            case FIFO -> fifo.thenComparing(seq);
            case FEFO -> expiry.thenComparing(fifo).thenComparing(seq);
            case LOC_SEQ -> seq.thenComparing(fifo);
        };
        return byDesignated.thenComparing(key)
                .thenComparingLong(Candidate::nodeId)
                .thenComparing(Candidate::lotId, Comparator.nullsFirst(Comparator.naturalOrder()))
                .thenComparing(Candidate::status);
    }
}
