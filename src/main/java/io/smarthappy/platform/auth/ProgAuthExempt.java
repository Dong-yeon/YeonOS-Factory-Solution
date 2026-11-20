package io.smarthappy.platform.auth;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 로그인만 하면 누구나 쓰는 API (세션 정보, 내 메뉴, 화면 문구 등). 프로그램 권한 검사를 하지 않는다.
 * 왜 예외인지 reason 에 적는다 — 리뷰·감사 때 근거가 된다.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface ProgAuthExempt {
    String reason();
}
