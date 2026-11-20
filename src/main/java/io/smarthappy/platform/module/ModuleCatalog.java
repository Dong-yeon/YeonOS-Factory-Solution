package io.smarthappy.platform.module;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 업무 모듈 목록과 의존 (docs/80 1·2장) — 원본은 이 상수. {@code sys_prog.module_cd} 와 같아야 한다(ModuleCatalogTest).
 *
 * - SYS(시스템·홈·내 업무·플랫폼 공통)는 항상 켜짐 — 목록에 있지만 끌 수 없다.
 * - requires = 필수 짝(켜진 모듈의 필수 짝이 꺼져 있으면 저장 거부), recommends = 함께 쓰면 좋음(안내만).
 *   근거는 module/* 패키지 사이 import(docs/80 2장 표).
 * - 플랫폼은 업무 모듈 클래스를 모른다(ModuleBoundaryGuardTest) — 코드 글자만 든다.
 */
public final class ModuleCatalog {

    public static final String SYS = "SYS";

    /** 모듈 하나. nameKey 는 메뉴 맨 위 폴더 이름 키와 같다 */
    public record Module(String code, String nameKey, int sortNo, Set<String> requires, Set<String> recommends) {
        public boolean toggleable() {
            return !SYS.equals(code);
        }
    }

    private static Module m(String code, String nameKey, int sortNo, Set<String> requires, Set<String> recommends) {
        return new Module(code, nameKey, sortNo, requires, recommends);
    }

    /** 메뉴 순서(docs/75) */
    public static final List<Module> MODULES = List.of(
            m(SYS, "menu.system", 0, Set.of(), Set.of()),
            m("MST", "menu.master", 100, Set.of(), Set.of()),
            m("SAL", "menu.sales", 200, Set.of("MST"), Set.of()),
            m("APS", "menu.aps", 250, Set.of("MST", "SAL", "PRD"), Set.of("SRM")),
            m("PRD", "menu.production", 300, Set.of("MST"), Set.of("SAL", "WMS")),
            m("QMS", "menu.qms", 350, Set.of("MST", "PRD", "WMS"), Set.of("PLM")),
            m("WMS", "menu.wms", 400, Set.of("MST"), Set.of("SRM", "QMS")),
            m("SRM", "menu.srm", 450, Set.of("MST", "WMS"), Set.of("QMS")),
            m("PMS", "menu.pms", 500, Set.of("MST"), Set.of("QMS", "PLM")),
            m("CMS", "menu.cms", 600, Set.of("MST"), Set.of("PRD")),
            m("PLM", "menu.plm", 650, Set.of("MST", "QMS"), Set.of("SAL")));

    /** 개발·데모 시드 기본으로 켜는 모듈 (docs/80 6장, 사용자 결정 2026-11-16 — 핵심 5개 + 구매) */
    public static final Set<String> DEMO_DEFAULT_ON = Set.of("MST", "PRD", "QMS", "WMS", "CMS", "SRM");

    private static final Map<String, Module> BY_CODE = MODULES.stream()
            .collect(Collectors.toMap(Module::code, x -> x, (a, b) -> a, LinkedHashMap::new));

    private ModuleCatalog() {
    }

    public static Optional<Module> find(String code) {
        return Optional.ofNullable(code == null ? null : BY_CODE.get(code));
    }

    /** 끌 수 있는 모듈 코드(목록 순서) */
    public static List<String> toggleableCodes() {
        return MODULES.stream().filter(Module::toggleable).map(Module::code).toList();
    }

    /** 이 모듈을 필수로 요구하는 모듈들 */
    public static Set<String> requiredBy(String code) {
        Set<String> out = new LinkedHashSet<>();
        for (Module x : MODULES) {
            if (x.requires().contains(code)) {
                out.add(x.code());
            }
        }
        return out;
    }

    /** 깨진 필수 짝 하나: 켜진 모듈 module 이 꺼진 모듈 missing 을 요구한다 */
    public record Broken(String module, String missing) {
    }

    /**
     * 켜진 집합(SYS 는 있든 없든 켜짐으로 본다)에서 필수 짝이 빠진 것 전부. 빈 목록이면 저장해도 된다. 순수 함수.
     */
    public static List<Broken> brokenPairs(Set<String> on) {
        Set<String> all = new LinkedHashSet<>(on);
        all.add(SYS);
        return MODULES.stream()
                .filter(x -> all.contains(x.code()))
                .flatMap(x -> x.requires().stream().sorted(ModuleCatalog::byMenuOrder)
                        .filter(r -> !all.contains(r)).map(r -> new Broken(x.code(), r)))
                .toList();
    }

    static int byMenuOrder(String a, String b) {
        return Integer.compare(find(a).map(Module::sortNo).orElse(9999), find(b).map(Module::sortNo).orElse(9999));
    }
}
