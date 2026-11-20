package io.smarthappy.module.srm;

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
 * 개발·데모 회사에 SRM 데모(발주·회신·ASN·입고·클레임·평가, 지난 60일) — {@link SrmDemoFlow}. 회사 관리자 이름으로 실행,
 * 회사에 발주가 있으면 건너뛴다. 기준정보·MES(DemoDataSeeder, ApplicationRunner) → QMS·WMS 시더({@link DemoSeedOrder}) 뒤에 돈다 —
 * 이 시더가 만드는 입고·재고·NCR 이 먼저 생기면 "데이터가 없을 때만" 넣는 그 시더들이 건너뛰기 때문
 */
@Slf4j
@Component
@Profile({"local", "demo"})
@RequiredArgsConstructor
public class SrmDemoSeeder {

    private final SrmDemoFlow flow;
    private final DemoSeedSupport support;

    @Value("${happyboot.dev.comp-cd:DEMO}")
    private String compCd;
    @Value("${happyboot.dev.demo-data:true}")
    private boolean enabled;

    @EventListener(ApplicationReadyEvent.class)
    @Order(DemoSeedOrder.SRM)
    public void seed() {
        if (!enabled) {
            return;
        }
        try {
            support.admin(compCd).ifPresent(admin -> {
                int n = flow.seed(compCd, admin);
                if (n > 0) {
                    log.info("데모 데이터: 회사 {} 에 SRM 발주 {}건(회신·ASN·입고·클레임 2·평가 3, 지난 60일)", compCd, n);
                }
            });
        } catch (RuntimeException e) {
            // 데모 시드 실패가 기동을 막지 않게 — 원인은 로그로(한 트랜잭션이라 남은 것 없음)
            log.warn("SRM 데모 데이터를 넣지 못했습니다: {}", e.toString());
        }
    }
}
