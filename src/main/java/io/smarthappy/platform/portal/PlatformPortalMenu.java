package io.smarthappy.platform.portal;

import java.util.List;

import org.springframework.stereotype.Component;

/** 플랫폼 포털 메뉴: 홈(맨 앞)·내 정보(맨 뒤). 업무 메뉴는 그 사이 sortNo 100~899 */
@Component
public class PlatformPortalMenu implements PortalMenuSource {

    public static final PortalMenuItem HOME = new PortalMenuItem("home", "portal.menu.home", PortalPageController.HOME, 0);
    public static final PortalMenuItem ME = new PortalMenuItem("me", "portal.menu.me", PortalPageController.ME, 900);

    @Override
    public List<PortalMenuItem> items() {
        return List.of(HOME, ME);
    }
}
