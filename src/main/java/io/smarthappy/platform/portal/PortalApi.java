package io.smarthappy.platform.portal;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 협력사 포털 API (/api/portal/**, docs/42 3장) — @ProgAuth 대신 붙인다. 화면 권한(sys_prog)은 쓰지 않는다(포털 메뉴는 코드로 고정).
 * 협력사 사용자만 통과한다(PortalApiAspect) — 회사 사용자는 PortalBoundaryFilter 가 먼저 막고, 여기서 한 번 더.
 * 가드 시험: /api/portal/** 의 모든 핸들러에 붙었는지, /api/portal/** 밖에는 없는지(PortalApiCoverageTest).
 * 행 거름은 서비스 몫 — 소속 협력사는 PortalUser.current().custId() 로만 얻는다(요청 값으로 받지 않는다, docs/42 4장).
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface PortalApi {
}
