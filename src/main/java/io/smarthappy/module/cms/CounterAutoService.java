package io.smarthappy.module.cms;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.smarthappy.module.cms.CmsCounterAutoMapper.Src;
import io.smarthappy.platform.time.BizClock;
import lombok.RequiredArgsConstructor;

/**
 * 카운터 자동 입력 (C10, docs/33 11.10, D23) — 정기 작업이 회사 범위 트랜잭션으로 부른다(요청 밖 = TenantRunner).
 * 아직 세지 않은 원장 행을 카운터별로 모아 AUTO 읽기 한 행(증가량 = 합) + 출처 행들을 넣고, 같은 트랜잭션에서 그 카운터의 사용량 예방점검을 만든다(C3).
 * 원장 행 × 카운터는 출처 표 PK 로 한 번만 — 늦게 커밋된 실적·늦게 닫힌 구간은 다음 실행에서 잡힌다.
 */
@Service
@RequiredArgsConstructor
public class CounterAutoService {

    static final String BY = "SYSTEM";
    /** 이보다 오래된 원장은 보지 않는다(작업이 이만큼 멈췄을 때만 놓친다 — 매시 작업, 표 전체를 훑지 않게) */
    static final Duration LOOKBACK = Duration.ofDays(7);
    static final int MAX_SOURCES = 20000;

    private final CmsCounterAutoMapper mapper;
    private final PmGenerator generator;
    private final BizClock clock;

    public record AutoResult(int counters, int sources, int pmRuns) {
    }

    @Transactional
    public AutoResult run() {
        OffsetDateTime now = clock.instant().atOffset(ZoneOffset.UTC);
        List<Src> pending = mapper.pending(now.minus(LOOKBACK), MAX_SOURCES);
        Map<Long, List<Src>> byCounter = new LinkedHashMap<>();
        for (Src s : pending) {
            byCounter.computeIfAbsent(s.getCounterId(), k -> new ArrayList<>()).add(s);
        }
        int pm = 0;
        for (Map.Entry<Long, List<Src>> e : byCounter.entrySet()) {
            BigDecimal delta = BigDecimal.ZERO;
            int results = 0;
            int states = 0;
            for (Src s : e.getValue()) {
                delta = delta.add(s.getQty());
                if ("RESULT".equals(s.getSrcKind())) {
                    results++;
                } else {
                    states++;
                }
            }
            // 카운터 자릿수(C3)에 맞추되 매번 반올림하면 어긋나 쌓이므로(0.83h 를 매시 1h 로) 정확한 출처 합을 자릿수로 내린 값 − 지금까지 넣은 값
            BigDecimal exact = mapper.srcTotal(e.getKey()).add(delta);
            BigDecimal credit = credit(exact, mapper.autoCredited(e.getKey()), mapper.scaleOf(e.getKey()));
            long readingId = mapper.insertAutoReading(e.getKey(), now, credit, remark(results, states), BY);
            for (Src s : e.getValue()) {
                mapper.insertSrc(s, readingId);
            }
            pm += generator.generateForCounter(e.getKey(), BY).runs();
        }
        return new AutoResult(byCounter.size(), pending.size(), pm);
    }

    /** 이번에 넣을 증가량 = 내림(정확한 누적, 자릿수) − 지금까지 넣은 값. 0 아래로는 안 감(무효로 한 AUTO 도 넣은 것으로 친다) */
    static BigDecimal credit(BigDecimal exactTotal, BigDecimal credited, int scale) {
        // 출처는 소수 12자리로 잘려 있어 1/3 같은 값의 합이 1.999… 이 된다 — 9자리로 반올림한 뒤 내린다
        BigDecimal d = exactTotal.setScale(9, java.math.RoundingMode.HALF_UP).setScale(scale, java.math.RoundingMode.FLOOR).subtract(credited);
        return d.signum() < 0 ? BigDecimal.ZERO.setScale(scale) : d;
    }

    /** 읽기 비고 — 출처 건수(화면에 그대로) */
    static String remark(int results, int states) {
        StringBuilder b = new StringBuilder("AUTO");
        if (results > 0) {
            b.append(" RESULT×").append(results);
        }
        if (states > 0) {
            b.append(" STATE×").append(states);
        }
        return b.toString();
    }
}
