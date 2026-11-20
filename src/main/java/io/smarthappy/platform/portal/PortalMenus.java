package io.smarthappy.platform.portal;

import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/** 포털 메뉴 모음 — 기동 때 한 번 모으고 코드·경로 중복이면 기동을 멈춘다(조용히 하나가 가려지지 않게) */
@Component
public class PortalMenus {

    private final List<PortalMenuItem> items;

    public PortalMenus(ObjectProvider<PortalMenuSource> sources) {
        List<PortalMenuItem> all = sources.orderedStream().flatMap(s -> s.items().stream())
                .sorted(Comparator.comparingInt(PortalMenuItem::sortNo).thenComparing(PortalMenuItem::code)).toList();
        Set<String> codes = new HashSet<>();
        Set<String> paths = new HashSet<>();
        for (PortalMenuItem i : all) {
            if (!codes.add(i.code()) || !paths.add(i.path())) {
                throw new IllegalStateException("포털 메뉴 코드·경로 중복: " + i);
            }
        }
        this.items = all;
    }

    public List<PortalMenuItem> items() {
        return items;
    }
}
