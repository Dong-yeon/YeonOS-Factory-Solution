package io.smarthappy.module.production.erp;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 시험·데모용 가짜 ERP (docs/07 D4, docs/25 4.11) — 메모리, 기동마다 비운다.
 *
 * 실제 중소 ERP 의 불편한 모양을 그대로 흉내 낸다:
 * - **멱등 없음**: 같은 업무 키를 또 받으면 전표가 하나 더 생긴다
 * - 호출 제한: N 번째 호출마다 429 (저장 안 함)
 * - 4xx: ERP 품목 코드가 없거나(매핑 없음) 수량이 없으면 거부
 * - 저장한 뒤 타임아웃: N 번째 호출마다 전표는 생기고 응답만 못 받음(UNKNOWN)
 */
@Component
public class FakeErpAdapter implements ErpPort {

    public static final String SYSTEM = "FAKE";

    private final Map<String, List<String>> docs = new ConcurrentHashMap<>();
    private final AtomicInteger calls = new AtomicInteger();
    private final AtomicInteger seq = new AtomicInteger();
    private volatile int rateLimitEvery;
    private volatile int timeoutEvery;

    public FakeErpAdapter(@Value("${happyboot.erp.fake.rate-limit-every:0}") int rateLimitEvery,
            @Value("${happyboot.erp.fake.timeout-every:0}") int timeoutEvery) {
        this.rateLimitEvery = Math.max(0, rateLimitEvery);
        this.timeoutEvery = Math.max(0, timeoutEvery);
    }

    @Override
    public String system() {
        return SYSTEM;
    }

    @Override
    public ErpResult post(ErpDoc doc) {
        int n = calls.incrementAndGet();
        if (rateLimitEvery > 0 && n % rateLimitEvery == 0) {
            return ErpResult.of(Kind.RATE_LIMITED, "429 too many requests");
        }
        Object item = doc.body().get("erpItemCd");
        Object qty = doc.body().get("qty");
        if (!(item instanceof String s) || s.isBlank()) {
            return ErpResult.of(Kind.REJECTED, "400 ITEM_UNMAPPED");
        }
        if (!(qty instanceof String q) || q.isBlank()) {
            return ErpResult.of(Kind.REJECTED, "400 QTY_REQUIRED");
        }
        String no = "FERP-" + seq.incrementAndGet();
        docs.computeIfAbsent(doc.compCd() + "|" + doc.bizKey(), k -> new ArrayList<>()).add(no);
        if (timeoutEvery > 0 && n % timeoutEvery == 0) {
            return ErpResult.of(Kind.UNKNOWN, "read timeout after store");
        }
        return ErpResult.ok(no);
    }

    @Override
    public int lookup(String compCd, String bizKey) {
        List<String> l = docs.get(compCd + "|" + bizKey);
        return l == null ? 0 : l.size();
    }

    /** 시험 조절 — 0 은 끔 */
    public void configure(int rateLimitEvery, int timeoutEvery) {
        this.rateLimitEvery = Math.max(0, rateLimitEvery);
        this.timeoutEvery = Math.max(0, timeoutEvery);
        calls.set(0);
    }

    /** 시험용 — 전표·카운터 비우기 */
    public void reset() {
        docs.clear();
        calls.set(0);
        rateLimitEvery = 0;
        timeoutEvery = 0;
    }

    /** 그 회사가 받은 전표 수 (모든 키 합) */
    public int count(String compCd) {
        return docs.entrySet().stream().filter(e -> e.getKey().startsWith(compCd + "|")).mapToInt(e -> e.getValue().size()).sum();
    }
}
