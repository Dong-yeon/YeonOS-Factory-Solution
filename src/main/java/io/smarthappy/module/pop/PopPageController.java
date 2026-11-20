package io.smarthappy.module.pop;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

import io.smarthappy.platform.module.CompModuleService;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;

/** POP 키오스크 화면 — 셸·메뉴 없음. /pop 은 단말(ROLE_TERMINAL)만, /pop/activate 는 누구나(PopSecurityConfig) */
@Controller
@RequiredArgsConstructor
public class PopPageController {

    /** 공정 단말(POP) = 생산 모듈(docs/80) */
    static final String MODULE = "PRD";

    private final CompModuleService modules;

    /**
     * 단말 홈 — 창고 단말(PDA)은 WMS 창고 홈으로(docs/25 4.33), 공정 단말(POP)은 작업장 홈.
     * 회사가 생산 모듈을 껐으면 안내 화면(403) — API 는 ModuleGateInterceptor 가 막는다
     */
    @GetMapping("/pop")
    public String home(@AuthenticationPrincipal TerminalPrincipal t, HttpServletResponse response) {
        if (t != null && "PDA".equals(t.terminalType())) {
            return "redirect:/pop/wh";
        }
        if (!modules.isOn(MODULE)) {
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            return "error/module-disabled";
        }
        return "pop/home";
    }

    @GetMapping("/pop/activate")
    public String activate() {
        return "pop/activate";
    }
}
