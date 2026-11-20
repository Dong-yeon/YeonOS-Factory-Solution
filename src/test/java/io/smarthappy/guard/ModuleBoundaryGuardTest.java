package io.smarthappy.guard;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.ibatis.annotations.Mapper;
import org.junit.jupiter.api.Test;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RestController;

import com.tngtech.archunit.core.domain.Dependency;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;

/**
 * 가드: 모듈 경계 (docs/27 S8 → docs/52 Q7 "ArchUnit 규칙 몇 개", 2026-10-27). 기존 가드 시험처럼 위반을 목록으로 실패시킨다.
 *
 * <ol>
 * <li>플랫폼은 업무 모듈을 모른다 — 모듈이 플랫폼 계약(SPI)을 구현하고, 플랫폼은 그 계약만 본다</li>
 * <li>다른 모듈의 MyBatis 매퍼를 직접 쓰지 않는다 — 다른 모듈 데이터는 그 모듈의 서비스·조회 계약(*Lookup 등)으로.
 *     매퍼를 건너뛰면 그 모듈의 상태·권한·회사 범위 규칙을 우회한다. MES(production·pop·sales)는 한 모듈(docs/29 2.3)</li>
 * <li>컨트롤러는 아무도 주입받지 않는다 — 화면·API 입구는 끝점이고, 업무 규칙은 서비스에</li>
 * </ol>
 * 모듈 사이 순환(예: apqp ↔ plm)은 지금 39곳이라 규칙으로 걸지 않았다 — docs/27 검토 절에 기록, 줄이는 것은 다음.
 */
class ModuleBoundaryGuardTest {

    static final JavaClasses MAIN = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("io.smarthappy");

    private static final Pattern MODULE = Pattern.compile("^io\\.smarthappy\\.module\\.([a-z]+)(\\..*)?$");

    /** 같은 모듈로 보는 패키지 묶음 (docs/29 2.3 이름 대역 — MES 세션 패키지) */
    private static final Map<String, String> GROUP = Map.of("pop", "production", "sales", "production");

    /**
     * 알려진 예외(쓰는 쪽 모듈 → 매퍼). 줄이기만 한다 — 새로 걸리면 여기에 넣지 말고 조회 계약으로 고친다.
     * - production → WorkforceMapper: 작업자·근무 조회. master 쪽 조회 계약(Lookup)으로 옮길 후보(2026-10-27 발견)
     */
    static final Set<String> KNOWN = Set.of("production -> io.smarthappy.module.master.workforce.WorkforceMapper");

    @Test
    void 플랫폼은_업무_모듈에_의존하지_않는다() {
        noClasses().that().resideInAPackage("io.smarthappy.platform..")
                .should().dependOnClassesThat().resideInAPackage("io.smarthappy.module..")
                .because("플랫폼은 계약(SPI)만 두고 모듈이 구현한다")
                .check(MAIN);
    }

    @Test
    void 다른_모듈의_매퍼를_직접_쓰지_않는다() {
        noClasses().that().resideInAPackage("io.smarthappy.module..")
                .should(useOtherModulesMapper())
                .because("다른 모듈 데이터는 그 모듈의 서비스·조회 계약으로 — 매퍼를 건너뛰면 상태·권한 규칙을 우회한다")
                .check(MAIN);
    }

    @Test
    void 컨트롤러는_다른_클래스가_주입받지_않는다() {
        // 필드·생성자 인자로 컨트롤러를 받는 것을 막는다(정적 도우미 호출 — 예 PortalPageController.addShell — 은 대상 밖)
        noClasses().should(new ArchCondition<>("inject a controller") {
            @Override
            public void check(JavaClass c, ConditionEvents events) {
                c.getFields().stream().filter(f -> isController(f.getRawType()))
                        .forEach(f -> events.add(SimpleConditionEvent.satisfied(f, f.getFullName())));
                c.getConstructors().forEach(k -> k.getRawParameterTypes().stream().filter(ModuleBoundaryGuardTest::isController)
                        .forEach(t -> events.add(SimpleConditionEvent.satisfied(k, k.getFullName()))));
            }
        }).because("업무 규칙은 서비스에 — 컨트롤러는 끝점").check(MAIN);
    }

    private static boolean isController(JavaClass t) {
        return !t.isAnnotation() && (t.isAnnotatedWith(RestController.class) || t.isAnnotatedWith(Controller.class));
    }

    @Test
    void 알려진_예외는_아직_실제로_있다() {
        // 고쳐서 없어진 예외가 목록에 남아 다음 위반을 숨기지 않게
        Set<String> found = new TreeSet<>();
        for (JavaClass c : MAIN) {
            for (Dependency d : c.getDirectDependenciesFromSelf()) {
                String key = crossModuleMapper(c, d.getTargetClass());
                if (key != null) {
                    found.add(key);
                }
            }
        }
        assertThat(found).containsAll(KNOWN);
    }

    @Test
    void 검사기가_실제로_잡는다() {
        assertThat(module("io.smarthappy.module.pop.terminal")).isEqualTo("production");
        assertThat(module("io.smarthappy.module.qms")).isEqualTo("qms");
        assertThat(module("io.smarthappy.platform.log")).isNull();
        // 실제 클래스: production 서비스가 master 매퍼를 쓰면 "production -> 매퍼" 로 잡힌다(KNOWN 이 그 증거)
        JavaClass mapper = MAIN.get("io.smarthappy.module.master.workforce.WorkforceMapper");
        JavaClass sameModule = MAIN.stream().filter(c -> "master".equals(module(c.getPackageName()))).findFirst().orElseThrow();
        assertThat(crossModuleMapper(sameModule, mapper)).isNull();
        JavaClass other = MAIN.stream().filter(c -> "qms".equals(module(c.getPackageName()))).findFirst().orElseThrow();
        assertThat(crossModuleMapper(other, mapper))
                .isEqualTo("qms -> io.smarthappy.module.master.workforce.WorkforceMapper");
    }


    private static ArchCondition<JavaClass> useOtherModulesMapper() {
        return new ArchCondition<>("use another module's @Mapper") {
            @Override
            public void check(JavaClass c, ConditionEvents events) {
                for (Dependency d : c.getDirectDependenciesFromSelf()) {
                    String key = crossModuleMapper(c, d.getTargetClass());
                    if (key != null && !KNOWN.contains(key)) {
                        events.add(SimpleConditionEvent.satisfied(d, d.getDescription()));
                    }
                }
            }
        };
    }

    /** 다른 모듈의 매퍼를 쓰면 "쓰는 모듈 -> 매퍼" — 아니면 null */
    static String crossModuleMapper(JavaClass from, JavaClass target) {
        if (!target.isAnnotatedWith(Mapper.class)) {
            return null;
        }
        String a = module(from.getPackageName());
        String b = module(target.getPackageName());
        if (a == null || b == null || a.equals(b)) {
            return null;
        }
        return a + " -> " + target.getName();
    }

    static String module(String pkg) {
        Matcher m = MODULE.matcher(pkg);
        if (!m.matches()) {
            return null;
        }
        return GROUP.getOrDefault(m.group(1), m.group(1));
    }
}
