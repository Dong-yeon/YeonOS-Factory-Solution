package io.smarthappy.platform.tenant;

import java.util.List;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 기동 시 RLS 우회 경로 검사. 위반이 있으면 기동을 중단한다.
 * (설치형 고객 DB 에 누군가 public 스키마로 뷰·함수·테이블을 추가한 경우도 잡는다.
 *  고객 자체 리포트용 객체는 별도 스키마에 만들도록 안내한다.)
 */
@Slf4j
@Component
@org.springframework.context.annotation.Profile("!migrate")
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
@RequiredArgsConstructor
public class TenantCatalogGuard implements ApplicationRunner {

    private final JdbcTemplate jdbc;

    @Override
    public void run(ApplicationArguments args) {
        List<String> violations = new TenantCatalogInspector(jdbc).inspect();
        if (!violations.isEmpty()) {
            throw new IllegalStateException("회사별 데이터 격리 규칙 위반으로 기동을 중단합니다:\n - "
                    + String.join("\n - ", violations));
        }
        log.info("Tenant catalog check OK (views, functions, RLS policies)");
    }
}
