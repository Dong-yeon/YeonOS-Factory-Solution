package io.smarthappy.guard;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.type.filter.RegexPatternTypeFilter;

import io.smarthappy.module.demo.DemoSeedOrder;

/**
 * 모듈 데모 시더 순서 가드 — ApplicationReadyEvent 에서 도는 *DemoSeeder 는 {@code @Order(DemoSeedOrder.X)} 가 있어야 한다.
 * 없으면 LOWEST_PRECEDENCE 라 뒤에 돌아야 할 SRM·PLM 시더보다 늦게 돌 수 있다 → QMS·WMS 시더가 "데이터 없음" 판정을 SRM 입고·NCR 때문에 놓친다.
 */
class DemoSeedOrderGuardTest {

    @Test
    void 준비_이벤트_데모_시더는_모두_순서가_있다() throws Exception {
        StandardEnvironment env = new StandardEnvironment();
        env.setActiveProfiles("local", "demo");      // 시더는 @Profile 빈 — 프로필이 안 맞으면 스캔이 거른다
        ClassPathScanningCandidateComponentProvider scan = new ClassPathScanningCandidateComponentProvider(false, env);
        scan.addIncludeFilter(new RegexPatternTypeFilter(Pattern.compile(".*DemoSeeder")));
        List<String> missing = new ArrayList<>();
        int listeners = 0;
        for (BeanDefinition bd : scan.findCandidateComponents("io.smarthappy.module")) {
            Class<?> type = Class.forName(bd.getBeanClassName());
            for (Method m : type.getDeclaredMethods()) {
                EventListener el = m.getAnnotation(EventListener.class);
                if (el == null || !onReady(el, m)) {
                    continue;
                }
                listeners++;
                if (m.getAnnotation(Order.class) == null && type.getAnnotation(Order.class) == null) {
                    missing.add(type.getSimpleName() + "." + m.getName());
                }
            }
        }
        assertThat(listeners).as("SRM·PLM·PMS·APQP 시더는 찾아야 한다").isGreaterThanOrEqualTo(4);
        assertThat(missing).as("@Order(DemoSeedOrder.X) 가 없는 데모 시더").isEmpty();
    }

    @Test
    void SRM_은_QMS_WMS_뒤_PLM_은_SRM_뒤() {
        assertThat(DemoSeedOrder.SRM).isGreaterThan(DemoSeedOrder.QMS).isGreaterThan(DemoSeedOrder.WMS).isGreaterThan(DemoSeedOrder.CMS)
                .isGreaterThan(DemoSeedOrder.PMS);
        assertThat(DemoSeedOrder.PLM).isGreaterThan(DemoSeedOrder.SRM);
    }

    @Test
    void MES_이력은_CMS_뒤_QMS_WMS_앞_QMS_보충은_공정검사_뒤() {
        // MES 는 CMS 상태 원장의 가동 시간을 읽고, QMS 이력·WMS 는 MES LOT·완제품 재고를 쓴다. QMS 공정검사 시더는 검사가 있으면 건너뛴다
        assertThat(DemoSeedOrder.MES).isGreaterThan(DemoSeedOrder.CMS).isLessThan(DemoSeedOrder.QMS);
        assertThat(DemoSeedOrder.QMS_HISTORY).isGreaterThan(DemoSeedOrder.QMS).isLessThan(DemoSeedOrder.WMS);
    }

    private static boolean onReady(EventListener el, Method m) {
        List<Class<?>> types = new ArrayList<>(Arrays.asList(el.value()));
        types.addAll(Arrays.asList(el.classes()));
        if (types.isEmpty()) {
            types.addAll(Arrays.asList(m.getParameterTypes()));
        }
        return types.stream().anyMatch(ApplicationReadyEvent.class::isAssignableFrom);
    }
}
