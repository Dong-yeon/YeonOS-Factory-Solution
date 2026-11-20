package io.smarthappy.guard;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import io.smarthappy.support.PostgresTestBase;

/**
 * 가드: 화면 템플릿에 손으로 적은 선택지가 공통코드 그룹 값과 겹치면 (docs/68 Q3)
 * <ul>
 * <li>회사 확장 그룹(comp_ext_yn = Y) → 손 목록 금지, {@code data-code} 로만 — 회사 코드·회사 숨김이 선택지에 보여야 한다</li>
 * <li>시스템 고정 그룹 → 손 목록은 되지만 이름은 그 코드의 키 {@code #{code.그룹.값}} 만 — 이름이 두 군데(js.* 와 code.*)에 적혀 어긋나지 않게.
 * 그룹의 일부만 고르는 목록(작업 종류 BM·CM 등)도 같은 규칙</li>
 * </ul>
 * 겹침 = 빈 값이 아닌 값이 2개 이상이고 모두 한 그룹의 코드. Y/N 만 있는 그룹·숫자뿐인 목록은 우연한 겹침이라 보지 않는다.
 * 대상: select 의 option, 같은 name 의 radio·checkbox. JS 로 만드는 선택지는 범위 밖(화면별로 손으로 확인 — docs/68 7장)
 */
class CodeOptionGuardTest extends PostgresTestBase {

    private static final Path TEMPLATES = Path.of("src/main/resources/templates");

    /**
     * 예외(파일:이름 → 근거). 다른 세션 브랜치가 병합 전인 화면은 그 병합 뒤 정리한다(docs/68 Q3 남김)
     */
    private static final Map<String, String> ALLOW = Map.ofEntries(
            Map.entry("cms/equip-profile-mgt.html:criticality", "조회 세그먼트에 등급 글자(A·B·C)만 — 긴 이름(code.CMS_CRIT)은 표·상세에서"),
            Map.entry("cms/equip-link.html:dataType", "설비 태그 자료형(NUM·BOOL) — 분류 속성 자료형 PROP_DATA_TYPE 과 값만 같은 다른 칸"),
            Map.entry("wms/label-mgt.html:ruleScope", "라벨 템플릿 규칙 범위(거래처·품목, ck_wms_label_tpl_rule_scope) — ERP 매핑 대상 EXT_ENTITY·CAPA 전개 대상과 값만 같다"),
            Map.entry("srm/notify-mgt.html:status", "협력사 알림 발송 결과(SENT·FAIL) — 라벨 인쇄 상태와 값만 같다"),
            Map.entry("pms/project-mgt.html:status", "프로젝트 상태 묶음 필터(진행·종료·취소) — 다른 그룹과 값만 같다"),
            Map.entry("aps/mps.html:bucket", "APS 브랜치 병합 전 — 병합 뒤 정리(docs/68 Q3)"),
            Map.entry("qms/spc.html:actType", "대시보드 브랜치가 spc.html 을 고쳐 병합 전 — 병합 뒤 정리(docs/68 Q3)"),
            Map.entry("qms/spc.html:rule", "대시보드 브랜치가 spc.html 을 고쳐 병합 전 — 병합 뒤 정리(docs/68 Q3)"),
            Map.entry("production/wo-mgt.html:kind", "대시보드 브랜치가 wo-mgt.html 을 고쳐 병합 전 — 병합 뒤 정리(docs/68 Q3)"));

    private static final Pattern SELECT = Pattern.compile("<select\\b([^>]*)>([\\s\\S]*?)</select>");
    private static final Pattern OPTION = Pattern.compile("<option\\b([^>]*)>");
    private static final Pattern CHOICE = Pattern.compile("<input\\b[^>]*type=\"(?:radio|checkbox)\"[^>]*>");
    private static final Pattern NUMERIC = Pattern.compile("^-?\\d+$");

    @Autowired
    JdbcTemplate jdbc;

    /** 그룹 → 코드, 회사 확장 그룹 */
    final Map<String, Set<String>> groups = new LinkedHashMap<>();
    final Set<String> ext = new HashSet<>();
    /** "그룹.코드" → 이름 키(code_nm_key — 대부분 code.그룹.코드) */
    final Map<String, String> keys = new HashMap<>();

    @BeforeEach
    void load() {
        jdbc.query("select g.grp_cd, g.comp_ext_yn, c.code, c.code_nm_key from sys_code_grp g join sys_code c on c.grp_cd = g.grp_cd", rs -> {
            keys.put(rs.getString(1) + "." + rs.getString(3), rs.getString(4));
            groups.computeIfAbsent(rs.getString(1), k -> new HashSet<>()).add(rs.getString(3));
            if ("Y".equals(rs.getString(2))) {
                ext.add(rs.getString(1));
            }
        });
        groups.values().removeIf(s -> Set.of("Y", "N").containsAll(s));
    }

    /** 한 묶음(select 또는 같은 name 의 radio): 값 → 그 요소 글자(option 태그·input 뒤 글자) */
    record Choice(String value, String label) {
    }

    @Test
    void 공통코드_값과_겹치는_손_선택지는_data_code_또는_code_키_이름만() throws IOException {
        List<String> bad = new ArrayList<>();
        try (Stream<Path> files = Files.walk(TEMPLATES)) {
            for (Path p : files.filter(f -> f.toString().endsWith(".html")).toList()) {
                String rel = TEMPLATES.relativize(p).toString().replace('\\', '/');
                String s = Files.readString(p, StandardCharsets.UTF_8);
                for (Map.Entry<String, List<Choice>> e : choices(s).entrySet()) {
                    String where = rel + ":" + e.getKey();
                    if (!ALLOW.containsKey(where)) {
                        check(where, e.getValue(), bad);
                    }
                }
            }
        }
        assertThat(bad).as("손으로 적은 선택지 — data-code 로 바꾸거나(회사 확장 그룹), 이름을 #{code.그룹.값} 으로").isEmpty();
    }

    @Test
    void 검사기가_실제로_잡는다() {
        List<String> bad = new ArrayList<>();
        // 시스템 고정 그룹 + 다른 키 이름 → 잡음, code 키 이름 → 통과
        check("t:a", choicesOf("<select data-field=\"a\"><option value=\"MAKE\" th:text=\"#{x.make}\">x</option>"
                + "<option value=\"BUY\" th:text=\"#{x.buy}\">y</option></select>"), bad);
        assertThat(bad).hasSize(1);
        bad.clear();
        check("t:b", choicesOf("<select data-field=\"b\"><option value=\"MAKE\" th:text=\"#{code.PROC_TYPE.MAKE}\">x</option>"
                + "<option value=\"BUY\" th:text=\"#{code.PROC_TYPE.BUY}\">y</option></select>"), bad);
        assertThat(bad).isEmpty();
        // 회사 확장 그룹은 code 키여도 손 목록 금지
        check("t:c", choicesOf("<select data-field=\"c\"><option value=\"PRESS\" th:text=\"#{code.PROC_CAT.PRESS}\">x</option>"
                + "<option value=\"WELD\" th:text=\"#{code.PROC_CAT.WELD}\">y</option></select>"), bad);
        assertThat(bad).hasSize(1);
        bad.clear();
        // radio 도 — input 뒤 span 의 키를 본다. data-code select 는 안 본다
        check("t:d", choicesOf("<label><input type=\"radio\" name=\"d\" value=\"MAKE\"><span th:text=\"#{js.make}\">x</span></label>"
                + "<label><input type=\"radio\" name=\"d\" value=\"BUY\"><span th:text=\"#{code.PROC_TYPE.BUY}\">y</span></label>"), bad);
        assertThat(bad).hasSize(1);
        assertThat(choicesOf("<select data-code=\"PROC_TYPE\"><option value=\"MAKE\">x</option><option value=\"BUY\">y</option></select>"))
                .isEmpty();
    }

    private List<Choice> choicesOf(String html) {
        return choices(html).values().stream().findFirst().orElse(List.of());
    }

    private static Map<String, List<Choice>> choices(String s) {
        Map<String, List<Choice>> out = new LinkedHashMap<>();
        Matcher m = SELECT.matcher(s);
        while (m.find()) {
            if (m.group(1).contains("data-code=")) {
                continue;
            }
            List<Choice> list = new ArrayList<>();
            Matcher o = OPTION.matcher(m.group(2));
            while (o.find()) {
                String v = attr(o.group(1), "value");
                if (v != null && !v.isEmpty()) {
                    list.add(new Choice(v, o.group(1)));
                }
            }
            out.put(name(m.group(1)), list);
        }
        Map<String, List<Choice>> radios = new HashMap<>();
        Matcher c = CHOICE.matcher(s);
        while (c.find()) {
            String tag = c.group();
            String nm = attr(tag, "name") != null ? attr(tag, "name") : attr(tag, "data-field");
            String v = attr(tag, "value");
            if (nm != null && v != null && !v.isEmpty()) {
                // input 뒤 글자(같은 label 안의 span) — 200자 안에서 첫 th:text
                String after = s.substring(c.end(), Math.min(s.length(), c.end() + 200));
                radios.computeIfAbsent(nm, k -> new ArrayList<>()).add(new Choice(v, after.split("</label>")[0]));
            }
        }
        out.putAll(radios);
        return out;
    }

    private void check(String where, List<Choice> list, List<String> bad) {
        if (list.size() < 2 || list.stream().allMatch(x -> NUMERIC.matcher(x.value()).matches())) {
            return;
        }
        List<String> hits = groups.entrySet().stream()
                .filter(g -> list.stream().allMatch(x -> g.getValue().contains(x.value()))).map(Map.Entry::getKey).toList();
        if (hits.isEmpty()) {
            return;
        }
        if (hits.stream().anyMatch(ext::contains)) {
            bad.add(where + " — 회사 확장 그룹 " + hits + " 은 data-code 로");
            return;
        }
        boolean named = hits.stream().anyMatch(g -> list.stream()
                .allMatch(x -> x.label().contains("#{" + keys.get(g + "." + x.value()) + "}")));
        if (!named) {
            bad.add(where + " — 그룹 " + hits + " 값: 이름을 #{code.그룹.값} 으로(또는 data-code)");
        }
    }

    private static String name(String attrs) {
        for (String a : new String[] {"name", "data-field", "id"}) {
            String v = attr(attrs, a);
            if (v != null) {
                return v;
            }
        }
        return "?";
    }

    private static String attr(String tag, String a) {
        Matcher m = Pattern.compile("(?:^|\\s)" + Pattern.quote(a) + "=\"([^\"]*)\"").matcher(tag);
        return m.find() ? m.group(1) : null;
    }
}
