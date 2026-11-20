package io.smarthappy.module.plm;

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
 * 개발·데모 회사에 PLM 데모(ECR·ECO·도면 리비전·4M 연결, 지난 60일) — {@link PlmDemoFlow}. 회사 관리자 이름으로 실행,
 * 회사에 ECR·ECO 가 있으면 건너뛴다. 기준정보·MES → QMS·WMS → SRM 시더({@link DemoSeedOrder}) 뒤에 돈다 — 4M 세부·도면은 QMS 표,
 * 설비·공정은 기준정보 데모를 쓰고, QMS 시더가 "데이터가 없을 때만" 넣는 관리문서·4M 을 먼저 만들지 않게
 */
@Slf4j
@Component
@Profile({"local", "demo"})
@RequiredArgsConstructor
public class PlmDemoSeeder {

    private final PlmDemoFlow flow;
    private final DemoSeedSupport support;

    @Value("${happyboot.dev.comp-cd:DEMO}")
    private String compCd;
    @Value("${happyboot.dev.demo-data:true}")
    private boolean enabled;

    @EventListener(ApplicationReadyEvent.class)
    @Order(DemoSeedOrder.PLM)
    public void seed() {
        if (!enabled) {
            return;
        }
        try {
            support.admin(compCd).ifPresent(admin -> {
                int n = flow.seed(compCd, admin);
                if (n > 0) {
                    log.info("데모 데이터: 회사 {} 에 PLM ECO {}건(도면 리비전 2·4M 변경 2·ECR 2, 지난 60일)", compCd, n);
                }
            });
        } catch (RuntimeException e) {
            // 데모 시드 실패가 기동을 막지 않게 — 원인은 로그로(한 트랜잭션이라 남은 것 없음)
            log.warn("PLM 데모 데이터를 넣지 못했습니다: {}", e.toString());
        }
    }
}
