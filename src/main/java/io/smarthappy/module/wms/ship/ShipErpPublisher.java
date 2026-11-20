package io.smarthappy.module.wms.ship;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import io.smarthappy.module.production.erp.ErpOutboundHandler;
import io.smarthappy.module.production.erp.ErpPort;
import io.smarthappy.module.wms.ship.ShipDtos.PickRow;
import io.smarthappy.module.wms.ship.ShipDtos.ShipLine;
import io.smarthappy.module.wms.ship.ShipDtos.ShipView;
import io.smarthappy.platform.messaging.OutboxService;
import io.smarthappy.platform.policy.PolicyService;
import lombok.RequiredArgsConstructor;

/**
 * ERP 출하 송신 발행 (docs/30 12.5 D-B17, docs/07:94) — 출하 확정·취소 트랜잭션 안에서 출하 줄마다 아웃박스 행 하나(ERP_SHIP).
 * 송신기는 P2 ERP 송신기({@link ErpOutboundHandler}, 종류 SHIP)를 그대로 쓴다 — 정책 ERP_SYSTEM 이 등록된 어댑터일 때만 발행(P2 ErpPublisher 와 같은 규칙).
 * 업무 키 = 출하번호-줄번호-v{머리 row_ver}(확정·취소마다 다름), 취소는 음수 수량. 줄의 팔레트 번호·SSCC 목록(B6b-2)도 싣는다.
 */
@Component
@RequiredArgsConstructor
public class ShipErpPublisher {

    public static final String TOPIC = "ERP_SHIP";
    static final String POLICY = "ERP_SYSTEM";

    private final OutboxService outbox;
    private final PolicyService policies;
    private final ShipErpMapper mapper;
    private final List<ErpPort> ports;

    /** @param sign 1 = 출하, -1 = 출하 취소 */
    @Transactional(propagation = Propagation.MANDATORY)
    public int shipped(ShipView v, List<PickRow> picks, int sign, OffsetDateTime at) {
        String v0 = policies.value(POLICY);
        if (ports.stream().noneMatch(p -> p.system().equals(v0))) {
            return 0;
        }
        Map<Long, List<PickRow>> byLine = picks.stream().collect(Collectors.groupingBy(PickRow::getShipLineId));
        int n = 0;
        for (ShipLine l : v.lines()) {
            List<PickRow> ps = byLine.get(l.getShipLineId());
            if (ps == null || ps.isEmpty()) {
                continue;
            }
            BigDecimal qty = ps.stream().map(PickRow::getQty).reduce(BigDecimal.ZERO, BigDecimal::add);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("system", v0);
            m.put("day", at.toLocalDate().toString());
            m.put("occurredAt", at.toString());
            m.put("shipNo", v.head().getShipNo());
            m.put("custCd", v.head().getCustCd());
            m.put("orderNo", l.getOrderNo());
            m.put("orderLineNo", l.getOrderLineNo());
            m.put("itemCd", l.getItemCd());
            m.put("itemNm", l.getItemNm());
            m.put("erpItemCd", mapper.erpItemCd(l.getItemId()));
            m.put("qty", (sign < 0 ? qty.negate() : qty).stripTrailingZeros().toPlainString());
            m.put("unitCd", l.getUnitCd());
            m.put("lotNo", ps.stream().map(PickRow::getLotNo).filter(x -> x != null).distinct().collect(Collectors.joining(",")));
            // 이 줄이 실린 팔레트(B6b-2) — 내부 번호·SSCC(GS1 업체코드가 있을 때만), 없으면 빈 목록
            m.put("palletNos", ps.stream().map(PickRow::getPalletNo).filter(x -> x != null).distinct().sorted().toList());
            m.put("ssccs", ps.stream().map(PickRow::getSscc).filter(x -> x != null).distinct().sorted().toList());
            outbox.publish(TOPIC, v.head().getShipNo() + "-" + l.getLineNo() + "-v" + v.head().getRowVer(), m);
            n++;
        }
        return n;
    }

    /** 아웃박스는 토픽마다 송신기 빈이 있어야 발행을 받는다 — P2 송신기를 종류 SHIP 으로 하나 더 */
    @Configuration
    static class Config {

        @Bean
        ErpOutboundHandler erpShipHandler(List<ErpPort> ports) {
            return new ErpOutboundHandler(TOPIC, "SHIP", ports);
        }
    }
}
