package io.smarthappy.module.srm.portal;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

import io.smarthappy.platform.portal.PortalMenus;
import io.smarthappy.platform.portal.PortalPageController;
import io.smarthappy.platform.portal.PortalService;
import lombok.RequiredArgsConstructor;

/**
 * 협력사 포털 SRM 화면 (docs/42 7장 3) — 셸 조각(portal/layout) + addShell. 협력사 사용자만 온다(PortalBoundaryFilter).
 * 화면은 틀만 — 자료는 /api/portal/srm/** 가 로그인 소속으로 거른 것만 그린다.
 */
@Controller
@RequiredArgsConstructor
public class PortalSrmPageController {

    public static final String PO = "/portal/po";
    public static final String ASN = "/portal/asn";
    public static final String ASN_PRINT = "/portal/asn-print";
    public static final String CLAIM = "/portal/claim";

    private final PortalMenus menus;
    private final PortalService portal;

    @GetMapping(PO)
    public String po(Model model) {
        PortalPageController.addShell(model, menus, portal, SrmPortalMenu.PO.code());
        return "portal/srm/po";
    }

    @GetMapping(ASN)
    public String asn(Model model) {
        PortalPageController.addShell(model, menus, portal, SrmPortalMenu.ASN.code());
        return "portal/srm/asn";
    }

    @GetMapping(CLAIM)
    public String claim(Model model) {
        PortalPageController.addShell(model, menus, portal, SrmPortalMenu.CLAIM.code());
        return "portal/srm/claim";
    }

    /** 납품서 인쇄(?asnId=) — 머리글·메뉴 없이 종이 한 장 */
    @GetMapping(ASN_PRINT)
    public String asnPrint(Model model) {
        PortalPageController.addShell(model, menus, portal, SrmPortalMenu.ASN.code());
        return "portal/srm/asn-print";
    }
}
