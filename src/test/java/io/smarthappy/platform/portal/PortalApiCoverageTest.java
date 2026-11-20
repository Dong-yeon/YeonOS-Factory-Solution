package io.smarthappy.platform.portal;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import io.smarthappy.platform.auth.ProgAuth;
import io.smarthappy.platform.auth.ProgAuthExempt;
import io.smarthappy.support.PostgresTestBase;

/**
 * 가드 (docs/42 3장): /api/portal/** 핸들러는 모두 @PortalApi 이고 @ProgAuth·@ProgAuthExempt 를 섞지 않는다,
 * @PortalApi 는 /api/portal/** 밖에 없다(밖에 붙이면 회사 사용자 화면 권한 검사가 빠진다).
 */
class PortalApiCoverageTest extends PostgresTestBase {

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    RequestMappingHandlerMapping mapping;

    @Test
    void 포털_API_는_모두_PortalApi_이고_PortalApi_는_포털_밖에_없다() {
        List<String> wrong = new ArrayList<>();
        int portalCount = 0;
        for (var entry : mapping.getHandlerMethods().entrySet()) {
            List<String> patterns = List.copyOf(entry.getKey().getPatternValues());
            boolean portal = patterns.stream().anyMatch(p -> p.equals("/api/portal") || p.startsWith("/api/portal/"));
            boolean allPortal = patterns.stream().allMatch(p -> p.equals("/api/portal") || p.startsWith("/api/portal/"));
            var method = entry.getValue().getMethod();
            boolean marked = method.getAnnotation(PortalApi.class) != null;
            String name = entry.getValue().getShortLogMessage() + " " + patterns;
            if (portal) {
                portalCount++;
                if (!marked || !allPortal) {
                    wrong.add("포털 경로인데 @PortalApi 없음·포털 밖 경로와 섞임: " + name);
                }
                if (method.getAnnotation(ProgAuth.class) != null || method.getAnnotation(ProgAuthExempt.class) != null) {
                    wrong.add("포털 API 에 @ProgAuth/@ProgAuthExempt: " + name);
                }
            } else if (marked) {
                wrong.add("@PortalApi 가 포털 밖: " + name);
            }
        }
        assertThat(portalCount).as("시험 탐침(PortalProbeController)까지 못 찾으면 가드가 무의미").isPositive();
        assertThat(wrong).isEmpty();
    }
}
