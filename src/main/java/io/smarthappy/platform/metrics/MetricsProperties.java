package io.smarthappy.platform.metrics;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 운영 지표 (docs/52 Y2) — happyboot.metrics.*
 *
 * @param compTag    업무 지표에 회사(comp) 꼬리표를 단다. SaaS 에서만 켤 수 있고 기본 끔(docs/52 Q2) — 회사 수만큼 시계열이 늘고
 *                   (카디널리티) 지표 저장소에 회사 목록이 남는다. 설치형(onprem)은 회사가 하나라 켜도 무시한다
 * @param allowedIps /actuator/prometheus 를 받을 수 있는 IP·CIDR(쉼표). 밖에서는 "없는 것처럼" 404 — 수집기(Prometheus) 주소만
 * @param token      수집기 Bearer 토큰. 있으면 Authorization: Bearer 값이 맞아야 한다. 운영(prod)에서 지표를 열면 필수(32자 이상)
 */
@ConfigurationProperties("happyboot.metrics")
public record MetricsProperties(boolean compTag, String allowedIps, String token) {

    public static final int MIN_TOKEN_LENGTH = 32;

    public MetricsProperties {
        allowedIps = allowedIps == null || allowedIps.isBlank() ? "127.0.0.1/32,::1/128" : allowedIps.trim();
        token = token == null ? "" : token.trim();
    }
}
