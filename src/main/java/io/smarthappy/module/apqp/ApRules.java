package io.smarthappy.module.apqp;

import java.util.ArrayList;
import java.util.List;

import io.smarthappy.module.apqp.ApqpDtos.ApRuleRow;
import io.smarthappy.module.apqp.ApqpDtos.Coverage;

/**
 * AP 규칙표 계산(순수 함수, docs/41 4장·P3). 규칙 = S·O·D 범위 → H/M/L. 표 값은 회사가 입력한다(핸드북 표 시드 없음).
 * 1,000 조합(각 1~10)을 정확히 한 규칙이 덮어야 "사용" 가능 — 빠지면 AP 를 못 정하고, 겹치면 어느 쪽인지 모른다.
 */
final class ApRules {

    private static final int SAMPLES = 5;

    private final String[] table = new String[1000];
    private final int missing;
    private final int overlap;
    private final List<int[]> missingSamples = new ArrayList<>();
    private final List<int[]> overlapSamples = new ArrayList<>();

    private ApRules(List<ApRuleRow> rules) {
        int[] hits = new int[1000];
        for (ApRuleRow r : rules) {
            for (int s = r.getSevFrom(); s <= r.getSevTo(); s++) {
                for (int o = r.getOccFrom(); o <= r.getOccTo(); o++) {
                    for (int d = r.getDetFrom(); d <= r.getDetTo(); d++) {
                        int i = index(s, o, d);
                        hits[i]++;
                        table[i] = r.getAp();
                    }
                }
            }
        }
        int miss = 0;
        int over = 0;
        for (int s = 1; s <= 10; s++) {
            for (int o = 1; o <= 10; o++) {
                for (int d = 1; d <= 10; d++) {
                    int i = index(s, o, d);
                    if (hits[i] == 0) {
                        miss++;
                        if (missingSamples.size() < SAMPLES) {
                            missingSamples.add(new int[] {s, o, d});
                        }
                    } else if (hits[i] > 1) {
                        over++;
                        table[i] = null;
                        if (overlapSamples.size() < SAMPLES) {
                            overlapSamples.add(new int[] {s, o, d});
                        }
                    }
                }
            }
        }
        this.missing = miss;
        this.overlap = over;
    }

    static ApRules of(List<ApRuleRow> rules) {
        return new ApRules(rules);
    }

    private static int index(int s, int o, int d) {
        return (s - 1) * 100 + (o - 1) * 10 + (d - 1);
    }

    Coverage coverage() {
        return new Coverage(missing, overlap, List.copyOf(missingSamples), List.copyOf(overlapSamples));
    }

    /** 정확히 한 규칙이 덮는 조합의 AP, 하나라도 비었거나 덮임이 없거나 겹치면 null */
    String ap(Integer s, Integer o, Integer d) {
        if (s == null || o == null || d == null || s < 1 || s > 10 || o < 1 || o > 10 || d < 1 || d > 10) {
            return null;
        }
        return table[index(s, o, d)];
    }
}
