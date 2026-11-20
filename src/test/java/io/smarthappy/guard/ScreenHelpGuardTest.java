package io.smarthappy.guard;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 가드: 화면 도움말 (docs/46). DB·Spring 없이 소스 파일만 읽는다 — 새 화면을 추가할 때 도움말이 빠지지 않게.
 * - 마이그레이션이 등록한 모든 sys_prog 화면에 help.{progCd}·.helpWhy·.helpWhen·.helpDo 문구가 ko/en 모두 있다
 * - 앞·뒤 화면 표 static/js/common/help-flow.json 에 모든 화면 항목이 있고, 가리키는 화면은 실제 화면이다(자기 제외·5개 이하)
 * - 화면 템플릿에 [도움말] 버튼(#hbHelpBtn)이 있다 — 인쇄 전용 화면만 예외(NO_BUTTON)
 */
class ScreenHelpGuardTest {

    private static final Path RES = Path.of("src/main/resources");
    private static final Path FLOW = RES.resolve("static/js/common/help-flow.json");
    /** INSERT INTO sys_prog … ; 문장 하나 */
    private static final Pattern INSERT = Pattern.compile("(?is)insert\\s+into\\s+sys_prog\\b(.*?);");
    /** ('prog-cd', 'prog.key', 'view/path' — VALUES 튜플의 앞 세 칸 */
    private static final Pattern TUPLE = Pattern.compile("\\(\\s*'([a-z0-9][a-z0-9-]{1,49})'\\s*,\\s*'prog\\.[A-Za-z0-9]+'\\s*,\\s*'([^']+)'");
    private static final List<String> PARTS = List.of("", ".helpWhy", ".helpWhen", ".helpDo");
    private static final int MAX_LINKS = 5;

    /** 도움말 버튼이 없어도 되는 화면 — 근거와 함께 */
    private static final Map<String, String> NO_BUTTON = Map.of(
            "srm-po-print", "발주서 인쇄 전용 창(A4, 도구 막대 없음) — 도움말은 발주 관리 화면에서",
            "srm-asn-print", "ASN 납품서 인쇄 전용 창 — 도움말은 ASN 관리 화면에서");

    @Test
    void 모든_화면에_도움말_문구가_ko_en_모두_있다() throws IOException {
        Map<String, String> progs = progs();
        Properties ko = load("messages.properties");
        Properties en = load("messages_en.properties");
        List<String> missing = new ArrayList<>();
        for (String p : progs.keySet()) {
            for (String part : PARTS) {
                String key = "help." + p + part;
                if (blank(ko.getProperty(key))) {
                    missing.add("ko " + key);
                }
                if (blank(en.getProperty(key))) {
                    missing.add("en " + key);
                }
            }
        }
        assertThat(missing).as("화면 도움말 문구 — docs/46 화면 지도에서 쓰고 messages*.properties 에 추가").isEmpty();
    }

    @Test
    void 앞뒤_화면_표가_모든_화면을_덮고_실제_화면만_가리킨다() throws IOException {
        Set<String> progs = progs().keySet();
        JsonNode root = JsonMapper.builder().build().readTree(Files.readString(FLOW, StandardCharsets.UTF_8));
        List<String> bad = new ArrayList<>();
        Set<String> listed = new TreeSet<>();
        for (Map.Entry<String, JsonNode> e : root.properties()) {
            String p = e.getKey();
            listed.add(p);
            if (!progs.contains(p)) {
                bad.add(p + ": sys_prog 에 없는 화면");
                continue;
            }
            for (String dir : List.of("prev", "next")) {
                JsonNode arr = e.getValue().get(dir);
                if (arr == null || !arr.isArray()) {
                    bad.add(p + "." + dir + ": 배열이 아님");
                    continue;
                }
                if (arr.size() > MAX_LINKS) {
                    bad.add(p + "." + dir + ": " + MAX_LINKS + "개 넘음");
                }
                for (JsonNode c : arr) {
                    String code = c.asString();
                    if (code.equals(p) || !progs.contains(code)) {
                        bad.add(p + "." + dir + ": " + code);
                    }
                }
            }
        }
        Set<String> notListed = new TreeSet<>(progs);
        notListed.removeAll(listed);
        assertThat(bad).as("help-flow.json 의 잘못된 항목").isEmpty();
        assertThat(notListed).as("help-flow.json 에 항목이 없는 화면(앞·뒤가 없으면 빈 배열로)").isEmpty();
    }

    /** 업무 흐름 지도(docs/46 12장)의 흐름 단계 — 화살표는 help-flow.json 이 원본이라 여기에는 소속·순서만 */
    @Test
    void 흐름_지도는_흐름_9개이고_실제_화면만_한_흐름에_한_번씩() throws IOException {
        Set<String> progs = progs().keySet();
        JsonNode root = JsonMapper.builder().build().readTree(Files.readString(RES.resolve("static/js/common/flow-map.json"),
                StandardCharsets.UTF_8));
        assertThat(root.properties().stream().map(Map.Entry::getKey).toList()).containsExactly("F0", "F1", "F2", "F3", "F4", "F5", "F6", "F7", "F8");
        List<String> bad = new ArrayList<>();
        for (Map.Entry<String, JsonNode> e : root.properties()) {
            JsonNode steps = e.getValue().get("steps");
            if (steps == null || !steps.isArray() || steps.isEmpty()) {
                bad.add(e.getKey() + ": steps 가 비었거나 배열이 아님");
                continue;
            }
            Set<String> seen = new TreeSet<>();
            for (JsonNode step : steps) {
                if (!step.isArray() || step.isEmpty()) {
                    bad.add(e.getKey() + ": 빈 단계");
                    continue;
                }
                for (JsonNode c : step) {
                    String code = c.asString();
                    if (!progs.contains(code)) {
                        bad.add(e.getKey() + ": sys_prog 에 없는 화면 " + code);
                    }
                    if (!seen.add(code)) {
                        bad.add(e.getKey() + ": 같은 흐름에 두 번 " + code);
                    }
                }
            }
        }
        assertThat(bad).as("flow-map.json 의 잘못된 항목").isEmpty();
    }

    @Test
    void 화면_템플릿에_도움말_버튼이_있다() throws IOException {
        List<String> missing = new ArrayList<>();
        for (Map.Entry<String, String> e : progs().entrySet()) {
            if (NO_BUTTON.containsKey(e.getKey())) {
                continue;
            }
            Path tpl = RES.resolve("templates/" + e.getValue() + ".html");
            if (!Files.isRegularFile(tpl) || !Files.readString(tpl, StandardCharsets.UTF_8).contains("id=\"hbHelpBtn\"")) {
                missing.add(e.getKey() + " → " + e.getValue());
            }
        }
        assertThat(missing).as("[도움말] 버튼(#hbHelpBtn) + aside#hbHelp + hb.page.js 를 넣는다").isEmpty();
    }

    /** 마이그레이션이 등록한 화면 → view_path (화면은 Flyway 로만 추가된다) */
    private static Map<String, String> progs() throws IOException {
        Map<String, String> out = new TreeMap<>();
        try (Stream<Path> files = Files.list(RES.resolve("db/migration"))) {
            for (Path f : files.filter(x -> x.toString().endsWith(".sql")).sorted().toList()) {
                Matcher ins = INSERT.matcher(stripComments(Files.readString(f, StandardCharsets.UTF_8)));
                while (ins.find()) {
                    Matcher t = TUPLE.matcher(ins.group(1));
                    while (t.find()) {
                        out.put(t.group(1), t.group(2));
                    }
                }
            }
        }
        assertThat(out).as("화면을 하나도 못 읽으면 가드가 무의미").hasSizeGreaterThan(100).containsKey("prd-wo");
        return out;
    }

    private static String stripComments(String sql) {
        return sql.replaceAll("--[^\\n]*", "");
    }

    private static Properties load(String file) throws IOException {
        Properties p = new Properties();
        try (Reader r = Files.newBufferedReader(RES.resolve(file), StandardCharsets.UTF_8)) {
            p.load(r);
        }
        return p;
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
