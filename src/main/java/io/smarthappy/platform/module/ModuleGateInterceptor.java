package io.smarthappy.platform.module;

import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.servlet.HandlerInterceptor;

import io.smarthappy.platform.common.web.BusinessException;
import io.smarthappy.platform.common.web.HbErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;

/**
 * 현장 단말 API(/api/pop/**) 의 모듈 문 (docs/80 4장). 단말은 사람 권한(@ProgAuth)을 타지 않아 권한 SQL 의 모듈 판정이 닿지 않는다 —
 * 그래서 경로로 모듈을 정해 꺼졌으면 403 MODULE_DISABLED. 활성화·세션·배지·언어·키오스크처럼 모든 단말에 공통인 경로는 막지 않는다.
 * 단말 화면(/pop·/pop/wh)은 그 컨트롤러가 같은 판정({@link CompModuleService#isOn})으로 안내 화면을 낸다.
 *
 * 순서가 중요하다 — 앞에서 맞는 규칙이 이긴다(검사·창고·설비 경로가 생산 경로보다 먼저).
 * 플랫폼은 업무 모듈을 모른다(ModuleBoundaryGuardTest) — 경로 글자만 든다. 경로 원본은 PopSecurityConfig 의 PDA_PATHS·POP_PATHS.
 */
@Component
@RequiredArgsConstructor
public class ModuleGateInterceptor implements HandlerInterceptor {

    /** (경로 패턴, 모듈) — 위에서부터 */
    static final List<Map.Entry<String, String>> RULES = List.of(
            Map.entry("/api/pop/wh/**", "WMS"),
            Map.entry("/api/pop/procs/*/insp", "QMS"),
            Map.entry("/api/pop/procs/*/insp/**", "QMS"),
            Map.entry("/api/pop/breakdown", "CMS"),
            Map.entry("/api/pop/breakdown/**", "CMS"),
            Map.entry("/api/pop/pm/**", "CMS"),
            Map.entry("/api/pop/home", "PRD"),
            Map.entry("/api/pop/procs/**", "PRD"),
            Map.entry("/api/pop/lots", "PRD"),
            Map.entry("/api/pop/results/**", "PRD"),
            Map.entry("/api/pop/params/**", "PRD"),
            Map.entry("/api/pop/approvals", "PRD"));

    private static final AntPathMatcher MATCHER = new AntPathMatcher();

    private final CompModuleService modules;

    /** 경로가 속한 모듈 — 공통 경로면 null */
    static String moduleOf(String path) {
        for (Map.Entry<String, String> r : RULES) {
            if (MATCHER.match(r.getKey(), path)) {
                return r.getValue();
            }
        }
        return null;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        String module = moduleOf(path);
        if (module != null && !modules.isOn(module)) {
            throw new BusinessException(HbErrorCode.MODULE_DISABLED);
        }
        return true;
    }
}
