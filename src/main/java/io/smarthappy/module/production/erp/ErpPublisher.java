package io.smarthappy.module.production.erp;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import io.smarthappy.module.production.erp.ErpDtos.ErpRow;
import io.smarthappy.platform.messaging.OutboxService;
import io.smarthappy.platform.policy.PolicyService;
import io.smarthappy.platform.time.BizClock;
import lombok.RequiredArgsConstructor;

/**
 * ERP 송신 발행 (docs/25 4.11) — 업무 트랜잭션 안에서 아웃박스 행 하나. 정책 ERP_SYSTEM 이 등록된 어댑터(FAKE …)일 때만,
 * NONE·EXCEL 은 발행하지 않는다. 송신기는 DB 를 못 쓰니 ERP 가 받을 값을 본문에 다 싣는다(매핑 없으면 erpItemCd = null → 어댑터 4xx).
 */
@Component
@RequiredArgsConstructor
public class ErpPublisher {

    public static final String TOPIC_WO_RESULT = "ERP_WO_RESULT";
    public static final String TOPIC_RECEIPT = "ERP_RECEIPT";
    public static final String POLICY = "ERP_SYSTEM";

    private final ErpMapper mapper;
    private final OutboxService outbox;
    private final PolicyService policies;
    private final BizClock clock;
    private final List<ErpPort> ports;

    /** 지시 완료 — 마지막 공정 유효 양품·전 공정 폐기 */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean woCompleted(long woId) {
        String system = adapter();
        if (system == null) {
            return false;
        }
        ErpRow r = mapper.woResult(woId, clock.zone().getId());
        outbox.publish(TOPIC_WO_RESULT, r.getBizKey(), body(system, r));
        return true;
    }

    /** 입고·입고 정정(음수) */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean receipt(long receiptId) {
        String system = adapter();
        if (system == null) {
            return false;
        }
        ErpRow r = mapper.receipt(receiptId, clock.zone().getId());
        outbox.publish(TOPIC_RECEIPT, r.getBizKey(), body(system, r));
        return true;
    }

    /** 정책 값이 어댑터 이름이면 그 값, 아니면 null */
    String adapter() {
        String v = policies.value(POLICY);
        return ports.stream().anyMatch(p -> p.system().equals(v)) ? v : null;
    }

    static Map<String, Object> body(String system, ErpRow r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("system", system);
        m.put("day", r.getDay() == null ? null : r.getDay().toString());
        m.put("occurredAt", r.getOccurredAt() == null ? null : r.getOccurredAt().toString());
        m.put("woNo", r.getWoNo());
        m.put("itemCd", r.getItemCd());
        m.put("itemNm", r.getItemNm());
        m.put("erpItemCd", r.getErpItemCd());
        m.put("qty", r.getQtyText());
        m.put("scrapQty", r.getScrapQtyText());
        m.put("unitCd", r.getUnitCd());
        m.put("whCd", r.getWhCd());
        m.put("lotNo", r.getLotNo());
        m.put("entryMethod", r.getEntryMethod());
        return m;
    }
}
