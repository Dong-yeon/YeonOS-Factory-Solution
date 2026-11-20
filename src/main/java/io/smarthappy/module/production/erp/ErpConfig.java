package io.smarthappy.module.production.erp;

import java.util.List;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** ERP 송신 토픽 2개 (docs/25 4.11) — 아웃박스는 토픽마다 송신기 빈이 있어야 발행을 받는다 */
@Configuration
public class ErpConfig {

    @Bean
    ErpOutboundHandler erpWoResultHandler(List<ErpPort> ports) {
        return new ErpOutboundHandler(ErpPublisher.TOPIC_WO_RESULT, "WO_RESULT", ports);
    }

    @Bean
    ErpOutboundHandler erpReceiptHandler(List<ErpPort> ports) {
        return new ErpOutboundHandler(ErpPublisher.TOPIC_RECEIPT, "RECEIPT", ports);
    }
}
