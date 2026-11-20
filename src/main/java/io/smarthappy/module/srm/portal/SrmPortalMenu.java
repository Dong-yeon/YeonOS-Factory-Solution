package io.smarthappy.module.srm.portal;

import java.util.List;

import org.springframework.stereotype.Component;

import io.smarthappy.platform.portal.PortalMenuItem;
import io.smarthappy.platform.portal.PortalMenuSource;

/** SRM 포털 메뉴 (docs/42 7장 3) — 발주·회신(100), 납품 통지(200), 클레임 회신(300, P-4). 납품서 인쇄는 ASN 화면에서 연다(메뉴 없음) */
@Component
public class SrmPortalMenu implements PortalMenuSource {

    public static final PortalMenuItem PO = new PortalMenuItem("srm-po", "portal.srm.menu.po", PortalSrmPageController.PO, 100);
    public static final PortalMenuItem ASN = new PortalMenuItem("srm-asn", "portal.srm.menu.asn", PortalSrmPageController.ASN, 200);
    public static final PortalMenuItem CLAIM = new PortalMenuItem("srm-claim", "portal.srm.menu.claim", PortalSrmPageController.CLAIM, 300);

    @Override
    public List<PortalMenuItem> items() {
        return List.of(PO, ASN, CLAIM);
    }
}
