package io.smarthappy.module.wms.pda;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

import io.smarthappy.platform.module.CompModuleService;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;

/** 창고 PDA 화면 (/pop/wh, B7-1 docs/30 12.12) — 셸·메뉴 없는 단말 화면. 보안은 MES 단말 체인(PDA 유형만), PDA 의 /pop 은 여기로 온다 */
@Controller
@RequiredArgsConstructor
public class PdaPageController {

    private final CompModuleService modules;

    /** 회사가 창고 모듈을 껐으면 안내 화면(403, docs/80) — API 는 ModuleGateInterceptor 가 막는다 */
    @GetMapping("/pop/wh")
    public String home(HttpServletResponse response) {
        if (!modules.isOn("WMS")) {
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            return "error/module-disabled";
        }
        return "wms/pda-home";
    }
}
