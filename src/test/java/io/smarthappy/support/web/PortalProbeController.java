package io.smarthappy.support.web;

import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import io.smarthappy.platform.portal.PortalApi;
import io.smarthappy.platform.portal.PortalUser;

/**
 * 시험 전용 포털 API (src/test 에만 있음) — 업무 포털 API(SRM)가 생기기 전에 경계 필터·@PortalApi·PortalUser 를 시험한다.
 * PortalApiCoverageTest 가 이 핸들러도 본다(가드가 빈 집합을 검사하지 않게).
 */
@RestController
public class PortalProbeController {

    public static final String ME = "/api/portal/_probe/me";

    @GetMapping(ME)
    @PortalApi
    public Map<String, Object> me() {
        PortalUser u = PortalUser.current();
        return Map.of("userId", u.userId(), "compCd", u.compCd(), "custId", u.custId());
    }
}
