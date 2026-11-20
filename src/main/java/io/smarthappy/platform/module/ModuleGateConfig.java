package io.smarthappy.platform.module;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import lombok.RequiredArgsConstructor;

/** 현장 단말 API 에 모듈 문을 단다 (docs/80 4장) — 사무 API 는 @ProgAuth(권한 SQL)가 이미 거른다 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication
@RequiredArgsConstructor
public class ModuleGateConfig implements WebMvcConfigurer {

    private final ModuleGateInterceptor gate;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(gate).addPathPatterns("/api/pop/**");
    }
}
