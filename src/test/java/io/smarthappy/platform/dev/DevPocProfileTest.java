package io.smarthappy.platform.dev;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.ApplicationContext;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import io.smarthappy.support.PostgresTestBase;

/**
 * 가드: 편집 그리드 PoC(/dev/poc/grid, /api/dev/**)·hb.chart 확인 화면(/dev/poc/chart)은 local 프로필 전용 — 그 밖의 프로필(test·prod)에는 빈·경로가 없다.
 * (@ProgAuthExempt 로 권한 검사를 면제한 PoC API 가 운영에 열리지 않게)
 */
class DevPocProfileTest extends PostgresTestBase {

    @Autowired
    ApplicationContext context;

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    RequestMappingHandlerMapping mapping;

    @Test
    void local_이_아니면_PoC_빈과_경로가_없다() {
        assertThat(context.getBeansOfType(GridPocController.class)).isEmpty();
        assertThat(context.getBeansOfType(ChartPocController.class)).isEmpty();
        assertThat(mapping.getHandlerMethods().keySet())
                .flatMap(info -> info.getPatternValues())
                .noneMatch(p -> p.startsWith("/dev/") || p.startsWith("/api/dev/"));
    }
}
