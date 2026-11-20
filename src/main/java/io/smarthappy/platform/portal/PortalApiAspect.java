package io.smarthappy.platform.portal;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import io.smarthappy.platform.log.AccessEvent;
import io.smarthappy.platform.log.AccessLogService;
import io.smarthappy.platform.log.AccessLogService.Result;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * @PortalApi 메서드는 협력사 사용자만 — 경계 필터 뒤 두 번째 막기 (docs/42 3장).
 * ProgAuthAspect 처럼 트랜잭션보다 먼저(가장 바깥): 거부면 업무 트랜잭션을 열지 않는다. 거부는 접속 로그 PERM_DENIED.
 */
@Slf4j
@Aspect
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
@RequiredArgsConstructor
public class PortalApiAspect {

    private final AccessLogService accessLog;

    @Around("@annotation(io.smarthappy.platform.portal.PortalApi)")
    public Object check(ProceedingJoinPoint pjp) throws Throwable {
        try {
            PortalUser.current();
        } catch (RuntimeException e) {
            log.info("포털 API 거부(협력사 사용자 아님) method={}", pjp.getSignature().toShortString());
            accessLog.recordCurrent(AccessEvent.PERM_DENIED, "portal", null, Result.DENIED, null);
            throw e;
        }
        return pjp.proceed();
    }
}
