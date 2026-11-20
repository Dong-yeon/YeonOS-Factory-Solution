package io.smarthappy.module.wms.inv;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import io.smarthappy.module.production.erp.ErpOutboundHandler;
import io.smarthappy.module.production.erp.ErpPort;
import io.smarthappy.module.wms.inv.CustRtnDtos.Rtn;
import io.smarthappy.module.wms.ship.ShipErpMapper;
import io.smarthappy.platform.messaging.OutboxService;
import io.smarthappy.platform.policy.PolicyService;
import lombok.RequiredArgsConstructor;

/**
 * ERP 고객 반품 송신 발행 (docs/59 R-4 "출하 송신 아웃박스 모양 그대로", 구현 2026-11-02) — 반품 입고·취소 트랜잭션 안에서 아웃박스 행 하나(ERP_CUST_RTN).
 * 송신기는 P2 ERP 송신기({@link ErpOutboundHandler}, 종류 CUST_RTN) — 정책 ERP_SYSTEM 이 등록된 어댑터일 때만 발행({@code ShipErpPublisher} 와 같은 규칙).
 * 업무 키 = 반품번호-R(입고) / 반품번호-C(취소) — 반품마다 입고·취소는 한 번씩이라 키가 겹치지 않는다. 취소는 음수 수량.
 */
@Component
@RequiredArgsConstructor
public class CustRtnErpPublisher {

    public static final String TOPIC = "ERP_CUST_RTN";
    static final String POLICY = "ERP_SYSTEM";

    private final OutboxService outbox;
    private final PolicyService policies;
    private final ShipErpMapper erpItems;
    private final List<ErpPort> ports;

    /**
     * @param sign 1 = 반품 입고, -1 = 반품 취소
     * @param at   발생 시각(입고 = 받은 시각, 취소 = 취소 시각)
     * @return 발행한 행 수(어댑터 없으면 0)
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public int publish(Rtn r, int sign, OffsetDateTime at) {
        String sys = policies.value(POLICY);
        if (ports.stream().noneMatch(p -> p.system().equals(sys))) {
            return 0;
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("system", sys);
        m.put("day", at.toLocalDate().toString());
        m.put("occurredAt", at.toString());
        m.put("rtnNo", r.getRtnNo());
        m.put("shipNo", r.getShipNo());
        m.put("custCd", r.getCustCd());
        m.put("itemCd", r.getItemCd());
        m.put("itemNm", r.getItemNm());
        m.put("erpItemCd", erpItems.erpItemCd(r.getItemId()));
        m.put("qty", (sign < 0 ? r.getQty().negate() : r.getQty()).stripTrailingZeros().toPlainString());
        m.put("unitCd", r.getUnitCd());
        m.put("lotNo", r.getLotNo());
        m.put("reasonCd", r.getReasonCd());
        m.put("capaNo", r.getCapaNo());
        m.put("ncrNo", r.getNcrNo());
        outbox.publish(TOPIC, r.getRtnNo() + (sign < 0 ? "-C" : "-R"), m);
        return 1;
    }

    /** 아웃박스는 토픽마다 송신기 빈이 있어야 발행을 받는다 — P2 송신기를 종류 CUST_RTN 으로 하나 더 */
    @Configuration
    static class Config {

        @Bean
        ErpOutboundHandler erpCustRtnHandler(List<ErpPort> ports) {
            return new ErpOutboundHandler(TOPIC, "CUST_RTN", ports);
        }
    }
}
