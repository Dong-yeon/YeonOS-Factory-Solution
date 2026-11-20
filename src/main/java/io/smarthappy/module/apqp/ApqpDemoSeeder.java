package io.smarthappy.module.apqp;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import io.smarthappy.module.demo.DemoSeedOrder;
import io.smarthappy.platform.tenant.TenantRunner;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 개발·데모 회사에 "예시(핸드북 아님)" AP 규칙표 하나(docs/41 P3) — 회사에 규칙표가 하나라도 있으면 건너뛴다.
 * 규칙은 우리가 정한 단순 예시(S 9~10 = H, S 1~8 · O 6~10 = M, 나머지 L)이고 AIAG-VDA 핸드북 표가 아니다 — 이름·sample_yn 으로 표시.
 * 운영 회사에는 넣지 않는다(local·demo 프로필만).
 */
@Slf4j
@Component
@Profile({"local", "demo"})
@RequiredArgsConstructor
public class ApqpDemoSeeder {

    private final TenantRunner runner;
    private final JdbcTemplate jdbc;

    @Value("${happyboot.dev.comp-cd:DEMO}")
    private String compCd;
    @Value("${happyboot.dev.demo-data:true}")
    private boolean enabled;

    @EventListener(ApplicationReadyEvent.class)
    @Order(DemoSeedOrder.PMS)
    public void seed() {
        if (!enabled) {
            return;
        }
        try {
            boolean made = runner.call(compCd, () -> {
                Integer n = jdbc.queryForObject("select count(*) from apqp_ap_set", Integer.class);
                if (n != null && n > 0) {
                    return false;
                }
                Long id = jdbc.queryForObject("""
                        insert into apqp_ap_set (comp_cd, set_cd, set_nm, sample_yn, remark, use_yn, created_by, updated_by)
                        values (hb_current_comp(), 'EXAMPLE', '예시 규칙표(핸드북 아님)', 'Y',
                                '데모용 단순 예시 — 실제 사용 전 고객 요구·회사 기준으로 새 표를 만드세요', 'Y', 'demo', 'demo')
                        returning set_id""", Long.class);
                jdbc.update("""
                        insert into apqp_ap_rule (comp_cd, set_id, seq, sev_from, sev_to, occ_from, occ_to, det_from, det_to, ap, created_by)
                        values (hb_current_comp(), ?, 1, 9, 10, 1, 10, 1, 10, 'H', 'demo'),
                               (hb_current_comp(), ?, 2, 1, 8, 6, 10, 1, 10, 'M', 'demo'),
                               (hb_current_comp(), ?, 3, 1, 8, 1, 5, 1, 10, 'L', 'demo')""", id, id, id);
                return true;
            });
            if (made) {
                log.info("데모 데이터: 회사 {} 에 예시 AP 규칙표(핸드북 아님)", compCd);
            }
        } catch (RuntimeException e) {
            // 데모 시드 실패가 기동을 막지 않게 — 원인은 로그로
            log.warn("APQP 데모 데이터를 넣지 못했습니다: {}", e.toString());
        }
    }
}
