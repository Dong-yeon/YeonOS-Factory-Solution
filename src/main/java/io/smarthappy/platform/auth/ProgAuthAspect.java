package io.smarthappy.platform.auth;

import java.lang.reflect.Method;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import io.smarthappy.platform.common.web.BusinessException;
import io.smarthappy.platform.common.web.HbErrorCode;
import io.smarthappy.platform.log.AccessEvent;
import io.smarthappy.platform.log.AccessLogService;
import io.smarthappy.platform.log.AccessLogService.Result;
import io.smarthappy.platform.module.CompModuleService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * @ProgAuth 가 붙은 메서드 실행 전에 권한을 확인한다.
 * 트랜잭션보다 먼저(가장 바깥) 실행 — 권한이 없으면 업무 트랜잭션을 열지 않는다.
 * 접속 로그: 거부는 PERM_DENIED, 조회(SEARCH) 외 기능은 실행 결과(OK/FAIL)를 ACTION 으로 남긴다.
 *
 * 포인트컷에서 애노테이션을 인자로 바인딩하지 않는다(@annotation(progAuth) 는 Spring 7 + AspectJ 에서
 * "Required to bind 2 arguments" 로 실패했다, 2026-10-15). 메서드에서 직접 읽는다.
 */
@Slf4j
@Aspect
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
@RequiredArgsConstructor
public class ProgAuthAspect {

    private final PermissionService permissions;
    private final AccessLogService accessLog;
    private final CompModuleService modules;

    @Around("@annotation(io.smarthappy.platform.auth.ProgAuth)")
    public Object check(ProceedingJoinPoint pjp) throws Throwable {
        Method method = ((MethodSignature) pjp.getSignature()).getMethod();
        ProgAuth progAuth = AnnotatedElementUtils.findMergedAnnotation(method, ProgAuth.class);
        if (progAuth == null) {
            // 포인트컷과 맞지 않는 경우는 없어야 한다 — 안전하게 거부
            log.error("@ProgAuth 를 읽지 못했습니다 method={}", pjp.getSignature().toShortString());
            permissions.require("__unknown__", Action.SEARCH);
            return null;
        }
        if (!permissions.actions(progAuth.prog()).contains(progAuth.action())) {
            log.info("권한 없음 prog={} action={} method={}", progAuth.prog(), progAuth.action(),
                    pjp.getSignature().toShortString());
            // 권한이 없는 까닭이 "이 회사가 쓰지 않는 모듈"이면 따로 알린다(docs/80) — 판정은 권한 SQL 이 이미 했다
            String off = modules.disabledModuleOfProg(progAuth.prog());
            accessLog.recordCurrent(AccessEvent.PERM_DENIED, progAuth.prog(), progAuth.action().name(), Result.DENIED,
                    off == null ? null : "module=" + off);
            if (off != null) {
                throw new BusinessException(HbErrorCode.MODULE_DISABLED);
            }
            permissions.require(progAuth.prog(), progAuth.action());
        }
        if (progAuth.action() == Action.SEARCH) {
            return pjp.proceed();
        }
        boolean ok = false;
        try {
            Object result = pjp.proceed();
            ok = true;
            return result;
        } finally {
            accessLog.recordCurrent(AccessEvent.ACTION, progAuth.prog(), progAuth.action().name(),
                    ok ? Result.OK : Result.FAIL, AccessLogService.currentDetail());
        }
    }
}
