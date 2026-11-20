package io.smarthappy.module.aps;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import io.smarthappy.module.demo.DemoSeedOrder;
import io.smarthappy.module.demo.DemoSeedSupport;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 개발·데모 회사에 APS 계획 시나리오(지시 30건·계획 기준정보·계획 1건) — {@link ApsDemoFlow}. 순서 {@link DemoSeedOrder#APS}(SRM·PLM 뒤 —
 * 재고·공급원·수주를 읽는다). 흐름이 스스로 "비어 있을 때만" 판단, 실패는 기동을 막지 않는다(한 트랜잭션이라 남는 것 없음)
 */
@Slf4j
@Component
@Profile({"local", "demo"})
@RequiredArgsConstructor
public class ApsDemoSeeder {

    private final ApsDemoFlow flow;
    private final DemoSeedSupport support;

    @Value("${happyboot.dev.comp-cd:DEMO}")
    private String compCd;
    @Value("${happyboot.dev.demo-data:true}")
    private boolean enabled;

    @EventListener(ApplicationReadyEvent.class)
    @Order(DemoSeedOrder.APS)
    public void seed() {
        if (!enabled) {
            return;
        }
        support.admin(compCd).ifPresent(u -> {
            try {
                int n = flow.seed(compCd, u);
                if (n > 0) {
                    log.info("데모 데이터: 회사 {} 에 APS 계획 시나리오 지시 {}건 + 계획 1건", compCd, n);
                }
            } catch (RuntimeException e) {
                log.warn("APS 계획 시나리오를 넣지 못했습니다: {}", e.toString(), e);
            }
        });
    }
}
