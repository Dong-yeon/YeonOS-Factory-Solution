package io.smarthappy.platform.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import io.smarthappy.platform.portal.PortalApi;
import io.smarthappy.support.PostgresTestBase;

/**
 * 가드: /api/** 핸들러는 모두 @ProgAuth 또는 @ProgAuthExempt(이유) 가 있어야 한다 — 협력사 포털 API(/api/portal/**)는 @PortalApi
 * (그 짝 규칙은 PortalApiCoverageTest, docs/42 3장).
 * 권한 검사를 깜빡한 API 가 생기면 여기서 실패한다 (docs/04 2.1 — 규칙을 사람 기억이 아니라 테스트가 지킨다).
 */
class ProgAuthCoverageTest extends PostgresTestBase {

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    RequestMappingHandlerMapping mapping;

    @Test
    void 모든_API_핸들러에_권한_표시가_있다() {
        List<String> missing = new ArrayList<>();
        int apiCount = 0;
        for (var entry : mapping.getHandlerMethods().entrySet()) {
            boolean isApi = entry.getKey().getPatternValues().stream().anyMatch(p -> p.startsWith("/api/"));
            if (!isApi) {
                continue;
            }
            apiCount++;
            var method = entry.getValue().getMethod();
            ProgAuthExempt exempt = method.getAnnotation(ProgAuthExempt.class);
            if (method.getAnnotation(ProgAuth.class) == null && (exempt == null || exempt.reason().isBlank())
                    && method.getAnnotation(PortalApi.class) == null) {
                missing.add(entry.getValue().getShortLogMessage() + " " + entry.getKey().getPatternValues());
            }
        }
        assertThat(apiCount).as("API 핸들러를 하나도 못 찾으면 가드가 무의미").isPositive();
        assertThat(missing).as("@ProgAuth 또는 @ProgAuthExempt(reason) 없는 API").isEmpty();
    }
}
