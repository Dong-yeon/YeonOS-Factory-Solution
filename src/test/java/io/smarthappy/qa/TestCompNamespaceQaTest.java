package io.smarthappy.qa;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

/**
 * QA 가드 후보 — 시험 보조끼리 회사 코드 이름 공간 충돌(상위 요청, 2026-10-23 ApiErrorMappingTest 순서 의존 실패의 같은 종류).
 * 시험 회사 코드는 "접두어 + 숫자" 다. 숫자를 서로 다른 카운터가 매기면(TestAccounts.SEQ vs 시험 클래스 자체 SEQ) 같은 접두어에서 순서에 따라
 * sys_comp 키가 겹친다(23505). 같은 카운터라도 접두어 하나가 다른 접두어 + 숫자이면("PL" + 412 = "PL4" + 12) 겹친다.
 * 이 시험은 src/test/java 를 읽어 ① newComp 계열 접두어끼리 ② 자체 카운터 접두어와 newComp 계열 접두어 사이의 충돌을 찾는다.
 */
class TestCompNamespaceQaTest {

    private static final Path TEST = Path.of("src/test/java");

    /** TestAccounts.newComp 로 가는 접두어 — 직접 호출과 접두어를 넘기는 보조(SysAdminTestSupport.setUpCompany, QaPopFixture) */
    static final Pattern NEW_COMP = Pattern.compile("(?:newComp|setUpCompany)\\(\"([A-Za-z0-9]+)\"\\)|QaPopFixture\\([^;]*?\"([A-Za-z0-9]+)\"\\)");

    /** 자체 카운터로 만든 회사 코드 — "접두어" + SEQ.incrementAndGet() / + n (sys_comp 를 직접 넣거나 회사 생성 API 를 부르는 파일만) */
    static final Pattern OWN = Pattern.compile("\"([A-Z]{1,8})\"\\s*\\+\\s*(?:SEQ\\.incrementAndGet\\(\\)|n\\b)");

    static boolean createsCompany(String src) {
        return src.contains("into sys_comp ") || src.contains("into sys_comp(") || src.contains("/api/ops/companies");
    }

    /** 두 접두어가 숫자를 붙였을 때 같은 코드를 만들 수 있나 — 같거나, 한쪽이 다른 쪽 + 숫자 */
    static boolean clash(String a, String b) {
        String x = a.toUpperCase();
        String y = b.toUpperCase();
        if (x.equals(y)) {
            return true;
        }
        String longer = x.length() > y.length() ? x : y;
        String shorter = longer == x ? y : x;
        return longer.startsWith(shorter) && longer.substring(shorter.length()).chars().allMatch(Character::isDigit);
    }

    record Scan(Map<String, TreeSet<String>> newComp, Map<String, TreeSet<String>> own) {
    }

    static Scan scan() throws IOException {
        Map<String, TreeSet<String>> nc = new TreeMap<>();
        Map<String, TreeSet<String>> own = new TreeMap<>();
        try (Stream<Path> files = Files.walk(TEST)) {
            for (Path f : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                if (f.endsWith("TestAccounts.java") || f.endsWith("TestCompNamespaceQaTest.java")) {
                    continue;
                }
                String src = Files.readString(f, StandardCharsets.UTF_8);
                String name = f.getFileName().toString();
                Matcher m = NEW_COMP.matcher(src);
                while (m.find()) {
                    nc.computeIfAbsent(m.group(1) != null ? m.group(1) : m.group(2), k -> new TreeSet<>()).add(name);
                }
                if (createsCompany(src)) {
                    Matcher o = OWN.matcher(src);
                    while (o.find()) {
                        own.computeIfAbsent(o.group(1), k -> new TreeSet<>()).add(name);
                    }
                }
            }
        }
        return new Scan(nc, own);
    }

    @Test
    void 검사기가_실제로_잡는다() throws IOException {
        assertThat(clash("PL", "PL4")).isTrue();
        assertThat(clash("PL4", "PL")).isTrue();
        assertThat(clash("PL4", "PL4B")).isFalse();
        assertThat(clash("RA", "RA")).isTrue();
        assertThat(clash("TPA", "PA")).isFalse();
        assertThat(clash("AP", "APS")).isFalse();
        Scan s = scan();
        assertThat(s.newComp()).containsKeys("QQ", "QP", "QE", "AD", "QB");
        assertThat(s.own()).as("자체 카운터 회사 코드(TenantIsolationTest·TenantRunnerTest·TestProbeController·OpsConsoleTest)")
                .containsKeys("TA", "TB", "BLANK", "TRA", "TRB", "TRS", "TPA", "TPB", "NEW");
    }

    @Test
    void newComp_접두어끼리_숫자만_달리_겹치지_않는다() throws IOException {
        List<String> keys = new ArrayList<>(scan().newComp().keySet());
        List<String> hits = new ArrayList<>();
        for (int i = 0; i < keys.size(); i++) {
            for (int j = i + 1; j < keys.size(); j++) {
                if (clash(keys.get(i), keys.get(j))) {
                    hits.add(keys.get(i) + " ↔ " + keys.get(j));
                }
            }
        }
        assertThat(hits).as("접두어는 숫자로 끝나지 않게(예: PL4 → PLF)").isEmpty();
    }

    @Test
    void 자체_카운터_회사_코드는_newComp_접두어와_겹치지_않는다() throws IOException {
        Scan s = scan();
        List<String> hits = new ArrayList<>();
        for (var o : s.own().entrySet()) {
            for (var n : s.newComp().entrySet()) {
                if (clash(o.getKey(), n.getKey())) {
                    hits.add(o.getKey() + " " + o.getValue() + " ↔ newComp " + n.getKey() + " " + n.getValue());
                }
            }
        }
        assertThat(hits).as("자체 카운터는 TestAccounts 와 겹치지 않는 접두어(T·RT 등)로, 또는 TestAccounts.newComp 로").isEmpty();
    }
}
