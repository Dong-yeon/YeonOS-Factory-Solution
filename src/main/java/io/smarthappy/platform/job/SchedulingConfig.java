package io.smarthappy.platform.job;

import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 스케줄 작업 켜기 (happyboot.scheduling.enabled, 기본 true). 테스트는 끄고 작업을 직접 부른다.
 * 작업은 모두 TenantRunner 로 회사 범위를 열고, 회사마다 JobLock 으로 중복 실행을 막는다.
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnBooleanProperty(name = "happyboot.scheduling.enabled", matchIfMissing = true)
public class SchedulingConfig {
}
