package io.smarthappy.platform.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;

/** 비밀번호 해시 — 웹이 아닌 실행(마이그레이션 모드 등)에서도 쓰는 빈이라 SecurityConfig(웹 전용)와 분리 */
@Configuration(proxyBeanMethods = false)
public class PasswordConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }
}
