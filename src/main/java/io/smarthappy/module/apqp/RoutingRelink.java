package io.smarthappy.module.apqp;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import io.smarthappy.module.apqp.ApqpDtos.StepOption;

/**
 * 라우팅 단계 다시 잇기의 짝 규칙(docs/41 11.9) — 옛 라우팅 단계 → 새 라우팅 단계는 **같은 op_seq + 같은 공정(proc_id)** 일 때만.
 * 추측 짝(공정만 같음·순서만 같음)은 하지 않는다. 짝이 없으면 부르는 쪽이 글자 단계로 남긴다
 */
final class RoutingRelink {

    private RoutingRelink() {
    }

    /** 옛 단계 id → 새 단계(짝 없으면 키 없음) */
    static Map<Long, StepOption> pair(List<StepOption> from, List<StepOption> to) {
        Map<String, StepOption> byKey = new HashMap<>();
        to.forEach(s -> byKey.put(key(s), s));
        Map<Long, StepOption> out = new HashMap<>();
        for (StepOption s : from) {
            StepOption t = byKey.get(key(s));
            if (t != null) {
                out.put(s.getStepId(), t);
            }
        }
        return out;
    }

    /** 짝 없는 단계의 글자 이름 — "10 용접"(200자 안) */
    static String text(StepOption s, String fallback) {
        String t = s == null ? fallback : s.getOpSeq() + " " + (s.getProcNm() == null ? fallback : s.getProcNm());
        return t.length() > 200 ? t.substring(0, 200) : t;
    }

    private static String key(StepOption s) {
        return s.getOpSeq() + ":" + s.getProcId();
    }
}
