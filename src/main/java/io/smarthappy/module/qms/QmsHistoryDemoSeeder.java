package io.smarthappy.module.qms;

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
 * 개발·데모 회사에 QMS 이력 보충(최종검사·SPC 한계·부적합·8D·COPQ) — {@link QmsDemoFlow}. 회사 관리자 이름으로, 순서는 {@link DemoSeedOrder#QMS_HISTORY}(DemoSeedOrder 설명 참고).
 * 흐름이 스스로 "비어 있을 때만" 판단한다(이미 있으면 0). 실패는 기동을 막지 않는다 — 한 트랜잭션이라 남는 것 없음
 */
@Slf4j
@Component
@Profile({"local", "demo"})
@RequiredArgsConstructor
public class QmsHistoryDemoSeeder {

    private final QmsDemoFlow flow;
    private final DemoSeedSupport support;

    @Value("${happyboot.dev.comp-cd:DEMO}")
    private String compCd;
    @Value("${happyboot.dev.demo-data:true}")
    private boolean enabled;

    @EventListener(ApplicationReadyEvent.class)
    @Order(DemoSeedOrder.QMS_HISTORY)
    public void seed() {
        if (!enabled) {
            return;
        }
        support.admin(compCd).ifPresent(u -> {
            long t0 = System.nanoTime();
            try {
                int n = flow.seed(compCd, u);
                if (n > 0) {
                    log.info("데모 데이터: 회사 {} 에 QMS 이력 보충(최종검사·SPC 한계·부적합·8D·COPQ) {}건, {}초", compCd, n, (System.nanoTime() - t0) / 1_000_000_000L);
                }
            } catch (RuntimeException e) {
                log.warn("QMS 이력 보충(최종검사·SPC 한계·부적합·8D·COPQ) 을(를) 넣지 못했습니다: {}", e.toString(), e);
            }
        });
    }
}
