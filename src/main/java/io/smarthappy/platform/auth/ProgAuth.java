package io.smarthappy.platform.auth;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 이 메서드는 로그인 사용자가 해당 프로그램의 액션 권한을 가져야 실행된다 (없으면 403 NO_PERMISSION).
 * 화면의 버튼 숨김은 편의일 뿐이고, 판정은 여기서 한다.
 * /api/** 핸들러는 @ProgAuth 또는 @ProgAuthExempt 중 하나가 반드시 있어야 한다 (ProgAuthCoverageTest).
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface ProgAuth {

    /** sys_prog.prog_cd */
    String prog();

    Action action();
}
