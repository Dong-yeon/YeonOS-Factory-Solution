package io.smarthappy.platform.ops;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

import io.smarthappy.platform.module.ModuleCatalog;
import io.smarthappy.platform.module.ModuleCatalog.Module;

/** 운영자 콘솔 화면 (/ops/login 공개 — 허용 IP 에서만, /ops 는 ROLE_OPS) */
@Controller
public class OpsPageController {

    @GetMapping("/ops/login")
    public String login() {
        return "ops/login";
    }

    /** modules = 새 회사 만들기의 사용 모듈 선택(끌 수 있는 것만, docs/80 5장) */
    @GetMapping("/ops")
    public String console(Model model) {
        model.addAttribute("modules", ModuleCatalog.MODULES.stream().filter(Module::toggleable).toList());
        return "ops/console";
    }
}
