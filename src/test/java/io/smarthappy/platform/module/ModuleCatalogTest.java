package io.smarthappy.platform.module;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import io.smarthappy.platform.module.ModuleCatalog.Broken;
import io.smarthappy.platform.module.ModuleCatalog.Module;
import io.smarthappy.platform.security.DefaultMenuTree;
import io.smarthappy.platform.security.DefaultMenuTree.Node;
import io.smarthappy.support.PostgresTestBase;

/**
 * 모듈 목록 = sys_prog.module_cd (docs/80 1장), 필수 짝 규칙(순수 함수), 메뉴 맨 위 폴더 = 모듈(1.1 바로잡기 회귀).
 */
class ModuleCatalogTest extends PostgresTestBase {

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void 목록은_sys_prog_의_module_cd_와_같다() {
        Set<String> inDb = new HashSet<>(jdbc.queryForList("select distinct module_cd from sys_prog", String.class));
        Set<String> inCatalog = new HashSet<>(ModuleCatalog.MODULES.stream().map(Module::code).toList());
        assertThat(inCatalog).as("새 module_cd 를 만들면 ModuleCatalog 에도").isEqualTo(inDb);
        // 이름 키 = 메뉴 맨 위 폴더 키, 필수·권장 짝은 목록 안 코드
        Set<String> folders = new HashSet<>(DefaultMenuTree.folderKeys());
        for (Module m : ModuleCatalog.MODULES) {
            assertThat(folders).as(m.code() + " 이름 키").contains(m.nameKey());
            assertThat(inCatalog).containsAll(m.requires()).containsAll(m.recommends());
            assertThat(m.requires()).as("자기 자신·SYS 를 요구하지 않는다").doesNotContain(m.code(), ModuleCatalog.SYS);
        }
    }

    @Test
    void 메뉴_맨_위_폴더의_화면은_그_모듈_코드를_갖는다() {
        // 업무 모듈 폴더(menu.master 등) 아래 본 자리 화면의 module_cd = 그 폴더의 모듈 — 끄면 폴더째 사라지게(docs/80 1.1)
        List<String> bad = new java.util.ArrayList<>();
        for (Node root : DefaultMenuTree.ROOTS) {
            ModuleCatalog.MODULES.stream().filter(m -> m.toggleable() && m.nameKey().equals(root.key())).findFirst().ifPresent(m ->
                    walk(root, n -> {
                        if (!n.folder() && !n.link()) {
                            String cd = jdbc.queryForObject("select module_cd from sys_prog where prog_cd = ?", String.class, n.progCd());
                            if (!m.code().equals(cd)) {
                                bad.add(n.progCd() + "=" + cd + " (폴더 " + m.code() + ")");
                            }
                        }
                    }));
        }
        assertThat(bad).isEmpty();
        assertThat(jdbc.queryForObject("select module_cd from sys_prog where prog_cd = 'sys-terminal'", String.class)).isEqualTo("SYS");
    }

    private static void walk(Node n, java.util.function.Consumer<Node> fn) {
        fn.accept(n);
        n.children().forEach(c -> walk(c, fn));
    }

    @Test
    void 필수_짝_검사() {
        assertThat(ModuleCatalog.brokenPairs(Set.copyOf(ModuleCatalog.toggleableCodes()))).isEmpty();
        assertThat(ModuleCatalog.brokenPairs(Set.of())).isEmpty();
        assertThat(ModuleCatalog.brokenPairs(Set.of("MST"))).isEmpty();
        assertThat(ModuleCatalog.brokenPairs(Set.of("QMS"))).containsExactly(
                new Broken("QMS", "MST"), new Broken("QMS", "PRD"), new Broken("QMS", "WMS"));
        assertThat(ModuleCatalog.brokenPairs(Set.of("MST", "PRD", "SAL", "APS"))).isEmpty();
        assertThat(ModuleCatalog.brokenPairs(Set.of("MST", "SRM"))).containsExactly(new Broken("SRM", "WMS"));
        assertThat(ModuleCatalog.brokenPairs(Set.of("MST", "PRD", "WMS", "PLM"))).containsExactly(new Broken("PLM", "QMS"));
        assertThat(ModuleCatalog.requiredBy("WMS")).containsExactlyInAnyOrder("QMS", "SRM");
        assertThat(ModuleCatalog.brokenPairs(ModuleCatalog.DEMO_DEFAULT_ON)).isEmpty();
    }

    @Test
    void 단말_경로_모듈() {
        assertThat(ModuleGateInterceptor.moduleOf("/api/pop/wh/counts")).isEqualTo("WMS");
        assertThat(ModuleGateInterceptor.moduleOf("/api/pop/procs/12/insp")).isEqualTo("QMS");
        assertThat(ModuleGateInterceptor.moduleOf("/api/pop/procs/12/insp/3/meas")).isEqualTo("QMS");
        assertThat(ModuleGateInterceptor.moduleOf("/api/pop/procs/12/results")).isEqualTo("PRD");
        assertThat(ModuleGateInterceptor.moduleOf("/api/pop/breakdown")).isEqualTo("CMS");
        assertThat(ModuleGateInterceptor.moduleOf("/api/pop/pm/runs/1/complete")).isEqualTo("CMS");
        for (String common : List.of("/api/pop/activate", "/api/pop/session", "/api/pop/badge", "/api/pop/lang",
                "/api/pop/worker/logout", "/api/pop/kiosk/exit")) {
            assertThat(ModuleGateInterceptor.moduleOf(common)).as(common).isNull();
        }
    }
}
