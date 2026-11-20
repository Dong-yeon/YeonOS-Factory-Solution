package io.smarthappy.platform.portal;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

import io.smarthappy.platform.security.PortalBoundaryFilter;
import lombok.RequiredArgsConstructor;

/**
 * 협력사 포털 셸 화면 (docs/42 7장 2). 회사 셸(/main)처럼 탭·iframe 을 쓰지 않는다 — 화면 몇 개를 고정 메뉴로 오간다.
 * 협력사 사용자만 온다(PortalBoundaryFilter 가 회사 사용자를 403). 업무 화면(SRM)은 같은 레이아웃 조각(portal/layout)을 쓴다.
 */
@Controller
@RequiredArgsConstructor
public class PortalPageController {

    public static final String HOME = PortalBoundaryFilter.PAGE;
    public static final String ME = "/portal/me";

    private final PortalMenus menus;
    private final PortalService service;

    @GetMapping(HOME)
    public String home(Model model) {
        shell(model, PlatformPortalMenu.HOME.code());
        return "portal/home";
    }

    @GetMapping(ME)
    public String me(Model model) {
        shell(model, PlatformPortalMenu.ME.code());
        return "portal/me";
    }

    /** 레이아웃 조각이 쓰는 값 — 업무 포털 화면 컨트롤러도 이것을 부른다 */
    public static void addShell(Model model, PortalMenus menus, PortalService service, String activeCode) {
        model.addAttribute("portalUser", PortalUser.current());
        model.addAttribute("supplier", service.supplier());
        model.addAttribute("menus", menus.items());
        model.addAttribute("activeMenu", activeCode);
    }

    private void shell(Model model, String activeCode) {
        addShell(model, menus, service, activeCode);
    }
}
